package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeNotesDraftTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ReforgeDb
    private lateinit var repo: ReforgeRepository
    private val fileName = "fictional-reforge-notes.db"
    @Before fun open() {
        context.deleteDatabase(fileName)
        db = Room.databaseBuilder(context, ReforgeDb::class.java, fileName).allowMainThreadQueries().build()
        repo = ReforgeRepository(db) { 1000L }
    }
    @After fun close() { db.close(); context.deleteDatabase(fileName) }
    private suspend fun rejects(operation: suspend () -> Unit) {
        try { operation(); fail("Expected guarded rejection") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }
    @Test fun recoveryDraftSurvivesCloseReopenWithoutChangingCommittedNotes() = runBlocking {
        val saved = repo.create(ReforgeCharacter("fictional-notes", "Fictional notes", notes = "Committed text"))
        val draft = repo.saveNoteDraft(saved.character.id, saved.revision, "Unsaved offline writing")
        assertEquals(saved, repo.get(saved.character.id))
        db.close()
        db = Room.databaseBuilder(context, ReforgeDb::class.java, fileName).allowMainThreadQueries().build()
        repo = ReforgeRepository(db) { 900L }
        assertEquals(draft, repo.readNoteDraft(saved.character.id))
        val changed = repo.saveNoteDraft(saved.character.id, saved.revision, "More offline writing", draft.updatedAt)
        assertTrue(changed.updatedAt > draft.updatedAt)
        assertEquals(saved, repo.get(saved.character.id))
        val backup = decodeReforgeBackup(repo.export())
        assertEquals(listOf(changed), backup.drafts)
    }
    @Test fun staleDraftCannotOverwriteOrEraseNewerCommittedNotesOrOtherDraftText() = runBlocking {
        val saved = repo.create(ReforgeCharacter("fictional-stale-notes", "Fictional notes", notes = "Original"))
        val first = repo.saveNoteDraft(saved.character.id, saved.revision, "Draft first")
        val second = repo.saveNoteDraft(saved.character.id, saved.revision, "Draft second", first.updatedAt)
        rejects { repo.saveNoteDraft(saved.character.id, saved.revision, "Stale draft", first.updatedAt) }
        rejects { repo.discardNoteDraft(saved.character.id, saved.revision, first.updatedAt) }
        val committed = repo.save(saved.character.id, saved.revision, saved.character.copy(notes = "Independent newer text"), "Edit notes")
        rejects { repo.saveNoteDraft(saved.character.id, saved.revision, "Old overwrite", second.updatedAt) }
        assertEquals(second, repo.readNoteDraft(saved.character.id))
        assertEquals(committed, repo.get(saved.character.id))
        repo.discardNoteDraft(saved.character.id, second.expectedRevision, second.updatedAt)
        assertNull(repo.readNoteDraft(saved.character.id))
        assertEquals(committed, repo.get(saved.character.id))
    }
    @Test fun restorePreservesAStaleRecoveryDraftWithoutApplyingItToCanonicalNotes() = runBlocking {
        val saved = repo.create(ReforgeCharacter("fictional-restore-notes", "Fictional notes", notes = "Original"))
        val draft = repo.saveNoteDraft(saved.character.id, saved.revision, "Separate recovery text")
        val canonical = repo.save(saved.character.id, saved.revision, saved.character.copy(notes = "New committed text"), "Edit notes")
        val text = repo.export()
        val otherDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        try {
            val other = ReforgeRepository(otherDb)
            val preview = other.previewRestore(text)
            assertTrue(preview.conflicts.toString(), preview.valid)
            other.restore(preview)
            assertEquals(canonical, other.get(saved.character.id))
            assertEquals(draft, other.readNoteDraft(saved.character.id))
        } finally { otherDb.close() }
    }
    @Test fun explicitRecoveryReviewRetainsDraftTextAndCannotOverwriteConcurrentChanges() = runBlocking {
        val saved = repo.create(ReforgeCharacter("fictional-reviewed-draft", "Fictional notes", notes = "Original"))
        val draft = repo.saveNoteDraft(saved.character.id, saved.revision, "Recovery text")
        val newer = repo.save(saved.character.id, saved.revision, saved.character.copy(notes = "New committed notes"), "Independent edit")
        rejects { repo.reviewNoteDraft(saved.character.id, draft.updatedAt - 1, newer.revision) }
        rejects { repo.reviewNoteDraft(saved.character.id, draft.updatedAt, saved.revision) }
        assertEquals(draft, repo.readNoteDraft(saved.character.id))
        val reviewed = repo.reviewNoteDraft(saved.character.id, draft.updatedAt, newer.revision)
        assertEquals("Recovery text", reviewed.text)
        assertEquals(newer.revision, reviewed.expectedRevision)
        assertTrue(reviewed.updatedAt > draft.updatedAt)
        assertEquals(newer, repo.get(saved.character.id))
        val committed = repo.save(saved.character.id, newer.revision, newer.character.copy(notes = reviewed.text), "Use reviewed recovery text")
        repo.discardNoteDraft(saved.character.id, reviewed.expectedRevision, reviewed.updatedAt)
        assertNull(repo.readNoteDraft(saved.character.id))
        assertEquals("Recovery text", committed.character.notes)
    }

}
