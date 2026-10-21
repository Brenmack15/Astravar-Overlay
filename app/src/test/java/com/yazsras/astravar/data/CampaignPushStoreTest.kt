package com.yazsras.astravar.data

import android.content.Context
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

/** Local encryption/callback races only; these tests do not claim FCM or phone delivery. */
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
