package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable

/** A physical effect reference, not a display name or a guessed source association. */
@Serializable data class GenesisLegacyModifierEffectRef(val effectId: String, val itemInstanceId: String? = null)
@Serializable enum class GenesisLegacyModifierDecision { SUMMARIZED_EXISTING_EFFECTS }
@Serializable data class GenesisLegacyModifierResolution(
    val sourceAbility: CharacterAbility,
    val playableRevision: Long,
    val decision: GenesisLegacyModifierDecision,
    val effects: List<GenesisLegacyModifierEffectRef>,
    val effectiveScore: Int,
    val effectiveModifier: Int,
    val withoutSelectedScore: Int,
    val withoutSelectedModifier: Int
)
data class GenesisLegacyModifierCandidate(val reference: GenesisLegacyModifierEffectRef, val effect: EffectDefinition)

private fun legacyStat(character: ReforgeCharacter, source: CharacterAbility): Stat {
    require(source.profileId == character.id) { "The old ability belongs to a different actor." }
    return Stat.entries.singleOrNull { it.name == source.key }
        ?: error("The old ability has no supported exact stat identity.")
}

/** Hypothetical calculation only. The persisted character, definitions and source rows never change here. */
private fun withoutLegacyEffects(character: ReforgeCharacter, refs: Set<GenesisLegacyModifierEffectRef>): ReforgeCharacter =
    character.copy(
        effects = character.effects.map { if (GenesisLegacyModifierEffectRef(it.id) in refs) it.copy(enabled = false) else it },
        itemDefinitions = character.itemDefinitions.map { definition ->
            val instance = character.items.singleOrNull { it.definitionId == definition.id }
            if (instance == null) definition else definition.copy(effects = definition.effects.map {
                if (GenesisLegacyModifierEffectRef(it.id, instance.id) in refs) it.copy(enabled = false) else it
            })
        }
    )

/** Only accepted, currently active, causally effective entries for this exact ability can be selected. */
internal fun genesisLegacyModifierCandidates(character: ReforgeCharacter, source: CharacterAbility): List<GenesisLegacyModifierCandidate> {
    val stat = legacyStat(character, source)
    val calculated = CharacterCalculator.calculate(character)
    val score = calculated.abilities.getValue(stat).effective.value ?: return emptyList()
    fun eligible(effect: EffectDefinition, instanceId: String?): Boolean {
        val number = effect.payload as? EffectPayload.Number ?: return false
        return number.target in setOf(NumericTarget.ABILITY, NumericTarget.ABILITY_CAP) && number.key == stat.name &&
            effect.source.acceptance in setOf(Acceptance.OFFICIAL, Acceptance.ACCEPTED_HOMEBREW) &&
            calculated.effectStatuses.singleOrNull { it.effectId == effect.id && it.itemInstanceId == instanceId }?.active == true
    }
    val candidates = buildList {
        character.effects.filter { eligible(it, null) }.forEach { add(GenesisLegacyModifierCandidate(GenesisLegacyModifierEffectRef(it.id), it)) }
        character.itemDefinitions.forEach { definition ->
            // Disabling a shared definition would also affect another physical instance. Do not guess.
            val instance = character.items.singleOrNull { it.definitionId == definition.id } ?: return@forEach
            definition.effects.filter { eligible(it, instance.id) }.forEach {
                add(GenesisLegacyModifierCandidate(GenesisLegacyModifierEffectRef(it.id, instance.id), it))
            }
        }
    }
    return candidates.filter {
        CharacterCalculator.calculate(withoutLegacyEffects(character, setOf(it.reference)))
            .abilities.getValue(stat).effective.value != score
    }.sortedWith(compareBy({ it.reference.itemInstanceId.orEmpty() }, { it.reference.effectId }))
}

/** The declaration records a user's explanation of old accounting; it does not approve or create an effect. */
internal fun genesisReviewLegacyModifier(
    character: ReforgeCharacter, source: CharacterAbility, selected: List<GenesisLegacyModifierEffectRef>,
    decision: GenesisLegacyModifierDecision
): GenesisLegacyModifierResolution {
    val stat = legacyStat(character, source)
    require(source.magicModifier != 0) { "This old ability has no manual modifier to reconcile." }
    val originalScore = requireNotNull(source.score) { "The original ability score is unknown. Review it without inventing a baseline." }
    require(originalScore in 0..100) { "The original ability score is outside the supported range." }
    require(selected.isNotEmpty() && selected.distinct().size == selected.size) { "Explicitly select each explaining effect once." }
    require(decision == GenesisLegacyModifierDecision.SUMMARIZED_EXISTING_EFFECTS)
    val candidates = genesisLegacyModifierCandidates(character, source).map { it.reference }.toSet()
    require(selected.all { it in candidates }) { "Every selected effect must be accepted, active, causal and bound to one exact physical instance." }
    val current = CharacterCalculator.calculate(character).abilities.getValue(stat)
    val effectiveScore = requireNotNull(current.effective.value) { "The effective ability score is unknown." }
    val effectiveModifier = requireNotNull(current.modifier)
    val originalModifier = CharacterCalculator.abilityModifier(originalScore)
    require(effectiveModifier == Math.addExact(originalModifier, source.magicModifier)) {
        "These effects do not explain the original recorded total modifier. Keep the source unresolved."
    }
    val without = CharacterCalculator.calculate(withoutLegacyEffects(character, selected.toSet())).abilities.getValue(stat)
    require(without.effective.value == originalScore && without.modifier == originalModifier) {
        "Removing the selected effects must recover both the exact original score and its modifier."
    }
    return GenesisLegacyModifierResolution(source, character.revision, decision,
        selected.sortedWith(compareBy({ it.itemInstanceId.orEmpty() }, { it.effectId })), effectiveScore, effectiveModifier,
        originalScore, originalModifier)
}

/** Full coverage is required at acceptance and during historical backup validation, never inferred. */
internal fun genesisValidateLegacyModifierResolutions(
    character: ReforgeCharacter, abilities: List<CharacterAbility>, resolutions: List<GenesisLegacyModifierResolution>
) {
    require(abilities.map { it.id }.distinct().size == abilities.size) { "The source contains duplicate ability identities." }
    val required = abilities.filter { it.magicModifier != 0 }
    require(resolutions.map { it.sourceAbility.id }.distinct().size == resolutions.size &&
        resolutions.map { it.sourceAbility.id }.toSet() == required.map { it.id }.toSet()) {
        "Every old manual magical modifier needs its own explicit existing-effects review."
    }
    resolutions.forEach { resolution ->
        val source = required.single { it.id == resolution.sourceAbility.id }
        require(source == resolution.sourceAbility && resolution.playableRevision == character.revision) { "The modifier proof is stale or belongs to different source values." }
        require(genesisReviewLegacyModifier(character, source, resolution.effects, resolution.decision) == resolution) {
            "The modifier proof differs from the frozen source and playable calculation."
        }
    }
}

internal fun genesisLegacyModifiersReviewed(review: GenesisActorAuthorityReview): Boolean = runCatching {
    genesisValidateLegacyModifierResolutions(review.playable.character, review.source?.abilities.orEmpty(), review.legacyModifierResolutions)
}.isSuccess
