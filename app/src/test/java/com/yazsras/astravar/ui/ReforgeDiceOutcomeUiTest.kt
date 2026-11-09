package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import com.yazsras.astravar.data.ReforgeRollRecord
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
class ReforgeDiceOutcomeUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun character(charged: Boolean): ReforgeCharacter {
        val effect = EffectDefinition("cold-rider", "Cold rider", RuleSource("test", "Synthetic", Acceptance.ACCEPTED_HOMEBREW),
            EffectPayload.DamageBonus(DamageComponent("cold", "Cold", DamageKind.COLD, DiceFormula(listOf(DiceTerm(1, 6))))),
            conditions = listOf(EffectCondition(ConditionKind.SUCCESSFUL_HIT)), requiresAttunement = false,
            chargeResourceId = "charges", chargeCost = 1)
        return ReforgeCharacter("synthetic", "Synthetic attacker", revision = 1, baseAbilities = mapOf(Stat.STR to 16),
            turn = TurnEconomy(tracking = true),
            attacks = listOf(AttackDefinition("club", "Synthetic club", damage = listOf(DamageComponent("base", "Base", DamageKind.BLUDGEONING, DiceFormula(listOf(DiceTerm(1, 4))))))),
            resources = listOf(ResourcePool("charges", "Charges", 2, 2)), effects = if (charged) listOf(effect) else emptyList())
    }
    private fun dialog(character: ReforgeCharacter, action: (ReforgeRollRecord, ReforgeCharacter?) -> Unit): org.robolectric.android.controller.ActivityController<ComponentActivity> {
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { AstravarTheme { ReforgeRollDialog(character, 1,
            ReforgeRollRequest("Synthetic club", null, RollKind.ATTACK, "club", "club", 1, character.id), {}, onSave = action) } }
        return controller
    }
    @Test fun chargedAttackRequiresHitDecisionAndMissSpendsActionWithoutHitCharge() {
        var recorded: ReforgeRollRecord? = null
        var next: ReforgeCharacter? = null
        val controller = dialog(character(true)) { roll, changed -> recorded = roll; next = changed }
        try {
            compose.onNodeWithTag("reforge-roll-now").performClick()
            compose.onNodeWithText("Save unresolved roll").assertDoesNotExist()
            assertNull(recorded)
            compose.onNodeWithText("Confirm miss").performScrollTo().performClick()
            compose.runOnIdle {
                assertNotNull(recorded)
                assertFalse(requireNotNull(next).turn.actionAvailable)
                assertEquals(1, requireNotNull(next).turn.attacksUsed)
                assertEquals(2, requireNotNull(next).resources.single().current)
            }
        } finally { controller.pause().stop().destroy(); System.clearProperty("robolectric.windowManager.useRequestedSize") }
    }
    @Test fun noHitCostUnresolvedAttackStillSpendsItsTrackedAction() {
        var next: ReforgeCharacter? = null
        val controller = dialog(character(false)) { _, changed -> next = changed }
        try {
            compose.onNodeWithTag("reforge-roll-now").performClick()
            compose.onNodeWithText("Save unresolved roll").performScrollTo().performClick()
            compose.runOnIdle {
                assertNotNull(next)
                assertFalse(requireNotNull(next).turn.actionAvailable)
                assertEquals(1, requireNotNull(next).turn.attacksUsed)
            }
        } finally { controller.pause().stop().destroy(); System.clearProperty("robolectric.windowManager.useRequestedSize") }
    }

    @Test fun aRestoredRollCannotUseAnotherCharacterAtTheSameRevision() {
        val source = character(false)
        val other = source.copy(id = "another-character")
        val request = ReforgeRollRequest("Custom roll", null, characterRevision = source.revision, profileId = source.id)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme {
                ReforgeRollDialog(other, other.revision, request, {}) { _, _ -> fail("Roll should stay bound to the original character") }
            } }
            compose.onNodeWithTag("reforge-roll-now").assertIsNotEnabled()
            compose.onNodeWithText("The character or its statistics changed.", substring = true).assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun savedAttackShowsTypedRolledDamageAndRetainsSignedDiscardedDice() {
        val roll = ReforgeRollRecord(profileId = "synthetic", characterRevision = 1, label = "Synthetic club", formula = "1d20+3 · damage 1d6+2 + 1d4",
            components = listOf(
                ReforgeRollComponent("Attack", listOf(ReforgeRollDie(20, 14), ReforgeRollDie(20, 4, kept = false), ReforgeRollDie(4, 2, sign = -1)), 3),
                ReforgeRollComponent("Base", listOf(ReforgeRollDie(6, 5)), 2, "BLUDGEONING"),
                ReforgeRollComponent("Cold rider", listOf(ReforgeRollDie(4, 3)), damageType = "COLD")
            ))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { ReforgeSavedRollCard(roll) } }
            compose.onNodeWithText("Attack 15").assertIsDisplayed()
            compose.onNodeWithText("Rolled damage").assertIsDisplayed()
            compose.onNodeWithText("Base · 7 bludgeoning").assertIsDisplayed()
            compose.onNodeWithText("Cold rider · 3 cold").assertIsDisplayed()
            compose.onNodeWithText("4 on d20 (discarded)", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Dice details").performClick()
            compose.onNodeWithText("14 on d20, 4 on d20 (discarded), −2 on d4 · Modifier +3").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun enablingTurnTrackingDoesNotResetAlreadySpentActionsOrSlotUse() {
        val original = character(false).copy(turn = TurnEconomy(tracking = false, actionAvailable = false,
            bonusActionAvailable = false, reactionAvailable = false, attacksUsed = 1, attacksPerAction = 1, spellSlotSpent = true))
        var saved: ReforgeCharacter? = null
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { ReforgeTurnDialog(original, {}) { saved = it } } }
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("Accepted attacks per Attack action").performTextReplacement("2")
            compose.onNodeWithText("Save turn settings").assertIsDisplayed().performClick()
            compose.runOnIdle {
                val turn = requireNotNull(saved).turn
                assertTrue(turn.tracking)
                assertFalse(turn.actionAvailable)
                assertFalse(turn.bonusActionAvailable)
                assertFalse(turn.reactionAvailable)
                assertTrue(turn.spellSlotSpent)
                assertEquals(1, turn.attacksUsed)
                assertEquals(2, turn.attacksPerAction)
            }
        } finally { controller.pause().stop().destroy(); System.clearProperty("robolectric.windowManager.useRequestedSize") }
    }

}
