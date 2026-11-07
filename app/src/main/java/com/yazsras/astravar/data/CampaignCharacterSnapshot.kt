package com.yazsras.astravar.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

private const val CHARACTER_ITEMS = "(SELECT itemId FROM item_placements WHERE holderProfileId = :profileId)"
private const val CHARACTER_REFERENCES = """
    SELECT r.id FROM campaign_references r
    JOIN external_record_references e ON e.externalId = r.externalId AND e.campaignId = r.campaignId
    WHERE (e.recordType = 'character-profile' AND e.recordId = :profileId)
       OR (e.recordType = 'item' AND e.recordId IN $CHARACTER_ITEMS)
"""

/**
 * A consistent, read-only source boundary for reviewed character copies. Queries select by exact
 * identity in SQLite, without materializing unrelated profiles, history, source payloads or backups.
 * SQL subqueries also avoid a bound-parameter limit for characters with many assigned items.
 * Relationships retain their exact endpoints; following them never traverses the whole campaign.
 */
@Dao
abstract class CampaignCharacterDao {
    @Query("SELECT * FROM character_profiles WHERE id = :profileId")
    protected abstract suspend fun profile(profileId: String): CharacterProfile?

    @Query("SELECT * FROM character_abilities WHERE profileId = :profileId ORDER BY `key`")
    protected abstract suspend fun abilities(profileId: String): List<CharacterAbility>

    @Query("SELECT * FROM party_sheets WHERE profileId = :profileId")
    protected abstract suspend fun sheet(profileId: String): PartySheetRecord?

    @Query("SELECT * FROM item_placements WHERE holderProfileId = :profileId ORDER BY itemId")
    protected abstract suspend fun placements(profileId: String): List<ItemPlacement>

    @Query("SELECT * FROM ledger_items WHERE id IN $CHARACTER_ITEMS ORDER BY id")
    protected abstract suspend fun items(profileId: String): List<LedgerItem>

    @Query("SELECT * FROM item_details WHERE itemId IN $CHARACTER_ITEMS ORDER BY itemId")
    protected abstract suspend fun details(profileId: String): List<ItemDetails>

    @Query("SELECT * FROM item_properties WHERE itemId IN $CHARACTER_ITEMS ORDER BY id")
    protected abstract suspend fun properties(profileId: String): List<ItemProperty>

    @Query("""SELECT * FROM campaign_links
        WHERE (fromType = 'character-profile' AND fromId = :profileId)
           OR (toType = 'character-profile' AND toId = :profileId)
           OR (fromType = 'item' AND fromId IN $CHARACTER_ITEMS)
           OR (toType = 'item' AND toId IN $CHARACTER_ITEMS) ORDER BY id""")
    protected abstract suspend fun links(profileId: String): List<CampaignLink>

    @Query("""SELECT * FROM external_record_references
        WHERE (recordType = 'character-profile' AND recordId = :profileId)
           OR (recordType = 'item' AND recordId IN $CHARACTER_ITEMS) ORDER BY id""")
    protected abstract suspend fun references(profileId: String): List<ExternalRecordReference>

    // Preserve archive attribution and incident edges, without fetching large narrative bodies.
    @Query("""SELECT id,campaignId,externalId,title,category,sourceUrl,sourceRevision,contentSha256,packageHash,
        length(CAST(body AS BLOB)) AS bodyBytes, length(CAST(propertiesJson AS BLOB)) AS propertiesBytes
        FROM campaign_references WHERE id IN ($CHARACTER_REFERENCES) ORDER BY id""")
    protected abstract suspend fun referenceMetadata(profileId: String): List<CampaignReferenceMetadata>

    @Query("""SELECT * FROM campaign_reference_relations
        WHERE fromId IN ($CHARACTER_REFERENCES) OR toId IN ($CHARACTER_REFERENCES) ORDER BY id""")
    protected abstract suspend fun referenceRelations(profileId: String): List<CampaignReferenceRelation>

    @Transaction
    open suspend fun snapshot(profileId: String): ReforgeLegacySnapshot {
        val profile = profile(profileId) ?: error("The original character is no longer available.")
        return ReforgeLegacySnapshot(profile, abilities(profileId), sheet(profileId), items(profileId),
            placements(profileId), details(profileId), properties(profileId), links(profileId),
            references(profileId), referenceMetadata(profileId), referenceRelations(profileId))
    }
}
