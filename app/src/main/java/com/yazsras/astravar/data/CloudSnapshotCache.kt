package com.yazsras.astravar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.*

internal data class CloudBinding(val origin: String, val continuity: String, val subject: String)

internal data class CloudVersion(val epoch: Long, val revision: Long, val grantRevision: Long, val grantExpiresAt: Long)

internal data class CloudSnapshot(val binding: CloudBinding, val version: CloudVersion, val checkedAt: Long, val visible: Boolean)

internal data class CloudHistoryCursor(val recordedAt: Long?, val id: String)

internal fun cloudHistoryCursor(row: JsonObject): CloudHistoryCursor {
    val stamp = (row["data"] as? JsonObject)?.get("recordedAt") as? JsonPrimitive
    return CloudHistoryCursor(stamp?.takeUnless { it.isString }?.longOrNull, row.cloudText("id"))
}

internal data class CloudPage(val version: CloudVersion, val records: List<JsonObject>, val nextAfter: String?)

internal val cloudIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

internal fun JsonObject.cloudText(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString)
    it.content
}

private fun JsonObject.cloudNumber(name: String): Long = getValue(name).jsonPrimitive.let {
    require(!it.isString)
    it.long.also { number -> require(number in 0..9_007_199_254_740_991L) }
}

internal fun cloudPage(body: JsonObject, binding: CloudBinding, after: String?, expected: CloudVersion?, now: Long): CloudPage {
    require(body.cloudText("authority") == "cloud" && body.cloudText("subject") == binding.subject &&
        body.cloudText("continuityId") == binding.continuity)
    val version = CloudVersion(body.cloudNumber("epoch"), body.cloudNumber("revision"), body.cloudNumber("grantRevision"),
        body.cloudNumber("grantExpiresAt"))
    require(version.epoch > 0 && version.grantExpiresAt > now)
    if (expected != null && version != expected) throw OwnerSyncFailure("SNAPSHOT_CHANGED")
    val records = body.getValue("records").jsonArray.also { require(it.size<=100) }.map { it.jsonObject }
    var priorId = after.orEmpty()
    records.forEach { row ->
        val id = row.cloudText("id")
        require(cloudIdentifier.matches(id) && id > priorId && cloudIdentifier.matches(row.cloudText("kind")))
        require(row.getValue("actorId").let { it == JsonNull || (it is JsonPrimitive && it.isString && cloudIdentifier.matches(it.content)) })
        // A staged native import retains its per-record revision before the first continuity command.
        require(row.cloudText("life") in setOf("active", "archived") && row.cloudNumber("revision") >= 1)
        row.cloudText("title")
        row.getValue("data").jsonObject
        priorId = id
    }
    val cursor = body.getValue("nextAfter").let { if (it == JsonNull) null else body.cloudText("nextAfter") }
    if (cursor != null) require(records.isNotEmpty() && cursor == priorId)
    return CloudPage(version, records, cursor)
}

/** Disposable, credential-bound read model. This database has no gameplay mutation API. */
internal class CloudSnapshotCache(context: Context, name: String? = "astravar-cloud-cache.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE workspace(slot INTEGER PRIMARY KEY CHECK(slot=1), origin TEXT NOT NULL,
            continuity TEXT NOT NULL, subject TEXT NOT NULL, epoch INTEGER NOT NULL, revision INTEGER NOT NULL,
            grant_revision INTEGER NOT NULL, grant_expiry INTEGER NOT NULL, checked_at INTEGER NOT NULL, token TEXT)""")
        db.execSQL("""CREATE TABLE records(token TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,life TEXT NOT NULL,
            actor TEXT,title TEXT NOT NULL,body TEXT NOT NULL,recorded_at INTEGER,PRIMARY KEY(token,id))""")
        createMediaCache(db)
        createHistoryIndex(db)
        db.execSQL("CREATE TABLE fenced(continuity TEXT PRIMARY KEY)")
        db.execSQL("CREATE INDEX cloud_cache_list ON records(token,life,kind,id)")
        db.execSQL("""CREATE TABLE commands(id TEXT PRIMARY KEY,origin TEXT NOT NULL,continuity TEXT NOT NULL,
            subject TEXT NOT NULL,envelope TEXT NOT NULL,acknowledged INTEGER NOT NULL DEFAULT 0)""")
    }

    private fun createMediaCache(db:SQLiteDatabase) = db.execSQL(
        "CREATE TABLE image_cache(id TEXT PRIMARY KEY,body BLOB NOT NULL CHECK(length(body)<=4194304))"
    )

    private fun createHistoryIndex(db: SQLiteDatabase) = db.execSQL(
        "CREATE INDEX cloud_actor_history ON records(token,actor,life,recorded_at DESC,id DESC)"
    )

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        require(old in 1..3 && new == 4) { "Unsupported cloud cache version." }
        if (old == 1) createMediaCache(db)
        if (old < 3) migrateHistoryIndex(db)
        db.execSQL("ALTER TABLE commands ADD COLUMN acknowledged INTEGER NOT NULL DEFAULT 0")
    }

    private fun migrateHistoryIndex(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN recorded_at INTEGER")
        db.rawQuery("SELECT token,id,body FROM records", null).use { cursor ->
            val update = db.compileStatement("UPDATE records SET recorded_at=? WHERE token=? AND id=?")
            try {
                while (cursor.moveToNext()) {
                    val row = Json.parseToJsonElement(cursor.getString(2)).jsonObject
                    val time = cloudHistoryCursor(row).recordedAt ?: continue
                    update.bindLong(1,time); update.bindString(2,cursor.getString(0)); update.bindString(3,cursor.getString(1))
                    update.executeUpdateDelete()
                }
            } finally { update.close() }
        }
        createHistoryIndex(db)
    }

    fun image(binding:CloudBinding,id:String,now:Long):ByteArray? = readableDatabase.rawQuery(
        "SELECT i.body FROM image_cache i,workspace w WHERE i.id=? AND w.token IS NOT NULL AND w.origin=? AND w.continuity=? AND w.subject=? AND w.grant_expiry>?",
        arrayOf(id,binding.origin,binding.continuity,binding.subject,now.toString())
    ).use { if(it.moveToFirst())it.getBlob(0) else null }

    fun evictImage(snapshot:CloudSnapshot,id:String) = transaction { db ->
        val current=workspace()
        if(current?.binding==snapshot.binding && current.version==snapshot.version)
            db.delete("image_cache","id=?",arrayOf(id))
    }

    fun saveImage(snapshot:CloudSnapshot,id:String,bytes:ByteArray) = transaction { db ->
        val current=workspace()
        require(current?.visible==true && current.version==snapshot.version && current.binding==snapshot.binding && bytes.size in 1..cloudMediaMax)
        db.execSQL("INSERT OR REPLACE INTO image_cache VALUES(?,?)",arrayOf(id,bytes))
        // Thumbnails are reconstructible and share an installation-wide cap.
        db.execSQL("DELETE FROM image_cache WHERE rowid NOT IN(SELECT rowid FROM image_cache ORDER BY rowid DESC LIMIT 100)")
    }

    fun workspace(): CloudSnapshot? = readableDatabase.rawQuery(
        "SELECT origin,continuity,subject,epoch,revision,grant_revision,grant_expiry,checked_at,token FROM workspace WHERE slot=1", null
    ).use {
        if (!it.moveToFirst()) null else CloudSnapshot(CloudBinding(it.getString(0), it.getString(1), it.getString(2)),
            CloudVersion(it.getLong(3), it.getLong(4), it.getLong(5), it.getLong(6)), it.getLong(7), !it.isNull(8))
    }

    private inline fun transaction(work: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { work(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    /** Keep the routing fence even when access expires or the owner revokes a grant. */
    fun hide() = transaction { db ->
        db.execSQL("UPDATE workspace SET token=NULL")
        db.delete("records", null, null)
        db.delete("image_cache",null,null)
        db.delete("commands", null, null)
    }

    fun fenced(continuity: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM fenced WHERE continuity=?", arrayOf(continuity)
    ).use { it.moveToFirst() }

    fun checked(at:Long) {
        writableDatabase.execSQL("UPDATE workspace SET checked_at=? WHERE token IS NOT NULL",arrayOf(at))
    }

    fun clearIncomplete() {
        writableDatabase.execSQL("DELETE FROM records WHERE token NOT IN (SELECT token FROM workspace WHERE token IS NOT NULL)")
    }

    fun stage(token: String, page: CloudPage) = transaction { db ->
        for (row in page.records) db.insertOrThrow("records", null, ContentValues().apply {
            put("token", token); put("id", row.cloudText("id")); put("kind", row.cloudText("kind"))
            put("actor", row["actorId"]?.jsonPrimitive?.contentOrNull)
            put("life", row.cloudText("life")); put("title", row.cloudText("title")); put("body", row.toString())
            cloudHistoryCursor(row).recordedAt?.let { put("recorded_at", it) }
        })
    }

    fun discard(token: String) {
        writableDatabase.delete("records", "token=? AND token NOT IN (SELECT token FROM workspace WHERE token IS NOT NULL)", arrayOf(token))
    }

    fun publish(binding: CloudBinding, version: CloudVersion, checkedAt: Long, token: String, explicit: Boolean) = transaction { db ->
        val current = workspace()
        require(version.grantExpiresAt > checkedAt)
        require(explicit || current?.binding == binding)
        val previous = current?.takeIf { it.binding == binding }
        if (previous != null) {
            require(version.epoch >= previous.version.epoch)
            if (version.epoch == previous.version.epoch) {
                require(version.revision >= previous.version.revision && version.grantRevision >= previous.version.grantRevision)
            }
        }
        if(previous?.version!=version || previous?.binding!=binding)db.delete("image_cache",null,null)
        if (previous == null || previous.version.grantRevision != version.grantRevision || previous.version.epoch != version.epoch)
            db.delete("commands", null, null)
        db.execSQL("INSERT OR IGNORE INTO fenced VALUES(?)", arrayOf(binding.continuity))
        db.execSQL("INSERT OR REPLACE INTO workspace VALUES(1,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(
            binding.origin, binding.continuity, binding.subject, version.epoch, version.revision, version.grantRevision,
            version.grantExpiresAt, checkedAt, token))
        db.delete("records", "token<>?", arrayOf(token))
    }

    fun records(binding: CloudBinding, now: Long, after: String = "", limit: Int = 50,
        kinds: Set<String> = emptySet(), actorId: String? = null, query: String = ""): List<JsonObject> {
        require(limit in 1..100 && kinds.size <= 100 && kinds.all(cloudIdentifier::matches))
        val clauses = mutableListOf("w.origin=?", "w.continuity=?", "w.subject=?", "w.grant_expiry>?", "r.id>?", "r.life='active'")
        val args = mutableListOf(binding.origin, binding.continuity, binding.subject, now.toString(), after)
        if (kinds.isNotEmpty()) {
            clauses.add("r.kind IN (" + kinds.joinToString(",") { "?" } + ")")
            args.addAll(kinds)
        }
        if (actorId != null) { clauses.add("r.actor=?"); args.add(actorId) }
        if (query.isNotBlank()) { clauses.add("instr(lower(r.title),lower(?))>0"); args.add(query) }
        args.add(limit.toString())
        val sql = "SELECT r.body FROM records r JOIN workspace w ON w.token=r.token WHERE " +
            clauses.joinToString(" AND ") + " ORDER BY r.id LIMIT ?"
        return readableDatabase.rawQuery(sql, args.toTypedArray())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(Json.parseToJsonElement(cursor.getString(0)).jsonObject) } }
    }

    fun actorHistory(binding: CloudBinding, now: Long, actorId: String, kinds: Set<String>, limit: Int,
        before: CloudHistoryCursor?): List<JsonObject> {
        require(cloudIdentifier.matches(actorId) && kinds.isNotEmpty() && kinds.size <= 30 && kinds.all(cloudIdentifier::matches) && limit in 1..100)
        val args = mutableListOf(binding.origin,binding.continuity,binding.subject,now.toString(),actorId)
        val where = mutableListOf("w.origin=?", "w.continuity=?", "w.subject=?", "w.grant_expiry>?", "r.actor=?", "r.life='active'",
            "r.kind IN (${kinds.joinToString(",") { "?" }})")
        args.addAll(kinds)
        before?.let { cursor ->
            require(cloudIdentifier.matches(cursor.id))
            if (cursor.recordedAt == null) { where.add("r.recorded_at IS NULL AND r.id<?"); args.add(cursor.id) }
            else {
                where.add("(r.recorded_at IS NULL OR r.recorded_at<? OR (r.recorded_at=? AND r.id<?))")
                args.add(cursor.recordedAt.toString()); args.add(cursor.recordedAt.toString()); args.add(cursor.id)
            }
        }
        args.add(limit.toString())
        val sql = "SELECT r.body FROM records r JOIN workspace w ON w.token=r.token WHERE " + where.joinToString(" AND ") +
            " ORDER BY r.recorded_at DESC,r.id DESC LIMIT ?"
        return readableDatabase.rawQuery(sql,args.toTypedArray()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Json.parseToJsonElement(cursor.getString(0)).jsonObject)
        } }
    }

    fun record(binding: CloudBinding, now: Long, id: String): JsonObject? = readableDatabase.rawQuery(
        """SELECT r.body FROM records r JOIN workspace w ON w.token=r.token
           WHERE w.origin=? AND w.continuity=? AND w.subject=? AND w.grant_expiry>? AND r.id=?""",
        arrayOf(binding.origin, binding.continuity, binding.subject, now.toString(), id)
    ).use { if (it.moveToFirst()) Json.parseToJsonElement(it.getString(0)).jsonObject else null }

    fun remember(binding: CloudBinding, command: JsonObject) {
        writableDatabase.insertOrThrow("commands", null, ContentValues().apply {
            put("id", command.cloudText("commandId")); put("origin", binding.origin); put("continuity", binding.continuity)
            put("subject", binding.subject); put("envelope", command.toString())
        })
    }

    fun command(binding:CloudBinding,id:String):JsonObject? = readableDatabase.rawQuery(
        "SELECT envelope FROM commands WHERE origin=? AND continuity=? AND subject=? AND id=?",
        arrayOf(binding.origin,binding.continuity,binding.subject,id)
    ).use { if(it.moveToFirst()) Json.parseToJsonElement(it.getString(0)).jsonObject else null }

    fun acknowledge(binding: CloudBinding, id: String) {
        writableDatabase.execSQL("UPDATE commands SET acknowledged=1 WHERE origin=? AND continuity=? AND subject=? AND id=?",
            arrayOf(binding.origin, binding.continuity, binding.subject, id))
    }

    fun uncertain(binding: CloudBinding, operation: String? = null, actorId: String? = null): JsonObject? = readableDatabase.rawQuery(
        "SELECT envelope FROM commands WHERE origin=? AND continuity=? AND subject=? AND acknowledged=0 ORDER BY rowid",
        arrayOf(binding.origin, binding.continuity, binding.subject)
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val command = Json.parseToJsonElement(cursor.getString(0)).jsonObject
            val actor = (command["args"] as? JsonObject)?.get("actorId")?.jsonPrimitive?.contentOrNull
            if (operation == null || (command.cloudText("operation") == operation && actor == actorId)) return@use command
        }
        null
    }

    fun pending(binding: CloudBinding): List<JsonObject> = readableDatabase.rawQuery(
        "SELECT envelope FROM commands WHERE origin=? AND continuity=? AND subject=? ORDER BY rowid LIMIT 100",
        arrayOf(binding.origin, binding.continuity, binding.subject)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(Json.parseToJsonElement(cursor.getString(0)).jsonObject) } }

    fun forget(id: String) { writableDatabase.delete("commands", "id=?", arrayOf(id)) }
}
