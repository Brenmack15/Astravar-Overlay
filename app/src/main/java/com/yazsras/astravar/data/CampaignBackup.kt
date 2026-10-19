package com.yazsras.astravar.data

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val CAMPAIGN_BACKUP_MAX_CHARACTERS = 32_000_000
internal fun supportedCampaignBackup(text: String): String {
    require(text.length in 1..CAMPAIGN_BACKUP_MAX_CHARACTERS) {
        "Backup exceeds this preview's 32-million-character export/restore limit. No complete backup was produced; preserve the original app data."
    }
    return text
}

/** Explicit, local, merge-only restore. Different existing records are never overwritten. */
internal class CampaignBackup(private val db: CampaignLedgerDb) {
    private val items = db.ledgerDao()
    private val records = db.recordsDao()
    private val party = db.partyDao()

    internal suspend fun snapshot(includeBackups: Boolean = true, includeArchive: Boolean = true) = LedgerExport(exportedAt = 0, items = items.allItems(), events = items.allEvents(),
        records = CampaignRecordsRepository(db).snapshot(), archive = if (includeArchive) CampaignArchiveRepository(db).snapshot(includeBackups = includeBackups) else CampaignArchiveState())

    private fun hash(value: LedgerExport) = campaignSerializedHash(LedgerExport.serializer(), value)

    suspend fun preview(text: String): ImportPreview = db.withTransaction {
        val current = snapshot()
        try {
            supportedCampaignBackup(text)
            val header = rejectDuplicateJsonMembers(text)
            require("format" in header && "schema" in header) { "Backup must declare its format and supported schema." }
            // Inspect the actual decoded header. A JSON tree would retain another full copy of source payloads.
            val incoming = campaignJson.decodeFromString<LedgerExport>(text)
            val conflicts = validate(incoming, current)
            ImportPreview(conflicts.isEmpty(), "${incoming.items.size} items, ${incoming.events.size} inventory events, " +
                "${incoming.records.projects.size} inventions, ${incoming.records.entities.size} connections, " +
                "${incoming.records.partySheets.size} party sheets, ${incoming.records.placements.size} equipment placements, " +
                "${incoming.records.wallets.size} wallets, ${incoming.records.checkpoints.size} checkpoints; existing different records will never be replaced.",
                conflicts, hash(incoming), hash(current), incoming)
        } catch (e: Exception) {
            ImportPreview(false, "Backup could not be validated.", listOf(e.message ?: "Unsupported backup."), "", hash(current))
        }
    }

    suspend fun restore(preview: ImportPreview, commandId: String): CommandReceipt = db.withTransaction {
        require(preview.valid && preview.conflicts.isEmpty()) { "Review a valid import preview first." }
        require(commandId.isNotBlank() && commandId.length <= 120)
        val incoming = requireNotNull(preview.backup)
        require(hash(incoming) == preview.fingerprint) { "Preview content changed. Preview the file again." }
        val fp = campaignHash("RESTORE:v2:${preview.fingerprint}")
        records.receipt(commandId)?.let { require(it.fingerprint == fp) { "Command ID collision." }; return@withTransaction it }
        require(items.event(commandId) == null) { "Command ID collision." }
        require(incoming.events.none { it.commandId == commandId } && incoming.records.receipts.none { it.commandId == commandId } &&
            incoming.records.audit.none { it.id == commandId }) { "Restore command ID is already present in the backup." }
        val current = snapshot()
        check(hash(current) == preview.currentFingerprint) { "Local data changed after preview. Preview the file again." }
        val conflicts = validate(incoming, current)
        require(conflicts.isEmpty()) { conflicts.joinToString("\n") }

        mergeRows(incoming, current)
        val now = System.currentTimeMillis()
        records.insert(CampaignAudit(commandId, commandId, "backup", preview.fingerprint, "RESTORE", "User-confirmed local file restore",
            0, 1, now, afterJson = preview.summary))
        CommandReceipt(commandId, fp, "RESTORE", preview.fingerprint, 1, now, outcomeJson = preview.summary).also { records.insert(it) }
    }

    internal suspend fun mergeRows(incoming: LedgerExport, current: LedgerExport) {
        val currentItems = current.items.associateBy { it.id }
        incoming.items.filter { it.id !in currentItems }.forEach { items.insertItem(it) }
        val currentEvents = current.events.associateBy { it.commandId }
        incoming.events.filter { it.commandId !in currentEvents }.forEach { items.insertEvent(it) }
        val r = incoming.records
        val c = current.records
        merge(r.projects, c.projects, { it.id }) { records.put(it) }
        merge(r.entities, c.entities, { it.id }) { records.put(it) }
        merge(r.companionStates, c.companionStates, { it.companionId }) { records.put(it) }
        merge(r.characterProfiles, c.characterProfiles, { it.id }) { records.put(it) }
        merge(r.characterAbilities, c.characterAbilities, { it.id }) { records.put(it) }
        merge(r.partySheets, c.partySheets, { it.profileId }) { party.put(it) }
        merge(r.placements, c.placements, { it.itemId }) { party.put(it) }
        merge(r.wallets, c.wallets, { it.id }) { party.put(it) }
        merge(r.checkpoints, c.checkpoints, { it.id }) { party.put(it) }
        merge(r.externalReferences, c.externalReferences, { it.id }) { party.put(it) }
        merge(r.stages, c.stages, { it.id }) { records.put(it) }
        merge(r.requirements, c.requirements, { it.id }) { records.put(it) }
        merge(r.facts, c.facts, { it.id }) { records.put(it) }
        merge(r.milestones, c.milestones, { it.id }) { records.put(it) }
        merge(r.companionResources, c.companionResources, { it.id }) { records.put(it) }
        merge(r.itemDetails, c.itemDetails, { it.itemId }) { records.put(it) }
        merge(r.itemProperties, c.itemProperties, { it.id }) { records.put(it) }
        merge(r.links, c.links, { it.id }) { records.put(it) }
        merge(r.crafts, c.crafts, { it.commandId }) { records.insert(it) }
        merge(r.consumptions, c.consumptions, { it.id }) { records.insert(it) }
        merge(r.audit, c.audit, { it.id }) { records.insert(it) }
        merge(r.receipts, c.receipts, { it.commandId }) { records.insert(it) }
        CampaignArchiveRepository(db).mergeState(incoming.archive, current.archive)
    }

    private suspend fun <T> merge(incoming: List<T>, current: List<T>, id: (T) -> String, insert: suspend (T) -> Unit) {
        val present = current.map(id).toSet()
        incoming.filter { id(it) !in present }.forEach { insert(it) }
    }

    internal fun validate(incoming: LedgerExport, current: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        if (incoming.format != "astravar-campaign-items" || incoming.schema !in 1..4) {
            return listOf("Unsupported backup format or schema. No data will be imported.")
        }
        if (incoming.schema == 1 && incoming.records != CampaignRecordsExport()) errors += "Schema 1 cannot contain v2 records."
        fun <T> checkRows(label: String, values: List<T>, existing: List<T>, key: (T) -> String) {
            if (values.size > 50_000) errors += "$label exceeds the 50,000-record restore limit."
            val keys = values.map(key)
            if (keys.any { it.isBlank() || it.length > 180 }) errors += "$label has an invalid identity."
            if (keys.distinct().size != keys.size) errors += "$label contains duplicate IDs."
            val local = existing.associateBy(key)
            values.forEach { value ->
                local[key(value)]?.let { if (it != value) errors += "$label ${key(value)} differs from local data. Preserve both backups and reconcile manually." }
            }
        }
        checkRows("Item", incoming.items, current.items) { it.id }
        checkRows("Inventory event", incoming.events, current.events) { it.commandId }
        errors += CampaignArchiveRepository.validateState(incoming.archive, current.archive)
        if (incoming.schema < 4 && incoming.archive != CampaignArchiveState()) errors += "Archive reference records require backup schema 4."
        val r = incoming.records
        val c = current.records
        val notionIdentities = buildList {
            (incoming.items + current.items).forEach { item ->
                item.notionPageId?.takeIf(String::isNotBlank)?.let { add(normalizedNotionIdentity(it) to item.id) }
            }
            (r.externalReferences + c.externalReferences).filter { it.namespace.equals("notion", ignoreCase = true) && it.recordType == "item" }
                .forEach { add(normalizedNotionIdentity(it.externalId) to it.recordId) }
        }
        if (notionIdentities.groupBy { it.first }.any { (_, rows) -> rows.map { it.second }.distinct().size > 1 }) errors += "One Notion item identity maps to multiple physical equipment records."
        val walletIdentities = (r.wallets + c.wallets).distinctBy { it.id }.groupBy { Triple(it.campaignId, it.holderProfileId, it.name) }
        if (walletIdentities.any { (_, rows) -> rows.size > 1 }) errors += "Wallet identities conflict; balances will not be combined."
        if (incoming.schema < 3 && (r.partySheets.isNotEmpty() || r.placements.isNotEmpty() || r.wallets.isNotEmpty() || r.checkpoints.isNotEmpty() || r.externalReferences.isNotEmpty() || r.receipts.any { it.kind == "transaction" })) {
            errors += "Legacy backup schemas cannot contain v3 party records."
        }
        checkRows("Party sheet", r.partySheets, c.partySheets) { it.profileId }
        checkRows("Placement", r.placements, c.placements) { it.itemId }
        checkRows("Wallet", r.wallets, c.wallets) { it.id }
        checkRows("Checkpoint", r.checkpoints, c.checkpoints) { it.id }
        checkRows("External reference", r.externalReferences, c.externalReferences) { it.id }
        checkRows("Invention", r.projects, c.projects) { it.id }
        checkRows("Stage", r.stages, c.stages) { it.id }
        checkRows("Requirement", r.requirements, c.requirements) { it.id }
        checkRows("Connection", r.entities, c.entities) { it.id }
        checkRows("Fact", r.facts, c.facts) { it.id }
        checkRows("Milestone", r.milestones, c.milestones) { it.id }
        checkRows("Companion", r.companionStates, c.companionStates) { it.companionId }
        checkRows("Resource", r.companionResources, c.companionResources) { it.id }
        checkRows("Character profile", r.characterProfiles, c.characterProfiles) { it.id }
        checkRows("Character ability", r.characterAbilities, c.characterAbilities) { it.id }
        checkRows("Item details", r.itemDetails, c.itemDetails) { it.itemId }
        checkRows("Item property", r.itemProperties, c.itemProperties) { it.id }
        checkRows("Link", r.links, c.links) { it.id }
        checkRows("Audit", r.audit, c.audit) { it.id }
        checkRows("Receipt", r.receipts, c.receipts) { it.commandId }
        checkRows("Craft", r.crafts, c.crafts) { it.commandId }
        checkRows("Consumption", r.consumptions, c.consumptions) { it.id }
        val itemIds = (incoming.items + current.items).map { it.id }.toSet()
        val projectIds = (r.projects + c.projects).map { it.id }.toSet()
        val entityIds = (r.entities + c.entities).map { it.id }.toSet()
        val companionIds = (r.companionStates + c.companionStates).map { it.companionId }.toSet()
        val characterProfileIds = (r.characterProfiles + c.characterProfiles).map { it.id }.toSet()
        val craftIds = (r.crafts + c.crafts).map { it.commandId }.toSet()
        fun short(value: String, max: Int = 16000, required: Boolean = false) = value.length <= max && (!required || value.isNotBlank())
        fun provenance(value: String) = short(value, 2000)
        val evidenceValues = setOf("Confirmed", "Unverified", "Proposed", "Disputed")
        if (incoming.items.any { it.title.isBlank() || it.title.length > 120 || it.revision < 1 ||
            (it.quantity != null && it.quantity !in 0..1_000_000) || (it.imageBase64?.length ?: 0) > 1_000_000 }) errors += "Invalid item values."
        if (incoming.items.any { !provenance(it.source) || !short(it.description) || !short(it.knownProperties) ||
            !short(it.possibleUses) || !short(it.limitations) || !short(it.owner, 120) || !short(it.location, 200) }) errors += "Item fields exceed supported bounds."
        if (incoming.events.any { it.itemId !in itemIds || it.resultingRevision < 1 }) errors += "Inventory events contain unresolved item references."
        incoming.events.forEach { event ->
            fun snapshotMatches(value: String?, revision: Long): Boolean = try {
                value != null && campaignJson.decodeFromString<LedgerItem>(value).let { it.id == event.itemId && it.revision == revision }
            } catch (_: Exception) { false }
            if (event.priorRevision < 0 || event.resultingRevision != event.priorRevision + 1 ||
                !snapshotMatches(event.afterJson, event.resultingRevision) ||
                (event.priorRevision == 0L && event.beforeJson != null) ||
                (event.priorRevision > 0 && !snapshotMatches(event.beforeJson, event.priorRevision))) {
                errors += "Inventory event ${event.commandId} has an invalid original snapshot or revision chain."
            }
        }
        if (r.projects.any { !short(it.title, 120, true) || !short(it.description) || !provenance(it.source) || it.revision < 1 ||
            it.status !in setOf("Proposed", "Research", "Blueprint", "Prototype", "Construction", "Completed", "Paused") }) errors += "Invalid invention values."
        if (r.stages.any { it.projectId !in projectIds || it.title.isBlank() || it.revision < 1 }) errors += "Invalid stage or invention reference."
        if (r.requirements.any { it.projectId !in projectIds || (it.itemId != null && it.itemId !in itemIds) ||
            (it.quantity != null && it.quantity !in 1..1_000_000) || it.revision < 1 }) errors += "Invalid material requirement or reference."
        if (r.entities.any { it.name.isBlank() || it.revision < 1 || (it.imageBase64?.length ?: 0) > 1_000_000 }) errors += "Invalid connection values."
        if (r.entities.any { !short(it.name, 120, true) || !short(it.notes) || !short(it.aliases, 2000) || !short(it.relationship, 3000) ||
            !provenance(it.source) || it.kind !in setOf("Person", "Organization", "Faction", "Creature", "Other") }) errors += "Invalid connection fields."
        if (r.facts.any { it.entityId !in entityIds || it.detail.isBlank() || it.revision < 1 }) errors += "Invalid connection fact or reference."
        if (r.facts.any { !short(it.detail, 6000, true) || !short(it.category, 120, true) || !provenance(it.source) || it.evidenceStatus !in evidenceValues }) errors += "Invalid connection fact fields."
        if (r.milestones.any { it.entityId !in entityIds || it.title.isBlank() || it.revision < 1 }) errors += "Invalid milestone or reference."
        if (r.companionStates.any { it.name.isBlank() || it.revision < 1 }) errors += "Invalid companion values."
        if (r.companionResources.any { it.companionId !in companionIds || it.name.isBlank() || it.revision < 1 ||
            (it.current != null && it.current !in 0..1_000_000) || (it.maximum != null && it.maximum !in 0..1_000_000) ||
            (it.current != null && it.maximum != null && it.current > it.maximum) }) errors += "Invalid companion resource or reference."
        val resourceKeys = (r.companionResources + c.companionResources).distinctBy { it.id }.map { it.companionId to it.name }
        if (resourceKeys.distinct().size != resourceKeys.size) errors += "Companion resource names conflict."
        if (r.characterProfiles.any { !short(it.id, 120, true) || !short(it.name, 120) || !provenance(it.source) || it.revision < 1 ||
            (it.currentHp != null && it.currentHp !in 0..1_000_000) || (it.maximumHp != null && it.maximumHp !in 0..1_000_000) ||
            (it.temporaryHp != null && it.temporaryHp !in 0..1_000_000) ||
            (it.currentHp != null && it.maximumHp != null && it.currentHp > it.maximumHp) ||
            (it.armorClass != null && it.armorClass !in 0..1000) || (it.level != null && it.level !in 1..1000) }) errors += "Invalid manual character profile."
        if (r.characterAbilities.any { it.profileId !in characterProfileIds || it.key !in setOf("STR", "DEX", "CON", "INT", "WIS", "CHA") ||
            (it.score != null && it.score !in 0..1000) || it.magicModifier !in -1000..1000 || !short(it.notes, 3000) ||
            !provenance(it.source) || it.revision < 1 }) errors += "Invalid manual ability score or character reference."
        val partyRepository = PartyRecordsRepository(db)
        r.partySheets.forEach { row ->
            try {
                require(row.profileId in characterProfileIds && row.revision >= 1 && provenance(row.source) && short(row.campaignId, 120, true))
                partyRepository.validateSheet(row.sheet)
            } catch (_: Exception) { errors += "Invalid party sheet ${row.profileId}; source, identity or typed values need review." }
        }
        val allItems = (current.items + incoming.items).distinctBy { it.id }
        val allPlacements = (c.placements + r.placements).distinctBy { it.itemId }
        val allProfiles = (c.characterProfiles + r.characterProfiles).distinctBy { it.id }
        val allProjects = (c.projects + r.projects).distinctBy { it.id }
        r.placements.forEach { row ->
            try {
                require(row.revision >= 1 && provenance(row.source))
                validatePlacement(row, allPlacements, allItems, allProfiles, allProjects)
                val item = allItems.first { it.id == row.itemId }
                val expectedLocation = when {
                    row.containerItemId != null -> "Item:${row.containerItemId}"
                    row.installedProjectId != null -> "Project:${row.installedProjectId}"
                    row.holderProfileId != null -> "Actor:${row.holderProfileId}"
                    else -> "Unknown"
                }
                require(item.itemState == row.state && item.location == expectedLocation) { "Ownership mirror disagrees with the placement." }
            } catch (_: Exception) { errors += "Invalid equipment placement ${row.itemId}; ownership, availability or containment conflicts." }
        }
        r.wallets.forEach { row ->
            try { partyRepository.validateWallet(row); require(row.revision >= 1 && provenance(row.source) && (row.holderProfileId == null || row.holderProfileId in characterProfileIds)) }
            catch (_: Exception) { errors += "Invalid wallet ${row.id}; unknown and negative balances must not be conflated." }
        }
        r.checkpoints.forEach { row ->
            try { partyRepository.validateCheckpoint(row); require(row.revision >= 1 && provenance(row.source)) }
            catch (_: Exception) { errors += "Invalid checkpoint ${row.id}." }
        }
        val externalKeys = (r.externalReferences + c.externalReferences).distinctBy { it.id }.map { it.namespace to it.externalId }
        if (externalKeys.distinct().size != externalKeys.size) errors += "External source identity maps to multiple records."
        r.externalReferences.forEach { row ->
            try {
                partyRepository.validateReference(row); require(row.revision >= 1 && provenance(row.source))
                val exists = when (row.recordType) {
                    "item" -> row.recordId in itemIds
                    "character-profile" -> row.recordId in characterProfileIds
                    "companion" -> row.recordId in companionIds
                    "project" -> row.recordId in projectIds
                    "connection" -> row.recordId in entityIds
                    "checkpoint" -> (r.checkpoints + c.checkpoints).any { it.id == row.recordId }
                    else -> false
                }
                require(exists)
            } catch (_: Exception) { errors += "Invalid external reference ${row.id}." }
        }
        if (r.itemDetails.any { it.itemId !in itemIds || it.revision < 1 || (it.weightGrams != null && it.weightGrams !in 0..1_000_000_000L) }) errors += "Invalid item details or reference."
        if (r.itemProperties.any { it.itemId !in itemIds || it.label.isBlank() || it.value.isBlank() || it.revision < 1 }) errors += "Invalid item property or reference."
        if (r.itemProperties.any { !short(it.label, 120, true) || !short(it.value, 6000, true) || !provenance(it.source) || it.evidenceStatus !in evidenceValues }) errors += "Invalid item property fields."
        fun endpoint(type: String, id: String) = when (type) {
            "item" -> id in itemIds
            "project" -> id in projectIds
            "connection" -> id in entityIds
            "companion" -> id in companionIds
            "character-profile" -> id in characterProfileIds
            else -> false
        }
        if (r.links.any { !endpoint(it.fromType, it.fromId) || !endpoint(it.toType, it.toId) || it.revision < 1 }) errors += "Links contain unresolved targets."
        if (r.crafts.any { it.projectId !in projectIds || it.outputItemId !in itemIds }) errors += "Craft contains unresolved references."
        if (r.consumptions.any { it.executionId !in craftIds || it.itemId !in itemIds || it.quantity <= 0 ||
            it.beforeQuantity.toLong() - it.afterQuantity != it.quantity.toLong() || it.afterQuantity < 0 ||
            it.resultingRevision != it.priorRevision + 1 }) errors += "Invalid craft consumption or reference."
        val ledgerCommands = (incoming.events + current.events).associateBy { it.commandId }
        (r.receipts + c.receipts).distinctBy { it.commandId }.forEach { receipt ->
            ledgerCommands[receipt.commandId]?.let { event ->
                if (receipt.fingerprint != event.payloadFingerprint || receipt.recordId != event.itemId || receipt.kind != event.kind ||
                    receipt.resultingRevision != event.resultingRevision || receipt.outcomeJson != event.afterJson) errors += "Receipt conflicts with inventory command ${receipt.commandId}."
            }
        }
        if (r.receipts.any { it.commandId == "" || it.fingerprint.isBlank() }) errors += "Invalid command receipt."
        val audit = (r.audit + c.audit).associateBy { it.id }
        val allReceipts = (r.receipts + c.receipts).associateBy { it.commandId }
        val recordIds = mapOf(
            "item" to itemIds, "project" to projectIds, "connection" to entityIds, "companion" to companionIds,
            "stage" to (r.stages + c.stages).map { it.id }.toSet(),
            "requirement" to (r.requirements + c.requirements).map { it.id }.toSet(),
            "fact" to (r.facts + c.facts).map { it.id }.toSet(),
            "milestone" to (r.milestones + c.milestones).map { it.id }.toSet(),
            "resource" to (r.companionResources + c.companionResources).map { it.id }.toSet(),
            "character-profile" to characterProfileIds,
            "character-ability" to (r.characterAbilities + c.characterAbilities).map { it.id }.toSet(),
            "item-details" to (r.itemDetails + c.itemDetails).map { it.itemId }.toSet(),
            "item-property" to (r.itemProperties + c.itemProperties).map { it.id }.toSet(),
            "link" to (r.links + c.links).map { it.id }.toSet(),
            "party-sheet" to (r.partySheets + c.partySheets).map { it.profileId }.toSet(),
            "item-placement" to (r.placements + c.placements).map { it.itemId }.toSet(),
            "wallet" to (r.wallets + c.wallets).map { it.id }.toSet(),
            "checkpoint" to (r.checkpoints + c.checkpoints).map { it.id }.toSet(),
            "external-reference" to (r.externalReferences + c.externalReferences).map { it.id }.toSet(),
            "transaction" to (r.receipts + c.receipts).filter { it.kind == "transaction" }.map { it.recordId }.toSet()
        )
        fun auditSnapshotMatches(event: CampaignAudit, value: String?, revision: Long): Boolean = try {
            if (value == null) false else {
                val obj = campaignJson.parseToJsonElement(value).jsonObject
                val key = when (event.recordType) { "companion" -> "companionId"; "item-details", "item-placement" -> "itemId"; "party-sheet" -> "profileId"; else -> "id" }
                val identity = if (event.recordType == "character-ability")
                    "${obj["profileId"]?.jsonPrimitive?.content}:${obj["key"]?.jsonPrimitive?.content}"
                else obj[key]?.jsonPrimitive?.content
                identity == event.recordId && obj["revision"]?.jsonPrimitive?.longOrNull == revision
            }
        } catch (_: Exception) { false }
        r.audit.forEach { event ->
            if (event.commandId !in allReceipts || event.priorRevision < 0 || event.resultingRevision != event.priorRevision + 1) {
                errors += "Audit ${event.id} has no original receipt or an invalid revision."
            }
            if (event.recordType == "backup") {
                if (event.kind != "RESTORE" || event.afterJson.isNullOrBlank()) errors += "Invalid restore audit ${event.id}."
            } else if (event.recordId !in recordIds[event.recordType].orEmpty() ||
                !auditSnapshotMatches(event, event.afterJson, event.resultingRevision) ||
                (event.priorRevision == 0L && event.beforeJson != null) ||
                (event.priorRevision > 0 && !auditSnapshotMatches(event, event.beforeJson, event.priorRevision))) {
                errors += "Audit ${event.id} has an unresolved subject or invalid snapshot."
            }
        }
        r.receipts.forEach { receipt ->
            val original = audit[receipt.commandId]
            if (receipt.fingerprint.length != 64 || receipt.fingerprint.any { it !in "0123456789abcdef" } ||
                receipt.resultingRevision < 1 || original == null || original.commandId != receipt.commandId ||
                original.recordId != receipt.recordId || original.resultingRevision != receipt.resultingRevision ||
                original.afterJson != receipt.outcomeJson || (receipt.outputId != null && receipt.outputId !in itemIds)) {
                errors += "Receipt ${receipt.commandId} is not linked to its original audited outcome."
            }
        }

        r.receipts.filter { it.kind == "transaction" }.forEach { receipt ->
            try {
                val outcome = campaignJson.decodeFromString<PartyExchangeOutcome>(receipt.outcomeJson)
                require(outcome.id == receipt.commandId && receipt.recordId == receipt.commandId && outcome.revision == 1L &&
                    outcome.childReceipts.size in 1..100 && outcome.childReceipts.map { it.commandId }.distinct().size == outcome.childReceipts.size)
                require(outcome.source.isNotBlank() && outcome.source.length <= 2000)
                require(outcome.childReceipts.all { child ->
                    child.commandId.startsWith("exchange-${campaignHash(receipt.commandId).take(40)}-") &&
                        child.kind in setOf("CREATE", "TRANSFER", "QUANTITY", "wallet") && allReceipts[child.commandId] == child
                })
                require(audit[receipt.commandId]?.kind == "ATOMIC_EXCHANGE")
            } catch (_: Exception) { errors += "Transaction ${receipt.commandId} has invalid or missing original child receipts." }
        }

        // A merge may add genuinely missing historical rows, but cannot create a competing
        // history branch or claim a state newer than the retained final record.
        val finalRows = mutableMapOf<Pair<String, String>, Pair<Long, String>>()
        fun finalRow(type: String, id: String, revision: Long, json: String) { finalRows[type to id] = revision to json }
        (incoming.items + current.items).forEach { finalRow("item", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.projects + c.projects).forEach { finalRow("project", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.stages + c.stages).forEach { finalRow("stage", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.requirements + c.requirements).forEach { finalRow("requirement", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.entities + c.entities).forEach { finalRow("connection", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.facts + c.facts).forEach { finalRow("fact", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.milestones + c.milestones).forEach { finalRow("milestone", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.companionStates + c.companionStates).forEach { finalRow("companion", it.companionId, it.revision, campaignJson.encodeToString(it)) }
        (r.companionResources + c.companionResources).forEach { finalRow("resource", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.characterProfiles + c.characterProfiles).forEach { finalRow("character-profile", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.characterAbilities + c.characterAbilities).forEach { finalRow("character-ability", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.itemDetails + c.itemDetails).forEach { finalRow("item-details", it.itemId, it.revision, campaignJson.encodeToString(it)) }
        (r.itemProperties + c.itemProperties).forEach { finalRow("item-property", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.links + c.links).forEach { finalRow("link", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.partySheets + c.partySheets).forEach { finalRow("party-sheet", it.profileId, it.revision, campaignJson.encodeToString(it)) }
        (r.placements + c.placements).forEach { finalRow("item-placement", it.itemId, it.revision, campaignJson.encodeToString(it)) }
        (r.wallets + c.wallets).forEach { finalRow("wallet", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.checkpoints + c.checkpoints).forEach { finalRow("checkpoint", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.externalReferences + c.externalReferences).forEach { finalRow("external-reference", it.id, it.revision, campaignJson.encodeToString(it)) }
        (r.receipts + c.receipts).filter { it.kind == "transaction" }.forEach { finalRow("transaction", it.recordId, it.resultingRevision, it.outcomeJson) }
        fun sameJson(a: String?, b: String?): Boolean = try {
            a != null && b != null && campaignJson.parseToJsonElement(a) == campaignJson.parseToJsonElement(b)
        } catch (_: Exception) { false }
        fun checkHistory(type: String, id: String, history: List<CampaignAudit>) {
            val finalState = finalRows[type to id] ?: return
            if (history.groupBy { it.resultingRevision }.any { (_, rows) -> rows.size > 1 }) {
                errors += "History for $type $id contains competing commands at the same revision."
            }
            history.forEach { event ->
                if (event.resultingRevision > finalState.first ||
                    (event.resultingRevision == finalState.first && !sameJson(event.afterJson, finalState.second))) {
                    errors += "History for $type $id is newer than or disagrees with its final saved state."
                }
            }
            history.sortedBy { it.resultingRevision }.zipWithNext().forEach { (before, after) ->
                if (after.priorRevision == before.resultingRevision && !sameJson(before.afterJson, after.beforeJson)) {
                    errors += "History for $type $id has incompatible adjacent snapshots."
                }
            }
        }
        (incoming.events + current.events).distinctBy { it.commandId }.groupBy { it.itemId }.forEach { (id, rows) ->
            checkHistory("item", id, rows.map { event -> CampaignAudit(event.commandId, event.commandId, "item", id,
                event.kind, event.source, event.priorRevision, event.resultingRevision, event.createdAt, event.beforeJson, event.afterJson) })
        }
        (r.audit + c.audit).distinctBy { it.id }.filter { it.recordType != "backup" }
            .groupBy { it.recordType to it.recordId }.forEach { (key, rows) -> checkHistory(key.first, key.second, rows) }
        return errors.distinct()
    }
}
