package com.yazsras.astravar.data

/** Delivery evidence only. This never enrolls actors, changes a grant, or writes gameplay. */
internal enum class CampaignSyncReadiness(val title: String, val detail: String) {
    DISCONNECTED("Device disconnected", "Pair this device with the selected campaign to authorize access."),
    AUTHORIZATION_REQUIRED("Authorization expired or revoked", "Renew owner authorization. Queued transactions remain saved for review."),
    REVOCATION_PENDING("Revocation pending", "Local access is disabled. Retry synchronization to confirm server revocation."),
    NO_CAMPAIGN("No campaign selected", "Select the existing character and authorize its campaign."),
    CAMPAIGN_NOT_PREPARED("Connected · campaign not prepared", "Prepare the connected campaign, then review character membership. Connection alone shares no character."),
    MEMBERSHIP_REQUIRED("Connected · character membership required", "Review the selected character's campaign membership before publishing its records."),
    CAMPAIGN_MISMATCH("Character belongs to another campaign", "Review the correct campaign and authorize that connection. Existing records were not reassigned."),
    VALUES_REVIEW_REQUIRED("Membership linked · review gameplay values", "Review shared values to reconcile the playable character. Membership alone does not verify its canonical statistics."),
    PERMISSION_DENIED("Record access denied", "Review the granted read categories or renew owner authorization. No extra access is inferred."),
    AUTHORIZATION_PENDING("Permission change pending", "The server has not yet confirmed the permission change. Retry sync; pending work is retained."),
    NO_ELIGIBLE_RECORDS("Connected · no eligible records", "No saved records are publishable within this campaign and its current read permissions."),
    DEVICE_COMMIT_PENDING("Gameplay update pending on device", "A server-delivered update still needs device approval or commitment. It is not a completed save."),
    RECEIPT_PENDING("Saved locally · receipt verification pending", "The local transaction is saved, but its server receipt and independent readback still need verification."),
    PUBLICATION_PENDING("Records awaiting verified delivery", "New or changed records and removals have not all passed independent server readback. Sync now or allow background recovery."),
    CONFLICT("Synchronization conflict", "Review changed revisions or permissions. Existing records and immutable receipts are retained."),
    RETRY_REQUIRED("Synchronization retry required", "The last attempt did not verify completion. Queued work is retained for the next connected attempt."),
    STALE("Records verified earlier · refresh required", "The last complete record verification is older than 5 minutes. Sync now to check current server state."),
    VERIFIED("Gameplay records independently verified", "Every currently eligible record matches an independently read server record, and the complete snapshot was acknowledged.")
}

internal data class CampaignSyncReadinessInput(
    val connected: Boolean, val hasDevice: Boolean, val hasCampaign: Boolean,
    val revokePending: Boolean, val campaignPrepared: Boolean, val hasActor: Boolean,
    val actorCampaignMatches: Boolean, val actorHasOtherCampaign: Boolean, val actorPlayable: Boolean,
    val readAccess: Boolean, val grantPending: Boolean, val eligible: Int, val verified: Int,
    val pendingPublications: Int, val pendingDevice: Int, val pendingReceipts: Int,
    val conflict: Boolean, val error: String, val verifiedAt: Long, val now: Long,
    val pendingRemovals: Int = 0
)

internal fun campaignSyncReadiness(input: CampaignSyncReadinessInput): CampaignSyncReadiness = with(input) {
    when {
        revokePending -> CampaignSyncReadiness.REVOCATION_PENDING
        hasDevice && !hasCampaign -> CampaignSyncReadiness.NO_CAMPAIGN
        !connected -> if (hasDevice) CampaignSyncReadiness.AUTHORIZATION_REQUIRED else CampaignSyncReadiness.DISCONNECTED
        !hasCampaign -> CampaignSyncReadiness.NO_CAMPAIGN
        error in setOf("ACCESS_DENIED", "READ_SCOPE_DENIED", "WRITE_SCOPE_DENIED", "CAMPAIGN_NOT_AUTHORIZED", "PRIVATE_GATEWAY_AUTH_REQUIRED") || !readAccess -> CampaignSyncReadiness.PERMISSION_DENIED
        conflict || error.contains("CONFLICT") || error == "GRANT_CHANGED" -> CampaignSyncReadiness.CONFLICT
        grantPending -> CampaignSyncReadiness.AUTHORIZATION_PENDING
        // Removing a previously published identity is still necessary when the
        // last local actor was removed or unassigned. Never ask for enrollment
        // before completing that scoped removal from the remote mirror.
        pendingRemovals > 0 -> if (error.isNotBlank()) CampaignSyncReadiness.RETRY_REQUIRED else CampaignSyncReadiness.PUBLICATION_PENDING
        hasActor && actorHasOtherCampaign -> CampaignSyncReadiness.CAMPAIGN_MISMATCH
        !campaignPrepared && (hasActor || eligible == 0) -> CampaignSyncReadiness.CAMPAIGN_NOT_PREPARED
        hasActor && !actorCampaignMatches -> CampaignSyncReadiness.MEMBERSHIP_REQUIRED
        hasActor && !actorPlayable -> CampaignSyncReadiness.VALUES_REVIEW_REQUIRED
        pendingDevice > 0 -> CampaignSyncReadiness.DEVICE_COMMIT_PENDING
        pendingReceipts > 0 -> CampaignSyncReadiness.RECEIPT_PENDING
        error.isNotBlank() -> CampaignSyncReadiness.RETRY_REQUIRED
        pendingPublications > 0 -> CampaignSyncReadiness.PUBLICATION_PENDING
        eligible == 0 -> CampaignSyncReadiness.NO_ELIGIBLE_RECORDS
        verified < eligible || verifiedAt <= 0 -> CampaignSyncReadiness.PUBLICATION_PENDING
        verifiedAt > now + 60_000 || now - verifiedAt > 5 * 60_000 -> CampaignSyncReadiness.STALE
        else -> CampaignSyncReadiness.VERIFIED
    }
}
