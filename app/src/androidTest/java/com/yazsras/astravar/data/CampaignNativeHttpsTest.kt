package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
    private val foreignHolder = "campaign-native-foreign-holder"
    private val foreignCampaign = "campaign-native-other-campaign"
    private val project = "campaign-native-project"
    private val stage = "campaign-native-stage"
    private val laterStage = "campaign-native-later-stage"
    private val source = "Confirmed isolated fictional native HTTPS campaign acceptance"
    private val allScopes = CampaignConnectionProtocol.categories
    private val measurements = mutableListOf<JsonObject>()
    private var mcpRequests = 0
    private var mcpErrors = 0
    private var expectedReceiptNotReadyPolls = 0
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
        ledger.records.saveProfile(CharacterProfile(name = "Fictional HTTPS owl", id = companion), source = source)
        ledger.records.saveCompanionState(CompanionState("Fictional HTTPS owl", companionId = companion), source = source)
        ledger.records.saveProfile(CharacterProfile(name = "Foreign fictional HTTPS holder", id = foreignHolder), source = source)
        db.partyDao().put(PartySheetRecord(foreignHolder, campaignJson.encodeToString(PartySheetData()), campaignId = foreignCampaign))
        assertFalse("The existing foreign holder is not enrolled in this campaign", CampaignSyncDomain(db).snapshot(campaign).any {
            it.kind == "character-profile" && it.recordId == foreignHolder
        })
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
    private fun rpc(name: String, arguments: JsonObject, allowReceiptPending: Boolean = false): JsonObject {
        val started = System.nanoTime()
        val envelope = owner("/mcp", buildJsonObject {
            put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", arguments) }
        })
        assertFalse("MCP JSON-RPC $name must be well-formed: $envelope", envelope.containsKey("error"))
        val result = envelope.getValue("result").jsonObject
        mcpRequests++
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
            if (allowReceiptPending && mcpErrorCode(result) == "RECEIPT_NOT_READY") expectedReceiptNotReadyPolls++ else mcpErrors++
        }
        measurements += buildJsonObject {
            put("operation", name); put("durationMs", (System.nanoTime() - started) / 1_000_000.0)
            put("responseBytes", result.toString().toByteArray().size)
        }
        return result
    }
    // MCP tool errors encode a string code; HTTP transport errors use a different envelope.
    private fun mcpErrorCode(response: JsonObject): String? = runCatching {
        Json.parseToJsonElement(response.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            .jsonObject.getValue("error").jsonPrimitive.content
    }.getOrNull()
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
            val beforeConnection = client.snapshot()
            control("/__ci/fault", buildJsonObject { put("offline", true) })
            val failure = runCatching { client.sync() }.exceptionOrNull()
            assertTrue("The synthetic 503 must remain an explicit transport failure", failure is OwnerSyncFailure)
            assertEquals("SERVICE_UNAVAILABLE", (failure as OwnerSyncFailure).code)
            assertEquals("Offline delivery cannot fabricate a local commit", before, CampaignSyncDomain(db).snapshot(campaign))
            client.close(); client = CampaignConnectionClient(context)
            val retained = client.snapshot()
            assertEquals("The first failed request is the grant read", "GRANT_READ", retained.getValue("failedStage").jsonPrimitive.content)
            assertEquals("Known temporary service failures remain actionable", "SERVICE_UNAVAILABLE", retained.getValue("failureCode").jsonPrimitive.content)
            assertEquals("A failed attempt must not refresh completion", beforeConnection.getValue("lastFullSyncAt"), retained.getValue("lastFullSyncAt"))
            assertEquals("Temporary unavailability preserves the owner grant", beforeConnection.getValue("grant"), retained.getValue("grant"))
            val pending = rpc("get_campaign_command_receipt", buildJsonObject {
                put("campaignId", campaign); put("commandId", id)
            }, allowReceiptPending = true)
            assertEquals("Queued server work is not a committed device receipt", "RECEIPT_NOT_READY", mcpErrorCode(pending))
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
        val nativeRows = CampaignSyncDomain(db).snapshot(campaign, mechanics = true, spellbook = true,
            loadout = true, actions = true, strikes = true)
        val local = CampaignConnectionProjection.project(nativeRows, allScopes)
        for (raw in receipt.getValue("records").jsonArray) {
            val saved = raw.jsonObject
            val kind = saved.getValue("kind").jsonPrimitive.content; val recordId = saved.getValue("recordId").jsonPrimitive.content
            assertEquals("Independent backend readback equals immutable receipt", saved, record(kind, recordId))
            val android = checkNotNull(local.singleOrNull { it.kind == kind && it.recordId == recordId }) {
                "Missing independent Android readback for $kind/$recordId"
            }
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

    /** A locally injected authenticated wake exercises the real named-store worker, HTTPS fetch,
     * Room commit and immutable receipt while screen-off. No Pushy delivery or Doze claim is made. */
    private suspend fun screenOffWorkerCommitsCheckpoint(): JsonObject {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val manager = WorkManager.getInstance(context)
        val preferences = CampaignSyncPreferences(context)
        val pausedBefore = preferences.paused
        val screenWasOn = device.isScreenOn
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val id = UUID.randomUUID().toString()
        val checkpointId = "campaign-native-screen-off-checkpoint"
        val started = SystemClock.elapsedRealtime()
        var observedFailure: Throwable? = null
        var validatedBeforeWake = buildJsonObject {}
        fun networkEvidence(): JsonObject {
            val active = connectivity.activeNetwork
            val capabilities = active?.let(connectivity::getNetworkCapabilities)
            return buildJsonObject {
                put("deviceAt", System.currentTimeMillis()); put("activeNetworkPresent", active != null)
                put("internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                put("notMetered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
                put("wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                put("cellular", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                put("ethernet", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                put("vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            }
        }
        fun workEvidence(name: String): JsonArray = runCatching {
            JsonArray(manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).takeLast(10).map { info ->
                buildJsonObject {
                    put("id", info.id.toString()); put("state", info.state.name); put("runAttemptCount", info.runAttemptCount)
                    put("tags", JsonArray(info.tags.sorted().map(::JsonPrimitive)))
                }
            })
        }.getOrElse { failure -> JsonArray(listOf(buildJsonObject { put("inspectionFailure", failure.javaClass.simpleName) })) }
        try {
            preferences.paused = false
            manager.cancelUniqueWork(CampaignConnectionSyncJob.WAKE).result.get(10, TimeUnit.SECONDS)
            manager.cancelUniqueWork(CampaignConnectionSyncJob.PERIODIC).result.get(10, TimeUnit.SECONDS)
            val previousWork = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
            val connection = client.snapshot()
            assertTrue("The fixture must retain standing continuity authorization", connection.getValue("localAutoSyncScopes").jsonArray.any { it.jsonPrimitive.content == "continuity" })
            val grant = connection.getValue("grant").jsonObject
            val credential = requireNotNull(OwnerSyncCredentialVault(context).credential(origin, campaign))
            val transaction = transactionBody(id, listOf(operation("save_checkpoint", "checkpoint", checkpointId, 0, buildJsonObject {
                put("title", "Confirmed fictional screen-off worker acceptance"); put("scene", "Isolated fixture workshop")
                put("lastConfirmedAction", "Recorded only this explicitly authorized fictional transport test")
            })))
            // The production CONNECTED constraint on API 26+ requires a genuinely validated
            // Internet network. The fixture must provide it; no constraint override is used.
            val validationDeadline = SystemClock.elapsedRealtime() + 30_000
            validatedBeforeWake = networkEvidence()
            while ((!validatedBeforeWake.getValue("internet").jsonPrimitive.boolean || !validatedBeforeWake.getValue("validated").jsonPrimitive.boolean) && SystemClock.elapsedRealtime() < validationDeadline) {
                delay(250); validatedBeforeWake = networkEvidence()
            }
            assertTrue("The emulator must supply actual INTERNET+VALIDATED capabilities before the production worker runs: $validatedBeforeWake",
                validatedBeforeWake.getValue("internet").jsonPrimitive.boolean && validatedBeforeWake.getValue("validated").jsonPrimitive.boolean)
            device.sleep()
            assertFalse("The command must be queued while the emulator screen is off", device.isScreenOn)
            val queued = mcp("submit_campaign_transaction", transaction)
            assertEquals("PENDING_DEVICE_COMMIT", queued.getValue("confirmation").jsonPrimitive.content)
            assertEquals("PENDING_DELIVERY", queued.getValue("command").jsonObject.getValue("status").jsonPrimitive.content)
            assertNull("Queue acceptance alone has not saved the checkpoint", db.partyDao().checkpoint(checkpointId))
            CampaignSyncPreferences(context).pushyEnabled = true
            val issuedAt = System.currentTimeMillis()
            val wake = linkedMapOf("type" to CampaignWakeProtocol.TYPE, "version" to "2", "issuedAt" to issuedAt.toString(),
                "expiresAt" to minOf(issuedAt + 30_000, credential.expiresAt, grant.getValue("expiresAt").jsonPrimitive.long).toString(),
                "wakeId" to UUID.randomUUID().toString())
            val signature = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(OwnerSyncProtocol.hash(credential.bearer).toByteArray(Charsets.UTF_8), "HmacSHA256"))
            }.doFinal(wake.values.joinToString("\n").toByteArray(Charsets.UTF_8))
            val signed = wake + ("signature" to Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            assertTrue("The production client accepts the injected authenticated wake", client.acceptWake(signed))
            assertFalse("The same injected wake is claimed once", client.acceptWake(signed))
            val wakeAt = CampaignPushStore(context).lastReceivedAt
            CampaignConnectionSyncJob.enqueue(context, wakeSequence = CampaignPushStore(context).lastWakeSequence)
            val deadline = SystemClock.elapsedRealtime() + 90_000
            val arguments = buildJsonObject { put("campaignId", campaign); put("commandId", id) }
            var receipt: JsonObject? = null
            while (receipt == null && SystemClock.elapsedRealtime() < deadline) {
                val response = rpc("get_campaign_command_receipt", arguments, allowReceiptPending = true)
                if (response.getValue("isError").jsonPrimitive.boolean) {
                    assertEquals("Only an uncommitted receipt may be pending; isolated fictional MCP response: $response", "RECEIPT_NOT_READY", mcpErrorCode(response))
                    delay(250)
                } else receipt = response.getValue("structuredContent").jsonObject.getValue("receipt").jsonObject
            }
            val saved = requireNotNull(receipt) { "Screen-off WorkManager did not produce a committed receipt within 90 seconds." }
            assertEquals("APPLIED", saved.getValue("status").jsonPrimitive.content)
            var worker = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).filter { it.id !in previousWork }
            while ((worker.isEmpty() || worker.any { !it.state.isFinished }) && SystemClock.elapsedRealtime() < deadline) {
                delay(100)
                worker = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).filter { it.id !in previousWork }
            }
            assertEquals("Exactly one production worker handles this injected wake", 1, worker.size)
            assertEquals("Wait for receipt readback and publication to finish before fixture cleanup", WorkInfo.State.SUCCEEDED, worker.single().state)
            assertFalse("The actual HTTPS/Room worker completed with the screen still off", device.isScreenOn)
            val committed = saved.getValue("records").jsonArray.single().jsonObject
            assertEquals("checkpoint", committed.getValue("kind").jsonPrimitive.content)
            assertEquals(checkpointId, committed.getValue("recordId").jsonPrimitive.content)
            assertEquals(1L, committed.getValue("revision").jsonPrimitive.long)
            assertEquals("The immutable receipt matches an independent MCP read", committed, record("checkpoint", checkpointId))
            val android = CampaignConnectionProjection.project(CampaignSyncDomain(db).snapshot(campaign, mechanics = true), allScopes)
                .single { it.kind == "checkpoint" && it.recordId == checkpointId }
            assertEquals("The worker committed the named Android Room store", campaignJson.encodeToJsonElement(android), committed)
            val timings = CampaignPushStore(context)
            assertTrue(timings.lastWorkStartedAt >= wakeAt)
            assertTrue(timings.lastRoomCommittedAt >= wakeAt)
            assertTrue("The production worker independently verified its receipt", timings.timings().any {
                val event = it.jsonObject
                event["stage"]?.jsonPrimitive?.content == "receipt_verified" && event["identity"]?.jsonPrimitive?.content == id
            })
            return buildJsonObject {
                put("screenOff", true); put("injectedAuthenticatedWake", true); put("actualPushyDelivery", false)
                put("validatedNetworkBeforeWake", validatedBeforeWake.getValue("validated")); put("networkBeforeWake", validatedBeforeWake)
                put("actualWorkManagerHttpsRoomCommit", true); put("workerSucceeded", true); put("receiptAndIndependentReadback", true)
                put("durationMs", SystemClock.elapsedRealtime() - started); put("recordCount", 1)
            }
        } catch (failure: Throwable) {
            observedFailure = failure
            throw failure
        } finally {
            // Preserve genuine scheduler/network observations before cancellation changes them.
            // No bearer, Pushy token, wake signature, command payload, IP address or SSID is retained.
            runCatching {
                val diagnostic = buildJsonObject {
                    put("syntheticOnly", true); put("beforeWorkerCancellation", true); put("failed", observedFailure != null)
                    put("failureType", observedFailure?.javaClass?.simpleName ?: "")
                    put("elapsedMs", SystemClock.elapsedRealtime() - started); put("deviceAt", System.currentTimeMillis())
                    put("screenOn", device.isScreenOn); put("automaticSyncPaused", preferences.paused)
                    put("networkBeforeWake", validatedBeforeWake)
                    put("networkNow", runCatching { networkEvidence() }.getOrElse { failure -> buildJsonObject { put("inspectionFailure", failure.javaClass.simpleName) } })
                    put("wakeWork", workEvidence(CampaignConnectionSyncJob.WAKE)); put("periodicWork", workEvidence(CampaignConnectionSyncJob.PERIODIC))
                    val timings = CampaignPushStore(context)
                    put("lastPushReceivedAt", timings.lastReceivedAt); put("lastWorkStartedAt", timings.lastWorkStartedAt)
                    put("lastRoomCommittedAt", timings.lastRoomCommittedAt); put("syncTimings", timings.timings())
                }
                File(context.getExternalFilesDir(null), "native-screen-off-worker-diagnostics.json").writeText(diagnostic.toString())
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nSCREEN_OFF_WORKER_DIAGNOSTICS: $diagnostic\n") })
            }
            try {
                manager.cancelUniqueWork(CampaignConnectionSyncJob.WAKE).result.get(10, TimeUnit.SECONDS)
                manager.cancelUniqueWork(CampaignConnectionSyncJob.PERIODIC).result.get(10, TimeUnit.SECONDS)
                val drainDeadline = SystemClock.elapsedRealtime() + 10_000
                while (manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).any { !it.state.isFinished } && SystemClock.elapsedRealtime() < drainDeadline) delay(50)
                assertTrue("The fixture must drain its worker before restoring HTTPS trust", manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
                // CANCELLED can precede CoroutineWorker's NonCancellable foreground cleanup.
                // This read-only shared-mutex barrier waits for that cleanup without syncing.
                withTimeout(15_000) { client.snapshot() }
            } finally {
                preferences.paused = pausedBefore
                if (screenWasOn) device.wakeUp() else device.sleep()
            }
        }
    }

    private suspend fun verifyEquipmentEffectOverHttps() {
        val authority = GenesisAuthorityRepository(db)
        authority.prepareOwnerLocalCampaign(commandId = "ci-equipment-campaign")
        val actorId = "ci-native-equipment-actor"
        val definition = ItemDefinition("ci-native-stone-definition", "Fictional test stone")
        val stone = ItemInstance("ci-native-stone", definition.id)
        val actor = authority.createActor("Fixture artificer", GenesisActorRole.PLAYER, campaign,
            playable = ReforgeCharacter(actorId, "Fixture artificer", level = 3, className = "Artificer", subclass = "Armorer",
                rules = RuleVersion(classVersion = "legacy-artificer-infusions-2020"), baseAbilities = mapOf(Stat.INT to 21),
                attacks = listOf(AttackDefinition("fixture-tap","Fictional tap",ability=Stat.INT,fixedAttackBonus=4,
                    addAbilityToDamage=false,damage=listOf(DamageComponent("tap","Tap",DamageKind.FORCE,DiceFormula(flat=2))))),
                spellSlots = mapOf(1 to 0, 2 to null)),
            commandId = "ci-equipment-actor")
        authority.createItem(actorId, requireNotNull(actor.playable).revision, definition, stone, "ci-equipment-grant")
        val sourcePage = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        val sourceUrl = "https://app.notion.com/p/$sourcePage"
        val spell = NotionSpellFact(sourcePage, sourceUrl, "fixture-r1", "guiding-glint", "Confirmed",
            "Fictional Guiding Glint", 1, SpellPreparation.ALWAYS_PREPARED, true, "1 Action", "30 ft.", "V", "1 minute", false)
        val native = requireNotNull(ReforgeRepository(db).get(actorId))
        val transfer = NotionCharacterTransfer(NOTION_CHARACTER_TRANSFER_FORMAT, "https-fixture-spells", "2026-11-21",
            NotionCharacterFacts(native.character.name, "Fixture", sourceUrl, 3, "Gnome", "Artificer", "Armorer",
                Stat.entries.associateWith { 10 }, targetProfileId = actorId), emptyList(), emptyList(), null,
            reviewedSpellbook = NotionSpellbook(native.character.rules.classVersion, 3, listOf(spell), spell.nativeId))
        val savedSpell = NotionTransferRepository(db).commit(native, transfer,
            NotionTransferChoices(stats = false, items = false, spells = true), false,
            GenesisAuthorityBackupRepository(db).exportWithProof().currentFingerprint, "https-reviewed-spell-import")
        assertEquals(1, savedSpell.spellsAdded)
        client.sync()
        val arguments = buildJsonObject { put("campaignId", campaign); put("profileId", actorId); put("limit", 100) }
        val spellRows = mcp("get_playable_spells", arguments).getValue("records").jsonArray.map { it.jsonObject }
        val published = spellRows.single { it.getValue("fields").jsonObject["subject"] == JsonPrimitive("SPELL") }
        val fresh = record("playable-spell", published.getValue("recordId").jsonPrimitive.content)
        val spellFields = fresh.getValue("fields").jsonObject
        assertEquals(savedSpell.profile.revision, fresh.getValue("revision").jsonPrimitive.long)
        assertEquals(campaignJson.encodeToJsonElement(spell.definition()), spellFields.getValue("data"))
        val casting = spellRows.single { it.getValue("fields").jsonObject["subject"] == JsonPrimitive("CASTING") }
            .getValue("fields").jsonObject.getValue("data").jsonObject
        val slots = casting.getValue("slots").jsonArray.map { it.jsonObject }.associateBy { it.getValue("level").jsonPrimitive.int }
        assertEquals(JsonPrimitive(0), slots.getValue(1)["current"])
        assertEquals(JsonNull, slots.getValue(2)["current"])
        assertEquals(JsonNull, casting["concentrationSpellId"])
        fun intelligence(): JsonObject = mcp("get_playable_statistics", arguments).getValue("records").jsonArray
            .map { it.jsonObject.getValue("fields").jsonObject }.single { it.getValue("target").jsonPrimitive.content == "ABILITY" && it.getValue("key").jsonPrimitive.content == "INT" }
        suspend fun edit(type: String, change: JsonObject) {
            val equipment = record("playable-equipment", stone.id)
            val actorRevision = intelligence().getValue("revision")
            submitAndVerify(operation(type, "playable-equipment", stone.id, equipment.getValue("revision").jsonPrimitive.long,
                JsonObject(change + ("actorRevision" to actorRevision))))
        }
        fun verifyInt(effective: Int) {
            val read = intelligence()
            assertEquals(21, read.getValue("base").jsonPrimitive.int)
            assertEquals(effective, read.getValue("effective").jsonPrimitive.int)
        }
        verifyInt(21)
        edit("edit_equipment_bonus", buildJsonObject {
            putJsonObject("bonus") {
                put("id", "5b1731bc-c1c1-455a-bf15-ef145af7d061"); put("label", "Synthetic Intellect")
                put("target", "ABILITY"); put("key", "INT"); put("amount", 1); put("enabled", true)
                put("stackingKey", JsonNull); put("expiresAtGameSeconds", JsonNull)
            }
        })
        verifyInt(21)
        edit("set_equipment_state", buildJsonObject { put("state", "HELD") })
        verifyInt(22)
        val loadout = mcp("get_playable_loadout", arguments).getValue("records").jsonArray
        val held = loadout.map { it.jsonObject.getValue("fields").jsonObject }
            .single { it["subject"] == JsonPrimitive("ITEM") }
        val itemData = record("playable-loadout", held.getValue("id").jsonPrimitive.content)
            .getValue("fields").jsonObject.getValue("data").jsonObject
        assertEquals(stone.id, itemData.getValue("instance").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), itemData.getValue("effectStatuses").jsonArray.single().jsonObject["active"])
        edit("edit_equipment_defense", buildJsonObject {
            putJsonObject("defense") {
                put("id", "edf27ed3-d934-4b12-ad26-ad5fcfe84537"); put("label", "Synthetic cold ward")
                put("damageType", "COLD"); put("kind", "IMMUNITY"); put("nonmagicalOnly", false)
                put("enabled", true); put("expiresAtGameSeconds", JsonNull)
            }
        })
        fun coldImmunity() = mcp("get_playable_statistics", arguments).getValue("records").jsonArray.any {
            val f = it.jsonObject.getValue("fields").jsonObject
            f.getValue("target").jsonPrimitive.content == "DAMAGE_DEFENSE" && f.getValue("key").jsonPrimitive.content == "COLD:IMMUNITY:ALL"
        }
        assertTrue(coldImmunity())
        edit("set_equipment_state", buildJsonObject { put("operating", false) })
        verifyInt(21)
        assertFalse(coldImmunity())

        val owlId = "ci-native-equipment-owl"
        authority.createActor("Fixture owl", GenesisActorRole.COMPANION, campaign, actorId, ReforgeCharacter(
            owlId, "Fixture owl", level = 9, baseAc=12, temporaryHp=0, baseAbilities = mapOf(Stat.INT to 8), baseMaxHp = 12, currentHp = 5,
            reviewedProficiency = ReviewedProficiency(3, RuleSource("fixture:independent-pb", "Reviewed creature tier", Acceptance.ACCEPTED_HOMEBREW)),
            resources = listOf(ResourcePool("repair", "Repair", 1, 0))), "ci-equipment-owl")
        client.sync()
        suspend fun transferTo(holder: String) {
            val rev = record("item", stone.id).getValue("revision").jsonPrimitive.long
            val currentPlacement = record("item-placement", stone.id)
            val placement = currentPlacement.getValue("revision")
            val sender = currentPlacement.getValue("fields").jsonObject.getValue("holderProfileId").jsonPrimitive.content
            fun actorRevision(id: String) = mcp("get_playable_statistics", JsonObject(arguments + ("profileId" to JsonPrimitive(id))))
                .getValue("records").jsonArray.first().jsonObject.getValue("revision")
            val senderRevision = actorRevision(sender)
            val recipientRevision = actorRevision(holder)
            val receipt = submitAndVerify(operation("move_item", "item", stone.id, rev, buildJsonObject {
                put("expectedPlacementRevision", placement); put("holderProfileId", holder); put("state", "Carried")
                putJsonObject("playableTransfer") {
                    put("version", 1); put("fromId", sender); put("fromRevision", senderRevision); put("toRevision", recipientRevision)
                }
            }))
            assertEquals(2, receipt.getValue("records").jsonArray.size)
            assertEquals(holder, record("playable-equipment", stone.id).getValue("fields").jsonObject.getValue("profileId").jsonPrimitive.content)
        }
        fun owlInt() = mcp("get_playable_statistics", JsonObject(arguments + ("profileId" to JsonPrimitive(owlId))))
            .getValue("records").jsonArray.map { it.jsonObject.getValue("fields").jsonObject }.single {
                it["target"] == JsonPrimitive("ABILITY") && it["key"] == JsonPrimitive("INT")
            }
        suspend fun owlEdit(change: JsonObject) {
            val rev = record("playable-equipment", stone.id).getValue("revision").jsonPrimitive.long
            submitAndVerify(operation("set_equipment_state", "playable-equipment", stone.id, rev,
                JsonObject(change + mapOf("actorRole" to JsonPrimitive("COMPANION"), "actorRevision" to owlInt().getValue("revision")))))
        }
        val owlStatistics = mcp("get_playable_statistics", JsonObject(arguments + ("profileId" to JsonPrimitive(owlId))))
        val pb = owlStatistics.getValue("records").jsonArray.map { it.jsonObject.getValue("fields").jsonObject }
            .single { it["key"] == JsonPrimitive("PROFICIENCY") }
        assertEquals(3, pb.getValue("effective").jsonPrimitive.int)
        assertEquals("fixture:independent-pb", pb.getValue("sourceId").jsonPrimitive.content)
        transferTo(owlId)
        if (!record("playable-equipment", stone.id).getValue("fields").jsonObject.getValue("operating").jsonPrimitive.boolean)
            owlEdit(buildJsonObject { put("operating", true) })
        owlEdit(buildJsonObject { put("state", "HELD") })
        assertEquals(9, owlInt().getValue("effective").jsonPrimitive.int)
        transferTo(actorId)
        assertEquals(8, owlInt().getValue("effective").jsonPrimitive.int)
        assertEquals(8, owlInt().getValue("base").jsonPrimitive.int)
        val owl = requireNotNull(ReforgeRepository(db).get(owlId)).character
        assertEquals(5, owl.currentHp)
        assertEquals(0, owl.resources.single().current)
        assertTrue(owl.items.isEmpty())
        verifyInt(21)

        suspend fun actorAction(type: String, input: JsonObject) {
            val actorRevision = record("playable-action", actorId).getValue("revision").jsonPrimitive.long
            submitAndVerify(operation(type, "playable-action", actorId, actorRevision,
                JsonObject(input + ("actorRole" to JsonPrimitive("PLAYER")))))
            val committed = record("playable-action", actorId).getValue("fields").jsonObject
            assertEquals(JsonPrimitive(type), committed.getValue("action").jsonObject["type"])
        }
        fun castingState() = mcp("get_playable_spells", arguments).getValue("records").jsonArray
            .map { it.jsonObject.getValue("fields").jsonObject }.single { it["subject"] == JsonPrimitive("CASTING") }
            .getValue("data").jsonObject
        fun available(state: JsonObject, level: Int) = state.getValue("slots").jsonArray.map { it.jsonObject }
            .single { it["level"] == JsonPrimitive(level) }.getValue("current")
        val newSpellId = "ci-authored-light"
        actorAction("define_spell", buildJsonObject {
            put("mode","CREATE"); put("spellId",newSpellId)
            putJsonObject("definition") { put("name","Fixture light"); put("level",0); put("sourceVersion","Fixture study") }
        })
        actorAction("define_spell", buildJsonObject {
            put("mode","EDIT"); put("spellId",newSpellId)
            putJsonObject("definition") { put("duration","One minute") }
        })
        val authored = requireNotNull(ReforgeRepository(db).get(actorId)).character.spells.single { it.id == newSpellId }
        assertEquals("Fixture study",authored.sourceVersion)
        val definitions = mcp("get_playable_spells",arguments).getValue("records").jsonArray.map { it.jsonObject.getValue("fields").jsonObject }
        val authoredRow = definitions.single { it["subject"] == JsonPrimitive("SPELL") &&
            it["data"]?.jsonObject?.get("id") == JsonPrimitive(newSpellId) }
        assertEquals(campaignJson.encodeToJsonElement(authored),authoredRow["data"])
        actorAction("complete_rest", buildJsonObject {
            put("confirmed",true); put("elapsedSeconds",28800); put("kind","LONG_REST")
        })
        val rested = castingState()
        val restoredSlots = available(rested,1).jsonPrimitive.int
        assertTrue(restoredSlots > 0)
        assertEquals(JsonNull,available(rested,2))
        actorAction("cast_spell", buildJsonObject {
            put("spellId",spell.nativeId); put("slotLevel",1); put("ritual",false); put("replaceConcentration",false)
        })
        val cast = castingState()
        assertEquals(JsonPrimitive(restoredSlots - 1),available(cast,1))
        assertEquals(JsonPrimitive(spell.nativeId),cast["concentrationSpellId"])

        val encounterRepo = authority.encounters
        val draft = encounterRepo.saveDraft("ci-https-encounter",null,campaign,"Fixture encounter","",listOf(
            GenesisEncounterParticipantInput(actorId,requireNotNull(authority.actor(actorId)).revision,18),
            GenesisEncounterParticipantInput(owlId,requireNotNull(authority.actor(owlId)).revision,5)),"ci-strike-draft")
        encounterRepo.start(encounterRepo.reviewStart(draft.record.id,draft.record.revision),"ci-strike-start")
        client.sync()
        fun activeEncounter() = mcp("get_playable_loadout",arguments).getValue("records").jsonArray.map { it.jsonObject }
            .single { it.getValue("fields").jsonObject["subject"] == JsonPrimitive("ENCOUNTER") }.getValue("fields").jsonObject.getValue("data").jsonObject
        val active = activeEncounter()
        actorAction("resolve_encounter_attack", buildJsonObject {
            put("targetRole","COMPANION"); put("targetId",owlId); put("targetRevision",record("playable-action",owlId).getValue("revision"))
            put("encounterId",draft.record.id); put("encounterRevision",active.getValue("encounterRevision"))
            put("attackId","fixture-tap"); put("naturalD20",15); putJsonObject("rolledDamage") { put("tap",2) }; put("finishTurn",true)
        })
        assertEquals(JsonPrimitive(3),record("character-profile",owlId).getValue("fields").jsonObject["currentHp"])
        assertEquals(JsonPrimitive(owlId),activeEncounter()["currentActorId"])
        verifyReviewedActorOverHttps(actorId)

    }

    private suspend fun verifyReviewedActorOverHttps(anchor: String) {
        val anchorRevision = record("playable-action",anchor).getValue("revision").jsonPrimitive.long
        val actorId="ci-reviewed-companion"
        val evidence=NativeSourceSync.Evidence("fixture","reviewed-moth","https://example.invalid/moth","1")
        val profile=buildJsonObject {
            put("name","Fictional moth");put("level",1);put("baseAc",12);put("baseMaxHp",9);put("currentHp",4)
            put("rules",campaignJson.encodeToJsonElement(RuleVersion(classVersion="Independent creature")))
        }
        val actor=NativeSourceSync.Request(1,"MIGRATION","CREATE",evidence,anchor,anchorRevision,actorId,
            GenesisActorRole.COMPANION,0,ownerId=anchor,profile=profile)
        val effect=EffectDefinition("fixture-ward-ac","Worn protection",RuleSource("fixture","Reviewed ward",Acceptance.ACCEPTED_HOMEBREW),
            EffectPayload.Number(NumericTarget.ARMOR_CLASS,NumberOperation.ADD,1),listOf(EffectCondition(ConditionKind.WORN)))
        val definition=ItemDefinition("ci-ward","Fictional ward",listOf(effect),true)
        val item=actor.copy(evidence=evidence.copy(externalId="ward",url="https://example.invalid/ward"),actorRevision=1,
            profile=null,ownerId=null,definition=definition,instance=ItemInstance("ci-ward-instance",definition.id,ItemState.WORN,true))
        val ops=listOf(operation(NativeSourceSync.ACTOR,"reference","ci-moth-source",0,Json.encodeToJsonElement(actor).jsonObject),
            operation(NativeSourceSync.ITEM,"reference","ci-ward-source",0,Json.encodeToJsonElement(item).jsonObject))
        val id=UUID.randomUUID().toString()
        val body=transactionBody(id,ops)
        val queued=mcp("submit_campaign_transaction",body).getValue("command").jsonObject
        assertEquals("PENDING_APPROVAL",queued.getValue("status").jsonPrimitive.content)
        client.sync()
        val pending=client.snapshot().getValue("pending").jsonArray.map { it.jsonObject }.single { it["commandId"]==JsonPrimitive(id) }
        val preview=pending.getValue("operations").jsonArray.first().jsonObject.getValue("nativePreview").jsonObject
        assertTrue("Android preview must be valid: $preview",preview.getValue("valid").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(4),preview.getValue(actorId).jsonObject["currentHp"])
        assertNull(db.genesisAuthorityDao().actor(actorId))
        client.decide(id,true)
        client.sync()
        val args=buildJsonObject { put("campaignId",campaign);put("commandId",id) }
        val receipt=mcp("get_campaign_command_receipt",args).getValue("receipt").jsonObject
        assertEquals("APPLIED",receipt.getValue("status").jsonPrimitive.content)
        val saved=record("character-profile",actorId).getValue("fields").jsonObject
        assertEquals(JsonPrimitive(13),saved["armorClass"]);assertEquals(JsonPrimitive(4),saved["currentHp"])
        assertEquals(JsonPrimitive(actorId),record("item-placement","ci-ward-instance").getValue("fields").jsonObject["holderProfileId"])
        assertTrue(record("playable-equipment","ci-ward-instance").getValue("fields").jsonObject.getValue("attuned").jsonPrimitive.boolean)
        mcp("submit_campaign_transaction",body)
        assertEquals(receipt,mcp("get_campaign_command_receipt",args).getValue("receipt").jsonObject)
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
        val publication = mcp("get_owner_connection", buildJsonObject {}).getValue("campaignSync").jsonObject
            .getValue("connections").jsonArray.single { it.jsonObject.getValue("campaignId").jsonPrimitive.content == campaign }.jsonObject
        val build = publication.getValue("deviceBuild").jsonObject
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val installedDigest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.File(context.applicationInfo.sourceDir).inputStream().use { input ->
            java.security.DigestInputStream(input, installedDigest).use { stream ->
                val buffer = ByteArray(65536)
                while (stream.read(buffer) != -1) { /* hash the independently installed package */ }
            }
        }
        assertEquals(installedDigest.digest().joinToString("") { "%02x".format(it) }, build.getValue("baseApkSha256").jsonPrimitive.content)
        assertEquals(context.packageName, build.getValue("packageName").jsonPrimitive.content)
        assertEquals(info.versionName, build.getValue("versionName").jsonPrimitive.content)
        assertEquals(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info), build.getValue("versionCode").jsonPrimitive.long)
        assertEquals(android.os.Build.VERSION.SDK_INT, build.getValue("androidApi").jsonPrimitive.int)
        assertEquals(context.applicationInfo.splitSourceDirs?.size ?: 0, build.getValue("splitApkCount").jsonPrimitive.int)
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

        // One MCP transaction both acquires and assigns a new physical item to the
        // companion's independently published existing profile; no follow-up move.
        val assignedItem = "campaign-native-assigned-lens"
        val holderRevision = record("character-profile", companion).getValue("revision").jsonPrimitive.long
        fun assignedDraft(holderId: String, expectedHolderRevision: Long) = buildJsonObject {
            put("title", "Confirmed fictional HTTPS owl lens"); put("quantity", 1); put("evidenceStatus", "Confirmed")
            putJsonObject("placement") {
                put("holderProfileId", holderId); put("expectedHolderRevision", expectedHolderRevision); put("state", "Equipped")
            }
        }
        val assignedReceipt = submitAndVerify(operation("acquire_item", "item", assignedItem, 0, assignedDraft(companion, holderRevision)))
        val assignedRecords = assignedReceipt.getValue("records").jsonArray.map { it.jsonObject }
        assertEquals("One transaction receipt includes the physical item and its initial placement", 2, assignedRecords.size)
        assertEquals(setOf("item", "item-placement"), assignedRecords.map { it.getValue("kind").jsonPrimitive.content }.toSet())
        assertTrue(assignedRecords.all { it.getValue("recordId").jsonPrimitive.content == assignedItem && it.getValue("revision").jsonPrimitive.long == 1L })
        assertEquals("Replay preserves one physical identity", 1, db.ledgerDao().allItems().count { it.id == assignedItem })
        assertEquals(1L, db.ledgerDao().getItem(assignedItem)!!.revision)
        assertEquals(1, db.ledgerDao().getItem(assignedItem)!!.quantity)
        assertEquals("Fictional HTTPS owl", db.ledgerDao().getItem(assignedItem)!!.owner)
        assertEquals("Actor:$companion", db.ledgerDao().getItem(assignedItem)!!.location)
        assertEquals("Equipped", db.ledgerDao().getItem(assignedItem)!!.itemState)
        assertEquals(companion, db.partyDao().placement(assignedItem)!!.holderProfileId)
        assertEquals(1L, db.partyDao().placement(assignedItem)!!.revision)
        for ((rejectedId, holderId, revision) in listOf(
            Triple("campaign-native-stale-holder-lens", companion, holderRevision + 1),
            Triple("campaign-native-foreign-holder-lens", foreignHolder, db.recordsDao().characterProfile(foreignHolder)!!.revision)
        )) {
            assertTrue("Stale or foreign holder assignment must fail through authenticated MCP", rpc("submit_campaign_transaction",
                transactionBody(UUID.randomUUID().toString(), listOf(operation("acquire_item", "item", rejectedId, 0, assignedDraft(holderId, revision)))))
                .getValue("isError").jsonPrimitive.boolean)
            client.sync()
            assertNull("Rejected acquisition creates no physical item", db.ledgerDao().getItem(rejectedId))
            assertNull("Rejected acquisition creates no placement", db.partyDao().placement(rejectedId))
        }
        assertEquals("The foreign holder's original campaign is preserved", foreignCampaign, db.partyDao().sheet(foreignHolder)!!.campaignId)
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
        verifyEquipmentEffectOverHttps()
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
        val screenOffWorker = screenOffWorkerCommitsCheckpoint()
        val revoked = client.revoke()
        assertFalse(revoked.getValue("connected").jsonPrimitive.boolean)
        assertFalse("Server revocation must be confirmed, not only disabled locally", revoked.getValue("revokePending").jsonPrimitive.boolean)
        assertTrue(revoked.getValue("status").jsonPrimitive.content.contains("confirmed"))
        assertEquals("Only deliberate stale-revision, stale/foreign holder and independently revoked read/write requests fail", 5, mcpErrors)
        val result = buildJsonObject {
            put("syntheticOnly", true); put("androidEmulator", true); put("productionWorkerHttps", true)
            put("hostedChatGpt", false); put("physicalGalaxy", false)
            put("hp", 8); put("gold", 6); put("spellSlots", 1); put("companionCharges", 1)
            put("atomicPurchase", true); put("atomicCompanionAcquireAndAssign", true); put("acquisitionReceiptRecords", assignedRecords.size)
            put("staleAndForeignAcquisitionHolderRejected", true); put("idempotentRetries", true); put("staleAndDisabledScopeRejected", true)
            put("nativeImportedSpellbookAndSpentSlotReadback", true)
            put("nativeEquipmentReceiptAndCalculatedReadback", true)
            put("companionTransferAndReturnWithDerivedReadbacks", true)
            put("receiptAndAndroidReadback", true); put("serverRevocationVerified", true)
            put("mcpRequests", mcpRequests); put("observedMcpErrors", mcpErrors); put("expectedNegativeTestErrors", 5)
            put("independentReadWriteRevocation", true)
            put("observedErrorRateIncludingExpectedNegativeTests", mcpErrors.toDouble() / mcpRequests)
            put("deliberateIdempotentRetryCommands", deliberateRetryCommands); put("lostDecisionResponseRetries", lostResponseRetries)
            put("offlineReconnectRecoveries", offlineReconnectRecoveries); put("screenOffWorker", screenOffWorker)
            put("expectedReceiptNotReadyPolls", expectedReceiptNotReadyPolls)
            put("maximumObservedRecordFreshnessMs", maximumFreshnessMs); put("measurements", JsonArray(measurements))
        }
        File(context.getExternalFilesDir(null), "native-campaign-https-acceptance.json").writeText(result.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nCAMPAIGN_HTTPS_ACCEPTANCE: $result\n") })
    }
}
