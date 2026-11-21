package com.yazsras.astravar.data

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

/** Selection tests complement, but do not substitute for, AndroidKeyStore instrumentation. */
class AndroidBootstrapKeysTest {
    private fun pair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test fun actualOaepSha256MgfSha256RoundTripPassesForSupportedKey() {
        assertTrue(AndroidBootstrapKeys.compatible(pair()))
    }

    @Test fun compatibleLegacyKeyIsReusedWithoutCreation() {
        val old = pair()
        val selected = AndroidBootstrapKeys.select(load = { if (it == AndroidBootstrapKeys.LEGACY_ALIAS) old else null },
            create = { error("Must not create") }, check = { it === old })
        assertSame(old, selected)
    }

    @Test fun incompatibleLegacyKeyGetsVersionedKeyWithoutReplacingOriginal() {
        val old = pair(); val fresh = pair()
        val stored = mutableMapOf(AndroidBootstrapKeys.LEGACY_ALIAS to old)
        val selected = AndroidBootstrapKeys.select(stored::get, create = { alias ->
            assertEquals(AndroidBootstrapKeys.CURRENT_ALIAS, alias)
            assertFalse(stored.containsKey(alias)); stored[alias] = fresh; fresh
        }, check = { it === fresh })
        assertSame(fresh, selected)
        assertSame(old, stored[AndroidBootstrapKeys.LEGACY_ALIAS])
        assertSame(fresh, AndroidBootstrapKeys.select(stored::get, { error("Must reuse") }, { it === fresh }))
        assertEquals(2, stored.size)
    }

    @Test fun incompatibleCurrentKeyFailsClosedWithoutOverwritingEitherAlias() {
        val old = pair(); val current = pair()
        val stored = mapOf(AndroidBootstrapKeys.LEGACY_ALIAS to old, AndroidBootstrapKeys.CURRENT_ALIAS to current)
        val failure = runCatching { AndroidBootstrapKeys.select(stored::get, { error("Must not replace") }, { false }) }.exceptionOrNull()
        assertEquals(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE, (failure as OwnerAuthorizationException).code)
        assertSame(old, stored[AndroidBootstrapKeys.LEGACY_ALIAS]); assertSame(current, stored[AndroidBootstrapKeys.CURRENT_ALIAS])
    }

    @Test fun unsupportedNewKeyDoesNotDowngradeToWeakerParameters() {
        var creations = 0
        val failure = runCatching { AndroidBootstrapKeys.select({ null }, { alias ->
            assertEquals(AndroidBootstrapKeys.CURRENT_ALIAS, alias); creations++; pair()
        }, { false }) }.exceptionOrNull()
        assertEquals(1, creations)
        assertEquals(OwnerAuthorizationError.STRONG_CRYPTO_UNAVAILABLE, (failure as OwnerAuthorizationException).code)
    }
}
