package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.ImportPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit SAF-only transfer. It never contacts Notion or the public sandbox. */
@Composable fun CampaignBackupPageV2(repository: CampaignLedgerRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var backupCreated by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val text = repository.export()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: error("Cannot open the chosen document.")
                        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                    backupCreated = true
                    status = "Campaign backup written to your selected document. Weapon data requires its separate backup."
                } catch(e: Exception) { status = "Export failed: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null && !busy) {
            busy = true; preview = null
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected file.")
                        stream.bufferedReader(Charsets.UTF_8).use { input ->
                            val result = StringBuilder(); val buffer = CharArray(8192)
                            while(true) {
                                val count = input.read(buffer)
                                if(count < 0) break
                                require(result.length + count <= 32 * 1024 * 1024) { "Backup exceeds the 32 Mi-character review limit." }
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        }
                    }
                    preview = repository.previewImport(text)
                    status = "Preview only. Nothing has been imported."
                } catch(e: Exception) { status = "Preview rejected: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    Page {
        SectionCaption("CAMPAIGN SAFEKEEPING", "LOCAL FILES ONLY")
        Section("Complete campaign export") {
            Text("Includes local inventory, images, item history, inventions, requirements, connections, Eek, links, craft lineage and audit receipts.")
            Text("The separate weapon save and public fictional sync queue are not included. Keep a weapon backup too.", color = Muted)
            Action("Export campaign backup", !busy) { exporter.launch("astravar-campaign-v2.json") }
            if(status.isNotBlank()) Text(status, color = Frost)
        }
        Section("Review a backup") {
            Text("Accepts Inventory Beta schema 1 and campaign schema 2. Existing unequal IDs are conflicts, never silently overwritten. Import adds missing records and retains existing data.")
            Text("Export a current backup in this session before restoring. Files are private campaign data; keep them out of public GitHub and the public sandbox.", color = Muted)
            Action("Choose file for preview", !busy) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            preview?.let { candidate ->
                Text(candidate.summary, color = if(candidate.valid) Frost else MaterialTheme.colorScheme.error)
                candidate.conflicts.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if(candidate.conflicts.size > 20) Text("${candidate.conflicts.size - 20} additional conflicts; no import will be applied.")
                if(!backupCreated) Text("Create your current backup above to enable restore.", color = Brass)
                Action("Review and restore missing records", !busy && candidate.valid && backupCreated) { confirmRestore = true }
                TextButton(onClick = { preview = null }, enabled = !busy) { Text("Discard preview") }
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if(confirmRestore) AlertDialog(onDismissRequest = { if(!busy) confirmRestore = false },
        title = { Text("Restore reviewed campaign records?") },
        text = { Text("This adds validated missing records from the preview. It does not replace conflicting records or touch the weapon save. A change since preview will require another review.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val candidate = preview ?: return@TextButton
            confirmRestore = false; busy = true
            scope.launch {
                try {
                    val receipt = repository.restore(candidate)
                    status = "Restore committed locally. Receipt ${receipt.commandId}."
                    preview = null; backupCreated = false
                } catch(e: Exception) { status = "Restore not applied: ${e.message}" }
                finally { busy = false }
            }
        }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }, enabled = !busy) { Text("Cancel") } })
}
