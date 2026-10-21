package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OwnerSyncConfigTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var previousDialogSizing: String? = null
    @Before fun honorNativeDialogWindowSize() {
        previousDialogSizing = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogWindowSize() {
        previousDialogSizing?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }
    private val activity = lazy { Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    private fun show(view: OwnerConnectionView, pair: (String, String, String) -> Unit = { _, _, _ -> },
        decision: (String, Boolean) -> Unit = { _, _ -> }, disconnect: () -> Unit = {}, publish: (Long) -> Unit = {}, edit: (Int, Long) -> Unit = { _, _ -> }) {
        activity.value.get().setContent {
            AstravarTheme { OwnerConnectionContent(view, pair, {}, {}, {}, {}, decision, disconnect, {}, onPublishFixture = publish, onEditFixture = edit) }
        }
    }
    private fun close() { if (activity.isInitialized()) activity.value.pause().stop().destroy() }
    private fun change(revision: Long = 4, queued: Boolean = false) = OwnerChangeView("command-1", "owner-sync-fixture", "Fictional Intelligence update", 4, revision,
        "ChatGPT fictional acceptance", listOf(OwnerFieldDifference("Intelligence", "16", "18")), decisionQueued = queued)

    @Test fun disconnected_state_does_not_claim_connectivity_or_allow_fixture_upload() {
        try {
            show(OwnerConnectionView())
            compose.onNodeWithText("Fictional acceptance only").assertExists()
            compose.onNodeWithText("Device not enrolled").assertExists()
            compose.onNodeWithText("Last successful sync: Not yet verified").assertExists()
            compose.onNodeWithText("Start secure pairing").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Expose fictional test character").performScrollTo().assertIsNotEnabled()
        } finally { close() }
    }

    @Test fun pairing_requires_explicit_button_and_forwards_only_entered_scope() {
        var paired: List<String>? = null
        try {
            show(OwnerConnectionView(deviceName = "Galaxy S26"), pair = { a, b, c -> paired = listOf(a, b, c) })
            compose.onNodeWithText("Verified service HTTPS origin").performScrollTo().performTextInput("https://owner-service.example")
            assertNull(paired)
            compose.onNodeWithText("Start secure pairing").performScrollTo().assertIsEnabled().performClick()
            assertEquals(listOf("https://owner-service.example", "synthetic-owner-acceptance", "Galaxy S26"), paired)
        } finally { close() }
    }

    @Test fun proposal_shows_each_value_and_waits_for_explicit_approval() {
        var decision: Pair<String, Boolean>? = null
        try {
            show(OwnerConnectionView(enrolled = true, pending = listOf(change())), decision = { id, yes -> decision = id to yes })
            compose.onNodeWithText("Current: 16").performScrollTo().assertExists()
            compose.onNodeWithText("Proposed: 18").performScrollTo().assertExists()
            assertNull(decision)
            compose.onNodeWithText("Approve change").performScrollTo().performClick()
            assertEquals("command-1" to true, decision)
        } finally { close() }
    }

    @Test fun stale_proposal_cannot_be_approved_but_can_be_rejected() {
        var decision: Pair<String, Boolean>? = null
        try {
            show(OwnerConnectionView(enrolled = true, pending = listOf(change(revision = 5).copy(state = "CONFLICT"))), decision = { id, yes -> decision = id to yes })
            compose.onNodeWithText("Approve change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Reject change").performScrollTo().performClick()
            assertEquals("command-1" to false, decision)
        } finally { close() }
    }

    @Test fun queued_decision_disables_repeat_approval_and_never_claims_saved_revision() {
        try {
            show(OwnerConnectionView(enrolled = true, pending = listOf(change(queued = true))))
            compose.onNodeWithText("Decision saved on this device; server confirmation pending.").performScrollTo().assertExists()
            compose.onNodeWithText("Approve change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Reject change").performScrollTo().assertIsNotEnabled()
            compose.onAllNodesWithText("Server revision", substring = true).assertCountEquals(0)
        } finally { close() }
    }

    @Test fun disconnect_requires_user_confirmation_and_does_not_invent_revocation() {
        var calls = 0
        try {
            show(OwnerConnectionView(enrolled = true), disconnect = { calls++ })
            compose.onNodeWithText("Revoke and disconnect").performScrollTo().performClick()
            assertEquals(0, calls)
            compose.onNodeWithText("Revoke this device?").assertExists()
            compose.onAllNodesWithText("Revoke and disconnect").filterToOne(hasAnyAncestor(isDialog())).performClick()
            assertEquals(1, calls)
        } finally { close() }
    }

    @Test fun response_adapter_keeps_unknown_revision_explicit_and_ignores_credentials() {
        val view = ownerConnectionView(buildJsonObject {
            put("bearer", "do-not-display"); put("connected", true)
            putJsonArray("pending") { add(buildJsonObject {
                put("commandId", "command-1"); put("recordId", "owner-sync-fixture"); put("expectedRevision", 4)
                put("currentScore", 16); put("proposedScore", 18)
            }) }
        })
        assertEquals(-1L, view.pending.single().currentRevision)
        assertFalse(view.toString().contains("do-not-display"))
    }
    @Test fun owner_authorization_link_cannot_switch_scheme_host_port_or_add_credentials() {
        assertEquals("https://owner.example/pair#public-bootstrap", verifiedOwnerPage("https://owner.example/pair#public-bootstrap", "https://owner.example").toString())
        listOf("http://owner.example/pair", "https://other.example/pair", "https://owner.example:8443/pair", "https://owner.example:443/pair", "https://user@owner.example/pair").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { verifiedOwnerPage(url, "https://owner.example") }
        }
    }

    @Test fun publishing_local_fictional_change_requires_review_of_specific_value_and_revision() {
        var reviewed: Long? = null
        try {
            show(OwnerConnectionView(enrolled = true, fictionalScore = 18, fictionalRevision = 5), publish = { reviewed = it })
            compose.onNodeWithText("Review local fictional change").performScrollTo().performClick()
            assertNull(reviewed)
            compose.onNodeWithText("Publish fictional ability?").assertExists()
            compose.onNodeWithText("Send this device's fictional Intelligence score 18, local revision 5, to the paired service.", substring = true).assertExists()
            compose.onNodeWithText("Publish reviewed value").performClick()
            assertEquals(5L, reviewed)
        } finally { close() }
    }

    @Test fun terminal_server_conflict_cannot_be_rejected_or_approved_again() {
        try {
            show(OwnerConnectionView(enrolled = true, pending = listOf(change(revision = 5).copy(state = "CONFLICT", terminalReceipt = true))))
            compose.onNodeWithText("Approve change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Reject change").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("The server has issued a final receipt.", substring = true).performScrollTo().assertExists()
        } finally { close() }
    }

    @Test fun fictional_local_edit_validates_score_and_requires_save_without_publishing() {
        var edited: Pair<Int, Long>? = null
        var publications = 0
        try {
            show(OwnerConnectionView(enrolled = true, fictionalScore = 16, fictionalRevision = 4),
                publish = { publications++ }, edit = { score, revision -> edited = score to revision })
            compose.onNodeWithText("Edit fictional Intelligence").performScrollTo().performClick()
            compose.onNodeWithText("Current local Intelligence: 16 · revision 4").assertExists()
            compose.onNodeWithText("Save locally").assertIsNotEnabled()
            compose.onNodeWithText("New Intelligence score (1–30)").performTextReplacement("31")
            compose.onNodeWithText("Save locally").assertIsNotEnabled()
            compose.onNodeWithText("New Intelligence score (1–30)").performTextReplacement("18")
            assertNull(edited)
            compose.onNodeWithText("Save locally").assertIsEnabled().performClick()
            assertEquals(18 to 4L, edited)
            assertEquals(0, publications)
        } finally { close() }
    }

    @Test fun quarantined_decision_is_visible_before_recent_history_without_any_decision_controls() {
        var decisions = 0
        try {
            val ordinary = (1..12).map { OwnerReceiptView("publication-$it", "VERIFIED", it.toLong(), "") }
            show(OwnerConnectionView(enrolled = true, receipts = ordinary + OwnerReceiptView("blocked-command", "DECISION_CONFLICT", null, "")),
                decision = { _, _ -> decisions++ })
            compose.onNodeWithText("Decision delivery stopped · review required").performScrollTo().assertExists()
            compose.onNodeWithText("Command blocked-command").performScrollTo().assertExists()
            compose.onNodeWithText("Astravar will not resubmit or replace this command's decision.", substring = true).performScrollTo().assertExists()
            compose.onAllNodesWithText("Approve change").assertCountEquals(0)
            compose.onAllNodesWithText("Reject change").assertCountEquals(0)
            assertEquals(0, decisions)
        } finally { close() }
    }

    @Test fun re_pairing_review_preserves_old_publication_without_automatic_resubmission() {
        try {
            val ordinary = (1..12).map { OwnerReceiptView("publication-$it", "VERIFIED", it.toLong(), "") }
            show(OwnerConnectionView(enrolled = true, receipts = ordinary + OwnerReceiptView("previous-publication", "REPAIR_REVIEW", null, "")))
            compose.onNodeWithText("Previous publication preserved · review required").performScrollTo().assertExists()
            compose.onNodeWithText("Command previous-publication").performScrollTo().assertExists()
            compose.onNodeWithText("Astravar will not resend it automatically; a new publication requires explicit review.", substring = true).performScrollTo().assertExists()
            compose.onAllNodesWithText("Approve change").assertCountEquals(0)
            compose.onAllNodesWithText("Reject change").assertCountEquals(0)
        } finally { close() }
    }

}
