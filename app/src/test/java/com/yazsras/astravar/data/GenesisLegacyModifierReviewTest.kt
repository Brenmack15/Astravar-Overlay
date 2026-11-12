package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class GenesisLegacyModifierReviewTest {
    private val source = CharacterAbility("STR", "modifier-player", 8, 5, notes = "Old accounting", source = "Fictional original")
    private val effect = EffectDefinition("strength-set", "Reviewed strength", RuleSource("fictional", "Reviewed fixture", Acceptance.ACCEPTED_HOMEBREW),
        EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 19, "STR"))
    private val character = ReforgeCharacter(source.profileId, "Fictional modifier review", revision = 3,
        baseAbilities = mapOf(Stat.STR to 8), effects = listOf(effect))
    private val ref = GenesisLegacyModifierEffectRef(effect.id)
    private val decision = GenesisLegacyModifierDecision.SUMMARIZED_EXISTING_EFFECTS
    private fun proof(c: ReforgeCharacter = character, s: CharacterAbility = source, refs: List<GenesisLegacyModifierEffectRef> = listOf(ref)) =
        genesisReviewLegacyModifier(c, s, refs, decision)
    private fun rejects(block: () -> Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }

    @Test fun explicitAcceptedEffectExplainsExactBaselineWithoutChangingMechanics() {
        val result = proof()
        assertEquals(source, result.sourceAbility)
        assertEquals(19, result.effectiveScore); assertEquals(4, result.effectiveModifier)
        assertEquals(8, result.withoutSelectedScore); assertEquals(-1, result.withoutSelectedModifier)
        assertEquals(listOf(ref), result.effects)
        genesisValidateLegacyModifierResolutions(character, listOf(source), listOf(result))
        assertEquals(listOf(effect), character.effects)
        assertNull(character.baselineConstitutionModifier)
        assertTrue(CharacterCalculator.calculate(character.copy(baseMaxHp = 20)).warnings.any { it.contains("Constitution is unknown") })
    }
    @Test fun noSelectionNeverMeansAutomaticAgreement() { rejects { proof(refs = emptyList()) }; rejects { genesisValidateLegacyModifierResolutions(character, listOf(source), emptyList()) } }
    @Test fun unknownOriginalScoreIsNotInferredFromEffectiveValue() { rejects { proof(s = source.copy(score = null)) } }
    @Test fun sameModifierButDifferentOriginalScoreIsInsufficient() {
        rejects { proof(character.copy(baseAbilities = mapOf(Stat.STR to 9))) }
    }
    @Test fun incorrectRecordedTotalRemainsUnresolved() { rejects { proof(s = source.copy(magicModifier = 4)) } }
    @Test fun trueFlatModifierCannotBeConvertedToAnAbilityEffect() { rejects { proof(character.copy(effects = emptyList())) } }
    @Test fun proposedUnverifiedRetiredAndDisabledEffectsCannotBeSelected() {
        listOf(Acceptance.PROPOSED, Acceptance.UNVERIFIED, Acceptance.RETIRED).forEach { acceptance ->
            val c = character.copy(effects = listOf(effect.copy(source = effect.source.copy(acceptance = acceptance))))
            assertTrue(genesisLegacyModifierCandidates(c, source).isEmpty()); rejects { proof(c) }
        }
        rejects { proof(character.copy(effects = listOf(effect.copy(enabled = false)))) }
    }
    @Test fun unavailableContextAndExpiredEffectsCannotBeSelected() {
        listOf(effect.copy(conditions = listOf(EffectCondition(ConditionKind.CONTEXT_TAG, "unconfirmed"))),
            effect.copy(expiresAtGameSeconds = 0)).forEach { rejects { proof(character.copy(effects = listOf(it))) } }
    }
    @Test fun overriddenSetIsNotCausalEvenIfEngineListsItActive() {
        val ignored = effect.copy(id = "ignored", priority = -1, payload = EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 12, "STR"))
        val c = character.copy(effects = listOf(effect, ignored))
        assertEquals(listOf(ref), genesisLegacyModifierCandidates(c, source).map { it.reference })
        rejects { proof(c, refs = listOf(ref, GenesisLegacyModifierEffectRef(ignored.id))) }
    }
    @Test fun exactStatAndActorIdentityAreRequired() {
        rejects { proof(s = source.copy(profileId = "someone-else")) }
        rejects { proof(s = source.copy(key = "Strength")) }
        rejects { proof(character.copy(effects = listOf(effect.copy(payload = EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 19))))) }
        rejects { proof(character.copy(effects = listOf(effect.copy(payload = EffectPayload.Number(NumericTarget.SAVE, NumberOperation.SET, 19, "STR"))))) }
    }
    @Test fun duplicateAndWrongPhysicalReferencesReject() {
        rejects { proof(refs = listOf(ref, ref)) }
        rejects { proof(refs = listOf(ref.copy(itemInstanceId = "not-an-item"))) }
    }
    @Test fun equipmentReferenceRequiresOneAvailableAttunedPhysicalInstance() {
        val definition = ItemDefinition("strength-item", "Fictional gauntlets", effects = listOf(effect), requiresAttunement = true)
        val item = ItemInstance("physical-gauntlets", definition.id, ItemState.WORN, attuned = true)
        val c = character.copy(effects = emptyList(), itemDefinitions = listOf(definition), items = listOf(item))
        val physical = ref.copy(itemInstanceId = item.id)
        assertEquals(listOf(physical), proof(c, refs = listOf(physical)).effects)
        listOf(item.copy(state = ItemState.CARRIED), item.copy(attuned = false), item.copy(quantity = 0), item.copy(operating = false)).forEach {
            rejects { proof(c.copy(items = listOf(it)), refs = listOf(physical)) }
        }
        rejects { proof(c.copy(items = listOf(item, item.copy(id = "another-instance", state = ItemState.CARRIED))), refs = listOf(physical)) }
    }
    @Test fun capsCanExplainAnExplicitNegativeSummaryWithoutInventingNewEffects() {
        val cap = effect.copy(payload = EffectPayload.Number(NumericTarget.ABILITY_CAP, NumberOperation.SET, 18, "STR"))
        val c = character.copy(baseAbilities = mapOf(Stat.STR to 20), effects = listOf(cap))
        val result = proof(c, source.copy(score = 20, magicModifier = -1))
        assertEquals(18, result.effectiveScore); assertEquals(20, result.withoutSelectedScore)
    }
    @Test fun allOldModifiersNeedDistinctCompleteProofs() {
        val strength = proof()
        rejects { genesisValidateLegacyModifierResolutions(character, listOf(source, source.copy(key = "DEX")), listOf(strength)) }
        rejects { genesisValidateLegacyModifierResolutions(character, listOf(source), listOf(strength, strength)) }
        rejects { genesisValidateLegacyModifierResolutions(character, listOf(source, source), listOf(strength)) }
        rejects { genesisValidateLegacyModifierResolutions(character, emptyList(), listOf(strength)) }
    }
    @Test fun alteredStoredProofAndChangedSourceOrRevisionReject() {
        val original = proof()
        rejects { genesisValidateLegacyModifierResolutions(character, listOf(source), listOf(original.copy(effectiveScore = 20))) }
        rejects { genesisValidateLegacyModifierResolutions(character, listOf(source.copy(notes = "Later source")), listOf(original)) }
        rejects { genesisValidateLegacyModifierResolutions(character.copy(revision = 4), listOf(source), listOf(original)) }
    }
    @Test fun zeroModifiersNeedNoProofAndCannotBeGivenAnInventedResolution() {
        genesisValidateLegacyModifierResolutions(character, listOf(source.copy(magicModifier = 0)), emptyList())
        rejects { proof(s = source.copy(magicModifier = 0)) }
    }

    @Serializable private data class PreModifierReview(
        val actorId: String, val role: GenesisActorRole, val campaignId: String?, val ownerActorId: String?,
        val expectedAuthorityRevision: Long?, val playable: ReforgeSavedCharacter, val source: ReforgeLegacySnapshot?,
        val differences: List<GenesisFieldDifference>, val fingerprint: String, val supplementalSourceJson: String = ""
    )
    @Test fun emptyResolutionPreservesPreFieldBytesFingerprintAndDecode() {
        val saved = ReforgeSavedCharacter(character, character.revision, ReforgeOrigin(), 100, 200)
        val old = PreModifierReview(character.id, GenesisActorRole.PLAYER, null, null, null, saved, null, emptyList(), "", "[null]")
        val originalBytes = campaignJson.encodeToString(old)
        val decoded = campaignJson.decodeFromString<GenesisActorAuthorityReview>(originalBytes)
        assertTrue(decoded.legacyModifierResolutions.isEmpty())
        assertEquals(originalBytes, campaignJson.encodeToString(decoded))
        assertEquals(campaignHash(originalBytes), genesisActorAuthorityReviewFingerprint(decoded))
        assertFalse(originalBytes.contains("legacyModifierResolutions"))
    }
}
