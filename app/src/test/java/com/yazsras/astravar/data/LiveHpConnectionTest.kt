package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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

/** Synthetic records exercise native Room and approval transactions; no campaign exports or handset data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveHpConnectionTest {
    private lateinit var context: Context
    private lateinit var db: LiveHpConnectionDb
    private lateinit var campaign: CampaignLedgerDb
    private lateinit var vault: OwnerSyncCredentialVault
    private val origin = "https://owner-bridge.example"
    private val profileId = "test-existing-character"
    private val campaignId get() = "astravar-live-${OwnerSyncProtocol.hash(profileId).take(32)}"
    private var time = 1_900_000_000_000L
    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiveHpConnectionDb::class.java).build()
        campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        vault = OwnerSyncCredentialVault(context, { time }) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }; vault.forgetAll()
    }
    @After fun close() { db.close(); campaign.close() }
    private fun client(remote: Remote, afterCommit: suspend () -> Unit = {}) = LiveHpConnectionClient(context, db, campaign, vault, remote, { time }, afterCommit)
    private suspend fun current() = requireNotNull(campaign.recordsDao().characterProfile(profileId))
    private suspend fun seed(hp: Int? = 17, max: Int? = 29) {
        CampaignRecordsRepository(campaign).saveProfile(CharacterProfile(id = profileId, name = "Test traveller", currentHp = hp, maximumHp = max, temporaryHp = 4, armorClass = 19, level = 9), source = "Synthetic test setup")
    }
    private suspend fun paired(remote: Remote, seeded: Boolean = false): LiveHpConnectionClient {
        if (!seeded) seed()
        val client = client(remote); client.beginPairing(origin, profileId, "Test device"); client.finishPairing(); return client
    }
    @Test fun first_pairing_publishes_existing_revision_and_only_narrow_profile_fields_without_local_write() = runBlocking {
        seed(); val records = CampaignRecordsRepository(campaign); records.saveProfile(current().copy(name = "Edited test traveller"), 1, "Test edit before connection")
        val original = current(); val remote = Remote(); val client = paired(remote, true)
        assertEquals(original, current()); assertEquals(2L, remote.revision); assertEquals(LiveHpConnectionProtocol.fields(original), remote.fields)
        assertFalse(remote.lastPublish!!.contains("armorClass")); assertFalse(remote.lastPublish!!.contains("level")); assertFalse(remote.lastPublish!!.contains("inventory"))
        assertEquals(2, campaign.recordsDao().allAudit().size); assertTrue(client.snapshot().getValue("current").jsonObject.getValue("synchronized").jsonPrimitive.boolean)
    }
    @Test fun authenticated_proposal_waits_then_native_approval_changes_only_hp_once_and_reads_receipt_independently() = runBlocking {
        val remote = Remote(); val client = paired(remote); val original = current(); val id = remote.propose(-3)
        client.sync(); assertEquals(original, current()); client.decide(id, true); client.decide(id, true); client.sync()
        val saved = current(); assertEquals(14, saved.currentHp); assertEquals(2L, saved.revision)
        assertEquals(original.copy(currentHp = 14, revision = saved.revision, source = saved.source, updatedAt = saved.updatedAt), saved)
        assertEquals("VERIFIED", db.connection().command(id)!!.state); assertEquals(2L, remote.revision); assertEquals(14, remote.fields!!.getValue("currentHp").jsonPrimitive.int)
        assertTrue(remote.receiptReads >= 2 && remote.recordReads >= 2); assertEquals(2, campaign.recordsDao().allAudit().size)
    }
    @Test fun rejection_never_changes_hp_and_stale_rejection_receipt_accepts_current_remote_revision() = runBlocking {
        val remote = Remote(); val client = paired(remote); val original = current(); val id = remote.propose(-2); client.sync()
        remote.advance(16); client.decide(id, false)
        assertEquals(original, current()); assertEquals("REJECTED", db.connection().command(id)!!.state); assertEquals(2L, remote.revision)
    }
    @Test fun unknown_hp_and_maximum_are_published_as_null_and_never_initialized_from_proposals() = runBlocking {
        seed(null, null); val remote = Remote(); val client = paired(remote, true)
        assertEquals(JsonNull, remote.fields!!.getValue("currentHp")); assertEquals(JsonNull, remote.fields!!.getValue("maximumHp"))
        remote.propose(-1, forcedHp = 3)
        assertTrue(runCatching { client.sync() }.isFailure); assertNull(current().currentHp); assertNull(current().maximumHp); assertEquals(1L, current().revision)
    }
    @Test fun concurrent_local_revision_prevents_proposal_application_and_preserves_local_change() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync()
        CampaignRecordsRepository(campaign).saveProfile(current().copy(armorClass = 20), 1, "Test local edit")
        client.decide(id, true); assertEquals(17, current().currentHp); assertEquals(20, current().armorClass); assertEquals(2L, current().revision)
        assertEquals("CONFLICT", db.connection().command(id)!!.state); assertEquals(1L, remote.revision)
    }
    @Test fun full_profile_baseline_guard_catches_equal_revision_content_change_outside_wire_fields() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync()
        campaign.recordsDao().put(current().copy(level = 8))
        client.decide(id, true); assertEquals(17, current().currentHp); assertEquals(8, current().level); assertEquals(1L, current().revision)
        assertEquals("CONFLICT", db.connection().command(id)!!.state)
    }
    @Test fun automatic_local_changes_publish_monotonic_skipped_revisions_without_extra_local_writes() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        CampaignRecordsRepository(campaign).saveProfile(current().copy(currentHp = 16), 1, "Local test edit")
        CampaignRecordsRepository(campaign).saveProfile(current().copy(currentHp = 15), 2, "Second local test edit")
        val original = current(); client.sync(); assertEquals(original, current()); assertEquals(3L, remote.revision); assertEquals(LiveHpConnectionProtocol.fields(original), remote.fields)
        assertEquals(3, campaign.recordsDao().allAudit().size)
    }
    @Test fun offline_local_publication_is_frozen_and_retries_same_id_after_process_recreation() = runBlocking {
        val remote = Remote(); val client = paired(remote)
        CampaignRecordsRepository(campaign).saveProfile(current().copy(currentHp = 16), 1, "Local test edit")
        remote.offlineOnPublish = true; assertTrue(runCatching { client.sync() }.isFailure)
        val body = requireNotNull(db.connection().settings()!!.publishBody); assertEquals(2L, current().revision)
        remote.offlineOnPublish = false; client(remote).sync(); assertEquals(body, remote.lastPublish); assertNull(db.connection().settings()!!.publishBody); assertEquals(2L, remote.revision)
    }
    @Test fun offline_approval_retries_frozen_exact_decision_after_recreation_without_duplicate_commit() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.offline = true
        client.decide(id, true); val decision = db.connection().command(id)!!.decisionBody
        assertEquals(14, current().currentHp); assertEquals("APPLIED_PENDING", db.connection().command(id)!!.state)
        remote.offline = false; client(remote).sync(); assertEquals(decision, remote.lastDecision); assertEquals(2L, current().revision); assertEquals(2L, remote.revision)
    }
    @Test fun crash_between_atomic_campaign_save_and_journal_update_recovers_original_receipt() = runBlocking {
        val remote = Remote(); val normal = paired(remote); val id = remote.propose(-3); normal.sync()
        val interrupted = client(remote) { throw IOException("Injected crash boundary") }
        assertTrue(runCatching { interrupted.decide(id, true) }.isFailure); assertEquals(2L, current().revision); assertEquals("APPROVING", db.connection().command(id)!!.state)
        client(remote).sync(); assertEquals("VERIFIED", db.connection().command(id)!!.state); assertEquals(2L, current().revision); assertEquals(2L, remote.revision)
    }
    @Test fun lost_decision_response_replays_identical_command_and_backend_commits_once() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.loseDecisionResponse = true
        client.decide(id, true); assertEquals(2L, remote.revision); val body = remote.lastDecision
        client.sync(); assertEquals(body, remote.lastDecision); assertEquals(2L, remote.revision); assertEquals(2L, current().revision); assertEquals("VERIFIED", db.connection().command(id)!!.state)
    }
    @Test fun disk_room_reopen_retains_saved_hp_and_delivery_journal() = runBlocking {
        db.close(); campaign.close(); val dbName = "hp-restart-${UUID.randomUUID()}"; val campaignName = "hp-records-${UUID.randomUUID()}"
        db = Room.databaseBuilder(context, LiveHpConnectionDb::class.java, dbName).build(); campaign = Room.databaseBuilder(context, CampaignLedgerDb::class.java, campaignName).build()
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.offline = true; client.decide(id, true)
        db.close(); campaign.close(); db = Room.databaseBuilder(context, LiveHpConnectionDb::class.java, dbName).build(); campaign = Room.databaseBuilder(context, CampaignLedgerDb::class.java, campaignName).build()
        assertEquals(14, current().currentHp); assertEquals(2L, current().revision); remote.offline = false; client(remote).sync(); assertEquals("VERIFIED", db.connection().command(id)!!.state)
    }
    @Test fun campaign_backup_restore_preserves_hp_and_original_receipt_recovers_without_reapplying() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.offline = true; client.decide(id, true)
        val original = current(); val localId = LiveHpConnectionProtocol.localCommand(origin, campaignId, id); val originalReceipt = campaign.recordsDao().receipt(localId)
        val backup = campaignJson.encodeToString(CampaignBackup(campaign).snapshot()); assertFalse(backup.contains("pairing-pending")); assertFalse(backup.contains("live_hp_settings"))
        db.close(); campaign.close(); db = Room.inMemoryDatabaseBuilder(context, LiveHpConnectionDb::class.java).build(); campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val restore = CampaignBackup(campaign); val preview = restore.preview(backup); assertTrue(preview.conflicts.joinToString(), preview.valid); restore.restore(preview, UUID.randomUUID().toString())
        assertEquals(original, current()); assertEquals(originalReceipt, campaign.recordsDao().receipt(localId))
        vault.forget(origin, campaignId); remote.offline = false; val restored = paired(remote, true); restored.sync()
        assertEquals(original, current()); assertEquals(2L, remote.revision)
        assertNull(db.connection().command(id)); assertEquals(originalReceipt, campaign.recordsDao().receipt(localId))
        assertTrue(db.connection().publications().any { it.state == "VERIFIED" })
        assertEquals(0, remote.decisionRequests)
        val oldDecision = buildJsonObject { put("commandId", id); put("approved", false); put("proposalFingerprint", remote.proposals.getValue(id).getValue("fingerprint")); put("localAppliedRevision", JsonNull); put("localReceiptId", JsonNull); put("localFields", JsonNull) }
        val denied = remote.request(origin, "/v1/hp/campaigns/$campaignId/decisions", vault.credential(origin, campaignId), null, OwnerSyncProtocol.canonical(oldDecision))
        assertEquals(409, denied.status); assertEquals(original, current())
    }
    @Test fun malformed_proposal_fingerprint_adjustment_extra_fields_and_wrong_profile_are_rejected_precommit() = runBlocking {
        val remote = Remote(); val client = paired(remote); val original = current()
        val cases = listOf<(JsonObject) -> JsonObject>(
            { JsonObject(it + ("fingerprint" to JsonPrimitive("0".repeat(64)))) },
            { JsonObject(it + ("adjustment" to JsonPrimitive(-4))) },
            { JsonObject(it + ("fields" to buildJsonObject { put("currentHp", 14); put("maximumHp", 99) })) },
            { JsonObject(it + ("recordId" to JsonPrimitive("another-character"))) },
            { JsonObject(it + ("fields" to buildJsonObject { put("currentHp", "14") })) }
        )
        for (mutate in cases) { remote.proposals.clear(); val id = remote.propose(-3); remote.proposals[id] = mutate(remote.proposals.getValue(id)); assertTrue(runCatching { client.sync() }.isFailure); assertEquals(original, current()) }
    }
    @Test fun source_boundary_is_validated_before_native_commit_and_preserved_without_truncation() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3, source = "s".repeat(1900)); client.sync(); client.decide(id, true)
        assertEquals(LiveHpConnectionProtocol.source(id, "s".repeat(1900)), current().source)
        remote.propose(-1, source = "s".repeat(1901)); assertTrue(runCatching { client.sync() }.isFailure); assertEquals(2L, current().revision)
    }
    @Test fun gateway_auth_failure_retains_device_key_but_verified_server_revocation_removes_it() = runBlocking {
        val remote = Remote(); val client = paired(remote); val key = vault.credential(origin, campaignId)
        remote.gatewayDenied = true; val error = runCatching { client.sync() }.exceptionOrNull(); assertEquals("PRIVATE_GATEWAY_AUTH_REQUIRED", (error as OwnerSyncFailure).code); assertTrue("Gateway failure must preserve the exact enrolled device credential", vault.credential(origin, campaignId)?.let { saved ->
            key != null && saved.credentialId == key.credentialId && saved.origin == key.origin && saved.campaignId == key.campaignId && saved.expiresAt == key.expiresAt && saved.bearer == key.bearer
        } == true)
        remote.gatewayDenied = false; remote.revoked = true; assertTrue(runCatching { client.sync() }.isFailure); assertNull(vault.credential(origin, campaignId)); assertEquals(17, current().currentHp)
    }
    @Test fun explicit_server_revocation_is_bound_to_device_and_response_loss_can_be_retried() = runBlocking {
        val remote = Remote(); val client = paired(remote); remote.loseRevokeResponse = true
        assertTrue(runCatching { client.revoke() }.isFailure); assertTrue(db.connection().settings()!!.revokePending); assertNotNull(vault.credential(origin, campaignId))
        client.sync(); assertNull(vault.credential(origin, campaignId)); assertFalse(db.connection().settings()!!.revokePending); assertEquals(17, current().currentHp)
    }
    @Test fun server_advanced_after_applied_ack_is_terminal_remote_newer_not_retried_forever() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.advanceAfterDecision = true; client.decide(id, true)
        assertEquals("REMOTE_NEWER", db.connection().command(id)!!.state); val requests = remote.decisionRequests; client.sync(); assertEquals(requests, remote.decisionRequests); assertEquals(14, current().currentHp); assertEquals(3L, remote.revision)
    }
    @Test fun immutable_decision_conflict_is_quarantined_with_body_and_local_receipt_and_does_not_block_reads() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.decisionConflict = true; client.decide(id, true)
        val saved = db.connection().command(id)!!; assertEquals("DECISION_CONFLICT", saved.state); assertNotNull(saved.decisionBody); assertNotNull(campaign.recordsDao().receipt(LiveHpConnectionProtocol.localCommand(origin, campaignId, id)))
        val writes = remote.decisionRequests; val reads = remote.pendingReads; client.sync(); assertEquals(writes, remote.decisionRequests); assertTrue(remote.pendingReads > reads); assertEquals(saved.decisionBody, db.connection().command(id)!!.decisionBody)
        assertEquals(14, current().currentHp); assertTrue(client.snapshot().getValue("pending").jsonArray.none { it.jsonObject.getValue("commandId").jsonPrimitive.content == id })
    }
    @Test fun stale_outbound_publication_is_quarantined_exactly_and_pending_reads_continue() = runBlocking {
        val remote = Remote(); val client = paired(remote); CampaignRecordsRepository(campaign).saveProfile(current().copy(currentHp = 16), 1, "Local test edit")
        remote.publishConflict = true; client.sync(); val blocked = db.connection().publications().single { it.state == "CONFLICT" }; val writes = remote.publishRequests
        assertNull(db.connection().settings()!!.publishBody); val reads = remote.pendingReads; client.sync(); assertEquals(writes, remote.publishRequests); assertTrue(remote.pendingReads > reads); assertEquals(16, current().currentHp); assertEquals(blocked, db.connection().publication(blocked.commandId))
    }
    @Test fun heartbeat_is_phone_authenticated_exact_snapshot_without_profile_revision_or_receipt_writes_and_get_does_not_fake_freshness() = runBlocking {
        val remote = Remote(); val client = paired(remote); val first = db.connection().settings()!!.lastDeviceSyncAt; val writes = remote.publishRequests
        time += 10_000; client.sync(); assertEquals(writes, remote.publishRequests); assertEquals(first, db.connection().settings()!!.lastDeviceSyncAt)
        time += 60_000; client.sync(); assertEquals(writes, remote.publishRequests); assertEquals(1, remote.heartbeatRequests); assertEquals(1L, current().revision); assertEquals(1L, remote.revision); assertTrue(db.connection().settings()!!.lastDeviceSyncAt > first)
    }
    @Test fun profile_selection_is_existing_only_and_does_not_upload_unselected_profile_or_fictional_fixture() = runBlocking {
        seed(); CampaignRecordsRepository(campaign).saveProfile(CharacterProfile(id = "unselected-test", name = "Private unselected test", currentHp = 5, maximumHp = 10), source = "Synthetic setup")
        val remote = Remote(); val client = paired(remote, true); assertFalse(remote.lastPublish!!.contains("Private unselected test")); assertTrue(runCatching { client.beginPairing(origin, "missing-test", "Device") }.isFailure)
        client.revoke(); CampaignRecordsRepository(campaign).saveProfile(CharacterProfile(id = OwnerConnectionProtocol.PROFILE, name = "Old fictional test"), source = "Synthetic setup")
        assertTrue(runCatching { client.beginPairing(origin, OwnerConnectionProtocol.PROFILE, "Device") }.isFailure)
    }
    @Test fun pairing_token_wrong_profile_or_excess_scope_never_grants_live_connection() = runBlocking {
        seed(); val remote = Remote(); val client = client(remote); client.beginPairing(origin, profileId, "Test device"); remote.wrongPairedProfile = true
        assertTrue(runCatching { client.finishPairing() }.isFailure); assertEquals(0L, remote.revision); assertEquals("pairing-pending", vault.credential(origin, campaignId)!!.credentialId)
    }
    @Test fun private_transport_credential_never_enters_snapshot_or_campaign_backup_and_only_same_origin_is_reused() = runBlocking {
        seed(); val remote = Remote(); val secret = "private-service-" + "x".repeat(50); vault.storeServiceAccess(origin, OwnerConnectionProtocol.CAMPAIGN, secret)
        val client = paired(remote, true); assertEquals(secret, remote.serviceAccess); assertFalse(client.snapshot().toString().contains(secret)); assertFalse(campaignJson.encodeToString(CampaignBackup(campaign).snapshot()).contains(secret))
    }
    @Test fun explicit_repair_quarantines_old_approved_intent_and_does_not_replay_with_new_credentials() = runBlocking {
        val remote = Remote(); val client = paired(remote); val id = remote.propose(-3); client.sync(); remote.offline = true; client.decide(id, true)
        val body = db.connection().command(id)!!.decisionBody; val localReceipt = campaign.recordsDao().receipt(LiveHpConnectionProtocol.localCommand(origin, campaignId, id))
        vault.forget(origin, campaignId); remote.offline = false; val requests = remote.decisionRequests
        client.beginPairing(origin, profileId, "Replacement test device"); client.finishPairing(); client.sync()
        assertEquals(requests, remote.decisionRequests); assertEquals(body, db.connection().command(id)!!.decisionBody)
        assertEquals("SUPERSEDED_BY_REPAIR", db.connection().command(id)!!.state); assertEquals(localReceipt, campaign.recordsDao().receipt(localReceipt!!.commandId)); assertEquals(2L, current().revision)
    }
    @Test fun foreground_failure_persists_offline_and_gateway_attention_without_discarding_device_grant() = runBlocking {
        val remote = Remote(); val client = paired(remote); remote.offline = true; assertTrue(runCatching { client.foreground() }.isFailure)
        assertTrue(db.connection().settings()!!.status.contains("Offline")); remote.offline = false; remote.gatewayDenied = true
        assertTrue(runCatching { client.foreground() }.isFailure); assertTrue(db.connection().settings()!!.status.contains("renewal")); assertNotNull(vault.credential(origin, campaignId))
    }
    @Test fun campaign_identity_reuses_safe_existing_party_scope_or_stable_namespace_without_creating_campaign() = runBlocking {
        seed(); campaign.partyDao().put(PartySheetRecord(profileId, "{}", campaignId = "safe-test-campaign"))
        val client = client(Remote()); assertEquals("safe-test-campaign", client.snapshot().getValue("profiles").jsonArray.single().jsonObject.getValue("campaignId").jsonPrimitive.content)
        campaign.partyDao().put(PartySheetRecord(profileId, "{}", campaignId = "unsafe campaign scope"))
        assertEquals(campaignId, client.snapshot().getValue("profiles").jsonArray.single().jsonObject.getValue("campaignId").jsonPrimitive.content)
        assertEquals(1, campaign.partyDao().allSheets().size); assertTrue(campaign.partyDao().allCheckpoints().isEmpty())
    }
    @Test fun recovery_of_old_lost_ack_does_not_freshen_until_a_new_authenticated_heartbeat() = runBlocking {
        val remote = Remote(); val client = paired(remote); val originalSync = remote.lastDeviceSyncAt
        val id = remote.propose(-3); client.sync(); remote.loseDecisionResponse = true; client.decide(id, true)
        assertEquals("APPLIED_PENDING", db.connection().command(id)!!.state); assertEquals(originalSync, remote.lastDeviceSyncAt)
        time += 6 * 60_000; client.sync()
        assertEquals(1, remote.heartbeatRequests); assertEquals(time, remote.lastDeviceSyncAt); assertEquals(time, db.connection().settings()!!.lastDeviceSyncAt)
        assertEquals("VERIFIED", db.connection().command(id)!!.state); assertEquals(2L, current().revision); assertEquals(14, current().currentHp); assertEquals(1, remote.publishRequests)
    }
    private inner class Remote : OwnerConnectionTransport {
        var fields: JsonObject? = null; var revision = 0L; var lastDeviceSyncAt = 0L; var recordReads = 0; var receiptReads = 0; var pendingReads = 0; var publishRequests = 0; var decisionRequests = 0; var heartbeatRequests = 0
        var offline = false; var offlineOnPublish = false; var gatewayDenied = false; var revoked = false; var loseDecisionResponse = false; var loseRevokeResponse = false
        var advanceAfterDecision = false; var decisionConflict = false; var publishConflict = false; var wrongPairedProfile = false
        var lastDecision: String? = null; var lastPublish: String? = null; var serviceAccess: String? = null
        val proposals = linkedMapOf<String, JsonObject>(); private val receipts = linkedMapOf<String, JsonObject>(); private val commands = linkedMapOf<String, String>(); private val proposalDevices = linkedMapOf<String, String>(); private var deviceNumber = 0; private var deviceId = ""
        fun propose(adjustment: Int, forcedHp: Int? = null, source: String = "Explicit synthetic HP connection test"): String {
            val id = UUID.randomUUID().toString(); val before = requireNotNull(fields); val current = before.getValue("currentHp").jsonPrimitive.intOrNull ?: 0
            var p = buildJsonObject { put("commandId", id); put("recordId", profileId); put("expectedRevision", revision); put("fields", buildJsonObject { put("currentHp", forcedHp ?: current + adjustment) }); put("adjustment", adjustment); put("source", source); put("currentFields", before); put("status", "PENDING") }
            p = JsonObject(p + ("fingerprint" to JsonPrimitive(LiveHpConnectionProtocol.fingerprint(campaignId, p)))); proposals[id] = p; proposalDevices[id] = deviceId; return id
        }
        fun advance(hp: Int) { revision++; lastDeviceSyncAt = time; fields = JsonObject(requireNotNull(fields) + ("currentHp" to JsonPrimitive(hp))) }
        private fun response(status: Int = 200, mode: String = LiveHpConnectionProtocol.MODE, body: JsonObject = buildJsonObject {}) = OwnerSyncResponse(status,
            buildJsonObject { put("mode", mode); put("campaignId", campaignId); body.forEach { (k,v) -> put(k,v) } })
        private fun error(code: String, status: Int = 409, mode: String = LiveHpConnectionProtocol.MODE) = response(status, mode, buildJsonObject { put("error", buildJsonObject { put("code", code) }) })
        private fun receipt(id: String, status: String, prior: Long, result: Long, fingerprint: String, localId: JsonElement = JsonNull, localFields: JsonElement = JsonNull) = buildJsonObject {
            put("commandId", id); put("recordId", profileId); put("status", status); put("priorRevision", prior); put("resultingRevision", result); put("fingerprint", fingerprint); put("localReceiptId", localId)
            put("localRecordFingerprint", if (localFields == JsonNull) JsonNull else JsonPrimitive(OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(localFields))))
            put("recordedAt", time); put("eventId", UUID.randomUUID().toString()); put("deviceCredentialId", deviceId)
        }
        override suspend fun request(origin: String, path: String, credential: OwnerSyncCredential?, serviceAccess: String?, body: String?): OwnerSyncResponse {
            this.serviceAccess = serviceAccess
            if (offline) throw IOException("Offline synthetic transport")
            if (gatewayDenied) return response(401, "PRIVATE_GATEWAY", buildJsonObject { put("error", buildJsonObject { put("code", "GATEWAY_EXPIRED") }) })
            val b = body?.let { Json.parseToJsonElement(it).jsonObject }
            if (path == "/v1/pairings") { require(b!!.getValue("profileId").jsonPrimitive.content == profileId); return response(mode = OwnerConnectionProtocol.MODE, body = buildJsonObject { put("deviceCode", "device-code"); put("userCode", "TEST-CODE"); put("expiresAt", time + 600_000) }) }
            if (path == "/v1/pairings/token") { revoked = false; deviceNumber++; deviceId = "test-hp-device-$deviceNumber"; return response(mode = OwnerConnectionProtocol.MODE, body = buildJsonObject { put("status", "PAIRED"); put("credentialId", deviceId); put("profileId", if (wrongPairedProfile) "wrong-profile" else profileId); put("ownerLabel", "Test owner"); put("expiresAt", time + 86_400_000); put("scopes", buildJsonArray { add("hp:read"); add("hp:publish"); add("hp:decide") }) }) }
            require(credential?.credentialId == deviceId)
            if (path.endsWith("/devices/revoke")) { revoked = true; if (loseRevokeResponse) { loseRevokeResponse = false; throw IOException("Lost revoke response") }; return response(body = buildJsonObject { put("status", "REVOKED"); put("revoked", true); put("credentialId", credential!!.credentialId) }) }
            if (revoked) return error("CREDENTIAL_REVOKED_OR_EXPIRED", 401)
            if (path.endsWith("/heartbeat") && b != null) {
                heartbeatRequests++
                require(b.keys == setOf("commandId", "profileId", "revision", "fieldsFingerprint", "sentAt"))
                require(b.getValue("profileId").jsonPrimitive.content == profileId && b.getValue("revision").jsonPrimitive.long == revision &&
                    b.getValue("fieldsFingerprint").jsonPrimitive.content == OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(requireNotNull(fields))) &&
                    kotlin.math.abs(b.getValue("sentAt").jsonPrimitive.long - time) <= 60_000)
                lastDeviceSyncAt = time
                return response(body = buildJsonObject { put("commandId", b.getValue("commandId")); put("profileId", profileId); put("revision", revision); put("fieldsFingerprint", b.getValue("fieldsFingerprint")); put("lastDeviceSyncAt", lastDeviceSyncAt) })
            }
            if (path.endsWith("/characters") && b != null) {
                publishRequests++; lastPublish = body; if (offlineOnPublish) throw IOException("Offline publication")
                if (publishConflict) return error("REVISION_CONFLICT")
                val id = b.getValue("commandId").jsonPrimitive.content
                receipts[id]?.let { return if (commands[id] == body) response(body = buildJsonObject { put("receipt", it) }) else error("COMMAND_ID_REUSED") }
                if (b.getValue("expectedRevision").jsonPrimitive.long != revision) return error("REVISION_CONFLICT")
                val prior = revision; revision = b.getValue("revision").jsonPrimitive.long; lastDeviceSyncAt = time; fields = b.getValue("fields").jsonObject
                val r = receipt(id, "PUBLISHED", prior, revision, OwnerSyncProtocol.hash(body!!)); receipts[id] = r; commands[id] = body
                return response(body = buildJsonObject { put("receipt", r) })
            }
            if (path.endsWith("/characters/$profileId")) { recordReads++; if (fields == null) return error("CHARACTER_NOT_FOUND", 404)
                return response(body = buildJsonObject { put("profileId", profileId); put("recordId", profileId); put("revision", revision); put("fields", fields!!); put("lastDeviceSyncAt", lastDeviceSyncAt) }) }
            if (path.endsWith("/pending")) { pendingReads++; return response(body = buildJsonObject { put("proposals", buildJsonArray { proposals.values.filter { !receipts.containsKey(it.getValue("commandId").jsonPrimitive.content) && proposalDevices[it.getValue("commandId").jsonPrimitive.content] == deviceId }.forEach(::add) })
                put("superseded", buildJsonArray { proposals.values.filter { !receipts.containsKey(it.getValue("commandId").jsonPrimitive.content) && proposalDevices[it.getValue("commandId").jsonPrimitive.content] != deviceId }.forEach { p -> add(buildJsonObject { put("commandId", p.getValue("commandId")); put("recordId", profileId); put("expectedRevision", p.getValue("expectedRevision")); put("status", "SUPERSEDED_BY_REPAIR"); put("source", p.getValue("source")) }) } }) }) }
            if (path.contains("/receipts/")) { receiptReads++; val id = path.substringAfterLast('/'); return receipts[id]?.let { response(body = buildJsonObject { put("receipt", it) }) } ?: error("RECEIPT_NOT_FOUND", 404) }
            if (path.endsWith("/decisions") && b != null) {
                decisionRequests++; lastDecision = body; if (decisionConflict) return error("COMMAND_ID_REUSED")
                val id = b.getValue("commandId").jsonPrimitive.content
                receipts[id]?.let { return if (commands[id] == body) response(body = buildJsonObject { put("receipt", it) }) else error("COMMAND_ID_REUSED") }
                if (proposalDevices[id] != deviceId) return error("PROPOSAL_MISMATCH")
                val p = proposals.getValue(id); val approved = b.getValue("approved").jsonPrimitive.boolean; val expected = p.getValue("expectedRevision").jsonPrimitive.long
                val status = if (!approved) "REJECTED" else if (revision != expected) "CONFLICT" else "APPLIED"; val prior = revision
                if (status == "APPLIED") { revision++; lastDeviceSyncAt = time; fields = b.getValue("localFields").jsonObject }
                val r = receipt(id, status, prior, revision, p.getValue("fingerprint").jsonPrimitive.content, b.getValue("localReceiptId"), b.getValue("localFields")); receipts[id] = r; commands[id] = body!!
                if (advanceAfterDecision && status == "APPLIED") { advanceAfterDecision = false; advance(13) }
                if (loseDecisionResponse) { loseDecisionResponse = false; throw IOException("Lost decision response") }
                return response(body = buildJsonObject { put("receipt", r) })
            }
            return error("NOT_FOUND", 404)
        }
    }
}
