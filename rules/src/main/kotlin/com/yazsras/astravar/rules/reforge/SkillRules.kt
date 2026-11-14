package com.yazsras.astravar.rules.reforge

import java.util.Locale

/** Skill presentation and edits share identities with calculations, rolls and backup data. */
object SkillRules {
    data class Entry(
        val id: String, val name: String, val training: SkillTraining,
        val custom: Boolean, val pinned: Boolean, val calculation: CalculatedNumber
    )

    fun entries(character: ReforgeCharacter, projection: CharacterProjection = CharacterCalculator.calculate(character)): List<Entry> {
        val ordinary = (CharacterCalculator.standardSkills.mapValues { SkillTraining(it.value) } + character.skills)
            .map { (name, training) -> Entry(name, name, training, false, name in character.pinnedSkillIds,
                projection.skills.getValue(name)) }
        val custom = character.customSkills.map { skill -> Entry(skill.id, skill.name, skill.training, true,
            skill.id in character.pinnedSkillIds, projection.customSkills.getValue(skill.id)) }
        return (ordinary + custom).sortedWith(compareByDescending<Entry> { it.pinned }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }

    fun pin(character: ReforgeCharacter, id: String, pinned: Boolean): ReforgeCharacter {
        require(id in CharacterCalculator.standardSkills || id in character.skills || character.customSkills.any { it.id == id }) {
            "That skill is no longer available."
        }
        return character.copy(pinnedSkillIds = if (pinned) character.pinnedSkillIds + id else character.pinnedSkillIds - id)
    }

    fun save(character: ReforgeCharacter, skill: CustomSkill): ReforgeCharacter {
        val normalized = skill.copy(name = skill.name.trim())
        val next = character.copy(customSkills = if (character.customSkills.any { it.id == skill.id })
            character.customSkills.map { if (it.id == skill.id) normalized else it }
        else character.customSkills + normalized)
        validate(next)
        return next
    }

    fun remove(character: ReforgeCharacter, id: String): ReforgeCharacter {
        require(character.customSkills.any { it.id == id }) { "That custom skill is no longer available." }
        // Historical rolls and sourced effects stay intact; only this live definition/pin is removed.
        return character.copy(customSkills = character.customSkills.filterNot { it.id == id }, pinnedSkillIds = character.pinnedSkillIds - id)
    }

    fun validate(character: ReforgeCharacter) {
        require(character.customSkills.size <= 500) { "A character supports up to 500 custom skills." }
        require(character.customSkills.map { it.id.lowercase(Locale.ROOT) }.distinct().size == character.customSkills.size) { "Custom skill identities must be unique." }
        character.customSkills.forEach { skill ->
            require(skill.id.startsWith("skill:") && skill.id.length in 7..160 && skill.id.none { it.isISOControl() } &&
                character.skills.keys.none { it.equals(skill.id, true) } && skill.id !in CharacterCalculator.standardSkills) { "Invalid custom skill identity." }
            require(skill.name.isNotBlank() && skill.name.length <= 100 && skill.name.none { it.isISOControl() }) { "Enter a skill name of up to 100 characters." }
            require(skill.bonus in -1000..1000 && (skill.baselineOverride == null || skill.baselineOverride in -1000..1000)) { "Skill bonuses and overrides must be between −1000 and 1000." }
            require(skill.notes.length <= 10_000) { "Skill notes are too long." }
        }
        require(character.pinnedSkillIds.size <= 1000 && character.pinnedSkillIds.all {
            it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl)
        }) { "Invalid pinned skill identity." }
    }
}
