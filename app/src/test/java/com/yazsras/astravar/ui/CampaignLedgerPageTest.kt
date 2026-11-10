package com.yazsras.astravar.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.CampaignLedgerDb
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.LedgerDraft
import com.yazsras.astravar.data.CharacterProfile
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
    @get:Rule val compose = createAstravarComposeRule()
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
            compose.onNodeWithText("Sources & advanced details").performScrollTo().performClick()
            compose.onNodeWithText("Source / checkpoint (optional)").performScrollTo().performTextInput("Synthetic saved UI state")
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
            if (compose.onAllNodesWithText("Source / checkpoint (optional)").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithText("Sources & advanced details").performScrollTo().performClick()
            }
            compose.onNodeWithText("Source / checkpoint (optional)").assert(
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

    @Test fun inventoryDetailEquipsExistingItemForCompanionOnlyAfterReviewedCommit() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val item = runBlocking {
            repo.records.saveProfile(CharacterProfile(id = "synthetic-ally", name = "Fictional scout"), source = "Synthetic inventory journey")
            repo.create(LedgerDraft(title = "Fictional silver harness", category = "Armor", quantity = null, source = "Synthetic inventory journey"))
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
        fun dialogButton(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
        try {
            activity.get().setContent { AstravarTheme { CampaignLedgerPage(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(item.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Export JSON").assertDoesNotExist()
            compose.onNode(hasText(item.title) and hasClickAction()).performClick()
            dialogButton("Equip or transfer").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Commit equipment move").fetchSemanticsNodes().isNotEmpty() }
            dialogButton("Commit equipment move").assertIsNotEnabled()
            dialogButton("Unknown holder ▾").performScrollTo().performClick()
            compose.onNode(hasText("Fictional scout") and hasClickAction()).performClick()
            dialogButton("Carried ▾").performScrollTo().performClick()
            compose.onNode(hasText("Equipped") and hasClickAction()).performClick()
            runBlocking { assertNull(db.partyDao().placement(item.id)); assertEquals(item, db.ledgerDao().getItem(item.id)) }
            dialogText("Confirmed transfer / equipment event source").performScrollTo().performTextInput("Fictional reviewed equip event")
            dialogButton("Commit equipment move").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().placement(item.id)?.state == "Equipped" } }
            dialogButton("Close").performClick()
            compose.onNode(hasText(item.title) and hasClickAction()).performClick()
            dialogText("Fictional scout").assertExists()
            dialogButton("Record quantity").assertExists()
            runBlocking {
                assertEquals(1, db.ledgerDao().allItems().size)
                assertNull(db.ledgerDao().getItem(item.id)!!.quantity)
                assertEquals("synthetic-ally", db.partyDao().placement(item.id)!!.holderProfileId)
                val audit = db.recordsDao().allAudit().single { it.recordType == "item-placement" }
                assertNotNull(db.recordsDao().receipt(audit.commandId))
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowInventoryKeepsWholeLabelsAtLargerText() = verifyInventoryLabels(1.3f, "inventory-360-font130")

    @Test
    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun wideInventoryKeepsWholeLabelsAtLargerText() = verifyInventoryLabels(1.3f, "inventory-400-font130")

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowInventoryKeepsWholeLabelsAtDoubleText() = verifyInventoryLabels(2f, "inventory-360-font200")

    @Test
    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun wideInventoryKeepsWholeLabelsAtDoubleText() = verifyInventoryLabels(2f, "inventory-400-font200")

    @Suppress("DEPRECATION")
    private fun verifyInventoryLabels(fontScale: Float, captureName: String) {
        val db = database()
        val repository = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val resources = activity.get().resources
        val original = Configuration(resources.configuration)
        val defects = mutableListOf<String>()
        fun capture(suffix: String, modal: Boolean = false) {
            compose.runOnIdle { invalidateNativeCaptureWindows() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { captureNativeTestWindows("$captureName-$suffix", activity.get(), modal) }
        }
        fun checkLabel(label: String, singleLine: Boolean) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            if (kotlin.math.abs(layout.layoutInput.density.fontScale - fontScale) > .01f)
                defects += "$label uses ${layout.layoutInput.density.fontScale}x instead of ${fontScale}x"
            for (line in 0 until layout.lineCount) {
                if (layout.isLineEllipsized(line) || layout.getLineLeft(line) < -.5f ||
                    layout.getLineRight(line) > layout.size.width + .5f ||
                    layout.getLineBottom(line) > layout.size.height + .5f)
                    defects += "$label has clipped or ellipsized line $line"
            }
            if (singleLine && layout.lineCount != 1)
                defects += "$label breaks its word across ${layout.lineCount} lines at ${layout.size.width}px"
            for (line in 0 until layout.lineCount - 1) {
                val boundary = layout.getLineEnd(line)
                if (boundary in 1 until label.length && !label[boundary - 1].isWhitespace() && !label[boundary].isWhitespace())
                    defects += "$label has a mid-word line break at offset $boundary"
            }
        }
        try {
            resources.updateConfiguration(Configuration(original).apply { this.fontScale = fontScale }, resources.displayMetrics)
            activity.get().setContent { AstravarTheme { CampaignLedgerPage(repository) } }
            compose.onNodeWithText("Inventory").assertIsDisplayed()
            capture("header")
            for (label in listOf("Inventory", "Items", "Equipped", "Categories")) checkLabel(label, singleLine = true)
            val add = compose.onNode(hasText("Add Item") and hasClickAction())
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            checkLabel("Add Item", singleLine = false)
            assertTrue("Inventory overview label defects: ${defects.joinToString("; ")}", defects.isEmpty())
            add.performClick()
            compose.onNodeWithText("Item name").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save new item").assertIsDisplayed().assertIsNotEnabled()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            // A pristine form intentionally has Close and a disabled Save; Cancel
            // appears only for a dirty draft. Exercise the real initial form contract.
            compose.onNode(hasText("Close") and hasClickAction()).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            for (label in listOf("Item name", "Save new item", "Close")) checkLabel(label, singleLine = false)
            capture("editor", modal = true)
            compose.onNode(hasText("Close") and hasClickAction()).performClick()
            compose.onNodeWithText("Item name").assertDoesNotExist()
            assertTrue("Opening and dismissing Add must not create an item", runBlocking { db.ledgerDao().allItems().isEmpty() })
            assertTrue("Opening and dismissing Add must not create an event", runBlocking { db.ledgerDao().allEvents().isEmpty() })
            assertTrue("Inventory label defects: ${defects.joinToString("; ")}", defects.isEmpty())
        } finally {
            resources.updateConfiguration(original, resources.displayMetrics)
            activity.pause().stop().destroy()
            db.close()
        }
    }
}
