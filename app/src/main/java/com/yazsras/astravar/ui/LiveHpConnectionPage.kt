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
import com.yazsras.astravar.data.LiveHpConnectionClient
import com.yazsras.astravar.data.OwnerSyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

/** Existing character HP, with explicit pairing and device-side approval. No fixture or seeded HP. */
@Composable fun LiveHpConnectionPage() {
    val context = LocalContext.current.applicationContext
    val client = remember(context) { LiveHpConnectionClient(context) }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var snapshot by remember { mutableStateOf(buildJsonObject {}) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    fun operation(action: suspend () -> JsonObject) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                snapshot = action(); error = false; message = snapshot.hpText("message")
            } catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) {
                error = true
                message = when (e.code) {
                    "AUTHENTICATION_REQUIRED", "CREDENTIAL_REVOKED_OR_EXPIRED" -> "This HP connection needs owner authorization. Pair or renew the connection."
                    "PAIRING_PENDING", "AUTHORIZATION_PENDING" -> "Waiting for owner approval. Astravar will check again automatically."
                    "PAIRING_EXPIRED", "PAIRING_EXPIRED_OR_UNKNOWN" -> "Pairing expired. Start pairing again."
                    "REVISION_CONFLICT", "COMMAND_ID_REUSED" -> "The saved profile or command changed. The original attempt remains available for conflict review."
                    "ACCESS_DENIED", "SCOPE_DENIED" -> "The owner service did not authorize this HP operation."
                    "PRIVATE_GATEWAY_AUTH_REQUIRED" -> "Private service authorization needs renewal. Your device grant is preserved. Open Service authorization below."
                    "UNVERIFIED_AUTH_RESPONSE" -> "The authorization response could not be verified. Your device grant is preserved; retry when the service is reachable."
                    "INVALID_AUTHORIZATION_BUNDLE" -> "The encrypted authorization file could not be verified for this installation."
                    else -> "HP synchronization could not be verified. Saved changes and decisions remain on this device."
                }
                runCatching { client.snapshot() }.getOrNull()?.let { snapshot = it }
            } catch (_: Exception) {
                error = true; message = "HP synchronization could not be verified. Check the saved profile, service access and connection. No unreviewed HP change was approved."
                runCatching { client.snapshot() }.getOrNull()?.let { snapshot = it }
            } finally { busy = false }
        }
    }
    fun openOwnerPage(url: String, origin: String) {
        val verified = verifiedOwnerPage(url, origin)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verified.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    LaunchedEffect(client) {
        try { snapshot = client.snapshot(); if (snapshot["connected"]?.jsonPrimitive?.booleanOrNull == true) operation { client.refresh() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true; message = "Saved HP connection could not be read. No character was created or changed." }
    }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentDiagnostics by rememberUpdatedState(diagnostics)
    val poll by rememberUpdatedState(newValue = {
        if (!currentDiagnostics) {
            val connected = currentSnapshot["connected"]?.jsonPrimitive?.booleanOrNull == true
            val code = (currentSnapshot["pairing"] as? JsonObject)?.hpText("userCode").orEmpty()
            if (connected) operation { client.sync() }
            else if (code.isNotBlank()) operation { client.finishPairing() }
        }
    })
    LaunchedEffect(client, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(5_000); poll() }
        }
    }
    val importAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
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
    if (diagnostics) {
        Column(Modifier.fillMaxSize()) {
            PlayerTextButton(onClick = { diagnostics = false }) { Text("‹ Character HP connection") }
            Box(Modifier.weight(1f)) { OwnerSyncConfigPage() }
        }
    } else {
        val view = liveHpConnectionView(snapshot).copy(busy = busy, error = error, message = message)
        LiveHpConnectionContent(view,
            onPair = { origin, profileId, deviceName -> operation {
                val saved = client.beginPairing(origin, profileId, deviceName)
                snapshot = saved
                val url = (saved["pairing"] as? JsonObject)?.hpText("verificationUri").orEmpty()
                if (url.isNotBlank()) openOwnerPage(url, origin)
                saved
            } },
            onOpenOwner = { url -> try { openOwnerPage(url, view.origin) } catch (_: Exception) { error = true; message = "Owner approval could not be opened. Verify the service address." } },
            onSync = { operation { client.sync() } },
            onDecision = { commandId, approve -> operation { client.decide(commandId, approve) } },
            onDisconnect = { operation { client.revoke() } },
            onAuthorizeService = { origin, profileId -> operation {
                val url = client.startServiceAuthorization(origin, profileId)
                openOwnerPage(url, origin)
                client.snapshot()
            } },
            onImportAuthorization = { importAuthorization.launch(arrayOf("application/json", "application/octet-stream")) },
            onOpenDiagnostics = { diagnostics = true }
        )
    }
}

private fun JsonObject.hpText(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.hpLong(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.hpInt(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.hpRows(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Whitelist the saved HP fields; never render a complete profile or transport response. */
internal fun liveHpConnectionView(snapshot: JsonObject): LiveHpConnectionView {
    val selected = snapshot.hpText("selectedProfileId")
    val campaign = snapshot.hpText("campaignId")
    fun character(row: JsonObject) = LiveHpCharacterView(row.hpText("id").ifBlank { selected }, row.hpText("name"),
        row.hpText("campaignId").ifBlank { campaign }, row.hpInt("currentHp"), row.hpInt("maximumHp"), row.hpInt("temporaryHp"), row.hpLong("revision") ?: 0)
    val profiles = snapshot.hpRows("profiles").map(::character)
    val current = (snapshot["current"] as? JsonObject)?.let(::character)
    val pairing = snapshot["pairing"] as? JsonObject
    val historyRows = snapshot.hpRows("history")
    val terminal = historyRows.filter { it["receiptJson"] != null && it["receiptJson"] !is JsonNull || it.hpText("state") in setOf("DECISION_CONFLICT", "REPAIR_REVIEW", "REPLAY_BLOCKED") }.map { it.hpText("commandId") }.toSet()
    val proposals = snapshot.hpRows("pending").map { row ->
        LiveHpProposalView(row.hpText("commandId"), row.hpText("profileId").ifBlank { selected }, row.hpText("name").ifBlank { current?.name.orEmpty() },
            row.hpInt("currentHp"), row.hpInt("proposedHp"), row.hpInt("maximumHp") ?: current?.maximumHp, row.hpInt("temporaryHp") ?: current?.temporaryHp,
            row.hpLong("expectedRevision") ?: -1, row.hpLong("currentRevision") ?: -1, row.hpText("source"), row.hpText("state").ifBlank { "PENDING" },
            row["decisionQueued"]?.jsonPrimitive?.booleanOrNull ?: false, row.hpText("commandId") in terminal, row.hpText("message"))
    }
    val history = historyRows.map { row ->
        val receipt = when (val raw = row["receiptJson"]) {
            is JsonObject -> raw
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrNull()
            else -> null
        }
        LiveHpReceiptView(row.hpText("commandId"), row.hpText("state"), row.hpText("message"),
            receipt?.hpLong("resultingRevision"), receipt?.hpText("recordedAt").orEmpty())
    }
    return LiveHpConnectionView(origin = snapshot.hpText("origin"), profiles = profiles, selectedProfileId = selected,
        campaignId = campaign, connected = snapshot["connected"]?.jsonPrimitive?.booleanOrNull ?: false,
        ownerLabel = snapshot.hpText("ownerLabel").ifBlank { snapshot.hpText("accountLabel") }, deviceName = snapshot.hpText("deviceName").ifBlank { "Android device" },
        deviceId = snapshot.hpText("deviceId"), status = snapshot.hpText("status").ifBlank { "Not connected" },
        lastSyncAt = snapshot.hpLong("lastSyncAt")?.takeIf { it > 0 }, scopes = (snapshot["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        pairingCode = pairing?.hpText("userCode")?.ifBlank { null }, pairingUrl = pairing?.hpText("verificationUri")?.ifBlank { null },
        current = current, pending = proposals, history = history)
}
