package com.yazsras.astravar.data

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Native AndroidKeyStore/Room + production HTTPS Worker and MCP protocol on disposable CI fixtures.
 * The local gateway simulates the Sites identity boundary. This is not hosted ChatGPT or phone acceptance.
 */
@RunWith(AndroidJUnit4::class)
class CampaignNativeHttpsTest {
    private lateinit var context: Context
    private lateinit var config: JsonObject
    private lateinit var originalSsl: SSLSocketFactory
    private lateinit var db: CampaignLedgerDb
    private lateinit var client: CampaignConnectionClient
    private lateinit var ledger: CampaignLedgerRepository
    private val origin = "https://hp-bridge.example"
    private val campaign = "local-campaign"
    private val player = "campaign-native-player"
    private val wallet = "campaign-native-wallet"
    private val companion = "campaign-native-owl"
    private val companionResource = "campaign-native-owl-charge"
    private val project = "campaign-native-project"
    private val stage = "campaign-native-stage"
    private val laterStage = "campaign-native-later-stage"
    private val source = "Confirmed isolated fictional native HTTPS campaign acceptance"
    private val allScopes = CampaignConnectionProtocol.categories
    private val measurements = mutableListOf<JsonObject>()
    private var mcpRequests = 0
    private var mcpErrors = 0
    private var deliberateRetryCommands = 0
    private var lostResponseRetries = 0
    private var offlineReconnectRecoveries = 0
    private var maximumFreshnessMs = 0L

    @Before fun isolatedNativeHttps() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("Destructive fixtures require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        config = Json.parseToJsonElement(File(context.filesDir, "ci-https-config.json").readText()).jsonObject
        assertEquals(origin, config.getValue("origin").jsonPrimitive.content)
        originalSsl = HttpsURLConnection.getDefaultSSLSocketFactory()
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        anchors.setCertificateEntry("ephemeral-native-campaign-ci", CertificateFactory.getInstance("X.509")
            .generateCertificate(config.getValue("certificatePem").jsonPrimitive.content.byteInputStream()))
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trusts.trustManagers, null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        assertTrue(control("/__ci/health").getValue("syntheticOnly").jsonPrimitive.boolean)
        db = CampaignLedgerDb.open(context)
        ledger = CampaignLedgerRepository(db)
        client = CampaignConnectionClient(context)
        ledger.records.saveProfile(CharacterProfile(name = "Fictional HTTPS campaign player", id = player,
            currentHp = 12, maximumHp = 20, temporaryHp = 2), source = source)
        ledger.party.saveSheet(player, PartySheetData(resources = listOf(SheetResource("Level 2", 2, 3), SheetResource("Unknown charge"))), source = source)
        ledger.party.saveWallet(PartyWallet(wallet, player, "Fictional HTTPS wallet", gold = 10), source = source)
        ledger.records.saveCompanionState(CompanionState("Fictional HTTPS owl", companionId = companion), source = source)
        ledger.records.saveCompanionResource(CompanionResource("Charges", companion, 2, 3, id = companionResource), source = source)
        ledger.records.saveProject(InventionProject("Fictional HTTPS project", status = "Construction", id = project), source = source)
        ledger.records.saveStage(ProjectStage(project, "Confirmed first stage", id = stage, status = "In progress"), source = source)
        ledger.records.saveStage(ProjectStage(project, "Unresolved later stage", id = laterStage), source = source)
        Unit
    }

    @After fun restoreProcessTrust() {
        if (::config.isInitialized) runCatching { control("/__ci/fault", buildJsonObject { put("offline", false) }) }
        if (::client.isInitialized) client.close()
        if (::db.isInitialized) db.close()
        if (::originalSsl.isInitialized) HttpsURLConnection.setDefaultSSLSocketFactory(originalSsl)
    }
    private fun control(path: String, body: JsonObject = buildJsonObject {}): JsonObject {
        require(path.startsWith("/__ci/"))
        val connection = URL(origin + path).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Astravar-Ci-Control", config.getValue("controlToken").jsonPrimitive.content)
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("Isolated HTTPS control $path: $response", 200, status)
            Json.parseToJsonElement(response).jsonObject
        } finally { connection.disconnect() }
    }
    private fun owner(path: String, body: JsonObject = buildJsonObject {}, method: String = "POST") = control("/__ci/owner", buildJsonObject {
        put("path", path); put("method", method); put("body", body)
    })
    private fun rpc(name: String, arguments: JsonObject): JsonObject {
        val started = System.nanoTime()
        val envelope = owner("/mcp", buildJsonObject {
            put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", arguments) }
        })
        assertFalse("MCP JSON-RPC $name must be well-formed: $envelope", envelope.containsKey("error"))
        val result = envelope.getValue("result").jsonObject
        mcpRequests++
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) mcpErrors++
        measurements += buildJsonObject {
            put("operation", name); put("durationMs", (System.nanoTime() - started) / 1_000_000.0)
            put("responseBytes", result.toString().toByteArray().size)
        }
        return result
    }
    private fun mcp(name: String, arguments: JsonObject): JsonObject {
        val response = rpc(name, arguments)
        assertFalse("MCP $name must succeed: $response", response.getValue("isError").jsonPrimitive.boolean)
        return response.getValue("structuredContent").jsonObject
    }
    private fun record(kind: String, id: String): JsonObject {
        val record = mcp("get_campaign_record", buildJsonObject {
            put("campaignId", campaign); put("kind", kind); put("recordId", id)
        }).getValue("records").jsonArray.single().jsonObject
        assertFalse("Freshly committed Android record is fresh", record.getValue("stale").jsonPrimitive.boolean)
        maximumFreshnessMs = maxOf(maximumFreshnessMs, System.currentTimeMillis() - record.getValue("lastDeviceSyncAt").jsonPrimitive.long)
        return buildJsonObject { listOf("recordId", "kind", "revision", "fields").forEach { put(it, record.getValue(it)) } }
    }
    private fun operation(type: String, kind: String, id: String, revision: Long, fields: JsonObject) = buildJsonObject {
        put("type", type); put("kind", kind); put("recordId", id); put("expectedRevision", revision); put("fields", fields)
    }
    private fun fields(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private suspend fun transactionBody(id: String, operations: List<JsonObject>): JsonObject {
        val grant = client.snapshot().getValue("grant").jsonObject
        return buildJsonObject {
            put("campaignId", campaign); put("commandId", id); put("expectedGrantRevision", grant.getValue("revision"))
            put("operations", JsonArray(operations)); put("source", source)
        }
    }
    private suspend fun submitAndVerify(vararg operations: JsonObject, loseDecisionResponse: Boolean = false,
        offlineBeforeDelivery: Boolean = false): JsonObject {
        val id = UUID.randomUUID().toString()
        val body = transactionBody(id, operations.toList())
        val started = System.nanoTime()
        val submitted = mcp("submit_campaign_transaction", body)
        assertEquals("PENDING_DEVICE_COMMIT", submitted.getValue("confirmation").jsonPrimitive.content)
        if (offlineBeforeDelivery) {
            val before = CampaignSyncDomain(db).snapshot(campaign)
            control("/__ci/fault", buildJsonObject { put("offline", true) })
            assertTrue("Offline delivery must report failure", runCatching { client.sync() }.isFailure)
            assertEquals("Offline delivery cannot fabricate a local commit", before, CampaignSyncDomain(db).snapshot(campaign))
            client.close(); client = CampaignConnectionClient(context)
            assertTrue(client.snapshot().getValue("status").jsonPrimitive.content.contains("Offline"))
            control("/__ci/fault", buildJsonObject { put("offline", false) })
            offlineReconnectRecoveries++
        }
        if (loseDecisionResponse) {
            control("/__ci/fault", buildJsonObject { put("dropNextDecision", true) })
            // The isolated gateway commits the decision, then intentionally drops its response.
            // Android must recover the same immutable receipt without a second local expenditure.
            runCatching { client.sync() }
            lostResponseRetries++
        }
        client.sync()
        val receipt = mcp("get_campaign_command_receipt", buildJsonObject { put("campaignId", campaign); put("commandId", id) }).getValue("receipt").jsonObject
        assertEquals("APPLIED", receipt.getValue("status").jsonPrimitive.content)
        val local = CampaignConnectionProjection.project(CampaignSyncDomain(db).snapshot(campaign), allScopes)
        for (raw in receipt.getValue("records").jsonArray) {
            val saved = raw.jsonObject
            val kind = saved.getValue("kind").jsonPrimitive.content; val recordId = saved.getValue("recordId").jsonPrimitive.content
            assertEquals("Independent backend readback equals immutable receipt", saved, record(kind, recordId))
            val android = local.single { it.kind == kind && it.recordId == recordId }
            assertEquals("Independent authorized Android Room projection equals backend", campaignJson.encodeToJsonElement(android), saved)
        }
        measurements += buildJsonObject {
            put("operation", "submit_to_device_receipt_and_independent_readback")
            put("recordCount", receipt.getValue("records").jsonArray.size)
            put("durationMs", (System.nanoTime() - started) / 1_000_000.0)
        }
        // Reusing the same command is an idempotent retry, never a second expenditure.
        mcp("submit_campaign_transaction", body); deliberateRetryCommands++; client.sync()
        val repeated = mcp("get_campaign_command_receipt", buildJsonObject { put("campaignId", campaign); put("commandId", id) }).getValue("receipt")
        assertEquals(receipt, repeated)
        return receipt
    }

    @Test fun authenticatedAutoSyncCommitsAllRecordCategoriesAndVerifiesReceipts() = runBlocking {
        val container = ledger.create(LedgerDraft("Fictional HTTPS container", quantity = 1))
        val item = ledger.create(LedgerDraft("Fictional established material", evidenceStatus = "Confirmed", quantity = 1))
        val authorization = URI(client.startServiceAuthorization(origin, player))
        val fragment = authorization.rawFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val encrypted = owner("/owner/bootstrap/bundle", buildJsonObject { put("origin", origin); fragment.forEach { (key, value) -> put(key, value) } })
        client.importServiceAuthorization(encrypted.toString().toByteArray())
        val pairing = client.beginPairing(origin, player, "CI fictional campaign emulator", allScopes, allScopes)
        owner("/v1/owner/pairings/approve", buildJsonObject { put("userCode", pairing.getValue("pairing").jsonObject.getValue("userCode")) })
        assertTrue(client.finishPairing().getValue("connected").jsonPrimitive.boolean)
        client.setAutoSync(allScopes); client.sync()
        assertEquals(12, record("character-profile", player).getValue("fields").jsonObject.getValue("currentHp").jsonPrimitive.int)

        submitAndVerify(operation("adjust_hp", "character-profile", player, 1, fields("delta" to JsonPrimitive(-3))), offlineBeforeDelivery = true)
        assertEquals(9, db.recordsDao().characterProfile(player)!!.currentHp)
        assertEquals(20, db.recordsDao().characterProfile(player)!!.maximumHp)
        assertEquals(2, db.recordsDao().characterProfile(player)!!.temporaryHp)
        submitAndVerify(operation("adjust_resource", "party-sheet", player, 1, fields("resourceName" to JsonPrimitive("Level 2"), "delta" to JsonPrimitive(-1))))
        assertEquals(1, db.partyDao().sheet(player)!!.sheet.resources.first().current)
        assertNull(db.partyDao().sheet(player)!!.sheet.resources.last().current)
        submitAndVerify(operation("move_item", "item", item.id, item.revision, fields("expectedPlacementRevision" to JsonNull,
            "containerItemId" to JsonPrimitive(container.id), "state" to JsonPrimitive("Stored"))))
        assertEquals(item.id, db.partyDao().placement(item.id)!!.itemId)
        assertEquals(container.id, db.partyDao().placement(item.id)!!.containerItemId)

        val purchased = "campaign-native-purchased-coil"
        submitAndVerify(operation("adjust_currency", "wallet", wallet, 1, fields("goldDelta" to JsonPrimitive(-4))),
            operation("acquire_item", "item", purchased, 0, campaignJson.encodeToJsonElement(LedgerDraft("Fictional purchased coil", evidenceStatus = "Confirmed", quantity = 1, itemState = "Carried")).jsonObject))
        assertEquals(6L, db.partyDao().wallet(wallet)!!.gold)
        assertEquals(1, db.ledgerDao().getItem(purchased)!!.quantity)
        submitAndVerify(operation("adjust_resource", "companion-resource", companionResource, 1, fields("delta" to JsonPrimitive(-1))))
        assertEquals(1, db.recordsDao().resource(companionResource)!!.current)
        assertEquals(9, db.recordsDao().characterProfile(player)!!.currentHp)
        val confirmedStage = db.recordsDao().stage(stage)!!
        submitAndVerify(operation("set_record", "stage", stage, confirmedStage.revision, campaignJson.encodeToJsonElement(confirmedStage.copy(status = "Completed")).jsonObject))
        assertEquals("Completed", db.recordsDao().stage(stage)!!.status)
        assertEquals("Planned", db.recordsDao().stage(laterStage)!!.status)
        assertEquals("Construction", db.recordsDao().project(project)!!.status)
        val checkpoint = CampaignCheckpoint("campaign-native-checkpoint", "Confirmed fictional session", scene = "Fictional workshop",
            lastConfirmedAction = "Finished only the confirmed first stage", unresolvedChoice = "Choose the next project",
            recordReferences = listOf("notion:fictional-native-source"), unknowns = listOf("Unknown charge count"))
        submitAndVerify(operation("save_checkpoint", "checkpoint", checkpoint.id, 0, campaignJson.encodeToJsonElement(checkpoint).jsonObject))
        assertEquals(checkpoint.recordReferences, db.partyDao().checkpoint(checkpoint.id)!!.recordReferences)

        val stale = transactionBody(UUID.randomUUID().toString(), listOf(operation("adjust_hp", "character-profile", player, 1, fields("delta" to JsonPrimitive(-1)))))
        assertTrue("Stale revisions must fail through MCP", rpc("submit_campaign_transaction", stale).getValue("isError").jsonPrimitive.boolean)
        assertEquals(9, db.recordsDao().characterProfile(player)!!.currentHp)
        client.setAutoSync(allScopes - "hp")
        val manualId = UUID.randomUUID().toString()
        mcp("submit_campaign_transaction", transactionBody(manualId, listOf(operation("adjust_hp", "character-profile", player, 2, fields("delta" to JsonPrimitive(-1))))))
        client.sync()
        assertEquals("Disabled scope does not auto-apply", 9, db.recordsDao().characterProfile(player)!!.currentHp)
        assertEquals("PENDING_APPROVAL", client.snapshot().getValue("pending").jsonArray.single { it.jsonObject.getValue("commandId").jsonPrimitive.content == manualId }.jsonObject.getValue("state").jsonPrimitive.content)
        client.decide(manualId, false)
        assertEquals("REJECTED", mcp("get_campaign_command_receipt", buildJsonObject { put("campaignId", campaign); put("commandId", manualId) }).getValue("receipt").jsonObject.getValue("status").jsonPrimitive.content)
        client.setAutoSync(allScopes)
        submitAndVerify(operation("adjust_hp", "character-profile", player, 2, fields("delta" to JsonPrimitive(-1))), loseDecisionResponse = true)
        assertEquals("Lost acknowledgement recovery applies exactly once", 8, db.recordsDao().characterProfile(player)!!.currentHp)
        val reduced = client.restrictPermissions(allScopes - "inventory", allScopes - setOf("inventory", "hp"))
        assertFalse("Read/write revocation is confirmed on the server", reduced.getValue("grantPending").jsonPrimitive.boolean)
        assertFalse(reduced.getValue("grant").jsonObject.getValue("readScopes").jsonArray.any { it.jsonPrimitive.content == "inventory" })
        assertFalse(reduced.getValue("grant").jsonObject.getValue("writeScopes").jsonArray.any { it.jsonPrimitive.content == "hp" })
        assertEquals("HP read remains independently authorized", 8, record("character-profile", player).getValue("fields").jsonObject.getValue("currentHp").jsonPrimitive.int)
        assertTrue("Revoked inventory read fails through MCP", rpc("get_campaign_record", buildJsonObject {
            put("campaignId", campaign); put("kind", "item"); put("recordId", item.id)
        }).getValue("isError").jsonPrimitive.boolean)
        assertTrue("Revoked HP write fails while HP read remains authorized", rpc("submit_campaign_transaction", transactionBody(UUID.randomUUID().toString(),
            listOf(operation("adjust_hp", "character-profile", player, 3, fields("delta" to JsonPrimitive(-1)))))).getValue("isError").jsonPrimitive.boolean)
        assertEquals(8, db.recordsDao().characterProfile(player)!!.currentHp)
        val revoked = client.revoke()
        assertFalse(revoked.getValue("connected").jsonPrimitive.boolean)
        assertFalse("Server revocation must be confirmed, not only disabled locally", revoked.getValue("revokePending").jsonPrimitive.boolean)
        assertTrue(revoked.getValue("status").jsonPrimitive.content.contains("confirmed"))
        assertEquals("Only deliberate stale-revision and independently revoked read/write requests fail", 3, mcpErrors)
        val result = buildJsonObject {
            put("syntheticOnly", true); put("androidEmulator", true); put("productionWorkerHttps", true)
            put("hostedChatGpt", false); put("physicalGalaxy", false)
            put("hp", 8); put("gold", 6); put("spellSlots", 1); put("companionCharges", 1)
            put("atomicPurchase", true); put("idempotentRetries", true); put("staleAndDisabledScopeRejected", true)
            put("receiptAndAndroidReadback", true); put("serverRevocationVerified", true)
            put("mcpRequests", mcpRequests); put("observedMcpErrors", mcpErrors); put("expectedNegativeTestErrors", 3)
            put("independentReadWriteRevocation", true)
            put("observedErrorRateIncludingExpectedNegativeTests", mcpErrors.toDouble() / mcpRequests)
            put("deliberateIdempotentRetryCommands", deliberateRetryCommands); put("lostDecisionResponseRetries", lostResponseRetries)
            put("offlineReconnectRecoveries", offlineReconnectRecoveries)
            put("maximumObservedRecordFreshnessMs", maximumFreshnessMs); put("measurements", JsonArray(measurements))
        }
        File(context.getExternalFilesDir(null), "native-campaign-https-acceptance.json").writeText(result.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nCAMPAIGN_HTTPS_ACCEPTANCE: $result\n") })
    }
}
