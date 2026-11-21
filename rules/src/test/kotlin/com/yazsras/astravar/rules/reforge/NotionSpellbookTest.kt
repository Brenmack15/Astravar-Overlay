package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

class NotionSpellbookTest {
    private val page = "773f7f68456e4019a7c58c4e2c0e74da"
    private val fact = NotionSpellFact(page, "https://app.notion.com/p/$page", "2026-11-21",
        "ice-flare", "Confirmed", "Ice Flare", 1, SpellPreparation.ALWAYS_PREPARED, false,
        "1 Action", "60 ft.", "V, S", "Instantaneous", false)
    private val rules = RuleVersion(classVersion = "legacy-artificer-infusions-2020")
    private val actor = ReforgeCharacter("test-spellbook", "Fixture", level = 9, className = "Artificer",
        subclass = "Armorer", rules = rules, spellSlots = mapOf(1 to 0, 2 to null))
    private val source = NotionCharacterTransfer(NOTION_CHARACTER_TRANSFER_FORMAT, "test-spellbook", "2026-11-21",
        NotionCharacterFacts(actor.name, "Owner", fact.sourceUrl, 9, "Gnome", actor.className, actor.subclass,
            Stat.entries.associateWith { 12 }), emptyList(), emptyList(), null)
    private val choices = NotionTransferChoices(stats = false, items = false, spells = true)
    private fun transfer(vararg spells: NotionSpellFact) =
        source.copy(reviewedSpellbook = NotionSpellbook(rules.classVersion, 9, spells.toList(), spells.first().nativeId))

    @Test fun reviewedClassPreparationIsOptionalAndChangesNoExpenditure() {
        val document = transfer(fact)
        assertEquals(actor, planNotionCharacterTransfer(actor, document, choices.copy(spells = false)).character)
        val planned = planNotionCharacterTransfer(actor, document, choices)
        assertEquals(listOf(fact.name), planned.addedSpells)
        assertTrue(planned.changed && planned.castingLevelChanged)
        assertEquals(9, planned.character.spellcastingClassLevel)
        assertEquals(mapOf(1 to 4, 2 to 3, 3 to 2), CharacterCalculator.calculate(planned.character).slotMaxima)
        assertEquals(actor.spellSlots, planned.character.spellSlots)
        assertEquals(SpellPreparation.ALWAYS_PREPARED, planned.character.spells.single().preparation)
        assertNull(planned.character.concentrationSpellId)
        assertFailsWith<IllegalArgumentException> { GameplayRules.previewCast(planned.character, fact.nativeId) }
        val repeated = planNotionCharacterTransfer(planned.character, document, choices)
        assertFalse(repeated.changed)
        assertEquals(listOf(fact.name), repeated.presentSpells)
    }

    @Test fun collisionsProposalsAndConflictingRulesPreserveTheExistingRecord() {
        val existing = actor.copy(spellcastingClassLevel = 8, spells = listOf(fact.definition().copy(name = "Local revision")))
        val result = planNotionCharacterTransfer(existing, transfer(fact, fact.copy(spellKey = "dream", name = "Dream", evidence = "Proposed")), choices)
        assertEquals(existing, result.character)
        assertEquals(3, result.deferredSpells.size)
        val manual = actor.copy(spells = listOf(fact.definition().copy(id = "independent-definition")))
        assertTrue(planNotionCharacterTransfer(manual, transfer(fact), choices).addedSpells.isEmpty())
        val ambiguous = planNotionCharacterTransfer(actor, transfer(fact, fact.copy(spellKey = "same-name")), choices)
        assertTrue(ambiguous.addedSpells.isEmpty())
        assertFalse(planNotionCharacterTransfer(actor.copy(rules = RuleVersion(classVersion = "custom-rules")), transfer(fact), choices).changed)
        val future = actor.copy(level = 4)
        val futurePlan = planNotionCharacterTransfer(future, transfer(fact), choices)
        assertNull(futurePlan.character.spellcastingClassLevel)
        assertTrue(futurePlan.addedSpells.isEmpty())
        val highSpell = planNotionCharacterTransfer(actor, transfer(fact.copy(level = 9)), choices)
        assertTrue(highSpell.addedSpells.isEmpty())
        assertTrue(highSpell.deferredSpells.single().contains("capacity"))
    }

    @Test fun sourceIdentityValidationAndLegacySerializationAreStable() {
        val verbose = Json { encodeDefaults = true }
        assertFalse(verbose.encodeToString(source).contains("reviewedSpellbook"))
        assertFalse(verbose.encodeToString(NotionTransferChoices()).contains("spells"))
        assertEquals(source, verbose.decodeFromString<NotionCharacterTransfer>(verbose.encodeToString(source)))
        assertEquals(transfer(fact), verbose.decodeFromString<NotionCharacterTransfer>(verbose.encodeToString(transfer(fact))))
        assertFailsWith<IllegalArgumentException> { validateNotionCharacterTransfer(transfer(fact, fact.copy(sourceId = page.uppercase()))) }
        assertFailsWith<IllegalArgumentException> { validateNotionCharacterTransfer(transfer(fact.copy(sourceUrl = "https://example.org/$page"))) }
    }
}
