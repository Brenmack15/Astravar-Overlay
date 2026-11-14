package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.encodeToString
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
class GenesisSourceAttackReviewUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun sourceReviewRequiresMechanicalChoicesKeepsDraftAndShowsExactCalculatedTotalsBeforeSave() {
        val character = ReforgeCharacter("fictional-review-ui", "Fictional review", revision = 3)
        val source = SheetAttack("Fictional ray", 6, "2d4+1", "Cold", "40 feet", actionEconomy = "Magic", evidenceStatus = "Confirmed", source = "Fictional provenance", id = "fictional-ray")
        val sheet = PartySheetRecord(character.id, campaignJson.encodeToString(PartySheetData(attacks = listOf(source,
            source.copy(id = "unresolved-ray", name = "Unresolved ray", resourceCost = "1 charge")),
            unresolvedConflicts = listOf("Fictional maximum HP unknown"))), source = "Fictional sheet")
        val review = GenesisSourceAttackReview(character.id, character.revision, sheet, reforgeHash(reforgeJson.encodeToString(sheet)))
        var saved: AttackDefinition? = null
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme { GenesisSourceAttackReviewSheet(review, character, false, "", {}, { id, choices ->
                        saved = sourceAttackDefinition(review, id, choices)
                    }) }
                }
            }
            compose.onNodeWithTag("genesis-source-attack-save").assertIsNotEnabled()
            compose.onNodeWithTag("review-source-attack:unresolved-ray").assertDoesNotExist()
            compose.onNodeWithTag("review-source-attack:fictional-ray").performScrollTo().performClick()
            fun choose(tag: String, value: String) {
                compose.onNode(hasText("Choose…") and hasAnyAncestor(hasTestTag(tag))).performScrollTo().performClick()
                compose.onNodeWithText(value).performClick()
            }
            choose("source-attack-action", "Full Action")
            choose("source-attack-reach", "Ranged")
            choose("source-attack-nature", "Magical")
            compose.onNodeWithText("Confirmed: no additional slot, charge, or resource cost").performScrollTo().performClick()
            compose.onNodeWithText("Confirmed: critical hits double damage dice, keeping flat bonuses").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-source-attack-save").assertIsNotEnabled()
            compose.onNodeWithText("I reviewed the source conflicts; this selected attack's exact mechanics are confirmed").performScrollTo().performClick()
            compose.onNodeWithText("Calculated now · to hit +6").performScrollTo().assertExists()
            compose.onNodeWithTag("genesis-source-attack-save").assertIsNotEnabled()
            var snapshot: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { snapshot = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(snapshot) { true }; visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("Calculated now · to hit +6").performScrollTo().assertExists()
            compose.onNodeWithText("I reviewed the complete attack and its calculated totals").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-source-attack-save").assertIsEnabled().performClick()
            val result = requireNotNull(saved)
            assertEquals(6, result.fixedAttackBonus)
            assertEquals("Full Action", result.actionType)
            assertEquals(source, reforgeJson.decodeFromString<SheetAttack>(result.recordedSource!!.attackJson))
            assertEquals(SecureDice.parse("2d4+1"), result.damage.single().formula)
            assertEquals(setOf("ranged"), result.tags)
            assertTrue(result.damage.single().magical)
        } finally { controller.pause().stop().destroy() }
    }
}
