package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.AscensionWorkerFixture
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w440dp-h1000dp",application=AstravarApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudDeviceToolsTest {
    @get:Rule val compose=createAstravarComposeRule()
    @Test fun enrolledToolsKeepsPromptsAppearanceAndAuthorityAwareDiagnostics(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val preferences=(context.applicationContext as AstravarApp).preferences
        val start=preferences.flow.first().haptics
        AscensionWorkerFixture.open(context).use { f->
            val snapshot=f.refresh()
            val a=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                a.get().setContent { AstravarTheme {CloudWorkspace(f.client,snapshot)} }
                compose.onNodeWithText("Tools").performClick()
                compose.onNodeWithText("ChatGPT Prompts").performScrollTo().performClick()
                compose.waitUntil(15000) {runCatching{compose.onNodeWithText("Add Prompt").assertIsEnabled()}.isSuccess}
                compose.onNodeWithContentDescription("Back to Owner connection").performClick()
                compose.onNodeWithText("Appearance & feedback").performScrollTo().performClick()
                compose.onNodeWithText("Haptic feedback").performClick()
                compose.waitUntil(10000){runBlocking { preferences.flow.first().haptics!=start }}
                compose.onNodeWithText("Choose personal artwork").assertExists()
                compose.onNodeWithContentDescription("Back to Owner connection").performClick()
                compose.onNodeWithText("Help & diagnostics").performScrollTo().performClick()
                compose.onNodeWithText("Installed signing certificate SHA-256").assertExists()
                compose.onNodeWithText("Your character, inventory and campaign share",substring=true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Back to Owner connection").performClick()
                compose.onNodeWithText("Campaign roster").assertExists()
                assertEquals(snapshot.version.revision,f.refresh().version.revision)
            } finally { preferences.haptics(start);a.pause().stop().destroy() }
        }
    }

    @Test fun enrolledRecoveryHubOpensEachOriginalStoreWithoutSubmittingCloudGameplay(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AscensionWorkerFixture.open(context).use { fixture ->
            val original = fixture.refresh()
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme { CloudWorkspace(fixture.client, original) } }
                compose.onNodeWithText("Tools").performClick()
                compose.onNodeWithText("Local backup and recovery").performScrollTo().performClick()
                compose.onNodeWithText("These tools read and restore", substring = true).assertExists()
                val pages = mapOf(
                    "Complete backup" to "Choose backup",
                    "Character backup" to "Choose character backup",
                    "Campaign backup" to "Choose file for preview",
                    "Campaign import" to "Choose campaign archive",
                    "Original armor archive" to "Choose weapon backup to preview"
                )
                for ((page, preview) in pages) {
                    compose.onNodeWithText(page).performScrollTo().performClick()
                    compose.waitUntil(15000) {
                        compose.onAllNodesWithText(preview).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText(preview).performScrollTo().assertIsEnabled()
                    compose.onNodeWithText("Back to local recovery").performClick()
                }
                compose.onNodeWithText("Close").performClick()
                assertEquals(original.version.revision, fixture.refresh().version.revision)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }
}
