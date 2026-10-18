package com.yazsras.astravar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Static reference presentation only. No current-resource counters, state mutations or simulated use. */
private data class ArmorCapabilityReference(
    val id: String, val name: String, val classification: String, val overview: String,
    val activation: String, val maximum: String, val recovery: String,
    val mechanics: List<String>, val integrationNote: String? = null
)

// Verified against Astravar Armor Mk.I Complete Reference, revision 11 October 2026, pp. 5–7.
// These suit references deliberately do not replace the preserved weapon Profile/Engine.
private val armorCapabilityReferences = listOf(
    ArmorCapabilityReference("null-ward", "Null Ward", "Integrated defensive system",
        "Nullifies qualifying spells directly targeting Zerg.", "Automatic · no action or reaction", "3 charges", "Long Rest",
        listOf("Qualifying spells are 2nd level or lower, including cantrips.",
            "One casting consumes one charge, including multiple projectiles. Only Zerg is protected from a multi-target spell.",
            "Zerg may consciously allow spells or pre-authorize trusted sources.",
            "Does not stop area effects, environmental magic, summoned creatures, breath weapons, mundane hazards, or effects that do not directly target Zerg.")),
    ArmorCapabilityReference("defensive-field", "Defensive Field", "Guardian system",
        "Creates a protective field of temporary hit points.", "Bonus Action", "3 uses", "Long Rest",
        listOf("Gain 6 temporary hit points.", "This reference does not assume that the field has been used or that temporary hit points are currently available.")),
    ArmorCapabilityReference("hunter-lock", "Hunter Lock", "Targeting system",
        "Designates one creature Zerg can see and resolve within 1 mile.", "Bonus Action", "1 use", "Short Rest",
        listOf("Astravar attack rolls against that creature have Advantage.",
            "Ignore half cover and three-quarters cover; remove Disadvantage caused solely by Longstrike or extreme range.",
            "Both palm emitters share the lock. It adds no damage; total cover still blocks Astravar.",
            "Ends when the target dies, Zerg consciously releases it, or it resets through the appropriate rest."),
        "Reference only. Hunter Lock does not automatically alter the existing weapon roller."),
    ArmorCapabilityReference("spell-reservoir", "Spell Reservoir", "Isolated payload storage",
        "Stores a compatible instantaneous spell payload until fired, purged or overwritten.", "Load, purge or overwrite: Bonus Action", "1 stored payload", "Unused payload persists through rests",
        listOf("Loading consumes an eligible spell slot OR an eligible scroll, never both.",
            "Purging or overwriting loses the existing spell energy without a refund.",
            "Immediate imbuing expends a compatible slot as part of the attack, without a separate Action or Bonus Action.",
            "A spell-imbued attack replaces ordinary elemental selector damage; the approved engine validates eligible damage."),
        "Reference capacity is Mk.I. The existing roller continues to use its saved, explicitly configured payload rules."),
    ArmorCapabilityReference("discharge", "Discharge", "High-output armor system",
        "Releases a concentrated chest-core beam at a designated point.", "Action", "1 use", "Long Rest",
        listOf("Mk.I reference: 1 mile range, 20-foot-radius sphere, Dexterity save DC 15; 12d12 Force, full damage on failure or half on success.",
            "No attack roll. Requires a viable targeting path and does not protect allies.",
            "Afterward, the advanced weapon network is unavailable for 10 minutes of in-world time. There is no real-world countdown.",
            "Ordinary Guardian functions, Defensive Field, Null Ward and normal spellcasting remain available."),
        "Mk.I armor reference only. These values are not applied to the preserved weapon engine or its independently saved Discharge configuration.")
)

@Composable
fun ArmorCapabilitiesRebuild(onDismiss: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = armorCapabilityReferences.firstOrNull { it.id == selectedId }
    PlayerSheet(selected?.name ?: "Core systems", onDismiss) {
        if (selected == null) {
            armorCapabilityReferences.forEachIndexed { index, capability ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .clickable(role = Role.Button) { selectedId = capability.id }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(capability.name, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Ice)
                        Text(capability.classification, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                    }
                    Text("›", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleLarge)
                }
                if (index != armorCapabilityReferences.lastIndex) HorizontalDivider(color = RebuildPalette.Elevated)
            }
            Text("Mk.I reference · 11 Oct 2026", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            PlayerTextButton(onClick = { selectedId = null }) { Text("‹ All systems") }
            Text(selected.classification, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
            Text(selected.overview, style = MaterialTheme.typography.bodyLarge)
            PlayerCard {
                CapabilityFact("Activation", selected.activation)
                CapabilityFact("Maximum", selected.maximum)
                CapabilityFact("Recovery", selected.recovery)
            }
            Text("Mechanics", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleMedium)
            selected.mechanics.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            selected.integrationNote?.let { Text(it, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall) }
            Text("Static reference. Current resources are not tracked here.", color = RebuildPalette.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapabilityFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = RebuildPalette.Ice)
    }
}
