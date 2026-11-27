package com.yazsras.astravar.rules.reforge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollManagerRulesTest {
    @Test fun circleAndArrowsRemoveSwitchAndPreserveDeliberateRepeats() {
        val skill = QueuedCheck(RollKind.SKILL, "Arcana", "Arcana")
        var q = RollManagerRules.select(emptyList(), skill, DiceMode.ADVANTAGE)
        q = RollManagerRules.select(q, skill, DiceMode.DISADVANTAGE)
        assertEquals(listOf(DiceMode.DISADVANTAGE), q.map { it.mode })
        assertTrue(RollManagerRules.select(q, skill, DiceMode.DISADVANTAGE).isEmpty())
        q = RollManagerRules.select(q + skill.copy(primary = false), skill, null)
        assertEquals(listOf(false), q.map { it.primary })
        q = RollManagerRules.select(q, skill, null)
        assertEquals(listOf(DiceMode.NORMAL, DiceMode.NORMAL), q.map { it.mode })
        assertEquals(listOf(true, false), RollManagerRules.move(q, 1, 0).map { it.primary })
    }
    @Test fun passiveOnlyEffectsDoNotLeakIntoChecksOrChangeBaseAndCheckOnlyBonusesLeaveMathIntact() {
        val source = RuleSource("source", "Synthetic training", Acceptance.ACCEPTED_HOMEBREW)
        fun effect(id: String, target: NumericTarget, key: String, amount: Int) = EffectDefinition(id, id, source,
            EffectPayload.Number(target,NumberOperation.ADD,amount,key), stackingKey = "shared-item")
        val a = ReforgeCharacter("fixture", "Fixture", level = 12, baseAbilities = mapOf(Stat.INT to 20, Stat.WIS to 10, Stat.STR to 21),
            skills = mapOf("Investigation" to SkillTraining(Stat.INT,true,true), "Perception" to SkillTraining(Stat.WIS,true),
                "Insight" to SkillTraining(Stat.WIS,true,true)), effects = listOf(effect("checks",NumericTarget.SKILL,"",1),
                effect("passive-sight",NumericTarget.PASSIVE,"Perception",5),effect("passive-deduction",NumericTarget.PASSIVE,"Investigation",5),
                effect("lifting",NumericTarget.ABILITY_CHECK,"STR",1)))
        val p = CharacterCalculator.calculate(a)
        assertEquals(listOf(5,14,9),listOf("Perception","Investigation","Insight").map { p.skills.getValue(it).value })
        assertEquals(listOf(20,29,19),listOf("Perception","Investigation","Insight").map { p.passiveScores[it] })
        assertEquals(5,p.abilities.getValue(Stat.STR).modifier); assertEquals(6,p.abilityChecks.getValue(Stat.STR).value)
        assertTrue(p.passiveExplanations.getValue("Investigation").explanation.any { it.label == "passive-deduction" })
    }
}
