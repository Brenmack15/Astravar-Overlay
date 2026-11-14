package com.yazsras.astravar.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
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
class ReforgeCharacterUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var captureActivity: ComponentActivity? = null
    private fun capture(name: String) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, requireNotNull(captureActivity)) }
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
        character: ReforgeCharacter = fixture(), localSetup: suspend (ReforgeRepository) -> Unit = {},
        companionProfileId: String? = null, initialSection: String = "Sheet", onBackToCompanions: (() -> Unit)? = null,
        fontScale: Float = 1f,
        block: (ReforgeRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val ledger = CampaignLedgerRepository(legacyDb)
        val localDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val repository = ReforgeRepository(localDb)
        runBlocking {
            if (seed) repository.create(character)
            localSetup(repository)
        }
        campaignSetup(ledger)
        val previous = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            captureActivity = controller.get()
            controller.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    AstravarTheme {
                        ReforgeCharacterSheet(ledger, repository, initialSection = initialSection,
                            companionProfileId = companionProfileId, onBackToCompanions = onBackToCompanions)
                    }
                }
            }
            block(repository)
        } finally {
            captureActivity = null
            controller.pause().stop().destroy(); localDb.close(); legacyDb.close()
            if (previous == null) System.clearProperty("robolectric.windowManager.useRequestedSize") else System.setProperty("robolectric.windowManager.useRequestedSize", previous)
        }
    }

    private fun actorPreferences() = ReforgePreferences.open(ApplicationProvider.getApplicationContext())

    @Test fun pinnedCompanionOpensCombatWithoutSwitchingTheMainActor() {
        val main = fixture()
        val companion = ReforgeCharacter("fictional-winged-ally", "Fictional winged ally", currentHp = 9,
            attacks = listOf(AttackDefinition("pinion-strike", "Pinion strike", fixedAttackBonus = 4)))
        var returned = false
        withSheet(character = main, companionProfileId = companion.id, initialSection = "Combat",
            onBackToCompanions = { returned = true }, localSetup = { repository ->
                actorPreferences().selectProfile(main.id)
                repository.create(companion)
            }) { repository ->
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Pinion strike").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(companion.name).assertExists()
            compose.onNodeWithText(main.name).assertDoesNotExist()
            compose.onNodeWithText("Turn economy").assertExists()
            compose.onNodeWithText("Switch character").assertDoesNotExist()
            assertEquals(main.id, runBlocking { actorPreferences().activeProfileId.first() })
            assertEquals(1L, runBlocking { repository.get(companion.id) }!!.revision)
            compose.onNodeWithText("Back to companions").assertIsDisplayed().performClick()
            assertTrue(returned)
        }
    }

    @Test fun pinnedCompanionAdoptsOnlyItsExplicitlyReviewedSource() {
        val main = fixture()
        val source = CharacterProfile(id = "fictional-source-ally", name = "Fictional source ally", level = 2,
            currentHp = 8, maximumHp = 12, armorClass = 13)
        lateinit var campaign: CampaignLedgerRepository
        withSheet(character = main, companionProfileId = source.id, localSetup = {
            actorPreferences().selectProfile(main.id)
        }, campaignSetup = { ledger ->
            campaign = ledger
            runBlocking {
                ledger.records.saveProfile(source, source = "Synthetic companion profile")
                ledger.records.saveProfile(CharacterProfile(id = "fictional-other-source", name = "Fictional other source"),
                    source = "Synthetic unrelated profile")
            }
        }) { repository ->
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Review campaign profile") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(main.name).assertDoesNotExist()
            compose.onNodeWithText("Fictional other source").assertDoesNotExist()
            val original = runBlocking { campaign.readCharacterSnapshot(source.id) }
            compose.onNode(hasText("Review campaign profile") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Review baselines & create sheet").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(runBlocking { repository.get(source.id) })
            compose.onNodeWithText("Review baselines & create sheet").assertIsNotEnabled()
            compose.onNodeWithText("I will review training, actions and current resources before relying on this sheet")
                .performScrollTo().performClick()
            compose.onNodeWithText("Review baselines & create sheet").performScrollTo().performClick()
            compose.onNodeWithText("Save character").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(source.id) } != null }
            val saved = runBlocking { repository.get(source.id) }!!
            assertEquals(source.id, saved.origin.sourceProfileId)
            assertEquals(source.currentHp, saved.character.currentHp)
            assertEquals(original, runBlocking { campaign.readCharacterSnapshot(source.id) })
            assertNull(runBlocking { repository.get("fictional-other-source") })
            assertEquals(main.id, runBlocking { actorPreferences().activeProfileId.first() })
        }
    }

    @Test fun missingPinnedCompanionDoesNotOpenTheMainActorOrOfferToCreateAReplacement() =
        withSheet(companionProfileId = "fictional-missing-ally") { repository ->
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Companion sheet unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(fixture().name).assertDoesNotExist()
            compose.onNodeWithText("Review campaign profile").assertDoesNotExist()
            compose.onNodeWithText("Create character").assertDoesNotExist()
            assertEquals(fixture().id, runBlocking { repository.observeAll().first() }.single().character.id)
        }

    @Test fun archivedPinnedCompanionRequiresExplicitRestoreAndNeverFallsBack() {
        val main = fixture()
        val companion = ReforgeCharacter("fictional-archived-ally", "Fictional archived ally", currentHp = 7)
        withSheet(character = main, companionProfileId = companion.id, localSetup = { repository ->
            actorPreferences().selectProfile(main.id)
            val saved = repository.create(companion)
            repository.setArchived(saved.character, true)
        }) { repository ->
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Restore playable sheet").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(main.name).assertDoesNotExist()
            compose.onNodeWithTag("reforge-STR").assertDoesNotExist()
            assertTrue(runBlocking { repository.get(companion.id) }!!.character.archived)
            compose.onNodeWithText("Restore playable sheet").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
            val restored = runBlocking { repository.get(companion.id) }!!
            assertEquals(3L, restored.revision)
            assertFalse(restored.character.archived)
            assertEquals(companion.currentHp, restored.character.currentHp)
            assertEquals(main.id, runBlocking { actorPreferences().activeProfileId.first() })
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
        compose.onNodeWithText("Review & save").performClick()
        compose.onNodeWithText("Save character").performClick()
        compose.waitUntil(10000) { runBlocking { repository.observeAll().first() }.any { it.character.name == "Fictional traveler" } }
        val character = runBlocking { repository.observeAll().first() }.single().character
        assertEquals(1, character.level)
        assertNull(character.baseMaxHp)
        assertTrue(character.baseAbilities.values.all { it == null })
        assertTrue(character.items.isEmpty())
    }

    @Test fun spellbookSearchAndFiltersPreserveTheReviewedCastingWorkflow() {
        val character = fixture().copy(spellSlots = mapOf(1 to 2), spells = listOf(
            SpellDefinition("veil", "Amber Veil", 1, SpellPreparation.ALWAYS_PREPARED,
                concentration = true, duration = "Up to 1 minute"),
            SpellDefinition("beacon", "Amber Beacon", 1, SpellPreparation.PREPARED),
            SpellDefinition("lock", "Amber Lock", 1),
            SpellDefinition("spark", "Quiet Spark", 0)
        ))
        withSheet(character = character) { repository ->
            compose.waitUntil(10000) { compose.onAllNodesWithText("Spells").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Spells") and hasAnyAncestor(hasTestTag("genesis-character-tabs"))).performScrollTo().performClick()
            compose.onNodeWithText("Quiet Spark").assertExists()
            compose.onNodeWithText("Cantrips").performScrollTo().performClick()
            compose.onNodeWithText("Quiet Spark").assertExists()
            compose.onNodeWithText("Amber Veil").assertDoesNotExist()

            compose.onNode(hasText("Prepared") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("Amber Beacon").assertExists()
            compose.onNodeWithText("Amber Veil").assertExists()
            compose.onNodeWithText("Amber Lock").assertDoesNotExist()

            val search = compose.onNode(hasSetTextAction() and hasText("Find a spell by name"))
            search.performScrollTo().performTextInput("  AMBER VEIL  ")
            compose.onNodeWithText("Amber Beacon").assertDoesNotExist()
            compose.onNodeWithText("Up to 1 minute", substring = true).assertExists()
            compose.onNodeWithText("Cast", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Cast Amber Veil").assertExists()
            compose.onNodeWithText("Preview casting cost").performScrollTo().performClick()
            compose.onNodeWithText("Amber Veil · consumes one level-1 slot · Concentration").assertExists()
            assertEquals(2, runBlocking { repository.get(character.id) }!!.character.spellSlots[1])
            compose.onNodeWithText("Cast & save").performClick()
            compose.waitUntil(10000) { runBlocking { repository.get(character.id) }!!.character.spellSlots[1] == 1 }
            assertEquals("veil", runBlocking { repository.get(character.id) }!!.character.concentrationSpellId)

            search.performScrollTo().performTextReplacement("no such spell")
            compose.onNodeWithText("No matching spells").assertExists()
            compose.onNodeWithText("Show all spells").performScrollTo().performClick()
            compose.onNodeWithText("Amber Lock").assertExists()
            compose.onNodeWithText("Quiet Spark").assertExists()
        }
    }

    @Test fun copyingCampaignItemIsExplicitAndKeepsOriginalIdentityWithoutMagic() {
        var sourceId = ""
        lateinit var playable: ReforgeRepository
        lateinit var campaign: CampaignLedgerRepository
        withSheet(localSetup = { playable = it }, campaignSetup = { ledger ->
            campaign = ledger
            runBlocking {
                playable.createReviewedCampaignIdentity(ledger, requireNotNull(playable.get("synthetic-player")))
                val item = ledger.create(LedgerDraft(title = "Runed lantern", quantity = 1,
                    itemState = "Carried", evidenceStatus = "Proposed",
                    description = "Mysterious runes, no confirmed effect"))
                sourceId = item.id
                ledger.party.moveItem(ItemMoveCommand(item.id, item.revision,
                    holderProfileId = "synthetic-player", state = "Carried", source = "Fictional reviewed assignment"))
                ledger.create(LedgerDraft(title = "Unassigned lantern", quantity = 1, itemState = "Carried"))
            }
        }) { repository ->
            val sourceBefore = runBlocking { campaignDecodeBackup(campaign.export()).copy(exportedAt = 0) }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Equipment").performScrollTo().performClick()
            compose.onNodeWithText("Add from campaign inventory").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Runed lantern").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Unassigned lantern").assertDoesNotExist()
            assertTrue(runBlocking { repository.get("synthetic-player") }!!.character.items.none { it.id == sourceId })
            compose.onNodeWithText("Runed lantern").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Add reviewed item").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Source evidence: Proposed").performScrollTo().assertIsDisplayed()
            assertTrue(runBlocking { repository.get("synthetic-player") }!!.character.items.none { it.id == sourceId })
            compose.onNodeWithText("Add reviewed item").performClick()
            compose.waitUntil(10000) {
                runBlocking { repository.get("synthetic-player") }!!.character.items.any { it.id == sourceId }
            }
            val character = runBlocking { repository.get("synthetic-player") }!!.character
            assertEquals(1, character.items.single { it.id == sourceId }.quantity)
            assertFalse(character.items.single { it.id == sourceId }.attuned)
            assertTrue(character.itemDefinitions.single { it.id == sourceId }.effects.isEmpty())
            assertEquals(sourceBefore, runBlocking { campaignDecodeBackup(campaign.export()).copy(exportedAt = 0) })
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

    @Test fun stackedEquipmentOpensSplitReviewBeforeOneItemCanBeEquipped() {
        val stacked = fixture().copy(items = fixture().items.map { it.copy(quantity = 3) })
        withSheet(character = stacked, initialSection = "Equipment") { repository ->
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Split off one item before equipping.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Equip", substring = false).assertDoesNotExist()
            compose.onNodeWithText("Split stack").performScrollTo().performClick()
            compose.onNodeWithText("Review split").assertIsEnabled()
            compose.onNodeWithText("Close").performClick()
            assertEquals(1L, runBlocking { repository.get(stacked.id) }!!.revision)

            // Inspection exposes the same safe path as the direct inventory row.
            compose.onNode(hasText("Synthetic armor") and hasClickAction()).performScrollTo().performClick()
            compose.onNode(hasText("Equip", substring = false) and hasAnyAncestor(isDialog())).assertDoesNotExist()
            compose.onNode(hasText("Split stack") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            compose.onNodeWithText("Quantity in the new stack").assertExists()
            compose.onNodeWithText("Review split").performScrollTo().performClick()
            compose.onNodeWithText("Confirm change").assertExists()
            val beforeConfirmation = runBlocking { repository.get(stacked.id) }!!
            assertEquals(1L, beforeConfirmation.revision)
            assertEquals(3, beforeConfirmation.character.items.single().quantity)
            compose.onNodeWithText("Confirm change").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(stacked.id) }!!.revision == 2L }
            val split = runBlocking { repository.get(stacked.id) }!!.character
            assertEquals(2, split.items.size)
            assertEquals(3, split.items.sumOf { it.quantity })
            assertEquals(2, split.items.single { it.id == "armor-instance" }.quantity)
            assertEquals(8, CharacterCalculator.calculate(split).abilities.getValue(Stat.STR).effective.value)

            compose.onNodeWithText("Equip", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Confirm change").assertExists()
            assertEquals(2L, runBlocking { repository.get(stacked.id) }!!.revision)
            compose.onNodeWithText("Confirm change").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(stacked.id) }!!.revision == 3L }
            val equipped = runBlocking { repository.get(stacked.id) }!!.character
            assertEquals(3, equipped.items.sumOf { it.quantity })
            assertEquals(1, equipped.items.single { it.state == ItemState.WORN }.quantity)
            assertEquals(21, CharacterCalculator.calculate(equipped).abilities.getValue(Stat.STR).effective.value)
            assertEquals(26, equipped.hpDamage)
        }
    }

    @Test fun emptyEquipmentCannotEquipAndHistoricalEquippedStackCanStillUnequip() {
        val character = fixture().copy(
            itemDefinitions = fixture().itemDefinitions + ItemDefinition("empty", "Empty supplies"),
            items = listOf(fixture().items.single().copy(quantity = 3, state = ItemState.WORN), ItemInstance("empty-stock", "empty", quantity = 0)))
        withSheet(character = character, initialSection = "Equipment") { repository ->
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Empty supplies").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Equip", substring = false).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("No items available to equip.").assertExists()
            compose.onNode(hasText("Synthetic armor") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("Manage").performClick()
            compose.onNodeWithText("Review split").assertIsNotEnabled()
            compose.onNodeWithText("Unequip and end attunement before splitting this stack.").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Unequip").performScrollTo().performClick()
            compose.onNodeWithText("Confirm change").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(character.id) }!!.revision == 2L }
            val result = runBlocking { repository.get(character.id) }!!.character
            assertEquals(ItemState.CARRIED, result.items.single { it.id == "armor-instance" }.state)
            assertEquals(3, result.items.single { it.id == "armor-instance" }.quantity)
            assertEquals(0, result.items.single { it.id == "empty-stock" }.quantity)
        }
    }

    @Test fun compactSheetShowsVitalsAndAllAbilitiesBeforeScrolling() = withSheet {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("HP 40 / 66").assertIsDisplayed()
        Stat.entries.forEach { compose.onNodeWithTag("reforge-${it.name}").assertIsDisplayed() }
        capture("rebirth-character-overview")
        assertCharacterChromeUsesCanvas()
    }

    @Test fun portraitHeaderOpensOptionalControlsWithoutRestoringShortcutRow() = withSheet { repository ->
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick-health").assertDoesNotExist()
        compose.onNodeWithContentDescription("Character controls").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Customize quick actions").assertIsDisplayed()
        compose.onNodeWithText("Health").assertIsDisplayed().performClick()
        compose.onNodeWithText("Damage or healing amount").assertIsDisplayed()
        assertEquals(40, runBlocking { repository.get(fixture().id) }!!.character.currentHp)
        capture("genesis-portrait-character-controls-health")
    }

    private fun assertCharacterChromeUsesCanvas() {
        listOf("quick-health", "quick-attack", "quick-spells", "quick-inventory").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        val sampleRows = listOf("genesis-character-tabs", "genesis-page-indicator")
            .associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.center.y.toInt() }
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            val decor = requireNotNull(captureActivity).window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                // Match the native evidence capture's initial cache-warming draw.
                decor.draw(Canvas(bitmap))
                decor.draw(Canvas(bitmap))
                sampleRows.forEach { (region, y) ->
                    assertEquals("$region must retain the navy canvas beneath its transparent controls",
                        RebuildPalette.Canvas.toArgb(), bitmap.getPixel(1, y))
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test @Config(qualifiers = "w360dp-h1000dp-xhdpi")
    fun scaledSheetKeepsWholeActionWordsAt360Font130() = verifyScaledSheetActions(360, 1.3f)

    @Test @Config(qualifiers = "w400dp-h1000dp-xhdpi")
    fun scaledSheetKeepsWholeActionWordsAt400Font130() = verifyScaledSheetActions(400, 1.3f)

    @Test @Config(qualifiers = "w360dp-h1000dp-xhdpi")
    fun scaledSheetKeepsWholeActionWordsAt360Font200() = verifyScaledSheetActions(360, 2f)

    @Test @Config(qualifiers = "w400dp-h1000dp-xhdpi")
    fun scaledSheetKeepsWholeActionWordsAt400Font200() = verifyScaledSheetActions(400, 2f)

    private fun verifyScaledSheetActions(width: Int, fontScale: Float) {
        withSheet(character = fixture().copy(name = "Alexandria Moonwhisper of Silver Keep"), fontScale = fontScale) {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
            val captureName = "rebirth-sheet-${width}-font${(fontScale * 100).toInt()}"
            capture("$captureName-overview")
            val indicatorLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("genesis-page-indicator")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(indicatorLayouts)) }
            val indicator = indicatorLayouts.single()
            assertEquals("Page count must stay on one line at enlarged font sizes", 1, indicator.lineCount)
            // String Text's semantics paragraph retains the Row's maximum
            // width even when its measured text fits. Check actual line ink and
            // every character, rather than that paragraph's blank allocation.
            assertEquals("1 / 7", indicator.layoutInput.text.text)
            assertEquals("The complete page count must remain visible", indicator.layoutInput.text.length,
                indicator.getLineEnd(0, visibleEnd = true))
            assertFalse("Page count must not be ellipsized", indicator.isLineEllipsized(0))
            assertTrue("Page count ink must fit beside the swipe hint", indicator.getLineLeft(0) >= -1f &&
                indicator.getLineRight(0) <= indicator.size.width + 1f)
            val indicatorBounds = compose.onNodeWithTag("genesis-page-indicator").fetchSemanticsNode().boundsInRoot
            assertTrue("Page count must not be clipped by its parent", indicatorBounds.width + 1f >= indicator.size.width &&
                indicatorBounds.height + 1f >= indicator.size.height)
            compose.onNodeWithText("Character setup").performScrollTo()
            capture("$captureName-actions")
            listOf("Roll dice", "Character setup", "Switch character").forEach { label ->
                val action = compose.onNode(hasText(label) and hasClickAction())
                // Switching is pinned above the pager, so it must remain visible
                // after the page scroll without requiring a scrollable ancestor.
                if (label != "Switch character") action.performScrollTo()
                action.assertIsDisplayed().assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val text = compose.onNodeWithText(label, useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertEquals("$label must retain the requested font scale", fontScale, layout.layoutInput.density.fontScale, .01f)
                assertFalse("$label must not omit lines", layout.multiParagraph.didExceedMaxLines)
                assertFalse("$label must not overflow vertically", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("$label must not be ellipsized", layout.isLineEllipsized(line))
                    assertTrue("$label ink must fit the measured line", layout.getLineLeft(line) >= -1f &&
                        layout.getLineRight(line) <= layout.size.width + 1f)
                    if (line > 0) {
                        val start = layout.getLineStart(line)
                        assertTrue("$label must wrap between whole words, not inside a word", start == 0 ||
                            label[start - 1].isWhitespace() || label.getOrNull(start)?.isWhitespace() == true)
                    }
                }
                val bounds = text.fetchSemanticsNode().boundsInRoot
                assertTrue("$label must not be clipped by its parent", bounds.height + 1f >= layout.size.height &&
                    bounds.width + 1f >= layout.size.width)
            }
        }
    }

    @Test fun fullSkillRowStartsAReviewableRollWithoutOpeningADisclosure() = withSheet { repository ->
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Arcana") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("reforge-roll-now").assertExists()
        assertTrue(runBlocking { repository.rollHistory("synthetic-player").first() }.isEmpty())
        assertEquals(1L, runBlocking { repository.get("synthetic-player") }!!.revision)
    }

    @Test fun unknownAbilitiesStayConciseAndUnknownSavesUseReadableFullWidthRows() {
        val unknown = fixture().copy(baseAbilities = emptyMap())
        withSheet(character = unknown) { repository ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reforge-STR").assertContentDescriptionEquals("STR Not set")
            compose.onNodeWithText("Score not set").assertDoesNotExist()
            capture("rebirth-character-unknown-stats")
            compose.onNodeWithTag("reforge-save-STR").performScrollTo().assertWidthIsAtLeast(300.dp).assertIsNotEnabled()
            compose.onNodeWithTag("reforge-save-DEX").performScrollTo().assertWidthIsAtLeast(300.dp).assertIsNotEnabled()
            capture("rebirth-character-unknown-saves")
            assertTrue(runBlocking { repository.rollHistory(unknown.id).first() }.isEmpty())
            assertTrue(runBlocking { repository.get(unknown.id) }!!.character.baseAbilities.isEmpty())
        }
    }

    @Test fun gearSearchAndInspectionRetainTheExplicitEquipReview() {
        val character = fixture().copy(
            itemDefinitions = fixture().itemDefinitions + ItemDefinition("lantern", "Fixture lantern", description = "A plain lantern."),
            items = fixture().items + ItemInstance("held-lantern", "lantern", state = ItemState.HELD))
        withSheet(character = character, initialSection = "Equipment") { repository ->
            compose.waitUntil(10000) { compose.onAllNodesWithText("Fixture lantern").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("section-Equipped").performScrollTo().performClick()
            compose.onNodeWithText("Fixture lantern").assertExists()
            compose.onNodeWithText("Synthetic armor").assertDoesNotExist()
            compose.onNodeWithTag("section-All gear").performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText("Find equipment")).performScrollTo().performTextInput("synthetic")
            compose.onNodeWithText("Fixture lantern").assertDoesNotExist()
            compose.onNode(hasText("Synthetic armor") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("Equipment effects").assertExists()
            compose.onNodeWithText("Manage").assertExists()
            compose.onNode(hasText("Equip", substring = false) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            compose.onNodeWithText("Confirm change").assertExists()
            assertEquals(1L, runBlocking { repository.get(character.id) }!!.revision)
            assertEquals(ItemState.CARRIED, runBlocking { repository.get(character.id) }!!.character.items.first().state)
        }
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
        compose.waitUntil(10000) { compose.onAllNodesWithText("View history").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reforge-STR").assertExists()
        compose.onNodeWithText("Roll history").assertDoesNotExist()
        capture("reforge-native-roll-saved-on-sheet")
        compose.onNodeWithText("View history").assertIsDisplayed().performClick()
        compose.onNodeWithText("Roll history").assertExists()
        compose.onNodeWithText("Dice details").assertExists()
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

    @Test fun archiveRequiresConfirmationAndRestoreReturnsSameSavedSheet() = withSheet { repository ->
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reforge-STR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Switch character").performClick()
        compose.onNodeWithText("Manage saved sheets").performScrollTo().performClick()
        compose.onNodeWithText("Archive Synthetic artificer").performScrollTo().performClick()
        assertFalse(runBlocking { repository.get("synthetic-player") }!!.character.archived)
        compose.onNodeWithText("Archive sheet").performClick()
        compose.waitUntil(10000) { runBlocking { repository.observeActive().first() }.isEmpty() }
        compose.onNodeWithTag("reforge-STR").assertDoesNotExist()
        compose.onNodeWithText("View archived sheets").performScrollTo().performClick()
        compose.onNodeWithText("Restore Synthetic artificer").performScrollTo().performClick()
        compose.waitUntil(10000) { runBlocking { repository.get("synthetic-player") }!!.revision == 3L }
        val after = runBlocking { repository.get("synthetic-player") }!!.character
        assertFalse(after.archived)
        assertEquals(fixture().items, after.items)
        assertEquals(fixture().baseAbilities, after.baseAbilities)
        assertEquals(fixture().currentHp, after.currentHp)
    }

}
