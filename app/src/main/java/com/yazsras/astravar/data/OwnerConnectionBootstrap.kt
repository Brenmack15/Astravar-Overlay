package com.yazsras.astravar.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyPair
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Deliberately not a data class: private service credentials never enter toString(). */
class OwnerServiceEnrollment internal constructor(val origin: String, val campaignId: String,
    internal val serviceAccessBearer: String, val expiresAt: Long, internal val requestNonce: String) {
    override fun toString() = "OwnerServiceEnrollment(redacted)"
}

/** The authenticated browser encrypts transport access for this installation's one-use challenge.
 * Record permissions remain controlled by the separate owner-approved device grant.
 */
class OwnerConnectionBootstrap internal constructor(context: Context, private val now: () -> Long,
    private val keyProvider: () -> KeyPair) {
    constructor(context: Context) : this(context.applicationContext, System::currentTimeMillis, AndroidBootstrapKeys::getOrCreate)
    private val preferences = context.applicationContext.getSharedPreferences("astravar-owner-bootstrap", Context.MODE_PRIVATE)
    companion object {
        private const val ALGORITHM = "RSA-OAEP-256+A256GCM"
        private const val MAX_AGE = 10 * 60_000L
        // All service clients in this process share one pending authorization request.
        private val requestLock = Any()
        private val headerKeys = setOf("v", "algorithm", "origin", "campaignId", "nonce", "expiresAt")
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun decode(value: String, max: Int): ByteArray {
            require(value.length <= max && value.matches(Regex("[A-Za-z0-9_-]+")))
            return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also { require(encode(it) == value) }
        }
        private fun document(bytes: ByteArray): JsonObject = Json.parseToJsonElement(Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()).jsonObject
        private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
        private fun JsonObject.number(name: String): Long = getValue(name).jsonPrimitive.let { require(!it.isString); it.long }
        private fun fail(code: OwnerAuthorizationError): Nothing = throw OwnerAuthorizationException(code)
    }

    fun begin(origin: String, campaign: String): String = synchronized(requestLock) {
        val endpoint = OwnerSyncProtocol.origin(origin)
        OwnerSyncProtocol.id(campaign)
        // Check actual OAEP parameters before superseding a request that might still be usable.
        val pair = try { keyProvider() } catch (_: Exception) { fail(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE) }
        if (!AndroidBootstrapKeys.compatible(pair)) fail(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE)
        val nonce = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val publicKey = encode(pair.public.encoded)
        val previous = preferences.all.toMap()
        saveOrRestore(previous) { putString("origin", endpoint).putString("campaign", campaign).putString("nonce", nonce).putLong("createdAt", now()) }
        // Fragments do not enter request URLs or referrer headers.
        "$endpoint/owner/bootstrap#campaignId=$campaign&nonce=$nonce&publicKeySpki=$publicKey"
    }

    private fun verifyRequest(origin: String, campaign: String, nonce: String, expiry: Long) {
        if (preferences.getString("origin", null) != origin || preferences.getString("campaign", null) != campaign) {
            if (preferences.getString("nonce", null) == null) fail(OwnerAuthorizationError.REQUEST_SUPERSEDED)
            fail(OwnerAuthorizationError.WRONG_SCOPE)
        }
        if (preferences.getString("nonce", null) != nonce) fail(OwnerAuthorizationError.REQUEST_SUPERSEDED)
        val created = preferences.getLong("createdAt", 0)
        val time = now()
        if (created <= 0 || time < created || time - created > MAX_AGE || expiry <= time || expiry - time > MAX_AGE)
            fail(OwnerAuthorizationError.REQUEST_EXPIRED)
    }

    /** Decryption alone does not consume the request: credential persistence must succeed first. */
    fun importEncryptedBundle(bytes: ByteArray): OwnerServiceEnrollment = synchronized(requestLock) {
        val bundle: JsonObject
        val origin: String
        val campaign: String
        val nonce: String
        val expiry: Long
        val wrapped: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        try {
            require(bytes.size in 1..32768)
            bundle = document(bytes)
            require(bundle.keys == headerKeys + setOf("wrappedKey", "iv", "ciphertext"))
            if (bundle.number("v") != 1L || bundle.text("algorithm") != ALGORITHM)
                fail(OwnerAuthorizationError.UNSUPPORTED_CRYPTO_PARAMETERS)
            origin = bundle.text("origin"); campaign = bundle.text("campaignId"); nonce = bundle.text("nonce")
            expiry = bundle.number("expiresAt")
            require(OwnerSyncProtocol.origin(origin) == origin && OwnerSyncProtocol.id(campaign) == campaign)
            require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
            wrapped = decode(bundle.text("wrappedKey"), 1024)
            iv = decode(bundle.text("iv"), 32); require(iv.size == 12)
            ciphertext = decode(bundle.text("ciphertext"), 20000); require(ciphertext.size >= 16)
        } catch (e: OwnerAuthorizationException) { throw e }
        catch (_: Exception) { fail(OwnerAuthorizationError.MALFORMED_BUNDLE) }
        verifyRequest(origin, campaign, nonce, expiry)
        val pair = try { keyProvider() } catch (_: Exception) { fail(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE) }
        val key = try {
            Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.DECRYPT_MODE, pair.private, AndroidBootstrapKeys.parameters())
            }.doFinal(wrapped)
        } catch (_: java.security.InvalidKeyException) { fail(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE) }
        catch (_: java.security.InvalidAlgorithmParameterException) { fail(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE) }
        catch (_: Exception) { fail(OwnerAuthorizationError.CRYPTO_VERIFICATION_FAILED) }
        val plaintext = try {
            require(key.size == 32)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD("ASTRAVAR_SERVICE_BOOTSTRAP_V1\n$origin\n$campaign\n$nonce\n$expiry".toByteArray(Charsets.UTF_8))
            }.doFinal(ciphertext)
        } catch (_: Exception) { fail(OwnerAuthorizationError.CRYPTO_VERIFICATION_FAILED) }
        finally { key.fill(0) }
        try {
            val secret = document(plaintext)
            require(secret.keys == headerKeys + "serviceAccessBearer")
            headerKeys.forEach { require(secret[it] == bundle[it]) }
            val bearer = secret.text("serviceAccessBearer")
            require(bearer.length in 32..8192 && bearer.none { it == '\r' || it == '\n' })
            OwnerServiceEnrollment(origin, campaign, bearer, expiry, nonce)
        } catch (_: Exception) { fail(OwnerAuthorizationError.CRYPTO_VERIFICATION_FAILED) }
        finally { plaintext.fill(0) }
    }

    /** Persistence must be idempotent. A crash after saving but before consumption is safely retried. */
    fun completeImport(enrollment: OwnerServiceEnrollment, persist: () -> Unit) = synchronized(requestLock) {
        verifyRequest(enrollment.origin, enrollment.campaignId, enrollment.requestNonce, enrollment.expiresAt)
        try { persist() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail(OwnerAuthorizationError.LOCAL_STORAGE_FAILED) }
        val previous = preferences.all.toMap()
        saveOrRestore(previous) { clear() }
    }

    private fun saveOrRestore(previous: Map<String, *>, edit: SharedPreferences.Editor.() -> Unit) {
        try {
            if (preferences.edit().apply(edit).commit()) return
        } catch (_: Exception) { /* Restore the prior in-process view as well as retrying disk state. */ }
        runCatching {
            val restore = preferences.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
            } }
            restore.commit()
        }
        fail(OwnerAuthorizationError.LOCAL_STORAGE_FAILED)
    }
}
