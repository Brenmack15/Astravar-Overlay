package com.yazsras.astravar.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Native readiness and fictional offline queue. There is no credential-paste or real-data upload flow. */
@Composable fun OwnerSyncConfigPage() {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { context.getSharedPreferences("astravar-owner-sync-settings", Context.MODE_PRIVATE) }
    val db = remember(context) { OwnerSyncDb.open(context) }
    val vault = remember(context) { OwnerSyncCredentialVault(context) }
    val repository = remember(db, vault) { OwnerSyncRepository(db, vault) }
    DisposableEffect(db) { onDispose { db.close() } }
    val scope = rememberCoroutineScope()
    var origin by rememberSaveable { mutableStateOf(settings.getString("origin", "") ?: "") }
    var campaign by rememberSaveable { mutableStateOf(settings.getString("campaign", "synthetic-owner-acceptance") ?: "synthetic-owner-acceptance") }
    var selectedOrigin by remember { mutableStateOf(settings.getString("origin", "") ?: "") }
    var selectedCampaign by remember { mutableStateOf(settings.getString("campaign", "synthetic-owner-acceptance") ?: "synthetic-owner-acceptance") }
    var commands by remember { mutableStateOf<List<OwnerSyncQueuedCommand>>(emptyList()) }
    var received by remember { mutableStateOf<List<OwnerSyncReceived>>(emptyList()) }
    var enrolled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    val configured = selectedOrigin.isNotBlank()
    LaunchedEffect(selectedOrigin, selectedCampaign) {
        if (configured) {
            enrolled = vault.credential(selectedOrigin, selectedCampaign) != null
            received = db.sync().received(selectedOrigin, selectedCampaign)
            repository.commands(selectedOrigin, selectedCampaign).collect { commands = it }
        } else { commands = emptyList(); received = emptyList(); enrolled = false }
    }
    fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false; message = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) {
                failed = true
                message = when (e.code) {
                    "AUTHENTICATION_REQUIRED" -> "No enrolled device credential. Owner-authenticated service enrollment is required."
                    "CREDENTIAL_REVOKED_OR_EXPIRED", "CREDENTIAL_SCOPE_OR_EXPIRY" -> "Device access expired or was revoked. Enroll again through the owner's authorized service."
                    "ACCESS_DENIED" -> "This device does not have permission for the selected campaign."
                    "REVISION_CONFLICT" -> "Remote records changed. Review the original command and latest record before creating a correction."
                    "COMMAND_ID_REUSED" -> "The command ID conflicts with different saved content. No automatic replacement was attempted."
                    "QUOTA_REACHED" -> "Service quota reached. Your command remains saved here; retry later."
                    else -> "Remote save could not be verified. Your local queue and cursor are retained."
                }
            } catch (_: Exception) {
                failed = true; message = "The service or response could not be verified. Your queue is retained. Check the address, access and connection."
            } finally { busy = false }
        }
    }
    Page {
        PlayerHeading("Owner connection")
        PlayerCard {
            Text("Real campaign sync is not activated", style = MaterialTheme.typography.titleMedium, color = RebuildPalette.GoldBright)
            Text("Your records stay on this device. This release can exercise an authenticated fictional campaign after a secure service enrolls the device. No service or credential is included.", color = RebuildPalette.Muted)
            Text("The existing private DM Bridge is preserved. The anonymous public test Worker cannot receive campaign records.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        PlayerCard {
            Text(if (enrolled) "Device credential present" else "Device not enrolled", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = origin, onValueChange = { origin = it }, enabled = !busy,
                label = { Text("Verified service HTTPS origin") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = campaign, onValueChange = { campaign = it }, enabled = !busy,
                label = { Text("Fictional campaign ID (synthetic-…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PlayerButton(enabled = !busy, onClick = {
                operation {
                    val endpoint = OwnerSyncProtocol.origin(origin)
                    OwnerSyncProtocol.id(campaign)
                    require(campaign.startsWith("synthetic-"))
                    check(settings.edit().putString("origin", endpoint).putString("campaign", campaign).commit())
                    selectedOrigin = endpoint; selectedCampaign = campaign
                    origin = endpoint
                    message = "Fictional test scope saved. No information was sent."
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save test address") }
            Text("Enrollment comes from an authenticated owner service. Never paste passwords, access tokens or signing keys into a chat.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) Text(message, color = if (failed) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PlayerCard {
            Text("Local queue & verified receipts", style = MaterialTheme.typography.titleMedium)
            Text("${commands.count { it.state == "PENDING" }} pending · ${commands.count { it.state == "BLOCKED" }} need review · ${commands.count { it.state == "REMOTE_VERIFIED" }} saved remotely")
            Text("${received.size} independently read events", color = RebuildPalette.Muted)
            PlayerOutlinedButton(enabled = configured && !busy && commands.none { it.state == "PENDING" }, onClick = {
                operation {
                    val previous = db.sync().received(selectedOrigin, selectedCampaign).map { Json.parseToJsonElement(it.eventJson).jsonObject }
                        .lastOrNull { it["recordId"]?.jsonPrimitive?.content == "fixture-connection" }
                    repository.queue(selectedOrigin, selectedCampaign, "fixture-connection",
                        previous?.get("resultingRevision")?.jsonPrimitive?.long ?: 0,
                        buildJsonObject { put("name", "Fictional Test Merchant"); put("note", "Confirmed native Astravar fictional connectivity test") },
                        "Owner connection synthetic test", true)
                    message = "Fictional change saved locally. It has not been uploaded."
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Prepare fictional offline test") }
            PlayerButton(enabled = configured && enrolled && !busy, onClick = {
                operation { received = repository.refresh(selectedOrigin, selectedCampaign); message = "Authenticated remote history independently verified." }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Read remote test history") }
            PlayerButton(enabled = configured && enrolled && !busy && commands.any { it.state == "PENDING" }, onClick = {
                operation {
                    repository.sendPending(selectedOrigin, selectedCampaign)
                    received = repository.refresh(selectedOrigin, selectedCampaign)
                    message = "Commit receipts and independent remote readback verified."
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Send fictional pending changes") }
            commands.takeLast(5).reversed().forEach { command ->
                Text(when (command.state) {
                    "REMOTE_VERIFIED" -> "Fictional change: remote receipt verified"
                    "BLOCKED" -> "Fictional change: review required (${command.issueCode.replace('_', ' ').lowercase()})"
                    else -> "Fictional change: saved locally, pending sync"
                }, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
            }
            PlayerOutlinedButton(enabled = configured && !busy, onClick = {
                operation {
                    vault.forget(selectedOrigin, selectedCampaign); enrolled = false
                    message = "This device's credential was removed. The service must separately revoke access; your local queue remains intact."
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Disconnect this device") }
        }
        Text("Release gates: authenticated hosting and owner enrollment, connector authorization, native Android round trip, backup/restore checks, and separate campaign cutover approval. No production synchronization is claimed.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
