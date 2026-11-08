package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ItemState
import org.junit.Assert.*
import org.junit.Test

class ReforgeCampaignItemBridgeTest {
    private fun item(quantity: Int? = 2, state: String = "Carried", archived: Boolean = false) = LedgerItem(
        id = "original-source-id", title = "Dwarven harness", description = "Inscribed with mysterious runes",
        quantity = quantity, itemState = state, archived = archived, createdAt = 100, updatedAt = 100, revision = 7)

    @Test fun copyRetainsIdDescriptionAndQuantityWithoutInventingEffects() {
        val original = item()
        val (definition, instance) = campaignItemPlayableCopy(original)
        assertEquals(original.id, definition.id)
        assertEquals(original.id, instance.id)
        assertEquals(original.description, definition.description)
        assertEquals(2, instance.quantity)
        assertEquals(ItemState.CARRIED, instance.state)
        assertTrue(definition.effects.isEmpty())
        assertFalse(instance.attuned)
    }

    @Test fun copyRejectsMissingStockAndUnavailableItems() {
        for (bad in listOf(item(quantity = null), item(quantity = 0), item(state = "Sold"), item(archived = true))) {
            assertThrows(IllegalArgumentException::class.java) { campaignItemPlayableCopy(bad) }
        }
    }
}
