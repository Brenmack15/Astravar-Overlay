package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Narrow display state. No credentials or unrelated character fields enter the connection screen. */
data class LiveHpCharacterView(val id: String, val name: String, val campaignId: String,
    val currentHp: Int?, val maximumHp: Int?, val temporaryHp: Int?, val revision: Long)
data class LiveHpProposalView(val commandId: String, val profileId: String, val name: String,
    val currentHp: Int?, val proposedHp: Int?, val maximumHp: Int?, val temporaryHp: Int?,
    val expectedRevision: Long, val currentRevision: Long, val source: String, val state: String,
    val decisionQueued: Boolean = false, val terminal: Boolean = false, val message: String = "")
data class LiveHpReceiptView(val commandId: String, val state: String, val message: String,
    val revision: Long?, val recordedAt: String = "")
data class LiveHpConnectionView(val origin: String = "", val profiles: List<LiveHpCharacterView> = emptyList(),
    val selectedProfileId: String = "", val campaignId: String = "", val connected: Boolean = false,
    val ownerLabel: String = "", val deviceName: String = "Android device", val deviceId: String = "",
    val status: String = "Not connected", val lastSyncAt: Long? = null, val scopes: List<String> = emptyList(),
    val pairingCode: String? = null, val pairingUrl: String? = null, val current: LiveHpCharacterView? = null,
    val pending: List<LiveHpProposalView> = emptyList(), val history: List<LiveHpReceiptView> = emptyList(),
    val busy: Boolean = false, val error: Boolean = false, val message: String = "")

@Composable fun LiveHpConnectionContent(view: LiveHpConnectionView,
    onPair: (origin: String, profileId: String, deviceName: String) -> Unit,
    onOpenOwner: (String) -> Unit, onSync: () -> Unit, onDecision: (String, Boolean) -> Unit,
    onDisconnect: () -> Unit, onAuthorizeService: (origin: String, profileId: String) -> Unit,
    onImportAuthorization: () -> Unit, onOpenDiagnostics: () -> Unit = {}) {
    var origin by rememberSaveable(view.origin) { mutableStateOf(view.origin) }
    var deviceName by rememberSaveable(view.deviceName) { mutableStateOf(view.deviceName) }
    var selection by rememberSaveable(view.selectedProfileId, view.profiles.map { it.id }) {
        mutableStateOf(view.selectedProfileId.takeIf { selected -> view.profiles.any { it.id == selected } }
            ?: view.profiles.singleOrNull()?.id.orEmpty())
    }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    var authorization by rememberSaveable { mutableStateOf(false) }
    val selected = view.profiles.firstOrNull { it.id == selection }
    val shown = view.current ?: selected
    Page {
        PlayerHeading("Owner connection")
        PlayerCard {
            Text("Character HP", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
            Text("ChatGPT can read this character's saved HP and propose an HP connection test. Each change waits for your approval here.", color = RebuildPalette.Muted)
            Text("Maximum HP, temporary HP and other character fields remain unchanged by HP proposals.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        PlayerCard {
            Text(view.status, style = MaterialTheme.typography.titleMedium,
                color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice)
            if (view.connected) {
                Text("Account: ${view.ownerLabel.ifBlank { "Identity unavailable" }}")
                Text("Device: ${view.deviceName}", color = RebuildPalette.Muted)
                Text("Device ID: ${view.deviceId.ifBlank { "Unreported" }}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
            }
            Text("Last successful sync: ${view.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Not yet verified"}", color = RebuildPalette.Muted)
            if (view.connected) PlayerOutlinedButton(enabled = !view.busy, onClick = onSync,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retry sync") }
        }
        if (!view.connected) PlayerCard {
            Text("Choose an existing character", style = MaterialTheme.typography.titleMedium)
            if (view.profiles.isEmpty()) Text("No saved character profile is available. Save your character in Astravar first, then return here. No profile or HP will be created by pairing.", color = RebuildPalette.Muted)
            else {
                if (view.profiles.size > 1 && selected == null) Text("More than one profile is saved. Select the exact character to connect.", color = RebuildPalette.GoldBright)
                view.profiles.forEach { profile ->
                    PlayerOutlinedButton(enabled = !view.busy && view.pairingCode == null, onClick = { selection = profile.id },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text((if (profile.id == selection) "Selected · " else "") + profile.name.ifBlank { "Unnamed character" })
                            Text("Profile ID: ${profile.id}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedTextField(value = origin, onValueChange = { origin = it }, enabled = !view.busy && view.pairingCode == null,
                label = { Text("Owner service HTTPS origin") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = deviceName, onValueChange = { deviceName = it.take(100) }, enabled = !view.busy && view.pairingCode == null,
                label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        shown?.let { character -> PlayerCard {
            Text(character.name.ifBlank { "Unnamed character" }, style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
            Text("Current HP: ${character.currentHp?.toString() ?: "Unknown"}", style = MaterialTheme.typography.headlineSmall)
            Text("Maximum HP: ${character.maximumHp?.toString() ?: "Unknown"} · Temporary HP: ${character.temporaryHp?.toString() ?: "Unknown"}", color = RebuildPalette.Muted)
            Text("Profile ID: ${character.id}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            Text("Campaign: ${character.campaignId.ifBlank { "Unresolved" }} · profile revision ${character.revision}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (character.currentHp == null || character.maximumHp == null) Text("An HP proposal requires confirmed current and maximum HP. Unknown values stay unknown; edit the saved character when you have verified them.", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall)
        } }
        if (!view.connected) PlayerCard {
            Text("Secure HP pairing", style = MaterialTheme.typography.titleMedium)
            Text("Requested permissions: read HP, synchronize saved HP and decide HP proposals. No inventory, currency, abilities or campaign narrative is shared.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (view.pairingCode == null) {
                PlayerButton(enabled = !view.busy && selected != null && origin.isNotBlank() && deviceName.isNotBlank(),
                    onClick = { selected?.let { onPair(origin.trim(), it.id, deviceName.trim()) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pair this character") }
            } else {
                Text("Pairing code: ${view.pairingCode}", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
                Text("Approve this character and device on the owner page. Astravar checks the server for approval automatically.", color = RebuildPalette.Muted)
                view.pairingUrl?.let { url -> PlayerButton(enabled = !view.busy, onClick = { onOpenOwner(url) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open owner approval") } }
            }
        }
        if (view.scopes.isNotEmpty()) PlayerCard {
            Text("Authorized permissions", style = MaterialTheme.typography.titleMedium)
            view.scopes.forEach { permission -> Text(when (permission) {
                "hp:read" -> "Read saved HP"
                "hp:publish" -> "Synchronize saved HP"
                "hp:decide" -> "Apply an HP proposal after your approval"
                else -> permission
            }, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        if (view.message.isNotBlank()) Text(view.message,
            color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PlayerCard {
            Text("Pending HP changes", style = MaterialTheme.typography.titleMedium)
            if (view.pending.isEmpty()) Text("No pending HP proposals have been retrieved.", color = RebuildPalette.Muted)
            else Text("${view.pending.size} to review", color = RebuildPalette.Muted)
        }
        view.pending.forEach { proposal -> LiveHpProposalCard(proposal, view.connected && !view.busy && proposal.profileId.isNotBlank() && proposal.profileId == view.selectedProfileId, onDecision) }
        PlayerCard {
            Text("HP sync history & receipts", style = MaterialTheme.typography.titleMedium)
            if (view.history.isEmpty()) Text("No independently verified HP receipts yet.", color = RebuildPalette.Muted)
            view.history.sortedBy { if (it.state in setOf("DECISION_CONFLICT", "CONFLICT", "REPAIR_REVIEW", "SUPERSEDED_BY_REPAIR")) 0 else 1 }.take(15).forEach { entry ->
                Text(if (entry.state == "SUPERSEDED_BY_REPAIR") "Previous device proposal superseded by owner re-pairing" else entry.state.replace('_', ' '), style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Ice)
                Text("Command ${entry.commandId}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                if (entry.message.isNotBlank()) Text(entry.message, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                if (entry.state == "SUPERSEDED_BY_REPAIR") Text("No server approval is claimed for this prior device proposal.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                entry.revision?.let { Text("Server profile revision $it", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted) }
                if (entry.recordedAt.isNotBlank()) Text(entry.recordedAt, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                HorizontalDivider()
            }
        }
        PlayerCard {
            PlayerOutlinedButton(enabled = !view.busy, onClick = { authorization = !authorization }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Service authorization") }
            if (authorization) {
                Text("Reuse the existing private service authorization when available. If it has expired, authorize in your browser and import the encrypted file created for this installation.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                PlayerOutlinedButton(enabled = !view.busy && (if (view.connected) view.origin else origin).isNotBlank() && (if (view.connected) view.selectedProfileId.isNotBlank() else selected != null),
                    onClick = { onAuthorizeService((if (view.connected) view.origin else origin).trim(), if (view.connected) view.selectedProfileId else requireNotNull(selected).id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Authorize owner service") }
                PlayerOutlinedButton(enabled = !view.busy, onClick = onImportAuthorization,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Import encrypted authorization") }
            }
            if (view.connected) PlayerOutlinedButton(enabled = !view.busy, onClick = { disconnect = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Revoke HP connection") }
            PlayerTextButton(onClick = onOpenDiagnostics) { Text("Fictional connection diagnostics") }
        }
    }
    if (disconnect) AlertDialog(onDismissRequest = { if (!view.busy) disconnect = false },
        title = { Text("Revoke this HP connection?") },
        text = { Text("Astravar asks the server to revoke this device before removing its HP credential. Saved character records, approved changes and receipts remain on this device. If the server cannot confirm revocation, you can retry.") },
        confirmButton = { TextButton(enabled = !view.busy, onClick = { disconnect = false; onDisconnect() }) { Text("Revoke connection") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}

@Composable private fun LiveHpProposalCard(proposal: LiveHpProposalView, enabled: Boolean,
    onDecision: (String, Boolean) -> Unit) {
    val stale = proposal.currentRevision < 1 || proposal.expectedRevision < 1 || proposal.currentRevision != proposal.expectedRevision
    val current = proposal.currentHp
    val proposed = proposal.proposedHp
    val maximum = proposal.maximumHp
    val valid = current != null && proposed != null && maximum != null && current in 0..maximum && proposed in 0..maximum && proposed != current
    val adjustment = if (current != null && proposed != null) proposed - current else null
    val canDecide = enabled && !proposal.decisionQueued && !proposal.terminal
    PlayerCard(Modifier.testTag("live-hp-proposal-${proposal.commandId}")) {
        Text(proposal.name.ifBlank { "Unnamed character" }, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.GoldBright)
        Text("HP connection test", style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Ice)
        Text("Current HP: ${current?.toString() ?: "Unknown"}")
        Text("Proposed HP: ${proposed?.toString() ?: "Unknown"}")
        Text("Adjustment: ${adjustment?.let { if (it >= 0) "+$it HP" else "$it HP" } ?: "Unknown"}", color = RebuildPalette.GoldBright)
        Text("Maximum HP: ${maximum?.toString() ?: "Unknown"} (unchanged)", color = RebuildPalette.Muted)
        Text("Temporary HP: ${proposal.temporaryHp?.toString() ?: "Unknown"} (unchanged)", color = RebuildPalette.Muted)
        Text("Source: ${proposal.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Profile ID: ${proposal.profileId} · current revision ${proposal.currentRevision}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Command ${proposal.commandId}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (stale) Text("The profile changed or its revision is unverified. Approval is blocked until a new proposal matches the saved profile.", color = MaterialTheme.colorScheme.error)
        if (!valid) Text("A valid change needs known current and maximum HP, and a proposed value within that maximum.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (proposal.message.isNotBlank()) Text(proposal.message, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (proposal.decisionQueued) Text("Decision saved locally · server receipt pending", color = RebuildPalette.GoldBright)
        if (proposal.terminal) Text("This command already has a final outcome. It cannot be approved or rejected again.", color = RebuildPalette.GoldBright)
        PlayerButton(enabled = canDecide && !stale && valid && proposal.state == "PENDING", onClick = { onDecision(proposal.commandId, true) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Approve HP change") }
        PlayerOutlinedButton(enabled = canDecide && proposal.state in setOf("PENDING", "CONFLICT"), onClick = { onDecision(proposal.commandId, false) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reject HP change") }
    }
}
