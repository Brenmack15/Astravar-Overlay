package com.yazsras.astravar.data

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yazsras.astravar.rules.*
import kotlinx.serialization.encodeToString
import java.io.Reader

/** Runs before Room exposes legacy state to any observer. No full history cursor is used. */
object HistoryStorage {
    const val ROLLS_QUERY="SELECT * FROM rolls ORDER BY sequence DESC, id DESC LIMIT " + HistoryRetention.MAX_HISTORY_ENTRIES
    const val EVENTS_QUERY="SELECT * FROM events ORDER BY revision DESC, id DESC LIMIT " + HistoryRetention.MAX_HISTORY_ENTRIES
    const val PRUNE_ROLLS="DELETE FROM rolls WHERE id NOT IN (SELECT id FROM rolls ORDER BY sequence DESC, id DESC LIMIT " + HistoryRetention.MAX_HISTORY_ENTRIES + ")"
    const val PRUNE_EVENTS="DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY revision DESC, id DESC LIMIT " + HistoryRetention.MAX_HISTORY_ENTRIES + ")"
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rolls (id TEXT NOT NULL PRIMARY KEY, sequence INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rolls_sequence_id ON rolls(sequence DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_revision_id ON events(revision DESC,id DESC)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS bound_rolls AFTER INSERT ON rolls BEGIN $PRUNE_ROLLS; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS bound_events AFTER INSERT ON events BEGIN $PRUNE_EVENTS; END")
    }
    fun cleanup(db: SupportSQLiteDatabase) {
        db.execSQL(PRUNE_EVENTS);db.execSQL(PRUNE_ROLLS)
        val hasState=db.query("SELECT id FROM campaign WHERE id=1").use {it.moveToFirst()}
        if(hasState) {
            val s=BackupCodec.decodeCampaignSnapshot(reader(db,"campaign",1))
            if(s.pending.isNotEmpty()) {
                db.execSQL("DELETE FROM rolls")
                s.pending.forEachIndexed {i,shot->db.execSQL("INSERT INTO rolls(id,sequence,body) VALUES (?,?,?)",arrayOf<Any>(shot.plan.id,i.toLong(),BackupCodec.json.encodeToString(shot)))}
            }
            db.execSQL("UPDATE campaign SET body=? WHERE id=1",arrayOf(BackupCodec.json.encodeToString(s.copy(pending=emptyList()))))
        }
        // Only indexed identifiers are collected, already bounded by SQL. Each body is streamed separately.
        val ids=db.query("SELECT id FROM events ORDER BY revision DESC,id DESC LIMIT ${HistoryRetention.MAX_HISTORY_ENTRIES}").use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
        ids.forEach {id ->
            try {
                val stream=HistoryJson(reader(db,"events",id));val raw=stream.eventText();stream.finish()
                val event=HistoryRetention.compact(BackupCodec.decodeHistoryEntry(raw)).also(BackupCodec::validateHistory)
                db.execSQL("UPDATE events SET body=?,label=?,gameSeconds=?,undone=? WHERE id=?",arrayOf(BackupCodec.json.encodeToString(event),event.label,event.gameSeconds,if(event.undone) 1 else 0,id))
            } catch(e: Exception) {
                Log.w("AstravarHistory","Discarding invalid accounting record $id",e)
                db.execSQL("DELETE FROM events WHERE id=?",arrayOf(id))
            }
        }
        val rollIds=db.query("SELECT id FROM rolls ORDER BY sequence DESC,id DESC LIMIT ${HistoryRetention.MAX_HISTORY_ENTRIES}").use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
        rollIds.forEach {id ->try {val stream=HistoryJson(reader(db,"rolls",id));require(BackupCodec.decodeShot(stream.shotText()).plan.id==id);stream.finish()} catch(e: Exception) {
            Log.w("AstravarHistory","Discarding invalid roll $id",e);db.execSQL("DELETE FROM rolls WHERE id=?",arrayOf(id))
        }}
        // Prior imports duplicated the entire journal here. They are not another retained roll source.
        db.execSQL("DELETE FROM archives")
    }
    /** SQLite substr limits both cursor-window and JVM string allocations, including oversized rows. */
    private fun reader(db: SupportSQLiteDatabase,table: String,id: Any): Reader = object: java.io.InputStream() {
        private var offset=1L
        private var chunk=byteArrayOf()
        private var position=0
        private var end=false
        override fun read(): Int {val one=ByteArray(1);return if(read(one,0,1)<0) -1 else one[0].toInt() and 255}
        override fun read(target: ByteArray,start: Int,length: Int): Int {
            if(length==0) return 0
            if(position==chunk.size) {
                if(end) return -1
                // BLOB offsets are bytes: no repeated UTF-8 prefix scanning for late chunks.
                chunk=db.query("SELECT substr(CAST(body AS BLOB),?,8192) FROM $table WHERE id=?",arrayOf(offset,id)).use {c->if(c.moveToFirst()) c.getBlob(0) else byteArrayOf()}
                position=0;offset+=chunk.size
                if(chunk.isEmpty()) {end=true;return -1}
            }
            val n=minOf(length,chunk.size-position);chunk.copyInto(target,start,position,position+n);position+=n;return n
        }
        override fun close() {end=true;chunk=byteArrayOf()}
    }.reader(Charsets.UTF_8)
}
