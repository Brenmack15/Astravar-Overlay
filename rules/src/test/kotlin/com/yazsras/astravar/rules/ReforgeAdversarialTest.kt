package com.yazsras.astravar.rules

import com.yazsras.astravar.rules.reforge.*
import org.junit.Assert.*
import org.junit.Test

/** Independent regression cases at the boundaries of the new gameplay contracts. */
class ReforgeAdversarialTest {
    private fun character() = ReforgeCharacter("qa-character", "QA character",
        baseAbilities = Stat.entries.associateWith { 10 }, baseMaxHp = 20,
        baselineConstitutionModifier = 0, currentHp = 13, temporaryHp = 0, speed = 30)

    @Test fun zeroAndUnknownRemainDistinctAndUnverifiedMagicDoesNotActivate() {
        val claim = EffectDefinition("claim", "Unverified imported claim",
            RuleSource("source", "Unverified import", Acceptance.UNVERIFIED),
            EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 21, "STR"))
        val c = character().copy(baseAbilities = mapOf(Stat.STR to 0, Stat.CON to null), effects = listOf(claim))
        val result = CharacterCalculator.calculate(c)
        assertEquals(0, result.abilities.getValue(Stat.STR).effective.value)
        assertEquals(-5, result.abilities.getValue(Stat.STR).modifier)
        assertNull(result.abilities.getValue(Stat.CON).effective.value)
        assertFalse(result.effectStatuses.single { it.effectId == "claim" }.active)
    }

    @Test fun unknownHpCannotBecomeKnownFromAStaleDamageProjection() {
        val c = character().copy(currentHp = null, hpDamage = 7)
        assertNull(CharacterCalculator.calculate(c).currentHp)
        val next = GameplayRules.advanceTime(c, 60, confirmed = true)
        assertNull(next.currentHp)
        assertNull(CharacterCalculator.calculate(next).currentHp)
    }

    @Test fun cancellingAResourcePreviewDoesNotConsumeSlotsOrConcentration() {
        val c = character().copy(rules = RuleVersion(classVersion = "legacy-artificer-infusions-2020"),
            spellcastingClassLevel = 1, spellSlots = mapOf(1 to 2),
            spells = listOf(SpellDefinition("qa-spell", "QA spell", 1, SpellPreparation.PREPARED, concentration = true)))
        val preview = GameplayRules.previewCast(c, "qa-spell")
        assertEquals(1, preview.slotLevel)
        assertEquals(2, c.spellSlots[1])
        assertNull(c.concentrationSpellId)
        val changedRevision = c.copy(revision = 1)
        assertThrows(IllegalArgumentException::class.java) { GameplayRules.commitCast(changedRevision, preview) }
        assertEquals(2, changedRevision.spellSlots[1])
    }

    @Test fun manualAdvantageRecordsBothFacesAndRejectsMissingOrExtraFaces() {
        val formula = SecureDice.parse("1d20 + 4")
        val result = SecureDice.manual(formula, listOf(3, 17), DiceMode.ADVANTAGE)
        assertEquals(21, result.total)
        assertEquals(17, result.naturalD20)
        assertEquals(listOf(false, true), result.faces.map { it.kept })
        assertTrue(result.manual)
        assertThrows(IllegalArgumentException::class.java) { SecureDice.manual(formula, listOf(17), DiceMode.ADVANTAGE) }
        assertThrows(IllegalArgumentException::class.java) { SecureDice.manual(formula, listOf(3, 17, 4), DiceMode.ADVANTAGE) }
        assertThrows(IllegalArgumentException::class.java) { SecureDice.manual(formula, listOf(3, 21), DiceMode.ADVANTAGE) }
    }

    @Test fun zeroChargesCannotActivateAPropertyAndStoredDonorsCannotStack() {
        val source = RuleSource("qa-accepted", "QA accepted homebrew", Acceptance.ACCEPTED_HOMEBREW)
        val effect = EffectDefinition("strength", "Strength property", source,
            EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 21, "STR"),
            chargeResourceId = "charges", chargeCost = 1)
        val definition = ItemDefinition("item-def", "QA item", effects = listOf(effect))
        val c = character().copy(resources = listOf(ResourcePool("charges", "Charges", 3, 0)),
            itemDefinitions = listOf(definition), items = listOf(ItemInstance("donor", "item-def", ItemState.WORN),
                ItemInstance("composite", "item-def", ItemState.STORED, donorInstanceIds = setOf("donor"))))
        val p = CharacterCalculator.calculate(c)
        assertEquals(10, p.abilities.getValue(Stat.STR).effective.value)
        assertFalse(p.effectStatuses.any { it.active })
        assertThrows(IllegalArgumentException::class.java) { GameplayRules.equip(c, "donor", ItemState.WORN) }
    }
}
