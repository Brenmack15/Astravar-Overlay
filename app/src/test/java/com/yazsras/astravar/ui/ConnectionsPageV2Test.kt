package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.CampaignLedgerDb
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.ConnectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionsPageV2Test {
    @get:Rule val compose = createEmptyComposeRule()

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
    ).build()

    @Test fun sourcedConnectionAndMilestoneRetainIdentityWithoutCreatingInventory() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(CampaignLedgerRepository(db)) } }
            compose.onNodeWithText("Connection name").performScrollTo().performTextInput("Synthetic archivist")
            compose.onNodeWithText("Save new connection").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Profile source / scene / checkpoint (required)").performScrollTo()
                .performTextInput("Synthetic resolved introduction")
            compose.onNodeWithText("Save new connection").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allEntities().size == 1 } }
            val entity = runBlocking { db.recordsDao().allEntities().single() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Meaningful milestones").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Meaningful milestones").performScrollTo().performClick()
            compose.onNodeWithText("Milestone title").performScrollTo().performTextInput("Returned the borrowed map")
            compose.onNodeWithText("Append milestone").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Milestone source (required)").performScrollTo()
                .performTextInput("Synthetic resolved conversation")
            compose.onNodeWithText("Append milestone").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allMilestones().size == 1 } }
            runBlocking {
                val after = db.recordsDao().allEntities().single()
                val milestone = db.recordsDao().allMilestones().single()
                assertEquals(entity, after)
                assertEquals(entity.id, milestone.entityId)
                assertEquals("Returned the borrowed map", milestone.title)
                assertEquals("Synthetic resolved conversation", milestone.source)
                val events = db.recordsDao().allAudit()
                assertEquals(2, events.size)
                assertTrue(events.any { it.recordType == "connection" && it.recordId == entity.id })
                assertTrue(events.any { it.recordType == "milestone" && it.recordId == milestone.id })
                assertTrue(db.ledgerDao().allItems().isEmpty())
                assertTrue(db.recordsDao().allProjects().isEmpty())
                assertTrue(db.recordsDao().allCompanionResources().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun changingConnectionRequiresExplicitDiscardOfUnsavedDraft() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(CampaignLedgerRepository(db)) } }
            compose.onNodeWithText("Connection name").performScrollTo().performTextInput("Unsaved person")
            compose.onNodeWithText("New connection").performScrollTo().performClick()
            compose.onNodeWithText("Leave unsaved connection notes?").assertExists()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Connection name").performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Unsaved person"))
            )
            compose.onNodeWithText("New connection").performScrollTo().performClick()
            compose.onNodeWithText("Discard edits").performClick()
            compose.onNodeWithText("Connection name").performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
            runBlocking {
                assertTrue(db.recordsDao().allEntities().isEmpty())
                assertTrue(db.recordsDao().allAudit().isEmpty())
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun directorySearchFindsAliasesWithoutChangingStoredConnections() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val first = ConnectionEntity(name = "Synthetic Mira", aliases = "Ashkeeper")
        val second = ConnectionEntity(name = "Synthetic Venn", aliases = "Harbormaster")
        runBlocking {
            repo.records.saveEntity(first, source = "Synthetic fixture")
            repo.records.saveEntity(second, source = "Synthetic fixture")
        }
        val before = runBlocking { db.recordsDao().allEntities() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(repo) } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Synthetic Venn").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Search names, aliases and known notes").performScrollTo().performTextInput("ashKEEPER")
            compose.onNodeWithText("Synthetic Mira").assertExists()
            compose.onNodeWithText("Synthetic Venn").assertDoesNotExist()
            runBlocking {
                assertEquals(before, db.recordsDao().allEntities())
                assertEquals(2, db.recordsDao().allAudit().size)
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
}
