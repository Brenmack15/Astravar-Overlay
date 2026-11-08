package com.yazsras.astravar.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.Stat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android SQLite failure/recovery acceptance, using two explicitly fictional stores.
 * No production store, owner campaign, network endpoint, UI hook or physical phone is used.
 * The trigger deliberately interrupts receipt insertion after the character row update;
 * native SQLite must roll that update back with the rest of its Room transaction.
 */
@RunWith(AndroidJUnit4::class)
class ReforgeRecoveryNativeTest {
    private suspend fun rejects(
        expected: Class<out Exception>, contains: String? = null, operation: suspend () -> Unit
    ) {
        try {
            operation()
            fail("The invalid/interrupted fictional operation must fail")
        } catch (error: Exception) {
            assertTrue("Expected ${expected.simpleName}, received ${error.javaClass.name}: ${error.message}",
                expected.isInstance(error))
            if (contains != null) assertTrue("The expected failure must explain its cause: ${error.message}",
                error.message.orEmpty().contains(contains, ignoreCase = true))
        }
    }

    @Test fun invalidDuplicateStaleAndInterruptedSavesRecoverWithVerifiedBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Destructive fictional fixtures may run only on an emulator", "1",
            device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("Native recovery fixture requires the isolated QA package",
            "com.yazsras.astravar.v2uidebug", context.packageName)

        val databaseName = "native-reforge-recovery-31-fictional.db"
        val restoredName = "native-reforge-recovery-31-fictional-restored.db"
        // These fixed fixture names are deliberately different from astravar-reforge.db.
        context.deleteDatabase(databaseName)
        context.deleteDatabase(restoredName)
        fun disk(name: String) = Room.databaseBuilder(context, ReforgeDb::class.java, name).build()
        var db = disk(databaseName)
        var restoredDb: ReforgeDb? = null
        try {
            var repo = ReforgeRepository(db) { 1_000L }
            val initial = ReforgeCharacter("native-recovery-player", "Fictional recovery player", level = 9,
                baseAbilities = mapOf(Stat.STR to 8, Stat.CON to 14, Stat.INT to 20),
                baseMaxHp = 66, baselineConstitutionModifier = 2, currentHp = 39, temporaryHp = 0,
                notes = "Fictional source attribution must survive recovery")

            // Invalid input cannot create either a partial row or an orphan receipt.
            rejects(IllegalArgumentException::class.java) {
                repo.create(initial.copy(name = ""), "native-invalid-create")
            }
            assertTrue(repo.observeAll().first().isEmpty())
            assertNull(repo.commandReceipt("native-invalid-create"))
            assertTrue(db.reforgeDao().allChanges().isEmpty())

            val created = repo.create(initial, "native-create-once")
            val createReceipt = requireNotNull(repo.commandReceipt("native-create-once"))
            assertEquals(created, repo.create(initial, "native-create-once"))
            assertEquals(createReceipt, repo.commandReceipt("native-create-once"))
            assertEquals(1, db.reforgeDao().allCharacters().size)
            assertEquals(1, db.reforgeDao().allChanges().size)

            // The exact accepted command is idempotent, even after its revision advanced.
            val renameInput = created.character.copy(name = "Fictional reviewed name")
            val renamed = repo.save(initial.id, created.revision, renameInput, "Fictional rename", "native-rename-once")
            val renameReceipt = requireNotNull(repo.commandReceipt("native-rename-once"))
            assertEquals(renamed, repo.save(initial.id, created.revision, renameInput,
                "Fictional rename", "native-rename-once"))
            assertEquals(renameReceipt, repo.commandReceipt("native-rename-once"))
            assertEquals(2, db.reforgeDao().allChanges().size)
            rejects(IllegalArgumentException::class.java, "different content") {
                repo.save(initial.id, created.revision, renameInput.copy(name = "Changed retry payload"),
                    "Fictional rename", "native-rename-once")
            }
            rejects(IllegalStateException::class.java, "changed") {
                repo.save(initial.id, created.revision, created.character.copy(currentHp = 1),
                    "Fictional stale edit", "native-stale-edit")
            }
            assertEquals(renamed, repo.get(initial.id))
            assertNull(repo.commandReceipt("native-stale-edit"))
            assertEquals(renameReceipt, repo.commandReceipt("native-rename-once"))

            // Abort at the receipt insertion boundary, after save() has updated the row.
            // A real SQLite trigger tests the whole transaction rather than mocking a DAO.
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER native_reforge_interrupt_receipt
                BEFORE INSERT ON reforge_changes
                WHEN NEW.commandId = 'native-interrupted-save'
                BEGIN
                    SELECT RAISE(ABORT, 'ASTRAVAR_NATIVE_FIXTURE_INTERRUPT');
                END
            """.trimIndent())
            val candidate = renamed.character.copy(currentHp = 7)
            rejects(SQLiteException::class.java, "ASTRAVAR_NATIVE_FIXTURE_INTERRUPT") {
                repo.save(initial.id, renamed.revision, candidate, "Fictional interrupted HP edit", "native-interrupted-save")
            }
            assertEquals("The character update must roll back with its failed receipt", renamed, repo.get(initial.id))
            assertNull("A failed transaction must have no success receipt", repo.commandReceipt("native-interrupted-save"))
            assertEquals(2, db.reforgeDao().allChanges().size)
            assertEquals(renameReceipt, repo.commandReceipt("native-rename-once"))

            // Read the rolled-back state from a fresh native Room/SQLite connection.
            db.close()
            db = disk(databaseName)
            repo = ReforgeRepository(db) { 1_000L }
            assertEquals(renamed, repo.get(initial.id))
            assertNull(repo.commandReceipt("native-interrupted-save"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER native_reforge_interrupt_receipt")
            val recovered = repo.save(initial.id, renamed.revision, candidate,
                "Fictional interrupted HP edit", "native-interrupted-save")
            assertEquals(3L, recovered.revision)
            assertEquals(7, recovered.character.currentHp)
            assertEquals(recovered, repo.save(initial.id, renamed.revision, candidate,
                "Fictional interrupted HP edit", "native-interrupted-save"))
            assertEquals(3, db.reforgeDao().allChanges().size)
            assertEquals(createReceipt, repo.commandReceipt("native-create-once"))
            assertEquals(renameReceipt, repo.commandReceipt("native-rename-once"))

            db.close()
            db = disk(databaseName)
            repo = ReforgeRepository(db) { 1_000L }
            assertEquals(recovered, repo.get(initial.id))
            val backupText = repo.export()
            val backup = decodeReforgeBackup(backupText) // Includes complete immutable-chain validation.
            assertEquals(1, backup.characters.size)
            assertEquals(3, backup.changes.size)
            assertTrue(backup.rolls.isEmpty())
            assertEquals(recovered, backup.characters.single().saved())
            assertEquals(setOf("native-create-once", "native-rename-once", "native-interrupted-save"),
                backup.changes.map { it.commandId }.toSet())
            assertEquals("Fictional source attribution must survive recovery", recovered.character.notes)

            // Restore into a second fictional disk store and verify it after reopening too.
            restoredDb = disk(restoredName)
            var target = ReforgeRepository(requireNotNull(restoredDb)) { 1_000L }
            val preview = target.previewRestore(backupText)
            assertTrue(preview.conflicts.toString(), preview.valid)
            val restoreReceipt = target.restore(preview, "native-recovery-restore")
            assertEquals(restoreReceipt, target.restore(preview, "native-recovery-restore"))
            restoredDb.close()
            restoredDb = disk(restoredName)
            target = ReforgeRepository(requireNotNull(restoredDb)) { 1_000L }
            assertEquals(recovered, target.get(initial.id))
            assertEquals(restoreReceipt, target.commandReceipt("native-recovery-restore"))
            val restoredBackup = decodeReforgeBackup(target.export())
            assertEquals(4, restoredBackup.changes.size)
            assertEquals(backup.characters, restoredBackup.characters)
            assertEquals(createReceipt, target.commandReceipt("native-create-once"))
            assertEquals(renameReceipt, target.commandReceipt("native-rename-once"))

            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nCHECKPOINT: actual Android Room/SQLite rejected invalid create and stale edit; exact command retries kept immutable receipts; a BEFORE INSERT receipt ABORT rolled back the preceding character update with no receipt; reopen and exact-command retry recovered; validated backup restored into a second fictional store and survived reopen. No physical-device or touch-UI claim.\n")
            })
        } finally {
            db.close()
            restoredDb?.close()
            context.deleteDatabase(databaseName)
            context.deleteDatabase(restoredName)
        }
    }
}
