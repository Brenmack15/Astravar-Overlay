package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.Ability
import com.yazsras.astravar.rules.DamageType
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
class GenesisArmorElementResponsiveTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun assertSingleReadableLine(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val result = layouts.single()
        assertEquals("$text must not be squeezed into fragments", 1, result.lineCount)
        assertFalse("$text must remain fully visible: width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, " +
            "size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
            "line=${result.getLineLeft(0)}..${result.getLineRight(0)}, bounds=${node.fetchSemanticsNode().boundsInRoot}", result.hasVisualOverflow)
        assertFalse(result.isLineEllipsized(0))
    }

    private fun verify(width: Dp, fontScale: Float, separateAction: Boolean, screenshot: String) {
        var changes = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    AstravarTheme { PlayerCard(Modifier.width(width)) {
                        GenesisArmorElementHeader(Ability.ELEMENTAL, listOf(DamageType.LIGHTNING), true) { changes++ }
                    } }
                }
            }
            if (System.getProperty("astravar.screenshots") != null) {
                compose.runOnIdle { invalidateNativeCaptureWindows() }
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                compose.runOnIdle { captureNativeTestWindows(screenshot, controller.get()) }
            }
            assertSingleReadableLine("Active element")
            assertSingleReadableLine("Lightning")
            assertSingleReadableLine("Change element")
            compose.onNodeWithTag("genesis-active-element-art").assertIsDisplayed().assertWidthIsEqualTo(52.dp)
            val label = compose.onNodeWithText("Active element").fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithText("Lightning").fetchSemanticsNode().boundsInRoot
            val action = compose.onNode(hasText("Change element") and hasClickAction())
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val actionBounds = action.fetchSemanticsNode().boundsInRoot
            if (separateAction) assertTrue("The action must be below the complete element value", actionBounds.top >= value.bottom)
            else assertTrue("Normal width keeps the action beside the element", actionBounds.top < value.bottom && actionBounds.bottom > label.top)
            action.performClick()
            assertEquals(1, changes)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun regularWidthKeepsReadableCompactHeader() =
        verify(400.dp, 1f, false, "genesis-active-element-normal")

    @Test fun narrowWidthPlacesChangeActionBelowElement() =
        verify(280.dp, 1f, true, "genesis-active-element-narrow")

    @Test fun doubledFontKeepsWholeLabelAndValueWithActionBelow() =
        verify(320.dp, 2f, true, "genesis-active-element-font200")
}
