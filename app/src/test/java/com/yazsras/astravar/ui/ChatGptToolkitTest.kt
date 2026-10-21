package com.yazsras.astravar.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
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
class ChatGptToolkitTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun searchFindsRecoveryInstructionsAndCopyKeepsTheCompleteEvergreenPrompt() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ChatGptPromptsPage() } }
            compose.onNodeWithText("Find a prompt").performTextInput("last resolved event")
            val campaignTitle = hasText("Continue a campaign in a new chat") and !hasSetTextAction()
            compose.onNode(campaignTitle).assertExists()
            // Multiple valid recovery cards may match; act on the selected card, not a global label.
            compose.onNode(hasText("Read prompt") and hasAnySibling(campaignTitle)).performScrollTo().performClick()
            compose.onNodeWithText("Act as DM for my established solo campaign.", substring = true).assertExists()
            compose.onNode(hasText("Copy handoff prompt") and hasAnySibling(campaignTitle)).performScrollTo().performClick()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(text.contains("through the connected authorized integration when available"))
            assertTrue(text.contains("Never invent actions, rolls, HP, currency"))
            assertFalse(text.contains("28c890"))
            compose.onNodeWithText("Copied — paste in ChatGPT").assertExists()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun allElevenWorkflowsAreSearchableAndCopyTheirRequiredRecoveryAndWriteBoundaries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = context.assets.open("astravar_chatgpt_prompts.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("templates").jsonArray
        }
        assertEquals("Keep the eleven established prompt identities", setOf("dm-continuity", "dev-continuity", "sync-reconcile",
            "level-up", "inventory-event", "checkpoint", "gear-audit", "research", "backup-recovery", "source-of-truth", "release"),
            catalog.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        assertEquals(11, catalog.size)
        val workflows = listOf(
            Triple("new chat", "Continue a campaign in a new chat", listOf("connected authorized integration", "Never invent actions", "sources disagree")),
            Triple("checkpoint ID", "Recover the latest authoritative checkpoint", listOf("read-only", "Do not continue the scene", "Never claim to have read inaccessible chats")),
            Triple("Save session handoff", "Save session handoff", listOf("pending dice/decisions", "read it back", "do not claim it was saved")),
            Triple("existing Astravar Android development", "Continue Android development in a new Work/Codex chat", listOf("actual remote heads", "persisted handoffs", "Do not force-push")),
            Triple("conversation context is exhausted", "Recover after conversation context is exhausted", listOf("actual accessible checkpoints", "Never claim access to earlier inaccessible chats", "read-only restore preview")),
            Triple("level-up", "Review a party member level-up", listOf("Do not invent HP rolls", "player and DM confirmation", "version-checked")),
            Triple("each denomination", "Update inventory, equipment and currency", listOf("unknown does not mean zero", "atomic command", "original receipt", "independent readback")),
            Triple("accepted recipe version", "Record inventions and completed crafting", listOf("stable idempotent command ID", "commit all related changes atomically", "review-only proposal")),
            Triple("cursor gaps", "Check synchronization, revisions and conflicts", listOf("same authoritative store", "revocation", "canonical cutover")),
            Triple("Compare without overwriting", "Reconcile Notion and Astravar records", listOf("read-only comparison", "stable external IDs", "do not import")),
            Triple("owner signing certificate", "Validate a new signed APK", listOf("exact owner signing certificate", "monotonic version code", "disposable emulator"))
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ChatGptPromptsPage() } }
            for ((query, title, required) in workflows) {
                compose.onNodeWithText("Find a prompt").performScrollTo().performTextReplacement(query)
                val cardTitle = hasText(title) and !hasSetTextAction()
                compose.onNode(cardTitle).assertExists()
                compose.onNode(hasText("Copy handoff prompt") and hasAnySibling(cardTitle)).performScrollTo().performClick()
                val copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip!!.getItemAt(0).text.toString()
                required.forEach { guard -> assertTrue("$title must retain: $guard", copied.contains(guard)) }
                assertFalse("Reusable prompts must not pin a prior build", copied.contains("28c890"))
                assertFalse("Reusable prompts must not pin a session UUID", Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").containsMatchIn(copied))
                compose.onNodeWithText("Copied — paste in ChatGPT").assertExists()
            }
        } finally { activity.pause().stop().destroy() }
    }
}
