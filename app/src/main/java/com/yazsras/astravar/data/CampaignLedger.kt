package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * Separate, offline-only store for the Campaign Inventory Beta.
 * Deliberately does not touch v1.7.0's existing AstravarDb (Room schema 3).
 * This is NOT automatically synchronized with Notion, Cloudflare or ChatGPT.
 */
@Serializable
@Entity(tableName = "ledger_items", indices = [Index(value = ["title"])])
data class LedgerItem(
    @PrimaryKey val id: String,
    val title: String,
    val category: String = "Other",
    val owner: String = "Unknown",
    val itemState: String = "Unknown",
    val location: String = "Unknown",
    val evidenceStatus: String = "Unverified",
    val quantity: Int? = null, // null = unknown, never inferred as zero
    val description: String = "",
    val knownProperties: String = "",
    val possibleUses: String = "",
    val limitations: String = "",
    val source: String = "",
    val notionPageId: String? = null,
    val imageBase64: String? = null, // <= 512px compressed thumbnail, travels with JSON export
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val archived: Boolean = false
)

@Serializable
@Entity(tableName = "ledger_events", indices = [Index(value = ["itemId"]), Index(value = ["createdAt"])])
data class LedgerEvent(
    @PrimaryKey val commandId: String,
    val itemId: String,
    val kind: String,
    val evidenceStatus: String,
    val detail: String,
    val source: String,
    val payloadFingerprint: String,
    val priorRevision: Long,
    val resultingRevision: Long,
    val createdAt: Long,
    val beforeJson: String? = null,
    val afterJson: String? = null
)

@Serializable
data class LedgerDraft(
    val title: String,
    val category: String = "Other",
    val owner: String = "Unknown",
    val itemState: String = "Unknown",
    val location: String = "Unknown",
    val evidenceStatus: String = "Unverified",
    val quantity: Int? = null,
    val description: String = "",
    val knownProperties: String = "",
    val possibleUses: String = "",
    val limitations: String = "",
    val source: String = "",
    val notionPageId: String? = null,
    val imageBase64: String? = null,
    val archived: Boolean = false
)

@Serializable
data class LedgerExport(
    val format: String = "astravar-campaign-items",
    val schema: Int = 4,
    val exportedAt: Long,
    val items: List<LedgerItem>,
    val events: List<LedgerEvent>,
    val records: CampaignRecordsExport = CampaignRecordsExport(),
    val archive: CampaignArchiveState = CampaignArchiveState()
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_items WHERE archived=0 ORDER BY title COLLATE NOCASE ASC")
    fun observeItems(): Flow<List<LedgerItem>>

    @Query("SELECT * FROM ledger_items WHERE id=:id")
    suspend fun getItem(id: String): LedgerItem?

    @Query("SELECT * FROM ledger_items ORDER BY id ASC")
    suspend fun allItems(): List<LedgerItem>

    @Query("SELECT * FROM ledger_events ORDER BY createdAt ASC, commandId ASC")
    suspend fun allEvents(): List<LedgerEvent>

    @Query("SELECT * FROM ledger_events WHERE itemId=:itemId ORDER BY createdAt DESC, commandId DESC")
    fun eventsForItem(itemId: String): Flow<List<LedgerEvent>>

    @Query("SELECT * FROM ledger_events WHERE commandId=:commandId")
    suspend fun event(commandId: String): LedgerEvent?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: LedgerItem)

    @Update
    suspend fun updateItem(item: LedgerItem)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: LedgerEvent)
}

@Database(entities = [LedgerItem::class, LedgerEvent::class,
    InventionProject::class, ProjectStage::class, MaterialRequirement::class,
    ConnectionEntity::class, ConnectionFact::class, ConnectionMilestone::class,
    CompanionState::class, CompanionResource::class, ItemDetails::class, ItemProperty::class,
    CharacterProfile::class, CharacterAbility::class,
    CampaignLink::class, CampaignAudit::class, CommandReceipt::class,
    CraftExecution::class, CraftConsumption::class, PartySheetRecord::class, ItemPlacement::class,
    PartyWallet::class, CampaignCheckpoint::class, ExternalRecordReference::class,
    CampaignReferenceRecord::class, CampaignReferenceRelation::class, CampaignImportReceipt::class,
    CampaignImportBackup::class, CampaignArchiveSource::class], version = 4, exportSchema = true)
@TypeConverters(PartyConverters::class)
abstract class CampaignLedgerDb : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun recordsDao(): CampaignRecordsDao
    abstract fun partyDao(): PartyRecordsDao
    abstract fun archiveDao(): CampaignArchiveDao

    companion object {
        fun open(context: Context): CampaignLedgerDb = Room.databaseBuilder(
            context.applicationContext, CampaignLedgerDb::class.java, "astravar-campaign-ledger.db"
        ).enableMultiInstanceInvalidation()
            .addMigrations(CAMPAIGN_MIGRATION_1_2, CAMPAIGN_MIGRATION_2_3, CAMPAIGN_MIGRATION_3_4).build() // NEVER fallbackToDestructiveMigration()
    }
}

class CampaignLedgerRepository(private val db: CampaignLedgerDb) {
    private val dao = db.ledgerDao()
    val records = CampaignRecordsRepository(db)
    val party = PartyRecordsRepository(db)
    val archive = CampaignArchiveRepository(db)
    val items: Flow<List<LedgerItem>> = dao.observeItems()
    fun eventsForItem(itemId: String): Flow<List<LedgerEvent>> = dao.eventsForItem(itemId)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private fun fingerprint(vararg fields: String): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(fields.toList()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun validate(input: LedgerDraft): LedgerDraft {
        val clean = input.copy(
            title = input.title.trim(),
            category = input.category.trim().ifEmpty { "Other" },
            owner = input.owner.trim().ifEmpty { "Unknown" },
            itemState = input.itemState.trim().ifEmpty { "Unknown" },
            location = input.location.trim().ifEmpty { "Unknown" },
            evidenceStatus = input.evidenceStatus.trim().ifEmpty { "Unverified" },
            notionPageId = input.notionPageId?.trim()?.ifBlank { null }
        )
        require(clean.title.length in 1..120) { "Item name must contain 1–120 characters." }
        require(clean.quantity == null || clean.quantity in 0..1_000_000) { "Invalid item quantity." }
        require(clean.description.length <= 16000 && clean.knownProperties.length <= 16000 &&
            clean.possibleUses.length <= 16000 && clean.limitations.length <= 16000) { "One of the notes is too long." }
        require(clean.source.length <= 2000 && clean.location.length <= 200 && clean.owner.length <= 120)
        require(clean.itemState in setOf("Carried", "Equipped", "Installed", "Stored", "Consumed", "Sold", "Lost", "Unknown"))
        require(clean.evidenceStatus in setOf("Confirmed", "Unverified", "Proposed", "Disputed"))
        require(clean.notionPageId == null || clean.notionPageId.length <= 160)
        require(clean.imageBase64 == null || clean.imageBase64.length <= 1_000_000) { "Image is too large." }
        return clean
    }

    private fun LedgerItem.draft() = LedgerDraft(
        title, category, owner, itemState, location, evidenceStatus, quantity, description,
        knownProperties, possibleUses, limitations, source,
        notionPageId, imageBase64, archived
    )

    /** Repeating the *same* command is safe; reusing its ID with different content is rejected. */
    suspend fun create(input: LedgerDraft, commandId: String = UUID.randomUUID().toString()): LedgerItem = db.withTransaction {
        val clean = validate(input)
        require(commandId.isNotBlank() && commandId.length <= 120)
        val fp = fingerprint("CREATE", json.encodeToString(clean))
        val previous = dao.event(commandId)
        if (previous != null) {
            require(previous.kind == "CREATE" && previous.payloadFingerprint == fp) { "Command ID collision; item not created." }
            return@withTransaction originalOutcome(previous)
        }
        require(db.recordsDao().receipt(commandId) == null) { "Command ID collision; item not created." }
        val time = System.currentTimeMillis()
        val item = LedgerItem(
            id = UUID.randomUUID().toString(), title = clean.title, category = clean.category,
            owner = clean.owner, itemState = clean.itemState, location = clean.location,
            evidenceStatus = clean.evidenceStatus, quantity = clean.quantity, description = clean.description,
            knownProperties = clean.knownProperties, possibleUses = clean.possibleUses,
            limitations = clean.limitations, source = clean.source, notionPageId = clean.notionPageId,
            imageBase64 = clean.imageBase64, createdAt = time, updatedAt = time,
            revision = 1, archived = clean.archived
        )
        dao.insertItem(item)
        dao.insertEvent(LedgerEvent(commandId, item.id, "CREATE", "Player entry", "Item record created",
            clean.source, fp, 0, 1, time, null, json.encodeToString(item)))
        recordReceipt(commandId, "CREATE", fp, null, item, clean.source)
        item
    }

    /** Requires the revision observed by the editor: stale edits cannot overwrite newer discoveries. */
    suspend fun update(
        itemId: String,
        expectedRevision: Long,
        input: LedgerDraft,
        source: String,
        commandId: String = UUID.randomUUID().toString()
    ): LedgerItem = db.withTransaction {
        val clean = validate(input)
        require(commandId.isNotBlank() && commandId.length <= 120)
        require(source.length <= 2000)
        val fp = fingerprint("UPDATE", itemId, expectedRevision.toString(), json.encodeToString(clean), source)
        val oldEvent = dao.event(commandId)
        if (oldEvent != null) {
            require(oldEvent.kind == "UPDATE" && oldEvent.itemId == itemId && oldEvent.payloadFingerprint == fp) { "Command ID collision; edit rejected." }
            return@withTransaction originalOutcome(oldEvent)
        }
        require(db.recordsDao().receipt(commandId) == null) { "Command ID collision; edit rejected." }
        val before = dao.getItem(itemId) ?: error("This item no longer exists.")
        check(before.revision == expectedRevision) { "Item changed since editing began. Reopen it before saving." }
        db.partyDao().placement(itemId)?.let {
            require(clean.owner == before.owner && clean.itemState == before.itemState && clean.location == before.location && clean.quantity == before.quantity) {
                "This item has structured ownership. Use equipment transfer or quantity commands to change its owner, state, location or amount."
            }
        }
        if (clean.archived || clean.itemState in setOf("Consumed", "Sold", "Lost") || clean.quantity == 0) {
            require(db.partyDao().allPlacements().none { it.containerItemId == itemId }) { "Remove this container's contents before making it unavailable." }
        }
        val after = before.copy(
            title = clean.title, category = clean.category, owner = clean.owner,
            itemState = clean.itemState, location = clean.location, evidenceStatus = clean.evidenceStatus,
            quantity = clean.quantity, description = clean.description, knownProperties = clean.knownProperties,
            possibleUses = clean.possibleUses, limitations = clean.limitations, source = clean.source,
            notionPageId = clean.notionPageId, imageBase64 = clean.imageBase64, archived = clean.archived,
            updatedAt = System.currentTimeMillis(), revision = before.revision + 1
        )
        dao.updateItem(after)
        dao.insertEvent(LedgerEvent(commandId, itemId, "UPDATE", "Player entry", "Item profile edited",
            source, fp, before.revision, after.revision, after.updatedAt,
            json.encodeToString(before), json.encodeToString(after)))
        recordReceipt(commandId, "UPDATE", fp, before, after, source)
        after
    }

    private fun append(existing: String, detail: String): String =
        if (existing.isBlank()) detail else "$existing\n• $detail"

    /** Discoveries keep a separate append-only event AND update the item's current summary. */
    suspend fun discover(
        itemId: String,
        expectedRevision: Long,
        type: String,
        detail: String,
        source: String,
        commandId: String = UUID.randomUUID().toString()
    ): LedgerItem = db.withTransaction {
        require(type in setOf("Confirmed", "Possible use", "Limitation", "Correction"))
        val note = detail.trim()
        require(note.isNotBlank() && note.length <= 3000) { "Discovery must contain 1–3,000 characters." }
        require(source.length <= 2000)
        require(commandId.isNotBlank() && commandId.length <= 120)
        val fp = fingerprint("DISCOVERY", itemId, expectedRevision.toString(), type, note, source)
        val previous = dao.event(commandId)
        if (previous != null) {
            require(previous.itemId == itemId && previous.kind == "DISCOVERY" && previous.payloadFingerprint == fp) { "Command ID collision; discovery rejected." }
            return@withTransaction originalOutcome(previous)
        }
        require(db.recordsDao().receipt(commandId) == null) { "Command ID collision; discovery rejected." }
        val before = dao.getItem(itemId) ?: error("Item not found.")
        check(before.revision == expectedRevision) { "A newer item update exists. Reopen this item and review it." }
        val after = when (type) {
            "Possible use" -> before.copy(possibleUses = append(before.possibleUses, note))
            "Limitation" -> before.copy(limitations = append(before.limitations, note))
            "Correction" -> before.copy(knownProperties = append(before.knownProperties, "Correction: $note"))
            else -> before.copy(knownProperties = append(before.knownProperties, note))
        }.copy(revision = before.revision + 1, updatedAt = System.currentTimeMillis())
        validate(after.draft())
        dao.updateItem(after)
        dao.insertEvent(LedgerEvent(commandId, itemId, "DISCOVERY", type, note, source,
            fp, before.revision, after.revision, after.updatedAt,
            json.encodeToString(before), json.encodeToString(after)))
        recordReceipt(commandId, "DISCOVERY", fp, before, after, source)
        after
    }

    /** Player-controlled JSON export includes compact embedded item icons and the full audit history. */
    suspend fun export(): String = db.withTransaction {
        campaignBackupJson(LedgerExport(exportedAt = System.currentTimeMillis(),
            items = dao.allItems(), events = dao.allEvents(), records = records.snapshot(), archive = archive.snapshot()))
    }

    /** Historical compatibility export only: does not include new inventions/connections/companion records. */
    suspend fun exportLegacy(): String = db.withTransaction {
        val value = LedgerExport(schema = 1, exportedAt = System.currentTimeMillis(), items = dao.allItems(), events = dao.allEvents())
        val objectValue = json.encodeToJsonElement(LedgerExport.serializer(), value) as kotlinx.serialization.json.JsonObject
        supportedCampaignBackup(kotlinx.serialization.json.JsonObject(objectValue.filterKeys { it != "records" && it != "archive" }).toString())
    }

    /** Import preview is read-only; restore never replaces an existing different record. */
    suspend fun previewImport(text: String): ImportPreview = CampaignBackup(db).preview(text)
    suspend fun restore(preview: ImportPreview, commandId: String = UUID.randomUUID().toString()): CommandReceipt =
        CampaignBackup(db).restore(preview, commandId)

    private fun originalOutcome(event: LedgerEvent): LedgerItem =
        event.afterJson?.let { json.decodeFromString<LedgerItem>(it) }
            ?: error("Original command outcome is unavailable. Review history before retrying.")

    private suspend fun recordReceipt(commandId: String, kind: String, fp: String, before: LedgerItem?, after: LedgerItem, source: String) {
        val out = json.encodeToString(after)
        db.recordsDao().insert(CommandReceipt(commandId, fp, kind, after.id, after.revision, after.updatedAt, outcomeJson = out))
        db.recordsDao().insert(CampaignAudit(commandId, commandId, "item", after.id, kind, source,
            before?.revision ?: 0, after.revision, after.updatedAt, before?.let { json.encodeToString(it) }, out))
    }
}
