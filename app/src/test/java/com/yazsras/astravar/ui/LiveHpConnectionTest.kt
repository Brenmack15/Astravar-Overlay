package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
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
class LiveHpConnectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var previousDialogSizing: String? = null
    @Before fun honorNativeDialogSize() {
        previousDialogSizing = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogSize() {
        previousDialogSizing?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }
    private val activity = lazy { Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    private fun show(view: LiveHpConnectionView, pair: (String, String, String) -> Unit = { _, _, _ -> },
        decide: (String, Boolean) -> Unit = { _, _ -> }, revoke: () -> Unit = {}) {
        activity.value.get().setContent { AstravarTheme {
            LiveHpConnectionContent(view, pair, {}, {}, decide, revoke, { _, _ -> }, {})
        } }
    }
    private fun close() { if (activity.isInitialized()) activity.value.pause().stop().destroy() }
    private fun proposal(current: Int? = 21, maximum: Int? = 40, revision: Long = 7,
        state: String = "PENDING", queued: Boolean = false, terminal: Boolean = false) = LiveHpProposalView(
        "hp-command-1", "profile-one", "Saved character", current, 22, maximum, null, 7, revision,
        "HP connection test requested by owner", state, queued, terminal)
    private fun connected(pending: LiveHpProposalView) = LiveHpConnectionView(connected = true,
        selectedProfileId = "profile-one", pending = listOf(pending))

    @Test fun no_saved_profile_blocks_pairing_without_creating_or_inventing_hp() {
        var pairings = 0
        try {
            show(LiveHpConnectionView(origin = "https://owner.example"), pair = { _, _, _ -> pairings++ })
            compose.onNodeWithText("No saved character profile is available.", substring = true).performScrollTo().assertExists()
            compose.onNodeWithText("Pair this character").performScrollTo().assertIsNotEnabled()
            compose.onAllNodesWithText("Current HP:", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Expose fictional test character").assertCountEquals(0)
            assertEquals(0, pairings)
        } finally { close() }
    }

    @Test fun ambiguous_existing_profiles_require_exact_selection_before_explicit_pairing() {
        var result: List<String>? = null
        val profiles = listOf("profile-one", "profile-two").map { LiveHpCharacterView(it, "Same saved name", "campaign-scope", null, null, null, 3) }
        try {
            show(LiveHpConnectionView(origin = "https://owner.example", deviceName = "Saved handset", profiles = profiles),
                pair = { origin, profile, device -> result = listOf(origin, profile, device) })
            compose.onNodeWithText("Pair this character").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Profile ID: profile-two").performScrollTo().performClick()
            assertNull(result)
            compose.onNodeWithText("Pair this character").performScrollTo().assertIsEnabled().performClick()
            assertEquals(listOf("https://owner.example", "profile-two", "Saved handset"), result)
        } finally { close() }
    }

    @Test fun approval_displays_saved_and_proposed_hp_signed_test_adjustment_and_unchanged_context() {
        var decision: Pair<String, Boolean>? = null
        try {
            show(connected(proposal()), decide = { id, approve -> decision = id to approve })
            compose.onNodeWithText("HP connection test").performScrollTo().assertExists()
            compose.onNodeWithText("Current HP: 21").performScrollTo().assertExists()
            compose.onNodeWithText("Proposed HP: 22").performScrollTo().assertExists()
            compose.onNodeWithText("Adjustment: +1 HP").performScrollTo().assertExists()
            compose.onNodeWithText("Maximum HP: 40 (unchanged)").performScrollTo().assertExists()
            compose.onNodeWithText("Temporary HP: Unknown (unchanged)").performScrollTo().assertExists()
            compose.onNodeWithText("Profile ID: profile-one · current revision 7").performScrollTo().assertExists()
            assertNull(decision)
            compose.onNodeWithText("Approve HP change").performScrollTo().assertIsEnabled().performClick()
            assertEquals("hp-command-1" to true, decision)
        } finally { close() }
    }

    @Test fun stale_profile_cannot_be_approved_and_can_be_explicitly_rejected() {
        var decision: Pair<String, Boolean>? = null
        try {
            show(connected(proposal(revision = 8, state = "CONFLICT")), decide = { id, approve -> decision = id to approve })
            compose.onNodeWithText("Approve HP change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Reject HP change").performScrollTo().performClick()
            assertEquals("hp-command-1" to false, decision)
        } finally { close() }
    }

    @Test fun unknown_current_or_maximum_hp_cannot_be_approved_or_filled_from_reference_values() {
        try {
            show(connected(proposal(current = null, maximum = null)))
            compose.onNodeWithText("Current HP: Unknown").performScrollTo().assertExists()
            compose.onNodeWithText("Adjustment: Unknown").performScrollTo().assertExists()
            compose.onNodeWithText("Maximum HP: Unknown (unchanged)").performScrollTo().assertExists()
            compose.onNodeWithText("Approve HP change").performScrollTo().assertIsNotEnabled()
        } finally { close() }
    }

    @Test fun queued_or_terminal_decision_never_offers_duplicate_application() {
        try {
            show(connected(proposal(queued = true, terminal = true)))
            compose.onNodeWithText("Decision saved locally · server receipt pending").performScrollTo().assertExists()
            compose.onNodeWithText("Approve HP change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Reject HP change").performScrollTo().assertIsNotEnabled()
        } finally { close() }
    }

    @Test fun a_proposal_for_another_profile_cannot_be_approved_or_rejected_here() {
        try {
            show(connected(proposal().copy(profileId = "different-profile")))
            compose.onNodeWithText("Approve HP change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Reject HP change").performScrollTo().assertIsNotEnabled()
        } finally { close() }
    }

    @Test fun revocation_waits_for_explicit_confirmation_and_does_not_claim_server_success() {
        var calls = 0
        try {
            show(LiveHpConnectionView(connected = true), revoke = { calls++ })
            compose.onNodeWithText("Revoke HP connection").performScrollTo().performClick()
            compose.onNodeWithText("Revoke this HP connection?").assertExists()
            assertEquals(0, calls)
            compose.onNodeWithText("Revoke connection").performClick()
            assertEquals(1, calls)
        } finally { close() }
    }

    @Test fun display_adapter_whitelists_hp_and_preserves_unknowns_and_missing_revision() {
        val view = liveHpConnectionView(buildJsonObject {
            put("selectedProfileId", "profile-one"); put("campaignId", "campaign-one"); put("bearer", "NEVER_RENDER")
            putJsonObject("current") { put("name", "Saved character"); put("currentHp", JsonNull); put("maximumHp", 40); put("temporaryHp", JsonNull); put("revision", 7); put("inventory", "PRIVATE_INVENTORY") }
            putJsonArray("pending") { add(buildJsonObject {
                put("commandId", "hp-command-1"); put("currentHp", JsonNull); put("proposedHp", 22); put("expectedRevision", 7)
            }) }
        })
        assertNull(view.current!!.currentHp)
        assertNull(view.current.temporaryHp)
        assertEquals(40, view.current.maximumHp)
        assertEquals(-1L, view.pending.single().currentRevision)
        assertFalse(view.toString().contains("NEVER_RENDER"))
        assertFalse(view.toString().contains("PRIVATE_INVENTORY"))
    }
    @Test fun an_hp_connection_test_can_show_a_negative_adjustment_to_zero_without_altering_context() {
        try {
            show(connected(proposal(current = 1).copy(proposedHp = 0, temporaryHp = 6)))
            compose.onNodeWithText("Proposed HP: 0").performScrollTo().assertExists()
            compose.onNodeWithText("Adjustment: -1 HP").performScrollTo().assertExists()
            compose.onNodeWithText("Temporary HP: 6 (unchanged)").performScrollTo().assertExists()
            compose.onNodeWithText("Approve HP change").performScrollTo().assertIsEnabled()
        } finally { close() }
    }

}
