package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Associations use original identities and never move equipment or rewrite source profiles. */
@Composable
internal fun PartyRelationshipsV31(repository: CampaignLedgerRepository, memberId: String, memberName: String, showSummary: Boolean = false) {
    val records = repository.records
    val links by records.links.collectAsState(initial = emptyList())
    val profiles by records.characterProfiles.collectAsState(initial = emptyList())
    val states by records.companionStates.collectAsState(initial = emptyList())
    val entities by records.entities.collectAsState(initial = emptyList())
    val projects by records.projects.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val actions = rememberLocalRecordActions()
    val ownType = if (memberId == "local-player") "character-profile" else "companion"
    val related = links.filter { (it.fromType in setOf("character-profile", "companion") && it.fromId == memberId) ||
        (it.toType in setOf("character-profile", "companion") && it.toId == memberId) }
    var mode by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    val baselineDraft = rememberUiDraftText()
    var baselineJson by baselineDraft
    val baseline = remember(baselineJson) { baselineJson?.let { runCatching { Json.decodeFromString<CampaignLink>(it) }.getOrNull() } }
    var kind by rememberSaveable(memberId) { mutableStateOf("Character") }
    var target by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    var label by rememberSaveable(memberId) { mutableStateOf("") }
    var source by rememberSaveable(memberId) { mutableStateOf("") }
    var command by rememberSaveable(memberId) { mutableStateOf(UUID.randomUUID().toString()) }
    fun open(link: CampaignLink? = null, remove: Boolean = false) {
        baselineJson = link?.let { Json.encodeToString(it) }
        label = link?.label.orEmpty(); source = link?.source.orEmpty(); kind = "Character"; target = null
        command = UUID.randomUUID().toString(); actions.message = ""
        mode = if (remove) "remove" else if (link == null) "create" else "edit"
    }
    fun targetName(type: String, id: String): String = when (type) {
        "character-profile", "companion" -> profiles.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank)
            ?: states.firstOrNull { it.companionId == id }?.name?.takeIf(String::isNotBlank)
        "item" -> items.firstOrNull { it.id == id }?.title
        "connection" -> entities.firstOrNull { it.id == id }?.name
        "project" -> projects.firstOrNull { it.id == id }?.title
        else -> null
    } ?: "Unavailable $type"

    if (showSummary && related.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Bonds", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                color = RebuildPalette.GoldBright)
            Text("${related.size}", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
        }
        related.take(3).forEach { link ->
            val outgoing = link.fromId == memberId && link.fromType in setOf("character-profile", "companion")
            val type = if (outgoing) link.toType else link.fromType
            val id = if (outgoing) link.toId else link.fromId
            PlayerEntry(targetName(type, id), link.label, "Emblem", { open(link) })
        }
    }
    PlayerDisclosure("Relationships") {
        if (related.isEmpty()) Text("No relationships recorded. Link a character, ally or campaign record.", color = RebuildPalette.Muted)
        related.forEach { link ->
            val outgoing = link.fromId == memberId && link.fromType in setOf("character-profile", "companion")
            val type = if (outgoing) link.toType else link.fromType
            val id = if (outgoing) link.toId else link.fromId
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(targetName(type, id), style = MaterialTheme.typography.titleMedium)
                Text(link.label, color = RebuildPalette.Ice)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerTextButton(onClick = { open(link) }, enabled = !actions.busy) { Text("Edit relationship") }
                    PlayerTextButton(onClick = { open(link, remove = true) }, enabled = !actions.busy) { Text("Remove relationship") }
                }
            }
        }
        if (states.none { it.companionId == memberId }) Text("Save this companion's status before adding a relationship.", color = RebuildPalette.Muted)
        PlayerTextButton(onClick = { open() }, enabled = !actions.busy && states.any { it.companionId == memberId }) { Text("Add relationship") }
        if (actions.message.isNotBlank()) Text(actions.message, color = if (actions.message.startsWith("Not saved")) MaterialTheme.colorScheme.error else RebuildPalette.Ice)
    }
    mode?.let { currentMode ->
        val stale = baseline != null && links.none { it.id == baseline.id && it.revision == baseline.revision }
        val safe = baselineDraft.canSave && (currentMode == "create" || baseline != null) && !stale
        PlayerSheet(if (currentMode == "remove") "Remove relationship" else "$memberName · relationship",
            onDismiss = { if (!actions.busy) mode = null },
            dirty = currentMode != "remove" && (label != baseline?.label.orEmpty() || source != baseline?.source.orEmpty() || target != null), actions = {
                PlayerButton(enabled = !actions.busy && safe && (currentMode == "remove" || label.isNotBlank()) &&
                    (currentMode != "create" || target != null), onClick = {
                    val provenance = source.trim().ifBlank { "Player-recorded local relationship" }
                    actions.run {
                        if (currentMode == "remove") records.unlink(requireNotNull(baseline).id, baseline.revision, provenance, command)
                        else {
                            val next = baseline?.copy(label = label.trim()) ?: CampaignLink(ownType, memberId,
                                connectionTargetType(kind), requireNotNull(target), label.trim(), id = "link-$command")
                            records.saveLink(next, baseline?.revision, provenance, command)
                        }
                        mode = null
                        if (currentMode == "remove") "Relationship removed; history retained." else "Relationship saved locally."
                    }
                }) { Text(if (currentMode == "remove") "Remove relationship" else "Save relationship") }
            }) {
            if (!safe) Text(baselineDraft.error ?: "The relationship changed. Close and reopen it before saving.", color = MaterialTheme.colorScheme.error)
            if (currentMode == "create") {
                ConnectionChoice("Related record type", listOf("Character", "Companion", "Inventory item", "Invention", "Connection"), kind, !actions.busy) { kind = it; target = null }
                val companionIds = states.map { it.companionId }.toSet()
                val choices = when (kind) {
                    "Character" -> profiles.filterNot { it.id == memberId || it.id in companionIds }.map { it.id to "${it.name.ifBlank { "Unnamed character" }} · Character" }
                    "Companion" -> states.filterNot { it.companionId == memberId }.map { it.companionId to "${targetName("companion", it.companionId)} · Companion" }
                    "Inventory item" -> items.map { it.id to "${it.title} · Item" }
                    "Invention" -> projects.map { it.id to "${it.title} · Invention" }
                    else -> entities.map { it.id to "${it.name} · ${it.kind}" }
                }.sortedBy { it.second.lowercase() }
                if (choices.isEmpty()) Text("No saved ${kind.lowercase()} records available.", color = RebuildPalette.Muted)
                else ConnectionTargetChoice(choices, target, !actions.busy) { target = it }
            }
            if (currentMode == "remove") Text("Remove “${baseline?.label.orEmpty()}”? Both records, ownership and all prior revisions remain saved.", color = RebuildPalette.Muted)
            else Field("Relationship to this record", label, { label = it }, enabled = !actions.busy)
            PlayerDisclosure("Source (optional)") { Field("Relationship source", source, { source = it }, enabled = !actions.busy) }
            if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (actions.message.startsWith("Not saved")) Text(actions.message, color = MaterialTheme.colorScheme.error)
        }
    }
}
