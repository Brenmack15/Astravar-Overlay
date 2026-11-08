package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeItemTransferTest {
    @Test fun archiveSurvivesReopenAndBackupAndRestoresSameIdentityAndUnknowns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fictional-reforge-archive-test.db"
        context.deleteDatabase(name)
        fun disk() = Room.databaseBuilder(context, ReforgeDb::class.java, name).build()
        var db = disk()
        try {
            var repo = ReforgeRepository(db)
            val created = repo.createWithOrigin(ReforgeCharacter("fixture-archive", "Fictional archive", notes = "Retain these notes"),
                ReforgeOrigin("Fictional source", sourceProfileId = "fictional-original"), "Create fictional fixture", "create-archive")
            val archived = repo.setArchived(created.character, true, "archive-once")
            assertEquals(archived, repo.setArchived(created.character, true, "archive-once"))
            assertTrue(repo.observeActive().first().isEmpty())
            db.close(); db = disk(); repo = ReforgeRepository(db)
            assertEquals(archived, repo.get(created.character.id))
            val backup = decodeReforgeBackup(repo.export())
            assertTrue(backup.characters.single().saved().character.archived)
            assertEquals(2, backup.changes.size)
            try {
                repo.appendDiceRoll(archived.character.id, archived.revision, "Archived roll",
                    SecureDice.manual(SecureDice.parse("1d20"), listOf(10)))
                fail("An archived sheet cannot record new play")
            } catch (_: IllegalArgumentException) { }
            val restored = repo.setArchived(archived.character, false, "restore-once")
            assertEquals(created.origin, restored.origin)
            assertEquals(created.character.id, repo.observeActive().first().single().character.id)
            assertEquals("Retain these notes", restored.character.notes)
            assertNull(restored.character.currentHp)
            assertTrue(restored.character.baseAbilities.isEmpty())
            assertEquals(3, decodeReforgeBackup(repo.export()).changes.size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun transferIsAtomicRetryableAndPreservesBackupReceiptChains() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        try {
            val repo = ReforgeRepository(db)
            val item = ItemDefinition("fictional-item", "Fictional compass", description = "Original attribution")
            val source = repo.create(ReforgeCharacter("source", "Fictional sender",
                itemDefinitions = listOf(item), items = listOf(ItemInstance("owned", item.id, quantity = 2))))
            val target = repo.create(ReforgeCharacter("target", "Fictional recipient"))
            val newer = repo.save(target.character.id, target.revision, target.character.copy(name = "Newer recipient"), "Rename")
            try { repo.transferItem(source.character, target.character, "owned", "stale-transfer"); fail("Expected a stale revision rejection") }
            catch (_: IllegalStateException) { }
            assertEquals(source, repo.get(source.character.id))
            assertEquals(newer, repo.get(target.character.id))
            val transferred = repo.transferItem(source.character, newer.character, "owned", "accepted-transfer")
            assertEquals(transferred, repo.transferItem(source.character, newer.character, "owned", "accepted-transfer"))
            assertTrue(transferred.first.character.items.isEmpty())
            assertEquals(2, transferred.second.character.items.single().quantity)
            assertEquals(item, transferred.second.character.itemDefinitions.single())
            val backup = decodeReforgeBackup(repo.export())
            assertEquals(5, backup.changes.size)
            assertEquals(2, backup.characters.size)
            assertEquals(1, backup.characters.flatMap { it.saved().character.items }.count { it.id == "owned" })
        } finally { db.close() }
    }
}
