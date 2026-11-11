package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class GenesisCreationUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var captureActivity: ComponentActivity? = null
    private fun capture(name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows("genesis-$name", requireNotNull(captureActivity)) }
    }

    private fun editor(content: @Composable () -> Unit, block: () -> Unit) {
        val previous = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            captureActivity = controller.get()
            controller.get().setContent { AstravarTheme { content() } }
            block()
        } finally {
            captureActivity = null
            controller.pause().stop().destroy()
            if (previous == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", previous)
        }
    }

    private fun section(name: String) = compose.onNode(hasText(name) and hasClickAction()).performScrollTo().performClick()

    @Test fun guidedCharacterSavesIdentityWithoutInventingStatisticsOrClassRules() {
        var saved: ReforgeCharacter? = null
        editor({ ReforgeCharacterEditor(null, {}, { saved = it }) }) {
            compose.onNodeWithText("Current HP (blank = unknown)").assertDoesNotExist()
            compose.onNodeWithText("Save character").assertDoesNotExist()
            compose.onNodeWithText("Name").performTextInput("Copper Vale")
            compose.onNodeWithText("Species").performScrollTo().performTextInput("Gnome")
            compose.onNodeWithText("Class").performScrollTo().performTextInput("Wizard")
            capture("character-identity-editor")
            compose.onNodeWithText("Review & save").performClick()
            capture("character-review-editor")
            compose.onNodeWithText("Save character").performClick()
            val result = requireNotNull(saved)
            assertEquals("Copper Vale", result.name)
            assertEquals("Gnome", result.species)
            assertEquals("Wizard", result.className)
            assertEquals(1, result.level)
            assertNull(result.currentHp)
            assertNull(result.baseMaxHp)
            assertNull(result.spellcastingClassLevel)
            assertTrue(result.spellSlots.isEmpty())
            assertTrue(result.baseAbilities.values.all { it == null })
            assertTrue(result.features.isEmpty())
        }
    }

    @Test fun invalidHealthDoesNotCommitAndReviewCanReturnDirectlyToTheField() {
        var saves = 0
        editor({ ReforgeCharacterEditor(null, {}, { saves++ }) }) {
            compose.onNodeWithText("Name").performTextInput("Invalid health fixture")
            section("Health & defense")
            compose.onNodeWithText("Unequipped maximum HP").performTextInput("10")
            compose.onNodeWithText("Current HP (blank = unknown)").performScrollTo().performTextInput("11")
            compose.onNodeWithText("Review & save").performClick()
            compose.onNodeWithText("Save character").performClick()
            compose.onNodeWithText("Current HP exceeds the new maximum").performScrollTo().assertIsDisplayed()
            assertEquals(0, saves)
            compose.onNodeWithText("Edit Health & defense").performScrollTo().performClick()
            compose.onNodeWithText("Current HP (blank = unknown)").performScrollTo().performTextReplacement("9")
            compose.onNodeWithText("Review & save").performClick()
            compose.onNodeWithText("Save character").performClick()
            assertEquals(1, saves)
        }
    }

    @Test fun creationDraftAndReviewPageSurviveRecreationAndRetriesKeepOneIdentity() {
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        val ids = mutableListOf<String>()
        editor({
            if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ReforgeCharacterEditor(null, {}, { ids += it.id; error("Synthetic save retry") })
            }
        }) {
            compose.onNodeWithText("Name").performTextInput("Returning draft")
            section("Ability scores")
            compose.onNodeWithText("INT").performTextInput("18")
            compose.onNodeWithText("Review & save").performClick()
            compose.onNodeWithText("Save character").performClick()
            var bundle: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { bundle = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(bundle) { true }; visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("Save character").assertIsDisplayed().performClick()
            assertEquals(2, ids.size)
            assertEquals(ids[0], ids[1])
            compose.onNodeWithText("Returning draft").assertExists()
            compose.onNodeWithText("Edit Ability scores").performScrollTo().performClick()
            compose.onNodeWithText("INT").assertTextContains("18")
        }
    }

    @Test fun itemReviewRetainsOwnerAndRequiresMechanicalApproval() {
        var saved: ReforgeCharacter? = null
        val owner = ReforgeCharacter("fixture-owner", "Fixture owner")
        editor({ ReforgeItemEditor(owner, {}, { saved = it }) }) {
            compose.onNodeWithText("Inventory owner · Fixture owner").assertExists()
            compose.onNodeWithText("Item name").performTextInput("Amber bracers")
            section("Effects")
            compose.onNodeWithText("Add a structured effect").performScrollTo().performClick()
            compose.onNodeWithText("Effect name").performScrollTo().performTextInput("Strength floor")
            compose.onNodeWithText("Value").performScrollTo().performTextInput("19")
            compose.onNodeWithText("Add effect to review").performScrollTo().performClick()
            compose.onNodeWithText("Review & save").performClick()
            compose.onNodeWithText("Save reviewed item").assertIsNotEnabled()
            compose.onNodeWithText("I reviewed these mechanics").performScrollTo().performClick()
            capture("item-reviewed-effects")
            compose.onNodeWithText("Save reviewed item").assertIsEnabled().performClick()
            val result = requireNotNull(saved)
            assertEquals(owner.id, result.id)
            assertEquals(1, result.items.size)
            assertFalse(result.items.single().attuned)
            assertEquals(ItemState.CARRIED, result.items.single().state)
            assertEquals(1, result.itemDefinitions.single().effects.size)
            assertNull(CharacterCalculator.calculate(result).abilities.getValue(Stat.STR).effective.value)
        }
    }

    @Test fun cantripDoesNotExposeIrrelevantSlotToggleAndSavesWithoutSlots() {
        var saved: ReforgeCharacter? = null
        val owner = ReforgeCharacter("fixture-caster", "Fixture caster")
        editor({ ReforgeSpellEditor(owner, {}, { saved = it }) }) {
            compose.onNodeWithText("Name").performTextInput("Small light")
            compose.onNodeWithText("Level (0 = cantrip)").performTextInput("0")
            section("Casting")
            compose.onNodeWithText("Requires a spell slot").assertDoesNotExist()
            compose.onNodeWithText("Cantrip · no spell slot required").assertExists()
            compose.onNodeWithText("Review & save").performClick()
            compose.onNodeWithText("Save spell").performClick()
            val spell = requireNotNull(saved).spells.single()
            assertEquals(0, spell.level)
            assertFalse(spell.slotRequired)
            assertTrue(requireNotNull(saved).spellSlots.isEmpty())
        }
    }

    @Test fun dirtyCancelKeepsDraftUntilExplicitDiscard() {
        var dismissed = false
        editor({ ReforgeResourceEditor(ReforgeCharacter("resource-owner", "Owner"), { dismissed = true }, {}) }) {
            compose.onNodeWithText("Capacity").performTextInput("4")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            assertFalse(dismissed)
            compose.onNodeWithText("Capacity").assertTextContains("4")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard").performClick()
            assertTrue(dismissed)
        }
    }

    @Test fun asynchronousSaveFailureRemainsVisibleInsideEditorAndAllowsRetry() {
        val feedback = mutableStateOf(GenesisEditorFeedback())
        val ids = mutableListOf<String>()
        editor({
            CompositionLocalProvider(LocalGenesisEditorFeedback provides feedback.value) {
                ReforgeCharacterEditor(null, {}, { ids += it.id; feedback.value = GenesisEditorFeedback(busy = true) })
            }
        }) {
            compose.onNodeWithText("Name").performTextInput("Retry fixture")
            compose.onNodeWithText("Review & save").performClick()
            compose.onNodeWithText("Save character").performClick()
            compose.onNodeWithText("Save character").assertIsNotEnabled()
            compose.onNodeWithText("Close").assertIsNotEnabled()
            compose.runOnIdle { feedback.value = GenesisEditorFeedback(error = "Storage unavailable; original record retained") }
            compose.onNodeWithText("Storage unavailable; original record retained").assertIsDisplayed()
            compose.onNodeWithText("Save character").assertIsEnabled().performClick()
            assertEquals(2, ids.size)
            assertEquals(ids[0], ids[1])
        }
    }

    @Test fun enlargedTextKeepsReviewSaveAndBackReachable() {
        var saved: ReforgeCharacter? = null
        editor({
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                ReforgeCharacterEditor(null, {}, { saved = it })
            }
        }) {
            compose.onNodeWithText("Name").performScrollTo().performTextInput("Large text fixture")
            compose.onNodeWithText("Review & save").assertIsDisplayed().performClick()
            compose.onNodeWithText("Back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            capture("character-review-font-2")
            compose.onNodeWithText("Save character").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals("Large text fixture", requireNotNull(saved).name)
        }
    }

    @Test fun companionCreationDefaultsToExistingActiveOwnerAndPersistsItsAssociation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val repository = CampaignLedgerRepository(db)
        val owner = CharacterProfile(id = "genesis-owner", name = "Genesis owner")
        runBlocking {
            repository.records.saveProfile(owner, source = "Fictional test")
            ReforgePreferences.open(context).selectProfile(owner.id)
        }
        try {
            editor({ PartyPageV2(repository, null, openInventory = {}) }) {
                compose.waitUntil(10_000) { compose.onAllNodes(hasText("+ Add companion") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("+ Add companion").performClick()
                compose.onNodeWithText("Companion name").performTextInput("Bronze finch")
                compose.onNodeWithText("Review & save").performClick()
                capture("companion-owner-review")
                compose.onNodeWithText("Create companion").performClick()
                compose.waitUntil(10_000) { runBlocking { repository.records.companionStates.first() }.any { it.name == "Bronze finch" } }
                val companion = runBlocking { repository.records.companionStates.first() }.single()
                val link = runBlocking { repository.records.links.first() }.single()
                assertEquals(companion.companionId, link.fromId)
                assertEquals(owner.id, link.toId)
                assertEquals("Companion of", link.label)
                assertNull(runBlocking { repository.records.characterProfiles.first() }.first { it.id == companion.companionId }.currentHp)
            }
        } finally { db.close() }
    }
}
