package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

class SkillRulesTest {
    private val source = RuleSource("fictional-source", "Accepted test rule", Acceptance.ACCEPTED_HOMEBREW)
    private fun actor() = ReforgeCharacter("fictional", "Fictional artisan", level = 9,
        baseAbilities = mapOf(Stat.INT to 20, Stat.STR to 8, Stat.WIS to 10))
    private fun skill(id: String = "skill:one", name: String = "Engineering", training: SkillTraining = SkillTraining(Stat.INT, true)) =
        CustomSkill(id, name, training)

    @Test fun expertiseUsesSharedAbilityAndProficiencyWithoutDoubleCounting() {
        val character = actor().copy(customSkills = listOf(skill().copy(training = SkillTraining(Stat.INT, true, true), bonus = 2)))
        assertEquals(15, CharacterCalculator.calculate(character).customSkills.getValue("skill:one").value)
        val effect = EffectDefinition("grant", "Expertise grant", source, EffectPayload.Proficiency("skill:one", expertise = true))
        assertEquals(15, CharacterCalculator.calculate(character.copy(effects = listOf(effect))).customSkills.getValue("skill:one").value)
    }

    @Test fun activeEquipmentAndConditionsApplyToCustomSkillsOnce() {
        val armor = ItemDefinition("armor", "Fictional armor", effects = listOf(
            EffectDefinition("strength", "Armor Strength", source, EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 21, "STR"))))
        val character = actor().copy(customSkills = listOf(skill(training = SkillTraining(Stat.STR, true))),
            itemDefinitions = listOf(armor), items = listOf(ItemInstance("worn-armor", armor.id, ItemState.WORN)),
            exhaustionLevel = 1, effects = listOf(EffectDefinition("luck", "Skill luck", source, EffectPayload.Number(NumericTarget.SKILL, NumberOperation.ADD, 1))))
        assertEquals(8, CharacterCalculator.calculate(character).customSkills.getValue("skill:one").value) // 5 + 4 + 1 − 2
        val unequipped = character.copy(items = character.items.map { it.copy(state = ItemState.CARRIED) })
        assertEquals(2, CharacterCalculator.calculate(unequipped).customSkills.getValue("skill:one").value)
        assertEquals(DiceMode.DISADVANTAGE, CharacterCalculator.rollMode(character.copy(conditions = setOf("Poisoned")), RollKind.SKILL, "skill:one"))
    }

    @Test fun sameDisplayNameCannotReplaceAnOfficialSkillOrRedirectItsEffects() {
        val character = actor().copy(customSkills = listOf(skill(name = "Arcana").copy(bonus = 10)),
            skills = mapOf("Arcana" to SkillTraining(Stat.INT, true)), effects = listOf(
                EffectDefinition("arcana", "Arcana only", source, EffectPayload.Number(NumericTarget.SKILL, NumberOperation.ADD, 2, "Arcana"))))
        val projection = CharacterCalculator.calculate(character)
        assertEquals(11, projection.skills.getValue("Arcana").value)
        assertEquals(19, projection.customSkills.getValue("skill:one").value)
        assertEquals(2, SkillRules.entries(character).count { it.name == "Arcana" })
        assertFailsWith<IllegalArgumentException> { SkillRules.save(character, skill(id = "Arcana")) }
    }

    @Test fun unknownAbilityStaysUnknownUntilAnExplicitOverrideIsSaved() {
        val character = actor().copy(customSkills = listOf(skill(training = SkillTraining(Stat.CHA, true)).copy(bonus = 4)))
        assertNull(CharacterCalculator.calculate(character).customSkills.getValue("skill:one").value)
        val overridden = SkillRules.save(character, character.customSkills.single().copy(baselineOverride = 7))
        assertEquals(5, CharacterCalculator.calculate(overridden.copy(exhaustionLevel = 1)).customSkills.getValue("skill:one").value)
        assertNull(overridden.baseAbilities[Stat.CHA])
    }

    @Test fun pinsSortBothGroupsAlphabeticallyWithStableDuplicateNamesAndDoNotChangeMechanics() {
        val character = actor().copy(customSkills = listOf(skill("skill:z", "arcana"), skill("skill:a", "ARCANA"), skill("skill:beta", "beta")))
        val before = CharacterCalculator.calculate(character)
        val pinned = SkillRules.pin(SkillRules.pin(SkillRules.pin(character, "skill:z", true), "Stealth", true), "skill:a", true)
        val entries = SkillRules.entries(pinned)
        assertEquals(listOf("skill:a", "skill:z", "Stealth"), entries.take(3).map { it.id })
        assertTrue(entries.drop(3).none { it.pinned })
        assertEquals("Acrobatics", entries[3].id)
        assertEquals(before, CharacterCalculator.calculate(pinned))
        assertEquals(listOf("skill:a", "Stealth"), SkillRules.entries(SkillRules.pin(pinned, "skill:z", false)).filter { it.pinned }.map { it.id })
    }

    @Test fun editAndDeleteUseIdentityAndLeaveOfficialTrainingOtherSkillsAndEffectsIntact() {
        val original = actor().copy(skills = mapOf("Arcana" to SkillTraining(Stat.INT, true)),
            customSkills = listOf(skill(), skill("skill:other", "Engineering")), pinnedSkillIds = setOf("skill:one", "Arcana"))
        val edited = SkillRules.save(original, original.customSkills.first().copy(name = "Rune work", notes = "Accepted tool synergy"))
        assertEquals(listOf("Rune work", "Engineering"), edited.customSkills.map { it.name })
        assertTrue("skill:one" in edited.pinnedSkillIds)
        val removed = SkillRules.remove(edited, "skill:one")
        assertEquals(listOf("skill:other"), removed.customSkills.map { it.id })
        assertEquals(setOf("Arcana"), removed.pinnedSkillIds)
        assertEquals(original.skills, removed.skills)
        assertFailsWith<IllegalArgumentException> { SkillRules.remove(edited, "Arcana") }
    }

    @Test fun oldCharacterJsonDefaultsAdditiveFieldsAndNewFieldsRoundTrip() {
        val old = Json.decodeFromString<ReforgeCharacter>("""{"id":"old","name":"Older sheet","skills":{"Arcana":{"ability":"INT","proficient":true}}}""")
        assertTrue(old.customSkills.isEmpty()); assertTrue(old.pinnedSkillIds.isEmpty())
        assertTrue(old.skills.getValue("Arcana").proficient)
        val current = SkillRules.pin(SkillRules.save(actor(), skill().copy(notes = "Saved note", baselineOverride = -2)), "skill:one", true)
        assertEquals(current, Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(current)))
    }

    @Test fun defaultFieldsStayAbsentFromLegacySnapshotsEvenWhenAllOtherDefaultsAreEncoded() {
        val codec = Json { encodeDefaults = true }
        val original = actor().copy(currentHp = 7)
        val old = codec.encodeToString(original)
        assertFalse(old.contains("\"customSkills\"")); assertFalse(old.contains("\"pinnedSkillIds\""))
        val restored = codec.decodeFromString<ReforgeCharacter>(old)
        val unrelatedWrite = restored.copy(currentHp = 6)
        assertEquals(old.replace("\"currentHp\":7", "\"currentHp\":6"), codec.encodeToString(unrelatedWrite))
        val withSkills = SkillRules.pin(SkillRules.save(restored, skill()), "skill:one", true)
        val written = codec.encodeToString(withSkills)
        assertTrue(written.contains("\"customSkills\"")); assertTrue(written.contains("\"pinnedSkillIds\""))
        assertEquals(withSkills, codec.decodeFromString<ReforgeCharacter>(written))
    }

    @Test fun invalidDraftCannotDamageCharacterOrCreateDuplicateIdentity() {
        val character = actor()
        assertFailsWith<IllegalArgumentException> { SkillRules.save(character, skill(name = " ")) }
        assertFailsWith<IllegalArgumentException> { SkillRules.save(character, skill().copy(bonus = 1001)) }
        assertFailsWith<IllegalArgumentException> { CharacterCalculator.calculate(character.copy(customSkills = listOf(skill(), skill()))) }
        assertFailsWith<IllegalArgumentException> { CharacterCalculator.calculate(character.copy(customSkills = listOf(skill("skill:One"), skill("skill:one")))) }
        assertFailsWith<IllegalArgumentException> { SkillRules.pin(character, "missing", true) }
        assertTrue(character.customSkills.isEmpty())
    }
}
