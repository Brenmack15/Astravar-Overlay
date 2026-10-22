package com.yazsras.astravar.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [32])
class CampaignSyncNotificationsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() {
        context.getSharedPreferences("campaign-sync-controls-v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @Test fun queued_and_failed_updates_never_display_synced_even_with_an_older_verified_time() {
        val now = 2_000L
        val base = buildJsonObject { put("connected", true); put("status", "Connected"); put("lastSyncAt", 1_000L) }
        assertEquals("SYNCED", campaignSyncStatus(base, false, now).state)
        val pending = JsonObject(base + ("pendingCount" to JsonPrimitive(2)))
        assertEquals("PENDING", campaignSyncStatus(pending, false, now).state)
        assertEquals(2, campaignSyncStatus(pending, false, now).pending)
        assertEquals("OFFLINE", campaignSyncStatus(JsonObject(base + ("lastError" to JsonPrimitive("REMOTE_UNAVAILABLE"))), false, now).state)
        assertEquals("CONFLICT", campaignSyncStatus(JsonObject(base + ("status" to JsonPrimitive("Conflict requires review"))), false, now).state)
        assertEquals("PAUSED", campaignSyncStatus(pending, true, now).state)
    }
    @Test fun connected_without_verified_device_time_is_not_synced_and_future_time_is_rejected() {
        assertEquals("DISCONNECTED", campaignSyncStatus(buildJsonObject { put("connected", true) }, false, 1000).state)
        assertNull(campaignSyncStatus(buildJsonObject { put("connected", true); put("lastSyncAt", 100_000L) }, false, 1000).verifiedAt)
    }
    @Test fun byte_bounded_server_backlog_never_displays_synced_or_an_invented_exact_count() {
        val snapshot = buildJsonObject {
            put("connected", true); put("status", "Pending delivery · more queued transactions")
            put("lastSyncAt", 1_000L); put("serverPendingUnfetched", true)
        }
        val status = campaignSyncStatus(snapshot, false, 2_000L)
        assertEquals("PENDING_MORE", status.state)
        assertEquals("Pending more", status.label())
        CampaignSyncNotifications.update(context, snapshot)
        val retained = Json.parseToJsonElement(CampaignSyncPreferences(context).savedStatus).jsonObject
        assertTrue(retained.getValue("serverPendingUnfetched").jsonPrimitive.boolean)
        assertEquals("PENDING_MORE", campaignSyncStatus(retained, false, 2_000L).state)
    }
    @Test fun notification_is_opt_in_and_retains_only_transport_metadata() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val snapshot = buildJsonObject {
            put("connected", true); put("status", "Connected"); put("lastSyncAt", System.currentTimeMillis())
            put("deviceToken", "MUST-NOT-PERSIST"); putJsonArray("profiles") { add("private campaign details") }
        }
        CampaignSyncNotifications.update(context, snapshot)
        assertEquals(0, manager.activeNotifications.size)
        val preferences = CampaignSyncPreferences(context)
        assertFalse(preferences.notificationsEnabled)
        assertFalse(preferences.savedStatus.contains("MUST-NOT-PERSIST"))
        assertFalse(preferences.savedStatus.contains("private campaign"))
        preferences.notificationsEnabled = true
        CampaignSyncNotifications.update(context, snapshot)
        assertEquals(1, manager.activeNotifications.size)
        val notification = manager.activeNotifications.single().notification
        assertEquals(3, notification.actions.size)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(CampaignSyncNotifications.CHANNEL).importance)
        preferences.paused = true
        CampaignSyncNotifications.refresh(context)
        assertTrue(manager.activeNotifications.single().notification.extras.getString("android.title").orEmpty().contains("Paused"))
        preferences.notificationsEnabled = false
        CampaignSyncNotifications.refresh(context)
        assertEquals(0, manager.activeNotifications.size)
    }
}
