package com.yazsras.astravar.ui

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yazsras.astravar.data.CampaignConnectionClient
import com.yazsras.astravar.data.OwnerSyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

/** Player-facing manager. No UI state is accepted as evidence of a successful device commit. */
@Composable fun CampaignConnectionPage() {
    val context = LocalContext.current.applicationContext
    val client = remember(context) { CampaignConnectionClient(context) }
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
        try { snapshot = client.snapshot(); operate { client.foreground() } }
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
            if (connected) operate { client.foreground() }
            else if (hasCode) operate { client.finishPairing() }
        }
    })
    LaunchedEffect(client, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh()
            while (true) { delay(5_000); refresh() }
        }
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
        val view = campaignConnectionView(snapshot).copy(busy = busy, error = error, message = message)
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
            onRestrictPermissions = { readScopes, writeScopes -> operate { client.restrictPermissions(readScopes, writeScopes) } }
        )
    }
}
