package com.yazsras.astravar.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.CampaignCheckpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Reproduces the 400dp-wide native failure with the actual app header, routes and bottom navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h889dp-xxhdpi", application = AstravarApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisCampaignResponsiveTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun capture(name: String, activity: ComponentActivity) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, activity) }
    }

    private fun scrollPosition(section: String): Float = compose.onNodeWithTag("genesis-campaign-scroll-$section")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertWholeText(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals("The actual app must retain the system text scale", 2f, layout.layoutInput.density.fontScale, .01f)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$label is vertically clipped: $bounds versus ${layout.size}", bounds.height + 1f >= layout.size.height)
        for (line in 0 until layout.lineCount) {
            assertFalse("$label is ellipsized", layout.isLineEllipsized(line))
            assertTrue("$label overflows its line", layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f)
        }
        val viewport = compose.onNodeWithTag("genesis-campaign-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("$label is covered by the tab strip", bounds.top >= viewport.top - 1f)
        assertTrue("$label is covered by primary navigation", bounds.bottom <= viewport.bottom + 1f)
    }

    private fun savedState(controller: ActivityController<ComponentActivity>): Bundle {
        val state = Bundle()
        controller.pause().saveInstanceState(state).resume()
        val writer = Parcel.obtain()
        val bytes = try { state.writeToParcel(writer, 0); writer.marshall() } finally { writer.recycle() }
        val reader = Parcel.obtain()
        return try {
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(reader).also { it.classLoader = ComponentActivity::class.java.classLoader }
        } finally { reader.recycle() }
    }

    @OptIn(androidx.room.ExperimentalRoomApi::class)
    @Suppress("DEPRECATION")
    @Test(timeout = 120_000) fun doubleTextJournalScrollsCampaignChromeAndRetainsReadingPositionAcrossNavigationAndRecreation() {
        val app = ApplicationProvider.getApplicationContext<AstravarApp>()
        val resources = app.resources
        val original = Configuration(resources.configuration)
        val previousSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        resources.updateConfiguration(Configuration(original).apply { fontScale = 2f }, resources.displayMetrics)
        val invalidationIntent = Intent(app, MultiInstanceInvalidationService::class.java)
        val invalidationService = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(invalidationIntent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), invalidationService.get().onBind(invalidationIntent))
        var activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun mount() { activity.get().setContent { AstravarScreen(app, {}, {}, {}) } }
        try {
            runBlocking {
                app.campaignLedger.party.saveCheckpoint(CampaignCheckpoint(id = "fictional-responsive-checkpoint",
                    title = "Fictional observatory pause", scene = "A fictional hilltop", lastConfirmedAction = "Read the map",
                    unresolvedChoice = "Choose the next path"), source = "Synthetic responsive campaign fixture")
            }
            val beforeRecords = runBlocking { Json.parseToJsonElement(app.campaignLedger.export()).jsonObject.getValue("records") }
            mount()
            compose.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                app.repository.error.value?.let { error("Weapon repository could not open: $it") }
                app.repository.state.value != null
            }
            compose.onNodeWithTag("navigation-Campaign").performClick()
            compose.onNodeWithTag("section-Journal").assertIsDisplayed().performClick()
            // There is one page heading plus the bottom navigation label, never a
            // second nested Campaign heading taking space above the journal.
            compose.onAllNodesWithText("Campaign", useUnmergedTree = true).assertCountEquals(2)
            compose.onNodeWithText("Your living world").performScrollTo()
            assertWholeText("Your living world")
            compose.onNodeWithText("Choose campaign").assertIsNotDisplayed()
            compose.onNodeWithTag("section-Journal").assertIsDisplayed().assertIsSelected()
            capture("genesis-campaign-journal-font200-readable", activity.get())

            // Every old route remains reachable through the same scroll surface.
            for (label in listOf("Search everything", "Connections", "Inventions", "Checkpoints")) {
                compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
                assertWholeText(label)
            }
            compose.onNodeWithText("Fictional observatory pause").performScrollTo()
            assertWholeText("Fictional observatory pause")
            val journalPosition = scrollPosition("Journal")
            assertTrue("The journal must actually have been scrolled", journalPosition > 0f)
            compose.onNodeWithTag("section-People").performClick()
            compose.onNodeWithText("No matching members").performScrollTo().assertIsDisplayed()
            val peoplePosition = scrollPosition("People")
            compose.onNodeWithTag("section-Journal").performClick()
            assertEquals(journalPosition, scrollPosition("Journal"), 1f)
            compose.onNodeWithText("Fictional observatory pause").assertIsDisplayed()
            compose.onNodeWithTag("section-People").performClick()
            assertEquals(peoplePosition, scrollPosition("People"), 1f)
            compose.onNodeWithTag("section-Journal").performClick()

            // Open a real detail route, then return through the real Android Back handler.
            compose.onNode(hasText("Connections") and hasClickAction()).performScrollTo()
            val beforeDetail = scrollPosition("Journal")
            compose.onNode(hasText("Connections") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Add connection").assertExists()
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("section-Journal").assertIsSelected()
            assertEquals(beforeDetail, scrollPosition("Journal"), 1f)
            compose.onNodeWithTag("navigation-Companions").performClick()
            compose.onNodeWithTag("navigation-Campaign").performClick()
            compose.onNodeWithTag("section-Journal").assertIsSelected()
            assertEquals(beforeDetail, scrollPosition("Journal"), 1f)

            val state = savedState(activity)
            activity.pause().stop().destroy()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).create(state).start().resume().visible()
            mount()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("genesis-campaign-scroll-Journal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("navigation-Campaign").assertIsSelected()
            compose.onNodeWithTag("section-Journal").assertIsSelected()
            assertEquals(beforeDetail, scrollPosition("Journal"), 1f)
            compose.onNodeWithText("Your living world").performScrollTo()
            assertWholeText("Your living world")
            capture("genesis-campaign-journal-font200-restored", activity.get())
            compose.onNodeWithText("Library & source records").performScrollTo().performClick()
            compose.onNode(hasText("Reference Library") and hasClickAction()).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("Campaign import") and hasClickAction()).performScrollTo().assertIsDisplayed()
            val expandedLibraryPosition = scrollPosition("Journal")
            compose.onNodeWithTag("section-People").performClick()
            compose.onNodeWithTag("section-Journal").performClick()
            compose.onNode(hasText("Campaign import") and hasClickAction()).assertIsDisplayed()
            assertEquals("An expanded library and its reading position survive section changes",
                expandedLibraryPosition, scrollPosition("Journal"), 1f)
            assertEquals("Navigation must not change retained campaign records", beforeRecords,
                runBlocking { Json.parseToJsonElement(app.campaignLedger.export()).jsonObject.getValue("records") })
        } finally {
            activity.pause().stop().destroy()
            invalidationService.destroy()
            resources.updateConfiguration(original, resources.displayMetrics)
            if (previousSize == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", previousSize)
        }
    }
}
