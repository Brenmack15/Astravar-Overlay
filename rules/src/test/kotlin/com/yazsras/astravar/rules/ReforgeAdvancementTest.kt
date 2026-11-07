package com.yazsras.astravar.rules

import com.yazsras.astravar.rules.reforge.*
import org.junit.Assert.*
import org.junit.Test

class ReforgeAdvancementTest {
    private fun actor() = ReforgeCharacter("advancement-fixture", "Fictional actor", level = 9,
        baseAbilities = mapOf(Stat.CON to 14, Stat.INT to 18), baselineConstitutionModifier = 2,
        baseMaxHp = 66, currentHp = 50, temporaryHp = 0, spellcastingClassLevel = 9,
        spellSlots = mapOf(1 to 1, 2 to null, 3 to 0))
    @Test fun advancementPreservesDamageAndSpentUnknownSlots() {
        val before = actor()
        val next = AdvancementRules.advance(before, 10, 5, mapOf(Stat.INT to 1))
        val p = CharacterCalculator.calculate(next)
        assertEquals(73, p.maximumHp.value); assertEquals(57, p.currentHp)
        assertEquals(19, p.abilities.getValue(Stat.INT).permanent)
        assertEquals(before.spellSlots, next.spellSlots)
        assertEquals(10, next.spellcastingClassLevel)
        assertTrue(next.features.isEmpty())
    }
    @Test fun unknownGainCannotInventHitPointsOrLevelSkip() {
        val next = AdvancementRules.advance(actor().copy(currentHp = null), 10, null)
        assertNull(CharacterCalculator.calculate(next).maximumHp.value)
        assertNull(CharacterCalculator.calculate(next).currentHp)
        try { AdvancementRules.advance(actor(), 11, 5); fail("Level skip should require separate review") } catch (_: IllegalArgumentException) { }
    }
}
