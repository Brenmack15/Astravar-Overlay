package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
    @get:Rule val compose = createEmptyComposeRule()

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

    @Test fun allFivePrimaryDestinationsRemainReachableAtLargeText() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val selected = mutableListOf<String>()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    AstravarTheme { Column { RebuildPrimaryNavigation("Armor", selected::add) } }
                }
            }
            for (name in listOf("Armor", "Combat", "Party", "Campaign", "Config"))
                compose.onNode(hasText(name) and hasClickAction()).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf("Armor", "Combat", "Party", "Campaign", "Config"), selected)
        } finally { activity.pause().stop().destroy() }
    }
}
