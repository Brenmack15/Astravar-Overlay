package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance

/**
 * A one-way, explicit local copy. The campaign ledger remains the original source record;
 * it is neither overwritten nor considered synchronized with the playable store.
 * No effects are inferred from free-form item descriptions.
 */
internal fun campaignItemPlayableCopy(item: LedgerItem): Pair<ItemDefinition, ItemInstance> {
    require(!item.archived && item.itemState !in setOf("Sold", "Lost", "Consumed")) {
        "This item is not currently available."
    }
    val quantity = requireNotNull(item.quantity) { "Confirm the item's quantity in campaign inventory first." }
    require(quantity > 0) { "A quantity greater than zero is required." }
    require(item.title.isNotBlank()) { "The source item needs a name." }
    return ItemDefinition(id = item.id, name = item.title, description = item.description) to
        ItemInstance(id = item.id, definitionId = item.id, quantity = quantity)
}
