package com.yazsras.astravar.rules

import java.io.Reader
import kotlinx.serialization.encodeToString

/** Streaming structural filter. No JSON tree/list is built for an old history array.
 * Only one record and the retained window exist at once. Historical records with invalid
 * contents are isolated; malformed structural JSON or invalid campaign resources fail safely.
 */
class HistoryJson(source: Reader) {
    private val input=source.buffered(8192)
    private var next=input.read()
    private var consumed=0L
    var skippedRecords=0; private set
    companion object {
        const val MAX_INPUT_CHARS=512L*1024*1024
        const val MAX_SHOT_CHARS=1024*1024
        private val NUMBER=Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
    private fun advance(): Int {
        require(++consumed<=MAX_INPUT_CHARS) { "Backup exceeds the streaming import limit" }
        val c=next;next=input.read();return c
    }
    private fun space() { while(next==32 || next==10 || next==13 || next==9) advance() }
    private fun expect(c: Char) { space();require(next==c.code) {"Malformed JSON"};advance() }
    private fun fields(visit: (String)->Unit) {
        expect('{');space();if(next=='}'.code) {advance();return}
        val keys=mutableSetOf<String>()
        while(true) {
            space();require(next=='"'.code) {"Expected a JSON field"}
            val key=BackupCodec.json.decodeFromString<String>(value(1024) ?: error("JSON key too long"))
            require(keys.add(key)) {"Duplicate JSON field"};expect(':');visit(key);space()
            if(next=='}'.code) {advance();return};expect(',')
        }
    }
    private fun array(visit: ()->Unit) {
        expect('[');space();if(next==']'.code) {advance();return}
        while(true) {visit();space();if(next==']'.code) {advance();return};expect(',')}
    }
    /** Null means oversized: consume the whole value, but stop retaining its characters. */
    private fun value(limit: Int): String? {
        var out: StringBuilder?=if(limit>0) StringBuilder() else null
        fun emit(c: Int) { val b=out ?: return;if(b.length<limit) b.append(c.toChar()) else out=null }
        fun take()=advance().also(::emit)
        fun string() {
            require(take()=='"'.code)
            while(true) {
                require(next>=32) {"Unterminated JSON string"}
                val c=take();if(c=='"'.code) return
                if(c=='\\'.code) {
                    val e=take();require(e in listOf('"'.code,'\\'.code,'/'.code,'b'.code,'f'.code,'n'.code,'r'.code,'t'.code,'u'.code)) {"Invalid JSON escape"}
                    if(e=='u'.code) repeat(4) {require(next.toChar() in "0123456789abcdefABCDEF");take()}
                }
            }
        }
        fun ws() {while(next==32 || next==10 || next==13 || next==9) take()}
        fun scan(depth: Int) {
            require(depth<=64) {"JSON nesting too deep"};ws()
            when(next) {
                '"'.code -> string()
                '{'.code -> {take();ws();if(next!='}'.code) while(true) {
                    require(next=='"'.code);string();ws();require(take()==':'.code);scan(depth+1);ws()
                    if(next=='}'.code) break;require(take()==','.code);ws()
                };require(take()=='}'.code)}
                '['.code -> {take();ws();if(next!=']'.code) while(true) {
                    scan(depth+1);ws();if(next==']'.code) break;require(take()==','.code)
                };require(take()==']'.code)}
                else -> {
                    val token=StringBuilder()
                    while(next>=0 && next !in intArrayOf(32,10,13,9,44,93,125)) {require(token.length<128);token.append(take().toChar())}
                    require(token.toString() in listOf("true","false","null") || NUMBER.matches(token.toString())) {"Invalid JSON value"}
                }
            }
        }
        scan(0);return out?.toString()
    }
    private fun requiredValue()=value(BackupCodec.MAX_BYTES) ?: error("Campaign field exceeds 8 MiB")
    private fun objectText(parts: List<Pair<String,String>>): String {
        require(parts.sumOf {it.first.length.toLong()+it.second.length+4} <= BackupCodec.MAX_BYTES) {"Retained data exceeds 8 MiB"}
        return parts.joinToString(",","{","}") {BackupCodec.json.encodeToString(it.first)+":"+it.second}
    }
    fun campaignText(): String {
        val parts=mutableListOf<Pair<String,String>>()
        fields { key ->
            if(key!="pending") parts+=key to requiredValue()
            else {
                val shots=linkedMapOf<String,String>()
                array {
                    val raw=value(MAX_SHOT_CHARS)
                    val shot=try {raw?.let(BackupCodec::decodeShot)} catch(_: Exception) {null}
                    if(shot==null) skippedRecords++ else {
                        shots.remove(shot.plan.id);shots[shot.plan.id]=BackupCodec.json.encodeToString(shot)
                        if(shots.size>HistoryRetention.MAX_HISTORY_ENTRIES) shots.remove(shots.keys.first())
                    }
                }
                parts+=key to shots.values.joinToString(",","[","]")
            }
        }
        return objectText(parts)
    }
    fun eventText(): String {
        space();if(next!='{'.code) return requiredValue()
        val parts=mutableListOf<Pair<String,String>>()
        fields {key -> parts+=key to if(key=="before" || key=="after") {space();if(next=='{'.code) campaignText() else requiredValue()} else requiredValue()}
        return objectText(parts)
    }
    fun backupText(): String {
        val parts=mutableListOf<Pair<String,String>>()
        fields { key -> when(key) {
            "campaign" -> parts+=key to campaignText()
            "history" -> {
                val entries=java.util.TreeMap<Pair<Long,String>,HistoryEntry>(compareBy<Pair<Long,String>> {it.first}.thenBy {it.second})
                array {
                    val text=eventText()
                    val e=try {HistoryRetention.compact(BackupCodec.decodeHistoryEntry(text)).also(BackupCodec::validateHistory)} catch(_: Exception) {skippedRecords++;null}
                    if(e!=null) {
                        entries[e.after.revision to e.id]=e
                        if(entries.size>HistoryRetention.MAX_HISTORY_ENTRIES) entries.pollFirstEntry()
                    }
                }
                parts+=key to BackupCodec.json.encodeToString(entries.values.toList())
            }
            // These were duplicate before/after journals, not the History screen's roll source.
            // Retention permanently discards them; current campaign.pending is authoritative.
            "archives" -> {value(0);parts+=key to "[]"}
            else -> parts+=key to requiredValue()
        } }
        return objectText(parts)
    }
    fun shotText(): String = value(MAX_SHOT_CHARS) ?: error("Historical roll exceeds its record limit")
    fun finish() {space();require(next==-1) {"Trailing JSON data"}}
}
