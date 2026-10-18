package com.yazsras.astravar.data

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HistoryBackupTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private class Fixture(context: Context) {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val repo=Repository(db,scope)
        val service=HistoryBackup(context,repo)
        suspend fun close() {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    private suspend fun fixture(block: suspend (Fixture)->Unit) {
        val f=Fixture(context);try {f.repo.awaitHistoryReady();block(f)} finally {f.close()}
    }
    private suspend fun seed(f: Fixture, notes: String="Synthetic source") {
        f.repo.mutate(label="Synthetic fixture") {Campaign(onboarded=true,notes=notes,slots=mapOf(2 to 3))}
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun canonical(j: JsonElement): String=when(j) {
        is JsonObject->j.toSortedMap().entries.joinToString(",","{","}") {JsonPrimitive(it.key).toString()+":"+canonical(it.value)}
        is JsonArray->j.joinToString(",","[","]") {canonical(it)}
        else->j.toString()
    }
    private fun archives(f: Fixture): Map<String,ByteArray> = f.db.openHelper.writableDatabase.query("SELECT id,CAST(body AS BLOB) FROM archives ORDER BY id").use {c->
        buildMap {while(c.moveToNext()) put(c.getString(0),c.getBlob(1))}
    }
    private suspend fun exported(f: Fixture): ByteArray = ByteArrayOutputStream().also {f.service.export(it)}.toByteArray()
    private fun members(bytes: ByteArray): LinkedHashMap<String,ByteArray> = ZipInputStream(bytes.inputStream()).use {z->
        linkedMapOf<String,ByteArray>().also {m->while(true) {val e=z.nextEntry?:break;m[e.name]=z.readBytes();z.closeEntry()}}
    }
    private fun zipped(m: Map<String,ByteArray>): ByteArray=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->
        m.forEach {(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}
    }}.toByteArray()
    private fun addRaw(m: MutableMap<String,ByteArray>, id: String, bytes: ByteArray) {
        val name="archives/"+hash(id.toByteArray(Charsets.UTF_8))+".bin";m[name]=bytes
        val row=buildJsonObject {put("id",id);put("entry",name);put("sha256",hash(bytes));put("bytes",bytes.size)}
        val index=m.getValue("archives.ndjson").toString(Charsets.UTF_8)+canonical(row)+"\n"
        m["archives.ndjson"]=index.toByteArray(Charsets.UTF_8)
        val rows=index.lineSequence().filter {it.isNotEmpty()}.map {Json.parseToJsonElement(it).jsonObject}.toList()
        val old=Json.parseToJsonElement(m.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        val header=JsonObject(old+mapOf("indexSha256" to JsonPrimitive(hash(m.getValue("archives.ndjson"))),
            "archiveCount" to JsonPrimitive(rows.size),"archiveBytes" to JsonPrimitive(rows.sumOf {it.getValue("bytes").jsonPrimitive.long})))
        m["manifest.json"]=canonical(header).toByteArray(Charsets.UTF_8)
    }
    private suspend fun fails(block: suspend ()->Unit) {
        val error=runCatching {block()}.exceptionOrNull()
        assertNotNull("Must reject without changing current records",error)
        assertFalse("Malformed input must not overflow the stack",error is StackOverflowError)
    }
    private fun assertArchives(expected: Map<String,ByteArray>, actual: Map<String,ByteArray>) {
        expected.forEach {(id,bytes)->assertArrayEquals("Exact opaque bytes for $id",bytes,actual[id])}
    }

    @Test fun completeZipRoundTripRestoresAllOpaqueHashesBeyondRecentWindow()=runBlocking {
        fixture {source->fixture {target->
            seed(source)
            repeat(25) {n->source.repo.mutate(label="Synthetic event $n") {it.copy(notes="Synthetic source $n")}}
            source.db.openHelper.writableDatabase.execSQL("INSERT INTO archives(id,body) VALUES (?,CAST(? AS TEXT))",arrayOf("opaque-invalid",byteArrayOf(0,0xff.toByte(),0x7b)))
            source.repo.mutate(label="Normalize synthetic opaque history") {it}
            val before=archives(source)
            val zip=exported(source);val pending=target.service.prepare(zip.inputStream())
            try {
                assertFalse(pending.preview.legacyJson)
                assertTrue(pending.preview.archiveCount>20)
                assertEquals(20,pending.backup.history.size)
                target.service.apply(pending,UUID.randomUUID().toString())
                assertArchives(before,archives(target))
                assertEquals("Synthetic source 24",BackupCodec.decode(target.repo.export()).campaign.notes)
                assertTrue(target.db.dao().eventCount()<=20)
            } finally {pending.close()}
        }}
    }
    @Test fun legacyJsonOriginalBytesSurviveProjectionReexportAndRestoreWithoutNesting()=runBlocking {
        fixture {source->fixture {target->
            seed(source)
            val bounded=source.repo.export()
            val original=bounded.replace("\"archives\":[]","\"archives\":[{\"oldMalformed\":\"retired SHATTER 🦉\"}]").toByteArray(Charsets.UTF_8)
            assertFalse(original.contentEquals(bounded.toByteArray()))
            val pending=source.service.prepare(original.inputStream())
            try {source.service.apply(pending,UUID.randomUUID().toString())} finally {pending.close()}
            val descriptorId=HistoryPreservation.ORIGINAL_PREFIX+"file:"+hash(original)
            val all=archives(source);val descriptor=Json.parseToJsonElement(all.getValue(descriptorId).toString(Charsets.UTF_8)).jsonObject
            val reconstructed=ByteArrayOutputStream()
            descriptor.getValue("chunks").jsonArray.forEach {part->
                reconstructed.write(Base64.decode(all.getValue(HistoryPreservation.ORIGINAL_PREFIX+"chunk:"+part.jsonPrimitive.content).toString(Charsets.UTF_8),Base64.NO_WRAP))
            }
            assertArrayEquals(original,reconstructed.toByteArray())
            val second=source.service.prepare(original.inputStream())
            try {source.service.apply(second,UUID.randomUUID().toString())} finally {second.close()}
            assertEquals(all.keys.count {it.startsWith(HistoryPreservation.ORIGINAL_PREFIX)},archives(source).keys.count {it.startsWith(HistoryPreservation.ORIGINAL_PREFIX)})
            val zip=exported(source);val restored=target.service.prepare(zip.inputStream())
            try {
                assertEquals(1,restored.preview.originalFileCount)
                assertEquals(original.size.toLong(),restored.preview.originalBytes)
                target.service.apply(restored,UUID.randomUUID().toString())
                assertArchives(archives(source),archives(target))
                assertEquals(1,archives(target).keys.count {it.startsWith(HistoryPreservation.ORIGINAL_PREFIX+"file:")})
            } finally {restored.close()}
        }}
    }
    @Test fun streamingBoundariesPreserveMultiBlockOpaqueBodiesAndDeduplicateOriginalChunks()=runBlocking {
        fixture {source->fixture {target->
            seed(source,"Synthetic streaming boundary")
            val opaque=ByteArray(2*1024*1024+17) {((it*31)%256).toByte()}
            source.db.openHelper.writableDatabase.execSQL("INSERT INTO archives(id,body) VALUES (?,CAST(? AS TEXT))",arrayOf("large-opaque",opaque))
            source.repo.mutate(label="Normalize synthetic multi-block history") {it}
            val original=source.repo.export().replace("\"archives\":[]",
                "\"archives\":[{\"unprojectedPadding\":\""+"A".repeat(4*65_536)+"🦉\"}]").toByteArray(Charsets.UTF_8)
            fun originalCount(f: Fixture)=f.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM archives WHERE id GLOB ?",
                arrayOf(HistoryPreservation.ORIGINAL_PREFIX+"*")).use {it.moveToFirst();it.getInt(0)}
            repeat(2) {
                val pending=source.service.prepare(original.inputStream())
                try {source.service.apply(pending,UUID.randomUUID().toString())} finally {pending.close()}
            }
            val descriptorId=HistoryPreservation.ORIGINAL_PREFIX+"file:"+hash(original)
            val descriptor=HistoryPreservation.openBody(source.db.openHelper.writableDatabase,descriptorId).use {
                Json.parseToJsonElement(it.reader(Charsets.UTF_8).readText()).jsonObject
            }
            val parts=descriptor.getValue("chunks").jsonArray.map {it.jsonPrimitive.content}
            assertTrue("Original source must cross the 64KiB chunk boundary",parts.size>4)
            assertTrue("Repeated identical chunks must share one content-addressed row",parts.toSet().size<parts.size)
            assertEquals(parts.toSet().size+1,originalCount(source))
            val zip=exported(source)
            repeat(2) {
                val pending=target.service.prepare(zip.inputStream())
                try {target.service.apply(pending,UUID.randomUUID().toString())} finally {pending.close()}
                // Stream the >2MiB body instead of placing it in a SQLite cursor window.
                val restored=HistoryPreservation.openBody(target.db.openHelper.writableDatabase,"large-opaque").use {it.readBytes()}
                assertArrayEquals(opaque,restored);assertEquals(hash(opaque),hash(restored))
                assertEquals(originalCount(source),originalCount(target))
                val rebuilt=ByteArrayOutputStream()
                parts.forEach {part->HistoryPreservation.openBody(target.db.openHelper.writableDatabase,
                    HistoryPreservation.ORIGINAL_PREFIX+"chunk:"+part).use {input->
                    rebuilt.write(Base64.decode(input.reader(Charsets.UTF_8).readText(),Base64.NO_WRAP))
                }}
                assertArrayEquals(original,rebuilt.toByteArray());assertEquals(hash(original),hash(rebuilt.toByteArray()))
            }
        }}
    }
    @Test fun prepareAndDiscardDoNotWriteAndRemovePrivateStaging()=runBlocking {
        fixture {f->
            seed(f);val zip=exported(f);val before=archives(f);val state=f.db.dao().state()
            val pending=f.service.prepare(zip.inputStream());val folder=pending.directory
            assertTrue(folder.isDirectory);assertEquals(state,f.db.dao().state());assertArchives(before,archives(f));assertEquals(before.size,archives(f).size)
            pending.close();assertFalse(folder.exists())
            fails {f.service.apply(pending,UUID.randomUUID().toString())}
            assertEquals(state,f.db.dao().state());assertEquals(before.size,archives(f).size)
        }
    }
    @Test fun staleRevisionAndArchiveOnlyChangesBothInvalidateApproval()=runBlocking {
        fixture {f->
            seed(f);val zip=exported(f)
            val pending=f.service.prepare(zip.inputStream())
            try {f.repo.mutate(label="Later synthetic correction") {it.copy(notes="Later value")};val before=archives(f)
                fails {f.service.apply(pending,UUID.randomUUID().toString())};assertEquals("Later value",BackupCodec.decode(f.repo.export()).campaign.notes);assertEquals(before.size,archives(f).size)
            } finally {pending.close()}
            val second=f.service.prepare(zip.inputStream())
            try {f.db.dao().archive(ArchiveRow("new-opaque","synthetic"));val before=archives(f)
                fails {f.service.apply(second,UUID.randomUUID().toString())};assertEquals(before.size,archives(f).size)
            } finally {second.close()}
        }
    }
    @Test fun originalReceiptRetriesAfterCacheDiscardAndOperationCollisionRejects()=runBlocking {
        fixture {f->
            seed(f);val zip=exported(f);val pending=f.service.prepare(zip.inputStream());val id=UUID.randomUUID().toString()
            val result=f.service.apply(pending,id);val after=archives(f);pending.close()
            assertEquals(result,f.service.apply(pending,id));assertEquals(after.size,archives(f).size)
            val changed=f.repo.export().replace("Synthetic source","Different synthetic source").toByteArray()
            val second=f.service.prepare(changed.inputStream())
            try {fails {f.service.apply(second,id)};assertEquals(after.size,archives(f).size)} finally {second.close()}
        }
    }
    @Test fun currentAndPreservedEventIdsCannotBecomeFalseRestoreReceipts()=runBlocking {
        fixture {f->
            seed(f);val id=UUID.randomUUID().toString();f.repo.mutate(id,"Synthetic command") {it}
            val zip=exported(f)
            for(pruned in listOf(false,true)) {
                if(pruned) repeat(21) {f.repo.mutate(label="Rotate recent window") {it}}
                val pending=f.service.prepare(zip.inputStream());val before=archives(f);val state=f.db.dao().state()
                try {fails {f.service.apply(pending,id)};assertEquals(state,f.db.dao().state());assertEquals(before.size,archives(f).size)} finally {pending.close()}
            }
        }
    }
    @Test fun incomingArchivedAndPendingEventIdsCannotSuppressTheWorkingRestore()=runBlocking {
        fixture {source->fixture {target->
            seed(source);seed(target,"Synthetic target sentinel")
            val zip=exported(source)
            for(pendingPrefix in listOf(false,true)) {
                val operation=UUID.randomUUID().toString();val bytes="opaque historical event".toByteArray()
                val canonicalId=HistoryPreservation.id("events",operation,hash(bytes))
                val id=if(pendingPrefix) canonicalId.replaceFirst(HistoryPreservation.PREFIX,HistoryPreservation.PENDING_PREFIX) else canonicalId
                val m=members(zip);addRaw(m,id,bytes);val before=archives(target);val current=target.db.dao().state()
                if(pendingPrefix) fails {target.service.prepare(zipped(m).inputStream())}
                else {
                    val preview=target.service.prepare(zipped(m).inputStream())
                    try {fails {target.service.apply(preview,operation)}} finally {preview.close()}
                }
                assertEquals(current,target.db.dao().state());assertEquals(before.size,archives(target).size)
                assertFalse(archives(target).containsKey(HistoryPreservation.RESTORE_RECEIPT_PREFIX+operation))
            }
        }}
    }
    @Test fun corruptedMissingDuplicateAndUnsafeZipMembersFailBeforeDatabaseWrites()=runBlocking {
        fixture {f->
            seed(f);val zip=exported(f);val before=archives(f);val state=f.db.dao().state()
            val corrupt=members(zip).apply {val key=keys.first {it.startsWith("archives/")};this[key]="corrupt".toByteArray()}
            val missing=members(zip).apply {remove(keys.first {it.startsWith("archives/")})}
            val unsafe=members(zip).apply {this["../escape-${UUID.randomUUID()}"]="no".toByteArray()}
            val duplicate=members(zip).apply {
                val line=getValue("archives.ndjson").toString(Charsets.UTF_8).lineSequence().first()
                val row=Json.parseToJsonElement(line).jsonObject
                addRaw(this,row.getValue("id").jsonPrimitive.content,getValue(row.getValue("entry").jsonPrimitive.content))
            }
            for(bad in listOf(corrupt,missing,unsafe,duplicate)) {
                fails {f.service.prepare(zipped(bad).inputStream())};assertEquals(state,f.db.dao().state());assertEquals(before.size,archives(f).size)
            }
        }
    }
    @Test fun coherentStagedManifestRewriteCannotChangeTheApprovedSource()=runBlocking {
        fixture {f->
            seed(f);val pending=f.service.prepare(exported(f).inputStream());val before=archives(f)
            try {
                val working=File(pending.directory,"backup.json");working.writeText(working.readText().replace("Synthetic source","Forged staged source"))
                val manifest=File(pending.directory,"manifest.json");val old=Json.parseToJsonElement(manifest.readText()).jsonObject
                manifest.writeText(canonical(JsonObject(old+("workingSha256" to JsonPrimitive(hash(working.readBytes()))))))
                fails {f.service.apply(pending,UUID.randomUUID().toString())};assertEquals(before.size,archives(f).size)
                assertEquals("Synthetic source",BackupCodec.decode(f.repo.export()).campaign.notes)
            } finally {pending.close()}
        }
    }
    @Test fun malformedProtectedIdentitiesAndDeepManifestsFailClosed()=runBlocking {
        fixture {f->
            seed(f);val zip=exported(f);val before=archives(f)
            val body="opaque".toByteArray();val operation=UUID.randomUUID().toString()
            val badReceipt=canonical(buildJsonObject {put("format","astravar-history-restore-receipt-v1");put("operationId",UUID.randomUUID().toString());put("fingerprint","0".repeat(64));put("resultingRevision",1)})
            val cases=listOf(
                HistoryPreservation.PREFIX+"unknown:61:"+hash(body) to body,
                HistoryPreservation.PREFIX+"events:0:"+hash(body) to body,
                HistoryPreservation.ORIGINAL_PREFIX+"chunk:"+"0".repeat(64) to Base64.encodeToString(body,Base64.NO_WRAP).toByteArray(),
                HistoryPreservation.RESTORE_RECEIPT_PREFIX+operation to badReceipt.toByteArray())
            for((id,bytes) in cases) {val m=members(zip);addRaw(m,id,bytes);fails {f.service.prepare(zipped(m).inputStream())}}
            val deep=members(zip).apply {this["manifest.json"]=("{\"x\":"+"[".repeat(4000)+"0"+"]".repeat(4000)+"}").toByteArray()}
            fails {f.service.prepare(zipped(deep).inputStream())};assertEquals(before.size,archives(f).size)
        }
    }
    @Test fun expandedAndLegacyChunkBudgetsRejectWithoutWriting()=runBlocking {
        fixture {f->
            seed(f);val zip=exported(f);val before=archives(f)
            val expanded=HistoryBackup(context,f.repo,HistoryBackupLimits(maxExpandedBytes=128))
            fails {expanded.prepare(zip.inputStream())}
            val legacy=HistoryBackup(context,f.repo,HistoryBackupLimits(maxArchives=1))
            fails {legacy.prepare(f.repo.export().byteInputStream())}
            val input=HistoryBackup(context,f.repo,HistoryBackupLimits(maxInputBytes=16))
            fails {input.prepare(zip.inputStream())};assertEquals(before.size,archives(f).size)
            fails {input.export(ByteArrayOutputStream())};assertEquals(before.size,archives(f).size)
        }
    }
    @Test fun cancellationClosesInputAndCleansUnpublishedPrivateCache()=runBlocking {
        fixture {f->
            seed(f);val bytes=f.repo.export().toByteArray();val folders=context.cacheDir.listFiles()!!.filter {it.name.startsWith("astravar-history-")}.map {it.name}.toSet()
            val job=Job();var closed=false
            val stream=object: ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray,off: Int,len: Int): Int=super.read(b,off,len).also {if(it>0) job.cancel()}
                override fun close() {closed=true;super.close()}
            }
            try {withContext(job) {f.service.prepare(stream)};fail("Cancelled preview must not publish a handle")}
            catch(_: CancellationException) {}
            assertTrue(closed)
            assertEquals(folders,context.cacheDir.listFiles()!!.filter {it.name.startsWith("astravar-history-")}.map {it.name}.toSet())
        }
    }
    @Test fun oversizedLegacyIdentityFailsExportExplicitlyWithoutDiscardingAnyRecord()=runBlocking {
        fixture {f->
            seed(f);f.db.dao().archive(ArchiveRow("x".repeat(2100),"Synthetic oversized identity"));val before=archives(f)
            fails {exported(f)};assertArchives(before,archives(f));assertEquals(before.size,archives(f).size)
        }
    }
}
