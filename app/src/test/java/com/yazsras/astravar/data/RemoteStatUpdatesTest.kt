package com.yazsras.astravar.data

import android.content.Context
import com.yazsras.astravar.BuildConfig
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteStatUpdatesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var campaign: CampaignLedgerDb
    private lateinit var sandboxDb: SandboxSyncDb
    private lateinit var remoteDb: RemoteStatDb
    private lateinit var records: CampaignRecordsRepository
    private lateinit var sandbox: SandboxSyncRepository
    private lateinit var remote: RemoteStatRepository
    private lateinit var api: RemoteStatFakeTransport
    private val origin = PublicSandbox.DEFAULT_ORIGIN
    @Before fun open() {
        campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        sandboxDb = Room.inMemoryDatabaseBuilder(context, SandboxSyncDb::class.java).build()
        remoteDb = Room.inMemoryDatabaseBuilder(context, RemoteStatDb::class.java).build()
        records = CampaignLedgerRepository(campaign).records
        api = RemoteStatFakeTransport()
        sandbox = SandboxSyncRepository(sandboxDb, api)
        remote = RemoteStatRepository(remoteDb, records, sandbox, api)
    }
    @After fun close() { remoteDb.close(); sandboxDb.close(); campaign.close() }
    private suspend fun command(score: Int = 16, expected: Long = 0, ability: String = "INT"): RemoteAbilityPayload {
        val setting = requireNotNull(remote.settings.first())
        return RemoteAbilityPayload(UUID.randomUUID().toString(), setting.sessionId, ability, expected, score)
            .also { api.external(RemoteStatProtocol.encode(it), it.commandId) }
    }
    private suspend fun row(id: String) = requireNotNull(remoteDb.remote().command(id))
    private suspend fun stat() = records.characterAbilities.first().firstOrNull { it.profileId == RemoteStatProtocol.PROFILE_ID && it.key == "INT" }
    private suspend fun fails(block: suspend () -> Unit) {
        try { block(); fail("Must reject without changing player data") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun actualVariantGateCannotBeBypassedOrWriteAnyRecord() = runBlocking {
        val disabled = RemoteStatRepository(remoteDb, records, sandbox, api, debugEnabled = false)
        val unknownCommand = UUID.randomUUID().toString()
        fails { disabled.setEnabled(true) }
        fails { disabled.refresh() }
        fails { disabled.apply(unknownCommand) }
        fails { disabled.reject(unknownCommand) }
        if (!BuildConfig.DEBUG) {
            // Passing true must never bypass the compiled production gate.
            val forced = RemoteStatRepository(remoteDb, records, sandbox, api, debugEnabled = true)
            for (repository in listOf(remote, forced)) {
                fails { repository.setEnabled(true) }; fails { repository.setEnabled(false) }
                fails { repository.refresh() }; fails { repository.apply(unknownCommand) }
                fails { repository.reject(unknownCommand) }
            }
        }
        assertEquals(0, api.requests)
        assertEquals(0, api.posts)
        assertNull(remote.settings.first())
        assertTrue(remote.commands.first().isEmpty())
        assertTrue(records.characterProfiles.first().isEmpty())
        assertTrue(records.characterAbilities.first().isEmpty())
        assertTrue(records.audit.first().isEmpty())
    }

    @Test fun strictCanonicalVersionedProtocolRejectsTypeCoercionDuplicateKeysAndOtherProfiles() {
        val value = RemoteAbilityPayload(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "INT", 0, 16)
        val wire = RemoteStatProtocol.encode(value)
        assertEquals(value, RemoteStatProtocol.parse(wire))
        listOf(wire.replace("\"v\":1", "\"v\":2"), wire.replace("\"score\":16", "\"score\":\"16\""),
            wire.replace("\"score\":16", "\"score\":16.0"), wire.replace("\"score\":16", "\"score\":1.6e1"),
            wire.replace("\"score\":16", "\"score\":1001"), wire.replace("\"score\":16", "\"score\":16,\"score\":16"),
            wire.replace("\"score\":16", "\"score\":16,\"extra\":true"),
            wire.replace(RemoteStatProtocol.PROFILE_ID, "local-player"), wire.replace("ChatGPT fictional test", "Claimed DM"),
            wire.replace("\"expectedRevision\":0", "\"expectedRevision\":null"), wire.replace("\"ability\":\"INT\"", "\"ability\":\"HP\""))
            .forEach { invalid -> try { RemoteStatProtocol.parse(invalid); fail(invalid) } catch (_: IllegalArgumentException) {} }
        assertNull(RemoteStatProtocol.parse("Fictional greeting"))
        assertFalse(RemoteStatProtocol.outcome(value.commandId, value.sessionId, "APPLIED").contains("score"))
    }

    @Test fun disabledAndReleaseGatePerformNoNetworkAndOptInSeedsNoScores() = runBlocking {
        if (!BuildConfig.DEBUG) {
            fails { remote.setEnabled(true) }
            fails { remote.refresh() }
            fails { remote.apply(UUID.randomUUID().toString()) }
            assertEquals(0, api.requests); assertEquals(0, api.posts)
            assertTrue(records.characterProfiles.first().isEmpty())
            assertTrue(records.characterAbilities.first().isEmpty())
            assertTrue(records.audit.first().isEmpty())
            assertTrue(remote.commands.first().isEmpty())
            assertNull(remote.settings.first())
            return@runBlocking
        }
        remote.refresh()
        assertEquals(0, api.requests)
        assertTrue(records.characterProfiles.first().isEmpty())
        val unavailable = RemoteStatRepository(remoteDb, records, sandbox, api, debugEnabled = false)
        fails { unavailable.setEnabled(true) }; fails { unavailable.refresh() }
        remote.setEnabled(true)
        assertEquals(RemoteStatProtocol.PROFILE_ID, records.characterProfiles.first().single().id)
        assertTrue(records.characterAbilities.first().isEmpty())
        assertEquals(0, api.requests)
        val session = remote.settings.first()!!.sessionId
        remote.setEnabled(false); remote.setEnabled(true)
        assertEquals(session, remote.settings.first()!!.sessionId)
    }

    @Test fun arrivalWaitsForConfirmationAndNeverTouchesRealCharacter() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        records.saveProfile(CharacterProfile(name = "Private test sentinel"), source = "Local only")
        records.saveAbility(CharacterAbility("INT", score = 19, magicModifier = 4), source = "Private test sentinel")
        remote.setEnabled(true)
        val payload = command()
        remote.refresh()
        assertEquals("PENDING", row(payload.commandId).status)
        assertNull(stat())
        assertEquals(19, records.characterAbilities.first().single().score)
        assertEquals(0, api.posts)
        remote.reject(payload.commandId)
        remote.refresh()
        assertNull(stat())
        assertEquals("REJECTED", row(payload.commandId).status)
        assertEquals("COMMITTED", row(payload.commandId).ackStatus)
        val detail = JSONObject(api.postBodies.single()).getString("detail")
        assertEquals(RemoteStatProtocol.outcome(payload.commandId, payload.sessionId, "REJECTED"), detail)
        assertFalse(api.postBodies.single().contains("Private"))
        assertFalse(detail.contains("score")); assertFalse(detail.contains("profileId"))
    }

    @Test fun approvedUpdateUsesExistingAtomicSaveAndPreservesMagicNotesAndBeforeProvenance() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        records.saveAbility(CharacterAbility("INT", RemoteStatProtocol.PROFILE_ID, 12, 3, "Fictional local note"), source = "Original local provenance")
        val payload = command(16, 1)
        remote.refresh()
        val applied = remote.apply(payload.commandId)
        assertEquals("APPLIED", applied.status)
        assertEquals(16, stat()!!.score); assertEquals(3, stat()!!.magicModifier)
        assertEquals("Fictional local note", stat()!!.notes); assertEquals(2L, stat()!!.revision)
        assertTrue(stat()!!.source.contains(payload.commandId))
        val audit = records.audit.first().single { it.commandId == RemoteStatProtocol.localCommandId(payload.commandId, payload.sessionId) }
        assertTrue(audit.beforeJson!!.contains("Original local provenance"))
        assertEquals(1L, audit.priorRevision); assertEquals(2L, audit.resultingRevision)
        remote.apply(payload.commandId)
        remote = RemoteStatRepository(remoteDb, records, sandbox, api)
        remote.refresh(); remote.apply(payload.commandId)
        assertEquals(2L, stat()!!.revision)
        assertEquals(1, records.audit.first().count { it.commandId == audit.commandId })
        assertEquals(1, api.posts)
        assertEquals(RemoteStatProtocol.outcome(payload.commandId, payload.sessionId, "APPLIED"), JSONObject(api.postBodies.single()).getString("detail"))
    }

    @Test fun editsAfterArrivalCauseConflictAndMustBeExplicitlyRejected() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh()
        records.saveAbility(CharacterAbility("INT", RemoteStatProtocol.PROFILE_ID, 11, 2), source = "Player entered a fictional value")
        assertEquals("CONFLICT", remote.apply(payload.commandId).status)
        assertEquals(11, stat()!!.score); assertEquals(1L, stat()!!.revision)
        assertEquals("NONE", row(payload.commandId).ackStatus)
        fails { remote.apply(payload.commandId) }
        remote.reject(payload.commandId); remote.refresh()
        assertEquals("REJECTED", row(payload.commandId).status)
        assertEquals(11, stat()!!.score)
    }

    @Test fun sameCommandReplayCannotResetLaterPlayerEditOrAddAnotherAudit() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh(); remote.apply(payload.commandId)
        records.saveAbility(stat()!!.copy(score = 17), 1, "Later player correction")
        api.external(RemoteStatProtocol.encode(payload), payload.commandId)
        remote.refresh(); remote.apply(payload.commandId)
        assertEquals(17, stat()!!.score); assertEquals(2L, stat()!!.revision)
        assertEquals(1, records.audit.first().count { it.commandId == RemoteStatProtocol.localCommandId(payload.commandId, payload.sessionId) })
    }

    @Test fun commandIdCollisionAndWrongSessionOrOuterIdDoNotModifyOriginalProposal() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh()
        api.external(RemoteStatProtocol.encode(payload.copy(score = 18)), payload.commandId)
        fails { remote.refresh() }
        assertEquals(16, row(payload.commandId).proposedScore)
        assertNull(stat())
        // Foreign session and mismatched transport IDs cannot enter the inbox.
        api.events.removeAt(api.events.lastIndex); api.revision--
        val foreign = payload.copy(commandId = UUID.randomUUID().toString(), sessionId = UUID.randomUUID().toString())
        api.external(RemoteStatProtocol.encode(foreign), foreign.commandId)
        val mismatch = payload.copy(commandId = UUID.randomUUID().toString())
        api.external(RemoteStatProtocol.encode(mismatch), UUID.randomUUID().toString())
        // Already immutable bad history is intentionally not rewritten; inspect via a new isolated sandbox store.
        val freshSandboxDb = Room.inMemoryDatabaseBuilder(context, SandboxSyncDb::class.java).build()
        try {
            val fresh = RemoteStatRepository(remoteDb, records, SandboxSyncRepository(freshSandboxDb, api), api)
            fresh.refresh()
            assertEquals(1, fresh.commands.first().size)
        } finally { freshSandboxDb.close() }
    }

    @Test fun offlineDeliveryWaitsForNetworkAndConfirmationAndOffSwitchPersists() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        api.offline = true
        try { remote.refresh(); fail() } catch (_: IOException) {}
        assertTrue(remote.commands.first().isEmpty()); assertNull(stat())
        remote = RemoteStatRepository(remoteDb, records, sandbox, api)
        api.offline = false; remote.refresh()
        assertEquals("PENDING", row(payload.commandId).status); assertNull(stat())
        remote.setEnabled(false)
        val requests = api.requests
        remote = RemoteStatRepository(remoteDb, records, sandbox, api)
        remote.refresh(); fails { remote.apply(payload.commandId) }; fails { remote.reject(payload.commandId) }
        assertEquals(requests, api.requests); assertNull(stat())
        remote.setEnabled(true); remote.apply(payload.commandId)
        assertEquals(16, stat()!!.score)
    }

    @Test fun lostOutcomeResponseRecoversOriginalReceiptWithoutSecondPost() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh(); remote.apply(payload.commandId)
        api.dropAfterCommit = true
        try { remote.refresh(); fail() } catch (_: IOException) {}
        val original = remoteDb.remote().acks(payload.commandId).single()
        assertEquals("PENDING", original.status); assertEquals(1, api.posts)
        remote = RemoteStatRepository(remoteDb, records, sandbox, api)
        remote.refresh()
        val recovered = remoteDb.remote().acks(payload.commandId).single()
        assertEquals(original.attemptId, recovered.attemptId); assertEquals(original.body, recovered.body)
        assertEquals("COMMITTED", recovered.status); assertEquals(1, api.posts)
        assertEquals(1L, stat()!!.revision)
    }

    @Test fun outcomeRevisionConflictPreservesOriginalAttemptAndDoesNotReapplyStat() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh(); remote.apply(payload.commandId)
        api.conflictNextPost = true
        try { remote.refresh(); fail() } catch (e: SandboxFailure) { assertEquals("REVISION_CONFLICT", e.code) }
        val first = remoteDb.remote().acks(payload.commandId).single()
        assertEquals("REVISION_CONFLICT", first.status)
        remote.refresh()
        val attempts = remoteDb.remote().acks(payload.commandId)
        assertEquals(2, attempts.size)
        assertEquals(first, attempts.single { it.attemptId == first.attemptId })
        assertEquals(1, attempts.count { it.status == "COMMITTED" })
        assertEquals(1L, stat()!!.revision)
        assertEquals("COMMITTED", row(payload.commandId).ackStatus)
    }

    @Test fun verifiedReceiptStaysCommittedWhenFollowingHistoryRefreshGoesOffline() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh(); remote.reject(payload.commandId)
        api.offlineAfterCommit = true
        try { remote.refresh(); fail() } catch (_: IOException) {}
        assertEquals("COMMITTED", remoteDb.remote().acks(payload.commandId).single().status)
        assertEquals("COMMITTED", row(payload.commandId).ackStatus)
        api.offline = false
        remote.refresh()
        assertEquals(1, api.posts)
    }

    @Test fun mismatchedReceiptCannotClaimConfirmationDelivery() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh(); remote.reject(payload.commandId)
        api.corruptReceipt = true
        fails { remote.refresh() }
        assertEquals("PENDING", row(payload.commandId).ackStatus)
        assertNull(remoteDb.remote().acks(payload.commandId).single().receiptJson)
        api.corruptReceipt = false
        remote.refresh()
        assertEquals("COMMITTED", row(payload.commandId).ackStatus)
        assertEquals(1, api.posts)
    }

    @Test fun corruptedReceiptOutboxCannotUploadPrivateFields() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        remote.setEnabled(true)
        val payload = command()
        remote.refresh(); remote.reject(payload.commandId)
        api.rejectPosts = true
        try { remote.refresh(); fail() } catch (_: IOException) {}
        val ack = remoteDb.remote().acks(payload.commandId).single()
        remoteDb.remote().update(ack.copy(body = JSONObject(ack.body).put("characterSheet", "private sentinel").toString()))
        val posts = api.posts
        api.rejectPosts = false
        fails { remote.refresh() }
        assertEquals(posts, api.posts)
        assertEquals("PENDING", row(payload.commandId).ackStatus)
    }

    @Test fun physicalDatabaseReopenRecoversApprovedTransactionExactlyOnceAfterCrashWindow() = runBlocking {
        assumeTrue("This fictional remote-stat scenario is a debug-only feature", BuildConfig.DEBUG)
        val names = listOf("remote-stat-campaign-reopen.db", "remote-stat-sandbox-reopen.db", "remote-stat-inbox-reopen.db")
        names.forEach(context::deleteDatabase)
        var c = Room.databaseBuilder(context, CampaignLedgerDb::class.java, names[0]).build()
        var s = Room.databaseBuilder(context, SandboxSyncDb::class.java, names[1]).build()
        var r = Room.databaseBuilder(context, RemoteStatDb::class.java, names[2]).build()
        try {
            var rec = CampaignLedgerRepository(c).records
            var bridge = RemoteStatRepository(r, rec, SandboxSyncRepository(s, api), api,
                afterCampaignSave = { throw IOException("Process ended after campaign commit") })
            bridge.setEnabled(true)
            val setting = bridge.settings.first()!!
            val payload = RemoteAbilityPayload(UUID.randomUUID().toString(), setting.sessionId, "INT", 0, 16)
            api.external(RemoteStatProtocol.encode(payload), payload.commandId)
            bridge.refresh()
            try { bridge.apply(payload.commandId); fail() } catch (_: IOException) {}
            assertEquals("APPLYING", r.remote().command(payload.commandId)!!.status)
            assertEquals(1L, rec.characterAbilities.first().single().revision)
            c.close(); s.close(); r.close()
            c = Room.databaseBuilder(context, CampaignLedgerDb::class.java, names[0]).build()
            s = Room.databaseBuilder(context, SandboxSyncDb::class.java, names[1]).build()
            r = Room.databaseBuilder(context, RemoteStatDb::class.java, names[2]).build()
            rec = CampaignLedgerRepository(c).records
            bridge = RemoteStatRepository(r, rec, SandboxSyncRepository(s, api), api)
            assertEquals(setting.sessionId, bridge.settings.first()!!.sessionId)
            bridge.refresh()
            assertEquals("APPLIED", r.remote().command(payload.commandId)!!.status)
            assertEquals("COMMITTED", r.remote().command(payload.commandId)!!.ackStatus)
            assertEquals(1L, rec.characterAbilities.first().single().revision)
            assertEquals(1, rec.audit.first().count { it.recordType == "character-ability" })
            bridge.apply(payload.commandId)
            assertEquals(1L, rec.characterAbilities.first().single().revision)
        } finally { c.close(); s.close(); r.close(); names.forEach(context::deleteDatabase) }
    }
}

/** Simulates only the deployed fixed fictional transport contract, never campaign records. */
private class RemoteStatFakeTransport : SandboxTransport {
    var requests = 0
    var posts = 0
    var revision = 1L
    var offline = false
    var dropAfterCommit = false
    var conflictNextPost = false
    var rejectPosts = false
    var offlineAfterCommit = false
    var corruptReceipt = false
    val events = mutableListOf<JSONObject>()
    val postBodies = mutableListOf<String>()
    private val receipts = mutableMapOf<String, JSONObject>()
    private fun scope() = JSONObject().put("mode", PublicSandbox.MODE).put("campaignId", PublicSandbox.CAMPAIGN).put("connectionId", PublicSandbox.CONNECTION)
    private fun error(status: Int, code: String) = SandboxHttpResponse(status, JSONObject().put("mode", PublicSandbox.MODE)
        .put("error", JSONObject().put("code", code).put("message", code)))
    fun external(detail: String, commandId: String = UUID.randomUUID().toString()) {
        events += JSONObject().put("eventId", UUID.randomUUID().toString()).put("commandId", commandId).put("kind", "new_fact")
            .put("detail", detail).put("priorRevision", revision).put("resultingRevision", ++revision).put("recordedAt", "2026-10-18T15:00:00Z")
    }
    override suspend fun request(origin: String, path: String, body: String?): SandboxHttpResponse {
        requests++
        check(origin == PublicSandbox.DEFAULT_ORIGIN)
        if (offline) throw IOException("Synthetic offline delivery")
        if (path == "/health") return SandboxHttpResponse(200, scope().put("disposable", true).put("authentication", "NONE").put("realCampaignAccess", false))
        if (path == PublicSandbox.BASE) return SandboxHttpResponse(200, scope().put("disposable", true).put("revision", revision).put("name", "Disposable Test Merchant"))
        if (path.endsWith("/receipt")) {
            val id = path.substringAfter("/commands/").substringBefore("/receipt")
            return receipts[id]?.let { SandboxHttpResponse(200, JSONObject(it.toString()).apply {
                if (corruptReceipt) put("payloadFingerprint", "invalid")
            }) } ?: error(404, "COMMAND_NOT_FOUND")
        }
        if (body != null) {
            if (rejectPosts) throw IOException("Synthetic offline outcome")
            posts++; postBodies += body
            val command = JSONObject(body)
            receipts[command.getString("commandId")]?.let { return SandboxHttpResponse(200, it) }
            if (conflictNextPost) { conflictNextPost = false; external("Fictional competing event") }
            val prior = command.getLong("expectedRevision")
            if (prior != revision) return error(409, "REVISION_CONFLICT")
            external(command.getString("detail"), command.getString("commandId"))
            val receipt = scope().put("status", "COMMITTED").put("eventId", events.last().getString("eventId"))
                .put("commandId", command.getString("commandId")).put("priorRevision", prior).put("resultingRevision", revision)
                .put("recordedAt", "2026-10-18T15:00:00Z").put("payloadFingerprint", PublicSandbox.fingerprint("new_fact", command.getString("detail"), prior))
            receipts[command.getString("commandId")] = receipt
            if (dropAfterCommit) { dropAfterCommit = false; throw IOException("Synthetic lost response after commit") }
            if (offlineAfterCommit) { offlineAfterCommit = false; offline = true }
            return SandboxHttpResponse(200, JSONObject(receipt.toString()).apply {
                if (corruptReceipt) put("payloadFingerprint", "invalid")
            })
        }
        if (path.contains("/changes?")) {
            val cursor = path.substringAfter("afterRevision=").substringBefore('&').toLong()
            val unread = events.filter { it.getLong("resultingRevision") > cursor }
            return SandboxHttpResponse(200, scope().put("currentRevision", revision).put("nextCursor", revision)
                .put("hasMore", false).put("milestones", JSONArray(unread)))
        }
        error("Unexpected route $path")
    }
}
