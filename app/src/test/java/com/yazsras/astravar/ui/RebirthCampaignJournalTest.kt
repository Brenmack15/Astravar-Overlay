package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
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
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RebirthCampaignJournalTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun capture(name: String, activity: ComponentActivity) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, activity, false) }
    }

    @Test fun journalShowsSavedSceneAndRoutesToExactRecordsWithoutChangingThem() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        val contact = ConnectionEntity(id = "journal-contact", name = "Fictional cartographer", relationship = "Knows the western road")
        val project = InventionProject(id = "journal-project", title = "Fictional compass", status = "Research")
        runBlocking {
            repo.records.saveEntity(contact, source = "Fictional fixture")
            repo.records.saveProject(project, source = "Fictional fixture")
            repo.party.saveCheckpoint(CampaignCheckpoint(id = "journal-checkpoint", title = "At the old observatory",
                scene = "Fictional hilltop", lastConfirmedAction = "Asked about the sealed door", unresolvedChoice = "No roll has been made"),
                source = "Fictional fixture")
        }
        val before = runBlocking { db.recordsDao().allAudit() }
        val opened = mutableListOf<String>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { RebirthCampaignJournal(repo, opened::add) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("At the old observatory").fetchSemanticsNodes().isNotEmpty() }
            capture("rebirth-campaign-journal-400", activity.get())
            compose.onNodeWithText("No roll has been made").performScrollTo().assertExists()
            capture("rebirth-campaign-checkpoint-400", activity.get())
            compose.onNodeWithText(contact.name).performScrollTo().performClick()
            compose.onNodeWithText(project.title).performScrollTo().performClick()
            assertEquals(listOf("Connections:${contact.id}", "Inventions:${project.id}"), opened)
            assertEquals(before, runBlocking { db.recordsDao().allAudit() })
            assertEquals(1, runBlocking { db.partyDao().allCheckpoints().size })
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun journalContactRouteOpensTheRequestedProfileAndKeepsItsIdentity() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        val contact = ConnectionEntity(id = "journal-target", name = "Fictional archivist", aliases = "Keeper of the amber ledger")
        runBlocking {
            repo.records.saveEntity(contact, source = "Fictional fixture")
            repo.records.saveEntity(ConnectionEntity(id = "journal-other", name = "Fictional other contact"), source = "Fictional fixture")
        }
        val before = runBlocking { db.recordsDao().allEntities() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ConnectionsPageV2(repo, initialEntityId = contact.id) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(contact.aliases).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(contact.aliases).assertIsDisplayed()
            compose.onNodeWithText("Connection name").assertDoesNotExist()
            assertEquals(before, runBlocking { db.recordsDao().allEntities() })
        } finally { activity.pause().stop().destroy(); db.close() }
    }
}
