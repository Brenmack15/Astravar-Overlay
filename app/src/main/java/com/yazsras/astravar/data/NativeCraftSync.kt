package com.yazsras.astravar.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal object NativeCraftSync {
    const val TYPE = "complete_native_craft"
    @Serializable data class Request(val version: Int, val ownerId: String, val materials: List<MaterialConsumption>,
        val actorRevisions: Map<String,Long>, val companionIds: Set<String>, val outputTitle: String,
        val outputCategory: String, val outputQuantity: Int)

    fun scopes(op: CampaignSyncOperation): Set<String> {
        val request = campaignJson.decodeFromJsonElement<Request>(op.fields)
        require(op.kind == "project" && op.expectedRevision > 0 && request.version == 1)
        return setOf("character","inventory","engineering") + if (request.companionIds.isEmpty()) emptySet() else setOf("companions")
    }

    suspend fun apply(db: CampaignLedgerDb, campaign: String, op: CampaignSyncOperation, source: String, child: String) {
        scopes(op)
        val f = campaignJson.decodeFromJsonElement<Request>(op.fields)
        require(db.genesisAuthorityDao().actor(f.ownerId)?.campaignId == campaign)
        val repo = GenesisCraftingRepository(db)
        val review = repo.review(op.recordId,f.materials,LedgerDraft(f.outputTitle, category=f.outputCategory,
            quantity=f.outputQuantity,itemState="Carried",evidenceStatus="Confirmed",source=source),f.ownerId,source=source)
        require(review.project.revision == op.expectedRevision && review.project.status != "Completed")
        require(review.actors.all { it.authority.campaignId == campaign } &&
            review.actors.associate { it.authority.actorId to it.playable.revision } == f.actorRevisions)
        require(review.actors.filter { it.authority.role == GenesisActorRole.COMPANION }.map { it.authority.actorId }.toSet() == f.companionIds)
        repo.accept(review,child)
    }
}
