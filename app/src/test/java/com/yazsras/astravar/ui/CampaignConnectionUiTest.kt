package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
    @get:Rule val compose = createEmptyComposeRule()
    private fun show(view: CampaignConnectionView, auto: (Set<String>) -> Unit = {}, decision: (String, Boolean) -> Unit = { _, _ -> },
        restrict: (Set<String>,Set<String>) -> Unit = { _, _ -> }): AutoCloseable {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { AstravarTheme {
            CampaignConnectionContent(view, { _, _, _, _, _ -> }, {}, {}, {}, auto, decision, {}, { _, _ -> }, {}, {}, restrict)
        } }
        return AutoCloseable { activity.pause().stop().destroy() }
    }

    @Test fun automatic_saves_require_both_category_selection_and_explicit_authorization() {
        var authorized: Set<String>? = null
        show(CampaignConnectionView(connected = true, grantRevision = 1, readScopes = setOf("hp", "inventory"), writeScopes = setOf("hp")),
            auto = { authorized = it }).use {
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
            compose.onNodeWithText("Current currentHp: 20").performScrollTo().assertExists()
            compose.onNodeWithText("Proposed currentHp: 17").performScrollTo().assertExists()
            compose.onNodeWithText("Approve reviewed transaction").performScrollTo().assertIsNotEnabled()
            assertEquals(0, decisions)
            compose.onNodeWithText("Reject transaction").performScrollTo().performClick()
            assertEquals(1, decisions)
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
            compose.onNodeWithText("Proposed currentHp: 17").performScrollTo().assertExists()
            compose.onAllNodesWithText("Proposed delta: -3").assertCountEquals(0)
            compose.onNodeWithText("Approve reviewed transaction").performScrollTo().assertIsEnabled().performClick()
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
            compose.onNodeWithText("Manage record permissions").performScrollTo().performClick()
            compose.onAllNodesWithContentDescription("Keep read access to Inventory and equipment").assertCountEquals(0)
            compose.onNodeWithContentDescription("Keep read access to HP, temporary HP and conditions").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("campaign-permissions-save").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun combat_records_are_read_only_and_never_offer_automatic_engine_mutations() {
        show(CampaignConnectionView(connected = true, readScopes = setOf("combat"), writeScopes = setOf("combat"))).use {
            compose.onNodeWithText("Astravar configuration and roll history · read only").performScrollTo().assertExists()
            compose.onNodeWithTag("trusted-dm-toggle").performScrollTo().assertIsNotEnabled()
            compose.onAllNodesWithContentDescription("Automatically save Astravar configuration and roll history").assertCountEquals(0)
            compose.onNodeWithText("Manage record permissions").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Keep update access to Astravar configuration and roll history").performScrollTo().assertIsNotEnabled()
        }
    }
}
