package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CampaignPagesV2Test {
    @get:Rule val compose = createEmptyComposeRule()

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
    ).build()

    @Test fun openingEekNeverInitializesHistoricalHpOrRecovery() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { EekPageV2(CampaignLedgerRepository(db)) } }
            compose.onNodeWithText("Current condition unknown").assertExists()
            compose.onNodeWithText("Save Eek status").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("No current resource values have been entered.").performScrollTo().assertExists()
            runBlocking {
                assertTrue(db.recordsDao().allCompanionStates().isEmpty())
                assertTrue(db.recordsDao().allCompanionResources().isEmpty())
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun savingBlueprintDoesNotInventOwnedOutputOrSpendMaterials() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { InventionsPageV2(repo) } }
            compose.onNodeWithText("Invention name").performScrollTo().performTextInput("Fictional test blueprint")
            compose.onNodeWithText("Save invention").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Source / resolved scene / checkpoint").performScrollTo().performTextInput("Synthetic resolved design notes")
            compose.onNodeWithText("Save invention").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allProjects().size == 1 } }
            runBlocking {
                val project = db.recordsDao().allProjects().single()
                assertEquals("Fictional test blueprint", project.title)
                assertEquals("Proposed", project.status)
                assertTrue(db.ledgerDao().allItems().isEmpty())
                assertTrue(db.recordsDao().allCrafts().isEmpty())
                assertEquals(1, db.recordsDao().allAudit().size)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun cancellingCraftReviewDoesNotConsumeMaterialsOrCreateOutput() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val project = InventionProject(title = "Synthetic workshop", status = "Blueprint")
        runBlocking {
            repo.records.saveProject(project, source = "Synthetic fixture")
            val material = repo.create(LedgerDraft(title = "Test copper", quantity = 5, source = "Synthetic fixture"))
            repo.records.saveRequirement(MaterialRequirement(projectId = project.id, label = "Copper material", itemId = material.id, quantity = 2), source = "Synthetic fixture")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { InventionsPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic workshop").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic workshop").performScrollTo().performClick()
            compose.onNodeWithText("Confirm a resolved craft").performScrollTo().performClick()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Crafted item name").performScrollTo().performTextInput("Synthetic output")
            compose.onNodeWithText("Review material consumption").performScrollTo().performClick()
            compose.onNodeWithText("Commit confirmed craft?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            runBlocking {
                assertEquals(5, db.ledgerDao().allItems().single().quantity)
                assertEquals("Test copper", db.ledgerDao().allItems().single().title)
                assertTrue(db.recordsDao().allCrafts().isEmpty())
                assertTrue(db.recordsDao().allConsumptions().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun craftReviewRejectsMaterialsChangedWhileConfirmationIsOpen() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val project = InventionProject(title = "Synthetic stale workshop", status = "Blueprint")
        val material = runBlocking {
            repo.records.saveProject(project, source = "Synthetic fixture")
            val value = repo.create(LedgerDraft(title = "Stale test copper", quantity = 5, source = "Synthetic fixture"))
            repo.records.saveRequirement(MaterialRequirement(projectId = project.id, label = "Stale copper material", itemId = value.id, quantity = 2), source = "Synthetic fixture")
            value
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { InventionsPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic stale workshop").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic stale workshop").performScrollTo().performClick()
            compose.onNodeWithText("Confirm a resolved craft").performScrollTo().performClick()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Crafted item name").performScrollTo().performTextInput("Must not be created")
            compose.onNodeWithText("Review material consumption").performScrollTo().performClick()
            compose.onNodeWithText("Commit confirmed craft?").assertExists()
            runBlocking {
                repo.update(material.id, material.revision,
                    LedgerDraft(title = material.title, quantity = 3, source = "Concurrent synthetic correction"),
                    "Concurrent synthetic correction")
            }
            compose.onNodeWithText("Confirm resolved craft").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Material changed. Review quantities first.", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                assertEquals(3, db.ledgerDao().allItems().single().quantity)
                assertEquals(material.id, db.ledgerDao().allItems().single().id)
                assertTrue(db.recordsDao().allCrafts().isEmpty())
                assertTrue(db.recordsDao().allConsumptions().isEmpty())
                assertEquals("Blueprint", db.recordsDao().allProjects().single().status)
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun linkingEekEquipmentPreservesPhysicalItemIdentityAndUnknownResources() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val item = runBlocking {
            repo.records.saveCompanionState(CompanionState(), source = "Synthetic fixture")
            repo.create(LedgerDraft(title = "Test lens", quantity = 1, source = "Synthetic fixture"))
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { EekPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic fixture").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Choose…").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test lens").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Test lens").performClick()
            compose.onNodeWithText("Link existing item to Eek").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allLinks().isNotEmpty() } }
            runBlocking {
                val link = db.recordsDao().allLinks().single()
                assertEquals(item.id, link.fromId)
                assertEquals("item", link.fromType)
                assertEquals("eek", link.toId)
                assertEquals(item, db.ledgerDao().allItems().single())
                assertTrue(db.recordsDao().allCompanionResources().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
}
