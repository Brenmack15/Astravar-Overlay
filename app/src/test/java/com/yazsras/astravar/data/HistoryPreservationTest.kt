package com.yazsras.astravar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HistoryPreservationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun seed()=Campaign(revision=1000,onboarded=true,attuned=true,installedId="active",gameSeconds=50,
        cores=listOf(Core("legacy","Historical destroyed core",destroyed=true),Core("active","Internal nexus")),slots=mapOf(2 to 2),notes="Synthetic resources retained")
    private fun shot(i: Int): PendingShot {
        val p=ShotPlan("historical-$i",0,Ability.SHATTER,"legacy",Profile(),emptyList(),targetPoint="Synthetic old point")
        return PendingShot(p,rolled=DiceRoller.roll(p,die={1},atMillis=i.toLong()))
    }
    private fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    private fun archived(db: AstravarDb,table: String,id: String,text: String) {
        val key=HistoryPreservation.id(table,id,hash(text))
        val raw=HistoryPreservation.openBody(db.openHelper.writableDatabase,key).use {it.readBytes().toString(Charsets.UTF_8)}
        assertEquals(text,raw)
    }
    private suspend fun ready(repo: Repository)=withTimeout(30000) {repo.state.first {it!=null}!!}

    @Test(timeout=90000) fun schema3StartupPreservesAllRawRowsMalformedHistoryAndLegacyArchiveBeforeCleanup()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val state=seed().copy(pending=(1..35).map(::shot))
        val stateRaw=BackupCodec.json.encodeToString(state)
        db.dao().put(StateRow(body=stateRaw))
        val eventBodies=(1..35).associate {i->"old-event-$i" to BackupCodec.json.encodeToString(HistoryEntry("old-event-$i","Historical synthetic Shatter",50,state.copy(revision=i.toLong(),pending=emptyList()),state.copy(revision=i.toLong(),pending=emptyList())))}
        eventBodies.forEach { (id,body)->db.dao().insert(EventRow(id,id.substringAfterLast('-').toLong(),body)) }
        val rolls=(1..35).associate {i->"historical-$i" to BackupCodec.json.encodeToString(shot(i))}
        rolls.forEach {(id,body)->db.dao().putRoll(RollRow(id,id.substringAfterLast('-').toLong(),body))}
        val malformed="{not valid JSON\nHistorical Shatter bytes must survive 🦉"
        db.dao().insert(EventRow("malformed",1001,malformed))
        val legacyArchive="{\"legacy\":\"Historical Shatter archive\",\"faces\":[1,2,3]}"
        db.dao().archive(ArchiveRow("legacy-original",legacyArchive))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            val current=ready(repo)
            assertEquals(20,current.pending.size);assertEquals(20,db.dao().rollCount());assertEquals(19,db.dao().eventCount())
            assertEquals(state.cores,current.cores);assertEquals(state.slots,current.slots)
            archived(db,"campaign","1",stateRaw)
            eventBodies.forEach {(id,body)->archived(db,"events",id,body)}
            rolls.forEach {(id,body)->archived(db,"rolls",id,body)}
            archived(db,"events","malformed",malformed)
            archived(db,"archives","legacy-original",legacyArchive)
            assertEquals(legacyArchive,db.openHelper.readableDatabase.query("SELECT body FROM archives WHERE id='legacy-original'").use {it.moveToFirst();it.getString(0)})
            val before=HistoryPreservation.stats(db.openHelper.writableDatabase)
            db.withTransaction {HistoryStorage.cleanup(db.openHelper.writableDatabase)}
            assertEquals(before,HistoryPreservation.stats(db.openHelper.writableDatabase))
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }

    @Test(timeout=90000) fun schema2MigrationPreservesOriginalCampaignEventsAndArchivesWithoutChangingSchemaContract()=runBlocking {
        val name="synthetic-preserve-v2.db";context.deleteDatabase(name)
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val old=SQLiteDatabase.openOrCreateDatabase(path,null)
        old.execSQL("CREATE TABLE campaign(id INTEGER NOT NULL PRIMARY KEY,body TEXT NOT NULL)")
        old.execSQL("CREATE TABLE events(id TEXT NOT NULL PRIMARY KEY,revision INTEGER NOT NULL,body TEXT NOT NULL,origin TEXT NOT NULL DEFAULT 'main')")
        old.execSQL("CREATE TABLE archives(id TEXT NOT NULL PRIMARY KEY,body TEXT NOT NULL)")
        val state=seed().copy(pending=(1..31).map(::shot));val stateRaw=BackupCodec.json.encodeToString(state)
        old.execSQL("INSERT INTO campaign VALUES(1,?)",arrayOf(stateRaw))
        val events=(1..31).associate { i -> "event-$i" to BackupCodec.json.encodeToString(HistoryEntry("event-$i","Synthetic historical action",50,seed().copy(revision=i.toLong()),seed().copy(revision=i.toLong()))) }
        events.forEach { (id,body) -> old.execSQL("INSERT INTO events VALUES(?,?,?,'main')",arrayOf<Any>(id,id.substringAfterLast('-').toInt(),body)) }
        val original="malformed old archive retained verbatim\n🦉"
        old.execSQL("INSERT INTO archives VALUES('original',?)",arrayOf(original));old.version=2;old.close()
        val db=Room.databaseBuilder(context,AstravarDb::class.java,name).addMigrations(AstravarDb.MIGRATION_2_3).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            val current=ready(repo)
            assertEquals(20,current.pending.size);assertEquals(20,db.dao().eventCount())
            archived(db,"campaign","1",stateRaw);archived(db,"events","event-1",events.getValue("event-1"));archived(db,"archives","original",original)
            assertEquals(3,db.openHelper.readableDatabase.version)
            assertEquals(state.slots,current.slots);assertEquals(state.gameSeconds,current.gameSeconds)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close();context.deleteDatabase(name)}
    }

    @Test(timeout=90000) fun repeatedMutationsRetainExpiredShotsAndOldCommandIdsWithoutTwentyDuplicateCopiesPerAction()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            var state=repo.mutate("seed","Synthetic seed") {seed()}
            var first: PendingShot?=null
            repeat(25) {i->
                val countBefore=HistoryPreservation.stats(db.openHelper.writableDatabase).records
                state=repo.mutate("shot-$i","Synthetic action") {s->DiceRoller.fire(s,Engine.plan(s,"shot-$i",Ability.ARCANE),die={1})}
                if(i==0) first=state.pending.single()
                assertTrue("Working reinsertion must deduplicate unchanged raw rows",HistoryPreservation.stats(db.openHelper.writableDatabase).records-countBefore<=5)
            }
            assertEquals(20,state.pending.size);assertNull(db.dao().event("shot-0"))
            archived(db,"rolls","shot-0",BackupCodec.json.encodeToString(first!!))
            val before=repo.export();val count=HistoryPreservation.stats(db.openHelper.writableDatabase)
            val replay=repo.mutate("shot-0","Old retry") {error("An archived command must never execute twice")}
            assertEquals(state,replay);assertEquals(before,repo.export());assertEquals(count,HistoryPreservation.stats(db.openHelper.writableDatabase))
            db.withTransaction {HistoryStorage.cleanup(db.openHelper.writableDatabase)}
            assertEquals(count,HistoryPreservation.stats(db.openHelper.writableDatabase))
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }

    @Test(timeout=60000) fun archiveFailureAfterWorkingDeletesRollsBackTheEntireActionAndResources()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            repo.mutate("seed","Synthetic seed") {seed().copy(pending=(1..20).map(::shot))}
            val before=repo.export();val counts=HistoryPreservation.stats(db.openHelper.writableDatabase)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_archive_failure BEFORE INSERT ON archives WHEN NEW.id GLOB '${HistoryPreservation.PREFIX}rolls:*' BEGIN SELECT RAISE(ABORT,'Synthetic archive write failure'); END")
            val spell=Template("spell","Synthetic approved spell",2,2,listOf(TypedDice(Dice(2,6),DamageType.COLD)),DamageType.COLD,true)
            val failed=runCatching {repo.mutate("must-rollback","Synthetic spell") {s->DiceRoller.fire(s,Engine.plan(s,"must-rollback",Ability.SPELL,immediate=spell),die={1})}}
            assertTrue(failed.isFailure);assertEquals(before,repo.export());assertEquals(counts,HistoryPreservation.stats(db.openHelper.writableDatabase))
            assertEquals(20,db.dao().rollCount());assertNull(db.dao().event("must-rollback"))
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }

    @Test(timeout=60000) fun preservedBytesRejectDeletionUpdateAndReplaceCollisionButAllowIdenticalRetry()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            ready(repo)
            val sql=db.openHelper.writableDatabase
            val body="Malformed historical bytes\n🦉"
            val id=HistoryPreservation.preserveText(sql,"imports","synthetic-source",body)
            sql.execSQL("INSERT INTO archives(id,body) VALUES ('mutable-legacy','Unrelated legacy source')")
            HistoryPreservation.normalizePending(sql)
            val before=HistoryPreservation.stats(sql)
            assertTrue(runCatching {sql.execSQL("UPDATE OR REPLACE archives SET id=?,body='different' WHERE id='mutable-legacy'",arrayOf(id))}.isFailure)
            assertTrue(runCatching {sql.execSQL("DELETE FROM archives WHERE id=?",arrayOf(id))}.isFailure)
            assertTrue(runCatching {sql.execSQL("UPDATE archives SET body='replacement' WHERE id=?",arrayOf(id))}.isFailure)
            assertTrue(runCatching {sql.execSQL("INSERT OR IGNORE INTO archives(id,body) VALUES (?,?)",arrayOf(id,"different"))}.isFailure)
            assertTrue(runCatching {sql.execSQL("INSERT OR REPLACE INTO archives(id,body) VALUES (?,?)",arrayOf(id,"different"))}.isFailure)
            assertEquals(id,HistoryPreservation.preserveText(sql,"imports","synthetic-source",body))
            assertEquals(before,HistoryPreservation.stats(sql));archived(db,"imports","synthetic-source",body)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }

    @Test(timeout=60000) fun normalizationSqlFailureAbortsInsteadOfDeletingAValidWorkingEvent()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        try {
            val sql=db.openHelper.writableDatabase
            HistoryStorage.create(sql)
            db.dao().put(StateRow(body=BackupCodec.json.encodeToString(seed())))
            val raw=BackupCodec.json.encodeToString(HistoryEntry("valid-event","Synthetic valid action",50,seed(),seed()))
            db.dao().insert(EventRow("valid-event",1000,raw))
            val before=HistoryPreservation.stats(sql)
            sql.execSQL("CREATE TRIGGER synthetic_normalization_failure BEFORE UPDATE ON events BEGIN SELECT RAISE(ABORT,'Synthetic storage failure'); END")
            val result=runCatching {db.withTransaction {HistoryStorage.cleanup(sql)}}
            assertTrue(result.isFailure);assertEquals(raw,db.dao().event("valid-event")!!.body)
            assertEquals(1,db.dao().eventCount());assertEquals(before,HistoryPreservation.stats(sql))
        } finally {db.close()}
    }

    @Test(timeout=60000) fun canonicalIdentityCollisionAbortsStartupBeforePruningAnySource()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        try {
            val original=BackupCodec.json.encodeToString(seed())
            db.dao().put(StateRow(body=original))
            for(i in 1..25) db.dao().putRoll(RollRow("historical-$i",i.toLong(),BackupCodec.json.encodeToString(shot(i))))
            val collision=HistoryPreservation.id("campaign","1",hash(original))
            db.dao().archive(ArchiveRow(collision,"Unrelated bytes under a conflicting identity"))
            val sql=db.openHelper.writableDatabase
            HistoryStorage.create(sql)
            val result=runCatching {db.withTransaction {HistoryStorage.cleanup(sql)}}
            assertTrue(result.isFailure);assertEquals(25,db.dao().rollCount());assertEquals(original,db.dao().state()!!.body)
            assertEquals("Unrelated bytes under a conflicting identity",HistoryPreservation.openBody(sql,collision).use {it.readBytes().toString(Charsets.UTF_8)})
        } finally {db.close()}
    }

    @Test(timeout=60000) fun oversizedLegacyArchiveStreamsWithoutCursorWindowAndRawImportSurvivesWorkingProjection()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val large="Synthetic old archive 🦉\n".repeat(150000)
        db.dao().archive(ArchiveRow("oversized-original",large))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            ready(repo)
            archived(db,"archives","oversized-original",large)
            val incoming=Backup(campaign=seed().copy(pending=(1..30).map(::shot)),archives=listOf(HistoryArchive("source-history",emptyList())))
            val original=BackupCodec.json.encodeToString(incoming)
            repo.import(original,"synthetic-import")
            archived(db,"imports","synthetic-import",original)
            assertEquals(20,BackupCodec.decode(repo.export()).campaign.pending.size)
            val proposed=seed().copy(pending=(1..30).map(::shot))
            repo.mutate("bulk","Synthetic bulk record") {proposed}
            proposed.pending.forEach {archived(db,"rolls",it.plan.id,BackupCodec.json.encodeToString(it))}
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
}
