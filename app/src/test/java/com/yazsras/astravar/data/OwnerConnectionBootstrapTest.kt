package com.yazsras.astravar.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnerConnectionBootstrapTest {
    private lateinit var context: Context
    private lateinit var pair: KeyPair
    private var clock = 100_000L
    private val origin = "https://owner.example"
    private val campaign = OwnerConnectionProtocol.CAMPAIGN
    private val bearer = "fictional-transport-only-".repeat(3)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("astravar-owner-bootstrap", Context.MODE_PRIVATE).edit().clear().commit()
        pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
    private fun helper() = OwnerConnectionBootstrap(context, { clock }, { pair })
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun begin(helper: OwnerConnectionBootstrap): String {
        val url = helper.begin(origin, campaign)
        assertEquals("/owner/bootstrap", URI(url).path)
        assertNull(URI(url).rawQuery)
        return URI(url).rawFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }.getValue("nonce")
    }
    private fun bundle(nonce: String, expiry: Long = clock + 600_000): JsonObject {
        val header = buildJsonObject { put("v", 1); put("algorithm", "RSA-OAEP-256+A256GCM"); put("origin", origin)
            put("campaignId", campaign); put("nonce", nonce); put("expiresAt", expiry) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }; val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val payload = JsonObject(header + ("serviceAccessBearer" to JsonPrimitive(bearer))).toString().toByteArray()
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv))
            updateAAD("ASTRAVAR_SERVICE_BOOTSTRAP_V1\n$origin\n$campaign\n$nonce\n$expiry".toByteArray()) }.doFinal(payload)
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, pair.public,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)) }.doFinal(raw)
        return JsonObject(header + mapOf("iv" to JsonPrimitive(encode(iv)), "wrappedKey" to JsonPrimitive(encode(wrapped)), "ciphertext" to JsonPrimitive(encode(encrypted))))
    }
    private fun JsonObject.bytes() = toString().toByteArray(Charsets.UTF_8)
    @Test fun encrypted_authorization_is_one_use_and_survives_client_recreation() {
        val first = helper(); val nonce = begin(first); val encrypted = bundle(nonce)
        assertFalse(encrypted.toString().contains(bearer))
        val result = helper().importEncryptedBundle(encrypted.bytes())
        assertEquals(origin, result.origin); assertEquals(campaign, result.campaignId); assertEquals(bearer, result.serviceAccessBearer)
        assertFalse(result.toString().contains(bearer))
        assertTrue(runCatching { helper().importEncryptedBundle(encrypted.bytes()) }.isFailure)
    }
    @Test fun expired_or_superseded_challenge_is_rejected() {
        val service = helper(); val old = bundle(begin(service)); begin(service)
        assertTrue(runCatching { service.importEncryptedBundle(old.bytes()) }.isFailure)
        val nonce = begin(service); val expired = bundle(nonce); clock += 600_001
        assertTrue(runCatching { service.importEncryptedBundle(expired.bytes()) }.isFailure)
    }
    @Test fun plaintext_credentials_real_campaign_and_insecure_origin_are_rejected() {
        val service = helper()
        assertTrue(runCatching { service.begin("http://owner.example", campaign) }.isFailure)
        assertTrue(runCatching { service.begin(origin, "live-campaign") }.isFailure)
        begin(service)
        assertTrue(runCatching { service.importEncryptedBundle(buildJsonObject { put("serviceAccessBearer", bearer) }.bytes()) }.isFailure)
        assertTrue(runCatching { service.importEncryptedBundle(ByteArray(32769)) }.isFailure)
    }
    @Test fun altered_scope_or_ciphertext_cannot_import_and_does_not_consume_valid_challenge() {
        val service = helper(); val encrypted = bundle(begin(service))
        assertTrue(runCatching { service.importEncryptedBundle(JsonObject(encrypted + ("origin" to JsonPrimitive("https://other.example"))).bytes()) }.isFailure)
        val bytes = encrypted.getValue("ciphertext").jsonPrimitive.content
        val tampered = (if (bytes.first() == 'A') "B" else "A") + bytes.drop(1)
        assertTrue(runCatching { service.importEncryptedBundle(JsonObject(encrypted + ("ciphertext" to JsonPrimitive(tampered))).bytes()) }.isFailure)
        assertEquals(bearer, service.importEncryptedBundle(encrypted.bytes()).serviceAccessBearer)
    }
    @Test fun foreign_installation_key_cannot_decrypt_authorization() {
        val service = helper(); val encrypted = bundle(begin(service))
        pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { service.importEncryptedBundle(encrypted.bytes()) }.isFailure)
    }
}
