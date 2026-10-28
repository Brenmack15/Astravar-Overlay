package com.yazsras.astravar.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual Room and Compose interactions; every record is an isolated fictional fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatGptPromptManagementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var database: CampaignLedgerDb
    private lateinit var repository: ChatGptPromptRepository
    private var activity: ActivityController<ComponentActivity>? = null

    @Before fun createIsolatedStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        repository = CampaignLedgerRepository(database).prompts
        repository.initialize(listOf(ChatGptPromptSeed("fixture-stored", "Fictional stored prompt", "Fixture only",
            "Original fictional wording", "Testing")))
    }

    @After fun closeIsolatedStore() {
        activity?.pause()?.stop()?.destroy()
        database.close()
    }

    private fun openPage() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity!!.get().setContent {
            AstravarTheme {
                Surface(color = RebuildPalette.Canvas, contentColor = MaterialTheme.colorScheme.onBackground) {
                    ChatGptPromptsPage(repository)
                }
            }
        }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Add Prompt").assertIsEnabled() }.isSuccess
        }
        compose.runOnIdle { captureNativeTestWindows("chatgpt-prompts-list", activity!!.get()) }
    }

    private fun stored(id: String): ChatGptPrompt? = runBlocking { repository.get(id) }
    private fun copiedText(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }
    private fun awaitSaved(id: String, revision: Long) {
        compose.waitUntil(10_000) { stored(id)?.revision == revision }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Name").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun createReadEditCancelAndConfirmedDeleteUseTheStoredRecord() {
        openPage()
        compose.onNodeWithText("Add Prompt").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performScrollTo().performTextInput("Fictional custom prompt")
        compose.onNodeWithText("Identifier").performScrollTo().performTextReplacement("fictional-custom")
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Fictional descriptive purpose")
        compose.onNodeWithText("Category").performScrollTo().performTextInput("Fictional tests")
        compose.onNodeWithText("Prompt content").performScrollTo().performTextInput("First fictional line.\nSecond fictional line.")
        compose.runOnIdle { captureNativeTestWindows("chatgpt-prompts-editor", activity!!.get(), modal = true) }
        compose.onNodeWithText("Save").performClick()
        awaitSaved("fictional-custom", 1)
        val created = requireNotNull(stored("fictional-custom"))
        assertEquals("Fictional descriptive purpose", created.description)
        assertEquals("Fictional tests", created.category)
        assertEquals("First fictional line.\nSecond fictional line.", created.content)
        assertTrue(created.createdAt > 0)

        compose.onNodeWithText("Find a prompt").performScrollTo().performTextInput("Fictional custom")
        compose.onNodeWithText("Read prompt").performScrollTo().performClick()
        compose.onNodeWithText(created.content).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("prompt-edit-fictional-custom").performScrollTo().performClick()
        compose.onNodeWithText("Identifier").assertIsNotEnabled()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("Fictional custom prompt edited")
        compose.onNodeWithText("Prompt content").performScrollTo().performTextReplacement("Confirmed fictional wording")
        compose.onNodeWithText("Save").performClick()
        awaitSaved("fictional-custom", 2)
        val edited = requireNotNull(stored("fictional-custom"))
        assertEquals("Confirmed fictional wording", edited.content)
        assertEquals(created.id, edited.id)
        assertEquals(created.createdAt, edited.createdAt)
        assertTrue(edited.updatedAt >= created.updatedAt)

        compose.onNodeWithTag("prompt-edit-fictional-custom").performScrollTo().performClick()
        compose.onNodeWithText("Prompt content").performScrollTo().performTextReplacement("Discard this fictional draft")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Name").assertDoesNotExist()
        assertEquals(edited, stored("fictional-custom"))

        compose.onNodeWithTag("prompt-delete-fictional-custom").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(edited, stored("fictional-custom"))
        compose.onNodeWithTag("prompt-delete-fictional-custom").performScrollTo().performClick()
        compose.onNode(hasText("Delete prompt") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { stored("fictional-custom") == null }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("prompt-edit-fictional-custom").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("prompt-edit-fictional-custom").assertDoesNotExist()
        assertNotNull("Deleting one prompt must preserve another", stored("fixture-stored"))
    }

    @Test fun copyingUsesTheLatestSavedTextAndPromptsForSinglePassVariableValues() {
        openPage()
        val original = requireNotNull(stored("fixture-stored"))
        runBlocking {
            repository.update(original.id, original.revision, ChatGptPromptDraft(original.name,
                "Latest fictional wording", original.description, original.category))
        }
        compose.onNodeWithTag("prompt-copy-fixture-stored").performScrollTo().performClick()
        compose.waitUntil(10_000) { copiedText() == "Latest fictional wording" }
        assertEquals("Latest fictional wording", copiedText())

        val current = requireNotNull(stored(original.id))
        runBlocking {
            repository.update(current.id, current.revision, ChatGptPromptDraft(current.name,
                "Describe {characterName} using {context}. Literal {{context}}.", current.description, current.category))
        }
        compose.onNodeWithTag("prompt-copy-fixture-stored").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Prompt variables").fetchSemanticsNodes().isNotEmpty() }
        val contextValue = "a \$5 object, a backslash \\ and {characterName}"
        compose.onNodeWithText("characterName").performScrollTo().performTextInput("Fictional Arin")
        compose.onNodeWithText("context").performScrollTo().performTextInput(contextValue)
        compose.onNodeWithText("Copy prompt").performClick()
        val expected = "Describe Fictional Arin using $contextValue. Literal {context}."
        compose.waitUntil(10_000) { copiedText() == expected }
        assertEquals(expected, copiedText())
        assertTrue("Template is saved with placeholders intact", stored(original.id)!!.content.contains("{characterName}"))
    }

    @Test fun editorValidationAndStaleRevisionNeverOverwriteANewerSavedPrompt() {
        openPage()
        compose.onNodeWithTag("prompt-edit-fixture-stored").performScrollTo().performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("Fictional stored prompt")
        compose.onNodeWithText("Prompt content").performScrollTo().performTextReplacement("Older unsaved fictional draft")
        val original = requireNotNull(stored("fixture-stored"))
        val newer = runBlocking {
            repository.update(original.id, original.revision, ChatGptPromptDraft(original.name,
                "Newer independently saved fictional wording", original.description, original.category))
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("changed", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(newer, stored(original.id))
        compose.onNodeWithText("Prompt content").assertTextContains("Older unsaved fictional draft")
    }
}
