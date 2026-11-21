package com.yazsras.astravar.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Bootstrap keys alone may migrate. Device credentials and their AES vault are never rotated. */
internal object AndroidBootstrapKeys {
    const val LEGACY_ALIAS = "astravar-owner-bootstrap-rsa-v1"
    const val CURRENT_ALIAS = "astravar-owner-bootstrap-rsa-oaep256-mgf256-v2"
    fun parameters() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    /** An alias and algorithm name cannot prove hardware authorization for MGF1 SHA-256. */
    fun compatible(pair: KeyPair): Boolean = try {
        val probe = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.ENCRYPT_MODE, pair.public, parameters())
            }.doFinal(probe)
            val recovered = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.DECRYPT_MODE, pair.private, parameters())
            }.doFinal(wrapped)
            try { MessageDigest.isEqual(probe, recovered) } finally { recovered.fill(0) }
        } finally { probe.fill(0) }
    } catch (_: Exception) { false }

    /** No deletion or overwrite path: an unusable existing v2 key fails closed. */
    internal fun select(load: (String) -> KeyPair?, create: (String) -> KeyPair,
        check: (KeyPair) -> Boolean = ::compatible): KeyPair {
        load(CURRENT_ALIAS)?.let {
            if (!check(it)) throw OwnerAuthorizationException(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE)
            return it
        }
        load(LEGACY_ALIAS)?.let { if (check(it)) return it }
        return create(CURRENT_ALIAS).also {
            if (!check(it)) throw OwnerAuthorizationException(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE)
        }
    }

    @Synchronized fun getOrCreate(): KeyPair = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        select(load = { alias ->
            if (!store.containsAlias(alias)) null else {
                val privateKey = store.getKey(alias, null) as? PrivateKey
                    ?: throw OwnerAuthorizationException(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE)
                val publicKey = store.getCertificate(alias)?.publicKey
                    ?: throw OwnerAuthorizationException(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE)
                KeyPair(publicKey, privateKey)
            }
        }, create = { alias ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            // Allowed MGF digests are immutable. Old aliases cannot acquire this authorization.
            if (Build.VERSION.SDK_INT >= 35) spec.setMgf1Digests(KeyProperties.DIGEST_SHA256)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(spec.build())
            }.generateKeyPair()
        })
    } catch (e: OwnerAuthorizationException) { throw e }
    catch (_: Exception) { throw OwnerAuthorizationException(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE) }
}
