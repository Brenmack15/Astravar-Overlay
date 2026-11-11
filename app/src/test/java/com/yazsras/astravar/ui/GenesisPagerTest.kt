package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

    private fun scrollOffset(page: String): Float = compose.onNodeWithTag("genesis-page-$page")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
