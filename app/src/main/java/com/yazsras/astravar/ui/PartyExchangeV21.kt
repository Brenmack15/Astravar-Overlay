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

/** One resolved item event and its explicit coin changes form a single, retry-safe Room command. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PartyExchangeV21(repository: CampaignLedgerRepository, memberId: String, onDismiss: () -> Unit) {
    val wallets by repository.party.wallets.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    val json = remember { Json { encodeDefaults = true } }
    var operation by rememberSaveable { mutableStateOf("New item") }
    var walletJson by rememberSaveable { mutableStateOf<String?>(null) }
    val itemSnapshotState = rememberUiDraftText()
    var itemJson by itemSnapshotState
    var placementJson by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("") }
    var quantityDelta by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf(memberId) }
    var placementState by rememberSaveable { mutableStateOf("Carried") }
    var copper by rememberSaveable { mutableStateOf("") }
    var silver by rememberSaveable { mutableStateOf("") }
    var gold by rememberSaveable { mutableStateOf("") }
    var electrum by rememberSaveable { mutableStateOf("") }
    var platinum by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf("") }
    val reviewState = rememberUiDraftText()
    var reviewJson by reviewState
    val receiptState = rememberUiDraftText()
    var receiptJson by receiptState
    val commandId = rememberSaveable { UUID.randomUUID().toString() }
    val actions = rememberLocalRecordActions()
    val memberWallets = wallets.filter { it.holderProfileId == memberId }
    LaunchedEffect(memberWallets.firstOrNull()?.id) {
        if (walletJson == null) memberWallets.firstOrNull()?.let { walletJson = json.encodeToString(it) }
    }
    val wallet = walletJson?.let { json.decodeFromString<PartyWallet>(it) }
    val item = itemJson?.let { runCatching { json.decodeFromString<LedgerItem>(it) }.getOrNull() }
    val placement = placementJson?.let { json.decodeFromString<ItemPlacement>(it) }
    val review = reviewJson?.let { runCatching { json.decodeFromString<PartyExchangeCommand>(it) }.getOrNull() }
    val receipt = receiptJson?.let { runCatching { json.decodeFromString<CommandReceipt>(it) }.getOrNull() }
    val stale = wallet != null && wallets.firstOrNull { it.id == wallet.id }?.revision != wallet.revision ||
        operation != "New item" && item != null && (items.firstOrNull { it.id == item.id }?.revision != item.revision ||
            placements.firstOrNull { it.itemId == item.id }?.revision != placement?.revision)
    fun coins(raw: String): Long = if (raw.isBlank()) 0 else raw.trim().toLongOrNull() ?: error("Coin changes must be whole numbers.")
    fun itemCount(raw: String): Int? = raw.trim().takeIf(String::isNotEmpty)?.let { it.toIntOrNull() ?: error("Quantity must be a whole number or blank for unknown.") }
    fun prepare() {
        try {
            val selectedWallet = wallet ?: error("Add a sourced wallet under Party → Currency first.")
            require(source.isNotBlank()) { "Describe the confirmed exchange source." }
            require(!stale) { "The wallet or item changed. Close and reopen to review current records." }
            val coinChange = CurrencyCommand(selectedWallet.id, selectedWallet.revision,
                coins(copper), coins(silver), coins(gold), source.trim(), electrumDelta = coins(electrum), platinumDelta = coins(platinum))
            val hasCoins = listOf(coinChange.copperDelta, coinChange.silverDelta, coinChange.goldDelta, coinChange.electrumDelta, coinChange.platinumDelta).any { it != 0L }
            var creation = emptyList<LedgerDraft>()
            var quantities = emptyList<QuantityCommand>()
            var moves = emptyList<ItemMoveCommand>()
            when (operation) {
                "New item" -> {
                    require(title.isNotBlank()) { "Name the acquired item." }
                    val quantity = itemCount(count)
                    require(quantity == null || quantity > 0) { "An acquired item needs a positive count, or blank for unknown." }
                    creation = listOf(LedgerDraft(title = title.trim(), owner = partyMemberName(memberId),
                        itemState = "Carried", location = "Actor:$memberId", quantity = quantity,
                        evidenceStatus = "Confirmed", source = source.trim()))
                }
                "Quantity" -> {
                    val original = item ?: error("Choose an existing item.")
                    val delta = quantityDelta.trim().toIntOrNull() ?: error("Enter a positive gain or negative expenditure.")
                    require(delta != 0) { "Enter a non-zero quantity change." }
                    quantities = listOf(QuantityCommand(original.id, original.revision, delta, source.trim()))
                }
                "Move item" -> {
                    val original = item ?: error("Choose an existing item.")
                    moves = listOf(ItemMoveCommand(original.id, original.revision, placement?.revision,
                        holderProfileId = destination, state = placementState, source = source.trim()))
                }
            }
            reviewJson = json.encodeToString(PartyExchangeCommand(moves = moves, quantities = quantities,
                currency = if (hasCoins) listOf(coinChange) else emptyList(), creations = creation,
                source = source.trim(), commandId = commandId,
                creationHolderProfileIds = if (creation.isEmpty()) emptyList() else listOf(memberId)))
            errorMessage = ""
        } catch (e: Exception) { errorMessage = e.message ?: "Review the exchange fields." }
    }
    val dirty = receipt == null && (title.isNotBlank() || count.isNotBlank() || quantityDelta.isNotBlank() ||
        copper.isNotBlank() || silver.isNotBlank() || gold.isNotBlank() || electrum.isNotBlank() || platinum.isNotBlank() || source.isNotBlank() || review != null)
    PlayerSheet("Confirmed item & currency exchange", onDismiss, dirty = dirty, showCancel = dirty, actions = {
        when {
            receipt != null -> PlayerButton(onClick = onDismiss) { Text("Done") }
            review != null -> PlayerButton(onClick = { actions.run {
                val outcome = repository.party.commitExchange(review)
                receiptJson = json.encodeToString(outcome)
                "Exchange committed once."
            } }, enabled = !actions.busy && !stale && itemSnapshotState.canSave && reviewState.canSave && receiptState.canSave) { Text("Commit confirmed exchange") }
            else -> PlayerButton(onClick = ::prepare, enabled = !actions.busy && wallet != null && source.isNotBlank() &&
                itemSnapshotState.canSave && reviewState.canSave && receiptState.canSave && profiles.any { it.id == memberId }) { Text("Review exchange") }
        }
    }) {
        if (receipt != null) {
            Text("Exchange saved", style = MaterialTheme.typography.headlineSmall, color = RebuildPalette.Gold)
            Text("The item and coin changes committed together. Repeating the same command returns its original receipt.", color = RebuildPalette.Ice)
            RecordValue("Receipt ID", receipt.commandId)
            RecordValue("Recorded source", source)
            RecordValue("Immutable outcome", receipt.outcomeJson)
        } else if (review != null) {
            Text("Review before committing", style = MaterialTheme.typography.titleLarge)
            Text("No records have changed yet. This saves every listed change together, or none.", color = RebuildPalette.Ice)
            RecordValue("Wallet", wallet?.name ?: "Unavailable")
            RecordValue("Opening balances", "${wallet?.copper ?: "?"} cp · ${wallet?.silver ?: "?"} sp · ${wallet?.gold ?: "?"} gp · ${wallet?.electrum ?: "?"} ep · ${wallet?.platinum ?: "?"} pp")
            val change = review.currency.firstOrNull()
            RecordValue("Exact coin changes", "${change?.copperDelta ?: 0} cp · ${change?.silverDelta ?: 0} sp · ${change?.goldDelta ?: 0} gp · ${change?.electrumDelta ?: 0} ep · ${change?.platinumDelta ?: 0} pp")
            review.creations.forEach { RecordValue("Acquired item", "${it.title} · quantity ${it.quantity ?: "unknown"} → ${partyMemberName(memberId)} · Carried") }
            review.quantities.forEach { RecordValue("Existing item quantity", "${item?.title ?: it.itemId} · ${item?.quantity ?: "unknown"} with change ${it.delta}") }
            review.moves.forEach { RecordValue("Existing item transfer", "${item?.title ?: it.itemId} → ${partyMemberName(it.holderProfileId)} · ${it.state}") }
            RecordValue("Confirmed source", review.source)
            if (stale) Text("A reviewed record changed. Close and reopen to use its current revision. No exchange has been applied.", color = MaterialTheme.colorScheme.error)
            PlayerTextButton(onClick = { reviewJson = null }) { Text("Edit exchange") }
        } else {
            Text("Use this after the in-game trade or transfer is resolved. Enter the agreed item and coin changes; Astravar never infers a price, conversion or recipient consent.",
                color = RebuildPalette.Muted)
            if (memberWallets.isEmpty()) Text("Add a sourced wallet under Party → Currency first. Unknown denominations cannot be spent.", color = RebuildPalette.Ice)
            PartySelection("Wallet", wallet?.name ?: "No wallet", memberWallets.map { it.id to it.name }) { id ->
                walletJson = memberWallets.firstOrNull { it.id == id }?.let { json.encodeToString(it) }
            }
            wallet?.let { Text("Opening: ${it.copper ?: "?"} cp · ${it.silver ?: "?"} sp · ${it.gold ?: "?"} gp · ${it.electrum ?: "?"} ep · ${it.platinum ?: "?"} pp", color = RebuildPalette.Gold) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("New item", "Quantity", "Move item").forEach { label ->
                    PlayerFilterChip(operation == label, onClick = { operation = label }, label = { Text(label) })
                }
            }
            if (operation == "New item") {
                Field("Acquired item name", title, { title = it })
                Field("Acquired quantity · blank = unknown", count, { count = it })
                Text("A new acquired physical item receives one persistent ID and recorded possession by ${partyMemberName(memberId)}. Use Quantity for an existing stock entry.", color = RebuildPalette.Muted,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                PartySelection("Existing inventory item", item?.title ?: "Choose item", items.filter {
                    it.itemState !in setOf("Consumed", "Sold", "Lost", "Installed") && it.quantity != 0
                }.map { it.id to it.title }) { id ->
                    items.firstOrNull { it.id == id }?.let { chosen ->
                        itemJson = json.encodeToString(chosen)
                        placementJson = placements.firstOrNull { it.itemId == id }?.let { json.encodeToString(it) }
                    }
                }
                if (operation == "Quantity") Field("Existing quantity change · + gain / − spend", quantityDelta, { quantityDelta = it })
                else {
                    PartySelection("Destination holder", partyMemberName(destination), profiles.map { it.id to it.name.ifBlank { partyMemberName(it.id) } }) { destination = it }
                    PartySelection("Destination state", placementState, listOf("Carried", "Equipped", "Stored").map { it to it }) { placementState = it }
                }
            }
            Text("Coin changes: positive = gained; negative = spent; blank = no change. Each denomination is independent.", color = RebuildPalette.Muted)
            Field("Exchange copper change", copper, { copper = it })
            Field("Exchange silver change", silver, { silver = it })
            Field("Exchange gold change", gold, { gold = it })
            Field("Exchange electrum change", electrum, { electrum = it })
            Field("Exchange platinum change", platinum, { platinum = it })
            Field("Exchange event source (required)", source, { source = it }, single = false)
            if (stale) Text("The selected wallet or item changed. Close and reopen before reviewing.", color = MaterialTheme.colorScheme.error)
        }
        if (errorMessage.isNotBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error)
        listOfNotNull(itemSnapshotState.error, reviewState.error, receiptState.error).distinct().forEach {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        LocalRecordStatus(actions.message, actions.busy)
    }
}
