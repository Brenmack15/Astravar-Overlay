package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisInventoryHubUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun saved(id: String, name: String, itemId: String = "shared:blade", archived: Boolean = false) =
        ReforgeSavedCharacter(ReforgeCharacter(id, name, itemDefinitions = listOf(ItemDefinition("definition", "Silver blade")),
            items = listOf(ItemInstance(itemId, "definition", quantity = 2)), archived = archived), 1, ReforgeOrigin(), 1, 1)
    private fun source(id: String = "shared:blade") = LedgerItem(id, "Silver blade", evidenceStatus = "Confirmed", quantity = 2, createdAt = 1, updatedAt = 1)

    @Test fun onePhysicalItemHasOneRowAndBothExactRecordActions() {
        val actor = saved("actor:a", "Mira")
        val entries = genesisInventoryProjection(listOf(actor), listOf(source()), listOf(ItemPlacement("shared:blade", "actor:a")))
        var opened: ReforgeSearchDestination? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisInventoryContent(entries, listOf(actor), "actor:a", mapOf("actor:a" to "Mira"), emptySet(), { opened = it }, {})
            } }
            compose.onAllNodesWithTag("genesis-inventory-item-shared:blade").assertCountEquals(1)
            compose.onNodeWithText("1 item").assertExists()
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").performClick()
            compose.onNodeWithText("Open Mira's item").performClick()
            compose.runOnIdle { assertEquals(ReforgeSearchDestination.Character("actor:a", "Equipment", "InspectItem:shared:blade"), opened) }
            compose.onNodeWithText("Open Mira's item").assertDoesNotExist()
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").performClick()
            compose.onNodeWithText("Open campaign record").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ReforgeSearchDestination.CampaignItem("shared:blade"), opened) }
            compose.onNodeWithText("Open campaign record").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun conflictedOwnershipIsVisibleAndRequiresChoosingExactHolder() {
        val first = saved("actor-a", "Mira")
        val second = saved("owl-b", "Fable")
        val entries = genesisInventoryProjection(listOf(first, second), emptyList(), emptyList())
        var opened: ReforgeSearchDestination? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisInventoryContent(entries, listOf(first, second), "actor-a", emptyMap(), setOf("owl-b"), { opened = it }, {})
            } }
            compose.onNodeWithText("Ownership needs review").assertExists()
            compose.onNodeWithText("?").assertExists()
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").performClick()
            compose.onNodeWithText("Review ownership before moving this item").assertExists()
            compose.runOnIdle { assertNull(opened) }
            compose.onNodeWithText("Open Fable's item").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ReforgeSearchDestination.Character("owl-b", "Equipment", "InspectItem:shared:blade", companion = true), opened) }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun addItemUsesActiveProfileAndCampaignSourceToolsRemainReachable() {
        val first = saved("actor-a", "Mira")
        val second = saved("actor-b", "Lark", "other-blade")
        val entries = genesisInventoryProjection(listOf(first, second), emptyList(), emptyList())
        var opened: ReforgeSearchDestination? = null
        var sourcesOpened = false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisInventoryContent(entries, listOf(first, second), "actor-b", emptyMap(), emptySet(), { opened = it }, { sourcesOpened = true })
            } }
            compose.onNodeWithTag("genesis-inventory-item-other-blade").assertExists()
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").assertDoesNotExist()
            compose.onNodeWithText("Add item").performClick()
            compose.runOnIdle { assertEquals(ReforgeSearchDestination.Character("actor-b", "Equipment", "Item"), opened) }
            compose.onNodeWithText("Campaign items").performClick()
            compose.runOnIdle { assertTrue(sourcesOpened) }
            compose.onNodeWithText("All items").performClick()
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").assertExists()
            compose.onNodeWithText("Find an item or owner").performTextInput("Mira")
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").assertExists()
            compose.onNodeWithTag("genesis-inventory-item-other-blade").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun absentOrArchivedActorCannotReceiveNewEquipmentImplicitly() {
        val actor = saved("archived", "Mira", archived = true)
        val entries = genesisInventoryProjection(listOf(actor), emptyList(), emptyList())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisInventoryContent(entries, listOf(actor), "archived", emptyMap(), emptySet(), { error("No gameplay route expected") }, {})
            } }
            compose.onNodeWithText("Add item").assertIsNotEnabled()
            compose.onNodeWithText("Create or review a character before adding playable equipment.").assertExists()
            compose.onNodeWithTag("genesis-inventory-item-shared:blade").performClick()
            compose.onNodeWithText("This owner is archived. Restore the saved sheet before changing this item.").assertExists()
            compose.onNodeWithText("Open Mira's item").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }
}
