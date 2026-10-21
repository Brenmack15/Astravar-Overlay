package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.text.DateFormat
import java.util.Date

internal val campaignScopeLabels = linkedMapOf(
    "hp" to "HP, temporary HP and conditions",
    "resources" to "Spell slots, resources and charges",
    "inventory" to "Inventory and equipment",
    "currency" to "Currency and resolved transactions",
    "character" to "Character records",
    "companions" to "Companion records and resources",
    "engineering" to "Inventions, research and crafting",
    "continuity" to "History, relationships and checkpoints",
    "combat" to "Astravar configuration and roll history"
)
private val writableCampaignCategories = campaignScopeLabels.keys - "combat"
data class CampaignConnectionProfileView(val id: String, val name: String, val campaignId: String)
data class CampaignOperationView(val type: String, val recordId: String, val expectedRevision: Long?,
    val currentRevision: Long?, val current: List<Pair<String, String>>, val proposed: List<Pair<String, String>>,
    val previewVerified: Boolean = false)
data class CampaignPendingView(val commandId: String, val state: String, val source: String,
    val message: String, val operations: List<CampaignOperationView>, val terminalReceipt: Boolean = false)
data class CampaignReceiptView(val commandId: String, val state: String, val message: String,
    val independentlyVerified: Boolean)
data class CampaignConnectionView(
    val connected: Boolean = false, val status: String = "Disconnected", val origin: String = "",
    val campaignId: String = "", val selectedProfileId: String = "", val deviceId: String = "",
    val deviceName: String = "Android device", val account: String = "", val lastSyncAt: Long? = null,
    val revokePending: Boolean = false, val profiles: List<CampaignConnectionProfileView> = emptyList(),
    val pairingCode: String? = null, val pairingUrl: String? = null, val grantId: String = "",
    val grantRevision: Long? = null, val readScopes: Set<String> = emptySet(),
    val writeScopes: Set<String> = emptySet(), val autoSyncScopes: Set<String> = emptySet(),
    val authorizationPending: Boolean = false,
    val pending: List<CampaignPendingView> = emptyList(), val receipts: List<CampaignReceiptView> = emptyList(),
    val busy: Boolean = false, val message: String = "", val error: Boolean = false
)

@Composable fun CampaignConnectionContent(view: CampaignConnectionView,
    onPair: (String, String, String, Set<String>, Set<String>) -> Unit,
    onOpenOwner: (String) -> Unit, onSync: () -> Unit, onCheckPairing: () -> Unit,
    onAutoSync: (Set<String>) -> Unit, onDecision: (String, Boolean) -> Unit,
    onRevoke: () -> Unit, onRenew: (String, String) -> Unit,
    onImportAuthorization: () -> Unit, onDiagnostics: () -> Unit,
    onRestrictPermissions: (Set<String>, Set<String>) -> Unit = { _, _ -> }
) {
    var origin by rememberSaveable(view.origin) { mutableStateOf(view.origin) }
    var deviceName by rememberSaveable(view.deviceName) { mutableStateOf(view.deviceName) }
    var selected by rememberSaveable(view.selectedProfileId, view.profiles.map { it.id }) {
        mutableStateOf(view.selectedProfileId.ifBlank { view.profiles.singleOrNull()?.id.orEmpty() })
    }
    var reads by remember { mutableStateOf(emptySet<String>()) }
    var writes by remember { mutableStateOf(emptySet<String>()) }
    var automatic by remember(view.grantRevision, view.autoSyncScopes) { mutableStateOf(view.autoSyncScopes.intersect(writableCampaignCategories)) }
    var trusted by remember(view.grantRevision, view.autoSyncScopes) { mutableStateOf(view.autoSyncScopes.intersect(writableCampaignCategories).isNotEmpty()) }
    var revokeReview by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var managePermissions by rememberSaveable { mutableStateOf(false) }
    var retainedReads by remember(view.grantId, view.grantRevision, view.readScopes) { mutableStateOf(view.readScopes) }
    var retainedWrites by remember(view.grantId, view.grantRevision, view.writeScopes) { mutableStateOf(view.writeScopes) }
    val profile = view.profiles.firstOrNull { it.id == selected }
    val locked = view.busy || view.pairingCode != null
    val hasWritableCategories = view.writeScopes.any { it in writableCampaignCategories }
    Page {
        PlayerHeading("Owner connection")
        PlayerCard {
            Text("Campaign connection", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
            Text("Let ChatGPT read your selected campaign and save resolved gameplay within the permissions you choose.", color = RebuildPalette.Muted)
            Text("Every save retains its source, revision and receipt. Pending delivery stays pending until your device commits it.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Ice)
        }
        PlayerCard {
            Text(if (view.busy) "Syncing" else view.status, style = MaterialTheme.typography.titleMedium,
                color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
                modifier = Modifier.testTag("campaign-connection-status"))
            Text("Account: ${view.account.ifBlank { "Not authenticated" }}")
            Text("Device: ${view.deviceName}", color = RebuildPalette.Muted)
            Text("Campaign: ${view.campaignId.ifBlank { "Not selected" }}", color = RebuildPalette.Muted)
            Text("Latest verified sync: ${view.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Not yet verified"}", color = RebuildPalette.Muted)
            if (view.revokePending) Text("Server revocation pending. Local access is disabled; retry to confirm server revocation.", color = RebuildPalette.GoldBright)
            if (view.connected || view.revokePending) PlayerButton(onClick = onSync, enabled = !view.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
        }
        if (!view.connected && !view.revokePending) PlayerCard {
            Text("Choose your campaign", style = MaterialTheme.typography.titleMedium)
            if (view.profiles.isEmpty()) Text("No saved player profile is available. Save or restore your established character before pairing.", color = RebuildPalette.Muted)
            view.profiles.forEach { candidate ->
                PlayerOutlinedButton(onClick = { selected = candidate.id }, enabled = !locked,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text((if (candidate.id == selected) "Selected · " else "") + candidate.name.ifBlank { "Unnamed character" })
                        Text(candidate.campaignId, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(origin, { origin = it }, label = { Text("Owner service HTTPS origin") },
                singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deviceName, { deviceName = it.take(100) }, label = { Text("Device name") },
                singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
            Text("Record access", style = MaterialTheme.typography.titleMedium)
            Text("Choose read access and permitted updates separately. Automatic saving remains off until you authorize it below after pairing.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            campaignScopeLabels.forEach { (key, label) ->
                Text(label, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(key in reads, { checked -> reads = if (checked) reads + key else reads - key; if (!checked) writes = writes - key }, enabled = !locked,
                        modifier = Modifier.semantics { contentDescription = "Read $label" })
                    Text("Read", modifier = Modifier.weight(1f))
                    Checkbox(key in writes && key in writableCampaignCategories, { checked -> writes = if (checked) writes + key else writes - key; if (checked) reads = reads + key }, enabled = !locked && key in writableCampaignCategories,
                        modifier = Modifier.semantics { contentDescription = "Update $label" })
                    Text(if (key in writableCampaignCategories) "Update" else "Read only", modifier = Modifier.weight(1f))
                }
            }
            if (view.pairingCode == null) PlayerButton(onClick = { profile?.let { onPair(origin.trim(), it.id, deviceName.trim(), reads, writes) } },
                enabled = !view.busy && profile != null && origin.isNotBlank() && deviceName.isNotBlank() && reads.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text(if (view.deviceId.isBlank()) "Pair selected campaign" else "Renew device pairing") }
            else {
                PairingCodeCopy(view.pairingCode)
                Text("Authorize this exact device, campaign and selected permissions on your owner page.", color = RebuildPalette.Muted)
                view.pairingUrl?.let { url -> PlayerButton(onClick = { onOpenOwner(url) }, enabled = !view.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Open owner authorization") } }
                PlayerOutlinedButton(onClick = onCheckPairing, enabled = !view.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Check pairing") }
            }
        }
        if (view.connected) {
            PlayerCard {
                Text("Available record access", style = MaterialTheme.typography.titleMedium)
                if (view.readScopes.isEmpty()) Text("All record access is revoked. Pair again with owner approval to grant new access.", color = RebuildPalette.Muted)
                campaignScopeLabels.forEach { (key, label) -> if (key in view.readScopes || key in view.writeScopes) {
                    Text("$label · ${if (key in view.writeScopes && key in writableCampaignCategories) "read and update" else "read only"}", color = RebuildPalette.Muted,
                        style = MaterialTheme.typography.bodySmall)
                } }
                Text("Permission revision: ${view.grantRevision?.toString() ?: "Unverified"}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                PlayerOutlinedButton(onClick = { managePermissions = !managePermissions },
                    modifier = Modifier.fillMaxWidth()) { Text("Manage record permissions") }
                if (managePermissions) {
                    Text("Turn off read access or updates independently. Removing read access also removes updates and automatic saving for that category. Revoked permissions require a new owner-approved pairing to restore.",
                        style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                    campaignScopeLabels.forEach { (key, label) -> if (key in view.readScopes || key in view.writeScopes) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(key in retainedReads, { checked ->
                                retainedReads = if (checked) retainedReads + key else retainedReads - key
                                if (!checked) retainedWrites = retainedWrites - key
                            }, enabled = !view.busy && !view.authorizationPending && key in view.readScopes,
                                modifier = Modifier.semantics { contentDescription = "Keep read access to $label" })
                            Text("Read", modifier = Modifier.weight(1f))
                            Checkbox(key in retainedWrites && key in writableCampaignCategories, { checked -> retainedWrites = if (checked) retainedWrites + key else retainedWrites - key },
                                enabled = !view.busy && !view.authorizationPending && key in view.writeScopes && key in retainedReads && key in writableCampaignCategories,
                                modifier = Modifier.semantics { contentDescription = "Keep update access to $label" })
                            Text(if (key in writableCampaignCategories) "Update" else "Read only", modifier = Modifier.weight(1f))
                        }
                    } }
                    PlayerButton(onClick = { onRestrictPermissions(retainedReads.intersect(view.readScopes), retainedWrites.intersect(retainedReads).intersect(view.writeScopes)) },
                        enabled = !view.busy && !view.authorizationPending &&
                            (retainedReads != view.readScopes || retainedWrites != view.writeScopes),
                        modifier = Modifier.fillMaxWidth().testTag("campaign-permissions-save")) { Text("Save reduced permissions") }
                    if (view.authorizationPending) Text("Permission changes are awaiting server verification. Local restrictions already prevent new access while delivery is pending.",
                        color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall)
                }
            }
            PlayerCard {
                Text("Trusted DM Auto-Sync", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
                Text("Authorize automatic saves only for gameplay you explicitly decide or resolve. Rule checks, revision checks and audit receipts still apply.", color = RebuildPalette.Muted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (trusted) "Automatic saving" else "Manual approval", modifier = Modifier.weight(1f))
                    Switch(trusted, { trusted = it }, enabled = !view.busy && !view.authorizationPending && hasWritableCategories,
                        modifier = Modifier.testTag("trusted-dm-toggle").semantics { contentDescription = "Trusted DM Auto-Sync" })
                }
                if (trusted) {
                    Text("Allow automatic saves for:", style = MaterialTheme.typography.labelLarge)
                    campaignScopeLabels.forEach { (key, label) -> if (key in view.writeScopes && key in writableCampaignCategories) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(key in automatic, { checked -> automatic = if (checked) automatic + key else automatic - key }, enabled = !view.busy && !view.authorizationPending,
                                modifier = Modifier.semantics { contentDescription = "Automatically save $label" })
                            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    } }
                }
                if (!hasWritableCategories) Text("This connection has read access only. Pair again to request supported update permissions. Combat configuration and roll history are read only.", color = RebuildPalette.Muted)
                PlayerButton(onClick = { onAutoSync(if (trusted) automatic.intersect(view.writeScopes).intersect(writableCampaignCategories) else emptySet()) },
                    enabled = hasWritableCategories && !view.busy && !view.authorizationPending && (!trusted || automatic.intersect(view.writeScopes).intersect(writableCampaignCategories).isNotEmpty()) &&
                        (if (trusted) automatic.intersect(view.writeScopes).intersect(writableCampaignCategories) else emptySet()) != view.autoSyncScopes,
                    modifier = Modifier.fillMaxWidth().testTag("trusted-dm-save")) {
                    Text(if (trusted) "Authorize selected automatic saves" else "Use manual approval")
                }
                Text(if (view.autoSyncScopes.intersect(writableCampaignCategories).isEmpty()) "Saved authorization: manual approval." else "Saved authorization: automatic saves for ${view.autoSyncScopes.intersect(writableCampaignCategories).mapNotNull { campaignScopeLabels[it] }.joinToString()}.",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Ice)
                if (view.authorizationPending) Text("Authorization update pending server verification. Automatic saving is paused until the saved scopes are verified.",
                    color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (view.message.isNotBlank()) Text(view.message, color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PlayerCard {
            Text("Pending operations", style = MaterialTheme.typography.titleMedium)
            if (view.pending.isEmpty()) Text("No pending operations have been retrieved.", color = RebuildPalette.Muted)
            else Text("${view.pending.size} pending or requiring review", color = RebuildPalette.Muted)
        }
        view.pending.forEach { pending -> PlayerCard {
            Text(pending.state.replace('_', ' '), color = RebuildPalette.GoldBright, style = MaterialTheme.typography.titleMedium)
            Text("Source: ${pending.source.ifBlank { "Unverified" }}", color = RebuildPalette.Muted)
            Text("Command ${pending.commandId}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
            pending.operations.forEach { op ->
                Text("${op.type.replace('_', ' ')} · ${op.recordId}", style = MaterialTheme.typography.labelLarge)
                Text("Expected revision ${op.expectedRevision?.toString() ?: "Unknown"} · saved revision ${op.currentRevision?.toString() ?: "Unknown"}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                op.current.forEach { (field, value) -> Text("Current $field: $value", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted) }
                op.proposed.forEach { (field, value) -> Text("Proposed $field: $value", style = MaterialTheme.typography.bodySmall) }
            }
            if (pending.message.isNotBlank()) Text(pending.message, color = RebuildPalette.Muted)
            val canApprove = !pending.terminalReceipt && pending.state in setOf("PENDING_APPROVAL", "PENDING_DELIVERY") && pending.operations.isNotEmpty() && pending.operations.all {
                it.expectedRevision != null && it.currentRevision != null && it.expectedRevision == it.currentRevision && it.proposed.isNotEmpty() && it.previewVerified
            }
            if (!pending.terminalReceipt && pending.state in setOf("PENDING_APPROVAL", "PENDING_DELIVERY", "CONFLICT")) {
                PlayerButton(onClick = { onDecision(pending.commandId, true) }, enabled = view.connected && !view.busy && canApprove,
                    modifier = Modifier.fillMaxWidth()) { Text("Approve reviewed transaction") }
                PlayerOutlinedButton(onClick = { onDecision(pending.commandId, false) }, enabled = view.connected && !view.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Reject transaction") }
            }
        } }
        PlayerCard {
            Text("Recent saves and receipts", style = MaterialTheme.typography.titleMedium)
            if (view.receipts.isEmpty()) Text("No command receipts have been retrieved.", color = RebuildPalette.Muted)
            view.receipts.take(15).forEach { receipt ->
                Text(receipt.state.replace('_', ' '), style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Ice)
                Text("Command ${receipt.commandId}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                if (receipt.message.isNotBlank()) Text(receipt.message, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text(if (receipt.independentlyVerified) "Independent server receipt retrieved" else "Server receipt pending or unverified",
                    color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
        PlayerCard {
            Text("Access and security", style = MaterialTheme.typography.titleMedium)
            Text("Renew the private service credential through your browser. Device credentials remain encrypted on this installation.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            PlayerOutlinedButton(onClick = { onRenew((if (view.connected) view.origin else origin).trim(), if (view.connected) view.selectedProfileId else selected) },
                enabled = !view.busy && (if (view.connected) view.origin else origin).isNotBlank() && (if (view.connected) view.selectedProfileId else selected).isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Renew private service authorization") }
            PlayerOutlinedButton(onClick = onImportAuthorization, enabled = !view.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Import encrypted authorization") }
            if (view.connected || view.revokePending) PlayerOutlinedButton(onClick = { revokeReview = true }, enabled = !view.busy,
                modifier = Modifier.fillMaxWidth()) { Text(if (view.revokePending) "Retry server revocation" else "Disconnect and revoke access") }
            PlayerTextButton(onClick = { advanced = !advanced }) { Text("Advanced diagnostics") }
            if (advanced) {
                Text("Device ID: ${view.deviceId.ifBlank { "Not enrolled" }}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Service: ${view.origin.ifBlank { "Not selected" }}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Grant ID: ${view.grantId.ifBlank { "Not enrolled" }}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                PlayerOutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Legacy and fictional test connections") }
            }
        }
    }
    if (revokeReview) AlertDialog(onDismissRequest = { if (!view.busy) revokeReview = false },
        title = { Text("Revoke campaign access?") },
        text = { Text("Stop this device's campaign synchronization and revoke its server grant. Saved campaign records and transaction history remain on this device. Offline revocation remains pending until the server confirms it.") },
        confirmButton = { TextButton(onClick = { revokeReview = false; onRevoke() }, enabled = !view.busy) { Text("Revoke access") } },
        dismissButton = { TextButton(onClick = { revokeReview = false }) { Text("Cancel") } })
}

private fun JsonObject.connectionText(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.connectionLong(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.connectionRows(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.connectionScopes(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it in campaignScopeLabels }?.toSet().orEmpty()
private fun displayFields(value: JsonElement?): List<Pair<String, String>> = (value as? JsonObject)?.entries?.filter { it.key !in setOf("token", "bearer", "credential", "deviceToken", "accessToken", "refreshToken") }?.map { (key, field) ->
    key to when (field) { is JsonNull -> "Unknown"; is JsonPrimitive -> field.content; else -> field.toString() }
}.orEmpty()

/** Whitelisted connection metadata and typed transaction previews only. */
internal fun campaignConnectionView(snapshot: JsonObject, now: Long = System.currentTimeMillis()): CampaignConnectionView {
    val grant = snapshot["grant"] as? JsonObject ?: buildJsonObject {}
    val pairing = snapshot["pairing"] as? JsonObject
    val grantExpired = grant.connectionLong("expiresAt")?.let { it <= now } == true
    val revoking = (snapshot["revokePending"] as? JsonPrimitive)?.booleanOrNull == true
    val authorizationPending = (snapshot["grantPending"] as? JsonPrimitive)?.booleanOrNull == true
    val localAutoScopes = if (snapshot["localAutoSyncScopes"] is JsonArray) snapshot.connectionScopes("localAutoSyncScopes") else grant.connectionScopes("autoSyncScopes")
    val terminalCommands = snapshot.connectionRows("history").filter { it["receiptJson"] != null && it["receiptJson"] !is JsonNull && it.connectionText("state") in setOf("VERIFIED", "REJECTED", "CONFLICT", "DECISION_CONFLICT") }.map { it.connectionText("commandId") }.toSet()
    val activePairing = pairing?.connectionLong("expiresAt")?.let { it > now } ?: false
    return CampaignConnectionView(
        connected = (snapshot["connected"] as? JsonPrimitive)?.booleanOrNull == true && !grantExpired && !revoking,
        status = if (revoking) "Server revocation pending" else if (grantExpired) "Authentication expired" else snapshot.connectionText("status").ifBlank { "Disconnected" }, origin = snapshot.connectionText("origin"),
        campaignId = snapshot.connectionText("campaignId"), selectedProfileId = snapshot.connectionText("selectedProfileId"),
        deviceId = snapshot.connectionText("deviceId"), deviceName = snapshot.connectionText("deviceName").ifBlank { "Android device" },
        account = snapshot.connectionText("accountLabel"), lastSyncAt = snapshot.connectionLong("lastSyncAt")?.takeIf { it > 0 },
        revokePending = revoking,
        profiles = snapshot.connectionRows("profiles").map { CampaignConnectionProfileView(it.connectionText("id"), it.connectionText("name"), it.connectionText("campaignId")) },
        pairingCode = if (activePairing) pairing?.connectionText("userCode")?.ifBlank { null } else null, pairingUrl = if (activePairing) pairing?.connectionText("verificationUri")?.ifBlank { null } else null,
        grantId = grant.connectionText("grantId"), grantRevision = grant.connectionLong("revision"),
        readScopes = grant.connectionScopes("readScopes").let { granted -> if (snapshot["localReadScopes"] is JsonArray) granted.intersect(snapshot.connectionScopes("localReadScopes")) else granted },
        writeScopes = grant.connectionScopes("writeScopes").let { granted -> if (snapshot["localWriteScopes"] is JsonArray) granted.intersect(snapshot.connectionScopes("localWriteScopes")) else granted },
        autoSyncScopes = if (authorizationPending || revoking || grantExpired) emptySet() else grant.connectionScopes("autoSyncScopes").intersect(localAutoScopes).intersect(writableCampaignCategories),
        authorizationPending = authorizationPending,
        pending = snapshot.connectionRows("pending").map { row -> CampaignPendingView(row.connectionText("commandId"), row.connectionText("state"), row.connectionText("source"), row.connectionText("message"),
            row.connectionRows("operations").map { op -> CampaignOperationView(op.connectionText("type"), op.connectionText("recordId"), op.connectionLong("expectedRevision"),
                op.connectionLong("currentRevision"), displayFields(op["current"] ?: op["currentFields"]), displayFields(op["proposedFields"] ?: op["fields"]),
                op["proposedFields"] is JsonObject) }, row.connectionText("commandId") in terminalCommands) },
        receipts = snapshot.connectionRows("history").map { row -> CampaignReceiptView(row.connectionText("commandId"), row.connectionText("state"), row.connectionText("message"),
            row["receiptJson"] != null && row["receiptJson"] !is JsonNull && row.connectionText("state") in setOf("COMMITTED", "VERIFIED", "REJECTED", "CONFLICT")) }
    )
}
