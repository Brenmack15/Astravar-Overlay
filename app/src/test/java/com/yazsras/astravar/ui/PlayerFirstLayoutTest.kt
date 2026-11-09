package com.yazsras.astravar.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
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
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerFirstLayoutTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun largeTextArmorAllowsScrollingToBothSystemCardsAndEditingAllSixAbilities() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    AstravarTheme { ArmorOverviewV2(Campaign(), CampaignLedgerRepository(db), {}, {}, {}, {}) }
                }
            }
            for (key in listOf("STR", "DEX", "CON", "INT", "WIS", "CHA"))
                compose.onNodeWithTag("ability-$key").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Active element").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Capabilities").performScrollTo().assertIsDisplayed()
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun allFourPrimaryDestinationsRemainReachableAtLargeText() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val selected = mutableListOf<String>()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    AstravarTheme { Column { RebuildPrimaryNavigation("Character", selected::add) } }
                }
            }
            for (name in listOf("Character", "Inventory", "Companions", "Campaign"))
                compose.onNode(hasText(name) and hasClickAction()).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf("Character", "Inventory", "Companions", "Campaign"), selected)
        } finally { activity.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun longSheetTitlePreservesScrollableBodyAndActionsInLandscapeAtDoubleText() {
        val previousSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val title = "Mithral focusing assembly with a deliberately long fictional inventory name and carefully recorded provenance"
        var saves = 0
        var closes = 0
        try {
            activity.get().setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 2f }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                    LocalConfiguration provides configuration) {
                    AstravarTheme {
                        PlayerSheet(title, { closes++ }, actions = {
                            PlayerButton(onClick = { saves++ }) { Text("Save review") }
                        }) {
                            Text("Fictional item details remain reachable")
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription(title).assertIsDisplayed()
            compose.onAllNodesWithText(title).assertCountEquals(1)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .assertHeightIsAtLeast(24.dp)
            compose.onNode(hasText("Close") and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText("Save review") and hasClickAction()).assertIsDisplayed().performClick()
            compose.onNodeWithText("Fictional item details remain reachable").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, saves)
                assertEquals(0, closes)
                captureNativeTestWindows("player-sheet-long-title-landscape-font200", activity.get(), modal = true)
            }
            compose.onNode(hasText("Close") and hasClickAction()).performClick()
            compose.runOnIdle { assertEquals(1, closes) }
        } finally {
            activity.pause().stop().destroy()
            previousSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
                ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
        }
    }
}
