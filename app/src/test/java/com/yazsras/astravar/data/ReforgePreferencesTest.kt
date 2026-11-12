package com.yazsras.astravar.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgePreferencesTest {
    @Test fun pendingCraftSurvivesRestartAndCompareAndClearPreservesOtherPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fictional-genesis-pending-craft.preferences_pb")
        file.delete()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun open() = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        val first = GenesisPendingCraft("fictional-project", "a".repeat(64), "fictional-craft-one")
        val second = first.copy(commandId = "fictional-craft-two")
        try {
            var preferences = open()
            preferences.selectCampaign("fictional-campaign")
            preferences.selectProfile("fictional-player")
            preferences.saveQuickActions("fictional-player", listOf("rest", "health"))
            preferences.rememberPendingCraft(first)
            preferences.rememberPendingCraft(first)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = open()
            assertEquals(first, preferences.pendingCraft(first.projectId))
            try { preferences.rememberPendingCraft(second); fail("An unresolved intent must not be overwritten") } catch (_: IllegalStateException) { }
            try { preferences.clearPendingCraft(second); fail("A stale surface must not clear another command") } catch (_: IllegalStateException) { }
            assertEquals(first, preferences.pendingCraft(first.projectId))
            preferences.clearPendingCraft(first)
            preferences.rememberPendingCraft(second)
            try { preferences.clearPendingCraft(first); fail("A later intent must survive the earlier acknowledgment") } catch (_: IllegalStateException) { }
            assertEquals(second, preferences.pendingCraft(first.projectId))
            preferences.clearPendingCraft(second)
            preferences.clearPendingCraft(second)
            assertNull(preferences.pendingCraft(first.projectId))
            assertEquals("fictional-campaign", preferences.selectedCampaignId.first())
            assertEquals("fictional-player", preferences.activeProfileId.first())
            assertEquals(listOf("rest", "health"), preferences.quickActions("fictional-player").first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }

    @Test fun concurrentCraftIntentWritersCannotReplaceOrClearTheWinningCommand() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fictional-genesis-pending-craft-race.preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        val tokens = (1..8).map { GenesisPendingCraft("fictional-project", "b".repeat(64), "fictional-command-$it") }
        try {
            val accepted = tokens.map { token -> async(Dispatchers.Default) {
                try { preferences.rememberPendingCraft(token); token } catch (_: IllegalStateException) { null }
            } }.awaitAll().filterNotNull()
            assertEquals(1, accepted.size)
            assertEquals(accepted.single(), preferences.pendingCraft("fictional-project"))
            tokens.filterNot { it == accepted.single() }.forEach {
                try { preferences.clearPendingCraft(it); fail("Losing writer must not clear winning intent") } catch (_: IllegalStateException) { }
            }
            assertEquals(accepted.single(), preferences.pendingCraft("fictional-project"))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }

    @Test fun malformedOrMisboundPendingHintIsRetainedAndCannotBeOverwritten() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fictional-genesis-invalid-pending-craft.preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val preferences = ReforgePreferences(store)
        val token = GenesisPendingCraft("fictional-project", "c".repeat(64), "fictional-command")
        val key = stringPreferencesKey("pending_craft_${reforgeHash(token.projectId)}")
        val valid = encodeGenesisPendingCraft(token)
        try {
            val invalid = listOf("not-json", valid.replace("\"projectId\":", "\"projectId\":\"duplicate\",\"projectId\":"),
                valid.replace("c".repeat(64), "invalid"), valid.dropLast(1) + ",\"unknown\":true}",
                encodeGenesisPendingCraft(token.copy(projectId = "different-project")))
            invalid.forEach { raw ->
                store.edit { it[key] = raw }
                try { preferences.pendingCraft(token.projectId); fail("Invalid hints must fail closed") } catch (_: Exception) { }
                try { preferences.rememberPendingCraft(token); fail("Invalid hints must not be replaced") } catch (_: Exception) { }
                try { preferences.clearPendingCraft(token); fail("Invalid hints must not be cleared") } catch (_: Exception) { }
                assertEquals(raw, store.data.first()[key])
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }

    @Test fun campaignContextPersistsAndClearsWithoutChangingActorOrQuickActions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fictional-genesis-campaign-context.preferences_pb")
        file.delete()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun open() = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            var preferences = open()
            assertNull(preferences.selectedCampaignId.first())
            preferences.selectProfile("fictional-player")
            preferences.saveQuickActions("fictional-player", listOf("rest", "health"))
            preferences.selectCampaign("fictional-campaign")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = open()
            assertEquals("fictional-campaign", preferences.selectedCampaignId.first())
            try { preferences.selectCampaign(""); fail("An invalid campaign identity must not replace selection") }
            catch (_: IllegalArgumentException) { }
            assertEquals("fictional-campaign", preferences.selectedCampaignId.first())
            preferences.selectCampaign(null)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = open()
            assertNull(preferences.selectedCampaignId.first())
            assertEquals("fictional-player", preferences.activeProfileId.first())
            assertEquals(listOf("rest", "health"), preferences.quickActions("fictional-player").first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }

    @Test fun quickActionsPersistPerActorWithoutChangingActiveSelection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fictional-genesis-actions.preferences_pb")
        file.delete()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            preferences.selectProfile("fixture-owner")
            preferences.saveQuickActions("fixture-owner", listOf("rest", "health", "dice"))
            assertEquals(ReforgePreferences.DEFAULT_QUICK_ACTIONS, preferences.quickActions("fixture-companion").first())
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(listOf("rest", "health", "dice"), preferences.quickActions("fixture-owner").first())
            assertEquals("fixture-owner", preferences.activeProfileId.first())
            for (invalid in listOf(emptyList(), listOf("health", "health"), listOf("unknown"), ReforgePreferences.QUICK_ACTIONS)) {
                try { preferences.saveQuickActions("fixture-owner", invalid); fail("Invalid choices must be rejected") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(listOf("rest", "health", "dice"), preferences.quickActions("fixture-owner").first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }

    @Test fun primaryFallbackNeverSelectsCompanionAheadOfPrimaryAndExplicitChoiceWins() {
        val profiles = listOf("eek", "local-player", "veyrath")
        assertEquals("local-player", ReforgePreferences.chooseProfile(null, profiles))
        assertEquals("local-player", ReforgePreferences.chooseProfile("unavailable", profiles))
        assertEquals("veyrath", ReforgePreferences.chooseProfile("veyrath", profiles))
        assertEquals("eek", ReforgePreferences.chooseProfile(null, listOf("eek", "korr")))
        assertNull(ReforgePreferences.chooseProfile("unavailable", emptyList()))
    }
    @Test fun selectedActorSurvivesClosingAndReopeningItsOwnPreferenceFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fictional-reforge-selection.preferences_pb")
        file.delete()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertNull(preferences.activeProfileId.first())
            preferences.selectProfile("fictional-companion")
            assertEquals("fictional-companion", preferences.activeProfileId.first())
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals("fictional-companion", preferences.activeProfileId.first())
            preferences.selectProfile("local-player")
            assertEquals("local-player", preferences.activeProfileId.first())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
