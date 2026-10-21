package com.yazsras.astravar.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Native emulator transport/control evidence. FCM delivery, Doze wake and physical-phone latency
 * require the owner's configured Firebase project and phone; these tests do not imply them. */
@RunWith(AndroidJUnit4::class)
class CampaignWakeNativeTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var controlsBefore: Map<String, *>
    private lateinit var pushBefore: Map<String, *>
    private var fixtureAuthorized = false
    private val now = 1_900_000_000_000L
    private val hash = "9".repeat(64)

    @Before fun isolatedEmulatorOnly() {
        context = ApplicationProvider.getApplicationContext()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("Destructive fixtures are restricted to a fresh emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        fixtureAuthorized = true
        controlsBefore = context.getSharedPreferences("campaign-sync-controls-v1", Context.MODE_PRIVATE).all.toMap()
        pushBefore = context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE).all.toMap()
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        CampaignSyncPreferences(context).paused = true
        CampaignSyncPreferences(context).notificationsEnabled = false
    }
    @After fun restoreOnlyFixtureControls() {
        if (!fixtureAuthorized) return
        device.wakeUp(); device.pressBack()
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        context.getSystemService(NotificationManager::class.java).cancel(CampaignSyncNotifications.ID)
        fun restore(name: String, before: Map<String, *>) {
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
            } }
            assertTrue(edit.commit())
        }
        restore("campaign-sync-controls-v1", controlsBefore)
        restore("astravar-campaign-push", pushBefore)
        // This fresh emulator is destroyed by the harness. Revoking a runtime permission
        // here can kill the instrumentation UID before its result is reported.
    }
    private fun signed(): Map<String, String> {
        val data = linkedMapOf("type" to CampaignWakeProtocol.TYPE, "version" to "1", "campaignId" to "fixture-campaign",
            "credentialId" to "fixture-device", "grantId" to "fixture-grant", "grantRevision" to "2",
            "issuedAt" to now.toString(), "expiresAt" to (now + 30_000).toString(), "wakeId" to UUID.randomUUID().toString())
        val input = data.values.joinToString("\n").toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hash.toByteArray(Charsets.UTF_8), "HmacSHA256")) }.doFinal(input)
        return data + ("signature" to Base64.encodeToString(mac, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }
    private fun valid(data: Map<String, String>, at: Long = now) = CampaignWakeProtocol.verified(data,
        "fixture-campaign", "fixture-device", "fixture-grant", 2, hash, at)

    @Test fun nativeWakeAuthenticationAndReplayRemainStrictWithScreenOff() {
        val wake = signed()
        assertTrue(valid(wake))
        assertFalse(valid(wake + ("campaignId" to "foreign-campaign")))
        assertFalse(valid(wake + ("credentialId" to "foreign-device")))
        assertFalse(valid(wake + ("grantRevision" to "3")))
        assertFalse(valid(wake, now + 30_001))
        assertFalse(valid(wake + ("command" to "never execute FCM gameplay")))
        val store = CampaignPushStore(context)
        val token = "fixture-only-not-a-real-fcm-token_".repeat(3)
        store.bindEnrollment(hash, now + 1_000_000)
        assertTrue(store.saveTokenIfBound(token, hash, now))
        assertEquals(token, CampaignPushStore(context).token())
        assertFalse(context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE).getString("token", "")!!.contains(token))
        assertTrue(store.claimWake(wake.getValue("wakeId"), now + 30_000, now))
        device.sleep()
        try {
            assertTrue(valid(wake))
            assertFalse(CampaignPushStore(context).claimWake(wake.getValue("wakeId"), now + 30_000, now))
            assertTrue(CampaignSyncPreferences(context).paused)
        } finally { device.wakeUp() }
    }

    @Test fun nativePauseBlocksAutomaticWorkAndRetainsManualChoice() {
        val manager = WorkManager.getInstance(context)
        CampaignConnectionSyncJob.schedule(context)
        CampaignConnectionSyncJob.enqueue(context, wakeAt = now)
        assertTrue(CampaignSyncPreferences(context).paused)
        assertTrue(manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.PERIODIC).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        assertTrue(manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        CampaignConnectionSyncJob.enqueue(context, manual = true)
        assertTrue(manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.MANUAL).get(10, TimeUnit.SECONDS).isNotEmpty())
        assertTrue("Manual sync must not resume automatic delivery", CampaignSyncPreferences(context).paused)
    }

    @Test fun nativeQuietNotificationHasImmutableActionsAndPausedScreenshot() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        CampaignSyncPreferences(context).notificationsEnabled = true
        CampaignSyncNotifications.update(context, buildJsonObject { put("connected", true); put("lastSyncAt", System.currentTimeMillis()) })
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CampaignSyncNotifications.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        val notification = manager.activeNotifications.single { it.id == CampaignSyncNotifications.ID }.notification
        assertTrue(notification.extras.getCharSequence("android.title").toString().contains("Paused"))
        assertEquals(3, notification.actions.size)
        assertTrue(notification.actions.all { it.actionIntent.creatorPackage == context.packageName })
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.actions.all { it.actionIntent.isImmutable })
        val receiver = context.packageManager.getReceiverInfo(android.content.ComponentName(context, CampaignSyncActionReceiver::class.java), 0)
        assertFalse(receiver.exported)
        // Exercise the actual app-owned PendingIntent broadcasts, not direct receiver calls.
        // A preference listener and main-thread idle barrier observe receiver completion.
        fun toggle(action: android.app.PendingIntent, expectedPaused: Boolean) {
            val changed = CountDownLatch(1)
            val observation = CampaignSyncPreferences(context).observeControls {
                if (CampaignSyncPreferences(context).paused == expectedPaused) changed.countDown()
            }
            try {
                action.send()
                assertTrue("Notification action must persist its pause state", changed.await(5, TimeUnit.SECONDS))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(expectedPaused, CampaignSyncPreferences(context).paused)
            } finally { observation.close() }
        }
        toggle(notification.actions[0].actionIntent, false)
        val managerWork = WorkManager.getInstance(context)
        val resumed = manager.activeNotifications.single { it.id == CampaignSyncNotifications.ID }.notification
        assertEquals("Pause", resumed.actions[0].title.toString())
        assertTrue("Resume must schedule OS fallback", managerWork.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.PERIODIC).get(10, TimeUnit.SECONDS).isNotEmpty())
        toggle(resumed.actions[0].actionIntent, true)
        val paused = manager.activeNotifications.single { it.id == CampaignSyncNotifications.ID }.notification
        assertEquals("Resume", paused.actions[0].title.toString())
        // Await the cancel operations issued by the real Pause receiver through WorkManager's
        // serial task queue. Manual work has its own unique name and remains the player's choice.
        val cancelled = CountDownLatch(1)
        val cancellation = managerWork.getWorkInfosForUniqueWorkLiveData(CampaignConnectionSyncJob.PERIODIC)
        val observer = androidx.lifecycle.Observer<List<androidx.work.WorkInfo>> { rows ->
            if (rows.isNotEmpty() && rows.all { it.state.isFinished }) cancelled.countDown()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { cancellation.observeForever(observer) }
        try { assertTrue("Pause must cancel automatic OS recovery", cancelled.await(5, TimeUnit.SECONDS)) }
        finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { cancellation.removeObserver(observer) } }
        paused.actions[1].actionIntent.send()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("Sync Now must retain Pause", CampaignSyncPreferences(context).paused)
        assertTrue("Sync Now must enqueue the explicit manual job", managerWork.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.MANUAL).get(10, TimeUnit.SECONDS).isNotEmpty())
        device.openNotification(); device.waitForIdle()
        val evidence = File(context.getExternalFilesDir(null), "campaign-sync-notification-paused.png")
        assertTrue(device.takeScreenshot(evidence))
        device.pressBack()
    }
}
