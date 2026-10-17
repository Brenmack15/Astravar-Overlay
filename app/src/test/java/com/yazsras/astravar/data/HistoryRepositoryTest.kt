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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HistoryRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun seed()=Campaign(onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Original crystal")),
        profile=Profile(baseDamageDiceCount=5,elementalSelectorDiceCount=7),notes="Campaign 🦉 survives",slots=mapOf(2 to 3),gameSeconds=600)
    private fun shot(i: Int,n: Int=4,blast: Boolean=false): PendingShot {
        val s=seed().copy(revision=i.toLong(),profile=Profile(baseDamageDiceCount=n))
        val p=Engine.plan(s,"roll-$i",if(blast) Ability.SHATTER else Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING),point="Gate")
        val r=DiceRoller.roll(p,die={if(it==20) 14 else 7},atMillis=i.toLong())
        return PendingShot(p,DiceRoller.resolution(p,r,targets=if(blast) listOf(TargetSave("Target",false)) else emptyList()),r)
    }
    @Test(timeout=60000) fun normalRollsRotateAt21And22Then100WithCurrentResultAndRestart()=runBlocking {
        context.deleteDatabase("bounded-history.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"bounded-history.db").addMigrations(AstravarDb.MIGRATION_1_2,AstravarDb.MIGRATION_2_3).build()
        var db=open();var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var repo=Repository(db,scope)
        try {
            var s=repo.mutate(label="Seed") {seed()}
            for(i in 1..100) {
                s=repo.fire(Engine.plan(s,"attack-$i",Ability.ELEMENTAL,elements=listOf(DamageType.THUNDER)),RollOptions(),"test")
                if(i in listOf(1,19,20,21,22,100)) {
                    assertEquals(minOf(i,20),db.dao().rollCount())
                    assertEquals((maxOf(1,i-19)..i).map {"attack-$it"},s.pending.map {it.plan.id})
                    assertEquals("attack-$i",s.recentRolls().first().plan.id);assertNotNull(s.pending.last().rolled)
                }
                assertTrue(db.dao().eventCount()<=HistoryRetention.MAX_HISTORY_ENTRIES)
            }
            val saved=BackupCodec.decode(repo.export());assertEquals(20,saved.campaign.pending.size)
            assertTrue(saved.history.all {it.before.pending.isEmpty() && it.after.pending.isEmpty()});assertTrue(saved.archives.isEmpty())
            assertTrue(BackupCodec.decodeCampaignSnapshot(db.dao().state()!!.body).pending.isEmpty())
            scope.coroutineContext[Job]!!.cancelAndJoin();db.close();db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);repo=Repository(db,scope)
            val reopened=withTimeout(10000) {repo.state.first {it!=null}!!}
            assertEquals(s,reopened)
            for(i in 101..110) s=repo.fire(Engine.plan(s,"attack-$i",Ability.ARCANE),RollOptions(),"test")
            assertEquals((91..110).map {"attack-$it"},s.pending.map {it.plan.id})
            assertEquals(20,db.dao().rollCount());assertNull(repo.error.value)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=60000) fun repeatedLargeShatterResultsRemainBoundedWithAllTypedFaces()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            repo.mutate(label="Seed") {seed()}
            for(i in 1..100) repo.mutate(label="Large Shatter") {s->s.copy(revision=1000,pending=s.pending+shot(i,listOf(4,5,10)[i%3],true))}
            val s=BackupCodec.decode(repo.export()).campaign
            assertEquals((81..100).map {"roll-$it"},s.pending.map {it.plan.id});assertEquals(20,db.dao().rollCount())
            s.pending.forEach {p->
                assertEquals(p.plan.blastDiceCount,p.rolled!!.damage.getValue("blast").faces.size)
                assertEquals(p.plan.blastDiceCount*7,Engine.damage(p.plan,p.result!!,p.result!!.targets.single())[DamageType.FORCE])
                assertEquals(14,Engine.damage(p.plan,p.result!!,p.result!!.targets.single())[DamageType.LIGHTNING])
            }
            val overlay=Repository(db,scope)
            assertEquals(s,withTimeout(10000) {overlay.state.first {it!=null}!!});assertNull(repo.error.value)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=90000) fun legacyThousandDuplicatedEventsAndTenThousandRollsMigrateBeforeObservation()=runBlocking {
        context.deleteDatabase("oversized-v2.db");val path=context.getDatabasePath("oversized-v2.db");path.parentFile!!.mkdirs()
        val old=SQLiteDatabase.openOrCreateDatabase(path,null)
        old.execSQL("CREATE TABLE campaign (id INTEGER NOT NULL PRIMARY KEY, body TEXT NOT NULL)")
        old.execSQL("CREATE TABLE events (id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, body TEXT NOT NULL, origin TEXT NOT NULL DEFAULT 'main')")
        old.execSQL("CREATE TABLE archives (id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)")
        val state=seed().copy(revision=10000)
        val window=(9951..10000).map {shot(it,10,true)}
        val body=BackupCodec.json.encodeToString(state).replace("\"pending\":[]", "\"pending\":["+(1..10000).joinToString(",") {BackupCodec.json.encodeToString(shot(it,10,true))}+"]")
        assertTrue(body.length>2*1024*1024) // Would not fit a normal CursorWindow as one row.
        old.execSQL("INSERT INTO campaign VALUES(1,?)",arrayOf(body))
        val duplicated=seed().copy(revision=10000,pending=window)
        val event=BackupCodec.json.encodeToString(HistoryEntry("legacy","Duplicated journal",600,duplicated,duplicated))
        old.beginTransaction()
        try {for(i in 1..1000) old.execSQL("INSERT INTO events VALUES(?,?,?,'main')",arrayOf<Any>("event-$i",i,event.replace("\"id\":\"legacy\"","\"id\":\"event-$i\"")));old.setTransactionSuccessful()} finally {old.endTransaction()}
        old.execSQL("INSERT INTO archives VALUES('old',?)",arrayOf("{\"unused\":true}"));old.version=2;old.close()
        val db=Room.databaseBuilder(context,AstravarDb::class.java,"oversized-v2.db").addMigrations(AstravarDb.MIGRATION_2_3).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            val migrated=withTimeout(60000) {repo.state.first {it!=null}!!}
            assertEquals((9981..10000).map {"roll-$it"},migrated.pending.map {it.plan.id})
            assertEquals(seed().profile,migrated.profile);assertEquals(seed().cores,migrated.cores);assertEquals(seed().notes,migrated.notes);assertEquals(seed().slots,migrated.slots)
            assertEquals(20,db.dao().rollCount());assertEquals(20,db.dao().eventCount());assertTrue(db.dao().archives().isEmpty())
            assertEquals((981..1000).reversed().map {"event-$it"},db.dao().events().map {it.id})
            assertTrue(db.dao().events().all {it.body.length<10000});assertTrue(db.dao().state()!!.body.length<10000)
            val retainedBytes=db.dao().events().sumOf {it.body.length}+db.dao().rolls().sumOf {it.body.length}+db.dao().state()!!.body.length
            assertTrue(retainedBytes<512*1024)
            println("Legacy fixture: 10000 rolls, 1000 duplicated events; campaign JSON ${body.length} chars, event JSON ${event.length} chars each. Retained total: $retainedBytes chars.")
            val indexPlan=db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT id FROM events ORDER BY revision DESC,id DESC LIMIT ${HistoryRetention.MAX_HISTORY_ENTRIES}").use {c->buildString {while(c.moveToNext()) append(c.getString(3))}}
            assertTrue(indexPlan.contains("index_events_revision_id"))
            val after=repo.fire(Engine.plan(migrated,"new-after-migration",Ability.ARCANE),RollOptions(),"test")
            assertEquals("new-after-migration",after.recentRolls().first().plan.id);assertEquals(20,db.dao().rollCount());assertNull(repo.error.value)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close();context.deleteDatabase("oversized-v2.db")}
    }
    @Test(timeout=30000) fun storageTriggersPruneBatchAndTiesDeterministicallyAndConcurrentMutationsStayBounded()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            repo.mutate(label="Seed") {seed()}
            db.withTransaction {for(i in 1..23) {val id="tie-${i.toString().padStart(2,'0')}";val p=shot(i);db.dao().putRoll(RollRow(id,1,BackupCodec.json.encodeToString(p.copy(plan=p.plan.copy(id=id)))))}}
            assertEquals(20,db.dao().rollCount());assertEquals("tie-23",db.dao().rolls().first().id);assertEquals("tie-04",db.dao().rolls().last().id)
            db.dao().clearRolls()
            coroutineScope {(1..50).map {i->async {repo.mutate(label="Concurrent $i") {s->s.copy(revision=100,pending=s.pending+shot(i))}}}.awaitAll()}
            assertEquals(20,db.dao().rollCount());assertEquals(20,db.dao().eventCount())
            val saved=BackupCodec.decode(repo.export()).campaign;assertEquals(20,saved.pending.map {it.plan.id}.distinct().size)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=30000) fun malformedLegacyRollAndEventDoNotBlockStartupOrEraseResources()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val s=seed().copy(revision=100,pending=(1..25).map {shot(it)})
        val raw=BackupCodec.json.encodeToString(s).replace("\"pending\":[","\"pending\":[{\"broken\":true},")
        db.dao().put(StateRow(body=raw));db.dao().insert(EventRow("broken",1,"not JSON"))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            val loaded=withTimeout(10000) {repo.state.first {it!=null}!!}
            assertEquals((6..25).map {"roll-$it"},loaded.pending.map {it.plan.id});assertEquals(s.notes,loaded.notes)
            assertEquals(0,db.dao().eventCount());assertEquals(s.profile,loaded.profile);assertNull(repo.error.value)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=30000) fun oversizedLegacyImportAndModernExportStayBoundedAndUndoKeepsResources()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            repo.mutate(label="Seed") {seed()}
            val incoming=seed().copy(revision=1000,notes="Imported",pending=(1..100).map {shot(it)})
            repo.import(BackupCodec.json.encodeToString(Backup(campaign=incoming)),"import")
            val saved=BackupCodec.decode(repo.export());assertEquals((81..100).map {"roll-$it"},saved.campaign.pending.map {it.plan.id})
            assertEquals(20,db.dao().rollCount());assertEquals(incoming.profile,saved.campaign.profile)
            repo.undo();assertEquals(seed().notes,BackupCodec.decode(repo.export()).campaign.notes)
            assertTrue(db.dao().rollCount()<=20)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
}
