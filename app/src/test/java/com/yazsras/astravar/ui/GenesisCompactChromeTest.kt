package com.yazsras.astravar.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
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
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisCompactChromeTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun fullLandscapeScaffoldAtDoubleFontKeepsContentAndEveryCharacterControlReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources
        val original = Configuration(resources.configuration)
        val previousSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        resources.updateConfiguration(Configuration(original).apply { fontScale = 2f }, resources.displayMetrics)
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val playableDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val repository = CampaignLedgerRepository(db)
        val playable = ReforgeRepository(playableDb)
        val actor = ReforgeCharacter("fictional-compact", "Fictional voyager", currentHp = 8, baseMaxHp = 12)
        runBlocking { playable.create(actor); ReforgePreferences.open(context).selectProfile(actor.id) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                Scaffold(containerColor = RebuildPalette.Canvas,
                    topBar = { RebuildAppHeader(false, search = {}, tools = {}) },
                    bottomBar = { RebuildPrimaryNavigation("Character") {} }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) { ReforgeCharacterSheet(repository, playable) }
                }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("genesis-character-controls").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("genesis-character-controls").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("HP 8 / 12").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("genesis-character-pager").assertHeightIsAtLeast(64.dp)
            compose.onNodeWithTag("section-Features").performScrollTo().performClick()
            compose.onNodeWithText("Edit identity & features").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("section-Features").assertIsSelected()
            compose.runOnIdle { invalidateNativeCaptureWindows() }
            compose.waitForIdle()
            compose.runOnIdle { captureNativeTestWindows("genesis-short-landscape-font200", activity.get()) }
            compose.onNodeWithTag("genesis-character-controls").performClick()
            val switch = compose.onNode(hasContentDescription("Switch character") and hasAnyAncestor(isDialog()))
            switch.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            val text = compose.onNode(hasText("Switch character") and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .fetchSemanticsNode()
            compose.runOnIdle {
                assertTrue(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertEquals("The real dialog must retain the system font scale", 2f, layouts.single().layoutInput.density.fontScale, .01f)
            }
            compose.onNode(hasContentDescription("Armor Class") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
            compose.onNode(hasTestTag("quick-health") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            compose.onNodeWithText("Hit points").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("section-Features").assertIsSelected()
            assertEquals(1L, runBlocking { playable.get(actor.id) }!!.revision)
        } finally {
            activity.pause().stop().destroy()
            db.close(); playableDb.close()
            resources.updateConfiguration(original, resources.displayMetrics)
            if (previousSize == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", previousSize)
        }
    }
}
