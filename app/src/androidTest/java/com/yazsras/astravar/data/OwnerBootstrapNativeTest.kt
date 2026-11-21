package com.yazsras.astravar.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Compiled only against APIs present in code52; run before install -r of the candidate. */
@RunWith(AndroidJUnit4::class)
class OwnerBootstrapBaselineSeedTest {
    @Test fun seedLegacyAliasOnBaseline(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        assertEquals("1", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals(52L, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        val alias = "astravar-owner-bootstrap-rsa-v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Only a fresh isolated emulator can seed the historical alias", keyStore.containsAlias(alias))
        val legacy = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
        }.generateKeyPair() // Deliberately historical default MGF1 SHA-1, without SHA-256 authorization.
        val vault = OwnerSyncCredentialVault(context)
        val credential = OwnerSyncCredential("bootstrap-native-sentinel", "bootstrap-native-preserved", "https://hp-bridge.example",
            System.currentTimeMillis() + 86_400_000, "synthetic_native_sentinel_credential_00000000000000000000")
        vault.enroll(credential)
        assertEquals(credential.credentialId, vault.credential(credential.origin, credential.campaignId)?.credentialId)
        check(context.getSharedPreferences("ci-bootstrap-upgrade", Context.MODE_PRIVATE).edit()
            .putString("legacyPublicKey", Base64.encodeToString(legacy.public.encoded, Base64.NO_WRAP)).commit())
        // Separate synthetic database proves install-r file preservation without polluting campaign authority.
        val db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, "ci-bootstrap-preserved.sqlite").build()
        try {
            CampaignRecordsRepository(db).saveProfile(CharacterProfile("Synthetic bootstrap sentinel", id = "bootstrap-native-sentinel",
                currentHp = 7, maximumHp = 20), source = "Isolated upgrade fixture", commandId = "bootstrap-sentinel-create")
        } finally { db.close() }
    }
}

/** Actual AndroidKeyStore + current Worker WebCrypto over hostname-verified TLS; not phone evidence. */
@RunWith(AndroidJUnit4::class)
class OwnerBootstrapNativeTest {
    private lateinit var context: Context
    private lateinit var config: JsonObject
    private lateinit var originalSsl: SSLSocketFactory
    private val origin = "https://hp-bridge.example"
    private val campaign = "bootstrap-native-campaign"

    @Before fun isolatedNativeTls() {
        context = ApplicationProvider.getApplicationContext()
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        assertEquals("1", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("getprop ro.kernel.qemu").trim())
        assertTrue(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode >= 53L)
        config = Json.parseToJsonElement(File(context.filesDir, "ci-https-config.json").readText()).jsonObject
        assertEquals(origin, config.getValue("origin").jsonPrimitive.content)
        originalSsl = HttpsURLConnection.getDefaultSSLSocketFactory()
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        anchors.setCertificateEntry("ephemeral-bootstrap-ci", CertificateFactory.getInstance("X.509")
            .generateCertificate(config.getValue("certificatePem").jsonPrimitive.content.byteInputStream()))
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply {
            init(null, trusts.trustManagers, null)
        }.socketFactory)
    }
    @After fun restoreProcessTrust() {
        if (::originalSsl.isInitialized) HttpsURLConnection.setDefaultSSLSocketFactory(originalSsl)
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun key(alias: String): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return KeyPair(requireNotNull(store.getCertificate(alias)).publicKey, store.getKey(alias, null) as PrivateKey)
    }
    private fun bundle(url: String): JsonObject {
        val fragment = requireNotNull(URI(url).rawFragment).split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        return bundle(fragment)
    }
    private fun bundle(fragment: Map<String, String>): JsonObject {
        val connection = URL("$origin/__ci/owner").openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Astravar-Ci-Control", config.getValue("controlToken").jsonPrimitive.content)
        val request = buildJsonObject {
            put("path", "/owner/bootstrap/bundle"); put("method", "POST")
            putJsonObject("body") { put("origin", origin); fragment.forEach { (name, value) -> put(name, value) } }
        }
        return try {
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            assertEquals("The isolated current Worker must accept the bootstrap request", 200, connection.responseCode)
            connection.inputStream.use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
        } finally { connection.disconnect() }
    }
    private fun rejected(expected: OwnerAuthorizationError, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected classified authorization failure $expected", failure is OwnerAuthorizationException)
        assertEquals(expected, (failure as OwnerAuthorizationException).code)
    }
    private fun persist(bootstrap: OwnerConnectionBootstrap, encrypted: JsonObject): OwnerServiceEnrollment {
        val enrollment = bootstrap.importEncryptedBundle(encrypted.toString().toByteArray())
        val vault = OwnerSyncCredentialVault(context)
        bootstrap.completeImport(enrollment) { vault.storeServiceAccess(enrollment.origin, enrollment.campaignId, enrollment.serviceAccessBearer) }
        assertTrue("Encrypted service access survives vault readback", enrollment.serviceAccessBearer == vault.serviceAccess(origin, campaign))
        return enrollment
    }

    @Test fun upgradeRepairsLegacyOaepAndPreservesCredentials() = runBlocking {
        val oldKey = key("astravar-owner-bootstrap-rsa-v1")
        val oldPublic = context.getSharedPreferences("ci-bootstrap-upgrade", Context.MODE_PRIVATE).getString("legacyPublicKey", null)
        assertEquals(oldPublic, Base64.encodeToString(oldKey.public.encoded, Base64.NO_WRAP))
        // Positive control: the retained key still works with its historically allowed MGF1.
        // This legacy parameter is used only to prove the defect, never by the production importer.
        val historical = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        val control = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(oldKey.public.encoded))
        val legacyWrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, publicKey, historical) }.doFinal(control)
        assertArrayEquals(control, Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, oldKey.private, historical)
        }.doFinal(legacyWrapped))
        control.fill(0)
        val encryptedToOld = bundle(mapOf("campaignId" to campaign,
            "nonce" to encode(ByteArray(32).also { SecureRandom().nextBytes(it) }), "publicKeySpki" to encode(oldKey.public.encoded)))
        val failure = runCatching {
            Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.DECRYPT_MODE, oldKey.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            }.doFinal(decode(encryptedToOld.getValue("wrappedKey").jsonPrimitive.content))
        }.exceptionOrNull()
        assertNotNull("Real WebCrypto ciphertext must reproduce incompatible old Keystore parameters", failure)
        val vault = OwnerSyncCredentialVault(context)
        val savedBefore = requireNotNull(vault.credential(origin, "bootstrap-native-preserved"))
        val bootstrap = OwnerConnectionBootstrap(context)
        val encrypted = bundle(bootstrap.begin(origin, campaign))
        persist(bootstrap, encrypted)
        assertEquals(oldPublic, Base64.encodeToString(key("astravar-owner-bootstrap-rsa-v1").public.encoded, Base64.NO_WRAP))
        assertNotEquals(oldPublic, Base64.encodeToString(key("astravar-owner-bootstrap-rsa-oaep256-mgf256-v2").public.encoded, Base64.NO_WRAP))
        val savedAfter = requireNotNull(vault.credential(origin, "bootstrap-native-preserved"))
        assertEquals(savedBefore.credentialId, savedAfter.credentialId)
        assertTrue("Existing device access remains unchanged", savedBefore.bearer == savedAfter.bearer)
        val sentinel = Room.databaseBuilder(context, CampaignLedgerDb::class.java, "ci-bootstrap-preserved.sqlite").build()
        try { assertEquals(7, sentinel.recordsDao().characterProfile("bootstrap-native-sentinel")?.currentHp) } finally { sentinel.close() }
        File(context.getExternalFilesDir(null), "bootstrap-native-upgrade.json").writeText(buildJsonObject {
            put("syntheticOnly", true); put("apiLevel", Build.VERSION.SDK_INT)
            put("legacyOaepFailure", requireNotNull(failure).javaClass.simpleName)
            put("freshVersionedKeyWebCryptoImport", true); put("legacyKeyRetained", true)
            put("existingDeviceCredentialRetained", true); put("existingDatabaseRetained", true)
        }.toString())
    }

    @Test fun freshRenewalAndSingleUseWorkWithWebCrypto() {
        val bootstrap = OwnerConnectionBootstrap(context)
        val firstUrl = bootstrap.begin(origin, campaign)
        val first = bundle(firstUrl)
        val secondUrl = bootstrap.begin(origin, campaign)
        val second = bundle(secondUrl)
        val firstKey = URI(firstUrl).rawFragment.substringAfter("publicKeySpki=")
        assertEquals("Compatible installation key is reused across renewals", firstKey, URI(secondUrl).rawFragment.substringAfter("publicKeySpki="))
        rejected(OwnerAuthorizationError.REQUEST_SUPERSEDED) { bootstrap.importEncryptedBundle(first.toString().toByteArray()) }
        persist(bootstrap, second)
        rejected(OwnerAuthorizationError.REQUEST_SUPERSEDED) { bootstrap.importEncryptedBundle(second.toString().toByteArray()) }
        persist(OwnerConnectionBootstrap(context), bundle(OwnerConnectionBootstrap(context).begin(origin, campaign)))
    }

    @Test fun invalidBundlesAndInterruptedPersistenceRemainRetryable() {
        val bootstrap = OwnerConnectionBootstrap(context)
        val url = bootstrap.begin(origin, campaign)
        val valid = bundle(url)
        rejected(OwnerAuthorizationError.MALFORMED_BUNDLE) { bootstrap.importEncryptedBundle("{broken".toByteArray()) }
        rejected(OwnerAuthorizationError.WRONG_SCOPE) { bootstrap.importEncryptedBundle(JsonObject(valid + ("campaignId" to JsonPrimitive("another-campaign"))).toString().toByteArray()) }
        rejected(OwnerAuthorizationError.REQUEST_EXPIRED) { bootstrap.importEncryptedBundle(JsonObject(valid + ("expiresAt" to JsonPrimitive(System.currentTimeMillis() - 1))).toString().toByteArray()) }
        val ciphertext = valid.getValue("ciphertext").jsonPrimitive.content
        val corrupt = (if (ciphertext.first() == 'A') "B" else "A") + ciphertext.drop(1)
        rejected(OwnerAuthorizationError.CRYPTO_VERIFICATION_FAILED) { bootstrap.importEncryptedBundle(JsonObject(valid + ("ciphertext" to JsonPrimitive(corrupt))).toString().toByteArray()) }
        val otherAlias = "astravar-ci-bootstrap-other-installation"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(otherAlias)) {
            val spec = KeyGenParameterSpec.Builder(otherAlias, KeyProperties.PURPOSE_DECRYPT).setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            if (Build.VERSION.SDK_INT >= 35) spec.setMgf1Digests(KeyProperties.DIGEST_SHA256)
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
        }
        val fragment = URI(url).rawFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val otherInstallation = bundle(fragment + ("publicKeySpki" to encode(key(otherAlias).public.encoded)))
        rejected(OwnerAuthorizationError.CRYPTO_VERIFICATION_FAILED) { bootstrap.importEncryptedBundle(otherInstallation.toString().toByteArray()) }
        val enrollment = bootstrap.importEncryptedBundle(valid.toString().toByteArray())
        rejected(OwnerAuthorizationError.LOCAL_STORAGE_FAILED) { bootstrap.completeImport(enrollment) { error("Synthetic storage interruption") } }
        // A failed credential save cannot consume the file; a fresh bootstrap object can recover it.
        persist(OwnerConnectionBootstrap(context), valid)
    }
}
