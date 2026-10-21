package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Local player-known checkpoints. Creating one does not import or replace the Notion authority. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun CampaignCheckpointPanel(repository: CampaignLedgerRepository) {
    val checkpoints by repository.party.checkpoints.collectAsState(initial = emptyList())
    val audit by repository.records.audit.collectAsState(initial = emptyList())
    var editor by rememberSaveable { mutableStateOf(false) }
    val snapshotState = rememberUiDraftText()
    var snapshotJson by snapshotState
    var historyId by rememberSaveable { mutableStateOf<String?>(null) }
    val json = remember { Json { encodeDefaults = true } }
    Page {
        PlayerHeading("Campaign checkpoints")
        Text("Record the player-known scene, exact last action and unresolved decisions. These local records do not perform the real-campaign import or change its source of truth.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        PlayerButton(onClick = { snapshotJson = null; editor = true }) { Text("Add local checkpoint") }
        if (checkpoints.isEmpty()) PlayerEmpty("No local checkpoint", "Unknown HP, resources, game time and unresolved rolls must remain unknown until a confirmed source establishes them.")
        checkpoints.forEach { checkpoint -> PlayerCard {
            Text(checkpoint.title, style = MaterialTheme.typography.titleLarge)
            RecordValue("Scene", checkpoint.scene.ifBlank { "Unknown" })
            RecordValue("Last confirmed action", checkpoint.lastConfirmedAction.ifBlank { "Unknown" })
            RecordValue("Unresolved choice / roll", checkpoint.unresolvedChoice.ifBlank { "Not recorded" })
            if (checkpoint.objective.isNotBlank()) RecordValue("Objective at checkpoint", checkpoint.objective)
            if (checkpoint.gameTime.isNotBlank()) RecordValue("In-world time", checkpoint.gameTime)
            if (checkpoint.unknowns.isNotEmpty()) RecordValue("Unknowns / conflicts", checkpoint.unknowns.joinToString("\n"))
            if (checkpoint.recordReferences.isNotEmpty()) RecordValue("Record references", checkpoint.recordReferences.joinToString("\n"))
            Text("Revision ${checkpoint.revision} · ${checkpoint.source}", color = RebuildPalette.Muted,
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerTextButton(onClick = { snapshotJson = json.encodeToString(checkpoint); editor = true }) { Text("Edit checkpoint") }
                PlayerTextButton(onClick = { historyId = checkpoint.id }) { Text("History") }
            }
        } }
    }
    if (editor) {
        val snapshot = snapshotJson?.let { runCatching { json.decodeFromString<CampaignCheckpoint>(it) }.getOrNull() }
        if (snapshotState.canSave) CampaignCheckpointEditorV21(repository, snapshot, checkpoints.firstOrNull { it.id == snapshot?.id }?.revision,
            onDismiss = { editor = false })
        else PlayerSheet("Checkpoint draft unavailable", onDismiss = { editor = false }) {
            Text(snapshotState.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    }
    historyId?.let { id -> PlayerSheet("Checkpoint history", onDismiss = { historyId = null }) {
        val rows = audit.filter { it.recordType == "checkpoint" && it.recordId == id }
        if (rows.isEmpty()) Text("No local history found.", color = RebuildPalette.Muted)
        rows.forEach { row -> PlayerDisclosure("${row.kind} · revision ${row.resultingRevision}") {
            RecordValue("Source", row.source)
            RecordValue("Receipt ID", row.commandId)
            row.beforeJson?.let { RecordValue("Earlier snapshot", it) }
            row.afterJson?.let { RecordValue("Saved snapshot", it) }
        } }
    } }
}

@Composable private fun CampaignCheckpointEditorV21(repository: CampaignLedgerRepository, snapshot: CampaignCheckpoint?,
    latestRevision: Long?, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(snapshot?.title.orEmpty()) }
    var scene by rememberSaveable { mutableStateOf(snapshot?.scene.orEmpty()) }
    var lastAction by rememberSaveable { mutableStateOf(snapshot?.lastConfirmedAction.orEmpty()) }
    var unresolved by rememberSaveable { mutableStateOf(snapshot?.unresolvedChoice.orEmpty()) }
    var objective by rememberSaveable { mutableStateOf(snapshot?.objective.orEmpty()) }
    var gameTime by rememberSaveable { mutableStateOf(snapshot?.gameTime.orEmpty()) }
    val referencesState = rememberUiDraftText(snapshot?.recordReferences?.joinToString("\n").orEmpty())
    var references by referencesState
    val unknownsState = rememberUiDraftText(snapshot?.unknowns?.joinToString("\n").orEmpty())
    var unknowns by unknownsState
    var source by rememberSaveable { mutableStateOf("") }
    val id = rememberSaveable { snapshot?.id ?: UUID.randomUUID().toString() }
    val commandId = rememberSaveable { UUID.randomUUID().toString() }
    val actions = rememberLocalRecordActions()
    val stale = snapshot?.revision != latestRevision
    fun lines(value: String) = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val dirty = title != snapshot?.title.orEmpty() || scene != snapshot?.scene.orEmpty() ||
        lastAction != snapshot?.lastConfirmedAction.orEmpty() || unresolved != snapshot?.unresolvedChoice.orEmpty() ||
        objective != snapshot?.objective.orEmpty() || gameTime != snapshot?.gameTime.orEmpty() ||
        references != snapshot?.recordReferences?.joinToString("\n").orEmpty() ||
        unknowns != snapshot?.unknowns?.joinToString("\n").orEmpty() || source.isNotBlank()
    PlayerSheet("Record local checkpoint", onDismiss, dirty = dirty, actions = {
        PlayerButton(onClick = { actions.run {
            val checkpoint = (snapshot ?: CampaignCheckpoint(id = id, title = title.trim())).copy(
                title = title.trim(), scene = scene, lastConfirmedAction = lastAction, unresolvedChoice = unresolved,
                objective = objective, gameTime = gameTime, recordReferences = lines(references.orEmpty()), unknowns = lines(unknowns.orEmpty()))
            repository.party.saveCheckpoint(checkpoint, snapshot?.revision, source.trim(), commandId)
            onDismiss(); "Local checkpoint saved."
        } }, enabled = !actions.busy && referencesState.canSave && unknownsState.canSave && !stale && title.isNotBlank() && source.isNotBlank()) { Text("Save local checkpoint") }
    }) {
        if (stale) Text("This checkpoint changed. Close and reopen to review the current revision.", color = MaterialTheme.colorScheme.error)
        Text("Include only authorized player-known information. Saving never advances game time, resolves a pending roll or restores resources.", color = RebuildPalette.Muted)
        Field("Checkpoint title", title, { title = it })
        Field("Current scene / location", scene, { scene = it }, single = false)
        Field("Exact last confirmed action", lastAction, { lastAction = it }, single = false)
        Field("Unresolved choice or pending roll", unresolved, { unresolved = it }, single = false)
        Field("Current objective", objective, { objective = it }, single = false)
        Field("Confirmed in-world time · blank = unknown", gameTime, { gameTime = it })
        Field("Record IDs / source links · one per line", references.orEmpty(), { references = it }, single = false)
        Field("Unknowns / conflicts · one per line", unknowns.orEmpty(), { unknowns = it }, single = false)
        listOfNotNull(referencesState.error, unknownsState.error).distinct().forEach {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Field("Checkpoint source (required)", source, { source = it }, single = false, enabled = !actions.busy)
        LocalRecordStatus(actions.message, actions.busy)
    }
}
