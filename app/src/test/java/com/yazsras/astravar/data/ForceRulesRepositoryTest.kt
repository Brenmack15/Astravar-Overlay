package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ForceRulesRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun old()=Campaign(onboarded=true,attuned=true,installedId="core",profile=Profile(baseDamageDiceCount=5,elementalSelectorDiceCount=7,forceRulesVersion=1),
        cores=listOf(Core("core","Existing crystal")),slots=mapOf(2 to 1),notes="Do not lose this",gameSeconds=300)
    private fun strip(e: JsonElement): JsonElement=when(e) {
        is JsonObject -> JsonObject(e.filterKeys {it!="forceRulesVersion"}.mapValues {strip(it.value)})
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    @Test fun legacyDatabaseMigratesSharedActiveProfileKeepsRowsAndSurvivesRestartUndoAndRestore()=runBlocking {
        context.deleteDatabase("force-migration.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"force-migration.db").addMigrations(AstravarDb.MIGRATION_1_2).build()
        val before=old()
        val after=DiceRoller.fire(before,Engine.plan(before,"old-shot",Ability.ARCANE),die={if(it==20) 14 else 9})
        val event=HistoryEntry("old-event","Old shot",300,before,after)
        val archive=HistoryArchive("archive",listOf(event))
        val oldState=strip(BackupCodec.json.encodeToJsonElement(Campaign.serializer(),after)).toString()
        val oldEvent=strip(BackupCodec.json.encodeToJsonElement(HistoryEntry.serializer(),event)).toString()
        val oldArchive=strip(BackupCodec.json.encodeToJsonElement(HistoryArchive.serializer(),archive)).toString()
        var db=open();db.dao().put(StateRow(body=oldState));db.dao().insert(EventRow(event.id,0,oldEvent));db.dao().archive(ArchiveRow(archive.id,oldArchive))
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var main=Repository(db,scope)
        try {
            val overlay=Repository(db,scope)
            val shared=withTimeout(10000) {overlay.state.first {it!=null}!!}
            assertEquals(shared,withTimeout(10000) {main.state.first {it!=null}!!})
            assertEquals(Dice(5,12,5),shared.profile.base);assertEquals(Dice(7,8),shared.profile.selector)
            assertEquals(after.pending,shared.pending);assertEquals(after.cores,shared.cores);assertEquals(after.slots,shared.slots)
            assertEquals(oldState,db.dao().state()!!.body);assertEquals(oldEvent,db.dao().event(event.id)!!.body)
            val prefs=Preferences(context);prefs.elements(listOf(DamageType.FIRE));prefs.mode(Ability.SHATTER)
            main.mutate(label="Keep migrated state") {it}
            scope.cancel();db.close();db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);main=Repository(db,scope)
            val reopened=withTimeout(10000) {main.state.first {it!=null}!!}
            assertEquals(5,reopened.profile.baseDamageDiceCount);assertEquals(7,reopened.profile.elementalSelectorDiceCount)
            assertEquals(2,reopened.profile.forceRulesVersion);assertEquals(300L,reopened.gameSeconds)
            assertEquals(listOf(DamageType.FIRE),Preferences(context).flow.first().elements)
            assertEquals(Ability.SHATTER,Preferences(context).flow.first().mode)
            val backup=main.export();main.import(backup,"restore")
            assertEquals(oldEvent,db.dao().event(event.id)!!.body);assertEquals(oldArchive,db.dao().archives().first {it.id==archive.id}.body)
            // Undo into a historical pre-upgrade profile must still restore current active Force rules.
            main.undo();main.undo();main.undo()
            val undone=BackupCodec.decode(main.export()).campaign
            assertEquals(Dice(5,12,5),undone.profile.base);assertEquals(7,undone.profile.elementalSelectorDiceCount)
            assertEquals("Do not lose this",undone.notes)
        } finally {scope.cancel();db.close()}
    }

    @Test fun maximumShatterPersistsAllFacesAndSharedStateWithoutLosingSelectors()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val main=Repository(db,scope)
        try {
            val before=main.mutate(label="Prepare") {old().copy(profile=Profile(baseDamageDiceCount=10,elementalSelectorDiceCount=8))}
            val p=Engine.plan(before,"maximum",Ability.SHATTER,elements=listOf(DamageType.LIGHTNING),point="Gate")
            val after=main.mutate(p.id,"Roll maximum Shatter") {DiceRoller.fire(it,p,die={it})}
            val overlay=Repository(db,scope)
            assertEquals(after,withTimeout(10000) {overlay.state.first {it?.revision==after.revision}!!})
            val backup=main.export();main.import(backup,"restore-large")
            val restored=BackupCodec.decode(main.export()).campaign
            val roll=restored.pending.single().rolled!!
            assertEquals(List(210) {12},roll.damage.getValue("blast").faces)
            assertEquals(2520,roll.damage.getValue("blast").total);assertEquals(List(8) {8},roll.damage.getValue("element0").faces)
            assertTrue(restored.core("core").destroyed);assertTrue(restored.postShatter)
        } finally {scope.cancel();db.close()}
    }
}
