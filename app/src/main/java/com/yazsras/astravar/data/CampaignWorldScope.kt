package com.yazsras.astravar.data

/** Resolve the legacy project and connection tables without assigning an unbound fixture to a player. */
internal class CampaignWorldScope(
    private val campaignId: String,
    references: List<ExternalRecordReference>,
    private val actors: Map<String, GenesisActorAuthority>,
    private val archivedMemberships: Map<Pair<String, String>, Set<String>> = emptyMap()
) {
    private val memberships = references.groupBy { it.recordType to it.recordId }

    fun includes(kind: String, id: String, source: String): Boolean {
        if (kind == "connection") actors[id]?.let { return it.campaignId == campaignId }
        val explicit = memberships[kind to id].orEmpty()
        if (explicit.isNotEmpty()) return explicit.any { it.campaignId == campaignId }
        // Old Complete exports can hold QA and player data together. The reserved source
        // marker says this row needs explicit membership, without supplying its campaign ID.
        if (fixtureOnlySource(source)) return false
        archivedMemberships[kind to id]?.let { return it.singleOrNull() == campaignId }
        return campaignId == "local-campaign"
    }
}

internal fun fixtureOnlySource(source: String): Boolean =
    source.substringBefore(':').trim().equals("SYNTHETIC_QA_ONLY", ignoreCase = true)
