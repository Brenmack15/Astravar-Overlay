package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.CharacterCalculator
import com.yazsras.astravar.rules.reforge.InventoryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Local gameplay state. It is deliberately not presented as a synchronized campaign mirror. */
const val REFORGE_LOCAL_ONLY = "Saved on this device · not synchronized"
internal val reforgeJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
internal const val REFORGE_MAX_JSON = 8_000_000

@Serializable data class ReforgeOrigin(
    val label: String = "Created on this device", val sourceProfileId: String? = null,
    val sourceFingerprint: String? = null, val sourceSnapshotJson: String? = null,
    val warnings: List<String> = emptyList()
)
@Serializable data class ReforgeSavedCharacter(
    val character: ReforgeCharacter, val revision: Long, val origin: ReforgeOrigin,
    val createdAt: Long, val updatedAt: Long, val saveState: String = REFORGE_LOCAL_ONLY
)

@Serializable @Entity(tableName = "reforge_characters")
data class ReforgeCharacterRow(
    @PrimaryKey val profileId: String, val characterJson: String, val revision: Long,
    val originJson: String, val createdAt: Long, val updatedAt: Long
) {
    fun saved(): ReforgeSavedCharacter = ReforgeSavedCharacter(
        reforgeJson.decodeFromString(characterJson), revision, reforgeJson.decodeFromString(originJson), createdAt, updatedAt
    )
}

/** Immutable command outcomes support retries without rebasing a changed operation. */
@Serializable @Entity(tableName = "reforge_changes", indices = [Index("profileId"), Index("createdAt")])
data class ReforgeChange(
    @PrimaryKey val commandId: String, val profileId: String, val operation: String,
    val fingerprint: String, val priorRevision: Long, val resultingRevision: Long,
    val createdAt: Long, val beforeJson: String?, val afterJson: String, val outcomeType: String = "CHARACTER"
)

@Serializable data class ReforgeRollDie(val sides: Int, val face: Int, val kept: Boolean = true, val sign: Int = 1)
@Serializable data class ReforgeRollComponent(
    val label: String, val dice: List<ReforgeRollDie>, val modifier: Int = 0, val damageType: String? = null
) {
    val total: Int get() = dice.filter { it.kept }.sumOf { it.face * it.sign } + modifier
}
@Serializable @Entity(tableName = "reforge_rolls", indices = [Index("profileId"), Index("createdAt")])
data class ReforgeRollRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val profileId: String,
    val characterRevision: Long, val label: String, val formula: String,
    val components: List<ReforgeRollComponent>, val manual: Boolean = false,
    val ownership: String = "Player", val createdAt: Long = System.currentTimeMillis()
) {
    @get:Ignore val total: Int get() = components.sumOf { it.total }
}
data class ReforgeExportSnapshot(val text: String, val currentFingerprint: String)

@Serializable data class ReforgeActionOutcome(val character: ReforgeSavedCharacter, val roll: ReforgeRollRecord)

/** Recovery draft only; it never overwrites a committed character without an explicit revision-checked save. */
@Serializable @Entity(tableName = "reforge_note_drafts")
data class ReforgeNoteDraft(@PrimaryKey val profileId: String, val expectedRevision: Long, val text: String, val updatedAt: Long)

data class ReforgeChangeSummary(val commandId: String, val profileId: String, val operation: String,
    val priorRevision: Long, val resultingRevision: Long, val createdAt: Long, val outcomeType: String)

class ReforgeConverters {
    @TypeConverter fun componentsToJson(value: List<ReforgeRollComponent>): String = reforgeJson.encodeToString(value)
    @TypeConverter fun componentsFromJson(value: String): List<ReforgeRollComponent> = reforgeJson.decodeFromString(value)
}

@Dao interface ReforgeDao {
    @Query("SELECT * FROM reforge_characters WHERE profileId=:profileId") fun observe(profileId: String): Flow<ReforgeCharacterRow?>
    @Query("SELECT * FROM reforge_characters ORDER BY profileId") fun observeAll(): Flow<List<ReforgeCharacterRow>>
    @Query("SELECT * FROM reforge_characters WHERE profileId=:profileId") suspend fun character(profileId: String): ReforgeCharacterRow?
    @Query("SELECT * FROM reforge_characters ORDER BY profileId") suspend fun allCharacters(): List<ReforgeCharacterRow>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: ReforgeCharacterRow)
    @Update suspend fun update(value: ReforgeCharacterRow)
    @Query("SELECT * FROM reforge_changes WHERE commandId=:id") suspend fun change(id: String): ReforgeChange?
    @Query("SELECT * FROM reforge_changes ORDER BY createdAt,commandId") suspend fun allChanges(): List<ReforgeChange>
    @Query("SELECT * FROM reforge_changes WHERE profileId=:profileId ORDER BY createdAt DESC,commandId DESC") fun history(profileId: String): Flow<List<ReforgeChange>>
    @Query("SELECT commandId,profileId,operation,priorRevision,resultingRevision,createdAt,outcomeType FROM reforge_changes WHERE profileId=:profileId ORDER BY createdAt DESC,commandId DESC LIMIT 100")
    fun historySummaries(profileId: String): Flow<List<ReforgeChangeSummary>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: ReforgeChange)
    @Query("SELECT * FROM reforge_rolls WHERE id=:id") suspend fun roll(id: String): ReforgeRollRecord?
    @Query("SELECT * FROM reforge_rolls ORDER BY createdAt,id") suspend fun allRolls(): List<ReforgeRollRecord>
    @Query("SELECT * FROM reforge_rolls WHERE profileId=:profileId ORDER BY createdAt DESC,id DESC LIMIT 100") fun rolls(profileId: String): Flow<List<ReforgeRollRecord>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: ReforgeRollRecord)
    @Query("SELECT * FROM reforge_note_drafts WHERE profileId=:profileId") suspend fun draft(profileId: String): ReforgeNoteDraft?
    @Query("SELECT * FROM reforge_note_drafts ORDER BY profileId") suspend fun allDrafts(): List<ReforgeNoteDraft>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: ReforgeNoteDraft)
    @Update suspend fun update(value: ReforgeNoteDraft)
    @Query("DELETE FROM reforge_note_drafts WHERE profileId=:profileId") suspend fun deleteDraft(profileId: String)
}

/** New additive store: no existing campaign/weapon table is opened or rewritten during creation. */
@Database(entities = [ReforgeCharacterRow::class, ReforgeChange::class, ReforgeRollRecord::class, ReforgeNoteDraft::class], version = 1, exportSchema = true)
@TypeConverters(ReforgeConverters::class)
abstract class ReforgeDb : RoomDatabase() {
    abstract fun reforgeDao(): ReforgeDao
    companion object {
        @Volatile private var instance: ReforgeDb? = null
        fun open(context: Context): ReforgeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReforgeDb::class.java, "astravar-reforge.db")
                .enableMultiInstanceInvalidation().build().also { instance = it }
        }
    }
}

/** Changes, previews and rolls are local transactions. No network adapter or campaign grant is implied. */
class ReforgeRepository(private val db: ReforgeDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.reforgeDao()
    companion object { fun open(context: Context): ReforgeRepository = ReforgeRepository(ReforgeDb.open(context)) }
    fun observe(profileId: String): Flow<ReforgeSavedCharacter?> = dao.observe(profileId).map { it?.saved() }.flowOn(Dispatchers.Default)
    fun observeAll(): Flow<List<ReforgeSavedCharacter>> = dao.observeAll().map { rows -> rows.map { it.saved() } }.flowOn(Dispatchers.Default)
    fun observeActive(): Flow<List<ReforgeSavedCharacter>> = observeAll().map { profiles -> profiles.filterNot { it.character.archived } }
    fun rollHistory(profileId: String): Flow<List<ReforgeRollRecord>> = dao.rolls(profileId)
    fun history(profileId: String): Flow<List<ReforgeChange>> = dao.history(profileId)
    fun historySummaries(profileId: String): Flow<List<ReforgeChangeSummary>> = dao.historySummaries(profileId)
    suspend fun get(profileId: String): ReforgeSavedCharacter? = withContext(Dispatchers.Default) { dao.character(profileId)?.saved() }
    suspend fun commandReceipt(commandId: String): ReforgeChange? = dao.change(commandId)
    suspend fun readNoteDraft(profileId: String): ReforgeNoteDraft? = dao.draft(profileId)
    suspend fun saveNoteDraft(profileId: String, expectedRevision: Long, text: String,
        expectedDraftUpdatedAt: Long? = null): ReforgeNoteDraft = db.withTransaction {
        checkId(profileId); require(text.length <= 100_000) { "Notes draft is too long." }
        val character = dao.character(profileId) ?: error("Save the character before writing notes.")
        check(character.revision == expectedRevision) { "Character changed. Your recovery draft is retained; review the latest notes before saving." }
        val before = dao.draft(profileId)
        if (before != null && before.expectedRevision == expectedRevision && before.text == text) return@withTransaction before
        check(before?.updatedAt == expectedDraftUpdatedAt && (before == null || before.expectedRevision == expectedRevision)) {
            "A different notes draft is saved. Review or discard that exact draft before replacing it."
        }
        val after = ReforgeNoteDraft(profileId, expectedRevision, text, maxOf(clock(), before?.updatedAt?.let { Math.addExact(it, 1) } ?: Long.MIN_VALUE))
        if (before == null) dao.insert(after) else dao.update(after)
        after
    }
    /** Called only after the user reviews recovery text against current notes; it never applies that text itself. */
    suspend fun reviewNoteDraft(profileId: String, expectedDraftUpdatedAt: Long, expectedCurrentRevision: Long,
        reviewedText: String? = null): ReforgeNoteDraft = db.withTransaction {
        checkId(profileId)
        val character = dao.character(profileId) ?: error("The character is no longer available.")
        check(character.revision == expectedCurrentRevision) { "Character changed during review. Review the latest notes again." }
        val before = dao.draft(profileId) ?: error("The recovery draft is no longer available.")
        check(before.updatedAt == expectedDraftUpdatedAt) { "Recovery draft changed during review. Review the latest draft again." }
        require(reviewedText == null || reviewedText.length <= 100_000) { "Reviewed notes are too long." }
        val after = before.copy(expectedRevision = expectedCurrentRevision, text = reviewedText ?: before.text, updatedAt = maxOf(clock(), Math.addExact(before.updatedAt, 1)))
        dao.update(after)
        after
    }

    suspend fun discardNoteDraft(profileId: String, expectedRevision: Long? = null, expectedUpdatedAt: Long? = null): Unit = db.withTransaction {
        checkId(profileId)
        dao.draft(profileId)?.let { draft ->
            check(expectedRevision == null || draft.expectedRevision == expectedRevision) { "Notes draft revision changed; it was retained." }
            check(expectedUpdatedAt == null || draft.updatedAt == expectedUpdatedAt) { "Notes draft changed; it was retained." }
            dao.deleteDraft(profileId)
        }
    }

    /** Atomic roll/resource action. No result exists without its mechanics, receipt and exact pre-action revision. */
    suspend fun commitRoll(profileId: String, expectedRevision: Long, nextCharacter: ReforgeCharacter,
        operation: String, roll: ReforgeRollRecord, commandId: String = roll.id): ReforgeActionOutcome = db.withTransaction {
        checkId(profileId); checkId(commandId); validateCharacter(nextCharacter); validateRoll(roll)
        require(operation.isNotBlank() && operation.length <= 200)
        require(nextCharacter.id == profileId && nextCharacter.revision == expectedRevision &&
            roll.profileId == profileId && roll.characterRevision == expectedRevision) { "Action or roll belongs to another character revision." }
        val fp = reforgeHash(reforgeJson.encodeToString(listOf("action-roll", profileId, expectedRevision.toString(), operation,
            reforgeJson.encodeToString(nextCharacter), reforgeJson.encodeToString(roll.copy(createdAt = 0)))))
        retry(commandId, fp)?.let { return@withTransaction reforgeJson.decodeFromString<ReforgeActionOutcome>(it.afterJson) }
        val before = dao.character(profileId) ?: error("Save the character before rolling an action.")
        check(before.revision == expectedRevision) { "Character changed since this action began. Review the latest state." }
        require(!before.saved().character.archived && !nextCharacter.archived) { "Restore this archived sheet before recording new actions." }
        require(dao.roll(roll.id) == null && dao.change(roll.id) == null) { "Roll identity already exists." }
        check(expectedRevision < Long.MAX_VALUE)
        val now = maxOf(clock(), Math.addExact(before.updatedAt, 1))
        val after = before.copy(characterJson = reforgeJson.encodeToString(nextCharacter.copy(revision = expectedRevision + 1)),
            revision = expectedRevision + 1, updatedAt = now)
        val outcome = ReforgeActionOutcome(after.saved(), roll)
        dao.update(after); dao.insert(roll)
        dao.insert(ReforgeChange(commandId, profileId, operation, fp, expectedRevision, after.revision, now,
            reforgeJson.encodeToString(before.saved()), reforgeJson.encodeToString(outcome), outcomeType = "ACTION"))
        outcome
    }

    /** Optional atomic action path: the exact roll binds the pre-action revision and both receipts commit together. */
    suspend fun saveWithRoll(profileId: String, expectedRevision: Long, character: ReforgeCharacter,
        operation: String, roll: ReforgeRollRecord, commandId: String = roll.id): ReforgeSavedCharacter = db.withTransaction {
        require(roll.profileId == profileId && roll.characterRevision == expectedRevision) { "Roll belongs to another character revision." }
        appendRoll(roll, commandId)
        save(profileId, expectedRevision, character, operation, "state:${reforgeHash(commandId)}")
    }

    suspend fun create(character: ReforgeCharacter, commandId: String = UUID.randomUUID().toString()): ReforgeSavedCharacter =
        createWithOrigin(character, ReforgeOrigin(), "Create character", commandId)

    /** A reversible revision-checked change; no character, source or historical receipt is deleted. */
    suspend fun setArchived(character: ReforgeCharacter, archived: Boolean,
        commandId: String = UUID.randomUUID().toString()): ReforgeSavedCharacter =
        save(character.id, character.revision, character.copy(archived = archived),
            if (archived) "Archive playable sheet" else "Restore archived playable sheet", commandId)

    /** Both ownership changes and their immutable revision receipts commit, or neither does. */
    suspend fun transferItem(from: ReforgeCharacter, to: ReforgeCharacter, itemId: String,
        commandId: String = UUID.randomUUID().toString()): Pair<ReforgeSavedCharacter, ReforgeSavedCharacter> = db.withTransaction {
        checkId(commandId)
        val (nextFrom, nextTo) = InventoryRules.transfer(from, to, itemId)
        val transferId = reforgeHash(commandId)
        val outgoing = save(from.id, from.revision, nextFrom, "Transfer item out", "transfer-out:$transferId")
        val incoming = save(to.id, to.revision, nextTo, "Receive transferred item", "transfer-in:$transferId")
        outgoing to incoming
    }

    internal suspend fun createWithOrigin(character: ReforgeCharacter, origin: ReforgeOrigin, operation: String, commandId: String): ReforgeSavedCharacter = db.withTransaction {
        require(character.revision == 0L) { "A new character must have revision zero." }
        validateCharacter(character); checkId(commandId)
        require(reforgeJson.encodeToString(origin).length <= 2_000_000) { "Reviewed source snapshot is too large for this local copy. Original records are unchanged." }
        val fp = reforgeHash(reforgeJson.encodeToString(listOf(operation, reforgeJson.encodeToString(character), reforgeJson.encodeToString(origin))))
        retry(commandId, fp)?.let { return@withTransaction reforgeJson.decodeFromString<ReforgeSavedCharacter>(it.afterJson) }
        check(dao.character(character.id) == null) { "This character already exists. Open its current saved state." }
        val now = clock()
        val after = ReforgeCharacterRow(character.id, reforgeJson.encodeToString(character.copy(revision = 1)), 1,
            reforgeJson.encodeToString(origin), now, now)
        dao.insert(after)
        dao.insert(ReforgeChange(commandId, character.id, operation, fp, 0, 1, now, null, reforgeJson.encodeToString(after.saved())))
        after.saved()
    }

    /** The editor/engine must submit the revision it actually read; stale changes never win silently. */
    suspend fun save(profileId: String, expectedRevision: Long, character: ReforgeCharacter, operation: String,
        commandId: String = UUID.randomUUID().toString()): ReforgeSavedCharacter = db.withTransaction {
        checkId(profileId); checkId(commandId); require(operation.isNotBlank() && operation.length <= 200)
        require(character.id == profileId && character.revision == expectedRevision) { "Character identity or expected revision changed." }
        validateCharacter(character)
        val fp = reforgeHash(reforgeJson.encodeToString(listOf("save", profileId, expectedRevision.toString(), operation, reforgeJson.encodeToString(character))))
        retry(commandId, fp)?.let { return@withTransaction reforgeJson.decodeFromString<ReforgeSavedCharacter>(it.afterJson) }
        val before = dao.character(profileId) ?: error("This character is not saved yet.")
        check(before.revision == expectedRevision) { "Character changed since this action began. Review the latest state." }
        check(expectedRevision < Long.MAX_VALUE) { "Character revision limit reached." }
        val now = maxOf(clock(), Math.addExact(before.updatedAt, 1))
        val after = before.copy(characterJson = reforgeJson.encodeToString(character.copy(revision = expectedRevision + 1)),
            revision = expectedRevision + 1, updatedAt = now)
        dao.update(after)
        dao.insert(ReforgeChange(commandId, profileId, operation, fp, before.revision, after.revision, now,
            reforgeJson.encodeToString(before.saved()), reforgeJson.encodeToString(after.saved())))
        after.saved()
    }

    /** Only explicitly initiated results are submitted. A retry returns the original immutable result. */
    suspend fun appendRoll(roll: ReforgeRollRecord, commandId: String = roll.id): ReforgeRollRecord = db.withTransaction {
        checkId(commandId); validateRoll(roll)
        val fp = reforgeHash("roll:${reforgeJson.encodeToString(roll.copy(createdAt = 0))}")
        retry(commandId, fp)?.let { return@withTransaction reforgeJson.decodeFromString<ReforgeRollRecord>(it.afterJson) }
        val character = dao.character(roll.profileId) ?: error("Save the character before recording a roll.")
        check(character.revision == roll.characterRevision) { "Character changed. Roll again from the latest statistics or record an explicit manual result." }
        require(!character.saved().character.archived) { "Restore this archived sheet before recording new rolls." }
        require(dao.roll(roll.id) == null && dao.change(roll.id) == null) { "Roll identity already exists." }
        dao.insert(roll)
        dao.insert(ReforgeChange(commandId, roll.profileId, "Roll", fp, character.revision, character.revision,
            clock(), null, reforgeJson.encodeToString(roll), outcomeType = "ROLL"))
        roll
    }

    /** Changing state and writing its receipt are one Room transaction. No inferred rest or refill occurs. */
    suspend fun export(): String = exportWithProof().text
    suspend fun exportWithProof(): ReforgeExportSnapshot = db.withTransaction {
        val current = snapshot(0)
        validateReforgeBackup(current)
        val text = reforgeJson.encodeToString(current.copy(exportedAt = clock()))
        require(text.length <= REFORGE_MAX_JSON) { "Backup exceeds the supported export size." }
        ReforgeExportSnapshot(text, reforgeHash(reforgeJson.encodeToString(current)))
    }
    suspend fun previewRestore(text: String): ReforgeRestorePreview = db.withTransaction {
        val current = snapshot(0)
        try {
            val incoming = decodeReforgeBackup(text)
            val conflicts = reforgeRestoreConflicts(incoming, current)
            ReforgeRestorePreview(conflicts.isEmpty(), "${incoming.characters.size} characters, ${incoming.changes.size} history entries, ${incoming.rolls.size} rolls. Existing different records are never overwritten.",
                conflicts, reforgeHash(reforgeJson.encodeToString(incoming)), reforgeHash(reforgeJson.encodeToString(current)), incoming)
        } catch (error: Exception) {
            ReforgeRestorePreview(false, "This backup could not be validated.", listOf(error.message ?: "Unsupported backup."), "", reforgeHash(reforgeJson.encodeToString(current)))
        }
    }
    suspend fun restore(preview: ReforgeRestorePreview, commandId: String = UUID.randomUUID().toString()): ReforgeChange = db.withTransaction {
        checkId(commandId)
        require(preview.valid && preview.conflicts.isEmpty()) { "Review a valid backup preview first." }
        val incoming = requireNotNull(preview.backup)
        require(reforgeHash(reforgeJson.encodeToString(incoming)) == preview.fingerprint) { "Backup preview changed. Review it again." }
        val fp = reforgeHash("restore:${preview.fingerprint}")
        retry(commandId, fp)?.let { return@withTransaction it }
        require(incoming.changes.none { it.commandId == commandId } && incoming.rolls.none { it.id == commandId }) { "Restore command identity is already in the backup." }
        val current = snapshot(0)
        check(reforgeHash(reforgeJson.encodeToString(current)) == preview.currentFingerprint) { "Local data changed after preview. Review the backup again." }
        val conflicts = reforgeRestoreConflicts(incoming, current)
        require(conflicts.isEmpty()) { conflicts.joinToString("\n") }
        val currentCharacters = current.characters.map { it.profileId }.toSet()
        val currentChanges = current.changes.map { it.commandId }.toSet()
        val currentRolls = current.rolls.map { it.id }.toSet()
        val currentDrafts = current.drafts.map { it.profileId }.toSet()
        incoming.characters.filter { it.profileId !in currentCharacters }.forEach { dao.insert(it) }
        incoming.changes.filter { it.commandId !in currentChanges }.forEach { dao.insert(it) }
        incoming.rolls.filter { it.id !in currentRolls }.forEach { dao.insert(it) }
        incoming.drafts.filter { it.profileId !in currentDrafts }.forEach { dao.insert(it) }
        ReforgeChange(commandId, "*", "Restore backup", fp, 0, 0, clock(), null, preview.summary, outcomeType = "RESTORE").also { dao.insert(it) }
    }

    private suspend fun retry(commandId: String, fingerprint: String): ReforgeChange? {
        dao.change(commandId)?.let { require(it.fingerprint == fingerprint) { "Command identity was reused with different content. Nothing changed." }; return it }
        require(dao.roll(commandId) == null) { "Command identity belongs to an existing roll." }
        return null
    }
    private suspend fun snapshot(time: Long) = ReforgeBackup(exportedAt = time, characters = dao.allCharacters(), changes = dao.allChanges(), rolls = dao.allRolls(), drafts = dao.allDrafts())
}

internal fun checkId(value: String) { require(value.isNotBlank() && value.length <= 160 && value.none { it.isISOControl() }) { "Invalid record identity." } }
internal fun reforgeHash(text: String): String = campaignHash(text)

/** Structural bounds complement the rules engine: unknown scores/resources remain nullable. */
internal fun validateCharacter(value: ReforgeCharacter) {
    CharacterCalculator.validate(value)
    checkId(value.id); require(value.name.isNotBlank() && value.name.length <= 160) { "Enter a character name." }
    require(value.schemaVersion == 1 && value.revision >= 0 && value.level in 1..1000) { "Unsupported character version, revision or level." }
    require(value.baseAbilities.values.all { it == null || it in 0..1000 }) { "Ability scores must be known whole numbers or unknown." }
    require(value.permanentAdvancements.values.all { it in -1000..1000 })
    require(listOf(value.baseMaxHp, value.currentHp, value.temporaryHp, value.hpDamage).all { it == null || it in 0..1_000_000 })
    require(value.baseAc == null || value.baseAc in 0..1000)
    require(value.spellSlots.all { (level, current) -> level in 1..9 && (current == null || current in 0..1000) })
    require(value.resources.all { it.id.isNotBlank() && (it.current == null || it.current in 0..1_000_000) && (it.maximum == null || it.maximum in 0..1_000_000) })
    require(value.gameSeconds >= 0 && value.turnNumber >= 0)
    fun distinct(ids: List<String>) { require(ids.size == ids.toSet().size && ids.all { it.isNotBlank() && it.length <= 160 }) { "Duplicate or invalid mechanical record identities." } }
    distinct(value.items.map { it.id }); distinct(value.itemDefinitions.map { it.id }); distinct(value.effects.map { it.id })
    distinct(value.attacks.map { it.id }); distinct(value.spells.map { it.id }); distinct(value.resources.map { it.id })
    val definitions = value.itemDefinitions.associateBy { it.id }
    val items = value.items.associateBy { it.id }
    require(value.items.all { it.definitionId in definitions }) { "Item definition is missing." }
    require(value.items.all { it.quantity in 0..1_000_000 && (it.containerId == null || it.containerId in items) })
    require(value.items.all { it.id !in it.donorInstanceIds && items.keys.containsAll(it.donorInstanceIds) })
    value.items.forEach { item ->
        val visited = mutableSetOf(item.id)
        var container = item.containerId
        while (container != null) {
            require(visited.add(container)) { "Container cycle is not allowed." }
            container = items[container]?.containerId
        }
    }
    // A donor must have one composite owner and the donor relation must be a DAG.
    val ownerByDonor = mutableMapOf<String, String>()
    value.items.forEach { composite -> composite.donorInstanceIds.forEach { donor ->
        require(ownerByDonor.put(donor, composite.id) == null) { "An integrated donor belongs to more than one composite item." }
    } }
    value.items.forEach { item ->
        val visited = mutableSetOf(item.id)
        var owner = ownerByDonor[item.id]
        while (owner != null) {
            require(visited.add(owner)) { "Composite item cycle is not allowed." }
            owner = ownerByDonor[owner]
        }
    }
    require(reforgeJson.encodeToString(value).length <= 1_000_000) { "Character record is too large." }
}
internal fun validateRoll(value: ReforgeRollRecord) {
    checkId(value.id); checkId(value.profileId)
    require(value.characterRevision > 0 && value.label.isNotBlank() && value.label.length <= 200 && value.formula.length <= 1000)
    require(value.ownership in setOf("Player", "DM", "Companion")) { "Unknown dice ownership." }
    require(value.components.size in 1..50 && value.components.sumOf { it.dice.size } <= 2000)
    require(value.components.all { component -> component.label.length <= 200 && component.modifier in -1_000_000..1_000_000 &&
        component.dice.all { it.sides in 2..1000 && it.face in 1..it.sides && it.sign in setOf(-1, 1) } }) { "Invalid recorded dice." }
}
