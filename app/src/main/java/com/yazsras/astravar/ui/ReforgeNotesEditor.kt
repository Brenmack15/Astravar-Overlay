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

/** One sequential writer, expected revisions, durable recovery text, and an explicit conflict review. */
@Composable internal fun ReforgeNotesEditor(saved: ReforgeSavedCharacter, repository: ReforgeRepository, onDismiss: () -> Unit) {
    var base by remember(saved.character.id) { mutableStateOf(saved) }
    var text by rememberSaveable(saved.character.id) { mutableStateOf(saved.character.notes) }
    var textRevision by rememberSaveable(saved.character.id) { mutableLongStateOf(saved.revision) }
    var undo by rememberSaveable(saved.character.id) { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<ReforgeNoteDraft?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading notes…") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository, saved.character.id) {
        try {
            base = repository.get(saved.character.id) ?: error("Character is no longer available.")
            val found = repository.readNoteDraft(saved.character.id)
            draft = found
            when {
                found == null -> {
                    conflict = textRevision != base.revision && text != base.character.notes
                    status = if (conflict) "Recovered text needs review" else "Saved on this device"
                }
                found.text == base.character.notes -> {
                    repository.discardNoteDraft(found.profileId, found.expectedRevision, found.updatedAt)
                    draft = null; text = base.character.notes; textRevision = base.revision; status = "Saved on this device"
                }
                found.expectedRevision == base.revision -> { text = found.text; status = "Recovered your local draft" }
                else -> { conflict = true; status = "Recovery text needs review" }
            }
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
                    draft = null
                }
                textRevision = base.revision; status = "Saved on this device"
                return@collect
            }
            try {
                status = "Saving recovery text…"; error = ""
                draft = repository.saveNoteDraft(base.character.id, base.revision, proposed, draft?.updatedAt)
                delay(500)
                if (proposed != text) return@collect
                val recovery = draft
                val next = repository.save(base.character.id, base.revision, base.character.copy(notes = proposed), "Autosave character notes")
                base = next; textRevision = next.revision
                if (recovery != null) repository.discardNoteDraft(recovery.profileId, recovery.expectedRevision, recovery.updatedAt)
                draft = null; status = "Saved on this device · revision ${next.revision}"
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
            PlayerButton(onClick = {
                scope.launch {
                    try {
                        val latest = repository.get(base.character.id) ?: error("Character is unavailable.")
                        // Refresh the comparison first if another edit occurred while reviewing.
                        check(latest.revision == base.revision) { "Saved notes changed again. Close and reopen to compare the latest revision. Recovery text was retained." }
                        val recovery = draft
                        if (recovery != null) {
                            draft = repository.reviewNoteDraft(base.character.id, recovery.updatedAt, latest.revision)
                            text = recovery.text
                        }
                        base = latest; textRevision = latest.revision; error = ""; conflict = false; status = "Reviewed edit ready to save"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Recovery text is retained." }
                }
            }) { Text("Use recovery as reviewed edit") }
            PlayerTextButton(onClick = onDismiss) { Text("Keep both and close") }
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
