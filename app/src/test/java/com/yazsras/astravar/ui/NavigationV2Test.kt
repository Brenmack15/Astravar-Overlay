package com.yazsras.astravar.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    @Test(timeout = 90_000) fun campaignPagesAndBackupsRemainAccessibleBeforeWeaponOnboardingWithoutChangingWeaponState() {
        val app = ApplicationProvider.getApplicationContext<AstravarApp>()
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
            compose.onNodeWithText("ARMOR & ARCANE SYSTEMS").assertExists()
            capture("v2-armor", activity.get())
            val before = app.repository.state.value!!
            assertFalse(before.onboarded)
            assertFalse(before.attuned)
            compose.onNodeWithText("Inventory").performClick()
            compose.onNodeWithText("Owned items & equipment").assertExists()
            compose.onNodeWithText("Inventory").assertIsSelected()
            capture("v2-inventory", activity.get())
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Unsaved navigation test")
            compose.onNodeWithText("Campaign").performClick()
            compose.onNodeWithText("People, groups & beings").assertExists()
            compose.onNodeWithText("Connections").assertIsSelected()
            capture("v2-connections", activity.get())
            compose.onNodeWithText("Inventions").performClick()
            compose.onNodeWithText("Workshop").assertExists()
            compose.onNodeWithText("Eek").performClick()
            compose.onNodeWithText("Mechanical owl companion").assertExists()
            compose.onNodeWithText("Eek").assertIsSelected()
            capture("v2-eek", activity.get())
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Campaign backup & restore").performScrollTo().performClick()
            compose.onNodeWithText("Complete campaign export").assertExists()
            compose.onNodeWithText("Astravar").performClick()
            compose.onNodeWithText("Combat").performClick()
            compose.onNodeWithText("Confirm your weapon profile").assertExists()
            compose.onNodeWithText("Inventory").performClick()
            compose.onNodeWithText("Owned items & equipment").assertExists()
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
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun primaryNavigationExposesSelectedDestinationAndReportsEachUserSelection() {
        var selected by mutableStateOf("Astravar")
        val visited = mutableListOf<String>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                AstravarTheme { CampaignNavigationV2(selected) { selected = it; visited += it } }
            }
            compose.onNodeWithText("Astravar").assertIsSelected()
            listOf("Inventory", "Campaign", "More", "Astravar").forEach { destination ->
                compose.onNodeWithText(destination).performClick()
                compose.onNodeWithText(destination).assertIsSelected()
            }
            assertEquals(listOf("Inventory", "Campaign", "More", "Astravar"), visited)
        } finally { activity.pause().stop().destroy() }
    }
}
