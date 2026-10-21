package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Composable internal fun PartyEquipmentV2(repository: CampaignLedgerRepository, memberId: String, openInventory: () -> Unit) {
    val items by repository.items.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    val held = placements.filter { it.holderProfileId == memberId && it.state !in setOf("Consumed", "Sold", "Lost") }
    var moveOpen by rememberSaveable(memberId) { mutableStateOf(false) }
    var itemId by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    var quantityId by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    var exchangeOpen by rememberSaveable(memberId) { mutableStateOf(false) }
    PlayerHeading("Equipment")
    Text("One persistent inventory item keeps its identity when given, taken, equipped, installed or stored. Moving an item never duplicates it or assumes a quantity.",
        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    if (profiles.none { it.id == memberId }) Text("Save this character's sourced status before assigning equipment.", color = RebuildPalette.Ice)
    PlayerButton(onClick = { itemId = null; moveOpen = true }, enabled = profiles.any { it.id == memberId }) { Text("Give / take / move item") }
    PlayerOutlinedButton(onClick = { exchangeOpen = true }, enabled = profiles.any { it.id == memberId }) { Text("Item & currency exchange") }
    PlayerTextButton(onClick = openInventory) { Text("Open full inventory") }
    if (held.isEmpty()) PlayerEmpty("No confirmed held items", "Older item owner notes remain in Inventory. Assigning an existing item records its structured possession without creating another copy.")
    held.forEach { placement ->
        val item = items.firstOrNull { it.id == placement.itemId }
        PlayerCard(onClick = { itemId = placement.itemId; moveOpen = true }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LedgerIcon(item?.imageBase64, Modifier.size(48.dp), "${item?.title ?: "Inventory item"} artwork")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item?.title ?: "Item not in current inventory view", style = MaterialTheme.typography.titleMedium)
                    Text("${placement.state} · quantity ${item?.quantity ?: "unknown"}", color = RebuildPalette.Ice)
                    placement.containerItemId?.let { id -> Text("Container: ${items.firstOrNull { it.id == id }?.title ?: id}", color = RebuildPalette.Muted) }
                    placement.installedProjectId?.let { Text("Installed project: $it", color = RebuildPalette.Muted) }
                    Text("Item revision ${item?.revision ?: "?"} · placement ${placement.revision}", color = RebuildPalette.Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item != null) PlayerTextButton(onClick = { quantityId = item.id }) { Text("Record gained / spent quantity") }
        }
    }
    if (exchangeOpen) PartyExchangeV21(repository, memberId, onDismiss = { exchangeOpen = false })
    quantityId?.let { id -> PartyQuantityEditorV21(repository, id, onDismiss = { quantityId = null }) }
    if (moveOpen) PartyEquipmentMoveV2(repository, memberId, itemId, onDismiss = { moveOpen = false })
}

@Composable private fun PartyEquipmentMoveV2(repository: CampaignLedgerRepository, defaultHolder: String,
    initialItemId: String?, onDismiss: () -> Unit) {
    val items by repository.items.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    val projects by repository.records.projects.collectAsState(initial = emptyList())
    var selected by rememberSaveable { mutableStateOf(initialItemId) }
    var query by rememberSaveable { mutableStateOf("") }
    val currentItem = items.firstOrNull { it.id == selected }
    val currentPlacement = placements.firstOrNull { it.itemId == selected }
    val snapshotItemState = rememberUiDraftText()
    var snapshotItemJson by snapshotItemState
    var snapshotPlacementJson by rememberSaveable { mutableStateOf<String?>(null) }
    var holder by rememberSaveable { mutableStateOf(defaultHolder) }
    var state by rememberSaveable { mutableStateOf("Carried") }
    var container by rememberSaveable { mutableStateOf<String?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf("") }
    var commandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val actions = rememberLocalRecordActions()
    val json = remember { Json { encodeDefaults = true } }
    fun select(item: LedgerItem) {
        val placement = placements.firstOrNull { it.itemId == item.id }
        selected = item.id; snapshotItemJson = json.encodeToString(item)
        snapshotPlacementJson = placement?.let { json.encodeToString(it) }
        holder = placement?.holderProfileId ?: defaultHolder
        state = placement?.state ?: "Carried"
        container = placement?.containerItemId; project = placement?.installedProjectId
        source = ""; commandId = UUID.randomUUID().toString()
    }
    LaunchedEffect(currentItem?.id) {
        if (snapshotItemState.canSave && snapshotItemJson == null && currentItem != null) select(currentItem)
    }
    val snapshot = snapshotItemJson?.let { runCatching { json.decodeFromString<LedgerItem>(it) }.getOrNull() }
    val placementSnapshot = snapshotPlacementJson?.let { json.decodeFromString<ItemPlacement>(it) }
    val stale = snapshot != null && (currentItem?.revision != snapshot.revision || currentPlacement?.revision != placementSnapshot?.revision)
    val dirty = snapshot != null && (holder != placementSnapshot?.holderProfileId || state != placementSnapshot?.state ||
        container != placementSnapshot?.containerItemId || project != placementSnapshot?.installedProjectId || source.isNotEmpty())
    PlayerSheet("Move existing equipment", onDismiss, dirty = dirty, actions = {
        PlayerButton(onClick = { actions.run {
            val item = snapshot ?: error("Select an inventory item.")
            val receipt = repository.party.moveItem(ItemMoveCommand(itemId = item.id,
                expectedItemRevision = item.revision, expectedPlacementRevision = placementSnapshot?.revision,
                holderProfileId = holder.takeIf(String::isNotBlank), containerItemId = container,
                installedProjectId = project, state = state, source = source.trim(), commandId = commandId))
            onDismiss()
            "Equipment move saved; receipt ${receipt.commandId}."
        } }, enabled = !actions.busy && snapshotItemState.canSave && snapshot != null && !stale && source.isNotBlank() &&
            (holder.isBlank() || profiles.any { it.id == holder })) { Text("Commit equipment move") }
    }) {
        if (!snapshotItemState.canSave) Text(snapshotItemState.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        if (snapshot == null) {
            PlayerSearch(query, { query = it }, "Find an existing inventory item")
            val filtered = items.filter { it.itemState !in setOf("Consumed", "Sold", "Lost") && it.quantity != 0 && (query.isBlank() || it.title.contains(query, true)) }
            if (filtered.isEmpty()) Text("No matching item. Add an acquired item through Inventory first.", color = RebuildPalette.Muted)
            filtered.take(80).forEach { item ->
                val placement = placements.firstOrNull { it.itemId == item.id }
                PlayerEntry(item.title, "${partyMemberName(placement?.holderProfileId)} · ${placement?.state ?: item.itemState} · quantity ${item.quantity ?: "unknown"}",
                    "Core", onClick = { select(item) })
            }
            if (filtered.size > 80) Text("Refine the search to show more matching items.", color = RebuildPalette.Muted)
        } else {
            Text(snapshot.title, style = MaterialTheme.typography.titleLarge)
            Text("Current holder: ${placementSnapshot?.holderProfileId?.let(::partyMemberName) ?: snapshot.owner}\nCurrent state: ${placementSnapshot?.state ?: snapshot.itemState}\nQuantity: ${snapshot.quantity ?: "unknown"} · item ID: ${snapshot.id}",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (stale) Text("This item or its possession changed. Close and reopen to review the latest revision.", color = MaterialTheme.colorScheme.error)
            PartySelection("Destination holder", partyMemberName(holder.takeIf(String::isNotBlank)),
                listOf("" to "Unknown holder") + profiles.map { it.id to it.name.ifBlank { partyMemberName(it.id) } }) { holder = it }
            PartySelection("Equipment state", state, listOf("Carried", "Equipped", "Installed", "Stored").map { it to it }) {
                state = it
                if (it != "Installed") project = null
            }
            PartySelection("Container", items.firstOrNull { it.id == container }?.title ?: "No container",
                listOf("" to "No container") + items.filter { it.id != snapshot.id && it.itemState !in setOf("Consumed", "Sold", "Lost") && it.quantity != 0 }.map { it.id to it.title }) { container = it.ifBlank { null }; if (container != null) project = null }
            if (state == "Installed") PartySelection("Attach to invention", projects.firstOrNull { it.id == project }?.title ?: "No invention",
                listOf("" to "No invention") + projects.map { it.id to it.title }) { project = it.ifBlank { null }; if (project != null) container = null }
            Text("Review: ${snapshot.title} → ${partyMemberName(holder.takeIf(String::isNotBlank))} · $state${container?.let { " · in ${items.firstOrNull { item -> item.id == it }?.title ?: it}" }.orEmpty()}${project?.let { " · attached to ${projects.firstOrNull { row -> row.id == it }?.title ?: it}" }.orEmpty()}.",
                color = RebuildPalette.Ice)
            Field("Confirmed transfer / equipment event source", source, { source = it }, single = false, enabled = !actions.busy)
            Text("The entire existing item entry moves. Unknown quantities stay unknown. Containers cannot cycle; container contents must be reconciled before changing their holder. A revision conflict saves nothing.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            PlayerTextButton(onClick = { snapshotItemJson = null; snapshotPlacementJson = null; selected = null }) { Text("Choose a different item") }
        }
        LocalRecordStatus(actions.message, actions.busy)
    }
}

@Composable internal fun PartySelection(label: String, selected: String, options: List<Pair<String, String>>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Muted)
        Box {
            PlayerOutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$selected ▾") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { choose(id); expanded = false }) }
            }
        }
    }
}

@Composable internal fun PartyHistoryV2(repository: CampaignLedgerRepository, memberId: String) {
    val audit by repository.records.audit.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val wallets by repository.party.wallets.collectAsState(initial = emptyList())
    val walletIds = wallets.filter { it.holderProfileId == memberId }.map { it.id }.toSet()
    val itemIds = placements.filter { it.holderProfileId == memberId }.map { it.itemId }.toSet()
    val historyJson = remember { Json { ignoreUnknownKeys = true } }
    val rows = audit.filter { row ->
        row.recordId == memberId || row.recordId.startsWith("$memberId:") || (row.recordType == "wallet" && row.recordId in walletIds) ||
            (row.recordType == "item-placement" && (row.recordId in itemIds ||
                listOfNotNull(row.beforeJson, row.afterJson).any { snapshot ->
                    runCatching { historyJson.decodeFromString<ItemPlacement>(snapshot).holderProfileId == memberId }.getOrDefault(false)
                }))
    }
    var historyLimit by rememberSaveable(memberId) { mutableIntStateOf(40) }
    PlayerHeading("Sourced history")
    Text("Earlier values, sources and command receipts stay available after a sheet edit or equipment transaction.",
        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    if (rows.isEmpty()) PlayerEmpty("No local history yet", "Opening a sheet never seeds statistics or imports campaign review documents.")
    if (rows.isNotEmpty()) Text("${rows.size} changes · showing ${minOf(rows.size, historyLimit)}", color = RebuildPalette.Muted,
        style = MaterialTheme.typography.bodySmall)
    rows.take(historyLimit).forEach { row ->
        PlayerDisclosure("${row.kind} · revision ${row.resultingRevision}") {
            RecordValue("Record", "${row.recordType} · ${row.recordId}")
            RecordValue("Source", row.source)
            RecordValue("Revision", "${row.priorRevision} → ${row.resultingRevision}")
            RecordValue("Command receipt ID", row.commandId)
            row.beforeJson?.let { RecordValue("Earlier snapshot", it) }
            row.afterJson?.let { RecordValue("Saved snapshot", it) }
        }
    }
    if (rows.size > historyLimit) PlayerTextButton(onClick = { historyLimit += 40 }) { Text("Show more party history") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PartyQuantityEditorV21(repository: CampaignLedgerRepository, itemId: String, onDismiss: () -> Unit) {
    val items by repository.items.collectAsState(initial = emptyList())
    val current = items.firstOrNull { it.id == itemId }
    val json = remember { Json { encodeDefaults = true } }
    val snapshotState = rememberUiDraftText()
    var snapshotJson by snapshotState
    LaunchedEffect(current?.id) { if (snapshotState.canSave && snapshotJson == null && current != null) snapshotJson = json.encodeToString(current) }
    val snapshot = snapshotJson?.let { runCatching { json.decodeFromString<LedgerItem>(it) }.getOrNull() }
    var mode by rememberSaveable(itemId) { mutableStateOf("change") }
    var amount by rememberSaveable(itemId) { mutableStateOf("") }
    var source by rememberSaveable(itemId) { mutableStateOf("") }
    val commandId = rememberSaveable(itemId) { UUID.randomUUID().toString() }
    val actions = rememberLocalRecordActions()
    val stale = current?.revision != snapshot?.revision
    val valid = amount.isBlank() && mode == "confirm" || amount.toIntOrNull() != null
    PlayerSheet("Record item quantity", onDismiss, dirty = amount.isNotBlank() || source.isNotBlank(), actions = {
        PlayerButton(onClick = { actions.run {
            val original = snapshot ?: error("Item unavailable.")
            val receipt = if (mode == "confirm") repository.party.confirmQuantity(QuantityConfirmationCommand(
                original.id, original.revision, amount.trim().takeIf(String::isNotEmpty)?.toInt(), source.trim(), commandId))
            else repository.party.changeQuantity(QuantityCommand(original.id, original.revision,
                amount.trim().toInt(), source.trim(), commandId))
            onDismiss(); "Quantity recorded; receipt ${receipt.commandId}."
        } }, enabled = !actions.busy && snapshotState.canSave && snapshot != null && !stale && valid && source.isNotBlank() &&
            (mode == "confirm" || snapshot.quantity != null && amount.toIntOrNull() != 0) &&
            snapshot.itemState !in setOf("Consumed", "Sold", "Lost", "Installed")) { Text("Save quantity record") }
    }) {
        if (!snapshotState.canSave) Text(snapshotState.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Text(snapshot?.title ?: "Loading item…", style = MaterialTheme.typography.titleLarge)
        Text("Current quantity: ${snapshot?.quantity ?: "unknown"}", color = RebuildPalette.Gold)
        if (snapshot?.itemState == "Installed") Text("Remove the installed item before recording an expenditure or count correction.", color = RebuildPalette.Ice)
        if (stale) Text("The item changed. Close and reopen before saving.", color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerFilterChip(mode == "change", onClick = { mode = "change"; amount = "" }, label = { Text("Gain / spend") })
            PlayerFilterChip(mode == "confirm", onClick = { mode = "confirm"; amount = snapshot?.quantity?.toString().orEmpty() }, label = { Text("Confirmed count") })
        }
        if (mode == "change") {
            Text("Use a positive gain or a negative expenditure. Unknown quantities cannot be spent; record a sourced count first. Installed items must be removed before expenditure.", color = RebuildPalette.Muted)
            Field("Quantity change · + gained / − spent", amount, { amount = it })
        } else {
            Text("Record a sourced opening count or correction. Blank explicitly means unknown. A zero count records consumption; it never deletes the item or its history.", color = RebuildPalette.Muted)
            Field("Confirmed total quantity · blank = unknown", amount, { amount = it })
        }
        if (!valid && amount.isNotBlank()) Text("Use a whole number.", color = MaterialTheme.colorScheme.error)
        Field("Quantity event source (required)", source, { source = it }, single = false, enabled = !actions.busy)
        LocalRecordStatus(actions.message, actions.busy)
    }
}
