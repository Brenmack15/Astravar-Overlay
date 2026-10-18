package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
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
class PartyPlayerFirstTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var previousSize: String? = null
    @Before fun configureDialogs() {
        previousSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogs() {
        previousSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()

    @Test fun partyRosterOpensFourStableIdentitiesWithoutImportingPrivateStats() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { PartyPageV2(repo, null) {} } }
            listOf("Zerg Hasma", "Eek", "Korr", "Veyrath").forEach {
                compose.onNodeWithText(it).performScrollTo().assertExists()
            }
            compose.onNodeWithText("Korr").performClick()
            compose.onNodeWithTag("ability-STR").performScrollTo()
                .assert(hasContentDescription("Strength score unknown", substring = true))
            compose.onNodeWithText("Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Edit Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Save sheet").assertIsNotEnabled()
            compose.onNodeWithText("Close").performClick()
            runBlocking {
                assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
                assertTrue(db.recordsDao().allCharacterAbilities().isEmpty())
                assertTrue(db.partyDao().allSheets().isEmpty())
                assertTrue(db.recordsDao().allAudit().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun sourcedFeatureEditRetainsPriorSheetAndIndependentAbilityValues() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        runBlocking {
            repo.records.saveProfile(CharacterProfile(id = "korr", name = "Fictional test companion"), source = "Synthetic fixture")
            repo.records.saveAbility(CharacterAbility(profileId = "korr", key = "STR", score = 12, magicModifier = 1), source = "Synthetic fixture")
            repo.party.saveSheet("korr", PartySheetData(capabilities = listOf(SheetCapability(name = "Test trait", detail = "Original trait",
                source = "Synthetic approved ruling", evidenceStatus = "Confirmed"))), source = "Synthetic fixture")
        }
        val ability = runBlocking { db.recordsDao().characterAbility("korr", "STR") }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { PartyTypedSheetV2(repo, "korr", "Korr") } } }
            compose.onNodeWithText("Features, actions & capabilities").performScrollTo().performClick()
            compose.onNodeWithText("Edit Features, actions & capabilities").performScrollTo().performClick()
            compose.onNodeWithText("Feature details 1").performScrollTo().performTextReplacement("Confirmed fictional upgrade")
            compose.onNodeWithText("Change source / resolved event (required)").performScrollTo().performTextReplacement("Synthetic second session")
            compose.onNodeWithText("Save sheet").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().sheet("korr")?.revision == 2L } }
            runBlocking {
                val saved = db.partyDao().sheet("korr")!!
                assertEquals("Confirmed fictional upgrade", saved.sheet.capabilities.single().detail)
                assertEquals("Confirmed", saved.sheet.capabilities.single().evidenceStatus)
                assertEquals(ability, db.recordsDao().characterAbility("korr", "STR"))
                val history = db.recordsDao().allAudit().filter { it.recordType == "party-sheet" }
                assertEquals(2, history.size)
                assertTrue(history.any { it.beforeJson?.contains("Original trait") == true })
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun equipmentTransferUsesOneItemAndPreservesUnknownQuantity() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val item = runBlocking {
            repo.records.saveProfile(CharacterProfile(id = "local-player", name = "Fictional owner"), source = "Synthetic fixture")
            repo.records.saveProfile(CharacterProfile(id = "korr", name = "Korr"), source = "Synthetic fixture")
            val saved = repo.create(LedgerDraft(title = "Synthetic harness", quantity = null, source = "Synthetic fixture"))
            repo.party.moveItem(ItemMoveCommand(saved.id, saved.revision, holderProfileId = "local-player", source = "Synthetic starting possession"))
            db.ledgerDao().getItem(saved.id)!!
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { PartyEquipmentV2(repo, "korr") {} } } }
            compose.onNodeWithText("Give / take / move item").performScrollTo().performClick()
            compose.onNodeWithText("Synthetic harness").performScrollTo().performClick()
            compose.onNodeWithText("Zerg Hasma ▾").performScrollTo().performClick()
            compose.onNodeWithText("Korr", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Carried ▾").performScrollTo().performClick()
            compose.onNodeWithText("Equipped", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Confirmed transfer / equipment event source").performScrollTo().performTextInput("Synthetic resolved give action")
            compose.onNodeWithText("Commit equipment move").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().placement(item.id)?.holderProfileId == "korr" } }
            runBlocking {
                assertEquals(1, db.ledgerDao().allItems().size)
                val saved = db.ledgerDao().getItem(item.id)!!
                assertNull(saved.quantity)
                assertEquals(item.id, saved.id)
                val placement = db.partyDao().placement(item.id)!!
                assertEquals("Equipped", placement.state)
                assertEquals(2L, placement.revision)
                val move = db.recordsDao().allAudit().single { it.recordType == "item-placement" && it.resultingRevision == 2L }
                assertNotNull(db.recordsDao().receipt(move.commandId))
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
    @Test fun walletUiPreservesUnknownCoinsAndRecordsAnExpenseWithoutConversion() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        runBlocking { repo.records.saveProfile(CharacterProfile(id = "korr", name = "Fictional wallet owner"), source = "Synthetic fixture") }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { PartyWalletV2(repo, "korr") } } }
            compose.onNodeWithText("Add wallet").performScrollTo().performClick()
            compose.onNodeWithText("Wallet name").performScrollTo().performTextInput("Synthetic purse")
            compose.onNodeWithText("Gold · blank = unknown").performScrollTo().performTextInput("5")
            compose.onNodeWithText("Currency event source (required)").performScrollTo().performTextInput("Synthetic opening count")
            compose.onNodeWithText("Save currency record").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().allWallets().size == 1 } }
            compose.onNodeWithText("Record income / expense").performScrollTo().performClick()
            compose.onNodeWithText("Gold · + gained / − spent").performScrollTo().performTextInput("-2")
            compose.onNodeWithText("Currency event source (required)").performScrollTo().performTextInput("Synthetic purchase")
            compose.onNodeWithText("Save currency record").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().allWallets().single().revision == 2L } }
            runBlocking {
                val saved = db.partyDao().allWallets().single()
                assertEquals(3L, saved.gold)
                assertNull(saved.copper); assertNull(saved.silver)
                assertEquals(2, db.recordsDao().allAudit().count { it.recordType == "wallet" })
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun checkpointUiRetainsExactPendingChoiceAndUnknownGameTime() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CampaignCheckpointPanel(repo) } }
            compose.onNodeWithText("Add local checkpoint").performScrollTo().performClick()
            compose.onNodeWithText("Checkpoint title").performScrollTo().performTextInput("Fictional paused scene")
            compose.onNodeWithText("Current scene / location").performScrollTo().performTextInput("Synthetic workshop")
            compose.onNodeWithText("Exact last confirmed action").performScrollTo().performTextInput("Asked to inspect a fictional lock")
            compose.onNodeWithText("Unresolved choice or pending roll").performScrollTo().performTextInput("No roll has been made")
            compose.onNodeWithText("Checkpoint source (required)").performScrollTo().performTextInput("Synthetic pause")
            compose.onNodeWithText("Save local checkpoint").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().allCheckpoints().size == 1 } }
            runBlocking {
                val saved = db.partyDao().allCheckpoints().single()
                assertEquals("No roll has been made", saved.unresolvedChoice)
                assertEquals("", saved.gameTime)
                assertTrue(saved.recordReferences.isEmpty())
                assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test @Config(qualifiers = "w360dp-h760dp")
    fun largeFontSheetEditorKeepsSourceAndSaveReachableAndGuardsDiscard() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        runBlocking {
            repo.records.saveProfile(CharacterProfile(id = "veyrath", name = "Fictional large-font actor"), source = "Synthetic fixture")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AstravarTheme { Page { PartyTypedSheetV2(repo, "veyrath", "Veyrath") } }
                }
            }
            compose.onNodeWithText("Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Edit Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Class / role").performScrollTo().performTextInput("Fictional creature role")
            compose.onNodeWithText("Change source / resolved event (required)").performScrollTo().assertIsDisplayed()
                .performTextInput("Synthetic accessibility check")
            compose.onNodeWithText("Save sheet").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Class / role").performScrollTo().assertTextContains("Fictional creature role")
            runBlocking { assertTrue(db.partyDao().allSheets().isEmpty()) }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun purchasePreviewAndFailedDebitCreateNothingThenOneAtomicRetryCommits() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        runBlocking {
            repo.records.saveProfile(CharacterProfile(id = "korr", name = "Synthetic purchaser"), source = "Synthetic fixture")
            repo.party.saveWallet(PartyWallet(id = "synthetic-purse", holderProfileId = "korr", name = "Synthetic purse", gold = 1), source = "Synthetic opening count")
        }
        val originalReceipts = runBlocking { db.recordsDao().allReceipts() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { PartyEquipmentV2(repo, "korr") {} } } }
            compose.onNodeWithText("Item & currency exchange").performScrollTo().performClick()
            compose.onNodeWithText("Acquired item name").performScrollTo().performTextInput("Synthetic purchased tool")
            compose.onNodeWithText("Exchange gold change").performScrollTo().performTextInput("-2")
            compose.onNodeWithText("Exchange event source (required)").performScrollTo().performTextInput("Synthetic agreed exchange")
            compose.onNodeWithText("Review exchange").performClick()
            compose.onNodeWithText("No records have changed yet. This saves every listed change together, or none.").performScrollTo().assertIsDisplayed()
            runBlocking {
                assertTrue(db.ledgerDao().allItems().isEmpty())
                assertEquals(1L, db.partyDao().wallet("synthetic-purse")?.gold)
                assertEquals(originalReceipts, db.recordsDao().allReceipts())
            }
            compose.onNodeWithText("Commit confirmed exchange").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Not saved:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                assertTrue(db.ledgerDao().allItems().isEmpty())
                assertTrue(db.partyDao().allPlacements().isEmpty())
                assertEquals(1L, db.partyDao().wallet("synthetic-purse")?.gold)
                assertEquals(originalReceipts, db.recordsDao().allReceipts())
            }
            compose.onNodeWithText("Edit exchange").performScrollTo().performClick()
            compose.onNodeWithText("Exchange gold change").performScrollTo().performTextReplacement("-1")
            compose.onNodeWithText("Review exchange").performClick()
            compose.onNodeWithText("Commit confirmed exchange").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().wallet("synthetic-purse")?.gold == 0L } }
            compose.onNodeWithText("Exchange saved").performScrollTo().assertIsDisplayed()
            runBlocking {
                val item = db.ledgerDao().allItems().single()
                assertEquals("Synthetic purchased tool", item.title)
                assertNull(item.quantity)
                assertEquals("korr", db.partyDao().placement(item.id)?.holderProfileId)
                val wallet = db.partyDao().wallet("synthetic-purse")!!
                assertNull(wallet.copper); assertNull(wallet.silver)
                assertEquals(0L, wallet.gold)
                assertEquals(1, db.recordsDao().allAudit().count { it.kind == "ATOMIC_EXCHANGE" })
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

}
