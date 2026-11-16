package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
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
class GenesisNotionTransferUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun possibleReplacementShowsEveryPermanentAbilityAndChangedBuildBeforeConsent() {
        val current = ReforgeCharacter("fictional-review", "Fictional reviewer", level = 3, species = "Human",
            className = "Artificer", baseAbilities = Stat.entries.associateWith { if (it == Stat.STR) 8 else 14 },
            baseMaxHp = 20, baselineConstitutionModifier = 2, currentHp = 7, hpDamage = null)
        val proposed = current.copy(level = 4, species = "Gnome", subclass = "Armorer",
            baseAbilities = current.baseAbilities + (Stat.INT to 18), baseMaxHp = 28, spellcastingClassLevel = 4,
            rules = RuleVersion(RuleFamily.LEGACY_2014, "legacy-artificer-infusions-2020"))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { GenesisNotionStatsReview(current, proposed, replacing = false) }
            } }
            compose.onNodeWithText("Review possible stat replacements").assertExists()
            compose.onNodeWithTag("notion-review-STR-permanent-current").assertTextEquals("Current: 8")
            compose.onNodeWithTag("notion-review-STR-permanent-proposed").assertTextEquals("Proposed: 8")
            compose.onNodeWithTag("notion-review-INT-permanent-current").assertTextEquals("Current: 14")
            compose.onNodeWithTag("notion-review-INT-permanent-proposed").assertTextEquals("Proposed: 18")
            Stat.entries.forEach { compose.onNodeWithTag("notion-review-${it.name}-permanent").assertExists() }
            compose.onNodeWithTag("notion-review-level-current").assertTextEquals("Current: 3")
            compose.onNodeWithTag("notion-review-level-proposed").assertTextEquals("Proposed: 4")
            compose.onNodeWithTag("notion-review-species-proposed").assertTextEquals("Proposed: Gnome")
            compose.onNodeWithTag("notion-review-subclass-current").assertTextEquals("Current: Unknown")
            compose.onNodeWithTag("notion-review-hp-base-proposed").assertTextEquals("Proposed: 28")
            compose.onNodeWithTag("notion-review-casting-level-proposed").assertTextEquals("Proposed: 4")
            compose.onNodeWithTag("notion-review-class-rules-proposed").performScrollTo().assertTextEquals("Proposed: Legacy Artificer (2020)")
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun reviewDistinguishesUnknownPermanentScoreFromAcceptedEquipmentAndCalculatedHp() {
        val current = ReforgeCharacter("fictional-effect-review", "Fictional reviewer", level = 2,
            baseAbilities = mapOf(Stat.CON to 14), baseMaxHp = 20, baselineConstitutionModifier = 2, currentHp = 7, hpDamage = 13)
        val item = ItemDefinition("fictional-suit", "Fictional suit", effects = listOf(
            EffectDefinition("fictional-strength", "Strength", RuleSource("fixture", "Fixture", Acceptance.ACCEPTED_HOMEBREW),
                EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 19, "STR"), requiresAttunement = false),
            EffectDefinition("fictional-constitution", "Constitution", RuleSource("fixture", "Fixture", Acceptance.ACCEPTED_HOMEBREW),
                EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 16, "CON"), requiresAttunement = false)))
        val proposed = current.copy(itemDefinitions = listOf(item), items = listOf(ItemInstance("fictional-instance", item.id, ItemState.WORN)))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { GenesisNotionStatsReview(current, proposed, replacing = true) }
            } }
            compose.onNodeWithText("Reviewed stat replacements").assertExists()
            compose.onNodeWithTag("notion-review-STR-permanent-current").assertTextEquals("Current: Unknown")
            compose.onNodeWithTag("notion-review-STR-permanent-proposed").assertTextEquals("Proposed: Unknown")
            compose.onNodeWithTag("notion-review-STR-effective-proposed").assertTextEquals("Proposed: 19")
            compose.onNodeWithTag("notion-review-hp-current-current").performScrollTo().assertTextEquals("Current: 7")
            compose.onNodeWithTag("notion-review-hp-current-proposed").assertTextEquals("Proposed: 9")
            assertEquals(current.currentHp, proposed.currentHp)
            assertEquals(current.hpDamage, proposed.hpDamage)
        } finally { activity.pause().stop().destroy() }
    }
}
