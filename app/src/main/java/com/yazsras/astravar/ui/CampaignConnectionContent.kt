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
import com.yazsras.astravar.data.CampaignSyncStage
import com.yazsras.astravar.data.campaignSyncFailureCode
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
    "combat" to "Combat mechanics and roll history"
)
private val writableCampaignCategories = campaignScopeLabels.keys
data class CampaignConnectionProfileView(val id: String, val name: String, val campaignId: String)
data class CampaignOperationView(val type: String, val recordId: String, val expectedRevision: Long?,
    val currentRevision: Long?, val current: List<Pair<String, String>>, val proposed: List<Pair<String, String>>,
    val previewVerified: Boolean = false)
data class CampaignPendingView(val commandId: String, val state: String, val source: String,
    val message: String, val operations: List<CampaignOperationView>, val terminalReceipt: Boolean = false)
data class CampaignReceiptView(val commandId: String, val state: String, val message: String,
    val independentlyVerified: Boolean, val eventId: String = "", val fingerprint: String = "",
    val operations: List<CampaignReceiptOperationView> = emptyList()) {
    val needsAttention get() = !independentlyVerified
}
data class CampaignReceiptOperationView(val type: String, val recordId: String, val expected: Long?, val current: Long?, val committed: Long?)
data class CampaignConnectionView(
    val connected: Boolean = false, val status: String = "Disconnected", val origin: String = "",
    val campaignId: String = "", val selectedProfileId: String = "", val deviceId: String = "",
    val deviceName: String = "Android device", val account: String = "", val lastSyncAt: Long? = null,
    val campaignName: String = "", val historyHasNewer: Boolean = false, val historyNext: String? = null,
    val unverifiedHistoryCount: Int = 0,
    val revokePending: Boolean = false, val profiles: List<CampaignConnectionProfileView> = emptyList(),
    val pairingCode: String? = null, val pairingUrl: String? = null, val grantId: String = "",
    val grantRevision: Long? = null, val readScopes: Set<String> = emptySet(),
    val writeScopes: Set<String> = emptySet(), val autoSyncScopes: Set<String> = emptySet(),
    val authorizationPending: Boolean = false,
    val pending: List<CampaignPendingView> = emptyList(), val receipts: List<CampaignReceiptView> = emptyList(),
    val busy: Boolean = false, val message: String = "", val error: Boolean = false,
    val automaticSyncPaused: Boolean = false, val syncNotificationEnabled: Boolean = false,
    val syncNotificationAvailable: Boolean = false, val pushConfigured: Boolean = false, val pushRegistered: Boolean = false,
    val pushEnabled: Boolean = false, val localRecordCount: Int = 0,
    val sourceActorCampaignId: String? = null, val sourceActorPlayable: Boolean = false,
    val sourceCampaignPrepared: Boolean = false,
    val readiness: String = "", val readinessTitle: String = "", val readinessDetail: String = "",
    val verifiedRecordCount: Int = 0, val pendingPublicationCount: Int = 0, val pendingRemovalCount: Int = 0,
    val pendingDeviceCommandCount: Int = 0, val pendingReceiptCount: Int = 0,
    val gameplayVerified: Boolean = false, val gameplayVerifiedAt: Long? = null,
    val canonicalReviewProfileId: String = "",
    val lastServerResponseAt: Long? = null, val lastUploadAttemptAt: Long? = null,
    val lastPublicationAcceptedAt: Long? = null, val lastIndependentVerificationAt: Long? = null,
    val lastFullSyncAt: Long? = null, val currentStage: String = "", val failedStage: String = "",
    val failureCode: String = "", val pendingRecordCount: Int = 0
)

private fun connectionTime(value: Long?) = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
} ?: "Not yet recorded"

@Composable fun CampaignConnectionContent(view: CampaignConnectionView,
    onPair: (String, String, String, Set<String>, Set<String>) -> Unit,
    onOpenOwner: (String) -> Unit, onSync: () -> Unit, onCheckPairing: () -> Unit,
    onAutoSync: (Set<String>) -> Unit, onDecision: (String, Boolean) -> Unit,
    onRevoke: () -> Unit, onRenew: (String, String) -> Unit,
    onImportAuthorization: () -> Unit, onDiagnostics: () -> Unit,
    onRestrictPermissions: (Set<String>, Set<String>) -> Unit = { _, _ -> },
    onPauseSync: (Boolean) -> Unit = {}, onSyncNotification: (Boolean) -> Unit = {},
    onPushy: (Boolean) -> Unit = {}, onPrepareCampaign: () -> Unit = {},
    onReviewMembership: () -> Unit = {}, onReviewCombatAccess: () -> Unit = {}, openTool: ((String)->Unit)? = null,
    onReceiptPage: (String?) -> Unit = {}
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
        if (view.deviceId.isNotBlank()) {
            OpenCloudWorkspace()
            LegacyPublishingConsent(view.origin, view.campaignId, view.deviceId)
        }
        PlayerHeading("Owner connection")
        PlayerCard {
            Text("Campaign connection", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
            Text("Let ChatGPT read your selected campaign and save resolved gameplay within the permissions you choose.", color = RebuildPalette.Muted)
            Text("Every save retains its source, revision and receipt. Pending delivery stays pending until your device commits it.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Ice)
        }
        PlayerCard {
            Text(if (view.busy) "Syncing" else if (view.automaticSyncPaused) "Paused" else view.readinessTitle.ifBlank { view.status }, style = MaterialTheme.typography.titleMedium,
                color = if (view.error) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
                modifier = Modifier.testTag("campaign-connection-status"))
            Text("Account: ${view.account.ifBlank { "Not authenticated" }}")
            Text("Device: ${view.deviceName}", color = RebuildPalette.Muted)
            val campaignLabel = view.campaignName.ifBlank { if (view.campaignId.isBlank()) "Not selected" else "Name unavailable" }
            Text("Campaign: $campaignLabel", color = RebuildPalette.Muted)
            if (view.readinessDetail.isNotBlank()) Text(view.readinessDetail, color = RebuildPalette.Muted,
                modifier = Modifier.testTag("campaign-readiness-detail"))
            Text("Last complete gameplay verification: ${view.gameplayVerifiedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) } ?: "Not yet verified"}", color = RebuildPalette.Muted,
                modifier = Modifier.testTag("campaign-gameplay-verification-time"))
            if (view.failedStage.isNotBlank()) {
                Text("Sync stopped at: ${view.failedStage.replace('_', ' ').lowercase()}", color = RebuildPalette.GoldBright,
                    modifier = Modifier.testTag("campaign-sync-failed-stage"))
                Text(ownerConnectionRecoveryMessage(view.failureCode)
                    ?: "This stage needs verification. Saved records and pending transactions are retained. Retry Sync now; review permissions or conflicts if it continues.",
                    color = RebuildPalette.Muted)
            } else if (view.currentStage.isNotBlank()) Text("Sync stage: ${view.currentStage.replace('_', ' ').lowercase()}", color = RebuildPalette.Muted)
            if (view.pendingRecordCount > 0) Text("Records retained for publication or verification: ${view.pendingRecordCount}", color = RebuildPalette.GoldBright)
            if (view.connected) {
                if (view.canonicalReviewProfileId.isNotBlank()) {
                    Text("Record eligibility and verification are blocked until shared values are reconciled.", color = RebuildPalette.GoldBright,
                        modifier = Modifier.testTag("campaign-record-verification-blocked"))
                }
                if (view.pendingPublicationCount > 0) Text("Uploads awaiting verification: ${view.pendingPublicationCount}", color = RebuildPalette.GoldBright)
                if (view.pendingRemovalCount > 0) Text("Previously shared records awaiting removal: ${view.pendingRemovalCount}", color = RebuildPalette.GoldBright)
                if (view.pendingDeviceCommandCount > 0) Text("Updates awaiting device approval or commitment: ${view.pendingDeviceCommandCount}", color = RebuildPalette.GoldBright)
                if (view.pendingReceiptCount > 0) Text("Device decisions awaiting verified receipts: ${view.pendingReceiptCount}", color = RebuildPalette.GoldBright)
            }
            if (view.revokePending) Text("Server revocation pending. Local access is disabled; retry to confirm server revocation.", color = RebuildPalette.GoldBright)
            if (view.authorizationPending) Text("Permission update awaiting verification", color = RebuildPalette.GoldBright)
            if (view.unverifiedHistoryCount > 0) Text("Receipts needing attention: ${view.unverifiedHistoryCount}", color = RebuildPalette.GoldBright)
            PlayerDisclosure("Sync details") {
                Text("Last successful service response: ${connectionTime(view.lastServerResponseAt)}", color = RebuildPalette.Muted,
                    modifier = Modifier.testTag("campaign-service-response-time"))
                Text("Last upload attempt: ${connectionTime(view.lastUploadAttemptAt)}", color = RebuildPalette.Muted)
                Text("Last accepted publication: ${connectionTime(view.lastPublicationAcceptedAt)}", color = RebuildPalette.Muted)
                Text("Last independent record verification: ${connectionTime(view.lastIndependentVerificationAt)}", color = RebuildPalette.Muted)
                Text("Last fully completed synchronization: ${connectionTime(view.lastFullSyncAt)}", color = RebuildPalette.Muted)
                if (view.connected && view.canonicalReviewProfileId.isBlank()) {
                    Text("Local campaign records eligible for sync: ${view.localRecordCount}", color = RebuildPalette.Muted)
                    Text("Current records independently verified: ${view.verifiedRecordCount} of ${view.localRecordCount}", color = RebuildPalette.Muted)
                }
            }
            if (view.connected || view.revokePending) PlayerButton(onClick = onSync, enabled = !view.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
        }
        if (view.connected && view.selectedProfileId.isNotBlank() &&
            (view.readiness in setOf("CAMPAIGN_NOT_PREPARED", "MEMBERSHIP_REQUIRED", "CAMPAIGN_MISMATCH", "VALUES_REVIEW_REQUIRED") ||
                (view.readiness.isBlank() && (view.sourceActorCampaignId != view.campaignId || !view.sourceActorPlayable || view.localRecordCount == 0)))) PlayerCard {
            Text("Complete campaign sharing", style = MaterialTheme.typography.titleMedium)
            when {
                view.canonicalReviewProfileId.isNotBlank() -> {
                    val actorName = view.profiles.firstOrNull { it.id == view.canonicalReviewProfileId }?.name
                        ?: view.canonicalReviewProfileId
                    Text("Shared records for $actorName differ from the saved playable values. Review and reconcile them before publishing. Existing records and queued work are retained.", color = RebuildPalette.GoldBright)
                    PlayerButton(onClick = onReviewMembership, enabled = !view.busy, modifier = Modifier.fillMaxWidth().testTag("review-campaign-membership")) {
                        Text("Review character membership and shared values")
                    }
                }
                view.sourceActorCampaignId != null && view.sourceActorCampaignId != view.campaignId ->
                    Text("This character belongs to a different saved campaign than the owner connection. Its records were not reassigned or shared. Review the correct campaign and start a separately authorized pairing.", color = RebuildPalette.GoldBright)
                view.campaignId == "local-campaign" && !view.sourceCampaignPrepared -> {
                    Text("The private service is connected, but this local campaign has not been prepared in Genesis. Preparing it creates a campaign identity only; it does not share a character or change any gameplay value.", color = RebuildPalette.GoldBright)
                    PlayerButton(onClick = onPrepareCampaign, enabled = !view.busy, modifier = Modifier.fillMaxWidth().testTag("prepare-local-campaign")) {
                        Text("Prepare connected local campaign")
                    }
                }
                !view.sourceCampaignPrepared -> Text("The owner connection references a campaign not available in the current Genesis records. Restore or select the correct campaign before sharing.", color = RebuildPalette.GoldBright)
                view.sourceActorCampaignId != view.campaignId || !view.sourceActorPlayable -> {
                    Text("The selected character's gameplay values have not been explicitly linked to this campaign. Review membership and reconcile saved values before publishing; the source sheet is preserved.", color = RebuildPalette.GoldBright)
                    PlayerButton(onClick = onReviewMembership, enabled = !view.busy, modifier = Modifier.fillMaxWidth().testTag("review-campaign-membership")) {
                        Text("Review character membership and shared values")
                    }
                }
                else -> Text("Membership is connected, but there are no typed records in this campaign yet. Review the character's saved values before expecting remote data.", color = RebuildPalette.GoldBright)
            }
        }
        PlayerDisclosure("Sync settings") {
            if (view.connected || view.revokePending) PlayerButton(onClick = onSync, enabled = !view.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Run manual sync") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pause automatic sync", modifier = Modifier.weight(1f))
                Switch(view.automaticSyncPaused, onPauseSync,
                    modifier = Modifier.testTag("campaign-sync-pause").semantics { contentDescription = "Pause automatic campaign sync" })
            }
            if (view.automaticSyncPaused) Text("Automatic delivery is paused in the app and background. Queued transactions and saved permissions are retained. Sync now remains available.", color = RebuildPalette.GoldBright)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Show Background Sync notification", modifier = Modifier.weight(1f))
                Switch(view.syncNotificationEnabled, onSyncNotification,
                    modifier = Modifier.testTag("campaign-sync-notification").semantics { contentDescription = "Show background campaign sync notification" })
            }
            if (view.syncNotificationEnabled && !view.syncNotificationAvailable) {
                Text("Android notification permission or this notification channel is off. Android may defer background delivery.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                PlayerOutlinedButton(onClick = { onSyncNotification(true) }, modifier = Modifier.fillMaxWidth()) { Text("Enable notification permission") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Use Pushy wake notifications", modifier = Modifier.weight(1f))
                Switch(view.pushEnabled, onPushy, enabled = view.pushConfigured && (view.connected || view.pushEnabled),
                    modifier = Modifier.testTag("campaign-sync-pushy").semantics { contentDescription = "Use optional Pushy campaign wake notifications" })
            }
            Text(when {
                !view.pushEnabled -> "Cloudflare sync is active on open and through Android's scheduled recovery. Pushy is optional and off."
                !view.pushConfigured -> "Pushy is unavailable in this build. Cloudflare sync on open and scheduled recovery remain available."
                !view.pushRegistered -> "Pushy setup or registration is pending. Cloudflare sync on open and scheduled recovery remain available."
                else -> "Pushy sends only a signed wake hint, then Astravar syncs through Cloudflare. Screen-off, Doze and Samsung battery settings may delay delivery."
            }, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (!view.connected && !view.revokePending) PlayerDisclosure("Choose your campaign", initiallyOpen = view.pairingCode != null) {
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
            PlayerDisclosure("Available record access") {
                if ("combat" in view.readScopes && "combat" !in view.writeScopes) {
                    Text("Allow reviewed attacks, defenses and resolved combat actions for this campaign. Roll history stays read-only. The owner must confirm the change on the private service.",
                        style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                    PlayerOutlinedButton(onClick = onReviewCombatAccess, enabled = !view.busy && !view.authorizationPending,
                        modifier = Modifier.fillMaxWidth()) { Text("Review combat update permission") }
                }
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
            PlayerDisclosure("Trusted DM Auto-Sync") {
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
                if (!hasWritableCategories) Text("This connection has read access only. Review additional access before enabling automatic saves. Roll history stays read-only.", color = RebuildPalette.Muted)
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
        view.pending.forEach { pending ->
            CampaignRequestCard(pending, view.connected && !view.busy, onDecision)
        }
        view.receipts.filter { it.needsAttention && view.pending.none { pending -> pending.commandId == it.commandId } }.forEach {
            PlayerCard { CampaignReceiptDetails(it) }
        }
        PlayerDisclosure("Recent activity and receipts") {
            if (view.receipts.isEmpty()) Text("No command receipts have been retrieved.", color = RebuildPalette.Muted)
            view.receipts.forEach { CampaignReceiptDetails(it) }
            view.historyNext?.let { before ->
                PlayerOutlinedButton({ onReceiptPage(before) }, enabled = !view.busy) { Text("Earlier receipts") }
            }
            if (view.historyHasNewer) PlayerTextButton({ onReceiptPage(null) }, enabled = !view.busy) { Text("Most recent receipts") }
        }
        if (openTool != null) {
            PlayerDisclosure("Backups and recovery") {
                for (label in listOf("Complete backup", "Character backup", "Campaign backup", "Campaign import")) {
                    PlayerEntry(label, "", "History", { openTool(label) })
                }
            }
            PlayerDisclosure("Archives") {
                PlayerEntry("Original armor archive", "Original weapon and armor configuration", "History", { openTool("Weapon backup") })
            }
        }
        PlayerDisclosure("Access and security") {
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
                Text("Campaign ID: ${view.campaignId.ifBlank { "Not selected" }}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun CampaignReceiptDetails(receipt: CampaignReceiptView) {
    Text(receipt.state.replace('_', ' '), style = MaterialTheme.typography.labelLarge,
        color = if (receipt.needsAttention) RebuildPalette.GoldBright else RebuildPalette.Ice)
    Text("Command ${receipt.commandId}", style = MaterialTheme.typography.bodySmall)
    if (receipt.message.isNotBlank()) Text(receipt.message, color = RebuildPalette.Muted)
    Text(if (receipt.independentlyVerified) "Independent server receipt retrieved" else "Server receipt pending or unverified")
    if (receipt.eventId.isNotBlank()) Text("Receipt ${receipt.eventId}", style = MaterialTheme.typography.bodySmall)
    if (receipt.fingerprint.isNotBlank()) Text("Fingerprint ${receipt.fingerprint}", style = MaterialTheme.typography.bodySmall)
    for (op in receipt.operations) {
        Text("${op.type.replace('_', ' ')} · ${op.recordId}")
        Text("Expected ${op.expected ?: "unknown"} · current ${op.current ?: "unknown"} · committed ${op.committed ?: "unknown"}",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

private fun JsonObject.connectionText(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.connectionLong(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.connectionRows(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.connectionScopes(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it in campaignScopeLabels }?.toSet().orEmpty()
private fun displayFields(value: JsonElement?): List<Pair<String, String>> = (value as? JsonObject)?.entries?.filter { it.key !in setOf("token", "bearer", "credential", "deviceToken", "accessToken", "refreshToken") }?.map { (key, field) ->
    key to when (field) { is JsonNull -> "Unknown"; is JsonPrimitive -> field.content; else -> field.toString() }
}.orEmpty()

private fun nativeSourceReview(fields: JsonElement?): List<Pair<String, String>> = buildList {
    fun show(label: String, value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> show(if (label.isEmpty()) key else "$label · $key", child) }
            is JsonArray -> if (value.isEmpty()) add(label to "None") else value.forEachIndexed { index, child -> show("$label ${index + 1}", child) }
            JsonNull -> add(label to "Unknown")
            is JsonPrimitive -> add(label to value.content)
        }
    }
    if (fields is JsonObject) show("", fields)
}

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
        campaignId = snapshot.connectionText("campaignId"), campaignName = snapshot.connectionText("campaignName"), selectedProfileId = snapshot.connectionText("selectedProfileId"),
        historyNext = snapshot.connectionText("historyNext").ifBlank { null },
        historyHasNewer = (snapshot["historyHasNewer"] as? JsonPrimitive)?.booleanOrNull == true,
        unverifiedHistoryCount = snapshot.connectionLong("unverifiedHistoryCount")?.coerceIn(0,Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        deviceId = snapshot.connectionText("deviceId"), deviceName = snapshot.connectionText("deviceName").ifBlank { "Android device" },
        account = snapshot.connectionText("accountLabel"), lastSyncAt = snapshot.connectionLong("lastSyncAt")?.takeIf { it > 0 },
        lastServerResponseAt = snapshot.connectionLong("lastServerResponseAt")?.takeIf { it > 0 },
        lastUploadAttemptAt = snapshot.connectionLong("lastUploadAttemptAt")?.takeIf { it > 0 },
        lastPublicationAcceptedAt = snapshot.connectionLong("lastPublicationAcceptedAt")?.takeIf { it > 0 },
        lastIndependentVerificationAt = snapshot.connectionLong("lastIndependentVerificationAt")?.takeIf { it > 0 },
        lastFullSyncAt = snapshot.connectionLong("lastFullSyncAt")?.takeIf { it > 0 },
        currentStage = snapshot.connectionText("currentStage").takeIf { value -> CampaignSyncStage.entries.any { it.name == value } }.orEmpty(),
        failedStage = snapshot.connectionText("failedStage").takeIf { value -> CampaignSyncStage.entries.any { it.name == value } }.orEmpty(),
        failureCode = snapshot.connectionText("failureCode").let { if (it.isBlank()) "" else campaignSyncFailureCode(it) },
        pendingRecordCount = snapshot.connectionLong("pendingRecordCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        localRecordCount = snapshot.connectionLong("localRecordCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        readiness = snapshot.connectionText("readiness"), readinessTitle = snapshot.connectionText("readinessTitle"),
        readinessDetail = snapshot.connectionText("readinessDetail"),
        canonicalReviewProfileId = snapshot.connectionText("canonicalReviewProfileId"),
        verifiedRecordCount = snapshot.connectionLong("verifiedRecordCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        pendingPublicationCount = snapshot.connectionLong("pendingPublicationCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        pendingRemovalCount = snapshot.connectionLong("pendingRemovalCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        pendingDeviceCommandCount = snapshot.connectionLong("pendingDeviceCommandCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        pendingReceiptCount = snapshot.connectionLong("pendingReceiptCount")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        gameplayVerified = (snapshot["gameplayVerified"] as? JsonPrimitive)?.booleanOrNull == true,
        gameplayVerifiedAt = snapshot.connectionLong("gameplayVerifiedAt")?.takeIf { it > 0 },
        sourceActorCampaignId = snapshot.connectionText("sourceActorCampaignId").ifBlank { null },
        sourceActorPlayable = (snapshot["sourceActorPlayable"] as? JsonPrimitive)?.booleanOrNull == true,
        sourceCampaignPrepared = (snapshot["sourceCampaignPrepared"] as? JsonPrimitive)?.booleanOrNull == true,
        revokePending = revoking,
        profiles = snapshot.connectionRows("profiles").map { CampaignConnectionProfileView(it.connectionText("id"), it.connectionText("name"), it.connectionText("campaignId")) },
        pairingCode = if (activePairing) pairing?.connectionText("userCode")?.ifBlank { null } else null, pairingUrl = if (activePairing) pairing?.connectionText("verificationUri")?.ifBlank { null } else null,
        grantId = grant.connectionText("grantId"), grantRevision = grant.connectionLong("revision"),
        readScopes = grant.connectionScopes("readScopes").let { granted -> if (snapshot["localReadScopes"] is JsonArray) granted.intersect(snapshot.connectionScopes("localReadScopes")) else granted },
        writeScopes = grant.connectionScopes("writeScopes").let { granted -> if (snapshot["localWriteScopes"] is JsonArray) granted.intersect(snapshot.connectionScopes("localWriteScopes")) else granted },
        autoSyncScopes = if (authorizationPending || revoking || grantExpired) emptySet() else grant.connectionScopes("autoSyncScopes").intersect(localAutoScopes).intersect(writableCampaignCategories),
        authorizationPending = authorizationPending,
        automaticSyncPaused = (snapshot["automaticSyncPaused"] as? JsonPrimitive)?.booleanOrNull == true,
        pushConfigured = (snapshot["pushConfigured"] as? JsonPrimitive)?.booleanOrNull == true,
        pushRegistered = (snapshot["pushRegistered"] as? JsonPrimitive)?.booleanOrNull == true,
        pushEnabled = (snapshot["pushEnabled"] as? JsonPrimitive)?.booleanOrNull == true,
        pending = snapshot.connectionRows("pending").map { row -> CampaignPendingView(row.connectionText("commandId"), row.connectionText("state"), row.connectionText("source"), row.connectionText("message"),
            row.connectionRows("operations").map { op -> CampaignOperationView(op.connectionText("type"), op.connectionText("recordId"), op.connectionLong("expectedRevision"),
                op.connectionLong("currentRevision"), displayFields(op["current"] ?: op["currentFields"]), if (op.connectionText("type") in setOf("define_native_item", "define_native_actor")) (nativeSourceReview(op["fields"]) + nativeSourceReview(op["nativePreview"])) else displayFields(op["proposedFields"] ?: op["fields"]) + if (op.connectionText("type") == "acquire_item") nativeSourceReview(op["nativePreview"]) else emptyList(),
                op["proposedFields"] is JsonObject && (op.connectionText("type") !in setOf("acquire_item","define_native_actor","define_native_item") ||
                    (op["nativePreview"] as? JsonObject)?.get("valid") == JsonPrimitive(true))) }, row.connectionText("commandId") in terminalCommands) },
        receipts = snapshot.connectionRows("history").map { row ->
            val stored = (row["receiptJson"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val commandId = row.connectionText("commandId")
            val state = row.connectionText("state")
            val expectedStatus = when (state) {
                "COMMITTED", "VERIFIED" -> "APPLIED"
                "REJECTED", "CONFLICT" -> state
                else -> null
            }
            val verified = expectedStatus != null && stored != null && stored.connectionText("commandId") == commandId &&
                stored.connectionText("status") == expectedStatus && stored.connectionText("eventId").isNotBlank() &&
                stored.connectionText("fingerprint").isNotBlank() && stored.connectionText("fingerprint") == row.connectionText("fingerprint")
            val results = stored?.connectionRows("records").orEmpty().associateBy { it.connectionText("kind") to it.connectionText("recordId") }
            CampaignReceiptView(commandId, state, row.connectionText("message"), verified,
                if (verified) stored?.connectionText("eventId").orEmpty() else "",
                if (verified) stored?.connectionText("fingerprint").orEmpty() else "",
                row.connectionRows("operations").map { op ->
                    val recordId = op.connectionText("recordId")
                    val result = if (verified) results[op.connectionText("kind") to recordId] else null
                    CampaignReceiptOperationView(op.connectionText("type"), recordId, op.connectionLong("expectedRevision"),
                        op.connectionLong("currentRevision"), result?.connectionLong("revision"))
                })
        }
    )
}

@Composable internal fun LegacyPublishingConsent(origin: String, continuity: String, credential: String) {
    if (continuity.isBlank() || credential.isBlank()) return
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val controls = remember(context) { com.yazsras.astravar.data.CampaignSyncPreferences(context) }
    var pausedAfterUpgrade by remember(origin, continuity, credential) {
        mutableStateOf(!controls.allowsLegacyPublishing(origin, continuity, credential))
    }
    if (pausedAfterUpgrade) PlayerCard {
        Text("Automatic device publishing is paused after the upgrade.")
        PlayerOutlinedButton({
            controls.resumeLegacyPublishing(origin, continuity, credential)
            pausedAfterUpgrade = false
        }) { Text("Resume existing device connection") }
    }
}
