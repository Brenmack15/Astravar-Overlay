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
class ElementalSelectorRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun seed(m: Int)=Campaign(onboarded=true,attuned=true,profile=Profile(baseDamageDiceCount=4,elementalSelectorDiceCount=m),installedId="core",cores=listOf(Core("core","Existing core")),slots=mapOf(2 to 2),notes="Retain campaign",gameSeconds=120)
    private fun withoutSelector(e: JsonElement): JsonElement=when(e) {
        is JsonObject -> JsonObject(e.filterKeys {it!="elementalSelectorDiceCount"}.mapValues {withoutSelector(it.value)})
        is JsonArray -> JsonArray(e.map(::withoutSelector))
        else -> e
    }

    @Test fun selectorSurvivesReopenSynchronizesBothObserversAndDrivesAllCombatModes()=runBlocking {
        context.deleteDatabase("selector-reopen.db")
        fun open()=Room.databaseBuilder(context,AstravarDb::class.java,"selector-reopen.db").addMigrations(AstravarDb.MIGRATION_1_2,AstravarDb.MIGRATION_2_3).build()
        for(m in listOf(3,4,7,10)) {
            var db=open();var scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var main=Repository(db,scope)
            main.mutate(label="Seed test campaign") {seed(2)}
            main.mutate(label="Select ${m}d8") {it.copy(profile=it.profile.copy(version=it.profile.version+1,elementalSelectorDiceCount=m))}
            scope.coroutineContext[Job]!!.cancelAndJoin();db.close()
            db=open();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);main=Repository(db,scope)
            try {
                val overlay=Repository(db,scope)
                val appState=withTimeout(10000) {main.state.first {it?.profile?.elementalSelectorDiceCount==m}!!}
                val shared=withTimeout(10000) {overlay.state.first {it?.profile?.elementalSelectorDiceCount==m}!!}
                assertEquals(appState,shared);assertEquals(4,shared.profile.baseDamageDiceCount)
                assertEquals("Retain campaign",shared.notes);assertEquals(120L,shared.gameSeconds)
                assertEquals("${m}d8",shared.profile.selector.toString())
                val p=Engine.plan(shared,"element-$m",Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING))
                assertEquals(listOf(Dice(4,12,5),Dice(m,8)),Engine.components(p).map {it.damage.dice})
                // Both production renderers read these exact engine components from the shared profile.
                val rolled=main.mutate(label="Deterministic elemental roll") {DiceRoller.fire(it,p,die={if(it==20) 14 else 3})}
                assertEquals(m,rolled.pending.single().rolled!!.damage.getValue("element0").faces.size)
                assertEquals(rolled,withTimeout(10000) {overlay.state.first {it?.revision==rolled.revision}!!})
                val neutral=Engine.plan(rolled,"neutral-$m",Ability.ARCANE)
                assertEquals(setOf("direct"),DiceRoller.roll(neutral,die={if(it==20) 14 else 3}).damage.keys)
                assertEquals(12,rolled.profile.dischargeDice);assertEquals(84,rolled.profile.shatterDice)
            } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
        }
    }

    @Test fun legacyDatabaseCompactsDuplicateJournalButPreservesExactHistoricalRolls()=runBlocking {
        val before=seed(1)
        val p=Engine.plan(before,"old-shot",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE))
        val after=DiceRoller.fire(before,p,die={if(it==20) 14 else 4})
        val event=HistoryEntry("old-event","Old elemental shot",120,before,after)
        val archive=HistoryArchive("old-archive",listOf(event))
        val oldState=withoutSelector(BackupCodec.json.encodeToJsonElement(Campaign.serializer(),after)).toString()
        val oldEvent=withoutSelector(BackupCodec.json.encodeToJsonElement(HistoryEntry.serializer(),event)).toString()
        val oldArchive=withoutSelector(BackupCodec.json.encodeToJsonElement(HistoryArchive.serializer(),archive)).toString()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        db.dao().put(StateRow(body=oldState));db.dao().insert(EventRow(event.id,0,oldEvent));db.dao().archive(ArchiveRow(archive.id,oldArchive))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val main=Repository(db,scope)
        try {
            val migrated=withTimeout(10000) {main.state.first {it!=null}!!}
            assertEquals(after.copy(profile=after.profile.copy(elementalSelectorDiceCount=2)),migrated)
            assertTrue(BackupCodec.decodeCampaignSnapshot(db.dao().state()!!.body).pending.isEmpty())
            assertTrue(BackupCodec.decodeHistoryEntry(db.dao().events().single().body).after.pending.isEmpty())
            val exported=main.export()
            main.import(exported,"reimport")
            assertTrue(BackupCodec.decodeHistoryEntry(db.dao().event(event.id)!!.body).after.pending.isEmpty())
            run {
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(oldArchive.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
                val original=HistoryPreservation.openBody(db.openHelper.readableDatabase,HistoryPreservation.id("archives",archive.id,sha)).use {it.readBytes().toString(Charsets.UTF_8)}
                assertEquals(oldArchive,original)
            }
            val backup=BackupCodec.decode(main.export())
            assertEquals(after.pending,backup.campaign.pending)
            assertTrue(backup.archives.isEmpty())
            assertEquals(4,backup.campaign.profile.baseDamageDiceCount)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }

    @Test fun backupsRestoreEightAndMigrateOldSelectorWithoutChangingForceOrHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val main=Repository(db,scope)
        try {
            main.mutate(label="Seed selector") {seed(8)}
            val exported=main.export()
            main.mutate(label="Temporarily select 1") {it.copy(profile=it.profile.copy(elementalSelectorDiceCount=1))}
            main.import(exported,"restore-eight")
            var restored=BackupCodec.decode(main.export()).campaign
            assertEquals(8,restored.profile.elementalSelectorDiceCount);assertEquals(4,restored.profile.baseDamageDiceCount)
            val old=withoutSelector(BackupCodec.json.parseToJsonElement(exported)).toString()
            main.import(old,"restore-old")
            restored=BackupCodec.decode(main.export()).campaign
            assertEquals(2,restored.profile.elementalSelectorDiceCount);assertEquals(4,restored.profile.baseDamageDiceCount)
            assertEquals(seed(8).cores,restored.cores);assertEquals(seed(8).slots,restored.slots)
            assertTrue(BackupCodec.decode(main.export()).history.any {it.label=="Temporarily select 1"})
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }

    @Test fun invalidSelectorMutationIsRejectedAtomicallyWithoutChangingEitherTrack()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val main=Repository(db,scope)
        try {
            main.mutate(label="Seed") {seed(7)}
            val saved=main.export()
            for(invalid in listOf(0,-1,11)) {
                try {main.mutate(label="Invalid") {it.copy(profile=it.profile.copy(elementalSelectorDiceCount=invalid))};fail("Invalid count persisted")} catch(_:IllegalArgumentException) {}
                assertEquals(saved,main.export())
            }
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
}
