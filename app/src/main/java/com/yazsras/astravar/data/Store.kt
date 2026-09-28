package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID

@Entity(tableName="campaign") data class StateRow(@PrimaryKey val id: Int = 1, val body: String)
@Entity(tableName="events") data class EventRow(@PrimaryKey val id: String, val revision: Long, val body: String, @ColumnInfo(defaultValue="'main'") val origin: String = "main")
@Entity(tableName="archives") data class ArchiveRow(@PrimaryKey val id: String,val body: String)
@Dao interface StateDao {
    @Query("SELECT * FROM campaign WHERE id=1") fun observe(): Flow<StateRow?>
    @Query("SELECT * FROM campaign WHERE id=1") suspend fun state(): StateRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row: StateRow)
    @Query("SELECT * FROM events ORDER BY revision DESC") fun observeEvents(): Flow<List<EventRow>>
    @Query("SELECT * FROM events ORDER BY revision ASC") suspend fun events(): List<EventRow>
    @Query("SELECT * FROM events WHERE id=:id") suspend fun event(id: String): EventRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row: EventRow)
    @Update suspend fun updateEvent(row: EventRow)
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("SELECT * FROM archives ORDER BY id") suspend fun archives(): List<ArchiveRow>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun archive(row: ArchiveRow)
}
@Database(entities=[StateRow::class,EventRow::class,ArchiveRow::class],version=2,exportSchema=true)
abstract class AstravarDb: RoomDatabase() {
    abstract fun dao(): StateDao
    companion object {
        val MIGRATION_1_2=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE events ADD COLUMN origin TEXT NOT NULL DEFAULT 'main'");db.execSQL("CREATE TABLE IF NOT EXISTS archives (id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)") }
        }
        fun open(context: Context)=Room.databaseBuilder(context,AstravarDb::class.java,"astravar.db").addMigrations(MIGRATION_1_2).build()
    }
}
class Repository(private val db: AstravarDb,scope: CoroutineScope) {
    private val dao=db.dao()
    val error=MutableStateFlow<String?>(null)
    val state=dao.observe().map { it?.let { BackupCodec.json.decodeFromString<Campaign>(it.body).also(BackupCodec::validate) } ?: Campaign() }
        .catch { error.value="Cannot read saved campaign. Data has been preserved: ${it.message}" }.stateIn(scope,SharingStarted.Eagerly,null)
    val history=dao.observeEvents().map { rows -> rows.map { BackupCodec.json.decodeFromString<HistoryEntry>(it.body) } }
        .catch { error.value="Cannot read history: ${it.message}" }.stateIn(scope,SharingStarted.Eagerly,emptyList())
    suspend fun mutate(id: String=UUID.randomUUID().toString(),label: String,origin: String="main",change: (Campaign)->Campaign): Campaign = db.withTransaction {
        val before=dao.state()?.let { BackupCodec.json.decodeFromString<Campaign>(it.body) } ?: Campaign()
        if(dao.event(id)!=null) return@withTransaction before
        val changed=change(before)
        val after=changed.copy(revision=maxOf(before.revision,changed.revision)+1)
        BackupCodec.validate(after)
        val e=HistoryEntry(id,label,after.gameSeconds,before,after,origin)
        dao.insert(EventRow(id,after.revision,BackupCodec.json.encodeToString(e),origin))
        dao.put(StateRow(body=BackupCodec.json.encodeToString(after)))
        after
    }
    suspend fun commit(p: ShotPlan,origin: String) = mutate(p.id,"Committed ${p.ability}: ${p.actionCost}; ${if(p.ability==Ability.SHATTER) "core and remaining payloads destroyed" else if(p.ability==Ability.DISCHARGE) "core exhausted for 600 game seconds" else "selected magic spent"}",origin) { Engine.commit(it,p) }
    suspend fun fire(p: ShotPlan,options: RollOptions,origin: String) = mutate(p.id,"Rolled ${p.ability.title}: ${p.actionCost}; costs and individual dice saved",origin) { DiceRoller.fire(it,p,options) }
    suspend fun rollPending(id: String,options: RollOptions,origin: String) = mutate("roll-$id","Rolled committed shot; no additional expenditure",origin) { DiceRoller.rollPending(it,id,options) }
    suspend fun undo()=db.withTransaction {
        val rows=dao.events()
        val pair=rows.asReversed().map {it to BackupCodec.json.decodeFromString<HistoryEntry>(it.body)}.firstOrNull {(_,e)->!e.undone && e.origin!="correction"} ?: error("No remaining operation to undo")
        val (row,e)=pair
        dao.updateEvent(row.copy(body=BackupCodec.json.encodeToString(e.copy(undone=true))))
        mutate(label="Undo/correction of ${e.id}: ${e.label}",origin="correction") { e.before }
    }
    suspend fun export(): String = db.withTransaction {
        val s=dao.state()?.let { BackupCodec.json.decodeFromString<Campaign>(it.body) } ?: Campaign()
        BackupCodec.encode(Backup(campaign=s,history=dao.events().map { BackupCodec.json.decodeFromString<HistoryEntry>(it.body) },archives=dao.archives().map {BackupCodec.json.decodeFromString<HistoryArchive>(it.body)}))
    }
    suspend fun import(text: String,operationId: String) {
        val incoming=BackupCodec.decode(text)
        db.withTransaction {
            if(dao.event(operationId)!=null) return@withTransaction
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
            val source=HistoryArchive(digest,incoming.history)
            val archived=dao.archives().associate {it.id to it.body}.toMutableMap()
            for(archive in incoming.archives+source) {
                val body=BackupCodec.json.encodeToString(archive)
                require(archived[archive.id]==null || archived[archive.id]==body) {"Conflicting archived history ID; import cancelled without changes"}
                dao.archive(ArchiveRow(archive.id,body));archived[archive.id]=body
            }
            mutate(operationId,"Replace campaign from validated backup; previous state recoverable with Undo", "import") { incoming.campaign }
        }
    }
}
val Context.overlayPreferences by preferencesDataStore("overlay")
data class OverlayPrefs(val portraitX: Int=0,val portraitY: Int=160,val landscapeX: Int=0,val landscapeY: Int=80,val scale: Float=1f,val image: String="",val elements: List<DamageType> = emptyList(),val mode: Ability=Ability.ARCANE,val selectedPayloads: Set<String> = emptySet(),val advantage: Boolean=false,val disadvantage: Boolean=false,val armorClass: Int?=null,val haptics: Boolean=true,val manualOverrides: Boolean=false,val distance: Int=30,val targetPoint: String="",val directImpact: Boolean=false,val immediateId: String?=null,val manualSlot: Boolean=false)
class Preferences(private val context: Context) {
    val flow=context.overlayPreferences.data.map { p -> OverlayPrefs(p[intPreferencesKey("portrait_x")] ?: 0,p[intPreferencesKey("portrait_y")] ?: 160,p[intPreferencesKey("landscape_x")] ?: 0,p[intPreferencesKey("landscape_y")] ?: 80,p[floatPreferencesKey("scale")] ?: 1f,p[stringPreferencesKey("image")] ?: "",p[stringPreferencesKey("elements")]?.split(',')?.mapNotNull {runCatching {DamageType.valueOf(it)}.getOrNull()} ?: emptyList(),runCatching {Ability.valueOf(p[stringPreferencesKey("mode")] ?: "ARCANE")}.getOrDefault(Ability.ARCANE),p[stringSetPreferencesKey("selected_payloads")] ?: emptySet(),p[booleanPreferencesKey("advantage")] ?: false,p[booleanPreferencesKey("disadvantage")] ?: false,p[intPreferencesKey("armor_class")],p[booleanPreferencesKey("haptics")] ?: true,p[booleanPreferencesKey("manual_overrides")] ?: false,p[intPreferencesKey("distance")] ?: 30,p[stringPreferencesKey("target_point")] ?: "",p[booleanPreferencesKey("direct_impact")] ?: false,p[stringPreferencesKey("immediate_id")],p[booleanPreferencesKey("manual_slot")] ?: false) }
    suspend fun position(landscape: Boolean,x: Int,y: Int) { context.overlayPreferences.edit { val key=if(landscape) "landscape" else "portrait"; it[intPreferencesKey("${key}_x")]=x; it[intPreferencesKey("${key}_y")]=y } }
    suspend fun scale(scale: Float) { context.overlayPreferences.edit { it[floatPreferencesKey("scale")]=scale.coerceIn(.85f,1.3f) } }
    suspend fun image(uri: String) { context.overlayPreferences.edit { it[stringPreferencesKey("image")]=uri } }
    suspend fun elements(values: List<DamageType>) { context.overlayPreferences.edit {it[stringPreferencesKey("elements")]=values.distinct().joinToString(",") {x->x.name}} }
    suspend fun mode(value: Ability) { context.overlayPreferences.edit {it[stringPreferencesKey("mode")]=value.name} }
    suspend fun payloads(ids: Set<String>) {context.overlayPreferences.edit {it[stringSetPreferencesKey("selected_payloads")]=ids}}
    suspend fun haptics(enabled: Boolean) {context.overlayPreferences.edit {it[booleanPreferencesKey("haptics")]=enabled}}
    suspend fun manualOverrides(enabled: Boolean) {context.overlayPreferences.edit {it[booleanPreferencesKey("manual_overrides")]=enabled}}
    suspend fun aim(distance: Int,point: String,direct: Boolean,immediate: String?,manual: Boolean) {
        require(distance in 0..180 && point.length<=500)
        context.overlayPreferences.edit {it[intPreferencesKey("distance")]=distance;it[stringPreferencesKey("target_point")]=point;it[booleanPreferencesKey("direct_impact")]=direct;if(immediate==null) it.remove(stringPreferencesKey("immediate_id")) else it[stringPreferencesKey("immediate_id")]=immediate;it[booleanPreferencesKey("manual_slot")]=manual}
    }
    suspend fun targeting(options: RollOptions) {options.validate();context.overlayPreferences.edit {it[booleanPreferencesKey("advantage")]=options.advantage;it[booleanPreferencesKey("disadvantage")]=options.disadvantage;val ac=options.armorClass;if(ac==null) it.remove(intPreferencesKey("armor_class")) else it[intPreferencesKey("armor_class")]=ac}}
}
