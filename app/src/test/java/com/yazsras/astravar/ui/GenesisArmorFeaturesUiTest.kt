package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisArmorFeaturesUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var captureActivity: ComponentActivity? = null
    private fun fixture(): ReforgeCharacter {
        val legacy = Campaign(onboarded = true, attuned = true, installedId = "feature-core",
            profile = Profile(), cores = listOf(Core("feature-core", "Fictional nexus")))
        return GenesisArmorRules.adopt(ReforgeCharacter("feature-actor", "Fictional feature actor", level = 11,
            baseAbilities = Stat.entries.associateWith { if (it == Stat.INT) 20 else 14 },
            currentHp = 10, spellSlots = mapOf(1 to 2)), legacy, listOf(DamageType.ACID), "Fictional source")
    }
    private fun withUi(content: @Composable () -> Unit, block: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try { captureActivity = controller.get(); controller.get().setContent { AstravarTheme { content() } }; block() }
        finally { captureActivity = null; controller.pause().stop().destroy() }
    }
    private fun capture(name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, requireNotNull(captureActivity)) }
    }
    private fun review() = compose.onNode(hasText("Review & save") and hasClickAction()).performClick()

    @Test fun stagedEditPreservesResourcesAndClosesOnlyAfterObservedSave() {
        val original = fixture()
        var character by mutableStateOf(original)
        var dismissed by mutableStateOf(false)
        var pending: ReforgeCharacter? = null
        withUi({ if (!dismissed) GenesisArmorConfigurationEditor(character, "damage", { dismissed = true }) { next, _ -> pending = next } }) {
            compose.onNodeWithText("Primary Force dice count (d12)").performTextReplacement("4")
            compose.onNodeWithText("Accepted ruling / source reference").performScrollTo().performTextInput("Approved test dice")
            review()
            capture("genesis-armor-damage-review")
            compose.onNodeWithText("Save armor rules").assertIsEnabled().performClick()
            assertFalse(dismissed)
            val next = requireNotNull(pending)
            assertEquals(original.spellSlots, next.spellSlots)
            assertEquals(original.currentHp, next.currentHp)
            assertEquals(original.armor!!.originalCampaign, next.armor!!.originalCampaign)
            assertEquals(original.armor!!.elements, next.armor!!.elements)
            assertEquals("Approved test dice", next.armor!!.configurationSources["damage:default"])
            assertEquals(4, next.armor!!.campaign.profile.baseDamageDiceCount)
            compose.runOnIdle { character = next.copy(revision = 1) }
            compose.waitForIdle()
            compose.waitUntil(5_000) { dismissed }
        }
    }

    @Test fun staleDraftCannotReplaceConcurrentCharacterChanges() {
        var character by mutableStateOf(fixture())
        var saves = 0
        withUi({ GenesisArmorConfigurationEditor(character, "upgrades", {}) { _, _ -> saves++ } }) {
            compose.onNodeWithText("Internal save DC").performScrollTo().performTextReplacement("17")
            compose.onNodeWithText("Accepted ruling / source reference").performScrollTo().performTextInput("Accepted internal DC")
            review()
            compose.onNodeWithText("Save armor rules").assertIsEnabled()
            compose.runOnIdle { character = character.copy(currentHp = 9, revision = 1) }
            compose.onNodeWithText("Save armor rules").assertIsNotEnabled()
            compose.onNodeWithText("The character changed while this draft was open. Close and reopen to review the latest values.").performScrollTo().assertIsDisplayed()
            assertEquals(0, saves)
        }
    }

    @Test fun liveAccuracyUsesEnteredCurrentTotalWithoutAddingOldAdvancementTwice() {
        val original = fixture().let { actor -> actor.copy(armor = actor.armor!!.copy(campaign = actor.armor!!.campaign.copy(
            profile = actor.armor!!.campaign.profile.copy(melee = MeleeProfile(8, Dice(2, 12, 5), 2, 2, 4,
                "Existing melee", guardianAttackBonus = 7))))) }
        var next: ReforgeCharacter? = null
        withUi({ GenesisArmorConfigurationEditor(original, "melee", {}) { saved, _ -> next = saved } }) {
            compose.onNodeWithText("Accepted enhanced attack bonus").performTextReplacement("11")
            compose.onNodeWithText("Follow future INT and proficiency changes").performScrollTo().performClick()
            review()
            capture("genesis-armor-gauntlet-review")
            compose.onNodeWithText("Save armor rules").performClick()
            val saved = requireNotNull(next)
            assertEquals(11, GenesisArmorRules.runtime(saved).profile.melee!!.attackBonus)
            assertEquals(12, GenesisArmorRules.runtime(saved.copy(baseAbilities = saved.baseAbilities + (Stat.INT to 22))).profile.melee!!.attackBonus)
        }
    }

    @Test fun originalWeaponNotesRemainEditableWithoutChangingCharacterNotesOrResources() {
        val original = fixture().let { actor -> actor.copy(notes = "Character journal", armor = actor.armor!!.copy(
            campaign = actor.armor!!.campaign.copy(notes = "Original weapon note"))) }
        var character by mutableStateOf(original)
        var dismissed by mutableStateOf(false)
        var pending: ReforgeCharacter? = null
        withUi({ if (!dismissed) GenesisArmorNotesEditor(character, { dismissed = true }) { next, _ -> pending = next } }) {
            compose.onNodeWithText("Original weapon note").assertExists()
            compose.onNode(hasSetTextAction()).performTextReplacement("Updated weapon reference")
            review()
            compose.onNodeWithText("Save armor notes").assertIsEnabled().performClick()
            assertFalse(dismissed)
            val next = requireNotNull(pending)
            assertEquals("Updated weapon reference", next.armor!!.campaign.notes)
            assertEquals(original, next.copy(armor = next.armor!!.copy(campaign = next.armor!!.campaign.copy(notes = original.armor!!.campaign.notes)))))
            compose.runOnIdle { character = next.copy(revision = 1) }
            compose.waitForIdle()
            compose.waitUntil(5_000) { dismissed }
        }
    }
}
