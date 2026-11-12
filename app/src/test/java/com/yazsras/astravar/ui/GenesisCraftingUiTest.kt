package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisCraftingUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var captureActivity: ComponentActivity? = null
    private val actorId = "fictional-crafting-keeper"
    private val materialId = "fictional-crafting-brass"
    private val containerId = "fictional-crafting-satchel"
    private data class Fixture(val env: GenesisConnectedEnvironment, val db: CampaignLedgerDb, val project: InventionProject, val databaseName: String)
    private fun fixture(block: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fictional-crafting-ui-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
        val prefFile = File(context.cacheDir, "fictional-crafting-ui-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { prefFile })
        val authority = GenesisAuthorityRepository(db)
        val campaign = CampaignLedgerRepository(db)
        val local = ReforgeRepository(db)
        val project = InventionProject("Fictional Compass Design", source = "Confirmed fictional crafting scene", id = "fictional-crafting-project")
        try {
            runBlocking {
                val actor = authority.createActor("Fictional Craft Keeper", GenesisActorRole.PLAYER,
                    playable = ReforgeCharacter(actorId, "Fictional Craft Keeper", baseMaxHp = 10, currentHp = 4, baseAc = 12))
                var saved = requireNotNull(actor.playable)
                saved = authority.createItem(actorId, saved.revision, ItemDefinition("fictional-brass-definition", "Fictional brass"), ItemInstance(materialId, "fictional-brass-definition", quantity = 5))
                authority.createItem(actorId, saved.revision, ItemDefinition("fictional-satchel-definition", "Fictional Satchel"), ItemInstance(containerId, "fictional-satchel-definition"))
                campaign.records.saveProject(project, source = project.source)
                campaign.records.saveRequirement(MaterialRequirement(project.id, "Brass pieces", materialId, 2), source = project.source)
            }
            block(Fixture(GenesisConnectedEnvironment(authority, campaign, local, prefs), db, project, name))
        } finally { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; db.close(); context.deleteDatabase(name); prefFile.delete() }
    }
    private fun show(content: @Composable () -> Unit, block: () -> Unit) {
        val previous = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try { captureActivity = controller.get(); controller.get().setContent { AstravarTheme { content() } }; block() }
        finally {
            captureActivity = null; controller.pause().stop().destroy()
            if (previous == null) System.clearProperty("robolectric.windowManager.useRequestedSize") else System.setProperty("robolectric.windowManager.useRequestedSize", previous)
        }
    }
    private fun capture(name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, requireNotNull(captureActivity)) }
    }
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun action(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        button(text).assertIsEnabled().assertIsDisplayed().performClick()
    }
    private fun contentAction(text: String) = button(text).performScrollTo().assertIsDisplayed().performClick()
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun chooseMaterialsAndOutput() {
        awaitText("Consume Brass pieces")
        contentAction("Consume Brass pieces")
        action("Choose output")
        compose.onNodeWithText("Crafted item name").performTextInput("Fictional Brass Compass")
        button("Review connected craft").assertIsNotEnabled()
        awaitText("Fictional Craft Keeper")
        contentAction("Fictional Craft Keeper")
    }
    private fun confirm() {
        compose.onNodeWithTag("genesis-confirm-craft").assertIsNotEnabled()
        contentAction("I confirm this craft is resolved and these quantities are correct")
        compose.onNodeWithTag("genesis-confirm-craft").assertIsEnabled().performClick()
    }

    @Test fun projectWorkflowConsumesOnceCreatesOneConnectedOutputAndSurvivesBackupAndReopen() = fixture { f ->
        var outputId: String? = null
        show({ CompositionLocalProvider(LocalGenesisConnectedEnvironment provides f.env) { InventionsPageV2(f.env.campaign, f.project.id) } }) {
            awaitText("Physical output")
            contentAction("Physical output")
            contentAction("Confirm a resolved craft")
            chooseMaterialsAndOutput()
            contentAction("No container")
            compose.onNodeWithText("Fictional Satchel").performClick()
            capture("genesis-connected-craft-output")
            action("Review connected craft")
            awaitText("I confirm this craft is resolved and these quantities are correct")
            runBlocking {
                assertEquals(5, f.db.ledgerDao().getItem(materialId)?.quantity)
                assertEquals(5, f.env.local.get(actorId)?.character?.items?.single { it.id == materialId }?.quantity)
                assertTrue(f.db.recordsDao().allCrafts().isEmpty())
            }
            capture("genesis-connected-craft-review")
            confirm()
            awaitText("Craft saved")
            action("Return to invention")
            awaitText("Craft saved · Fictional Brass Compass")
            runBlocking {
                val saved = requireNotNull(f.env.local.get(actorId))
                val output = f.db.ledgerDao().allItems().single { it.title == "Fictional Brass Compass" }
                outputId = output.id
                val physical = saved.character.items.single { it.id == output.id }
                assertEquals(3, saved.character.items.single { it.id == materialId }.quantity)
                assertEquals(3, f.db.ledgerDao().getItem(materialId)?.quantity)
                assertEquals(containerId, physical.containerId)
                assertEquals(ItemState.STORED, physical.state)
                assertEquals(actorId, f.db.partyDao().placement(output.id)?.holderProfileId)
                assertEquals(containerId, f.db.partyDao().placement(output.id)?.containerItemId)
                assertEquals(actorId, f.db.genesisAuthorityDao().item(output.id)?.ownerActorId)
                assertEquals(1, f.db.recordsDao().allCrafts().size)
                assertEquals(2, f.db.recordsDao().allConsumptions().single().quantity)
                assertEquals(output.id, f.db.recordsDao().allCrafts().single().outputItemId)
                assertTrue(saved.character.itemDefinitions.single { it.id == physical.definitionId }.effects.isEmpty())
                assertEquals(4, saved.character.currentHp)
                val backup = f.env.authority.backup.exportWithProof()
                assertTrue(f.env.authority.backup.previewRestore(backup.text).valid)
                assertEquals(output.id, decodeGenesisAuthorityBackup(backup.text).authority.items.single { it.itemId == output.id }.itemId)
            }
        }
        f.db.close()
        val reopened = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java, f.databaseName).allowMainThreadQueries().build()
        try { runBlocking {
            val id = requireNotNull(outputId)
            assertEquals(3, ReforgeRepository(reopened).get(actorId)?.character?.items?.single { it.id == materialId }?.quantity)
            assertEquals(containerId, ReforgeRepository(reopened).get(actorId)?.character?.items?.single { it.id == id }?.containerId)
            assertEquals(1, reopened.recordsDao().allCrafts().size)
            assertTrue(GenesisAuthorityRepository(reopened).backup.previewRestore(GenesisAuthorityRepository(reopened).backup.exportWithProof().text).valid)
        } } finally { reopened.close() }
    }

    @Test fun commitBeforeDismissRecoversWithoutBundleAndStaleBundleNeverConsumesAgain() = fixture { f ->
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        var callbacks = 0
        var firstOutputId: String? = null
        show({ if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            GenesisCraftingEditor(f.env, f.project, { visible.value = false }, {
                callbacks++; firstOutputId?.let { original -> assertEquals(original, it.outputId) }
                firstOutputId = it.outputId; visible.value = false
            })
        } }) {
            chooseMaterialsAndOutput()
            action("Review connected craft")
            awaitText("I confirm this craft is resolved and these quantities are correct")
            confirm()
            awaitText("Craft saved")
            assertEquals(0, callbacks)
            val pending = runBlocking { requireNotNull(f.env.preferences.pendingCraft(f.project.id)) }
            val outputId = runBlocking { f.db.recordsDao().allCrafts().single().outputItemId }
            firstOutputId = outputId
            capture("genesis-connected-craft-saved")

            // Dispose every in-memory editor state, including its bundle. Only the durable intent remains.
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(null) { true }; visible.value = true }
            awaitText("Craft saved")
            compose.onNodeWithTag("genesis-confirm-craft").assertDoesNotExist()
            assertEquals(0, callbacks)
            var staleBundle: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { staleBundle = registry.performSave() }
            action("Return to invention")
            compose.waitForIdle()
            assertEquals(1, callbacks)
            runBlocking { assertNull(f.env.preferences.pendingCraft(f.project.id)) }

            // An older Android saved-state snapshot must still show the same immutable outcome.
            compose.runOnIdle { registry = SaveableStateRegistry(staleBundle) { true }; visible.value = true }
            awaitText("Craft saved")
            compose.onNodeWithTag("genesis-confirm-craft").assertDoesNotExist()
            runBlocking {
                assertEquals(3, f.db.ledgerDao().getItem(materialId)?.quantity)
                assertEquals(3, f.env.local.get(actorId)?.character?.items?.single { it.id == materialId }?.quantity)
                assertEquals(outputId, f.db.recordsDao().allCrafts().single().outputItemId)
                assertEquals(2, f.db.recordsDao().allConsumptions().single().quantity)
                assertEquals(outputId, f.env.authority.crafting.completed(pending.projectId, pending.reviewFingerprint, pending.commandId)?.outputId)
                assertTrue(f.env.authority.backup.previewRestore(f.env.authority.backup.exportWithProof().text).valid)
            }
            action("Return to invention")
            compose.waitForIdle()
            assertEquals(2, callbacks)
        }
    }

    @Test fun pendingUncommittedAttemptRequiresFreshReviewAndFreshApproval() = fixture { f ->
        val unused = GenesisPendingCraft(f.project.id, "e".repeat(64), "fictional-uncommitted-craft")
        runBlocking { f.env.preferences.rememberPendingCraft(unused) }
        show({ GenesisCraftingEditor(f.env, f.project, {}, {}) }) {
            awaitText("No completed craft was found for this attempt.")
            compose.onNodeWithTag("genesis-confirm-craft").assertDoesNotExist()
            runBlocking {
                assertEquals(unused, f.env.preferences.pendingCraft(f.project.id))
                assertEquals(5, f.db.ledgerDao().getItem(materialId)?.quantity)
                assertTrue(f.db.recordsDao().allCrafts().isEmpty())
            }
            action("Review draft again")
            awaitText("Crafted item name")
            runBlocking { assertNull(f.env.preferences.pendingCraft(f.project.id)) }
            action("Back")
            chooseMaterialsAndOutput()
            action("Review connected craft")
            awaitText("I confirm this craft is resolved and these quantities are correct")
            compose.onNodeWithTag("genesis-confirm-craft").assertIsNotEnabled()
            runBlocking { assertEquals(5, f.db.ledgerDao().getItem(materialId)?.quantity); assertTrue(f.db.recordsDao().allCrafts().isEmpty()) }
        }
    }

    @Test fun sourceOnlyWorkflowRemainsReachableAndDoesNotEnrollItsOutput() = fixture { f ->
        val canvas = runBlocking {
            val item = f.env.campaign.create(LedgerDraft("Fictional canvas", quantity = 3, itemState = "Carried", source = "Fictional source-only fixture"))
            f.env.campaign.records.saveRequirement(MaterialRequirement(f.project.id, "Canvas strips", item.id, 1), source = f.project.source)
            item
        }
        show({ CompositionLocalProvider(LocalGenesisConnectedEnvironment provides f.env) { InventionsPageV2(f.env.campaign, f.project.id) } }) {
            awaitText("Physical output")
            contentAction("Physical output")
            contentAction("Confirm a resolved craft")
            contentAction("Historical source-only workflow")
            contentAction("Record a source-only craft")
            awaitText("Actually consume Canvas strips")
            contentAction("Actually consume Canvas strips")
            compose.onNodeWithText("Crafted item name").performScrollTo().performTextInput("Fictional source banner")
            action("Review material consumption")
            action("Confirm resolved craft")
            awaitText("Craft recorded · materials and output saved together")
            runBlocking {
                val output = f.db.ledgerDao().allItems().single { it.title == "Fictional source banner" }
                assertEquals(2, f.db.ledgerDao().getItem(canvas.id)?.quantity)
                assertEquals(5, f.env.local.get(actorId)?.character?.items?.single { it.id == materialId }?.quantity)
                assertNull(f.db.genesisAuthorityDao().item(output.id))
                assertEquals("Unknown", output.owner)
                assertEquals(1, f.db.recordsDao().allCrafts().size)
                assertTrue(f.env.authority.backup.previewRestore(f.env.authority.backup.exportWithProof().text).valid)
            }
        }
    }

    @Test fun changedMaterialAfterFrozenReviewRejectsEverythingWithoutAdditionalConsumption() = fixture { f ->
        var saved: GenesisCraftingOutcome? = null
        show({ GenesisCraftingEditor(f.env, f.project, {}, { saved = it }) }) {
            chooseMaterialsAndOutput()
            action("Review connected craft")
            awaitText("I confirm this craft is resolved and these quantities are correct")
            runBlocking {
                val current = requireNotNull(f.env.local.get(actorId))
                f.env.local.save(actorId, current.revision, current.character.copy(items = current.character.items.map { if (it.id == materialId) it.copy(quantity = 4) else it }), "Fictional later material adjustment")
            }
            confirm()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("genesis-craft-error").fetchSemanticsNodes().isNotEmpty() }
            assertNull(saved)
            runBlocking {
                assertEquals(4, f.db.ledgerDao().getItem(materialId)?.quantity)
                assertEquals(4, f.env.local.get(actorId)?.character?.items?.single { it.id == materialId }?.quantity)
                assertTrue(f.db.recordsDao().allCrafts().isEmpty())
                assertEquals(2, f.db.ledgerDao().allItems().size)
            }
        }
    }

    @Test fun recreationKeepsDraftButRequiresFreshReviewAndCancellationNeverConsumes() = fixture { f ->
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        var dismissed = false
        show({ if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            GenesisCraftingEditor(f.env, f.project, { dismissed = true }, {})
        } }) {
            chooseMaterialsAndOutput()
            action("Review connected craft")
            awaitText("I confirm this craft is resolved and these quantities are correct")
            contentAction("I confirm this craft is resolved and these quantities are correct")
            var state: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { state = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(state) { true }; visible.value = true }
            awaitText("Crafted item name")
            compose.onNodeWithText("Crafted item name").assertTextContains("Fictional Brass Compass")
            compose.onNodeWithTag("genesis-confirm-craft").assertDoesNotExist()
            action("Review connected craft")
            awaitText("I confirm this craft is resolved and these quantities are correct")
            compose.onNodeWithTag("genesis-confirm-craft").assertIsNotEnabled()
            button("Close").performClick()
            awaitText("Discard changes?")
            compose.onNodeWithText("Discard").performClick()
            assertTrue(dismissed)
            runBlocking { assertEquals(5, f.db.ledgerDao().getItem(materialId)?.quantity); assertTrue(f.db.recordsDao().allCrafts().isEmpty()) }
        }
    }

    @Test fun unconnectedOwnerNeedsExplicitReviewAndCannotReceiveOutputAutomatically() = fixture { f ->
        runBlocking { f.env.local.create(ReforgeCharacter("fictional-unconnected-crafter", "Fictional Unconnected Crafter")) }
        show({ GenesisCraftingEditor(f.env, f.project, {}, {}) }) {
            awaitText("Consume Brass pieces")
            contentAction("Consume Brass pieces")
            action("Choose output")
            compose.onNodeWithText("Crafted item name").performTextInput("Unassigned fictional craft")
            awaitText("Review connection for Fictional Unconnected Crafter")
            button("Review connected craft").assertIsNotEnabled()
            contentAction("Review connection for Fictional Unconnected Crafter")
            awaitText("Campaign membership")
            runBlocking {
                assertNull(f.env.authority.actor("fictional-unconnected-crafter"))
                assertTrue(f.db.recordsDao().allCrafts().isEmpty())
                assertEquals(5, f.db.ledgerDao().getItem(materialId)?.quantity)
            }
        }
    }
}
