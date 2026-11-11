package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SAF-only native character recovery. Data and large previews never enter saved-instance Bundles. */
@Composable fun ReforgeBackupPage(repository: ReforgeRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var failure by rememberSaveable { mutableStateOf(false) }
    var reviewWasOpen by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ReforgeRestorePreview?>(null) }
    var pendingExport by remember { mutableStateOf<ReforgeExportSnapshot?>(null) }
    var savedBackupFingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (reviewWasOpen && preview == null) {
            reviewWasOpen = false
            status = "Choose the backup again for a fresh review. Check saved characters before retrying any interrupted restore."
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = pendingExport
        pendingExport = null
        if (uri == null) {
            status = "Backup save cancelled."
        } else if (snapshot == null) {
            failure = true
            status = "The export snapshot was not retained. Save a new backup."
        } else if (!busy) {
            busy = true; failure = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(CampaignDocumentIoV21.utf8Size(snapshot.text) <= REFORGE_DOCUMENT_BYTES) { "Backup exceeds the 8 MiB document limit." }
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the chosen backup document.")
                        output.use { CampaignDocumentIoV21.writeUtf8(snapshot.text, it); it.flush() }
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot reopen the saved backup for verification.")
                        CampaignDocumentIoV21.verifySavedUtf8(snapshot.text, input, REFORGE_DOCUMENT_BYTES)
                    }
                    // A mere successful write is insufficient: this proof follows independent byte readback.
                    savedBackupFingerprint = snapshot.currentFingerprint
                    preview?.backup?.let { incoming ->
                        val text = withContext(Dispatchers.Default) { Json { encodeDefaults = true }.encodeToString(incoming) }
                        preview = repository.previewRestore(text)
                    }
                    status = "Character backup saved and verified."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    savedBackupFingerprint = null; failure = true
                    status = "Backup not verified: ${e.message ?: "choose a different destination and retry"}"
                } finally { busy = false }
            }
        }
    }
    fun saveCurrent() {
        if (busy || pendingExport != null) return
        busy = true; failure = false; status = "Preparing character backup…"
        scope.launch {
            try {
                val snapshot = repository.exportWithProof()
                withContext(Dispatchers.Default) {
                    require(CampaignDocumentIoV21.utf8Size(snapshot.text) <= REFORGE_DOCUMENT_BYTES) { "Backup exceeds the 8 MiB document limit." }
                }
                pendingExport = snapshot
                busy = false
                exporter.launch("astravar-character-backup.json")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; pendingExport = null; failure = true; status = "Backup could not be prepared: ${e.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true; preview = null; reviewWasOpen = false; failure = false
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected backup.")
                        CampaignDocumentIoV21.readUtf8(input, REFORGE_DOCUMENT_BYTES)
                    }
                    preview = repository.previewRestore(text)
                    reviewWasOpen = true
                    status = "Preview ready. Your saved characters have not changed."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = true; status = "Backup preview rejected: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    Page {
        PlayerHeading("Character backup")
        PlayerCard {
            Text("Keep your playable sheets safe", style = MaterialTheme.typography.titleLarge)
            Text("Characters and companions, equipment and effects, spells and resources, notes and recovery drafts, every saved roll and change.", color = RebuildPalette.Muted)
            PlayerButton(onClick = ::saveCurrent, enabled = !busy && pendingExport == null, modifier = Modifier.testTag("reforge-export-backup")) { Text("Save character backup") }
        }
        PlayerCard {
            Text("Add records from a backup", style = MaterialTheme.typography.titleLarge)
            Text("Review the file, save a verified current backup, then confirm the merge. Different existing records are retained and block conflicting restores.", color = RebuildPalette.Muted)
            PlayerOutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy,
                modifier = Modifier.testTag("reforge-choose-backup")) { Text("Choose character backup") }
        }
        Text("These are local character sheets. Keep the separate Campaign and Weapon backups too. No backup file is uploaded by this screen.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, color = if (failure) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.testTag("reforge-backup-status").semantics { liveRegion = LiveRegionMode.Polite })
    }
    preview?.let { candidate ->
        val backupProtected = savedBackupFingerprint == candidate.currentFingerprint
        PlayerSheet("Review character backup", onDismiss = { if (!busy) { preview = null; reviewWasOpen = false } }, actions = {
            PlayerTextButton(onClick = { if (!busy) { preview = null; reviewWasOpen = false } }, enabled = !busy) { Text("Cancel") }
            PlayerButton(enabled = !busy && candidate.valid && backupProtected, modifier = Modifier.testTag("reforge-confirm-restore"), onClick = {
                if (busy || !candidate.valid || !backupProtected) return@PlayerButton
                busy = true; failure = false
                scope.launch {
                    try {
                        repository.restore(candidate)
                        preview = null; reviewWasOpen = false; savedBackupFingerprint = null
                        status = "Backup records merged on this device."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = true; savedBackupFingerprint = null; status = "Restore not applied: ${e.message}" }
                    finally { busy = false }
                }
            }) { Text("Confirm merge") }
        }) {
            Text(candidate.summary, style = MaterialTheme.typography.titleMedium)
            Text("Preview changes nothing. Different existing records will not be replaced, and source campaign records remain separate.", color = RebuildPalette.Muted)
            if (!backupProtected) {
                Text("Save and verify a backup of the current character data to enable this merge.", color = RebuildPalette.GoldBright)
                PlayerOutlinedButton(onClick = ::saveCurrent, enabled = !busy && pendingExport == null) { Text("Save current backup") }
            } else Text("Current data has a verified backup.", color = RebuildPalette.Ice)
            candidate.backup?.let { incoming -> PlayerDisclosure("Inspect characters and recovery drafts") {
                val summaries by produceState(initialValue = emptyList<Pair<String, String>>(), key1 = candidate.fingerprint) {
                    value = withContext(Dispatchers.Default) { incoming.characters.map { row ->
                        val saved = row.saved()
                        "${saved.character.name} · level ${saved.character.level} · revision ${saved.revision}" to
                            "${saved.character.items.size} items · ${saved.character.spells.size} spells · ${saved.character.resources.size} resources"
                    } }
                }
                summaries.forEach { (identity, counts) ->
                    Text(identity)
                    Text(counts, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (incoming.drafts.isNotEmpty()) Text("${incoming.drafts.size} recovery drafts remain separate from committed notes.", color = RebuildPalette.GoldBright)
            } }
            if (candidate.conflicts.isNotEmpty()) PlayerDisclosure("${candidate.conflicts.size} conflicts", initiallyOpen = true) {
                candidate.conflicts.take(20).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (candidate.conflicts.size > 20) Text("${candidate.conflicts.size - 20} additional conflicts. Merge is blocked.")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(status, color = MaterialTheme.colorScheme.error)
        }
    }
}
