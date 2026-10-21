package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Native repository+real Room tests; transport fixture is not claimed as hosted/device E2E evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnerConnectionTest {
    private lateinit var context: Context
    private lateinit var db: OwnerConnectionDb
    private lateinit var campaign: CampaignLedgerDb
    private lateinit var vault: OwnerSyncCredentialVault
    private val origin = "https://owner-bridge.example"
    private val time = 1_900_000_000_000L
    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, OwnerConnectionDb::class.java).build()
        campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        vault = OwnerSyncCredentialVault(context, { time }) { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        vault.forgetAll()
    }
    @After fun close() { db.close(); campaign.close() }
    private fun client(remote: Remote, afterCommit: suspend () -> Unit = {}) = OwnerConnectionClient(context, db, campaign, vault, remote, { time }, afterCommit)
    private suspend fun paired(remote: Remote): OwnerConnectionClient {
        val client = client(remote)
        client.beginPairing(origin, OwnerConnectionProtocol.CAMPAIGN, "Fictional S26")
        client.finishPairing()
        client.createFictionalFixture()
        return client
    }
    private suspend fun current() = campaign.recordsDao().characterAbility(OwnerConnectionProtocol.PROFILE, "INT")!!
    @Test fun network_retry_job_has_required_android14_permissions_and_schedules() {
        val permissions = context.packageManager
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            permissions.checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, context.packageName))
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            permissions.checkPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED, context.packageName))
        OwnerConnectionSyncJob.schedule(context)
        val job = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.single {
            it.service.className == OwnerConnectionSyncJob::class.java.name
        }
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(job.isPersisted)
        OwnerConnectionSyncJob.cancel(context)
    }
    @Test fun proposal_does_not_mutate_then_approval_commits_once_and_independently_reads_back() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        val proposal = remote.propose(20)
        client.refresh(); assertEquals(16, current().score); assertEquals(1, current().revision)
        client.decide(proposal, true); assertEquals(20, current().score); assertEquals(2, current().revision)
        client.decide(proposal, true); client.sync(); assertEquals(2, current().revision)
        assertEquals("VERIFIED", db.connection().proposal(proposal)!!.state)
        assertTrue(remote.characterReads > 0); assertTrue(remote.receiptReads > 0)
        val audit = campaign.recordsDao().allAudit().filter { it.recordType == "character-ability" }
        assertEquals(2, audit.size)
    }
    @Test fun rejection_is_durable_and_never_changes_local_or_remote_score() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(22)
        client.sync(); client.decide(id, false); client.decide(id, false)
        assertEquals(16, current().score); assertEquals(1, current().revision)
        assertEquals("REJECTED", db.connection().proposal(id)!!.state)
        assertEquals(16, remote.score); assertEquals(1, remote.revision)
    }
    @Test fun offline_approved_decision_is_retried_with_same_local_receipt_and_revision() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20)
        client.sync(); remote.offline = true; client.decide(id, true)
        assertEquals(20, current().score); assertEquals(2, current().revision)
        assertEquals("APPLIED_PENDING", db.connection().proposal(id)!!.state)
        val body = db.connection().proposal(id)!!.decisionBody
        remote.offline = false; client(remote).sync()
        assertEquals(body, remote.lastDecision); assertEquals(2, current().revision)
        assertEquals("VERIFIED", db.connection().proposal(id)!!.state)
    }
    @Test fun lost_backend_response_replays_original_decision_exactly_once() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20)
        client.sync(); remote.loseDecisionResponse = true; client.decide(id, true)
        assertEquals(2, remote.revision); assertEquals("APPLIED_PENDING", db.connection().proposal(id)!!.state)
        client.sync(); assertEquals(2, remote.revision); assertEquals(2, current().revision)
        assertEquals("VERIFIED", db.connection().proposal(id)!!.state)
    }
    @Test fun crash_after_atomic_campaign_commit_recovers_journal_without_reapplying() = runBlocking {
        val remote = Remote(); val normal = paired(remote); val id = remote.propose(20); normal.sync()
        var crash = true
        val interrupted = client(remote) { if (crash) { crash = false; throw IOException("Injected process boundary") } }
        assertTrue(runCatching { interrupted.decide(id, true) }.isFailure)
        assertEquals(2, current().revision); assertEquals("APPROVING", db.connection().proposal(id)!!.state)
        client(remote).sync()
        assertEquals(2, current().revision); assertEquals(2, remote.revision); assertEquals("VERIFIED", db.connection().proposal(id)!!.state)
    }
    @Test fun concurrent_local_change_blocks_approval_and_preserves_both_records() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        CampaignRecordsRepository(campaign).saveAbility(current().copy(score = 18), 1, "Player fictional local edit")
        client.decide(id, true)
        assertEquals(18, current().score); assertEquals(2, current().revision); assertEquals(16, remote.score)
        assertEquals("CONFLICT", db.connection().proposal(id)!!.state)
    }
    @Test fun invalid_credentials_or_revoked_access_do_not_mutate_and_revocation_is_server_confirmed() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.denied = true
        assertTrue(runCatching { client.sync() }.isFailure); assertEquals(16, current().score)
        remote.denied = false; client.revoke()
        assertNull(vault.credential(origin, OwnerConnectionProtocol.CAMPAIGN)); assertTrue(remote.revoked)
        assertFalse(client.snapshot().getValue("connected").jsonPrimitive.boolean)
        assertTrue(runCatching { client.decide(id, true) }.isFailure); assertEquals(16, current().score)
    }
    @Test fun forged_proposal_fingerprint_and_live_campaign_are_rejected_before_commit() = runBlocking {
        val remote = Remote(); val client = paired(remote); remote.propose(20); remote.forge = true
        assertTrue(runCatching { client.sync() }.isFailure); assertEquals(16, current().score)
        assertTrue(runCatching { client.beginPairing(origin, "zerg-campaign", "Device") }.isFailure)
        assertTrue(runCatching { client.beginPairing(PublicSandbox.DEFAULT_ORIGIN, OwnerConnectionProtocol.CAMPAIGN, "Device") }.isFailure)
    }
    @Test fun same_command_with_changed_content_is_rejected_after_initial_inbox() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.propose(22, id)
        assertTrue(runCatching { client.sync() }.isFailure); assertEquals(16, current().score)
        assertEquals(20, db.connection().proposal(id)!!.proposedScore)
    }
    @Test fun encrypted_service_access_is_separate_from_device_and_never_in_snapshot() = runBlocking {
        val remote = Remote(); val client = paired(remote); val secret = "private-service-" + "x".repeat(50)
        client.acceptServiceAccess(origin, OwnerConnectionProtocol.CAMPAIGN, secret)
        client.sync(); assertEquals(secret, remote.serviceAccess)
        assertFalse(client.snapshot().toString().contains(secret))
        assertNotEquals(secret, vault.credential(origin, OwnerConnectionProtocol.CAMPAIGN)!!.bearer)
    }
    @Test fun native_reviewed_fixture_edit_saves_local_receipt_without_uploading_or_replacing_concurrent_revision() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        client.saveFictionalAbility(18, 1)
        assertEquals(18, current().score); assertEquals(2L, current().revision); assertEquals(16, remote.score)
        assertEquals("Player-edited fictional owner connection ability", current().source)
        assertEquals(2, campaign.recordsDao().allAudit().count { it.recordType == "character-ability" })
        assertTrue(runCatching { client.saveFictionalAbility(20, 1) }.isFailure)
        assertTrue(runCatching { client.saveFictionalAbility(31, 2) }.isFailure)
        client.sync(); assertEquals(16, remote.score)
    }
    @Test fun reviewed_local_edit_upload_is_explicit_and_survives_offline_retry() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        CampaignRecordsRepository(campaign).saveAbility(current().copy(score = 18), 1, "Player fictional local edit")
        client.sync(); assertEquals(16, remote.score) // Normal sync never uploads unreviewed local changes.
        assertTrue(runCatching { client.publishFictionalAbility(1) }.isFailure)
        remote.offline = true; client.publishFictionalAbility(2)
        assertNotNull(db.connection().settings()!!.publishBody)
        remote.offline = false; client(remote).sync()
        assertEquals(18, remote.score); assertEquals(2L, remote.revision); assertEquals(2L, current().revision)
        assertNull(db.connection().settings()!!.publishBody)
    }
    @Test fun persisted_room_stores_reopen_with_approved_value_and_retry_journal_intact() = runBlocking {
        db.close(); campaign.close()
        db = Room.databaseBuilder(context, OwnerConnectionDb::class.java, "owner-connection-restart-test").build()
        campaign = Room.databaseBuilder(context, CampaignLedgerDb::class.java, "owner-campaign-restart-test").build()
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.offline = true; client.decide(id, true); db.close(); campaign.close()
        db = Room.databaseBuilder(context, OwnerConnectionDb::class.java, "owner-connection-restart-test").build()
        campaign = Room.databaseBuilder(context, CampaignLedgerDb::class.java, "owner-campaign-restart-test").build()
        assertEquals(20, current().score); assertEquals(2L, current().revision)
        remote.offline = false; client(remote).sync(); assertEquals("VERIFIED", db.connection().proposal(id)!!.state)
        assertEquals(2L, current().revision)
    }
    @Test fun campaign_backup_restore_keeps_original_approval_receipt_and_cannot_reapply() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.offline = true; client.decide(id, true)
        val original = current(); val receiptId = OwnerConnectionProtocol.localCommand(origin, OwnerConnectionProtocol.CAMPAIGN, id)
        val receipt = campaign.recordsDao().receipt(receiptId)
        val backup = campaignJson.encodeToString(CampaignBackup(campaign).snapshot())
        db.close(); campaign.close()
        db = Room.inMemoryDatabaseBuilder(context, OwnerConnectionDb::class.java).build()
        campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val restore = CampaignBackup(campaign); val preview = restore.preview(backup)
        assertTrue(preview.conflicts.joinToString(), preview.valid); restore.restore(preview, UUID.randomUUID().toString())
        assertEquals(original, current()); assertEquals(receipt, campaign.recordsDao().receipt(receiptId))
        remote.offline = false; vault.forget(origin, OwnerConnectionProtocol.CAMPAIGN)
        val restoredClient = client(remote); restoredClient.beginPairing(origin, OwnerConnectionProtocol.CAMPAIGN, "Restored fictional device"); restoredClient.finishPairing()
        restoredClient.sync(); restoredClient.sync()
        assertEquals(original, current()); assertEquals(2L, remote.revision)
        assertEquals("VERIFIED", db.connection().proposal(id)!!.state)
    }
    @Test fun competing_immutable_decision_is_quarantined_without_rollback_and_does_not_block_new_reads() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.conflictingDecision = true; client.decide(id, true)
        val blocked = db.connection().proposal(id)!!
        assertEquals("DECISION_CONFLICT", blocked.state); assertNotNull(blocked.receiptJson)
        val exactAttempt = blocked.decisionBody; assertNotNull(exactAttempt)
        val localId = OwnerConnectionProtocol.localCommand(origin, OwnerConnectionProtocol.CAMPAIGN, id)
        val localReceipt = campaign.recordsDao().receipt(localId); assertNotNull(localReceipt)
        assertEquals(20, current().score); assertEquals(2L, current().revision); assertEquals(16, remote.score)
        val next = remote.propose(22); client.sync()
        assertEquals(1, remote.decisionRequests); assertNotNull(db.connection().proposal(next))
        assertEquals(exactAttempt, db.connection().proposal(id)!!.decisionBody)
        assertEquals(localReceipt, campaign.recordsDao().receipt(localId))
        val snapshot = client.snapshot()
        assertTrue(snapshot.getValue("pending").jsonArray.none { it.jsonObject.getValue("commandId").jsonPrimitive.content == id })
        assertFalse(snapshot.getValue("fixture").jsonObject.getValue("synchronized").jsonPrimitive.boolean)
        assertEquals(20, current().score)
    }
    @Test fun quarantined_decision_with_temporarily_missing_receipt_retries_only_reads_and_remains_blocked() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.receiptsUnavailable = true; remote.conflictingDecision = true; client.decide(id, true)
        val blocked = db.connection().proposal(id)!!
        assertEquals("DECISION_CONFLICT", blocked.state); assertNull(blocked.receiptJson)
        assertTrue(runCatching { client.decide(id, false) }.isFailure)
        val next = remote.propose(22); client.sync()
        assertNotNull(db.connection().proposal(next)); assertEquals(1, remote.decisionRequests)
        assertEquals(blocked.decisionBody, db.connection().proposal(id)!!.decisionBody)
        remote.receiptsUnavailable = false; client.sync()
        val verified = db.connection().proposal(id)!!
        assertEquals("DECISION_CONFLICT", verified.state); assertNotNull(verified.receiptJson)
        assertEquals(blocked.decisionBody, verified.decisionBody); assertEquals(1, remote.decisionRequests)
        assertEquals(20, current().score); assertEquals(2L, current().revision)
    }
    @Test fun encrypted_service_origin_switch_quarantines_old_outbox_and_resets_scope_metadata() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        client.saveFictionalAbility(18, 1); remote.offline = true; client.publishFictionalAbility(2)
        val original = db.connection().settings()!!; val attempted = requireNotNull(original.publishBody)
        val command = Json.parseToJsonElement(attempted).jsonObject.getValue("commandId").jsonPrimitive.content
        client.forgetLocal()
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bootstrap = OwnerConnectionBootstrap(context, { time }, { pair })
        val importer = OwnerConnectionClient(context, db, campaign, vault, remote, { time }, {}, bootstrap)
        val nextOrigin = "https://other-owner-service.example"
        val url = bootstrap.begin(nextOrigin, OwnerConnectionProtocol.CAMPAIGN)
        val nonce = java.net.URI(url).rawFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }.getValue("nonce")
        val expiry = time + 60_000
        val header = buildJsonObject { put("v", 1); put("algorithm", "RSA-OAEP-256+A256GCM"); put("origin", nextOrigin)
            put("campaignId", OwnerConnectionProtocol.CAMPAIGN); put("nonce", nonce); put("expiresAt", expiry) }
        val bearer = "fictional-new-service-" + "x".repeat(64)
        val rawKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            updateAAD("ASTRAVAR_SERVICE_BOOTSTRAP_V1\n$nextOrigin\n${OwnerConnectionProtocol.CAMPAIGN}\n$nonce\n$expiry".toByteArray())
        }.doFinal(JsonObject(header + ("serviceAccessBearer" to JsonPrimitive(bearer))).toString().toByteArray())
        val wrapped = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, pair.public, javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT))
        }.doFinal(rawKey)
        fun encoded(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val bundle = JsonObject(header + mapOf("iv" to JsonPrimitive(encoded(iv)), "wrappedKey" to JsonPrimitive(encoded(wrapped)), "ciphertext" to JsonPrimitive(encoded(encrypted))))
        importer.importServiceAuthorization(bundle.toString().toByteArray())
        val changed = db.connection().settings()!!
        assertEquals(nextOrigin, changed.origin); assertNull(changed.publishBody); assertFalse(changed.published)
        assertEquals(0L, changed.remoteRevision); assertNull(changed.remoteFieldsJson); assertEquals("", changed.deviceId)
        assertEquals("", changed.deviceCode); assertEquals("", changed.accountLabel); assertEquals("[]", changed.scopesJson)
        val quarantined = db.connection().publication(command)!!
        assertEquals(origin, quarantined.origin); assertEquals(attempted, quarantined.body); assertEquals("REPAIR_REVIEW", quarantined.state)
        val retainedHistory = importer.snapshot().getValue("history").jsonArray.map { it.jsonObject }.single { it.getValue("commandId").jsonPrimitive.content == command }
        assertEquals("REPAIR_REVIEW", retainedHistory.getValue("state").jsonPrimitive.content); assertEquals(origin, retainedHistory.getValue("origin").jsonPrimitive.content)
        assertNull(vault.credential(origin, OwnerConnectionProtocol.CAMPAIGN)); assertEquals(bearer, vault.serviceAccess(nextOrigin, OwnerConnectionProtocol.CAMPAIGN))
        assertEquals(18, current().score); assertEquals(2L, current().revision); assertEquals(16, remote.score)
    }
    @Test fun re_pairing_quarantines_original_publication_and_requires_explicit_review_before_new_upload() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        client.saveFictionalAbility(18, 1); remote.offline = true; client.publishFictionalAbility(2)
        val attempted = db.connection().settings()!!.publishBody; assertNotNull(attempted)
        val id = Json.parseToJsonElement(attempted!!).jsonObject.getValue("commandId").jsonPrimitive.content
        client.forgetLocal(); remote.offline = false
        client.beginPairing(origin, OwnerConnectionProtocol.CAMPAIGN, "Re-paired fictional S26")
        assertNull(db.connection().settings()!!.publishBody)
        val quarantined = db.connection().publication(id)!!
        assertEquals("REPAIR_REVIEW", quarantined.state); assertEquals(attempted, quarantined.body)
        client.finishPairing(); client.sync()
        assertEquals(16, remote.score); assertEquals(18, current().score); assertEquals(quarantined, db.connection().publication(id))
        client.publishFictionalAbility(2)
        assertEquals(18, remote.score); assertEquals(2L, remote.revision)
        assertEquals(quarantined, db.connection().publication(id)); assertEquals(18, current().score)
    }
    @Test fun conflicting_publication_is_quarantined_with_exact_body_and_does_not_block_pending_reads() = runBlocking {
        val remote = Remote(); val client = paired(remote); val pending = remote.propose(20)
        client.saveFictionalAbility(18, 1); remote.conflictNextPublication = true
        client.publishFictionalAbility(2)
        assertNull(db.connection().settings()!!.publishBody)
        val blocked = db.connection().publications().single { it.state == "CONFLICT" }
        val original = Json.parseToJsonElement(blocked.body).jsonObject
        assertEquals(1L, original.getValue("expectedRevision").jsonPrimitive.long)
        assertEquals(18, original.getValue("ability").jsonObject.getValue("fields").jsonObject.getValue("score").jsonPrimitive.int)
        assertNotNull(db.connection().proposal(pending)); assertEquals(18, current().score); assertEquals(2L, current().revision)
        client.sync()
        assertEquals(blocked, db.connection().publication(blocked.commandId)); assertEquals(22, remote.score)
        assertEquals(2L, client.snapshot().getValue("fixture").jsonObject.getValue("remoteRevision").jsonPrimitive.long)
    }
    @Test fun later_remote_commit_after_approval_preserves_original_receipt_without_infinite_retry_or_local_overwrite() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(20); client.sync()
        remote.advanceAfterDecision = true; client.decide(id, true)
        assertEquals(20, current().score); assertEquals(2L, current().revision); assertEquals(3L, remote.revision)
        val proposal = db.connection().proposal(id)!!
        assertEquals("REMOTE_NEWER", proposal.state); assertNotNull(proposal.receiptJson)
        client.sync(); assertEquals("REMOTE_NEWER", db.connection().proposal(id)!!.state)
        assertFalse(client.snapshot().getValue("fixture").jsonObject.getValue("synchronized").jsonPrimitive.boolean)
        assertEquals(20, current().score)
    }
    @Test fun full_proposal_baseline_mismatch_is_conflict_even_when_revision_matches() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        remote.differentBaseline(18); val id = remote.propose(20); client.sync()
        assertEquals("CONFLICT", db.connection().proposal(id)!!.state)
        assertTrue(runCatching { client.decide(id, true) }.isFailure)
        assertEquals(16, current().score); assertEquals(1L, current().revision)
    }
    @Test fun provenance_source_limit_preserves_long_accepted_source_and_rejects_overflow_before_commit() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        val source = "a".repeat(1900); val id = remote.propose(20, source = source); client.sync(); client.decide(id, true)
        assertEquals("Player-approved owner proposal $id · $source", current().source)
        remote.propose(22, source = "b".repeat(1901))
        assertTrue(runCatching { client.sync() }.isFailure); assertEquals(2L, current().revision)
    }
    private inner class Remote : OwnerConnectionTransport {
        var score = 16; var revision = 1L; var offline = false; var denied = false; var revoked = false
        var loseDecisionResponse = false; var advanceAfterDecision = false; var conflictNextPublication = false
        var conflictingDecision = false; var receiptsUnavailable = false; var decisionRequests = 0; var forge = false; var characterReads = 0; var receiptReads = 0
        var lastDecision: String? = null; var serviceAccess: String? = null
        private var deviceHash = ""
        private var mirror: JsonObject? = null
        private val proposals = linkedMapOf<String, JsonObject>(); private val receipts = linkedMapOf<String, JsonObject>()
        fun differentBaseline(value: Int) {
            val m = requireNotNull(mirror); val fields = m.getValue("fields").jsonObject
            mirror = JsonObject(m + ("fields" to JsonObject(fields + ("score" to JsonPrimitive(value)))))
            score = value
        }
        fun propose(value: Int, id: String = UUID.randomUUID().toString(), source: String = "ChatGPT fictional test"): String {
            val data = buildJsonObject { put("commandId", id); put("recordId", OwnerConnectionProtocol.RECORD); put("expectedRevision", revision)
                put("fields", buildJsonObject { put("score", value) }); put("source", source)
                put("currentFields", requireNotNull(mirror).getValue("fields")); put("status", "PENDING") }
            proposals[id] = JsonObject(data + ("fingerprint" to JsonPrimitive(OwnerConnectionProtocol.proposalFingerprint(OwnerConnectionProtocol.CAMPAIGN, data))))
            return id
        }
        private fun envelope(vararg pairs: Pair<String, JsonElement>) = buildJsonObject {
            put("mode", OwnerConnectionProtocol.MODE); put("campaignId", OwnerConnectionProtocol.CAMPAIGN); pairs.forEach { (k,v) -> put(k,v) }
        }
        override suspend fun request(origin: String, path: String, credential: OwnerSyncCredential?, serviceAccess: String?, body: String?): OwnerSyncResponse {
            this.serviceAccess = serviceAccess
            if (offline) throw IOException("Fictional network offline")
            if (denied || revoked) return OwnerSyncResponse(401, buildJsonObject { put("error", buildJsonObject { put("code", "CREDENTIAL_REVOKED_OR_EXPIRED") }) })
            val data = body?.let { Json.parseToJsonElement(it).jsonObject }
            val base = "/v1/campaigns/${OwnerConnectionProtocol.CAMPAIGN}"
            val result = when (path) {
                "/v1/pairings" -> { deviceHash = data!!.getValue("deviceSecretHash").jsonPrimitive.content
                    envelope("deviceCode" to JsonPrimitive("pairing-code"), "userCode" to JsonPrimitive("ABCD-EFGH"), "expiresAt" to JsonPrimitive(time + 60_000)) }
                "/v1/pairings/token" -> {
                    assertEquals(deviceHash, OwnerSyncProtocol.hash(data!!.getValue("deviceSecret").jsonPrimitive.content))
                    envelope("status" to JsonPrimitive("PAIRED"), "credentialId" to JsonPrimitive("fictional-device"), "ownerLabel" to JsonPrimitive("Fictional Owner"),
                        "expiresAt" to JsonPrimitive(time + 86_400_000), "scopes" to JsonArray(listOf("character:read", "character:publish", "proposal:decide").map(::JsonPrimitive))) }
                "$base/characters" -> { require(credential != null)
                    if (conflictNextPublication) {
                        conflictNextPublication = false; revision++; differentBaseline(22)
                        mirror = JsonObject(requireNotNull(mirror) + ("revision" to JsonPrimitive(revision)))
                        return OwnerSyncResponse(409, envelope("error" to buildJsonObject { put("code", "REVISION_CONFLICT") }))
                    }
                    mirror = data!!.getValue("ability").jsonObject
                    score = mirror!!.getValue("fields").jsonObject.getValue("score").jsonPrimitive.int
                    revision = mirror!!.getValue("revision").jsonPrimitive.long
                    val id = data.getValue("commandId").jsonPrimitive.content
                    val receipt = receipts[id] ?: buildJsonObject {
                        put("commandId", id); put("recordId", OwnerConnectionProtocol.RECORD); put("status", "PUBLISHED")
                        put("priorRevision", data.getValue("expectedRevision")); put("resultingRevision", revision)
                        put("fingerprint", OwnerSyncProtocol.hash(requireNotNull(body))); put("localReceiptId", id)
                        put("recordedAt", time); put("eventId", UUID.randomUUID().toString())
                    }.also { receipts[id] = it }
                    envelope("receipt" to receipt) }
                "$base/pending" -> { require(credential != null); envelope("proposals" to JsonArray(proposals.values.filter { it.getValue("commandId").jsonPrimitive.content !in receipts }.map {
                    if (forge) JsonObject(it + ("fingerprint" to JsonPrimitive("0".repeat(64)))) else it })) }
                "$base/decisions" -> {
                    require(credential != null); decisionRequests++; lastDecision = body
                    if (conflictingDecision) {
                        conflictingDecision = false
                        val id = data!!.getValue("commandId").jsonPrimitive.content; val p = proposals.getValue(id)
                        receipts[id] = buildJsonObject {
                            put("commandId", id); put("recordId", OwnerConnectionProtocol.RECORD); put("status", "REJECTED")
                            put("priorRevision", revision); put("resultingRevision", revision); put("fingerprint", p.getValue("fingerprint"))
                            put("localReceiptId", JsonNull); put("localRecordFingerprint", JsonNull); put("recordedAt", time); put("eventId", UUID.randomUUID().toString())
                        }
                        return OwnerSyncResponse(409, envelope("error" to buildJsonObject { put("code", "COMMAND_ID_REUSED") }))
                    }
                    val id = data!!.getValue("commandId").jsonPrimitive.content
                    var receipt = receipts[id]
                    if (receipt == null) {
                        val p = proposals.getValue(id); val approved = data.getValue("approved").jsonPrimitive.boolean
                        val prior = revision
                        if (approved) { score = p.getValue("fields").jsonObject.getValue("score").jsonPrimitive.int; revision++
                            mirror = JsonObject(requireNotNull(mirror) + mapOf("fields" to data.getValue("localFields"), "revision" to JsonPrimitive(revision))) }
                        receipt = buildJsonObject { put("commandId", id); put("recordId", OwnerConnectionProtocol.RECORD); put("status", if (approved) "APPLIED" else "REJECTED")
                            put("priorRevision", prior); put("resultingRevision", revision); put("fingerprint", p.getValue("fingerprint")); put("localReceiptId", data.getValue("localReceiptId"))
                            put("recordedAt", time); put("eventId", UUID.randomUUID().toString())
                            put("localRecordFingerprint", if (approved) JsonPrimitive(OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(data.getValue("localFields")))) else JsonNull) }
                        receipts[id] = receipt
                    }
                    if (advanceAfterDecision) {
                        advanceAfterDecision = false; revision++; differentBaseline(22)
                        mirror = JsonObject(requireNotNull(mirror) + ("revision" to JsonPrimitive(revision)))
                    }
                    if (loseDecisionResponse) { loseDecisionResponse = false; throw IOException("Response lost after server commit") }
                    envelope("receipt" to receipt)
                }
                "$base/characters/${OwnerConnectionProtocol.PROFILE}" -> { characterReads++
                    envelope("abilities" to buildJsonArray { add(requireNotNull(mirror)) }) }
                "$base/devices/revoke" -> { revoked = true; envelope("status" to JsonPrimitive("REVOKED"), "revoked" to JsonPrimitive(true), "credentialId" to JsonPrimitive("fictional-device")) }
                else -> if (path.startsWith("$base/receipts/")) {
                    receiptReads++
                    if (receiptsUnavailable) return OwnerSyncResponse(404, envelope("error" to buildJsonObject { put("code", "RECEIPT_NOT_READY") }))
                    envelope("receipt" to receipts.getValue(path.substringAfterLast('/'))) } else error("Unexpected path $path")
            }
            return OwnerSyncResponse(200, result)
        }
    }
}
