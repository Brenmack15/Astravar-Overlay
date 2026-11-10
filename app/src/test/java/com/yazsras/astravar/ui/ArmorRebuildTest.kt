package com.yazsras.astravar.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.rules.DamageType
import com.yazsras.astravar.rules.Profile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h760dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArmorRebuildTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var requestedSize: String? = null

    @Before fun prepareNativeDialogSizing() {
        requestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreNativeDialogSizing() {
        requestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    @Test fun choosingUnlockedElementClosesAndSelectingItAgainDoesNotToggleItOff() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val chosen = mutableListOf<DamageType>()
            var closed = 0
            activity.get().setContent { AstravarTheme {
                ElementWheelV2(Profile(), emptyList(), { closed++ }, { chosen += it })
            } }
            compose.onNodeWithContentDescription("Cold element").performClick()
            compose.runOnIdle {
                assertEquals(listOf(DamageType.COLD), chosen)
                assertEquals(1, closed)
            }
            activity.get().setContent { AstravarTheme {
                ElementWheelV2(Profile(), listOf(DamageType.COLD), { closed++ }, { chosen += it })
            } }
            compose.onNodeWithContentDescription("Cold element").performClick()
            compose.runOnIdle {
                assertEquals(listOf(DamageType.COLD), chosen)
                assertEquals(2, closed)
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun lockedAdvancedElementCannotChangeTheSharedSelection() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val chosen = mutableListOf<DamageType>()
            var closed = false
            activity.get().setContent { AstravarTheme {
                ElementWheelV2(Profile(), emptyList(), { closed = true }, { chosen += it })
            } }
            compose.onNodeWithContentDescription("Radiant locked element").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Neutral Force").performClick()
            compose.runOnIdle { assertTrue(chosen.isEmpty()); assertTrue(closed) }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun dismissingEditedAbilityKeepsDraftUntilDiscardAndNeverWritesIt() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        runBlocking {
            repo.records.saveProfile(CharacterProfile(name = "Synthetic character"), source = "UI fixture")
            repo.records.saveAbility(CharacterAbility(key = "DEX", score = 14, magicModifier = 2), source = "UI fixture")
        }
        val before = runBlocking { db.recordsDao().allAudit() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { CharacterOverviewV2(repo) } } }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Dexterity score 14", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("ability-DEX").performScrollTo().performClick()
            compose.onNodeWithText("Ability score").performTextClearance()
            compose.onNodeWithText("Ability score").performTextInput("18")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertExists()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Advanced · recorded modifiers & sources").performClick()
            compose.onNodeWithText("Recorded modifier adjustment").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2")))
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            runBlocking {
                assertEquals(14, db.recordsDao().characterAbility("local-player", "DEX")?.score)
                assertEquals(2, db.recordsDao().characterAbility("local-player", "DEX")?.magicModifier)
                assertEquals(before, db.recordsDao().allAudit())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowDoubleTextArmorKeepsWholeSystemLabelsAndOpensCoreReference() =
        verifyDoubleTextSystemCards("armor-systems-360-font200")

    @Test
    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun wideDoubleTextArmorKeepsWholeSystemLabelsAndOpensCoreReference() =
        verifyDoubleTextSystemCards("armor-systems-400-font200")

    @Suppress("DEPRECATION")
    private fun verifyDoubleTextSystemCards(captureName: String) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repository = CampaignLedgerRepository(db)
        val before = runBlocking { db.recordsDao().allAudit() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val resources = activity.get().resources
        val original = Configuration(resources.configuration)
        var elementOpens = 0
        try {
            // Both tools are native Dialog compositions; verify the actual resource
            // font setting inside each window instead of relying on a parent provider.
            resources.updateConfiguration(Configuration(original).apply { fontScale = 2f }, resources.displayMetrics)
            activity.get().setContent { AstravarTheme {
                RebuildToolSheet("Armor systems", {}) {
                    ArmorOverviewV2(Campaign(), repository, {}, {}, {}, {}, selectElement = { elementOpens++ })
                }
            } }
            val active = compose.onNode(hasText("Active element") and hasClickAction())
                .performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            active.performClick()
            assertEquals(1, elementOpens)
            val core = compose.onNode(hasText("Capabilities") and hasClickAction())
                .performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.runOnIdle { invalidateNativeCaptureWindows() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { captureNativeTestWindows(captureName, activity.get(), modal = true) }
            for (label in listOf("Active element", "Core systems", "Capabilities")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertEquals("Dialog text must use the real 2x setting: $label", 2f, layout.layoutInput.density.fontScale, .01f)
                assertEquals("System label must remain whole: $label", 1, layout.lineCount)
                assertFalse("System label must not be ellipsized: $label", layout.isLineEllipsized(0))
                assertTrue("System label must fit its text allocation: $label",
                    layout.getLineLeft(0) >= -.5f && layout.getLineRight(0) <= layout.size.width + .5f &&
                        layout.getLineBottom(0) <= layout.size.height + .5f)
            }
            core.performClick()
            compose.onNodeWithText("Null Ward").performScrollTo().assertIsDisplayed()
            val detailLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Null Ward", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(detailLayouts)) }
            assertEquals(2f, detailLayouts.single().layoutInput.density.fontScale, .01f)
            assertEquals("Opening references must not alter campaign history", before, runBlocking { db.recordsDao().allAudit() })
        } finally {
            resources.updateConfiguration(original, resources.displayMetrics)
            activity.pause().stop().destroy()
            db.close()
        }
    }

}
