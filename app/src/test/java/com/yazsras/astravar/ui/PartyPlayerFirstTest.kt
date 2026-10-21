package com.yazsras.astravar.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode


/** Reproduce the app Scaffold's ambient text color when testing a standalone native page. */
@Composable private fun PartyTestTheme(content: @Composable () -> Unit) {
    AstravarTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground, content = content)
    }
}

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
    private fun capture(name: String, activity: ComponentActivity, modal: Boolean = false) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, activity, modal) }
    }
    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForEnabledAction(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()

    @Test fun partyRosterOpensFourStableIdentitiesWithoutImportingPrivateStats() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { PartyTestTheme { PartyPageV2(repo, null) {} } }
            listOf("Zerg Hasma", "Eek", "Korr", "Veyrath").forEach {
                compose.onNodeWithText(it).performScrollTo().assertExists()
            }
            capture("party-roster-400", activity.get())
            compose.onNodeWithText("Korr").performClick()
            capture("party-unknown-sheet-400", activity.get())
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
            activity.get().setContent { PartyTestTheme { Page { PartyTypedSheetV2(repo, "korr", "Korr") } } }
            compose.onNodeWithText("Features, actions & capabilities").performScrollTo().performClick()
            waitForText("Test trait")
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
            activity.get().setContent { PartyTestTheme { Page { PartyEquipmentV2(repo, "korr") {} } } }
            waitForEnabledAction("Give / take / move item")
            compose.onNodeWithText("Give / take / move item").performScrollTo().performClick()
            waitForText("Synthetic harness")
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
            activity.get().setContent { PartyTestTheme { Page { PartyWalletV2(repo, "korr") } } }
            waitForEnabledAction("Add wallet")
            compose.onNodeWithText("Add wallet").performScrollTo().performClick()
            compose.onNodeWithText("Wallet name").performScrollTo().performTextInput("Synthetic purse")
            compose.onNodeWithText("Gold · blank = unknown").performScrollTo().performTextInput("5")
            compose.onNodeWithText("Currency event source (required)").performScrollTo().performTextInput("Synthetic opening count")
            compose.onNodeWithText("Save currency record").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().allWallets().size == 1 } }
            waitForText("Record income / expense")
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
            activity.get().setContent { PartyTestTheme { CampaignCheckpointPanel(repo) } }
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
                    PartyTestTheme { Page { PartyTypedSheetV2(repo, "veyrath", "Veyrath") } }
                }
            }
            compose.onNodeWithText("Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Edit Identity & progression").performScrollTo().performClick()
            compose.onNode(hasText("Class / role") and hasSetTextAction()).performScrollTo().performTextInput("Fictional creature role")
            compose.onNodeWithText("Change source / resolved event (required)").performScrollTo().assertIsDisplayed()
                .performTextInput("Synthetic accessibility check")
            capture("party-sheet-editor-360-font200", activity.get(), modal = true)
            compose.onNodeWithText("Save sheet").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(hasText("Class / role") and hasSetTextAction()).performScrollTo().assertTextContains("Fictional creature role")
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
            activity.get().setContent { PartyTestTheme { Page { PartyEquipmentV2(repo, "korr") {} } } }
            waitForEnabledAction("Item & currency exchange")
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
            capture("party-exchange-receipt-400", activity.get(), modal = true)
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

    @Test @Config(qualifiers = "w360dp-h760dp")
    fun eekToolsFromPartyHaveBoundedScrollableViewportAtLargeFontWithoutWrites() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                val density = LocalDensity.current
                val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 2f }
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalConfiguration provides configuration) {
                    PartyTestTheme { PartyPageV2(repo, null) {} }
                }
            }
            compose.onNodeWithText("Eek").performScrollTo().performClick()
            compose.onNodeWithText("Eek's specialized companion tools").performScrollTo().performClick()
            compose.onNodeWithText("Condition unknown").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Resources").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Equipment & upgrades").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Capabilities").performScrollTo().assertIsDisplayed()
            capture("party-eek-tools-360-font200", activity.get(), modal = true)
            compose.onNodeWithText("Close").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("Eek's specialized companion tools").assertExists()
            runBlocking {
                assertTrue(db.recordsDao().allCompanionStates().isEmpty())
                assertTrue(db.recordsDao().allCompanionResources().isEmpty())
                assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
                assertTrue(db.partyDao().allSheets().isEmpty())
                assertTrue(db.recordsDao().allAudit().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun unchangedCompanionResourceClosesDirectlyAndInvalidSaveShowsVisibleRetainedDraft() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        runBlocking {
            repo.records.saveCompanionState(CompanionState(companionId = "korr", name = "Synthetic companion", condition = "Active"), source = "Synthetic fixture")
            repo.records.saveCompanionResource(CompanionResource(id = "synthetic-counter", companionId = "korr", name = "Synthetic counter", current = 2, maximum = 3), source = "Synthetic fixture")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { PartyTestTheme { Page { PartyCompanionEditorV2(repo, "korr", "Korr") } } }
            // Room emits state/resources on independent IO queries after the initial empty UI.
            waitForText("Synthetic counter")
            waitForText("Active")
            compose.onNodeWithText("Synthetic counter").performScrollTo().performClick()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertDoesNotExist()
            compose.onNodeWithText("Synthetic counter").performScrollTo().performClick()
            compose.onNodeWithText("Current amount · blank = unknown").performScrollTo().performTextReplacement("4")
            compose.onNodeWithText("Save resource").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Not saved: Current amount cannot exceed maximum.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Not saved: Current amount cannot exceed maximum.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Current amount · blank = unknown").performScrollTo().assertTextContains("4")
            runBlocking { assertEquals(2, db.recordsDao().resource("synthetic-counter")?.current) }
            compose.onNodeWithText("Current amount · blank = unknown").performTextClearance()
            compose.onNodeWithText("Save resource").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().resource("synthetic-counter")?.revision == 2L } }
            runBlocking {
                val saved = db.recordsDao().resource("synthetic-counter")!!
                assertNull(saved.current)
                assertEquals(3, saved.maximum)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun portraitPickerCompressesLocalImageAndSourcedRemovalRetainsEarlierRevision() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.filesDir, "synthetic-party-portrait.png")
        val fixture = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            fixture.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { fixture.recycle() }
        runBlocking {
            repo.records.saveProfile(CharacterProfile(id = "korr", name = "Synthetic portrait owner"), source = "Synthetic fixture")
            repo.party.saveSheet("korr", PartySheetData(role = "Synthetic portrait role"), source = "Synthetic fixture")
        }
        try {
            activity.get().setContent { PartyTestTheme { Page { PartyTypedSheetV2(repo, "korr", "Korr") } } }
            compose.onNodeWithText("Identity & progression").performScrollTo().performClick()
            waitForText("Synthetic portrait role")
            compose.onNodeWithText("Edit Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Choose portrait").performScrollTo().performClick()
            val launched = requireNotNull(shadowOf(activity.get()).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_GET_CONTENT, launched.intent.action)
            assertEquals("image/*", launched.intent.type)
            compose.runOnIdle {
                assertTrue(activity.get().activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(file))))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Draft portrait for Korr").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Change source / resolved event (required)").performScrollTo().performTextReplacement("Synthetic local portrait")
            compose.onNodeWithText("Save sheet").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().sheet("korr")?.revision == 2L } }
            val portrait = runBlocking { db.partyDao().sheet("korr")!!.sheet.portraitBase64!! }
            val bytes = Base64.decode(portrait, Base64.DEFAULT)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertTrue(decoded.width <= 448 && decoded.height <= 448)
            decoded.recycle()
            compose.onNodeWithContentDescription("Portrait for Korr").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Edit Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Remove custom portrait").performScrollTo().performClick()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Discard").performClick()
            runBlocking { assertEquals(portrait, db.partyDao().sheet("korr")!!.sheet.portraitBase64) }
            compose.onNodeWithText("Edit Identity & progression").performScrollTo().performClick()
            compose.onNodeWithText("Remove custom portrait").performScrollTo().performClick()
            compose.onNodeWithText("Save sheet").performClick()
            compose.waitUntil(10_000) { runBlocking { db.partyDao().sheet("korr")?.revision == 3L } }
            runBlocking {
                assertNull(db.partyDao().sheet("korr")!!.sheet.portraitBase64)
                assertTrue(db.recordsDao().allAudit().any { it.recordType == "party-sheet" && it.beforeJson?.contains(portrait) == true })
            }
        } finally { activity.pause().stop().destroy(); file.delete(); db.close() }
    }

}
