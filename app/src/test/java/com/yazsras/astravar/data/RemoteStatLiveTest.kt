package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Explicit opt-in: writes one fictional proposal and its tiny decision to the EXISTING public Worker.
 * Real HTTPS + actual Kotlin/Room path, with a simulated explicit player confirmation. Not a phone test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteStatLiveTest {
    @Test fun actualHttpsCommandWaitsForApprovalSurvivesRestartAndReturnsOneDecision() = runBlocking {
        assumeTrue("Live remote-stat tests are available only in debug builds", BuildConfig.DEBUG)
        assumeTrue("Live remote-stat test requires ASTRAVAR_LIVE_REMOTE_STATS=true",
            System.getenv("ASTRAVAR_LIVE_REMOTE_STATS") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runId = UUID.randomUUID().toString()
        val campaignName = "remote-stat-live-campaign-$runId.db"
        val sandboxName = "remote-stat-live-transport-$runId.db"
        val inboxName = "remote-stat-live-inbox-$runId.db"
        var campaignDb = Room.databaseBuilder(context, CampaignLedgerDb::class.java, campaignName).build()
        var sandboxDb = Room.databaseBuilder(context, SandboxSyncDb::class.java, sandboxName).build()
        var inboxDb = Room.databaseBuilder(context, RemoteStatDb::class.java, inboxName).build()
        val transport = SandboxHttpTransport()
        val origin = PublicSandbox.DEFAULT_ORIGIN
        var records = CampaignLedgerRepository(campaignDb).records
        var sandbox = SandboxSyncRepository(sandboxDb, transport)
        var remote = RemoteStatRepository(inboxDb, records, sandbox, transport)
        var passed = false
        try {
            remote.setEnabled(true)
            val settings = requireNotNull(remote.settings.first())
            assertTrue(settings.enabled)
            assertEquals(RemoteStatProtocol.PROFILE_ID, records.characterProfiles.first().single().id)
            assertTrue("Opt-in must not seed any scores", records.characterAbilities.first().isEmpty())
            sandbox.refresh()
            val revision = requireNotNull(sandbox.endpoint(origin).first()?.remoteRevision)
            val commandId = UUID.randomUUID().toString()
            val payload = RemoteAbilityPayload(commandId, settings.sessionId, "INT", 0, 16)
            val detail = RemoteStatProtocol.encode(payload)
            val body = JSONObject().put("campaignId", PublicSandbox.CAMPAIGN).put("connectionId", PublicSandbox.CONNECTION)
                .put("kind", "new_fact").put("detail", detail).put("commandId", commandId)
                .put("expectedRevision", revision).put("confirmed", true).toString()
            // Save the immutable sender intent before HTTPS. This is only a fixed disposable profile.
            val evidenceDir = System.getProperty("astravar.screenshots")?.let { File(it).parentFile } ?: context.filesDir
            evidenceDir.mkdirs()
            File(evidenceDir, "remote-stat-live-request.json").writeText(body)
            println("LIVE_REMOTE_STAT_INTENT commandId=$commandId sessionId=${settings.sessionId} body=$body")
            val commandReceipt = receiptFirst(transport, commandId, body)
            assertEquals(PublicSandbox.fingerprint("new_fact", detail, revision), commandReceipt.getString("payloadFingerprint"))
            assertEquals(commandId, commandReceipt.getString("commandId"))
            assertEquals(revision, commandReceipt.getLong("priorRevision"))
            assertEquals(revision + 1, commandReceipt.getLong("resultingRevision"))
            sandbox.refresh()
            remote.refresh()
            assertEquals("PENDING", remote.commands.first().single { it.commandId == commandId }.status)
            assertTrue("Remote delivery must never apply a score", records.characterAbilities.first().isEmpty())

            // Close/reopen all three physical databases before player approval.
            inboxDb.close(); sandboxDb.close(); campaignDb.close()
            campaignDb = Room.databaseBuilder(context, CampaignLedgerDb::class.java, campaignName).build()
            sandboxDb = Room.databaseBuilder(context, SandboxSyncDb::class.java, sandboxName).build()
            inboxDb = Room.databaseBuilder(context, RemoteStatDb::class.java, inboxName).build()
            records = CampaignLedgerRepository(campaignDb).records
            sandbox = SandboxSyncRepository(sandboxDb, transport)
            remote = RemoteStatRepository(inboxDb, records, sandbox, transport)
            assertEquals(settings.sessionId, remote.settings.first()?.sessionId)
            assertEquals("PENDING", remote.commands.first().single { it.commandId == commandId }.status)
            assertTrue(records.characterAbilities.first().isEmpty())
            // The only approval in this host test. Device approval is separately performed by the player.
            assertEquals("APPLIED", remote.apply(commandId).status)
            val ability = records.characterAbilities.first().single()
            assertEquals(RemoteStatProtocol.PROFILE_ID, ability.profileId)
            assertEquals("INT", ability.key)
            assertEquals(16, ability.score)
            assertEquals(0, ability.magicModifier)
            assertEquals(1L, ability.revision)
            assertTrue(ability.source.contains(commandId))
            remote.refresh()
            val decision = remote.commands.first().single { it.commandId == commandId }
            assertEquals("COMMITTED", decision.ackStatus)
            val ack = inboxDb.remote().acks(commandId).single { it.status == "COMMITTED" }
            assertEquals(RemoteStatProtocol.outcome(commandId, settings.sessionId, "APPLIED"), ack.detail)
            assertEquals(setOf("type", "v", "commandId", "sessionId", "status"), JSONObject(ack.detail).keys().asSequence().toSet())
            val ackReceipt = transport.request(origin,
                "/api/v1/campaigns/${PublicSandbox.CAMPAIGN}/commands/${ack.attemptId}/receipt", null)
            assertEquals(200, ackReceipt.status)
            assertEquals(ack.fingerprint, ackReceipt.json.getString("payloadFingerprint"))
            assertEquals(ack.attemptId, ackReceipt.json.getString("commandId"))
            sandbox.refresh()
            assertEquals(ack.detail, sandbox.events(origin).first().single { it.commandId == ack.attemptId }.detail)

            // Exact command replay on the deployed Worker returns the original receipt, never a new event.
            val replay = transport.request(origin, PublicSandbox.BASE + "/milestones", body)
            assertEquals(200, replay.status)
            assertEquals(commandReceipt.toString(), replay.json.toString())
            remote.refresh()
            assertEquals("APPLIED", remote.apply(commandId).status)
            val localCommand = RemoteStatProtocol.localCommandId(commandId, settings.sessionId)
            assertEquals(1, records.audit.first().count { it.commandId == localCommand })
            assertEquals(ability, records.characterAbilities.first().single())
            assertEquals(1, sandbox.events(origin).first().count { it.commandId == commandId })
            assertEquals(1, inboxDb.remote().acks(commandId).count { it.status == "COMMITTED" })
            println("LIVE_REMOTE_STAT_VERIFIED commandId=$commandId sessionId=${settings.sessionId} " +
                "commandEventId=${commandReceipt.getString("eventId")} ackId=${ack.attemptId} " +
                "ackEventId=${ackReceipt.json.getString("eventId")} localRevision=${ability.revision} " +
                "singleAudit=true; DISPOSABLE_PROFILE_ONLY; SIMULATED_PLAYER_APPROVAL; NOT_PHYSICAL_ANDROID")
            passed = true
        } finally {
            inboxDb.close(); sandboxDb.close(); campaignDb.close()
            if (passed) {
                context.deleteDatabase(inboxName); context.deleteDatabase(sandboxName); context.deleteDatabase(campaignName)
            } // Failed/uncertain runs retain their synthetic Room state and saved sender intent for investigation.
        }
    }

    private suspend fun receiptFirst(transport: SandboxTransport, id: String, body: String): JSONObject {
        val lookup = transport.request(PublicSandbox.DEFAULT_ORIGIN,
            "/api/v1/campaigns/${PublicSandbox.CAMPAIGN}/commands/$id/receipt", null)
        val response = if (lookup.status == 404 && lookup.json.optJSONObject("error")?.optString("code") == "COMMAND_NOT_FOUND") {
            transport.request(PublicSandbox.DEFAULT_ORIGIN, PublicSandbox.BASE + "/milestones", body)
        } else lookup
        assertEquals("Existing public transport must commit the exact command", 200, response.status)
        assertEquals(PublicSandbox.MODE, response.json.getString("mode"))
        assertEquals(PublicSandbox.CAMPAIGN, response.json.getString("campaignId"))
        assertEquals(PublicSandbox.CONNECTION, response.json.getString("connectionId"))
        assertEquals("COMMITTED", response.json.getString("status"))
        assertEquals(id, response.json.getString("commandId"))
        return response.json
    }
}
