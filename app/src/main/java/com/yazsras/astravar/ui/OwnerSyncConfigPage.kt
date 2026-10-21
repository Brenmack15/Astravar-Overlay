package com.yazsras.astravar.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yazsras.astravar.data.OwnerConnectionClient
import com.yazsras.astravar.data.OwnerSyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.URI
import java.io.ByteArrayOutputStream

/** Native owner connection. Only the explicit fictional acceptance scope is available. */
@Composable fun OwnerSyncConfigPage() {
    val context = LocalContext.current.applicationContext
    val client = remember(context) { OwnerConnectionClient(context) }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(buildJsonObject {}) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    fun operation(action: suspend () -> JsonObject) {
        if (busy) return
        busy = true; failed = false; message = ""
        scope.launch {
            try {
                snapshot = action()
                // Success text and connection state come from the persisted client result.
                message = snapshot.text("message")
            } catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) {
                failed = true
                message = when (e.code) {
                    "AUTHENTICATION_REQUIRED" -> "Pair this device through the owner authorization page."
                    "CREDENTIAL_REVOKED_OR_EXPIRED", "CREDENTIAL_SCOPE_OR_EXPIRY", "INVALID_AUTHORIZATION", "UNAUTHORIZED" -> "Device authorization expired or was revoked. Pair again through the owner page."
                    "ACCESS_DENIED", "FORBIDDEN" -> "This device does not have the requested permission."
                    "REVISION_CONFLICT", "STALE_REVISION" -> "The record changed. Refresh and review a new proposal before approving."
                    "COMMAND_ID_REUSED" -> "This command ID belongs to different content. No replacement was made."
                    "PAIRING_PENDING", "AUTHORIZATION_PENDING" -> "Owner approval is still pending. Authorize the displayed device, then check again."
                    "PAIRING_EXPIRED", "EXPIRED_TOKEN" -> "Pairing expired. Start a new pairing request."
                    "INVALID_AUTHORIZATION_BUNDLE" -> "The encrypted authorization file could not be verified for this device. Start private service authorization again."
                    "QUOTA_REACHED", "RATE_LIMITED" -> "The service limit was reached. Saved decisions remain queued; retry later."
                    else -> "Connection could not be verified (${e.code.replace('_', ' ').lowercase()}). Saved decisions remain on this device."
                }
                try { snapshot = client.snapshot() } catch (_: Exception) { /* retain last verified display */ }
            } catch (_: Exception) {
                failed = true
                message = "The connection could not be verified. Check your network and service address, then retry. Saved decisions remain on this device."
                try { snapshot = client.snapshot() } catch (_: Exception) { /* retain last verified display */ }
            } finally { busy = false }
        }
    }
    LaunchedEffect(client) {
        try {
            snapshot = client.snapshot()
            if (snapshot["connected"]?.jsonPrimitive?.booleanOrNull == true) operation { client.refresh() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Saved connection could not be read. No records were sent."; failed = true }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshOnResume by rememberUpdatedState(newValue = {
        if (snapshot["connected"]?.jsonPrimitive?.booleanOrNull == true) operation { client.refresh() }
    })
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshOnResume() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun openOwnerPage(url: String, origin: String) {
        val uri = verifiedOwnerPage(url, origin)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val importAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
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
            try {
                require(bytes.isNotEmpty())
                val saved = client.importServiceAuthorization(bytes)
                JsonObject(saved + ("message" to JsonPrimitive(if (saved["connected"]?.jsonPrimitive?.booleanOrNull == true) "Encrypted service authorization renewed for this device. Sync to verify access." else "Encrypted service authorization imported for this device. Complete device pairing to connect.")))
            } catch (e: IllegalArgumentException) {
                throw OwnerSyncFailure("INVALID_AUTHORIZATION_BUNDLE")
            } finally { bytes.fill(0) }
        }
    }
    val view = ownerConnectionView(snapshot).copy(busy = busy, message = message, error = failed)
    OwnerConnectionContent(view,
        onPair = { origin, campaign, device -> operation { client.beginPairing(origin, campaign, device) } },
        onCheckPairing = { operation { client.finishPairing() } },
        onOpenPairing = { url ->
            try {
                openOwnerPage(url, view.origin)
            } catch (_: Exception) { failed = true; message = "The owner authorization page could not be opened. Verify the service address." }
        },
        onRefresh = { operation { client.refresh() } },
        onRetry = { operation { client.sync() } },
        onDecision = { id, approve -> operation { client.decide(id, approve) } },
        onDisconnect = { operation { client.revoke() } },
        onCreateFixture = { operation { client.createFictionalFixture() } },
        onPrivateAuthorization = { origin, campaign -> operation {
            val url = client.startServiceAuthorization(origin, campaign)
            openOwnerPage(url, origin)
            val code = URI(url).fragment?.split('&')?.firstOrNull { it.startsWith("nonce=") }?.substringAfter("nonce=")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }?.take(12)
            JsonObject(client.snapshot() + ("message" to JsonPrimitive("Owner authorization page opened.${code?.let { " Compare request code $it on the owner page." } ?: ""} After approving, import the encrypted authorization file.")))
        } },
        onImportAuthorization = { importAuthorization.launch(arrayOf("application/json", "application/octet-stream")) },
        onPublishFixture = { revision -> operation { client.publishFictionalAbility(revision) } },
        onEditFixture = { score, revision -> operation { client.saveFictionalAbility(score, revision) } }
    )
}

private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.rows(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** A narrow adapter prevents credential fields or arbitrary campaign JSON from reaching UI. */
internal fun ownerConnectionView(snapshot: JsonObject): OwnerConnectionView {
    val pairing = snapshot["pairing"] as? JsonObject
    val fixture = snapshot["fixture"] as? JsonObject
    val terminalCommands = snapshot.rows("history").filter { it["receiptJson"] != null && it["receiptJson"] !is JsonNull }.map { it.text("commandId") }.toSet()
    val pending = snapshot.rows("pending").map { row ->
        val expected = row.number("expectedRevision") ?: -1
        OwnerChangeView(
            commandId = row.text("commandId"), recordId = row.text("recordId"),
            title = row.text("title").ifBlank { "Fictional character · ${row.text("ability").ifBlank { "Intelligence" }}" },
            expectedRevision = expected, currentRevision = row.number("currentRevision") ?: -1,
            source = row.text("source").ifBlank { "Unspecified source" },
            fields = listOf(OwnerFieldDifference(row.text("ability").ifBlank { "Intelligence" },
                row["currentScore"]?.let { if (it is JsonNull) "Unknown" else (it as? JsonPrimitive)?.contentOrNull ?: "Unknown" } ?: "Unknown",
                row["proposedScore"]?.let { if (it is JsonNull) "Unknown" else (it as? JsonPrimitive)?.contentOrNull ?: "Unknown" } ?: "Unknown")),
            state = row.text("state").ifBlank { "PENDING" },
            decisionQueued = row["decisionQueued"]?.jsonPrimitive?.booleanOrNull ?: false,
            terminalReceipt = row.text("commandId") in terminalCommands, detail = row.text("message")
        )
    }
    val history = snapshot.rows("history").map { row ->
        val receipt = when (val raw = row["receiptJson"]) {
            is JsonObject -> raw
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrNull()
            else -> null
        }
        OwnerReceiptView(row.text("commandId"), row.text("state").ifBlank { "PENDING" },
            receipt?.number("resultingRevision") ?: row.number("resultingRevision"),
            receipt?.text("recordedAt")?.ifBlank { null } ?: row.text("recordedAt"))
    }
    val connected = snapshot["connected"]?.jsonPrimitive?.booleanOrNull ?: false
    return OwnerConnectionView(
        origin = snapshot.text("origin"), campaignId = snapshot.text("campaignId").ifBlank { "synthetic-owner-acceptance" },
        deviceName = snapshot.text("deviceName").ifBlank { "Samsung Galaxy S26" }, enrolled = connected,
        account = snapshot.text("accountLabel"), deviceId = snapshot.text("deviceId"),
        health = snapshot.text("status").ifBlank { if (connected) "Paired · awaiting verified sync" else "Not connected" }.replace('_', ' '),
        lastSuccessfulSync = snapshot.number("lastSyncAt")?.takeIf { it > 0 },
        scopes = (snapshot["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
        pairingCode = pairing?.text("userCode")?.ifBlank { null }, pairingUrl = pairing?.text("verificationUri")?.ifBlank { null },
        pending = pending, receipts = history,
        fictionalScore = fixture?.number("score")?.toInt(), fictionalRevision = fixture?.number("revision"),
        fictionalRemoteRevision = fixture?.number("remoteRevision")?.takeIf { it > 0 }
    )
}

/** Authorization links stay on the verified service origin, including its port. */
internal fun verifiedOwnerPage(url: String, origin: String): URI {
    val uri = URI(url)
    val selected = URI(origin)
    require(uri.scheme == "https" && uri.scheme == selected.scheme && uri.host != null &&
        uri.host.equals(selected.host, ignoreCase = true) && uri.port == selected.port && uri.port == -1 &&
        uri.rawUserInfo == null && selected.rawUserInfo == null && uri.isAbsolute)
    return uri
}
