package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.AstravarDb
import com.yazsras.astravar.data.Repository
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CorePickerRebuildTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun inspect(campaign: Campaign, check: (Repository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AstravarDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = Repository(db, scope)
        val initial = runBlocking { repo.mutate(label = "Synthetic preserved core fixture") { campaign } }
        val before = runBlocking { repo.export() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CorePicker(initial, Actions(repo, scope)) {} } }
            compose.onNodeWithText("Permanent internal nexus").assertIsDisplayed()
            compose.onNodeWithText("Install core").assertDoesNotExist()
            compose.onNodeWithText("Confirm install").assertDoesNotExist()
            compose.onNodeWithText("Spare fixture").assertDoesNotExist()
            check(repo)
            assertEquals(before, runBlocking { repo.export() })
        } finally {
            activity.pause().stop().destroy()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    @Test fun permanentNexusInspectionCannotSwapOrSpendPreservedLegacyCores() {
        inspect(Campaign(onboarded = true, attuned = true, encounter = true, installedId = "core-a",
            cores = listOf(Core("core-a", "Installed fixture"), Core("core-b", "Spare fixture")))) { repo ->
            compose.onNodeWithText("Installed fixture").assertIsDisplayed()
            val after = runBlocking { BackupCodec.decode(repo.export()).campaign }
            assertEquals("core-a", after.installedId)
            assertEquals(2, after.cores.size)
            assertFalse(after.turn.fullAction)
        }
    }

    @Test fun historicalDestructionRemainsReadableWithoutOfferingReplacement() {
        inspect(Campaign(onboarded = true, attuned = true, installedId = "core-a", postShatter = true,
            cores = listOf(Core("core-a", "Historical fixture", destroyed = true)))) { repo ->
            compose.onNodeWithText("Destroyed").assertIsDisplayed()
            val after = runBlocking { BackupCodec.decode(repo.export()).campaign }
            assertTrue(after.postShatter)
            assertTrue(after.core("core-a").destroyed)
            assertEquals(0L, after.gameSeconds)
        }
    }

    @Test fun missingPermanentNexusDoesNotInventOrInstallACore() {
        inspect(Campaign(onboarded = true)) { repo ->
            val after = runBlocking { BackupCodec.decode(repo.export()).campaign }
            assertNull(after.installedId)
            assertTrue(after.cores.isEmpty())
        }
    }
}
