package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.ImportPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit SAF-only transfer. Backup contents are never stored in saved-instance state or sent online. */
@Composable fun CampaignBackupPageV2(repository: CampaignLedgerRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var failure by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var backupCreated by remember { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    var reviewWasOpen by rememberSaveable { mutableStateOf(false) }
    // A potentially 32 Mi-character campaign file must not enter Android's small state Bundle.
    // Recreating the Activity safely requires a fresh file review instead of restoring stale authorization.
    LaunchedEffect(Unit) {
        if (reviewWasOpen && preview == null) {
            status = "This preview was not retained. Choose the backup again to review it. If a confirmed restore was in progress, check saved records and the audit history before retrying."
            reviewWasOpen = false
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) {
            busy = true; failure = false
            scope.launch {
                try {
                    val text = repository.export()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: error("Cannot open the chosen document.")
                        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                    backupCreated = true
                    status = "Campaign backup saved."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = true; status = "Export failed: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true; preview = null; reviewWasOpen = false; failure = false
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected file.")
                        stream.bufferedReader(Charsets.UTF_8).use { input ->
                            val result = StringBuilder(); val buffer = CharArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(result.length + count <= 32 * 1024 * 1024) { "Backup exceeds the 32 Mi-character review limit." }
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        }
                    }
                    preview = repository.previewImport(text)
                    reviewWasOpen = true
                    status = "Preview ready. Nothing has been restored."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = true; status = "Preview rejected: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    Page {
        PlayerHeading("Campaign backup")
        PlayerCard {
            Text("Save a copy", style = MaterialTheme.typography.titleLarge)
            Text("Inventory, party sheets, wallets, checkpoints, workshop, Connections and their histories.", color = RebuildPalette.Muted)
            Action("Export campaign backup", !busy) { exporter.launch("astravar-campaign-v4.json") }
        }
        PlayerCard {
            Text("Restore from a file", style = MaterialTheme.typography.titleLarge)
            Text("Review first. Restore adds missing records and preserves existing data.", color = RebuildPalette.Muted)
            Action("Choose file for preview", !busy) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, color = if (failure) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("Weapon saves and the fictional sync queue are separate.", color = RebuildPalette.Muted,
            style = MaterialTheme.typography.bodySmall)
        PlayerEntry("What's included", "Backup contents, compatibility and restore safeguards", "History", { info = true })
    }
    if (info) PlayerSheet("Backup details", onDismiss = { info = false }) {
        PlayerDisclosure("Campaign contents", initiallyOpen = true) {
            Text("Inventory and images, item properties and history, character status and ability scores, party sheets and equipment, wallets, checkpoints, external source IDs, inventions and materials, Connections and milestones, Eek, linked records, crafting lineage, source reference archives, attachments, import receipts and verified recovery snapshots.")
        }
        PlayerDisclosure("Separate backups") {
            Text("The weapon save and public fictional sync queue are not included. Keep the existing weapon backup too.")
        }
        PlayerDisclosure("Restore safeguards") {
            Text("Inventory Beta and campaign schema 2/3/4 files are supported. Conflicting records are never silently overwritten. Export a current backup in this session before confirming a restore.")
            Text("Backup files contain private campaign data. Keep them out of public GitHub and the public sandbox.")
        }
    }
    preview?.let { candidate ->
        PlayerSheet("Review campaign backup", onDismiss = {
            if (!busy) { preview = null; reviewWasOpen = false }
        }, actions = {
            PlayerTextButton(onClick = { if (!busy) { preview = null; reviewWasOpen = false } }, enabled = !busy) { Text("Cancel") }
            PlayerButton(enabled = !busy && candidate.valid && backupCreated, onClick = {
                if (busy) return@PlayerButton
                busy = true; failure = false
                scope.launch {
                    try {
                        repository.restore(candidate)
                        status = "Restore committed locally."
                        preview = null; reviewWasOpen = false; backupCreated = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = true; status = "Restore not applied: ${e.message}" }
                    finally { busy = false }
                }
            }) { Text("Confirm restore") }
        }) {
            Text(candidate.summary, style = MaterialTheme.typography.titleMedium,
                color = if (candidate.valid) RebuildPalette.Ice else MaterialTheme.colorScheme.error)
            Text("Only validated missing records will be added. Existing records and weapon data stay intact.", color = RebuildPalette.Muted)
            if (!backupCreated) {
                Text("Save a current campaign backup to enable restore.", color = RebuildPalette.GoldBright)
                PlayerOutlinedButton(onClick = { exporter.launch("astravar-campaign-v4.json") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Save current backup") }
            }
            candidate.backup?.let { incoming ->
                PlayerDisclosure("Inspect incoming records") { CampaignBackupContentsV21(incoming) }
            }
            if (candidate.conflicts.isNotEmpty()) PlayerDisclosure("${candidate.conflicts.size} conflicts", initiallyOpen = true) {
                candidate.conflicts.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (candidate.conflicts.size > 20) Text("${candidate.conflicts.size - 20} additional conflicts. Restore is blocked.")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(status, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
