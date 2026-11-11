package com.yazsras.astravar.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class GenesisActorRole { PLAYER, COMPANION, NPC }

@Serializable @Entity(tableName = "genesis_campaigns")
data class GenesisCampaignRecord(
    @PrimaryKey val id: String, val name: String, val notes: String = "",
    val revision: Long = 1, val archived: Boolean = false,
    val createdAt: Long, val updatedAt: Long, val commandId: String
)

/** Explicit role/membership. Null campaign/owner are unknown or unassigned, never inferred from names. */
@Serializable @Entity(tableName = "genesis_actor_authority")
data class GenesisActorAuthority(
    @PrimaryKey val actorId: String, val role: GenesisActorRole,
    val campaignId: String? = null, val ownerActorId: String? = null,
    val playable: Boolean = false, val revision: Long = 1,
    val activationCommandId: String, val lastCommandId: String,
    val playableRevision: Long? = null, val projectionFingerprint: String? = null,
    val resourceBindingsJson: String = "{}",
    val createdAt: Long, val updatedAt: Long
)

@Serializable @Entity(tableName = "genesis_item_authority", indices = [Index("ownerActorId")])
data class GenesisItemAuthority(
    @PrimaryKey val itemId: String, val ownerActorId: String?,
    val definitionJson: String, val instanceJson: String,
    val revision: Long = 1, val activationCommandId: String,
    val sourceProofJson: String? = null, val updatedAt: Long
)

/** Request-bound original outcomes; gameplay histories retain their existing immutable receipts too. */
@Serializable @Entity(tableName = "genesis_authority_receipts")
data class GenesisAuthorityReceipt(
    @PrimaryKey val commandId: String, val kind: String, val fingerprint: String,
    val requestJson: String, val outcomeJson: String, val createdAt: Long
)

@Serializable data class GenesisAuthorityExport(
    val campaigns: List<GenesisCampaignRecord> = emptyList(),
    val actors: List<GenesisActorAuthority> = emptyList(),
    val items: List<GenesisItemAuthority> = emptyList(),
    val receipts: List<GenesisAuthorityReceipt> = emptyList()
)

@Serializable data class GenesisFieldDifference(val field: String, val sourceValue: String, val playableValue: String)
@Serializable data class GenesisActorAuthorityReview(
    val actorId: String, val role: GenesisActorRole, val campaignId: String?, val ownerActorId: String?,
    val expectedAuthorityRevision: Long?, val playable: ReforgeSavedCharacter,
    val source: ReforgeLegacySnapshot?, val differences: List<GenesisFieldDifference>, val fingerprint: String,
    val supplementalSourceJson: String = ""
)
@Serializable data class GenesisActorCreationOutcome(
    val actor: GenesisActorAuthority, val profile: CharacterProfile, val playable: ReforgeSavedCharacter?
)

class GenesisAuthorityConverters {
    @TypeConverter fun roleToString(role: GenesisActorRole): String = role.name
    @TypeConverter fun roleFromString(role: String): GenesisActorRole = GenesisActorRole.valueOf(role)
}

@Dao interface GenesisAuthorityDao {
    @Query("SELECT EXISTS(SELECT 1 FROM genesis_campaigns) OR EXISTS(SELECT 1 FROM genesis_actor_authority) OR EXISTS(SELECT 1 FROM genesis_item_authority) OR EXISTS(SELECT 1 FROM genesis_authority_receipts)") suspend fun hasAuthority(): Boolean
    @Query("SELECT * FROM genesis_campaigns ORDER BY name,id") fun campaigns(): Flow<List<GenesisCampaignRecord>>
    @Query("SELECT * FROM genesis_campaigns ORDER BY id") suspend fun allCampaigns(): List<GenesisCampaignRecord>
    @Query("SELECT * FROM genesis_campaigns WHERE id=:id") suspend fun campaign(id: String): GenesisCampaignRecord?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: GenesisCampaignRecord)
    @Update suspend fun update(value: GenesisCampaignRecord)
    @Query("SELECT * FROM genesis_actor_authority ORDER BY actorId") fun actors(): Flow<List<GenesisActorAuthority>>
    @Query("SELECT * FROM genesis_actor_authority ORDER BY actorId") suspend fun allActors(): List<GenesisActorAuthority>
    @Query("SELECT * FROM genesis_actor_authority WHERE actorId=:id") suspend fun actor(id: String): GenesisActorAuthority?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: GenesisActorAuthority)
    @Update suspend fun update(value: GenesisActorAuthority)
    @Query("SELECT * FROM genesis_item_authority ORDER BY itemId") suspend fun allItems(): List<GenesisItemAuthority>
    @Query("SELECT * FROM genesis_item_authority WHERE itemId=:id") suspend fun item(id: String): GenesisItemAuthority?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: GenesisItemAuthority)
    @Update suspend fun update(value: GenesisItemAuthority)
    @Query("SELECT * FROM genesis_authority_receipts ORDER BY commandId") suspend fun allReceipts(): List<GenesisAuthorityReceipt>
    @Query("SELECT * FROM genesis_authority_receipts WHERE commandId=:id") suspend fun receipt(id: String): GenesisAuthorityReceipt?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: GenesisAuthorityReceipt)
}

internal suspend fun genesisAuthoritySnapshot(db: CampaignLedgerDb): GenesisAuthorityExport = genesisAuthorityBackupRows(db)
