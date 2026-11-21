package com.yazsras.astravar.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignSyncReadinessTest {
    private val ready = CampaignSyncReadinessInput(
        connected = true, hasDevice = true, hasCampaign = true, revokePending = false,
        campaignPrepared = true, hasActor = true, actorCampaignMatches = true,
        actorHasOtherCampaign = false, actorPlayable = true, readAccess = true,
        grantPending = false, eligible = 4, verified = 4, pendingPublications = 0,
        pendingDevice = 0, pendingReceipts = 0, conflict = false, error = "", verifiedAt = 1_000, now = 1_000)
    private fun check(expected: CampaignSyncReadiness, state: CampaignSyncReadinessInput) =
        assertEquals(expected, campaignSyncReadiness(state))

    @Test fun connectionAndConsentStatesRemainDistinct() {
        check(CampaignSyncReadiness.DISCONNECTED, ready.copy(connected = false, hasDevice = false))
        check(CampaignSyncReadiness.AUTHORIZATION_REQUIRED, ready.copy(connected = false))
        check(CampaignSyncReadiness.REVOCATION_PENDING, ready.copy(revokePending = true))
        check(CampaignSyncReadiness.NO_CAMPAIGN, ready.copy(hasCampaign = false))
        check(CampaignSyncReadiness.CAMPAIGN_NOT_PREPARED, ready.copy(campaignPrepared = false))
        check(CampaignSyncReadiness.MEMBERSHIP_REQUIRED, ready.copy(actorCampaignMatches = false))
        check(CampaignSyncReadiness.CAMPAIGN_MISMATCH, ready.copy(actorHasOtherCampaign = true))
        check(CampaignSyncReadiness.VALUES_REVIEW_REQUIRED, ready.copy(actorPlayable = false))
    }
    @Test fun temporarilyUnavailableCredentialStorageDoesNotRequireRepairingOwnerGrant() {
        check(CampaignSyncReadiness.CREDENTIAL_STORAGE_UNAVAILABLE,
            ready.copy(connected = false, error = "CREDENTIAL_STORAGE_UNAVAILABLE", pendingPublications = 1))
        check(CampaignSyncReadiness.AUTHORIZATION_REQUIRED,
            ready.copy(connected = false, error = "CREDENTIAL_REVOKED_OR_EXPIRED"))
    }
    @Test fun heartbeatAndPermissionNeverMasqueradeAsGameplayVerification() {
        check(CampaignSyncReadiness.NO_ELIGIBLE_RECORDS, ready.copy(eligible = 0, verified = 0))
        check(CampaignSyncReadiness.PERMISSION_DENIED, ready.copy(readAccess = false))
        check(CampaignSyncReadiness.PERMISSION_DENIED, ready.copy(error = "READ_SCOPE_DENIED"))
        check(CampaignSyncReadiness.AUTHORIZATION_PENDING, ready.copy(grantPending = true))
        check(CampaignSyncReadiness.PUBLICATION_PENDING, ready.copy(verified = 3))
        check(CampaignSyncReadiness.PUBLICATION_PENDING, ready.copy(verifiedAt = 0))
    }
    @Test fun deviceCommitReceiptsAndRecordDeliveryAreSeparate() {
        check(CampaignSyncReadiness.DEVICE_COMMIT_PENDING, ready.copy(pendingDevice = 1))
        check(CampaignSyncReadiness.RECEIPT_PENDING, ready.copy(pendingReceipts = 1))
        check(CampaignSyncReadiness.PUBLICATION_PENDING, ready.copy(pendingPublications = 1))
        check(CampaignSyncReadiness.CONFLICT, ready.copy(conflict = true))
        check(CampaignSyncReadiness.CONFLICT, ready.copy(error = "REVISION_CONFLICT"))
        check(CampaignSyncReadiness.RETRY_REQUIRED, ready.copy(error = "REMOTE_UNAVAILABLE"))
    }
    @Test fun pendingRemovalIsVisibleWithoutRequiringNewCampaignEnrollment() {
        val removedLegacyActor = ready.copy(hasActor = false, campaignPrepared = false,
            eligible = 0, verified = 0, pendingRemovals = 1)
        check(CampaignSyncReadiness.PUBLICATION_PENDING, removedLegacyActor)
        check(CampaignSyncReadiness.PUBLICATION_PENDING,
            removedLegacyActor.copy(hasActor = true, actorCampaignMatches = false))
        check(CampaignSyncReadiness.RETRY_REQUIRED, removedLegacyActor.copy(error = "REMOTE_UNAVAILABLE"))
        check(CampaignSyncReadiness.PERMISSION_DENIED, removedLegacyActor.copy(readAccess = false))
        check(CampaignSyncReadiness.AUTHORIZATION_REQUIRED, removedLegacyActor.copy(connected = false))
    }

    @Test fun onlyFreshCompleteReadbackIsVerified() {
        check(CampaignSyncReadiness.VERIFIED, ready)
        check(CampaignSyncReadiness.STALE, ready.copy(now = 902_000))
        check(CampaignSyncReadiness.STALE, ready.copy(verifiedAt = 62_000))
        // Historical grants may have published records without a Genesis actor association.
        check(CampaignSyncReadiness.VERIFIED, ready.copy(campaignPrepared = false, hasActor = false))
    }

    @Test fun blockedCanonicalProjectionRequiresReviewInsteadOfEmptyOrRemovedRecords() {
        val blocked = ready.copy(canonicalValuesRequireReview = true, eligible = 0, verified = 0,
            hasActor = false, pendingPublications = 1, error = "CONNECTION_UNVERIFIED")
        check(CampaignSyncReadiness.VALUES_REVIEW_REQUIRED, blocked)
        check(CampaignSyncReadiness.PERMISSION_DENIED, blocked.copy(readAccess = false))
        check(CampaignSyncReadiness.AUTHORIZATION_REQUIRED, blocked.copy(connected = false))
    }
}
