package com.yazsras.astravar.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Proof lives in the original immutable campaign receipt, never in a mutable profile label. */
@Serializable internal data class ReforgeCampaignIdentityProof(
    val format: String = "astravar-reviewed-playable-identity-v1",
    val playableId: String, val playableRevision: Long, val playableCreatedAt: Long,
    val originFingerprint: String, val reviewedName: String
)

internal data class ReforgeCampaignIdentity(
    val profile: CharacterProfile, val copiedFromRevision: Long? = null,
    val originalReceipt: CommandReceipt? = null
)

private const val IDENTITY_SOURCE_PREFIX = "Reviewed name-only playable identity\n"
internal fun reforgeIdentityCommand(profileId: String) = "reforge-identity:${reforgeHash(profileId).take(48)}"

/** Resolve an established source identity, a proven prior copy, or a vacant identity. Never aliases by name. */
internal suspend fun ReforgeRepository.inspectCampaignIdentity(legacy: CampaignLedgerRepository,
    reviewed: ReforgeSavedCharacter): ReforgeCampaignIdentity? {
    val character = reviewed.character
    checkId(character.id)
    require(reviewed.revision > 0 && character.revision == reviewed.revision)
    inspectManagedCampaignIdentity(legacy, reviewed)?.let { return it }
    val originalId = reviewed.origin.sourceProfileId
    if (originalId != null) {
        require(originalId == character.id && reviewed.origin.sourceFingerprint != null) { "The original campaign identity needs source review." }
        val sourceJson = requireNotNull(reviewed.origin.sourceSnapshotJson)
        val source = reforgeJson.decodeFromString<ReforgeLegacySnapshot>(sourceJson)
        require(source.profile.id == originalId && reforgeHash(sourceJson) == reviewed.origin.sourceFingerprint) {
            "The original identity proof disagrees with this sheet. No campaign record changed."
        }
        val profile = legacy.records.characterProfiles.first().firstOrNull { it.id == originalId }
            ?: error("The original campaign profile is unavailable. Restore or reconcile it before creating relationships.")
        return ReforgeCampaignIdentity(profile)
    }
    val receipt = legacy.records.receipt(reforgeIdentityCommand(character.id))
    val existing = legacy.records.characterProfiles.first().firstOrNull { it.id == character.id }
    if (receipt == null) {
        check(existing == null) { "A different campaign record already uses this identity. Review the conflict; it was not linked or overwritten." }
        return null
    }
    require(receipt.kind == "character-profile" && receipt.recordId == character.id && receipt.resultingRevision == 1L) {
        "The campaign identity receipt belongs to another operation. No record changed."
    }
    val outcome = campaignJson.decodeFromString<CharacterProfile>(receipt.outcomeJson)
    require(outcome.source.startsWith(IDENTITY_SOURCE_PREFIX)) { "The campaign identity has no matching playable proof." }
    val proof = reforgeJson.decodeFromString<ReforgeCampaignIdentityProof>(outcome.source.removePrefix(IDENTITY_SOURCE_PREFIX))
    require(proof.format == "astravar-reviewed-playable-identity-v1" && proof.playableId == character.id &&
        proof.playableRevision in 1..reviewed.revision && proof.playableCreatedAt == reviewed.createdAt &&
        proof.originFingerprint == reforgeHash(reforgeJson.encodeToString(reviewed.origin))) { "The campaign identity was created from a different playable record." }
    val original = CharacterProfile(id = character.id, name = proof.reviewedName)
    require(outcome.copy(revision = 1, source = "", updatedAt = 0) == original && outcome.revision == 1L) {
        "The original identity receipt contains more than the reviewed name. Review it before using this identity."
    }
    val payload = campaignJson.encodeToString(original.copy(revision = 0, source = "", updatedAt = 0))
    val fingerprint = campaignHash(campaignJson.encodeToString(listOf("v2", "character-profile", character.id, null, payload, outcome.source)))
    require(receipt.fingerprint == fingerprint) { "The campaign identity proof failed verification." }
    return ReforgeCampaignIdentity(existing ?: error("The proven campaign identity is missing. Restore or reconcile it before continuing."),
        proof.playableRevision, receipt)
}

/** Explicit one-time name copy. No HP, equipment, level, resources, mechanics or synchronization grants cross stores. */
internal suspend fun ReforgeRepository.createReviewedCampaignIdentity(legacy: CampaignLedgerRepository,
    reviewed: ReforgeSavedCharacter): ReforgeCampaignIdentity {
    // A completed source write is recoverable even if a later playable rename/archive advanced its revision.
    inspectCampaignIdentity(legacy, reviewed)?.let { return it }
    return withReviewedIdentitySnapshot(reviewed) {
        inspectCampaignIdentity(legacy, reviewed)?.let { return@withReviewedIdentitySnapshot it }
        require(!reviewed.character.archived) { "Restore this playable sheet before creating its campaign identity." }
        val name = reviewed.character.name.trim()
        require(name.isNotBlank() && name.length <= 120) { "Campaign connection names must contain 1–120 characters. Review the playable name first." }
        val proof = ReforgeCampaignIdentityProof(playableId = reviewed.character.id, playableRevision = reviewed.revision,
            playableCreatedAt = reviewed.createdAt, originFingerprint = reforgeHash(reforgeJson.encodeToString(reviewed.origin)), reviewedName = name)
        val provenance = IDENTITY_SOURCE_PREFIX + reforgeJson.encodeToString(proof)
        // saveProfile guards vacancy, writes profile+audit+receipt atomically, and returns its original result on retry.
        legacy.records.saveProfile(CharacterProfile(id = reviewed.character.id, name = name), source = provenance,
            commandId = reforgeIdentityCommand(reviewed.character.id))
        requireNotNull(inspectCampaignIdentity(legacy, reviewed))
    }
}
