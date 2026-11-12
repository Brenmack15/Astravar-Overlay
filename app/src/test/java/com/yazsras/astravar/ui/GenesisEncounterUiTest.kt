package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
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

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisEncounterUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val hero = "fictional-encounter-hero"
    private data class Fixture(val db: CampaignLedgerDb, val env: GenesisConnectedEnvironment, val campaign: GenesisCampaignRecord,
        val other: GenesisCampaignRecord, val npc: String, val databaseName: String)
    private fun fixture(block: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "fictional-encounter-ui-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, databaseName).allowMainThreadQueries().build()
        val prefFile = File(context.cacheDir, "fictional-encounter-ui-${UUID.randomUUID()}.preferences_pb")
        val prefScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = prefScope) { prefFile })
        val authority = GenesisAuthorityRepository(db)
        try {
            val (campaign, other, npc) = runBlocking {
                val campaign = authority.createCampaign("Fictional Vale")
                val other = authority.createCampaign("Fictional Coast")
                authority.createActor("Fictional Hero", GenesisActorRole.PLAYER, campaign.id,
                    playable = ReforgeCharacter(hero, "Fictional Hero", baseMaxHp = 10, currentHp = 4, baseAc = 12))
                val npc = authority.createActor("Fictional Scout", GenesisActorRole.NPC, campaign.id)
                authority.createActor("Fictional Outsider", GenesisActorRole.NPC, other.id)
                preferences.selectCampaign(campaign.id)
                Triple(campaign, other, npc.actor.actorId)
            }
            block(Fixture(db, GenesisConnectedEnvironment(authority, CampaignLedgerRepository(db), ReforgeRepository(db), preferences, campaign.id), campaign, other, npc, databaseName))
        } finally { runBlocking { prefScope.coroutineContext[Job]!!.cancelAndJoin() }; db.close(); context.deleteDatabase(databaseName); prefFile.delete() }
    }
    private fun show(content: @Composable () -> Unit, block: (ComponentActivity) -> Unit) {
        val previous = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
            block(activity.get())
        } finally {
            activity.pause().stop().destroy()
            if (previous == null) System.clearProperty("robolectric.windowManager.useRequestedSize") else System.setProperty("robolectric.windowManager.useRequestedSize", previous)
        }
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String, scroll: Boolean = false, last: Boolean = false) {
        val selector = hasText(text) and hasClickAction()
        compose.waitUntil(15_000) { compose.onAllNodes(selector and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val node = if (last) compose.onAllNodes(selector).onLast() else compose.onNode(selector)
        if (scroll) node.performScrollTo()
        node.assertIsEnabled().assertIsDisplayed().performClick()
    }
    private fun capture(name: String, activity: ComponentActivity) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, activity) }
    }
    private fun populate() {
        awaitText("Encounter name")
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Encounter name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Encounter name").performTextInput("Fictional Watchtower")
        click("Next")
        awaitText("Add Fictional Hero")
        compose.onNodeWithText("Add Fictional Outsider").assertDoesNotExist()
        click("Add Fictional Hero", scroll = true)
        click("Add Fictional Scout", scroll = true)
        compose.onNodeWithText("Initiative: Fictional Hero").performScrollTo().performTextInput("3")
        compose.onNodeWithText("Initiative: Fictional Scout").performScrollTo().performTextInput("20")
    }
    @Test fun completeManualEncounterJourneyPreservesMechanicsAndSurvivesBackupAndReopen() = fixture { f ->
        val before = runBlocking { requireNotNull(f.env.local.get(hero)) }
        var route: ReforgeSearchDestination.Character? = null
        var id: String? = null
        show({ GenesisEncountersPage(f.env) { route = it } }) { activity ->
            click("Create encounter")
            populate()
            // Initiative never silently sorts the chosen participants.
            compose.onNodeWithText("1. Fictional Hero").performScrollTo().assertExists()
            click("Move Fictional Scout up", scroll = true)
            click("Review & save")
            capture("genesis-encounter-setup-review", activity)
            click("Save encounter")
            awaitText("Review start")
            runBlocking {
                val saved = f.env.authority.encounters.observe(f.campaign.id).first().single()
                id = saved.record.id
                assertEquals(listOf(f.npc, hero), saved.participants.map { it.actorId })
                assertEquals(listOf(20, 3), saved.participants.map { it.initiative })
                assertEquals(GenesisEncounterStatus.DRAFT, saved.record.status)
                assertEquals(before, f.env.local.get(hero))
            }
            click("Review start")
            awaitText("I confirm this turn order")
            compose.onNode(hasText("Start encounter") and hasClickAction()).assertIsNotEnabled()
            click("I confirm this turn order", scroll = true)
            click("Start encounter")
            awaitText("Current turn: Fictional Scout")
            compose.onNodeWithText("Open Fictional Scout's actions").assertDoesNotExist()
            click("View Fictional Scout's source", scroll = true)
            awaitText("This participant remains a source record. Encounter tracking does not create a playable sheet or invent statistics.")
            click("Close", last = true)
            click("Next turn")
            awaitText("Current turn: Fictional Hero")
            click("Open Fictional Hero's actions", scroll = true)
            assertEquals(hero, route?.profileId)
            assertEquals("Combat", route?.section)
            click("Next turn")
            awaitText("Round 2")
            awaitText("Current turn: Fictional Scout")
            compose.onNodeWithText("Current turn: Fictional Scout").performScrollTo()
            capture("genesis-encounter-active", activity)
            click("End encounter")
            awaitText("End encounter?")
            click("End encounter", last = true)
            awaitText("Ended · round 2")
            click("Reopen setup")
            awaitText("Reopen setup?")
            click("Reopen setup", last = true)
            awaitText("Setup · confirm the order before starting")
            runBlocking {
                val saved = requireNotNull(f.env.authority.encounters.get(requireNotNull(id)))
                assertEquals(GenesisEncounterStatus.DRAFT, saved.record.status)
                assertEquals(0, saved.record.round); assertNull(saved.record.currentActorId)
                assertEquals(before, f.env.local.get(hero))
                assertNull(f.env.local.get(f.npc))
                assertNull(f.db.recordsDao().characterProfile(f.npc)?.currentHp)
                val backup = f.env.authority.backup.exportWithProof()
                assertTrue(f.env.authority.backup.previewRestore(backup.text).valid)
            }
        }
        f.db.close()
        val reopened = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java, f.databaseName).allowMainThreadQueries().build()
        try { runBlocking {
            val saved = requireNotNull(GenesisAuthorityRepository(reopened).encounters.get(requireNotNull(id)))
            assertEquals(GenesisEncounterStatus.DRAFT, saved.record.status)
            assertEquals(listOf(f.npc, hero), saved.participants.map { it.actorId })
            assertEquals(before, ReforgeRepository(reopened).get(hero))
        } } finally { reopened.close() }
    }

    @Test fun invalidInitiativeAndCampaignChangePreserveDraftAndBlockSave() = fixture { f ->
        show({ GenesisEncounterEditor(f.env, null, f.campaign.id, {}, {}) }) {
            populate()
            compose.onNodeWithText("Initiative: Fictional Scout").performScrollTo().performTextReplacement("unresolved")
            click("Review & save")
            compose.onNodeWithText("Save encounter").assertIsNotEnabled()
            compose.onNodeWithText("Initiative must be a whole number or blank.").assertExists()
            click("Edit Details", scroll = true)
            click("Fictional Vale", scroll = true)
            click("Fictional Coast")
            click("Review & save")
            compose.onNodeWithText("Review changed campaign membership before saving.").assertExists()
            compose.onNodeWithText("1. Fictional Hero").assertExists()
            compose.onNodeWithText("Save encounter").assertIsNotEnabled()
            runBlocking { assertTrue(f.env.authority.encounters.observe(f.campaign.id).first().isEmpty()); assertTrue(f.env.authority.encounters.observe(f.other.id).first().isEmpty()) }
        }
    }

    @Test fun recreationRetainsExplicitOrderAndDraftCancellationWritesNothing() = fixture { f ->
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        var dismissed = false
        show({ if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            GenesisEncounterEditor(f.env, null, f.campaign.id, { dismissed = true }, {})
        } }) {
            populate()
            click("Move Fictional Scout up", scroll = true)
            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { saved = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; visible.value = true }
            awaitText("1. Fictional Scout")
            compose.onNodeWithText("Initiative: Fictional Scout").performScrollTo().assertTextContains("20")
            click("Close")
            awaitText("Discard changes?")
            click("Discard")
            assertTrue(dismissed)
            runBlocking { assertTrue(f.env.authority.encounters.observe(f.campaign.id).first().isEmpty()) }
        }
    }

    @Test fun staleReviewedStartDoesNotStartOrChangeAnyActor() = fixture { f ->
        val draft = runBlocking { f.env.authority.encounters.saveDraft("fictional-stale-encounter", null, f.campaign.id, "Fictional stale setup", "",
            listOf(GenesisEncounterParticipantInput(hero, requireNotNull(f.env.authority.actor(hero)).revision))) }
        val before = runBlocking { f.env.local.get(hero) }
        show({ GenesisEncountersPage(f.env) {} }) {
            click("Fictional stale setup", scroll = true)
            click("Review start")
            awaitText("I confirm this turn order")
            runBlocking { f.env.authority.encounters.saveDraft(draft.record.id, draft.record.revision, f.campaign.id, "Fictional revised setup", "",
                listOf(GenesisEncounterParticipantInput(hero, requireNotNull(f.env.authority.actor(hero)).revision))) }
            click("I confirm this turn order", scroll = true)
            click("Start encounter")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("genesis-encounter-error").fetchSemanticsNodes().isNotEmpty() }
            runBlocking {
                assertEquals(GenesisEncounterStatus.DRAFT, f.env.authority.encounters.get(draft.record.id)?.record?.status)
                assertEquals(before, f.env.local.get(hero))
            }
        }
    }

    @Test fun delayedCampaignContextInitializesOnceWithoutReassigningAnyActor() = fixture { f ->
        val context = mutableStateOf(f.env.copy(selectedCampaignId = null, selectionLoaded = false))
        val actorBefore = runBlocking { f.env.authority.actor(hero) }
        show({ GenesisEncountersPage(context.value) {} }) {
            awaitText("Loading campaign context…")
            compose.onNode(hasText("Create encounter") and hasClickAction()).assertIsNotEnabled()
            compose.runOnIdle { context.value = f.env }
            awaitText("Fictional Vale")
            click("Create encounter")
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Encounter name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Encounter name").performTextInput("Fictional late context")
            click("Review & save")
            click("Save encounter")
            awaitText("Setup · confirm the order before starting")
            runBlocking {
                assertEquals(f.campaign.id, f.env.authority.encounters.observe(f.campaign.id).first().single().record.campaignId)
                assertEquals(actorBefore, f.env.authority.actor(hero))
                assertTrue(f.env.authority.encounters.observe(f.other.id).first().isEmpty())
            }
        }
    }

    @Test fun savedSetupLoadsExactMembershipRevisionsAndEditsSurviveRecreationAndReopen() = fixture { f ->
        val members = runBlocking {
            listOf(hero, f.npc).map { id ->
                val current = requireNotNull(f.env.authority.actor(id))
                f.env.authority.updateActorAssociation(id, current.revision, current.role, current.campaignId, current.ownerActorId)
            }
        }
        assertTrue(members.all { it.revision > 1 })
        val original = runBlocking {
            f.env.authority.encounters.saveDraft("fictional-existing-editor", null, f.campaign.id,
                "Fictional saved setup", "Retained setup notes",
                listOf(GenesisEncounterParticipantInput(hero, members[0].revision, 9),
                    GenesisEncounterParticipantInput(f.npc, members[1].revision, null)))
        }
        val before = runBlocking { f.env.local.get(hero) }
        // Delay the repository's real first emission, without replacing Room or
        // inventing actor rows. The editor must not initialize from its empty
        // loading list when this readiness gate opens.
        val ready = CompletableDeferred<Unit>()
        val environment = f.env.copy(authority = GenesisAuthorityRepository(f.db) { ready.await() })
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        var outcome: GenesisEncounterSnapshot? = null
        show({
            if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                GenesisEncounterEditor(environment, original, f.campaign.id, {}, {
                    outcome = it; visible.value = false
                })
            }
        }) { activity ->
            awaitText("Encounter name")
            compose.onNodeWithText("Encounter name").assertIsNotEnabled()
            assertNull(outcome)
            compose.runOnIdle { ready.complete(Unit) }
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("Encounter name") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Encounter name").performTextReplacement("Fictional edited setup")
            click("Next")
            awaitText("1. Fictional Hero")
            compose.onNodeWithText("Initiative: Fictional Hero").performScrollTo().assertTextContains("9")
            compose.onNodeWithText("Initiative: Fictional Hero").performTextReplacement("17")
            click("Move Fictional Scout up", scroll = true)

            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { saved = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; visible.value = true }
            awaitText("1. Fictional Scout")
            compose.onNodeWithText("Initiative: Fictional Hero").performScrollTo().assertTextContains("17")
            click("Review & save")
            compose.onNodeWithText("Fictional edited setup").assertExists()
            compose.onNodeWithText("1. Fictional Scout").assertExists()
            compose.onNodeWithText("Initiative: Not entered").assertExists()
            compose.onNodeWithText("Review changed campaign membership before saving.").assertDoesNotExist()
            capture("genesis-encounter-existing-setup-review", activity)
            click("Save encounter")
            compose.waitUntil(15_000) { outcome != null }
        }
        val saved = requireNotNull(outcome)
        assertEquals(original.record.id, saved.record.id)
        assertEquals(original.record.revision + 1, saved.record.revision)
        assertEquals(original.record.creationCommandId, saved.record.creationCommandId)
        assertEquals("Fictional edited setup", saved.record.title)
        assertEquals("Retained setup notes", saved.record.notes)
        assertEquals(GenesisEncounterStatus.DRAFT, saved.record.status)
        assertEquals(listOf(f.npc, hero), saved.participants.map { it.actorId })
        assertEquals(listOf(null, 17), saved.participants.map { it.initiative })
        runBlocking {
            assertEquals(saved, f.env.authority.encounters.get(saved.record.id))
            assertEquals(1, f.env.authority.encounters.observe(f.campaign.id).first().size)
            assertEquals(before, f.env.local.get(hero))
            assertNull(f.env.local.get(f.npc))
            assertEquals(members, members.map { requireNotNull(f.env.authority.actor(it.actorId)) })
            val backup = f.env.authority.backup.exportWithProof()
            assertTrue(f.env.authority.backup.previewRestore(backup.text).valid)
        }
        f.db.close()
        val reopened = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java,
            f.databaseName).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals(saved, GenesisAuthorityRepository(reopened).encounters.get(saved.record.id))
                assertEquals(before, ReforgeRepository(reopened).get(hero))
                assertNull(ReforgeRepository(reopened).get(f.npc))
            }
        } finally { reopened.close() }
    }

    @Test fun editingSavedSetupDoesNotSilentlyRefreshAChangedMembershipRevision() = fixture { f ->
        val member = runBlocking { requireNotNull(f.env.authority.actor(hero)) }
        val original = runBlocking {
            f.env.authority.encounters.saveDraft("fictional-existing-membership", null, f.campaign.id,
                "Fictional membership setup", "",
                listOf(GenesisEncounterParticipantInput(hero, member.revision, 8)))
        }
        val before = runBlocking { f.env.local.get(hero) }
        var outcome: GenesisEncounterSnapshot? = null
        show({ GenesisEncounterEditor(f.env, original, f.campaign.id, {}, { outcome = it }) }) {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("Encounter name") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Encounter name").performTextReplacement("Fictional pending edit")
            click("Review & save")
            compose.onNodeWithText("Save encounter").assertIsEnabled()
            runBlocking {
                f.env.authority.updateActorAssociation(hero, member.revision, member.role, member.campaignId, member.ownerActorId)
            }
            awaitText("Review changed campaign membership before saving.")
            compose.onNodeWithText("Save encounter").assertIsNotEnabled()
            assertNull(outcome)
            runBlocking {
                assertEquals(original, f.env.authority.encounters.get(original.record.id))
                assertEquals(before, f.env.local.get(hero))
            }
        }
    }
}
