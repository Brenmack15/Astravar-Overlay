package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class CombatProfileV21Test {
    @get:Rule val compose = createEmptyComposeRule()

    private inner class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AstravarDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = Repository(db, scope)
        val actions = Actions(repo, scope)
        val preferences = Preferences(context)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        init {
            runBlocking {
                repo.mutate(label = "Synthetic melee configuration") {
                    Campaign(onboarded = true, attuned = true, installedId = "fixture-nexus",
                        cores = listOf(Core("fixture-nexus", "Synthetic nexus")),
                        profile = Profile(character = "Synthetic player", level = 6,
                            melee = MeleeProfile(7, Dice(2, 6, 3), 0, 2, 4, "Synthetic accepted rule", chargedDamageType = DamageType.FORCE, guardianAttackBonus = 6)))
                }
                preferences.mode(Ability.ARCANE)
                preferences.elements(emptyList())
                preferences.payloads(emptySet())
                preferences.targeting(RollOptions())
                preferences.aim(30, "", false, null, false)
                preferences.haptics(false)
            }
        }
        override fun close() {
            activity.pause().stop().destroy()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    @Test(timeout = 90_000) fun selectedGauntletUsesFiveFootReachAndPersistsChargeConsumptionWithRoll() {
        Fixture().use { f ->
            f.activity.get().setContent { AstravarTheme {
                val s by f.repo.state.collectAsState()
                val prefs by f.preferences.flow.collectAsState(initial = OverlayPrefs())
                s?.let { Dashboard(it, f.actions, {}, {}, {}, f.preferences, prefs) }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Change").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Change").onFirst().performClick()
            compose.onNodeWithText("Gauntlet strike").assertHasClickAction().performClick()
            // Selecting melee persists mode and aim in separate suspend edits.
            // Wait for the completed action rather than its intermediate mode emission.
            compose.waitUntil(10_000) {
                val selected = runBlocking { f.preferences.flow.first() }
                selected.mode == Ability.GAUNTLET && selected.distance == 5 && !f.actions.busy
            }
            assertEquals(5, runBlocking { f.preferences.flow.first().distance })
            compose.onNodeWithText("Prepare charged strike").performScrollTo().performClick()
            compose.waitUntil(10_000) { f.repo.state.value?.gauntletCharge != null && !f.actions.busy }
            compose.onNodeWithText("Roll attack").performClick()
            compose.waitUntil(10_000) { f.repo.state.value?.pending?.isNotEmpty() == true && !f.actions.busy }
            val after = runBlocking { BackupCodec.decode(f.repo.export()).campaign }
            val shot = after.pending.single()
            assertEquals(Ability.GAUNTLET, shot.plan.ability)
            assertEquals(5, shot.plan.distance)
            assertEquals(7, shot.plan.attackBonus)
            assertNotNull(shot.plan.chargedGauntlet)
            assertNotNull(shot.rolled)
            assertNull(after.gauntletCharge)
            assertTrue(after.turn.bonus)
            DiceRoller.validate(shot.plan, shot.rolled!!)
        }
    }

    @Test(timeout = 90_000) fun sourcedAdvancedFormulaPersistsWithoutBypassingUnlockOrChangingOldRoll() {
        Fixture().use { f ->
            val existing = runBlocking { f.repo.mutate(label = "Synthetic previous roll") { current ->
                DiceRoller.fire(current, Engine.plan(current, "previous-fixture-roll", Ability.ARCANE), die = { 2 })
            } }.pending.single()
            f.activity.get().setContent { AstravarTheme {
                val state by f.repo.state.collectAsState()
                Column(Modifier.verticalScroll(rememberScrollState())) { state?.let { ElementDamageProfilesEditor(it, f.actions) } }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Radiant").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Radiant").performScrollTo().performClick()
            compose.onNodeWithText("Dice count").performTextReplacement("3")
            compose.onNodeWithText("Die size (4, 6, 8, 10, 12 or 20)").performScrollTo().performTextReplacement("6")
            compose.onNodeWithText("Accepted flat bonus").performScrollTo().performTextReplacement("1")
            compose.onNodeWithText("Accepted ruling / source reference").performScrollTo().performTextReplacement("Synthetic future formula")
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Save formula").performScrollTo().performClick()
            compose.waitUntil(10_000) { f.repo.state.value?.profile?.elementDamageProfiles?.containsKey(DamageType.RADIANT) == true && !f.actions.busy }
            val after = runBlocking { BackupCodec.decode(f.repo.export()).campaign }
            assertEquals(Dice(3, 6, 1), after.profile.elementDamageProfiles.getValue(DamageType.RADIANT).dice)
            assertTrue(after.profile.elementDamageProfiles.getValue(DamageType.RADIANT).approved)
            assertFalse(DamageType.RADIANT in Engine.elements(after.profile))
            assertEquals(existing, after.pending.single())
            assertThrows(IllegalArgumentException::class.java) { Engine.plan(after, "locked-fixture", Ability.ELEMENTAL, elements = listOf(DamageType.RADIANT)) }
        }
    }
}
