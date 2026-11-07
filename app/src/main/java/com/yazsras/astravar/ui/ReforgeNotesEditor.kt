package com.yazsras.astravar.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class ReforgeNotesLoadDecision(val text: String, val conflict: Boolean, val discardDuplicate: Boolean)

/** A restored editor is newer than its own last persisted draft; competing writers require review. */
internal fun reforgeNotesLoadDecision(canonical: String, revision: Long, editorText: String, editorRevision: Long,
    restored: Boolean, knownDraftUpdatedAt: Long?, draft: ReforgeNoteDraft?): ReforgeNotesLoadDecision {
    val duplicate = draft?.text == canonical
    if (restored) {
        val staleEditor = editorRevision != revision && editorText != canonical
        val competingDraft = draft != null && !duplicate &&
            (draft.expectedRevision != revision || (draft.updatedAt != knownDraftUpdatedAt && draft.text != editorText))
        return ReforgeNotesLoadDecision(editorText, staleEditor || competingDraft, duplicate)
    }
    return when {
        draft == null || duplicate -> ReforgeNotesLoadDecision(canonical, false, duplicate)
        draft.expectedRevision == revision -> ReforgeNotesLoadDecision(draft.text, false, false)
        else -> ReforgeNotesLoadDecision(canonical, true, false)
    }
}

/** One sequential writer, expected revisions, durable recovery text, and an explicit conflict review. */
@Composable internal fun ReforgeNotesEditor(saved: ReforgeSavedCharacter, repository: ReforgeRepository, onDismiss: () -> Unit) {
    var base by remember(saved.character.id) { mutableStateOf(saved) }
    var text by rememberSaveable(saved.character.id) { mutableStateOf(saved.character.notes) }
    var textRevision by rememberSaveable(saved.character.id) { mutableLongStateOf(saved.revision) }
    var initializedBefore by rememberSaveable(saved.character.id) { mutableStateOf(false) }
    var knownDraftStamp by rememberSaveable(saved.character.id) { mutableLongStateOf(-1L) }
    var undo by rememberSaveable(saved.character.id) { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<ReforgeNoteDraft?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading notes…") }
    var error by remember { mutableStateOf("") }
    var reviewing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository, saved.character.id) {
        try {
            base = repository.get(saved.character.id) ?: error("Character is no longer available.")
            val found = repository.readNoteDraft(saved.character.id)
            draft = found
            val decision = reforgeNotesLoadDecision(base.character.notes, base.revision, text, textRevision,
                initializedBefore, knownDraftStamp.takeIf { it >= 0 }, found)
            text = decision.text; conflict = decision.conflict
            if (decision.discardDuplicate && found != null) {
                repository.discardNoteDraft(found.profileId, found.expectedRevision, found.updatedAt)
                draft = null
            }
            knownDraftStamp = draft?.updatedAt ?: -1L
            if (!conflict) textRevision = base.revision
            initializedBefore = true
            status = when { conflict -> "Recovery text needs review"; text != base.character.notes -> "Recovered your editor text"; else -> "Saved on this device" }
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Notes could not be loaded." }
    }
    LaunchedEffect(loaded, conflict) {
        if (!loaded || conflict) return@LaunchedEffect
        // Collect sequentially: cancelling a debounce must never lose a committed receipt.
        snapshotFlow { text }.distinctUntilChanged().collect { proposed ->
            if (proposed == base.character.notes) {
                draft?.takeIf { it.expectedRevision == base.revision }?.let { recovery ->
                    repository.discardNoteDraft(recovery.profileId, recovery.expectedRevision, recovery.updatedAt)
                    draft = null; knownDraftStamp = -1L
                }
                textRevision = base.revision; status = "Saved on this device"
                return@collect
            }
            try {
                status = "Saving recovery text…"; error = ""
                draft = repository.saveNoteDraft(base.character.id, base.revision, proposed, draft?.updatedAt)
                knownDraftStamp = draft?.updatedAt ?: -1L
                delay(500)
                if (proposed != text) return@collect
                val recovery = draft
                val next = repository.save(base.character.id, base.revision, base.character.copy(notes = proposed), "Autosave character notes")
                base = next; textRevision = next.revision
                if (recovery != null) repository.discardNoteDraft(recovery.profileId, recovery.expectedRevision, recovery.updatedAt)
                draft = null; knownDraftStamp = -1L; status = "Saved on this device · revision ${next.revision}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e.message ?: "Notes were not saved. Recovery text is retained."
                draft = runCatching { repository.readNoteDraft(base.character.id) }.getOrNull()
                runCatching { repository.get(base.character.id) }.getOrNull()?.let { base = it }
                conflict = true; status = "Not saved · review required"
            }
        }
    }
    fun edit(value: String) { if (value.length <= 100_000) { undo = text; text = value } }
    fun reviewSelectedText(chosenText: String) {
        if (reviewing) return
        reviewing = true
        scope.launch {
            try {
                val latest = repository.get(base.character.id) ?: error("Character is unavailable.")
                check(latest.revision == base.revision) { "Saved notes changed again. Reopen to compare the latest revision. Recovery text was retained." }
                val recovery = draft
                draft = if (recovery != null) repository.reviewNoteDraft(base.character.id, recovery.updatedAt, latest.revision, chosenText)
                    else repository.saveNoteDraft(base.character.id, latest.revision, chosenText)
                knownDraftStamp = draft?.updatedAt ?: -1L
                text = chosenText; textRevision = latest.revision; base = latest
                error = ""; conflict = false; status = "Reviewed edit ready to save"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Recovery text was retained." }
            finally { reviewing = false }
        }
    }
    PlayerSheet("Character notes", onDismiss, dirty = loaded && text != base.character.notes, actions = {
        PlayerTextButton(onClick = { preview = !preview }) { Text(if (preview) "Write" else "Preview") }
        PlayerTextButton(enabled = undo != null && !conflict, onClick = { val current = text; text = undo ?: text; undo = current }) { Text("Undo") }
    }) {
        Text(status, color = if (conflict) RebuildPalette.GoldBright else RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
        Text("Notes save automatically on this device. Campaign synchronization remains separate.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (conflict) {
            Text("The character changed after this draft began. Review both texts before choosing a new edit.", color = RebuildPalette.GoldBright)
            PlayerDisclosure("Currently saved notes", initiallyOpen = true) { ReforgeRichText(base.character.notes.ifBlank { "No saved notes." }) }
            PlayerDisclosure("Recovery text", initiallyOpen = true) { ReforgeRichText(draft?.text ?: text) }
            PlayerButton(enabled = !reviewing, onClick = { reviewSelectedText(draft?.text ?: text) }) { Text("Use recovery as reviewed edit") }
            PlayerOutlinedButton(enabled = !reviewing, onClick = { reviewSelectedText(base.character.notes) }) { Text("Keep saved notes") }
            if (draft != null && text != draft?.text && text != base.character.notes) {
                PlayerDisclosure("Restored editor text", initiallyOpen = true) { ReforgeRichText(text) }
                PlayerOutlinedButton(enabled = !reviewing, onClick = { reviewSelectedText(text) }) { Text("Use restored editor text") }
            }
            val guardedClose = LocalPlayerDismiss.current
            PlayerTextButton(onClick = guardedClose) { Text("Close notes") }
        } else if (preview) ReforgeRichText(text.ifBlank { "Start writing your notes." })
        else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Heading" to "\n## Heading\n", "Bold" to "**text**", "List" to "\n- ", "Checklist" to "\n- [ ] ", "Quote" to "\n> ").forEach { (label, value) ->
                    PlayerTextButton(onClick = { edit(text + value) }, enabled = loaded) { Text(label) }
                }
            }
            OutlinedTextField(text, ::edit, enabled = loaded, label = { Text("Write your notes") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp), minLines = 8)
        }
    }
}
