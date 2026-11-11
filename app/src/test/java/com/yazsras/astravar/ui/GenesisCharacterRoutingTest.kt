package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class GenesisCharacterRoutingTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val main = ReforgeCharacter("fictional-main", "Same display name", currentHp = 13)
    private val other = ReforgeCharacter("fictional-other", "Same display name", currentHp = 7,
        itemDefinitions = listOf(ItemDefinition("definition-two", "Fictional moon compass", description = "Second actor's exact item")),
        items = listOf(ItemInstance("instance-two", "definition-two")))

    private fun withRecords(test: (CampaignLedgerRepository, ReforgeRepository, ReforgePreferences, ComponentActivity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ledgerDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val playableDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val ledger = CampaignLedgerRepository(ledgerDb)
        val playable = ReforgeRepository(playableDb)
        val prefs = ReforgePreferences.open(context)
        runBlocking { playable.create(main); playable.create(other); prefs.selectProfile(main.id) }
        val oldSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try { test(ledger, playable, prefs, activity.get()) }
        finally {
            activity.pause().stop().destroy(); playableDb.close(); ledgerDb.close()
            if (oldSize == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", oldSize)
        }
    }

    @Test fun exactItemRouteChoosesTheRightIdentityWithoutChangingTheItem() = withRecords { ledger, playable, prefs, activity ->
        activity.setContent { AstravarTheme { ReforgeCharacterSheet(ledger, playable,
            navigationRequest = GenesisCharacterRoute(other.id, "Equipment", "InspectItem:instance-two", 1)) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Second actor's exact item").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Second actor's exact item").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("section-Equipment").assertIsSelected()
        assertEquals(other.id, runBlocking { prefs.activeProfileId.first() })
        assertEquals(1L, runBlocking { playable.get(other.id) }!!.revision)
        assertEquals(ItemState.CARRIED, runBlocking { playable.get(other.id) }!!.character.items.single().state)
        assertEquals(1L, runBlocking { playable.get(main.id) }!!.revision)
    }

    @Test fun companionDetailRouteDoesNotReplaceTheMainActor() = withRecords { ledger, playable, prefs, activity ->
        activity.setContent { AstravarTheme { ReforgeCharacterSheet(ledger, playable,
            companionProfileId = other.id, navigationRequest = GenesisCharacterRoute(other.id, "Equipment", "InspectItem:instance-two", 4)) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Second actor's exact item").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Second actor's exact item").assertIsDisplayed()
        assertEquals(main.id, runBlocking { prefs.activeProfileId.first() })
        assertEquals(1L, runBlocking { playable.get(other.id) }!!.revision)
    }

    @Test fun staleDetailRouteReportsTheMissingItemAndKeepsPagingUsable() = withRecords { ledger, playable, prefs, activity ->
        val route = mutableStateOf<GenesisCharacterRoute?>(null)
        activity.setContent { AstravarTheme { ReforgeCharacterSheet(ledger, playable,
            navigationRequest = route.value) } }
        // A search selection arrives after the sheet has observed its initial records.
        // Cold-start routes remain covered by the exact-item and companion tests above.
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("HP 13 / Not set").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            route.value = GenesisCharacterRoute(other.id, "Equipment", "InspectItem:no-longer-present", 9)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("That record is no longer available. Its character is open.").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { prefs.activeProfileId.first() } == other.id
        }
        compose.onNodeWithText("That record is no longer available. Its character is open.").assertIsDisplayed()
        compose.onNodeWithContentDescription("HP 7 / Not set").assertIsDisplayed()
        assertEquals(other.id, runBlocking { prefs.activeProfileId.first() })
        assertEquals(1L, runBlocking { playable.get(other.id) }!!.revision)
        assertEquals(1L, runBlocking { playable.get(main.id) }!!.revision)
        compose.onNodeWithTag("section-Equipment").assertIsSelected()
        compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("section-Spells").assertIsSelected()
    }

    @Test fun absentProfileNeverFallsThroughToAnUnrelatedSavedCharacter() = withRecords { ledger, playable, prefs, activity ->
        activity.setContent { AstravarTheme { ReforgeCharacterSheet(ledger, playable,
            navigationRequest = GenesisCharacterRoute("fictional-deleted", "Equipment", token = 12)) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sheet unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Same display name").assertDoesNotExist()
        assertEquals(main.id, runBlocking { prefs.activeProfileId.first() })
        compose.onNodeWithText("Back to character").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("HP 13 / Not set").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("HP 13 / Not set").assertIsDisplayed()
        assertEquals(1L, runBlocking { playable.get(main.id) }!!.revision)
    }
}
