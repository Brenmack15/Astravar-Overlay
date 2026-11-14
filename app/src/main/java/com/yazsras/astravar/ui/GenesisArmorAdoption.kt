package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.GenesisArmorRules
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.TurnEconomy
import java.util.UUID

/** Default reconciliation cannot restore a spent action or bonus; the owner may explicitly choose the current sheet. */
internal fun genesisReviewedArmorTurn(character: ReforgeCharacter, legacy: Campaign, sheetOnly: Boolean): TurnEconomy {
    val current = character.turn
    if (sheetOnly) return current
    val attacks = maxOf(current.attacksUsed, if (legacy.encounter) legacy.turn.attacks else 0)
    return current.copy(tracking = current.tracking || legacy.encounter,
        attacksUsed = attacks,
        actionAvailable = current.actionAvailable && (!legacy.encounter || !legacy.turn.fullAction) && attacks < current.attacksPerAction,
        bonusActionAvailable = current.bonusActionAvailable && !legacy.turn.bonus)
}

/** A one-time identity review, never a rest or an overwrite of current character resources. */
@Composable internal fun GenesisArmorAdoption(character: ReforgeCharacter, legacyCampaign: Campaign?,
    legacyElements: List<DamageType> = emptyList(), alreadyAdoptedBy: String? = null,
    onSave: (ReforgeCharacter, String) -> Unit) {
    if (character.armor != null) return
    val feedback = LocalGenesisEditorFeedback.current
    var open by rememberSaveable(character.id) { mutableStateOf(false) }
    var confirmed by rememberSaveable(character.id) { mutableStateOf(false) }
    var importUnknown by rememberSaveable(character.id) { mutableStateOf(false) }
    var useSheetTurn by rememberSaveable(character.id) { mutableStateOf(false) }
    var source by rememberSaveable(character.id) { mutableStateOf("") }
    var baseline by rememberSaveable(character.id) { mutableLongStateOf(character.revision) }
    var legacyRevision by rememberSaveable(character.id) { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf("") }
    val recorded = legacyCampaign?.takeIf { it.onboarded || it.cores.isNotEmpty() || it.templates.isNotEmpty() }
    if (alreadyAdoptedBy != null) {
        Text("Saved Astravar systems are connected to $alreadyAdoptedBy.", color = RebuildPalette.Muted)
        return
    }
    if (recorded == null) return
    PlayerEntry("Connect saved Astravar systems", "Review the armor attacks, active element and reservoir for ${character.name}.", "Core", {
        baseline = character.revision; legacyRevision = recorded.revision; confirmed = false; importUnknown = false; useSheetTurn = false; error = ""; open = true
    })
    if (!open) return
    PlayerSheet("Connect Astravar to ${character.name}", { open = false }, dirty = confirmed || source.isNotBlank(), showCancel = true, actions = {
        PlayerButton(enabled = !feedback.busy && character.revision == baseline && recorded.revision == legacyRevision && confirmed && source.isNotBlank(),
            modifier = Modifier.testTag("genesis-adopt-armor"), onClick = {
                try {
                    val slots = if (importUnknown) character.spellSlots + recorded.slots.filterKeys { character.spellSlots[it] == null }
                        else character.spellSlots
                    val reviewed = character.copy(spellSlots = slots, turn = genesisReviewedArmorTurn(character, recorded, useSheetTurn))
                    val next = GenesisArmorRules.adopt(reviewed, recorded, legacyElements, source.trim())
                    onSave(next, "Connected reviewed Astravar systems to character")
                } catch (failure: Exception) { error = failure.message ?: "Unable to connect these records." }
            }) { Text("Connect reviewed systems") }
    }) {
        Text("Destination: ${character.name}", style = MaterialTheme.typography.titleMedium)
        Text("${recorded.cores.size} retained core records · ${recorded.templates.size} payload templates · ${recorded.cores.sumOf { it.payloads.size }} stored spells")
        Text("Active element: ${legacyElements.joinToString { it.name.lowercase() }.ifBlank { "Neutral Force" }}")
        Text("Attunement: ${if (recorded.attuned) "confirmed" else "not confirmed"} · Nexus: ${recorded.installed?.readiness(recorded.gameSeconds) ?: "not configured"}", color = RebuildPalette.Muted)
        Text("Current ability modifiers come from this character sheet. Accepted attack formulas, upgrades, core readiness, stored spell snapshots and artwork are retained.", color = RebuildPalette.Muted)
        PlayerDisclosure("Review spell-slot balances", initiallyOpen = true) {
            (character.spellSlots.keys + recorded.slots.keys).sorted().forEach { level ->
                Text("Level $level · Sheet: ${character.spellSlots[level]?.toString() ?: "unknown"} · Armor record: ${recorded.slots[level]?.toString() ?: "unknown"}")
            }
            Text("The sheet's recorded counts remain authoritative. Unknown counts stay unknown unless you explicitly choose the saved armor counts below.", color = RebuildPalette.Muted)
            if (recorded.slots.keys.any { character.spellSlots[it] == null }) {
                Toggle("Use recorded armor slots for unknown levels", importUnknown, !feedback.busy) { importUnknown = it }
            }
        }
        val reconciledTurn = genesisReviewedArmorTurn(character, recorded, false)
        if (reconciledTurn != character.turn) PlayerDisclosure("Review current actions", initiallyOpen = true) {
            Text("Sheet · ${if (character.turn.tracking) "tracking on" else "tracking off"} · ${character.turn.attacksUsed} attacks used · Action ${if (character.turn.actionAvailable) "available" else "spent"} · Bonus Action ${if (character.turn.bonusActionAvailable) "available" else "spent"}")
            Text("Armor · ${if (recorded.encounter) "tracking on" else "tracking off"} · ${recorded.turn.attacks} attacks used · Action ${if (recorded.turn.fullAction) "spent" else "not spent"} · Bonus Action ${if (recorded.turn.bonus) "spent" else "available"}")
            Text("By default, spent actions from either record remain spent. The sheet's attack allowance, reaction and spell-slot-spent flag remain unchanged. Saved spell-firing counts and Charged Gauntlet are preserved.", color = RebuildPalette.Muted)
            Toggle("Use the current sheet's action budget instead", useSheetTurn, !feedback.busy) { useSheetTurn = it }
        }
        Text("This connection preserves an original armor snapshot for recovery. Future combat, spell loading and spellbook casting share this character's resources. No rest, healing or game time is added.", color = RebuildPalette.Muted)
        Field("Source or confirmation note", source, { source = it }, single = false, enabled = !feedback.busy)
        Toggle("These saved Astravar systems belong to ${character.name}", confirmed, !feedback.busy) { confirmed = it }
        if (character.revision != baseline || recorded.revision != legacyRevision) Text("Saved records changed. Close and reopen this review.", color = MaterialTheme.colorScheme.error)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
    }
}

/** Recovery controls live beside the character's other resources and retain recorded in-world time. */
@Composable internal fun GenesisArmorRecovery(character: ReforgeCharacter,
    onSave: (ReforgeCharacter, String) -> Unit) {
    if (character.armor == null) return
    val available = runCatching { GenesisArmorRules.runtime(character) }
    val campaign = available.getOrNull()
    if (campaign == null) {
        Text("Astravar recovery: ${available.exceptionOrNull()?.message ?: "Review character setup."}", color = RebuildPalette.Muted)
        return
    }
    val feedback = LocalGenesisEditorFeedback.current
    var open by rememberSaveable(character.id) { mutableStateOf(false) }
    var baseline by rememberSaveable(character.id) { mutableLongStateOf(character.revision) }
    var seconds by rememberSaveable(character.id) { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var submittedState by remember { mutableStateOf<ReforgeCharacter?>(null) }
    LaunchedEffect(character.revision) {
        val submitted = submittedState
        if (submitted != null && character.revision > submitted.revision && character == submitted.copy(revision = character.revision)) {
            open = false; submittedState = null
        }
    }
    PlayerEntry("Astravar recovery", campaign.installed?.readiness(campaign.gameSeconds) ?: "Internal nexus not configured", "Core", {
        baseline = character.revision; seconds = ""; error = ""; open = true
    })
    if (open) PlayerSheet("Confirm elapsed game time", { open = false }, dirty = seconds.isNotBlank(), showCancel = true, actions = {
        PlayerButton(enabled = !feedback.busy && character.revision == baseline && seconds.isNotBlank(), onClick = {
            try {
                val elapsed = seconds.intValue("Elapsed seconds", 0, 31536000).toLong()
                val after = Engine.advance(campaign, elapsed)
                val next = GenesisArmorRules.apply(character, after)
                submittedState = next
                onSave(next, "Recorded elapsed game time for Astravar recovery")
            } catch (failure: Exception) { error = failure.message ?: "Check the elapsed time." }
        }) { Text("Confirm elapsed time") }
    }) {
        Text("Advance only time that actually passed at your table. This does not perform a rest or refill spell slots.", color = RebuildPalette.Muted)
        campaign.cores.filterNot { it.destroyed }.forEach { Text("${it.name} · ${it.readiness(campaign.gameSeconds)}") }
        Field("Elapsed game seconds", seconds, { seconds = it }, enabled = !feedback.busy)
        if (character.revision != baseline) Text("Character resources changed. Close and reopen this panel.", color = MaterialTheme.colorScheme.error)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
    }
    GenesisNexusRecovery(character, onSave)
}

@Composable private fun GenesisNexusRecovery(character: ReforgeCharacter, onSave: (ReforgeCharacter, String) -> Unit) {
    val campaign = GenesisArmorRules.runtime(character)
    if (!Engine.needsNexusAdoption(campaign)) return
    val feedback = LocalGenesisEditorFeedback.current
    var open by rememberSaveable(character.id) { mutableStateOf(false) }
    var baseline by rememberSaveable(character.id) { mutableLongStateOf(character.revision) }
    var selected by rememberSaveable(character.id) { mutableStateOf<String?>(null) }
    var source by rememberSaveable(character.id) { mutableStateOf("") }
    var confirmed by rememberSaveable(character.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val newId = rememberSaveable { "internal-nexus-${UUID.randomUUID()}" }
    val intact = campaign.cores.filterNot { it.destroyed }
    PlayerEntry("Review permanent nexus", "Resolve an old uninstalled or interrupted core record.", "Core", { baseline = character.revision; open = true })
    if (!open) return
    PlayerSheet("Review permanent nexus", { open = false }, dirty = source.isNotBlank() || confirmed, showCancel = true, actions = {
        PlayerButton(enabled = !feedback.busy && character.revision == baseline && confirmed && source.isNotBlank() && (intact.isEmpty() || selected != null), onClick = {
            try {
                val next = Engine.adoptPermanentNexus(campaign, campaign.revision, if (intact.isEmpty()) newId else requireNotNull(selected), source,
                    recordConfirmedExistingNexus = intact.isEmpty(), confirmed = confirmed)
                onSave(GenesisArmorRules.apply(character, next), "Reviewed permanent nexus record")
            } catch (failure: Exception) { error = failure.message ?: "Check the nexus source." }
        }) { Text("Save reviewed nexus") }
    }) {
        if (intact.isEmpty()) {
            Text("Only proceed if the campaign already established an intact, currently available nexus with an empty reservoir. This does not craft or repair anything. Destroyed records remain unchanged.", color = RebuildPalette.Muted)
        } else {
            Choices("Existing intact nexus", intact.map { it.id }, selected,
                display = { id -> intact.first { it.id == id }.let { "${it.name} · ${it.readiness(campaign.gameSeconds)} · ${it.payloads.size} stored spells" } }, enabled = !feedback.busy) { selected = it }
        }
        Field("Resolved source or checkpoint", source, { source = it }, single = false, enabled = !feedback.busy)
        Toggle(if (intact.isEmpty()) "I confirm the established available nexus and empty reservoir" else "This is the established permanent nexus", confirmed, !feedback.busy) { confirmed = it }
        Text("Attunement, slots, original snapshots and game time remain unchanged.", color = RebuildPalette.Muted)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
    }
}
