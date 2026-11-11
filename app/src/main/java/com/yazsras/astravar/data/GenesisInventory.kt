package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.ItemState
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/** One row per physical identity; both current gameplay and source evidence remain inspectable. */
enum class GenesisInventoryAuthority { PLAYABLE, CAMPAIGN_SOURCE, REVIEW_REQUIRED }

data class GenesisInventoryOwner(
    val profileId: String, val name: String, val archived: Boolean,
    val instance: ItemInstance, val definition: ItemDefinition
)

data class GenesisInventoryEntry(
    val itemId: String, val displayName: String,
    val playableOwners: List<GenesisInventoryOwner>,
    val sourceItem: LedgerItem?, val sourcePlacement: ItemPlacement?,
    val authority: GenesisInventoryAuthority, val reviewIssues: List<String>
) {
    /** Ambiguous physical ownership never selects an arbitrary holder or stock count. */
    val currentQuantity: Int? get() = when (authority) {
        GenesisInventoryAuthority.PLAYABLE -> playableOwners.single().instance.quantity
        GenesisInventoryAuthority.CAMPAIGN_SOURCE -> sourceItem?.quantity
        GenesisInventoryAuthority.REVIEW_REQUIRED -> null
    }
}

fun genesisInventoryProjection(
    playable: List<ReforgeSavedCharacter>, campaignItems: List<LedgerItem>, placements: List<ItemPlacement>
): List<GenesisInventoryEntry> {
    val owners = playable.flatMap { saved ->
        val definitions = saved.character.itemDefinitions.associateBy { it.id }
        saved.character.items.mapNotNull { instance -> definitions[instance.definitionId]?.let { definition ->
            instance.id to GenesisInventoryOwner(saved.character.id, saved.character.name,
                saved.character.archived, instance, definition)
        } }
    }.groupBy({ it.first }, { it.second })
    val sourceById = campaignItems.associateBy { it.id }
    val placementById = placements.associateBy { it.itemId }
    return (owners.keys + sourceById.filterValues { !it.archived }.keys).map { id ->
        val held = owners[id].orEmpty().sortedBy { it.profileId }
        val source = sourceById[id]
        val placement = placementById[id]
        val issues = buildList {
            if (held.size > 1) add("This physical item appears on more than one playable sheet. Review ownership; no stock total is assumed.")
            if (held.any { it.archived }) add("An archived sheet still owns this item. Restore that sheet before transferring it.")
            if (source != null && held.isNotEmpty()) {
                if (placement?.holderProfileId == null) add("The campaign source has no verified holder. Review its assignment before reconciliation.")
                else if (held.any { it.profileId != placement.holderProfileId }) add("Campaign and playable ownership disagree. Review the two records before moving this item.")
                if (held.size == 1) {
                    val current = held.single()
                    if (source.quantity != current.instance.quantity) add("Campaign quantity differs from current playable quantity. Neither record has been overwritten.")
                    if (source.title != current.definition.name || source.description != current.definition.description)
                        add("Campaign description differs from the current playable item. The source remains available for review.")
                    if (placement != null && (placement.state != campaignPlacementState(current.instance.state) ||
                                placement.containerItemId != current.instance.containerId))
                        add("Campaign placement differs from current playable equipment or container state.")
                }
                if (source.archived || source.itemState in setOf("Consumed", "Sold", "Lost"))
                    add("The campaign source marks this item unavailable while a playable sheet still holds it.")
            }
            if (source != null && source.evidenceStatus != "Confirmed") add("Campaign evidence is ${source.evidenceStatus.lowercase()}; this does not approve mechanical effects.")
            if (held.isEmpty() && source?.quantity == null) add("Campaign quantity is unknown; confirm it before adding playable equipment.")
        }
        val authority = when {
            held.size > 1 -> GenesisInventoryAuthority.REVIEW_REQUIRED
            held.size == 1 && source != null && (placement?.holderProfileId != held.single().profileId ||
                source.archived || source.itemState in setOf("Consumed", "Sold", "Lost")) -> GenesisInventoryAuthority.REVIEW_REQUIRED
            held.size == 1 -> GenesisInventoryAuthority.PLAYABLE
            else -> GenesisInventoryAuthority.CAMPAIGN_SOURCE
        }
        GenesisInventoryEntry(id, held.singleOrNull()?.definition?.name ?: source?.title ?: "Item requiring review",
            held, source, placement, authority, issues)
    }.sortedWith(compareBy({ it.displayName.lowercase() }, { it.itemId }))
}

private fun campaignPlacementState(state: ItemState) = when (state) {
    ItemState.STORED -> "Stored"
    ItemState.CARRIED -> "Carried"
    ItemState.WORN, ItemState.HELD -> "Equipped"
}

fun ReforgeRepository.observeInventory(campaign: CampaignLedgerRepository): Flow<List<GenesisInventoryEntry>> =
    combine(observeAll(), campaign.itemsIncludingArchived, campaign.party.placements, ::genesisInventoryProjection)
        .flowOn(Dispatchers.Default)

/** Exact selected source snapshot. This is evidence, never a new mutable source of truth. */
@Serializable data class GenesisInventorySource(
    val profileId: String, val profileRevision: Long, val campaignId: String?,
    val item: LedgerItem, val placement: ItemPlacement,
    val details: ItemDetails?, val properties: List<ItemProperty>
)

@Serializable data class GenesisInventoryReview(
    val profileId: String, val expectedRevision: Long,
    val source: GenesisInventorySource, val sourceFingerprint: String,
    val definition: ItemDefinition, val instance: ItemInstance
)

@Serializable data class GenesisInventoryOutcome(
    val character: ReforgeSavedCharacter, val review: GenesisInventoryReview
)

private suspend fun campaignItemSource(campaign: CampaignLedgerRepository, profileId: String, itemId: String): GenesisInventorySource {
    val snapshot = campaign.readCharacterSnapshot(profileId)
    val placement = snapshot.placements.singleOrNull { it.itemId == itemId && it.holderProfileId == profileId }
        ?: error("This campaign item is not assigned to this character. Review its holder in campaign inventory first.")
    val item = snapshot.items.singleOrNull { it.id == itemId }
        ?: error("The assigned campaign item is unavailable. Review its current record.")
    return GenesisInventorySource(profileId, snapshot.profile.revision, snapshot.sheet?.campaignId,
        item, placement, snapshot.itemDetails.singleOrNull { it.itemId == itemId },
        snapshot.itemProperties.filter { it.itemId == itemId }.sortedBy { it.id })
}

/** Strict context-aware copy mapping. Installed or ambiguous physical state needs explicit source review. */
internal fun genesisCampaignItemMapping(source: GenesisInventorySource, target: ReforgeCharacter): Pair<ItemDefinition, ItemInstance> {
    require(source.profileId == target.id && source.placement.holderProfileId == target.id &&
        source.item.id == source.placement.itemId) { "Campaign item ownership does not match this character." }
    val item = source.item
    require(!item.archived && item.itemState !in setOf("Consumed", "Sold", "Lost")) { "This campaign item is unavailable." }
    require(item.quantity != null && item.quantity > 0) { "Confirm a positive campaign quantity before adding playable equipment." }
    require(source.placement.installedProjectId == null && source.placement.state in setOf("Carried", "Stored", "Equipped")) {
        "Review this item's installed or unknown placement before adding playable equipment."
    }
    require(source.placement.containerItemId == null || target.items.any { it.id == source.placement.containerItemId }) {
        "Add this item's assigned container first, or explicitly change its campaign placement."
    }
    require(target.items.none { it.id == item.id } && target.itemDefinitions.none { it.id == item.id }) {
        "This identity already exists on the playable sheet. Open that item and review its source instead of copying it again."
    }
    val definition = ItemDefinition(item.id, item.title, description = item.description)
    val instance = ItemInstance(item.id, item.id, quantity = item.quantity,
        state = when (source.placement.state) { "Stored" -> ItemState.STORED; "Equipped" -> ItemState.WORN; else -> ItemState.CARRIED },
        containerId = source.placement.containerItemId)
    // The source has no executable attunement/effect contract. No prose is interpreted as a bonus.
    return definition to instance
}

suspend fun ReforgeRepository.reviewCampaignItem(campaign: CampaignLedgerRepository, profileId: String,
    itemId: String): GenesisInventoryReview {
    checkId(profileId); checkId(itemId)
    val target = get(profileId) ?: error("Save or review this character before adding equipment.")
    require(!target.character.archived) { "Restore this character before adding equipment." }
    inspectCampaignIdentity(campaign, target)
        ?: error("Review this character's campaign identity before adding campaign equipment.")
    val source = campaignItemSource(campaign, profileId, itemId)
    val (definition, instance) = genesisCampaignItemMapping(source, target.character)
    val encoded = reforgeJson.encodeToString(source)
    require(encoded.length <= 2_000_000) { "Selected item evidence is too large. Original records are unchanged." }
    return GenesisInventoryReview(profileId, target.revision, source, reforgeHash(encoded), definition, instance)
}

/**
 * Single local transaction with an immutable source proof. Source is rechecked before local commit;
 * there is no cross-database atomicity or continuous campaign synchronization claim.
 */
suspend fun ReforgeRepository.acceptCampaignItem(campaign: CampaignLedgerRepository, review: GenesisInventoryReview,
    commandId: String = UUID.randomUUID().toString()): ReforgeSavedCharacter {
    validateGenesisInventoryReview(review)
    val fingerprint = genesisInventoryRequestFingerprint(review)
    commandReceipt(commandId)?.let { return genesisInventoryRetry(it, fingerprint).character }
    val source = campaignItemSource(campaign, review.profileId, review.source.item.id)
    check(source == review.source && reforgeHash(reforgeJson.encodeToString(source)) == review.sourceFingerprint) {
        "The campaign item or its assignment changed after review. Review the latest source before adding it."
    }
    return commitReviewedCampaignItem(review, commandId).character
}

internal fun validateGenesisInventoryReview(review: GenesisInventoryReview) {
    checkId(review.profileId); checkId(review.source.item.id)
    require(review.expectedRevision > 0 && review.source.profileRevision > 0 && review.source.item.revision > 0 && review.source.placement.revision > 0)
    require(review.profileId == review.source.profileId &&
        review.sourceFingerprint == reforgeHash(reforgeJson.encodeToString(review.source))) { "The reviewed campaign source proof changed." }
    require(reforgeJson.encodeToString(review.source).length <= 2_000_000) { "Selected item evidence is too large." }
}

internal fun genesisInventoryRequestFingerprint(review: GenesisInventoryReview) =
    reforgeHash("genesis-campaign-item-v1:${reforgeJson.encodeToString(review)}")

internal fun genesisInventoryRetry(receipt: ReforgeChange, fingerprint: String): GenesisInventoryOutcome {
    require(receipt.outcomeType == "INVENTORY" && receipt.fingerprint == fingerprint) {
        "Command identity was reused with different inventory content. Nothing changed."
    }
    return reforgeJson.decodeFromString(receipt.afterJson)
}

/** New collisions reject. Historical collisions remain visible and do not block unrelated edits. */
internal fun validateGenesisItemOwnership(before: ReforgeCharacter?, after: ReforgeCharacter,
    otherCharacters: List<ReforgeCharacter>) {
    val previous = before?.items.orEmpty().associateBy { it.id }
    val previousDefinitions = before?.itemDefinitions.orEmpty().associateBy { it.id }
    val nextDefinitions = after.itemDefinitions.associateBy { it.id }
    val otherOwners = otherCharacters.filter { it.id != after.id }.flatMap { it.items }.map { it.id }.toSet()
    after.items.filter { it.id in otherOwners }.forEach { item ->
        val prior = previous[item.id]
        require(prior != null && prior == item && previousDefinitions[prior.definitionId] == nextDefinitions[item.definitionId]) {
            "This physical item already belongs to another playable sheet. Review ownership or use Transfer; no duplicate was saved."
        }
    }
}

/** A merge must not create a new collision between independently valid backup domains. */
internal fun genesisRestoreOwnershipConflicts(incoming: ReforgeBackup, current: ReforgeBackup): List<String> {
    fun holders(rows: List<ReforgeCharacterRow>) = rows.flatMap { row -> row.saved().character.items.map { it.id to row.profileId } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    val before = holders(current.characters)
    val incomingHolders = holders(incoming.characters)
    val merged = holders((current.characters + incoming.characters).distinctBy { it.profileId })
    return merged.filter { (id, owners) -> owners.size > 1 && owners != before[id] && owners != incomingHolders[id] }
        .map { (id, _) -> "Physical item $id would have multiple owners after merging. Review the conflicting sheets before restoring." }
}
