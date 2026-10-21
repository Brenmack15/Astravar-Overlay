package com.yazsras.astravar.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.*
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class HistoryBackupSummary(val revision: Long, val fingerprint: String, val archiveCount: Int,
    val archiveBytes: Long, val originalFileCount: Int, val originalBytes: Long)
data class HistoryRestorePreview(val campaign: Campaign, val archiveCount: Int, val archiveBytes: Long,
    val originalFileCount: Int, val originalBytes: Long, val legacyJson: Boolean)
data class HistoryRestoreReceipt(val operationId: String, val fingerprint: String, val resultingRevision: Long)
data class HistoryBackupLimits(val maxInputBytes: Long = 1024L * 1024 * 1024,
    val maxExpandedBytes: Long = 1024L * 1024 * 1024, val maxArchiveBytes: Long = 64L * 1024 * 1024,
    val maxArchives: Int = 100_000) {
    init { require(maxInputBytes in 1..1024L*1024*1024 && maxExpandedBytes in 1..1024L*1024*1024 &&
        maxArchiveBytes in 1..64L*1024*1024 && maxArchives in 1..100_000) }
}
class PendingHistoryRestore internal constructor(internal val directory: File, internal val backup: Backup,
    val preview: HistoryRestorePreview, val baselineRevision: Long, val baselineFingerprint: String,
    internal val fingerprint: String, internal val legacy: Boolean, internal val manifestFingerprint: String?) : Closeable {
    @Volatile internal var closed = false
    override fun close() { closed = true; directory.deleteRecursively() }
}

/** Complete opaque history transport. The decoded working view stays bounded to twenty entries. */
class HistoryBackup(context: Context, private val repository: Repository,
    private val limits: HistoryBackupLimits = HistoryBackupLimits()) {
    private val cache = context.applicationContext.cacheDir
    private val db get() = repository.historyDb
    companion object {
        const val FORMAT = "astravar-complete-history"
        private const val CHUNK = 65_536
        private const val MAX_LEGACY = 512L * 1024 * 1024
        private const val MAX_INDEX_LINE = 8192
        private const val ORIGINAL = "astravar-history-original-v1:"
        private const val RECEIPT = "astravar-history-restore-receipt-v1:"
        private val HASH = Regex("[a-f0-9]{64}")
    }
    private data class Raw(val id: String, val entry: String, val sha: String, val bytes: Long)
    private data class State(val revision: Long, val fingerprint: String)
    private data class Manifest(val count: Int, val bytes: Long, val originals: Int, val originalBytes: Long)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private suspend fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(CHUNK)
        while (true) { currentCoroutineContext().ensureActive();val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }; hash.digest().hex()
    }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString()+":"+canonical(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    private fun strictObject(text: String): JsonObject {
        var depth=0;var quoted=false;var escaped=false
        text.forEach { c ->
            if(quoted) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false}
            else when(c) {'"' -> quoted=true;'{','[' -> {depth++;require(depth<=64) {"Manifest nesting exceeds its limit."}}
                '}',']' -> {depth--;require(depth>=0)}}
        }
        require(depth==0 && !quoted)
        return Json.parseToJsonElement(text).jsonObject.also {
            require(canonical(it) == text) { "Noncanonical or duplicate manifest fields." }
        }
    }
    private fun stringField(j: JsonObject, key: String): String = j.getValue(key).jsonPrimitive.also {require(it.isString)}.content
    private fun number(j: JsonObject, key: String): Long = j.getValue(key).jsonPrimitive.also {
        require(!it.isString && Regex("0|[1-9][0-9]*").matches(it.content))
    }.long
    private fun utf8(file: File): Reader = InputStreamReader(file.inputStream(), Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    private fun smallText(file: File, maximum: Long): String {
        require(file.length() <= maximum); return utf8(file).use { it.readText() }
    }
    private fun temp(): File = File.createTempFile("astravar-history-", ".stage", cache).also {
        check(it.delete() && it.mkdir()) { "Private backup staging is unavailable." }
    }
    private fun entry(id: String) = "archives/" + sha(id.toByteArray(Charsets.UTF_8)) + ".bin"
    private fun local(directory: File, entry: String) = File(directory, entry.replace('/', '_'))
    private fun line(reader: Reader): String? {
        val text = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (text.isEmpty()) null else text.toString()
            if (c == 10) return text.toString()
            require(text.length < MAX_INDEX_LINE) { "Archive index line is too large." }; text.append(c.toChar())
        }
    }
    private fun raw(line: String): Raw {
        val j = strictObject(line)
        require(j.keys == setOf("id", "entry", "sha256", "bytes"))
        val id = stringField(j,"id")
        val name = stringField(j,"entry")
        val hash = stringField(j,"sha256")
        val bytes = number(j,"bytes")
        require(id.length in 1..2048 && name == entry(id) && HASH.matches(hash) && bytes in 0..limits.maxArchiveBytes)
        return Raw(id, name, hash, bytes)
    }
    private fun index(raw: Raw) = canonical(buildJsonObject {
        put("id", raw.id); put("entry", raw.entry); put("sha256", raw.sha); put("bytes", raw.bytes)
    })
    private suspend fun eachIndex(file: File, action: suspend (Raw) -> Unit) = utf8(file).buffered().use { reader ->
        while (true) {currentCoroutineContext().ensureActive();action(raw(line(reader) ?: break))}
    }
    /** SQL chunk reads never materialize an oversized history body in a cursor window. */
    private fun body(sql: SupportSQLiteDatabase, table: String, id: String): InputStream = object : InputStream() {
        private var offset = 1L; private var bytes = byteArrayOf(); private var position = 0
        override fun read(): Int { val one=ByteArray(1); return if(read(one,0,1)<0) -1 else one[0].toInt() and 255 }
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) {
                bytes = sql.query("SELECT substr(CAST(body AS BLOB),?,?) FROM $table WHERE id=?", arrayOf<Any>(offset, CHUNK, id))
                    .use { if(it.moveToFirst()) it.getBlob(0) else byteArrayOf() }
                offset += bytes.size; position = 0; if(bytes.isEmpty()) return -1
            }
            val n=minOf(length,bytes.size-position);bytes.copyInto(target,start,position,position+n);position+=n;return n
        }
    }
    private suspend fun hashBody(sql: SupportSQLiteDatabase, table: String, id: String): Pair<String, Long> = body(sql,table,id).use {
        val hash=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(CHUNK);var size=0L
        while(true) {currentCoroutineContext().ensureActive();val n=it.read(buffer);if(n<0)break;size+=n;require(size<=limits.maxArchiveBytes) {"An archival record exceeds the explicit byte budget; nothing was truncated."};hash.update(buffer,0,n)}
        hash.digest().hex() to size
    }
    private suspend fun state(sql: SupportSQLiteDatabase): State {
        val hash=MessageDigest.getInstance("SHA-256")
        for(table in listOf("campaign","events","rolls","archives")) {
            sql.query("SELECT CAST(id AS TEXT) FROM $table ORDER BY id").use { cursor -> while(cursor.moveToNext()) {
                val id=cursor.getString(0); val (digest,bytes)=hashBody(sql,table,id)
                hash.update("$table:${id.toByteArray().size}:$id:$bytes:$digest\n".toByteArray(Charsets.UTF_8))
            } }
        }
        val revision=sql.query("SELECT body FROM campaign WHERE id=1").use { c ->
            if(c.moveToFirst()) BackupCodec.decodeCampaignSnapshot(c.getString(0)).revision else 0L
        }
        return State(revision,hash.digest().hex())
    }
    private suspend fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer=ByteArray(CHUNK);var size=0L
        while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=limit) {"Backup exceeds its explicit byte budget; nothing was imported."};output.write(buffer,0,n)}
        return size
    }
    suspend fun export(output: OutputStream): HistoryBackupSummary = withContext(Dispatchers.IO) {
        repository.awaitHistoryReady()
        val folder=temp()
        try { db.withTransaction {
            val sql=db.openHelper.writableDatabase;val baseline=state(sql)
            val working=repository.export().toByteArray(Charsets.UTF_8)
            require(working.size<=BackupCodec.MAX_BYTES)
            File(folder,"backup.json").writeBytes(working)
            val indexFile=File(folder,"archives.ndjson");var count=0;var bytes=0L;var originals=0;var originalBytes=0L
            val bounded=object: FilterOutputStream(output) {
                private var written=0L
                override fun write(value: Int) {require(++written<=limits.maxInputBytes) {"Compressed backup exceeds its explicit input budget."};out.write(value)}
                override fun write(value: ByteArray, offset: Int, length: Int) {
                    require(written+length<=limits.maxInputBytes) {"Compressed backup exceeds its explicit input budget."}
                    out.write(value,offset,length);written+=length
                }
            }
            ZipOutputStream(BufferedOutputStream(bounded)).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"));zip.write(working);zip.closeEntry()
                indexFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    suspend fun append(table: String, sourceId: String, archiveId: String, digest: String, length: Long) {
                        require(++count<=limits.maxArchives);bytes+=length
                        require(bytes+working.size<=limits.maxExpandedBytes)
                        val record=Raw(archiveId,entry(archiveId),digest,length);val encoded=index(record)
                        require(encoded.length<=MAX_INDEX_LINE) {"Archive identity exceeds the portable index budget; nothing was truncated."}
                        raw(encoded) // Export and import enforce exactly the same identity/record limits.
                        zip.putNextEntry(ZipEntry(entry(archiveId)))
                        local(folder,record.entry).outputStream().buffered().use { staged -> body(sql,table,sourceId).use { input ->
                            val buffer=ByteArray(CHUNK);while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;zip.write(buffer,0,n);staged.write(buffer,0,n)}
                        } };zip.closeEntry()
                        writer.write(encoded);writer.newLine()
                        if(archiveId.startsWith(ORIGINAL+"file:")) {
                            require(length<=1024L*1024) {"Original-file descriptor exceeds its byte budget."}
                            val descriptor=body(sql,table,sourceId).use { it.readBytes() }.toString(Charsets.UTF_8)
                            val j=strictObject(descriptor);originals++;originalBytes+=j.getValue("bytes").jsonPrimitive.long
                            require(originalBytes<=limits.maxExpandedBytes) {"Original-file total exceeds its explicit byte budget."}
                        }
                    }
                    sql.query("SELECT id FROM archives ORDER BY id").use { c -> while(c.moveToNext()) {
                        val id=c.getString(0);val (digest,length)=hashBody(sql,"archives",id);append("archives",id,id,digest,length)
                    } }
                    // Include exact current rows even when no later mutation has archived their latest version yet.
                    for(table in listOf("campaign","events","rolls")) sql.query("SELECT CAST(id AS TEXT) FROM $table ORDER BY id").use { c -> while(c.moveToNext()) {
                        val id=c.getString(0);val (digest,length)=hashBody(sql,table,id)
                        val archived=HistoryPreservation.id(table,id,digest)
                        val exists=sql.query("SELECT 1 FROM archives WHERE id=?",arrayOf(archived)).use {it.moveToFirst()}
                        if(!exists) append(table,id,archived,digest,length)
                    } }
                }
                require(indexFile.length()<=64L*1024*1024 && bytes+working.size+indexFile.length()+16_384<=limits.maxExpandedBytes)
                zip.putNextEntry(ZipEntry("archives.ndjson"));indexFile.inputStream().use {copy(it,zip,64L*1024*1024)};zip.closeEntry()
                val manifest=canonical(buildJsonObject {
                    put("format",FORMAT);put("version",1);put("archiveCount",count);put("archiveBytes",bytes)
                    put("originalFileCount",originals);put("originalBytes",originalBytes)
                    put("workingSha256",sha(working));put("indexSha256",digest(indexFile))
                })
                File(folder,"manifest.json").writeText(manifest,Charsets.UTF_8)
                validate(folder) // Never report a successful export that this importer cannot restore.
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toByteArray(Charsets.UTF_8));zip.closeEntry()
            }
            HistoryBackupSummary(baseline.revision,baseline.fingerprint,count,bytes,originals,originalBytes)
        } } finally {folder.deleteRecursively()}
    }
    suspend fun prepare(input: InputStream): PendingHistoryRestore {
        var staged: File?=null
        try { return withContext(Dispatchers.IO) {
        repository.awaitHistoryReady()
        val baseline=db.withTransaction {state(db.openHelper.writableDatabase)}
        val folder=temp().also {staged=it}
        try {
            val source=File(folder,"input.bin");input.use { source.outputStream().use {out->copy(it,out,limits.maxInputBytes)} }
            val zipped=source.inputStream().use {it.read()==0x50 && it.read()==0x4b && it.read()==3 && it.read()==4}
            val backup: Backup;val info: Manifest
            if(zipped) { extract(source,folder);info=validate(folder);backup=utf8(File(folder,"backup.json")).use(BackupCodec::decode) }
            else {
                require(source.length()<=MAX_LEGACY)
                backup=utf8(source).use(BackupCodec::decode)
                info=originalParts(source,digest(source)) {_,_->}
            }
            PendingHistoryRestore(folder,backup,HistoryRestorePreview(backup.campaign,info.count,info.bytes,info.originals,info.originalBytes,!zipped),
                baseline.revision,baseline.fingerprint,digest(source),!zipped,if(zipped)digest(File(folder,"manifest.json")) else null)
        } catch(e: Throwable) {folder.deleteRecursively();throw e}
        } } catch(e: Throwable) {staged?.deleteRecursively();runCatching {input.close()};throw e}
    }
    private suspend fun extract(source: File, folder: File) {
        var bytes=0L;var count=0;val seen=HashSet<String>()
        ZipInputStream(source.inputStream().buffered()).use { zip -> while(true) {
            val e=zip.nextEntry ?: break;val name=e.name
            require(!e.isDirectory && seen.add(name) && ++count<=limits.maxArchives+3) {"Duplicate or excessive ZIP entries."}
            val maximum=when {
                name=="manifest.json" -> 16_384L
                name=="backup.json" -> BackupCodec.MAX_BYTES.toLong()
                name=="archives.ndjson" -> 64L*1024*1024
                Regex("archives/[a-f0-9]{64}\\.bin").matches(name) -> limits.maxArchiveBytes
                else -> error("Unexpected or unsafe ZIP path.")
            }
            require(e.size<0 || e.size<=maximum)
            val target=if(name.startsWith("archives/"))local(folder,name) else File(folder,name)
            target.outputStream().use { out ->
                val buffer=ByteArray(CHUNK);var entryBytes=0L
                while(true) {currentCoroutineContext().ensureActive();val n=zip.read(buffer);if(n<0)break;entryBytes+=n;bytes+=n
                    require(entryBytes<=maximum && bytes<=limits.maxExpandedBytes) {"Expanded backup exceeds its explicit byte budget."};out.write(buffer,0,n)}
            };zip.closeEntry()
        } }
        require(seen.containsAll(listOf("manifest.json","backup.json","archives.ndjson"))) {"Incomplete ZIP backup."}
    }
    private suspend fun validate(folder: File): Manifest {
        val header=strictObject(smallText(File(folder,"manifest.json"),16_384))
        require(header.keys==setOf("format","version","archiveCount","archiveBytes","originalFileCount","originalBytes","workingSha256","indexSha256"))
        require(stringField(header,"format")==FORMAT && number(header,"version")==1L)
        require(digest(File(folder,"backup.json"))==stringField(header,"workingSha256") &&
            digest(File(folder,"archives.ndjson"))==stringField(header,"indexSha256")) {"Working backup or index checksum mismatch."}
        val ids=HashSet<String>();var count=0;var bytes=0L;var originals=0;var originalBytes=0L
        eachIndex(File(folder,"archives.ndjson")) { row ->
            require(ids.add(row.id) && ++count<=limits.maxArchives) {"Duplicate archive ID."}
            val file=local(folder,row.entry)
            require(file.isFile && file.length()==row.bytes && digest(file)==row.sha) {"Archive checksum mismatch."}
            bytes+=row.bytes;require(bytes<=limits.maxExpandedBytes)
            require(!row.id.startsWith(HistoryPreservation.PENDING_PREFIX)) {"Internal pending history must be normalized before portable backup."}
            if(row.id.startsWith(HistoryPreservation.PREFIX)) {
                val parts=row.id.removePrefix(HistoryPreservation.PREFIX).split(':')
                require(parts.size==3 && parts[0] in setOf("campaign","events","rolls","archives","imports") &&
                    Regex("[0-9a-f]*").matches(parts[1]) && parts[1].length%2==0 && parts[2]==row.sha) {"Preserved record identity mismatch."}
                val source=ByteArray(parts[1].length/2) {parts[1].substring(it*2,it*2+2).toInt(16).toByte()}
                val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(source)).toString()
                require(HistoryPreservation.id(parts[0],decoded,row.sha)==row.id)
            }
            if(row.id.startsWith(RECEIPT)) {
                val restored=receipt(smallText(file,16_384))
                require(row.id==RECEIPT+restored.operationId) {"Restore receipt identity mismatch."}
            }
            if(row.id.startsWith(ORIGINAL)) require(row.id.startsWith(ORIGINAL+"chunk:") || row.id.startsWith(ORIGINAL+"file:")) {"Unknown original-file record."}
            if(row.id.startsWith(ORIGINAL+"chunk:")) {
                val hash=row.id.removePrefix(ORIGINAL+"chunk:");require(HASH.matches(hash))
                val encoded=smallText(file,2L*CHUNK);val decoded=Base64.decode(encoded,Base64.NO_WRAP)
                require(decoded.size in 1..CHUNK && sha(decoded)==hash && Base64.encodeToString(decoded,Base64.NO_WRAP)==encoded) {"Original chunk checksum mismatch."}
            }
            if(row.id.startsWith(ORIGINAL+"file:")) {
                val descriptor=strictObject(smallText(file,1024L*1024))
                require(descriptor.keys==setOf("format","sha256","bytes","chunks") && stringField(descriptor,"format")=="astravar-original-json-v1")
                val hash=stringField(descriptor,"sha256")
                require(HASH.matches(hash) && row.id==ORIGINAL+"file:"+hash)
                val expected=number(descriptor,"bytes");require(expected in 1..MAX_LEGACY)
                originalBytes+=expected;require(originalBytes<=limits.maxExpandedBytes) {"Original-file total exceeds its explicit byte budget."}
                val parts=descriptor.getValue("chunks").jsonArray;require(parts.size in 1..8192)
                val total=MessageDigest.getInstance("SHA-256");var length=0L
                parts.forEach { part ->
                    currentCoroutineContext().ensureActive()
                    require(part.jsonPrimitive.isString);val chunkHash=part.jsonPrimitive.content;require(HASH.matches(chunkHash))
                    val encoded=smallText(local(folder,entry(ORIGINAL+"chunk:"+chunkHash)),2L*CHUNK)
                    val decoded=Base64.decode(encoded,Base64.NO_WRAP)
                    require(decoded.size in 1..CHUNK && sha(decoded)==chunkHash && Base64.encodeToString(decoded,Base64.NO_WRAP)==encoded)
                    total.update(decoded);length+=decoded.size;require(length<=expected)
                }
                require(length==expected && total.digest().hex()==hash) {"Original legacy JSON checksum mismatch."}
                originals++
            }
        }
        val staged=folder.listFiles()?.count {it.name.startsWith("archives_") && it.name.endsWith(".bin")} ?: 0
        require(bytes+File(folder,"backup.json").length()+File(folder,"archives.ndjson").length()+File(folder,"manifest.json").length()<=limits.maxExpandedBytes)
        require(staged==count && count.toLong()==number(header,"archiveCount") && bytes==number(header,"archiveBytes") &&
            originals.toLong()==number(header,"originalFileCount") && originalBytes==number(header,"originalBytes")) {"Archive manifest totals mismatch."}
        return Manifest(count,bytes,originals,originalBytes)
    }
    private suspend fun insert(sql: SupportSQLiteDatabase, id: String, file: File, expected: String) {
        val exists=sql.query("SELECT 1 FROM archives WHERE id=?",arrayOf(id)).use {it.moveToFirst()}
        if(exists) {require(HistoryPreservation.bodySha256(sql,"archives",id)==expected) {"Existing immutable archive conflicts with import."};return}
        // A temporary BLOB table avoids building a huge JVM String or firing archive triggers for each chunk.
        sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS astravar_history_restore_stage(body BLOB NOT NULL)")
        try {
            sql.execSQL("DELETE FROM astravar_history_restore_stage")
            sql.execSQL("INSERT INTO astravar_history_restore_stage(body) VALUES (X'')")
            file.inputStream().use { input ->val buffer=ByteArray(1024*1024);while(true) {
                currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                sql.execSQL("UPDATE astravar_history_restore_stage SET body=CAST(body || ? AS BLOB)",arrayOf(buffer.copyOf(n)))
            } }
            sql.execSQL("INSERT INTO archives(id,body) SELECT ?,CAST(body AS TEXT) FROM astravar_history_restore_stage",arrayOf(id))
            require(HistoryPreservation.bodySha256(sql,"archives",id)==expected)
        } finally {sql.execSQL("DROP TABLE IF EXISTS astravar_history_restore_stage")}
    }
    private fun insertSmall(sql: SupportSQLiteDatabase, id: String, text: String) {
        val expected=sha(text.toByteArray(Charsets.UTF_8))
        val old=sql.query("SELECT 1 FROM archives WHERE id=?",arrayOf(id)).use {it.moveToFirst()}
        if(old) require(HistoryPreservation.bodySha256(sql,"archives",id)==expected) {"Immutable original/receipt collision."}
        else sql.execSQL("INSERT INTO archives(id,body) VALUES (?,?)",arrayOf(id,text))
    }
    private suspend fun originalParts(file: File, hash: String, save: (String,String)->Unit): Manifest {
        val chunks=ArrayList<JsonElement>()
        val unique=HashMap<String,Int>()
        file.inputStream().use {input -> val buffer=ByteArray(CHUNK);while(true) {
            currentCoroutineContext().ensureActive();var n=0;while(n<buffer.size) {val read=input.read(buffer,n,buffer.size-n);if(read<0)break;n+=read};if(n==0)break
            val bytes=buffer.copyOf(n);val part=sha(bytes);chunks+=JsonPrimitive(part)
            val encoded=Base64.encodeToString(bytes,Base64.NO_WRAP);unique[part]=encoded.length
            require(encoded.length.toLong()<=limits.maxArchiveBytes && unique.size+1<=limits.maxArchives &&
                unique.values.sumOf {it.toLong()}<=limits.maxExpandedBytes) {"Original-file chunks exceed the explicit archive budget."}
            save(ORIGINAL+"chunk:"+part,encoded)
        } }
        val descriptor=canonical(buildJsonObject {put("format","astravar-original-json-v1");put("sha256",hash);put("bytes",file.length());put("chunks",JsonArray(chunks))})
        require(descriptor.toByteArray(Charsets.UTF_8).size<=minOf(limits.maxArchiveBytes,1024L*1024) &&
            unique.values.sumOf {it.toLong()}+descriptor.toByteArray(Charsets.UTF_8).size<=limits.maxExpandedBytes)
        save(ORIGINAL+"file:"+hash,descriptor)
        return Manifest(unique.size+1,unique.values.sumOf {it.toLong()}+descriptor.toByteArray(Charsets.UTF_8).size,1,file.length())
    }
    private fun receipt(text: String): HistoryRestoreReceipt {
        val j=strictObject(text);require(j.keys==setOf("format","operationId","fingerprint","resultingRevision") &&
            stringField(j,"format")=="astravar-history-restore-receipt-v1")
        val operation=stringField(j,"operationId");val hash=stringField(j,"fingerprint");val revision=number(j,"resultingRevision")
        require(UUID.fromString(operation).toString()==operation && HASH.matches(hash) && revision>=0)
        return HistoryRestoreReceipt(operation,hash,revision)
    }
    suspend fun apply(pending: PendingHistoryRestore, operationId: String): HistoryRestoreReceipt = withContext(Dispatchers.IO) {
        require(UUID.fromString(operationId).toString()==operationId)
        repository.awaitHistoryReady()
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.query("SELECT body FROM archives WHERE id=?",arrayOf(RECEIPT+operationId)).use { c ->if(c.moveToFirst()) {
                val existing=receipt(c.getString(0));require(existing.operationId==operationId && existing.fingerprint==pending.fingerprint) {"Restore operation ID was reused."}
                return@withTransaction existing
            } }
            check(!pending.closed && pending.directory.isDirectory) {"Restore preview was discarded."}
            require(File(pending.directory,"input.bin").length()<=limits.maxInputBytes && digest(File(pending.directory,"input.bin"))==pending.fingerprint) {"Staged source changed after preview or exceeds the input budget."}
            if(!pending.legacy) {
                require(digest(File(pending.directory,"manifest.json"))==pending.manifestFingerprint) {"Staged manifest changed after preview."}
                validate(pending.directory)
            } else originalParts(File(pending.directory,"input.bin"),pending.fingerprint) {_,_->}
            val before=state(sql)
            require(before.revision==pending.baselineRevision && before.fingerprint==pending.baselineFingerprint) {"Current history changed after preview. Choose the backup again."}
            require(db.dao().event(operationId)==null && !HistoryPreservation.hasEvent(sql,operationId)) {"Restore operation ID already belongs to an existing event."}
            if(!pending.legacy) {
                val eventPrefix=HistoryPreservation.id("events",operationId,"0".repeat(64)).substringBeforeLast(':')+":"
                eachIndex(File(pending.directory,"archives.ndjson")) { row ->
                    require(!row.id.startsWith(eventPrefix) && row.id!=RECEIPT+operationId) {"Restore operation ID conflicts with imported history."}
                }
            }
            HistoryPreservation.preserveAll(sql)
            if(pending.legacy) originalParts(File(pending.directory,"input.bin"),pending.fingerprint) {id,text->insertSmall(sql,id,text)}
            else eachIndex(File(pending.directory,"archives.ndjson")) { row ->insert(sql,row.id,local(pending.directory,row.entry),row.sha) }
            require(!HistoryPreservation.hasEvent(sql,operationId)) {"Restore operation ID conflicts with preserved history."}
            require(pending.backup.history.none {it.id==operationId}) {"Import operation conflicts with historical event ID."}
            db.dao().clearEvents()
            pending.backup.history.forEach { event ->db.dao().insert(EventRow(event.id,event.after.revision,BackupCodec.json.encodeToString(event),event.origin,event.label,event.gameSeconds,event.undone))}
            repository.import(pending.backup,operationId)
            val resulting=state(sql).revision
            val result=HistoryRestoreReceipt(operationId,pending.fingerprint,resulting)
            insertSmall(sql,RECEIPT+operationId,canonical(buildJsonObject {
                put("format","astravar-history-restore-receipt-v1");put("operationId",operationId);put("fingerprint",pending.fingerprint);put("resultingRevision",resulting)
            }))
            result
        }
    }
}
