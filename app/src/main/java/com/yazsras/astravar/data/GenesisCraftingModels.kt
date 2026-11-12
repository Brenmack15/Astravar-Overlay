package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class GenesisCraftMaterialReview(
    val use: MaterialConsumption, val item: LedgerItem, val placement: ItemPlacement?, val authority: GenesisItemAuthority?
)
@Serializable data class GenesisCraftActorReview(
    val authority: GenesisActorAuthority, val playable: ReforgeSavedCharacter, val profile: CharacterProfile
)
@Serializable data class GenesisCraftingReview(
    val project: InventionProject, val materials: List<GenesisCraftMaterialReview>,
    val output: LedgerDraft, val outputOwnerActorId: String, val outputContainerItemId: String?,
    val actors: List<GenesisCraftActorReview>, val source: String, val fingerprint: String
)
@Serializable data class GenesisCraftingOutcome(
    val outputId: String, val craftReceipt: CommandReceipt, val execution: CraftExecution,
    val consumptions: List<CraftConsumption>, val outputLink: CampaignLink,
    val sourceOutput: LedgerItem, val actors: List<ReforgeSavedCharacter>
)

/** Compact immutable pointer; the full reviewed source remains in its authority parent receipt. */
@Serializable data class GenesisCraftItemProof(
    val format: String = "astravar-crafted-item-v1", val parentCommandId: String, val craftCommandId: String,
    val outputId: String, val projectId: String, val reviewFingerprint: String,
    val consumptions: List<CraftConsumption>, val outputLinkId: String
)

internal fun genesisCraftReviewFingerprint(review: GenesisCraftingReview) =
    campaignHash(campaignJson.encodeToString(review.copy(fingerprint = "")))
internal fun genesisCraftCommandId(parent: String) = genesisChild(parent, "craft")
internal fun genesisCraftPlayableCommand(parent: String, actorId: String) = genesisChild(parent, "craft-playable:$actorId")
internal fun genesisCraftProjectionCommand(parent: String, actorId: String) = genesisChild(parent, "craft-project:$actorId")
internal fun genesisCraftProof(parent: String, review: GenesisCraftingReview, outcome: GenesisCraftingOutcome) =
    GenesisCraftItemProof(parentCommandId = parent, craftCommandId = outcome.craftReceipt.commandId, outputId = outcome.outputId,
        projectId = review.project.id, reviewFingerprint = review.fingerprint, consumptions = outcome.consumptions,
        outputLinkId = outcome.outputLink.id)

internal fun genesisCraftLegacyCommand(review: GenesisCraftingReview, parent: String) = CraftCommand(
    review.project.id, review.project.revision, review.materials.map { it.use }, review.output, review.source, genesisCraftCommandId(parent))

/** Only quantity changes and the plain new output are applied; no recipe, effect, cost or time is invented. */
internal fun genesisCraftCharacters(review: GenesisCraftingReview, output: LedgerItem): List<ReforgeCharacter> {
    require(review.actors.map { it.playable.character.id }.distinct().size == review.actors.size)
    val definition = ItemDefinition(output.id, output.title, description = output.description)
    val instance = ItemInstance(output.id, definition.id, quantity = requireNotNull(output.quantity),
        state = if (review.output.itemState == "Stored") ItemState.STORED else ItemState.CARRIED, containerId = review.outputContainerItemId)
    return review.actors.map { actor ->
        val before = actor.playable.character
        val uses = review.materials.filter { it.authority?.ownerActorId == before.id }.associateBy { it.use.itemId }
        val next = before.copy(items = before.items.map { item -> uses[item.id]?.let { use ->
            val remaining = item.quantity - use.use.quantity
            require(remaining >= 0)
            item.copy(quantity = remaining, containerId = item.containerId.takeIf { remaining > 0 })
        } ?: item }).let {
            if (before.id != review.outputOwnerActorId) it else {
                require(it.items.none { item -> item.id == output.id } && it.itemDefinitions.none { existing -> existing.id == definition.id })
                it.copy(itemDefinitions = it.itemDefinitions + definition, items = it.items + instance)
            }
        }
        GameplayRules.preserveCapacities(before, next).also(::validateCharacter)
    }
}
