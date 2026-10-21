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
import org.junit.Before
import org.junit.After
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
class CampaignPagesV2Test {
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

    private fun snapshot(repository: CampaignLedgerRepository) = runBlocking {
        campaignJson.decodeFromString<LedgerExport>(repository.export()).copy(exportedAt = 0)
    }

    @Test fun openingEekNeverInitializesHistoricalHpOrRecovery() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { EekPageV2(CampaignLedgerRepository(db)) } }
            compose.onNodeWithText("Condition unknown").assertExists()
            compose.onNodeWithText("No confirmed resource values").assertExists()
            compose.onNodeWithText("Save Eek status").assertDoesNotExist()
            compose.onNodeWithText("Edit status").performClick()
            compose.onNodeWithText("Save Eek status").assertIsNotEnabled()
            compose.onNodeWithText("Close").performClick()
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
            compose.onNodeWithText("Invention name").assertDoesNotExist()
            compose.onNodeWithText("Add Invention").performClick()
            compose.onNodeWithText("Invention name").performScrollTo().performTextInput("Fictional test blueprint")
            compose.onNodeWithText("Save invention").assertIsNotEnabled()
            compose.onNodeWithText("Source / resolved scene / checkpoint").performScrollTo().performTextInput("Synthetic resolved design notes")
            compose.onNodeWithText("Save invention").performClick()
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

    @Test fun researchAndMaterialLinksRemainReferencesRegardlessOfProjectStatus() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val projects = listOf("Proposed", "Completed").map { status ->
            InventionProject(title = "Synthetic $status design", status = status)
        }
        runBlocking {
            val clue = repo.create(LedgerDraft(title = "Synthetic research sample", quantity = null, source = "Synthetic research"))
            val material = repo.create(LedgerDraft(title = "Synthetic stored plates", quantity = 4, source = "Synthetic material purchase"))
            projects.forEach { project ->
                repo.records.saveProject(project, source = "Synthetic recorded design status")
                repo.records.saveRequirement(MaterialRequirement(projectId = project.id, label = "Plates needed", itemId = material.id, quantity = null), source = "Synthetic unconfirmed requirement")
                repo.records.saveLink(CampaignLink("item", clue.id, "project", project.id, "Fictional research clue"), source = "Synthetic research association")
                repo.records.saveLink(CampaignLink("project", project.id, "item", material.id, "Purchased materials reference"), source = "Synthetic material association")
            }
        }
        val before = snapshot(repo)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { InventionsPageV2(repo) } }
            projects.forEach { project ->
                compose.waitUntil(10_000) { compose.onAllNodesWithText(project.title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(project.title).performScrollTo().performClick()
                compose.onNodeWithText("Physical output").performScrollTo().performClick()
                compose.onNodeWithText("No linked physical output or equipment").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Synthetic research sample").assertDoesNotExist()
                compose.onNodeWithText("Synthetic stored plates").assertDoesNotExist()
                compose.onNodeWithText("References & associations").performScrollTo().performClick()
                compose.onNodeWithText("Fictional research clue").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Synthetic research sample").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Purchased materials reference").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Synthetic stored plates").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Close").performClick()
            }
            assertEquals(before, snapshot(repo))
            assertTrue(before.records.crafts.isEmpty())
            assertTrue(before.records.consumptions.isEmpty())
            assertNull(before.items.single { it.title == "Synthetic research sample" }.quantity)
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun confirmedCraftOutputIsSeparateFromResearchAndViewingDoesNotReplayCraft() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        val project = InventionProject(title = "Synthetic confirmed workshop", status = "Blueprint")
        runBlocking {
            repo.records.saveProject(project, source = "Synthetic design")
            val material = repo.create(LedgerDraft(title = "Synthetic craft copper", quantity = 4, source = "Synthetic materials"))
            val clue = repo.create(LedgerDraft(title = "Synthetic craft research sample", quantity = null, source = "Synthetic research"))
            repo.records.saveRequirement(MaterialRequirement(projectId = project.id, label = "Copper needed", itemId = material.id, quantity = 2), source = "Synthetic design")
            repo.records.saveLink(CampaignLink("item", clue.id, "project", project.id, "Fictional research clue"), source = "Synthetic research association")
            repo.records.saveLink(CampaignLink("project", project.id, "item", material.id, "Material source reference"), source = "Synthetic material association")
            repo.records.confirmCraft(CraftCommand(project.id, project.revision,
                listOf(MaterialConsumption(material.id, material.revision, 2)),
                LedgerDraft(title = "Synthetic confirmed instrument", quantity = 1, source = "Synthetic confirmed construction"),
                "Synthetic confirmed construction", "synthetic-ui-confirmed-craft"))
        }
        val before = snapshot(repo)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { InventionsPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(project.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(project.title).performScrollTo().performClick()
            compose.onNodeWithText("Physical output").performScrollTo().performClick()
            compose.onNodeWithText("Confirmed craft output").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Synthetic confirmed instrument").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No linked physical output or equipment").assertDoesNotExist()
            compose.onNodeWithText("Synthetic craft research sample").assertDoesNotExist()
            compose.onNodeWithText("Synthetic craft copper").assertDoesNotExist()
            compose.onNodeWithText("Physical output").performScrollTo().performClick()
            compose.onNodeWithText("References & associations").performScrollTo().performClick()
            compose.onNodeWithText("Fictional research clue").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Synthetic craft research sample").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Material source reference").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Synthetic craft copper").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Synthetic confirmed instrument").assertDoesNotExist()
            compose.onNodeWithText("Confirmed craft output").assertDoesNotExist()
            assertEquals(before, snapshot(repo))
            assertEquals("Completed", before.records.projects.single().status)
            assertEquals(2, before.items.single { it.title == "Synthetic craft copper" }.quantity)
            assertEquals(1, before.records.crafts.size)
            assertEquals(1, before.records.consumptions.size)
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
            compose.onNodeWithText("Physical output").performScrollTo().performClick()
            compose.onNodeWithText("Confirm a resolved craft").performScrollTo().performClick()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Crafted item name").performScrollTo().performTextInput("Synthetic output")
            compose.onNodeWithText("Review material consumption").performClick()
            compose.onNodeWithText("Commit confirmed craft?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Crafted item name").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString("Synthetic output"))
            )
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
            compose.onNodeWithText("Physical output").performScrollTo().performClick()
            compose.onNodeWithText("Confirm a resolved craft").performScrollTo().performClick()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Crafted item name").performScrollTo().performTextInput("Must not be created")
            compose.onNodeWithText("Review material consumption").performClick()
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
            compose.onNodeWithText("Equipment & upgrades").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Link equipment") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Link equipment").performClick()
            compose.onNodeWithText("Choose an existing record").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test lens").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Test lens").performClick()
            compose.onNodeWithText("Link existing item to Eek").assertIsDisplayed().assertIsEnabled().performClick()
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

    @Test fun eekResourceEditorKeepsUnknownCurrentValueAndDoesNotRecoverCondition() {
        val db = database()
        val repo = CampaignLedgerRepository(db)
        runBlocking { repo.records.saveCompanionState(CompanionState(condition = "Disabled"), source = "Synthetic confirmed status") }
        val stateBefore = runBlocking { db.recordsDao().allCompanionStates().single() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { EekPageV2(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Add resource") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add resource").performClick()
            compose.onNodeWithText("Resource name").performScrollTo().performTextInput("HP")
            compose.onNodeWithText("Confirmed maximum (blank = unknown)").performScrollTo().performTextInput("30")
            compose.onNodeWithText("Save known resource").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().allCompanionResources().size == 1 } }
            runBlocking {
                val resource = db.recordsDao().allCompanionResources().single()
                assertNull(resource.current)
                assertEquals(30, resource.maximum)
                assertEquals("Synthetic confirmed status", resource.source)
                assertEquals(stateBefore, db.recordsDao().allCompanionStates().single())
                assertTrue(db.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }
}
