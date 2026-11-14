package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable internal data class GenesisSourceAttackReview(
    val profileId: String, val characterRevision: Long, val sheet: PartySheetRecord,
    val fingerprint: String
)
internal data class GenesisSourceAttackCandidate(val source: SheetAttack, val problem: String?)
@Serializable internal data class GenesisSourceAttackChoices(
    val actionType: String, val reach: String, val magical: Boolean,
    val noAdditionalCost: Boolean, val standardCritical: Boolean,
    val sourceConflictsReviewed: Boolean = false
)
internal data class GenesisSourceAttackAssessment(val preview: AttackPreview?, val problem: String?)

/** Reads only the proven, current on-device source identity; also works after baseline adoption. */
internal suspend fun ReforgeRepository.reviewSourceAttacks(
    legacy: CampaignLedgerRepository, saved: ReforgeSavedCharacter
): GenesisSourceAttackReview = withCampaignSnapshot(legacy) {
    check(get(saved.character.id) == saved) { "The character changed. Open source review again." }
    val identity = inspectCampaignIdentity(legacy, saved)
        ?: error("This character has no reviewed campaign source identity.")
    val sheet = legacy.readCharacterSnapshot(identity.profile.id).sheet
        ?: error("This source does not have a saved character sheet.")
    GenesisSourceAttackReview(saved.character.id, saved.revision, sheet,
        reforgeHash(reforgeJson.encodeToString(sheet)))
}

internal fun sourceAttackCandidates(review: GenesisSourceAttackReview): List<GenesisSourceAttackCandidate> {
    require(review.sheet.profileId == review.profileId &&
        reforgeHash(reforgeJson.encodeToString(review.sheet)) == review.fingerprint) { "The source review changed." }
    val sheet = review.sheet.sheet
    val duplicates = sheet.attacks.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    return sheet.attacks.map { attack ->
        val formula = runCatching { SecureDice.parse(attack.damageFormula) }.getOrNull()
        GenesisSourceAttackCandidate(attack, when {
            attack.id in duplicates -> "The source repeats this attack identity."
            attack.evidenceStatus != "Confirmed" -> "${attack.evidenceStatus} mechanics require source confirmation."
            attack.name.isBlank() -> "The attack name is missing."
            runCatching { checkId(attack.id) }.isFailure -> "The source attack identity needs correction."
            attack.attackBonus == null || attack.attackBonus !in -1000..1000 -> "Confirm a supported exact attack bonus."
            attack.resourceCost.isNotBlank() -> "Configure the recorded resource cost before using this attack: ${attack.resourceCost}"
            attack.multiattack.isNotBlank() -> "The recorded multiattack requires separate action setup: ${attack.multiattack}"
            attack.criticalRule.isNotBlank() -> "Custom critical behavior needs manual setup: ${attack.criticalRule}"
            attack.actionEconomy.trim().lowercase() !in setOf("action", "attack", "magic", "bonus action", "reaction") -> "Review the recorded action timing: ${attack.actionEconomy}"
            DamageKind.entries.none { it.name.equals(attack.damageType.trim(), true) } -> "Confirm one supported damage type; mixed damage needs separate components."
            formula == null || formula.dice.isEmpty() && formula.flat < 0 -> "Review the damage formula: ${attack.damageFormula}"
            else -> null
        })
    }
}

internal fun sourceAttackDefinition(review: GenesisSourceAttackReview, attackId: String,
    choices: GenesisSourceAttackChoices): AttackDefinition {
    val candidate = sourceAttackCandidates(review).single { it.source.id == attackId }
    require(candidate.problem == null) { requireNotNull(candidate.problem) }
    val source = candidate.source
    val timing = when (source.actionEconomy.trim().lowercase()) {
        "action", "attack" -> setOf("Action", "Full Action")
        "magic" -> setOf("Full Action")
        "bonus action" -> setOf("Bonus Action")
        "reaction" -> setOf("Reaction")
        else -> emptySet()
    }
    require(choices.actionType in timing) { "Confirm whether this uses one attack or the complete Action." }
    require(choices.reach in setOf("melee", "ranged")) { "Confirm melee or ranged attack behavior." }
    require(choices.noAdditionalCost && choices.standardCritical) { "Review resource cost and critical behavior before adding this attack." }
    require(review.sheet.sheet.unresolvedConflicts.isEmpty() || choices.sourceConflictsReviewed) {
        "Review the source sheet's conflicts and confirm this selected attack's exact mechanics."
    }
    val json = reforgeJson.encodeToString(source)
    return AttackDefinition(source.id, source.name, proficient = false, fixedAttackBonus = source.attackBonus,
        tags = setOf(choices.reach), addAbilityToDamage = false, actionType = choices.actionType, range = source.range,
        damage = listOf(DamageComponent("${source.id}:recorded", "Recorded damage",
            DamageKind.entries.first { it.name.equals(source.damageType.trim(), true) },
            SecureDice.parse(source.damageFormula), magical = choices.magical)),
        recordedSource = RecordedAttackSource(review.profileId, source.id, review.sheet.revision,
            review.sheet.source, json, reforgeHash(json), review.sheet.sheet.unresolvedConflicts))
}

/** Fixed totals may already contain equipment bonuses. Never silently add them a second time. */
internal fun assessSourceAttack(character: ReforgeCharacter, attack: AttackDefinition): GenesisSourceAttackAssessment {
    val collision = character.attacks.firstOrNull { it.id == attack.id }
    if (collision != null) return GenesisSourceAttackAssessment(null,
        if (collision.recordedSource?.attackId == attack.id) "This source attack is already present. Edit its existing Combat entry."
        else "A playable attack already uses this identity. Reconcile the existing entry first.")
    if (character.attacks.any { it.name.trim().equals(attack.name.trim(), true) })
        return GenesisSourceAttackAssessment(null, "A playable attack already has this name. Review it before adding another source identity.")
    val draft = character.copy(attacks = character.attacks + attack, turn = character.turn.copy(tracking = false))
    return try {
        val preview = AttackRules.preview(draft, attack.id)
        val changed = preview.attackBonus.value != attack.fixedAttackBonus || preview.components != attack.damage ||
            preview.effectIds.isNotEmpty() || preview.attemptEffectIds.isNotEmpty()
        GenesisSourceAttackAssessment(preview, if (changed)
            "Active effects change this recorded attack. Reconcile the fixed totals and automatic effects in Features / Equipment before importing; no bonuses were subtracted or added to the source."
            else null)
    } catch (e: IllegalArgumentException) {
        GenesisSourceAttackAssessment(null, e.message ?: "The current character cannot preview this attack.")
    }
}

internal fun validateRecordedAttackSources(character: ReforgeCharacter) {
    character.attacks.forEach { attack -> attack.recordedSource?.let { source ->
        require(reforgeHash(source.attackJson) == source.fingerprint) { "Recorded attack source checksum differs." }
        require(reforgeJson.decodeFromString<SheetAttack>(source.attackJson).id == source.attackId) { "Recorded attack source identity differs." }
    } }
}

/** Exact source + canonical character + receipt are checked in the same production transaction. */
internal suspend fun ReforgeRepository.commitSourceAttack(
    legacy: CampaignLedgerRepository, review: GenesisSourceAttackReview, attackId: String,
    choices: GenesisSourceAttackChoices, commandId: String = UUID.randomUUID().toString()
): ReforgeSavedCharacter = withCampaignSnapshot(legacy) {
    val incoming = sourceAttackDefinition(review, attackId, choices)
    val operation = "Add reviewed source attack"
    commandReceipt(commandId)?.let { receipt ->
        require(receipt.profileId == review.profileId && receipt.operation == operation && receipt.priorRevision == review.characterRevision) { "This command identity belongs to another operation." }
        val before = reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(receipt.beforeJson))
        val after = reforgeJson.decodeFromString<ReforgeSavedCharacter>(receipt.afterJson)
        require(after.character.copy(revision = before.revision) == before.character.copy(attacks = before.character.attacks + incoming)) { "This retry contains a different attack interpretation." }
        return@withCampaignSnapshot after
    }
    val current = requireNotNull(get(review.profileId)) { "The playable character is unavailable." }
    check(!current.character.archived && current.revision == review.characterRevision) { "The character changed. Review its latest state." }
    val latest = reviewSourceAttacks(legacy, current)
    check(latest == review) { "The source sheet changed. Review the latest recorded attack." }
    val assessment = assessSourceAttack(current.character, incoming)
    require(assessment.problem == null) { requireNotNull(assessment.problem) }
    save(review.profileId, review.characterRevision,
        current.character.copy(attacks = current.character.attacks + incoming), operation, commandId)
}
