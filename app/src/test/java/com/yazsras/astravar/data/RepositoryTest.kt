package com.yazsras.astravar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val template=Template("cold","TEST energy",2,2,listOf(TypedDice(Dice(3,6),DamageType.COLD)),DamageType.COLD,true,"Test fixture only")
    private fun seed()=Campaign(onboarded=true,demo=true,attuned=true,installedId="core",cores=listOf(Core("core","Test core")),slots=mapOf(2 to 2))
    private fun withoutBaseCount(element: JsonElement): JsonElement = when(element) {
        is JsonObject -> JsonObject(element.filterKeys {it!="baseDamageDiceCount"}.mapValues {withoutBaseCount(it.value)})
        is JsonArray -> JsonArray(element.map(::withoutBaseCount))
        else -> element
    }
    @Test fun concurrentCommitsConsumeExactlyOnce()= runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val r=Repository(db,scope)
        val initial=r.mutate(label="Seed fixture") {Engine.charge(seed(),"core",template,"payload",SourceKind.SLOT,true)}
        val p=Engine.plan(initial,"same-command",Ability.SPELL,listOf("payload"))
        coroutineScope { (1..12).map {async(Dispatchers.Default) {r.commit(p,"test")}}.awaitAll() }
        val b=BackupCodec.decode(r.export());assertEquals(1,b.campaign.slots[2]);assertEquals(1,b.campaign.pending.size);assertEquals(1,b.history.count {it.id==p.id})
        r.undo();val undone=BackupCodec.decode(r.export());assertEquals(1,undone.campaign.core("core").payloads.size);assertTrue(undone.campaign.pending.isEmpty())
        r.commit(p,"test");assertEquals(1,BackupCodec.decode(r.export()).campaign.core("core").payloads.size)
        scope.cancel();db.close()
    }
    @Test fun pendingShotSurvivesDatabaseReopen()= runBlocking {
        context.deleteDatabase("reopen-test.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"reopen-test.db").addMigrations(AstravarDb.MIGRATION_1_2).build()
        var db=open();var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var repo=Repository(db,scope)
        val state=repo.mutate(label="Seed") {seed()};repo.commit(Engine.plan(state,"pending",Ability.DISCHARGE,point="Test point"),"test")
        scope.cancel();db.close()
        db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);repo=Repository(db,scope)
        val restored=BackupCodec.decode(repo.export()).campaign
        assertNull(restored.pending.single().result);assertFalse(restored.core("core").usable(restored.gameSeconds));assertEquals(0L,restored.gameSeconds)
        scope.cancel();db.close()
    }
    @Test fun importIsAtomicIdempotentAndPreservesHistory()= runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        repo.mutate(label="Seed") {seed()};val backup=repo.export()
        repo.mutate(label="Change") {it.copy(attuned=false)}
        repo.import(backup,"import-once");repo.import(backup,"import-once")
        val b=BackupCodec.decode(repo.export());assertTrue(b.campaign.attuned);assertEquals(1,b.history.count {it.id=="import-once"});assertEquals(1,b.archives.size)
        try {repo.import("{}","invalid");fail("Malformed import accepted")} catch(_: Exception) {}
        assertTrue(BackupCodec.decode(repo.export()).campaign.attuned)
        repo.undo();assertFalse(BackupCodec.decode(repo.export()).campaign.attuned)
        scope.cancel();db.close()
    }
    @Test fun migrationPreservesVersionOneRows()=runBlocking {
        context.deleteDatabase("migration-test.db")
        val path=context.getDatabasePath("migration-test.db");path.parentFile!!.mkdirs()
        val old=SQLiteDatabase.openOrCreateDatabase(path,null)
        old.execSQL("CREATE TABLE campaign (id INTEGER NOT NULL PRIMARY KEY, body TEXT NOT NULL)")
        old.execSQL("CREATE TABLE events (id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, body TEXT NOT NULL)")
        old.execSQL("INSERT INTO campaign (id,body) VALUES (1,?)",arrayOf(BackupCodec.json.encodeToString(Campaign.serializer(),seed())))
        old.version=1;old.close()
        val db=Room.databaseBuilder(context,AstravarDb::class.java,"migration-test.db").addMigrations(AstravarDb.MIGRATION_1_2).build()
        assertNotNull(db.dao().state());assertTrue(db.dao().archives().isEmpty());db.close()
    }
    @Test fun repeatedUndoStepsBackInsteadOfRedoing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        repo.mutate(label="Seed") {seed()};repo.mutate(label="Advance") {Engine.advance(it,600)}
        repo.undo();assertEquals(0L,BackupCodec.decode(repo.export()).campaign.gameSeconds)
        repo.undo();assertFalse(BackupCodec.decode(repo.export()).campaign.onboarded)
        assertEquals(2,BackupCodec.decode(repo.export()).history.count {it.undone})
        scope.cancel();db.close()
    }
    @Test fun bothSurfacesReadTheSameDurableSelections()=runBlocking {
        val main=Preferences(context);val overlay=Preferences(context)
        main.elements(listOf(DamageType.FIRE,DamageType.COLD));main.mode(Ability.SPELL);main.payloads(setOf("chosen"))
        val loaded=overlay.flow.first()
        assertEquals(listOf(DamageType.FIRE,DamageType.COLD),loaded.elements);assertEquals(Ability.SPELL,loaded.mode);assertEquals(setOf("chosen"),loaded.selectedPayloads)
    }
    @Test fun automaticFireIsAtomicIdempotentAndVisibleToBothSurfaces()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val main=Repository(db,scope);val overlay=Repository(db,scope)
        val initial=main.mutate(label="Seed") {Engine.charge(seed(),"core",template,"payload",SourceKind.SLOT,true)}
        val preview=Engine.plan(initial,"automatic-once",Ability.SPELL,listOf("payload"))
        assertEquals(1,BackupCodec.decode(main.export()).campaign.core("core").payloads.size)
        coroutineScope {(1..8).map {async {overlay.fire(preview,RollOptions(advantage=true),"overlay")}}.awaitAll()}
        val fromMain=BackupCodec.decode(main.export());val fromOverlay=BackupCodec.decode(overlay.export())
        assertEquals(fromMain.campaign,fromOverlay.campaign)
        assertTrue(fromMain.campaign.core("core").payloads.isEmpty());assertEquals(1,fromMain.campaign.slots[2])
        assertEquals(1,fromMain.history.count {it.id=="automatic-once"})
        val shot=fromMain.campaign.pending.single();assertNotNull(shot.result);assertEquals(2,shot.rolled!!.attackFaces.size)
        assertEquals(shot.rolled,fromMain.history.last().after.pending.single().rolled)
        assertEquals("overlay",fromMain.history.last().origin)
        scope.cancel();db.close()
    }
    @Test fun automaticDiceSurviveProcessStyleDatabaseReopenExactly()=runBlocking {
        context.deleteDatabase("automatic-reopen.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"automatic-reopen.db").addMigrations(AstravarDb.MIGRATION_1_2).build()
        var db=open();var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var repo=Repository(db,scope)
        val initial=repo.mutate(label="Seed") {seed()}
        val plan=Engine.plan(initial,"persisted-roll",Ability.ARCANE)
        repo.fire(plan,RollOptions(disadvantage=true),"overlay")
        val before=BackupCodec.decode(repo.export())
        scope.cancel();db.close();db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);repo=Repository(db,scope)
        assertEquals(before,BackupCodec.decode(repo.export()))
        repo.fire(plan,RollOptions(disadvantage=true),"main")
        assertEquals(before,BackupCodec.decode(repo.export()))
        scope.cancel();db.close()
    }
    @Test fun baseDamageUpgradeSurvivesRestartAndIsSharedByMainAndOverlayRepositories()=runBlocking {
        context.deleteDatabase("base-damage-reopen.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"base-damage-reopen.db").addMigrations(AstravarDb.MIGRATION_1_2).build()
        var db=open();var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var main=Repository(db,scope)
        val initial=main.mutate(label="Seed base damage profile") {seed().copy(notes="preserve this note")}
        assertEquals(2,initial.profile.baseDamageDiceCount)
        val n3=main.mutate(label="Select 3d10") {it.copy(profile=it.profile.copy(version=it.profile.version+1,baseDamageDiceCount=3))}
        val overlay=Repository(db,scope)
        assertEquals(3,overlay.state.first {it?.profile?.baseDamageDiceCount==3}!!.profile.baseDamageDiceCount)
        scope.cancel();db.close()

        db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);main=Repository(db,scope)
        var restored=main.state.first {it?.profile?.baseDamageDiceCount==3}!!
        assertEquals("preserve this note",restored.notes);assertEquals(3,restored.profile.baseDamageDiceCount)
        val n4=main.mutate(label="Select 4d10") {it.copy(profile=it.profile.copy(version=it.profile.version+1,baseDamageDiceCount=4))}
        assertEquals(4,n4.profile.baseDamageDiceCount)
        scope.cancel();db.close()

        db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);main=Repository(db,scope)
        restored=main.state.first {it?.profile?.baseDamageDiceCount==4}!!
        assertEquals(4,restored.profile.baseDamageDiceCount)
        val current=BackupCodec.decode(main.export()).campaign
        val plan=Engine.plan(current,"new-4d10-shot",Ability.ARCANE)
        assertEquals(Dice(4,10,5),Engine.components(plan).single().damage.dice)
        scope.cancel();db.close()
    }
    @Test fun oldStoredCampaignWithoutBaseCountDefaultsToTwoAndKeepsCampaignState()=runBlocking {
        val old=seed().copy(profile=Profile(baseDamageDiceCount=1),notes="existing note",gameSeconds=180,attuned=true)
        val oldJson=withoutBaseCount(BackupCodec.json.parseToJsonElement(BackupCodec.json.encodeToString(Campaign.serializer(),old))).toString()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        db.dao().put(StateRow(body=oldJson))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        val migrated=repo.state.first {it?.profile?.baseDamageDiceCount==2}!!
        assertEquals("existing note",migrated.notes);assertEquals(180L,migrated.gameSeconds)
        assertEquals(old.cores,migrated.cores);assertTrue(migrated.attuned);assertTrue(migrated.demo)
        scope.cancel();db.close()
    }
    @Test fun targetAndFeedbackPreferencesAreSharedAndDurable()=runBlocking {
        val main=Preferences(context);main.targeting(RollOptions(advantage=true,armorClass=18));main.aim(90,"Archway",true,"cold",true);main.haptics(false);main.manualOverrides(true)
        val overlay=Preferences(context).flow.first()
        assertTrue(overlay.advantage);assertEquals(18,overlay.armorClass);assertEquals(90,overlay.distance);assertEquals("Archway",overlay.targetPoint);assertTrue(overlay.directImpact);assertEquals("cold",overlay.immediateId);assertTrue(overlay.manualSlot);assertFalse(overlay.haptics);assertTrue(overlay.manualOverrides)
    }

    @Test fun sevenAndTenSurviveDatabaseRestartDriveBothSurfacesAndRestoreFromBackup()=runBlocking {
        context.deleteDatabase("slider-reopen.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"slider-reopen.db").addMigrations(AstravarDb.MIGRATION_1_2).build()
        for(count in listOf(7,10)) {
            var db=open();var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            var main=Repository(db,scope)
            main.mutate(label="Select ${count}d10") {seed().copy(profile=Profile(baseDamageDiceCount=count),notes="retained campaign")}
            scope.cancel();db.close()

            db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);main=Repository(db,scope)
            try {
                val overlay=Repository(db,scope)
                val shared=withTimeout(10000) {overlay.state.first {it?.profile?.baseDamageDiceCount==count}!!}
                val appState=withTimeout(10000) {main.state.first {it?.profile?.baseDamageDiceCount==count}!!}
                assertEquals(appState,shared)
                assertEquals("retained campaign",shared.notes)
                assertEquals("${count}d10+5",shared.profile.base.toString())
                assertEquals(if(count==7) 20 else 26,shared.profile.dischargeDice)
                assertEquals(if(count==7) 60 else 78,shared.profile.shatterDice)
                val plan=Engine.plan(shared,"slider-shot-$count",Ability.ARCANE)
                overlay.fire(plan,RollOptions(),"overlay")
                val saved=BackupCodec.decode(main.export())
                val shot=saved.campaign.pending.single()
                assertEquals(count*(if(shot.rolled!!.critical) 2 else 1),shot.rolled!!.damage.getValue("direct").faces.size)
                assertEquals(Dice(count,10,5),Engine.components(plan).single().damage.dice)
                val backup=main.export()
                main.mutate(label="Temporary profile change") {it.copy(profile=it.profile.copy(baseDamageDiceCount=2))}
                main.import(backup,"restore-$count")
                val restored=BackupCodec.decode(main.export())
                assertEquals(count,restored.campaign.profile.baseDamageDiceCount)
                assertEquals(shot,restored.campaign.pending.single())
            } finally {scope.cancel();db.close()}
        }
    }

    @Test fun migratedArchiveCanBeExportedAndReimportedWithoutFalseConflict()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repo=Repository(db,scope)
        try {
            val before=seed()
            val plan=Engine.plan(before,"old-emergency",Ability.DISCHARGE,point="Old door").copy(blastDiceCount=8)
            val after=before.copy(pending=listOf(PendingShot(plan,rolled=DiceRoller.roll(plan,die={4}))))
            val archive=HistoryArchive("same-archive",listOf(HistoryEntry("old-event","Old emergency",0,before,after)))
            val oldBody=BackupCodec.json.encodeToString(HistoryArchive.serializer(),archive).replace(",\"blastDiceCount\":8","")
            db.dao().archive(ArchiveRow(archive.id,oldBody))
            repo.mutate(label="Existing campaign") {before}
            val exported=repo.export()
            repo.import(exported,"reimport-migrated-archive")
            val restored=BackupCodec.decode(repo.export()).archives.first {it.id==archive.id}
            assertEquals(archive,restored)
            assertEquals(oldBody,db.dao().archives().first {it.id==archive.id}.body)
        } finally {scope.cancel();db.close()}
    }

}
