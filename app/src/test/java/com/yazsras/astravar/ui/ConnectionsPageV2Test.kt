package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.CampaignLedgerDb
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.ConnectionEntity
import com.yazsras.astravar.data.CampaignLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
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
class ConnectionsPageV2Test {
    @get:Rule val compose = createAstravarComposeRule()
    private var oldRequestedSize: String? = null
    @Before fun useRequestedDialogSize() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogSize() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
    ).build()

    @Test fun sourcedConnectionAndMilestoneRetainIdentityWithoutCreatingInventory() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(CampaignLedgerRepository(db)) } }
            compose.onNodeWithText("Connection name").assertDoesNotExist()
            compose.onNodeWithContentDescription("Add connection").performClick()
            compose.onNodeWithText("Connection name").performScrollTo().performTextInput("Synthetic archivist")
            compose.onNodeWithText("Save new connection").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Advanced · sources").performScrollTo().performClick()
            compose.onNodeWithText("Profile source (optional)").performScrollTo()
                .performTextInput("Synthetic resolved introduction")
            compose.onNodeWithText("Save new connection").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allEntities().size == 1 } }
            val entity = runBlocking { db.recordsDao().allEntities().single() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Meaningful milestones").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Meaningful milestones").performScrollTo().performClick()
            compose.onNodeWithText("Add milestone").performScrollTo().performClick()
            compose.onNodeWithText("Milestone title").performScrollTo().performTextInput("Returned the borrowed map")
            compose.onNode(hasText("Append milestone") and hasClickAction()).assertIsEnabled()
            compose.onNodeWithText("Source (optional)").performScrollTo().performClick()
            compose.onNodeWithText("Milestone source").performScrollTo()
                .performTextInput("Synthetic resolved conversation")
            compose.onNode(hasText("Append milestone") and hasClickAction()).performClick()
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
            compose.onNodeWithContentDescription("Add connection").performClick()
            compose.onNodeWithText("Connection name").performScrollTo().performTextInput("Unsaved person")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard changes?").assertExists()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Connection name").performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Unsaved person"))
            )
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText("Connection name").assertDoesNotExist()
            compose.onNodeWithContentDescription("Add connection").performClick()
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

    @Test fun learnedInformationAppendsToSameConnectionWithEvidenceAndNoOwnershipChange() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val entity = ConnectionEntity(name = "Synthetic keeper", relationship = "Confirmed map loan")
        runBlocking { repo.records.saveEntity(entity, source = "Synthetic introduction") }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(entity.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(entity.name).performClick()
            compose.onNodeWithText("Connection name").assertDoesNotExist()
            compose.onNodeWithText("Add learned information").performScrollTo().performClick()
            compose.onNodeWithText("Known context / agreement / promise").performScrollTo().performTextInput("Knows the western road")
            compose.onNodeWithText("Append relationship context").assertIsEnabled()
            compose.onNodeWithText("Source (optional)").performScrollTo().performClick()
            compose.onNodeWithText("Context source").performScrollTo().performTextInput("Synthetic resolved conversation")
            compose.onNodeWithText("Append relationship context").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allFacts().size == 1 } }
            runBlocking {
                val fact = db.recordsDao().allFacts().single()
                assertEquals(entity.id, fact.entityId)
                assertEquals("Unverified", fact.evidenceStatus)
                assertEquals("Knows the western road", fact.detail)
                assertEquals("Synthetic resolved conversation", fact.source)
                assertEquals(1L, db.recordsDao().allEntities().single().revision)
                assertTrue(db.ledgerDao().allItems().isEmpty())
                assertTrue(db.recordsDao().allMilestones().isEmpty())
                assertEquals(2, db.recordsDao().allAudit().size)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
    @Test fun linkedCompanionUsesItsOwnNameAndRelationshipCanBeEditedRemovedAndReviewed() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val contact = ConnectionEntity(name = "Synthetic keeper")
        val companion = runBlocking {
            repo.records.saveEntity(contact, source = "Fictional introduction")
            repo.records.createCompanion("Synthetic Glimmer")
        }
        val link = CampaignLink("connection", contact.id, "companion", companion, "Guides")
        runBlocking { repo.records.saveLink(link, source = "Fictional relationship") }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(contact.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(contact.name).performClick()
            compose.onNodeWithText("Connected Records").performScrollTo().performClick()
            compose.onNodeWithText("Synthetic Glimmer").performScrollTo().assertExists()
            compose.onNodeWithText("Edit relationship").performScrollTo().performClick()
            compose.onNodeWithText("Relationship to this record").performScrollTo().performTextReplacement("Protects")
            compose.onNodeWithText("Save relationship").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().link(link.id)?.revision == 2L } }
            // Read back the returned UI as well as the committed revision. The
            // connected-records section must stay open after its nested editor.
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Protects") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasText("Remove relationship") and hasClickAction()).fetchSemanticsNodes().size == 1
            }
            compose.onNode(hasText("Protects") and hasClickAction()).performScrollTo().assertExists()
            compose.onNode(hasText("Remove relationship") and hasClickAction()).performScrollTo().performClick()
            compose.onNode(hasText("Remove relationship") and hasClickAction()).performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().activeLinks().isEmpty() } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No linked records.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("No linked records.").performScrollTo().assertExists()
            runBlocking {
                val events = db.recordsDao().allAudit()
                assertTrue(link.id in connectionHistoryLinkIds(emptyList(), events, contact.id))
                assertEquals(3L, db.recordsDao().link(link.id)!!.revision)
                assertNotNull(db.recordsDao().characterProfile(companion))
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

}
