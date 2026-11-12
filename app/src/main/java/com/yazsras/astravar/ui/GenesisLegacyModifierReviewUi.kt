package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*

/** Selections explain existing accounting only. The parent obtains a fresh frozen repository review. */
@Composable internal fun GenesisLegacyModifierReviewEditor(
    review: GenesisActorAuthorityReview,
    busy: Boolean,
    onResolved: (List<GenesisLegacyModifierResolution>) -> Unit
) {
    val required = review.source?.abilities.orEmpty().filter { it.magicModifier != 0 }
    if (required.isEmpty()) return
    val character = review.playable.character
    var selected by remember(review.fingerprint) { mutableStateOf<Map<String, List<GenesisLegacyModifierEffectRef>>>(emptyMap()) }
    var declaration by remember(review.fingerprint) { mutableStateOf(false) }
    var error by remember(review.fingerprint) { mutableStateOf("") }
    val resolved = genesisLegacyModifiersReviewed(review)
    Column(Modifier.fillMaxWidth().testTag("genesis-legacy-modifier-review"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Review recorded modifiers", style = MaterialTheme.typography.titleMedium)
        Text("Choose existing effects only if the old modifiers summarized those effects. This explanation creates no mechanics and approves no proposed effects.", color = RebuildPalette.Muted)
        if (resolved) {
            Text("Modifier explanation reviewed", color = RebuildPalette.Ice)
            review.legacyModifierResolutions.forEach { proof ->
                Text("${proof.sourceAbility.key}: original score ${proof.withoutSelectedScore}, recorded modifier ${signedModifier(proof.sourceAbility.magicModifier)}; existing effects give score ${proof.effectiveScore} and modifier ${signedModifier(proof.effectiveModifier)}.")
            }
            Text("Review the full values and membership again before accepting.", color = RebuildPalette.Muted)
        } else {
            required.forEach { ability ->
                val candidates = remember(review.fingerprint, ability) {
                    runCatching { genesisLegacyModifierCandidates(character, ability) }.getOrElse { emptyList() }
                }
                Text("${ability.key} · recorded modifier ${signedModifier(ability.magicModifier)}", style = MaterialTheme.typography.titleSmall)
                if (ability.score == null) {
                    Text("${ability.key}'s original score is unknown. Keep this modifier unresolved until its source is reviewed.", color = RebuildPalette.Muted)
                } else if (candidates.isEmpty()) {
                    Text("No accepted active effect explains ${ability.key}. Review the existing mechanics before connecting these values.", color = RebuildPalette.Muted)
                } else {
                    Text("Original score ${ability.score}. Select the effects that this recorded modifier summarized.", color = RebuildPalette.Muted)
                    candidates.forEach { candidate ->
                        val reference = candidate.reference
                        val chosen = reference in selected[ability.id].orEmpty()
                        val itemName = reference.itemInstanceId?.let { id ->
                            character.items.singleOrNull { it.id == id }?.let { instance ->
                                character.itemDefinitions.singleOrNull { it.id == instance.definitionId }?.name
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("genesis-modifier-effect-${ability.key}-${reference.effectId}")
                            .toggleable(value = chosen, enabled = !busy, role = Role.Checkbox, onValueChange = { checked ->
                                val current = selected[ability.id].orEmpty()
                                selected = selected + (ability.id to if (checked) current + reference else current - reference)
                                declaration = false; error = ""
                            }), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = chosen, onCheckedChange = null, enabled = !busy)
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(candidate.effect.name)
                                Text(itemName ?: candidate.effect.source.name, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                            }
                        }
                    }
                }
            }
            val selectionComplete = required.all { it.score != null && selected[it.id].orEmpty().isNotEmpty() }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("genesis-modifier-declaration")
                .toggleable(value = declaration, enabled = !busy && selectionComplete, role = Role.Checkbox, onValueChange = { declaration = it; error = "" }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = declaration, onCheckedChange = null, enabled = !busy && selectionComplete)
                Text("These modifiers summarized the selected existing effects", Modifier.weight(1f).padding(vertical = 8.dp))
            }
            PlayerOutlinedButton(enabled = !busy && selectionComplete && declaration, onClick = {
                try {
                    val proofs = required.map { ability -> genesisReviewLegacyModifier(character, ability,
                        selected[ability.id].orEmpty(), GenesisLegacyModifierDecision.SUMMARIZED_EXISTING_EFFECTS) }
                    genesisValidateLegacyModifierResolutions(character, review.source?.abilities.orEmpty(), proofs)
                    onResolved(proofs)
                } catch (failure: Exception) {
                    error = failure.message ?: "The selected effects do not explain these source values. Nothing changed."
                }
            }) { Text("Review modifier explanation") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

private fun signedModifier(value: Int) = if (value >= 0) "+$value" else value.toString()
