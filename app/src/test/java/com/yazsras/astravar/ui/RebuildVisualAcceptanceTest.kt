package com.yazsras.astravar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Actual Android/Compose drawings at compact portrait sizes. These are synthetic
 * Room fixtures, never imported character/campaign data. Opt-in PNGs include the
 * Dialog windows; an Activity-only drawing would silently omit in-app overlays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RebuildVisualAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var previousRequestedSize: String? = null

    @Before fun useRealRequestedWindowSizes() {
        previousRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        // Robolectric 4.16 otherwise ignores dialog WRAP_CONTENT requests. This is
        // a scoped shadow configuration, not a product workaround/idling bypass.
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }

    @After fun restoreRequestedWindowSizes() {
        previousRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private val seed get() = Campaign(
        onboarded = true, attuned = true, installedId = "synthetic-core",
        cores = listOf(Core("synthetic-core", "Synthetic crystal")),
        profile = Profile(character = "Arden Vale · UI fixture", level = 6,
            baseDamageDiceCount = 3, elementalSelectorDiceCount = 2)
    )

    private inner class Fixture(populatedCharacter: Boolean = true) : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledgerDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val weaponDb = Room.inMemoryDatabaseBuilder(context, AstravarDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger = CampaignLedgerRepository(ledgerDb)
        val repository = Repository(weaponDb, scope)
        val actions = Actions(repository, scope)
        val preferences = Preferences(context)
        val activity: ActivityController<ComponentActivity>

        init {
            runBlocking {
                repository.mutate("synthetic-ui-fixture", "Synthetic UI fixture") { seed }
                preferences.mode(Ability.ELEMENTAL)
                preferences.elements(listOf(DamageType.LIGHTNING))
                preferences.payloads(emptySet())
                preferences.targeting(RollOptions())
                preferences.aim(30, "", false, null, false)
                preferences.haptics(false)
                if (populatedCharacter) {
                    ledger.records.saveProfile(CharacterProfile(name = "Arden Vale", level = 6,
                        armorClass = 19, currentHp = 32, maximumHp = 44), source = "Synthetic UI acceptance fixture")
                    linkedMapOf("STR" to 10, "DEX" to 14, "CON" to 12, "INT" to 18, "WIS" to 10, "CHA" to 8)
                        .forEach { (key, value) -> ledger.records.saveAbility(
                            CharacterAbility(key = key, score = value, magicModifier = if (key == "DEX") 2 else 0,
                                notes = "Synthetic scoped source; no automatic combat modifier"),
                            source = "Synthetic UI acceptance fixture") }
                }
            }
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        }

        fun render(fontScale: Float = 1f, destination: String = "Armor", content: @Composable Fixture.() -> Unit) {
            activity.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AstravarTheme {
                        Scaffold(containerColor = RebuildPalette.Canvas,
                            topBar = { RebuildAppHeader(false) },
                            bottomBar = { RebuildPrimaryNavigation(destination) {} }) { insets ->
                            Box(Modifier.fillMaxSize().padding(insets)) { content() }
                        }
                    }
                }
            }
        }

        @Composable fun armor() {
            val prefs by preferences.flow.collectAsState(initial = OverlayPrefs(mode = Ability.ELEMENTAL,
                elements = listOf(DamageType.LIGHTNING), haptics = false))
            var wheel by remember { mutableStateOf(false) }
            ArmorOverviewV2(seed, ledger, {}, {}, {}, {}, prefs.elements, { wheel = true })
            if (wheel) ElementWheelV2(seed.profile, prefs.elements, { wheel = false }) { type ->
                scope.launch {
                    preferences.elements(if (type == DamageType.FORCE) emptyList() else listOf(type))
                    preferences.mode(if (type == DamageType.FORCE) Ability.ARCANE else Ability.ELEMENTAL)
                }
            }
        }

        @Composable fun combat(stateOverride: Campaign? = null) {
            val current by repository.state.collectAsState()
            val prefs by preferences.flow.collectAsState(initial = OverlayPrefs(mode = Ability.ELEMENTAL,
                elements = listOf(DamageType.LIGHTNING), haptics = false))
            Dashboard(stateOverride ?: current ?: seed, actions, {}, {}, {}, preferences, prefs)
        }

        override fun close() {
            activity.pause().stop().destroy()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            ledgerDb.close(); weaponDb.close()
        }
    }

    private fun settleUntil(timeout: Long = 15_000, condition: () -> Boolean) {
        compose.waitUntil(timeout) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        compose.waitForIdle()
    }

    /** assertIsDisplayed permits partial visibility; it missed a genuinely clipped square-card label. */
    private fun assertTextFullyVisible(text: String, singleLine: Boolean = false) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            assertTrue("Text layout must be inspectable: $text", action?.invoke(layouts) == true)
            val layout = layouts.single()
            // MultiParagraph may retain the parent's maximum constraint width even
            // when its natural-width Text is measured smaller. For Add Item the
            // diagnostic was paragraph=640px, size=111px, actual ink=110.92578px;
            // hasVisualOverflow flagged that harmless cached width. Check the
            // actual line extents, line count and ancestor clipping instead.
            assertFalse("Text must not overflow vertically: $text", layout.didOverflowHeight)
            assertFalse("Text must not omit lines: $text", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertFalse("Text must not be ellipsized: $text; line=$line", layout.isLineEllipsized(line))
                assertTrue("Text ink must fit its layout: $text; line=$line; left=${layout.getLineLeft(line)}; right=${layout.getLineRight(line)}; width=${layout.size.width}",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("Text line height must fit its layout: $text; line=$line",
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            assertTrue("Text must not be clipped by an ancestor: $text; visible=${node.boundsInRoot.height}, required=${layout.size.height}",
                node.boundsInRoot.height + 1f >= layout.size.height)
            assertTrue("Text must not be clipped horizontally by an ancestor: $text; visible=${node.boundsInRoot.width}, required=${layout.size.width}",
                node.boundsInRoot.width + 1f >= layout.size.width)
            if (singleLine) assertEquals("A short control label must not split in the middle of a word: $text", 1, layout.lineCount)
        }
    }

    private fun capture(name: String, activity: ComponentActivity, modal: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle {
            val decor = activity.window.decorView
            val windows = WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            if (modal) assertTrue("A modal capture must contain its real Android Dialog window", windows.any { it !== decor })
            val directory = System.getProperty("astravar.screenshots") ?: return@runOnIdle
            check(decor.width > 0 && decor.height > 0)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val locations = mutableListOf<String>()
            try {
                val canvas = Canvas(bitmap)
                decor.draw(canvas)
                val base = IntArray(2).also { decor.getLocationOnScreen(it) }
                windows.filter { it !== decor }.forEach { window ->
                    val position = IntArray(2).also { window.getLocationOnScreen(it) }
                    val params = window.layoutParams as? WindowManager.LayoutParams
                    if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                        canvas.drawColor(Color.argb((params.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
                    }
                    canvas.save()
                    canvas.translate((position[0] - base[0]).toFloat(), (position[1] - base[1]).toFloat())
                    window.draw(canvas)
                    canvas.restore()
                    locations += "${window.javaClass.simpleName}: ${window.width}x${window.height} at ${position[0] - base[0]},${position[1] - base[1]}"
                }
                savePresentationCapture(bitmap, name)
                Files.write(Path.of(directory, "$name-windows.txt"),
                    ("Robolectric API 35 native Android drawing; activity ${decor.width}x${decor.height}\n" + locations.joinToString("\n")).toByteArray())
            } finally { bitmap.recycle() }
        }
    }

    @Test(timeout = 90_000) fun armorDashboardAndAbilityEditorPreserveSavedMagicAndCancellation() {
        Fixture().use { fixture ->
            fixture.render { armor() }
            settleUntil { compose.onAllNodesWithText("Arden Vale").fetchSemanticsNodes().isNotEmpty() }
            listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").forEach { compose.onNodeWithTag("ability-$it").assertIsDisplayed() }
            compose.onNodeWithText("Active element").assertIsDisplayed()
            compose.onNodeWithText("Core systems").assertIsDisplayed()
            compose.onNodeWithText("Raw ability score (blank = unknown)").assertDoesNotExist()
            capture("rebuild-armor-400", fixture.activity.get())
            val before = runBlocking { fixture.ledgerDb.recordsDao().characterAbility("local-player", "DEX")!! }
            compose.onNodeWithTag("ability-DEX").performClick()
            compose.onNodeWithText("Edit Dexterity").assertIsDisplayed()
            compose.onNodeWithText("Save ability").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            capture("rebuild-ability-editor", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Raw ability score (blank = unknown)").performTextReplacement("16")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Raw ability score (blank = unknown)").assertTextContains("16")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText("Edit Dexterity").assertDoesNotExist()
            assertEquals(before, runBlocking { fixture.ledgerDb.recordsDao().characterAbility("local-player", "DEX") })
            compose.onNodeWithTag("ability-DEX").performClick()
            compose.onNodeWithText("Raw ability score (blank = unknown)").performTextReplacement("18")
            compose.onNodeWithText("Separate magical modifier").assertTextContains("2")
            compose.onNodeWithText("Save ability").performClick()
            settleUntil { runBlocking { fixture.ledgerDb.recordsDao().characterAbility("local-player", "DEX")?.score == 18 } }
            val saved = runBlocking { fixture.ledgerDb.recordsDao().characterAbility("local-player", "DEX")!! }
            assertEquals(2, saved.magicModifier); assertEquals(4, saved.baseModifier); assertEquals(6, saved.totalModifier)
            assertEquals(0, runBlocking { fixture.weaponDb.dao().rollCount() })
        }
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowArmorKeepsSummaryAbilitiesAndBothPrimaryEntriesVisible() {
        Fixture().use { fixture ->
            fixture.render { armor() }
            settleUntil { compose.onAllNodesWithText("Arden Vale").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Armor Class").assertIsDisplayed()
            compose.onNodeWithText("Astravar ATK").assertIsDisplayed()
            compose.onNodeWithText("Spell Save DC").assertIsDisplayed()
            compose.onNodeWithTag("ability-CHA").assertIsDisplayed()
            compose.onNodeWithText("Active element").assertIsDisplayed()
            compose.onNodeWithText("Capabilities").assertIsDisplayed()
            capture("rebuild-armor-360", fixture.activity.get())
            assertTextFullyVisible("Lightning")
            assertTextFullyVisible("Capabilities")
        }
    }

    @Test(timeout = 90_000) fun elementOverlaySelectsImmediatelyPersistsAndLeavesLockedTypesDisabled() {
        Fixture().use { fixture ->
            fixture.render { armor() }
            settleUntil { compose.onAllNodesWithText("Arden Vale").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Active element").performClick()
            compose.onNodeWithTag("element-wheel").assertIsDisplayed()
            listOf("Fire", "Cold", "Lightning", "Acid", "Poison", "Thunder").forEach {
                compose.onNodeWithContentDescription("$it element").assertIsDisplayed().assertIsEnabled()
            }
            listOf("Necrotic", "Psychic", "Radiant").forEach {
                compose.onNodeWithContentDescription("$it locked element").assertIsNotEnabled()
            }
            compose.onNodeWithContentDescription("Neutral Force").assertIsDisplayed().assertIsEnabled()
            capture("rebuild-element-wheel", fixture.activity.get(), modal = true)
            compose.onNodeWithContentDescription("Fire element").performClick()
            compose.onNodeWithTag("element-wheel").assertDoesNotExist()
            settleUntil { runBlocking { fixture.preferences.flow.first().elements == listOf(DamageType.FIRE) } }
            compose.onNodeWithText("Fire").assertIsDisplayed()
            // A newly constructed reader must see the same persisted choice, not a local UI-only state.
            assertEquals(listOf(DamageType.FIRE), runBlocking { Preferences(fixture.context).flow.first().elements })
            compose.onNodeWithText("Active element").performClick()
            compose.onNodeWithText("Close").performClick()
            assertEquals(listOf(DamageType.FIRE), runBlocking { fixture.preferences.flow.first().elements })
            compose.onNodeWithText("Active element").performClick()
            compose.onNodeWithContentDescription("Neutral Force").performClick()
            settleUntil { runBlocking { fixture.preferences.flow.first().elements.isEmpty() } }
            assertEquals(Ability.ARCANE, runBlocking { fixture.preferences.flow.first().mode })
            assertEquals(seed.profile, fixture.repository.state.value!!.profile)
        }
    }

    @Test(timeout = 90_000) fun combatInitialViewportAndAttackChooserUseRealSupportedSelections() {
        Fixture().use { fixture ->
            fixture.render(destination = "Combat") { combat() }
            compose.onNodeWithText("Roll attack").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            listOf("Normal", "Advantage", "Disadvantage").forEach { compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
            compose.onNodeWithText("Latest result").assertIsDisplayed()
            capture("rebuild-combat-default", fixture.activity.get())
            compose.onAllNodesWithText("Change").onFirst().performClick()
            compose.onNodeWithText("Choose attack").assertIsDisplayed()
            listOf("Elemental shot", "Gauntlet strike", "Charged gauntlet", "Spell-imbued shot", "Spell-imbued strike", "Discharge")
                .forEach { compose.onAllNodesWithText(it).onLast().assertIsDisplayed() }
            listOf("Gauntlet strike", "Charged gauntlet", "Spell-imbued strike").forEach { name ->
                compose.onNode(isNotEnabled() and hasAnyDescendant(hasText(name)))
                    .assertIsDisplayed().assertIsNotEnabled().assertHasNoClickAction()
            }
            capture("rebuild-attack-selection", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Spell-imbued shot").performClick()
            settleUntil { runBlocking { fixture.preferences.flow.first().mode == Ability.SPELL } }
            compose.onNodeWithText("Choose attack").assertDoesNotExist()
            compose.onNodeWithText(Ability.SPELL.title).assertIsDisplayed()
            compose.onAllNodesWithText("Change").onFirst().performClick()
            compose.onNodeWithText("Existing weapon modes").performScrollTo().performClick()
            compose.onNodeWithText(Ability.SHATTER.title).performScrollTo().assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithText(Ability.ARCANE.title).performScrollTo().assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithText(Ability.ARCANE.title).performClick()
            settleUntil { runBlocking { fixture.preferences.flow.first().mode == Ability.ARCANE } }
            compose.onNodeWithText("Choose attack").assertDoesNotExist()
            compose.onNodeWithText(Ability.ARCANE.title).assertIsDisplayed()
            capture("rebuild-combat-arcane", fixture.activity.get())
        }
    }

    @Test(timeout = 90_000) fun automaticAttackPersistsDiceResetsModeAndOpensResultDetails() {
        Fixture().use { fixture ->
            fixture.render(destination = "Combat") { combat() }
            compose.onNodeWithText("Advantage").performClick()
            settleUntil { runBlocking { fixture.preferences.flow.first().advantage } }
            compose.onNodeWithText("Roll attack").performClick()
            settleUntil { fixture.repository.state.value?.pending?.lastOrNull()?.rolled != null && !fixture.actions.busy }
            val saved = fixture.repository.state.value!!.pending.single()
            val rolled = requireNotNull(saved.rolled)
            assertEquals(RollMode.ADVANTAGE, rolled.mode)
            assertEquals(2, rolled.attackFaces.size)
            DiceRoller.validate(saved.plan, rolled)
            assertFalse(runBlocking { fixture.preferences.flow.first().advantage })
            assertFalse(runBlocking { fixture.preferences.flow.first().disadvantage })
            assertEquals(1, runBlocking { fixture.weaponDb.dao().rollCount() })
            compose.onNodeWithText("ATTACK").assertIsDisplayed()
            compose.onNodeWithText("DAMAGE").assertIsDisplayed()
            compose.onNodeWithText("HIT").assertDoesNotExist()
            compose.onNodeWithText("MISS").assertDoesNotExist()
            capture("rebuild-combat-result", fixture.activity.get())
            compose.onNodeWithText("Latest result").performClick()
            compose.onNodeWithText("Roll details").assertIsDisplayed()
            compose.onNodeWithText("Copy full result").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            capture("rebuild-result-breakdown", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Copy full result").performClick()
            val clipboard = fixture.context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(shotSummary(saved), clipboard.primaryClip!!.getItemAt(0).text.toString())
            compose.onNodeWithText("Close").performClick()
            assertEquals(1, runBlocking { fixture.weaponDb.dao().rollCount() })
        }
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowLargerTextKeepsCombatRollModesAndPrimaryActionReachable() {
        Fixture().use { fixture ->
            fixture.render(fontScale = 1.3f, destination = "Combat") { combat() }
            compose.onNodeWithText("Roll attack").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            listOf("Normal", "Advantage", "Disadvantage").forEach {
                compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
            compose.onNodeWithText("Latest result").assertIsDisplayed()
            capture("rebuild-combat-360-font130", fixture.activity.get())
            listOf("Normal", "Adv.", "Disadv.").forEach { assertTextFullyVisible(it, singleLine = true) }
        }
    }

    @Test(timeout = 90_000) fun emptyArmorNeverSeedsInventedCharacterValues() {
        Fixture(populatedCharacter = false).use { fixture ->
            fixture.render { ArmorOverviewV2(Campaign(), ledger, {}, {}, {}, {}) }
            compose.onNodeWithText("Your character").assertIsDisplayed()
            compose.onNodeWithContentDescription("Strength score unknown", substring = true).assertIsDisplayed()
            capture("rebuild-armor-empty", fixture.activity.get())
            assertTrue(runBlocking { fixture.ledgerDb.recordsDao().allCharacterProfiles().isEmpty() })
            assertTrue(runBlocking { fixture.ledgerDb.recordsDao().allCharacterAbilities().isEmpty() })
        }
    }

    @Test(timeout = 90_000) fun saveOnlyDischargeHasNoRollModeAndCostConfirmationCanBeCancelled() {
        Fixture().use { fixture ->
            runBlocking {
                fixture.preferences.mode(Ability.DISCHARGE)
                fixture.preferences.aim(30, "Synthetic empty test point", false, null, false)
            }
            fixture.render(destination = "Combat") { combat() }
            settleUntil { compose.onAllNodesWithText("Roll discharge").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Roll discharge").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            listOf("Normal", "Advantage", "Disadvantage").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            capture("rebuild-combat-discharge", fixture.activity.get())
            val before = fixture.repository.state.value!!
            val plan = Engine.plan(before, "synthetic-save-only", Ability.DISCHARGE,
                elements = listOf(DamageType.LIGHTNING), point = "Synthetic empty test point")
            val rolled = DiceRoller.roll(plan, die = { 2 })
            assertTrue(rolled.attackFaces.isEmpty())
            assertNull(rolled.attackTotal)
            compose.onNodeWithText("Roll discharge").performClick()
            compose.onNodeWithText("Confirm discharge").assertIsDisplayed()
            compose.onNodeWithText("Roll now").assertIsDisplayed().assertIsEnabled()
            capture("rebuild-discharge-confirmation", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Confirm discharge").assertDoesNotExist()
            assertEquals(before, fixture.repository.state.value)
            assertEquals(0, runBlocking { fixture.weaponDb.dao().rollCount() })
        }
    }
}
