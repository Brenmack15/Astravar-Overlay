package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
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
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisConsolidationNavigationTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun toolsRetainBackupAndAppSettingsWithoutCharacterMechanicsEntries() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val opened = mutableListOf<String>()
        try {
            activity.get().setContent { AstravarTheme { RebuildConfigHub { opened += it } } }
            listOf("Armor systems", "Armor weapon", "Advanced combat & magic", "Spell reservoir",
                "Spell library", "Rules & profile", "Resources & recovery", "Roll history").forEach {
                compose.onNodeWithText(it).assertDoesNotExist()
            }
            compose.onNodeWithText("Appearance").assertIsDisplayed()
            compose.onNodeWithText("Records, backups & connection").performScrollTo().performClick()
            compose.onNodeWithText("Original armor archive").performScrollTo().performClick()
            assertEquals(listOf("Weapon backup"), opened)
            compose.onNodeWithText("Complete backup").performScrollTo().assertExists()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun restoredOldRoutesResolveToTheirCharacterPages() {
        mapOf("Armor systems" to "Features", "Rules & profile" to "Features", "Armor weapon" to "Combat",
            "Spell reservoir" to "Spells", "Spell library" to "Spells", "Resources & recovery" to "Resources",
            "Roll history" to "History").forEach { (route, page) -> assertEquals(page, genesisRetiredToolSection(route)) }
        assertNull(genesisRetiredToolSection("Complete backup"))
        assertNull(genesisRetiredToolSection(null))
    }
}
