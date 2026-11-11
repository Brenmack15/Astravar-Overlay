package com.yazsras.astravar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Presentation only: stable identities, revisions, events and transactions remain repository-owned. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampaignLedgerPage(repository: CampaignLedgerRepository, openReferences: (() -> Unit)? = null) =
    CampaignLedgerPage(repository, initialItemId = null, openReferences = openReferences)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampaignLedgerPage(repository: CampaignLedgerRepository, initialItemId: String?, openReferences: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records = repository.records
    val inventoryState by remember(repository, initialItemId) { (if (initialItemId == null) repository.items else repository.itemsIncludingArchived).map { true to it } }.collectAsState(initial = false to emptyList())
    val items = inventoryState.second
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val itemDetails by records.itemDetails.collectAsState(initial = emptyList())
    val itemProperties by records.itemProperties.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val projects by records.projects.collectAsState(initial = emptyList())
    val entities by records.entities.collectAsState(initial = emptyList())
    val companions by records.companionStates.collectAsState(initial = emptyList())
    val profiles by records.characterProfiles.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var mode by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedInitialItem by rememberSaveable { mutableStateOf<String?>(null) }
    // A saved Activity state can predate a successful Room commit. Replaying that draft must return its receipt.
    var profileCommandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
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
        profileCommandId = UUID.randomUUID().toString()
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
    LaunchedEffect(initialItemId, inventoryState.first, items) {
        if (initialItemId != null && consumedInitialItem != initialItemId && inventoryState.first) {
            consumedInitialItem = initialItemId
            val target = items.firstOrNull { it.id == initialItemId }
            if (target == null) message = "This item is no longer available. No other item was opened."
            else { load(target); mode = "detail" }
        }
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
            knownProperties = known, possibleUses = potential, limitations = limitations, source = source.ifBlank { "Local inventory edit" },
            notionPageId = notionId.trim().ifEmpty { null }, imageBase64 = iconData, archived = archived)
        execute {
            require(raw.isEmpty() || raw.toIntOrNull() != null) { "Quantity must be a whole number or blank." }
            val saved = if (before == null) repository.create(draft, profileCommandId) else repository.update(before.id, before.revision, draft, draft.source, profileCommandId)
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
    val expandedText = LocalDensity.current.fontScale >= 1.5f
    Page {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (expandedText) 1 else 2) {
            Column(Modifier.weight(1f)) {
                Text("Inventory", style = MaterialTheme.typography.headlineMedium)
                Text("Equipment, treasures & discoveries", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            PlayerButton(onClick = { load(null); mode = "create" }, enabled = !busy,
                modifier = Modifier.align(Alignment.CenterVertically)) { Text("Add Item") }
        }
        FlowRow(Modifier.fillMaxWidth().background(RebuildPalette.Panel, RoundedCornerShape(12.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (expandedText) 1 else 3) {
            LedgerSummary("Items", items.size.toString(), Modifier.weight(1f), horizontal = expandedText)
            LedgerSummary("Equipped", items.count { item -> (placements.firstOrNull { it.itemId == item.id }?.state ?: item.itemState) == "Equipped" }.toString(), Modifier.weight(1f), horizontal = expandedText)
            LedgerSummary("Categories", items.map { it.category }.distinct().size.toString(), Modifier.weight(1f), horizontal = expandedText)
        }
        PlayerSearch(query, { query = it }, "Search inventory")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All") + items.map { it.category }.distinct().sorted()).forEach { name ->
                PlayerFilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
            }
        }
        if (message.isNotBlank() && mode.isBlank()) Text(message, color = Frost, style = MaterialTheme.typography.bodySmall)
        if (visibleItems.isEmpty()) PlayerEmpty(
            if (items.isEmpty()) "Your inventory is ready" else "No matching items",
            if (items.isEmpty()) "Add equipment, materials or a discovery." else "Try another name or category.", "Core")
        Column(Modifier.fillMaxWidth()) {
            visibleItems.forEach { item ->
                val placement = placements.firstOrNull { it.itemId == item.id }
                val holderName = if (placement == null) null else profiles.firstOrNull { it.id == placement.holderProfileId }?.name ?: "Unknown holder"
                val state = placement?.state ?: item.itemState
                Row(Modifier.fillMaxWidth().heightIn(min = 88.dp)
                    .clickable(role = Role.Button) { load(item); mode = "detail" }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LedgerCategoryArtwork(item, Modifier.size(44.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(item.category, state.takeUnless { it == "Unknown" }).filterNotNull().joinToString(" · "),
                            color = if (state == "Equipped") RebuildPalette.Ice else Muted, style = MaterialTheme.typography.bodySmall)
                        (holderName ?: item.owner.takeUnless { it == "Unknown" })?.let {
                            Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(item.quantity?.let { "×$it" } ?: "Unknown", color = RebuildPalette.Silver,
                            style = if (item.quantity != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall)
                        Text("quantity", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .4f))
            }
        }
        PlayerDisclosure("Inventory records & backup") {
            if (openReferences != null) PlayerEntry("Inventory source archive", "Original descriptions and acquisition history", "History", openReferences)
            PlayerTextButton(onClick = { exportPicker.launch("astravar-campaign-items.json") }, enabled = !busy) { Text("Export JSON") }
        }
    }
    if (mode == "move" && selectedId != null) {
        PartyEquipmentMoveV2(repository, structuredPlacement?.holderProfileId.orEmpty(), selectedId,
            onDismiss = { mode = "detail" })
    } else if (mode == "quantity" && selectedId != null) {
        PartyQuantityEditorV21(repository, selectedId!!, onDismiss = { mode = "detail" })
    } else if (mode.isNotBlank()) {
        val sheetTitle = when(mode) { "create" -> "Add Item"; "edit" -> "Edit item"; "handling" -> "Weight & handling"; "property" -> if (loadedProperty == null) "Add property" else "Edit property"; "link" -> "Connect record"; "research" -> "Add discovery"; "event" -> "History entry"; else -> selected?.title ?: "Item" }
        PlayerSheet(sheetTitle, onDismiss = { if (!busy) mode = "" }, dirty = dirty, actions = {
            if (mode in setOf("create", "edit")) PlayerButton(onClick = ::saveProfile, enabled = !busy && profileDraftValid && !stale && title.isNotBlank()) { Text(if (mode == "create") "Save new item" else "Save item changes") }
            if (mode == "detail") PlayerButton(onClick = { selected?.let(::load); mode = "edit" }, enabled = !busy) { Text("Edit item") }
            if (mode == "handling") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val before = loadedDetails; val raw = weight.trim(); val provenance = source.ifBlank { "Local inventory edit" }
                val model = before?.copy(weightGrams = raw.toLongOrNull(), notes = notes) ?: ItemDetails(itemId = item.id, weightGrams = raw.toLongOrNull(), notes = notes)
                execute {
                    require(raw.isEmpty() || raw.toLongOrNull()?.let { it >= 0 } == true) { "Weight must be nonnegative grams or unknown." }
                    records.saveItemDetails(model, before?.revision, provenance); mode = "detail"; message = "Weight and handling saved"
                }
            }, enabled = !busy && itemDraftValid && detailsDraftValid && !staleDetails) { Text("Save weight and handling") }
            if (mode == "property") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val before = loadedProperty; val provenance = source.ifBlank { "Local inventory edit" }
                val model = before?.copy(label = propertyLabel, value = propertyValue, evidenceStatus = propertyEvidence) ?: ItemProperty(itemId = item.id, label = propertyLabel, value = propertyValue, evidenceStatus = propertyEvidence)
                execute { records.saveItemProperty(model, before?.revision, provenance); mode = "detail"; message = "Property saved" }
            }, enabled = !busy && itemDraftValid && propertyDraftValid && propertyLabel.isNotBlank() && propertyValue.isNotBlank()) { Text(if (loadedProperty == null) "Add item property" else "Save item property") }
            if (mode == "link") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val target = relatedId ?: return@PlayerButton; val provenance = source.ifBlank { "Local inventory edit" }
                val link = CampaignLink(fromType = "item", fromId = item.id, toType = relatedType, toId = target, label = relationLabel)
                execute { records.saveLink(link, null, provenance); relatedId = null; mode = "detail"; message = "Record connected" }
            }, enabled = !busy && itemDraftValid && relatedId != null) { Text("Link this existing item") }
            if (mode == "research") PlayerButton(onClick = {
                val item = selected ?: return@PlayerButton; val kind = discoveryType; val text = discoveryText; val provenance = discoverySource.ifBlank { "Local discovery entry" }
                execute { val saved = repository.discover(item.id, item.revision, kind, text, provenance); load(saved); discoveryText = ""; discoverySource = ""; mode = "detail"; message = "Discovery saved" }
            }, enabled = !busy && itemDraftValid && discoveryText.isNotBlank()) { Text("Append discovery") }
        }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            draftErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (message.isNotBlank()) Text(message, color = Frost, style = MaterialTheme.typography.bodySmall)
            when(mode) {
                "create", "edit" -> {
                    if (stale) Text("This item changed elsewhere. Your draft is preserved. Close and reopen it to load the latest revision.", color = Frost)
                    Field("Item name", title, { title = it }, enabled = !busy)
                    Choices("Category", listOf("Armor", "Weapon", "Tool", "Component", "Material", "Magic Item", "Consumable", "Treasure", "Other"), category, enabled = !busy) { category = it }
                    Field("Quantity", quantity, { quantity = it }, enabled = !busy && structuredPlacement == null)
                    Text(if (structuredPlacement == null) "Blank quantity means unknown" else "Use Equip or transfer and Record quantity from this item to update assigned equipment.", style = MaterialTheme.typography.labelSmall, color = Muted)
                    Field("Description / appearance", description, { description = it }, single = false, enabled = !busy)
                    PlayerDisclosure("Ownership & location") {
                        Field("Owner / holder label", owner, { owner = it }, enabled = !busy && structuredPlacement == null)
                        Choices("Current state", listOf("Unknown", "Carried", "Equipped", "Installed", "Stored", "Consumed", "Sold", "Lost"), itemState, enabled = !busy && structuredPlacement == null) { itemState = it }
                        Field("Specific location / container (optional)", location, { location = it }, enabled = !busy && structuredPlacement == null)
                    }
                    PlayerDisclosure("Properties & research notes") {
                        Field("Known properties / legacy notes", known, { known = it }, single = false, enabled = !busy)
                        Field("Possible uses (not active mechanics)", potential, { potential = it }, single = false, enabled = !busy)
                        Field("Known limits", limitations, { limitations = it }, single = false, enabled = !busy)
                    }
                    PlayerDisclosure("Artwork") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LedgerIcon(iconData, Modifier.size(64.dp))
                            PlayerTextButton(onClick = { imageTarget = selectedId; imageToken = draftToken; imagePicker.launch("image/*") }, enabled = !busy) { Text("Choose item image") }
                        }
                        if (iconData != null) PlayerTextButton(onClick = { iconData = null }, enabled = !busy) { Text("Remove image") }
                    }
                    PlayerDisclosure("Sources & advanced details") {
                        Choices("Evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), evidence, enabled = !busy) { evidence = it }
                        Field("Notion item page ID or URL (optional)", notionId, { notionId = it }, enabled = !busy)
                        Field("Source / checkpoint (optional)", source, { source = it }, single = false, enabled = !busy)
                        Text("Local edits are attributed automatically. Existing source text is preserved.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (loadedItem != null) PlayerDisclosure("Archive") { Toggle("Archive this entry (keep history)", archived, enabled = !busy) { archived = it } }
                }
                "detail" -> selected?.let { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LedgerCategoryArtwork(item, Modifier.size(64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.category, color = RebuildPalette.Gold, style = MaterialTheme.typography.labelLarge)
                            Text(item.quantity?.let { "Quantity $it" } ?: "Quantity not set", style = MaterialTheme.typography.titleMedium)
                            Text(structuredPlacement?.state ?: item.itemState.takeUnless { it == "Unknown" } ?: "Status not set", color = Muted)
                        }
                    }
                    Text("Overview", style = MaterialTheme.typography.titleMedium)
                    if (item.description.isNotBlank()) ReforgeRichText(item.description)
                    val holderName = if (structuredPlacement == null) null else profiles.firstOrNull { it.id == structuredPlacement.holderProfileId }?.name ?: "Unknown holder"
                    Row(Modifier.fillMaxWidth().background(RebuildPalette.Elevated, RoundedCornerShape(10.dp)).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { RecordValue("Held by", holderName ?: item.owner.takeUnless { it == "Unknown" } ?: "Not assigned") }
                        Column(Modifier.weight(1f)) { RecordValue("Location", structuredPlacement?.containerItemId?.let { id -> items.firstOrNull { it.id == id }?.title }
                            ?: item.location.takeUnless { it == "Unknown" } ?: "Not set") }
                    }
                    PlayerButton(onClick = { mode = "move" }, modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && item.itemState !in setOf("Consumed", "Sold", "Lost") && item.quantity != 0) { Text("Equip or transfer") }
                    if (structuredPlacement != null) PlayerTextButton(onClick = { mode = "quantity" }) { Text("Record quantity") }
                    if (item.knownProperties.isNotBlank()) {
                        Text("Properties", style = MaterialTheme.typography.titleMedium)
                        ReforgeRichText(item.knownProperties)
                    }
                    if (item.limitations.isNotBlank()) RecordValue("Limits", item.limitations)
                    val value = itemDetails.firstOrNull { it.itemId == item.id }
                    if (value?.weightGrams != null) RecordValue("Weight per item", "${value.weightGrams} g")
                    if (value?.notes?.isNotBlank() == true) ReforgeRichText(value.notes)
                    itemProperties.filter { it.itemId == item.id }.forEach { property ->
                        PlayerTextButton(onClick = { propertySnapshot = Json.encodeToString(property); propertyLabel = property.label; propertyValue = property.value; propertyEvidence = property.evidenceStatus; source = property.source; baselineSource = source; mode = "property" }) {
                            Column(Modifier.fillMaxWidth()) { Text(property.label); Text(property.value, color = Muted, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    PlayerDisclosure("Item details & properties") {
                        PlayerTextButton(onClick = { detailsSnapshot = value?.let { Json.encodeToString(it) }; weight = value?.weightGrams?.toString().orEmpty(); notes = value?.notes.orEmpty(); source = item.source; baselineSource = source; mode = "handling" }) { Text("Edit weight & handling") }
                        PlayerTextButton(onClick = { propertySnapshot = null; propertyLabel = ""; propertyValue = ""; propertyEvidence = "Unverified"; source = item.source; baselineSource = source; mode = "property" }) { Text("Add property") }
                    }
                    PlayerDisclosure("Sources & record details") {
                        RecordValue("Evidence", item.evidenceStatus)
                        if (item.source.isNotBlank()) ReforgeRichText(item.source)
                        RecordValue("Record identity", item.id)
                        item.notionPageId?.let { RecordValue("Linked source", it) }
                        RecordValue("Saved revision", item.revision.toString())
                    }
                    PlayerDisclosure("Research") {
                        if (item.possibleUses.isNotBlank()) RecordValue("Possible uses", item.possibleUses)
                        val discoveries = events.filter { it.kind == "DISCOVER" || it.kind.contains("DISCOVER", true) }
                        if (discoveries.isEmpty()) Text("No discoveries recorded", color = Muted)
                        discoveries.take(5).forEach { ReforgeRichText(it.detail) }
                        PlayerTextButton(onClick = { discoveryText = ""; discoverySource = ""; mode = "research" }) { Text("Add discovery") }
                    }
                    PlayerDisclosure("Connected Records") {
                        val related = links.filter { (it.fromType == "item" && it.fromId == item.id) || (it.toType == "item" && it.toId == item.id) }
                        if (related.isEmpty()) Text("No connected records", color = Muted)
                        related.forEach { link ->
                            val forward = link.fromType == "item" && link.fromId == item.id
                            val type = if (forward) link.toType else link.fromType; val id = if (forward) link.toId else link.fromId
                            val name = when(type) {
                                "companion" -> companions.firstOrNull { it.companionId == id }?.name
                                    ?: profiles.firstOrNull { it.id == id }?.name
                                "character-profile" -> profiles.firstOrNull { it.id == id }?.name
                                "project" -> projects.firstOrNull { it.id == id }?.title
                                "connection" -> entities.firstOrNull { it.id == id }?.name
                                else -> items.firstOrNull { it.id == id }?.title
                            } ?: "Missing linked record ($id)"
                            RecordValue(link.label, name)
                        }
                        PlayerTextButton(onClick = { relatedType = "companion"; relatedId = null; relationLabel = "Equipment"; source = item.source; baselineSource = source; mode = "link" }) { Text("Connect record") }
                    }
                    PlayerDisclosure("History") {
                        if (events.isEmpty()) Text("No history recorded", color = Muted)
                        events.take(historyLimit).forEach { entry ->
                            PlayerTextButton(onClick = { eventId = entry.commandId; mode = "event" }) { Column(Modifier.fillMaxWidth()) { Text(entry.kind.replace('_', ' '), color = Frost); Text(entry.detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                        }
                        val propertyIds = itemProperties.filter { it.itemId == item.id }.map { it.id }.toSet()
                        val linkIds = links.filter { (it.fromType == "item" && it.fromId == item.id) || (it.toType == "item" && it.toId == item.id) }.map { it.id }.toSet()
                        val relatedAudit = audit.filter { it.recordId == item.id || it.recordId in propertyIds || it.recordId in linkIds }
                        relatedAudit.take(historyLimit).forEach { entry -> RecordValue(entry.kind, entry.source) }
                        if (events.size > historyLimit || relatedAudit.size > historyLimit) PlayerTextButton(onClick = { historyLimit += 40 }) { Text("Show more item history") }
                        Text(item.id, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                "handling" -> {
                    if (staleDetails) Text("These details changed elsewhere. Your draft is preserved; reopen the item before saving.", color = Frost)
                    Field("Weight per item in grams (blank = unknown)", weight, { weight = it }, enabled = !busy)
                    Field("Property / handling notes", notes, { notes = it }, single = false, enabled = !busy)
                    PlayerDisclosure("Sources & advanced details") {
                        Field("Source / checkpoint (optional)", source, { source = it }, single = false, enabled = !busy)
                        Text("Local edits are attributed automatically. Existing source text is preserved.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                "property" -> {
                    Field("Property name", propertyLabel, { propertyLabel = it }, enabled = !busy)
                    Field("Property value / exact rule reference", propertyValue, { propertyValue = it }, single = false, enabled = !busy)
                    Choices("Property evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), propertyEvidence, enabled = !busy) { propertyEvidence = it }
                    PlayerDisclosure("Sources & advanced details") {
                        Field("Source / checkpoint (optional)", source, { source = it }, single = false, enabled = !busy)
                        Text("Local edits are attributed automatically. Existing source text is preserved.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                "link" -> {
                    Choices("Related record type", listOf("companion", "character-profile", "project", "connection"), relatedType,
                        display = { when(it) { "companion" -> "Companion"; "character-profile" -> "Character"; "project" -> "Invention"; else -> "Person / connection" } },
                        enabled = !busy) { relatedType = it; relatedId = null }
                    val targets = when(relatedType) {
                        "companion" -> companions.map { it.companionId to it.name }
                        "character-profile" -> profiles.map { it.id to it.name }
                        "project" -> projects.map { it.id to it.title }
                        else -> entities.map { it.id to it.name }
                    }
                    if (targets.isEmpty()) Text("Create the related record first", color = Muted)
                    Choices("Related record", targets.map { it.first }, relatedId, display = { id -> targets.firstOrNull { it.first == id }?.second ?: "Choose record" }, enabled = !busy) { relatedId = it }
                    Choices("Relationship", listOf("Owned by", "Held by", "Equipment", "Installed component", "Material requirement", "Research subject", "Origin"), relationLabel, enabled = !busy) { relationLabel = it }
                    PlayerDisclosure("Sources & advanced details") {
                        Field("Source / checkpoint (optional)", source, { source = it }, single = false, enabled = !busy)
                        Text("Local edits are attributed automatically. Existing source text is preserved.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Links keep the same item identity. Ownership and quantity stay unchanged.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                "research" -> {
                    Choices("Evidence category", listOf("Confirmed", "Possible use", "Limitation", "Correction"), discoveryType, enabled = !busy) { discoveryType = it }
                    Field("What was discovered?", discoveryText, { discoveryText = it }, single = false, enabled = !busy)
                    PlayerDisclosure("Source details (optional)") { Field("Source / roll / scene", discoverySource, { discoverySource = it }, single = false, enabled = !busy) }
                }
                "event" -> events.firstOrNull { it.commandId == eventId }?.let { entry ->
                    RecordValue("Change", entry.kind); Text(entry.detail); RecordValue("Evidence", entry.evidenceStatus); RecordValue("Source", entry.source)
                    RecordValue("Revision", "${entry.priorRevision} → ${entry.resultingRevision}")
                }
            }
        }
    }
}

@Composable private fun LedgerSummary(label: String, value: String, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val valueText: @Composable () -> Unit = {
        Text(value, color = RebuildPalette.Gold, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    val labelText: @Composable () -> Unit = { Text(label, color = Muted, style = MaterialTheme.typography.labelMedium) }
    if (horizontal) Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        labelText()
        valueText()
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        valueText()
        labelText()
    }
}

@Composable internal fun LedgerCategoryArtwork(item: LedgerItem, modifier: Modifier) {
    if (item.imageBase64 != null) LedgerIcon(item.imageBase64, modifier, "${item.title} artwork")
    else {
        val (icon, tint) = when (item.category.lowercase()) {
            "weapon" -> "Weapon" to Color(0xFFEDAF91)
            "armor" -> "Emblem" to RebuildPalette.Ice
            "consumable" -> "Payload" to Color(0xFF9FD3AE)
            "magic item" -> "Radiant" to RebuildPalette.Purple
            "tool", "component" -> "Settings" to RebuildPalette.Gold
            "treasure" -> "Core" to RebuildPalette.GoldBright
            else -> "Core" to RebuildPalette.Muted
        }
        Box(modifier.background(tint.copy(alpha = .10f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            ArcaneIcon(icon, Modifier.size(28.dp), tint)
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
