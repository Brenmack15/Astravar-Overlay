package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.serialization.encodeToString

/** Validate portable authority only where explicit enrollment exists; old differences remain evidence. */
internal fun genesisAuthorityBackupErrors(
    authority: GenesisAuthorityExport, campaign: LedgerExport, playable: ReforgeBackup
): List<String> {
    val errors = mutableListOf<String>()
    fun verify(label: String, work: () -> Unit) {
        try { work() } catch (e: Exception) { errors += "$label: ${e.message ?: "invalid connected proof"}" }
    }
    val campaigns = authority.campaigns.associateBy { it.id }
    val actors = authority.actors.associateBy { it.actorId }
    val items = authority.items.associateBy { it.itemId }
    val receipts = authority.receipts.associateBy { it.commandId }
    val profiles = campaign.records.characterProfiles.associateBy { it.id }
    val ledgerItems = campaign.items.associateBy { it.id }
    val saved = playable.characters.mapNotNull { row ->
        try { row.profileId to row.saved() } catch (e: Exception) { errors += "Playable ${row.profileId}: ${e.message}"; null }
    }.toMap()
    val actorProofs = mutableMapOf<String, GenesisActorAuthority>()
    val actorOutcomes = mutableMapOf<String, GenesisActorCreationOutcome>()
    val campaignProofs = mutableMapOf<String, GenesisCampaignRecord>()
    val itemProofs = mutableMapOf<String, ReforgeSavedCharacter>()
    val playableHistory = playable.changes.associateBy { it.commandId }
    val unrelatedCommands = (campaign.events.map { it.commandId } + campaign.records.receipts.map { it.commandId } +
        campaign.records.audit.map { it.id } + playable.changes.map { it.commandId } + playable.rolls.map { it.id }).toSet()
    authority.receipts.forEach { receipt -> verify("Authority receipt ${receipt.commandId}") {
        checkId(receipt.commandId)
        require(receipt.commandId !in unrelatedCommands) { "Command identity also belongs to a different saved domain." }
        require(receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind, receipt.requestJson)) { "Request fingerprint does not match its original content." }
        rejectDuplicateJsonMembers(receipt.requestJson)
        rejectDuplicateJsonMembers(receipt.outcomeJson)
        fun request(count: Int) = campaignJson.decodeFromString<List<String?>>(receipt.requestJson).also {
            require(it.size == count) { "Unsupported request shape." }
        }
        when (receipt.kind) {
            "create-campaign" -> {
                val input = request(2)
                val outcome = campaignJson.decodeFromString<GenesisCampaignRecord>(receipt.outcomeJson)
                require(outcome.commandId == receipt.commandId && outcome.name == input[0] && outcome.notes == input[1] && outcome.revision == 1L) { "Campaign outcome differs from its creation request." }
                require(outcome.id in campaigns) { "Created campaign identity is missing from portable authority." }
                campaignProofs[receipt.commandId] = outcome
            }
            "create-actor", "register-actor", "accept-actor" -> {
                val outcome = campaignJson.decodeFromString<GenesisActorCreationOutcome>(receipt.outcomeJson)
                val actor = outcome.actor
                require(actor.actorId in actors && actor.actorId in profiles) { "Created or reviewed actor identity is missing." }
                require(actor.activationCommandId == receipt.commandId && actor.lastCommandId == receipt.commandId &&
                    actor.actorId == outcome.profile.id && actor.playable == (outcome.playable != null)) { "Actor outcome is not linked to its authority decision." }
                outcome.playable?.let {
                    require(it.character.id == actor.actorId && actor.playableRevision == it.revision) { "Playable outcome identity or revision differs." }
                    validateCharacter(it.character)
                    val child = requireNotNull(playableHistory[genesisChild(receipt.commandId, "playable")]) { "Original playable child receipt is missing." }
                    require(reforgeChangeCharacter(child) == it) { "Playable authority outcome differs from its immutable child receipt." }
                }
                require(campaign.records.audit.any { audit ->
                    audit.recordType == "character-profile" && audit.recordId == actor.actorId &&
                        runCatching { campaignJson.decodeFromString<CharacterProfile>(requireNotNull(audit.afterJson)) }.getOrNull() == outcome.profile
                }) { "Original source profile outcome is missing from immutable history." }
                when (receipt.kind) {
                    "create-actor" -> {
                        val input = request(5)
                        require(outcome.profile.name == input[0] && actor.role.name == input[1] && actor.campaignId == input[2] && actor.ownerActorId == input[3] && actor.revision == 1L)
                        val requested = input[4]?.let { reforgeJson.decodeFromString<ReforgeCharacter>(it) }
                        require((requested == null) == (outcome.playable == null))
                        requested?.let { require(outcome.playable?.character == it.copy(revision = 1)) { "Actor creation changed requested mechanics." } }
                    }
                    "register-actor" -> {
                        val input = request(5)
                        require(actor.actorId == input[0] && actor.role.name == input[2] && actor.campaignId == input[3] && actor.ownerActorId == input[4] && actor.revision == 1L && !actor.playable)
                        require(outcome.profile.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L)) { "Registration has no matching source revision." }
                    }
                    else -> {
                        val review = campaignJson.decodeFromString<GenesisActorAuthorityReview>(receipt.requestJson)
                        require(review.fingerprint == genesisActorAuthorityReviewFingerprint(review)) { "Reviewed source proof fingerprint is invalid." }
                        // Validate the original frozen decision, not today's actor after equipment or advancement changes.
                        genesisValidateLegacyModifierResolutions(review.playable.character, review.source?.abilities.orEmpty(), review.legacyModifierResolutions)
                        require(actor.actorId == review.actorId && actor.role == review.role && actor.campaignId == review.campaignId && actor.ownerActorId == review.ownerActorId &&
                            actor.revision == Math.addExact(review.expectedAuthorityRevision ?: 0L, 1L)) { "Authority acceptance changed the reviewed association." }
                        val result = requireNotNull(outcome.playable)
                        require(result.character == review.playable.character.copy(revision = Math.addExact(review.playable.revision, 1L)) &&
                            result.origin == review.playable.origin && result.createdAt == review.playable.createdAt) { "Authority acceptance changed unreviewed mechanics or original provenance." }
                    }
                }
                actorProofs[receipt.commandId] = actor
                actorOutcomes[receipt.commandId] = outcome
            }
            "update-association" -> {
                val input = request(5)
                val outcome = campaignJson.decodeFromString<GenesisActorAuthority>(receipt.outcomeJson)
                require(outcome.actorId in actors && outcome.actorId in profiles) { "Changed actor identity is missing." }
                require(outcome.actorId == input[0] && outcome.role.name == input[2] && outcome.campaignId == input[3] && outcome.ownerActorId == input[4] &&
                    outcome.lastCommandId == receipt.commandId && outcome.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L)) { "Association outcome differs from its request." }
                actorProofs[receipt.commandId] = outcome
            }
            "create-item", "create-items" -> {
                val input = request(4)
                val definition = reforgeJson.decodeFromString<ItemDefinition>(requireNotNull(input[2]))
                val instances = if (receipt.kind == "create-items") reforgeJson.decodeFromString<List<ItemInstance>>(requireNotNull(input[3]))
                    else listOf(reforgeJson.decodeFromString<ItemInstance>(requireNotNull(input[3])))
                val outcome = reforgeJson.decodeFromString<ReforgeSavedCharacter>(receipt.outcomeJson)
                require(instances.all { it.id in items }) { "Created physical identity is missing from portable authority." }
                require(outcome.character.id == input[0] && outcome.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L) &&
                    instances.all { instance -> outcome.character.items.singleOrNull { it.id == instance.id } == instance } &&
                    outcome.character.itemDefinitions.singleOrNull { it.id == definition.id } == definition) { "Created item differs from its reviewed request." }
                validateCharacter(outcome.character)
                val child = requireNotNull(playableHistory[genesisChild(receipt.commandId, "playable")]) { "Created item's playable receipt is missing." }
                require(reforgeChangeCharacter(child) == outcome) { "Created item outcome differs from its playable receipt." }
                if (receipt.kind == "create-items") {
                    val before = reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(child.beforeJson))
                    require(outcome.character == genesisCreatedItemsCharacter(before.character, definition, instances).copy(revision = outcome.revision) &&
                        outcome.origin == before.origin && outcome.createdAt == before.createdAt) { "Item creation changed unreviewed gameplay or original provenance." }
                }
                itemProofs[receipt.commandId] = outcome
            }
            "craft-items" -> {
                val outcome = genesisValidateCraftReceipt(receipt, GenesisCraftingEvidence(campaign.events, campaign.records.audit,
                    campaign.records.receipts, campaign.records.crafts, campaign.records.consumptions, playable.changes, campaign.records.links))
                val review = campaignJson.decodeFromString<GenesisCraftingReview>(receipt.requestJson)
                require(outcome.outputId in items) { "Crafted physical output authority is missing." }
                itemProofs[receipt.commandId] = outcome.actors.single { it.character.id == review.outputOwnerActorId }
            }
            GENESIS_ENCOUNTER_COMMAND -> genesisValidateEncounterReceipt(receipt, receipts::get)
            else -> error("Unsupported authority receipt kind.")
        }
    } }
    authority.campaigns.forEach { value -> verify("Campaign ${value.id}") {
        checkId(value.id)
        require(value.name.isNotBlank() && value.name.length <= 120 && value.notes.length <= 12_000 && value.revision > 0 && value.createdAt <= value.updatedAt)
        require(campaignProofs[value.commandId] == value) { "Campaign identity differs from its original authority receipt." }
    } }
    val activeOwnerLinks = campaign.records.links.filter { link ->
        link.fromType == "companion" && link.toType == "character-profile" && link.label == "Companion of" &&
            campaign.records.audit.none { it.recordType == "link" && it.recordId == link.id && it.kind == "UNLINK" && it.resultingRevision == link.revision }
    }.groupBy { it.fromId }
    fun association(actor: GenesisActorAuthority) {
        checkId(actor.actorId)
        require(actor.actorId in profiles) { "Campaign actor identity is missing." }
        require(actor.revision > 0 && actor.createdAt <= actor.updatedAt)
        val profile = requireNotNull(profiles[actor.actorId])
        if (actor.role == GenesisActorRole.COMPANION) require(campaign.records.companionStates.singleOrNull { it.companionId == actor.actorId }?.name == profile.name) { "Companion name differs from its canonical campaign identity." }
        if (actor.role == GenesisActorRole.NPC) require(campaign.records.entities.singleOrNull { it.id == actor.actorId }?.name == profile.name) { "NPC name differs from its canonical campaign identity." }
        val ownerLinks = activeOwnerLinks[actor.actorId].orEmpty()
        if (actor.role == GenesisActorRole.COMPANION && actor.ownerActorId != null) require(ownerLinks.size == 1 && ownerLinks.single().toId == actor.ownerActorId) { "Active companion ownership link differs from its explicit owner." }
        else require(ownerLinks.isEmpty()) { "An unassigned actor still has an active companion ownership link." }
        actor.campaignId?.let { require(it in campaigns) { "Campaign membership refers to a missing campaign." } }
        actor.ownerActorId?.let { owner ->
            require(actor.role == GenesisActorRole.COMPANION && owner != actor.actorId && owner in profiles) { "Companion owner is invalid or missing." }
            require(actor.campaignId == null || actors[owner]?.campaignId == actor.campaignId) { "Owner has no matching explicit campaign membership." }
        }
        var owner = actor.ownerActorId
        val seen = mutableSetOf(actor.actorId)
        while (owner != null) { require(seen.add(owner)) { "Companion ownership forms a cycle." }; owner = actors[owner]?.ownerActorId }
    }
    authority.actors.forEach { actor -> verify("Actor authority ${actor.actorId}") {
        association(actor)
        val activation = requireNotNull(actorOutcomes[actor.activationCommandId]) { "Original actor authority decision is missing." }
        require(activation.actor.actorId == actor.actorId && activation.actor.playable == actor.playable) { "Actor activation belongs to another identity or authority mode." }
        rejectDuplicateJsonMembers(actor.resourceBindingsJson)
        val bindings = campaignJson.decodeFromString<Map<String, String>>(actor.resourceBindingsJson)
        require(bindings.size <= 10_000 && bindings.values.distinct().size == bindings.size) { "Resource source identities are ambiguous." }
        bindings.forEach { (pool, sourceId) ->
            checkId(pool); checkId(sourceId)
            require(activation.playable?.character?.resources?.any { it.id == pool } == true) { "Resource binding was not present in the explicit activation decision." }
        }
        if (!actor.playable) require(bindings.isEmpty()) { "Source-only association cannot invent playable resource bindings." }
        val last = requireNotNull(actorProofs[actor.lastCommandId]) { "Latest association receipt is missing." }
        require(last.copy(playableRevision = null, projectionFingerprint = null) == actor.copy(playableRevision = null, projectionFingerprint = null)) { "Current association differs from its latest immutable decision." }
        if (actor.playable) {
            val current = requireNotNull(saved[actor.actorId]) { "Managed playable sheet is missing." }
            errors += genesisProjectionErrors(actor, current, campaign)
            current.character.items.forEach { item -> require(items[item.id]?.ownerActorId == actor.actorId) { "Managed equipment ${item.id} is missing its physical authority." } }
        } else require(actor.playableRevision == null && actor.projectionFingerprint == null) { "Source-only membership cannot claim a playable projection." }
    } }
    val owners = saved.values.flatMap { actor -> actor.character.items.map { it.id to actor } }.groupBy({ it.first }, { it.second })
    authority.items.forEach { item -> verify("Item authority ${item.itemId}") {
        checkId(item.itemId)
        require(item.revision > 0 && item.itemId in ledgerItems) { "Managed physical item or revision is missing." }
        val definition = reforgeJson.decodeFromString<ItemDefinition>(item.definitionJson)
        val instance = reforgeJson.decodeFromString<ItemInstance>(item.instanceJson)
        require(instance.id == item.itemId && instance.definitionId == definition.id && instance.quantity in 0..1_000_000) { "Physical item definition or identity is inconsistent." }
        val acquisitionReceipt = playableHistory[item.activationCommandId]?.takeIf { it.outcomeType == "INVENTORY" }
        val acquisition = acquisitionReceipt?.let { receipt ->
            reforgeJson.decodeFromString<GenesisInventoryOutcome>(receipt.afterJson).also { outcome ->
                validateGenesisInventoryReview(outcome.review)
                require(receipt.fingerprint == genesisInventoryRequestFingerprint(outcome.review) &&
                    outcome.review.instance.id == item.itemId && outcome.character.character.items.singleOrNull { it.id == item.itemId } == outcome.review.instance &&
                    outcome.character.character.itemDefinitions.singleOrNull { it.id == outcome.review.definition.id } == outcome.review.definition) { "Item acquisition is not bound to its original reviewed mapping." }
            }
        }
        val original = actorOutcomes[item.activationCommandId]?.playable ?: itemProofs[item.activationCommandId] ?: acquisition?.character
        require(original?.character?.items?.any { it.id == item.itemId } == true) { "Physical item has no original explicit activation proof." }
        val held = owners[item.itemId].orEmpty()
        if (item.ownerActorId != null) {
            require(actors[item.ownerActorId]?.playable == true && held.size == 1 && held.single().character.id == item.ownerActorId) { "Physical item has conflicting or missing ownership." }
            val current = held.single().character
            require(current.items.single { it.id == item.itemId } == instance && current.itemDefinitions.single { it.id == instance.definitionId } == definition) { "Physical authority differs from the owner's current equipment." }
        } else {
            require(held.isEmpty()) { "Unassigned physical item still has a playable owner." }
            val projection = requireNotNull(ledgerItems[item.itemId])
            val placement = campaign.records.placements.singleOrNull { it.itemId == item.itemId }
            val inactiveState = if (instance.quantity == 0) "Consumed" else "Stored"
            require(projection.title == definition.name && projection.description == definition.description && projection.quantity == instance.quantity &&
                projection.itemState == inactiveState && placement != null && placement.holderProfileId == null && placement.containerItemId == null &&
                placement.installedProjectId == null && placement.state == inactiveState) { "Unassigned item's source projection differs from its retained physical record." }
            val retained = playable.changes.any { change ->
                val candidates = buildList {
                    runCatching { reforgeChangeCharacter(change) }.getOrNull()?.let { add(it) }
                    change.beforeJson?.let { runCatching { reforgeJson.decodeFromString<ReforgeSavedCharacter>(it) }.getOrNull()?.let { before -> add(before) } }
                }
                candidates.any { saved -> saved.character.items.any { it == instance } && saved.character.itemDefinitions.any { it == definition } }
            }
            require(retained) { "Unassigned item lost the historical mechanics retained at removal." }
        }
        item.sourceProofJson?.let { proof ->
            if (acquisition != null) {
                val review = reforgeJson.decodeFromString<GenesisInventoryReview>(proof)
                require(review == acquisition.review) { "Physical item's source proof differs from its immutable acquisition review." }
            } else if (receipts[item.activationCommandId]?.kind == "craft-items") {
                val parent = requireNotNull(receipts[item.activationCommandId])
                val review = campaignJson.decodeFromString<GenesisCraftingReview>(parent.requestJson)
                val outcome = campaignJson.decodeFromString<GenesisCraftingOutcome>(parent.outcomeJson)
                require(outcome.outputId == item.itemId && campaignJson.decodeFromString<GenesisCraftItemProof>(proof) == genesisCraftProof(parent.commandId, review, outcome)) {
                    "Crafted item's original material and project proof is inconsistent."
                }
            } else {
                val review = campaignJson.decodeFromString<GenesisActorAuthorityReview>(proof)
                require(review.fingerprint == genesisActorAuthorityReviewFingerprint(review) && review.playable.character.items.any { it.id == item.itemId } &&
                    receipts[item.activationCommandId]?.let { it.kind == "accept-actor" && it.requestJson == proof } == true) { "Item's original reviewed source proof is inconsistent." }
            }
        }
        if (acquisition != null) require(item.sourceProofJson != null) { "Managed acquisition is missing its original source proof." }
        if (receipts[item.activationCommandId]?.kind == "craft-items") require(item.sourceProofJson != null) { "Crafted output is missing its original source proof." }
    } }
    // Management markers are retained in immutable history. Removing just the authority tables
    // from a file cannot turn an already connected projection into independently writable truth.
    fun actorMarker(id: String) { if (id !in actors) errors += "Managed actor $id is missing portable authority. Use a complete connected backup." }
    fun itemMarker(id: String) { if (id !in items) errors += "Managed item $id is missing portable authority. Use a complete connected backup." }
    campaign.records.characterProfiles.filter { it.source == GENESIS_AUTHORITY_SOURCE }.forEach { actorMarker(it.id) }
    campaign.items.filter { it.source == GENESIS_AUTHORITY_SOURCE }.forEach { itemMarker(it.id) }
    campaign.events.filter { it.source == GENESIS_AUTHORITY_SOURCE }.forEach { itemMarker(it.itemId) }
    campaign.records.audit.filter { it.source == GENESIS_AUTHORITY_SOURCE }.forEach { audit ->
        when (audit.recordType) {
            "character-profile", "party-sheet", "companion", "connection" -> actorMarker(audit.recordId)
            "character-ability" -> campaign.records.characterAbilities.find { it.id == audit.recordId }?.let { actorMarker(it.profileId) }
            "resource" -> campaign.records.companionResources.find { it.id == audit.recordId }?.let { actorMarker(it.companionId) }
            "item", "item-placement" -> itemMarker(audit.recordId)
        }
    }
    playable.changes.filter { it.operation.startsWith(GENESIS_AUTHORITY_SOURCE) }.forEach { actorMarker(it.profileId) }
    saved.values.filter { it.origin.label == GENESIS_AUTHORITY_SOURCE }.forEach { actorMarker(it.character.id) }
    errors += genesisEncounterBackupErrors(authority)
    return errors.distinct()
}
