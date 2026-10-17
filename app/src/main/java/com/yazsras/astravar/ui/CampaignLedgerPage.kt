package com.yazsras.astravar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.LedgerDraft
import com.yazsras.astravar.data.LedgerItem
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Native v1.7.0-compatible, OFFLINE-ONLY campaign inventory beta. */
@Composable
fun CampaignLedgerPage(repository: CampaignLedgerRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by repository.items.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var newItemRequest by remember { mutableIntStateOf(0) }
    val selectedItem = items.firstOrNull { it.id == selectedId }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var owner by remember { mutableStateOf("Zerg") }
    var itemState by remember { mutableStateOf("Unknown") }
    var evidenceStatus by remember { mutableStateOf("Unverified") }
    var location by remember { mutableStateOf("Unknown") }
    var quantity by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var known by remember { mutableStateOf("") }
    var potential by remember { mutableStateOf("") }
    var limitations by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var notionId by remember { mutableStateOf("") }
    var iconData by remember { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(false) }
    var discoveryType by remember { mutableStateOf("Confirmed") }
    var discoveryText by remember { mutableStateOf("") }
    var discoverySource by remember { mutableStateOf("") }

    LaunchedEffect(selectedId, selectedItem?.revision, newItemRequest) {
        title = selectedItem?.title.orEmpty()
        category = selectedItem?.category ?: "Other"
        owner = selectedItem?.owner ?: "Zerg"
        itemState = selectedItem?.itemState ?: "Unknown"
        evidenceStatus = selectedItem?.evidenceStatus ?: "Unverified"
        location = selectedItem?.location ?: "Unknown"
        quantity = selectedItem?.quantity?.toString().orEmpty()
        description = selectedItem?.description.orEmpty()
        known = selectedItem?.knownProperties.orEmpty()
        potential = selectedItem?.possibleUses.orEmpty()
        limitations = selectedItem?.limitations.orEmpty()
        source = selectedItem?.source.orEmpty()
        notionId = selectedItem?.notionPageId.orEmpty()
        iconData = selectedItem?.imageBase64
        archived = selectedItem?.archived ?: false
        discoveryText = ""
        discoverySource = ""
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    iconData = compressIcon(context, uri)
                    status = "Image prepared. Save the item to keep it."
                } catch (e: Exception) {
                    status = "Image not attached: ${e.message ?: "Unable to read picture"}"
                } finally { busy = false }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val bytes = repository.export().toByteArray(Charsets.UTF_8)
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: error("The selected document could not be opened.")
                        stream.use { it.write(bytes) }
                    }
                    status = "Campaign inventory backup saved to the chosen document."
                } catch (e: Exception) {
                    status = "Export failed: ${e.message ?: "No data was written"}"
                } finally { busy = false }
            }
        }
    }

    val eventsFlow = remember(selectedId) { selectedId?.let { repository.eventsForItem(it) } }
    val activeEvents = eventsFlow?.collectAsState(initial = emptyList())?.value ?: emptyList()

    Page(resetKey = selectedId to newItemRequest) {
        SectionCaption("CAMPAIGN RECORDS", "LOCAL BETA · NOT CONNECTED TO NOTION")
        Section("Campaign inventory") {
            Text(
                "A separate, offline-first journal of items and discoveries. Does not change weapon resources, rolls or existing Astravar saves.",
                color = Muted, style = MaterialTheme.typography.bodySmall
            )
            Text("The DM's Notion ledger remains separate until secure sync is tested.",
                style = MaterialTheme.typography.bodySmall, color = Frost)
            if (status.isNotBlank()) Text(status, color = Brass, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selectedId = null; newItemRequest++; status = "" },
                    enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("New item")
                }
                OutlinedButton(onClick = { exportPicker.launch("astravar-campaign-items.json") },
                    enabled = !busy, modifier = Modifier.weight(1f)) { Text("Export JSON") }
            }
            if (items.isEmpty()) Text("No local items recorded yet. Your real campaign inventory has NOT been imported.", color = Muted)
            else items.forEach { item ->
                TextButton(onClick = { selectedId = item.id; status = "" },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LedgerIcon(item.imageBase64, Modifier.size(50.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = Brass)
                            Text("${item.owner} · ${item.itemState} · ${item.quantity?.toString() ?: "unknown quantity"}",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Text("›", color = Frost)
                    }
                }
                HorizontalDivider()
            }
        }
        Section(if (selectedItem == null) "Create an item" else "Edit: ${selectedItem.title}") {
            Field("Item name", title, { title = it })
            Choices("Category", listOf("Armor", "Weapon", "Tool", "Component", "Material", "Magic Item", "Consumable", "Treasure", "Other"), category) { category = it }
            Choices("Held by", listOf("Zerg", "Eek", "Korr", "Veyrath", "Shared", "Storage", "Other", "Unknown"), owner) { owner = it }
            Choices("Current state", listOf("Unknown", "Carried", "Equipped", "Installed", "Stored", "Consumed", "Sold", "Lost"), itemState) { itemState = it }
            Choices("Evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), evidenceStatus) { evidenceStatus = it }
            Field("Specific location / container (optional)", location, { location = it })
            Field("Quantity (blank means unknown)", quantity, { quantity = it })
            Field("Description / appearance", description, { description = it }, single = false)
            Field("Confirmed properties", known, { known = it }, single = false)
            Field("Possible uses (not active mechanics)", potential, { potential = it }, single = false)
            Field("Known limits", limitations, { limitations = it }, single = false)
            Field("Source / checkpoint", source, { source = it }, single = false)
            Field("Notion item page ID or URL (optional)", notionId, { notionId = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LedgerIcon(iconData, Modifier.size(82.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !busy) { Text("Choose item image") }
                    if (iconData != null) TextButton(onClick = { iconData = null }, enabled = !busy) { Text("Remove image") }
                    Text("Images are compressed and included in JSON exports.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            if (selectedItem != null) Toggle("Archive this entry (keep history)", archived) { archived = it }
            Action(if (selectedItem == null) "Save new item" else "Save item changes", !busy && title.isNotBlank()) {
                busy = true
                status = "Saving item…"
                val current = selectedItem
                val draft = LedgerDraft(title = title, category = category, owner = owner,
                    itemState = itemState, location = location, evidenceStatus = evidenceStatus,
                    quantity = quantity.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(),
                    description = description, knownProperties = known, possibleUses = potential,
                    limitations = limitations, source = source,
                    notionPageId = notionId.trim().ifEmpty { null }, imageBase64 = iconData, archived = archived)
                val qtyText = quantity.trim()
                scope.launch {
                    try {
                        require(qtyText.isEmpty() || qtyText.toIntOrNull() != null) { "Quantity must be a whole number or blank." }
                        val saved = if (current == null) repository.create(draft)
                            else repository.update(current.id, current.revision, draft, source)
                        selectedId = saved.id
                        status = "Saved locally: ${saved.title} (revision ${saved.revision}). Not synchronized."
                    } catch (e: Exception) {
                        status = "Save failed: ${e.message ?: "Unknown error"}"
                    } finally { busy = false }
                }
            }
        }
        if (selectedItem != null) Section("Research and discoveries") {
            Text("Study results are appended to this same item and kept in a revision history.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Choices("Evidence category", listOf("Confirmed", "Possible use", "Limitation", "Correction"), discoveryType) { discoveryType = it }
            Field("What was discovered?", discoveryText, { discoveryText = it }, single = false)
            Field("Source / roll / scene", discoverySource, { discoverySource = it }, single = false)
            Action("Append discovery", !busy && discoveryText.isNotBlank()) {
                busy = true
                val item = selectedItem
                val kind = discoveryType; val detail = discoveryText; val src = discoverySource
                scope.launch {
                    try {
                        val saved = repository.discover(item.id, item.revision, kind, detail, src)
                        status = "Discovery saved locally on ${saved.title} (revision ${saved.revision})."
                        discoveryText = ""; discoverySource = ""
                    } catch (e: Exception) {
                        status = "Discovery not saved: ${e.message ?: "Unknown error"}"
                    } finally { busy = false }
                }
            }
            if (activeEvents.isEmpty()) Text("No research recorded yet.", color = Muted)
            activeEvents.take(40).forEach { entry ->
                Text("${entry.evidenceStatus} · ${entry.kind}: ${entry.detail}",
                    style = MaterialTheme.typography.bodySmall)
                if (entry.source.isNotBlank()) Text("Source: ${entry.source}",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LedgerIcon(base64: String?, modifier: Modifier) {
    val bmp = remember(base64) {
        runCatching {
            base64?.let {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bmp != null) Image(bitmap = bmp, contentDescription = "Item artwork", modifier = modifier,
        contentScale = ContentScale.Fit)
    else InstrumentMedallion("Core", modifier, Frost)
}

/** No persistent provider permission or public image hosting required. */
private suspend fun compressIcon(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val header = context.contentResolver.openInputStream(uri) ?: error("Cannot open selected image.")
    header.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "Invalid picture format." }
    val sampling = BitmapFactory.Options()
    var factor = 1
    while (options.outWidth / factor > 640 || options.outHeight / factor > 640) factor *= 2
    sampling.inSampleSize = factor
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sampling) }
        ?: error("Unable to decode picture.")
    val scale = minOf(1f, 448f / maxOf(decoded.width, decoded.height).toFloat())
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
    val output = ByteArrayOutputStream()
    try {
        require(scaled.compress(Bitmap.CompressFormat.WEBP, 82, output)) { "Unable to compress picture." }
    } finally {
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }
    val bytes = output.toByteArray()
    require(bytes.size <= 600_000) { "Picture exceeds the compact icon limit." }
    Base64.encodeToString(bytes, Base64.NO_WRAP)
}
