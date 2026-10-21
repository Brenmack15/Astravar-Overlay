package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yazsras.astravar.data.*
import java.util.UUID

/**
 * Companion state and counters stay in their original records, including Eek.
 * The typed sheet complements these records without rewriting accepted homebrew.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyCompanionEditorV2(repository: CampaignLedgerRepository, memberId: String, memberName: String) {
    require(memberId in setOf("eek", "korr", "veyrath")) { "Only separately reviewed party IDs belong in this editor" }
    val records = repository.records
    val allStates by records.companionStates.collectAsState(initial = emptyList())
    val allResources by records.companionResources.collectAsState(initial = emptyList())
    val allLinks by records.links.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val state = allStates.firstOrNull { it.companionId == memberId }
    val resources = allResources.filter { it.companionId == memberId }
    val links = allLinks.filter {
        (it.toType == "companion" && it.toId == memberId) ||
            (it.fromType == "companion" && it.fromId == memberId)
    }
    val actions = rememberLocalRecordActions()
    var mode by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    var expectedRevision by rememberSaveable(memberId) { mutableStateOf<Long?>(null) }
    var condition by rememberSaveable(memberId) { mutableStateOf("Unknown") }
    var notes by rememberSaveable(memberId) { mutableStateOf("") }
    var source by rememberSaveable(memberId) { mutableStateOf("") }
    var resourceId by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    var resourceName by rememberSaveable(memberId) { mutableStateOf("") }
    var resourceCurrent by rememberSaveable(memberId) { mutableStateOf("") }
    var resourceMax by rememberSaveable(memberId) { mutableStateOf("") }
    var baselineFields by rememberSaveable(memberId) { mutableStateOf(emptyList<String>()) }
    var commandId by rememberSaveable(memberId) { mutableStateOf(UUID.randomUUID().toString()) }
    val staleState = mode == "state" && state?.revision != expectedRevision
    val staleResource = mode == "resource" && resources.firstOrNull { it.id == resourceId }?.revision != expectedRevision

    fun editState() {
        expectedRevision = state?.revision
        condition = state?.condition ?: "Unknown"
        notes = state?.notes.orEmpty()
        source = state?.source.orEmpty()
        baselineFields = listOf(condition, notes, source)
        commandId = UUID.randomUUID().toString()
        actions.message = ""
        mode = "state"
    }
    fun editResource(row: CompanionResource?) {
        resourceId = row?.id ?: UUID.randomUUID().toString()
        expectedRevision = row?.revision
        resourceName = row?.name.orEmpty()
        resourceCurrent = row?.current?.toString().orEmpty()
        resourceMax = row?.maximum?.toString().orEmpty()
        source = row?.source ?: state?.source.orEmpty()
        baselineFields = listOf(resourceName, resourceCurrent, resourceMax, source)
        commandId = UUID.randomUUID().toString()
        actions.message = ""
        mode = "resource"
    }

    PlayerEntry("Companion status", state?.condition ?: "Unknown · add a sourced status", "Emblem", ::editState)
    if (!state?.notes.isNullOrBlank()) Text(state!!.notes, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Known resources", style = MaterialTheme.typography.titleMedium)
        PlayerTextButton(onClick = { editResource(null) }, enabled = state != null) { Text("Add resource") }
    }
    if (resources.isEmpty()) Text("No resource values recorded. Unknown is not zero.", color = RebuildPalette.Muted)
    else resources.forEach { row ->
        PlayerEntry(row.name, "${row.current ?: "?"} / ${row.maximum ?: "?"}", "Core", onClick = { editResource(row) })
    }
    if (links.isNotEmpty()) PlayerEntry("Historical equipment associations", "${links.size} relationship(s) · current possession is under Equipment", "Core", onClick = { mode = "links" })
    if (mode == null) CompanionEditorFeedback(actions.message, actions.busy)

    if (mode == "state") PlayerSheet("Edit $memberName status", onDismiss = { mode = null },
        dirty = listOf(condition, notes, source) != baselineFields, actions = {
        PlayerButton(onClick = {
            val recordedSource = source.trim()
            val revision = expectedRevision
            val original = state
            actions.run {
                require(recordedSource.isNotEmpty()) { "Name the confirmed in-game source or player correction." }
                require(state?.revision == revision) { "The status changed. Reopen to edit the current revision." }
                val draft = (original ?: CompanionState(name = memberName, companionId = memberId))
                    .copy(condition = condition.trim().ifBlank { "Unknown" }, notes = notes.trim())
                records.saveCompanionState(draft, revision, recordedSource, commandId)
                mode = null
                "$memberName's status saved locally."
            }
        }, enabled = !actions.busy && !staleState && source.isNotBlank()) { Text("Save status") }
    }) {
        if (staleState) Text("This status changed. Close and reopen it to review the current revision.", color = MaterialTheme.colorScheme.error)
        Text("Only record player-known, DM-confirmed changes. Status editing never refills resources.", color = RebuildPalette.Muted)
        Field("Condition / current state", condition, { condition = it }, enabled = !actions.busy)
        Field("Companion notes and accepted capabilities", notes, { notes = it }, single = false, enabled = !actions.busy)
        Field("Source / resolved scene", source, { source = it }, single = false, enabled = !actions.busy)
        CompanionEditorFeedback(actions.message, actions.busy)
    }

    if (mode == "resource") PlayerSheet("${memberName} · resource", onDismiss = { mode = null }, dirty = listOf(resourceName, resourceCurrent, resourceMax, source) != baselineFields, actions = {
        PlayerButton(onClick = {
            val candidate = resourceId ?: error("Resource identity unavailable")
            val prior = resources.firstOrNull { it.id == candidate }
            val revision = expectedRevision
            val confirmedSource = source.trim()
            actions.run {
                require(state != null) { "Save $memberName's status before resources." }
                require(confirmedSource.isNotBlank()) { "Resource source is required." }
                require(prior?.revision == revision) { "Resource changed. Reopen it before saving." }
                fun nullableCount(text: String, label: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull()?.also { number -> require(number in 0..1_000_000) { "$label must be nonnegative and within the supported limit." } }
                        ?: error("$label must be a whole number or blank for unknown.")
                }
                val current = nullableCount(resourceCurrent, "Current amount")
                val maximum = nullableCount(resourceMax, "Maximum amount")
                require(current == null || maximum == null || current <= maximum) { "Current amount cannot exceed maximum." }
                val draft = CompanionResource(name = resourceName.trim(), companionId = memberId,
                    id = candidate, current = current, maximum = maximum)
                records.saveCompanionResource(draft, revision, confirmedSource, commandId)
                mode = null
                "Resource saved locally; no recharge assumed."
            }
        }, enabled = !actions.busy && !staleResource && resourceName.isNotBlank() && source.isNotBlank()) { Text("Save resource") }
    }) {
        if (staleResource) Text("This resource changed. Close and reopen it to review the current revision.", color = MaterialTheme.colorScheme.error)
        Text("Blank values mean unknown. Never silently restore charges/HP on a rest.", color = RebuildPalette.Muted)
        Field("Resource name", resourceName, { resourceName = it }, enabled = !actions.busy)
        Field("Current amount · blank = unknown", resourceCurrent, { resourceCurrent = it }, enabled = !actions.busy)
        Field("Maximum amount · blank = unknown", resourceMax, { resourceMax = it }, enabled = !actions.busy)
        Field("Evidence / game event source", source, { source = it }, single = false, enabled = !actions.busy)
        CompanionEditorFeedback(actions.message, actions.busy)
    }

    if (mode == "links") PlayerSheet("${memberName} equipment", onDismiss = { mode = null }) {
        Text("Existing links indicate equipment associations, not verified transfer of ownership or consumption.", color = RebuildPalette.Muted)
        if (links.isEmpty()) Text("No item linked to this companion in the local records.", color = RebuildPalette.Muted)
        links.forEach { link ->
            val itemId = if (link.toType == "item") link.toId else link.fromId
            val item = items.firstOrNull { it.id == itemId }
            PlayerCard {
                Text(item?.title ?: "Item not in visible inventory", style = MaterialTheme.typography.titleMedium)
                Text(link.label, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Use the Equipment section to record an atomic give, take, equip, install or store action. These older associations remain historical references.", color = RebuildPalette.Muted)
    }
}


@Composable private fun CompanionEditorFeedback(message: String, busy: Boolean) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotBlank()) Text(message,
        color = if (message.startsWith("Not saved")) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
