package com.yazsras.astravar.data

import androidx.room.withTransaction
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Reviewed wrapper over the existing craft ledger, in the same Room transaction as gameplay. */
class GenesisCraftingRepository(private val db: CampaignLedgerDb, private val ready: suspend () -> Unit = {}) {
    private val campaign get() = CampaignLedgerRepository(db)
    private val playable get() = ReforgeRepository(db)
    private suspend fun <T> transaction(block: suspend () -> T): T { ready(); return db.withTransaction { block() } }

    suspend fun review(projectId: String, materials: List<MaterialConsumption>, output: LedgerDraft,
        outputOwnerActorId: String, outputContainerItemId: String? = null, source: String): GenesisCraftingReview = transaction {
        checkId(projectId); checkId(outputOwnerActorId)
        require(source.isNotBlank() && source.length <= 2_000) { "Describe the confirmed craft's source." }
        require(materials.size in 1..100 && materials.map { it.itemId }.distinct().size == materials.size) { "Select each consumed material once." }
        val project = requireNotNull(db.recordsDao().project(projectId)) { "The invention is missing." }
        require(project.revision in 1 until Long.MAX_VALUE) { "The invention revision cannot advance safely." }
        val allPlayable = db.reforgeDao().allCharacters().map { it.saved() }
        val allPlacements = db.partyDao().allPlacements()
        val selected = materials.map { use ->
            checkId(use.itemId)
            val item = requireNotNull(db.ledgerDao().getItem(use.itemId)) { "A selected material is missing." }
            val placement = db.partyDao().placement(item.id)
            val authority = readGenesisAuthorityItem(db, item.id)
            check(item.revision == use.expectedRevision) { "A selected material changed. Review its current quantity." }
            require(item.revision in 1 until Long.MAX_VALUE) { "The material revision cannot advance safely." }
            require(use.quantity > 0 && item.quantity != null && item.quantity >= use.quantity && !item.archived &&
                item.itemState in setOf("Carried", "Stored") && (placement == null || placement.state in setOf("Carried", "Stored") && placement.installedProjectId == null)) {
                "Craft only confirmed quantities of carried or stored materials; detach, unequip or review other states first."
            }
            require(allPlacements.none { it.containerItemId == item.id }) { "Move a container's contents before consuming it." }
            val owners = allPlayable.filter { actor -> actor.character.items.any { it.id == item.id } }
            if (authority == null) require(owners.isEmpty()) { "This material also belongs to an unconnected playable sheet. Reconcile its authority before consuming it." }
            else {
                val ownerId = requireNotNull(authority.ownerActorId) { "Assign retained managed material to a connected actor before crafting." }
                require(owners.size == 1 && owners.single().character.id == ownerId) { "Material ownership is missing or ambiguous." }
                val character = owners.single().character
                val physical = character.items.single { it.id == item.id }
                val definition = character.itemDefinitions.single { it.id == physical.definitionId }
                require(physical.state in setOf(ItemState.CARRIED, ItemState.STORED) && !physical.attuned && definition.effects.isEmpty() &&
                    physical.donorInstanceIds.isEmpty() && character.items.none { item.id in it.donorInstanceIds || it.containerId == item.id } &&
                    character.attacks.none { it.requiredItemId == item.id }) {
                    "This craft supports ordinary unattuned materials without effects, contents, composite links or attack dependencies. Review other materials separately."
                }
            }
            GenesisCraftMaterialReview(use, item, placement, authority)
        }
        val actorIds = (selected.mapNotNull { it.authority?.ownerActorId } + outputOwnerActorId).distinct().sorted()
        val actors = actorIds.map { id ->
            val authority = requireNotNull(db.genesisAuthorityDao().actor(id)) { "Connect each affected actor before crafting." }
            require(authority.playable) { "Configure and review the output owner's playable inventory before crafting." }
            genesisRequireAffectedActor(db, authority)
            val saved = requireNotNull(playable.get(id))
            require(!saved.character.archived) { "Restore each affected actor before using its inventory in crafting." }
            GenesisCraftActorReview(authority, saved, requireNotNull(db.recordsDao().characterProfile(id)))
        }
        val recipient = actors.single { it.authority.actorId == outputOwnerActorId }
        outputContainerItemId?.let { containerId ->
            checkId(containerId)
            require(selected.none { it.item.id == containerId }) { "The output container cannot also be consumed." }
            val container = recipient.playable.character.items.singleOrNull { it.id == containerId }
            require(container != null && container.quantity > 0 && container.state in setOf(ItemState.CARRIED, ItemState.STORED) &&
                readGenesisAuthorityItem(db, containerId)?.ownerActorId == outputOwnerActorId) { "Choose an available container in the output owner's current inventory." }
        }
        require(output.quantity != null && output.quantity in 1..1_000_000 && !output.archived && output.itemState.trim() in setOf("Carried", "Stored")) {
            "Confirm a positive plain output quantity, carried or stored. Equip or review effects after crafting."
        }
        val state = if (outputContainerItemId != null) "Stored" else output.itemState.trim()
        val normalized = campaign.validatedDraft(output.copy(owner = recipient.profile.name, itemState = state,
            location = placementLocation(ItemPlacement("preview-output", outputOwnerActorId, outputContainerItemId, state = state))))
        val draft = GenesisCraftingReview(project, selected, normalized, outputOwnerActorId, outputContainerItemId, actors, source, "")
        draft.copy(fingerprint = genesisCraftReviewFingerprint(draft))
    }

    /** Recover only a proven earlier outcome. This never retries or approves a pending craft. */
    suspend fun completed(projectId: String, reviewFingerprint: String, commandId: String): GenesisCraftingOutcome? = transaction {
        checkId(projectId); checkId(commandId)
        require(reviewFingerprint.matches(Regex("[0-9a-f]{64}"))) { "The saved craft review identity is invalid." }
        val receipt = readGenesisAuthorityReceipt(db, commandId)
        if (receipt == null) {
            val child = genesisCraftCommandId(commandId)
            require(db.recordsDao().receipt(commandId) == null && db.ledgerDao().event(commandId) == null &&
                db.reforgeDao().change(commandId) == null && db.reforgeDao().roll(commandId) == null &&
                db.recordsDao().receipt(child) == null && db.recordsDao().craft(child) == null && db.recordsDao().craftConsumptions(child).isEmpty() &&
                db.ledgerDao().event("craft-output-${campaignHash(child).take(40)}") == null &&
                db.recordsDao().receipt("craft-link-${campaignHash(child).take(40)}") == null) {
                "This pending craft identity has conflicting or incomplete saved records. Preserve it and review complete recovery before continuing."
            }
            return@transaction null
        }
        require(receipt.kind == "craft-items" && receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind, receipt.requestJson)) {
            "The pending craft identity belongs to a different or invalid saved operation."
        }
        rejectDuplicateJsonMembers(receipt.requestJson); rejectDuplicateJsonMembers(receipt.outcomeJson)
        val review = campaignJson.decodeFromString<GenesisCraftingReview>(receipt.requestJson)
        require(review.project.id == projectId && review.fingerprint == reviewFingerprint && review.fingerprint == genesisCraftReviewFingerprint(review)) {
            "The saved craft belongs to a different project or frozen review."
        }
        val outcome = genesisValidateCraftReceipt(receipt, genesisCraftEvidence(db, receipt))
        val physical = requireNotNull(readGenesisAuthorityItem(db, outcome.outputId)) { "The recorded crafted output authority is missing. Review complete recovery." }
        require(physical.activationCommandId == commandId && db.ledgerDao().getItem(outcome.outputId) != null &&
            physical.sourceProofJson == campaignJson.encodeToString(genesisCraftProof(commandId, review, outcome))) {
            "The crafted output's original evidence is incomplete. Review complete recovery."
        }
        outcome
    }

    suspend fun accept(review: GenesisCraftingReview, commandId: String = UUID.randomUUID().toString()): GenesisCraftingOutcome = transaction {
        checkId(commandId)
        val request = campaignJson.encodeToString(review)
        validateGenesisAuthorityDocument(request)
        val fingerprint = genesisAuthorityFingerprint("craft-items", request)
        readGenesisAuthorityReceipt(db, commandId)?.let {
            require(it.kind == "craft-items" && it.requestJson == request && it.fingerprint == fingerprint) { "Command identity was reused with different craft content. Nothing changed." }
            return@transaction requireNotNull(completed(review.project.id, review.fingerprint, commandId))
        }
        require(db.recordsDao().receipt(commandId) == null && db.ledgerDao().event(commandId) == null &&
            db.reforgeDao().change(commandId) == null && db.reforgeDao().roll(commandId) == null) { "Craft command identity belongs to another operation." }
        require(review.fingerprint == genesisCraftReviewFingerprint(review)) { "The reviewed craft proof changed." }
        check(this@GenesisCraftingRepository.review(review.project.id, review.materials.map { it.use }, review.output, review.outputOwnerActorId, review.outputContainerItemId, review.source) == review) {
            "The project, material, owner or inventory changed after review. Review the complete craft again."
        }
        val childCommand = genesisCraftCommandId(commandId)
        require(db.recordsDao().receipt(childCommand) == null) { "A craft child exists without its parent proof. Restore or reconcile the complete records." }
        val craftReceipt = withGenesisProjectionDeferred(db) { campaign.records.confirmCraft(genesisCraftLegacyCommand(review, commandId)) }
        val outputId = requireNotNull(craftReceipt.outputId)
        val sourceOutput = requireNotNull(db.ledgerDao().getItem(outputId))
        val execution = requireNotNull(db.recordsDao().craft(childCommand))
        val consumptions = db.recordsDao().craftConsumptions(childCommand)
        val outputLink = requireNotNull(db.recordsDao().link("craft-output-link-${campaignHash(childCommand).take(40)}"))
        val saved = withGenesisProjectionDeferred(db) {
            genesisCraftCharacters(review, sourceOutput).map { character ->
                val before = review.actors.single { it.authority.actorId == character.id }.playable
                playable.save(character.id, before.revision, character, "$GENESIS_AUTHORITY_SOURCE: confirm craft",
                    genesisCraftPlayableCommand(commandId, character.id))
            }
        }
        val outcome = GenesisCraftingOutcome(outputId, craftReceipt, execution, consumptions, outputLink, sourceOutput, saved)
        val proof = campaignJson.encodeToString(genesisCraftProof(commandId, review, outcome))
        withGenesisProjection(db) {
            saved.forEach { after ->
                val before = review.actors.single { it.authority.actorId == after.character.id }.playable
                genesisProjectCharacter(db, before, after, genesisCraftProjectionCommand(commandId, after.character.id),
                    sourceProof = proof.takeIf { after.character.id == review.outputOwnerActorId })
            }
        }
        val physical = requireNotNull(readGenesisAuthorityItem(db, outputId))
        db.genesisAuthorityDao().update(physical.copy(activationCommandId = commandId, sourceProofJson = proof))
        val receipt = GenesisAuthorityReceipt(commandId, "craft-items", fingerprint, request, campaignJson.encodeToString(outcome), System.currentTimeMillis())
        db.genesisAuthorityDao().insert(receipt)
        genesisRequireAuthorityCommand(db, receipt)
        outcome
    }
}
