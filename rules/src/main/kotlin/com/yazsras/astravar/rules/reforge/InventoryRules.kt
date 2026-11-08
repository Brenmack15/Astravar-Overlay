package com.yazsras.astravar.rules.reforge

/** Inventory changes retain stable identities and never rewrite the campaign source ledger. */
object InventoryRules {
    fun split(character: ReforgeCharacter, itemId: String, quantity: Int, newId: String): ReforgeCharacter {
        require(newId.isNotBlank() && character.items.none { it.id == newId }) { "Choose a new item identity." }
        val item = movable(character, itemId)
        require(item.state in setOf(ItemState.CARRIED, ItemState.STORED) && !item.attuned) { "Unequip and end attunement before splitting this stack." }
        require(quantity in 1 until item.quantity) { "Split quantity must leave at least one item in the original stack." }
        return character.copy(items = character.items.map { if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it } +
            item.copy(id = newId, quantity = quantity, favorite = false))
    }

    fun remove(character: ReforgeCharacter, itemId: String): ReforgeCharacter {
        movable(character, itemId)
        return GameplayRules.preserveCapacities(character, character.copy(items = character.items.filterNot { it.id == itemId }))
    }

    /** The receiver gets carried, unattuned gear; any mechanical activation is reviewed separately. */
    fun transfer(from: ReforgeCharacter, to: ReforgeCharacter, itemId: String): Pair<ReforgeCharacter, ReforgeCharacter> {
        require(from.id != to.id) { "Choose another character or companion sheet." }
        require(!from.archived && !to.archived) { "Restore archived sheets before transferring equipment." }
        val item = movable(from, itemId)
        require(to.items.none { it.id == itemId }) { "The receiving sheet already contains this item identity." }
        val definition = from.itemDefinitions.single { it.id == item.definitionId }
        val existing = to.itemDefinitions.singleOrNull { it.id == definition.id }
        require(existing == null || existing == definition) { "The receiving sheet has a different definition with this identity. Review the conflict first." }
        require(definition.effects.all { effect ->
            effect.chargeResourceId == null && when (val payload = effect.payload) {
                is EffectPayload.DamageBonus -> payload.qualifier.attackIds.isEmpty()
                is EffectPayload.DamageConversion -> payload.qualifier.attackIds.isEmpty()
                else -> true
            }
        }) { "This item has sheet-specific attacks or charge resources. Review those dependencies before transfer." }
        val nextTo = to.copy(
            itemDefinitions = if (existing == null) to.itemDefinitions + definition else to.itemDefinitions,
            items = to.items + item.copy(state = ItemState.CARRIED, attuned = false, containerId = null))
        return remove(from, itemId) to GameplayRules.preserveCapacities(to, nextTo)
    }

    private fun movable(character: ReforgeCharacter, itemId: String): ItemInstance {
        CharacterCalculator.validate(character)
        val item = character.items.singleOrNull { it.id == itemId } ?: error("Item is no longer available.")
        require(item.donorInstanceIds.isEmpty() && character.items.none { itemId in it.donorInstanceIds }) {
            "Separate this item's composite links before moving or removing it."
        }
        require(character.items.none { it.containerId == itemId }) { "Move this container's contents first." }
        require(character.attacks.none { it.requiredItemId == itemId }) { "This item is required by a saved attack. Edit that attack before moving or removing it." }
        return item
    }
}
