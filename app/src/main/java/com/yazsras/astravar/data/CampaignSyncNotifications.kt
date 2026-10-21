package com.yazsras.astravar.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yazsras.astravar.MainActivity
import com.yazsras.astravar.R
import kotlinx.serialization.json.*
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Device-local transport controls. These never change owner grants or discard the journal. */
class CampaignSyncPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("campaign-sync-controls-v1", Context.MODE_PRIVATE)
    var paused: Boolean
        get() = preferences.getBoolean("paused", false)
        set(value) { if (!preferences.edit().putBoolean("paused", value).commit()) throw IOException("Automatic sync setting could not be saved.") }
    var notificationsEnabled: Boolean
        get() = preferences.getBoolean("notificationsEnabled", false)
        set(value) { if (!preferences.edit().putBoolean("notificationsEnabled", value).commit()) throw IOException("Notification setting could not be saved.") }
    internal var savedStatus: String
        get() = preferences.getString("status", "{}") ?: "{}"
        set(value) { preferences.edit().putString("status", value).apply() }
    fun observeControls(changed: () -> Unit): AutoCloseable {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("paused", "notificationsEnabled")) changed()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

internal data class CampaignSyncStatus(val state: String, val verifiedAt: Long?, val pending: Int) {
    fun label(): String = when (state) {
        "SYNCED" -> "Synced at ${formatCampaignSyncTime(verifiedAt!!)}"
        "PENDING" -> "Pending $pending"
        "PAUSED" -> "Paused"
        "SYNCING" -> "Syncing"
        "SCHEDULED" -> "Sync scheduled"
        "OFFLINE" -> "Offline"
        "CONFLICT" -> "Conflict"
        else -> "Not connected"
    }
}

internal fun formatCampaignSyncTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))

/** Notification text is derived only from verified metadata; queued work is never a saved record. */
internal fun campaignSyncStatus(snapshot: JsonObject, paused: Boolean, now: Long = System.currentTimeMillis()): CampaignSyncStatus {
    fun text(key: String) = (snapshot[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val pendingRows = (snapshot["pending"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val pendingCount = (snapshot["pendingCount"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: pendingRows.size
    val conflict = text("status").contains("conflict", true) || pendingRows.any { (it["state"] as? JsonPrimitive)?.contentOrNull in setOf("CONFLICT", "DECISION_CONFLICT") }
    // lastSyncAt is recorded using the device clock only after the mirror fingerprint/receipt checks succeed.
    val verifiedAt = (snapshot["lastSyncAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 && it <= now + 60_000 }
    val connected = (snapshot["connected"] as? JsonPrimitive)?.booleanOrNull == true
    val state = when {
        paused -> "PAUSED"
        conflict -> "CONFLICT"
        text("status") == "Sync scheduled" -> "SCHEDULED"
        text("status") == "Syncing" -> "SYNCING"
        text("lastError").isNotBlank() || text("status").contains("offline", true) -> "OFFLINE"
        !connected -> "DISCONNECTED"
        pendingCount > 0 -> "PENDING"
        verifiedAt != null -> "SYNCED"
        else -> "DISCONNECTED"
    }
    return CampaignSyncStatus(state, verifiedAt, pendingCount)
}

/** A quiet ordinary notification, not a foreground service or a claim of continuous connectivity. */
object CampaignSyncNotifications {
    const val CHANNEL = "campaign-background-sync-v1"
    const val ID = 0x415343
    const val OPEN_SETTINGS = "com.yazsras.astravar.OPEN_CAMPAIGN_SYNC_SETTINGS"

    private fun channel(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "Astravar Background Sync", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Quiet campaign sync status and pause controls"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun canShow(context: Context): Boolean {
        if (!CampaignSyncPreferences(context).notificationsEnabled) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() && channel(context).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun update(context: Context, snapshot: JsonObject) {
        // Retain only transport metadata, never command IDs, profiles, token values or gameplay fields.
        val status = campaignSyncStatus(snapshot, false)
        CampaignSyncPreferences(context).savedStatus = buildJsonObject {
            put("connected", (snapshot["connected"] as? JsonPrimitive)?.booleanOrNull == true)
            put("status", (snapshot["status"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(80))
            put("lastError", (snapshot["lastError"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(80))
            put("lastSyncAt", status.verifiedAt ?: 0)
            put("pendingCount", status.pending)
            if (status.state == "CONFLICT") put("status", "Conflict requires review")
        }.toString()
        refresh(context)
    }

    fun wakeReceived(context: Context) {
        val preferences = CampaignSyncPreferences(context)
        if (preferences.paused) { refresh(context); return }
        val saved = runCatching { Json.parseToJsonElement(preferences.savedStatus).jsonObject }.getOrDefault(buildJsonObject {})
        preferences.savedStatus = JsonObject(saved + ("status" to JsonPrimitive("Sync scheduled"))).toString()
        refresh(context)
    }

    fun refresh(context: Context) {
        if (!canShow(context)) { NotificationManagerCompat.from(context).cancel(ID); return }
        val preferences = CampaignSyncPreferences(context)
        val saved = runCatching { Json.parseToJsonElement(preferences.savedStatus).jsonObject }.getOrDefault(buildJsonObject {})
        val status = campaignSyncStatus(saved, preferences.paused)
        val open = PendingIntent.getActivity(context, 303, Intent(context, MainActivity::class.java).setAction(OPEN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, request: Int): PendingIntent = PendingIntent.getBroadcast(context, request,
            Intent(context, CampaignSyncActionReceiver::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = when {
            status.state == "PAUSED" -> "Automatic sync stopped. Queued transactions and permissions are retained."
            status.state == "CONFLICT" -> "Review the preserved transaction in Owner connection."
            status.state == "SCHEDULED" -> "Wake received. Waiting for Android to run synchronization."
            status.state == "SYNCED" -> "Last verified device sync. Android may defer later background delivery."
            status.verifiedAt != null -> "Last verified device sync: ${formatCampaignSyncTime(status.verifiedAt)}"
            else -> "No successful device sync has been verified."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_crystal)
            .setContentTitle("Astravar Background Sync · ${status.label()}")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true).setOnlyAlertOnce(true).setOngoing(true)
            .setShowWhen(false)
            .addAction(0, if (preferences.paused) "Resume" else "Pause", action(if (preferences.paused) CampaignSyncActionReceiver.RESUME else CampaignSyncActionReceiver.PAUSE, 300))
            .addAction(0, "Sync Now", action(CampaignSyncActionReceiver.SYNC, 301))
            .addAction(0, "Settings", open)
            .build()
        try { NotificationManagerCompat.from(context).notify(ID, notification) }
        catch (_: SecurityException) { /* Permission can be revoked between inspection and publication. */ }
    }
}

/** Explicit, immutable app-owned PendingIntents only; no transaction data enters through an action. */
class CampaignSyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = CampaignSyncPreferences(context)
        try {
            when (intent.action) {
                // SharedPreferences updates memory before disk; even a disk failure must stop this session.
                PAUSE -> try { preferences.paused = true } finally { CampaignConnectionSyncJob.cancel(context) }
                RESUME -> { preferences.paused = false; CampaignConnectionSyncJob.schedule(context); CampaignConnectionSyncJob.enqueue(context) }
                SYNC -> CampaignConnectionSyncJob.enqueue(context, manual = true)
                else -> return
            }
        } catch (_: IOException) {
            android.widget.Toast.makeText(context, "Sync setting could not be saved. Open Astravar to review it before restarting the app.", android.widget.Toast.LENGTH_LONG).show()
        } finally {
            CampaignSyncNotifications.refresh(context)
        }
    }
    companion object {
        const val PAUSE = "com.yazsras.astravar.PAUSE_CAMPAIGN_SYNC"
        const val RESUME = "com.yazsras.astravar.RESUME_CAMPAIGN_SYNC"
        const val SYNC = "com.yazsras.astravar.SYNC_CAMPAIGN_NOW"
    }
}
