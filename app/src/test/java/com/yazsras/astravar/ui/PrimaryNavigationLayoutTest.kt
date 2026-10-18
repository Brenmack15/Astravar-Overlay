package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrimaryNavigationLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun normalTextKeepsAllFiveReadableDestinationsInOneRow() = verifyNavigation(1f, 1)

    @Test fun doubleTextKeepsCompleteSingleLineLabelsAndSelectedNavigationInTwoRows() = verifyNavigation(2f, 2)

    private fun verifyNavigation(fontScale: Float, expectedRows: Int) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val visited = mutableListOf<String>()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    AstravarTheme {
                        var selected by remember { mutableStateOf("Armor") }
                        Box(Modifier.width(360.dp)) {
                            RebuildPrimaryNavigation(selected) { selected = it; visited += it }
                        }
                    }
                }
            }
            val controlBounds = rebuildDestinations.map { name ->
                val text = compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.runOnIdle {
                    assertTrue("$name must expose its real text layout",
                        text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                    val layout = layouts.single()
                    assertEquals("$name must retain the requested accessibility font scale", fontScale, layout.layoutInput.density.fontScale, .01f)
                    assertEquals("The entire $name label must stay on one line", 1, layout.lineCount)
                    assertFalse("$name must not be clipped or ellipsized: size=${layout.size}; " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                        "constraints=${layout.layoutInput.constraints}; widthOverflow=${layout.didOverflowWidth}; " +
                        "heightOverflow=${layout.didOverflowHeight}; line=${layout.getLineLeft(0)},${layout.getLineTop(0)}-" +
                        "${layout.getLineRight(0)},${layout.getLineBottom(0)}", layout.hasVisualOverflow)
                }
                compose.onNode(hasText(name) and hasClickAction())
                    .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode().boundsInRoot
            }
            assertEquals("Navigation should add rows only when scaled labels require it", expectedRows,
                controlBounds.map { it.top }.distinct().size)
            for (name in rebuildDestinations) {
                compose.onNode(hasText(name) and hasClickAction()).performClick().assertIsSelected()
                for (other in rebuildDestinations.filterNot { it == name })
                    compose.onNode(hasText(other) and hasClickAction()).assertIsNotSelected()
            }
            assertEquals(rebuildDestinations, visited)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
