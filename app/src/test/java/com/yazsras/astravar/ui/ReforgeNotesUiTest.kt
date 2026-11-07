package com.yazsras.astravar.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReforgeNotesUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun writingAutosavesActualNotesWithoutSourceOrSaveButton() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReforgeDb::class.java).build()
        val repo = ReforgeRepository(db)
        val original = runBlocking { repo.create(ReforgeCharacter("notes-ui", "Fictional actor")) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { ReforgeNotesEditor(original, repo) {} } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Write your notes").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Write your notes").performScrollTo().performTextInput("## Field notes\nA player-owned discovery.")
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            compose.waitUntil(10_000) { runBlocking { repo.get(original.character.id)?.character?.notes?.contains("player-owned") == true } }
            val saved = runBlocking { requireNotNull(repo.get(original.character.id)) }
            assertEquals(original.revision + 1, saved.revision)
            assertNull(saved.character.currentHp)
            assertNull(runBlocking { repo.readNoteDraft(original.character.id) })
            compose.onNodeWithText("Preview").performClick()
            compose.onNodeWithText("Field notes").performScrollTo().assertExists()
        } finally { controller.pause().stop().destroy(); db.close() }
    }
    @Test fun staleRecoveryRequiresComparisonAndCannotAutosaveOverNewerNotes() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReforgeDb::class.java).build()
        val repo = ReforgeRepository(db)
        val original = runBlocking { repo.create(ReforgeCharacter("stale-notes-ui", "Fictional actor")) }
        runBlocking { repo.saveNoteDraft(original.character.id, original.revision, "Older recovery") }
        val latest = runBlocking { repo.save(original.character.id, original.revision, original.character.copy(notes = "Newer accepted notes"), "Local edit") }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { ReforgeNotesEditor(latest, repo) {} } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Use recovery as reviewed edit").fetchSemanticsNodes().isNotEmpty() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(latest, runBlocking { repo.get(latest.character.id) })
            assertEquals("Older recovery", runBlocking { repo.readNoteDraft(latest.character.id)?.text })
            compose.onNodeWithText("Newer accepted notes").assertExists()
            compose.onNodeWithText("Older recovery").assertExists()
        } finally { controller.pause().stop().destroy(); db.close() }
    }
}
