package com.yazsras.astravar.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.room.Room
import androidx.work.NetworkType
import kotlinx.coroutines.runBlocking
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** Native emulator transport/control evidence. Pushy delivery, Doze wake and physical-phone latency
 * require the owner's configured Pushy app and phone; these tests do not imply them. */
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
        val data = linkedMapOf("type" to CampaignWakeProtocol.TYPE, "version" to "2",
            "issuedAt" to now.toString(), "expiresAt" to (now + 30_000).toString(), "wakeId" to UUID.randomUUID().toString())
        val input = data.values.joinToString("\n").toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hash.toByteArray(Charsets.UTF_8), "HmacSHA256")) }.doFinal(input)
        return data + ("signature" to Base64.encodeToString(mac, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }
    private fun valid(data: Map<String, String>, at: Long = now) = CampaignWakeProtocol.verified(data, hash, at)

    @Test fun nativeWakeAuthenticationAndReplayRemainStrictWithScreenOff() {
        val wake = signed()
        assertTrue(valid(wake))
        assertFalse(valid(wake + ("campaignId" to "foreign-campaign")))
        assertFalse(valid(wake + ("credentialId" to "foreign-device")))
        assertFalse(valid(wake + ("grantRevision" to "3")))
        assertFalse(valid(wake, now + 30_001))
        assertFalse(valid(wake + ("command" to "never execute Pushy gameplay")))
        val store = CampaignPushStore(context)
        val token = "fixture-only-not-a-real-pushy-token_".repeat(3)
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
        CampaignConnectionSyncJob.enqueue(context, wakeSequence = CampaignPushStore(context).lastWakeSequence)
        assertTrue(CampaignSyncPreferences(context).paused)
        assertTrue(manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.PERIODIC).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        assertTrue(manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        CampaignConnectionSyncJob.enqueue(context, manual = true)
        assertTrue(manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.MANUAL).get(10, TimeUnit.SECONDS).isNotEmpty())
        assertTrue("Manual sync must not resume automatic delivery", CampaignSyncPreferences(context).paused)
    }

    /** Injected, authenticated wake scheduling under actual emulator power states. This does not
     * send an Pushy message, wake a terminated process, or assert that a campaign record committed. */
    @Test fun nativeInjectedWakeSchedulingRespectsBatterySaverDozeAndPause() = runBlocking {
        val power = context.getSystemService(PowerManager::class.java)
        val screenWasOn = device.isScreenOn
        val lowPowerBefore = device.executeShellCommand("settings get global low_power").trim()
        val powerSaveBefore = power.isPowerSaveMode
        val batteryBefore = device.executeShellCommand("dumpsys battery")
        val idleBefore = device.executeShellCommand("dumpsys deviceidle get deep").trim()
        // This test controls only a fresh owned emulator. Refuse an existing shell battery override
        // or forced idle state because resetting those would discard another test's configuration.
        assertFalse("No earlier test may leave a battery override", batteryBefore.contains("UPDATES STOPPED", true))
        assertTrue("Power fixture must begin outside forced deep idle", idleBefore != "IDLE")
        val journal = Room.inMemoryDatabaseBuilder(context, CampaignConnectionDb::class.java).build()
        val campaignDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val vault = OwnerSyncCredentialVault(context)
        val origin = "https://power-wake.example"
        val campaign = "power-wake-fixture"
        val credentialId = "power-wake-device"
        val grantId = "power-wake-grant"
        val bearer = "p".repeat(43)
        val expiry = System.currentTimeMillis() + 600_000
        val scope = CampaignConnectionProtocol.scopeArray(setOf("hp"))
        val grant = buildJsonObject {
            put("grantId", grantId); put("revision", 1); put("expiresAt", expiry)
            put("readScopes", scope); put("writeScopes", scope); put("autoSyncScopes", scope)
        }
        vault.enroll(OwnerSyncCredential(credentialId, campaign, origin, expiry, bearer))
        journal.connection().settings(CampaignConnectionSettings(origin = origin, campaignId = campaign, deviceId = credentialId,
            requestedReadJson = scope.toString(), requestedWriteJson = scope.toString(), localAutoSyncJson = scope.toString(), grantJson = grant.toString()))
        val client = CampaignConnectionClient(context, journal, campaignDb, vault, OwnerConnectionTransport { _, _, _, _, _ ->
            error("Power-state wake validation must not perform a network request")
        })
        val manager = WorkManager.getInstance(context)
        val rows = mutableListOf<JsonObject>()
        fun awaitState(predicate: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            return predicate()
        }
        fun signedWake(): Map<String, String> {
            val issuedAt = System.currentTimeMillis()
            val data = linkedMapOf("type" to CampaignWakeProtocol.TYPE, "version" to "2",
                "issuedAt" to issuedAt.toString(), "expiresAt" to (issuedAt + 30_000).toString(), "wakeId" to UUID.randomUUID().toString())
            val signature = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(OwnerSyncProtocol.hash(bearer).toByteArray(Charsets.UTF_8), "HmacSHA256"))
            }.doFinal(data.values.joinToString("\n").toByteArray(Charsets.UTF_8))
            return data + ("signature" to Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }
        suspend fun exercise(state: String, supported: Boolean, observed: String) {
            if (!supported) {
                rows += buildJsonObject { put("state", state); put("supported", false); put("observed", observed.take(120)); put("wakeSchedulingTested", false) }
                return
            }
            CampaignSyncPreferences(context).pushyEnabled = true
            CampaignSyncPreferences(context).paused = false
            val before = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(15, TimeUnit.SECONDS).map { it.id }.toSet()
            val wake = signedWake()
            assertTrue("Locally signed wake must authenticate in $state", client.acceptWake(wake))
            CampaignConnectionSyncJob.enqueue(context, wakeSequence = CampaignPushStore(context).lastWakeSequence)
            val queued = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(15, TimeUnit.SECONDS)
            val added = queued.filter { it.id !in before }
            assertEquals("One unique worker must be appended for one authenticated wake in $state", 1, added.size)
            assertEquals(NetworkType.CONNECTED, added.single().constraints.requiredNetworkType)
            assertFalse("Duplicate wake must not schedule another worker in $state", client.acceptWake(wake))
            val afterDuplicate = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(15, TimeUnit.SECONDS).map { it.id }.toSet()
            assertEquals(queued.map { it.id }.toSet(), afterDuplicate)
            CampaignSyncPreferences(context).paused = true
            CampaignConnectionSyncJob.cancel(context)
            assertFalse("Pause must reject an otherwise valid new wake in $state", client.acceptWake(signedWake()))
            CampaignConnectionSyncJob.enqueue(context, wakeSequence = CampaignPushStore(context).lastWakeSequence)
            val afterPause = manager.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.WAKE).get(15, TimeUnit.SECONDS).map { it.id }.toSet()
            assertTrue("Pause must not add another automatic job in $state", (afterPause - afterDuplicate).isEmpty())
            rows += buildJsonObject {
                put("state", state); put("supported", true); put("observed", observed.take(120)); put("wakeSchedulingTested", true)
                put("authenticatedWakeAccepted", true); put("uniqueConstrainedWork", true); put("duplicateRejected", true); put("pauseSuppressed", true)
            }
        }
        try {
            device.executeShellCommand("cmd battery unplug")
            val saverCommand = device.executeShellCommand("cmd power set-mode 1")
            val saver = awaitState { power.isPowerSaveMode }
            exercise("battery_saver", saver, "isPowerSaveMode=${power.isPowerSaveMode}; command=$saverCommand")
            device.executeShellCommand("cmd power set-mode 0")
            awaitState { !power.isPowerSaveMode }
            device.sleep()
            val idleCommand = device.executeShellCommand("dumpsys deviceidle force-idle deep")
            val idle = awaitState { power.isDeviceIdleMode }
            val deepState = device.executeShellCommand("dumpsys deviceidle get deep").trim()
            exercise("doze_screen_off", idle && deepState == "IDLE", "isDeviceIdleMode=${power.isDeviceIdleMode}; deep=$deepState; command=$idleCommand")
        } finally {
            // Restore OS controls even if an assertion, WorkManager query or authentication fails.
            device.executeShellCommand("dumpsys deviceidle unforce")
            device.executeShellCommand("cmd battery reset")
            device.executeShellCommand("cmd power set-mode ${if (powerSaveBefore) "1" else "0"}")
            if (lowPowerBefore == "null") device.executeShellCommand("settings delete global low_power")
            val powerRestored = awaitState { power.isPowerSaveMode == powerSaveBefore }
            if (screenWasOn) device.wakeUp() else device.sleep()
            CampaignSyncPreferences(context).paused = true
            CampaignConnectionSyncJob.cancel(context)
            vault.forget(origin, campaign)
            client.close()
            val evidence = buildJsonObject {
                put("syntheticOnly", true); put("injectedWake", true); put("actualPushyDelivery", false)
                put("actualRoomCommit", false); put("actualDozeWakeLatency", false); put("powerStates", JsonArray(rows))
                put("restoredPowerSaveMode", power.isPowerSaveMode); put("powerSaveModeBefore", powerSaveBefore); put("powerModeRestored", powerRestored)
                put("deepStateAfterRestore", device.executeShellCommand("dumpsys deviceidle get deep").trim())
            }
            File(context.getExternalFilesDir(null), "campaign-power-state-scheduling.json").writeText(evidence.toString())
            assertTrue("Power saver mode must be restored after the fixture", powerRestored)
            assertFalse("Forced deep idle must be released after the fixture", power.isDeviceIdleMode)
        }
    }

    @Test fun nativeQuietNotificationHasImmutableActionsAndPausedScreenshot() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        CampaignSyncPreferences(context).notificationsEnabled = true
        val manager = context.getSystemService(NotificationManager::class.java)
        val observations = mutableListOf<JsonObject>()
        fun observe(phase: String, result: String) {
            observations += buildJsonObject {
                put("phase", phase); put("result", result); put("deviceAt", System.currentTimeMillis())
                put("canShow", CampaignSyncNotifications.canShow(context))
                put("notificationPermission", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                put("channelImportance", manager.getNotificationChannel(CampaignSyncNotifications.CHANNEL)?.importance ?: -1)
                put("active", manager.activeNotifications.joinToString {
                    "${it.id}:${it.notification.extras.getCharSequence("android.title")}:actions=${it.notification.actions?.joinToString { action -> action.title.toString() }}"
                })
            }
            File(context.getExternalFilesDir(null), "campaign-sync-notification-observations.json")
                .writeText(JsonArray(observations).toString())
        }
        // notify() is enqueued in system_server; app-main-thread idleness does not make the
        // notification active. Observe the actual posted state, including after action updates.
        fun awaitNotification(phase: String, actionTitle: String, titleText: String? = null): android.app.Notification {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val notification = manager.activeNotifications.firstOrNull { it.id == CampaignSyncNotifications.ID }?.notification
                if (notification != null && notification.actions?.firstOrNull()?.title?.toString() == actionTitle &&
                    (titleText == null || notification.extras.getCharSequence("android.title").toString().contains(titleText))) {
                    observe(phase, "posted")
                    return notification
                }
                SystemClock.sleep(50)
            }
            observe(phase, "not observed within 10 seconds")
            throw AssertionError("Expected $phase notification with action $actionTitle; diagnostics: ${observations.last()}")
        }
        observe("permission granted", "before notify")
        assertTrue("Opt-in, runtime grant and channel must permit posting: ${observations.last()}", CampaignSyncNotifications.canShow(context))
        CampaignSyncNotifications.update(context, buildJsonObject { put("connected", true); put("lastSyncAt", System.currentTimeMillis()) })
        observe("post requested", "before posted-state observation")
        val channel = manager.getNotificationChannel(CampaignSyncNotifications.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        val notification = awaitNotification("initial Paused", "Resume", "Paused")
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
        val resumed = awaitNotification("after Resume action", "Pause")
        assertEquals("Pause", resumed.actions[0].title.toString())
        assertTrue("Resume must schedule OS fallback", managerWork.getWorkInfosForUniqueWork(CampaignConnectionSyncJob.PERIODIC).get(10, TimeUnit.SECONDS).isNotEmpty())
        toggle(resumed.actions[0].actionIntent, true)
        val paused = awaitNotification("after Pause action", "Resume", "Paused")
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
        val finalPaused = awaitNotification("after manual Sync Now", "Resume", "Paused")
        val pausedTitle = finalPaused.extras.getCharSequence("android.title").toString()
        val titleSelector = By.pkg("com.android.systemui").text(pausedTitle)
        val evidenceDirectory = context.getExternalFilesDir(null)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        try {
            val opened = device.openNotification()
            var titleVisible = device.wait(Until.hasObject(titleSelector), 5_000)
            var fallbackResult = "not needed"
            if (!titleVisible) {
                // A successful request to expand the shade is not proof that its card is visible.
                // Retry through SystemUI only after observing that the real title is absent.
                fallbackResult = device.executeShellCommand("cmd statusbar expand-notifications").trim()
                titleVisible = device.wait(Until.hasObject(titleSelector), 5_000)
            }
            val hierarchy = File(evidenceDirectory, "campaign-sync-notification-paused-hierarchy.xml")
            device.dumpWindowHierarchy(hierarchy)
            observe("paused shade screenshot", "openNotification=$opened; fallback=$fallbackResult; titleVisible=$titleVisible")
            if (!titleVisible) {
                device.takeScreenshot(File(evidenceDirectory, "campaign-sync-notification-paused-not-visible.png"))
                throw AssertionError("Paused notification title was not visible in SystemUI; hierarchy=${hierarchy.name}; diagnostics=${observations.last()}")
            }
            val title = device.findObject(titleSelector)
            assertNotNull("The paused SystemUI notification must remain visible before capture", title)
            val bounds = title.visibleBounds
            assertTrue("The paused notification title must occupy visible screen space: $bounds", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("Capture the observed paused notification card", device.takeScreenshot(File(evidenceDirectory, "campaign-sync-notification-paused.png")))
        } finally { device.pressBack() }
    }
}
