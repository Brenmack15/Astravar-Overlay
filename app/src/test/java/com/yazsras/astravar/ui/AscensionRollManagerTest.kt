package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
class AscensionRollManagerTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun selectingAndChangingModesDoesNotRollAndCopyNeverExecutesTwice() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val committed = mutableListOf<PreparedRollBatch>()
        val check = QueuedCheck(RollKind.SKILL, "research", "Research")
        try {
            activity.get().setContent { AstravarTheme {
                RollManagerPanel("fixture", "3", listOf(ManagerOption(check, 7)), emptyList(), emptyList(), true, false, {},
                    resolve = { c, _ -> ManagerFormula(DiceFormula(listOf(DiceTerm(1,20)),7),c.mode,if(c.mode==DiceMode.NORMAL)1 else 2) },
                    commit = { batch -> committed.add(batch); SavedRollBatch(listOf("Research: (13) + 7 = 20"), "Recorded") },
                    savePreset = { "Saved" }, deletePreset = { "Deleted" })
            } }
            compose.onNodeWithText("Skills").performClick()
            compose.onNodeWithContentDescription("Research advantage, unselected").performClick()
            compose.onNodeWithContentDescription("Research disadvantage, unselected").performClick()
            compose.onNodeWithContentDescription("Research disadvantage, selected").performClick()
            compose.onNodeWithContentDescription("Research Selection, unselected").performClick()
            assertTrue(committed.isEmpty())
            compose.onNodeWithText("Roll Queue (1)").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("13")
            compose.onNodeWithText("Roll All").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(20, committed.single().rolls.single().result.total)
            compose.onNodeWithText("Research: (13) + 7 = 20").performClick()
            compose.onNodeWithText("Copy All").performClick()
            assertEquals(1, committed.size)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun manualAndAuthorizedDrawsPreserveThreeDiceAndSignedCustomTerms() {
        val d20 = ManagerFormula(DiceFormula(listOf(DiceTerm(1,20)),3), DiceMode.ADVANTAGE, 3)
        val manual = composeRollEvidence(d20, "5 19 19") { error("Manual rolls must not draw.") }
        assertEquals(listOf(false,true,false), manual.faces.map { it.kept }); assertEquals(22,manual.total)
        val rng = listOf(7,1,2).iterator()
        val drawn = composeRollEvidence(d20, null) { rng.next() }
        assertEquals(10,drawn.total); assertFalse(drawn.manual)
        val mixed = composeRollEvidence(ManagerFormula(SecureDice.parse("d8-d4+3"),DiceMode.NORMAL), "6 2")
        assertEquals(7,mixed.total); assertEquals(listOf(1,-1),mixed.faces.map { it.sign })
    }
}
