package com.yazsras.astravar.ui

import android.graphics.Bitmap
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.room.MultiInstanceInvalidationService
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.savePresentationCapture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp", application = AstravarApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationV2Test {
    @get:Rule val compose = createEmptyComposeRule()

    private fun capture(name: String, activity: ComponentActivity) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.waitForIdle()
        compose.runOnIdle {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(android.graphics.Canvas(bitmap)); savePresentationCapture(bitmap, name) }
            finally { bitmap.recycle() }
        }
    }

    @OptIn(androidx.room.ExperimentalRoomApi::class)
    @Test(timeout = 90_000) fun campaignPagesAndBackupsRemainAccessibleBeforeWeaponOnboardingWithoutChangingWeaponState() {
        val app = ApplicationProvider.getApplicationContext<AstravarApp>()
        // Android supplies a non-null ComponentName and the Room service binder when binding.
        // Robolectric's default unconfigured bind instead invokes the callback with nulls.
        // Use the actual service so production cross-instance invalidation stays enabled.
        val invalidationIntent = Intent(app, MultiInstanceInvalidationService::class.java)
        val invalidationService = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(invalidationIntent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), invalidationService.get().onBind(invalidationIntent))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var overlayStarts = 0
        var permissionRequests = 0
        var overlayStops = 0
        try {
            activity.get().setContent {
                AstravarScreen(app, { overlayStarts++ }, { permissionRequests++ }, { overlayStops++ })
            }
            compose.waitUntil(20_000) {
                // AstravarApp intentionally owns a Main.immediate scope. Room completes on IO,
                // then resumes its eager StateFlow through the Android main Handler. PAUSED
                // Robolectric loopers need explicit draining; advancing Compose's frame clock
                // alone does not execute that Handler continuation.
                shadowOf(Looper.getMainLooper()).idle()
                app.repository.error.value?.let { error("Weapon repository could not open: $it") }
                app.repository.state.value != null
            }
            compose.onNodeWithText("Ability scores").assertExists()
            capture("v2-armor", activity.get())
            val before = app.repository.state.value!!
            assertFalse(before.onboarded)
            assertFalse(before.attuned)
            compose.onNodeWithText("Campaign").performClick()
            compose.onNodeWithText("Inventory").performClick()
            compose.onNodeWithText("Add Item").assertExists()
            capture("v2-inventory", activity.get())
            compose.onNodeWithText("‹ Campaign").performClick()
            compose.onNodeWithText("Connections").performClick()
            compose.onNodeWithContentDescription("Add connection").assertExists()
            capture("v2-connections", activity.get())
            compose.onNodeWithText("‹ Campaign").performClick()
            compose.onNodeWithText("Inventions").performClick()
            compose.onNodeWithText("Add Invention").assertExists()
            compose.onNodeWithText("‹ Campaign").performClick()
            compose.onNodeWithText("Party").performClick()
            listOf("Zerg Hasma", "Eek", "Korr", "Veyrath").forEach { compose.onNodeWithText(it).assertExists() }
            capture("v2-party", activity.get())
            compose.onNodeWithText("Eek").performClick()
            capture("v2-eek-sheet", activity.get())
            compose.onNodeWithText("Eek's specialized companion tools").performScrollTo().performClick()
            compose.onNodeWithText("Condition unknown").assertExists()
            compose.runOnIdle { captureNativeTestWindows("v2-eek", activity.get(), modal = true) }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Config").performClick()
            compose.onNodeWithText("Campaign backup").performScrollTo().performClick()
            compose.onNodeWithText("Export campaign backup").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Combat").performClick()
            compose.onNodeWithText("CONFIRM YOUR WEAPON PROFILE").assertExists()
            compose.onNodeWithText("Campaign").performClick()
            compose.onNodeWithText("Inventory").assertExists()
            compose.onNodeWithText("Checkpoints").assertExists()
            compose.onNodeWithText("Party").performClick()
            compose.onNodeWithText("Eek's specialized companion tools").performScrollTo().assertExists()
            compose.runOnIdle { assertEquals(before, app.repository.state.value) }
            assertEquals(0, overlayStarts)
            assertEquals(0, permissionRequests)
            assertEquals(0, overlayStops)
            val exported = runBlocking { Json.parseToJsonElement(app.campaignLedger.export()).jsonObject }
            assertTrue(exported.getValue("items").jsonArray.isEmpty())
            val records = exported.getValue("records").jsonObject
            assertTrue(records.getValue("companionStates").jsonArray.isEmpty())
            assertTrue(records.getValue("projects").jsonArray.isEmpty())
            assertTrue(records.getValue("entities").jsonArray.isEmpty())
            assertTrue(records.getValue("characterProfiles").jsonArray.isEmpty())
            assertTrue(records.getValue("partySheets").jsonArray.isEmpty())
        } finally { activity.pause().stop().destroy(); invalidationService.destroy() }
    }

    @Test fun primaryNavigationExposesSelectedDestinationAndReportsEachUserSelection() {
        var selected by mutableStateOf("Armor")
        val visited = mutableListOf<String>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                AstravarTheme { RebuildPrimaryNavigation(selected) { selected = it; visited += it } }
            }
            compose.onNodeWithText("Armor").assertIsSelected()
            listOf("Combat", "Party", "Campaign", "Config", "Armor").forEach { destination ->
                compose.onNodeWithText(destination).performClick()
                compose.onNodeWithText(destination).assertIsSelected()
            }
            assertEquals(listOf("Combat", "Party", "Campaign", "Config", "Armor"), visited)
        } finally { activity.pause().stop().destroy() }
    }
}
