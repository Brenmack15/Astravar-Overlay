package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import java.util.UUID

/** Small, explicit bridge from retained source evidence into the existing Combat attack engine. */
@Composable internal fun GenesisSourceAttackReviewButton(
    repository: CampaignLedgerRepository, local: ReforgeRepository, saved: ReforgeSavedCharacter
) {
    val reviewDraft = rememberReforgeDraft<GenesisSourceAttackReview?>(null, GenesisSourceAttackReview.serializer().nullable)
    var review by reviewDraft
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    PlayerOutlinedButton(enabled = !busy, modifier = Modifier.testTag("genesis-source-attacks"), onClick = {
        busy = true; message = ""
        scope.launch {
            try { review = local.reviewSourceAttacks(repository, saved) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "The source could not be read." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Reading source…" else "Review source attacks") }
    if (message.isNotBlank()) Text(message, color = RebuildPalette.Muted)
    review?.let { current -> key(current.profileId, current.characterRevision, current.fingerprint) {
        val commandId = rememberSaveable { UUID.randomUUID().toString() }
        GenesisSourceAttackReviewSheet(current, saved.character, busy, message,
            onDismiss = { if (!busy) review = null }, onSave = { attackId, choices ->
                busy = true; message = ""
                scope.launch {
                    try {
                        local.commitSourceAttack(repository, current, attackId, choices, commandId)
                        review = null; message = "Source attack saved in Combat. Its original evidence is retained."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "The attack was not saved." }
                    finally { busy = false }
                }
            })
    } }
}

@Composable internal fun GenesisSourceAttackReviewSheet(review: GenesisSourceAttackReview,
    character: ReforgeCharacter, busy: Boolean, error: String, onDismiss: () -> Unit,
    onSave: (String, GenesisSourceAttackChoices) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var timing by rememberSaveable(selected) { mutableStateOf("") }
    var reach by rememberSaveable(selected) { mutableStateOf("") }
    var magical by rememberSaveable(selected) { mutableStateOf("") }
    var noCost by rememberSaveable(selected) { mutableStateOf(false) }
    var standardCritical by rememberSaveable(selected) { mutableStateOf(false) }
    var conflictsReviewed by rememberSaveable(selected) { mutableStateOf(false) }
    var approved by rememberSaveable(selected, timing, reach, magical, noCost, standardCritical, conflictsReviewed) { mutableStateOf(false) }
    val candidates = remember(review) { sourceAttackCandidates(review) }
    val source = candidates.singleOrNull { it.source.id == selected }
    val stale = character.id != review.profileId || character.revision != review.characterRevision
    val choices = GenesisSourceAttackChoices(timing, reach.lowercase(), magical == "Magical", noCost, standardCritical, conflictsReviewed)
    val attack = if (source != null && magical.isNotBlank()) runCatching { sourceAttackDefinition(review, source.source.id, choices) }.getOrNull() else null
    val assessment = attack?.let { assessSourceAttack(character, it) }
    PlayerSheet("Review source attacks", onDismiss, dirty = selected != null, dismissEnabled = !busy, actions = {
        PlayerButton(enabled = !busy && !stale && approved && attack != null && assessment?.problem == null,
            modifier = Modifier.testTag("genesis-source-attack-save"), onClick = { onSave(requireNotNull(selected), choices) }) {
            Text(if (busy) "Saving…" else "Add reviewed attack")
        }
    }) {
        Text("These are saved on-device source records. Review each entry before making it playable. Complete recorded bonuses stay fixed; ability and proficiency are not added again.", color = RebuildPalette.Muted)
        if (review.sheet.sheet.unresolvedConflicts.isNotEmpty()) PlayerDisclosure("Source conflicts to review", initiallyOpen = true) {
            review.sheet.sheet.unresolvedConflicts.forEach { Text(it, color = RebuildPalette.GoldBright) }
            Text("Other unknown sheet values do not replace this attack. If a conflict questions its numbers, costs, or effects, resolve that conflict before confirming the entry.", color = RebuildPalette.Muted)
        }
        if (stale) Text("The character changed. Close and reopen this review.", color = MaterialTheme.colorScheme.error)
        if (candidates.isEmpty()) Text("The source sheet contains no recorded attacks.", color = RebuildPalette.Muted)
        candidates.forEach { candidate ->
            val row = candidate.source
            val existing = character.attacks.firstOrNull { it.id == row.id || it.name.trim().equals(row.name.trim(), true) }
            PlayerCard {
                Text(row.name.ifBlank { "Unnamed attack" }, style = MaterialTheme.typography.titleMedium)
                Text("Recorded to hit ${reforgeSigned(row.attackBonus)} · ${row.damageFormula} ${row.damageType}")
                Text("${row.actionEconomy} · ${row.range.ifBlank { "Range not recorded" }}", color = RebuildPalette.Muted)
                if (candidate.problem != null) Text(candidate.problem, color = RebuildPalette.GoldBright)
                else if (existing != null) Text("Already represented in Combat as ${existing.name}. Review its existing entry; no duplicate will be added.", color = RebuildPalette.Muted)
                else PlayerOutlinedButton(enabled = !busy && !stale, onClick = { selected = row.id },
                    modifier = Modifier.testTag("review-source-attack:${row.id}")) { Text(if (selected == row.id) "Selected for review" else "Review this attack") }
                PlayerDisclosure("Recorded evidence") {
                    Text("Status · ${row.evidenceStatus}")
                    Text("Source · ${row.source.ifBlank { review.sheet.source }}")
                    Text("Resource cost · ${row.resourceCost.ifBlank { "Not recorded" }}")
                    Text("Critical behavior · ${row.criticalRule.ifBlank { "Not recorded" }}")
                    if (row.multiattack.isNotBlank()) Text("Multiattack · ${row.multiattack}")
                    Text("Source sheet revision ${review.sheet.revision}", color = RebuildPalette.Muted)
                }
            }
        }
        if (source != null) {
            HorizontalDivider()
            Text("Confirm ${source.source.name}", style = MaterialTheme.typography.titleLarge)
            val timings = when (source.source.actionEconomy.trim().lowercase()) {
                "action", "attack" -> listOf("Action", "Full Action")
                "magic" -> listOf("Full Action")
                "bonus action" -> listOf("Bonus Action")
                else -> listOf("Reaction")
            }
            Column(Modifier.testTag("source-attack-action")) {
                Choices("Action use", timings, timing.ifBlank { null }) { timing = it }
            }
            Text("Action uses one attack from your Attack action. Full Action uses the whole Action, including a spell cast with an Action. Choose the accepted behavior.", color = RebuildPalette.Muted)
            Column(Modifier.testTag("source-attack-reach")) {
                Choices("Attack reach", listOf("Melee", "Ranged"), reach.ifBlank { null }) { reach = it }
            }
            Column(Modifier.testTag("source-attack-nature")) {
                Choices("Damage nature", listOf("Magical", "Nonmagical"), magical.ifBlank { null }) { magical = it }
            }
            Toggle("Confirmed: no additional slot, charge, or resource cost", noCost, enabled = !busy) { noCost = it }
            Toggle("Confirmed: critical hits double damage dice, keeping flat bonuses", standardCritical, enabled = !busy) { standardCritical = it }
            if (review.sheet.sheet.unresolvedConflicts.isNotEmpty()) Toggle(
                "I reviewed the source conflicts; this selected attack's exact mechanics are confirmed",
                conflictsReviewed, enabled = !busy) { conflictsReviewed = it }
            Text("No equipment prerequisite, spell slot, armor mode, saving throw, or prose effect is inferred. Use Spells or Armor for their configured resource-backed actions.", color = RebuildPalette.Muted)
            assessment?.preview?.let { preview ->
                Text("Calculated now · to hit ${reforgeSigned(preview.attackBonus.value)}", color = RebuildPalette.Ice)
                Text(preview.components.joinToString(" + ") { "${SecureDice.format(it.formula)} ${it.type.name.lowercase()}" })
            }
            assessment?.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Future structured effects still apply. Review fixed totals when changing equipment or features. Original source evidence stays available on the attack.", color = RebuildPalette.Muted)
            Toggle("I reviewed the complete attack and its calculated totals", approved,
                enabled = !busy && !stale && attack != null && assessment?.problem == null) { approved = it }
        }
        if (candidates.any { it.problem != null }) Text("Entries needing setup remain preserved. Use Combat → Add attack for reviewed exact formulas, or Spells / Features for resource-backed mechanics; do not roll an unresolved entry.", color = RebuildPalette.Muted)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun GenesisRecordedAttackEvidence(attack: AttackDefinition) {
    attack.recordedSource?.let { source ->
        PlayerDisclosure("Original attack evidence") {
            val row = remember(source) { runCatching { reforgeJson.decodeFromString<SheetAttack>(source.attackJson) }.getOrNull() }
            if (row != null) {
                Text("${row.name} · to hit ${reforgeSigned(row.attackBonus)} · ${row.damageFormula} ${row.damageType}")
                Text("${row.actionEconomy} · ${row.range}")
                Text(row.source.ifBlank { source.sheetSource })
                if (row.resourceCost.isNotBlank()) Text("Resource cost · ${row.resourceCost}")
                if (row.criticalRule.isNotBlank()) Text("Critical · ${row.criticalRule}")
            }
            Text("Recorded source revision ${source.sheetRevision}. Current playable values may have been explicitly edited.", color = RebuildPalette.Muted)
            if (source.reviewedSheetConflicts.isNotEmpty()) {
                Text("Sheet conflicts reviewed for this import", style = MaterialTheme.typography.labelLarge)
                source.reviewedSheetConflicts.forEach { Text(it, color = RebuildPalette.Muted) }
            }
        }
    }
}
