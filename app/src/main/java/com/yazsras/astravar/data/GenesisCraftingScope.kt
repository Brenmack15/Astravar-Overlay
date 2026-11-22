package com.yazsras.astravar.data

/** Membership for connected craft inputs. Historical command receipts keep their original review. */
internal class GenesisCraftingScope(
    private val campaignId: String?,
    references: List<ExternalRecordReference>,
    actors: List<GenesisActorAuthority>,
    sheets: List<PartySheetRecord>,
    placements: List<ItemPlacement>,
    managed: List<GenesisItemMembership>,
    archiveMemberships: Map<Pair<String, String>, Set<String>>,
    private val showDefaultLocal: Boolean
) {
    private val actorsById = actors.associateBy { it.actorId }
    private val sheetsById = sheets.associateBy { it.profileId }
    private val placementsById = placements.associateBy { it.itemId }
    private val managedById = managed.associateBy { it.itemId }
    private val explicit = references.groupBy { it.recordType to it.recordId }
    private val world = CampaignWorldScope(campaignId ?: "local-campaign", references, actorsById, archiveMemberships)

    private fun matches(stored: String?) = stored == campaignId || (showDefaultLocal && stored == "local-campaign")

    fun permitsActor(id: String): Boolean {
        actorsById[id]?.let { return matches(it.campaignId) }
        sheetsById[id]?.let { return matches(it.campaignId) }
        return campaignId == null
    }

    fun permitsProject(project: InventionProject): Boolean {
        if (campaignId == null && explicit.containsKey("project" to project.id)) return false
        return world.includes("project", project.id, project.source)
    }

    fun permitsMaterial(item: LedgerItem): Boolean {
        val memberships = explicit["item" to item.id].orEmpty()
        if (memberships.isNotEmpty() && memberships.none { matches(it.campaignId) }) return false
        val holders = listOfNotNull(managedById[item.id]?.ownerActorId, placementsById[item.id]?.holderProfileId)
        if (holders.isNotEmpty()) return holders.all(::permitsActor)
        return world.includes("item", item.id, item.source)
    }
}

internal suspend fun CampaignLedgerDb.craftScope(campaignId: String?, showDefaultLocal: Boolean = false) = GenesisCraftingScope(
    campaignId, partyDao().allReferences(), genesisAuthorityDao().allActors(), partyDao().allSheets(),
    partyDao().allPlacements(), genesisAuthorityDao().itemMembership(), archiveDao().worldMemberships(), showDefaultLocal
)
