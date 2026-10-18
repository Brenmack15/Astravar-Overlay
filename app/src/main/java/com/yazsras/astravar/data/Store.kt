package com.yazsras.astravar.data

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.util.UUID

@Entity(tableName="campaign") data class StateRow(@PrimaryKey val id: Int = 1, val body: String)
@Entity(tableName="events",indices=[Index(value=["revision","id"],orders=[Index.Order.DESC,Index.Order.DESC])])
data class EventRow(@PrimaryKey val id: String,val revision: Long,val body: String,@ColumnInfo(defaultValue="'main'") val origin: String="main",
    @ColumnInfo(defaultValue="''") val label: String="",@ColumnInfo(defaultValue="0") val gameSeconds: Long=0,@ColumnInfo(defaultValue="0") val undone: Boolean=false)
@Entity(tableName="rolls",indices=[Index(value=["sequence","id"],orders=[Index.Order.DESC,Index.Order.DESC])])
data class RollRow(@PrimaryKey val id: String,val sequence: Long,val body: String)
@Entity(tableName="archives") data class ArchiveRow(@PrimaryKey val id: String,val body: String)
@Dao interface StateDao {
    @Query("SELECT * FROM campaign WHERE id=1") fun observe(): Flow<StateRow?>
    @Query("SELECT * FROM campaign WHERE id=1") suspend fun state(): StateRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row: StateRow)
    @Query("SELECT id,label,gameSeconds,origin,undone FROM events ORDER BY revision DESC,id DESC LIMIT " + HistoryRetention.MAX_HISTORY_ENTRIES) fun observeEvents(): Flow<List<HistorySummary>>
    @Query(HistoryStorage.EVENTS_QUERY) suspend fun events(): List<EventRow>
    @Query("SELECT * FROM events WHERE id=:id") suspend fun event(id: String): EventRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row: EventRow)
    @Update suspend fun updateEvent(row: EventRow)
    @Query(HistoryStorage.PRUNE_EVENTS) suspend fun pruneEvents()
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query(HistoryStorage.ROLLS_QUERY) fun observeRolls(): Flow<List<RollRow>>
    @Query(HistoryStorage.ROLLS_QUERY) suspend fun rolls(): List<RollRow>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRoll(row: RollRow)
    @Query("DELETE FROM rolls") suspend fun clearRolls()
    @Query(HistoryStorage.PRUNE_ROLLS) suspend fun pruneRolls()
    @Query("SELECT COUNT(*) FROM rolls") suspend fun rollCount(): Int
    @Query("SELECT COUNT(*) FROM events") suspend fun eventCount(): Int
    @Query("SELECT * FROM archives ORDER BY id LIMIT " + HistoryRetention.MAX_HISTORY_ENTRIES) suspend fun archives(): List<ArchiveRow>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun archive(row: ArchiveRow)
}
@Database(entities=[StateRow::class,EventRow::class,ArchiveRow::class,RollRow::class],version=3,exportSchema=true)
abstract class AstravarDb: RoomDatabase() {
    abstract fun dao(): StateDao
    companion object {
        val MIGRATION_1_2=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE events ADD COLUMN origin TEXT NOT NULL DEFAULT 'main'");db.execSQL("CREATE TABLE IF NOT EXISTS archives (id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)") }
        }
        val MIGRATION_2_3=object: Migration(2,3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN label TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN gameSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN undone INTEGER NOT NULL DEFAULT 0")
                HistoryStorage.create(db);HistoryStorage.cleanup(db)
            }
        }
        fun open(context: Context)=Room.databaseBuilder(context,AstravarDb::class.java,"astravar.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    }
}
class Repository(private val db: AstravarDb,scope: CoroutineScope) {
    private val dao=db.dao()
    val error=MutableStateFlow<String?>(null)
    // Gates every read/write, including eager flows. New databases also receive storage triggers.
    private val ready=scope.async(Dispatchers.IO) {db.withTransaction {HistoryStorage.create(db.openHelper.writableDatabase);HistoryStorage.cleanup(db.openHelper.writableDatabase)}}
    private fun failure(message: String,cause: Throwable) {Log.e("AstravarStorage",message,cause);error.value=message}
    private fun roll(row: RollRow): PendingShot? = try {BackupCodec.decodeShot(row.body).also {require(it.plan.id==row.id)}} catch(e: Exception) {Log.w("AstravarHistory","Invalid retained roll ${row.id}",e);null}
    private fun assemble(row: StateRow?,rolls: List<RollRow>): Campaign = (row?.let {BackupCodec.decodeCampaignSnapshot(it.body)} ?: Campaign()).copy(pending=rolls.asReversed().mapNotNull(::roll))
    val state=flow {ready.await();emitAll(db.invalidationTracker.createFlow("campaign","rolls").map {db.withTransaction {current()}})}
        .catch {failure("Saved data is unavailable. Restart Astravar or check diagnostics.",it)}.stateIn(scope,SharingStarted.Eagerly,null)
    // Metadata only: no before/after campaign graphs decoded by the UI or overlay.
    val history=flow {ready.await();emitAll(dao.observeEvents())}
        .catch {failure("Recent history is unavailable. Try reopening Astravar.",it)}.stateIn(scope,SharingStarted.Eagerly,emptyList())
    private suspend fun current()=assemble(dao.state(),dao.rolls())
    suspend fun mutate(id: String=UUID.randomUUID().toString(),label: String,origin: String="main",change: (Campaign)->Campaign): Campaign {
        ready.await()
        return db.withTransaction {
            val before=current()
            if(dao.event(id)!=null) return@withTransaction before
            val changed=change(before)
            val after=HistoryRetention.campaign(changed.copy(profile=changed.profile.currentForceRules(),revision=maxOf(before.revision,changed.revision)+1))
            BackupCodec.validate(after)
            val e=HistoryRetention.compact(HistoryEntry(id,label,after.gameSeconds,before,after,origin))
            dao.insert(EventRow(id,after.revision,BackupCodec.json.encodeToString(e),origin,e.label,e.gameSeconds,e.undone))
            dao.pruneEvents()
            // The at-most-20 immutable snapshots are the only persisted dice source. Observers
            // see this replacement atomically with campaign resource consumption and pruning.
            dao.clearRolls()
            after.pending.forEachIndexed {i,shot->dao.putRoll(RollRow(shot.plan.id,i.toLong(),BackupCodec.json.encodeToString(shot)))}
            dao.pruneRolls()
            dao.put(StateRow(body=BackupCodec.json.encodeToString(after.copy(pending=emptyList()))))
            after
        }
    }
    suspend fun commit(p: ShotPlan,origin: String) = mutate(p.id,"Committed ${p.ability}: ${p.actionCost}; ${if(p.ability==Ability.SHATTER) "core and remaining payloads destroyed" else if(p.ability==Ability.DISCHARGE) "core exhausted for 600 game seconds" else "selected magic spent"}",origin) { Engine.commit(it,p) }
    suspend fun fire(p: ShotPlan,options: RollOptions,origin: String) = mutate(p.id,"Rolled ${p.ability.title}: ${p.actionCost}; costs and individual dice saved",origin) { DiceRoller.fire(it,p,options) }
    suspend fun rollPending(id: String,options: RollOptions,origin: String) = mutate("roll-$id","Rolled committed shot; no additional expenditure",origin) { DiceRoller.rollPending(it,id,options) }
    suspend fun undo(): Campaign {
        ready.await()
        return db.withTransaction {
            val row=dao.events().firstOrNull {!it.undone && it.origin!="correction"} ?: error("No retained operation to undo")
            val e=BackupCodec.decodeHistoryEntry(row.body)
            dao.updateEvent(row.copy(body=BackupCodec.json.encodeToString(e.copy(undone=true)),undone=true))
            mutate(label="Undo/correction of ${e.id}: ${e.label}",origin="correction") {s->e.before.copy(pending=HistoryRetention.restoreRolls(s.pending,e))}
        }
    }
    suspend fun export(): String {
        ready.await()
        return db.withTransaction {BackupCodec.encode(Backup(campaign=current(),history=dao.events().asReversed().map {BackupCodec.decodeHistoryEntry(it.body)}))}
    }
    suspend fun import(text: String,operationId: String) = import(BackupCodec.decode(text),operationId)
    suspend fun import(incoming: Backup,operationId: String) {
        // Revalidate the bounded preview; never retain the original oversized import text.
        val validated=BackupCodec.decode(BackupCodec.encode(incoming))
        mutate(operationId,"Replaced campaign from backup; recent rolls limited to ${HistoryRetention.MAX_HISTORY_ENTRIES}","import") {validated.campaign}
    }
}
internal object RetireTargetAc: androidx.datastore.core.DataMigration<androidx.datastore.preferences.core.Preferences> {
    private val key=intPreferencesKey("armor_class")
    override suspend fun shouldMigrate(currentData: androidx.datastore.preferences.core.Preferences)=currentData.contains(key)
    override suspend fun migrate(currentData: androidx.datastore.preferences.core.Preferences)=currentData.toMutablePreferences().apply {remove(key)}.toPreferences()
    override suspend fun cleanUp() {}
}
/** Only the current mode preference changes; historical Ability.SHATTER snapshots remain untouched. */
internal object RetireShatterMode: androidx.datastore.core.DataMigration<androidx.datastore.preferences.core.Preferences> {
    private val key=stringPreferencesKey("mode")
    override suspend fun shouldMigrate(currentData: androidx.datastore.preferences.core.Preferences)=currentData[key]==Ability.SHATTER.name
    override suspend fun migrate(currentData: androidx.datastore.preferences.core.Preferences)=currentData.toMutablePreferences().apply {
        if(this[key]==Ability.SHATTER.name) this[key]=Ability.ARCANE.name
    }.toPreferences()
    override suspend fun cleanUp() {}
}
private fun currentMode(value: Ability)=if(value==Ability.SHATTER) Ability.ARCANE else value
val Context.overlayPreferences by preferencesDataStore("overlay",produceMigrations={listOf(RetireTargetAc,RetireShatterMode)})
data class OverlayPrefs(val portraitX: Int=0,val portraitY: Int=160,val landscapeX: Int=0,val landscapeY: Int=80,val scale: Float=1f,val image: String="",val elements: List<DamageType> = emptyList(),val mode: Ability=Ability.ARCANE,val selectedPayloads: Set<String> = emptySet(),val advantage: Boolean=false,val disadvantage: Boolean=false,val haptics: Boolean=true,val manualOverrides: Boolean=false,val distance: Int=30,val targetPoint: String="",val directImpact: Boolean=false,val immediateId: String?=null,val manualSlot: Boolean=false)
class Preferences(private val context: Context) {
    val flow=context.overlayPreferences.data.map { p -> OverlayPrefs(p[intPreferencesKey("portrait_x")] ?: 0,p[intPreferencesKey("portrait_y")] ?: 160,p[intPreferencesKey("landscape_x")] ?: 0,p[intPreferencesKey("landscape_y")] ?: 80,p[floatPreferencesKey("scale")] ?: 1f,p[stringPreferencesKey("image")] ?: "",p[stringPreferencesKey("elements")]?.split(',')?.mapNotNull {runCatching {DamageType.valueOf(it)}.getOrNull()} ?: emptyList(),currentMode(runCatching {Ability.valueOf(p[stringPreferencesKey("mode")] ?: "ARCANE")}.getOrDefault(Ability.ARCANE)),p[stringSetPreferencesKey("selected_payloads")] ?: emptySet(),p[booleanPreferencesKey("advantage")] ?: false,p[booleanPreferencesKey("disadvantage")] ?: false,p[booleanPreferencesKey("haptics")] ?: true,p[booleanPreferencesKey("manual_overrides")] ?: false,p[intPreferencesKey("distance")] ?: 30,p[stringPreferencesKey("target_point")] ?: "",p[booleanPreferencesKey("direct_impact")] ?: false,p[stringPreferencesKey("immediate_id")],p[booleanPreferencesKey("manual_slot")] ?: false) }
    suspend fun position(landscape: Boolean,x: Int,y: Int) { context.overlayPreferences.edit { val key=if(landscape) "landscape" else "portrait"; it[intPreferencesKey("${key}_x")]=x; it[intPreferencesKey("${key}_y")]=y } }
    suspend fun scale(scale: Float) { context.overlayPreferences.edit { it[floatPreferencesKey("scale")]=scale.coerceIn(.85f,1.3f) } }
    suspend fun image(uri: String) { context.overlayPreferences.edit { it[stringPreferencesKey("image")]=uri } }
    suspend fun elements(values: List<DamageType>) { context.overlayPreferences.edit {it[stringPreferencesKey("elements")]=values.distinct().joinToString(",") {x->x.name}} }
    suspend fun mode(value: Ability) { context.overlayPreferences.edit {it[stringPreferencesKey("mode")]=currentMode(value).name} }
    suspend fun payloads(ids: Set<String>) {context.overlayPreferences.edit {it[stringSetPreferencesKey("selected_payloads")]=ids}}
    suspend fun haptics(enabled: Boolean) {context.overlayPreferences.edit {it[booleanPreferencesKey("haptics")]=enabled}}
    suspend fun manualOverrides(enabled: Boolean) {context.overlayPreferences.edit {it[booleanPreferencesKey("manual_overrides")]=enabled}}
    suspend fun aim(distance: Int,point: String,direct: Boolean,immediate: String?,manual: Boolean) {
        require(distance in 0..180 && point.length<=500)
        context.overlayPreferences.edit {it[intPreferencesKey("distance")]=distance;it[stringPreferencesKey("target_point")]=point;it[booleanPreferencesKey("direct_impact")]=direct;if(immediate==null) it.remove(stringPreferencesKey("immediate_id")) else it[stringPreferencesKey("immediate_id")]=immediate;it[booleanPreferencesKey("manual_slot")]=manual}
    }
    suspend fun targeting(options: RollOptions) {options.validate();context.overlayPreferences.edit {it[booleanPreferencesKey("advantage")]=options.advantage;it[booleanPreferencesKey("disadvantage")]=options.disadvantage;it.remove(intPreferencesKey("armor_class"))}}
}
