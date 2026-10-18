package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.CampaignLedgerDb
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.LedgerDraft
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignLedgerPageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var oldRequestedSize: String? = null
    @Before fun useRequestedDialogSize() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogSize() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
    ).build()

    @Test fun newItemRequiresExplicitDiscardBeforeClearingAnUnsavedForm() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CampaignLedgerPage(CampaignLedgerRepository(db)) } }
            // Creation is contextual; the overview no longer exposes an empty data-entry form.
            compose.onNodeWithText("Item name").assertDoesNotExist()
            compose.onNodeWithText("Add Item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Unsaved draft")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertExists()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Item name").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Unsaved draft"))
            )
            runBlocking { assertTrue(db.ledgerDao().allItems().isEmpty()) }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText("Add Item").performClick()
            // Preserve the original clear/new-item assertions, now following an approved discard.
            compose.onNodeWithText("Item name").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
            compose.onNodeWithText("Save new item").assertIsNotEnabled()
            runBlocking { assertTrue(db.ledgerDao().allItems().isEmpty()) }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun restoredSavedUiStatePreservesDraftUntilOneExplicitSave() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        // Exercise the actual rememberSaveable contract through full composition disposal/restore.
        // This is host UI-state restoration coverage, not a claim about a physical process kill.
        var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val visible = mutableStateOf(true)
        try {
            activity.get().setContent {
                if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme { CampaignLedgerPage(repo) }
                }
            }
            compose.onNodeWithText("Add Item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Restored synthetic item")
            compose.onNodeWithText("Quantity").performScrollTo().performTextInput("3")
            compose.onNodeWithText("Source / checkpoint").performScrollTo().performTextInput("Synthetic saved UI state")
            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { saved = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.onNodeWithText("Item name").assertDoesNotExist()
            compose.runOnIdle {
                registry = SaveableStateRegistry(restoredValues = saved, canBeSaved = { true })
                visible.value = true
            }
            compose.waitForIdle()
            compose.onNodeWithText("Item name").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Restored synthetic item"))
            )
            compose.onNodeWithText("Quantity").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("3"))
            )
            compose.onNodeWithText("Source / checkpoint").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Synthetic saved UI state"))
            )
            runBlocking { assertTrue(db.ledgerDao().allItems().isEmpty()); assertTrue(db.ledgerDao().allEvents().isEmpty()) }
            compose.onNodeWithText("Save new item").performClick()
            compose.waitUntil(10_000) { runBlocking { db.ledgerDao().allItems().size == 1 } }
            runBlocking {
                val item = db.ledgerDao().allItems().single()
                assertEquals("Restored synthetic item", item.title)
                assertEquals(3, item.quantity)
                assertEquals("Synthetic saved UI state", item.source)
                assertEquals(1, db.ledgerDao().allEvents().size)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
    @Test fun missingLargeBaselineCannotTurnAnEditIntoANewItemOrEraseItsSavedImage() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val largeImage = "A".repeat(900_000) // Synthetic Base64-sized text, no real artwork or campaign data.
        val original = runBlocking { repo.create(LedgerDraft(title = "Large synthetic item", imageBase64 = largeImage, source = "Synthetic recovery fixture")) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val visible = mutableStateOf(true)
        try {
            activity.get().setContent {
                if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme { CampaignLedgerPage(repo) }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(original.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(original.title).performClick()
            compose.onNodeWithText("Edit item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextReplacement("Unsaved protected edit")
            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { saved = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            val strings = saved.values.flatten().filterIsInstance<String>()
            assertTrue("Large images and baseline JSON must not enter the Activity Bundle", strings.none { it.length > UiDraftTextStore.INLINE_BYTES })
            val references = strings.filter { it.startsWith("astravar-ui-draft:v1:f:") }
            assertTrue("Both the baseline and image use compact references", references.size >= 2)
            val baselineToken = references.first { it.split(':')[3].toInt() > largeImage.length }
            val baselineFile = File(File(context.filesDir, "ui-drafts"), baselineToken.substringAfterLast(':') + ".txt")
            assertTrue(baselineFile.delete()) // Delete only this synthetic transient draft, never a Room record.
            compose.runOnIdle {
                registry = SaveableStateRegistry(restoredValues = saved, canBeSaved = { true })
                visible.value = true
            }
            compose.waitForIdle()
            compose.onNodeWithText(UiDraftTextStore.MISSING_ERROR).assertExists()
            compose.onNodeWithText("Save item changes").assertIsNotEnabled()
            compose.onNodeWithText("Save new item").assertDoesNotExist()
            runBlocking {
                assertEquals(listOf(original), db.ledgerDao().allItems())
                assertEquals(1, db.ledgerDao().allEvents().size)
            }
            // Explicit discard/reopen reloads the authoritative record and image, enabling safe editing.
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText(original.title).performClick()
            compose.onNodeWithText("Edit item").performClick()
            compose.onNodeWithText(UiDraftTextStore.MISSING_ERROR).assertDoesNotExist()
            compose.onNodeWithText("Save item changes").assertIsEnabled()
            runBlocking {
                assertEquals(original.id, db.ledgerDao().allItems().single().id)
                assertEquals(largeImage, db.ledgerDao().allItems().single().imageBase64)
                assertEquals(1, db.ledgerDao().allEvents().size)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

}
