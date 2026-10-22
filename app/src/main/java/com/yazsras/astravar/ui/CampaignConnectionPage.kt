package com.yazsras.astravar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yazsras.astravar.data.CampaignConnectionClient
import com.yazsras.astravar.data.CampaignConnectionSyncJob
import com.yazsras.astravar.data.CampaignSyncNotifications
import com.yazsras.astravar.data.CampaignSyncPreferences
import com.yazsras.astravar.data.OwnerSyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Player-facing manager. No UI state is accepted as evidence of a successful device commit. */
@Composable fun CampaignConnectionPage() {
    val context = LocalContext.current.applicationContext
    val client = remember(context) { CampaignConnectionClient(context) }
    val syncPreferences = remember(context) { CampaignSyncPreferences(context) }
    var syncPaused by remember { mutableStateOf(syncPreferences.paused) }
    var syncNotifications by remember { mutableStateOf(syncPreferences.notificationsEnabled) }
    var pushyEnabled by remember { mutableStateOf(syncPreferences.pushyEnabled) }
    var notificationAvailable by remember { mutableStateOf(CampaignSyncNotifications.canShow(context)) }
    DisposableEffect(syncPreferences) {
        val observer = syncPreferences.observeControls {
            syncPaused = syncPreferences.paused
            syncNotifications = syncPreferences.notificationsEnabled
            pushyEnabled = syncPreferences.pushyEnabled
            notificationAvailable = CampaignSyncNotifications.canShow(context)
        }
        onDispose { observer.close() }
    }
    DisposableEffect(client) { onDispose { client.close() } }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var snapshot by remember { mutableStateOf(buildJsonObject {}) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var diagnostics by rememberSaveable { mutableStateOf(false) }

    fun operate(action: suspend () -> JsonObject) {
        if (busy) return
        busy = true
        coroutineScope.launch {
            try {
                snapshot = action()
                error = false
                message = (snapshot["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) {
                error = true
                message = when (e.code) {
                    "AUTHENTICATION_REQUIRED", "CREDENTIAL_REVOKED_OR_EXPIRED", "CREDENTIAL_SCOPE_OR_EXPIRY" -> "Authentication expired or access was revoked. Renew authorization or pair this device again."
                    "PAIRING_PENDING", "AUTHORIZATION_PENDING" -> "Waiting for owner authorization. Astravar will check again while this screen is open."
                    "PAIRING_EXPIRED", "PAIRING_EXPIRED_OR_UNKNOWN" -> "The pairing code expired. Start a new pairing request."
                    "REVISION_CONFLICT", "STALE_REVISION", "COMMAND_ID_REUSED" -> "Conflict requires review. The original transaction is preserved; refresh before deciding how to correct it."
                    "ACCESS_DENIED", "SCOPE_DENIED", "FORBIDDEN" -> "This campaign operation is outside the saved owner permissions."
                    "PRIVATE_GATEWAY_AUTH_REQUIRED" -> "Private service authentication expired. Renew private service authorization below."
                    "INVALID_AUTHORIZATION_BUNDLE" -> "This encrypted authorization file could not be verified for this installation."
                    else -> "Synchronization is pending or could not be verified (${e.code.replace('_', ' ').lowercase()}). Saved records and pending transactions are preserved."
                }
                runCatching { client.snapshot() }.getOrNull()?.let { snapshot = it }
            } catch (_: Exception) {
                error = true
                message = "Offline or synchronization could not be verified. Saved records and pending transactions are preserved. Retry Sync now when connected."
                runCatching { client.snapshot() }.getOrNull()?.let { snapshot = it }
            } finally { busy = false }
        }
    }
    fun openOwner(url: String, origin: String) {
        val verified = verifiedOwnerPage(url, origin)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verified.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    LaunchedEffect(client) {
        try { snapshot = client.snapshot(); if (!syncPreferences.paused) operate { client.foreground() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true; message = "The saved campaign connection could not be read. Existing campaign records are preserved." }
    }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestDiagnostics by rememberUpdatedState(diagnostics)
    val refresh by rememberUpdatedState(newValue = {
        if (!latestDiagnostics) {
            val connected = (latestSnapshot["connected"] as? JsonPrimitive)?.booleanOrNull == true
            val pairing = latestSnapshot["pairing"] as? JsonObject
            val hasCode = (pairing?.get("userCode") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
            notificationAvailable = CampaignSyncNotifications.canShow(context)
            if (syncPreferences.paused) operate { client.snapshot() }
            // MainActivity already owns the active-UI transport timer. This page only redraws its journal.
            else if (connected) operate { client.snapshot() }
            else if (hasCode) operate { client.finishPairing() }
        }
    })
    LaunchedEffect(client, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh()
            while (true) { delay(5_000); refresh() }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAvailable = CampaignSyncNotifications.canShow(context)
        CampaignSyncNotifications.refresh(context)
        operate { client.snapshot() }
    }
    val importAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operate {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 32_768) throw OwnerSyncFailure("INVALID_AUTHORIZATION_BUNDLE")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw OwnerSyncFailure("INVALID_AUTHORIZATION_BUNDLE")
            }
            try { require(bytes.isNotEmpty()); client.importServiceAuthorization(bytes) }
            catch (_: IllegalArgumentException) { throw OwnerSyncFailure("INVALID_AUTHORIZATION_BUNDLE") }
            finally { bytes.fill(0) }
        }
    }
    if (diagnostics) Column(Modifier.fillMaxSize()) {
        PlayerTextButton(onClick = { diagnostics = false }) { Text("‹ Campaign connection") }
        Text("Advanced · legacy HP and fictional test connections")
        Box(Modifier.weight(1f)) { LiveHpConnectionPage() }
    } else {
        val view = campaignConnectionView(snapshot).copy(busy = busy, error = error, message = message,
            automaticSyncPaused = syncPaused, syncNotificationEnabled = syncNotifications,
            syncNotificationAvailable = notificationAvailable, pushEnabled = pushyEnabled)
        CampaignConnectionContent(view,
            onPair = { origin, profile, name, reads, writes -> operate {
                val result = client.beginPairing(origin, profile, name, reads, writes)
                snapshot = result
                val url = ((result["pairing"] as? JsonObject)?.get("verificationUri") as? JsonPrimitive)?.contentOrNull
                if (!url.isNullOrBlank()) openOwner(url, origin)
                result
            } },
            onOpenOwner = { url -> try { openOwner(url, view.origin) } catch (_: Exception) { error = true; message = "The owner authorization page could not be opened. Verify the private service address." } },
            onSync = { operate { client.sync() } },
            onCheckPairing = { operate { client.finishPairing() } },
            onAutoSync = { scopes -> operate { client.setAutoSync(scopes) } },
            onDecision = { id, approve -> operate { client.decide(id, approve) } },
            onRevoke = { operate { client.revoke() } },
            onRenew = { origin, profile -> operate { val url = client.startServiceAuthorization(origin, profile); openOwner(url, origin); client.snapshot() } },
            onImportAuthorization = { importAuthorization.launch(arrayOf("application/json", "application/octet-stream")) },
            onDiagnostics = { diagnostics = true },
            onRestrictPermissions = { readScopes, writeScopes -> operate { client.restrictPermissions(readScopes, writeScopes) } },
            onPauseSync = { paused ->
                try {
                    syncPreferences.paused = paused
                    syncPaused = paused
                    if (!paused) { CampaignConnectionSyncJob.schedule(context); CampaignConnectionSyncJob.enqueue(context) }
                    operate { client.snapshot() }
                } catch (_: IOException) {
                    syncPaused = syncPreferences.paused
                    error = true
                    message = "Android could not save this setting. The displayed pause state applies to this session; review it before restarting Astravar."
                } finally {
                    if (paused) CampaignConnectionSyncJob.cancel(context)
                    CampaignSyncNotifications.refresh(context)
                }
            },
            onPushy = { enabled ->
                try {
                    syncPreferences.pushyEnabled = enabled
                    pushyEnabled = enabled
                    if (!syncPreferences.paused) CampaignConnectionSyncJob.enqueue(context)
                    operate { client.snapshot() }
                } catch (_: IOException) {
                    pushyEnabled = syncPreferences.pushyEnabled
                    error = true
                    message = "Android could not save the Pushy preference. Cloudflare synchronization remains available."
                }
            },
            onSyncNotification = { enabled ->
                try {
                    syncPreferences.notificationsEnabled = enabled
                    syncNotifications = enabled
                    if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (enabled && !CampaignSyncNotifications.canShow(context)) {
                        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, CampaignSyncNotifications.CHANNEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    operate { client.snapshot() }
                } catch (_: IOException) {
                    syncNotifications = syncPreferences.notificationsEnabled
                    error = true
                    message = "Android could not save the notification preference. Review it before restarting Astravar."
                } finally {
                    notificationAvailable = CampaignSyncNotifications.canShow(context)
                    CampaignSyncNotifications.update(context, snapshot)
                }
            }
        )
    }
}
