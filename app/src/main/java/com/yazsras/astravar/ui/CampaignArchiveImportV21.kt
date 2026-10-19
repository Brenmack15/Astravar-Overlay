package com.yazsras.astravar.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignArchivePreview
import com.yazsras.astravar.data.CampaignImportReceipt
import com.yazsras.astravar.data.CampaignLedgerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Require an openable document, rather than a virtual provider item without readable file bytes. */
internal class CampaignOpenDocumentContractV21 : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

/** A document selection and a successful backup do not authorize a commit. Consent is never restored. */
@Composable fun CampaignArchiveImportV21(repository: CampaignLedgerRepository) {
    val importer = repository.archive
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receipts by importer.receipts.collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var failure by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<CampaignArchivePreview?>(null) }
    var hadPreview by rememberSaveable { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var verifiedBackupHash by remember { mutableStateOf<String?>(null) }
    var requestedBackupHash by remember { mutableStateOf<String?>(null) }
    var rollbackReview by remember { mutableStateOf<CampaignImportReceipt?>(null) }
    var rollbackConsent by remember { mutableStateOf(false) }
    var receiptLimit by rememberSaveable { mutableIntStateOf(20) }

    LaunchedEffect(Unit) {
        if (hadPreview && preview == null) {
            status = "Choose the archive again to review it. Saved import receipts remain below; consent and previews are not restored after the Activity restarts."
            hadPreview = false
        }
    }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val requested = requestedBackupHash
        requestedBackupHash = null
        if (uri != null && requested != null && !busy && preview?.fingerprint == requested) {
            busy = true; failure = false; consent = false; verifiedBackupHash = null
            scope.launch {
                try {
                    val backup = importer.exportPreImportBackup(requested)
                    withContext(Dispatchers.IO) {
                        require(CampaignDocumentIoV21.utf8Size(backup) <= CampaignDocumentIoV21.MAX_DOCUMENT_BYTES) {
                            "Backup is too large for verified document transfer. Import remains blocked."
                        }
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the chosen backup document.")
                        output.use { CampaignDocumentIoV21.writeUtf8(backup, it) }
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot reopen the saved backup for verification.")
                        CampaignDocumentIoV21.verifySavedUtf8(backup, input)
                    }
                    verifiedBackupHash = requested
                    status = "Pre-import backup saved and independently read back. Review the archive and confirm below."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = true; status = "Backup not verified: ${e.message ?: "Choose a different destination and retry."}" }
                finally { busy = false }
            }
        }
    }

    val chooseArchive = rememberLauncherForActivityResult(CampaignOpenDocumentContractV21()) { uri ->
        if (uri != null && !busy) {
            busy = true; failure = false; preview = null; hadPreview = false
            consent = false; verifiedBackupHash = null
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the selected campaign archive.")
                        CampaignDocumentIoV21.readUtf8(input)
                    }
                    preview = importer.preview(text)
                    hadPreview = true
                    status = "Archive preview ready. Review its records and unresolved issues below."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = true; status = "Archive rejected: ${e.message ?: "The file could not be validated."}" }
                finally { busy = false }
            }
        }
    }

    fun saveBackup(candidate: CampaignArchivePreview) {
        requestedBackupHash = candidate.fingerprint
        exportBackup.launch("astravar-pre-import-${candidate.fingerprint.take(12)}.json")
    }

    Page {
        PlayerHeading("Campaign import")
        PlayerCard {
            Text("Bring your campaign with you", style = MaterialTheme.typography.titleLarge)
            Text("Choose a private, versioned campaign archive. Review the source, record counts, conflicts and unresolved details before adding records to this device.", color = RebuildPalette.Muted)
            Action("Choose campaign archive", !busy) {
                chooseArchive.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            Text("Notion remains a recoverable source. This workflow does not advance campaign play or establish an authoritative cutover.",
                color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, color = if (failure) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

        Text("Import receipts", style = MaterialTheme.typography.titleLarge)
        Text("Receipts persist locally. Verify a saved import or review a guarded rollback using its exact pre-import backup.", color = RebuildPalette.Muted)
        if (receipts.isEmpty()) Text("No campaign archives have been imported on this device.", color = RebuildPalette.Muted)
        receipts.take(receiptLimit).forEach { receipt ->
            val rolledBack = receipts.any { it.packageHash == "rollback:${receipt.packageHash}" }
            PlayerCard {
                Text(receipt.title, style = MaterialTheme.typography.titleMedium)
                Text("${receipt.recordCount} source records · ${receipt.relationCount} relations · ${if (rolledBack) "APPLIED, then rolled back" else receipt.state}", color = RebuildPalette.Ice)
                Text(receipt.summary, style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text("Package SHA-256\n${receipt.packageHash}", style = MaterialTheme.typography.bodySmall) }
                if (receipt.state == "APPLIED" && !rolledBack) {
                    PlayerOutlinedButton(onClick = {
                        if (!busy) {
                            busy = true; failure = false
                            scope.launch {
                                try {
                                    val result = importer.verify(receipt.packageHash)
                                    failure = !result.valid
                                    status = result.summary + if (result.errors.isEmpty()) "" else "\n" + result.errors.joinToString("\n")
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failure = true; status = "Readback failed: ${e.message}" }
                                finally { busy = false }
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Verify saved import") }
                    PlayerTextButton(onClick = { rollbackReview = receipt; rollbackConsent = false }, enabled = !busy) { Text("Review rollback") }
                }
            }
        }
        if (receipts.size > receiptLimit) PlayerOutlinedButton(onClick = { receiptLimit += 20 }, modifier = Modifier.fillMaxWidth()) { Text("Show more import receipts") }
        Text("Archives are read as text; remote content is never executed or opened automatically. Files and backups can contain private campaign information.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }

    preview?.let { candidate ->
        val alreadyImported = receipts.any { it.packageHash == candidate.fingerprint && it.state == "APPLIED" }
        PlayerSheet("Review campaign archive", onDismiss = {
            if (!busy) { preview = null; hadPreview = false; consent = false; verifiedBackupHash = null }
        }, actions = {
            PlayerButton(enabled = !busy && !alreadyImported && candidate.valid && verifiedBackupHash == candidate.fingerprint && consent,
                onClick = {
                    if (!busy && consent && verifiedBackupHash == candidate.fingerprint) {
                        busy = true; failure = false; consent = false
                        scope.launch {
                            try {
                                val receipt = importer.commit(candidate, confirmed = true)
                                val result = importer.verify(receipt.packageHash)
                                require(result.valid) { "Import receipt was saved, but independent readback needs review: ${result.summary} ${result.errors.joinToString("; ")}" }
                                status = "Import saved. Independent database readback passed."
                                preview = null; hadPreview = false; verifiedBackupHash = null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failure = true; verifiedBackupHash = null; status = "Import needs review: ${e.message ?: "Check the saved import receipts before retrying."}" }
                            finally { busy = false }
                        }
                    }
                }) { Text("Confirm local import") }
        }) {
            CampaignArchivePreviewContentsV21(candidate)
            if (alreadyImported) {
                Text("Already imported on this device. Close this preview to verify the existing receipt. No records or equipment will be added again.", color = RebuildPalette.Ice)
            } else if (candidate.valid) {
                PlayerOutlinedButton(onClick = { saveBackup(candidate) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Save verified pre-import backup")
                }
                Text(if (verifiedBackupHash == candidate.fingerprint) "Backup verified for this review." else "Save and verify a current backup to enable import.", color = RebuildPalette.Ice)
                ArchiveConsentV21("I reviewed the archive and want to add approved records locally.", consent,
                    enabled = !busy && verifiedBackupHash == candidate.fingerprint) { consent = it }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(status, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }

    rollbackReview?.let { receipt ->
        PlayerSheet("Review rollback", onDismiss = { if (!busy) { rollbackReview = null; rollbackConsent = false } }, actions = {
            PlayerButton(enabled = !busy && rollbackConsent, onClick = {
                if (!busy && rollbackConsent) {
                    busy = true; failure = false; rollbackConsent = false
                    scope.launch {
                        try {
                            val result = importer.rollback(receipt.packageHash, confirmed = true)
                            status = result.summary
                            rollbackReview = null
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = true; status = "Rollback blocked: ${e.message ?: "Review changes made after importing."}" }
                        finally { busy = false }
                    }
                }
            }) { Text("Confirm rollback") }
        }) {
            Text(receipt.title, style = MaterialTheme.typography.titleMedium)
            Text("Rollback restores the verified campaign snapshot from immediately before this import. It is blocked if later local changes would be lost. Weapon data and the original Notion archive are separate.", color = RebuildPalette.Muted)
            Text("Package ${receipt.packageHash}", style = MaterialTheme.typography.bodySmall)
            ArchiveConsentV21("I understand this restores the verified pre-import campaign backup.", rollbackConsent, !busy) { rollbackConsent = it }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure) Text(status, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun ArchiveConsentV21(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun CampaignArchivePreviewContentsV21(candidate: CampaignArchivePreview) {
    Text(candidate.summary, style = MaterialTheme.typography.titleMedium,
        color = if (candidate.valid) RebuildPalette.Ice else MaterialTheme.colorScheme.error)
    Text("Current records are preserved. Historical pages remain references; unknown values remain unknown. Importing does not spend items, add currency transactions, restore HP or complete inventions.", color = RebuildPalette.Muted)
    candidate.archive?.let { archive ->
        Text(archive.title, style = MaterialTheme.typography.titleLarge)
        SelectionContainer { Text("Source root\n${archive.sourceRootUrl}\nRevision · ${archive.sourceRootRevision}", style = MaterialTheme.typography.bodySmall) }
        Text("${archive.records.size} source records · ${archive.attachments.size} attachment records", style = MaterialTheme.typography.bodyMedium)
        PlayerDisclosure("Source categories", initiallyOpen = true) {
            archive.records.groupingBy { it.category }.eachCount().toSortedMap().forEach { (category, count) ->
                Text("$category · $count", style = MaterialTheme.typography.bodySmall)
            }
        }
        PlayerDisclosure("Inspect original source records (${archive.records.size})") {
            var recordLimit by remember(candidate.fingerprint) { mutableIntStateOf(40) }
            archive.records.take(recordLimit).forEach { record ->
                PlayerDisclosure("${record.title} · ${record.category}") {
                    ArchiveOriginalTextV21(record.body, "preview source text", record.externalId)
                    SelectionContainer { Text("Original ID · ${record.externalId}\nSource · ${record.sourceUrl}\nRevision · ${record.sourceRevision}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (archive.records.size > recordLimit) PlayerOutlinedButton(onClick = { recordLimit += 40 }) { Text("Show more preview sources") }
        }
        archive.mapped?.let { mapped ->
            PlayerDisclosure("Inspect mapped campaign records") { CampaignBackupContentsV21(mapped) }
        }
        if (archive.attachments.isNotEmpty()) PlayerDisclosure("Inspect attachments (${archive.attachments.size})") {
            archive.attachments.forEach { attachment ->
                Text("${attachment.fileName} · ${attachment.status}", style = MaterialTheme.typography.bodyMedium)
                Text(if (attachment.base64 == null) "External reference only; bytes were not included." else "Embedded bytes verified by the importer.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (candidate.conflicts.isNotEmpty()) PlayerDisclosure("${candidate.conflicts.size} conflicts", initiallyOpen = true) {
        candidate.conflicts.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (candidate.warnings.isNotEmpty()) PlayerDisclosure("${candidate.warnings.size} unresolved details / notices", initiallyOpen = true) {
        candidate.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    SelectionContainer { Text("Package SHA-256\n${candidate.fingerprint}", style = MaterialTheme.typography.bodySmall) }
}
