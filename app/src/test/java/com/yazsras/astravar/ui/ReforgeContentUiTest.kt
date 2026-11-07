package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
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
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReforgeContentUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var oldRequestedSize: String? = null
    @Before fun useRequestedDialogSize() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogSize() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }
    @Test fun readableInternalLinkUsesNativeTextInteractionAndReturnsStableRecordIdentity() {
        var selected = ""
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                ReforgeRichText("[Open profile](astravar://record/companion/test-profile)", onRecordLink = { selected = it })
            } }
            compose.onNodeWithText("Open profile").performTouchInput { click(center) }
            compose.runOnIdle { assertEquals("test-profile", selected) }
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun importedNpcOpensAsStructuredReadableProfileAndRetainsExactStoredContent() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        val notes = "Sky castle patron.\nStatus: Historical\nEra: [\"Hoard\"]\nKnown facts: Commissioned completed work.\nPromises/open threads: Future work is proposed.\nExternal ID: SYNTHETIC-PRIVATE-ID\nSource: <mention url=\"https://example.org/source\">Recovered history</mention>"
        val entity = ConnectionEntity(name = "Synthetic patron", notes = notes)
        runBlocking { repo.records.saveEntity(entity, source = "Synthetic provenance") }
        val before = runBlocking { db.recordsDao().allEntities() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(entity.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(entity.name).performClick()
            compose.onNodeWithText("Historical").assertExists()
            compose.onNodeWithText("Hoard").assertExists()
            compose.onNodeWithText("OVERVIEW").assertExists()
            compose.onNodeWithText("Commissioned completed work.").assertExists()
            compose.onNodeWithText("Future work is proposed.").assertExists()
            compose.onAllNodes(hasText("<mention", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("SYNTHETIC-PRIVATE-ID", substring = true)).assertCountEquals(0)
            compose.runOnIdle { captureNativeTestWindows("reforge-npc-profile", activity.get(), modal = true) }
            compose.onNodeWithText("Sources & record details").performScrollTo().performClick()
            compose.onNodeWithText("SYNTHETIC-PRIVATE-ID").assertExists()
            compose.onNodeWithText("Recovered history").assertExists()
            assertEquals(before, runBlocking { db.recordsDao().allEntities() })
            assertEquals(notes, runBlocking { db.recordsDao().allEntities().single().notes })
            assertEquals(1, runBlocking { db.recordsDao().allAudit().size })
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun ordinaryAbilityEditNeedsNoCitationAndPreservesLegacyBonusAndAttribution() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        runBlocking {
            repo.records.saveProfile(CharacterProfile(name = "Synthetic character"), source = "Original status")
            repo.records.saveAbility(CharacterAbility(key = "STR", score = 8, magicModifier = 2, notes = "Accepted legacy bonus"), source = "Original attribution")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { CharacterOverviewV2(repo) } } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Strength score 8", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ability-STR").performClick()
            compose.onNodeWithText("Ability source (optional)").assertDoesNotExist()
            compose.onNodeWithText("Recorded modifier adjustment").assertDoesNotExist()
            compose.onNodeWithText("Ability score").performTextClearance()
            compose.onNodeWithText("Ability score").performTextInput("9")
            compose.runOnIdle { captureNativeTestWindows("reforge-compact-ability-editor", activity.get(), modal = true) }
            compose.onNodeWithText("Save ability").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().characterAbility("local-player", "STR")?.score == 9 } }
            runBlocking {
                val after = db.recordsDao().characterAbility("local-player", "STR")!!
                assertEquals(2, after.magicModifier)
                assertEquals("Original attribution", after.source)
                assertEquals("Accepted legacy bonus", after.notes)
                assertEquals(2L, after.revision)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
}
