package com.yazsras.astravar.data

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.yazsras.astravar.ui.LiveHpConnectionPage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
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
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** API36 emulator integration, real TLS/AndroidKeyStore/Room/native Compose.
 * The localhost-only gateway is a test Sites boundary, not ChatGPT itself.
 * No production server, owner credentials or physical handset are accessed.
 */
@RunWith(AndroidJUnit4::class)
class LiveHpNativeHttpsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var config: JsonObject
    private lateinit var originalSsl: SSLSocketFactory
    private lateinit var client: LiveHpConnectionClient
    private lateinit var campaignDb: CampaignLedgerDb
    private val origin = "https://hp-bridge.example"
    private val profileId = "hp-native-player"
    private val campaignId get() = "astravar-live-${OwnerSyncProtocol.hash(profileId).take(32)}"
    private val otherId = "hp-native-unrelated"
    private val command1 = "08bd59ae-c6db-441c-80a7-22e444449001"
    private val rejected = "08bd59ae-c6db-441c-80a7-22e444449002"
    private val offline = "08bd59ae-c6db-441c-80a7-22e444449003"

    @Before fun isolatedEmulatorTls() {
        context = ApplicationProvider.getApplicationContext()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("No physical device is permitted", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        config = Json.parseToJsonElement(File(context.filesDir, "ci-https-config.json").readText()).jsonObject
        assertEquals(origin, config.getValue("origin").jsonPrimitive.content)
        assertEquals(profileId, config.getValue("profileId").jsonPrimitive.content)
        originalSsl = HttpsURLConnection.getDefaultSSLSocketFactory()
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            config.getValue("certificatePem").jsonPrimitive.content.byteInputStream())
        anchors.setCertificateEntry("ephemeral-native-ci-only", certificate)
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trusts.trustManagers, null) }
        // Process-local test trust only. Production transport and hostname verification remain unchanged.
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        client = LiveHpConnectionClient(context)
        campaignDb = CampaignLedgerDb.open(context)
        assertTrue(request("/__ci/health").getValue("syntheticOnly").jsonPrimitive.boolean)
    }

    @After fun restoreProcessTrust() {
        if (::client.isInitialized) client.close()
        if (::campaignDb.isInitialized) campaignDb.close()
        if (::originalSsl.isInitialized) HttpsURLConnection.setDefaultSSLSocketFactory(originalSsl)
    }

    private fun request(path: String, body: JsonObject = buildJsonObject {}): JsonObject {
        require(path.startsWith("/__ci/"))
        val connection = URL(origin + path).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Astravar-Ci-Control", config.getValue("controlToken").jsonPrimitive.content)
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            assertEquals("Isolated CI gateway request", 200, connection.responseCode)
            return Json.parseToJsonElement(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        } finally { connection.disconnect() }
    }
    private fun owner(path: String, body: JsonObject = buildJsonObject {}, method: String = "POST") = request("/__ci/owner", buildJsonObject {
        put("path", path); put("method", method); put("body", body)
    })
    private fun rpc(name: String, arguments: JsonObject) = owner("/mcp", buildJsonObject {
        put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", "tools/call")
        put("params", buildJsonObject { put("name", name); put("arguments", arguments) })
    }).getValue("result").jsonObject
    private fun mcp(name: String, arguments: JsonObject): JsonObject {
        val result = rpc(name, arguments)
        assertFalse("Isolated MCP protocol operation succeeded", result.getValue("isError").jsonPrimitive.boolean)
        return result.getValue("structuredContent").jsonObject
    }
    private fun mcpError(name: String, arguments: JsonObject, code: String? = null) {
        val result = rpc(name, arguments)
        assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        val error = Json.parseToJsonElement(result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content).jsonObject
        if (code != null) assertEquals(code, error.getValue("error").jsonPrimitive.content)
    }
    private fun proposal(id: String, revision: Long, hp: Int, adjustment: Int) = buildJsonObject {
        put("campaignId", campaignId); put("profileId", profileId); put("commandId", id)
        put("expectedRevision", revision); put("expectedCurrentHp", hp); put("adjustment", adjustment)
        put("source", "Explicit fictional native HTTPS acceptance")
    }
    private fun remote() = mcp("get_character_hp", buildJsonObject { put("campaignId", campaignId); put("profileId", profileId) })
    private fun receipt(id: String) = mcp("get_hp_command_receipt", buildJsonObject { put("campaignId", campaignId); put("commandId", id) }).getValue("receipt").jsonObject
    private suspend fun current() = requireNotNull(campaignDb.recordsDao().characterProfile(profileId))
    private suspend fun invariant(hp: Int, revision: Long) {
        val profile = current()
        assertEquals(hp, profile.currentHp); assertEquals(revision, profile.revision)
        assertEquals(66, profile.maximumHp); assertEquals(7, profile.temporaryHp)
        assertEquals(19, profile.armorClass); assertEquals(9, profile.level)
        val unrelated = requireNotNull(campaignDb.recordsDao().characterProfile(otherId))
        assertEquals(11, unrelated.currentHp); assertEquals(1L, unrelated.revision)
        assertEquals("CI fictional unrelated record", unrelated.source)
        assertEquals(20, campaignDb.recordsDao().characterAbility(profileId, "INT")!!.score)
    }
    private fun assertRemote(hp: Int, revision: Long) {
        val record = remote()
        assertEquals(revision, record.getValue("revision").jsonPrimitive.long)
        val fields = record.getValue("fields").jsonObject
        assertEquals(hp, fields.getValue("currentHp").jsonPrimitive.int)
        assertEquals(66, fields.getValue("maximumHp").jsonPrimitive.int)
        assertEquals(7, fields.getValue("temporaryHp").jsonPrimitive.int)
    }
    private fun displayPage() { compose.setContent { LiveHpConnectionPage() } }
    private fun waitFor(label: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }
    private fun tap(label: String) {
        waitFor(label)
        val button = hasText(label) and hasClickAction()
        compose.onAllNodes(button).onFirst().performScrollTo()
        compose.waitUntil(30_000) { compose.onAllNodes(button and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val enabled = compose.onAllNodes(button and isEnabled()).onFirst()
        enabled.assertIsEnabled()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nUI_ACTION: $label · clickable and enabled after scrolling\n")
        })
        enabled.performClick()
    }
    private fun nativeTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        try { body() } catch (failure: Throwable) {
            runCatching {
                // This isolated test contains only the explicitly seeded fictional profiles.
                File(context.getExternalFilesDir(null), "failure-semantics.txt")
                    .writeText(compose.onRoot().printToString())
            }
            withContext(NonCancellable) { runCatching {
                val snapshot = withTimeout(5_000) { client.snapshot() }
                val safe = buildJsonObject {
                    put("fictionalOnly", true)
                    listOf("connected", "status").forEach { key -> snapshot[key]?.let { put(key, it) } }
                    (snapshot["current"] as? JsonObject)?.let { current -> put("current", buildJsonObject {
                        listOf("currentHp", "maximumHp", "temporaryHp", "revision").forEach { key -> current[key]?.let { put(key, it) } }
                    }) }
                    put("pending", buildJsonArray { (snapshot["pending"] as? JsonArray)?.forEach { element ->
                        val pending = element.jsonObject
                        add(buildJsonObject { listOf("state", "currentHp", "proposedHp", "expectedRevision", "currentRevision").forEach { key -> pending[key]?.let { put(key, it) } } })
                    } })
                }
                File(context.getExternalFilesDir(null), "failure-state.json").writeText(safe.toString())
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nFAILURE_STATE: $safe\n") })
            } }
            throw failure
        }
    }
    private fun checkpoint(name: String, hp: Int, revision: Long) {
        val json = buildJsonObject { put("syntheticOnly", true); put("emulatorApi", android.os.Build.VERSION.SDK_INT)
            put("profileId", profileId); put("checkpoint", name); put("currentHp", hp); put("revision", revision)
            put("physicalGalaxy", false); put("actualChatGptHost", false) }
        File(context.getExternalFilesDir(null), "native-hp-$name.json").writeText(json.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: $name · fictional HP $hp revision $revision\n") })
    }
    private suspend fun pair() {
        val pairing = client.beginPairing(origin, profileId, "CI fictional emulator")
        owner("/v1/owner/pairings/approve", buildJsonObject {
            put("userCode", pairing.getValue("pairing").jsonObject.getValue("userCode"))
        })
        assertTrue(client.finishPairing().getValue("connected").jsonPrimitive.boolean)
        client.sync()
    }

    @Test fun aNativeKeystorePublicationApprovalRejectionAndOfflineIntent() = nativeTest {
        invariant(38, 1)
        val wrongHost = URL("https://wrong-hp-bridge.example/__ci/health").openConnection() as HttpsURLConnection
        wrongHost.connectTimeout = 10_000; wrongHost.readTimeout = 10_000
        val rejectedHost = try { runCatching { wrongHost.responseCode }.exceptionOrNull() } finally { wrongHost.disconnect() }
        assertTrue("Matching certificate hostname is mandatory even with the isolated test CA", rejectedHost is SSLHandshakeException || rejectedHost is SSLPeerUnverifiedException)
        val url = URI(client.startServiceAuthorization(origin, profileId))
        val fragment = url.rawFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val bundle = owner("/owner/bootstrap/bundle", buildJsonObject {
            put("origin", origin); fragment.forEach { (key, value) -> put(key, value) }
        })
        val challenge = context.getSharedPreferences("astravar-owner-bootstrap", Context.MODE_PRIVATE)
        val importNow = System.currentTimeMillis()
        val createdAt = challenge.getLong("createdAt", 0)
        val expiresAt = bundle.getValue("expiresAt").jsonPrimitive.long
        val bindings = buildJsonObject {
            put("fictionalOnly", true)
            put("originMatches", bundle.getValue("origin").jsonPrimitive.content == origin && challenge.getString("origin", null) == origin)
            put("campaignMatches", bundle.getValue("campaignId").jsonPrimitive.content == campaignId && challenge.getString("campaign", null) == campaignId)
            put("nonceMatches", challenge.getString("nonce", null) == bundle.getValue("nonce").jsonPrimitive.content && fragment["nonce"] == bundle.getValue("nonce").jsonPrimitive.content)
            put("challengeAgeMs", importNow - createdAt)
            put("remainingTtlMs", expiresAt - importNow)
            put("serverIssuedMinusDeviceNowMs", expiresAt - 600_000L - importNow)
            put("localWindowValid", createdAt > 0 && importNow >= createdAt && importNow - createdAt <= 600_000L)
            put("serverWindowValid", expiresAt > importNow && expiresAt <= importNow + 600_000L)
        }
        File(context.getExternalFilesDir(null), "bootstrap-bindings.json").writeText(bindings.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nBOOTSTRAP_BINDINGS: $bindings\n") })
        for (binding in listOf("originMatches", "campaignMatches", "nonceMatches", "localWindowValid", "serverWindowValid")) {
            assertTrue("Nonsecret bootstrap diagnostic $binding must hold before decryption", bindings.getValue(binding).jsonPrimitive.boolean)
        }
        val encrypted = bundle.getValue("ciphertext").jsonPrimitive.content
        val tampered = (if (encrypted.first() == 'A') "B" else "A") + encrypted.drop(1)
        assertTrue(runCatching { client.importServiceAuthorization(JsonObject(bundle + ("ciphertext" to JsonPrimitive(tampered))).toString().toByteArray()) }.isFailure)
        client.importServiceAuthorization(bundle.toString().toByteArray())
        assertTrue(runCatching { client.importServiceAuthorization(bundle.toString().toByteArray()) }.isFailure)
        checkpoint("native-bootstrap-imported", 38, 1)
        pair(); assertRemote(38, 1)
        mcp("propose_hp_adjustment", proposal(command1, 1, 38, -7))
        client.sync(); invariant(38, 1); assertRemote(38, 1)
        displayPage(); waitFor("Current HP: 38"); waitFor("Proposed HP: 31")
        val crossInstanceObservation = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(30_000) {
                campaignDb.recordsDao().characterProfiles().first { rows -> rows.any { it.id == profileId && it.revision == 2L } }
                    .single { it.id == profileId }
            }
        }
        tap("Approve HP change")
        compose.waitUntil(30_000) { runBlocking { current().revision == 2L } }
        assertEquals("A collector on another production-open Room instance receives the native approval", 31, crossInstanceObservation.await().currentHp)
        client.sync(); invariant(31, 2); assertRemote(31, 2)
        assertEquals("APPLIED", receipt(command1).getValue("status").jsonPrimitive.content)
        mcp("propose_hp_adjustment", proposal(command1, 1, 38, -7))
        mcpError("propose_hp_adjustment", proposal(command1, 1, 38, -6), "COMMAND_ID_REUSED")
        mcpError("propose_hp_adjustment", proposal(UUID.randomUUID().toString(), 1, 38, -1), "REVISION_CONFLICT")
        mcpError("propose_hp_adjustment", proposal(UUID.randomUUID().toString(), 2, 31, 100))
        invariant(31, 2); assertRemote(31, 2)
        mcp("propose_hp_adjustment", proposal(rejected, 2, 31, 4)); client.sync()
        waitFor("Proposed HP: 35"); tap("Reject HP change")
        compose.waitUntil(30_000) { runCatching { receipt(rejected).getValue("status").jsonPrimitive.content == "REJECTED" }.getOrDefault(false) }
        invariant(31, 2); assertRemote(31, 2)
        mcp("propose_hp_adjustment", proposal(offline, 2, 31, -1)); client.sync()
        waitFor("Proposed HP: 30")
        request("/__ci/fault", buildJsonObject { put("offline", true) })
        tap("Approve HP change")
        compose.waitUntil(30_000) { runBlocking { current().revision == 3L } }
        invariant(30, 3); assertRemote(31, 2)
        checkpoint("offline-intent", 30, 3)
    }

    @Test fun bRestartRetryAndValidatedBackupRestore() = nativeTest {
        invariant(30, 3); assertRemote(31, 2)
        request("/__ci/fault", buildJsonObject { put("offline", false); put("dropNextDecision", true) })
        // First delivery commits server-side and loses its HTTP response.
        runCatching { client.sync() }
        invariant(30, 3); assertRemote(30, 3)
        client.sync(); client.sync()
        assertEquals("APPLIED", receipt(offline).getValue("status").jsonPrimitive.content)
        invariant(30, 3); assertRemote(30, 3)
        displayPage(); waitFor("Current HP: 30")
        val backup = CampaignLedgerRepository(campaignDb).export()
        val vault = OwnerSyncCredentialVault(context)
        vault.credential(origin, campaignId)?.let { assertFalse(backup.contains(it.bearer)) }
        vault.serviceAccess(origin, campaignId)?.let { assertFalse(backup.contains(it)) }
        assertFalse(backup.contains("serviceAccessBearer"))
        val restored = Room.databaseBuilder(context, CampaignLedgerDb::class.java, "ci-hp-validated-restore.db").build()
        try {
            val repository = CampaignLedgerRepository(restored)
            val preview = repository.previewImport(backup); assertTrue(preview.conflicts.joinToString(), preview.valid)
            repository.restore(preview, "ci-native-hp-restore")
            assertEquals(current(), restored.recordsDao().characterProfile(profileId))
            assertEquals(campaignDb.recordsDao().characterProfile(otherId), restored.recordsDao().characterProfile(otherId))
            assertEquals(campaignDb.recordsDao().allAudit().filter { it.recordType != "backup" }.toSet(), restored.recordsDao().allAudit().filter { it.recordType != "backup" }.toSet())
            val p = requireNotNull(restored.recordsDao().characterProfile(profileId))
            CampaignRecordsRepository(restored).saveProfile(p.copy(currentHp = 29), p.revision, "CI explicit restore-conflict edit")
            assertFalse(repository.previewImport(backup).valid)
            assertEquals(29, restored.recordsDao().characterProfile(profileId)!!.currentHp)
        } finally { restored.close() }
        checkpoint("restart-retry-backup-restore", 30, 3)
    }

    @Test fun cSimultaneousLocalEditAndAuthorizationRevocation() = nativeTest {
        invariant(30, 3); assertRemote(30, 3)
        val stale = UUID.randomUUID().toString()
        mcp("propose_hp_adjustment", proposal(stale, 3, 30, -2)); client.sync()
        val p = current()
        CampaignRecordsRepository(campaignDb).saveProfile(p.copy(currentHp = 29), 3, "CI explicit concurrent local HP edit")
        client.decide(stale, true)
        invariant(29, 4)
        client.sync(); assertRemote(30, 3)
        // A visible conflict blocks publication until its old proposal is explicitly rejected.
        displayPage(); tap("Reject HP change")
        compose.waitUntil(30_000) { runCatching { receipt(stale).getValue("status").jsonPrimitive.content == "REJECTED" }.getOrDefault(false) }
        client.sync(); invariant(29, 4); assertRemote(29, 4)
        val vault = OwnerSyncCredentialVault(context)
        val credential = requireNotNull(vault.credential(origin, campaignId))
        val forbiddenProfile = OwnerConnectionHttpsTransport().request(origin, "/v1/hp/campaigns/$campaignId/characters/$otherId", credential, vault.serviceAccess(origin, campaignId), null)
        assertEquals("Device enrollment cannot read another profile", 403, forbiddenProfile.status)
        val forbiddenCampaign = OwnerConnectionHttpsTransport().request(origin, "/v1/hp/campaigns/other-fictional-campaign/characters/$profileId", credential, vault.serviceAccess(origin, campaignId), null)
        assertEquals("Device enrollment cannot cross campaigns", 403, forbiddenCampaign.status)
        val invalid = OwnerSyncCredential(credential.credentialId, campaignId, origin, credential.expiresAt, "invalid-native-test-token-".repeat(3))
        val response = OwnerConnectionHttpsTransport().request(origin, "/v1/hp/campaigns/$campaignId/characters/$profileId", invalid, vault.serviceAccess(origin, campaignId), null)
        assertEquals(401, response.status); invariant(29, 4)
        request("/__ci/expire", buildJsonObject { put("credentialId", credential.credentialId) })
        assertTrue(runCatching { client.sync() }.isFailure)
        assertFalse(client.snapshot().getValue("connected").jsonPrimitive.boolean)
        invariant(29, 4); pair(); assertRemote(29, 4)
        val freshGrant = requireNotNull(vault.credential(origin, campaignId))
        val freshServiceAccess = requireNotNull(vault.serviceAccess(origin, campaignId))
        assertNotEquals("Re-pairing issues a distinct grant", credential.credentialId, freshGrant.credentialId)
        assertTrue("The new grant is unexpired before revocation", freshGrant.expiresAt > System.currentTimeMillis())
        assertEquals("The captured grant and transport bearer authorize native HTTPS before revocation", 200,
            OwnerConnectionHttpsTransport().request(origin, "/v1/hp/campaigns/$campaignId/characters/$profileId", freshGrant, freshServiceAccess, null).status)
        client.revoke()
        assertFalse(client.snapshot().getValue("connected").jsonPrimitive.boolean)
        assertTrue(runCatching { client.sync() }.isFailure)
        assertTrue(runCatching { client.decide(command1, true) }.isFailure)
        val revoked = OwnerConnectionHttpsTransport().request(origin, "/v1/hp/campaigns/$campaignId/characters/$profileId", freshGrant, freshServiceAccess, null)
        assertEquals("The exact formerly working grant is rejected while outer transport access is retained", 401, revoked.status)
        assertEquals("CREDENTIAL_REVOKED_OR_EXPIRED", revoked.json.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        invariant(29, 4)
        mcpError("get_character_hp", buildJsonObject { put("campaignId", campaignId); put("profileId", profileId) }, "HP_CONNECTION_NOT_AUTHORIZED")
        checkpoint("conflicts-and-revocation", 29, 4)
    }
}

/** Runs against the previous published debug APK before install -r of the new source.
 * Only existing campaign APIs are referenced, so this does not require HP code in the baseline.
 */
@RunWith(AndroidJUnit4::class)
class LiveHpBaselineSeedTest {
    @Test fun existingFictionalProfilesBeforeUpdate(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        assertEquals("1", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("getprop ro.kernel.qemu").trim())
        val db = CampaignLedgerDb.open(context)
        try {
            val records = CampaignRecordsRepository(db)
            assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
            records.saveProfile(CharacterProfile(id = "hp-native-player", name = "CI fictional HP player", currentHp = 38,
                maximumHp = 66, temporaryHp = 7, armorClass = 19, level = 9), source = "CI fictional original profile")
            records.saveProfile(CharacterProfile(id = "hp-native-unrelated", name = "CI fictional unrelated", currentHp = 11,
                maximumHp = 20), source = "CI fictional unrelated record")
            records.saveAbility(CharacterAbility("INT", "hp-native-player", 20), source = "CI fictional preserved ability")
        } finally { db.close() }
    }
}
