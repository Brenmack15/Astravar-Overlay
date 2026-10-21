package com.yazsras.astravar.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.CampaignReferenceRecord
import com.yazsras.astravar.data.CampaignArchivePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Only an explicit player tap opens an external source. Text/HTML is never rendered as a WebView. */
internal fun safeCampaignSourceLinkV21(value: String): String? = runCatching {
    val uri = URI(value)
    value.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null }
}.getOrNull()

internal fun campaignReferenceMatchesV21(record: CampaignReferenceRecord, query: String): Boolean = query.isBlank() ||
    listOf(record.title, record.category, record.body, record.propertiesJson, record.externalId, record.sourceUrl)
        .any { it.contains(query.trim(), ignoreCase = true) }

/** A source details screen retains metadata, never a second copy of embedded file bodies. */
internal data class CampaignSourceAttachmentMetadataV21(
    val id: String, val fileName: String, val mimeType: String, val sourceUrl: String,
    val sha256: String?, val status: String, val embedded: Boolean
)
internal data class CampaignSourceMetadataV21(
    val title: String, val sourceRootUrl: String, val sourceRootRevision: String,
    val issues: List<String>, val attachments: List<CampaignSourceAttachmentMetadataV21>
)
internal fun campaignSourceMetadataV21(source: CampaignArchivePayload) = CampaignSourceMetadataV21(
    source.title, source.sourceRootUrl, source.sourceRootRevision, source.issues,
    source.attachments.map { CampaignSourceAttachmentMetadataV21(it.id, it.fileName, it.mimeType,
        it.sourceUrl, it.sha256, it.status, it.base64 != null) })

@Composable fun CampaignReferenceLibraryV21(repository: CampaignLedgerRepository) {
    val references by repository.archive.references.collectAsState(initial = emptyList())
    val relations by repository.archive.relations.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by rememberSaveable(query, category) { mutableIntStateOf(40) }
    val categories = remember(references) { references.map { it.category }.distinct().sorted() }
    val visible = remember(references, category, query) {
        references.filter { (category == "All" || it.category == category) && campaignReferenceMatchesV21(it, query) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
    val selected = references.firstOrNull { it.id == selectedId }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf("") }

    Page {
        PlayerHeading("Reference Library")
        Text("Player-known campaign sources, original text and relationships. These references preserve history and unresolved details alongside current records.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium)
        PlayerSearch(query, { query = it }, "Search campaign references")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All") + categories).forEach { name ->
                PlayerFilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) })
            }
        }
        Text("${visible.size} of ${references.size} references", color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
        if (references.isEmpty()) PlayerEmpty("Your campaign sources belong here", "Use Campaign import to preview and add a private archive.", "History")
        else if (visible.isEmpty()) PlayerEmpty("No matching references", "Try another name, original ID, location or phrase.", "History")
        visible.take(limit).forEach { record ->
            PlayerCard(onClick = { selectedId = record.id; linkError = "" }) {
                Text(record.title, style = MaterialTheme.typography.titleMedium)
                Text(record.category, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
                if (record.body.isNotBlank()) Text(record.body.take(600), color = RebuildPalette.Muted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("Source revision · ${record.sourceRevision.ifBlank { "Unknown" }}", color = RebuildPalette.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visible.size > limit) PlayerOutlinedButton(onClick = { limit += 40 }, modifier = Modifier.fillMaxWidth()) { Text("Show more campaign references") }
    }

    selected?.let { record ->
        var relationLimit by remember(record.id) { mutableIntStateOf(40) }
        val associated = relations.filter { it.fromId == record.id || it.toId == record.id }
        PlayerSheet(record.title, onDismiss = { selectedId = null; linkError = "" }) {
            Text(record.category, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
            PlayerDisclosure("Original source text", initiallyOpen = true) {
                ArchiveOriginalTextV21(record.body.ifBlank { "This source has no body text. See its preserved properties below." }, "source text", record.id)
            }
            PlayerDisclosure("Source properties") {
                ArchiveOriginalTextV21(record.propertiesJson, "source properties", record.id)
            }
            PlayerDisclosure("Archive source & attachments") { CampaignArchiveSourceDetailsV21(repository, record.packageHash) }
            PlayerDisclosure("Relationships (${associated.size})", initiallyOpen = associated.isNotEmpty()) {
                associated.take(relationLimit).forEach { relation ->
                    val otherId = if (relation.fromId == record.id) relation.toId else relation.fromId
                    val related = references.firstOrNull { it.id == otherId }
                    if (related != null) PlayerEntry(related.title,
                        "${relation.kind} · ${if (relation.fromId == record.id) "outgoing" else "incoming"}", "History",
                        onClick = { selectedId = related.id; linkError = "" })
                    else Text("${relation.kind} · unresolved reference: $otherId", color = RebuildPalette.Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (associated.size > relationLimit) PlayerOutlinedButton(onClick = { relationLimit += 40 }) { Text("Show more relationships") }
                if (associated.isEmpty()) Text("No explicit source relationships recorded.", color = RebuildPalette.Muted)
            }
            PlayerDisclosure("Provenance", initiallyOpen = true) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Original Notion ID\n${record.externalId}", style = MaterialTheme.typography.bodySmall)
                        Text("Source revision\n${record.sourceRevision.ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Source URL\n${record.sourceUrl.ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Content SHA-256\n${record.contentSha256}", style = MaterialTheme.typography.bodySmall)
                        Text("Import package SHA-256\n${record.packageHash}", style = MaterialTheme.typography.bodySmall)
                        Text("Campaign ID\n${record.campaignId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                safeCampaignSourceLinkV21(record.sourceUrl)?.let { source ->
                    PlayerOutlinedButton(onClick = {
                        linkError = runCatching { uriHandler.openUri(source) }.exceptionOrNull()?.let { "The source could not be opened. Its URL is available above." }.orEmpty()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open source in browser") }
                }
                if (linkError.isNotBlank()) Text(linkError, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Embedded bytes leave the app only through an explicit local document selection; URL-only files remain clearly labeled. */
@Composable private fun CampaignArchiveSourceDetailsV21(repository: CampaignLedgerRepository, packageHash: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var archive by remember(packageHash) { mutableStateOf<CampaignSourceMetadataV21?>(null) }
    var status by remember(packageHash) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedAttachmentId by remember { mutableStateOf<String?>(null) }
    var limit by rememberSaveable(packageHash) { mutableIntStateOf(40) }
    LaunchedEffect(repository, packageHash) {
        try { archive = repository.archive.sourceArchive(packageHash)?.let(::campaignSourceMetadataV21) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = "Archive details could not be read: ${e.message}" }
    }
    val saveAttachment = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val attachmentId = selectedAttachmentId
        selectedAttachmentId = null
        if (uri != null && attachmentId != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val bytes = repository.archive.attachmentBytes(packageHash, attachmentId)
                    withContext(Dispatchers.IO) {
                        require(bytes.size <= CampaignDocumentIoV21.MAX_DOCUMENT_BYTES) { "Attachment exceeds the safe document transfer limit." }
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the chosen attachment document.")
                        output.use { it.write(bytes) }
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot reopen the saved attachment.")
                        CampaignDocumentIoV21.verifySavedBytes(bytes, CampaignDocumentIoV21.readBytes(input))
                    }
                    status = "Attachment saved and independently verified."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = "Attachment not saved: ${e.message}" }
                finally { busy = false }
            }
        }
    }
    archive?.let { source ->
        Text(source.title, style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text("${source.sourceRootUrl}\nRevision · ${source.sourceRootRevision}", style = MaterialTheme.typography.bodySmall) }
        if (source.issues.isNotEmpty()) PlayerDisclosure("Preserved unresolved details (${source.issues.size})") {
            source.issues.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text("${source.attachments.size} archive attachments", style = MaterialTheme.typography.labelLarge)
        source.attachments.take(limit).forEach { attachment ->
            PlayerDisclosure(attachment.fileName) {
                Text("${attachment.mimeType} · ${attachment.status}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text("Original attachment ID · ${attachment.id}\nSource · ${attachment.sourceUrl.ifBlank { "Unknown" }}\nSHA-256 · ${attachment.sha256 ?: "Unknown"}", style = MaterialTheme.typography.bodySmall) }
                if (attachment.embedded) PlayerOutlinedButton(onClick = {
                    selectedAttachmentId = attachment.id
                    val name = attachment.fileName.substringAfterLast('/').substringAfterLast('\\').take(180)
                        .replace(Regex("[\\p{Cntrl}]"), "_").ifBlank { "campaign-attachment.bin" }
                    saveAttachment.launch(name)
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save verified attachment") }
                else {
                    Text("External reference only. File bytes are not available offline in this archive.", color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
                    safeCampaignSourceLinkV21(attachment.sourceUrl)?.let { url ->
                        PlayerOutlinedButton(onClick = {
                            status = runCatching { uriHandler.openUri(url) }.exceptionOrNull()?.let { "The attachment source could not be opened." }.orEmpty()
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open attachment source in browser") }
                    }
                }
            }
        }
        if (source.attachments.size > limit) PlayerOutlinedButton(onClick = { limit += 40 }) { Text("Show more archive attachments") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank()) Text(status, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
}

/** A bounded visible window preserves all original content without a giant first Compose text layout. */
@Composable internal fun ArchiveOriginalTextV21(text: String, label: String, recordId: String) {
    var shown by rememberSaveable(recordId, label) { mutableIntStateOf(12_000) }
    val end = minOf(shown, text.length).let { limit ->
        if (limit > 0 && limit < text.length && text[limit - 1].isHighSurrogate()) limit - 1 else limit
    }
    SelectionContainer { Text(text.take(end), style = MaterialTheme.typography.bodyMedium) }
    if (end < text.length) {
        Text("Showing $end of ${text.length} characters. Original content is retained in full.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        PlayerOutlinedButton(onClick = { shown += 12_000 }, modifier = Modifier.fillMaxWidth()) { Text("Show more $label") }
    }
}
