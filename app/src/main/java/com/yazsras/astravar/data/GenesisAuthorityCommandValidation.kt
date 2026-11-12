package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.serialization.encodeToString

/**
 * A normal command validates its own receipt and affected records, not an export of unrelated
 * retained source archives. Complete backup still validates the entire portable graph and its cap.
 * Call only inside the command's Room transaction so failed readback rolls back every child write.
 */
internal suspend fun genesisRequireAuthorityCommand(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt) {
    validateGenesisAuthorityDocument(campaignJson.encodeToString(receipt))
    require(readGenesisAuthorityReceipt(db, receipt.commandId) == receipt) { "The connected command receipt failed independent readback." }
    genesisCheckAuthorityReceiptHeader(db, receipt)
    when (receipt.kind) {
        "create-campaign" -> {
            val input = genesisAuthorityRequest(receipt, 2)
            val outcome = campaignJson.decodeFromString<GenesisCampaignRecord>(receipt.outcomeJson)
            require(outcome.commandId == receipt.commandId && outcome.name == input[0] && outcome.notes == input[1] && outcome.revision == 1L)
            require(db.genesisAuthorityDao().campaign(outcome.id) == outcome) { "Campaign creation failed current-record readback." }
        }
        "create-actor", "register-actor", "accept-actor" -> {
            val outcome = genesisRequireActorDecision(db, receipt)
            require(db.genesisAuthorityDao().actor(outcome.actor.actorId) == outcome.actor &&
                db.recordsDao().characterProfile(outcome.actor.actorId) == outcome.profile) { "Actor creation failed current-record readback." }
            outcome.playable?.let { require(ReforgeRepository(db).get(outcome.actor.actorId) == it) }
            genesisRequireAffectedActor(db, outcome.actor)
        }
        "update-association" -> {
            val input = genesisAuthorityRequest(receipt, 5)
            val outcome = campaignJson.decodeFromString<GenesisActorAuthority>(receipt.outcomeJson)
            require(outcome.actorId == input[0] && outcome.role.name == input[2] && outcome.campaignId == input[3] && outcome.ownerActorId == input[4] &&
                outcome.lastCommandId == receipt.commandId && outcome.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L))
            require(db.genesisAuthorityDao().actor(outcome.actorId) == outcome) { "Membership change failed current-record readback." }
            genesisRequireAffectedActor(db, outcome)
        }
        "create-item" -> {
            val input = genesisAuthorityRequest(receipt, 4)
            val definition = reforgeJson.decodeFromString<ItemDefinition>(requireNotNull(input[2]))
            val instance = reforgeJson.decodeFromString<ItemInstance>(requireNotNull(input[3]))
            val outcome = reforgeJson.decodeFromString<ReforgeSavedCharacter>(receipt.outcomeJson)
            require(outcome.character.id == input[0] && outcome.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L) &&
                outcome.character.items.singleOrNull { it.id == instance.id } == instance &&
                outcome.character.itemDefinitions.singleOrNull { it.id == definition.id } == definition) { "Created item differs from its requested definition or physical identity." }
            genesisRequirePlayableDecision(db, receipt.commandId, outcome)
            require(ReforgeRepository(db).get(outcome.character.id) == outcome) { "Item creation failed playable readback." }
            require(readGenesisAuthorityItem(db, instance.id)?.activationCommandId == receipt.commandId) { "Created item lost its original activation decision." }
            genesisRequireAffectedActor(db, requireNotNull(db.genesisAuthorityDao().actor(outcome.character.id)))
        }
        else -> error("Unsupported connected command kind.")
    }
}

private fun genesisAuthorityRequest(receipt: GenesisAuthorityReceipt, count: Int) =
    campaignJson.decodeFromString<List<String?>>(receipt.requestJson).also { require(it.size == count) { "Unsupported connected request shape." } }

private suspend fun genesisCheckAuthorityReceiptHeader(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt) {
    checkId(receipt.commandId)
    require(receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind, receipt.requestJson)) { "Connected request fingerprint does not match its original content." }
    rejectDuplicateJsonMembers(receipt.requestJson)
    rejectDuplicateJsonMembers(receipt.outcomeJson)
    require(db.recordsDao().receipt(receipt.commandId) == null && db.ledgerDao().event(receipt.commandId) == null &&
        db.reforgeDao().change(receipt.commandId) == null && db.reforgeDao().roll(receipt.commandId) == null) { "Connected command identity also belongs to another saved domain." }
}

private suspend fun genesisRequirePlayableDecision(db: CampaignLedgerDb, commandId: String, saved: ReforgeSavedCharacter) {
    validateCharacter(saved.character)
    val child = requireNotNull(GenesisBackupRow.read(db.openHelper.writableDatabase, "reforge_changes",
        "commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType".split(','),
        "commandId", keyColumn = "commandId", key = genesisChild(commandId, "playable")) {
        ReforgeChange(it.text("commandId"), it.text("profileId"), it.text("operation"), it.text("fingerprint"),
            it.long("priorRevision"), it.long("resultingRevision"), it.long("createdAt"), it.optionalText("beforeJson"),
            it.text("afterJson"), it.text("outcomeType"))
    }.singleOrNull()) { "Original playable child receipt is missing." }
    require(reforgeChangeCharacter(child) == saved) { "Connected outcome differs from its immutable playable receipt." }
}

/** Historical activation is compared to its frozen request, never to later edited mechanics. */
internal suspend fun genesisRequireActorDecision(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt): GenesisActorCreationOutcome {
    genesisCheckAuthorityReceiptHeader(db, receipt)
    val outcome = campaignJson.decodeFromString<GenesisActorCreationOutcome>(receipt.outcomeJson)
    val actor = outcome.actor
    require(actor.activationCommandId == receipt.commandId && actor.lastCommandId == receipt.commandId &&
        actor.actorId == outcome.profile.id && actor.playable == (outcome.playable != null)) { "Actor outcome is not linked to its original authority decision." }
    outcome.playable?.let {
        require(it.character.id == actor.actorId && actor.playableRevision == it.revision)
        genesisRequirePlayableDecision(db, receipt.commandId, it)
    }
    // Only the matching immutable source outcome is needed; archive text and other actors are not read.
    val sourcePresent = db.openHelper.writableDatabase.query(
        "SELECT EXISTS(SELECT 1 FROM campaign_audit WHERE recordType='character-profile' AND recordId=? AND afterJson=?)",
        arrayOf(actor.actorId, campaignJson.encodeToString(outcome.profile))).use { it.moveToFirst(); it.getInt(0) == 1 }
    require(sourcePresent) { "Original source profile outcome is missing from immutable history." }
    when (receipt.kind) {
        "create-actor" -> {
            val input = genesisAuthorityRequest(receipt, 5)
            require(outcome.profile.name == input[0] && actor.role.name == input[1] && actor.campaignId == input[2] && actor.ownerActorId == input[3] && actor.revision == 1L)
            val requested = input[4]?.let { reforgeJson.decodeFromString<ReforgeCharacter>(it) }
            require((requested == null) == (outcome.playable == null))
            requested?.let { require(outcome.playable?.character == it.copy(revision = 1)) { "Actor creation changed requested mechanics." } }
        }
        "register-actor" -> {
            val input = genesisAuthorityRequest(receipt, 5)
            require(actor.actorId == input[0] && actor.role.name == input[2] && actor.campaignId == input[3] && actor.ownerActorId == input[4] && actor.revision == 1L && !actor.playable)
            require(outcome.profile.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L))
        }
        "accept-actor" -> {
            val review = campaignJson.decodeFromString<GenesisActorAuthorityReview>(receipt.requestJson)
            require(review.fingerprint == genesisActorAuthorityReviewFingerprint(review)) { "Reviewed source fingerprint is invalid." }
            genesisValidateLegacyModifierResolutions(review.playable.character, review.source?.abilities.orEmpty(), review.legacyModifierResolutions)
            require(actor.actorId == review.actorId && actor.role == review.role && actor.campaignId == review.campaignId && actor.ownerActorId == review.ownerActorId &&
                actor.revision == Math.addExact(review.expectedAuthorityRevision ?: 0L, 1L))
            val result = requireNotNull(outcome.playable)
            require(result.character == review.playable.character.copy(revision = Math.addExact(review.playable.revision, 1L)) &&
                result.origin == review.playable.origin && result.createdAt == review.playable.createdAt) { "Authority acceptance changed unreviewed mechanics or provenance." }
        }
        else -> error("Actor has no explicit activation decision.")
    }
    return outcome
}

internal suspend fun genesisRequireAffectedActor(db: CampaignLedgerDb, actor: GenesisActorAuthority) {
    val profile = requireNotNull(db.recordsDao().characterProfile(actor.actorId)) { "Connected actor profile is missing." }
    require(actor.revision > 0 && actor.createdAt <= actor.updatedAt)
    if (actor.role == GenesisActorRole.COMPANION) require(db.recordsDao().companion(actor.actorId)?.name == profile.name)
    if (actor.role == GenesisActorRole.NPC) require(db.recordsDao().entity(actor.actorId)?.name == profile.name)
    actor.campaignId?.let { require(db.genesisAuthorityDao().campaign(it) != null) { "Connected campaign membership is missing." } }
    val links = db.recordsDao().activeLinks().filter { it.fromType == "companion" && it.fromId == actor.actorId && it.toType == "character-profile" && it.label == "Companion of" }
    if (actor.role == GenesisActorRole.COMPANION && actor.ownerActorId != null) {
        require(links.size == 1 && links.single().toId == actor.ownerActorId)
        require(db.recordsDao().characterProfile(actor.ownerActorId) != null)
        require(actor.campaignId == null || db.genesisAuthorityDao().actor(actor.ownerActorId)?.campaignId == actor.campaignId)
    } else require(actor.ownerActorId == null && links.isEmpty())
    val seen = mutableSetOf(actor.actorId)
    var owner = actor.ownerActorId
    while (owner != null) { require(seen.add(owner)) { "Companion ownership forms a cycle." }; owner = db.genesisAuthorityDao().actor(owner)?.ownerActorId }
    val activation = genesisRequireActorDecision(db, requireNotNull(readGenesisAuthorityReceipt(db, actor.activationCommandId)))
    require(activation.actor.actorId == actor.actorId && activation.actor.playable == actor.playable)
    val lastReceipt = requireNotNull(readGenesisAuthorityReceipt(db, actor.lastCommandId)) { "Latest membership decision is missing." }
    genesisCheckAuthorityReceiptHeader(db, lastReceipt)
    val last = if (lastReceipt.kind == "update-association") {
        val input = genesisAuthorityRequest(lastReceipt, 5)
        campaignJson.decodeFromString<GenesisActorAuthority>(lastReceipt.outcomeJson).also {
            require(it.actorId == input[0] && it.role.name == input[2] && it.campaignId == input[3] && it.ownerActorId == input[4] &&
                it.lastCommandId == lastReceipt.commandId && it.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L))
        }
    } else genesisRequireActorDecision(db, lastReceipt).actor
    require(last.copy(playableRevision = null, projectionFingerprint = null) == actor.copy(playableRevision = null, projectionFingerprint = null)) {
        "Current membership differs from its latest immutable decision."
    }
    rejectDuplicateJsonMembers(actor.resourceBindingsJson)
    val bindings = campaignJson.decodeFromString<Map<String, String>>(actor.resourceBindingsJson)
    require(bindings.size <= 10_000 && bindings.values.distinct().size == bindings.size && actor.resourceBindingsJson == activation.actor.resourceBindingsJson)
    bindings.forEach { (pool, source) -> checkId(pool); checkId(source); require(activation.playable?.character?.resources?.any { it.id == pool } == true) }
    if (!actor.playable) {
        require(bindings.isEmpty() && actor.playableRevision == null && actor.projectionFingerprint == null)
        return
    }
    val current = requireNotNull(ReforgeRepository(db).get(actor.actorId))
    val original = requireNotNull(activation.playable)
    require(current.origin == original.origin && current.createdAt == original.createdAt) { "Connected actor origin differs from its activation decision." }
    genesisRequireProjection(db, actor.actorId, current)
    current.character.items.forEach { instance ->
        val item = requireNotNull(readGenesisAuthorityItem(db, instance.id)) { "Connected physical item authority is missing." }
        require(item.ownerActorId == actor.actorId && reforgeJson.decodeFromString<ItemInstance>(item.instanceJson) == instance &&
            reforgeJson.decodeFromString<ItemDefinition>(item.definitionJson) == current.character.itemDefinitions.single { it.id == instance.definitionId }) {
            "Connected physical item differs from the owner's current equipment."
        }
    }
}
