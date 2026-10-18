package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

    @Test fun inspectingACoreSpendsNothingAndConfirmationUsesTheExistingInstallTransaction() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AstravarDb::class.java).build()
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = Repository(db, repositoryScope)
        val initial = runBlocking {
            repo.mutate(label = "Synthetic UI fixture") { Campaign(onboarded = true, attuned = true,
                encounter = true, installedId = "core-a", cores = listOf(Core("core-a", "Installed fixture"), Core("core-b", "Spare fixture"))) }
        }
        val before = runBlocking { repo.export() }
        var dismissed = false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                val state by repo.state.collectAsState()
                val scope = rememberCoroutineScope()
                val actions = remember(repo) { Actions(repo, scope) }
                state?.let { CorePicker(it, actions) { dismissed = true } }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Spare fixture").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Spare fixture").performClick()
            compose.onNodeWithText("Install core").assertIsDisplayed()
            compose.onNodeWithText("Installation cost").assertIsDisplayed()
            compose.onNodeWithText(initial.profile.swapCost).assertIsDisplayed()
            compose.onNodeWithText("Confirm install").assertIsEnabled()
            assertEquals(before, runBlocking { repo.export() })
            assertFalse(dismissed)
            compose.onNodeWithText("Confirm install").performClick()
            compose.waitUntil(10_000) { runBlocking { BackupCodec.decode(repo.export()).campaign.installedId == "core-b" } }
            val after = runBlocking { BackupCodec.decode(repo.export()) }
            assertEquals("core-b", after.campaign.installedId)
            assertTrue(after.campaign.turn.fullAction)
            assertEquals(initial.cores, after.campaign.cores)
            assertEquals(initial.gameSeconds, after.campaign.gameSeconds)
            val installationEvent = after.history.single { it.label == "Install Spare fixture" }
            java.util.UUID.fromString(installationEvent.id)
            // Replaying the UI's actual persisted command returns the existing commit.
            // Without command idempotency, a second install would fail or spend again.
            val replay = runBlocking { repo.mutate(installationEvent.id, installationEvent.label) { Engine.install(it, "core-b") } }
            assertEquals(after.campaign, replay)
            assertEquals(1, runBlocking { BackupCodec.decode(repo.export()).history.count { it.id == installationEvent.id } })
            compose.runOnIdle { assertTrue(dismissed) }
        } finally {
            activity.pause().stop().destroy()
            runBlocking { repositoryScope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }
    @Test fun busyReviewCannotNavigateToAnotherCoreOrDismissTheConfirmation() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AstravarDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = Repository(db, scope)
        val initial = runBlocking { repo.mutate(label = "Synthetic busy fixture") {
            Campaign(onboarded = true, attuned = true, installedId = "core-a",
                cores = listOf(Core("core-a", "Installed fixture"), Core("core-b", "Spare fixture")))
        } }
        val actions = Actions(repo, scope)
        var dismissed = false
        val before = runBlocking { repo.export() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CorePicker(initial, actions) { dismissed = true } } }
            compose.onNodeWithText("Spare fixture").performClick()
            compose.runOnIdle { actions.busy = true }
            compose.onNodeWithText("‹ All cores").assertIsNotEnabled()
            compose.onNodeWithText("Installing…").assertIsNotEnabled()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Install core").assertIsDisplayed()
            compose.runOnIdle { assertFalse(dismissed); actions.busy = false }
            compose.onNodeWithText("‹ All cores").performClick()
            compose.onNodeWithText("Choose a core").assertIsDisplayed()
            assertEquals(before, runBlocking { repo.export() })
            compose.onNodeWithText("Close").performClick()
            compose.runOnIdle { assertTrue(dismissed) }
        } finally {
            activity.pause().stop().destroy()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    @Test fun aRevisionChangeAfterReviewRejectsTheTransactionEvenBeforeUiStateCatchesUp() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AstravarDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = Repository(db, scope)
        val initial = runBlocking { repo.mutate(label = "Synthetic stale fixture") {
            Campaign(onboarded = true, attuned = true, encounter = true, installedId = "core-a",
                cores = listOf(Core("core-a", "Installed fixture"), Core("core-b", "Spare fixture")))
        } }
        val actions = Actions(repo, scope)
        var dismissed = false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            // Hold the reviewed UI snapshot intentionally: transaction safety cannot
            // rely on the asynchronous Room observer disabling the button in time.
            activity.get().setContent { AstravarTheme { CorePicker(initial, actions) { dismissed = true } } }
            compose.onNodeWithText("Spare fixture").performClick()
            runBlocking { repo.mutate(label = "Another confirmed operation") { it.copy(notes = "Synthetic state change") } }
            val changed = runBlocking { repo.export() }
            compose.onNodeWithText("Confirm install").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { !actions.busy && actions.message.contains("Core configuration changed") }
            compose.onNodeWithText("Core configuration changed. Review it again before installing.").assertExists()
            assertEquals(changed, runBlocking { repo.export() })
            assertEquals("core-a", runBlocking { BackupCodec.decode(repo.export()).campaign.installedId })
            assertFalse(runBlocking { BackupCodec.decode(repo.export()).campaign.turn.fullAction })
            assertFalse(dismissed)
        } finally {
            activity.pause().stop().destroy()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

}
