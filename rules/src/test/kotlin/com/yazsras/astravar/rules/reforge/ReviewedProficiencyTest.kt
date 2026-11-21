package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

class ReviewedProficiencyTest {
    @Test fun historicalActorKeepsIndependentProficiencyAcrossEveryTrainedProjection() {
        val source = RuleSource("fictional:creature-tier", "Accepted independent creature tier", Acceptance.ACCEPTED_HOMEBREW, 2)
        val actor = ReforgeCharacter("fictional-owl", "Owl", level = 9, baseAbilities = mapOf(Stat.DEX to 18, Stat.INT to 10),
            skills = mapOf("Stealth" to SkillTraining(Stat.DEX, proficient = true, expertise = true)),
            saveProficiencies = setOf(Stat.DEX),
            attacks = listOf(AttackDefinition("talons", "Talons", Stat.DEX)))
        val oldWire = Json.encodeToString(actor)
        assertFalse("reviewedProficiency" in oldWire)
        assertEquals(4, CharacterCalculator.calculate(Json.decodeFromString<ReforgeCharacter>(oldWire)).proficiencyBonus)
        val historical = actor.copy(reviewedProficiency = ReviewedProficiency(3, source))
        val reopened = Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(historical))
        val stats = CharacterCalculator.calculate(reopened)
        assertEquals(source, reopened.reviewedProficiency?.source)
        assertEquals(3, stats.proficiencyBonus)
        assertEquals(10, stats.skills.getValue("Stealth").value)
        assertEquals(7, stats.saves.getValue(Stat.DEX).value)
        assertEquals(3, stats.spellAttack.value)
        assertEquals(7, AttackRules.preview(reopened, "talons").attackBonus.value)
        assertEquals(4, CharacterCalculator.calculate(reopened.copy(reviewedProficiency = null)).proficiencyBonus)
        assertFails { CharacterCalculator.calculate(historical.copy(reviewedProficiency =
            ReviewedProficiency(3, source.copy(acceptance = Acceptance.PROPOSED)))) }
    }
}
