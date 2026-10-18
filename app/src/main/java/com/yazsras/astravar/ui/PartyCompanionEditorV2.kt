package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import java.util.UUID

/**
 * Companion state and counters stay in their original records, including Eek.
 * The typed sheet complements these records without rewriting accepted homebrew.
 */
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

    fun editState() {
        expectedRevision = state?.revision
        condition = state?.condition ?: "Unknown"
        notes = state?.notes.orEmpty()
        source = state?.source.orEmpty()
        mode = "state"
    }
    fun editResource(row: CompanionResource?) {
        resourceId = row?.id ?: UUID.randomUUID().toString()
        expectedRevision = row?.revision
        resourceName = row?.name.orEmpty()
        resourceCurrent = row?.current?.toString().orEmpty()
        resourceMax = row?.maximum?.toString().orEmpty()
        source = row?.source ?: state?.source.orEmpty()
        mode = "resource"
    }

    PlayerEntry("Companion status", state?.condition ?: "Unknown · add a sourced status", "Emblem", ::editState)
    if (!state?.notes.isNullOrBlank()) Text(state!!.notes, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Known resources", style = MaterialTheme.typography.titleMedium)
        PlayerTextButton(onClick = { editResource(null) }, enabled = state != null) { Text("Add resource") }
    }
    if (resources.isEmpty()) Text("No resource values recorded. Unknown is not zero.", color = RebuildPalette.Muted)
    else resources.forEach { row ->
        PlayerEntry(row.name, "${row.current ?: "?"} / ${row.maximum ?: "?"}", "Core", onClick = { editResource(row) })
    }
    if (links.isNotEmpty()) PlayerEntry("Historical equipment associations", "${links.size} relationship(s) · current possession is under Equipment", "Core", onClick = { mode = "links" })
    if (actions.message.isNotBlank()) Text(actions.message, color = RebuildPalette.Ice)

    if (mode == "state") PlayerSheet("Edit $memberName status", onDismiss = { mode = null },
        dirty = condition != (state?.condition ?: "Unknown") || notes != state?.notes.orEmpty() || source != state?.source.orEmpty(), actions = {
        PlayerButton(onClick = {
            val recordedSource = source.trim()
            val revision = expectedRevision
            val original = state
            actions.run {
                require(recordedSource.isNotEmpty()) { "Name the confirmed in-game source or player correction." }
                require(state?.revision == revision) { "The status changed. Reopen to edit the current revision." }
                val draft = (original ?: CompanionState(name = memberName, companionId = memberId))
                    .copy(condition = condition.trim().ifBlank { "Unknown" }, notes = notes.trim())
                records.saveCompanionState(draft, revision, recordedSource)
                mode = null
                "$memberName's status saved locally."
            }
        }, enabled = !actions.busy && source.isNotBlank()) { Text("Save status") }
    }) {
        Text("Only record player-known, DM-confirmed changes. Status editing never refills resources.", color = RebuildPalette.Muted)
        Field("Condition / current state", condition, { condition = it }, enabled = !actions.busy)
        Field("Companion notes and accepted capabilities", notes, { notes = it }, single = false, enabled = !actions.busy)
        Field("Source / resolved scene", source, { source = it }, single = false, enabled = !actions.busy)
    }

    if (mode == "resource") PlayerSheet("${memberName} · resource", onDismiss = { mode = null }, dirty = true, actions = {
        PlayerButton(onClick = {
            val candidate = resourceId ?: error("Resource identity unavailable")
            val prior = resources.firstOrNull { it.id == candidate }
            val revision = expectedRevision
            val confirmedSource = source.trim()
            actions.run {
                require(state != null) { "Save $memberName's status before resources." }
                require(confirmedSource.isNotBlank()) { "Resource source is required." }
                require(prior?.revision == revision) { "Resource changed. Reopen it before saving." }
                fun nullableCount(text: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                    ?: run { require(text.isBlank()) { "Enter a whole number or leave blank for unknown." }; null }
                val draft = CompanionResource(name = resourceName.trim(), companionId = memberId,
                    id = candidate, current = nullableCount(resourceCurrent), maximum = nullableCount(resourceMax))
                records.saveCompanionResource(draft, revision, confirmedSource)
                mode = null
                "Resource saved locally; no recharge assumed."
            }
        }, enabled = !actions.busy && resourceName.isNotBlank() && source.isNotBlank()) { Text("Save resource") }
    }) {
        Text("Blank values mean unknown. Never silently restore charges/HP on a rest.", color = RebuildPalette.Muted)
        Field("Resource name", resourceName, { resourceName = it }, enabled = !actions.busy)
        Field("Current amount · blank = unknown", resourceCurrent, { resourceCurrent = it }, enabled = !actions.busy)
        Field("Maximum amount · blank = unknown", resourceMax, { resourceMax = it }, enabled = !actions.busy)
        Field("Evidence / game event source", source, { source = it }, single = false, enabled = !actions.busy)
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
