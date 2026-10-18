package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()

    @Test fun itemCreateReplayFromPrecommitSavedActivityStateReturnsOriginalItemAndReceipt() {
        val db = database(); val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val visible = mutableStateOf(true)
        try {
            activity.get().setContent { if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                AstravarTheme { CampaignLedgerPage(repo) }
            } }
            compose.onNodeWithText("Add Item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Synthetic crash-retry lens")
            compose.onNodeWithText("Source / checkpoint").performScrollTo().performTextInput("Synthetic confirmed acquisition")
            var beforeCommit: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { beforeCommit = registry.performSave() }
            compose.onNodeWithText("Save new item").performClick()
            compose.waitUntil(10_000) { runBlocking { db.ledgerDao().allItems().size == 1 } }
            compose.waitForIdle()
            val original = runBlocking { db.ledgerDao().allItems().single() }
            val receipt = runBlocking { db.recordsDao().allReceipts().single() }
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(beforeCommit, canBeSaved = { true }); visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("Save new item").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save new item").fetchSemanticsNodes().isEmpty() }
            runBlocking {
                assertEquals(listOf(original), db.ledgerDao().allItems())
                assertEquals(1, db.ledgerDao().allEvents().size)
                assertEquals(listOf(receipt), db.recordsDao().allReceipts())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun inventionCreateReplayFromPrecommitSavedStateDoesNotCreateSecondProject() {
        val db = database(); val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        val visible = mutableStateOf(true)
        try {
            activity.get().setContent { if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                AstravarTheme { InventionsPageV2(repo) }
            } }
            compose.onNodeWithText("Add Invention").performClick()
            compose.onNodeWithText("Invention name").performScrollTo().performTextInput("Synthetic crash-retry blueprint")
            compose.onNodeWithText("Source / resolved scene / checkpoint").performScrollTo().performTextInput("Synthetic accepted design")
            var beforeCommit: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { beforeCommit = registry.performSave() }
            compose.onNodeWithText("Save invention").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allProjects().size == 1 } }
            compose.waitForIdle()
            val original = runBlocking { db.recordsDao().allProjects().single() }
            val receipt = runBlocking { db.recordsDao().allReceipts().single() }
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(beforeCommit, canBeSaved = { true }); visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("Save invention").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save invention").fetchSemanticsNodes().isEmpty() }
            runBlocking {
                assertEquals(listOf(original), db.recordsDao().allProjects())
                assertEquals(listOf(receipt), db.recordsDao().allReceipts())
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun itemAuditPaginationRemainsReachableWhenThereIsOnlyOneInventoryEvent() {
        val db = database(); val repo = CampaignLedgerRepository(db)
        val item = runBlocking {
            val item = repo.create(LedgerDraft("Synthetic audit-rich item"))
            repeat(45) { index -> repo.records.saveItemProperty(ItemProperty(item.id, "Synthetic property $index", "Known test value"), source = "Synthetic property source $index") }
            item
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CampaignLedgerPage(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(item.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(item.title).performClick()
            compose.onNodeWithText("History").performScrollTo().performClick()
            compose.onNodeWithText("Show more item history").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("Synthetic property source 0").performScrollTo().assertIsDisplayed()
            runBlocking { assertEquals(1, db.ledgerDao().allEvents().size); assertEquals(46, db.recordsDao().allAudit().size) }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun backupReviewExposesEveryFieldUnknownsOriginalIdsSourcesAndOlderRowsWithoutWrites() {
        val item = LedgerItem("synthetic-review-item", "Synthetic review relic", quantity = null, source = "Synthetic authoritative checkpoint", createdAt = 1, updatedAt = 1)
        val rows = (0..40).map { index -> item.copy(id = "synthetic-review-$index", title = "Synthetic item $index", source = "Synthetic source $index") }
        val backup = LedgerExport(exportedAt = 1, items = rows + item, events = emptyList(), records = CampaignRecordsExport(
            wallets = listOf(PartyWallet("synthetic-wallet", name = "Synthetic purse", gold = 3, source = "Synthetic wallet source"))))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { CampaignBackupContentsV21(backup) } } }
            compose.onNodeWithText("Items (42)").performScrollTo().performClick()
            compose.onNodeWithText("Show more items").performScrollTo().performClick()
            compose.onNodeWithText("Synthetic review relic · synthetic-re").performScrollTo().performClick()
            compose.onNodeWithText("Original ID").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("synthetic-review-item").onFirst().assertExists()
            compose.onAllNodesWithText("Synthetic authoritative checkpoint").onFirst().assertExists()
            compose.onNodeWithText("quantity").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Unknown (null)").onFirst().assertExists()
            compose.onNodeWithText("Wallets (1)").performScrollTo().assertExists()
        } finally { activity.pause().stop().destroy() }
    }
}
