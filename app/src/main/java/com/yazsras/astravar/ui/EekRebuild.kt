package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Manual, sourced companion state. Presentation never rolls, refills or changes Eek's rules. */
@Composable fun EekPageV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val companions by records.companionStates.collectAsState(initial = emptyList())
    val resources by records.companionResources.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    val state = companions.firstOrNull { it.companionId == "eek" }
    val eekResources = resources.filter { it.companionId == "eek" }
    val equipment = links.filter { (it.toType == "companion" && it.toId == "eek") || (it.fromType == "companion" && it.fromId == "eek") }
    val actions = rememberLocalRecordActions()
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val stateDraft = rememberUiDraftText()
    var stateJson by stateDraft
    val decodedState = remember(stateJson) { stateJson?.let { runCatching { Json.decodeFromString<CompanionState>(it) } } }
    val stateBaseline = decodedState?.getOrNull()
    val resourceDraft = rememberUiDraftText()
    var resourceJson by resourceDraft
    var resourceDraftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var equipmentDraftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val decodedResource = remember(resourceJson) { resourceJson?.let { runCatching { Json.decodeFromString<CompanionResource>(it) } } }
    val resourceBaseline = decodedResource?.getOrNull()
    var condition by rememberSaveable { mutableStateOf("Unknown") }
    var notes by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var resourceName by rememberSaveable { mutableStateOf("") }
    var resourceCurrent by rememberSaveable { mutableStateOf("") }
    var resourceMaximum by rememberSaveable { mutableStateOf("") }
    var itemId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkKind by rememberSaveable { mutableStateOf("Equipment") }
    var selectedLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var capabilityIndex by rememberSaveable { mutableIntStateOf(0) }
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyLimit by rememberSaveable { mutableIntStateOf(40) }
    val stateError = stateDraft.error ?: if (decodedState?.isFailure == true || (mode == "status" && state != null && stateBaseline == null))
        "Eek's saved status draft could not be verified. Close and reopen the status before saving." else null
    val resourceError = resourceDraft.error ?: if (decodedResource?.isFailure == true)
        "The saved resource draft could not be verified. Close and reopen the resource before saving." else null
    val stateDirty = condition != (stateBaseline?.condition ?: "Unknown") || notes != stateBaseline?.notes.orEmpty() || source != stateBaseline?.source.orEmpty()
    val resourceDirty = resourceName != resourceBaseline?.name.orEmpty() || resourceCurrent != resourceBaseline?.current?.toString().orEmpty() ||
        resourceMaximum != resourceBaseline?.maximum?.toString().orEmpty() || source != (resourceBaseline?.source ?: state?.source.orEmpty())
    val dirty = when(mode) { "status" -> stateDirty; "resource" -> resourceDirty; "equipment" -> itemId != null || source != state?.source.orEmpty(); else -> false }
    val staleState = stateBaseline != null && state != null && state.revision != stateBaseline.revision
    val latestResource = resources.firstOrNull { it.id == resourceBaseline?.id }
    val staleResource = resourceBaseline != null && latestResource != null && latestResource.revision != resourceBaseline.revision
    fun openStatus() {
        stateJson = state?.let { Json.encodeToString(it) }; condition = state?.condition ?: "Unknown"
        notes = state?.notes.orEmpty(); source = state?.source.orEmpty(); actions.message = ""; mode = "status"
    }
    fun openResource(resource: CompanionResource?) {
        resourceDraftId = resource?.id ?: UUID.randomUUID().toString()
        resourceJson = resource?.let { Json.encodeToString(it) }; resourceName = resource?.name.orEmpty()
        resourceCurrent = resource?.current?.toString().orEmpty(); resourceMaximum = resource?.maximum?.toString().orEmpty()
        source = resource?.source ?: state?.source.orEmpty(); actions.message = ""; mode = "resource"
    }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.eek_portrait), contentDescription = "Eek, brass mechanical owl with a blue core",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                Text("Eek", style = MaterialTheme.typography.headlineMedium)
                Text("Mechanical owl companion", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            PlayerTextButton(onClick = { openStatus() }) { Text("Edit status") }
        }
        PlayerCard(onClick = { openStatus() }) {
            Text(state?.condition ?: "Condition unknown", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.Ice)
            if (!state?.notes.isNullOrBlank()) Text(state!!.notes, maxLines = 3, color = RebuildPalette.Muted)
            if (state == null) Text("Add confirmed status", color = RebuildPalette.Muted)
        }
        if (mode == null) {
            EekSaveStatus(actions)
            stateDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            resourceDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Resources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            PlayerTextButton(onClick = { openResource(null) }, enabled = state != null && !actions.busy) { Text("Add resource") }
        }
        if (eekResources.isEmpty()) PlayerCard {
            Text("No confirmed resource values", style = MaterialTheme.typography.bodyMedium)
            Text(if (state == null) "Save Eek's status before adding resources." else "Unknown values stay unknown.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        val resourceColumns = if (LocalConfiguration.current.fontScale > 1.2f || LocalConfiguration.current.screenWidthDp < 360) 1 else 2
        eekResources.chunked(resourceColumns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { resource ->
                    PlayerCard(modifier = Modifier.weight(1f), onClick = { openResource(resource) }) {
                        Text(resource.name, style = MaterialTheme.typography.labelLarge)
                        Text("${resource.current?.toString() ?: "?"} / ${resource.maximum?.toString() ?: "?"}", style = MaterialTheme.typography.headlineSmall, color = RebuildPalette.Ice)
                    }
                }
                if (resourceColumns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        PlayerEntry("Equipment & upgrades", "${equipment.size} linked record${if (equipment.size == 1) "" else "s"}", "Weapon", { mode = "equipmentList" })
        Text("Give, take, equip and install items in Party → Eek → Equipment.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        PlayerEntry("Capabilities", "Accepted companion reference", "Core", { mode = "capabilities" })
        PlayerEntry("History", "Sourced status, resources and equipment", "History", { mode = "history" })
    }
    val currentMode = mode
    if (currentMode != null) {
        val title = when(currentMode) {
            "status" -> "Eek status"
            "resource" -> if (resourceBaseline == null) "Add known resource" else resourceBaseline.name
            "equipmentList" -> "Equipment & upgrades"
            "equipment" -> "Link equipment"
            "equipmentDetail" -> "Equipment details"
            "capabilities" -> "Eek capabilities"
            "capability" -> eekCapabilities[capabilityIndex].first
            "history" -> "Eek history"
            "event" -> "Event details"
            else -> "Eek"
        }
        PlayerSheet(title, onDismiss = {
            if (!actions.busy) mode = when(currentMode) { "equipment", "equipmentDetail" -> "equipmentList"; "capability" -> "capabilities"; "event" -> "history"; else -> null }
        }, dirty = dirty, actions = {
            when(currentMode) {
                "status" -> PlayerButton(enabled = stateError == null && !actions.busy && !staleState && source.isNotBlank() && (stateBaseline == null || stateDirty), onClick = {
                    check(stateError == null) { "Reload Eek's saved status before saving." }
                    val before = stateBaseline; val src = source.trim()
                    val model = before?.copy(condition = condition, notes = notes) ?: CompanionState(condition = condition, notes = notes)
                    actions.run {
                        records.saveCompanionState(model, before?.revision, src)
                        mode = null; "Eek status saved locally."
                    }
                }) { Text("Save Eek status") }
                "resource" -> PlayerButton(enabled = resourceError == null && !actions.busy && !staleResource && state != null && resourceName.isNotBlank() && source.isNotBlank(), onClick = {
                    check(resourceError == null) { "Reload the saved resource before saving." }
                    val before = resourceBaseline; val current = resourceCurrent.trim(); val maximum = resourceMaximum.trim(); val src = source.trim()
                    val model = before?.copy(name = resourceName.trim(), current = current.toIntOrNull(), maximum = maximum.toIntOrNull())
                        ?: CompanionResource(name = resourceName.trim(), current = current.toIntOrNull(), maximum = maximum.toIntOrNull(), id = resourceDraftId)
                    actions.run {
                        require(current.isEmpty() || current.toIntOrNull()?.let { it >= 0 } == true) { "Current value must be nonnegative or unknown." }
                        require(maximum.isEmpty() || maximum.toIntOrNull()?.let { it >= 0 } == true) { "Maximum must be nonnegative or unknown." }
                        records.saveCompanionResource(model, before?.revision, src)
                        mode = null; "Confirmed resource saved locally."
                    }
                }) { Text(if (resourceBaseline == null) "Save known resource" else "Update known resource") }
                "equipment" -> PlayerButton(enabled = !actions.busy && state != null && itemId != null && source.isNotBlank(), onClick = {
                    val id = itemId!!; val relation = linkKind; val src = source.trim()
                    actions.run {
                        records.saveLink(CampaignLink(fromType = "item", fromId = id, toType = "companion", toId = "eek", label = relation, id = equipmentDraftId), null, src)
                        mode = "equipmentList"; "Equipment linked. Ownership unchanged."
                    }
                }) { Text("Link existing item to Eek") }
            }
        }) {
            EekSaveStatus(actions)
            stateError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            resourceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            when(currentMode) {
                "status" -> {
                    if (staleState) Text("A newer status exists. Cancel and reopen to load it; your draft is preserved.", color = MaterialTheme.colorScheme.error)
                    ConnectionChoice("Confirmed condition", listOf("Unknown", "Active", "Dormant", "Disabled", "Other"), condition, !actions.busy) { condition = it }
                    Field("Current status / upgrade notes", notes, { notes = it }, single = false, enabled = !actions.busy)
                    Field("Source / resolved scene / checkpoint", source, { source = it }, single = false, enabled = !actions.busy)
                    Text("Manual status only. No resources are restored.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                "resource" -> {
                    if (staleResource) Text("A newer resource value exists. Cancel and reopen to load it.", color = MaterialTheme.colorScheme.error)
                    Field("Resource name", resourceName, { resourceName = it }, enabled = !actions.busy)
                    Field("Confirmed current value (blank = unknown)", resourceCurrent, { resourceCurrent = it }, enabled = !actions.busy)
                    Field("Confirmed maximum (blank = unknown)", resourceMaximum, { resourceMaximum = it }, enabled = !actions.busy)
                    Field("Resource source", source, { source = it }, single = false, enabled = !actions.busy)
                    Text("Blank is unknown. Counters do not recover automatically.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                "equipmentList" -> {
                    if (equipment.isEmpty()) Text("No equipment or upgrades linked.", color = RebuildPalette.Muted)
                    equipment.forEach { link ->
                        val id = if (link.fromType == "item") link.fromId else link.toId
                        val item = items.firstOrNull { it.id == id }
                        PlayerEntry(item?.title ?: "Related record", link.label, "Weapon", { selectedLinkId = link.id; mode = "equipmentDetail" })
                    }
                    PlayerButton(enabled = state != null && items.isNotEmpty(), onClick = { itemId = null; linkKind = "Equipment"; source = state?.source.orEmpty(); equipmentDraftId = UUID.randomUUID().toString(); actions.message = ""; mode = "equipment" }) { Text("Link equipment") }
                    if (state == null) Text("Save Eek's status before linking equipment.", color = RebuildPalette.Muted)
                    else if (items.isEmpty()) Text("Add an inventory item before linking equipment.", color = RebuildPalette.Muted)
                }
                "equipment" -> {
                    ConnectionTargetChoice(items.map { it.id to it.title }, itemId, !actions.busy) { itemId = it }
                    ConnectionChoice("Relationship", listOf("Equipment", "Installed upgrade", "Carried", "Research subject"), linkKind, !actions.busy) { linkKind = it }
                    Field("Equipment source", source, { source = it }, single = false, enabled = !actions.busy)
                    Text("Uses the existing item. Edit ownership and location in Inventory; a link grants no new mechanics.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                "equipmentDetail" -> equipment.firstOrNull { it.id == selectedLinkId }?.let { link ->
                    val id = if (link.fromType == "item") link.fromId else link.toId
                    val item = items.firstOrNull { it.id == id }
                    Text(item?.title ?: "Related record", style = MaterialTheme.typography.titleLarge)
                    Text(link.label, color = RebuildPalette.Ice)
                    if (!item?.description.isNullOrBlank()) Text(item!!.description)
                    if (!item?.knownProperties.isNullOrBlank()) ExpandableRecordSection("Properties & Mechanics", true) { Text(item!!.knownProperties) }
                    Text("Source: ${link.source}", color = RebuildPalette.Muted)
                    Text("Item: $id", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                }
                "capabilities" -> {
                    eekCapabilities.forEachIndexed { index, capability -> PlayerEntry(capability.first, capability.second, "Core", { capabilityIndex = index; mode = "capability" }) }
                    Text("Reference rules do not establish current HP, charges or availability.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                "capability" -> {
                    Text(eekCapabilities[capabilityIndex].third, style = MaterialTheme.typography.bodyLarge)
                    Text("Accepted companion rules", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    PlayerDisclosure("Reference") {
                        Text("06_HOMEBREW_AND_EEK.md · Later accepted amendments take precedence.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                "history", "event" -> {
                    val resourceIds = eekResources.map { it.id }.toSet()
                    val linkIds = equipment.map { it.id }.toSet()
                    val events = audit.filter { when(it.recordType) { "companion" -> it.recordId == "eek"; "resource" -> it.recordId in resourceIds; "link" -> it.recordId in linkIds; else -> false } }
                    if (currentMode == "event") events.firstOrNull { it.id == eventId }?.let { ConnectionAuditDetail(it) }
                    else {
                        if (events.isEmpty()) Text("No saved history yet.", color = RebuildPalette.Muted)
                        events.take(historyLimit).forEach { event -> PlayerEntry(event.kind.replace('_', ' '), "${event.recordType} · revision ${event.resultingRevision}", "History", { eventId = event.id; mode = "event" }) }
                        if (events.size > historyLimit) PlayerTextButton(onClick = { historyLimit += 40 }) { Text("Show more history") }
                    }
                }
            }
        }
    }
}

@Composable private fun EekSaveStatus(actions: LocalRecordActions) {
    if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (actions.message.isNotBlank()) Text(actions.message, color = if (actions.message.startsWith("Not saved")) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private val eekCapabilities = listOf(
    Triple("Companion identity", "Homunculus Servant · dedicated infusion", "HB-01 · Dedicated companion infusion slot. Eek remains Zerg's Homunculus Servant, not a generic familiar or Steel Defender. Brass mechanical owl; folds into a compact sphere."),
    Triple("Turn & commands", "Acts immediately after Zerg", "HB-03 · Eek acts immediately after Zerg; commands do not cost Zerg a Bonus Action. This screen does not automate turns or dice."),
    Triple("Build & Force Strike", "Level-dependent reference", "HB-04 · Maximum HP = 6 + 4 × Artificer level. AC = 13 + PB. Force Strike = 1d6 + PB force; attack uses Zerg's base spell attack modifier. Evasion follows the accepted Dexterity-save rule. Historical example levels do not establish the current build."),
    Triple("Channel Magic", "Touch delivery · shared Reaction", "HB-05 · One shared Reaction per round. Channel Magic delivers Zerg's Touch-range spell when Eek is within 120 ft. of Zerg and can reach the target."),
    Triple("Protective Deflection", "Shared Reaction · within 5 ft.", "HB-05 · Protective Deflection can reduce an attack's damage by Zerg's PB when Zerg is hit, Eek can perceive that attack and Eek is within 5 ft. of Zerg. It uses Eek's one shared Reaction per round."),
    Triple("Repair & reconstruction", "Separate confirmed recovery", "HB-06 · At 0 HP Eek folds into a Disabled sphere. Active Mending Repairs: 2d6 HP up to maximum, once per Short Rest. Disabled Reconstruction: 10 minutes with Tinker's Tools and Mending, once per Long Rest. Time or a rest alone does not automatically reactivate or refill Eek.")
)
