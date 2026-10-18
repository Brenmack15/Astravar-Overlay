package com.yazsras.astravar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.savePresentationCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Regression coverage for presentation drafts versus confirmed resource execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CombatDraftSafetyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var oldRequestedSize: String? = null
    @Before fun useRequestedWindowSizes() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreRequestedWindowSizes() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private fun template(id: String, name: String, type: DamageType) = Template(
        id, name, 1, 1, listOf(TypedDice(Dice(1, 6), type)), type, true,
        "Synthetic approved instantaneous test fixture"
    )

    private inner class Fixture(spellSelection: Boolean = false) : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AstravarDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = Repository(db, scope)
        val preferences = Preferences(context)
        val actions = Actions(repository, scope)
        val stored = template("synthetic-stored", "Synthetic Spark", DamageType.LIGHTNING)
        val immediate = template("synthetic-immediate", "Synthetic Frost", DamageType.COLD)
        val retained = template("synthetic-retained", "Synthetic Ember", DamageType.FIRE)
        val selectedPayload = Payload("synthetic-selected-payload", stored, SourceKind.SLOT, "synthetic-core", 0)
        val retainedPayload = Payload("synthetic-retained-payload", retained, SourceKind.SLOT, "synthetic-core", 0)
        val initial: Campaign
        val initialPreferences: OverlayPrefs
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()

        init {
            initial = runBlocking {
                repository.mutate("synthetic-combat-safety-fixture", "Synthetic combat safety fixture") {
                    Campaign(onboarded = true, attuned = true, installedId = "synthetic-core",
                        profile = Profile(character = "Synthetic UI fixture", level = 6,
                            capacityOverride = 3, releaseOverride = 2),
                        cores = listOf(Core("synthetic-core", "Synthetic crystal", payloads = listOf(selectedPayload, retainedPayload))),
                        templates = listOf(stored, immediate, retained), slots = mapOf(1 to 3))
                }
            }
            runBlocking {
                preferences.mode(if (spellSelection) Ability.SPELL else Ability.ARCANE)
                preferences.elements(emptyList())
                preferences.payloads(if (spellSelection) setOf(selectedPayload.id) else emptySet())
                preferences.targeting(RollOptions())
                preferences.aim(30, "", false, if (spellSelection) immediate.id else null, false)
                preferences.manualOverrides(true)
                preferences.haptics(false)
            }
            initialPreferences = runBlocking { preferences.flow.first() }
            activity.get().setContent {
                val current by repository.state.collectAsState()
                val prefs by preferences.flow.collectAsState(initial = initialPreferences)
                AstravarTheme {
                    // Match the real shell's app header, system insets and bottom navigation.
                    Scaffold(containerColor = RebuildPalette.Canvas,
                        topBar = { RebuildAppHeader(false) },
                        bottomBar = { RebuildPrimaryNavigation("Combat") {} }) { insets ->
                        Box(Modifier.fillMaxSize().padding(insets)) {
                            Dashboard(current ?: initial, actions, {}, {}, {}, preferences, prefs)
                        }
                    }
                }
            }
        }

        override fun close() {
            activity.pause().stop().destroy()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    private fun settleUntil(condition: () -> Boolean) {
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        compose.waitForIdle()
    }

    private fun captureShell(fixture: Fixture, name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle {
            val decor = fixture.activity.get().window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try { decor.draw(Canvas(bitmap)); savePresentationCapture(bitmap, name) }
            finally { bitmap.recycle() }
        }
    }

    @Test(timeout = 90_000) fun discardingOptionsCannotChangeTheNextCommittedRoll() {
        Fixture().use { fixture ->
            settleUntil { fixture.repository.state.value != null }
            compose.onNodeWithText("Roll attack").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Latest result").assertIsDisplayed()
            captureShell(fixture, "rebuild-full-shell-combat-safety")
            val eventsBefore = runBlocking { fixture.db.dao().eventCount() }
            compose.onNodeWithText("Options").performClick()
            compose.onNodeWithText("Distance in feet").performScrollTo().performTextReplacement("180")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            assertEquals(30, runBlocking { fixture.preferences.flow.first().distance })
            assertEquals(eventsBefore, runBlocking { fixture.db.dao().eventCount() })
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText("Roll attack").performClick()
            settleUntil { fixture.repository.state.value?.pending?.singleOrNull()?.rolled != null && !fixture.actions.busy }
            val pending = fixture.repository.state.value!!.pending.single()
            assertEquals(30, pending.plan.distance)
            assertFalse(pending.plan.disadvantage)
            assertEquals(RollMode.NORMAL, pending.rolled!!.mode)
            assertEquals(1, pending.rolled!!.attackFaces.size)
            assertEquals(30, runBlocking { fixture.preferences.flow.first().distance })
            assertEquals(eventsBefore + 1, runBlocking { fixture.db.dao().eventCount() })
            // The ordinary attack cannot spend either stored payload or any spell slot.
            assertEquals(fixture.initial.cores, fixture.repository.state.value!!.cores)
            assertEquals(fixture.initial.slots, fixture.repository.state.value!!.slots)
        }
    }

    @Test(timeout = 90_000) fun clearingTemporarySpellSelectionClearsBothKindsWithoutSpendingAnything() {
        Fixture(spellSelection = true).use { fixture ->
            settleUntil { fixture.repository.state.value != null }
            val before = fixture.repository.state.value!!
            val eventsBefore = runBlocking { fixture.db.dao().eventCount() }
            assertEquals(setOf(fixture.selectedPayload.id), runBlocking { fixture.preferences.flow.first().selectedPayloads })
            assertEquals(fixture.immediate.id, runBlocking { fixture.preferences.flow.first().immediateId })
            compose.onNodeWithText("Spell payload").performClick()
            compose.onNodeWithText("Clear temporary selection").performScrollTo().performClick()
            settleUntil {
                val prefs = runBlocking { fixture.preferences.flow.first() }
                prefs.selectedPayloads.isEmpty() && prefs.immediateId == null && !fixture.actions.busy
            }
            compose.onNodeWithText("Select spell").assertIsDisplayed()
            assertEquals(before, fixture.repository.state.value)
            assertEquals(eventsBefore, runBlocking { fixture.db.dao().eventCount() })
            assertEquals(0, runBlocking { fixture.db.dao().rollCount() })
            assertEquals(listOf(fixture.selectedPayload.id, fixture.retainedPayload.id), fixture.repository.state.value!!.installed!!.payloads.map { it.id })
            assertEquals(mapOf(1 to 3), fixture.repository.state.value!!.slots)
        }
    }

    @Test(timeout = 90_000) fun externalCommitClearsTemporarySelectionsOnlyAfterOneConfirmedExpenditure() {
        Fixture(spellSelection = true).use { fixture ->
            settleUntil { fixture.repository.state.value != null }
            val eventsBefore = runBlocking { fixture.db.dao().eventCount() }
            compose.onNodeWithText("Options").performClick()
            compose.onNodeWithText("Use external dice").performScrollTo().performClick()
            compose.onNodeWithText("Confirm spell shot").assertIsDisplayed()
            // Merely opening the review cannot consume magic or clear the temporary selection.
            assertEquals(fixture.initial.cores, fixture.repository.state.value!!.cores)
            assertEquals(mapOf(1 to 3), fixture.repository.state.value!!.slots)
            assertEquals(setOf(fixture.selectedPayload.id), runBlocking { fixture.preferences.flow.first().selectedPayloads })
            assertEquals(fixture.immediate.id, runBlocking { fixture.preferences.flow.first().immediateId })
            assertEquals(eventsBefore, runBlocking { fixture.db.dao().eventCount() })
            compose.onNodeWithText("Commit").performClick()
            settleUntil {
                val prefs = runBlocking { fixture.preferences.flow.first() }
                fixture.repository.state.value?.pending?.size == 1 && prefs.selectedPayloads.isEmpty() &&
                    prefs.immediateId == null && !prefs.manualSlot && !fixture.actions.busy
            }
            val after = fixture.repository.state.value!!
            val pending = after.pending.single()
            assertEquals(Ability.SPELL, pending.plan.ability)
            assertEquals(listOf(fixture.selectedPayload.id), pending.plan.selectedPayloads.map { it.id })
            assertEquals(fixture.immediate.id, pending.plan.immediate!!.id)
            assertNull(pending.rolled)
            assertNull(pending.result)
            assertEquals(mapOf(1 to 2), after.slots)
            assertEquals(listOf(fixture.retainedPayload), after.installed!!.payloads)
            assertEquals(eventsBefore + 1, runBlocking { fixture.db.dao().eventCount() })
            assertEquals(1, runBlocking { fixture.db.dao().rollCount() })
            assertEquals(Ability.SPELL, runBlocking { fixture.preferences.flow.first().mode })
        }
    }
}
