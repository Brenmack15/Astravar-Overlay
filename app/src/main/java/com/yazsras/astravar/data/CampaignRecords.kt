package com.yazsras.astravar.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** Local player-entered records only. Nothing in this database is automatically uploaded. */
@Serializable @Entity(tableName = "invention_projects")
data class InventionProject(
    val title: String, val description: String = "", val status: String = "Proposed",
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "project_stages", foreignKeys = [ForeignKey(
    entity = InventionProject::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("projectId")])
data class ProjectStage(
    val projectId: String, val title: String, val notes: String = "", val status: String = "Planned", val sortOrder: Int = 0,
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "material_requirements", foreignKeys = [ForeignKey(
    entity = InventionProject::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT
), ForeignKey(entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("projectId"), Index("itemId")])
data class MaterialRequirement(
    val projectId: String, val label: String, val itemId: String? = null, val quantity: Int? = null, val notes: String = "",
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "connection_entities")
data class ConnectionEntity(
    val name: String, val kind: String = "Person", val notes: String = "", val aliases: String = "",
    val relationship: String = "", val imageBase64: String? = null,
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "connection_facts", foreignKeys = [ForeignKey(
    entity = ConnectionEntity::class, parentColumns = ["id"], childColumns = ["entityId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("entityId")])
data class ConnectionFact(
    val entityId: String, val detail: String, val category: String = "Known information", val evidenceStatus: String = "Unverified",
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "connection_milestones", foreignKeys = [ForeignKey(
    entity = ConnectionEntity::class, parentColumns = ["id"], childColumns = ["entityId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("entityId")])
data class ConnectionMilestone(
    val entityId: String, val title: String, val detail: String = "",
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "companion_states")
data class CompanionState(
    val name: String = "Eek", val condition: String = "Unknown", val notes: String = "",
    @PrimaryKey val companionId: String = "eek", val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "companion_resources", foreignKeys = [ForeignKey(
    entity = CompanionState::class, parentColumns = ["companionId"], childColumns = ["companionId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("companionId"), Index(value = ["companionId", "name"], unique = true)])
data class CompanionResource(
    val name: String, val companionId: String = "eek", val current: Int? = null, val maximum: Int? = null,
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

/** Optional manually confirmed display values. Independent of the validated combat Profile. */
@Serializable @Entity(tableName = "character_profiles")
data class CharacterProfile(
    val name: String = "", val currentHp: Int? = null, val maximumHp: Int? = null,
    val temporaryHp: Int? = null, val armorClass: Int? = null, val level: Int? = null,
    @PrimaryKey val id: String = "local-player", val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "character_abilities", primaryKeys = ["profileId", "key"], foreignKeys = [ForeignKey(
    entity = CharacterProfile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("profileId")])
data class CharacterAbility(
    val key: String, val profileId: String = "local-player", val score: Int? = null,
    val magicModifier: Int = 0, val notes: String = "", val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
) {
    @get:Ignore val id: String get() = "$profileId:$key"
    @get:Ignore val baseModifier: Int? get() = score?.let { Math.floorDiv(it - 10, 2) }
    @get:Ignore val totalModifier: Int? get() = baseModifier?.plus(magicModifier)
}

@Serializable @Entity(tableName = "item_details", foreignKeys = [ForeignKey(
    entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT
)])
data class ItemDetails(
    @PrimaryKey val itemId: String, val weightGrams: Long? = null, val notes: String = "", val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "item_properties", foreignKeys = [ForeignKey(
    entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("itemId")])
data class ItemProperty(
    val itemId: String, val label: String, val value: String, val evidenceStatus: String = "Unverified",
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

/** Links refer to an existing item/project/connection/companion; they never copy the physical item. */
@Serializable @Entity(tableName = "campaign_links", indices = [Index(value = ["fromType", "fromId"]), Index(value = ["toType", "toId"])])
data class CampaignLink(
    val fromType: String, val fromId: String, val toType: String, val toId: String, val label: String,
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "campaign_audit", indices = [Index(value = ["recordType", "recordId"]), Index("commandId")])
data class CampaignAudit(
    @PrimaryKey val id: String, val commandId: String, val recordType: String, val recordId: String,
    val kind: String, val source: String, val priorRevision: Long, val resultingRevision: Long,
    val createdAt: Long, val beforeJson: String? = null, val afterJson: String? = null
)

@Serializable @Entity(tableName = "command_receipts")
data class CommandReceipt(
    @PrimaryKey val commandId: String, val fingerprint: String, val kind: String, val recordId: String,
    val resultingRevision: Long, val createdAt: Long, val outputId: String? = null, val outcomeJson: String = ""
)

@Serializable @Entity(tableName = "craft_executions", foreignKeys = [ForeignKey(
    entity = InventionProject::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT
), ForeignKey(entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["outputItemId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("projectId"), Index("outputItemId")])
data class CraftExecution(
    @PrimaryKey val commandId: String, val projectId: String, val outputItemId: String, val source: String, val createdAt: Long
)

@Serializable @Entity(tableName = "craft_consumptions", foreignKeys = [ForeignKey(
    entity = CraftExecution::class, parentColumns = ["commandId"], childColumns = ["executionId"], onDelete = ForeignKey.RESTRICT
), ForeignKey(entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("executionId"), Index("itemId")])
data class CraftConsumption(
    @PrimaryKey val id: String, val executionId: String, val itemId: String, val quantity: Int,
    val beforeQuantity: Int, val afterQuantity: Int, val priorRevision: Long, val resultingRevision: Long
)

@Serializable data class MaterialConsumption(val itemId: String, val expectedRevision: Long, val quantity: Int)
@Serializable data class CraftCommand(
    val projectId: String, val expectedProjectRevision: Long, val materials: List<MaterialConsumption>,
    val output: LedgerDraft, val source: String, val commandId: String = UUID.randomUUID().toString()
)

@Serializable data class CampaignRecordsExport(
    val projects: List<InventionProject> = emptyList(), val stages: List<ProjectStage> = emptyList(),
    val requirements: List<MaterialRequirement> = emptyList(), val entities: List<ConnectionEntity> = emptyList(),
    val facts: List<ConnectionFact> = emptyList(), val milestones: List<ConnectionMilestone> = emptyList(),
    val links: List<CampaignLink> = emptyList(), val companionStates: List<CompanionState> = emptyList(),
    val companionResources: List<CompanionResource> = emptyList(), val itemDetails: List<ItemDetails> = emptyList(),
    val itemProperties: List<ItemProperty> = emptyList(), val audit: List<CampaignAudit> = emptyList(),
    val receipts: List<CommandReceipt> = emptyList(), val crafts: List<CraftExecution> = emptyList(),
    val consumptions: List<CraftConsumption> = emptyList(),
    val characterProfiles: List<CharacterProfile> = emptyList(), val characterAbilities: List<CharacterAbility> = emptyList(),
    val partySheets: List<PartySheetRecord> = emptyList(), val placements: List<ItemPlacement> = emptyList(),
    val wallets: List<PartyWallet> = emptyList(), val checkpoints: List<CampaignCheckpoint> = emptyList(),
    val externalReferences: List<ExternalRecordReference> = emptyList()
)

data class ImportPreview(
    val valid: Boolean, val summary: String, val conflicts: List<String>, val fingerprint: String,
    val currentFingerprint: String, val backup: LedgerExport? = null
)

internal val campaignJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
internal fun campaignHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Dao
interface CampaignRecordsDao {
    @Query("SELECT * FROM invention_projects ORDER BY title COLLATE NOCASE") fun projects(): Flow<List<InventionProject>>
    @Query("SELECT * FROM invention_projects ORDER BY id") suspend fun allProjects(): List<InventionProject>
    @Query("SELECT * FROM invention_projects WHERE id=:id") suspend fun project(id: String): InventionProject?
    @Query("SELECT * FROM project_stages WHERE projectId=:projectId ORDER BY sortOrder,id") fun stagesFor(projectId: String): Flow<List<ProjectStage>>
    @Query("SELECT * FROM project_stages ORDER BY id") suspend fun allStages(): List<ProjectStage>
    @Query("SELECT * FROM project_stages WHERE id=:id") suspend fun stage(id: String): ProjectStage?
    @Query("SELECT * FROM material_requirements WHERE projectId=:projectId ORDER BY label,id") fun requirementsFor(projectId: String): Flow<List<MaterialRequirement>>
    @Query("SELECT * FROM material_requirements ORDER BY id") suspend fun allRequirements(): List<MaterialRequirement>
    @Query("SELECT * FROM material_requirements WHERE id=:id") suspend fun requirement(id: String): MaterialRequirement?
    @Query("SELECT * FROM connection_entities ORDER BY name COLLATE NOCASE") fun entities(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connection_entities ORDER BY id") suspend fun allEntities(): List<ConnectionEntity>
    @Query("SELECT * FROM connection_entities WHERE id=:id") suspend fun entity(id: String): ConnectionEntity?
    @Query("SELECT * FROM connection_facts ORDER BY updatedAt DESC,id") fun facts(): Flow<List<ConnectionFact>>
    @Query("SELECT * FROM connection_facts ORDER BY id") suspend fun allFacts(): List<ConnectionFact>
    @Query("SELECT * FROM connection_facts WHERE id=:id") suspend fun fact(id: String): ConnectionFact?
    @Query("SELECT * FROM connection_milestones ORDER BY updatedAt DESC,id") fun milestones(): Flow<List<ConnectionMilestone>>
    @Query("SELECT * FROM connection_milestones ORDER BY id") suspend fun allMilestones(): List<ConnectionMilestone>
    @Query("SELECT * FROM connection_milestones WHERE id=:id") suspend fun milestone(id: String): ConnectionMilestone?
    @Query("SELECT * FROM companion_states ORDER BY companionId") fun companionStates(): Flow<List<CompanionState>>
    @Query("SELECT * FROM companion_states ORDER BY companionId") suspend fun allCompanionStates(): List<CompanionState>
    @Query("SELECT * FROM companion_states WHERE companionId=:id") suspend fun companion(id: String): CompanionState?
    @Query("SELECT * FROM companion_resources ORDER BY name,id") fun companionResources(): Flow<List<CompanionResource>>
    @Query("SELECT * FROM companion_resources ORDER BY id") suspend fun allCompanionResources(): List<CompanionResource>
    @Query("SELECT * FROM companion_resources WHERE id=:id") suspend fun resource(id: String): CompanionResource?
    @Query("SELECT * FROM character_profiles ORDER BY id") fun characterProfiles(): Flow<List<CharacterProfile>>
    @Query("SELECT * FROM character_profiles ORDER BY id") suspend fun allCharacterProfiles(): List<CharacterProfile>
    @Query("SELECT * FROM character_profiles WHERE id=:id") suspend fun characterProfile(id: String): CharacterProfile?
    @Query("SELECT * FROM character_abilities ORDER BY profileId,`key`") fun characterAbilities(): Flow<List<CharacterAbility>>
    @Query("SELECT * FROM character_abilities ORDER BY profileId,`key`") suspend fun allCharacterAbilities(): List<CharacterAbility>
    @Query("SELECT * FROM character_abilities WHERE profileId=:profileId AND `key`=:key")
    suspend fun characterAbility(profileId: String, key: String): CharacterAbility?
    @Query("SELECT * FROM item_details ORDER BY itemId") fun itemDetails(): Flow<List<ItemDetails>>
    @Query("SELECT * FROM item_details ORDER BY itemId") suspend fun allItemDetails(): List<ItemDetails>
    @Query("SELECT * FROM item_details WHERE itemId=:id") suspend fun details(id: String): ItemDetails?
    @Query("SELECT * FROM item_properties ORDER BY label,id") fun itemProperties(): Flow<List<ItemProperty>>
    @Query("SELECT * FROM item_properties ORDER BY id") suspend fun allItemProperties(): List<ItemProperty>
    @Query("SELECT * FROM item_properties WHERE id=:id") suspend fun property(id: String): ItemProperty?
    @Query("SELECT * FROM campaign_links ORDER BY label,id") fun links(): Flow<List<CampaignLink>>
    @Query("SELECT * FROM campaign_links ORDER BY id") suspend fun allLinks(): List<CampaignLink>
    @Query("SELECT * FROM campaign_links WHERE id=:id") suspend fun link(id: String): CampaignLink?
    @Query("SELECT * FROM campaign_audit ORDER BY createdAt DESC,id") fun audit(): Flow<List<CampaignAudit>>
    @Query("SELECT * FROM campaign_audit ORDER BY id") suspend fun allAudit(): List<CampaignAudit>
    @Query("SELECT * FROM command_receipts WHERE commandId=:id") suspend fun receipt(id: String): CommandReceipt?
    @Query("SELECT * FROM command_receipts ORDER BY commandId") suspend fun allReceipts(): List<CommandReceipt>
    @Query("SELECT * FROM craft_executions ORDER BY commandId") suspend fun allCrafts(): List<CraftExecution>
    @Query("SELECT * FROM craft_consumptions ORDER BY id") suspend fun allConsumptions(): List<CraftConsumption>
    @Upsert suspend fun put(value: InventionProject)
    @Upsert suspend fun put(value: ProjectStage)
    @Upsert suspend fun put(value: MaterialRequirement)
    @Upsert suspend fun put(value: ConnectionEntity)
    @Upsert suspend fun put(value: ConnectionFact)
    @Upsert suspend fun put(value: ConnectionMilestone)
    @Upsert suspend fun put(value: CompanionState)
    @Upsert suspend fun put(value: CompanionResource)
    @Upsert suspend fun put(value: CharacterProfile)
    @Upsert suspend fun put(value: CharacterAbility)
    @Upsert suspend fun put(value: ItemDetails)
    @Upsert suspend fun put(value: ItemProperty)
    @Upsert suspend fun put(value: CampaignLink)
    @Insert suspend fun insert(value: CampaignAudit)
    @Insert suspend fun insert(value: CommandReceipt)
    @Insert suspend fun insert(value: CraftExecution)
    @Insert suspend fun insert(value: CraftConsumption)
}

class CampaignRecordsRepository(private val db: CampaignLedgerDb) {
    private val dao = db.recordsDao()
    val projects = dao.projects()
    val entities = dao.entities()
    val facts = dao.facts()
    val milestones = dao.milestones()
    val companionStates = dao.companionStates()
    val companionResources = dao.companionResources()
    val characterProfiles = dao.characterProfiles()
    val characterAbilities = dao.characterAbilities()
    val itemDetails = dao.itemDetails()
    val itemProperties = dao.itemProperties()
    val links = dao.links()
    val audit = dao.audit()
    fun stagesFor(projectId: String) = dao.stagesFor(projectId)
    fun requirementsFor(projectId: String) = dao.requirementsFor(projectId)
    suspend fun receipt(commandId: String) = dao.receipt(commandId)

    private fun text(value: String, max: Int = 16000, required: Boolean = false) {
        require(value.length <= max && (!required || value.isNotBlank())) { "A required field is blank or too long." }
    }
    private fun evidence(value: String) { require(value in setOf("Confirmed", "Unverified", "Proposed", "Disputed")) }
    private fun id(value: String, max: Int = 120) { text(value, max, true) }
    private fun source(value: String) { text(value, 2000, true) }
    private suspend fun endpoint(type: String, value: String): Boolean = when (type) {
        "item" -> db.ledgerDao().getItem(value) != null
        "project" -> dao.project(value) != null
        "connection" -> dao.entity(value) != null
        "companion" -> dao.companion(value) != null
        "character-profile" -> dao.characterProfile(value) != null
        else -> false
    }

    /** Caller runs inside one Room transaction. The persisted receipt is the ORIGINAL outcome. */
    private suspend fun save(
        kind: String, recordId: String, expected: Long?, oldRevision: Long?, before: String?, payload: String,
        provenance: String, commandId: String, write: suspend (Long, Long) -> String
    ): CommandReceipt {
        id(recordId, 180); id(commandId); source(provenance)
        val fp = campaignHash(campaignJson.encodeToString(listOf("v2", kind, recordId, expected?.toString(), payload, provenance)))
        dao.receipt(commandId)?.let { require(it.fingerprint == fp) { "Command ID collision. No changes saved." }; return it }
        require(db.ledgerDao().event(commandId) == null) { "Command ID already belongs to an inventory operation." }
        check(oldRevision == expected) { "A newer record exists. Reopen it before saving." }
        val revision = (oldRevision ?: 0) + 1
        val now = System.currentTimeMillis()
        val after = write(revision, now)
        dao.insert(CampaignAudit(commandId, commandId, kind, recordId, if (oldRevision == null) "CREATE" else "UPDATE",
            provenance, oldRevision ?: 0, revision, now, before, after))
        return CommandReceipt(commandId, fp, kind, recordId, revision, now, outcomeJson = after).also { dao.insert(it) }
    }

    suspend fun saveProject(value: InventionProject, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.title, 120, true); text(value.description)
        require(value.status in setOf("Proposed", "Research", "Blueprint", "Prototype", "Construction", "Completed", "Paused"))
        val old = dao.project(value.id)
        save("project", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveStage(value: ProjectStage, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.title, 120, true); text(value.notes); require(value.sortOrder >= 0)
        require(value.status in setOf("Planned", "In progress", "Completed", "Blocked"))
        val old = dao.stage(value.id); require(old == null || old.projectId == value.projectId)
        save("stage", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveRequirement(value: MaterialRequirement, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.label, 120, true); text(value.notes); require(value.quantity == null || value.quantity in 1..1_000_000)
        val old = dao.requirement(value.id); require(old == null || old.projectId == value.projectId)
        save("requirement", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveEntity(value: ConnectionEntity, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.name, 120, true); text(value.notes); text(value.aliases, 2000); text(value.relationship, 3000)
        require(value.kind in setOf("Person", "Organization", "Faction", "Creature", "Other"))
        require(value.imageBase64 == null || value.imageBase64.length <= 1_000_000)
        val old = dao.entity(value.id)
        save("connection", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveFact(value: ConnectionFact, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.detail, 6000, true); text(value.category, 120, true); evidence(value.evidenceStatus)
        val old = dao.fact(value.id); require(old == null || old.entityId == value.entityId)
        save("fact", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveMilestone(value: ConnectionMilestone, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.title, 120, true); text(value.detail, 6000)
        val old = dao.milestone(value.id); require(old == null || old.entityId == value.entityId)
        save("milestone", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveCompanionState(value: CompanionState, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.name, 120, true); text(value.condition, 200, true); text(value.notes)
        val old = dao.companion(value.companionId)
        save("companion", value.companionId, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveCompanionResource(value: CompanionResource, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.name, 120, true)
        require(value.current == null || value.current in 0..1_000_000)
        require(value.maximum == null || value.maximum in 0..1_000_000)
        require(value.current == null || value.maximum == null || value.current <= value.maximum)
        val old = dao.resource(value.id); require(old == null || old.companionId == value.companionId)
        save("resource", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveItemDetails(value: ItemDetails, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        require(value.weightGrams == null || value.weightGrams in 0..1_000_000_000L); text(value.notes)
        val old = dao.details(value.itemId)
        save("item-details", value.itemId, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveProfile(value: CharacterProfile, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        id(value.id); text(value.name, 120)
        require(value.currentHp == null || value.currentHp in 0..1_000_000)
        require(value.maximumHp == null || value.maximumHp in 0..1_000_000)
        require(value.temporaryHp == null || value.temporaryHp in 0..1_000_000)
        require(value.currentHp == null || value.maximumHp == null || value.currentHp <= value.maximumHp)
        require(value.armorClass == null || value.armorClass in 0..1000)
        require(value.level == null || value.level in 1..1000)
        val old = dao.characterProfile(value.id)
        save("character-profile", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    /** Magic modifiers are descriptive and never alter weapon/overlay calculations. */
    suspend fun saveAbility(value: CharacterAbility, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        id(value.profileId)
        require(value.key in setOf("STR", "DEX", "CON", "INT", "WIS", "CHA"))
        require(value.score == null || value.score in 0..1000)
        require(value.magicModifier in -1000..1000); text(value.notes, 3000)
        val old = dao.characterAbility(value.profileId, value.key)
        save("character-ability", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveItemProperty(value: ItemProperty, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.label, 120, true); text(value.value, 6000, true); evidence(value.evidenceStatus)
        val old = dao.property(value.id); require(old == null || old.itemId == value.itemId)
        save("item-property", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    suspend fun saveLink(value: CampaignLink, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        text(value.label, 120, true)
        require(endpoint(value.fromType, value.fromId) && endpoint(value.toType, value.toId)) { "Link targets must already exist." }
        require(value.fromType != value.toType || value.fromId != value.toId) { "A record cannot link to itself." }
        val old = dao.link(value.id)
        save("link", value.id, expectedRevision, old?.revision, old?.let { campaignJson.encodeToString(it) },
            campaignJson.encodeToString(value.copy(revision = 0, source = "", updatedAt = 0)), source, commandId) { rev, now ->
            value.copy(revision = rev, source = source, updatedAt = now).let { dao.put(it); campaignJson.encodeToString(it) }
        }
    }

    /** Only records an already confirmed result. Does not roll, advance time or infer a recipe. */
    suspend fun confirmCraft(command: CraftCommand): CommandReceipt = db.withTransaction {
        id(command.commandId); source(command.source)
        val fp = campaignHash(campaignJson.encodeToString(command.copy(commandId = "")))
        dao.receipt(command.commandId)?.let { require(it.fingerprint == fp) { "Command ID collision." }; return@withTransaction it }
        require(db.ledgerDao().event(command.commandId) == null) { "Command ID collision." }
        val project = dao.project(command.projectId) ?: error("Invention no longer exists.")
        check(project.revision == command.expectedProjectRevision) { "Invention changed. Review it before recording construction." }
        require(command.materials.isNotEmpty()) { "Select the actual materials consumed." }
        require(command.materials.map { it.itemId }.distinct().size == command.materials.size) { "A material can appear only once." }
        require(command.output.quantity != null && command.output.quantity in 1..1_000_000) { "Confirm the actual output quantity." }
        val inventory = db.ledgerDao()
        val outputCommand = "craft-output-${campaignHash(command.commandId).take(40)}"
        val linkCommand = "craft-link-${campaignHash(command.commandId).take(40)}"
        val materialCommands = command.materials.indices.map { "craft-${campaignHash(command.commandId).take(40)}-$it" }
        for (child in materialCommands + outputCommand + linkCommand) {
            require(inventory.event(child) == null && dao.receipt(child) == null) { "A craft child command already exists. No materials were consumed." }
        }
        val before = command.materials.map { use ->
            require(use.quantity > 0)
            val item = inventory.getItem(use.itemId) ?: error("Material no longer exists.")
            check(item.revision == use.expectedRevision) { "Material changed. Review quantities first." }
            require(!item.archived && item.itemState !in setOf("Consumed", "Sold", "Lost", "Installed")) { "Material is not available; detach installed components before consuming them." }
            require(item.quantity != null && item.quantity >= use.quantity) { "Material quantity is unknown or insufficient." }
            require(item.quantity != use.quantity || db.partyDao().allPlacements().none { it.containerItemId == item.id }) { "Remove a container's contents before consuming it for crafting." }
            item
        }
        val now = System.currentTimeMillis()
        val ledger = CampaignLedgerRepository(db)
        val output = ledger.create(command.output, outputCommand)
        dao.insert(CraftExecution(command.commandId, project.id, output.id, command.source, now))
        command.materials.zip(before).forEachIndexed { index, (use, item) ->
            val remaining = requireNotNull(item.quantity) - use.quantity
            val priorPlacement = db.partyDao().placement(item.id)
            val depleted = priorPlacement?.takeIf { remaining == 0 }?.let { depletedPlacement(it, command.source, now) }
            val after = item.copy(quantity = remaining, itemState = if (remaining == 0) "Consumed" else item.itemState,
                revision = item.revision + 1, updatedAt = now, location = depleted?.let(::placementLocation) ?: item.location)
            inventory.updateItem(after)
            inventory.insertEvent(LedgerEvent(materialCommands[index], item.id,
                "CRAFT_CONSUMPTION", "Confirmed", "Consumed ${use.quantity} for ${project.title}", command.source,
                fp, item.revision, after.revision, now, campaignJson.encodeToString(item), campaignJson.encodeToString(after)))
            if (depleted != null && priorPlacement != null) {
                db.partyDao().put(depleted)
                dao.insert(CampaignAudit("${command.commandId}:placement:$index", command.commandId, "item-placement", item.id,
                    "CRAFT_CONSUMPTION", command.source, priorPlacement.revision, depleted.revision, now,
                    campaignJson.encodeToString(priorPlacement), campaignJson.encodeToString(depleted)))
            }
            dao.insert(CraftConsumption("${command.commandId}:$index", command.commandId, item.id, use.quantity,
                requireNotNull(item.quantity), remaining, item.revision, after.revision))
            dao.insert(CampaignAudit("${command.commandId}:material:$index", command.commandId, "item", item.id,
                "CRAFT_CONSUMPTION", command.source, item.revision, after.revision, now,
                campaignJson.encodeToString(item), campaignJson.encodeToString(after)))
        }
        val completed = project.copy(status = "Completed", revision = project.revision + 1, source = command.source, updatedAt = now)
        dao.put(completed)
        saveLink(CampaignLink("project", project.id, "item", output.id, "Confirmed craft output",
            id = "craft-output-link-${campaignHash(command.commandId).take(40)}"), source = command.source,
            commandId = linkCommand)
        dao.insert(CampaignAudit(command.commandId, command.commandId, "project", project.id, "CONFIRMED_CRAFT",
            command.source, project.revision, completed.revision, now, campaignJson.encodeToString(project), campaignJson.encodeToString(completed)))
        CommandReceipt(command.commandId, fp, "CRAFT", project.id, completed.revision, now, output.id,
            campaignJson.encodeToString(completed)).also { dao.insert(it) }
    }

    internal suspend fun snapshot() = CampaignRecordsExport(dao.allProjects(), dao.allStages(), dao.allRequirements(),
        dao.allEntities(), dao.allFacts(), dao.allMilestones(), dao.allLinks(), dao.allCompanionStates(),
        dao.allCompanionResources(), dao.allItemDetails(), dao.allItemProperties(), dao.allAudit(), dao.allReceipts(),
        dao.allCrafts(), dao.allConsumptions(), dao.allCharacterProfiles(), dao.allCharacterAbilities(),
        db.partyDao().allSheets(), db.partyDao().allPlacements(), db.partyDao().allWallets(),
        db.partyDao().allCheckpoints(), db.partyDao().allReferences())
}

/** Explicit additive migration. All v1 item/event rows, values and Room schema remain intact. */
val CAMPAIGN_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        campaignV2CreateStatements.forEach(db::execSQL)
    }
}

internal val campaignV2CreateStatements = listOf(
    "CREATE TABLE IF NOT EXISTS invention_projects (title TEXT NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS project_stages (projectId TEXT NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL, status TEXT NOT NULL, sortOrder INTEGER NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(projectId) REFERENCES invention_projects(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_project_stages_projectId ON project_stages(projectId)",
    "CREATE TABLE IF NOT EXISTS material_requirements (projectId TEXT NOT NULL, label TEXT NOT NULL, itemId TEXT, quantity INTEGER, notes TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(projectId) REFERENCES invention_projects(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(itemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_material_requirements_projectId ON material_requirements(projectId)",
    "CREATE INDEX IF NOT EXISTS index_material_requirements_itemId ON material_requirements(itemId)",
    "CREATE TABLE IF NOT EXISTS connection_entities (name TEXT NOT NULL, kind TEXT NOT NULL, notes TEXT NOT NULL, aliases TEXT NOT NULL, relationship TEXT NOT NULL, imageBase64 TEXT, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS connection_facts (entityId TEXT NOT NULL, detail TEXT NOT NULL, category TEXT NOT NULL, evidenceStatus TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(entityId) REFERENCES connection_entities(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_connection_facts_entityId ON connection_facts(entityId)",
    "CREATE TABLE IF NOT EXISTS connection_milestones (entityId TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(entityId) REFERENCES connection_entities(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_connection_milestones_entityId ON connection_milestones(entityId)",
    "CREATE TABLE IF NOT EXISTS companion_states (name TEXT NOT NULL, condition TEXT NOT NULL, notes TEXT NOT NULL, companionId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS companion_resources (name TEXT NOT NULL, companionId TEXT NOT NULL, current INTEGER, maximum INTEGER, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(companionId) REFERENCES companion_states(companionId) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_companion_resources_companionId ON companion_resources(companionId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_companion_resources_companionId_name ON companion_resources(companionId,name)",
    "CREATE TABLE IF NOT EXISTS character_profiles (name TEXT NOT NULL, currentHp INTEGER, maximumHp INTEGER, temporaryHp INTEGER, armorClass INTEGER, level INTEGER, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS character_abilities (`key` TEXT NOT NULL, profileId TEXT NOT NULL, score INTEGER, magicModifier INTEGER NOT NULL, notes TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(profileId,`key`), FOREIGN KEY(profileId) REFERENCES character_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_character_abilities_profileId ON character_abilities(profileId)",
    "CREATE TABLE IF NOT EXISTS item_details (itemId TEXT NOT NULL PRIMARY KEY, weightGrams INTEGER, notes TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE TABLE IF NOT EXISTS item_properties (itemId TEXT NOT NULL, label TEXT NOT NULL, value TEXT NOT NULL, evidenceStatus TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_item_properties_itemId ON item_properties(itemId)",
    "CREATE TABLE IF NOT EXISTS campaign_links (fromType TEXT NOT NULL, fromId TEXT NOT NULL, toType TEXT NOT NULL, toId TEXT NOT NULL, label TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_campaign_links_fromType_fromId ON campaign_links(fromType,fromId)",
    "CREATE INDEX IF NOT EXISTS index_campaign_links_toType_toId ON campaign_links(toType,toId)",
    "CREATE TABLE IF NOT EXISTS campaign_audit (id TEXT NOT NULL PRIMARY KEY, commandId TEXT NOT NULL, recordType TEXT NOT NULL, recordId TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, priorRevision INTEGER NOT NULL, resultingRevision INTEGER NOT NULL, createdAt INTEGER NOT NULL, beforeJson TEXT, afterJson TEXT)",
    "CREATE INDEX IF NOT EXISTS index_campaign_audit_recordType_recordId ON campaign_audit(recordType,recordId)",
    "CREATE INDEX IF NOT EXISTS index_campaign_audit_commandId ON campaign_audit(commandId)",
    "CREATE TABLE IF NOT EXISTS command_receipts (commandId TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, kind TEXT NOT NULL, recordId TEXT NOT NULL, resultingRevision INTEGER NOT NULL, createdAt INTEGER NOT NULL, outputId TEXT, outcomeJson TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS craft_executions (commandId TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, outputItemId TEXT NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(projectId) REFERENCES invention_projects(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(outputItemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_craft_executions_projectId ON craft_executions(projectId)",
    "CREATE INDEX IF NOT EXISTS index_craft_executions_outputItemId ON craft_executions(outputItemId)",
    "CREATE TABLE IF NOT EXISTS craft_consumptions (id TEXT NOT NULL PRIMARY KEY, executionId TEXT NOT NULL, itemId TEXT NOT NULL, quantity INTEGER NOT NULL, beforeQuantity INTEGER NOT NULL, afterQuantity INTEGER NOT NULL, priorRevision INTEGER NOT NULL, resultingRevision INTEGER NOT NULL, FOREIGN KEY(executionId) REFERENCES craft_executions(commandId) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(itemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_craft_consumptions_executionId ON craft_consumptions(executionId)",
    "CREATE INDEX IF NOT EXISTS index_craft_consumptions_itemId ON craft_consumptions(itemId)"
)
