package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.ReforgePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisQuickActionsUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val preferences get() = ReforgePreferences.open(ApplicationProvider.getApplicationContext())
    private fun actor() = "quick-ui-${UUID.randomUUID()}"
    private fun saved(profile: String) = runBlocking { preferences.quickActions(profile).first() }
    private fun seed(profile: String, actions: List<String>) = runBlocking { preferences.saveQuickActions(profile, actions) }

    private fun surface(content: @Composable () -> Unit, block: () -> Unit) {
        val old = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { content() } }
            block()
        } finally {
            controller.pause().stop().destroy()
            if (old == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", old)
        }
    }

    private fun awaitLoaded() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("quick-customize") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun open() {
        awaitLoaded()
        compose.onNodeWithTag("quick-customize").performClick()
        compose.onNodeWithText("Save quick actions").assertIsDisplayed()
    }

    @Test fun accessibleReorderRemoveAndPinPersistAfterActivityReopen() {
        val id = actor()
        seed(id, listOf("health", "attack"))
        val selected = mutableListOf<String>()
        surface({ GenesisQuickActions(id) { selected += it } }) {
            open()
            compose.onNodeWithContentDescription("Move Actions up").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Remove Health quick action").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Remove Actions quick action").assertIsNotEnabled()
            compose.onNodeWithText("Pin Rest").performScrollTo().performClick()
            compose.onNodeWithText("Save quick actions").performClick()
            compose.waitUntil(10_000) { saved(id) == listOf("attack", "rest") }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("quick-rest").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("quick-rest").performClick()
            assertEquals(listOf("rest"), selected)
        }
        surface({ GenesisQuickActions(id) {} }) {
            awaitLoaded()
            compose.onNodeWithTag("quick-health").assertDoesNotExist()
            compose.onNodeWithTag("quick-rest").assertIsDisplayed()
            open()
            compose.onNodeWithContentDescription("Move Actions up").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Move Rest up").assertIsEnabled()
            assertEquals(listOf("attack", "rest"), saved(id))
        }
    }

    @Test fun profileSwitchLoadsOnlyItsOwnSavedActionsAndDismissesOtherProfilesEditor() {
        val a = actor(); val b = actor()
        seed(a, listOf("health", "rest")); seed(b, listOf("dice", "inventory"))
        val current = mutableStateOf(a)
        val selected = mutableListOf<Pair<String, String>>()
        surface({ GenesisQuickActions(current.value) { selected += current.value to it } }) {
            awaitLoaded()
            compose.onNodeWithTag("quick-health").assertIsDisplayed()
            open()
            compose.runOnIdle { current.value = b }
            awaitLoaded()
            compose.onNodeWithText("Save quick actions").assertDoesNotExist()
            compose.onNodeWithTag("quick-health").assertDoesNotExist()
            compose.onNodeWithTag("quick-rest").assertDoesNotExist()
            compose.onNodeWithTag("quick-dice").performClick()
            assertEquals(listOf(b to "dice"), selected)
            assertEquals(listOf("health", "rest"), saved(a))
            assertEquals(listOf("dice", "inventory"), saved(b))
        }
    }

    @Test fun longPressDragReordersTheSavedActionsWithoutInvokingGameplay() {
        val id = actor()
        seed(id, listOf("health", "attack", "spells"))
        var gameplayCalls = 0
        surface({ GenesisQuickActions(id) { gameplayCalls++ } }) {
            open()
            val healthBounds = compose.onNodeWithTag("quick-drag-health").fetchSemanticsNode().boundsInRoot
            val actionBounds = compose.onNodeWithTag("quick-drag-attack").fetchSemanticsNode().boundsInRoot
            val travel = actionBounds.center.y - healthBounds.center.y + 12f
            compose.onNodeWithTag("quick-drag-health").performTouchInput {
                down(center)
                advanceEventTime(750)
                moveBy(Offset(0f, travel), delayMillis = 200)
                up()
            }
            compose.waitForIdle()
            assertTrue("Dragging Health one row down must move Actions before it",
                compose.onNodeWithTag("quick-drag-attack").fetchSemanticsNode().boundsInRoot.top <
                    compose.onNodeWithTag("quick-drag-health").fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithText("Save quick actions").performClick()
            compose.waitUntil(10_000) { saved(id) == listOf("attack", "health", "spells") }
            assertEquals(0, gameplayCalls)
        }
    }

    @Test fun cancellingChangesLeavesTheSavedLayoutIntact() {
        val id = actor()
        seed(id, listOf("rest", "dice"))
        surface({ GenesisQuickActions(id) {} }) {
            open()
            compose.onNodeWithContentDescription("Move Dice up").performScrollTo().performClick()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            assertEquals(listOf("rest", "dice"), saved(id))
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard").performClick()
            assertEquals(listOf("rest", "dice"), saved(id))
            open()
            compose.onNodeWithContentDescription("Move Rest up").assertIsNotEnabled()
        }
    }
}
