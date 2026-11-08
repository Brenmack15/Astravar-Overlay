package com.yazsras.astravar.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import com.yazsras.astravar.savePresentationCapture
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
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
class ReforgeCharacterUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var captureView: View? = null
    private fun capture(name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle {
            val view = requireNotNull(captureView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val windows = WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                val base = IntArray(2).also { view.getLocationOnScreen(it) }
                repeat(2) {
                    val canvas = Canvas(bitmap); view.draw(canvas)
                    windows.filter { it !== view }.forEach { window ->
                        val position = IntArray(2).also { window.getLocationOnScreen(it) }
                        val parameters = window.layoutParams as? WindowManager.LayoutParams
                        if (parameters != null && parameters.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                            canvas.drawColor(android.graphics.Color.argb((parameters.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
                        canvas.save(); canvas.translate((position[0] - base[0]).toFloat(), (position[1] - base[1]).toFloat())
                        window.draw(canvas); canvas.restore()
                    }
                }
                savePresentationCapture(bitmap, name)
            } finally { bitmap.recycle() }
        }
    }
    private fun fixture() = ReforgeCharacter("synthetic-player", "Synthetic artificer", level = 9,
        baseAbilities = mapOf(Stat.STR to 8, Stat.DEX to 15, Stat.CON to 14, Stat.INT to 21, Stat.WIS to 11, Stat.CHA to 12),
        baseMaxHp = 66, baselineConstitutionModifier = 2, currentHp = 40, temporaryHp = 0, baseAc = 12,
        rules = RuleVersion(classVersion = "legacy-artificer-infusions-2020"), spellcastingClassLevel = 9,
        itemDefinitions = listOf(ItemDefinition("armor-definition", "Synthetic armor", effects = listOf(
            EffectDefinition("strength", "Armor strength", RuleSource("synthetic", "Test fixture", Acceptance.ACCEPTED_HOMEBREW), EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 21, "STR"), requiresAttunement = false),
            EffectDefinition("constitution", "Armor constitution", RuleSource("synthetic", "Test fixture", Acceptance.ACCEPTED_HOMEBREW), EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 16, "CON"), requiresAttunement = false)
        ))), items = listOf(ItemInstance("armor-instance", "armor-definition")))

    private fun withSheet(seed: Boolean = true, campaignSetup: (CampaignLedgerRepository) -> Unit = {},
        block: (ReforgeRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val ledger = CampaignLedgerRepository(legacyDb)
        val localDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val repository = ReforgeRepository(localDb)
        if (seed) runBlocking { repository.create(fixture()) }
        campaignSetup(ledger)
        val previous = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            captureView = controller.get().window.decorView
            controller.get().setContent { AstravarTheme { ReforgeCharacterSheet(ledger, repository) } }
            block(repository)
        } finally {
            captureView = null
            controller.pause().stop().destroy(); localDb.close(); legacyDb.close()
            if (previous == null) System.clearProperty("robolectric.windowManager.useRequestedSize") else System.setProperty("robolectric.windowManager.useRequestedSize", previous)
        }
    }

    @Test fun emptySheetDoesNotInventCharacterOrResources() = withSheet(seed = false) { repository ->
        compose.waitUntil(10000) { compose.onAllNodesWithText("Create character").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create character").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(runBlocking { repository.observeAll().first() }.isEmpty())
    }

    @Test fun newCharacterNeedsOnlyNameAndRetainsUnknownStats() = withSheet(seed = false) { repository ->
        compose.waitUntil(10000) { compose.onAllNodesWithText("Create character").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create character").performClick()
        compose.onNodeWithText("Name", substring = false).performTextInput("Fictional traveler")
        compose.onNodeWithText("Save character").performClick()
        compose.waitUntil(10000) { runBlocking { repository.observeAll().first() }.any { it.character.name == "Fictional traveler" } }
        val character = runBlocking { repository.observeAll().first() }.single().character
        assertEquals(1, character.level)
        assertNull(character.baseMaxHp)
        assertTrue(character.baseAbilities.values.all { it == null })
        assertTrue(character.items.isEmpty())
    }

    @Test fun copyingCampaignItemIsExplicitAndKeepsOriginalIdentityWithoutMagic() {
        var sourceId = ""
        withSheet(campaignSetup = { ledger ->
            sourceId = runBlocking { ledger.create(LedgerDraft(title = "Runed lantern", quantity = 1,
                description = "Mysterious runes, no confirmed effect")).id }
        }) { repository ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Equipment").performScrollTo().performClick()
            compose.onNodeWithText("Add from campaign inventory").performScrollTo().performClick()
            assertTrue(runBlocking { repository.get("synthetic-player") }!!.character.items.none { it.id == sourceId })
            compose.onNodeWithText("Runed lantern").performScrollTo().performClick()
            compose.onNodeWithText("Copy item to character").performClick()
            compose.waitUntil(10000) {
                runBlocking { repository.get("synthetic-player") }!!.character.items.any { it.id == sourceId }
            }
            val character = runBlocking { repository.get("synthetic-player") }!!.character
            assertEquals(1, character.items.single { it.id == sourceId }.quantity)
            assertTrue(character.itemDefinitions.single { it.id == sourceId }.effects.isEmpty())
        }
    }

    @Test fun equipPreviewIsReadOnlyAndConfirmationPreservesDamage() = withSheet { repository ->
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        capture("reforge-native-character-sheet")
        compose.onNodeWithText("Equipment").performScrollTo().performClick()
        compose.onNodeWithText("Equip", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Confirm change").assertExists()
        capture("reforge-native-equipment-preview")
        assertEquals(1L, runBlocking { repository.get("synthetic-player") }!!.revision)
        assertEquals(ItemState.CARRIED, runBlocking { repository.get("synthetic-player") }!!.character.items.single().state)
        compose.onNodeWithText("Confirm change").performClick()
        compose.waitUntil(10000) { runBlocking { repository.get("synthetic-player") }!!.revision == 2L }
        val character = runBlocking { repository.get("synthetic-player") }!!.character
        val sheet = CharacterCalculator.calculate(character)
        assertEquals(21, sheet.abilities.getValue(Stat.STR).effective.value)
        assertEquals(75, sheet.maximumHp.value)
        assertEquals(49, sheet.currentHp)
        assertEquals(26, character.hpDamage)
        assertEquals(8, character.baseAbilities[Stat.STR])
    }

    @Test fun openingDiceDoesNotRollAndOnlyExplicitSavedRollPersists() = withSheet { repository ->
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reforge-STR").performClick()
        compose.onNodeWithText("Roll check").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reforge-roll-now").assertExists()
        assertTrue(runBlocking { repository.rollHistory("synthetic-player").first() }.isEmpty())
        compose.onNodeWithTag("reforge-roll-now").performClick()
        compose.onNodeWithTag("reforge-roll-result").assertExists()
        capture("reforge-native-dice-result")
        assertTrue(runBlocking { repository.rollHistory("synthetic-player").first() }.isEmpty())
        compose.onNodeWithText("Save roll").performScrollTo().performClick()
        compose.waitUntil(10000) { runBlocking { repository.rollHistory("synthetic-player").first() }.size == 1 }
        val roll = runBlocking { repository.rollHistory("synthetic-player").first() }.single()
        assertEquals(-1, roll.components.single().modifier)
        assertTrue(roll.components.single().dice.single().face in 1..20)
        assertEquals(1L, runBlocking { repository.get("synthetic-player") }!!.revision)
    }
    @Test fun nameOnlySetupEditKeepsEquippedHpBaselineAndOutstandingDamage() = withSheet { repository ->
        runBlocking {
            val before = repository.get("synthetic-player")!!
            repository.save(before.character.id, before.revision,
                GameplayRules.equip(before.character, "armor-instance", ItemState.WORN), "Synthetic equip")
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Character setup").performScrollTo().performClick()
        compose.onNodeWithText("Name", substring = false).performTextReplacement("Renamed synthetic artificer")
        compose.onNodeWithText("Save character").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { runBlocking { repository.get("synthetic-player") }!!.revision == 3L }
        val after = runBlocking { repository.get("synthetic-player") }!!.character
        assertEquals("Renamed synthetic artificer", after.name)
        assertEquals(66, after.baseMaxHp)
        assertEquals(2, after.baselineConstitutionModifier)
        assertEquals(26, after.hpDamage)
        assertEquals(49, CharacterCalculator.calculate(after).currentHp)
    }

}
