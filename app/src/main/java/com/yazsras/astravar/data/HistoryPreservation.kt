package com.yazsras.astravar.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.InputStream
import java.security.MessageDigest

/** Opaque immutable history in the existing archives table; never another campaign authority. */
object HistoryPreservation {
    const val PREFIX = "astravar-preserved-v1:"
    const val PENDING_PREFIX = "astravar-preserve-pending-v1:"
    const val ORIGINAL_PREFIX = "astravar-history-original-v1:"
    const val RESTORE_RECEIPT_PREFIX = "astravar-history-restore-receipt-v1:"
    private val tables = setOf("campaign", "events", "rolls", "archives")
    private val kinds = tables + "imports"
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val protected = listOf(PREFIX, ORIGINAL_PREFIX, RESTORE_RECEIPT_PREFIX)
    data class Stats(val records: Long, val bytes: Long)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun base(prefix: String, kind: String, sourceId: String) = "$prefix$kind:${hex(sourceId.toByteArray(Charsets.UTF_8))}:"
    fun id(kind: String, sourceId: String, sha256: String): String {
        require(kind in kinds && hashPattern.matches(sha256))
        return base(PREFIX, kind, sourceId) + sha256
    }
    fun isProtected(id: String) = protected.any(id::startsWith)
    private fun protectedSql(value: String) = protected.joinToString(" OR ", "(", ")") { "$value GLOB '$it*'" }

    /** No custom SQLite hash function is needed. Triggers deduplicate exact source/body bytes first. */
    fun install(db: SupportSQLiteDatabase) {
        // Protect the canonical snapshots, original-file chunks and durable import receipts, including OR REPLACE collisions.
        val protectedOld = protectedSql("OLD.id")
        val protectedNew = protectedSql("NEW.id")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_archive_no_delete BEFORE DELETE ON archives WHEN $protectedOld BEGIN SELECT RAISE(ABORT,'Preserved history is immutable'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_archive_no_update BEFORE UPDATE ON archives WHEN $protectedOld AND (OLD.id!=NEW.id OR CAST(OLD.body AS BLOB)!=CAST(NEW.body AS BLOB)) BEGIN SELECT RAISE(ABORT,'Preserved history is immutable'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_archive_no_update_collision BEFORE UPDATE ON archives WHEN $protectedNew AND OLD.id!=NEW.id BEGIN SELECT RAISE(ABORT,'Preserved history identity collision'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_archive_no_collision BEFORE INSERT ON archives WHEN $protectedNew AND EXISTS(SELECT 1 FROM archives WHERE id=NEW.id AND CAST(body AS BLOB)!=CAST(NEW.body AS BLOB)) BEGIN SELECT RAISE(ABORT,'Preserved history identity collision'); END")
        for (table in tables) {
            fun capture(alias: String): String {
                val source = "lower(hex(CAST($alias.id AS TEXT)))"
                val pending = "'$PENDING_PREFIX$table:' || $source || ':'"
                val canonical = "'$PREFIX$table:' || $source || ':'"
                val allowed = if (table == "archives") "NOT ${protectedSql("$alias.id")} AND $alias.id NOT GLOB '$PENDING_PREFIX*' AND " else ""
                return "INSERT INTO archives(id,body) SELECT $pending || lower(hex(randomblob(16))),$alias.body WHERE $allowed NOT EXISTS(SELECT 1 FROM archives WHERE ((id >= $pending AND id < $pending || '~') OR (id >= $canonical AND id < $canonical || '~')) AND CAST(body AS BLOB)=CAST($alias.body AS BLOB));"
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_${table}_delete BEFORE DELETE ON $table BEGIN ${capture("OLD")} END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_${table}_update_old BEFORE UPDATE ON $table WHEN CAST(OLD.body AS BLOB)!=CAST(NEW.body AS BLOB) OR OLD.id!=NEW.id BEGIN ${capture("OLD")} END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_${table}_update_new AFTER UPDATE ON $table WHEN CAST(OLD.body AS BLOB)!=CAST(NEW.body AS BLOB) OR OLD.id!=NEW.id BEGIN ${capture("NEW")} END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_${table}_insert AFTER INSERT ON $table BEGIN ${capture("NEW")} END")
            // SQLite REPLACE does not always invoke DELETE triggers. Preserve the old body explicitly before replacement.
            val oldPrefix = "'$PENDING_PREFIX$table:' || lower(hex(CAST(id AS TEXT))) || ':'"
            val excluded = if (table == "archives") "AND NOT ${protectedSql("NEW.id")} AND NEW.id NOT GLOB '$PENDING_PREFIX*'" else ""
            db.execSQL("CREATE TRIGGER IF NOT EXISTS preserve_${table}_replace BEFORE INSERT ON $table BEGIN INSERT INTO archives(id,body) SELECT $oldPrefix || lower(hex(randomblob(16))),body FROM $table AS prior WHERE id=NEW.id AND CAST(body AS BLOB)!=CAST(NEW.body AS BLOB) $excluded AND NOT EXISTS(SELECT 1 FROM archives AS saved WHERE ((saved.id >= '$PENDING_PREFIX$table:' || lower(hex(CAST(prior.id AS TEXT))) || ':' AND saved.id < '$PENDING_PREFIX$table:' || lower(hex(CAST(prior.id AS TEXT))) || ':~') OR (saved.id >= '$PREFIX$table:' || lower(hex(CAST(prior.id AS TEXT))) || ':' AND saved.id < '$PREFIX$table:' || lower(hex(CAST(prior.id AS TEXT))) || ':~')) AND CAST(saved.body AS BLOB)=CAST(prior.body AS BLOB)); END")
        }
    }

    /** Called before startup normalization; includes malformed records without attempting to decode them. */
    fun preserveAll(db: SupportSQLiteDatabase) {
        for (table in tables) {
            val where = if (table == "archives") " WHERE NOT ${protectedSql("id")} AND id NOT GLOB '$PENDING_PREFIX*'" else ""
            var last: String? = null
            while(true) {
                val query="SELECT id FROM $table"+where+(if(last == null) "" else if(where.isEmpty()) " WHERE id>?" else " AND id>?")+" ORDER BY id LIMIT 64"
                val ids=db.query(query,if(last == null) emptyArray() else arrayOf(last)).use { cursor -> buildList {
                    while(cursor.moveToNext()) add(cursor.getString(0))
                } }
                if(ids.isEmpty()) break
                for(sourceId in ids) {
                    val target = id(table, sourceId, bodySha256(db, table, sourceId))
                    db.execSQL("INSERT OR IGNORE INTO archives(id,body) SELECT ?,body FROM $table WHERE id=?", arrayOf(target,sourceId))
                }
                last=ids.last()
            }
        }
        normalizePending(db)
    }

    /** Hash/re-key within the caller's transaction. A failed archive insert aborts before any temporary copy is removed. */
    fun normalizePending(db: SupportSQLiteDatabase) {
        while (true) {
            val pending = db.query("SELECT id FROM archives WHERE id >= ? AND id < ? ORDER BY id LIMIT 64", arrayOf(PENDING_PREFIX,PENDING_PREFIX+"~")).use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            } }
            if (pending.isEmpty()) return
            for (temporary in pending) {
                val parts = temporary.removePrefix(PENDING_PREFIX).split(':')
                val sha=bodySha256(db,"archives",temporary)
                val target = if(parts.size == 3 && parts[0] in tables && parts[1].matches(Regex("(?:[0-9a-f]{2})*")))
                    PREFIX+parts[0]+":"+parts[1]+":"+sha
                    else id("archives",temporary,sha) // Preserve even malformed legacy staging metadata as opaque quarantine.
                db.execSQL("INSERT OR IGNORE INTO archives(id,body) SELECT ?,body FROM archives WHERE id=?", arrayOf(target,temporary))
                val retained = db.query("SELECT 1 FROM archives WHERE id=?", arrayOf(target)).use { it.moveToFirst() }
                check(retained) { "History preservation did not commit; original bytes retained." }
                db.execSQL("DELETE FROM archives WHERE id=?", arrayOf(temporary))
            }
        }
    }

    /** Preserve a supplied working-state/import string before a bounded codec can drop its history. */
    fun preserveText(db: SupportSQLiteDatabase, kind: String, sourceId: String, text: String): String {
        val sha = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        val target = id(kind,sourceId,sha)
        db.execSQL("INSERT OR IGNORE INTO archives(id,body) VALUES (?,?)",arrayOf(target,text))
        return target
    }
    fun hasEvent(db: SupportSQLiteDatabase, eventId: String): Boolean {
        val a=base(PREFIX,"events",eventId); val b=base(PENDING_PREFIX,"events",eventId)
        return db.query("SELECT 1 FROM archives WHERE (id >= ? AND id < ?) OR (id >= ? AND id < ?) LIMIT 1",
            arrayOf(a,a+"~",b,b+"~")).use { it.moveToFirst() }
    }
    fun stats(db: SupportSQLiteDatabase): Stats = db.query("SELECT COUNT(*),COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM archives").use {
        check(it.moveToFirst()); Stats(it.getLong(0),it.getLong(1))
    }
    fun bodySha256(db: SupportSQLiteDatabase, table: String, sourceId: String): String {
        val digest=MessageDigest.getInstance("SHA-256")
        openBody(db,table,sourceId).use { input -> val buffer=ByteArray(8192); while(true) {
            val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n)
        } }
        return hex(digest.digest())
    }
    fun openBody(db: SupportSQLiteDatabase, archiveId: String): InputStream = openBody(db,"archives",archiveId)
    /** Chunked BLOB offsets retain exact UTF-8 bytes, including malformed JSON and oversized SQLite rows. */
    fun openBody(db: SupportSQLiteDatabase, table: String, sourceId: String): InputStream {
        require(table in tables)
        return object: InputStream() {
            private var offset=1L; private var chunk=byteArrayOf(); private var position=0; private var ended=false
            override fun read(): Int { val one=ByteArray(1); return if(read(one,0,1)<0) -1 else one[0].toInt() and 255 }
            override fun read(target: ByteArray,start: Int,length: Int): Int {
                require(start>=0 && length>=0 && start<=target.size-length)
                if(length==0) return 0
                if(ended) return -1
                if(position==chunk.size) {
                    if(ended) return -1
                    chunk=db.query("SELECT substr(CAST(body AS BLOB),?,8192) FROM $table WHERE id=?",arrayOf<Any>(offset,sourceId)).use {
                        if(it.moveToFirst()) it.getBlob(0) else throw IllegalStateException("History source is missing; no records were deleted.")
                    }
                    position=0; offset+=chunk.size
                    if(chunk.isEmpty()) { ended=true; return -1 }
                }
                val n=minOf(length,chunk.size-position);chunk.copyInto(target,start,position,position+n);position+=n;return n
            }
            override fun close() { ended=true;chunk=byteArrayOf();position=0 }
        }
    }
}
