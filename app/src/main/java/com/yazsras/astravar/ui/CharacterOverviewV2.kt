package com.yazsras.astravar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.CampaignRecordsRepository
import com.yazsras.astravar.data.CharacterAbility
import com.yazsras.astravar.data.CharacterProfile

private val abilityNames = linkedMapOf("STR" to "Strength", "DEX" to "Dexterity", "CON" to "Constitution",
    "INT" to "Intelligence", "WIS" to "Wisdom", "CHA" to "Charisma")
private fun signedModifier(value: Int?): String = value?.let { if (it >= 0) "+$it" else it.toString() } ?: "?"
private fun optionalNumber(text: String, label: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.let {
    it.toIntOrNull() ?: error("$label must be a whole number or blank for unknown.")
}

/** Manually confirmed character display. This never configures the weapon engine or rolls. */
@Composable
fun CharacterOverviewV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val profiles by records.characterProfiles.collectAsState(initial = emptyList())
    val abilities by records.characterAbilities.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == "local-player" }
    var profileEditorOpen by remember { mutableStateOf(false) }
    var profileSnapshot by remember { mutableStateOf<CharacterProfile?>(null) }
    var abilityEditorKey by remember { mutableStateOf<String?>(null) }
    var abilitySnapshot by remember { mutableStateOf<CharacterAbility?>(null) }
    var notice by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArcanePanel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile?.name?.takeIf { it.isNotBlank() } ?: "Character overview", color = Brass,
                        style = MaterialTheme.typography.titleLarge)
                    Text(if (profile == null) "Current values have not been entered." else "Manually confirmed · revision ${profile.revision}",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { profileSnapshot = profile; profileEditorOpen = true }) { Text("Edit status") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CharacterResourceTile("HIT POINTS", "${profile?.currentHp ?: "?"} / ${profile?.maximumHp ?: "?"}",
                    "Temp ${profile?.temporaryHp ?: "?"}", Modifier.weight(1.65f))
                CharacterResourceTile("AC", profile?.armorClass?.toString() ?: "?", if (profile?.armorClass == null) "Unknown" else "Confirmed", Modifier.weight(1f))
                CharacterResourceTile("LEVEL", profile?.level?.toString() ?: "?", if (profile?.level == null) "Unknown" else "Confirmed", Modifier.weight(1f))
            }
            Text("Local reference only. Character values and magical modifiers do not change Astravar attack or damage calculations.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            if (notice.isNotBlank()) Text(notice, color = Frost, style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            abilityNames.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (key, fullName) ->
                        val ability = abilities.firstOrNull { it.profileId == "local-player" && it.key == key }
                        Column(
                            Modifier.weight(1f).metalSurface(accent = Brass)
                                .clickable(role = Role.Button, onClickLabel = "Edit $fullName") {
                                    abilitySnapshot = ability; abilityEditorKey = key
                                }
                                .testTag("ability-$key")
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "$fullName score ${ability?.score ?: "unknown"}; base modifier ${signedModifier(ability?.baseModifier)}; magical modifier ${ability?.magicModifier?.let { signedModifier(it) } ?: "unknown"}; total ${signedModifier(ability?.totalModifier)}"
                                }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(key, color = Frost, style = MaterialTheme.typography.labelLarge)
                            Text(ability?.score?.toString() ?: "?", color = Brass, fontFamily = DisplayFace,
                                fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
                                border = BorderStroke(1.dp, Brass.copy(alpha = .45f))) {
                                Text(signedModifier(ability?.totalModifier), color = Frost,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Base ${signedModifier(ability?.baseModifier)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                            Text("Magic ${ability?.magicModifier?.let { signedModifier(it) } ?: "?"}", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (profileEditorOpen) CharacterProfileDialog(
        snapshot = profileSnapshot, latest = profile, records = records,
        onDismiss = { profileEditorOpen = false },
        onSaved = { profileEditorOpen = false; notice = "Character status saved locally. Combat configuration unchanged." }
    )
    val editingKey = abilityEditorKey
    if (editingKey != null) CharacterAbilityDialog(
        key = editingKey, snapshot = abilitySnapshot,
        latest = abilities.firstOrNull { it.profileId == "local-player" && it.key == editingKey },
        profileExists = profile != null, records = records,
        onDismiss = { abilityEditorKey = null },
        onSaved = { abilityEditorKey = null; notice = "Ability score and separate magic modifier saved locally. Combat configuration unchanged." }
    )
}

@Composable
private fun CharacterResourceTile(label: String, value: String, detail: String, modifier: Modifier) {
    Column(modifier.metalSurface(accent = Frost).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, color = Brass, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CharacterProfileDialog(snapshot: CharacterProfile?, latest: CharacterProfile?, records: CampaignRecordsRepository,
    onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(snapshot?.name.orEmpty()) }
    var hp by remember { mutableStateOf(snapshot?.currentHp?.toString().orEmpty()) }
    var maximum by remember { mutableStateOf(snapshot?.maximumHp?.toString().orEmpty()) }
    var temporary by remember { mutableStateOf(snapshot?.temporaryHp?.toString().orEmpty()) }
    var ac by remember { mutableStateOf(snapshot?.armorClass?.toString().orEmpty()) }
    var level by remember { mutableStateOf(snapshot?.level?.toString().orEmpty()) }
    var source by remember { mutableStateOf(snapshot?.source.orEmpty()) }
    val actions = rememberLocalRecordActions()
    val stale = latest?.revision != snapshot?.revision
    CharacterEditorDialog(
        onDismissRequest = { if (!actions.busy) onDismiss() },
        title = { Text("Edit character status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter only confirmed current values. Blank numbers stay unknown; opening this editor never restores resources.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (stale) Text("Status changed after opening. Cancel and reopen to review the latest values.", color = MaterialTheme.colorScheme.error)
                Field("Character name (optional)", name, { name = it }, enabled = !actions.busy)
                Field("Current HP (blank = unknown)", hp, { hp = it }, enabled = !actions.busy)
                Field("Maximum HP (blank = unknown)", maximum, { maximum = it }, enabled = !actions.busy)
                Field("Temporary HP (blank = unknown)", temporary, { temporary = it }, enabled = !actions.busy)
                Field("Armor class (blank = unknown)", ac, { ac = it }, enabled = !actions.busy)
                Field("Character level (blank = unknown)", level, { level = it }, enabled = !actions.busy)
                Field("Character status source (required)", source, { source = it }, single = false, enabled = !actions.busy)
                if (actions.message.isNotBlank()) Text(actions.message, color = Frost, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                actions.run {
                    val model = (snapshot ?: CharacterProfile()).copy(name = name.trim(),
                        currentHp = optionalNumber(hp, "Current HP"), maximumHp = optionalNumber(maximum, "Maximum HP"),
                        temporaryHp = optionalNumber(temporary, "Temporary HP"), armorClass = optionalNumber(ac, "Armor class"),
                        level = optionalNumber(level, "Level"))
                    records.saveProfile(model, snapshot?.revision, source.trim())
                    onSaved(); "Saved locally."
                }
            }, enabled = !actions.busy && !stale && source.isNotBlank()) { Text("Save character status") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } }
    )
}

@Composable
private fun CharacterAbilityDialog(key: String, snapshot: CharacterAbility?, latest: CharacterAbility?, profileExists: Boolean,
    records: CampaignRecordsRepository, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var score by remember { mutableStateOf(snapshot?.score?.toString().orEmpty()) }
    var magic by remember { mutableStateOf(snapshot?.magicModifier?.toString() ?: "0") }
    var notes by remember { mutableStateOf(snapshot?.notes.orEmpty()) }
    var source by remember { mutableStateOf(snapshot?.source.orEmpty()) }
    val actions = rememberLocalRecordActions()
    val stale = latest?.revision != snapshot?.revision
    val scorePreview = score.toIntOrNull()?.takeIf { it in 0..1000 }
    val magicPreview = magic.toIntOrNull()?.takeIf { it in -1000..1000 }
    val basePreview = scorePreview?.let { Math.floorDiv(it - 10, 2) }
    CharacterEditorDialog(
        onDismissRequest = { if (!actions.busy) onDismiss() },
        title = { Text("Edit ${abilityNames[key] ?: key}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Raw ability score and magical bonus remain separate. Changing the score preserves the magical bonus you enter below.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (!profileExists) Text("Save a sourced character status first. No current build has been assumed.", color = Frost)
                if (stale) Text("This ability changed after opening. Cancel and reopen before saving.", color = MaterialTheme.colorScheme.error)
                Field("Raw ability score (blank = unknown)", score, { score = it }, enabled = !actions.busy)
                Field("Separate magical modifier", magic, { magic = it }, enabled = !actions.busy)
                Text("Base ${signedModifier(basePreview)} · magic ${signedModifier(magicPreview)} · total ${signedModifier(if (basePreview != null && magicPreview != null) basePreview + magicPreview else null)}",
                    color = Frost, style = MaterialTheme.typography.bodySmall)
                Field("Ability / magic source notes", notes, { notes = it }, single = false, enabled = !actions.busy)
                Field("Ability source (required)", source, { source = it }, single = false, enabled = !actions.busy)
                Text("These are display values. Existing weapon modifiers, saves and automatic dice are unchanged.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (actions.message.isNotBlank()) Text(actions.message, color = Frost, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                actions.run {
                    val raw = optionalNumber(score, "Ability score")
                    val magical = magic.toIntOrNull() ?: error("Magical modifier must be a whole number.")
                    val model = (snapshot ?: CharacterAbility(key = key)).copy(score = raw, magicModifier = magical, notes = notes)
                    records.saveAbility(model, snapshot?.revision, source.trim())
                    onSaved(); "Saved locally."
                }
            }, enabled = !actions.busy && !stale && profileExists && source.isNotBlank()) { Text("Save ability") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actions.busy) { Text("Cancel") } }
    )
}

/** Material's measured dialog host keeps the multi-field editor bounded on small/IME windows. */
@Composable
private fun CharacterEditorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = { Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { text() } },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = CutCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = Brass,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}
