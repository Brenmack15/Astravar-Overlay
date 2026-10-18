package com.yazsras.astravar.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Descriptive, sourced character rules. Saving these records never executes a combat mechanic. */
@Serializable data class SheetMovement(val type: String, val feet: Int? = null, val notes: String = "")
@Serializable data class SheetModifier(val name: String, val modifier: Int? = null, val proficient: Boolean? = null, val notes: String = "")
@Serializable data class SheetCapability(
    val name: String, val detail: String, val kind: String = "Trait", val evidenceStatus: String = "Unverified",
    val source: String = "", val id: String = UUID.randomUUID().toString()
)
@Serializable data class SheetAttack(
    val name: String, val attackBonus: Int? = null, val damageFormula: String = "", val damageType: String = "",
    val range: String = "", val criticalRule: String = "", val actionEconomy: String = "Action",
    val resourceCost: String = "", val multiattack: String = "", val evidenceStatus: String = "Unverified",
    val source: String = "", val id: String = UUID.randomUUID().toString()
)
@Serializable data class SheetResource(val name: String, val current: Int? = null, val maximum: Int? = null, val notes: String = "")
@Serializable data class PartySheetData(
    val portraitBase64: String? = null, val creatureType: String = "", val size: String = "", val role: String = "",
    val progression: String = "", val proficiencyBonus: Int? = null, val initiative: Int? = null,
    val armorClassSources: String = "", val movement: List<SheetMovement> = emptyList(),
    val savingThrows: List<SheetModifier> = emptyList(), val skills: List<SheetModifier> = emptyList(),
    val senses: String = "", val languages: String = "", val resistances: String = "", val immunities: String = "",
    val vulnerabilities: String = "", val conditions: String = "", val capabilities: List<SheetCapability> = emptyList(),
    val attacks: List<SheetAttack> = emptyList(), val spellcasting: String = "", val resources: List<SheetResource> = emptyList(),
    val references: List<String> = emptyList(), val unresolvedConflicts: List<String> = emptyList()
)

@Serializable
@Entity(tableName = "party_sheets", foreignKeys = [ForeignKey(entity = CharacterProfile::class,
    parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.RESTRICT)])
data class PartySheetRecord(
    @PrimaryKey val profileId: String, val contentJson: String, val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0, val campaignId: String = "local-campaign"
) {
    @get:Ignore val sheet: PartySheetData get() = campaignJson.decodeFromString(contentJson)
}

/** Exactly one placement per existing physical item; containers are existing item identities. */
@Serializable
@Entity(tableName = "item_placements", foreignKeys = [
    ForeignKey(entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CharacterProfile::class, parentColumns = ["id"], childColumns = ["holderProfileId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = LedgerItem::class, parentColumns = ["id"], childColumns = ["containerItemId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = InventionProject::class, parentColumns = ["id"], childColumns = ["installedProjectId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("holderProfileId"), Index("containerItemId"), Index("installedProjectId")])
data class ItemPlacement(
    @PrimaryKey val itemId: String, val holderProfileId: String? = null, val containerItemId: String? = null,
    val installedProjectId: String? = null, val state: String = "Carried", val revision: Long = 1,
    val source: String = "", val updatedAt: Long = 0
)

@Serializable @Entity(tableName = "party_wallets", foreignKeys = [ForeignKey(entity = CharacterProfile::class,
    parentColumns = ["id"], childColumns = ["holderProfileId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("holderProfileId")])
data class PartyWallet(
    @PrimaryKey val id: String, val holderProfileId: String? = null, val name: String,
    val copper: Long? = null, val silver: Long? = null, val gold: Long? = null,
    val revision: Long = 1, val source: String = "", val updatedAt: Long = 0, val campaignId: String = "local-campaign"
)

@Serializable @Entity(tableName = "campaign_checkpoints", indices = [Index("campaignId")])
data class CampaignCheckpoint(
    @PrimaryKey val id: String, val title: String, val scene: String = "", val lastConfirmedAction: String = "",
    val unresolvedChoice: String = "", val objective: String = "", val gameTime: String = "",
    val recordReferences: List<String> = emptyList(), val unknowns: List<String> = emptyList(),
    val revision: Long = 1, val source: String = "", val updatedAt: Long = 0, val campaignId: String = "local-campaign"
)

@Serializable @Entity(tableName = "external_record_references", indices = [Index(value = ["namespace", "externalId"], unique = true)])
data class ExternalRecordReference(
    @PrimaryKey val id: String, val namespace: String, val externalId: String, val recordType: String,
    val recordId: String, val url: String = "", val revision: Long = 1, val source: String = "", val updatedAt: Long = 0,
    val campaignId: String = "local-campaign"
)

class PartyConverters {
    @TypeConverter fun stringsFromJson(value: String): List<String> = campaignJson.decodeFromString(value)
    @TypeConverter fun stringsToJson(value: List<String>): String = campaignJson.encodeToString(value)
}

@Serializable data class ItemMoveCommand(
    val itemId: String, val expectedItemRevision: Long, val expectedPlacementRevision: Long? = null,
    val holderProfileId: String? = null, val containerItemId: String? = null, val installedProjectId: String? = null,
    val state: String = "Carried", val source: String, val commandId: String = UUID.randomUUID().toString()
)
@Serializable data class QuantityCommand(
    val itemId: String, val expectedItemRevision: Long, val delta: Int, val source: String,
    val commandId: String = UUID.randomUUID().toString()
)
@Serializable data class QuantityConfirmationCommand(
    val itemId: String, val expectedItemRevision: Long, val quantity: Int?, val source: String,
    val commandId: String = UUID.randomUUID().toString()
)
@Serializable data class CurrencyCommand(
    val walletId: String, val expectedRevision: Long, val copperDelta: Long = 0, val silverDelta: Long = 0,
    val goldDelta: Long = 0, val source: String, val commandId: String = UUID.randomUUID().toString()
)

@Serializable data class PartyExchangeCommand(
    val moves: List<ItemMoveCommand> = emptyList(), val quantities: List<QuantityCommand> = emptyList(),
    val currency: List<CurrencyCommand> = emptyList(), val creations: List<LedgerDraft> = emptyList(), val source: String,
    val commandId: String = UUID.randomUUID().toString(), val creationHolderProfileIds: List<String?> = emptyList()
)
@Serializable data class PartyExchangeOutcome(
    val id: String, val childReceipts: List<CommandReceipt>, val source: String, val createdAt: Long,
    val revision: Long = 1
)

internal fun placementLocation(value: ItemPlacement): String = when {
    value.containerItemId != null -> "Item:${value.containerItemId}"
    value.installedProjectId != null -> "Project:${value.installedProjectId}"
    value.holderProfileId != null -> "Actor:${value.holderProfileId}"
    else -> "Unknown"
}
internal fun depletedPlacement(value: ItemPlacement, source: String, now: Long): ItemPlacement = value.copy(
    state = "Consumed", containerItemId = null, installedProjectId = null, revision = value.revision + 1, source = source, updatedAt = now
)

internal fun exchangeChildId(commandId: String, kind: String, index: Int): String =
    "exchange-${campaignHash(commandId).take(40)}-$kind-$index"

@Dao interface PartyRecordsDao {
    @Query("SELECT * FROM party_sheets ORDER BY profileId") fun sheets(): Flow<List<PartySheetRecord>>
    @Query("SELECT * FROM party_sheets ORDER BY profileId") suspend fun allSheets(): List<PartySheetRecord>
    @Query("SELECT * FROM party_sheets WHERE profileId=:id") suspend fun sheet(id: String): PartySheetRecord?
    @Query("SELECT * FROM item_placements ORDER BY itemId") fun placements(): Flow<List<ItemPlacement>>
    @Query("SELECT * FROM item_placements ORDER BY itemId") suspend fun allPlacements(): List<ItemPlacement>
    @Query("SELECT * FROM item_placements WHERE itemId=:id") suspend fun placement(id: String): ItemPlacement?
    @Query("SELECT * FROM party_wallets ORDER BY id") fun wallets(): Flow<List<PartyWallet>>
    @Query("SELECT * FROM party_wallets ORDER BY id") suspend fun allWallets(): List<PartyWallet>
    @Query("SELECT * FROM party_wallets WHERE id=:id") suspend fun wallet(id: String): PartyWallet?
    @Query("SELECT * FROM campaign_checkpoints ORDER BY updatedAt DESC,id") fun checkpoints(): Flow<List<CampaignCheckpoint>>
    @Query("SELECT * FROM campaign_checkpoints ORDER BY id") suspend fun allCheckpoints(): List<CampaignCheckpoint>
    @Query("SELECT * FROM campaign_checkpoints WHERE id=:id") suspend fun checkpoint(id: String): CampaignCheckpoint?
    @Query("SELECT * FROM external_record_references ORDER BY id") suspend fun allReferences(): List<ExternalRecordReference>
    @Query("SELECT * FROM external_record_references WHERE id=:id") suspend fun reference(id: String): ExternalRecordReference?
    @Upsert suspend fun put(value: PartySheetRecord)
    @Upsert suspend fun put(value: ItemPlacement)
    @Upsert suspend fun put(value: PartyWallet)
    @Upsert suspend fun put(value: CampaignCheckpoint)
    @Upsert suspend fun put(value: ExternalRecordReference)
}

/** Adds typed sheets and commands to the same campaign database, receipt and audit domains. */
class PartyRecordsRepository(private val db: CampaignLedgerDb) {
    private val dao = db.partyDao()
    private val records = db.recordsDao()
    private val inventory = db.ledgerDao()
    val sheets = dao.sheets()
    val placements = dao.placements()
    val wallets = dao.wallets()
    val checkpoints = dao.checkpoints()

    private fun checkText(value: String, max: Int = 16000, required: Boolean = false) {
        require(value.length <= max && (!required || value.isNotBlank())) { "A required field is missing or too long." }
    }
    private fun checkSource(source: String) = checkText(source, 2000, true)
    private fun checkId(id: String) = checkText(id, 120, true)
    private fun checkEvidence(value: String) = require(value in setOf("Confirmed", "Unverified", "Proposed", "Disputed"))

    /** Original outcomes remain immutable even when the corresponding current record has advanced. */
    private suspend fun retry(commandId: String, fingerprint: String): CommandReceipt? {
        checkId(commandId)
        records.receipt(commandId)?.let {
            require(it.fingerprint == fingerprint) { "Command ID collision. No records changed." }
            return it
        }
        require(inventory.event(commandId) == null) { "Command ID belongs to another inventory command." }
        return null
    }

    private suspend fun audit(
        commandId: String, fingerprint: String, type: String, id: String, priorRevision: Long,
        revision: Long, source: String, now: Long, before: String?, after: String,
        auditId: String = commandId, kind: String = if (priorRevision == 0L) "CREATE" else "UPDATE"
    ): CommandReceipt {
        records.insert(CampaignAudit(auditId, commandId, type, id, kind, source, priorRevision, revision, now, before, after))
        return CommandReceipt(commandId, fingerprint, type, id, revision, now, outcomeJson = after)
    }

    suspend fun saveSheet(profileId: String, sheet: PartySheetData, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        checkId(profileId); checkSource(source); validateSheet(sheet)
        val payload = campaignJson.encodeToString(sheet)
        val fp = campaignHash(campaignJson.encodeToString(listOf("party-sheet-v1", profileId, expectedRevision?.toString(), payload, source)))
        retry(commandId, fp)?.let { return@withTransaction it }
        require(records.characterProfile(profileId) != null) { "Save the character's identity before adding a sheet." }
        val before = dao.sheet(profileId)
        check(before?.revision == expectedRevision) { "The sheet changed. Reopen it before saving." }
        val now = System.currentTimeMillis()
        val after = PartySheetRecord(profileId, payload, (before?.revision ?: 0) + 1, source, now, before?.campaignId ?: "local-campaign")
        dao.put(after)
        audit(commandId, fp, "party-sheet", profileId, before?.revision ?: 0, after.revision, source, now,
            before?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(after)).also { records.insert(it) }
    }

    /** A transfer never clones the item or changes its quantity. A whole stack moves as one identity. */
    suspend fun moveItem(command: ItemMoveCommand): CommandReceipt = db.withTransaction {
        checkId(command.itemId); checkSource(command.source)
        val fp = campaignHash(campaignJson.encodeToString(command.copy(commandId = "")))
        retry(command.commandId, fp)?.let { return@withTransaction it }
        val before = inventory.getItem(command.itemId) ?: error("The item no longer exists.")
        val oldPlacement = dao.placement(command.itemId)
        check(before.revision == command.expectedItemRevision && oldPlacement?.revision == command.expectedPlacementRevision) {
            "Item ownership changed. Review the current item and retry with its current revisions."
        }
        require(!before.archived && before.itemState !in setOf("Consumed", "Sold", "Lost") && before.quantity != 0) { "This item is unavailable." }
        val placement = ItemPlacement(command.itemId, command.holderProfileId, command.containerItemId,
            command.installedProjectId, command.state, (oldPlacement?.revision ?: 0) + 1, command.source, System.currentTimeMillis())
        validatePlacement(placement, dao.allPlacements(), inventory.allItems(), records.allCharacterProfiles(), records.allProjects())
        val holder = command.holderProfileId?.let { records.characterProfile(it) }
        val location = when {
            command.containerItemId != null -> "Item:${command.containerItemId}"
            command.installedProjectId != null -> "Project:${command.installedProjectId}"
            command.holderProfileId != null -> "Actor:${command.holderProfileId}"
            else -> "Unknown"
        }
        val after = before.copy(owner = holder?.name?.ifBlank { holder.id } ?: "Unknown", itemState = command.state,
            location = location, revision = before.revision + 1, updatedAt = placement.updatedAt, source = command.source)
        dao.put(placement)
        inventory.updateItem(after)
        inventory.insertEvent(LedgerEvent(command.commandId, before.id, "TRANSFER", "Confirmed", "${before.itemState} → ${command.state}",
            command.source, fp, before.revision, after.revision, after.updatedAt, campaignJson.encodeToString(before), campaignJson.encodeToString(after)))
        // The command's main receipt is the existing item outcome; placement is a second audited affected record.
        val receipt = audit(command.commandId, fp, "item", after.id, before.revision, after.revision, command.source, after.updatedAt,
            campaignJson.encodeToString(before), campaignJson.encodeToString(after), kind = "TRANSFER").copy(kind = "TRANSFER")
        audit(command.commandId, fp, "item-placement", after.id, oldPlacement?.revision ?: 0, placement.revision,
            command.source, after.updatedAt, oldPlacement?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(placement),
            auditId = "${command.commandId}:placement", kind = "TRANSFER")
        records.insert(receipt)
        receipt
    }

    /** Acquisition/expense only after resolution. Unknown stock cannot be spent or inferred from a delta. */
    suspend fun changeQuantity(command: QuantityCommand): CommandReceipt = db.withTransaction {
        checkId(command.itemId); checkSource(command.source); require(command.delta != 0)
        val fp = campaignHash(campaignJson.encodeToString(command.copy(commandId = "")))
        retry(command.commandId, fp)?.let { return@withTransaction it }
        val before = inventory.getItem(command.itemId) ?: error("The item no longer exists.")
        check(before.revision == command.expectedItemRevision) { "Quantity changed. Review the current record." }
        require(!before.archived && before.itemState !in setOf("Consumed", "Sold", "Lost", "Installed")) { "This item is not available for a quantity change." }
        val quantity = requireNotNull(before.quantity) { "Quantity is unknown. Confirm the baseline before recording a change." }.toLong() + command.delta.toLong()
        require(quantity in 0..1_000_000L) { "Insufficient inventory or quantity exceeds the supported limit." }
        require(quantity != 0L || dao.allPlacements().none { it.containerItemId == before.id }) { "Remove this container's contents before consuming it." }
        val now = System.currentTimeMillis()
        val oldPlacement = dao.placement(before.id)
        val depleted = oldPlacement?.takeIf { quantity == 0L }?.let { depletedPlacement(it, command.source, now) }
        val after = before.copy(quantity = quantity.toInt(), revision = before.revision + 1, updatedAt = now,
            itemState = if (quantity == 0L) "Consumed" else before.itemState, source = command.source,
            location = depleted?.let(::placementLocation) ?: before.location)
        // Retain the consumed identity/history without trapping it inside a now-empty container.
        if (depleted != null && oldPlacement != null) {
            dao.put(depleted)
            audit(command.commandId, fp, "item-placement", oldPlacement.itemId, oldPlacement.revision, depleted.revision, command.source, now,
                campaignJson.encodeToString(oldPlacement), campaignJson.encodeToString(depleted), "${command.commandId}:placement", "CONSUMPTION")
        }
        inventory.updateItem(after)
        inventory.insertEvent(LedgerEvent(command.commandId, before.id, "QUANTITY", "Confirmed", "Recorded quantity change ${command.delta}", command.source,
            fp, before.revision, after.revision, now, campaignJson.encodeToString(before), campaignJson.encodeToString(after)))
        audit(command.commandId, fp, "item", after.id, before.revision, after.revision, command.source, now,
            campaignJson.encodeToString(before), campaignJson.encodeToString(after), kind = "QUANTITY").copy(kind = "QUANTITY").also { records.insert(it) }
    }

    /** Establish/correct a sourced baseline explicitly; a delta can never establish an unknown baseline. */
    suspend fun confirmQuantity(command: QuantityConfirmationCommand): CommandReceipt = db.withTransaction {
        checkId(command.itemId); checkSource(command.source)
        require(command.quantity == null || command.quantity in 0..1_000_000)
        val fp = campaignHash(campaignJson.encodeToString(command.copy(commandId = "")))
        retry(command.commandId, fp)?.let { return@withTransaction it }
        val before = inventory.getItem(command.itemId) ?: error("The item no longer exists.")
        check(before.revision == command.expectedItemRevision) { "The quantity changed. Review the current item." }
        require(!before.archived && before.itemState !in setOf("Consumed", "Sold", "Lost", "Installed")) { "This item is unavailable for a baseline correction." }
        require(command.quantity != 0 || dao.allPlacements().none { it.containerItemId == before.id }) { "Remove this container's contents before consuming it." }
        val now = System.currentTimeMillis()
        val oldPlacement = dao.placement(before.id)
        val depleted = oldPlacement?.takeIf { command.quantity == 0 }?.let { depletedPlacement(it, command.source, now) }
        val after = before.copy(quantity = command.quantity, revision = before.revision + 1, source = command.source,
            updatedAt = now, itemState = if (command.quantity == 0) "Consumed" else before.itemState,
            location = depleted?.let(::placementLocation) ?: before.location)
        inventory.updateItem(after)
        if (depleted != null && oldPlacement != null) {
            dao.put(depleted)
            audit(command.commandId, fp, "item-placement", oldPlacement.itemId, oldPlacement.revision, depleted.revision, command.source, now,
                campaignJson.encodeToString(oldPlacement), campaignJson.encodeToString(depleted), "${command.commandId}:placement", "QUANTITY_BASELINE")
        }
        inventory.insertEvent(LedgerEvent(command.commandId, before.id, "QUANTITY_BASELINE", "Confirmed", "Confirmed a sourced quantity baseline",
            command.source, fp, before.revision, after.revision, now, campaignJson.encodeToString(before), campaignJson.encodeToString(after)))
        audit(command.commandId, fp, "item", after.id, before.revision, after.revision, command.source, now,
            campaignJson.encodeToString(before), campaignJson.encodeToString(after), kind = "QUANTITY_BASELINE")
            .copy(kind = "QUANTITY_BASELINE").also { records.insert(it) }
    }

    /** A confirmed item/currency event commits all children once, or none. No conversion/price is inferred. */
    suspend fun commitExchange(command: PartyExchangeCommand): CommandReceipt = db.withTransaction {
        checkSource(command.source)
        val normalized = command.copy(commandId = "",
            moves = command.moves.map { it.copy(source = command.source, commandId = "") },
            quantities = command.quantities.map { it.copy(source = command.source, commandId = "") },
            currency = command.currency.map { it.copy(source = command.source, commandId = "") },
            creations = command.creations.map { it.copy(source = command.source) })
        val fp = campaignHash("party-exchange-v1:" + campaignJson.encodeToString(normalized))
        retry(command.commandId, fp)?.let { return@withTransaction it }
        require(command.creationHolderProfileIds.isEmpty() || command.creationHolderProfileIds.size == command.creations.size) {
            "Each acquisition needs its corresponding known or unknown holder."
        }
        val acquisitionHolders = command.creationHolderProfileIds.ifEmpty { List(command.creations.size) { null } }
        acquisitionHolders.filterNotNull().forEach { require(records.characterProfile(it) != null) { "Save the acquiring character's identity first." } }
        val count = command.moves.size + command.quantities.size + command.currency.size + command.creations.size + acquisitionHolders.count { it != null }
        require(count in 1..100) { "An exchange needs 1–100 explicitly confirmed changes." }
        val itemIds = command.moves.map { it.itemId } + command.quantities.map { it.itemId }
        require(itemIds.distinct().size == itemIds.size) { "Each physical item may appear only once in an exchange." }
        require(command.currency.map { it.walletId }.distinct().size == command.currency.size) { "Each wallet may appear only once in an exchange." }
        val childIds = command.moves.indices.map { exchangeChildId(command.commandId, "move", it) } +
            command.quantities.indices.map { exchangeChildId(command.commandId, "quantity", it) } +
            command.currency.indices.map { exchangeChildId(command.commandId, "currency", it) } +
            command.creations.indices.map { exchangeChildId(command.commandId, "create", it) } +
            acquisitionHolders.indices.filter { acquisitionHolders[it] != null }.map { exchangeChildId(command.commandId, "acquire", it) }
        childIds.forEach { require(records.receipt(it) == null && inventory.event(it) == null) { "An exchange child command already exists; no changes applied." } }
        val children = mutableListOf<CommandReceipt>()
        command.creations.forEachIndexed { index, draft ->
            val childId = exchangeChildId(command.commandId, "create", index)
            val created = CampaignLedgerRepository(db).create(draft.copy(source = command.source), childId)
            children += requireNotNull(records.receipt(childId))
            acquisitionHolders[index]?.let { holder ->
                children += moveItem(ItemMoveCommand(created.id, created.revision, holderProfileId = holder, state = "Carried",
                    source = command.source, commandId = exchangeChildId(command.commandId, "acquire", index)))
            }
        }
        command.moves.forEachIndexed { index, child -> children += moveItem(child.copy(source = command.source, commandId = exchangeChildId(command.commandId, "move", index))) }
        command.quantities.forEachIndexed { index, child -> children += changeQuantity(child.copy(source = command.source, commandId = exchangeChildId(command.commandId, "quantity", index))) }
        command.currency.forEachIndexed { index, child -> children += adjustCurrency(child.copy(source = command.source, commandId = exchangeChildId(command.commandId, "currency", index))) }
        val now = System.currentTimeMillis()
        val outcome = PartyExchangeOutcome(command.commandId, children, command.source, now)
        audit(command.commandId, fp, "transaction", command.commandId, 0, 1, command.source, now, null,
            campaignJson.encodeToString(outcome), kind = "ATOMIC_EXCHANGE").also { records.insert(it) }
    }

    suspend fun saveWallet(wallet: PartyWallet, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        checkId(wallet.id); checkSource(source); validateWallet(wallet)
        require(wallet.holderProfileId == null || records.characterProfile(wallet.holderProfileId) != null) { "Unknown wallet holder." }
        val fp = campaignHash(campaignJson.encodeToString(listOf("wallet-v1", campaignJson.encodeToString(wallet.copy(revision = 0, source = "", updatedAt = 0)), expectedRevision?.toString(), source)))
        retry(commandId, fp)?.let { return@withTransaction it }
        val before = dao.wallet(wallet.id)
        check(before?.revision == expectedRevision) { "The wallet changed. Review the current balance." }
        val now = System.currentTimeMillis()
        val after = wallet.copy(revision = (before?.revision ?: 0) + 1, source = source, updatedAt = now)
        dao.put(after)
        audit(commandId, fp, "wallet", wallet.id, before?.revision ?: 0, after.revision, source, now,
            before?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(after)).also { records.insert(it) }
    }

    suspend fun adjustCurrency(command: CurrencyCommand): CommandReceipt = db.withTransaction {
        checkId(command.walletId); checkSource(command.source)
        require(listOf(command.copperDelta, command.silverDelta, command.goldDelta).any { it != 0L }) { "Record a non-zero currency change." }
        val fp = campaignHash(campaignJson.encodeToString(command.copy(commandId = "")))
        retry(command.commandId, fp)?.let { return@withTransaction it }
        val before = dao.wallet(command.walletId) ?: error("Wallet not found.")
        check(before.revision == command.expectedRevision) { "The balance changed. Review the current wallet." }
        fun change(current: Long?, delta: Long): Long? {
            if (delta == 0L) return current
            require(current != null) { "This denomination's opening balance is unknown. No currency changed." }
            val result = Math.addExact(current, delta)
            require(result in 0..1_000_000_000L) { "Insufficient currency or balance exceeds the supported limit." }
            return result
        }
        val now = System.currentTimeMillis()
        val after = before.copy(copper = change(before.copper, command.copperDelta), silver = change(before.silver, command.silverDelta),
            gold = change(before.gold, command.goldDelta), revision = before.revision + 1, source = command.source, updatedAt = now)
        dao.put(after)
        audit(command.commandId, fp, "wallet", before.id, before.revision, after.revision, command.source, now,
            campaignJson.encodeToString(before), campaignJson.encodeToString(after), kind = "CURRENCY").also { records.insert(it) }
    }

    suspend fun saveCheckpoint(checkpoint: CampaignCheckpoint, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        checkSource(source); validateCheckpoint(checkpoint)
        val fp = campaignHash(campaignJson.encodeToString(listOf("checkpoint-v1", campaignJson.encodeToString(checkpoint.copy(revision = 0, source = "", updatedAt = 0)), expectedRevision?.toString(), source)))
        retry(commandId, fp)?.let { return@withTransaction it }
        val before = dao.checkpoint(checkpoint.id)
        check(before?.revision == expectedRevision) { "Checkpoint changed. Review the current version." }
        val now = System.currentTimeMillis()
        val after = checkpoint.copy(revision = (before?.revision ?: 0) + 1, source = source, updatedAt = now)
        dao.put(after)
        audit(commandId, fp, "checkpoint", after.id, before?.revision ?: 0, after.revision, source, now,
            before?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(after)).also { records.insert(it) }
    }

    suspend fun saveExternalReference(reference: ExternalRecordReference, expectedRevision: Long? = null, source: String,
        commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        checkSource(source); validateReference(reference)
        require(recordExists(reference.recordType, reference.recordId)) { "External reference target must already exist." }
        val fp = campaignHash(campaignJson.encodeToString(listOf("external-reference-v1", campaignJson.encodeToString(reference.copy(revision = 0, source = "", updatedAt = 0)), expectedRevision?.toString(), source)))
        retry(commandId, fp)?.let { return@withTransaction it }
        val before = dao.reference(reference.id)
        check(before?.revision == expectedRevision) { "External reference changed." }
        require(dao.allReferences().none { it.id != reference.id && it.namespace == reference.namespace && it.externalId == reference.externalId }) { "External source identity already mapped. Review the existing record instead of duplicating it." }
        val now = System.currentTimeMillis()
        val after = reference.copy(revision = (before?.revision ?: 0) + 1, source = source, updatedAt = now)
        dao.put(after)
        audit(commandId, fp, "external-reference", after.id, before?.revision ?: 0, after.revision, source, now,
            before?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(after)).also { records.insert(it) }
    }

    private suspend fun recordExists(type: String, id: String) = when (type) {
        "item" -> inventory.getItem(id) != null
        "character-profile" -> records.characterProfile(id) != null
        "companion" -> records.companion(id) != null
        "project" -> records.project(id) != null
        "connection" -> records.entity(id) != null
        "checkpoint" -> dao.checkpoint(id) != null
        else -> false
    }

    /** Read-only migration reconciliation uses the existing strict whole-backup preview. */
    suspend fun migrationPreview(text: String): ImportPreview = CampaignBackup(db).preview(text)

    /** Audited compensating transfer. No history is erased and later changes make this rollback conflict. */
    suspend fun reverseTransfer(originalCommandId: String, expectedItemRevision: Long, expectedPlacementRevision: Long,
        source: String, commandId: String = UUID.randomUUID().toString()): CommandReceipt = db.withTransaction {
        val original = records.receipt(originalCommandId) ?: error("Original transfer receipt not found.")
        val placementHistory = records.allAudit().firstOrNull { it.id == "$originalCommandId:placement" }
            ?: error("No placement outcome is attached to this receipt.")
        require(placementHistory.kind == "TRANSFER") { "Only a transfer can be reversed here." }
        // A first structured placement has no known previous structured owner. Never infer ownership from old free text.
        val previous = placementHistory.beforeJson?.let { campaignJson.decodeFromString<ItemPlacement>(it) }
            ?: error("Previous ownership was unstructured/unknown. Review and explicitly choose its destination.")
        require(original.kind == "TRANSFER") { "Original receipt is not a transfer." }
        val originalItem = campaignJson.decodeFromString<LedgerItem>(original.outcomeJson)
        val originalPlacement = campaignJson.decodeFromString<ItemPlacement>(requireNotNull(placementHistory.afterJson))
        check(originalItem.id == previous.itemId)
        check(expectedItemRevision == originalItem.revision && expectedPlacementRevision == originalPlacement.revision) {
            "Later changes prevent automatic reversal. Review the current owner and record a new transfer instead."
        }
        moveItem(ItemMoveCommand(previous.itemId, expectedItemRevision, expectedPlacementRevision, previous.holderProfileId,
            previous.containerItemId, previous.installedProjectId, previous.state, source, commandId))
    }

    internal fun validateSheet(sheet: PartySheetData) {
        require((sheet.portraitBase64?.length ?: 0) <= 1_000_000)
        listOf(sheet.creatureType, sheet.size, sheet.role, sheet.progression, sheet.armorClassSources, sheet.senses,
            sheet.languages, sheet.resistances, sheet.immunities, sheet.vulnerabilities, sheet.conditions, sheet.spellcasting).forEach { checkText(it) }
        require(sheet.proficiencyBonus == null || sheet.proficiencyBonus in -1000..1000)
        require(sheet.initiative == null || sheet.initiative in -1000..1000)
        require(sheet.movement.size <= 20 && sheet.skills.size <= 100 && sheet.savingThrows.size <= 100 && sheet.capabilities.size <= 200 && sheet.attacks.size <= 200 && sheet.resources.size <= 100)
        sheet.movement.forEach { checkText(it.type, 120, true); require(it.feet == null || it.feet in 0..1_000_000); checkText(it.notes, 3000) }
        (sheet.skills + sheet.savingThrows).forEach { checkText(it.name, 120, true); require(it.modifier == null || it.modifier in -1000..1000); checkText(it.notes, 3000) }
        require(sheet.capabilities.map { it.id }.distinct().size == sheet.capabilities.size && sheet.attacks.map { it.id }.distinct().size == sheet.attacks.size) { "Duplicate capability or attack identities." }
        sheet.capabilities.forEach { checkId(it.id); checkText(it.name, 120, true); checkText(it.detail); checkText(it.kind, 120, true); checkEvidence(it.evidenceStatus); checkText(it.source, 2000) }
        sheet.attacks.forEach { a -> checkId(a.id); checkText(a.name, 120, true); require(a.attackBonus == null || a.attackBonus in -1000..1000); checkEvidence(a.evidenceStatus)
            listOf(a.damageFormula, a.damageType, a.range, a.criticalRule, a.actionEconomy, a.resourceCost, a.multiattack).forEach { checkText(it, 3000) }; checkText(a.source, 2000) }
        sheet.resources.forEach { checkText(it.name, 120, true); require(it.current == null || it.current in 0..1_000_000); require(it.maximum == null || it.maximum in 0..1_000_000)
            require(it.current == null || it.maximum == null || it.current <= it.maximum); checkText(it.notes, 3000) }
        require(sheet.references.size <= 200 && sheet.unresolvedConflicts.size <= 200)
        (sheet.references + sheet.unresolvedConflicts).forEach { checkText(it, 3000, true) }
        require(campaignJson.encodeToString(sheet).length <= 2_000_000) { "Character sheet is too large." }
    }

    internal fun validateWallet(wallet: PartyWallet) {
        checkId(wallet.id); checkText(wallet.name, 120, true); checkId(wallet.campaignId)
        listOf(wallet.copper, wallet.silver, wallet.gold).forEach { require(it == null || it in 0..1_000_000_000L) }
    }
    internal fun validateCheckpoint(checkpoint: CampaignCheckpoint) {
        checkId(checkpoint.id); checkId(checkpoint.campaignId); checkText(checkpoint.title, 120, true)
        listOf(checkpoint.scene, checkpoint.lastConfirmedAction, checkpoint.unresolvedChoice, checkpoint.objective, checkpoint.gameTime).forEach { checkText(it) }
        require(checkpoint.recordReferences.size <= 500 && checkpoint.unknowns.size <= 500)
        (checkpoint.recordReferences + checkpoint.unknowns).forEach { checkText(it, 3000, true) }
    }
    internal fun validateReference(reference: ExternalRecordReference) {
        checkId(reference.id); checkId(reference.campaignId); checkText(reference.namespace, 120, true)
        checkText(reference.externalId, 180, true); checkText(reference.recordType, 120, true); checkId(reference.recordId); checkText(reference.url, 2000)
    }
}

internal fun validatePlacement(value: ItemPlacement, placements: List<ItemPlacement>, items: List<LedgerItem>,
    profiles: List<CharacterProfile>, projects: List<InventionProject>) {
    require(value.state in setOf("Carried", "Equipped", "Installed", "Stored", "Consumed")) { "Unsupported placement state." }
    val item = items.firstOrNull { it.id == value.itemId } ?: error("Unknown placement item.")
    require(value.holderProfileId == null || profiles.any { it.id == value.holderProfileId }) { "Unknown item holder." }
    require(value.containerItemId == null || items.any { it.id == value.containerItemId && !it.archived && it.quantity != 0 && it.itemState !in setOf("Consumed", "Sold", "Lost") }) { "Container is unavailable." }
    require(value.installedProjectId == null || projects.any { it.id == value.installedProjectId }) { "Unknown invention." }
    require(value.containerItemId == null || value.installedProjectId == null) { "Choose one container or invention destination." }
    require(value.state != "Equipped" || value.holderProfileId != null) { "Choose the character equipping the item." }
    require(value.state != "Installed" || value.holderProfileId != null || value.containerItemId != null || value.installedProjectId != null) { "Choose the installation destination." }
    require(value.state == "Installed" || value.installedProjectId == null) { "An invention attachment must be installed." }
    require(value.state != "Consumed" || item.quantity == 0) { "Only an exhausted record may be consumed." }
    require(item.quantity != 0 || value.state == "Consumed") { "An exhausted item cannot remain carried, equipped, stored or installed." }
    require(value.state != "Consumed" || (value.containerItemId == null && value.installedProjectId == null)) { "An exhausted record cannot occupy a physical container or installation." }
    val byId = (placements.filter { it.itemId != value.itemId } + value).associateBy { it.itemId }
    val visited = mutableSetOf(value.itemId)
    var parent = value.containerItemId
    while (parent != null) {
        require(visited.add(parent)) { "An item cannot contain itself or form a containment cycle." }
        val parentPlacement = byId[parent]
        require(value.holderProfileId == parentPlacement?.holderProfileId || parentPlacement == null) { "Container and contents must have the same verified holder." }
        parent = parentPlacement?.containerItemId
    }
    // Moving a container cannot silently transfer ownership of its children.
    require(placements.none { it.containerItemId == value.itemId && it.holderProfileId != value.holderProfileId }) {
        "Remove contained items before changing this container’s holder; their ownership cannot change implicitly."
    }
}

val CAMPAIGN_MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) { campaignV3CreateStatements.forEach(db::execSQL) }
}
internal val campaignV3CreateStatements = listOf(
    "CREATE TABLE IF NOT EXISTS party_sheets (profileId TEXT NOT NULL PRIMARY KEY, contentJson TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, campaignId TEXT NOT NULL, FOREIGN KEY(profileId) REFERENCES character_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE TABLE IF NOT EXISTS item_placements (itemId TEXT NOT NULL PRIMARY KEY, holderProfileId TEXT, containerItemId TEXT, installedProjectId TEXT, state TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(holderProfileId) REFERENCES character_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(containerItemId) REFERENCES ledger_items(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(installedProjectId) REFERENCES invention_projects(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_item_placements_holderProfileId ON item_placements(holderProfileId)",
    "CREATE INDEX IF NOT EXISTS index_item_placements_containerItemId ON item_placements(containerItemId)",
    "CREATE INDEX IF NOT EXISTS index_item_placements_installedProjectId ON item_placements(installedProjectId)",
    "CREATE TABLE IF NOT EXISTS party_wallets (id TEXT NOT NULL PRIMARY KEY, holderProfileId TEXT, name TEXT NOT NULL, copper INTEGER, silver INTEGER, gold INTEGER, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, campaignId TEXT NOT NULL, FOREIGN KEY(holderProfileId) REFERENCES character_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS index_party_wallets_holderProfileId ON party_wallets(holderProfileId)",
    "CREATE TABLE IF NOT EXISTS campaign_checkpoints (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, scene TEXT NOT NULL, lastConfirmedAction TEXT NOT NULL, unresolvedChoice TEXT NOT NULL, objective TEXT NOT NULL, gameTime TEXT NOT NULL, recordReferences TEXT NOT NULL, unknowns TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, campaignId TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_campaign_checkpoints_campaignId ON campaign_checkpoints(campaignId)",
    "CREATE TABLE IF NOT EXISTS external_record_references (id TEXT NOT NULL PRIMARY KEY, namespace TEXT NOT NULL, externalId TEXT NOT NULL, recordType TEXT NOT NULL, recordId TEXT NOT NULL, url TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, updatedAt INTEGER NOT NULL, campaignId TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_external_record_references_namespace_externalId ON external_record_references(namespace,externalId)"
)
