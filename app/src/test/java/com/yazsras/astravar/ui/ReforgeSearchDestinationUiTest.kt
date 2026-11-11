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
class ReforgeSearchDestinationUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun tappingSpellSearchResultOpensExactActorInspectionWithoutCasting() {
        val character = ReforgeCharacter("fictional-actor", "Fictional scholar", spells = listOf(SpellDefinition("frost", "Frost spark", 1)),
            spellSlots = mapOf(1 to 2))
        val entries = reforgeSearchCatalog(characters = listOf(ReforgeSavedCharacter(character, 1, ReforgeOrigin(), 1, 1)))
        var opened: ReforgeSearchDestination? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ReforgeSearchResults(entries, { opened = it }) } }
            compose.onNodeWithText("Items, people, places, spells…").performTextInput("Frost")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Frost spark").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Frost spark").performClick()
            compose.runOnIdle {
                assertEquals(ReforgeSearchDestination.Character("fictional-actor", "Spells", "InspectSpell:frost"), opened)
                assertEquals(2, character.spellSlots[1])
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun tappingImportedSpellReadsItsUnapprovedSourceWithoutGameplayNavigation() {
        val reference = CampaignReferenceRecord("fictional-reference", "fictional-campaign", "external", "Frost proposal", "Spell",
            "Proposed mechanics remain under review.", "{}", "https://example.org/reference", "r1", "hash", "package")
        val entries = reforgeSearchCatalog(sources = listOf(reference))
        var opened: ReforgeSearchDestination? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ReforgeSearchResults(entries, { opened = it }) } }
            compose.onNodeWithText("Items, people, places, spells…").performTextInput("Frost")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Frost proposal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Frost proposal").performClick()
            compose.onNodeWithText("Reading a source does not approve it for play or change a character's mechanics.").assertExists()
            compose.runOnIdle { assertNull(opened) }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Frost proposal").assertExists()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun campaignConnectionResultOpensTheRecordInsteadOfAnExcerptDeadEnd() {
        val entries = reforgeSearchCatalog(people = listOf(ConnectionEntity(id = "fictional-scout", name = "Amber scout")))
        var opened: ReforgeSearchDestination? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ReforgeSearchResults(entries, { opened = it }) } }
            compose.onNodeWithText("Items, people, places, spells…").performTextInput("Amber")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Amber scout").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Amber scout").performClick()
            compose.runOnIdle { assertEquals(ReforgeSearchDestination.Connection("fictional-scout"), opened) }
        } finally { activity.pause().stop().destroy() }
    }
}
