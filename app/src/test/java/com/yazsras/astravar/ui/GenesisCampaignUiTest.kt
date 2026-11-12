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
class GenesisCampaignUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var captureActivity: ComponentActivity? = null
    private fun capture(name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, requireNotNull(captureActivity)) }
    }
    private fun fixture(block: (GenesisConnectedEnvironment, CampaignLedgerDb) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "fictional-genesis-ui-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, databaseName).allowMainThreadQueries().build()
        val preferenceFile = File(context.cacheDir, "genesis-ui-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { preferenceFile })
        try { block(GenesisConnectedEnvironment(GenesisAuthorityRepository(db), CampaignLedgerRepository(db), ReforgeRepository(db), preferences), db) }
        finally { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }; db.close(); context.deleteDatabase(databaseName); preferenceFile.delete() }
    }
    private fun show(content: @Composable () -> Unit, block: () -> Unit) {
        val previous = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            captureActivity = controller.get()
            controller.get().setContent { AstravarTheme { content() } }
            block()
        } finally {
            captureActivity = null
            controller.pause().stop().destroy()
            if (previous == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", previous)
        }
    }
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    // Sheet footer actions are fixed outside the scrolling content. Requiring a
    // scroll ancestor for those buttons fails before any tap is attempted.
    private fun click(text: String) = button(text).assertIsDisplayed().performClick()
    private fun contentClick(text: String) = button(text).performScrollTo().assertIsDisplayed().performClick()
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun createCampaignThroughGuidedEditorRetainsOneIdentityAndNotes() = fixture { env, db ->
        var result: GenesisCampaignRecord? = null
        show({ GenesisCampaignEditor(env.authority, {}, { result = it }) }) {
            compose.onNodeWithText("Campaign name").performTextInput("Fictional Amber Shore")
            compose.onNodeWithText("Campaign notes").performTextInput("Fictional campaign fixture")
            capture("genesis-connected-campaign-editor")
            click("Review & save")
            capture("genesis-connected-campaign-review")
            click("Save campaign")
            compose.waitUntil(15_000) { result != null }
            val saved = requireNotNull(result)
            assertEquals("Fictional Amber Shore", saved.name)
            runBlocking {
                assertEquals(listOf(saved), db.genesisAuthorityDao().allCampaigns())
                assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
            }
        }
    }

    @Test fun sourceNpcCreatesSameIdentityInCampaignAndConnectionsWithoutInventedStats() = fixture { env, db ->
        val world = runBlocking { env.authority.createCampaign("Fictional NPC Coast") }
        var result: GenesisActorCreationOutcome? = null
        val editorOpen = mutableStateOf(true)
        show({
            if (editorOpen.value) GenesisSourceActorEditor(env.copy(selectedCampaignId = world.id), GenesisActorRole.NPC, {}, { result = it; editorOpen.value = false })
            else GenesisCampaignHub(env.copy(selectedCampaignId = world.id), open = {}, openActor = { _, _ -> })
        }) {
            compose.onNodeWithText("NPC name").performTextInput("Fictional Harbor Keeper")
            click("Review & save")
            compose.onNodeWithText(world.name).assertExists()
            capture("genesis-connected-npc-review")
            click("Create NPC")
            compose.waitUntil(15_000) { result != null }
            val saved = requireNotNull(result)
            awaitText(saved.profile.name)
            compose.onNodeWithText(saved.profile.name).assertExists()
            capture("genesis-connected-campaign-people")
            assertEquals(world.id, saved.actor.campaignId)
            assertEquals(GenesisActorRole.NPC, saved.actor.role)
            assertFalse(saved.actor.playable)
            assertNull(saved.profile.currentHp)
            assertNull(saved.profile.maximumHp)
            assertNull(saved.profile.level)
            runBlocking {
                assertEquals(saved.profile.name, db.recordsDao().entity(saved.actor.actorId)?.name)
                assertNull(env.local.get(saved.actor.actorId))
            }
        }
    }

    @Test fun sourceCompanionCreationKeepsExplicitOwnerAndUnknownMechanics() = fixture { env, db ->
        val world = runBlocking { env.authority.createCampaign("Fictional Companion Coast") }
        val owner = runBlocking { env.authority.createActor("Fictional Companion Keeper", GenesisActorRole.PLAYER, world.id) }
        var created: GenesisActorCreationOutcome? = null
        show({ GenesisSourceActorEditor(env.copy(selectedCampaignId = world.id), GenesisActorRole.COMPANION, {}, { created = it }, owner.actor.actorId) }) {
            compose.onNodeWithText("Companion name").performTextInput("Fictional Brass Finch")
            click("Review & save")
            compose.onNodeWithText(owner.profile.name).assertExists()
            capture("genesis-connected-companion-review")
            click("Create companion")
            compose.waitUntil(15_000) { created != null }
            val result = requireNotNull(created)
            assertEquals(world.id, result.actor.campaignId)
            assertEquals(owner.actor.actorId, result.actor.ownerActorId)
            assertFalse(result.actor.playable)
            assertNull(result.profile.currentHp)
            runBlocking {
                assertEquals("Fictional Brass Finch", db.recordsDao().companion(result.actor.actorId)?.name)
                assertTrue(db.recordsDao().activeLinks().any { it.fromId == result.actor.actorId && it.toId == owner.actor.actorId && it.label == "Companion of" })
                assertTrue(db.recordsDao().allCompanionResources().none { it.companionId == result.actor.actorId })
                assertTrue(env.authority.backup.previewRestore(env.authority.backup.exportWithProof().text).valid)
            }
        }
    }

    @Test fun connectedCharacterAndItemEditorsWriteOneSharedPhysicalIdentity() = fixture { env, db ->
        val world = runBlocking { env.authority.createCampaign("Fictional Creation Coast") }
        var saved: ReforgeSavedCharacter? = null
        val addingItem = mutableStateOf(false)
        var itemSaved = false
        var expected: ReforgeSavedCharacter? = null
        show({
            if (!addingItem.value) GenesisConnectedCharacterEditor(env.copy(selectedCampaignId = world.id), {}, { saved = it })
            else GenesisConnectedItemEditor(env, requireNotNull(saved), {}, { itemSaved = true })
        }) {
            compose.onNodeWithText("Name").performTextInput("Fictional Copper Ranger")
            capture("genesis-connected-character-editor")
            click("Review & save")
            capture("genesis-connected-character-review")
            click("Save character")
            compose.waitUntil(15_000) { saved != null }
            val actor = requireNotNull(saved)
            runBlocking {
                assertEquals(world.id, env.authority.actor(actor.character.id)?.campaignId)
                assertEquals(actor.character.name, db.recordsDao().characterProfile(actor.character.id)?.name)
            }
            compose.runOnIdle { addingItem.value = true }
            compose.onNodeWithText("Item name").performTextInput("Fictional Brass Compass")
            click("Review & save")
            capture("genesis-connected-equipment-review")
            click("Save item")
            compose.waitUntil(15_000) { itemSaved }
            runBlocking {
                val updated = requireNotNull(env.local.get(actor.character.id))
                val physical = updated.character.items.single()
                assertEquals("Fictional Brass Compass", db.ledgerDao().getItem(physical.id)?.title)
                assertEquals(actor.character.id, db.partyDao().placement(physical.id)?.holderProfileId)
                assertEquals(actor.character.id, db.genesisAuthorityDao().item(physical.id)?.ownerActorId)
                assertEquals(1, db.ledgerDao().allItems().size)
                assertEquals(1, updated.character.itemDefinitions.size)
                val exported = env.authority.backup.exportWithProof()
                val portable = decodeGenesisAuthorityBackup(exported.text)
                assertEquals(physical.id, portable.authority.items.single().itemId)
                assertTrue(env.authority.backup.previewRestore(exported.text).valid)
                expected = updated
            }
        }
        val databaseName = requireNotNull(db.openHelper.databaseName)
        db.close()
        val reopened = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java, databaseName)
            .allowMainThreadQueries().build()
        try {
            runBlocking {
                val actual = ReforgeRepository(reopened).get(requireNotNull(expected).character.id)
                assertEquals(expected, actual)
                assertEquals(requireNotNull(expected).character.id, reopened.genesisAuthorityDao().allItems().single().ownerActorId)
                assertTrue(GenesisAuthorityRepository(reopened).backup.previewRestore(GenesisAuthorityRepository(reopened).backup.exportWithProof().text).valid)
            }
        } finally { reopened.close() }
    }

    @Test fun staleSharedReviewCannotOverwriteLaterGameplay() = fixture { env, db ->
        val original = runBlocking { env.local.create(ReforgeCharacter("fictional-stale-review", "Fictional Stale Review", baseMaxHp = 20, currentHp = 12)) }
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        show({
            if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                GenesisActorMembershipEditor(env, original.character.id, {})
            }
        }) {
            awaitText("Fictional Stale Review")
            contentClick("Character")
            compose.onNodeWithText("Companion").performClick()
            var savedDraft: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { savedDraft = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(savedDraft) { true }; visible.value = true }
            awaitText("Fictional Stale Review")
            compose.onNode(hasText("Companion") and hasClickAction()).assertExists()
            compose.onNode(hasText("Close") and hasClickAction()).performClick()
            awaitText("Discard changes?")
            compose.onNodeWithText("Keep editing").performClick()
            click("Review shared values")
            awaitText("I reviewed these values and membership")
            compose.onNode(hasText("Accept reviewed values") and hasClickAction()).assertIsNotEnabled()
            capture("genesis-shared-character-review")
            runBlocking { env.local.save(original.character.id, original.revision, original.character.copy(currentHp = 9), "Fictional later damage") }
            contentClick("I reviewed these values and membership")
            click("Accept reviewed values")
            awaitText("These records changed after review. Review the latest differences before accepting.")
            runBlocking {
                assertEquals(9, env.local.get(original.character.id)?.character?.currentHp)
                assertNull(env.authority.actor(original.character.id))
                assertNull(db.recordsDao().characterProfile(original.character.id))
            }
        }
    }

    @Test fun ownerChoicesUseRoleAndCampaignAndOnlyShowIdForTrueAmbiguity() {
        val a = CharacterProfile("Shared name", id = "first-fixture", level = 2)
        val b = CharacterProfile("Shared name", id = "second-fixture", level = 5)
        assertFalse(genesisOwnerLabel(a, listOf(a, b), emptyList(), emptyList()).contains(a.id))
        assertTrue(genesisOwnerLabel(a, listOf(a, b.copy(level = 2)), emptyList(), emptyList()).contains(a.id))
    }

    @Test fun explicitRoleOverridesRetainedCompanionHistory() {
        val profile = CharacterProfile("Fictional Former Companion", id = "fictional-role")
        val retained = CompanionState(name = profile.name, companionId = profile.id)
        val actor = GenesisActorAuthority(profile.id, GenesisActorRole.NPC, activationCommandId = "fictional-create", lastCommandId = "fictional-change", createdAt = 1, updatedAt = 2)
        assertTrue(partyRoster(listOf(profile), listOf(retained), listOf(actor)).isEmpty())
        val source = reforgeSearchCatalog(profiles = listOf(profile), companions = listOf(retained), associations = listOf(actor))
        assertTrue(source.none { it.destination is ReforgeSearchDestination.Companion })
        assertEquals(profile.id, partyRoster(listOf(profile), listOf(retained), listOf(actor.copy(role = GenesisActorRole.COMPANION))).single().id)
    }
}
