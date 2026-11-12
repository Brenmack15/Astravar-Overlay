package com.yazsras.astravar.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fictional, file-backed upgrade fixtures; historical JSON is deliberately not interpreted. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisStorageMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var destination: CampaignLedgerDb
    private val sourceName = "astravar-reforge.db"
    private val destinationName = "genesis-storage-migration-fixture.db"
    private val tables = listOf("reforge_characters", "reforge_changes", "reforge_rolls", "reforge_note_drafts")
    private val characterJson = """ { "id" : "fictional-player", "name" : "龍\u0020fixture", "futureField" : { "unknown" : null } } """
    private val originJson = """ { "label" : "Fictional original evidence", "futureProvenance" : [1, null, "龍"] } """
    private val componentsJson = """ [ { "label" : "Fictional archived roll", "dice" : [], "futureDie" : true } ] """

    @Before fun open() {
        context.deleteDatabase(sourceName)
        context.deleteDatabase(destinationName)
        destination = memoryDestination()
    }

    @After fun close() {
        destination.close()
        context.deleteDatabase(sourceName)
        context.deleteDatabase(destinationName)
    }

    private fun memoryDestination() = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java)
        .openHelperFactory(GenesisNonDestructiveOpenHelperFactory()).allowMainThreadQueries().build()

    private fun diskDestination() = Room.databaseBuilder(context, CampaignLedgerDb::class.java, destinationName)
        .openHelperFactory(GenesisNonDestructiveOpenHelperFactory()).allowMainThreadQueries().build()

    private fun createSource() {
        val source = Room.databaseBuilder(context, ReforgeDb::class.java, sourceName)
            .openHelperFactory(GenesisNonDestructiveOpenHelperFactory()).allowMainThreadQueries().build()
        try { seedRaw(source.openHelper.writableDatabase) } finally { source.close() }
    }

    private fun seedRaw(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO reforge_characters (profileId,characterJson,revision,originJson,createdAt,updatedAt) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>("fictional-player", characterJson, 7L, originJson, 123L, 456L))
        db.execSQL("INSERT INTO reforge_changes (commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType) VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("historical-command", "fictional-player", "Fictional history", "historical-fingerprint", 6L, 7L, 456L,
                null, " { \"futureOutcome\" : \"preserve verbatim\" } ", "HISTORICAL_FUTURE_TYPE"))
        db.execSQL("INSERT INTO reforge_rolls (id,profileId,characterRevision,label,formula,components,manual,ownership,createdAt) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("historical-roll", "fictional-player", 6L, "Fictional roll", "1d20", componentsJson, 1, "Player", 400L))
        db.execSQL("INSERT INTO reforge_note_drafts (profileId,expectedRevision,text,updatedAt) VALUES (?,?,?,?)",
            arrayOf<Any?>("fictional-player", 7L, "Uncommitted fictional draft\n龍\n", 457L))
    }

    private fun Cursor.rawRows(): List<List<Any?>> = use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                    else -> cursor.getString(index)
                }
            })
        }
    }

    private fun destinationRows(): Map<String, List<List<Any?>>> = tables.associateWith {
        destination.openHelper.writableDatabase.query("SELECT * FROM $it ORDER BY 1").rawRows()
    }

    private fun sourceRows(): Map<String, List<List<Any?>>> {
        val source = SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try { tables.associateWith { source.rawQuery("SELECT * FROM $it ORDER BY 1", null).rawRows() } }
        finally { source.close() }
    }

    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        val error = runCatching { block() }.exceptionOrNull()
        assertNotNull("Ambiguous or incomplete migration must reject without assigning authority", error)
        return requireNotNull(error)
    }

    @Test fun rawHistoryAndDraftsCopyWithoutDecodingOrNormalizingJson() = runBlocking {
        createSource()
        val original = sourceRows()
        val receipt = migrateGenesisStorage(context, destination)

        assertEquals(original, destinationRows())
        assertEquals(characterJson, destination.openHelper.writableDatabase.query("SELECT characterJson FROM reforge_characters").use { it.moveToFirst(); it.getString(0) })
        assertEquals(componentsJson, destination.openHelper.writableDatabase.query("SELECT components FROM reforge_rolls").use { it.moveToFirst(); it.getString(0) })
        assertEquals(1, receipt.sourceVersion)
        assertEquals(64, receipt.sourceDigest.length)
        assertTrue(receipt.countsJson.isNotBlank())
        assertEquals(receipt, destination.genesisStorageDao().receipt())
        assertTrue("The original store remains available for recovery", context.getDatabasePath(sourceName).isFile)
        assertEquals(original, sourceRows())
    }

    @Test fun nearCursorWindowLimitHistoricalOutcomeIsCopiedExactlyWithoutAJsonRoundTrip() = runBlocking {
        createSource()
        val largeOutcome = "{\"futureOutcome\":\"" + "x".repeat(1_999_980) + "\"}"
        assertEquals(2_000_000, largeOutcome.toByteArray(Charsets.UTF_8).size)
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE reforge_changes SET afterJson=? WHERE commandId=?", arrayOf<Any?>(largeOutcome, "historical-command"))
        }
        val original = sourceRows()

        val receipt = migrateGenesisStorage(context, destination)
        val copiedOutcome = destination.openHelper.writableDatabase.query("SELECT afterJson FROM reforge_changes WHERE commandId='historical-command'").use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        assertEquals(largeOutcome, copiedOutcome)
        assertEquals(original, destinationRows())
        assertEquals(original, sourceRows())
        assertEquals(receipt, destination.genesisStorageDao().receipt())
    }

    @Test fun historicalSnapshotsWhoseCombinedRowExceedsCursorWindowArePreservedByScalarReads() = runBlocking {
        createSource()
        val before = "{\"fictionalBefore\":\"" + "b".repeat(1_700_000) + "\"}"
        val after = "{\"fictionalAfter\":\"" + "a".repeat(1_700_000) + "\"}"
        assertTrue(before.length + after.length > 3 * 1024 * 1024)
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE reforge_changes SET beforeJson=?,afterJson=? WHERE commandId=?",
                arrayOf<Any?>(before, after, "historical-command"))
        }

        val receipt = migrateGenesisStorage(context, destination)
        // SELECT * would overflow a normal CursorWindow even though each source value is valid.
        destination.openHelper.writableDatabase.compileStatement("SELECT beforeJson FROM reforge_changes WHERE commandId='historical-command'").use {
            assertEquals(before, it.simpleQueryForString())
        }
        destination.openHelper.writableDatabase.compileStatement("SELECT afterJson FROM reforge_changes WHERE commandId='historical-command'").use {
            assertEquals(after, it.simpleQueryForString())
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { original ->
            original.compileStatement("SELECT beforeJson FROM reforge_changes WHERE commandId='historical-command'").use {
                assertEquals(before, it.simpleQueryForString())
            }
            original.compileStatement("SELECT afterJson FROM reforge_changes WHERE commandId='historical-command'").use {
                assertEquals(after, it.simpleQueryForString())
            }
        }
        assertTrue(context.getDatabasePath(sourceName).isFile)
        assertEquals(receipt, destination.genesisStorageDao().receipt())
    }

    @Test fun malformedUtf8TextRejectsMigrationWithoutReplacingOriginalBytes() = runBlocking {
        createSource()
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            // C3 must be followed by a UTF-8 continuation byte; 28 is deliberately invalid.
            it.execSQL("UPDATE reforge_changes SET afterJson=CAST(X'66C32867' AS TEXT) WHERE commandId='historical-command'")
            it.compileStatement("SELECT typeof(afterJson) FROM reforge_changes WHERE commandId='historical-command'").use { type ->
                assertEquals("text", type.simpleQueryForString())
            }
        }

        rejected { migrateGenesisStorage(context, destination) }
        assertTrue(destinationRows().values.all { it.isEmpty() })
        assertNull(destination.genesisStorageDao().receipt())
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { original ->
            original.compileStatement("SELECT hex(afterJson) FROM reforge_changes WHERE commandId='historical-command'").use {
                assertEquals("66C32867", it.simpleQueryForString())
            }
        }
        assertTrue(context.getDatabasePath(sourceName).isFile)
    }

    @Test fun physicallyCorruptSourceAndWalRemainByteExactAfterFailure() = runBlocking {
        val sourceFile = context.getDatabasePath(sourceName)
        requireNotNull(sourceFile.parentFile).mkdirs()
        val wal = java.io.File(sourceFile.path + "-wal")
        val shm = java.io.File(sourceFile.path + "-shm")
        val journal = java.io.File(sourceFile.path + "-journal")
        val originalDatabase = "Fictional non-SQLite source recovery evidence\n".repeat(8).toByteArray(Charsets.UTF_8)
        val originalWal = "Fictional source WAL recovery evidence".toByteArray(Charsets.UTF_8)
        val originalShm = "Fictional source SHM recovery evidence".toByteArray(Charsets.UTF_8)
        val originalJournal = "Fictional retained rollback journal evidence".toByteArray(Charsets.UTF_8)
        sourceFile.writeBytes(originalDatabase)
        wal.writeBytes(originalWal)
        shm.writeBytes(originalShm)
        journal.writeBytes(originalJournal)

        rejected { migrateGenesisStorage(context, destination) }
        // Never open the damaged fixture through a default Android corruption handler.
        assertTrue(sourceFile.isFile)
        assertTrue(wal.isFile)
        assertTrue(shm.isFile)
        assertTrue(journal.isFile)
        assertArrayEquals(originalDatabase, sourceFile.readBytes())
        assertArrayEquals(originalWal, wal.readBytes())
        assertArrayEquals(originalJournal, journal.readBytes())
        // SHM is SQLite's derived lock/index file, not committed recovery data; opening
        // a WAL database may rebuild it even with OPEN_READONLY. DB and WAL stay byte-exact.
        assertTrue(destinationRows().values.all { it.isEmpty() })
        assertNull(destination.genesisStorageDao().receipt())
    }

    @Test fun corruptCanonicalDatabaseIsRetainedAndNeverRecreatedFromTheOlderPlayableSource() = runBlocking {
        createSource()
        val originalSource = sourceRows()
        val canonicalName = "astravar-campaign-ledger.db"
        context.deleteDatabase(canonicalName)
        val canonicalFile = context.getDatabasePath(canonicalName)
        requireNotNull(canonicalFile.parentFile).mkdirs()
        val originalCanonical = "Fictional damaged canonical database with newer campaign state\n".repeat(8).toByteArray(Charsets.UTF_8)
        canonicalFile.writeBytes(originalCanonical)
        destination.close()
        destination = CampaignLedgerDb.open(context)
        try {
            rejected { migrateGenesisStorage(context, destination) }
            // Exact retained bytes also prove that no empty schema or stale source copy replaced it.
            assertTrue(canonicalFile.isFile)
            assertArrayEquals(originalCanonical, canonicalFile.readBytes())
            assertEquals(originalSource, sourceRows())
            assertTrue(context.getDatabasePath(sourceName).isFile)
        } finally {
            destination.close()
            context.deleteDatabase(canonicalName)
            destination = memoryDestination()
        }
    }

    @Test fun unreadableCurrentCharacterRemainsRetainedAndBlocksReadinessEvenWithACompletedReceipt() = runBlocking {
        createSource()
        val original = sourceRows()
        val receipt = migrateGenesisStorage(context, destination)

        rejected { requireReadableGenesisCharacters(destination) }
        assertEquals(original, destinationRows())
        assertEquals(original, sourceRows())
        assertEquals(receipt, destination.genesisStorageDao().receipt())

        assertEquals(receipt, migrateGenesisStorage(context, destination) { error("Completed storage must not be recopied") })
        rejected { requireReadableGenesisCharacters(destination) }
        assertEquals(original, destinationRows())
        assertEquals(original, sourceRows())
        assertEquals(receipt, destination.genesisStorageDao().receipt())
    }

    @Test fun unknownStoredAuthorityIsRejectedWithoutResettingProofOrRecords() = runBlocking {
        createSource()
        migrateGenesisStorage(context, destination)
        val original = sourceRows()
        destination.openHelper.writableDatabase.execSQL("UPDATE genesis_storage_receipt SET authority=? WHERE id=1",
            arrayOf<Any?>("fictional-unsupported-authority"))
        val retainedProof = destination.genesisStorageDao().receipt()

        rejected { migrateGenesisStorage(context, destination) }
        assertEquals(retainedProof, destination.genesisStorageDao().receipt())
        assertEquals(original, destinationRows())
        assertEquals(original, sourceRows())
    }

    @Test fun interruptionBeforeAuthorityReceiptRollsBackEveryCopiedTableAndCanRetry() = runBlocking {
        createSource()
        val original = sourceRows()
        val failure = rejected {
            migrateGenesisStorage(context, destination) { throw IllegalStateException("Injected fictional interruption") }
        }
        assertEquals("Injected fictional interruption", failure.message)
        assertTrue(destinationRows().values.all { it.isEmpty() })
        assertNull(destination.genesisStorageDao().receipt())
        assertEquals(original, sourceRows())

        val completed = migrateGenesisStorage(context, destination)
        assertEquals(original, destinationRows())
        assertEquals(completed, destination.genesisStorageDao().receipt())
    }

    @Test fun durableReceiptSurvivesRestartAndNeverReplaysArchivedSourceOverLaterEdits() = runBlocking {
        createSource()
        val original = sourceRows()
        destination.close()
        destination = diskDestination()
        val receipt = migrateGenesisStorage(context, destination)
        val laterJson = " { \"id\" : \"fictional-player\", \"name\" : \"Later canonical edit\" } "
        destination.openHelper.writableDatabase.execSQL("UPDATE reforge_characters SET characterJson=?,revision=?,updatedAt=? WHERE profileId=?",
            arrayOf<Any?>(laterJson, 8L, 999L, "fictional-player"))
        val laterRows = destinationRows()
        destination.close()
        destination = diskDestination()

        val retried = migrateGenesisStorage(context, destination) { error("A completed cutover must not copy again") }
        assertEquals(receipt, retried)
        assertEquals(laterRows, destinationRows())
        assertEquals(original, sourceRows())
    }

    @Test fun identicalPartialDestinationRowsAreVerifiedAndCompletedWithoutDuplicateHistory() = runBlocking {
        createSource()
        destination.openHelper.writableDatabase.execSQL("INSERT INTO reforge_characters (profileId,characterJson,revision,originJson,createdAt,updatedAt) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>("fictional-player", characterJson, 7L, originJson, 123L, 456L))
        migrateGenesisStorage(context, destination)
        assertEquals(sourceRows(), destinationRows())
        assertTrue(destinationRows().values.all { it.size == 1 })
    }

    @Test fun conflictingDestinationIdentityRetainsBothOriginalsAndDoesNotClaimAuthority() = runBlocking {
        createSource()
        seedRaw(destination.openHelper.writableDatabase)
        destination.openHelper.writableDatabase.execSQL("UPDATE reforge_characters SET characterJson=? WHERE profileId=?",
            arrayOf<Any?>("{\"name\":\"Independent conflicting fictional actor\"}", "fictional-player"))
        val originalSource = sourceRows()
        val originalDestination = destinationRows()

        rejected { migrateGenesisStorage(context, destination) }
        assertEquals(originalSource, sourceRows())
        assertEquals(originalDestination, destinationRows())
        assertNull(destination.genesisStorageDao().receipt())
    }

    @Test fun unprovenAdditionalDestinationRowsCannotBeSilentlyMergedIntoCutover() = runBlocking {
        createSource()
        destination.openHelper.writableDatabase.execSQL("INSERT INTO reforge_note_drafts (profileId,expectedRevision,text,updatedAt) VALUES (?,?,?,?)",
            arrayOf<Any?>("different-fixture", 3L, "Independent local draft", 200L))
        val originalSource = sourceRows()
        val originalDestination = destinationRows()

        rejected { migrateGenesisStorage(context, destination) }
        assertEquals(originalSource, sourceRows())
        assertEquals(originalDestination, destinationRows())
        assertNull(destination.genesisStorageDao().receipt())
    }

    @Test fun absentSourceEstablishesEmptyCanonicalStoreWithoutCreatingAnOldDatabase() = runBlocking {
        assertFalse(context.getDatabasePath(sourceName).exists())
        val receipt = migrateGenesisStorage(context, destination)
        assertTrue(destinationRows().values.all { it.isEmpty() })
        assertEquals(receipt, destination.genesisStorageDao().receipt())
        assertEquals(receipt, migrateGenesisStorage(context, destination))
        assertFalse("A new installation must not manufacture an old source store", context.getDatabasePath(sourceName).exists())
    }

    @Test fun missingSourceDoesNotAuthorizePreexistingUnprovenDestinationData() = runBlocking {
        seedRaw(destination.openHelper.writableDatabase)
        val original = destinationRows()
        rejected { migrateGenesisStorage(context, destination) }
        assertEquals(original, destinationRows())
        assertNull(destination.genesisStorageDao().receipt())
        assertFalse(context.getDatabasePath(sourceName).exists())
    }

    @Test fun orphanedSourceWalIsRetainedForRecoveryInsteadOfTreatingTheInstallAsEmpty() = runBlocking {
        val sourceFile = context.getDatabasePath(sourceName)
        requireNotNull(sourceFile.parentFile).mkdirs()
        val wal = java.io.File(sourceFile.absolutePath + "-wal")
        val recoveryBytes = "Fictional retained WAL evidence".toByteArray(Charsets.UTF_8)
        wal.writeBytes(recoveryBytes)
        try {
            rejected { migrateGenesisStorage(context, destination) }
            assertTrue(destinationRows().values.all { it.isEmpty() })
            assertNull(destination.genesisStorageDao().receipt())
            assertFalse(sourceFile.exists())
            assertTrue(wal.isFile)
            assertArrayEquals(recoveryBytes, wal.readBytes())
        } finally { wal.delete() }
    }

    @Test fun unknownSourceSchemaIsRetainedAndRejectedWithoutEmptyFallback() = runBlocking {
        createSource()
        val original = sourceRows()
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = 2
        }

        rejected { migrateGenesisStorage(context, destination) }
        assertTrue(destinationRows().values.all { it.isEmpty() })
        assertNull(destination.genesisStorageDao().receipt())
        assertEquals(original, sourceRows())
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
        }
    }
}
