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
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * User-selected private data only. No Notion token, live connector, campaign export, or personal
 * records ship inside the APK. A verified complete backup is required before the one-way merge.
 */
@Composable fun GenesisNotionTransferDialog(
    saved: ReforgeSavedCharacter, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val storage = remember(context) { GenesisApplicationStorage.open(context) }
    val transferRepository = remember(context) { NotionTransferRepository(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<NotionTransferOutcome?>(null) }
    var selected by remember { mutableStateOf<NotionCharacterTransfer?>(null) }
    var includeStats by rememberSaveable { mutableStateOf(true) }
    var includeItems by rememberSaveable { mutableStateOf(true) }
    var replaceStats by rememberSaveable { mutableStateOf(false) }
    var includeWallet by rememberSaveable { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var verifiedBackup by remember { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf<GenesisAuthorityBackupSnapshot?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true; error = ""; selected = null; consent = false; verifiedBackup = null; outcome = null
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: kotlin.error("Could not open that private document.")
                        CampaignDocumentIoV21.readUtf8(input, 4 * 1024 * 1024)
                    }
                    val data = withContext(Dispatchers.Default) {
                        Json { ignoreUnknownKeys = false }.decodeFromString<NotionCharacterTransfer>(text).also(::validateNotionCharacterTransfer)
                    }
                    require(data.character.name.trim().equals(saved.character.name.trim(), ignoreCase = true)) {
                        "The file belongs to ${data.character.name}, not ${saved.character.name}. No records changed."
                    }
                    selected = data
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "The source could not be validated." }
                finally { busy = false }
            }
        }
    }
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = pendingBackup
        pendingBackup = null
        if (uri != null && snapshot != null && !busy) {
            busy = true; error = ""; verifiedBackup = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(CampaignDocumentIoV21.utf8Size(snapshot.text) <= GENESIS_AUTHORITY_DOCUMENT_BYTES) {
                            "The current complete backup exceeds 64 MiB. This import is blocked."
                        }
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: kotlin.error("Cannot write the chosen backup document.")
                        output.use { CampaignDocumentIoV21.writeUtf8(snapshot.text, it) }
                        val input = context.contentResolver.openInputStream(uri)
                            ?: kotlin.error("Cannot reopen the saved backup for verification.")
                        CampaignDocumentIoV21.verifySavedUtf8(snapshot.text, input, GENESIS_AUTHORITY_DOCUMENT_BYTES)
                    }
                    verifiedBackup = snapshot.currentFingerprint
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "Backup not verified: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    fun saveBackup() {
        if (busy || pendingBackup != null) return
        busy = true; error = ""; verifiedBackup = null
        scope.launch {
            try {
                pendingBackup = storage.backup.exportWithProof()
                busy = false
                backupSaver.launch("astravar-before-notion-transfer.json")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pendingBackup = null; error = "Could not prepare the complete backup: ${e.message}"; busy = false }
        }
    }

    val choices = NotionTransferChoices(includeStats, includeItems, replaceStats)
    val proposed = remember(selected, saved, choices) {
        selected?.let { runCatching { planNotionCharacterTransfer(saved.character, it, choices) } }
    }
    PlayerSheet("Import Notion character records", onDismiss, dismissEnabled = !busy && pendingBackup == null, actions = {
        if (selected != null && outcome == null) {
            PlayerButton(
                enabled = !busy && pendingBackup == null && consent && verifiedBackup != null && proposed?.isSuccess == true &&
                    (proposed.getOrNull()?.changed == true || includeWallet),
                modifier = Modifier.testTag("notion-transfer-commit"), onClick = {
                    val data = selected ?: return@PlayerButton
                    busy = true; error = ""; consent = false
                    scope.launch {
                        try {
                            outcome = transferRepository.commit(saved, data, choices, includeWallet,
                                requireNotNull(verifiedBackup))
                            verifiedBackup = null
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { verifiedBackup = null; error = e.message ?: "Nothing was imported. Existing data is unchanged." }
                        finally { busy = false }
                    }
                }
            ) { Text("Apply reviewed transfer") }
        }
    }) {
        Text("Add verified character facts and physical inventory to this existing sheet: ${saved.character.name}. " +
            "The private JSON stays on your device. This does not edit Notion, fill unknown HP or slots, or replace your character.")
        PlayerOutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            enabled = !busy && pendingBackup == null, modifier = Modifier.fillMaxWidth().testTag("notion-transfer-choose-file")) {
            Text("Choose private Notion transfer file")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        outcome?.let { done ->
            Text("Transferred ${done.itemsAdded} items; ${done.itemsPreviouslyPresent} previously recorded. " +
                "${if (done.walletAdded) "Provisional wallet added." else if (done.walletPreviouslyPresent) "Provisional wallet already present." else "Wallet unchanged."}",
                color = RebuildPalette.Ice)
            Text("Saved locally. Campaign sharing still requires reviewed membership, authorized scopes and verified synchronization.",
                color = RebuildPalette.Muted)
            done.warnings.take(12).forEach { Text(it, color = RebuildPalette.Muted) }
        }
        val transfer = selected
        if (transfer != null && outcome == null) {
            Text("Source: ${transfer.character.name}, level ${transfer.character.level}, ${transfer.capturedDate}",
                style = MaterialTheme.typography.titleMedium)
            Text("${transfer.inventory.size} inventory/source rows · ${transfer.currency.size} currency entries. " +
                "Consumed, installed, other-creature-owned, disputed and unknown-location items remain deferred.",
                color = RebuildPalette.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(includeStats, { includeStats = it; consent = false })
                Text("Import confirmed level, permanent abilities, HP baseline and armor class where safe.", Modifier.weight(1f))
            }
            if (includeStats) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(replaceStats, { replaceStats = it; consent = false })
                Text("Explicitly replace conflicting stored stats. Existing resources, HP lost, spell slots and turn state stay unchanged.", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(includeItems, { includeItems = it; consent = false })
                Text("Add confirmed ${transfer.character.name}-owned carried, equipped and stored items. Skip duplicate names and IDs.", Modifier.weight(1f))
            }
            val plan = proposed?.getOrNull()
            if (plan != null) {
                Text("Preview: ${plan.added.size} new items · ${plan.alreadyPresent.size} already present · " +
                    "${plan.deferred.size} intentionally deferred.", color = RebuildPalette.Ice)
                val projection = CharacterCalculator.calculate(plan.character)
                Text("Proposed level ${plan.character.level} · AC ${projection.armorClass.value ?: "unknown"} · " +
                    "max HP ${projection.maximumHp.value ?: "unknown"}. Current HP is preserved.", color = RebuildPalette.Ice)
                PlayerDisclosure("Items to add (${plan.added.size})") { plan.added.forEach { Text(it) } }
                if (plan.alreadyPresent.isNotEmpty()) PlayerDisclosure("Existing items (${plan.alreadyPresent.size})") {
                    plan.alreadyPresent.forEach { Text(it) }
                }
                if (plan.deferred.isNotEmpty()) PlayerDisclosure("Not imported as playable items (${plan.deferred.size})") {
                    plan.deferred.forEach { Text(it) }
                }
                if (plan.warnings.isNotEmpty()) PlayerDisclosure("Review warnings (${plan.warnings.size})", initiallyOpen = true) {
                    plan.warnings.forEach { Text(it, color = RebuildPalette.GoldBright) }
                }
            } else Text(proposed?.exceptionOrNull()?.message ?: "The file could not be previewed.", color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("Currency: ${transfer.provisionalGpEquivalent?.let { String.format(Locale.US, "%,d", it) } ?: "unknown"} gp equivalent.",
                style = MaterialTheme.typography.titleMedium)
            Text("Notion's ledger is incomplete as a verified closing wallet; physical denominations and some expenses remain uncertain. " +
                "Recording the estimate creates a clearly labeled provisional wallet and never changes an existing wallet.",
                color = RebuildPalette.GoldBright)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(includeWallet, { includeWallet = it; consent = false }, enabled = transfer.provisionalGpEquivalent != null)
                Text("Record this provisional GP-equivalent wallet after review.", Modifier.weight(1f))
            }
            HorizontalDivider()
            if (verifiedBackup == null) {
                Text("Before import, save and independently verify a complete backup of the current Astravar records.", color = RebuildPalette.GoldBright)
                PlayerOutlinedButton(onClick = ::saveBackup, enabled = !busy && pendingBackup == null,
                    modifier = Modifier.fillMaxWidth().testTag("notion-transfer-backup")) { Text("Save verified current backup") }
            } else Text("Current complete backup saved and verified.", color = RebuildPalette.Ice)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(consent, { consent = it }, enabled = !busy && verifiedBackup != null && proposed?.isSuccess == true)
                Text("I've reviewed the differences and want these selected Notion facts added to this exact sheet.", Modifier.weight(1f))
            }
        }
    }
}
