package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.GameplayRules
import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.ItemState
import com.yazsras.astravar.rules.reforge.ReforgeCharacter

/** Replayable exact creation request; existing gameplay state changes only through the rules engine. */
internal fun genesisCreatedItemsCharacter(before: ReforgeCharacter, definition: ItemDefinition,
    instances: List<ItemInstance>): ReforgeCharacter {
    require(instances.size in 1..100 && instances.map { it.id }.distinct().size == instances.size &&
        instances.all { it.definitionId == definition.id && it.quantity > 0 } && instances.sumOf { it.quantity.toLong() } <= 1_000_000L) {
        "Create distinct physical items with a positive total quantity of at most one million."
    }
    require(before.itemDefinitions.none { it.id == definition.id } && instances.none { item -> before.items.any { it.id == item.id } }) {
        "This item identity already exists. Edit or split the existing item instead."
    }
    var next = before.copy(itemDefinitions = before.itemDefinitions + definition,
        items = before.items + instances.map { it.copy(state = ItemState.CARRIED, attuned = false, formId = null, formKnown = true) })
    validateCharacter(next)
    instances.forEach { item ->
        next = GameplayRules.equip(next, item.id, item.state)
        if (item.attuned) next = GameplayRules.attune(next, item.id, true)
    }
    val reviewed = instances.filter { it.formId != null || !it.formKnown }.associateBy { it.id }
    if (reviewed.isNotEmpty()) next = next.copy(items = next.items.map { item ->
        reviewed[item.id]?.let { item.copy(formId=it.formId,formKnown=it.formKnown) } ?: item
    })
    return GameplayRules.preserveCapacities(before, next).also(::validateCharacter)
}
