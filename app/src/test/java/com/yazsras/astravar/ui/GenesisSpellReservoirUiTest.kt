package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
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
class GenesisSpellReservoirUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val template = Template("test-template", "Fictional frost", 1, 1,
        listOf(TypedDice(Dice(1, 6), DamageType.COLD)), DamageType.COLD, true, "Fictional accepted source")
    private fun character(slots: Int? = 2) = GenesisArmorRules.adopt(
        ReforgeCharacter("test-actor", "Fictional artificer", level = 9, baseAbilities = mapOf(Stat.INT to 20), spellSlots = mapOf(1 to slots)),
        Campaign(onboarded = true, attuned = true, installedId = "test-core", cores = listOf(Core("test-core", "Fictional nexus")), templates = listOf(template)),
        emptyList(), "Reviewed fixture")

    @Test fun loadButtonUsesCharacterBalanceAndShowsStoredSpellAfterSaving() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        val repository = ReforgeRepository(db)
        val saved = runBlocking { repository.create(character()) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                val current by repository.observe(saved.character.id).collectAsState(initial = saved)
                AstravarTheme { Page {
                    GenesisSpellReservoir(requireNotNull(current).character) { next, label ->
                        runBlocking { repository.save(next.id, next.revision, next, label) }
                    }
                } }
            }
            compose.onNodeWithTag("genesis-load-reservoir").performScrollTo().performClick()
            compose.onNodeWithText("Level 1 slots: 2").assertExists()
            compose.onNodeWithTag("genesis-confirm-reservoir-load").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(saved.character.id)?.character?.spellSlots?.get(1) == 1 } }
            compose.waitForIdle()
            compose.onNodeWithText("1 / 1 stored spells").assertExists()
            val state = runBlocking { requireNotNull(repository.get(saved.character.id)).character }
            assertEquals(1, state.spellSlots[1])
            assertEquals(template, state.armor!!.campaign.installed!!.payloads.single().template)
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    @Test fun unknownSlotCountIsNotInventedAndIncompleteCharacterDoesNotCrash() {
        var current by mutableStateOf(character(null))
        var saves = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
        controller.get().setContent { AstravarTheme { Page { GenesisSpellReservoir(current) { _, _ -> saves++ } } } }
        compose.onNodeWithTag("genesis-load-reservoir").performScrollTo().performClick()
        compose.onNodeWithText("Level 1 slots: Not set").assertExists()
        compose.onNodeWithTag("genesis-confirm-reservoir-load").performClick()
        compose.waitForIdle()
        assertEquals(0, saves)
        // State updates during an open editor are blocked by revision checks; incomplete
        // character stats must still render a recoverable screen instead of throwing.
        compose.runOnIdle { current = current.copy(revision = current.revision + 1, baseAbilities = emptyMap()) }
        compose.waitForIdle()
        compose.onNodeWithText("Reservoir configuration").assertExists()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun competingCharacterEditDoesNotDismissAnUncommittedLoad() {
        var current by mutableStateOf(character())
        var proposal: ReforgeCharacter? = null
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { Page { GenesisSpellReservoir(current) { next, _ -> proposal = next } } } }
            compose.onNodeWithTag("genesis-load-reservoir").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-confirm-reservoir-load").performClick()
            compose.runOnIdle {
                assertNotNull(proposal)
                current = current.copy(revision = current.revision + 1, name = "Changed elsewhere")
            }
            compose.waitForIdle()
            compose.onNodeWithTag("genesis-confirm-reservoir-load").assertExists().assertIsNotEnabled()
            assertEquals(2, current.spellSlots[1])
            assertTrue(current.armor!!.campaign.installed!!.payloads.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun adoptionExplicitlyReconcilesOnlyUnknownCountsAndPreservesSource() {
        val actor = character().copy(armor = null, spellSlots = mapOf(1 to 1, 2 to null))
        val legacy = Campaign(onboarded = true, attuned = true, installedId = "source-core", gameSeconds = 600,
            cores = listOf(Core("source-core", "Existing nexus", exhaustedUntil = 1000)), slots = mapOf(1 to 4, 2 to 3),
            encounter = true, turn = Turn(attacks = 1, bonus = true, spellShots = 1))
        var adopted: ReforgeCharacter? = null
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { Page {
                GenesisArmorAdoption(actor, legacy, listOf(DamageType.COLD)) { next, _ -> adopted = next }
            } } }
            compose.onNodeWithText("Connect saved Astravar systems").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-adopt-armor").assertIsNotEnabled()
            compose.onNodeWithText("Use recorded armor slots for unknown levels").performScrollTo().performClick()
            compose.onNodeWithText("Source or confirmation note").performScrollTo().performTextInput("Reviewed fixture source")
            compose.onNodeWithText("These saved Astravar systems belong to Fictional artificer").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-adopt-armor").performClick()
            compose.runOnIdle {
                val result = requireNotNull(adopted)
                assertEquals(1, result.spellSlots[1])
                assertEquals(3, result.spellSlots[2])
                assertTrue(result.turn.tracking)
                assertEquals(1, result.turn.attacksUsed)
                assertFalse(result.turn.actionAvailable)
                assertFalse(result.turn.bonusActionAvailable)
                assertEquals(1, result.armor!!.campaign.turn.spellShots)
                assertEquals(legacy, result.armor!!.originalCampaign)
                assertEquals(listOf(DamageType.COLD), result.armor!!.elements)
                assertEquals(600L, GenesisArmorRules.runtime(result).gameSeconds)
                assertEquals(1000L, GenesisArmorRules.runtime(result).installed!!.exhaustedUntil)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun actionBudgetReconciliationNeverRefillsUnlessCurrentSheetExplicitlyChosen() {
        val actor = character().copy(turn = TurnEconomy(tracking = true, reactionAvailable = false,
            attacksPerAction = 2, attacksUsed = 1, spellSlotSpent = true))
        val old = Campaign(encounter = true, turn = Turn(attacks = 2, fullAction = true, bonus = true, spellShots = 1))
        val reconciled = genesisReviewedArmorTurn(actor, old, false)
        assertEquals(2, reconciled.attacksUsed)
        assertEquals(2, reconciled.attacksPerAction)
        assertFalse(reconciled.actionAvailable)
        assertFalse(reconciled.bonusActionAvailable)
        assertFalse(reconciled.reactionAvailable)
        assertTrue(reconciled.spellSlotSpent)
        assertEquals(actor.turn, genesisReviewedArmorTurn(actor, old, true))
        assertFalse(genesisReviewedArmorTurn(actor, old.copy(encounter = false), false).bonusActionAvailable)
    }

}
