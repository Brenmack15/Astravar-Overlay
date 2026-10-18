package com.yazsras.astravar.data

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
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

    private suspend fun snapshot() = LedgerExport(exportedAt = 0, items = items.allItems(), events = items.allEvents(),
        records = CampaignRecordsRepository(db).snapshot())

    private fun hash(value: LedgerExport) = campaignHash(campaignJson.encodeToString(value))

    suspend fun preview(text: String): ImportPreview = db.withTransaction {
        val current = snapshot()
        try {
            supportedCampaignBackup(text)
            val header = campaignJson.parseToJsonElement(text).jsonObject
            require(header["format"]?.jsonPrimitive?.content == "astravar-campaign-items" &&
                (header["schema"]?.jsonPrimitive?.intOrNull ?: -1) in 1..2) { "Backup must declare its format and supported schema." }
            val incoming = campaignJson.decodeFromString<LedgerExport>(text)
            val conflicts = validate(incoming, current)
            ImportPreview(conflicts.isEmpty(), "${incoming.items.size} items, ${incoming.events.size} inventory events, " +
                "${incoming.records.projects.size} inventions, ${incoming.records.entities.size} connections; existing different records will never be replaced.",
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
        val now = System.currentTimeMillis()
        records.insert(CampaignAudit(commandId, commandId, "backup", preview.fingerprint, "RESTORE", "User-confirmed local file restore",
            0, 1, now, afterJson = preview.summary))
        CommandReceipt(commandId, fp, "RESTORE", preview.fingerprint, 1, now, outcomeJson = preview.summary).also { records.insert(it) }
    }

    private suspend fun <T> merge(incoming: List<T>, current: List<T>, id: (T) -> String, insert: suspend (T) -> Unit) {
        val present = current.map(id).toSet()
        incoming.filter { id(it) !in present }.forEach { insert(it) }
    }

    private fun validate(incoming: LedgerExport, current: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        if (incoming.format != "astravar-campaign-items" || incoming.schema !in 1..2) {
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
        val r = incoming.records
        val c = current.records
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
        if (r.itemDetails.any { it.itemId !in itemIds || it.revision < 1 || (it.weightGrams != null && it.weightGrams !in 0..1_000_000_000L) }) errors += "Invalid item details or reference."
        if (r.itemProperties.any { it.itemId !in itemIds || it.label.isBlank() || it.value.isBlank() || it.revision < 1 }) errors += "Invalid item property or reference."
        if (r.itemProperties.any { !short(it.label, 120, true) || !short(it.value, 6000, true) || !provenance(it.source) || it.evidenceStatus !in evidenceValues }) errors += "Invalid item property fields."
        fun endpoint(type: String, id: String) = when (type) {
            "item" -> id in itemIds
            "project" -> id in projectIds
            "connection" -> id in entityIds
            "companion" -> id in companionIds
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
            "link" to (r.links + c.links).map { it.id }.toSet()
        )
        fun auditSnapshotMatches(event: CampaignAudit, value: String?, revision: Long): Boolean = try {
            if (value == null) false else {
                val obj = campaignJson.parseToJsonElement(value).jsonObject
                val key = when (event.recordType) { "companion" -> "companionId"; "item-details" -> "itemId"; else -> "id" }
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
