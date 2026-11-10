package com.yazsras.astravar.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowJournalKeepsWholeLabelsAtLargerText() = verifyJournalLabels(1.3f, "journal-360-font130")

    @Test
    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun wideJournalKeepsWholeLabelsAtLargerText() = verifyJournalLabels(1.3f, "journal-400-font130")

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowJournalKeepsWholeLabelsAtDoubleText() = verifyJournalLabels(2f, "journal-360-font200")

    @Test
    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun wideJournalKeepsWholeLabelsAtDoubleText() = verifyJournalLabels(2f, "journal-400-font200")

    @Suppress("DEPRECATION")
    private fun verifyJournalLabels(fontScale: Float, captureName: String) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repository = CampaignLedgerRepository(db)
        val before = runBlocking { db.recordsDao().allAudit() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val resources = activity.get().resources
        val original = Configuration(resources.configuration)
        val opened = mutableListOf<String>()
        val defects = mutableListOf<String>()
        fun checkLabel(label: String, singleLine: Boolean) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            if (kotlin.math.abs(layout.layoutInput.density.fontScale - fontScale) > .01f)
                defects += "$label uses ${layout.layoutInput.density.fontScale}x instead of ${fontScale}x"
            for (line in 0 until layout.lineCount) {
                if (layout.isLineEllipsized(line) || layout.getLineLeft(line) < -.5f ||
                    layout.getLineRight(line) > layout.size.width + .5f ||
                    layout.getLineBottom(line) > layout.size.height + .5f)
                    defects += "$label has clipped or ellipsized line $line"
            }
            if (singleLine && layout.lineCount != 1)
                defects += "$label breaks its word across ${layout.lineCount} lines at ${layout.size.width}px"
            for (line in 0 until layout.lineCount - 1) {
                val boundary = layout.getLineEnd(line)
                if (boundary in 1 until label.length && !label[boundary - 1].isWhitespace() && !label[boundary].isWhitespace())
                    defects += "$label has a mid-word line break at offset $boundary"
            }
        }
        try {
            resources.updateConfiguration(Configuration(original).apply { this.fontScale = fontScale }, resources.displayMetrics)
            activity.get().setContent { AstravarTheme { RebirthCampaignJournal(repository, opened::add) } }
            compose.onNodeWithText("Campaign").assertIsDisplayed()
            capture("$captureName-header", activity.get())
            checkLabel("Campaign", singleLine = true)
            val search = compose.onNode(hasText("Search everything") and hasClickAction())
                .performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            checkLabel("Search everything", singleLine = false)
            search.performClick()
            for (label in listOf("Connections", "Inventions", "Checkpoints")) {
                val destination = compose.onNode(hasText(label) and hasClickAction())
                    .performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                checkLabel(label, singleLine = true)
                destination.performClick()
            }
            capture("$captureName-destinations", activity.get())
            assertEquals(listOf("Search everything", "Connections", "Inventions", "Checkpoints"), opened)
            assertEquals("Browsing the journal must not change campaign history", before, runBlocking { db.recordsDao().allAudit() })
            assertTrue("Journal label defects: ${defects.joinToString("; ")}", defects.isEmpty())
        } finally {
            resources.updateConfiguration(original, resources.displayMetrics)
            activity.pause().stop().destroy()
            db.close()
        }
    }

}
