package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CampaignConnectionUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun expand(label: String) { compose.onNodeWithText(label).performScrollTo().performClick() }
    private fun show(view: CampaignConnectionView, auto: (Set<String>) -> Unit = {}, decision: (String, Boolean) -> Unit = { _, _ -> },
        restrict: (Set<String>,Set<String>) -> Unit = { _, _ -> }, pause: (Boolean) -> Unit = {}, sync: () -> Unit = {},
        prepare: () -> Unit = {}, review: () -> Unit = {}, combatReview: () -> Unit = {}): AutoCloseable {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { AstravarTheme {
            CampaignConnectionContent(view, { _, _, _, _, _ -> }, {}, sync, {}, auto, decision, {}, { _, _ -> }, {}, {}, restrict, pause,
                onPrepareCampaign = prepare, onReviewMembership = review, onReviewCombatAccess = combatReview, onReceiptPage = {})
        } }
        return AutoCloseable { activity.pause().stop().destroy() }
    }

    @Test fun emptyButSuccessfulHeartbeatOffersExplicitEnrollmentInsteadOfClaimingPublishedCharacterData() {
        var prepares = 0
        show(CampaignConnectionView(connected = true, status = "Connected", campaignId = "local-campaign",
            selectedProfileId = "fictional-player", localRecordCount = 0, sourceCampaignPrepared = false),
            prepare = { prepares++ }).use {
            expand("Sync details")
            compose.onNodeWithText("Local campaign records eligible for sync: 0").performScrollTo().assertExists()
            compose.onNodeWithText("Prepare connected local campaign").performScrollTo().performClick()
            assertEquals(1, prepares)
        }
    }

    @Test fun serviceContactCannotSupplyTheGameplayVerificationTimestamp() {
        val view = campaignConnectionView(buildJsonObject {
            put("connected", true); put("status", "Connected"); put("lastSyncAt", 10_000)
            put("lastDeviceSyncAt", 10_000); put("localRecordCount", 0)
            put("readiness", "NO_ELIGIBLE_RECORDS"); put("readinessTitle", "Connected · no eligible records")
            put("readinessDetail", "No saved records are publishable within this campaign and its current read permissions.")
            put("gameplayVerified", false); put("gameplayVerifiedAt", JsonNull)
        })
        assertNotNull(view.lastSyncAt)
        assertNull(view.lastServerResponseAt)
        assertNull(view.gameplayVerifiedAt)
        show(view).use {
            compose.onNodeWithTag("campaign-connection-status").assertTextEquals("Connected · no eligible records")
            compose.onNodeWithTag("campaign-gameplay-verification-time").performScrollTo()
                .assertTextEquals("Last complete gameplay verification: Not yet verified")
            compose.onNodeWithTag("campaign-service-response-time").assertDoesNotExist()
            expand("Sync details")
            compose.onNodeWithText("Current records independently verified: 0 of 0").performScrollTo().assertExists()
            compose.onNodeWithTag("campaign-service-response-time").performScrollTo()
                .assertTextEquals("Last successful service response: Not yet recorded")
        }
    }

    @Test fun failedPublicationShowsItsStageAndRetainedWorkSeparatelyFromServiceContact() {
        val view = campaignConnectionView(buildJsonObject {
            put("connected", true); put("status", "Synchronization retry required")
            put("lastServerResponseAt", 50_000); put("lastUploadAttemptAt", 60_000)
            put("lastPublicationAcceptedAt", 40_000); put("lastIndependentVerificationAt", 30_000)
            put("lastFullSyncAt", 20_000); put("lastSyncAt", 10_000)
            put("failedStage", "PUBLICATION_READBACK"); put("failureCode", "NETWORK_TIMEOUT")
            put("pendingRecordCount", 17); put("localRecordCount", 17); put("verifiedRecordCount", 0)
        })
        assertEquals(50_000L, view.lastServerResponseAt)
        assertEquals(60_000L, view.lastUploadAttemptAt)
        assertEquals(40_000L, view.lastPublicationAcceptedAt)
        assertEquals(30_000L, view.lastIndependentVerificationAt)
        assertEquals(20_000L, view.lastFullSyncAt)
        assertNull(view.gameplayVerifiedAt)
        show(view).use {
            compose.onNodeWithTag("campaign-sync-failed-stage").performScrollTo()
                .assertTextEquals("Sync stopped at: publication readback")
            compose.onNodeWithText("Records retained for publication or verification: 17").performScrollTo().assertExists()
        }
    }

    @Test fun diagnosticFieldsRejectUnknownProviderValuesAndDoNotPromoteOldCompletionTimes() {
        val view = campaignConnectionView(buildJsonObject {
            put("currentStage", "provider-secret-stage"); put("failedStage", "provider-secret-failure")
            put("failureCode", "provider-secret-code"); put("lastSyncAt", 1234)
            put("lastServerResponseAt", -1); put("pendingRecordCount", -1)
        })
        assertEquals("", view.currentStage)
        assertEquals("", view.failedStage)
        assertNull(view.lastServerResponseAt)
        assertNull(view.lastFullSyncAt)
        assertEquals(0, view.pendingRecordCount)
        assertFalse(view.toString().contains("provider-secret"))
    }

    @Test fun preparedCampaignShowsReviewedMembershipActionBeforeGameplaySharing() {
        var reviews = 0
        show(CampaignConnectionView(connected = true, status = "Connected", campaignId = "local-campaign",
            selectedProfileId = "fictional-player", localRecordCount = 0, sourceCampaignPrepared = true),
            review = { reviews++ }).use {
            compose.onNodeWithText("Review character membership and shared values").performScrollTo().performClick()
            assertEquals(1, reviews)
        }
    }

    @Test fun playableProjectionDriftNamesTheAffectedActorAndOffersExplicitReview() {
        var reviews = 0
        val view = campaignConnectionView(buildJsonObject {
            put("connected", true); put("campaignId", "local-campaign"); put("selectedProfileId", "selected-player")
            put("sourceCampaignPrepared", true); put("sourceActorPlayable", true)
            put("sourceActorCampaignId", "local-campaign"); put("readiness", "VALUES_REVIEW_REQUIRED")
            put("canonicalReviewProfileId", "other-player")
            put("profiles", buildJsonArray { add(buildJsonObject { put("id", "other-player"); put("name", "Fictional other actor"); put("campaignId", "local-campaign") }) })
        })
        assertEquals("other-player", view.canonicalReviewProfileId)
        assertEquals("selected-player", view.selectedProfileId)
        show(view, review = { reviews++ }).use {
            compose.onNodeWithTag("campaign-record-verification-blocked").performScrollTo().assertExists()
            compose.onAllNodesWithText("Local campaign records eligible for sync: 0").assertCountEquals(0)
            compose.onNodeWithText("Shared records for Fictional other actor differ from the saved playable values. Review and reconcile them before publishing. Existing records and queued work are retained.").performScrollTo().assertExists()
            compose.onNodeWithText("Review character membership and shared values").performScrollTo().performClick()
            assertEquals(1, reviews)
        }
    }

    @Test fun pausing_delivery_does_not_revoke_standing_permissions_and_manual_sync_stays_available() {
        var changedPause: Boolean? = null
        var changedScopes: Set<String>? = null
        var syncs = 0
        show(CampaignConnectionView(connected = true, automaticSyncPaused = true, readScopes = setOf("hp"),
            writeScopes = setOf("hp"), autoSyncScopes = setOf("hp")), auto = { changedScopes = it },
            pause = { changedPause = it }, sync = { syncs++ }).use {
            compose.onNodeWithTag("campaign-connection-status").assertTextEquals("Paused")
            compose.onNodeWithText("Sync now").performScrollTo().assertIsEnabled().performClick()
            assertEquals(1, syncs)
            expand("Sync settings")
            compose.onNodeWithTag("campaign-sync-pause").performScrollTo().assertIsOn().performClick()
            assertEquals(false, changedPause)
            assertNull(changedScopes)
        }
    }

    @Test fun automatic_saves_require_both_category_selection_and_explicit_authorization() {
        var authorized: Set<String>? = null
        show(CampaignConnectionView(connected = true, grantRevision = 1, readScopes = setOf("hp", "inventory"), writeScopes = setOf("hp")),
            auto = { authorized = it }).use {
            expand("Trusted DM Auto-Sync")
            compose.onNodeWithText("Saved authorization: manual approval.").performScrollTo().assertExists()
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().performClick()
            assertNull(authorized)
            compose.onNodeWithTag("trusted-dm-save").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithContentDescription("Automatically save HP, temporary HP and conditions").performScrollTo().performClick()
            assertNull(authorized)
            compose.onNodeWithTag("trusted-dm-save").performScrollTo().assertIsEnabled().performClick()
            assertEquals(setOf("hp"), authorized)
            compose.onAllNodesWithContentDescription("Automatically save Inventory and equipment").assertCountEquals(0)
        }
    }

    @Test fun disabling_auto_sync_is_an_explicit_empty_scope_update() {
        var authorized: Set<String>? = null
        show(CampaignConnectionView(connected = true, grantRevision = 2, readScopes = setOf("hp"), writeScopes = setOf("hp"), autoSyncScopes = setOf("hp")),
            auto = { authorized = it }).use {
            expand("Trusted DM Auto-Sync")
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().performClick()
            assertNull(authorized)
            compose.onNodeWithTag("trusted-dm-save").performScrollTo().assertIsEnabled().performClick()
            assertEquals(emptySet<String>(), authorized)
        }
    }

    @Test fun stale_revision_and_missing_preview_never_allow_manual_approval() {
        var decisions = 0
        val operation = CampaignOperationView("adjust_hp", "player", 4, 5, listOf("currentHp" to "20"), listOf("currentHp" to "17"), previewVerified = true)
        show(CampaignConnectionView(connected = true, pending = listOf(CampaignPendingView("cmd", "PENDING_APPROVAL", "Player confirmed action", "", listOf(operation)))),
            decision = { _, _ -> decisions++ }).use {
            compose.onNodeWithText("Current HP · 20").performScrollTo().assertExists()
            compose.onNodeWithText("Proposed HP · 17").performScrollTo().assertExists()
            compose.onNodeWithText("Verify").performScrollTo().assertIsNotEnabled()
            assertEquals(0, decisions)
            compose.onNodeWithText("Refuse").performScrollTo().performClick()
            assertEquals(1, decisions)
        }
    }

    @Test fun successfulReceiptDetailsStayCollapsedWhileUnverifiedHistoryRemainsVisible() {
        val committed = CampaignReceiptView("verified-fixture", "VERIFIED", "Saved", true, "event-1", "a".repeat(64),
            listOf(CampaignReceiptOperationView("adjust_hp","scope-actor",4,9,5)))
        val failed = CampaignReceiptView("pending-fixture","REPAIR_REVIEW","Earlier decision needs review",false)
        show(CampaignConnectionView(connected = true, campaignId = "opaque-continuity", campaignName = "Reviewed continuity",
            receipts = listOf(committed,failed), unverifiedHistoryCount = 1)).use {
            compose.onNodeWithText("Campaign: Reviewed continuity").assertExists()
            compose.onNodeWithText("opaque-continuity",substring = true).assertDoesNotExist()
            compose.onNodeWithText("Receipts needing attention: 1").assertExists()
            compose.onNodeWithText("Command pending-fixture").assertExists()
            compose.onNodeWithText("Command verified-fixture").assertDoesNotExist()
            expand("Recent activity and receipts")
            compose.onNodeWithText("Command verified-fixture").performScrollTo().assertExists()
            compose.onNodeWithText("Expected 4 · current 9 · committed 5").assertExists()
            compose.onNodeWithText("Receipt event-1").assertExists()
            expand("Sync settings")
            compose.onNodeWithTag("campaign-sync-notification").assertExists()
            compose.onNodeWithTag("campaign-sync-pushy").assertExists()
        }
    }

    @Test fun adapter_preserves_unknowns_and_does_not_display_credentials_or_claim_a_pending_receipt() {
        val view = campaignConnectionView(buildJsonObject {
            put("bearer", "secret"); put("connected", true)
            putJsonObject("grant") { put("revision", 1); putJsonArray("readScopes") { add("hp"); add("unrestricted-sql") } }
            putJsonArray("history") { add(buildJsonObject { put("commandId", "cmd"); put("state", "PENDING"); put("receiptJson", JsonNull) }) }
            putJsonArray("pending") { add(buildJsonObject {
                put("commandId", "cmd"); put("state", "PENDING_APPROVAL")
                putJsonArray("operations") { add(buildJsonObject {
                    put("type", "adjust_hp"); put("recordId", "player"); put("expectedRevision", 2)
                    putJsonObject("currentFields") { put("currentHp", JsonNull) }
                    putJsonObject("fields") { put("delta", -3) }
                }) }
            }) }
        })
        assertEquals(setOf("hp"), view.readScopes)
        assertNull(view.lastSyncAt)
        assertNull(view.pending.single().operations.single().currentRevision)
        assertEquals("Unknown", view.pending.single().operations.single().current.single().second)
        assertFalse(view.receipts.single().independentlyVerified)
        assertFalse(view.toString().contains("secret"))
    }

    @Test fun connectionSnapshotExposesLocalSharingReadinessWithoutInventingMembership() {
        val view = campaignConnectionView(buildJsonObject {
            put("connected", true); put("campaignId", "local-campaign")
            put("selectedProfileId", "fictional-player"); put("localRecordCount", 0)
            put("sourceActorCampaignId", JsonNull)
            put("sourceActorPlayable", false); put("sourceCampaignPrepared", false)
        })
        assertEquals(0, view.localRecordCount)
        assertNull(view.sourceActorCampaignId)
        assertFalse(view.sourceActorPlayable)
        assertFalse(view.sourceCampaignPrepared)
    }

    @Test fun exact_pending_approval_snapshot_displays_prepared_values_and_allows_one_reviewed_action() {
        var decision: Pair<String,Boolean>? = null
        val view = campaignConnectionView(buildJsonObject {
            put("connected", true); put("status", "Connected")
            putJsonObject("grant") { put("revision", 2); put("expiresAt", 2000); putJsonArray("writeScopes") { add("hp") } }
            putJsonArray("pending") { add(buildJsonObject {
                put("commandId", "cmd-exact"); put("state", "PENDING_APPROVAL"); put("source", "Explicit fictional player action")
                putJsonArray("operations") { add(buildJsonObject {
                    put("type", "adjust_hp"); put("recordId", "player"); put("expectedRevision", 4); put("currentRevision", 4)
                    putJsonObject("current") { put("currentHp", 20) }; putJsonObject("fields") { put("delta", -3) }
                    putJsonObject("proposedFields") { put("currentHp", 17) }
                }) }
            }) }
        }, now = 1000)
        show(view, decision = { id, approved -> decision = id to approved }).use {
            compose.onNodeWithText("Proposed HP · 17").performScrollTo().assertExists()
            compose.onAllNodesWithText("Proposed delta: -3").assertCountEquals(0)
            compose.onNodeWithText("Verify").performScrollTo().assertIsEnabled().performClick()
            assertEquals("cmd-exact" to true, decision)
        }
    }

    @Test fun expired_pairing_unlocks_inputs_and_revoking_or_expired_grants_never_show_connected() {
        val expired = campaignConnectionView(buildJsonObject {
            put("connected", true); put("status", "Connected")
            putJsonObject("grant") { put("expiresAt", 999) }
            putJsonObject("pairing") { put("userCode", "0000-AbCd"); put("verificationUri", "https://owner.example/pair"); put("expiresAt", 999) }
        }, now = 1000)
        assertFalse(expired.connected); assertEquals("Authentication expired", expired.status); assertNull(expired.pairingCode)
        val revoking = campaignConnectionView(buildJsonObject { put("connected", true); put("status", "Connected"); put("revokePending", true) })
        assertFalse(revoking.connected); assertEquals("Server revocation pending", revoking.status)
    }

    @Test fun update_permission_can_be_revoked_while_retaining_read_access() {
        var narrowed: Pair<Set<String>,Set<String>>? = null
        show(CampaignConnectionView(connected = true, readScopes = setOf("hp", "inventory"), writeScopes = setOf("hp", "inventory")),
            restrict = { reads, writes -> narrowed = reads to writes }).use {
            expand("Available record access")
            compose.onNodeWithText("Manage record permissions").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Keep update access to HP, temporary HP and conditions").performScrollTo().performClick()
            assertNull(narrowed)
            compose.onNodeWithTag("campaign-permissions-save").performScrollTo().assertIsEnabled().performClick()
            assertEquals(setOf("hp", "inventory") to setOf("inventory"), narrowed)
        }
    }

    @Test fun revoking_read_access_cascades_to_updates_only_after_explicit_save() {
        var narrowed: Pair<Set<String>,Set<String>>? = null
        show(CampaignConnectionView(connected = true, readScopes = setOf("hp", "inventory"), writeScopes = setOf("hp", "inventory")),
            restrict = { reads, writes -> narrowed = reads to writes }).use {
            expand("Available record access")
            compose.onNodeWithText("Manage record permissions").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Keep read access to Inventory and equipment").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Keep update access to Inventory and equipment").performScrollTo().assertIsOff().assertIsNotEnabled()
            assertNull(narrowed)
            compose.onNodeWithTag("campaign-permissions-save").performScrollTo().performClick()
            assertEquals(setOf("hp") to setOf("hp"), narrowed)
        }
    }

    @Test fun pending_restrictions_remain_explicit_and_revoked_categories_cannot_be_reenabled() {
        val view = campaignConnectionView(buildJsonObject {
            put("connected", true); put("grantPending", true)
            putJsonObject("grant") {
                put("revision", 2); put("expiresAt", 2000)
                putJsonArray("readScopes") { add("hp"); add("inventory") }
                putJsonArray("writeScopes") { add("hp"); add("inventory") }
                putJsonArray("autoSyncScopes") { add("hp") }
            }
            putJsonArray("localReadScopes") { add("hp") }; putJsonArray("localWriteScopes") {}
        }, now = 1000)
        assertEquals(setOf("hp"), view.readScopes); assertEquals(emptySet<String>(), view.writeScopes)
        assertTrue(view.autoSyncScopes.isEmpty()); assertTrue(view.authorizationPending)
        show(view).use {
            expand("Available record access")
            compose.onNodeWithText("Manage record permissions").performScrollTo().performClick()
            compose.onAllNodesWithContentDescription("Keep read access to Inventory and equipment").assertCountEquals(0)
            compose.onNodeWithContentDescription("Keep read access to HP, temporary HP and conditions").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("campaign-permissions-save").performScrollTo().assertIsNotEnabled()
            expand("Trusted DM Auto-Sync")
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun readOnlyCombatOffersAnOwnerReviewWithoutEnablingAutomaticSaves() {
        var requested = 0
        show(CampaignConnectionView(connected = true, readScopes = setOf("combat")), combatReview = { requested++ }).use {
            expand("Available record access")
            compose.onNodeWithText("Combat mechanics and roll history · read only").performScrollTo().assertExists()
            expand("Trusted DM Auto-Sync")
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Review combat update permission").performScrollTo().performClick()
            assertEquals(1, requested)
        }
    }

    @Test fun grantedCombatIsWritableAndAutomaticSavingNeedsASeparateChoice() {
        show(CampaignConnectionView(connected = true, readScopes = setOf("combat"), writeScopes = setOf("combat"))).use {
            expand("Available record access")
            compose.onNodeWithText("Combat mechanics and roll history · read and update").performScrollTo().assertExists()
            expand("Trusted DM Auto-Sync")
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithContentDescription("Automatically save Combat mechanics and roll history").performScrollTo().assertIsOff()
            compose.onAllNodesWithText("Review combat update permission").assertCountEquals(0)
        }
    }
}
