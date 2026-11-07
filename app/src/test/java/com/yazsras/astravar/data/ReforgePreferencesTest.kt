package com.yazsras.astravar.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
