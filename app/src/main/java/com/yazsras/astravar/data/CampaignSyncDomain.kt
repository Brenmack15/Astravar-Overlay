package com.yazsras.astravar.data

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Versioned, typed projections of committed local records, never a database replacement. */
@Serializable data class CampaignSyncRecord(val recordId: String, val kind: String, val revision: Long, val fields: JsonObject)
@Serializable data class CampaignSyncOperation(val type: String, val recordId: String, val kind: String,
    val expectedRevision: Long, val fields: JsonObject)

/** All record changes and the parent receipt share the existing campaign Room transaction. */
class CampaignSyncDomain(private val db: CampaignLedgerDb, private val weaponDb: AstravarDb? = null) {
    private val records = CampaignRecordsRepository(db)
    private val party = PartyRecordsRepository(db)
    private val ledger = CampaignLedgerRepository(db)
    companion object {
        fun receiptId(commandId: String) = "campaign-sync-${campaignHash(commandId).take(48)}"
        fun category(kind: String): String = when (kind) {
            "character-profile", "character-ability", "party-sheet" -> "character"
            "companion-state", "companion-resource" -> "companions"
            "item", "item-placement", "item-details", "item-property" -> "inventory"
            "wallet" -> "currency"
            "project", "stage", "requirement" -> "engineering"
            "connection", "fact", "milestone", "link", "checkpoint", "reference", "audit" -> "continuity"
            "configuration", "roll-history" -> "combat"
            else -> error("Unsupported campaign record kind.")
        }
        fun requiredReadScopes(record: CampaignSyncRecord): Set<String> = when (record.kind) {
            "character-profile" -> setOf("character", "hp")
            "party-sheet" -> setOf("character", "hp", "resources")
            "audit" -> setOf("hp", "resources", "inventory", "currency", "character", "companions", "engineering", "continuity", "combat")
            "reference" -> if (record.fields.containsKey("body")) setOf("hp", "resources", "inventory", "currency", "character", "companions", "engineering", "continuity", "combat") else setOf("continuity")
            else -> setOf(category(record.kind))
        }
        fun category(operation: CampaignSyncOperation): String = when (operation.type) {
            "adjust_hp", "set_status" -> "hp"
            "adjust_resource" -> if (operation.kind == "companion-resource") "companions" else "resources"
            else -> category(operation.kind)
        }
    }
    private inline fun <reified T> envelope(kind: String, id: String, revision: Long, value: T) =
        CampaignSyncRecord(id, kind, revision, campaignJson.encodeToJsonElement(value).jsonObject)

    /** Membership is explicit: imported source IDs and current placements, never a blanket campaign reassignment. */
    suspend fun snapshot(campaignId: String, includeReadOnly: Boolean = true): List<CampaignSyncRecord> = db.withTransaction {
        OwnerSyncProtocol.id(campaignId)
        val local = campaignId == "local-campaign"
        val diagnostics = setOf(OwnerConnectionProtocol.PROFILE, RemoteStatProtocol.PROFILE_ID)
        val allSheets = db.partyDao().allSheets()
        val sheets = allSheets.filter { it.campaignId == campaignId && it.profileId !in diagnostics }
        val scopedReferences = db.partyDao().allReferences().filter { it.campaignId == campaignId }
        val otherReferences = db.partyDao().allReferences().filter { it.campaignId != campaignId }
        fun mapped(type: String) = scopedReferences.filter { it.recordType == type }.map { it.recordId }.toSet()
        fun unassigned(type: String,id: String) = otherReferences.none { it.recordType == type && it.recordId == id }
        val profileIds = (sheets.map { it.profileId }.toSet() + mapped("character-profile") + if (local) db.recordsDao().allCharacterProfiles()
            .filter { p -> p.id !in diagnostics && allSheets.none { it.profileId == p.id && it.campaignId != campaignId } && unassigned("character-profile",p.id) }.map { it.id } else emptyList()).minus(diagnostics)
        val placements = db.partyDao().allPlacements()
        val itemIds = mapped("item") + placements.filter { it.holderProfileId in profileIds }.map { it.itemId } +
            if (local) db.ledgerDao().allItems().filter { unassigned("item",it.id) && placements.none { p -> p.itemId==it.id && p.holderProfileId!=null && p.holderProfileId !in profileIds } }.map {it.id} else emptyList()
        val projectIds = mapped("project") + if(local) db.recordsDao().allProjects().filter {unassigned("project",it.id)}.map {it.id} else emptyList()
        val entityIds = mapped("connection") + if(local) db.recordsDao().allEntities().filter {unassigned("connection",it.id)}.map {it.id} else emptyList()
        val companionIds = profileIds + mapped("companion") + if(local) db.recordsDao().allCompanionStates().filter {unassigned("companion",it.companionId) && unassigned("character-profile",it.companionId) && allSheets.none {s -> s.profileId==it.companionId && s.campaignId!=campaignId}}.map {it.companionId} else emptyList()
        val output = mutableListOf<CampaignSyncRecord>()
        db.recordsDao().allCharacterProfiles().filter { it.id in profileIds }.forEach { output += envelope("character-profile", it.id, it.revision, it) }
        db.recordsDao().allCharacterAbilities().filter { it.profileId in profileIds }.forEach { output += envelope("character-ability", it.id, it.revision, it) }
        sheets.forEach { output += envelope("party-sheet", it.profileId, it.revision, it.copy(contentJson = campaignJson.encodeToString(it.sheet.copy(portraitBase64 = null)))) }
        db.partyDao().allWallets().filter { it.campaignId == campaignId }.forEach { wallet ->
            val row = envelope("wallet",wallet.id,wallet.revision,wallet)
            output += row.copy(fields = JsonObject(row.fields + mapOf("electrum" to (wallet.electrum?.let(::JsonPrimitive) ?: JsonNull),"platinum" to (wallet.platinum?.let(::JsonPrimitive) ?: JsonNull))))
        }
        db.partyDao().allCheckpoints().filter { it.campaignId == campaignId }.forEach {output += envelope("checkpoint",it.id,it.revision,it)}
        scopedReferences.filter {it.namespace!="campaign-sync-membership"}.forEach {output += envelope("reference",it.id,it.revision,it)}
        if (includeReadOnly) db.archiveDao().allReferences().filter {it.campaignId==campaignId}.forEach {output += CampaignSyncReferenceProjection.records(it)}
        db.ledgerDao().allItems().filter {it.id in itemIds}.forEach {output += envelope("item",it.id,it.revision,it.copy(imageBase64=null))}
        placements.filter {it.itemId in itemIds && (it.holderProfileId==null || it.holderProfileId in profileIds) && (it.containerItemId==null || it.containerItemId in itemIds) && (it.installedProjectId==null || it.installedProjectId in projectIds)}.forEach {output += envelope("item-placement",it.itemId,it.revision,it)}
        db.recordsDao().allItemDetails().filter {it.itemId in itemIds}.forEach {output += envelope("item-details",it.itemId,it.revision,it)}
        db.recordsDao().allItemProperties().filter {it.itemId in itemIds}.forEach {output += envelope("item-property",it.id,it.revision,it)}
        db.recordsDao().allCompanionStates().filter {it.companionId in companionIds}.forEach {output += envelope("companion-state",it.companionId,it.revision,it)}
        db.recordsDao().allCompanionResources().filter {it.companionId in companionIds}.forEach {output += envelope("companion-resource",it.id,it.revision,it)}
        db.recordsDao().allProjects().filter {it.id in projectIds}.forEach {output += envelope("project",it.id,it.revision,it)}
        db.recordsDao().allStages().filter {it.projectId in projectIds}.forEach {output += envelope("stage",it.id,it.revision,it)}
        db.recordsDao().allRequirements().filter {it.projectId in projectIds && (it.itemId==null || it.itemId in itemIds)}.forEach {output += envelope("requirement",it.id,it.revision,it)}
        db.recordsDao().allEntities().filter {it.id in entityIds}.forEach {output += envelope("connection",it.id,it.revision,it.copy(imageBase64=null))}
        db.recordsDao().allFacts().filter {it.entityId in entityIds}.forEach {output += envelope("fact",it.id,it.revision,it)}
        db.recordsDao().allMilestones().filter {it.entityId in entityIds}.forEach {output += envelope("milestone",it.id,it.revision,it)}
        fun endpoint(type:String,id:String) = when(type){"item"->id in itemIds;"project"->id in projectIds;"connection"->id in entityIds;"companion"->id in companionIds;"character-profile"->id in profileIds;else->false}
        db.recordsDao().allLinks().filter {endpoint(it.fromType,it.fromId) && endpoint(it.toType,it.toId)}.forEach {output += envelope("link",it.id,it.revision,it)}
        val visibleSubjects=output.map {it.recordId}.toSet() + profileIds + companionIds
        if (includeReadOnly) db.recordsDao().allAudit().filter {audit -> (audit.recordId in visibleSubjects || audit.recordType=="campaign-transaction" && audit.recordId==campaignId) && diagnostics.none {audit.recordId==it || audit.recordId.startsWith("$it:")}}.forEach {output += CampaignSyncAuditProjection.records(envelope("audit",it.id,1,it))}
        // Initial acquisition and later inventory edits predate CampaignAudit and retain their original ledger identities.
        if (includeReadOnly) db.ledgerDao().allEvents().filter { it.itemId in itemIds }.forEach { event ->
            output += CampaignSyncAuditProjection.records(envelope("audit", "ledger:${campaignHash(event.commandId).take(48)}", 1, event))
        }
        // The established weapon store belongs to its primary local-player profile's selected campaign.
        if (includeReadOnly && ("local-player" in profileIds || local && allSheets.none {it.profileId=="local-player"})) weaponDb?.let {weapon ->
            weapon.withTransaction {
                val revision=weapon.dao().state()?.let {row ->
                    val state=com.yazsras.astravar.rules.BackupCodec.decodeCampaignSnapshot(row.body)
                    output += CampaignSyncRecord("astravar-configuration","configuration",state.revision.coerceAtLeast(1),buildJsonObject {put("campaign",Json.parseToJsonElement(row.body));put("source","Committed Android weapon store")})
                    state.revision.coerceAtLeast(1)
                } ?: 1L
                weapon.dao().rolls().forEach {row -> output += CampaignSyncRecord(row.id,"roll-history",revision,buildJsonObject {put("roll",Json.parseToJsonElement(row.body));put("source","Committed Android roll history")})}
            }
        }
        output.sortedWith(compareBy({it.kind},{it.recordId}))
    }

    private fun JsonObject.allowed(vararg names: String) { require(keys.all { it in names }) { "Unsupported fields for typed operation." } }
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun JsonObject.long(name: String, default: Long = 0) = get(name)?.jsonPrimitive?.long ?: default
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.optional(name: String) = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun expected(value: Long): Long? = value.takeUnless { it == 0L }
    private fun key(kind: String, id: String) = "$kind\u0000$id"

    suspend fun apply(campaignId: String, commandId: String, source: String,
        operations: List<CampaignSyncOperation>): CommandReceipt = db.withTransaction {
        OwnerSyncProtocol.id(campaignId); require(commandId.length in 1..120 && source.isNotBlank() && source.length <= 2000)
        require(operations.size in 1..50 && operations.map { key(it.kind,it.recordId) }.distinct().size == operations.size)
        val fingerprint = campaignHash("campaign-sync-v2:" + OwnerSyncProtocol.canonical(buildJsonObject {
            put("campaignId", campaignId); put("source", source); put("operations", campaignJson.encodeToJsonElement(operations))
        }))
        val parentId = receiptId(commandId)
        db.recordsDao().receipt(parentId)?.let { require(it.fingerprint == fingerprint) { "Command ID collision." }; return@withTransaction it }
        val before = snapshot(campaignId, includeReadOnly = false).associateBy { key(it.kind,it.recordId) }
        val childIds = operations.indices.map { "campaign-sync-${campaignHash(commandId).take(40)}-$it" }
        childIds.forEach { require(db.recordsDao().receipt(it) == null && db.ledgerDao().event(it) == null) { "Child command collision." } }
        val created = mutableSetOf<String>()
        operations.forEachIndexed { index, operation ->
            require(operation.expectedRevision >= 0 && operation.recordId.length in 1..180)
            val current = before[key(operation.kind,operation.recordId)]
            check((current?.revision ?: 0) == operation.expectedRevision) { "Record changed or is outside selected campaign." }
            require(current != null || operation.expectedRevision == 0L)
            require(current != null || operation.type in setOf("set_record", "save_checkpoint", "acquire_item")) { "Existing record required." }
            if (campaignId != "local-campaign" && current == null && operation.kind in setOf("character-ability", "party-sheet", "wallet")) {
                val parentProfileId = if (operation.kind == "wallet") operation.fields.optional("holderProfileId") else operation.fields.optional("profileId")
                    ?: if (operation.kind == "party-sheet") operation.recordId else operation.recordId.substringBeforeLast(':', "").takeIf { it.isNotBlank() }
                require(parentProfileId == null || before.containsKey(key("character-profile", parentProfileId))) { "Parent identity is outside selected campaign." }
            }
            fun known(kind:String,id:String?): Boolean {
                val normalized=if(kind=="companion") "companion-state" else kind
                return id!=null && (before.containsKey(key(normalized,id)) || key(normalized,id) in created)
            }
            if (operation.type == "acquire_item" && operation.fields.containsKey("placement")) {
                val destination = operation.fields.getValue("placement").jsonObject
                destination.allowed("holderProfileId", "expectedHolderRevision", "state")
                val holderId = destination.text("holderProfileId")
                val holderRevision = destination.getValue("expectedHolderRevision").jsonPrimitive.long
                require(holderRevision > 0 && known("character-profile", holderId)) { "Acquiring holder is outside selected campaign." }
                check(before[key("character-profile", holderId)]?.revision == holderRevision &&
                    db.recordsDao().characterProfile(holderId)?.revision == holderRevision) { "Acquiring holder changed." }
                check(db.partyDao().placement(operation.recordId) == null) { "Acquisition placement already exists." }
            }
            if(operation.type=="move_item") {
                for((field,kind) in listOf("holderProfileId" to "character-profile","containerItemId" to "item","installedProjectId" to "project"))
                    operation.fields.optional(field)?.let {require(known(kind,it)) {"Destination is outside selected campaign."}}
            }
            if(operation.kind=="reference" && operation.type=="set_record") {
                val merged=JsonObject((current?.fields ?: emptyMap())+operation.fields)
                require(merged.optional("namespace")!="campaign-sync-membership") {"System scope membership cannot be edited remotely."}
                require(known(merged.optional("recordType") ?: "",merged.optional("recordId"))) {"Reference target is outside selected campaign."}
            }
            if (campaignId!="local-campaign" && current==null) {
                val f=operation.fields
                val scopedParent=when(operation.kind) {
                    "item", "project", "connection", "checkpoint", "reference" -> true
                    "stage", "requirement" -> known("project",f.optional("projectId"))
                    "fact", "milestone" -> known("connection",f.optional("entityId"))
                    "item-details", "item-property" -> known("item",f.optional("itemId"))
                    "companion-state" -> known("character-profile",f.optional("companionId"))
                    "companion-resource" -> known("companion-state",f.optional("companionId")) || known("character-profile",f.optional("companionId"))
                    "character-ability", "party-sheet" -> known("character-profile",f.optional("profileId") ?: if(operation.kind=="party-sheet")operation.recordId else operation.recordId.substringBeforeLast(':',"").takeIf{it.isNotBlank()})
                    "wallet" -> f.optional("holderProfileId")==null || known("character-profile",f.optional("holderProfileId"))
                    "link" -> known(f.optional("fromType") ?: "",f.optional("fromId")) && known(f.optional("toType") ?: "",f.optional("toId"))
                    else -> false
                }
                require(scopedParent) {"New record has no scoped parent in this campaign."}
            }
            applyOne(campaignId, operation, current, source, childIds[index])
            if (current==null) {
                created += key(operation.kind,operation.recordId)
                if (campaignId!="local-campaign" && operation.kind in setOf("item","project","connection")) {
                    party.saveExternalReference(ExternalRecordReference("sync-scope-${campaignHash(campaignId+":"+operation.kind+":"+operation.recordId).take(48)}",
                        "campaign-sync-membership",campaignHash(operation.kind+":"+operation.recordId),operation.kind,operation.recordId,campaignId=campaignId),
                        source=source,commandId=childIds[index]+"-scope")
                }
            }
        }
        val after = snapshot(campaignId, includeReadOnly = false)
        operations.forEach { operation ->
            val committed = after.singleOrNull { it.kind == operation.kind && it.recordId == operation.recordId }
            check(committed != null && committed.revision == operation.expectedRevision + 1) { "Committed record escaped campaign scope or revision contract." }
        }
        val changed = after.filter { before[key(it.kind,it.recordId)] != it && it.kind !in setOf("audit", "configuration", "roll-history") }
        require(changed.isNotEmpty())
        val now = System.currentTimeMillis()
        val receipt = CommandReceipt(parentId, fingerprint, "CAMPAIGN_SYNC_V2", campaignId,
            1, now, outcomeJson = campaignJson.encodeToString(changed))
        db.recordsDao().insert(CampaignAudit(parentId, parentId, "campaign-transaction", campaignId, "TRUSTED_DM_TRANSACTION", source,
            0, receipt.resultingRevision, now, campaignJson.encodeToString(operations.mapNotNull { before[key(it.kind,it.recordId)] }), receipt.outcomeJson))
        db.recordsDao().insert(receipt)
        receipt
    }

    private suspend fun applyOne(campaignId: String, op: CampaignSyncOperation, current: CampaignSyncRecord?, source: String, child: String) {
        val f = op.fields
        val expected = expected(op.expectedRevision)
        when (op.type) {
            "acquire_item" -> {
                require(op.kind == "item" && current == null && op.expectedRevision == 0L)
                val destination = f["placement"]?.jsonObject
                val draft = campaignJson.decodeFromJsonElement<LedgerDraft>(JsonObject(f - "placement"))
                require(draft.evidenceStatus == "Confirmed" && draft.itemState !in setOf("Consumed", "Sold", "Lost", "Installed")) { "Only a resolved acquisition may establish an item." }
                if (destination == null) ledger.create(draft.copy(source = source),child,op.recordId)
                else {
                    val holderId = destination.text("holderProfileId")
                    val holder = requireNotNull(db.recordsDao().characterProfile(holderId)) { "Save the acquiring character or companion profile first." }
                    check(holder.revision == destination.getValue("expectedHolderRevision").jsonPrimitive.long) { "Acquiring holder changed." }
                    val state = destination.optional("state") ?: "Carried"
                    require(state in setOf("Carried", "Equipped")) { "Acquisition supports carried or equipped placement." }
                    val owner = holder.name.ifBlank { holder.id }; val location = "Actor:$holderId"
                    require(!draft.archived && draft.quantity != 0) { "An acquired item must be available." }
                    require(draft.owner in setOf("Unknown", owner) && draft.location in setOf("Unknown", location) &&
                        draft.itemState in setOf("Unknown", state)) { "Acquisition fields conflict with its placement." }
                    val item = ledger.create(draft.copy(source = source, owner = owner, itemState = state, location = location),child,op.recordId)
                    val placement = ItemPlacement(item.id, holderProfileId = holderId, state = state, source = source, updatedAt = item.updatedAt)
                    validatePlacement(placement,db.partyDao().allPlacements(),db.ledgerDao().allItems(),db.recordsDao().allCharacterProfiles(),db.recordsDao().allProjects())
                    db.partyDao().put(placement)
                    db.recordsDao().insert(CampaignAudit("$child:placement", child, "item-placement", item.id, "ACQUISITION", source,
                        0, placement.revision, placement.updatedAt, null, campaignJson.encodeToString(placement)))
                }
            }
            "set_status" -> {
                if (op.kind == "character-profile") {
                    f.allowed("currentHp", "maximumHp", "temporaryHp"); require(f.isNotEmpty()); val old = requireNotNull(db.recordsDao().characterProfile(op.recordId))
                    fun status(name: String, prior: Int?) = if (f.containsKey(name)) f[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int else prior
                    records.saveProfile(old.copy(currentHp = status("currentHp",old.currentHp), maximumHp = status("maximumHp",old.maximumHp),
                        temporaryHp = status("temporaryHp",old.temporaryHp)),expected,source,child)
                } else {
                    require(op.kind == "party-sheet"); f.allowed("conditions"); val old = requireNotNull(db.partyDao().sheet(op.recordId))
                    party.saveSheet(old.profileId,old.sheet.copy(conditions = f.text("conditions")),expected,source,child)
                }
            }
            "adjust_hp" -> {
                require(op.kind == "character-profile"); f.allowed("delta")
                val old = requireNotNull(db.recordsDao().characterProfile(op.recordId))
                val hp = Math.addExact(requireNotNull(old.currentHp) { "Current HP is unknown." }, f.int("delta"))
                require(hp >= 0 && (old.maximumHp == null || hp <= old.maximumHp)) { "HP change exceeds known bounds." }
                records.saveProfile(old.copy(currentHp = hp), expected, source, child)
            }
            "adjust_resource" -> {
                f.allowed("name", "resourceName", "delta")
                val delta = f.int("delta"); require(delta != 0)
                if (op.kind == "companion-resource") {
                    val old = requireNotNull(db.recordsDao().resource(op.recordId))
                    val value = Math.addExact(requireNotNull(old.current) { "Resource count is unknown." }, delta)
                    records.saveCompanionResource(old.copy(current = value), expected, source, child)
                } else {
                    require(op.kind == "party-sheet")
                    val old = requireNotNull(db.partyDao().sheet(op.recordId)); val name = f.optional("name") ?: f.text("resourceName")
                    val found = old.sheet.resources.filter { it.name == name }; require(found.size == 1) { "Resource must identify exactly one existing counter." }
                    val resource = found.single(); val value = Math.addExact(requireNotNull(resource.current) { "Resource count is unknown." }, delta)
                    require(value >= 0 && (resource.maximum == null || value <= resource.maximum))
                    party.saveSheet(old.profileId, old.sheet.copy(resources = old.sheet.resources.map { if (it.name == name) it.copy(current = value) else it }), expected, source, child)
                }
            }
            "adjust_currency" -> {
                require(op.kind == "wallet"); f.allowed("copperDelta", "silverDelta", "goldDelta", "electrumDelta", "platinumDelta")
                party.adjustCurrency(CurrencyCommand(op.recordId, op.expectedRevision, f.long("copperDelta"), f.long("silverDelta"), f.long("goldDelta"), source, child, f.long("electrumDelta"), f.long("platinumDelta")))
            }
            "move_item" -> {
                require(op.kind == "item"); f.allowed("expectedPlacementRevision", "holderProfileId", "containerItemId", "installedProjectId", "state")
                require(f.containsKey("expectedPlacementRevision")) { "Confirm the existing placement revision, including an explicit unknown placement." }
                val oldPlacement = db.partyDao().placement(op.recordId)
                fun placementField(name: String, old: String?) = if (f.containsKey(name)) f.optional(name) else old
                require(!f.containsKey("state") || f["state"] is JsonPrimitive && f["state"] != JsonNull && f.getValue("state").jsonPrimitive.isString) { "Placement state must be a named state." }
                party.moveItem(ItemMoveCommand(op.recordId, op.expectedRevision,
                    f["expectedPlacementRevision"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                    placementField("holderProfileId", oldPlacement?.holderProfileId), placementField("containerItemId", oldPlacement?.containerItemId),
                    placementField("installedProjectId", oldPlacement?.installedProjectId), f.optional("state") ?: oldPlacement?.state ?: "Carried", source, child,
                    preserveOwner = !f.containsKey("holderProfileId")))
            }
            "consume_item" -> {
                require(op.kind == "item"); f.allowed("quantity", "expectedPlacementRevision"); val amount = f.int("quantity"); require(amount > 0)
                if (f.containsKey("expectedPlacementRevision")) check(db.partyDao().placement(op.recordId)?.revision == f["expectedPlacementRevision"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long) { "Placement changed." }
                party.changeQuantity(QuantityCommand(op.recordId, op.expectedRevision, -amount, source, child))
            }
            "set_ability" -> {
                require(op.kind == "character-ability"); f.allowed("score"); val old = requireNotNull(current).fields
                records.saveAbility(campaignJson.decodeFromJsonElement<CharacterAbility>(old).copy(score = f["score"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int), expected, source, child)
            }
            "save_checkpoint" -> { require(op.kind == "checkpoint"); saveTyped(campaignId,op,current,source,child) }
            "set_record" -> saveTyped(campaignId,op,current,source,child)
            else -> error("Unsupported campaign operation.")
        }
    }

    private suspend fun saveTyped(campaignId: String, op: CampaignSyncOperation, current: CampaignSyncRecord?, source: String, child: String) {
        var f = JsonObject((current?.fields ?: emptyMap()) + op.fields)
        // Stable operation identity and the authorized campaign provide context only for omitted fields.
        // Explicit mismatches remain errors; no random UUID or other campaign can be substituted.
        val identityKey = when (op.kind) {
            "party-sheet" -> "profileId"; "companion-state" -> "companionId"; "item-details" -> "itemId"
            "character-ability" -> null; else -> "id"
        }
        if (identityKey != null && !f.containsKey(identityKey)) f = JsonObject(f + (identityKey to JsonPrimitive(op.recordId)))
        if (op.kind in setOf("checkpoint", "wallet", "party-sheet", "reference") && !f.containsKey("campaignId"))
            f = JsonObject(f + ("campaignId" to JsonPrimitive(campaignId)))
        if (op.kind == "character-ability") {
            val separator = op.recordId.lastIndexOf(':'); require(separator > 0 && separator < op.recordId.lastIndex)
            if (!f.containsKey("profileId")) f = JsonObject(f + ("profileId" to JsonPrimitive(op.recordId.substring(0,separator))))
            if (!f.containsKey("key")) f = JsonObject(f + ("key" to JsonPrimitive(op.recordId.substring(separator+1))))
        }
        if (op.kind == "party-sheet" && current != null && op.fields.containsKey("contentJson")) {
            val oldContent = Json.parseToJsonElement(current.fields.getValue("contentJson").jsonPrimitive.content).jsonObject
            val incomingContent = Json.parseToJsonElement(op.fields.getValue("contentJson").jsonPrimitive.content).jsonObject
            f = JsonObject(f + ("contentJson" to JsonPrimitive(JsonObject(oldContent + incomingContent).toString())))
        }
        val expected = expected(op.expectedRevision)
        fun identity(value: String) { require(value == op.recordId) { "Record identity cannot change." } }
        // Typed field decoding rejects unknown keys; source/revision/timestamps are assigned by domain repositories.
        when (op.kind) {
            "character-profile" -> {
                val value = campaignJson.decodeFromJsonElement<CharacterProfile>(f); identity(value.id)
                current?.let { val old = campaignJson.decodeFromJsonElement<CharacterProfile>(it.fields)
                    require(value.currentHp == old.currentHp && value.maximumHp == old.maximumHp && value.temporaryHp == old.temporaryHp) { "Use HP operations for health changes." } }
                require(current != null) { "Enroll character identity locally before remote editing." }
                records.saveProfile(value, expected, source, child)
            }
            "character-ability" -> { val v = campaignJson.decodeFromJsonElement<CharacterAbility>(f); identity(v.id); records.saveAbility(v,expected,source,child) }
            "party-sheet" -> { val v = campaignJson.decodeFromJsonElement<PartySheetRecord>(f); identity(v.profileId); require(v.campaignId == campaignId)
                require(current != null || campaignId == "local-campaign") { "Establish campaign membership locally before synchronizing a new sheet." }
                val old = current?.let { campaignJson.decodeFromJsonElement<PartySheetRecord>(it.fields) }
                require(if (old == null) v.sheet.resources.all { it.current == null } else v.sheet.resources == old.sheet.resources) { "Use resource operations for expenditure." }
                require(v.sheet.conditions == (old?.sheet?.conditions ?: "")) { "Use status operations for conditions." }
                val existing = db.partyDao().sheet(v.profileId)?.sheet
                party.saveSheet(v.profileId,v.sheet.copy(portraitBase64 = existing?.portraitBase64),expected,source,child) }
            "companion-state" -> { val v = campaignJson.decodeFromJsonElement<CompanionState>(f); identity(v.companionId); records.saveCompanionState(v,expected,source,child) }
            "companion-resource" -> { val v = campaignJson.decodeFromJsonElement<CompanionResource>(f); identity(v.id); records.saveCompanionResource(v,expected,source,child) }
            "item-details" -> { val v = campaignJson.decodeFromJsonElement<ItemDetails>(f); identity(v.itemId); records.saveItemDetails(v,expected,source,child) }
            "item-property" -> { val v = campaignJson.decodeFromJsonElement<ItemProperty>(f); identity(v.id); records.saveItemProperty(v,expected,source,child) }
            "wallet" -> { val v = campaignJson.decodeFromJsonElement<PartyWallet>(f); identity(v.id); require(v.campaignId == campaignId)
                require(current == null) { "Use resolved currency adjustments for an existing wallet." }; party.saveWallet(v,expected,source,child) }
            "project" -> { val v = campaignJson.decodeFromJsonElement<InventionProject>(f); identity(v.id); records.saveProject(v,expected,source,child) }
            "stage" -> { val v = campaignJson.decodeFromJsonElement<ProjectStage>(f); identity(v.id); records.saveStage(v,expected,source,child) }
            "requirement" -> { val v = campaignJson.decodeFromJsonElement<MaterialRequirement>(f); identity(v.id); records.saveRequirement(v,expected,source,child) }
            "connection" -> { val v = campaignJson.decodeFromJsonElement<ConnectionEntity>(f); identity(v.id); records.saveEntity(v.copy(imageBase64 = db.recordsDao().entity(v.id)?.imageBase64),expected,source,child) }
            "fact" -> { val v = campaignJson.decodeFromJsonElement<ConnectionFact>(f); identity(v.id); records.saveFact(v,expected,source,child) }
            "milestone" -> { val v = campaignJson.decodeFromJsonElement<ConnectionMilestone>(f); identity(v.id); records.saveMilestone(v,expected,source,child) }
            "link" -> { val v = campaignJson.decodeFromJsonElement<CampaignLink>(f); identity(v.id); records.saveLink(v,expected,source,child) }
            "checkpoint" -> { val v = campaignJson.decodeFromJsonElement<CampaignCheckpoint>(f); identity(v.id); require(v.campaignId == campaignId); party.saveCheckpoint(v,expected,source,child) }
            "reference" -> { val v = campaignJson.decodeFromJsonElement<ExternalRecordReference>(f); identity(v.id); require(v.campaignId == campaignId); party.saveExternalReference(v,expected,source,child) }
            "item" -> {
                val value = campaignJson.decodeFromJsonElement<LedgerItem>(f); identity(value.id)
                val old = db.ledgerDao().getItem(value.id)
                val draft = LedgerDraft(value.title,value.category,value.owner,value.itemState,value.location,value.evidenceStatus,
                    value.quantity,value.description,value.knownProperties,value.possibleUses,value.limitations,source,value.notionPageId,old?.imageBase64,value.archived)
                require(old != null) { "Use acquire_item to establish a new physical identity." }
                require(value.quantity == old.quantity && value.owner == old.owner && value.location == old.location && value.itemState == old.itemState) { "Use typed quantity and movement operations." }
                ledger.update(value.id,op.expectedRevision,draft,source,child)
            }
            else -> error("This record is read-only or requires a dedicated typed operation.")
        }
    }
}

/** Immutable archive projections retain original source identities while bounding each transport record. */
internal object CampaignSyncReferenceProjection {
    const val MAX_ESCAPED_TEXT_BYTES = 16 * 1024
    private val cache = LinkedHashMap<String,List<CampaignSyncRecord>>()
    private var cachedCharacters = 0L
    internal fun parts(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val output = mutableListOf<String>(); var start = 0; var index = 0; var bytes = 0
        while (index < value.length) {
            val point = Character.codePointAt(value,index)
            val chars = Character.charCount(point)
            val cost = when {
                point == 34 || point == 92 || point == 8 || point == 9 || point == 10 || point == 12 || point == 13 -> 2
                point < 32 -> 6
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
            if (bytes + cost > MAX_ESCAPED_TEXT_BYTES && index > start) {
                output += value.substring(start,index); start = index; bytes = 0
            }
            bytes += cost; index += chars
        }
        output += value.substring(start)
        return output
    }
    fun records(reference: CampaignReferenceRecord): List<CampaignSyncRecord> {
        val bodyHash=campaignHash(reference.body); val propertiesHash=campaignHash(reference.propertiesJson)
        val version=campaignHash(campaignJson.encodeToString(listOf(reference.id,reference.campaignId,reference.title,reference.category,reference.sourceUrl,reference.sourceRevision,reference.contentSha256,reference.packageHash,bodyHash,propertiesHash)))
        synchronized(cache){cache[version]?.let{return it}}
        val body = parts(reference.body); val properties = parts(reference.propertiesJson)
        val prefix = "archive:${version.take(40)}"
        val source = reference.sourceUrl.ifBlank { "Original player-known campaign archive" }
        val output = mutableListOf(CampaignSyncRecord("$prefix:meta","reference",1,buildJsonObject {
            put("referenceId",reference.id); put("externalId",reference.externalId); put("campaignId",reference.campaignId)
            put("title",reference.title); put("category",reference.category); put("sourceUrl",reference.sourceUrl)
            put("sourceRevision",reference.sourceRevision); put("contentSha256",reference.contentSha256); put("packageHash",reference.packageHash)
            put("bodySha256",bodyHash); put("propertiesSha256",propertiesHash); put("sourceVersion",version)
            put("bodyBytes",reference.body.toByteArray(Charsets.UTF_8).size); put("propertiesBytes",reference.propertiesJson.toByteArray(Charsets.UTF_8).size)
            put("bodyParts",body.size); put("propertiesParts",properties.size); put("source",source)
        }))
        for ((part,chunks) in listOf("body" to body,"properties" to properties)) chunks.forEachIndexed { index,text ->
            output += CampaignSyncRecord("$prefix:$part:$index","reference",1,buildJsonObject {
                put("referenceId",reference.id); put("externalId",reference.externalId); put("sourceUrl",reference.sourceUrl)
                put("sourceRevision",reference.sourceRevision); put("sourceVersion",version); put("part",part); put("index",index); put("total",chunks.size)
                put("text",text); put("contentHash",campaignHash(text)); put("source",source)
            })
        }
        synchronized(cache) {
            cache.remove(version)?.let {old -> cachedCharacters -= old.sumOf {it.fields["text"]?.jsonPrimitive?.content?.length?.toLong() ?: 0L}}
            cache[version]=output
            cachedCharacters += reference.body.length.toLong()+reference.propertiesJson.length
            while(cache.size>512 || cachedCharacters>8_000_000L) {
                val first=cache.entries.first(); cachedCharacters -= first.value.sumOf {it.fields["text"]?.jsonPrimitive?.content?.length?.toLong() ?: 0L};cache.remove(first.key)
            }
        }
        return output
    }
}


/** Bounded read-only history projections retain the exact full original fields and immutable source IDs. */
internal object CampaignSyncAuditProjection {
    fun records(record: CampaignSyncRecord): List<CampaignSyncRecord> {
        require(record.kind == "audit")
        val content = OwnerSyncProtocol.canonical(record.fields)
        val bytes = content.toByteArray(Charsets.UTF_8).size
        if (bytes <= 30_000) return listOf(record)
        val chunks = CampaignSyncReferenceProjection.parts(content)
        val hash = campaignHash(content)
        val prefix = "audit-part:${campaignHash(record.recordId+":"+hash).take(40)}"
        val source = record.fields["source"] ?: JsonPrimitive("Committed Android campaign history")
        val summary = buildJsonObject {
            val preserved = setOf("id", "commandId", "recordType", "recordId", "itemId", "kind", "evidenceStatus", "detail", "source", "payloadFingerprint", "priorRevision", "resultingRevision", "createdAt")
            record.fields.filterKeys(preserved::contains).forEach { (key,value) -> put(key,value) }
            put("chunked", true); put("originalRecordId", record.recordId); put("originalKind", record.kind)
            put("contentSha256", hash); put("contentBytes", bytes); put("parts", chunks.size); put("partIdPrefix", "$prefix:"); put("encoding", "canonical-json")
        }
        return listOf(record.copy(fields=summary)) + chunks.mapIndexed { index,text -> CampaignSyncRecord("$prefix:$index", "audit", record.revision, buildJsonObject {
            put("originalRecordId", record.recordId); put("originalKind", record.kind); put("contentSha256", hash)
            put("index", index); put("total", chunks.size); put("text", text); put("partSha256", campaignHash(text)); put("source", source)
        }) }
    }
}
