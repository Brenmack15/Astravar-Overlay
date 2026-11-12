package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.ResourcePool
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
class GenesisCompanionResourceVisibilityTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val pool = ResourcePool("mechanical-pool", "Current repair", 3, 2)
    private val actor = GenesisActorAuthority("fictional-owl", GenesisActorRole.COMPANION, playable = true,
        activationCommandId = "fixture", lastCommandId = "fixture", playableRevision = 2,
        resourceBindingsJson = "{\"mechanical-pool\":\"original-source-counter\"}", createdAt = 1, updatedAt = 2)
    private fun saved(resources: List<ResourcePool> = listOf(pool)) = ReforgeSavedCharacter(
        ReforgeCharacter(actor.actorId, "Fictional owl", resources = resources), 2, ReforgeOrigin(), 1, 2)

    @Test fun exactBindingSurvivesRenameAndNamesCannotReactivateHistoricalCounters() {
        val renamed = saved(listOf(pool.copy(name = "Renamed repair")))
        val access = genesisCompanionResourceAccess(actor.actorId, actor, renamed)
        assertTrue(access.editable("original-source-counter"))
        assertFalse(access.editable("Renamed repair"))
        assertFalse(access.editable("same-name-unbound-counter"))
        assertFalse(access.editable(null))
        val newPool = pool.copy(id = "different-pool", name = pool.name)
        val replacement = genesisCompanionResourceAccess(actor.actorId, actor, saved(listOf(newPool)))
        assertFalse(replacement.editable("original-source-counter"))
        assertTrue(replacement.editable(genesisResourceId(actor, newPool.id)))
    }

    @Test fun sourceOnlyAndUnclassifiedLegacyCountersKeepTheirExistingEditor() {
        assertTrue(genesisCompanionResourceAccess(actor.actorId, null, null).editable(null))
        val sourceOnly = genesisCompanionResourceAccess(actor.actorId, actor.copy(playable = false), null)
        assertTrue(sourceOnly.editable("manual-source-counter"))
        assertTrue(sourceOnly.editable(null))
    }

    @Test fun missingStaleOrMalformedManagedIdentityCannotEnableEditing() {
        assertFalse(genesisCompanionResourceAccess(actor.actorId, actor, null).editable("original-source-counter"))
        assertFalse(genesisCompanionResourceAccess("another-actor", actor, saved()).editable("original-source-counter"))
        assertFalse(genesisCompanionResourceAccess(actor.actorId, actor, saved().copy(revision = 3)).loaded)
        val malformed = actor.copy(resourceBindingsJson = "{\"mechanical-pool\":\"one\",\"mechanical-pool\":\"two\"}")
        assertFalse(genesisCompanionResourceAccess(actor.actorId, malformed, saved()).editable("one"))
        assertFalse(genesisCompanionResourceAccess(actor.actorId, actor, saved().copy(character = saved().character.copy(archived = true))).editable("original-source-counter"))
    }

    private fun fixture(block: (GenesisConnectedEnvironment, CampaignLedgerDb, ReforgeSavedCharacter) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val environment = GenesisConnectedEnvironment(GenesisAuthorityRepository(db), CampaignLedgerRepository(db),
            ReforgeRepository(db), ReforgePreferences.open(context))
        try {
            val created = runBlocking {
                requireNotNull(environment.authority.createActor("Fictional Eek", GenesisActorRole.COMPANION,
                    playable = ReforgeCharacter("eek", "Fictional Eek", resources = listOf(
                        ResourcePool("retired-pool", "Retired repair", 1, 1), pool)), commandId = "resource-ui-create").playable)
            }
            block(environment, db, created)
        } finally { db.close() }
    }
    private fun show(environment: GenesisConnectedEnvironment, content: @Composable () -> Unit, block: () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalGenesisConnectedEnvironment provides environment) { AstravarTheme { content() } }
            }
            block()
        } finally { activity.pause().stop().destroy() }
    }
    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun dialogAction(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    // Disabled text fields deliberately omit SetText. Requiring that action would exclude
    // the very read-only control this regression needs to inspect.
    private fun readOnlyField(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(isDialog()))
        .assertIsNotEnabled().assert(hasSetTextAction().not())

    @Test fun companionEditorRetainsInactiveHistoryAndUpdatesOnlyBoundAvailability() = fixture { env, db, created ->
        val originalCounter = runBlocking { db.recordsDao().allCompanionResources().single { it.name == "Retired repair" } }
        runBlocking { env.local.save("eek", created.revision, created.character.copy(resources = listOf(pool)), "Remove fictional configured pool", "resource-ui-remove") }
        show(env, { Page { PartyCompanionEditorV2(env.campaign, "eek", "Fictional Eek") } }) {
            waitFor("Inactive history")
            compose.onNodeWithText("Add resource").assertIsNotEnabled()
            compose.onNode(hasText("Retired repair") and hasClickAction()).performScrollTo().performClick()
            waitFor("Inactive source history.")
            dialogAction("Save resource").assertIsNotEnabled()
            readOnlyField("Current amount · blank = unknown")
            dialogAction("Close").performClick()
            compose.onNode(hasText(pool.name) and hasClickAction()).performScrollTo().performClick()
            waitFor("Shared resource.")
            readOnlyField("Resource name")
            readOnlyField("Maximum amount · blank = unknown")
            compose.onNode(hasSetTextAction() and hasText("Current amount · blank = unknown")).performTextReplacement("1")
            dialogAction("Save resource").assertIsEnabled().performClick()
            waitFor("Resource saved locally")
            runBlocking {
                assertEquals(1, env.local.get("eek")!!.character.resources.single().current)
                assertEquals(originalCounter, db.recordsDao().resource(originalCounter.id))
            }
        }
    }

    @Test fun eekEditorDisablesAnOpenCounterWhenItsConfiguredPoolIsRemoved() = fixture { env, db, created ->
        val originalCounter = runBlocking { db.recordsDao().allCompanionResources().single { it.name == pool.name } }
        show(env, { EekPageV2(env.campaign) }) {
            waitFor("Add resources on the playable sheet")
            compose.onNode(hasText(pool.name) and hasClickAction()).performScrollTo().performClick()
            dialogAction("Update known resource").assertIsEnabled()
            runBlocking { env.local.save("eek", created.revision, created.character.copy(resources = emptyList()), "Remove fictional resources", "resource-ui-remove-open") }
            waitFor("Inactive source history.")
            dialogAction("Update known resource").assertIsNotEnabled()
            readOnlyField("Confirmed current value (blank = unknown)")
            runBlocking {
                assertTrue(env.local.get("eek")!!.character.resources.isEmpty())
                assertEquals(originalCounter, db.recordsDao().resource(originalCounter.id))
            }
        }
    }
}
