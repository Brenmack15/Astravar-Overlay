package com.yazsras.astravar.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/** Local encryption/callback races only; these tests do not claim Pushy or phone delivery. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignPushStoreTest {
    private lateinit var context: Context
    private fun store() = CampaignPushStore(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    private val oldBinding = "a".repeat(64)
    private val newBinding = "b".repeat(64)
    private val oldToken = "fixture-old-token_".repeat(5)
    private val newToken = "fixture-new-token_".repeat(5)
    private val time = 1_900_000_000_000L
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun delayedSdkCallbackCannotRecreateTokenAfterUnpairOrOverwriteRenewedEnrollment() = runBlocking {
        val connectionStore = store()
        connectionStore.bindEnrollment(oldBinding, time + 60_000)
        assertTrue(connectionStore.saveTokenIfBound(oldToken, oldBinding, time))
        val released = CompletableDeferred<Unit>()
        val pendingSdkCallback = async(Dispatchers.Default) {
            released.await()
            store().saveTokenIfBound(oldToken, oldBinding, time)
        }
        // Unpair removes the token and invalidates already-issued asynchronous SDK callbacks.
        connectionStore.forgetToken()
        released.complete(Unit)
        assertFalse(pendingSdkCallback.await())
        assertNull(store().token())
        // Re-pairing to a new owner/device/grant must not reactivate the old callback.
        connectionStore.bindEnrollment(newBinding, time + 60_000)
        assertFalse(store().saveTokenIfBound(oldToken, oldBinding, time))
        assertTrue(store().saveTokenIfBound(newToken, newBinding, time))
        assertEquals(newToken, store().token())
        assertFalse(store().saveTokenIfBound(oldToken, oldBinding, time))
        assertEquals(newToken, store().token())
        assertFalse(context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE).getString("token", "")!!.contains(newToken))
    }
    @Test fun actualSdkJsonWrapperIsIgnoredButUnknownOrTypedPayloadsAreRejected() {
        val payload = mapOf("type" to CampaignWakeProtocol.TYPE, "version" to "2", "issuedAt" to "1900000000000",
            "expiresAt" to "1900000030000", "wakeId" to "21110000-1111-4111-8111-111111111111", "signature" to "signed-placeholder")
        fun intent() = Intent("pushy.me").apply { payload.forEach { (key, value) -> putExtra(key, value) } }
        assertEquals(payload, campaignPushyWakeData(intent().putExtra("__json", "raw SDK envelope".repeat(40))))
        assertNull(campaignPushyWakeData(intent().putExtra("gameplay", "never accepted")))
        assertNull(campaignPushyWakeData(intent().putExtra("version", 2)))
        assertNull(campaignPushyWakeData(intent().setAction("foreign.sender")))
    }
    @Test fun cachedPushyTokenNeverSchedulesARecursiveSyncWorker() {
        assertTrue(CampaignPushy.shouldEnqueueTokenRefresh(null, "0123456789abcdef0123"))
        assertFalse(CampaignPushy.shouldEnqueueTokenRefresh("0123456789abcdef0123", "0123456789abcdef0123"))
        assertTrue(CampaignPushy.shouldEnqueueTokenRefresh("0123456789abcdef0123", "abcdef01234567890123"))
    }
    @Test fun providerUpgradeDropsLegacyTokenWithoutTouchingWakeProgress() {
        val preferences = context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE)
        preferences.edit().putString("provider", "fcm").putString("token", "old-provider-ciphertext")
            .putString("registered", "old-registration").putLong("wakeSequence", 9)
            .putLong("completedWakeSequence", 8).putLong("lastReceivedAt", time).commit()
        val upgraded = store()
        assertNull(upgraded.token())
        assertEquals("", upgraded.registration)
        assertEquals(9L, upgraded.lastWakeSequence)
        assertTrue(upgraded.isWakeCompleted(8))
        assertEquals(time, upgraded.lastReceivedAt)
        assertEquals("pushy", preferences.getString("provider", null))
    }
    @Test fun newWakeCannotBeCoalescedAwayBySameMillisecondOrBackwardsClock() {
        val connectionStore = store()
        // A pre-upgrade timestamp marker must not suppress generation-based work.
        context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE)
            .edit().putLong("completedWakeAt", time + 86_400_000).commit()
        assertTrue(connectionStore.claimWake("first", time + 30_000, time))
        assertFalse(store().isWakeCompleted(connectionStore.lastWakeSequence))
        val first = connectionStore.lastWakeSequence
        connectionStore.completeWake(first)
        assertTrue(store().isWakeCompleted(first))
        assertFalse(store().isWakeCompleted(0)) // token refresh/manual recovery must always drain
        assertTrue(store().claimWake("same-millisecond", time + 30_000, time))
        val second = store().lastWakeSequence
        assertTrue(second > first)
        assertFalse(store().isWakeCompleted(second))
        // A signal arriving while the first worker finishes must remain eligible.
        connectionStore.completeWake(first)
        assertFalse(store().isWakeCompleted(second))
        assertTrue(store().claimWake("clock-corrected", time - 60_000 + 30_000, time - 60_000))
        val third = store().lastWakeSequence
        assertTrue(third > second)
        assertEquals(time - 60_000, store().lastReceivedAt)
        store().completeWake(second)
        assertFalse(store().isWakeCompleted(third))
        assertFalse(store().claimWake("clock-corrected", time - 30_000, time - 60_000))
        assertEquals(third, store().lastWakeSequence)
        store().completeWake(third)
        // A stale worker completion must never roll back a newer completed generation.
        store().completeWake(first)
        assertTrue(store().isWakeCompleted(third))
    }
    @Test fun expiredEnrollmentAndRegistrationInvalidationRejectOutstandingTokenCallbacks() {
        val connectionStore = store()
        connectionStore.bindEnrollment(oldBinding, time + 30_000)
        assertFalse(connectionStore.saveTokenIfBound(oldToken, oldBinding, time + 30_001))
        assertNull(connectionStore.token())
        connectionStore.bindEnrollment(oldBinding, time + 60_000)
        connectionStore.clearRegistration()
        assertFalse(store().saveTokenIfBound(oldToken, oldBinding, time))
        assertNull(connectionStore.token())
    }
}
