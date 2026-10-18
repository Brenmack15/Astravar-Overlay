package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
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
    @get:Rule val compose = createEmptyComposeRule()
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
            compose.onNodeWithText("Raw ability score (blank = unknown)").performTextClearance()
            compose.onNodeWithText("Raw ability score (blank = unknown)").performTextInput("18")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertExists()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Separate magical modifier").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2")))
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            runBlocking {
                assertEquals(14, db.recordsDao().characterAbility("local-player", "DEX")?.score)
                assertEquals(2, db.recordsDao().characterAbility("local-player", "DEX")?.magicModifier)
                assertEquals(before, db.recordsDao().allAudit())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
}
