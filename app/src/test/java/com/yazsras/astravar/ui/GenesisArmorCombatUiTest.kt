package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.yazsras.astravar.data.ReforgeRollRecord
import com.yazsras.astravar.rules.*
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

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisArmorCombatUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun fixture() = GenesisArmorRules.adopt(
        ReforgeCharacter("armor-ui", "Fictional armor owner", level = 11,
            baseAbilities = mapOf(Stat.STR to 19, Stat.DEX to 14, Stat.INT to 20),
            spellSlots = mapOf(1 to 2), turn = TurnEconomy(tracking = true, attacksPerAction = 2)),
        Campaign(onboarded = true, attuned = true, installedId = "nexus",
            cores = listOf(Core("nexus", "Internal nexus"))), listOf(DamageType.COLD), "UI fixture")

    @Test fun inlineCombatRetainsElementAndCommitsFacesAndCostsOnRollWithSafeRetry() {
        val owner = mutableStateOf(fixture())
        val original = owner.value.armor!!.originalCampaign
        val attempts = mutableListOf<ReforgeRollRecord>()
        var reject = true
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                AstravarTheme { Page {
                    GenesisArmorCombat(owner.value,
                        { next, _ -> owner.value = next.copy(revision = next.revision + 1) },
                        { next, record ->
                            attempts += record
                            if (reject) { reject = false; error("Fictional save interruption") }
                            owner.value = next.copy(revision = next.revision + 1)
                        }, {})
                } }
            }
            compose.onNodeWithText("Cold", substring = false).assertExists()
            compose.onNodeWithTag("genesis-armor-accuracy").assertTextEquals("+10 to hit")
            compose.onNodeWithTag("genesis-armor-roll").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, attempts.size)
            assertEquals(0, owner.value.turn.attacksUsed)
            compose.onNodeWithText("Retry saving the same roll").assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
            assertEquals(1, owner.value.turn.attacksUsed)
            assertEquals(original, owner.value.armor!!.originalCampaign)
            assertEquals(listOf(DamageType.COLD), owner.value.armor!!.elements)
            assertEquals(attempts.first().id, owner.value.armor!!.campaign.pending.single().plan.id)
            compose.onNodeWithText("Retry saving the same roll").assertDoesNotExist()
            compose.onNodeWithText("Change element").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Fire element").performClick()
            compose.waitForIdle()
            assertEquals(listOf(DamageType.FIRE), owner.value.armor!!.elements)
            assertEquals(1, owner.value.turn.attacksUsed)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingCharacterIntelligenceShowsAnActionableNoticeWithoutACompositionCrash() {
        val owner = fixture().let { it.copy(baseAbilities = it.baseAbilities - Stat.INT) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { Page { GenesisArmorCombat(owner, { _, _ -> }, { _, _ -> }, {}) } } }
            compose.onAllNodesWithText("Set the character's Intelligence before using the armor.").assertCountEquals(2)
            compose.onNodeWithTag("genesis-armor-roll").performScrollTo().assertIsNotEnabled()
        } finally { controller.pause().stop().destroy() }
    }
}
