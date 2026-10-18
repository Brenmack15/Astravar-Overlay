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
import com.yazsras.astravar.data.ItemDetails
import com.yazsras.astravar.data.ItemProperty
import com.yazsras.astravar.data.CampaignLink
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Native local inventory; existing item IDs, data and audit history remain authoritative. */
@Composable
fun CampaignLedgerPage(repository: CampaignLedgerRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records = repository.records
    val itemDetails by records.itemDetails.collectAsState(initial = emptyList())
    val itemProperties by records.itemProperties.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val projects by records.projects.collectAsState(initial = emptyList())
    val entities by records.entities.collectAsState(initial = emptyList())
    val companionStates by records.companionStates.collectAsState(initial = emptyList())
    val extensionActions = rememberLocalRecordActions()
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("All categories") }
    var weight by remember { mutableStateOf("") }
    var extraNotes by remember { mutableStateOf("") }
    var propertyLabel by remember { mutableStateOf("") }
    var propertyValue by remember { mutableStateOf("") }
    var propertyEvidence by remember { mutableStateOf("Unverified") }
    var selectedProperty by remember { mutableStateOf<ItemProperty?>(null) }
    var relatedType by remember { mutableStateOf("companion") }
    var relatedId by remember { mutableStateOf<String?>(null) }
    var relationLabel by remember { mutableStateOf("Equipment") }
    val items by repository.items.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var newItemRequest by remember { mutableIntStateOf(0) }
    val selectedItem = items.firstOrNull { it.id == selectedId }
    var loadedItem by remember { mutableStateOf<LedgerItem?>(null) }
    var loadedDetails by remember { mutableStateOf<ItemDetails?>(null) }
    var historyLimit by remember(selectedId) { mutableIntStateOf(40) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var owner by remember { mutableStateOf("Unknown") }
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
    var imageTargetId by remember { mutableStateOf<String?>(null) }
    var imageTargetRequest by remember { mutableIntStateOf(0) }
    var discoveryType by remember { mutableStateOf("Confirmed") }
    var discoveryText by remember { mutableStateOf("") }
    var discoverySource by remember { mutableStateOf("") }

    val profileDirty = title != loadedItem?.title.orEmpty() || category != (loadedItem?.category ?: "Other") ||
        owner != (loadedItem?.owner ?: "Unknown") || itemState != (loadedItem?.itemState ?: "Unknown") ||
        evidenceStatus != (loadedItem?.evidenceStatus ?: "Unverified") || location != (loadedItem?.location ?: "Unknown") ||
        quantity != loadedItem?.quantity?.toString().orEmpty() || description != loadedItem?.description.orEmpty() ||
        known != loadedItem?.knownProperties.orEmpty() || potential != loadedItem?.possibleUses.orEmpty() ||
        limitations != loadedItem?.limitations.orEmpty() || source != loadedItem?.source.orEmpty() ||
        notionId != loadedItem?.notionPageId.orEmpty() || iconData != loadedItem?.imageBase64 || archived != (loadedItem?.archived ?: false)
    val staleProfile = selectedItem != null && loadedItem != null && selectedItem.revision != loadedItem?.revision
    LaunchedEffect(selectedId, selectedItem?.revision, newItemRequest) {
        if (loadedItem?.id == selectedId && selectedItem != null && loadedItem != null && profileDirty) return@LaunchedEffect
        loadedItem = selectedItem
        title = selectedItem?.title.orEmpty()
        category = selectedItem?.category ?: "Other"
        owner = selectedItem?.owner ?: "Unknown"
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

    val details = itemDetails.firstOrNull { it.itemId == selectedId }
    val detailsDirty = weight != loadedDetails?.weightGrams?.toString().orEmpty() || extraNotes != loadedDetails?.notes.orEmpty()
    val staleDetails = details != null && loadedDetails != null && details.revision != loadedDetails?.revision
    LaunchedEffect(selectedId, details?.revision, newItemRequest) {
        if (loadedDetails?.itemId == selectedId && loadedDetails != null && detailsDirty) return@LaunchedEffect
        loadedDetails = details
        weight = details?.weightGrams?.toString().orEmpty()
        extraNotes = details?.notes.orEmpty()
    }
    LaunchedEffect(selectedId) {
        selectedProperty = null; propertyLabel = ""; propertyValue = ""; propertyEvidence = "Unverified"
        relatedId = null
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    check(imageTargetId == selectedId && imageTargetRequest == newItemRequest) { "The item changed. Choose its image again." }
                    val prepared = compressIcon(context, uri)
                    check(imageTargetId == selectedId && imageTargetRequest == newItemRequest) { "The item changed. Choose its image again." }
                    iconData = prepared
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
        SectionCaption("INVENTORY", "LOCAL RECORDS · PERSISTENT ITEM IDENTITIES")
        Section("Owned items & equipment") {
            Text(
                "Items, equipment, materials and discoveries saved privately on this device. Weapon resources and rolls remain separate.",
                color = Muted, style = MaterialTheme.typography.bodySmall
            )
            Text("Private campaign records are not sent to the public synthetic sandbox.",
                style = MaterialTheme.typography.bodySmall, color = Frost)
            Field("Search inventory", search, { search = it }, enabled = !busy && !extensionActions.busy)
            Choices("Show category", listOf("All categories") + items.map { it.category }.distinct().sorted(), categoryFilter, enabled = !busy && !extensionActions.busy) { categoryFilter = it }
            LocalRecordStatus(extensionActions.message, extensionActions.busy)
            if (status.isNotBlank()) Text(status, color = Brass, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selectedId = null; newItemRequest++; status = "" },
                    enabled = !busy && !extensionActions.busy, modifier = Modifier.weight(1f)) {
                    Text("New item")
                }
                OutlinedButton(onClick = { exportPicker.launch("astravar-campaign-items.json") },
                    enabled = !busy && !extensionActions.busy, modifier = Modifier.weight(1f)) { Text("Export JSON") }
            }
            if (items.isEmpty()) Text("No local items recorded yet. Add an item to begin; no campaign data has been imported.", color = Muted)
            else items.filter { item ->
                (categoryFilter == "All categories" || item.category == categoryFilter) &&
                    (search.isBlank() || "${item.title} ${item.category} ${item.owner} ${item.description}".contains(search, true))
            }.forEach { item ->
                TextButton(onClick = { loadedItem = null; loadedDetails = null; selectedId = item.id; newItemRequest++; status = "" },
                    enabled = !busy && !extensionActions.busy, modifier = Modifier.fillMaxWidth()) {
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
            if (staleProfile) Text("A newer item revision exists. Your draft is preserved. Select this item again to load its current state before saving.", color = Frost, style = MaterialTheme.typography.bodySmall)
            Field("Item name", title, { title = it }, enabled = !busy && !extensionActions.busy)
            Choices("Category", listOf("Armor", "Weapon", "Tool", "Component", "Material", "Magic Item", "Consumable", "Treasure", "Other"), category, enabled = !busy && !extensionActions.busy) { category = it }
            Field("Owner / holder label", owner, { owner = it }, enabled = !busy && !extensionActions.busy)
            Choices("Current state", listOf("Unknown", "Carried", "Equipped", "Installed", "Stored", "Consumed", "Sold", "Lost"), itemState, enabled = !busy && !extensionActions.busy) { itemState = it }
            Choices("Evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), evidenceStatus, enabled = !busy && !extensionActions.busy) { evidenceStatus = it }
            Field("Specific location / container (optional)", location, { location = it }, enabled = !busy && !extensionActions.busy)
            Field("Quantity (blank means unknown)", quantity, { quantity = it }, enabled = !busy && !extensionActions.busy)
            Field("Description / appearance", description, { description = it }, single = false, enabled = !busy && !extensionActions.busy)
            Text("Properties and research notes are descriptive. They never change attack calculations or activate an unapproved mechanic.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Field("Known properties / legacy notes", known, { known = it }, single = false, enabled = !busy && !extensionActions.busy)
            Field("Possible uses (not active mechanics)", potential, { potential = it }, single = false, enabled = !busy && !extensionActions.busy)
            Field("Known limits", limitations, { limitations = it }, single = false, enabled = !busy && !extensionActions.busy)
            Field("Source / checkpoint", source, { source = it }, single = false, enabled = !busy && !extensionActions.busy)
            Field("Notion item page ID or URL (optional)", notionId, { notionId = it }, enabled = !busy && !extensionActions.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LedgerIcon(iconData, Modifier.size(82.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { imageTargetId = selectedId; imageTargetRequest = newItemRequest; imagePicker.launch("image/*") }, enabled = !busy && !extensionActions.busy) { Text("Choose item image") }
                    if (iconData != null) TextButton(onClick = { iconData = null }, enabled = !busy) { Text("Remove image") }
                    Text("Images are compressed and included in JSON exports.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            if (selectedItem != null) Toggle("Archive this entry (keep history)", archived, enabled = !busy && !extensionActions.busy) { archived = it }
            Action(if (selectedItem == null) "Save new item" else "Save item changes", !busy && !extensionActions.busy && !staleProfile && title.isNotBlank()) {
                busy = true
                status = "Saving item…"
                val current = loadedItem
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
                        loadedItem = saved
                        selectedId = saved.id
                        status = "Saved locally: ${saved.title} (revision ${saved.revision}). Not synchronized."
                    } catch (e: Exception) {
                        status = "Save failed: ${e.message ?: "Unknown error"}"
                    } finally { busy = false }
                }
            }
        }
        if (selectedItem != null) {
            ExpandableRecordSection("Weight, properties & mechanics") {
                val selectedDetails = loadedDetails
                if (staleDetails) Text("Weight or handling changed elsewhere. Your draft is preserved; reopen this item before saving those details.", color = Frost, style = MaterialTheme.typography.bodySmall)
                Field("Weight per item in grams (blank = unknown)", weight, { weight = it }, enabled = !busy && !extensionActions.busy)
                Field("Property / handling notes", extraNotes, { extraNotes = it }, single = false, enabled = !busy && !extensionActions.busy)
                Text("Use measured or confirmed weight. Existing quantity and identity are unchanged.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Action("Save weight and handling", !busy && !extensionActions.busy && !staleDetails && source.isNotBlank()) {
                    val text = weight.trim(); val notes = extraNotes; val provenance = source
                    val model = selectedDetails?.copy(weightGrams = text.toLongOrNull(), notes = notes)
                        ?: ItemDetails(itemId = selectedItem.id, weightGrams = text.toLongOrNull(), notes = notes)
                    extensionActions.run {
                        require(text.isEmpty() || text.toLongOrNull()?.let { it >= 0 } == true) { "Weight must be nonnegative grams or unknown." }
                        val receipt = records.saveItemDetails(model, selectedDetails?.revision, provenance)
                        loadedDetails = model.copy(revision = receipt.resultingRevision, source = provenance, updatedAt = receipt.createdAt)
                        "Weight and handling saved locally."
                    }
                }
                val properties = itemProperties.filter { it.itemId == selectedItem.id }
                properties.forEach { property ->
                    TextButton(onClick = { selectedProperty = property; propertyLabel = property.label; propertyValue = property.value; propertyEvidence = property.evidenceStatus }, enabled = !busy && !extensionActions.busy) {
                        Text("${property.label}: ${property.value} · ${property.evidenceStatus}", color = Brass)
                    }
                }
                Field("Property name", propertyLabel, { propertyLabel = it }, enabled = !busy && !extensionActions.busy)
                Field("Property value / exact rule reference", propertyValue, { propertyValue = it }, single = false, enabled = !busy && !extensionActions.busy)
                Choices("Property evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), propertyEvidence, enabled = !busy && !extensionActions.busy) { propertyEvidence = it }
                Text("Confirmed descriptive evidence does not activate a combat effect. The existing rules engine remains authoritative.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Action(if (selectedProperty == null) "Add item property" else "Save item property", !busy && !extensionActions.busy && propertyLabel.isNotBlank() && propertyValue.isNotBlank() && source.isNotBlank()) {
                    val before = selectedProperty; val provenance = source
                    val model = before?.copy(label = propertyLabel, value = propertyValue, evidenceStatus = propertyEvidence)
                        ?: ItemProperty(itemId = selectedItem.id, label = propertyLabel, value = propertyValue, evidenceStatus = propertyEvidence)
                    extensionActions.run {
                        records.saveItemProperty(model, before?.revision, provenance)
                        selectedProperty = null; propertyLabel = ""; propertyValue = ""
                        "Property saved locally as descriptive evidence."
                    }
                }
                if (selectedProperty != null) TextButton(onClick = { selectedProperty = null; propertyLabel = ""; propertyValue = "" }, enabled = !extensionActions.busy) { Text("New property") }
                if (source.isBlank()) Text("Provide a source in the item editor before saving structured details.", color = Frost, style = MaterialTheme.typography.bodySmall)
            }
            ExpandableRecordSection("Ownership links, inventions & equipment") {
                Text("A link references this physical item by its permanent ID. It does not duplicate, transfer, install, consume or activate it.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("Item ID: ${selectedItem.id}", color = Muted, style = MaterialTheme.typography.labelSmall)
                links.filter { (it.fromType == "item" && it.fromId == selectedItem.id) || (it.toType == "item" && it.toId == selectedItem.id) }.forEach { link ->
                    val targetType = if (link.fromType == "item" && link.fromId == selectedItem.id) link.toType else link.fromType
                    val targetId = if (link.fromType == "item" && link.fromId == selectedItem.id) link.toId else link.fromId
                    val targetName = when (targetType) {
                        "companion" -> "Eek"
                        "project" -> projects.firstOrNull { it.id == targetId }?.title
                        "connection" -> entities.firstOrNull { it.id == targetId }?.name
                        else -> null
                    } ?: "Linked record"
                    Text("${link.label} · $targetName", style = MaterialTheme.typography.bodySmall)
                }
                Choices("Related record type", listOf("companion", "project", "connection"), relatedType,
                    display = { when (it) { "companion" -> "Eek"; "project" -> "Invention"; else -> "Connection" } }, enabled = !busy && !extensionActions.busy) { relatedType = it; relatedId = null }
                val targets = when (relatedType) {
                    "companion" -> companionStates.map { it.companionId to it.name }
                    "project" -> projects.map { it.id to it.title }
                    else -> entities.map { it.id to it.name }
                }
                if (targets.isEmpty()) Text("Create a sourced ${if (relatedType == "companion") "Eek status" else "related record"} before adding this link.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Choices("Related record", targets.map { it.first }, relatedId, display = { id -> targets.firstOrNull { it.first == id }?.second ?: "Choose record" }, enabled = !busy && !extensionActions.busy) { relatedId = it }
                Choices("Relationship", listOf("Owned by", "Held by", "Equipment", "Installed component", "Material requirement", "Research subject", "Origin"), relationLabel, enabled = !busy && !extensionActions.busy) { relationLabel = it }
                Action("Link this existing item", !busy && !extensionActions.busy && relatedId != null && source.isNotBlank()) {
                    val target = relatedId ?: return@Action; val kind = relatedType; val relation = relationLabel; val provenance = source
                    extensionActions.run {
                        records.saveLink(CampaignLink(fromType = "item", fromId = selectedItem.id, toType = kind, toId = target, label = relation), null, provenance)
                        "Persistent item link saved locally. Quantity and ownership state unchanged."
                    }
                }
            }
        }
        if (selectedItem != null) Section("Research and discoveries") {
            Text("Study results are appended to this same item and kept in a revision history.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Choices("Evidence category", listOf("Confirmed", "Possible use", "Limitation", "Correction"), discoveryType, enabled = !busy && !extensionActions.busy) { discoveryType = it }
            Field("What was discovered?", discoveryText, { discoveryText = it }, single = false, enabled = !busy && !extensionActions.busy)
            Field("Source / roll / scene", discoverySource, { discoverySource = it }, single = false, enabled = !busy && !extensionActions.busy)
            Action("Append discovery", !busy && !extensionActions.busy && discoveryText.isNotBlank() && discoverySource.isNotBlank()) {
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
            activeEvents.take(historyLimit).forEach { entry ->
                Text("${entry.evidenceStatus} · ${entry.kind}: ${entry.detail}",
                    style = MaterialTheme.typography.bodySmall)
                if (entry.source.isNotBlank()) Text("Source: ${entry.source}",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
            if (activeEvents.size > historyLimit) OutlinedButton(onClick = { historyLimit += 40 }, enabled = !busy) {
                Text("Show more item history (${activeEvents.size - historyLimit} remaining)")
            }
        }
    }
}

@Composable
internal fun LedgerIcon(base64: String?, modifier: Modifier, description: String = "Item artwork") {
    val bmp = remember(base64) {
        runCatching {
            base64?.let {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bmp != null) Image(bitmap = bmp, contentDescription = description, modifier = modifier,
        contentScale = ContentScale.Fit)
    else InstrumentMedallion("Core", modifier, Frost)
}

/** No persistent provider permission or public image hosting required. */
internal suspend fun compressIcon(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
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
