package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Display-only models: tokens and the rest of the campaign database never enter Compose state. */
data class OwnerConnectionView(
    val origin: String = "",
    val campaignId: String = "synthetic-owner-acceptance",
    val deviceName: String = "Android device",
    val enrolled: Boolean = false,
    val account: String = "",
    val deviceId: String = "",
    val health: String = "Not connected",
    val lastSuccessfulSync: Long? = null,
    val scopes: List<String> = emptyList(),
    val pairingCode: String? = null,
    val pairingUrl: String? = null,
    val authorizationCode: String? = null,
    val pending: List<OwnerChangeView> = emptyList(),
    val receipts: List<OwnerReceiptView> = emptyList(),
    val fictionalScore: Int? = null,
    val fictionalRevision: Long? = null,
    val fictionalRemoteRevision: Long? = null,
    val busy: Boolean = false,
    val message: String = "",
    val error: Boolean = false
)
data class OwnerFieldDifference(val field: String, val current: String, val proposed: String)
data class OwnerChangeView(
    val commandId: String,
    val recordId: String,
    val title: String,
    val expectedRevision: Long,
    val currentRevision: Long,
    val source: String,
    val fields: List<OwnerFieldDifference>,
    val state: String = "PENDING",
    val decisionQueued: Boolean = false,
    val terminalReceipt: Boolean = false,
    val detail: String = ""
)
data class OwnerReceiptView(val commandId: String, val status: String, val revision: Long?, val recordedAt: String)

/** No timers, optimistic success labels, synthetic connectivity or automatic approval. */
@Composable fun OwnerConnectionContent(
    view: OwnerConnectionView,
    onPair: (origin: String, campaign: String, deviceName: String) -> Unit,
    onCheckPairing: () -> Unit,
    onOpenPairing: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDecision: (commandId: String, approve: Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onCreateFixture: () -> Unit,
    onPrivateAuthorization: (origin: String, campaign: String) -> Unit = { _, _ -> },
    onImportAuthorization: () -> Unit = {},
    onPublishFixture: (reviewedRevision: Long) -> Unit = {},
    onEditFixture: (score: Int, reviewedRevision: Long) -> Unit = { _, _ -> }
) {
    var origin by rememberSaveable(view.origin) { mutableStateOf(view.origin) }
    var campaign by rememberSaveable(view.campaignId) { mutableStateOf(view.campaignId) }
    var deviceName by rememberSaveable(view.deviceName) { mutableStateOf(view.deviceName) }
    var showDisconnect by rememberSaveable { mutableStateOf(false) }
    var publishReview by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var editReview by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var editScore by rememberSaveable { mutableStateOf("") }
    Page {
        PlayerHeading("Owner connection")
        PlayerCard {
            Text("Fictional acceptance only", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.titleMedium)
            Text("Pair this device to test a character read and an approved ability update. Live campaign sharing requires separate authorization.", color = RebuildPalette.Muted)
            Text("ChatGPT proposals wait here. Only your approval can save a change.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Ice)
        }
        PlayerCard {
            Text(view.health, style = MaterialTheme.typography.titleMedium, color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice)
            Text(if (view.enrolled) "Connected account: ${view.account.ifBlank { "Identity unavailable" }}" else "Device not enrolled")
            if (view.enrolled) {
                Text("Device: ${view.deviceName}", color = RebuildPalette.Muted)
                Text("Device ID: ${view.deviceId.ifBlank { "Unreported" }}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Service: ${view.origin}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Campaign: ${view.campaignId}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("Last successful sync: ${view.lastSuccessfulSync?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Not yet verified"}", color = RebuildPalette.Muted)
            if (view.scopes.isNotEmpty()) {
                Text("Authorized permissions", style = MaterialTheme.typography.labelLarge)
                view.scopes.forEach { Text(it.replace('_', ' '), color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall) }
            }
            if (view.enrolled) {
                PlayerButton(enabled = !view.busy, onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sync now") }
                PlayerOutlinedButton(enabled = !view.busy, onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retry saved decisions") }
                PlayerOutlinedButton(enabled = !view.busy, onClick = { showDisconnect = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Revoke and disconnect") }
            }
        }
        if (!view.enrolled) PlayerCard {
            Text("Secure device pairing", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = origin, onValueChange = { origin = it }, enabled = !view.busy && view.pairingCode == null,
                label = { Text("Verified service HTTPS origin") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = campaign, onValueChange = { campaign = it }, enabled = false,
                label = { Text("Fictional campaign ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = deviceName, onValueChange = { deviceName = it.take(100) }, enabled = !view.busy && view.pairingCode == null,
                label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Requested permissions: read this fictional character, publish approved fictional ability changes, review proposals and receipts, revoke this device.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
            if (view.pairingCode == null) {
                PlayerButton(enabled = !view.busy && origin.isNotBlank() && campaign == "synthetic-owner-acceptance" && deviceName.isNotBlank(),
                    onClick = { onPair(origin.trim(), campaign.trim(), deviceName.trim()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start secure pairing") }
            } else {
                PairingCodeCopy(view.pairingCode)
                Text("Authorize this exact device and fictional campaign in your owner connection page, then check pairing below.", color = RebuildPalette.Muted)
                view.pairingUrl?.let { url ->
                    PlayerOutlinedButton(enabled = !view.busy, onClick = { onOpenPairing(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open owner authorization") }
                }
                PlayerButton(enabled = !view.busy, onClick = onCheckPairing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check pairing approval") }
            }
            Text("Passwords, signing keys and access tokens are never entered in chat. Device credentials stay encrypted on this device.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        PlayerCard {
            Text("Private service authorization", style = MaterialTheme.typography.titleMedium)
            Text("For a private service, authorize in your browser and import its encrypted authorization file. Then complete device pairing above. You can repeat this if service access expires.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            PlayerOutlinedButton(enabled = !view.busy && (if (view.enrolled) view.origin else origin).isNotBlank(),
                onClick = { onPrivateAuthorization((if (view.enrolled) view.origin else origin).trim(), campaign) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Authorize private service") }
            PlayerOutlinedButton(enabled = !view.busy, onClick = onImportAuthorization,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Import encrypted authorization") }
            Text("Only an encrypted file created for this device is accepted. Do not paste credentials into Astravar or chat.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        view.authorizationCode?.let { PairingCodeCopy(it, "Authorization request code") }
        if (view.message.isNotBlank()) Text(view.message,
            color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PlayerCard {
            Text("Pending ChatGPT changes", style = MaterialTheme.typography.titleMedium)
            Text("${view.pending.size} awaiting review", color = RebuildPalette.Muted)
            if (view.pending.isEmpty()) Text("No pending changes have been retrieved.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        }
        view.pending.forEach { change ->
            OwnerChangeCard(change, !view.busy && view.enrolled, onDecision)
        }
        PlayerCard {
            Text("Sync history & command receipts", style = MaterialTheme.typography.titleMedium)
            if (view.receipts.isEmpty()) Text("No independently verified receipts yet.", color = RebuildPalette.Muted)
            // A quarantined decision remains visible even after later publication-history entries.
            view.receipts.sortedBy { when (it.status) { "DECISION_CONFLICT" -> 0; "REPAIR_REVIEW" -> 1; else -> 2 } }.take(10).forEach { receipt ->
                Text(when (receipt.status) {
                    "DECISION_CONFLICT" -> "Decision delivery stopped · review required"
                    "REPAIR_REVIEW" -> "Previous publication preserved · review required"
                    else -> receipt.status.replace('_', ' ')
                }, style = MaterialTheme.typography.labelLarge,
                    color = if (receipt.status in listOf("CONFLICT", "REJECTED", "DECISION_CONFLICT", "REPAIR_REVIEW")) RebuildPalette.GoldBright else RebuildPalette.Ice)
                Text("Command ${receipt.commandId}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                if (receipt.status == "DECISION_CONFLICT") Text("The server reported a delivery conflict. The original request and any local changes are preserved. Astravar will not resubmit or replace this command's decision. Sync can still read server records and receipts for review.",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.GoldBright)
                if (receipt.status == "REPAIR_REVIEW") Text("A pairing or service change moved this publication aside. Its original request is preserved. Astravar will not resend it automatically; a new publication requires explicit review.",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.GoldBright)
                receipt.revision?.let { Text("Server revision $it", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted) }
                if (receipt.recordedAt.isNotBlank()) Text(receipt.recordedAt, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                HorizontalDivider()
            }
        }
        PlayerCard {
            Text("Fictional character test", style = MaterialTheme.typography.titleMedium)
            Text("Creates or exposes only the fixed disposable test character. Your character, inventory, currency and campaign history are excluded.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            Text("Local Intelligence: ${view.fictionalScore?.toString() ?: "Unknown"} · local revision ${view.fictionalRevision?.toString() ?: "Unknown"}", color = RebuildPalette.Ice)
            Text("Last verified server revision: ${view.fictionalRemoteRevision?.toString() ?: "Unknown"}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            PlayerOutlinedButton(enabled = !view.busy && view.fictionalScore != null && view.fictionalRevision != null,
                onClick = { editReview = view.fictionalScore!! to view.fictionalRevision!!; editScore = view.fictionalScore.toString() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit fictional Intelligence") }
            PlayerOutlinedButton(enabled = view.enrolled && !view.busy, onClick = onCreateFixture, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Expose fictional test character") }
            PlayerOutlinedButton(enabled = view.enrolled && !view.busy && view.fictionalScore != null && view.fictionalRevision != null,
                onClick = { publishReview = view.fictionalScore!! to view.fictionalRevision!! }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review local fictional change") }
        }
    }
    editReview?.let { reviewed ->
        val proposed = editScore.toIntOrNull()
        AlertDialog(
            onDismissRequest = { if (!view.busy) editReview = null },
            title = { Text("Edit fictional Intelligence") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Current local Intelligence: ${reviewed.first} · revision ${reviewed.second}")
                OutlinedTextField(value = editScore, onValueChange = { editScore = it.filter(Char::isDigit).take(2) },
                    label = { Text("New Intelligence score (1–30)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("Save changes only on this device. Review and publish separately to share this fictional value with ChatGPT.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = !view.busy && proposed != null && proposed in 1..30 && proposed != reviewed.first,
                onClick = { editReview = null; onEditFixture(requireNotNull(proposed), reviewed.second) }) { Text("Save locally") } },
            dismissButton = { TextButton(onClick = { editReview = null }) { Text("Cancel") } }
        )
    }
    publishReview?.let { reviewed -> AlertDialog(
        onDismissRequest = { if (!view.busy) publishReview = null },
        title = { Text("Publish fictional ability?") },
        text = { Text("Send this device's fictional Intelligence score ${reviewed.first}, local revision ${reviewed.second}, to the paired service. The server must verify its expected revision before saving. Your real character and campaign records are excluded.") },
        confirmButton = { TextButton(enabled = !view.busy, onClick = { publishReview = null; onPublishFixture(reviewed.second) }) { Text("Publish reviewed value") } },
        dismissButton = { TextButton(onClick = { publishReview = null }) { Text("Cancel") } }
    ) }
    if (showDisconnect) AlertDialog(
        onDismissRequest = { if (!view.busy) showDisconnect = false },
        title = { Text("Revoke this device?") },
        text = { Text("Astravar will ask the server to revoke this device before removing its local credential. If the server cannot confirm revocation, the connection stays available so you can retry. Campaign records and saved receipts stay on this device.") },
        confirmButton = { TextButton(enabled = !view.busy, onClick = { showDisconnect = false; onDisconnect() }) { Text("Revoke and disconnect") } },
        dismissButton = { TextButton(onClick = { showDisconnect = false }) { Text("Cancel") } }
    )
}

@Composable private fun OwnerChangeCard(change: OwnerChangeView, enabled: Boolean, onDecision: (String, Boolean) -> Unit) {
    val stale = change.currentRevision < 0 || change.expectedRevision < 0 || change.currentRevision != change.expectedRevision
    PlayerCard {
        Text(change.title, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.GoldBright)
        Text("Record ${change.recordId} · revision ${change.currentRevision}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Source: ${change.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Command ${change.commandId}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        change.fields.forEach { difference ->
            Text(difference.field, style = MaterialTheme.typography.labelLarge)
            Text("Current: ${difference.current}", color = RebuildPalette.Muted)
            Text("Proposed: ${difference.proposed}", color = RebuildPalette.Ice)
        }
        if (stale) Text(if (change.currentRevision < 0 || change.expectedRevision < 0) "The current revision could not be verified. Refresh before approving." else "Conflict: proposal expects revision ${change.expectedRevision}. Refresh and request a new proposal before approving.", color = MaterialTheme.colorScheme.error)
        if (change.decisionQueued) Text("Decision saved on this device; server confirmation pending.", color = RebuildPalette.GoldBright)
        if (change.state != "PENDING") Text(change.state.replace('_', ' '), color = RebuildPalette.GoldBright)
        if (change.detail.isNotBlank()) Text(change.detail, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (change.terminalReceipt) Text("The server has issued a final receipt. Review any local and remote conflict separately; this command cannot be changed.", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall)
        PlayerButton(enabled = enabled && !change.terminalReceipt && !stale && change.fields.isNotEmpty() && change.fields.none { it.current == "Unknown" || it.proposed == "Unknown" } && !change.decisionQueued && change.state == "PENDING", onClick = { onDecision(change.commandId, true) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Approve change") }
        PlayerOutlinedButton(enabled = enabled && !change.terminalReceipt && !change.decisionQueued && change.state in setOf("PENDING", "CONFLICT"), onClick = { onDecision(change.commandId, false) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reject change") }
    }
}
