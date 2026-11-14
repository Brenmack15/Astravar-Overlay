package com.yazsras.astravar.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.ReforgeRollRecord
import com.yazsras.astravar.rules.PendingShot
import com.yazsras.astravar.rules.title
import com.yazsras.astravar.rules.shotSummary
import java.text.DateFormat
import java.util.Date

internal data class GenesisHistoryEntry(val id: String, val time: Long,
    val characterRoll: ReforgeRollRecord? = null, val armorRoll: PendingShot? = null) {
    val label: String get() = characterRoll?.label ?: armorRoll!!.plan.ability.title
}

/** Identical saved shot IDs are one event even when both structured and full snapshots exist. */
internal fun genesisHistoryEntries(rolls: List<ReforgeRollRecord>, armor: List<PendingShot>): List<GenesisHistoryEntry> {
    val shots = armor.distinctBy { it.plan.id }
    val shotIds = shots.mapTo(mutableSetOf()) { it.plan.id }
    return (rolls.filter { it.id !in shotIds }.map { GenesisHistoryEntry(it.id, it.createdAt, characterRoll = it) } +
        shots.map { GenesisHistoryEntry(it.plan.id, it.rolled?.rolledAtMillis ?: 0, armorRoll = it) })
        .sortedWith(compareByDescending<GenesisHistoryEntry> { it.time }.thenBy { it.id })
}

@Composable internal fun GenesisCharacterHistory(rolls: List<ReforgeRollRecord>, armor: List<PendingShot>) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All rolls") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = remember(rolls, armor) { genesisHistoryEntries(rolls, armor) }
    PlayerSearch(query, { query = it }, "Find a saved roll")
    PlayerTabs(listOf("All rolls", "Character", "Armor"), filter, { filter = it }, Modifier.genesisHorizontalControl())
    val visible = entries.filter {
        it.label.contains(query.trim(), ignoreCase = true) &&
            (filter == "All rolls" || (filter == "Armor") == (it.armorRoll != null))
    }
    if (entries.isEmpty()) Text("No rolls yet. Rolls occur only when you tap Roll or enter a manual result.", color = RebuildPalette.Muted)
    else if (visible.isEmpty()) PlayerEmpty("No matching rolls", "Try another name or show all rolls.")
    visible.forEach { entry -> key(entry.id) {
        val record = entry.characterRoll
        if (record != null) ReforgeSavedRollCard(record)
        else entry.armorRoll?.let { shot ->
            PlayerCard(onClick = { selected = entry.id }) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium)
                Text(if (entry.time > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.time)) else "Earlier saved roll",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                Text(shot.rolled?.attackTotal?.let { "Attack $it" } ?: if (shot.plan.blast) "Dexterity save · DC ${shot.plan.profile.saveDc}" else "Recorded attack",
                    color = RebuildPalette.GoldBright)
                shot.rolled?.takeIf { it.attackTotal != null }?.let {
                    Text(it.attackDescription(shot.plan), style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                }
                Text("Inspect dice, damage and effects", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Ice)
            }
        }
    } }
    entries.firstOrNull { it.id == selected }?.armorRoll?.let { shot ->
        PlayerSheet("Roll details", { selected = null }) {
            SelectionContainer { Text(shotSummary(shot)) }
        }
    }
}
