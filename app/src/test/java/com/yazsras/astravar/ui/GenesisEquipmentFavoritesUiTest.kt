package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
class GenesisEquipmentFavoritesUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun favoritingIsAccessibleReversibleAndDoesNotEquipOrChangeMechanics() {
        val definition = ItemDefinition("fictional-favorite-armor", "Starsteel vest", equipmentSlot = "armor",
            effects = listOf(EffectDefinition("vest-ac", "Vest defense", RuleSource("fictional", "Accepted fixture", Acceptance.ACCEPTED_HOMEBREW),
                EffectPayload.Number(NumericTarget.ARMOR_CLASS, NumberOperation.ADD, 1), requiresAttunement = false)))
        var item by mutableStateOf(ItemInstance("fictional-favorite-instance", definition.id, state = ItemState.CARRIED))
        var busy by mutableStateOf(false)
        var equip = 0; var inspect = 0; var manage = 0
        val original = ReforgeCharacter("fictional-favorites", "Fictional owner", baseAc = 12,
            itemDefinitions = listOf(definition), items = listOf(item))
        val before = CharacterCalculator.calculate(original)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { Page {
                RebirthItemRow(item, definition, busy, { inspect++ }, { equip++ }, { manage++ }) { desired -> item = item.copy(favorite = desired) }
            } } }
            compose.onNodeWithContentDescription("Add Starsteel vest to favorites").assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithContentDescription("Remove Starsteel vest from favorites").assertIsOn()
            assertTrue(item.favorite)
            assertEquals(ItemState.CARRIED, item.state)
            assertEquals(before, CharacterCalculator.calculate(original.copy(items = listOf(item))))
            assertEquals(0, equip); assertEquals(0, inspect); assertEquals(0, manage)
            compose.runOnIdle { busy = true }
            compose.onNodeWithContentDescription("Remove Starsteel vest from favorites").assertIsNotEnabled()
            compose.runOnIdle { busy = false }
            compose.onNodeWithContentDescription("Remove Starsteel vest from favorites").performClick()
            compose.onNodeWithContentDescription("Add Starsteel vest to favorites").assertIsOff()
            assertFalse(item.favorite)
            compose.onNodeWithText("Equip").performClick()
            assertEquals(1, equip)
            assertFalse(item.favorite)
        } finally { controller.pause().stop().destroy() }
    }
}
