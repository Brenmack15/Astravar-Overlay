package com.yazsras.astravar.data

import android.util.LruCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only keep the campaign and two identity lists. Source prose and backup bytes stay in Room. */
@Serializable private data class SourceWorldId(val id: String)
@Serializable private data class SourceWorldRecords(
    val projects: List<SourceWorldId> = emptyList(), val entities: List<SourceWorldId> = emptyList()
)
@Serializable private data class SourceWorldMapping(val records: SourceWorldRecords = SourceWorldRecords())
@Serializable private data class SourceWorldArchive(val campaignId: String, val mapped: SourceWorldMapping? = null)

private val worldSourceJson = Json { ignoreUnknownKeys = true }
private val cachedWorldSources = LruCache<String, SourceWorldArchive>(16)

internal fun activeImportedCampaigns(receipts: List<CampaignImportReceipt>): List<CampaignImportReceipt> {
    val rolledBack = receipts.filter { it.state == "ROLLED_BACK" }.map { it.packageHash.removePrefix("rollback:") }.toSet()
    return receipts.filter { it.state == "APPLIED" && it.packageHash !in rolledBack }
}

internal suspend fun CampaignArchiveDao.worldMemberships(receipts: List<CampaignImportReceipt>? = null): Map<Pair<String,String>, Set<String>> {
    val memberships = mutableMapOf<Pair<String,String>, MutableSet<String>>()
    val retained = sourceHashes().toSet()
    for (receipt in activeImportedCampaigns(receipts ?: allReceipts())) {
        val hash = receipt.packageHash
        if (hash !in retained) continue
        var archive = cachedWorldSources.get(hash)
        if (archive == null) {
            val saved = source(hash) ?: continue
            check(campaignHash(saved.payloadJson) == hash) { "Archived campaign source integrity check failed." }
            archive = worldSourceJson.decodeFromString<SourceWorldArchive>(saved.payloadJson).also { cachedWorldSources.put(hash, it) }
        }
        check(archive.campaignId == receipt.campaignId) { "Archived campaign identity differs from its receipt." }
        val rows = archive.mapped?.records ?: continue
        for (row in rows.projects) memberships.getOrPut("project" to row.id) { mutableSetOf() }.add(archive.campaignId)
        for (row in rows.entities) memberships.getOrPut("connection" to row.id) { mutableSetOf() }.add(archive.campaignId)
    }
    return memberships
}
