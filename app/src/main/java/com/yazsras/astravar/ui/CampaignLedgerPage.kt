package com.yazsras.astravar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/** Presentation only: stable identities, revisions, events and transactions remain repository-owned. */
@Composable
fun CampaignLedgerPage(repository: CampaignLedgerRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records = repository.records
    val items by repository.items.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val itemDetails by records.itemDetails.collectAsState(initial = emptyList())
    val itemProperties by records.itemProperties.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val projects by records.projects.collectAsState(initial = emptyList())
    val entities by records.entities.collectAsState(initial = emptyList())
    val companions by records.companionStates.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var mode by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Frozen serializable editor baselines survive recreation, including their optimistic revision.
    val itemDraft = rememberUiDraftText()
    var itemSnapshot by itemDraft
    val detailsDraft = rememberUiDraftText()
    var detailsSnapshot by detailsDraft
    val propertyDraft = rememberUiDraftText()
    var propertySnapshot by propertyDraft
    val loadedItem = remember(itemSnapshot) { itemSnapshot?.let { runCatching { Json.decodeFromString<LedgerItem>(it) }.getOrNull() } }
    val loadedDetails = remember(detailsSnapshot) { detailsSnapshot?.let { runCatching { Json.decodeFromString<ItemDetails>(it) }.getOrNull() } }
    val loadedProperty = remember(propertySnapshot) { propertySnapshot?.let { runCatching { Json.decodeFromString<ItemProperty>(it) }.getOrNull() } }
    val selected = items.firstOrNull { it.id == selectedId } ?: loadedItem
    var title by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Other") }
    var owner by rememberSaveable { mutableStateOf("Unknown") }
    var itemState by rememberSaveable { mutableStateOf("Unknown") }
    var evidence by rememberSaveable { mutableStateOf("Unverified") }
    var location by rememberSaveable { mutableStateOf("Unknown") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var known by rememberSaveable { mutableStateOf("") }
    var potential by rememberSaveable { mutableStateOf("") }
    var limitations by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var baselineSource by rememberSaveable { mutableStateOf("") }
    var notionId by rememberSaveable { mutableStateOf("") }
    val imageDraft = rememberUiDraftText()
    var iconData by imageDraft
    var archived by rememberSaveable { mutableStateOf(false) }
    var weight by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var propertyLabel by rememberSaveable { mutableStateOf("") }
    var propertyValue by rememberSaveable { mutableStateOf("") }
    var propertyEvidence by rememberSaveable { mutableStateOf("Unverified") }
    var relatedType by rememberSaveable { mutableStateOf("companion") }
    var relatedId by rememberSaveable { mutableStateOf<String?>(null) }
    var relationLabel by rememberSaveable { mutableStateOf("Equipment") }
    var discoveryType by rememberSaveable { mutableStateOf("Confirmed") }
    var discoveryText by rememberSaveable { mutableStateOf("") }
    var discoverySource by rememberSaveable { mutableStateOf("") }
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyLimit by rememberSaveable(selectedId) { mutableIntStateOf(40) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var imageTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var draftToken by rememberSaveable { mutableIntStateOf(0) }
    var imageToken by rememberSaveable { mutableIntStateOf(0) }

    val eventsFlow = remember(selectedId) { selectedId?.let(repository::eventsForItem) }
    val events = eventsFlow?.collectAsState(initial = emptyList())?.value.orEmpty()
    val structuredPlacement = placements.firstOrNull { it.itemId == selectedId }
    val stale = selected != null && loadedItem != null && selected.revision != loadedItem.revision
    val details = itemDetails.firstOrNull { it.itemId == selectedId }
    val staleDetails = details != null && loadedDetails != null && details.revision != loadedDetails.revision
    val itemDraftValid = itemDraft.canSave && (itemSnapshot == null || loadedItem != null) &&
        (selectedId == null || loadedItem?.id == selectedId)
    val detailsDraftValid = detailsDraft.canSave && (detailsSnapshot == null || loadedDetails != null)
    val propertyDraftValid = propertyDraft.canSave && (propertySnapshot == null || loadedProperty != null)
    val profileDraftValid = itemDraftValid && imageDraft.canSave
    val draftErrors = buildList {
        itemDraft.error?.let(::add)
        if (!itemDraftValid && itemDraft.error == null) add("The saved item draft could not be read. Close and reopen the item before saving.")
        if (mode in setOf("create", "edit")) imageDraft.error?.let(::add)
        if (mode == "handling") {
            detailsDraft.error?.let(::add)
            if (!detailsDraftValid && detailsDraft.error == null) add("The saved handling draft could not be read. Reopen weight & handling before saving.")
        }
        if (mode == "property") {
            propertyDraft.error?.let(::add)
            if (!propertyDraftValid && propertyDraft.error == null) add("The saved property draft could not be read. Reopen the property before saving.")
        }
    }.distinct()
    val profileDirty = title != loadedItem?.title.orEmpty() || category != (loadedItem?.category ?: "Other") ||
        owner != (loadedItem?.owner ?: "Unknown") || itemState != (loadedItem?.itemState ?: "Unknown") ||
        evidence != (loadedItem?.evidenceStatus ?: "Unverified") || location != (loadedItem?.location ?: "Unknown") ||
        quantity != loadedItem?.quantity?.toString().orEmpty() || description != loadedItem?.description.orEmpty() ||
        known != loadedItem?.knownProperties.orEmpty() || potential != loadedItem?.possibleUses.orEmpty() ||
        limitations != loadedItem?.limitations.orEmpty() || source != loadedItem?.source.orEmpty() ||
        notionId != loadedItem?.notionPageId.orEmpty() || iconData != loadedItem?.imageBase64 || archived != (loadedItem?.archived ?: false)
    val dirty = when (mode) {
        "create", "edit" -> profileDirty
        "handling" -> weight != loadedDetails?.weightGrams?.toString().orEmpty() || notes != loadedDetails?.notes.orEmpty() || source != baselineSource
        "property" -> propertyLabel != loadedProperty?.label.orEmpty() || propertyValue != loadedProperty?.value.orEmpty() || propertyEvidence != (loadedProperty?.evidenceStatus ?: "Unverified") || source != baselineSource
        "link" -> relatedId != null || source != baselineSource
        "research" -> discoveryText.isNotBlank() || discoverySource.isNotBlank()
        else -> false
    }
    fun load(item: LedgerItem?) {
        itemSnapshot = item?.let { Json.encodeToString(it) }; selectedId = item?.id; draftToken++
        // An explicit reopen/reload replaces failed draft references from authoritative state.
        detailsSnapshot = null; propertySnapshot = null
        title = item?.title.orEmpty(); category = item?.category ?: "Other"; owner = item?.owner ?: "Unknown"
        itemState = item?.itemState ?: "Unknown"; evidence = item?.evidenceStatus ?: "Unverified"
        location = item?.location ?: "Unknown"; quantity = item?.quantity?.toString().orEmpty()
        description = item?.description.orEmpty(); known = item?.knownProperties.orEmpty()
        potential = item?.possibleUses.orEmpty(); limitations = item?.limitations.orEmpty()
        source = item?.source.orEmpty(); baselineSource = source; notionId = item?.notionPageId.orEmpty()
        iconData = item?.imageBase64; archived = item?.archived ?: false; message = ""
    }
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Could not save. Please try again." }
            finally { busy = false }
        }
    }
    fun saveProfile() {
        if (!profileDraftValid) { message = draftErrors.firstOrNull() ?: "Reload this item before saving."; return }
        val before = loadedItem
        val raw = quantity.trim()
        val draft = LedgerDraft(title = title, category = category, owner = owner, itemState = itemState,
            location = location, evidenceStatus = evidence, quantity = raw.toIntOrNull(), description = description,
            knownProperties = known, possibleUses = potential, limitations = limitations, source = source,
            notionPageId = notionId.trim().ifEmpty { null }, imageBase64 = iconData, archived = archived)
        execute {
            require(raw.isEmpty() || raw.toIntOrNull() != null) { "Quantity must be a whole number or blank." }
            val saved = if (before == null) repository.create(draft) else repository.update(before.id, before.revision, draft, draft.source)
            load(saved); mode = if (saved.archived) "" else "detail"; message = "Item saved"
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) execute {
            check(imageTarget == selectedId && imageToken == draftToken) { "The item changed. Choose its image again." }
            val prepared = compressIcon(context, uri)
            check(imageTarget == selectedId && imageToken == draftToken) { "The item changed. Choose its image again." }
            iconData = prepared; message = "Image ready to save"
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) execute {
            val bytes = repository.export().toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.IO) { (context.contentResolver.openOutputStream(uri) ?: error("Cannot open the document.")).use { it.write(bytes) } }
            message = "Backup saved"
        }
    }
    val visibleItems = items.filter { (filter == "All" || it.category == filter) &&
        (query.isBlank() || "${it.title} ${it.category} ${it.owner} ${it.description}".contains(query, true)) }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Inventory", style = MaterialTheme.typography.headlineMedium); Text("${items.size} items", color = Muted, style = MaterialTheme.typography.bodySmall) }
            PlayerButton(onClick = { load(null); mode = "create" }, enabled = !busy) { Text("Add Item") }
        }
        PlayerSearch(query, { query = it }, "Search inventory")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All") + items.map { it.category }.distinct().sorted()).forEach { name ->
                PlayerFilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
            }
        }
        if (message.isNotBlank() && mode.isBlank()) Text(message, color = Frost, style = MaterialTheme.typography.bodySmall)
        if (visibleItems.isEmpty()) PlayerCard {
            Text(if (items.isEmpty()) "Your inventory is ready" else "No matching items", style = MaterialTheme.typography.titleMedium)
            Text(if (items.isEmpty()) "Add equipment, materials or a discovery." else "Try another name or category.", color = Muted)
        }
        visibleItems.forEach { item ->
            PlayerCard(onClick = { load(item); mode = "detail" }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LedgerIcon(item.imageBase64, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(item.category, item.itemState.takeUnless { it == "Unknown" }, item.owner.takeUnless { it == "Unknown" }).filterNotNull().joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(item.quantity?.toString() ?: "—", color = Frost, style = MaterialTheme.typography.titleLarge)
                        Text("qty", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        PlayerTextButton(onClick = { exportPicker.launch("astravar-campaign-items.json") }, enabled = !busy) { Text("Export JSON") }
    }
    if (mode.isNotBlank()) {
        val sheetTitle = when(mode) { "create" -> "Add Item"; "edit" -> "Edit item"; "handling" -> "Weight & handling"; "property" -> if (loadedProperty == null) "Add property" else "Edit property"; "link" -> "Connect record"; "research" -> "Add discovery"; "event" -> "History entry"; else -> selected?.title ?: "Item" }
        PlayerSheet(sheetTitle, onDismiss = { if (!busy) mode = "" }, dirty = dirty, actions = {
            if (mode in setOf("create", "edit")) PlayerButton(onClick = ::saveProfile, enabled = !busy && profileDraftValid && !stale && title.isNotBlank()) { Text(if (mode == "create") "Save new item" else "Save item changes") }
            if (mode == "detail") PlayerButton(onClick = { selected?.let(::load); mode = "edit" }, enabled = !busy) { Text("Edit item") }
            if (mode == "handling") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val before = loadedDetails; val raw = weight.trim(); val provenance = source
                val model = before?.copy(weightGrams = raw.toLongOrNull(), notes = notes) ?: ItemDetails(itemId = item.id, weightGrams = raw.toLongOrNull(), notes = notes)
                execute {
                    require(raw.isEmpty() || raw.toLongOrNull()?.let { it >= 0 } == true) { "Weight must be nonnegative grams or unknown." }
                    records.saveItemDetails(model, before?.revision, provenance); mode = "detail"; message = "Weight and handling saved"
                }
            }, enabled = !busy && itemDraftValid && detailsDraftValid && !staleDetails && source.isNotBlank()) { Text("Save weight and handling") }
            if (mode == "property") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val before = loadedProperty; val provenance = source
                val model = before?.copy(label = propertyLabel, value = propertyValue, evidenceStatus = propertyEvidence) ?: ItemProperty(itemId = item.id, label = propertyLabel, value = propertyValue, evidenceStatus = propertyEvidence)
                execute { records.saveItemProperty(model, before?.revision, provenance); mode = "detail"; message = "Property saved" }
            }, enabled = !busy && itemDraftValid && propertyDraftValid && propertyLabel.isNotBlank() && propertyValue.isNotBlank() && source.isNotBlank()) { Text(if (loadedProperty == null) "Add item property" else "Save item property") }
            if (mode == "link") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val target = relatedId ?: return@PlayerButton; val provenance = source
                val link = CampaignLink(fromType = "item", fromId = item.id, toType = relatedType, toId = target, label = relationLabel)
                execute { records.saveLink(link, null, provenance); relatedId = null; mode = "detail"; message = "Record connected" }
            }, enabled = !busy && itemDraftValid && relatedId != null && source.isNotBlank()) { Text("Link this existing item") }
            if (mode == "research") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val kind = discoveryType; val text = discoveryText; val provenance = discoverySource
                execute { val saved = repository.discover(item.id, item.revision, kind, text, provenance); load(saved); discoveryText = ""; discoverySource = ""; mode = "detail"; message = "Discovery saved" }
            }, enabled = !busy && itemDraftValid && discoveryText.isNotBlank() && discoverySource.isNotBlank()) { Text("Append discovery") }
        }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            draftErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (message.isNotBlank()) Text(message, color = Frost, style = MaterialTheme.typography.bodySmall)
            when(mode) {
                "create", "edit" -> {
                    if (stale) Text("This item changed elsewhere. Your draft is preserved. Close and reopen it to load the latest revision.", color = Frost)
                    Field("Item name", title, { title = it }, enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Choices("Category", listOf("Armor", "Weapon", "Tool", "Component", "Material", "Magic Item", "Consumable", "Treasure", "Other"), category, enabled = !busy) { category = it } }
                        Box(Modifier.weight(1f)) { Field("Quantity", quantity, { quantity = it }, enabled = !busy && structuredPlacement == null) }
                    }
                    Text(if (structuredPlacement == null) "Blank quantity means unknown" else "Party → Equipment records ownership and quantity changes atomically. These fields are read-only here.", style = MaterialTheme.typography.labelSmall, color = Muted)
                    Field("Description / appearance", description, { description = it }, single = false, enabled = !busy)
                    ExpandableRecordSection("Ownership & location") {
                        Field("Owner / holder label", owner, { owner = it }, enabled = !busy && structuredPlacement == null)
                        Choices("Current state", listOf("Unknown", "Carried", "Equipped", "Installed", "Stored", "Consumed", "Sold", "Lost"), itemState, enabled = !busy && structuredPlacement == null) { itemState = it }
                        Field("Specific location / container (optional)", location, { location = it }, enabled = !busy && structuredPlacement == null)
                    }
                    ExpandableRecordSection("Properties & research notes") {
                        Field("Known properties / legacy notes", known, { known = it }, single = false, enabled = !busy)
                        Field("Possible uses (not active mechanics)", potential, { potential = it }, single = false, enabled = !busy)
                        Field("Known limits", limitations, { limitations = it }, single = false, enabled = !busy)
                    }
                    ExpandableRecordSection("Artwork & provenance") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LedgerIcon(iconData, Modifier.size(64.dp))
                            PlayerTextButton(onClick = { imageTarget = selectedId; imageToken = draftToken; imagePicker.launch("image/*") }, enabled = !busy) { Text("Choose item image") }
                        }
                        if (iconData != null) PlayerTextButton(onClick = { iconData = null }, enabled = !busy) { Text("Remove image") }
                        Choices("Evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), evidence, enabled = !busy) { evidence = it }
                        Field("Notion item page ID or URL (optional)", notionId, { notionId = it }, enabled = !busy)
                    }
                    Field("Source / checkpoint", source, { source = it }, single = false, enabled = !busy)
                    if (loadedItem != null) ExpandableRecordSection("Archive") { Toggle("Archive this entry (keep history)", archived, enabled = !busy) { archived = it } }
                }
                "detail" -> selected?.let { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LedgerIcon(item.imageBase64, Modifier.size(64.dp))
                        Column { Text(item.category, color = Frost, style = MaterialTheme.typography.labelLarge); Text("${item.quantity?.toString() ?: "Unknown"} quantity · ${item.itemState}", color = Muted) }
                    }
                    ExpandableRecordSection("Overview", true) {
                        if (item.description.isNotBlank()) Text(item.description)
                        RecordValue("Owner", item.owner); RecordValue("Location", item.location); RecordValue("Evidence", item.evidenceStatus)
                        if (item.source.isNotBlank()) RecordValue("Source", item.source)
                    }
                    ExpandableRecordSection("Properties & Mechanics") {
                        if (item.knownProperties.isNotBlank()) Text(item.knownProperties)
                        if (item.limitations.isNotBlank()) RecordValue("Limits", item.limitations)
                        val value = itemDetails.firstOrNull { it.itemId == item.id }
                        RecordValue("Weight per item", value?.weightGrams?.let { "$it g" } ?: "Unknown")
                        if (value?.notes?.isNotBlank() == true) Text(value.notes)
                        PlayerTextButton(onClick = { detailsSnapshot = value?.let { Json.encodeToString(it) }; weight = value?.weightGrams?.toString().orEmpty(); notes = value?.notes.orEmpty(); source = item.source; baselineSource = source; mode = "handling" }) { Text("Edit weight & handling") }
                        itemProperties.filter { it.itemId == item.id }.forEach { property ->
                            PlayerTextButton(onClick = { propertySnapshot = Json.encodeToString(property); propertyLabel = property.label; propertyValue = property.value; propertyEvidence = property.evidenceStatus; source = property.source; baselineSource = source; mode = "property" }) { Column(Modifier.fillMaxWidth()) { Text(property.label); Text(property.value, color = Muted, style = MaterialTheme.typography.bodySmall) } }
                        }
                        PlayerTextButton(onClick = { propertySnapshot = null; propertyLabel = ""; propertyValue = ""; propertyEvidence = "Unverified"; source = item.source; baselineSource = source; mode = "property" }) { Text("Add property") }
                    }
                    ExpandableRecordSection("Research") {
                        if (item.possibleUses.isNotBlank()) RecordValue("Possible uses", item.possibleUses)
                        val discoveries = events.filter { it.kind == "DISCOVER" || it.kind.contains("DISCOVER", true) }
                        if (discoveries.isEmpty()) Text("No discoveries recorded", color = Muted)
                        discoveries.take(5).forEach { Text(it.detail, style = MaterialTheme.typography.bodySmall) }
                        PlayerTextButton(onClick = { discoveryText = ""; discoverySource = ""; mode = "research" }) { Text("Add discovery") }
                    }
                    ExpandableRecordSection("Connected Records") {
                        val related = links.filter { (it.fromType == "item" && it.fromId == item.id) || (it.toType == "item" && it.toId == item.id) }
                        if (related.isEmpty()) Text("No connected records", color = Muted)
                        related.forEach { link ->
                            val forward = link.fromType == "item" && link.fromId == item.id
                            val type = if (forward) link.toType else link.fromType; val id = if (forward) link.toId else link.fromId
                            val name = when(type) { "companion" -> companions.firstOrNull { it.companionId == id }?.name ?: "Eek"; "project" -> projects.firstOrNull { it.id == id }?.title; "connection" -> entities.firstOrNull { it.id == id }?.name; else -> items.firstOrNull { it.id == id }?.title } ?: "Related record"
                            RecordValue(link.label, name)
                        }
                        PlayerTextButton(onClick = { relatedType = "companion"; relatedId = null; relationLabel = "Equipment"; source = item.source; baselineSource = source; mode = "link" }) { Text("Connect record") }
                    }
                    ExpandableRecordSection("History") {
                        if (events.isEmpty()) Text("No history recorded", color = Muted)
                        events.take(historyLimit).forEach { entry ->
                            PlayerTextButton(onClick = { eventId = entry.commandId; mode = "event" }) { Column(Modifier.fillMaxWidth()) { Text(entry.kind.replace('_', ' '), color = Frost); Text(entry.detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                        }
                        audit.filter { entry -> entry.recordId == item.id || entry.recordId in itemProperties.filter { p -> p.itemId == item.id }.map { p -> p.id } || entry.recordId in links.filter { link -> (link.fromType == "item" && link.fromId == item.id) || (link.toType == "item" && link.toId == item.id) }.map { it.id } }.take(historyLimit).forEach { entry -> RecordValue(entry.kind, entry.source) }
                        if (events.size > historyLimit) PlayerTextButton(onClick = { historyLimit += 40 }) { Text("Show more item history") }
                        Text(item.id, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                "handling" -> {
                    if (staleDetails) Text("These details changed elsewhere. Your draft is preserved; reopen the item before saving.", color = Frost)
                    Field("Weight per item in grams (blank = unknown)", weight, { weight = it }, enabled = !busy)
                    Field("Property / handling notes", notes, { notes = it }, single = false, enabled = !busy)
                    Field("Source / checkpoint", source, { source = it }, single = false, enabled = !busy)
                }
                "property" -> {
                    Field("Property name", propertyLabel, { propertyLabel = it }, enabled = !busy)
                    Field("Property value / exact rule reference", propertyValue, { propertyValue = it }, single = false, enabled = !busy)
                    Choices("Property evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), propertyEvidence, enabled = !busy) { propertyEvidence = it }
                    Field("Source / checkpoint", source, { source = it }, single = false, enabled = !busy)
                }
                "link" -> {
                    Choices("Related record type", listOf("companion", "project", "connection"), relatedType, display = { when(it) { "companion" -> "Eek"; "project" -> "Invention"; else -> "Connection" } }, enabled = !busy) { relatedType = it; relatedId = null }
                    val targets = when(relatedType) { "companion" -> companions.map { it.companionId to it.name }; "project" -> projects.map { it.id to it.title }; else -> entities.map { it.id to it.name } }
                    if (targets.isEmpty()) Text("Create the related record first", color = Muted)
                    Choices("Related record", targets.map { it.first }, relatedId, display = { id -> targets.firstOrNull { it.first == id }?.second ?: "Choose record" }, enabled = !busy) { relatedId = it }
                    Choices("Relationship", listOf("Owned by", "Held by", "Equipment", "Installed component", "Material requirement", "Research subject", "Origin"), relationLabel, enabled = !busy) { relationLabel = it }
                    Field("Source / checkpoint", source, { source = it }, single = false, enabled = !busy)
                    Text("Links keep the same item identity. Ownership and quantity stay unchanged.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                "research" -> {
                    Choices("Evidence category", listOf("Confirmed", "Possible use", "Limitation", "Correction"), discoveryType, enabled = !busy) { discoveryType = it }
                    Field("What was discovered?", discoveryText, { discoveryText = it }, single = false, enabled = !busy)
                    Field("Source / roll / scene", discoverySource, { discoverySource = it }, single = false, enabled = !busy)
                }
                "event" -> events.firstOrNull { it.commandId == eventId }?.let { entry ->
                    RecordValue("Change", entry.kind); Text(entry.detail); RecordValue("Evidence", entry.evidenceStatus); RecordValue("Source", entry.source)
                    RecordValue("Revision", "${entry.priorRevision} → ${entry.resultingRevision}")
                }
            }
        }
    }
}

@Composable internal fun RecordValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
