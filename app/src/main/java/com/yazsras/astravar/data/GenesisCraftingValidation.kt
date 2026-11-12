package com.yazsras.astravar.data

import kotlinx.serialization.encodeToString

internal data class GenesisCraftingEvidence(
    val events: List<LedgerEvent>, val audits: List<CampaignAudit>, val receipts: List<CommandReceipt>,
    val crafts: List<CraftExecution>, val consumptions: List<CraftConsumption>, val changes: List<ReforgeChange>, val links: List<CampaignLink>
)

/** Original frozen material/actor states are checked against immutable children, not later owners. */
internal fun genesisValidateCraftReceipt(receipt: GenesisAuthorityReceipt, evidence: GenesisCraftingEvidence): GenesisCraftingOutcome {
    require(receipt.kind == "craft-items" && receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind, receipt.requestJson))
    val review = campaignJson.decodeFromString<GenesisCraftingReview>(receipt.requestJson)
    val outcome = campaignJson.decodeFromString<GenesisCraftingOutcome>(receipt.outcomeJson)
    require(review.fingerprint == genesisCraftReviewFingerprint(review)) { "Craft review fingerprint is invalid." }
    val command = genesisCraftLegacyCommand(review, receipt.commandId)
    val child = command.commandId
    val legacy = outcome.craftReceipt
    require(legacy == evidence.receipts.singleOrNull { it.commandId == child } && legacy.kind == "CRAFT" &&
        legacy.recordId == review.project.id && legacy.outputId == outcome.outputId &&
        legacy.resultingRevision == review.project.revision + 1 &&
        legacy.fingerprint == campaignHash(campaignJson.encodeToString(command.copy(commandId = "")))) { "Craft child differs from its frozen request." }
    val completed = review.project.copy(status = "Completed", revision = review.project.revision + 1, source = review.source, updatedAt = legacy.createdAt)
    require(campaignJson.decodeFromString<InventionProject>(legacy.outcomeJson) == completed)
    val projectAudit = requireNotNull(evidence.audits.singleOrNull { it.id == child })
    require(projectAudit.commandId == child && projectAudit.recordType == "project" && projectAudit.recordId == review.project.id &&
        projectAudit.kind == "CONFIRMED_CRAFT" && projectAudit.source == review.source &&
        campaignJson.decodeFromString<InventionProject>(requireNotNull(projectAudit.beforeJson)) == review.project &&
        campaignJson.decodeFromString<InventionProject>(requireNotNull(projectAudit.afterJson)) == completed) { "Original craft project proof is missing or changed." }
    require(outcome.execution == evidence.crafts.singleOrNull { it.commandId == child } &&
        outcome.execution == CraftExecution(child, review.project.id, outcome.outputId, review.source, legacy.createdAt)) { "Original craft execution differs." }
    require(review.materials.size in 1..100 && review.materials.map { it.use.itemId }.distinct().size == review.materials.size)
    require(outcome.consumptions.size == review.materials.size && evidence.consumptions.filter { it.executionId == child }.sortedBy { it.id } == outcome.consumptions)
    review.materials.forEachIndexed { index, material ->
        val use = material.use; val before = material.item; val quantity = requireNotNull(before.quantity)
        require(use.itemId == before.id && use.expectedRevision == before.revision && use.quantity in 1..quantity &&
            !before.archived && before.itemState in setOf("Carried", "Stored"))
        val consumption = CraftConsumption("$child:$index", child, before.id, use.quantity, quantity,
            quantity - use.quantity, before.revision, before.revision + 1)
        require(outcome.consumptions.singleOrNull { it.id == consumption.id } == consumption) { "Craft consumption differs from the reviewed quantity." }
        val depleted = material.placement?.takeIf { consumption.afterQuantity == 0 }?.let { depletedPlacement(it, review.source, legacy.createdAt) }
        val after = before.copy(quantity = consumption.afterQuantity, itemState = if (consumption.afterQuantity == 0) "Consumed" else before.itemState,
            revision = before.revision + 1, updatedAt = legacy.createdAt, location = depleted?.let(::placementLocation) ?: before.location)
        val event = requireNotNull(evidence.events.singleOrNull { it.commandId == "craft-${campaignHash(child).take(40)}-$index" })
        require(event.itemId == before.id && event.kind == "CRAFT_CONSUMPTION" && event.source == review.source &&
            event.payloadFingerprint == legacy.fingerprint && event.priorRevision == before.revision && event.resultingRevision == after.revision &&
            campaignJson.decodeFromString<LedgerItem>(requireNotNull(event.beforeJson)) == before &&
            campaignJson.decodeFromString<LedgerItem>(requireNotNull(event.afterJson)) == after) { "Material source history differs from its original craft consumption." }
        val audit = requireNotNull(evidence.audits.singleOrNull { it.id == "$child:material:$index" })
        require(audit.commandId == child && audit.recordType == "item" && audit.recordId == before.id && audit.kind == "CRAFT_CONSUMPTION" &&
            audit.source == review.source && audit.beforeJson == event.beforeJson && audit.afterJson == event.afterJson)
        if (depleted != null) {
            val placementAudit = requireNotNull(evidence.audits.singleOrNull { it.id == "$child:placement:$index" })
            require(placementAudit.commandId == child && placementAudit.recordId == before.id && placementAudit.recordType == "item-placement" &&
                campaignJson.decodeFromString<ItemPlacement>(requireNotNull(placementAudit.beforeJson)) == material.placement &&
                campaignJson.decodeFromString<ItemPlacement>(requireNotNull(placementAudit.afterJson)) == depleted) { "Depleted material placement proof is missing." }
        }
    }
    val output = outcome.sourceOutput
    val requested = review.output
    require(output.id == outcome.outputId && output.revision == 1L && output.updatedAt == output.createdAt &&
        output.title == requested.title && output.category == requested.category && output.owner == requested.owner &&
        output.itemState == requested.itemState && output.location == requested.location && output.evidenceStatus == requested.evidenceStatus &&
        output.quantity == requested.quantity && output.description == requested.description && output.knownProperties == requested.knownProperties &&
        output.possibleUses == requested.possibleUses && output.limitations == requested.limitations && output.source == requested.source &&
        output.notionPageId == requested.notionPageId && output.imageBase64 == requested.imageBase64 && !output.archived && !requested.archived &&
        output.quantity != null && output.quantity in 1..1_000_000 && output.itemState in setOf("Carried", "Stored")) { "Crafted output differs from its reviewed plain record." }
    val outputEvent = requireNotNull(evidence.events.singleOrNull { it.commandId == "craft-output-${campaignHash(child).take(40)}" })
    require(outputEvent.kind == "CREATE" && outputEvent.itemId == output.id && outputEvent.beforeJson == null &&
        outputEvent.payloadFingerprint == campaignHash(campaignJson.encodeToString(listOf("CREATE", campaignJson.encodeToString(requested)))) &&
        campaignJson.decodeFromString<LedgerItem>(requireNotNull(outputEvent.afterJson)) == output) { "Craft output lost its original physical creation proof." }
    val link = outcome.outputLink
    require(evidence.links.any { it.id == link.id }) { "Craft output's retained project relationship is missing." }
    require(link.id == "craft-output-link-${campaignHash(child).take(40)}" && link.fromType == "project" && link.fromId == review.project.id &&
        link.toType == "item" && link.toId == output.id && link.label == "Confirmed craft output" && link.revision == 1L && link.source == review.source)
    val linkReceipt = requireNotNull(evidence.receipts.singleOrNull { it.commandId == "craft-link-${campaignHash(child).take(40)}" })
    require(linkReceipt.kind == "link" && linkReceipt.recordId == link.id && campaignJson.decodeFromString<CampaignLink>(linkReceipt.outcomeJson) == link) {
        "Craft output's original project relationship is missing."
    }
    val expectedActors = (review.materials.mapNotNull { it.authority?.ownerActorId } + review.outputOwnerActorId).distinct().sorted()
    require(review.actors.map { it.authority.actorId } == expectedActors && outcome.actors.map { it.character.id } == expectedActors)
    val expectedCharacters = genesisCraftCharacters(review, output).associateBy { it.id }
    review.actors.forEach { actor ->
        val id = actor.authority.actorId
        require(actor.authority.playable && actor.playable.character.id == id && actor.profile.id == id &&
            !actor.playable.character.archived &&
            actor.authority.playableRevision == actor.playable.revision && actor.authority.projectionFingerprint == genesisProjectionFingerprint(actor.playable))
        val change = requireNotNull(evidence.changes.singleOrNull { it.commandId == genesisCraftPlayableCommand(receipt.commandId, id) })
        val saved = outcome.actors.single { it.character.id == id }
        val expected = expectedCharacters.getValue(id)
        require(change.outcomeType == "CHARACTER" && change.profileId == id && change.priorRevision == actor.playable.revision &&
            change.resultingRevision == actor.playable.revision + 1 &&
            reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(change.beforeJson)) == actor.playable && reforgeChangeCharacter(change) == saved &&
            saved.character == expected.copy(revision = actor.playable.revision + 1) && saved.origin == actor.playable.origin && saved.createdAt == actor.playable.createdAt) {
            "Crafted gameplay delta differs from its frozen actor state or changed unrelated mechanics."
        }
    }
    review.materials.forEach { material -> material.authority?.let { authority ->
        val before = review.actors.single { it.authority.actorId == authority.ownerActorId }.playable.character
        val instance = before.items.single { it.id == material.item.id }
        val definition = before.itemDefinitions.single { it.id == instance.definitionId }
        require(authority.itemId == instance.id && reforgeJson.decodeFromString<com.yazsras.astravar.rules.reforge.ItemInstance>(authority.instanceJson) == instance &&
            reforgeJson.decodeFromString<com.yazsras.astravar.rules.reforge.ItemDefinition>(authority.definitionJson) == definition &&
            material.placement?.holderProfileId == before.id && instance.quantity == material.item.quantity && !instance.attuned && definition.effects.isEmpty() &&
            instance.donorInstanceIds.isEmpty() && before.items.none { instance.id in it.donorInstanceIds || it.containerId == instance.id } &&
            before.attacks.none { it.requiredItemId == instance.id }) { "Crafted material's original mechanical identity is inconsistent." }
    } }
    val owner = review.actors.single { it.authority.actorId == review.outputOwnerActorId }
    require(requested.owner == owner.profile.name && requested.location == placementLocation(ItemPlacement("preview-output", owner.authority.actorId,
        review.outputContainerItemId, state = requested.itemState)))
    review.outputContainerItemId?.let { container ->
        require(owner.playable.character.items.any { it.id == container && it.quantity > 0 } && review.materials.none { it.item.id == container })
    }
    return outcome
}

internal suspend fun genesisCraftEvidence(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt): GenesisCraftingEvidence {
    val review = campaignJson.decodeFromString<GenesisCraftingReview>(receipt.requestJson)
    val outcome = campaignJson.decodeFromString<GenesisCraftingOutcome>(receipt.outcomeJson)
    val sql = db.openHelper.writableDatabase
    fun <T> row(table: String, columns: String, keyColumn: String, key: String, decode: (GenesisBackupRow) -> T): T? =
        GenesisBackupRow.read(sql, table, columns.split(','), keyColumn, keyColumn, key, decode).singleOrNull()
    val child = genesisCraftCommandId(receipt.commandId)
    val eventIds = review.materials.indices.map { "craft-${campaignHash(child).take(40)}-$it" } + "craft-output-${campaignHash(child).take(40)}"
    val events = eventIds.mapNotNull { id -> row("ledger_events", "commandId,itemId,kind,evidenceStatus,detail,source,payloadFingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson", "commandId", id) {
        LedgerEvent(it.text("commandId"), it.text("itemId"), it.text("kind"), it.text("evidenceStatus"), it.text("detail"), it.text("source"),
            it.text("payloadFingerprint"), it.long("priorRevision"), it.long("resultingRevision"), it.long("createdAt"), it.optionalText("beforeJson"), it.optionalText("afterJson"))
    } }
    val auditIds = listOf(child) + review.materials.indices.flatMap { listOf("$child:material:$it", "$child:placement:$it") }
    val audits = auditIds.mapNotNull { id -> row("campaign_audit", "id,commandId,recordType,recordId,kind,source,priorRevision,resultingRevision,createdAt,beforeJson,afterJson", "id", id) {
        CampaignAudit(it.text("id"), it.text("commandId"), it.text("recordType"), it.text("recordId"), it.text("kind"), it.text("source"),
            it.long("priorRevision"), it.long("resultingRevision"), it.long("createdAt"), it.optionalText("beforeJson"), it.optionalText("afterJson"))
    } }
    val changes = review.actors.mapNotNull { actor -> row("reforge_changes", "commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType",
        "commandId", genesisCraftPlayableCommand(receipt.commandId, actor.authority.actorId)) {
        ReforgeChange(it.text("commandId"), it.text("profileId"), it.text("operation"), it.text("fingerprint"), it.long("priorRevision"),
            it.long("resultingRevision"), it.long("createdAt"), it.optionalText("beforeJson"), it.text("afterJson"), it.text("outcomeType"))
    } }
    return GenesisCraftingEvidence(events, audits, listOfNotNull(db.recordsDao().receipt(child),
        db.recordsDao().receipt("craft-link-${campaignHash(child).take(40)}")), listOfNotNull(db.recordsDao().craft(child)),
        db.recordsDao().craftConsumptions(child), changes, listOfNotNull(db.recordsDao().link(outcome.outputLink.id)))
}

internal suspend fun genesisRequireCraftCommand(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt) {
    val review = campaignJson.decodeFromString<GenesisCraftingReview>(receipt.requestJson)
    val outcome = campaignJson.decodeFromString<GenesisCraftingOutcome>(receipt.outcomeJson)
    val child = genesisCraftCommandId(receipt.commandId)
    val evidence = genesisCraftEvidence(db, receipt)
    require(genesisValidateCraftReceipt(receipt, evidence) == outcome)
    require(db.recordsDao().project(review.project.id) == campaignJson.decodeFromString<InventionProject>(outcome.craftReceipt.outcomeJson)) {
        "The completed craft project failed current-record readback."
    }
    review.materials.forEachIndexed { index, material ->
        val event = evidence.events.single { it.commandId == "craft-${campaignHash(child).take(40)}-$index" }
        require(db.ledgerDao().getItem(material.item.id) == campaignJson.decodeFromString<LedgerItem>(requireNotNull(event.afterJson))) {
            "The consumed source material failed current-quantity readback."
        }
        val remaining = requireNotNull(material.item.quantity) - material.use.quantity
        val expectedPlacement = material.placement?.let { if (remaining == 0) depletedPlacement(it, review.source, outcome.craftReceipt.createdAt) else it }
        require(db.partyDao().placement(material.item.id) == expectedPlacement) { "The consumed material placement failed readback." }
    }
    val physical = requireNotNull(readGenesisAuthorityItem(db, outcome.outputId))
    require(physical.activationCommandId == receipt.commandId && physical.ownerActorId == review.outputOwnerActorId &&
        physical.sourceProofJson == campaignJson.encodeToString(genesisCraftProof(receipt.commandId, review, outcome)))
    outcome.actors.forEach { actor ->
        require(ReforgeRepository(db).get(actor.character.id) == actor)
        genesisRequireAffectedActor(db, requireNotNull(db.genesisAuthorityDao().actor(actor.character.id)))
    }
}
