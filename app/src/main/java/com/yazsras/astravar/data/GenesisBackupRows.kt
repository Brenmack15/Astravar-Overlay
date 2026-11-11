package com.yazsras.astravar.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit backup reads must not make a wide historical row pass through CursorWindow.
 * Small rows keep a single-query fast path; large TEXT is decoded from bounded byte chunks.
 * Table/column names are private schema constants, never input from a document.
 */
internal class GenesisBackupRow private constructor(
    private val db: SupportSQLiteDatabase,
    private val table: String,
    private val rowId: Long,
    private val small: Map<String, Any?>?
) {
    fun text(column: String): String = requireNotNull(optionalText(column)) { "Stored $table.$column is unexpectedly null." }
    fun optionalText(column: String): String? {
        if (small != null) return small[column]?.let { require(it is String) { "Stored $table.$column is not text." }; it }
        val metadata = db.query("SELECT typeof(`$column`),length(CAST(`$column` AS BLOB)) FROM `$table` WHERE rowid=?", arrayOf(rowId)).use {
            check(it.moveToFirst()) { "Stored recovery row disappeared." }
            it.getString(0) to if (it.isNull(1)) null else it.getLong(1)
        }
        if (metadata.first == "null") return null
        require(metadata.first == "text") { "Stored $table.$column is not text." }
        val size = requireNotNull(metadata.second)
        require(size in 0..GENESIS_AUTHORITY_DOCUMENT_BYTES.toLong()) { "Stored $table.$column exceeds the complete backup size limit." }
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(128 * 1024, bytes.size - offset)
            val chunk = db.query("SELECT substr(CAST(`$column` AS BLOB),?,?) FROM `$table` WHERE rowid=?",
                arrayOf<Any>(offset.toLong() + 1, count, rowId)).use {
                check(it.moveToFirst() && !it.isNull(0)) { "Stored recovery text disappeared." }
                it.getBlob(0)
            }
            check(chunk.size == count) { "Stored recovery text was truncated." }
            chunk.copyInto(bytes, offset); offset += count
        }
        return validatedCampaignUtf8(bytes)
    }
    fun long(column: String): Long = requireNotNull(optionalLong(column)) { "Stored $table.$column is unexpectedly null." }
    fun optionalLong(column: String): Long? {
        if (small != null) return small[column]?.let { require(it is Long) { "Stored $table.$column is not an integer." }; it }
        return db.query("SELECT typeof(`$column`),CASE WHEN typeof(`$column`)='integer' THEN `$column` END FROM `$table` WHERE rowid=?", arrayOf(rowId)).use {
            check(it.moveToFirst()) { "Stored recovery row disappeared." }
            if (it.getString(0) == "null") null else {
                require(it.getString(0) == "integer") { "Stored $table.$column is not an integer." }
                it.getLong(1)
            }
        }
    }
    fun boolean(column: String): Boolean = long(column).let { require(it == 0L || it == 1L) { "Stored $table.$column is not a Boolean." }; it == 1L }

    companion object {
        internal fun <T> read(db: SupportSQLiteDatabase, table: String, columns: List<String>, order: String,
            keyColumn: String? = null, key: String? = null, decode: (GenesisBackupRow) -> T): List<T> {
            val totalBytes = columns.joinToString("+") { "coalesce(length(CAST(`$it` AS BLOB)),0)" }
            val where = if (keyColumn == null) "" else " WHERE `$keyColumn`=?"
            val args: Array<Any> = if (keyColumn == null) emptyArray() else arrayOf(requireNotNull(key))
            val ids = db.query("SELECT rowid,($totalBytes)<=524288 FROM `$table`$where ORDER BY $order", args).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to (cursor.getInt(1) == 1)) }
            }
            return ids.map { (rowId, isSmall) ->
                // getBlob on a TEXT cursor field is driver-dependent. Request an explicit
                // SQLite BLOB conversion while carrying the original SQL type separately.
                val selected = columns.joinToString { column ->
                    "typeof(`$column`),CASE WHEN typeof(`$column`)='text' THEN CAST(`$column` AS BLOB) ELSE `$column` END"
                }
                val small = if (!isSmall) null else db.query("SELECT $selected FROM `$table` WHERE rowid=?", arrayOf(rowId)).use { cursor ->
                    check(cursor.moveToFirst()) { "Stored recovery row disappeared." }
                    columns.mapIndexed { index, column ->
                        val valueIndex = index * 2 + 1
                        column to when (cursor.getString(index * 2)) {
                            "null" -> null
                            "integer" -> cursor.getLong(valueIndex)
                            "real" -> cursor.getDouble(valueIndex)
                            "text" -> validatedCampaignUtf8(cursor.getBlob(valueIndex))
                            "blob" -> cursor.getBlob(valueIndex)
                            else -> error("Unsupported stored recovery SQL type.")
                        }
                    }.toMap()
                }
                decode(GenesisBackupRow(db, table, rowId, small))
            }
        }
    }
}

internal fun genesisPlayableBackupRows(db: CampaignLedgerDb): ReforgeBackup {
    val sql = db.openHelper.writableDatabase
    fun <T> rows(table: String, columns: String, order: String, decode: (GenesisBackupRow) -> T) =
        GenesisBackupRow.read(sql, table, columns.split(','), order, decode = decode)
    return ReforgeBackup(exportedAt = 0,
        characters = rows("reforge_characters", "profileId,characterJson,revision,originJson,createdAt,updatedAt", "profileId") {
            ReforgeCharacterRow(it.text("profileId"), it.text("characterJson"), it.long("revision"), it.text("originJson"),
                it.long("createdAt"), it.long("updatedAt"))
        },
        changes = rows("reforge_changes", "commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType", "createdAt,commandId") {
            ReforgeChange(it.text("commandId"), it.text("profileId"), it.text("operation"), it.text("fingerprint"), it.long("priorRevision"),
                it.long("resultingRevision"), it.long("createdAt"), it.optionalText("beforeJson"), it.text("afterJson"), it.text("outcomeType"))
        },
        rolls = rows("reforge_rolls", "id,profileId,characterRevision,label,formula,components,manual,ownership,createdAt", "createdAt,id") {
            ReforgeRollRecord(it.text("id"), it.text("profileId"), it.long("characterRevision"), it.text("label"), it.text("formula"),
                reforgeJson.decodeFromString(it.text("components")), it.boolean("manual"), it.text("ownership"), it.long("createdAt"))
        },
        drafts = rows("reforge_note_drafts", "profileId,expectedRevision,text,updatedAt", "profileId") {
            ReforgeNoteDraft(it.text("profileId"), it.long("expectedRevision"), it.text("text"), it.long("updatedAt"))
        })
}

internal fun genesisAuthorityBackupRows(db: CampaignLedgerDb): GenesisAuthorityExport {
    val sql = db.openHelper.writableDatabase
    fun <T> rows(table: String, columns: String, order: String, decode: (GenesisBackupRow) -> T) =
        GenesisBackupRow.read(sql, table, columns.split(','), order, decode = decode)
    return GenesisAuthorityExport(
        campaigns = rows("genesis_campaigns", "id,name,notes,revision,archived,createdAt,updatedAt,commandId", "id") {
            GenesisCampaignRecord(it.text("id"), it.text("name"), it.text("notes"), it.long("revision"), it.boolean("archived"),
                it.long("createdAt"), it.long("updatedAt"), it.text("commandId"))
        },
        actors = rows("genesis_actor_authority", "actorId,role,campaignId,ownerActorId,playable,revision,activationCommandId,lastCommandId,playableRevision,projectionFingerprint,resourceBindingsJson,createdAt,updatedAt", "actorId") {
            GenesisActorAuthority(it.text("actorId"), GenesisActorRole.valueOf(it.text("role")), it.optionalText("campaignId"),
                it.optionalText("ownerActorId"), it.boolean("playable"), it.long("revision"), it.text("activationCommandId"),
                it.text("lastCommandId"), it.optionalLong("playableRevision"), it.optionalText("projectionFingerprint"),
                it.text("resourceBindingsJson"), it.long("createdAt"), it.long("updatedAt"))
        },
        items = rows("genesis_item_authority", "itemId,ownerActorId,definitionJson,instanceJson,revision,activationCommandId,sourceProofJson,updatedAt", "itemId") {
            GenesisItemAuthority(itemId = it.text("itemId"), ownerActorId = it.optionalText("ownerActorId"),
                definitionJson = it.text("definitionJson"), instanceJson = it.text("instanceJson"), revision = it.long("revision"),
                activationCommandId = it.text("activationCommandId"), sourceProofJson = it.optionalText("sourceProofJson"), updatedAt = it.long("updatedAt"))
        },
        receipts = rows("genesis_authority_receipts", "commandId,kind,fingerprint,requestJson,outcomeJson,createdAt", "commandId") {
            GenesisAuthorityReceipt(it.text("commandId"), it.text("kind"), it.text("fingerprint"), it.text("requestJson"),
                it.text("outcomeJson"), it.long("createdAt"))
        })
}

/** Retry lookup for a reviewed request can itself contain several megabytes of original proof. */
internal fun readGenesisAuthorityReceipt(db: CampaignLedgerDb, commandId: String): GenesisAuthorityReceipt? =
    GenesisBackupRow.read(db.openHelper.writableDatabase, "genesis_authority_receipts",
        "commandId,kind,fingerprint,requestJson,outcomeJson,createdAt".split(','), "commandId",
        keyColumn = "commandId", key = commandId) {
        GenesisAuthorityReceipt(it.text("commandId"), it.text("kind"), it.text("fingerprint"), it.text("requestJson"),
            it.text("outcomeJson"), it.long("createdAt"))
    }.singleOrNull()

internal fun readGenesisAuthorityItem(db: CampaignLedgerDb, itemId: String): GenesisItemAuthority? =
    GenesisBackupRow.read(db.openHelper.writableDatabase, "genesis_item_authority",
        "itemId,ownerActorId,definitionJson,instanceJson,revision,activationCommandId,sourceProofJson,updatedAt".split(','), "itemId",
        keyColumn = "itemId", key = itemId) {
        GenesisItemAuthority(itemId = it.text("itemId"), ownerActorId = it.optionalText("ownerActorId"),
            definitionJson = it.text("definitionJson"), instanceJson = it.text("instanceJson"), revision = it.long("revision"),
            activationCommandId = it.text("activationCommandId"), sourceProofJson = it.optionalText("sourceProofJson"), updatedAt = it.long("updatedAt"))
    }.singleOrNull()
