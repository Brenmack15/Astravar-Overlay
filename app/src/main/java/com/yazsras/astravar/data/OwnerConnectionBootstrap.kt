package com.yazsras.astravar.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.*
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Deliberately not a data class: private service credentials never enter toString(). */
class OwnerServiceEnrollment(val origin: String, val campaignId: String, internal val serviceAccessBearer: String, val expiresAt: Long) {
    override fun toString() = "OwnerServiceEnrollment(redacted)"
}

/** Before native pairing, an authenticated owner page encrypts private-Site
 * transport access to this installation. SAF sees ciphertext only. The local
 * one-use challenge and non-exportable RSA key bind the imported file to this
 * phone, service and campaign. Neither key nor credentials enter app backups.
 * This grants private transport access only: a separately owner-approved,
 * campaign/profile-scoped device enrollment controls record permissions.
 */
class OwnerConnectionBootstrap internal constructor(context: Context, private val now: () -> Long,
    private val keyProvider: () -> KeyPair) {
    constructor(context: Context) : this(context.applicationContext, System::currentTimeMillis, Companion::androidKey)
    private val preferences = context.applicationContext.getSharedPreferences("astravar-owner-bootstrap", Context.MODE_PRIVATE)
    companion object {
        private const val ALIAS = "astravar-owner-bootstrap-rsa-v1"
        private const val ALGORITHM = "RSA-OAEP-256+A256GCM"
        private const val MAX_AGE = 10 * 60_000L
        private fun androidKey(): KeyPair {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? java.security.PrivateKey)?.let {
                return KeyPair(store.getCertificate(ALIAS).publicKey, it)
            }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            // WebCrypto OAEP SHA-256 also uses MGF1 SHA-256. Older Keystore
            // implementations may reject that combination; never weaken it.
            if (Build.VERSION.SDK_INT >= 35) spec.setMgf1Digests(KeyProperties.DIGEST_SHA256)
            return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
        }
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun decode(value: String, max: Int): ByteArray {
            require(value.length <= max && value.matches(Regex("[A-Za-z0-9_-]+")))
            return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
    @Synchronized fun begin(origin: String, campaign: String): String {
        val endpoint = OwnerSyncProtocol.origin(origin)
        OwnerSyncProtocol.id(campaign)
        val nonce = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val publicKey = encode(keyProvider().public.encoded)
        check(preferences.edit().putString("origin", endpoint).putString("campaign", campaign).putString("nonce", nonce)
            .putLong("createdAt", now()).commit()) { "Service authorization request was not saved." }
        // Fragment stays inside the authenticated browser; no public key or
        // challenge is sent in request URLs or referrer headers.
        return "$endpoint/owner/bootstrap#campaignId=$campaign&nonce=$nonce&publicKeySpki=$publicKey"
    }
    @Synchronized fun importEncryptedBundle(bytes: ByteArray): OwnerServiceEnrollment {
        require(bytes.size in 1..32768)
        val bundle = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(bundle.keys == setOf("v", "algorithm", "origin", "campaignId", "nonce", "expiresAt", "wrappedKey", "iv", "ciphertext"))
        require(bundle.getValue("v").jsonPrimitive.int == 1 && bundle.getValue("algorithm").jsonPrimitive.content == ALGORITHM)
        val origin = bundle.getValue("origin").jsonPrimitive.content
        val campaign = bundle.getValue("campaignId").jsonPrimitive.content
        val nonce = bundle.getValue("nonce").jsonPrimitive.content
        val expiry = bundle.getValue("expiresAt").jsonPrimitive.long
        val created = preferences.getLong("createdAt", 0)
        require(OwnerSyncProtocol.origin(origin) == origin && OwnerSyncProtocol.id(campaign) == campaign &&
            preferences.getString("origin", null) == origin && preferences.getString("campaign", null) == campaign &&
            nonce.matches(Regex("[A-Za-z0-9_-]{43}")) && preferences.getString("nonce", null) == nonce &&
            created > 0 && now() >= created && now() - created <= MAX_AGE && expiry > now() && expiry <= now() + MAX_AGE) {
            "Authorization is expired or belongs to another installation or service. Start again from Astravar."
        }
        val wrapped = decode(bundle.getValue("wrappedKey").jsonPrimitive.content, 1024)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, keyProvider().private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)) }
        val key = rsa.doFinal(wrapped)
        require(key.size == 32)
        val iv = decode(bundle.getValue("iv").jsonPrimitive.content, 32); require(iv.size == 12)
        val aad = "ASTRAVAR_SERVICE_BOOTSTRAP_V1\n$origin\n$campaign\n$nonce\n$expiry".toByteArray(Charsets.UTF_8)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad)
            }.doFinal(decode(bundle.getValue("ciphertext").jsonPrimitive.content, 20000))
        } finally { key.fill(0) }
        val secret = try { Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject } finally { plaintext.fill(0) }
        require(secret.keys == setOf("v", "algorithm", "origin", "campaignId", "nonce", "expiresAt", "serviceAccessBearer"))
        listOf("v", "algorithm", "origin", "campaignId", "nonce", "expiresAt").forEach { require(secret[it] == bundle[it]) }
        val bearer = secret.getValue("serviceAccessBearer").jsonPrimitive.content
        require(bearer.length in 32..8192 && bearer.none { it == '\r' || it == '\n' })
        check(preferences.edit().clear().commit()) { "Authorization could not be marked consumed." }
        return OwnerServiceEnrollment(origin, campaign, bearer, expiry)
    }
}
