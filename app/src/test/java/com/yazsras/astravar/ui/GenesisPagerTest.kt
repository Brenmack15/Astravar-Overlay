package com.yazsras.astravar.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
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
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisPagerTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun horizontalFlickAndTabSelectionMoveTheSamePagerAndBackReturnsToSheet() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisCharacterPager("fictional-actor") { page -> Text("Content for $page"); Spacer(Modifier.height(1200.dp)) }
            } }
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
            compose.onNodeWithText("Content for Combat").assertIsDisplayed()
            compose.onNodeWithTag("section-Equipment").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Content for Equipment").assertIsDisplayed()
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeRight() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun verticalReadingPositionsAndCurrentPageSurviveCompositionRestoration() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var registry = SaveableStateRegistry(null) { true }
        val shown = mutableStateOf(true)
        try {
            activity.get().setContent {
                if (shown.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme { GenesisCharacterPager("fictional-actor") { page ->
                        repeat(35) { Text("$page row $it", Modifier.height(56.dp)) }
                    } }
                }
            }
            compose.onNodeWithText("Sheet row 20").performScrollTo()
            val sheetOffset = scrollOffset("Sheet")
            assertTrue(sheetOffset > 0)
            compose.onNodeWithTag("section-Combat").performClick()
            compose.waitForIdle()
            assertEquals(0f, scrollOffset("Combat"), 0.5f)
            compose.onNodeWithText("Combat row 15").performScrollTo()
            val combatOffset = scrollOffset("Combat")
            compose.onNodeWithTag("section-Sheet").performClick()
            compose.waitForIdle()
            assertEquals(sheetOffset, scrollOffset("Sheet"), 0.5f)
            compose.onNodeWithTag("section-Combat").performClick()
            compose.waitForIdle()
            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { saved = registry.performSave(); shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; shown.value = true }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
            assertEquals(combatOffset, scrollOffset("Combat"), 0.5f)
            compose.onNodeWithTag("section-Sheet").performClick()
            compose.waitForIdle()
            assertEquals(sheetOffset, scrollOffset("Sheet"), 0.5f)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun horizontalFiltersOwnTheirGestureEvenAtTheListBoundary() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisCharacterPager("fictional-actor") { page ->
                    if (page == "Sheet") PlayerTabs(listOf("All", "Known", "Prepared"), "All", {},
                        Modifier.genesisHorizontalControl().testTag("nested-filter"))
                    Text("Content for $page")
                    Spacer(Modifier.height(1200.dp))
                }
            } }
            compose.onNodeWithTag("nested-filter").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun routeRequestsAreConsumedOnceAndActorChangesCannotLeakThePreviousPage() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val actor = mutableStateOf("fictional-first")
        val route = mutableStateOf("Sheet")
        val token = mutableIntStateOf(0)
        try {
            activity.get().setContent { AstravarTheme {
                GenesisCharacterPager(actor.value, route.value, token.intValue) { page -> Text("${actor.value} $page") }
            } }
            compose.runOnIdle { route.value = "Equipment"; token.intValue++ }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Equipment").assertIsSelected()
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Spells").assertIsSelected()
            compose.runOnIdle { route.value = "Sheet" }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Spells").assertIsSelected()
            compose.runOnIdle { actor.value = "fictional-second" }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            compose.onNodeWithText("fictional-second Sheet").assertIsDisplayed()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun richTextTableOwnsHorizontalDraggingAtBothEndsButAllowsVerticalReading() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisCharacterPager("fictional-actor") { page ->
                    if (page == "Sheet") ReforgeRichText("| Name | Range | Duration |\n| --- | --- | --- |\n| Ward | Touch | One hour |")
                    repeat(30) { Text("$page row $it", Modifier.height(56.dp)) }
                }
            } }
            val table = compose.onNodeWithTag("reforge-rich-table")
            assertTrue(table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
            repeat(3) { table.performTouchInput { swipeLeft() }; compose.waitForIdle() }
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            repeat(3) { table.performTouchInput { swipeRight() }; compose.waitForIdle() }
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            table.performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertTrue("Table gestures must not prevent vertical page scrolling", scrollOffset("Sheet") > 0f)
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun fittingRichTextTableDoesNotBecomeAnAccidentalPageSwipeRegion() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                GenesisCharacterPager("fictional-actor") { page ->
                    if (page == "Sheet") ReforgeRichText("| Name | Range |\n| --- | --- |\n| Ward | Touch |")
                    Spacer(Modifier.height(1200.dp))
                }
            } }
            val table = compose.onNodeWithTag("reforge-rich-table")
            assertEquals(0f, table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue(), .5f)
            table.performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun openModalSuspendsPagingWithoutLosingItsCurrentPosition() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val modal = mutableStateOf(true)
        try {
            activity.get().setContent { AstravarTheme {
                GenesisCharacterPager("fictional-actor", modalOpen = modal.value) { Text(it) }
            } }
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            compose.runOnIdle { modal.value = false }
            compose.onNodeWithTag("genesis-character-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Combat").assertIsSelected()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun scrolledPageControlsDoNotOccludeNativeSectionTouchTargets() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var pixelsPerDp = 0f
        try {
            activity.get().setContent { AstravarTheme {
                pixelsPerDp = LocalDensity.current.density
                GenesisCharacterPager("fictional-scrolled-spellbook", requestedSection = "Spells") { page ->
                    if (page == "Spells") PlayerDisclosure("Spell slots & preparation") {
                        Text("Prepared spell controls")
                    }
                    repeat(30) { Text("$page reading row $it", Modifier.height(56.dp)) }
                }
            } }
            compose.onNodeWithTag("section-Spells").assertIsSelected()
            // Leave the first real disclosure partly visible at the page's top.
            // Compose 1.10 expands its native touch bounds 24 dp above the scroll
            // viewport, which previously occluded the adjacent fixed tab strip.
            compose.onNodeWithTag("genesis-page-Spells").performSemanticsAction(SemanticsActions.ScrollBy) {
                assertTrue(it(0f, 38f * pixelsPerDp))
            }
            compose.waitForIdle()
            val readingOffset = scrollOffset("Spells")
            assertEquals(38f * pixelsPerDp, readingOffset, 1f)
            val viewport = compose.onNodeWithTag("genesis-page-Spells").fetchSemanticsNode().boundsInRoot
            val disclosure = compose.onNodeWithText("Spell slots & preparation").fetchSemanticsNode()
            assertTrue("The regression must exercise a partly clipped real disclosure",
                disclosure.positionInRoot.y < viewport.top && disclosure.boundsInRoot.bottom > viewport.top)

            // Read Android's accessibility provider, not just Compose's original
            // layout rectangle: sibling occlusion affects only these final bounds.
            val equipmentId = compose.onNodeWithTag("section-Equipment").fetchSemanticsNode().id
            val spellsId = compose.onNodeWithTag("section-Spells").fetchSemanticsNode().id
            val hint = compose.onNodeWithText("Swipe between sections").fetchSemanticsNode()
            val indicator = compose.onNodeWithTag("genesis-page-indicator").fetchSemanticsNode()
            compose.runOnIdle {
                val provider = requireNotNull(findAccessibilityProvider(activity.get().window.decorView))
                for ((id, selected) in listOf(equipmentId to false, spellsId to true)) {
                    val info = requireNotNull(provider.createAccessibilityNodeInfo(id))
                    val bounds = Rect().also(info::getBoundsInScreen)
                    assertTrue("Native section bounds must retain 48 dp after body scrolling: $bounds",
                        bounds.height() >= (48f * pixelsPerDp).roundToInt())
                    assertTrue("Native section width must retain 48 dp: $bounds",
                        bounds.width() >= (48f * pixelsPerDp).roundToInt())
                    assertTrue(info.isEnabled)
                    assertTrue(info.isVisibleToUser)
                    assertEquals(selected, info.isSelected)
                    if (!selected) assertTrue(info.isClickable)
                }
                for (chrome in listOf(hint, indicator)) {
                    val info = requireNotNull(provider.createAccessibilityNodeInfo(chrome.id))
                    val bounds = Rect().also(info::getBoundsInScreen)
                    assertTrue("The native page hint must remain wholly readable: $bounds",
                        info.isVisibleToUser && bounds.height() >= chrome.boundsInRoot.height.roundToInt() &&
                            bounds.width() >= chrome.boundsInRoot.width.roundToInt())
                }
                assertEquals("Spells, page 4 of 7", provider.createAccessibilityNodeInfo(indicator.id)?.stateDescription)
            }
            compose.onNodeWithTag("section-Equipment").performTouchInput {
                click(Offset(centerX, height - 1f))
            }
            compose.onNodeWithTag("section-Equipment").assertIsSelected()
            compose.onNodeWithTag("section-Spells").performClick()
            compose.onNodeWithTag("section-Spells").assertIsSelected()
            assertEquals("Navigation must retain the spellbook reading position", readingOffset, scrollOffset("Spells"), 1f)
            compose.onNodeWithText("Spell slots & preparation").performClick()
            compose.onNodeWithText("Prepared spell controls").assertIsDisplayed()
        } finally { activity.pause().stop().destroy() }
    }

    private fun findAccessibilityProvider(view: View): AccessibilityNodeProvider? {
        view.accessibilityNodeProvider?.let { return it }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findAccessibilityProvider(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun scrollOffset(page: String): Float = compose.onNodeWithTag("genesis-page-$page")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
