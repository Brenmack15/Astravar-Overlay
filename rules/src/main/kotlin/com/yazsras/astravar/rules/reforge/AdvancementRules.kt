package com.yazsras.astravar.rules.reforge

/** Advancement only records the player's confirmed choices; it never grants a rulebook's contents. */
object AdvancementRules {
    fun advance(character: ReforgeCharacter, targetLevel: Int, hitDieGain: Int?,
        permanentIncreases: Map<Stat, Int> = emptyMap()): ReforgeCharacter {
        require(targetLevel == character.level + 1 && targetLevel <= 20) { "Advance one level at a time, up to level 20." }
        require(hitDieGain == null || hitDieGain in 1..1000) { "Enter the accepted Hit Die roll or fixed value before Constitution." }
        require(permanentIncreases.values.all { it in 0..10 }) { "Record only the permanent increases earned at this advancement." }
        val baselineGain = if (hitDieGain != null && character.baselineConstitutionModifier != null)
            maxOf(1, hitDieGain + character.baselineConstitutionModifier) else null
        val nextBase = if (character.baseMaxHp != null && baselineGain != null) Math.addExact(character.baseMaxHp, baselineGain) else null
        val next = character.copy(level = targetLevel, baseMaxHp = nextBase,
            permanentAdvancements = character.permanentAdvancements.toMutableMap().apply {
                permanentIncreases.forEach { (stat, increase) -> put(stat, Math.addExact(get(stat) ?: 0, increase)) }
            },
            // This is a pinned, single-class Artificer option. An unknown class level stays unknown.
            spellcastingClassLevel = character.spellcastingClassLevel?.let { if (it == character.level) targetLevel else it })
        return GameplayRules.preserveCapacities(character, next)
    }
}
