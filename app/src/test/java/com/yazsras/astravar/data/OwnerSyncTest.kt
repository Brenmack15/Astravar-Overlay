package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnerSyncTest {
    private lateinit var db: OwnerSyncDb
    private val origin = "https://verified-owner-service.example"
    private val campaign = "synthetic-native-acceptance"
    private var clock = 10_000L
    private val credential get() = OwnerSyncCredential("device-fixture", campaign, origin, clock + 60_000, "a".repeat(43))
    private val fields = buildJsonObject { put("name", "Test Explorer"); put("currentHp", JsonNull) }
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OwnerSyncDb::class.java).build()
    }
    @After fun close() { db.close() }
    private fun repository(remote: FakeRemote, provider: OwnerSyncCredentialProvider = OwnerSyncCredentialProvider { _, _ -> credential }) =
        OwnerSyncRepository(db, provider, remote) { clock }
    private suspend fun queue(repo: OwnerSyncRepository, id: String = UUID.randomUUID().toString(), revision: Long = 0) =
        repo.queue(origin, campaign, "fixture-actor", revision, fields, "Synthetic confirmed test", true, id)

    @Test fun anonymous_worker_insecure_origins_and_real_campaigns_fail_closed() = runBlocking {
        for (endpoint in listOf("http://service.example", "https://localhost", "https://127.0.0.1", "https://name:secret@service.example", PublicSandbox.DEFAULT_ORIGIN)) {
            assertTrue(runCatching { OwnerSyncProtocol.origin(endpoint) }.isFailure)
        }
        val remote = FakeRemote()
        assertTrue(runCatching { repository(remote).queue(origin, "real-campaign", "actor", 0, fields, "fixture", true) }.isFailure)
        assertEquals(0, remote.requests)
    }
    @Test fun canonical_fields_are_stable_and_unknown_hp_stays_unknown() {
        val id = "00000000-0000-0000-0000-000000000001"
        val a = OwnerSyncProtocol.command(campaign, id, "actor", 0, buildJsonObject { put("b", 1); put("a", JsonNull) }, "fixture", true)
        val b = OwnerSyncProtocol.command(campaign, id, "actor", 0, buildJsonObject { put("a", JsonNull); put("b", 1) }, "fixture", true)
        assertEquals(a, b)
        assertEquals(64, OwnerSyncProtocol.hash(a).length)
        assertTrue(a.contains("\"a\":null"))
    }
    @Test fun cross_runtime_unicode_canonical_fixture_matches_reference_service_hash() {
        val body = OwnerSyncProtocol.command(campaign, "00000000-0000-0000-0000-000000000001", "actor", 0,
            buildJsonObject { put("😀", "accent é"); put("\uE000", JsonNull); put("integer", 42) }, "fixture", true)
        assertEquals("a59d32aaca438f1d93581551f49f2986ea0434e0215f02971ec0c70c4e80ae20", OwnerSyncProtocol.hash(body))
    }
    @Test fun invalid_revision_unconfirmed_or_reserved_fields_are_rejected() {
        val id = UUID.randomUUID().toString()
        for (revision in listOf(-1L, Long.MAX_VALUE)) {
            assertTrue(runCatching { OwnerSyncProtocol.command(campaign, id, "actor", revision, fields, "fixture", true) }.isFailure)
        }
        assertTrue(runCatching { OwnerSyncProtocol.command(campaign, id, "actor", 0, fields, "fixture", false) }.isFailure)
        assertTrue(runCatching { OwnerSyncProtocol.command(campaign, id, "actor", 0, buildJsonObject { put("_secret", 1) }, "fixture", true) }.isFailure)
        assertTrue(runCatching { OwnerSyncProtocol.command(campaign, id, "actor", 0, buildJsonObject { put("unsupportedDecimal", 1.5) }, "fixture", true) }.isFailure)
        for (noncanonical in listOf("0-0-0-0-1", "ABCDEFAB-0000-0000-0000-000000000001")) {
            assertTrue(runCatching { OwnerSyncProtocol.command(campaign, noncanonical, "actor", 0, fields, "fixture", true) }.isFailure)
        }
    }
    @Test fun queue_is_idempotent_and_reused_id_with_changed_revision_is_rejected() = runBlocking {
        val repo = repository(FakeRemote())
        val first = queue(repo)
        assertEquals(first, queue(repo, first.commandId))
        assertTrue(runCatching { queue(repo, first.commandId, 1) }.isFailure)
        assertEquals(1, db.sync().pending(origin, campaign).size)
    }
    @Test fun absent_credentials_never_reach_transport_and_command_remains_pending() = runBlocking {
        val remote = FakeRemote()
        val repo = repository(remote, OwnerSyncCredentialProvider { _, _ -> null })
        val command = queue(repo)
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.exceptionOrNull() is OwnerSyncFailure)
        assertEquals(0, remote.requests)
        assertEquals("PENDING", db.sync().command(command.commandId)!!.state)
    }
    @Test fun cross_campaign_or_expired_credentials_never_reach_transport() = runBlocking {
        for (bad in listOf(OwnerSyncCredential("device", "synthetic-other", origin, clock + 1, "b".repeat(43)),
            OwnerSyncCredential("device", campaign, origin, clock - 1, "b".repeat(43)))) {
            val remote = FakeRemote()
            val repo = repository(remote, OwnerSyncCredentialProvider { _, _ -> bad })
            queue(repo)
            assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
            assertEquals(0, remote.requests)
        }
    }
    @Test fun verified_receipt_and_independent_device_readback_agree() = runBlocking {
        val remote = FakeRemote(); val repo = repository(remote)
        val command = queue(repo)
        repo.sendPending(origin, campaign)
        val saved = db.sync().command(command.commandId)!!
        assertEquals("REMOTE_VERIFIED", saved.state)
        assertNotNull(saved.receiptJson)
        val events = repo.refresh(origin, campaign)
        assertEquals(1, events.size)
        assertEquals(command.commandId, events.single().commandId)
        assertEquals(1L, db.sync().cursor(origin, campaign)!!.cursor)
        assertEquals(events, repo.refresh(origin, campaign))
    }
    @Test fun lost_post_response_retries_original_body_and_returns_original_receipt_once() = runBlocking {
        val remote = FakeRemote().apply { loseNextResponse = true }; val repo = repository(remote)
        val command = queue(repo)
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
        assertEquals("PENDING", db.sync().command(command.commandId)!!.state)
        val firstReceipt = remote.receipts.getValue(command.commandId)
        repository(remote).sendPending(origin, campaign)
        assertEquals(1, remote.events.size)
        assertEquals(2, remote.postBodies.size)
        assertEquals(remote.postBodies[0], remote.postBodies[1])
        assertEquals(OwnerSyncProtocol.canonical(firstReceipt), db.sync().command(command.commandId)!!.receiptJson)
    }
    @Test fun file_backed_queue_survives_close_and_reopen() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "owner-sync-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, OwnerSyncDb::class.java, name).build()
        try {
            val command = queue(repository(FakeRemote()))
            db.close()
            db = Room.databaseBuilder(context, OwnerSyncDb::class.java, name).build()
            assertEquals(command, db.sync().command(command.commandId))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun revocation_blocks_queue_until_explicit_review_and_then_same_command_can_retry() = runBlocking {
        val remote = FakeRemote().apply { status = 401 }; val repo = repository(remote)
        val command = queue(repo)
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
        assertEquals("BLOCKED", db.sync().command(command.commandId)!!.state)
        assertEquals("CREDENTIAL_REVOKED_OR_EXPIRED", db.sync().command(command.commandId)!!.issueCode)
        remote.status = 200
        repo.sendPending(origin, campaign)
        assertTrue(remote.events.isEmpty())
        repo.resumeAfterAccessRestored(command.commandId)
        repo.sendPending(origin, campaign)
        assertEquals("REMOTE_VERIFIED", db.sync().command(command.commandId)!!.state)
    }
    @Test fun conflict_never_rebases_and_cannot_resume_as_authentication_retry() = runBlocking {
        val remote = FakeRemote().apply { status = 409 }; val repo = repository(remote)
        val command = queue(repo)
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
        val saved = db.sync().command(command.commandId)!!
        assertEquals("BLOCKED", saved.state)
        assertEquals(0L, saved.expectedRevision)
        assertEquals(command.body, saved.body)
        assertTrue(runCatching { repo.resumeAfterAccessRestored(command.commandId) }.isFailure)
    }
    @Test fun quota_or_offline_failure_keeps_durable_pending_body() = runBlocking {
        val remote = FakeRemote().apply { status = 429 }; val repo = repository(remote)
        val command = queue(repo)
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
        assertEquals("QUOTA_REACHED", db.sync().command(command.commandId)!!.issueCode)
        assertEquals(command.body, db.sync().command(command.commandId)!!.body)
        assertEquals("PENDING", db.sync().command(command.commandId)!!.state)
    }
    @Test fun mismatched_receipt_never_claims_remote_save() = runBlocking {
        val remote = FakeRemote().apply { badReceipt = true }; val repo = repository(remote)
        val command = queue(repo)
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
        assertEquals("PENDING", db.sync().command(command.commandId)!!.state)
        assertNull(db.sync().command(command.commandId)!!.receiptJson)
    }
    @Test fun tampered_outbox_is_rejected_before_network() = runBlocking {
        val remote = FakeRemote(); val repo = repository(remote)
        val command = queue(repo)
        db.sync().update(command.copy(body = command.body.replace("Test Explorer", "Tampered")))
        assertTrue(runCatching { repo.sendPending(origin, campaign) }.isFailure)
        assertEquals(0, remote.requests)
    }
    @Test fun missing_history_cursor_or_changed_event_retains_original_cursor() = runBlocking {
        val remote = FakeRemote(); val repo = repository(remote)
        queue(repo); repo.sendPending(origin, campaign)
        remote.corruptEvent = true
        assertTrue(runCatching { repo.refresh(origin, campaign) }.isFailure)
        assertNull(db.sync().cursor(origin, campaign))
        assertTrue(db.sync().received(origin, campaign).isEmpty())
        remote.corruptEvent = false
        assertEquals(1, repo.refresh(origin, campaign).size)
    }
    @Test fun encrypted_device_vault_persists_scoped_expiring_credentials_and_local_revocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val vault = OwnerSyncCredentialVault(context, { clock }, { key })
        vault.forgetAll()
        val value = credential
        vault.enroll(value)
        val stored = context.getSharedPreferences("astravar-owner-sync-credentials", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains(value.bearer))
        assertEquals("OwnerSyncCredential(redacted)", value.toString())
        val reopened = OwnerSyncCredentialVault(context, { clock }, { key })
        assertEquals(value.bearer, reopened.credential(origin, campaign)!!.bearer)
        assertNull(reopened.credential(origin, "synthetic-other"))
        reopened.forget(origin, campaign)
        assertNull(vault.credential(origin, campaign))
        vault.enroll(value)
        clock += 60_001
        assertNull(reopened.credential(origin, campaign))
    }
    @Test fun restored_credentials_without_original_hardware_key_fail_closed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = OwnerSyncCredentialVault(context, { clock }, { SecretKeySpec(ByteArray(32) { 1 }, "AES") })
        vault.forgetAll(); vault.enroll(credential)
        val other = OwnerSyncCredentialVault(context, { clock }, { SecretKeySpec(ByteArray(32) { 2 }, "AES") })
        assertNull(other.credential(origin, campaign))
        assertNull(vault.credential(origin, campaign))
    }

    private inner class FakeRemote : OwnerSyncTransport {
        var requests = 0
        var status = 200
        var loseNextResponse = false
        var badReceipt = false
        var corruptEvent = false
        val receipts = mutableMapOf<String, JsonObject>()
        val events = mutableListOf<JsonObject>()
        val postBodies = mutableListOf<String>()
        override suspend fun request(origin: String, path: String, credential: OwnerSyncCredential, body: String?): OwnerSyncResponse {
            requests++
            check(origin == this@OwnerSyncTest.origin && credential.campaignId == campaign)
            if (status != 200) return OwnerSyncResponse(status, buildJsonObject { put("error", buildJsonObject { put("code", "REVISION_CONFLICT") }) })
            if (body != null) {
                postBodies += body
                val data = Json.parseToJsonElement(body).jsonObject
                val id = data.getValue("commandId").jsonPrimitive.content
                val receipt = receipts.getOrPut(id) {
                    val value = buildJsonObject {
                        put("commandId", id); put("fingerprint", OwnerSyncProtocol.hash(body)); put("eventId", UUID.randomUUID().toString())
                        put("recordId", data.getValue("recordId")); put("priorRevision", data.getValue("expectedRevision"))
                        put("resultingRevision", data.getValue("expectedRevision").jsonPrimitive.long + 1)
                        put("cursor", events.size + 1); put("recordedAt", 1000)
                    }
                    events += JsonObject(value + mapOf("campaignId" to JsonPrimitive(campaign), "fields" to data.getValue("fields"),
                        "source" to data.getValue("source"), "confirmed" to JsonPrimitive(true)))
                    value
                }
                if (loseNextResponse) { loseNextResponse = false; throw IOException("Synthetic offline fixture") }
                return OwnerSyncResponse(200, buildJsonObject {
                    put("mode", OwnerSyncProtocol.MODE); put("campaignId", campaign)
                    put("receipt", if (badReceipt) JsonObject(receipt + ("fingerprint" to JsonPrimitive("bad"))) else receipt)
                })
            }
            val after = path.substringAfter("after=").substringBefore('&').toLong()
            val page = events.filter { it.getValue("cursor").jsonPrimitive.long > after }.map {
                if (corruptEvent) JsonObject(it + ("fields" to buildJsonObject { put("name", "Unverified change") })) else it
            }
            return OwnerSyncResponse(200, buildJsonObject {
                put("mode", OwnerSyncProtocol.MODE); put("campaignId", campaign); put("events", JsonArray(page))
                put("currentCursor", events.size); put("nextCursor", events.size); put("hasMore", false)
            })
        }
    }
}
