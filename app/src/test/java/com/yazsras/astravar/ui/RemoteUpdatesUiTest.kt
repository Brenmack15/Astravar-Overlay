package com.yazsras.astravar.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.rules.Profile
import com.yazsras.astravar.savePresentationCapture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteUpdatesUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var oldRequestedSize: String? = null
    @Before fun realDialogSizes() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreDialogSizes() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private inner class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledgerDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val remoteDb = Room.inMemoryDatabaseBuilder(context, RemoteStatDb::class.java).build()
        val sandboxDb = Room.inMemoryDatabaseBuilder(context, SandboxSyncDb::class.java).build()
        val records = CampaignLedgerRepository(ledgerDb)
        val networkCalls = AtomicInteger()
        val transport = SandboxTransport { _, _, _ -> networkCalls.incrementAndGet(); throw IOException("Synthetic offline") }
        val sandbox = SandboxSyncRepository(sandboxDb, transport)
        val remote = RemoteStatRepository(remoteDb, records.records, sandbox, transport)
        val activity: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val originalConfiguration = Configuration(activity.get().resources.configuration)
        @Suppress("DEPRECATION")
        fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
            // Dialog creates its own Android composition owner. Set the Activity's
            // real resource configuration so this exercises the dialog font scale,
            // rather than overriding only the parent composition's LocalDensity.
            val resources = activity.get().resources
            resources.updateConfiguration(Configuration(resources.configuration).apply { this.fontScale = fontScale }, resources.displayMetrics)
            activity.get().setContent { AstravarTheme {
                Surface(Modifier.fillMaxSize(), color = RebuildPalette.Canvas,
                    contentColor = MaterialTheme.colorScheme.onSurface) { content() }
            } }
        }
        suspend fun command(key: String = "STR", score: Int = 18, revision: Long = 0): RemoteStatCommand {
            val settings = remote.settings.first()!!
            val payload = RemoteAbilityPayload("a5811ddd-ac91-411b-83e6-02d96ff089d0", settings.sessionId, key, revision, score)
            return RemoteStatCommand(payload.commandId, settings.sessionId, PublicSandbox.DEFAULT_ORIGIN,
                "afeb6ce2-9c78-4859-962c-8662d35e233d", RemoteStatProtocol.encode(payload), key, score, revision, receivedAt = 1)
        }
        override fun close() {
            @Suppress("DEPRECATION")
            activity.get().resources.updateConfiguration(originalConfiguration, activity.get().resources.displayMetrics)
            activity.pause().stop().destroy(); ledgerDb.close(); remoteDb.close(); sandboxDb.close()
        }
    }

    /** Includes actual Android Dialog windows; an activity-only bitmap would omit the confirmation. */
    private fun capture(name: String, activity: ComponentActivity, modal: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle {
            val decor = activity.window.decorView
            val windows = WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            if (modal) assertTrue(windows.any { it !== decor })
            if (System.getProperty("astravar.screenshots") == null) return@runOnIdle
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); decor.draw(canvas)
                val base = IntArray(2).also { decor.getLocationOnScreen(it) }
                windows.filter { it !== decor }.forEach { window ->
                    val position = IntArray(2).also { window.getLocationOnScreen(it) }
                    val params = window.layoutParams as? WindowManager.LayoutParams
                    if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                        canvas.drawColor(Color.argb((params.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
                    }
                    canvas.save(); canvas.translate((position[0] - base[0]).toFloat(), (position[1] - base[1]).toFloat())
                    window.draw(canvas); canvas.restore()
                }
                savePresentationCapture(bitmap, name)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun optInCreatesOnlyEmptyDisposableProfileAndDoesNotBorrowRealCharacterValues() {
        Fixture().use { f ->
            runBlocking {
                f.records.records.saveProfile(CharacterProfile(name = "Real sheet fixture", level = 7), source = "Synthetic local fixture")
                f.records.records.saveAbility(CharacterAbility("STR", score = 20, magicModifier = 2), source = "Synthetic local fixture")
            }
            val original = runBlocking { f.ledgerDb.recordsDao().characterAbility("local-player", "STR") }
            f.render {
                val abilities by f.records.records.characterAbilities.collectAsState(initial = emptyList())
                RebuildToolSheet("Remote Updates", {}) { RemoteUpdatesPage(f.remote, abilities) {} }
            }
            compose.onNodeWithTag("remote-test-toggle").assertTextContains("Enable remote test").performClick()
            compose.waitUntil(10_000) { runBlocking { f.remote.settings.first()?.enabled == true } }
            compose.onNodeWithText("Copy test session").performScrollTo().assertIsDisplayed()
            capture("remote-updates-opt-in", f.activity.get(), modal = true)
            compose.onNodeWithText("Copy test details").performScrollTo().performClick()
            val clipboard = f.context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val session = runBlocking { f.remote.settings.first()!!.sessionId }
            assertEquals("Astravar fictional stat test\nSession: $session\nProfile: ${RemoteStatProtocol.PROFILE_ID}\n" +
                "STR revision 0 · DEX revision 0 · CON revision 0 · INT revision 0 · WIS revision 0 · CHA revision 0",
                clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(0, f.networkCalls.get())
            f.render { Page { CharacterOverviewV2(f.records,
                Campaign(onboarded = true, profile = Profile(character = "Weapon identity fixture", level = 6)), RemoteStatProtocol.PROFILE_ID) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Disposable stat test").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Real sheet fixture").assertDoesNotExist()
            compose.onNodeWithText("Weapon identity fixture").assertDoesNotExist()
            compose.onNodeWithText("Level unknown").assertExists()
            listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").forEach { key ->
                compose.onNodeWithTag("ability-$key").assert(hasContentDescription("score unknown", substring = true))
            }
            runBlocking {
                assertEquals(original, f.ledgerDb.recordsDao().characterAbility("local-player", "STR"))
                assertEquals(1, f.ledgerDb.recordsDao().allCharacterAbilities().size)
            }
        }
    }

    @Test fun closingProposalDefersWithoutApplyingOrRejectingAndExplicitRejectIsSeparate() {
        Fixture().use { f ->
            runBlocking { f.remote.setEnabled(true) }
            val command = runBlocking { f.command() }
            var open by mutableStateOf(true)
            var applied = 0; var rejected = 0; var dismissed = 0
            f.render {
                if (open) RemoteStatReviewOverlay(command, null, true, false, onApply = { applied++ },
                    onReject = { rejected++ }, onDismiss = { dismissed++; open = false })
            }
            compose.onNodeWithText("Unknown").assertIsDisplayed()
            compose.onNodeWithText("18").assertIsDisplayed()
            compose.onNodeWithText("Source: ChatGPT fictional test").assertIsDisplayed()
            compose.onNodeWithTag("remote-apply").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("remote-reject").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            capture("remote-stat-confirmation", f.activity.get(), modal = true)
            compose.onNodeWithText("Close").performClick()
            compose.runOnIdle { assertEquals(0, applied); assertEquals(0, rejected); assertEquals(1, dismissed); open = true }
            compose.onNodeWithTag("remote-reject").performClick()
            compose.runOnIdle { assertEquals(0, applied); assertEquals(1, rejected) }
            assertTrue(runBlocking { f.ledgerDb.recordsDao().allCharacterAbilities().isEmpty() })
        }
    }

    @Test fun confirmedApplyImmediatelyUpdatesDisposableArmorTileAndPreservesRealSheetAndMagicModifier() {
        Fixture().use { f ->
            runBlocking {
                f.remote.setEnabled(true)
                f.records.records.saveProfile(CharacterProfile(name = "Real sheet fixture"), source = "Synthetic fixture")
                f.records.records.saveAbility(CharacterAbility("STR", score = 12, magicModifier = 4), source = "Synthetic real-sheet fixture")
                f.records.records.saveAbility(CharacterAbility("STR", RemoteStatProtocol.PROFILE_ID, 14, 2,
                    "Separate confirmed magic"), source = "Synthetic disposable fixture")
            }
            val originalReal = runBlocking { f.ledgerDb.recordsDao().characterAbility("local-player", "STR") }
            val command = runBlocking { f.command(revision = 1).also { f.remoteDb.remote().insert(it) } }
            f.render {
                val scope = rememberCoroutineScope()
                val commands by f.remote.commands.collectAsState(initial = emptyList())
                val abilities by f.records.records.characterAbilities.collectAsState(initial = emptyList())
                Page { CharacterOverviewV2(f.records, profileId = RemoteStatProtocol.PROFILE_ID) }
                commands.firstOrNull { it.commandId == command.commandId && it.status == "PENDING" }?.let { row ->
                    RemoteStatReviewOverlay(row, abilities.firstOrNull { it.profileId == RemoteStatProtocol.PROFILE_ID && it.key == "STR" },
                        true, false, onApply = { scope.launch { f.remote.apply(row.commandId) } },
                        onReject = { scope.launch { f.remote.reject(row.commandId) } }, onDismiss = {})
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("remote-apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("remote-apply").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Strength score 18", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Review ability update").assertDoesNotExist()
            compose.onNodeWithTag("ability-STR").assert(hasContentDescription("magical modifier +2", substring = true))
            capture("remote-stat-applied-armor", f.activity.get())
            runBlocking {
                val saved = f.ledgerDb.recordsDao().characterAbility(RemoteStatProtocol.PROFILE_ID, "STR")!!
                assertEquals(18, saved.score); assertEquals(2, saved.magicModifier); assertEquals(2L, saved.revision)
                assertEquals("Separate confirmed magic", saved.notes)
                assertEquals(originalReal, f.ledgerDb.recordsDao().characterAbility("local-player", "STR"))
                assertEquals("APPLIED", f.remoteDb.remote().command(command.commandId)?.status)
            }
        }
    }

    @Test fun conflictingOrDisabledProposalCannotApplyButConflictCanBeRejected() {
        Fixture().use { f ->
            runBlocking { f.remote.setEnabled(true) }
            val command = runBlocking { f.command(revision = 1) }
            val current = CharacterAbility("STR", RemoteStatProtocol.PROFILE_ID, 15, revision = 2)
            var applied = 0; var rejected = 0
            var enabled by mutableStateOf(true)
            f.render { RemoteStatReviewOverlay(command, current, enabled, false, onApply = { applied++ },
                onReject = { rejected++ }, onDismiss = {}) }
            compose.onNodeWithTag("remote-apply").assertIsNotEnabled()
            compose.onNodeWithText("This ability changed", substring = true).assertIsDisplayed()
            capture("remote-stat-conflict", f.activity.get(), modal = true)
            compose.onNodeWithTag("remote-reject").performClick()
            compose.runOnIdle { assertEquals(0, applied); assertEquals(1, rejected); enabled = false }
            compose.onNodeWithTag("remote-reject").assertIsNotEnabled()
            compose.onNodeWithTag("remote-apply").assertIsNotEnabled()
            capture("remote-stat-disabled", f.activity.get(), modal = true)
        }
    }

    @Test fun switchingProfileClosesUnsavedEditorWithoutMovingDraftIntoAnotherCharacter() {
        Fixture().use { f ->
            runBlocking {
                f.remote.setEnabled(true)
                f.records.records.saveProfile(CharacterProfile(name = "Real sheet fixture"), source = "Synthetic fixture")
                f.records.records.saveAbility(CharacterAbility("STR", score = 12, magicModifier = 4), source = "Synthetic real-sheet fixture")
                f.records.records.saveAbility(CharacterAbility("STR", RemoteStatProtocol.PROFILE_ID, 14, 2), source = "Synthetic test fixture")
            }
            val before = runBlocking { f.ledgerDb.recordsDao().allCharacterAbilities() }
            var profileId by mutableStateOf("local-player")
            f.render { Page { CharacterOverviewV2(f.records, profileId = profileId) } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Strength score 12", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ability-STR").performScrollTo().performClick()
            compose.onNodeWithText("Raw ability score (blank = unknown)").performTextReplacement("99")
            compose.runOnIdle { profileId = RemoteStatProtocol.PROFILE_ID }
            compose.onNodeWithText("Edit Strength").assertDoesNotExist()
            compose.onNodeWithTag("ability-STR").assert(hasContentDescription("Strength score 14", substring = true))
            compose.onNodeWithTag("ability-STR").performClick()
            compose.onNodeWithText("Raw ability score (blank = unknown)").assertTextContains("14")
            compose.onNodeWithText("Close").performClick()
            assertEquals(before, runBlocking { f.ledgerDb.recordsDao().allCharacterAbilities() })
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowLargeTextConfirmationKeepsBothDecisionButtonsVisible() {
        Fixture().use { f ->
            runBlocking { f.remote.setEnabled(true) }
            val command = runBlocking { f.command() }
            f.render(fontScale = 1.35f) {
                RemoteStatReviewOverlay(command, null, true, false, onApply = {}, onReject = {}, onDismiss = {})
            }
            val layouts = mutableListOf<TextLayoutResult>()
            val heading = compose.onNodeWithText("Review ability update").fetchSemanticsNode()
            compose.runOnIdle {
                assertTrue(heading.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertEquals(1.35f, layouts.single().layoutInput.density.fontScale, .01f)
            }
            val unknownLayouts = mutableListOf<TextLayoutResult>()
            val unknown = compose.onNodeWithText("Unknown").fetchSemanticsNode()
            compose.runOnIdle {
                assertTrue(unknown.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(unknownLayouts) == true)
                assertEquals("The unknown-value label must remain readable without splitting a word", 1, unknownLayouts.single().lineCount)
            }
            compose.onNodeWithTag("remote-apply").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("remote-reject").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Close").assertIsDisplayed()
            capture("remote-stat-confirmation-360-font135", f.activity.get(), modal = true)
        }
    }
}
