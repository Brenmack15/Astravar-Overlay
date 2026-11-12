package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.util.UUID

/** One SAF document preserves source records, playable mechanics and their explicit relationships. */
@Composable fun GenesisUnifiedBackupPage(repository: GenesisAuthorityBackupRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var failure by rememberSaveable { mutableStateOf(false) }
    var reviewWasOpen by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<GenesisAuthorityRestorePreview?>(null) }
    var pendingExport by remember { mutableStateOf<GenesisAuthorityBackupSnapshot?>(null) }
    var savedBackupFingerprint by remember { mutableStateOf<String?>(null) }
    // Only small identities enter saved-instance state. A retry of the same document keeps its
    // original command; the backend binds that command to the complete incoming fingerprint.
    var restoreFingerprint by rememberSaveable { mutableStateOf("") }
    var restoreCommand by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (reviewWasOpen && preview == null) {
            reviewWasOpen = false
            status = "Choose the backup again for a fresh review. Check saved records before retrying an interrupted merge."
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = pendingExport
        pendingExport = null
        if (uri == null) {
            status = "Backup save cancelled."
        } else if (snapshot == null) {
            failure = true
            status = "The export snapshot was not retained. Save a new complete backup."
        } else if (!busy) {
            busy = true; failure = false; savedBackupFingerprint = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(CampaignDocumentIoV21.utf8Size(snapshot.text) <= GENESIS_AUTHORITY_DOCUMENT_BYTES) {
                            "Complete backup exceeds the supported 64 MiB document limit."
                        }
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Cannot open the chosen backup document.")
                        output.use { CampaignDocumentIoV21.writeUtf8(snapshot.text, it); it.flush() }
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Cannot reopen the saved backup for verification.")
                        CampaignDocumentIoV21.verifySavedUtf8(snapshot.text, input, GENESIS_AUTHORITY_DOCUMENT_BYTES)
                    }
                    // This protection is earned only by reopening and hashing the provider's bytes.
                    savedBackupFingerprint = snapshot.currentFingerprint
                    preview?.let { preview = repository.recheck(it) }
                    status = "Complete backup saved and independently verified."
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
        busy = true; failure = false; status = "Preparing complete backup…"
        scope.launch {
            try {
                pendingExport = repository.exportWithProof()
                busy = false
                exporter.launch("astravar-connected-backup.json")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false; pendingExport = null; failure = true
                status = "Backup could not be prepared: ${e.message}"
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            status = "Backup selection cancelled."
        } else if (!busy && pendingExport == null) {
            busy = true; preview = null; reviewWasOpen = false; failure = false
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected backup.")
                        CampaignDocumentIoV21.readUtf8(input, GENESIS_AUTHORITY_DOCUMENT_BYTES)
                    }
                    val candidate = repository.previewRestore(text)
                    if (candidate.fingerprint != restoreFingerprint || restoreCommand.isBlank()) {
                        restoreFingerprint = candidate.fingerprint
                        restoreCommand = UUID.randomUUID().toString()
                    }
                    preview = candidate; reviewWasOpen = true
                    status = "Preview ready. Your saved records have not changed."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = true; status = "Backup preview rejected: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    Page {
        PlayerHeading("Complete backup")
        PlayerCard {
            Text("Keep connected records together", style = MaterialTheme.typography.titleLarge)
            Text("Campaigns and source history, characters and companions, inventory and equipment, playable mechanics, relationships, reviews and recovery drafts.", color = RebuildPalette.Muted)
            PlayerButton(onClick = ::saveCurrent, enabled = !busy && pendingExport == null,
                modifier = Modifier.testTag("genesis-export-backup")) { Text("Save complete backup") }
        }
        PlayerCard {
            Text("Recover from a file", style = MaterialTheme.typography.titleLarge)
            Text("Review the file and save a verified current backup before merging. Conflicting records are preserved and block the merge.", color = RebuildPalette.Muted)
            PlayerOutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy && pendingExport == null, modifier = Modifier.testTag("genesis-choose-backup")) { Text("Choose backup") }
        }
        Text("Existing campaign and character backup files can be reviewed here. The separate Weapon backup remains available in Tools. Files stay at your chosen destination; this screen uploads nothing.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Complete backup files support up to 64 MiB. Larger records remain on the device; a failed export never clears them.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, color = if (failure) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.testTag("genesis-backup-status").semantics { liveRegion = LiveRegionMode.Polite })
    }
    preview?.let { candidate ->
        val backupProtected = savedBackupFingerprint != null && savedBackupFingerprint == candidate.currentFingerprint
        PlayerSheet("Review complete backup", dismissEnabled = !busy && pendingExport == null,
            onDismiss = { if (!busy && pendingExport == null) { preview = null; reviewWasOpen = false } }, actions = {
            PlayerTextButton(enabled = !busy && pendingExport == null, onClick = { preview = null; reviewWasOpen = false }) { Text("Cancel") }
            PlayerButton(enabled = !busy && pendingExport == null && candidate.valid && backupProtected,
                modifier = Modifier.testTag("genesis-confirm-restore"), onClick = {
                    if (busy || pendingExport != null || !candidate.valid || !backupProtected) return@PlayerButton
                    busy = true; failure = false
                    scope.launch {
                        try {
                            val receipt = repository.restore(candidate, restoreCommand)
                            // Independently reopen the committed graph and the request-bound receipt.
                            val readback = repository.exportWithProof()
                            val verified = withContext(Dispatchers.Default) { decodeGenesisAuthorityBackup(readback.text) }
                            check(verified.campaign.records.receipts.any { it == receipt }) {
                                "The merge receipt could not be verified. Check saved records before retrying this same file."
                            }
                            preview = null; reviewWasOpen = false; savedBackupFingerprint = null
                            status = "Connected backup records merged and verified on this device."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            failure = true; savedBackupFingerprint = null
                            status = "Merge could not be verified: ${e.message}. Check saved records before retrying this same file."
                        } finally { busy = false }
                    }
                }) { Text("Confirm merge") }
        }) {
            Text(candidate.summary, style = MaterialTheme.typography.titleMedium)
            Text("Preview changes nothing. Only validated missing records are added. Existing different records are never overwritten.", color = RebuildPalette.Muted)
            if (!backupProtected) {
                Text("Save and verify a complete backup of the current records to enable this merge.", color = RebuildPalette.GoldBright)
                PlayerOutlinedButton(onClick = ::saveCurrent, enabled = !busy && pendingExport == null) { Text("Save current backup") }
            } else Text("Current records have a verified backup.", color = RebuildPalette.Ice)
            candidate.backup?.let { incoming ->
                PlayerDisclosure("Inspect campaign records") { CampaignBackupContentsV21(incoming.campaign) }
                PlayerDisclosure("Inspect playable sheets and relationships") {
                    val summaries by produceState(initialValue = emptyList<String>(), key1 = candidate.fingerprint) {
                        value = withContext(Dispatchers.Default) { incoming.playable.characters.take(100).map { row ->
                            "${row.saved().character.name} · revision ${row.revision}"
                        } }
                    }
                    summaries.forEach { Text(it) }
                    if (incoming.playable.characters.size > 100) Text("${incoming.playable.characters.size - 100} additional playable sheets.")
                    Text("${incoming.playable.drafts.size} separate recovery drafts · ${incoming.authority.campaigns.size} campaigns · ${incoming.authority.actors.size} actor associations · ${incoming.authority.items.size} physical item associations.", color = RebuildPalette.Muted)
                    Text("Original reviews, source history and accepted mechanical effects are retained with their records.", color = RebuildPalette.Muted)
                }
            }
            if (candidate.conflicts.isNotEmpty()) PlayerDisclosure("${candidate.conflicts.size} conflicts", initiallyOpen = true) {
                candidate.conflicts.take(20).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (candidate.conflicts.size > 20) Text("${candidate.conflicts.size - 20} additional conflicts. Merge is blocked.")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(status, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
