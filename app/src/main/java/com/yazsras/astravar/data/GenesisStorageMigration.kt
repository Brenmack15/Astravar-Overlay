package com.yazsras.astravar.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/** Installation-local cutover proof, not a gameplay approval or a portable backup identity. */
@Entity(tableName = "genesis_storage_receipt")
data class GenesisStorageReceipt(
    @PrimaryKey val id: Int = 1,
    val sourceVersion: Int,
    val sourceDigest: String,
    val countsJson: String,
    val completedAt: Long,
    val authority: String = "campaign-ledger-reforge-v1"
)

@Dao interface GenesisStorageDao {
    @Query("SELECT * FROM genesis_storage_receipt WHERE id=1")
    suspend fun receipt(): GenesisStorageReceipt?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(receipt: GenesisStorageReceipt)
}

private data class GenesisRawTable(val name: String, val columns: String) {
    val fields = columns.split(',')
    val rowIds = "SELECT rowid FROM `$name` ORDER BY `${fields.first()}`"
    // At most 32 KiB per column crosses CursorWindow, even for multi-megabyte history rows.
    val metadata = fields.joinToString { column ->
        "typeof(`$column`),length(CAST(`$column` AS BLOB))," +
            "CASE WHEN typeof(`$column`) IN ('text','blob') THEN substr(CAST(`$column` AS BLOB),1,$GENESIS_COLUMN_CHUNK) ELSE `$column` END"
    }
}

private const val GENESIS_COLUMN_CHUNK = 32 * 1024

private val genesisTables = listOf(
    GenesisRawTable("reforge_characters", "profileId,characterJson,revision,originJson,createdAt,updatedAt"),
    GenesisRawTable("reforge_changes", "commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType"),
    GenesisRawTable("reforge_rolls", "id,profileId,characterRevision,label,formula,components,manual,ownership,createdAt"),
    GenesisRawTable("reforge_note_drafts", "profileId,expectedRevision,text,updatedAt")
)

val CAMPAIGN_MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `reforge_characters` (`profileId` TEXT NOT NULL, `characterJson` TEXT NOT NULL, `revision` INTEGER NOT NULL, `originJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reforge_changes` (`commandId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `operation` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `priorRevision` INTEGER NOT NULL, `resultingRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `beforeJson` TEXT, `afterJson` TEXT NOT NULL, `outcomeType` TEXT NOT NULL, PRIMARY KEY(`commandId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reforge_changes_profileId` ON `reforge_changes` (`profileId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reforge_changes_createdAt` ON `reforge_changes` (`createdAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reforge_rolls` (`id` TEXT NOT NULL, `profileId` TEXT NOT NULL, `characterRevision` INTEGER NOT NULL, `label` TEXT NOT NULL, `formula` TEXT NOT NULL, `components` TEXT NOT NULL, `manual` INTEGER NOT NULL, `ownership` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reforge_rolls_profileId` ON `reforge_rolls` (`profileId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reforge_rolls_createdAt` ON `reforge_rolls` (`createdAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reforge_note_drafts` (`profileId` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_storage_receipt` (`id` INTEGER NOT NULL, `sourceVersion` INTEGER NOT NULL, `sourceDigest` TEXT NOT NULL, `countsJson` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `authority` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** No entire wide row, or unbounded text cell, is materialized by an Android CursorWindow. */
private fun rawRow(query: (String) -> Cursor, table: GenesisRawTable, rowId: Long): List<Any?> =
    query("SELECT ${table.metadata} FROM `${table.name}` WHERE rowid=$rowId").use { cursor ->
        check(cursor.moveToFirst()) { "A playable source row changed during storage recovery." }
        table.fields.mapIndexed { column, field ->
            val offset = column * 3
            when (cursor.getString(offset)) {
                "null" -> null
                "integer" -> cursor.getLong(offset + 2)
                "real" -> cursor.getDouble(offset + 2)
                "text", "blob" -> {
                    val size = cursor.getLong(offset + 1)
                    require(size in 0..Int.MAX_VALUE.toLong()) { "A playable source field exceeds the supported recovery size. Its original database was retained." }
                    val prefix = cursor.getBlob(offset + 2)
                    val bytes = if (size <= GENESIS_COLUMN_CHUNK) prefix else {
                        val output = java.io.ByteArrayOutputStream(size.toInt())
                        output.write(prefix)
                        var start = prefix.size.toLong() + 1
                        while (start <= size) {
                            val part = query("SELECT substr(CAST(`$field` AS BLOB),$start,$GENESIS_COLUMN_CHUNK) FROM `${table.name}` WHERE rowid=$rowId").use {
                                check(it.moveToFirst()) { "A playable source row changed during storage recovery." }
                                it.getBlob(0)
                            }
                            check(part.isNotEmpty()) { "A playable source field changed during storage recovery." }
                            output.write(part)
                            start += part.size
                        }
                        output.toByteArray()
                    }
                    check(bytes.size.toLong() == size) { "A playable source field changed during storage recovery." }
                    if (cursor.getString(offset) == "blob") bytes else {
                        try {
                            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                        } catch (_: java.nio.charset.CharacterCodingException) {
                            error("A playable text field contains invalid UTF-8. Original bytes were retained for recovery.")
                        }
                    }
                }
                else -> error("An unsupported SQLite value was retained in the original playable database.")
            }
        }
    }

/** Length-prefix every value, retaining SQLite types and opaque JSON whitespace. */
private fun MessageDigest.field(value: Any?) {
    val kind: Byte = when (value) { null -> 0; is Long -> 1; is Double -> 2; is ByteArray -> 4; else -> 3 }
    val bytes = when (value) {
        null -> byteArrayOf()
        is ByteArray -> value
        is Double -> java.lang.Double.doubleToRawLongBits(value).toString().toByteArray(Charsets.UTF_8)
        else -> value.toString().toByteArray(Charsets.UTF_8)
    }
    update(kind)
    update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
    update(bytes)
}

private fun sameRawRow(first: List<Any?>, second: List<Any?>): Boolean = first.size == second.size &&
    first.indices.all { i -> if (first[i] is ByteArray && second[i] is ByteArray)
        (first[i] as ByteArray).contentEquals(second[i] as ByteArray) else first[i] == second[i] }

private fun rawDigest(query: (String) -> Cursor): Pair<String, Map<String, Long>> {
    val hash = MessageDigest.getInstance("SHA-256")
    val counts = linkedMapOf<String, Long>()
    for (table in genesisTables) {
        hash.field(table.name)
        var count = 0L
        query(table.rowIds).use { cursor ->
            while (cursor.moveToNext()) {
                rawRow(query, table, cursor.getLong(0)).forEach { hash.field(it) }
                count++
            }
        }
        hash.field(count)
        counts[table.name] = count
    }
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to counts
}

private fun checkSourceSchema(source: SQLiteDatabase) {
    require(source.version == 1) { "The original playable database has an unsupported schema. It was retained for recovery." }
    source.rawQuery("PRAGMA encoding", null).use { cursor ->
        require(cursor.moveToFirst() && cursor.getString(0).equals("UTF-8", ignoreCase = true)) {
            "The original playable database uses an unsupported text encoding. Its original bytes were retained."
        }
    }
    val allowed = genesisTables.map { it.name }.toSet() + setOf("room_master_table", "android_metadata")
    source.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
        while (cursor.moveToNext()) require(cursor.getString(0) in allowed) {
            "The original playable database contains an unrecognized table. Review it before moving storage."
        }
    }
    for (table in genesisTables) {
        val columns = mutableListOf<String>()
        source.rawQuery("PRAGMA table_info(`${table.name}`)", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        require(columns == table.fields) { "The original playable database has an unexpected table shape. Original records were retained." }
    }
    source.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
        require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "The original playable database needs recovery before storage can move." }
    }
}

/**
 * All new-version production access to the old file is read-only. The app's ready gate prevents
 * playable writes until this completes. Source bytes are copied without interpreting old JSON or
 * discarding unknown receipt payloads. The original database and WAL are never deleted or replaced.
 */
internal suspend fun migrateGenesisStorage(context: Context, destination: CampaignLedgerDb,
    beforeCommit: suspend () -> Unit = {}): GenesisStorageReceipt = withContext(Dispatchers.IO) {
    destination.withTransaction {
        destination.genesisStorageDao().receipt()?.let {
            val counts = runCatching { reforgeJson.decodeFromString<Map<String, Long>>(it.countsJson) }.getOrNull()
            require(it.authority == "campaign-ledger-reforge-v1" && it.sourceVersion in 0..1 &&
                it.sourceDigest.matches(Regex("[0-9a-f]{64}")) && it.completedAt > 0 &&
                counts != null && counts.keys == genesisTables.map { table -> table.name }.toSet() &&
                counts.values.all { count -> count >= 0 } && (it.sourceVersion != 0 || counts.values.all { count -> count == 0L })) {
                "Unknown or incomplete playable storage authority. Review recovery before continuing."
            }
            return@withTransaction it
        }
        val target = destination.openHelper.writableDatabase
        val sourceFile = context.applicationContext.getDatabasePath("astravar-reforge.db")
        require(sourceFile.exists() || listOf("-wal", "-shm", "-journal").none { java.io.File(sourceFile.path + it).exists() }) {
            "Playable recovery files exist without their original database. They were retained; review recovery before continuing."
        }
        val copied = if (sourceFile.exists()) {
            SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                genesisOriginalDatabaseErrorHandler).use { source ->
                checkSourceSchema(source)
                val baseline = rawDigest { source.rawQuery(it, null) }
                for (table in genesisTables) {
                    source.rawQuery(table.rowIds, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = rawRow({ source.rawQuery(it, null) }, table, cursor.getLong(0))
                            val existing = target.query("SELECT rowid FROM `${table.name}` WHERE `${table.fields.first()}`=?",
                                arrayOf(row.first())).use { found -> if (found.moveToFirst()) rawRow({ target.query(it) }, table, found.getLong(0)) else null }
                            require(existing == null || sameRawRow(existing, row)) {
                                "A different playable record already exists in campaign storage. Neither version was overwritten; review recovery."
                            }
                            if (existing == null) target.execSQL("INSERT INTO `${table.name}` (${table.fields.joinToString { "`$it`" }}) VALUES (${table.fields.joinToString { "?" }})", row.toTypedArray())
                        }
                    }
                }
                require(rawDigest { target.query(it) } == baseline) {
                    "Campaign storage contains unmatched playable records. Storage cutover was rolled back for review."
                }
                require(rawDigest { source.rawQuery(it, null) } == baseline) {
                    "The original playable records changed during recovery. Retry after closing other writers."
                }
                1 to baseline
            }
        } else {
            val empty = rawDigest { target.query(it) }
            require(empty.second.values.all { it == 0L }) {
                "Playable records exist without their storage authority or original database. Review recovery; no records were replaced."
            }
            0 to empty
        }
        beforeCommit()
        GenesisStorageReceipt(sourceVersion = copied.first, sourceDigest = copied.second.first,
            countsJson = reforgeJson.encodeToString(copied.second.second), completedAt = System.currentTimeMillis())
            .also { destination.genesisStorageDao().insert(it) }
    }
}

sealed interface GenesisStorageStatus {
    data object Loading : GenesisStorageStatus
    data object Ready : GenesisStorageStatus
    data class RecoveryRequired(val message: String) : GenesisStorageStatus
}

internal suspend fun requireReadableGenesisCharacters(db: CampaignLedgerDb) {
    require(db.reforgeDao().allCharacters().all { row ->
        runCatching { row.saved().let { it.character.id == row.profileId && it.character.revision == row.revision } }
            .getOrDefault(false)
    }) { "Some current playable records require a newer or repaired reader. Their complete original values are retained; no character was reset." }
}

/** One owner for nested Room transactions; independently owned connection databases stay independent. */
class GenesisApplicationStorage private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<GenesisStorageStatus>(GenesisStorageStatus.Loading)
    val status: StateFlow<GenesisStorageStatus> = mutableStatus
    private val db = CampaignLedgerDb.open(context)
    val campaign = CampaignLedgerRepository(db)
    val playable = ReforgeRepository(db, ready = ::awaitReady)
    init { retry() }

    fun retry() { scope.launch { runCatching { awaitReady() } } }

    private suspend fun awaitReady() = mutex.withLock {
        if (mutableStatus.value == GenesisStorageStatus.Ready) return@withLock
        mutableStatus.value = GenesisStorageStatus.Loading
        try {
            migrateGenesisStorage(context, db)
            // Storage copying never discards future/unknown payloads. An unreadable current actor
            // needs a recovery surface instead of an uncaught decoding failure or an empty roster.
            requireReadableGenesisCharacters(db)
            mutableStatus.value = GenesisStorageStatus.Ready
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutableStatus.value = GenesisStorageStatus.RecoveryRequired(e.message ?: "Playable storage needs recovery. Original records were retained.")
            throw e
        }
    }

    companion object {
        @Volatile private var instance: GenesisApplicationStorage? = null
        fun open(context: Context): GenesisApplicationStorage = instance ?: synchronized(this) {
            instance ?: GenesisApplicationStorage(context.applicationContext).also { instance = it }
        }
    }
}
