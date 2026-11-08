package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import java.util.UUID

data class ReforgeCompanionActionCandidate(val attack: AttackDefinition, val source: String)
data class ReforgeCompanionActionReview(
    val profileId: String, val legacyFingerprint: String,
    val candidates: List<ReforgeCompanionActionCandidate>, val unavailable: List<Pair<String, String>>,
    val sourceSnapshotJson: String
)

/** Strict reviewed conversion, never extraction from prose or a generic creature stat block. */
fun reviewCompanionActions(preview: ReforgeAdoptionPreview): ReforgeCompanionActionReview {
    require(reforgeHash(preview.sourceJson) == preview.legacyFingerprint) { "The source snapshot changed." }
    return companionReviewFromSnapshot(preview.sourceJson)
}

private fun companionReviewFromSnapshot(sourceJson: String): ReforgeCompanionActionReview {
    val source = reforgeJson.decodeFromString<ReforgeLegacySnapshot>(sourceJson)
    val record = source.sheet
    val sheet = record?.sheet
    val duplicates = sheet?.attacks.orEmpty().groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    val candidates = mutableListOf<ReforgeCompanionActionCandidate>()
    val unavailable = mutableListOf<Pair<String, String>>()
    sheet?.attacks.orEmpty().forEach { attack ->
        val actionType = when (attack.actionEconomy.trim().lowercase()) {
            "action" -> "Action"
            "bonus action" -> "Bonus Action"
            "reaction" -> "Reaction"
            else -> null
        }
        val problem = when {
            sheet!!.unresolvedConflicts.isNotEmpty() -> "Resolve the source sheet's outstanding conflicts first."
            attack.id in duplicates -> "The source repeats this attack identity."
            attack.evidenceStatus != "Confirmed" -> "${attack.evidenceStatus} mechanics are not activated."
            attack.name.isBlank() -> "Attack name is missing."
            attack.attackBonus == null -> "The attack bonus is unknown."
            attack.resourceCost.isNotBlank() -> "Resource cost needs explicit structured setup: ${attack.resourceCost}"
            attack.multiattack.isNotBlank() -> "Multiattack sequence needs explicit structured setup: ${attack.multiattack}"
            attack.criticalRule.isNotBlank() -> "Custom critical behavior needs explicit structured setup: ${attack.criticalRule}"
            actionType == null -> "Unrecognized action timing: ${attack.actionEconomy}"
            DamageKind.entries.none { it.name.equals(attack.damageType.trim(), true) } -> "Damage must specify one recognized type."
            else -> null
        }
        if (problem != null) unavailable += attack.name.ifBlank { "Unnamed attack" } to problem
        else {
            val formula = runCatching { SecureDice.parse(attack.damageFormula) }.getOrNull()
            if (formula == null || formula.dice.isEmpty() && formula.flat < 0) unavailable += attack.name to "Damage formula needs review: ${attack.damageFormula}"
            else candidates += ReforgeCompanionActionCandidate(AttackDefinition(
                id = attack.id, name = attack.name, proficient = false, fixedAttackBonus = attack.attackBonus,
                damage = listOf(DamageComponent("${attack.id}:source-damage", "Recorded damage", DamageKind.entries.first { it.name.equals(attack.damageType.trim(), true) }, formula)),
                addAbilityToDamage = false, actionType = requireNotNull(actionType), range = attack.range
            ), attack.source.ifBlank { record?.source.orEmpty() })
        }
    }
    return ReforgeCompanionActionReview(source.profile.id, reforgeHash(sourceJson), candidates, unavailable, sourceJson)
}

/** Recheck both source and local revisions; append only exact selected reviewed attacks. */
suspend fun ReforgeRepository.commitCompanionActions(
    legacy: CampaignLedgerRepository, review: ReforgeCompanionActionReview,
    selectedIds: Set<String>, expectedRevision: Long, commandId: String = UUID.randomUUID().toString()
): ReforgeSavedCharacter {
    require(selectedIds.isNotEmpty()) { "Select at least one confirmed attack." }
    require(reforgeHash(review.sourceSnapshotJson) == review.legacyFingerprint) { "The reviewed source snapshot changed." }
    val canonical = companionReviewFromSnapshot(review.sourceSnapshotJson)
    require(canonical == review) { "The reviewed interpretation changed." }
    val selected = canonical.candidates.filter { it.attack.id in selectedIds }.map { it.attack }
    require(selected.map { it.id }.toSet() == selectedIds) { "An unverified attack cannot be activated." }
    commandReceipt(commandId)?.let { receipt ->
        require(receipt.profileId == review.profileId && receipt.operation == "Add reviewed companion attacks" && receipt.priorRevision == expectedRevision) { "Command identity was reused for another operation." }
        val before = reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(receipt.beforeJson))
        val after = reforgeJson.decodeFromString<ReforgeSavedCharacter>(receipt.afterJson)
        selected.forEach { incoming ->
            val existing = before.character.attacks.firstOrNull { it.id == incoming.id }
            require(existing == null || existing == incoming) { "Command identity was reused with a different attack." }
        }
        val additions = selected.filter { incoming -> before.character.attacks.none { it.id == incoming.id } }
        val intended = before.character.copy(attacks = before.character.attacks + additions)
        require(after.character.copy(revision = before.revision) == intended) { "Command identity was reused with different attacks." }
        return after
    }
    val latestPreview = readLegacyPreview(legacy, review.profileId)
    check(latestPreview.legacyFingerprint == review.legacyFingerprint) { "The companion source changed. Review it again." }
    val current = get(review.profileId) ?: error("Review and adopt this companion in Character before activating attacks.")
    check(!current.character.archived) { "Restore the archived playable companion before adding attacks." }
    check(current.revision == expectedRevision) { "The playable companion changed. Review its current state." }
    selected.forEach { incoming ->
        val existing = current.character.attacks.firstOrNull { it.id == incoming.id }
        require(existing == null || existing == incoming) { "A different local attack uses this identity. Reconcile it before adding source mechanics." }
    }
    val additions = selected.filter { incoming -> current.character.attacks.none { it.id == incoming.id } }
    if (additions.isEmpty()) return current
    return save(review.profileId, expectedRevision, current.character.copy(attacks = current.character.attacks + additions),
        "Add reviewed companion attacks", commandId)
}
