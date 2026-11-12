package com.yazsras.astravar.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable enum class GenesisEncounterStatus { DRAFT, ACTIVE, ENDED }

/** A tracker references saved actors. It never owns a second set of combat statistics. */
@Serializable @Entity(tableName = "genesis_encounters", foreignKeys = [ForeignKey(
    entity = GenesisCampaignRecord::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index("campaignId")])
data class GenesisEncounterRecord(
    @PrimaryKey val id: String, val campaignId: String, val title: String, val notes: String,
    val status: GenesisEncounterStatus = GenesisEncounterStatus.DRAFT,
    val round: Int = 0, val currentActorId: String? = null, val revision: Long = 1,
    val creationCommandId: String, val lastCommandId: String, val createdAt: Long, val updatedAt: Long
)

@Serializable @Entity(tableName = "genesis_encounter_participants", primaryKeys = ["encounterId", "actorId"],
    foreignKeys = [
        ForeignKey(entity = GenesisEncounterRecord::class, parentColumns = ["id"], childColumns = ["encounterId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenesisActorAuthority::class, parentColumns = ["actorId"], childColumns = ["actorId"], onDelete = ForeignKey.RESTRICT)
    ], indices = [Index("actorId"), Index(value = ["encounterId", "position"], unique = true)])
data class GenesisEncounterParticipant(val encounterId: String, val actorId: String, val initiative: Int?, val position: Int)

@Serializable data class GenesisEncounterSnapshot(
    @Embedded val record: GenesisEncounterRecord,
    @Relation(parentColumn = "id", entityColumn = "encounterId") val participants: List<GenesisEncounterParticipant>
)
@Serializable data class GenesisEncounterParticipantInput(val actorId: String, val expectedAuthorityRevision: Long, val initiative: Int? = null)
/** Historical membership is bound to its immutable decision, not today's later association. */
@Serializable data class GenesisEncounterMember(val actorId: String, val authorityRevision: Long, val role: GenesisActorRole,
    val campaignId: String, val membershipCommandId: String)
@Serializable data class GenesisEncounterStartReview(val snapshot: GenesisEncounterSnapshot,
    val members: List<GenesisEncounterMember>, val fingerprint: String)

@Serializable internal enum class GenesisEncounterOperation { SAVE_DRAFT, START, NEXT, END, REOPEN_SETUP }
@Serializable internal data class GenesisEncounterDraft(val campaignId: String, val title: String, val notes: String,
    val participants: List<GenesisEncounterParticipantInput>)
@Serializable internal data class GenesisEncounterRequest(val operation: GenesisEncounterOperation, val encounterId: String,
    val expectedRevision: Long?, val draft: GenesisEncounterDraft? = null, val startReview: GenesisEncounterStartReview? = null)
@Serializable internal data class GenesisEncounterOutcome(val before: GenesisEncounterSnapshot?, val after: GenesisEncounterSnapshot,
    val members: List<GenesisEncounterMember> = emptyList())

internal const val GENESIS_ENCOUNTER_COMMAND = "encounter-v1"
internal fun genesisEncounterReviewFingerprint(value: GenesisEncounterStartReview) =
    campaignHash(campaignJson.encodeToString(value.copy(fingerprint = "")))
internal fun GenesisEncounterSnapshot.ordered() = copy(participants = participants.sortedBy { it.position })

class GenesisEncounterConverters {
    @TypeConverter fun statusToString(value: GenesisEncounterStatus): String = value.name
    @TypeConverter fun statusFromString(value: String): GenesisEncounterStatus = GenesisEncounterStatus.valueOf(value)
}

@Dao interface GenesisEncounterDao {
    @Transaction @Query("SELECT * FROM genesis_encounters WHERE campaignId=:campaignId ORDER BY updatedAt DESC,id")
    fun observe(campaignId: String): Flow<List<GenesisEncounterSnapshot>>
    @Transaction @Query("SELECT * FROM genesis_encounters WHERE id=:id") suspend fun get(id: String): GenesisEncounterSnapshot?
    @Query("SELECT * FROM genesis_encounters ORDER BY id") suspend fun allEncounters(): List<GenesisEncounterRecord>
    @Query("SELECT * FROM genesis_encounter_participants ORDER BY encounterId,position") suspend fun allParticipants(): List<GenesisEncounterParticipant>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: GenesisEncounterRecord)
    @Update suspend fun update(value: GenesisEncounterRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: GenesisEncounterParticipant)
    @Query("DELETE FROM genesis_encounter_participants WHERE encounterId=:id") suspend fun clearParticipants(id: String)
}
