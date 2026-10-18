package com.yazsras.astravar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.R
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.CampaignRecordsRepository
import com.yazsras.astravar.data.CharacterAbility
import com.yazsras.astravar.data.CharacterProfile
import com.yazsras.astravar.rules.Campaign
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin

private val abilityNames = linkedMapOf("STR" to "Strength", "DEX" to "Dexterity", "CON" to "Constitution",
    "INT" to "Intelligence", "WIS" to "Wisdom", "CHA" to "Charisma")
private fun signedModifier(value: Int?): String = value?.let { if (it >= 0) "+$it" else it.toString() } ?: "?"
private fun optionalNumber(text: String, label: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.let {
    it.toIntOrNull() ?: error("$label must be a whole number or blank for unknown.")
}

/** The manual character record remains separate from the accepted weapon engine. */
@Composable
fun CharacterOverviewV2(repository: CampaignLedgerRepository, campaign: Campaign? = null,
    profileId: String = "local-player", onEditorVisibilityChanged: (Boolean) -> Unit = {}) {
    val records = repository.records
    val profiles by records.characterProfiles.collectAsState(initial = emptyList())
    val abilities by records.characterAbilities.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == profileId }
    var profileEditorOpen by rememberSaveable(profileId) { mutableStateOf(false) }
    var profileSnapshotJson by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var abilityEditorKey by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var abilitySnapshotJson by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var notice by rememberSaveable(profileId) { mutableStateOf("") }
    // A disposable test profile must never borrow a real character's identity or values.
    val confirmedWeapon = campaign?.takeIf { it.onboarded && profileId == "local-player" }?.profile
    val name = profile?.name?.takeIf { it.isNotBlank() } ?: confirmedWeapon?.character
        ?: when(profileId) {
            "local-player" -> "Your character"
            "eek" -> "Eek"
            "remote-stat-test-v1" -> "Disposable stat test"
            "korr" -> "Korr"
            "veyrath" -> "Veyrath"
            else -> "Unconfigured party member"
        }
    val level = profile?.level ?: confirmedWeapon?.level
    val recordedIntelligence = abilities.firstOrNull { it.profileId == profileId && it.key == "INT" }?.totalModifier
    val conflicts = buildList {
        if (profile?.level != null && confirmedWeapon != null && profile.level != confirmedWeapon.level)
            add("Character level ${profile.level}; weapon Artificer level ${confirmedWeapon.level}")
        if (recordedIntelligence != null && confirmedWeapon != null && recordedIntelligence != confirmedWeapon.intelligence)
            add("Character INT modifier ${signedModifier(recordedIntelligence)}; weapon INT ${signedModifier(confirmedWeapon.intelligence)}")
    }
    SideEffect { onEditorVisibilityChanged(profileEditorOpen || abilityEditorKey != null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp, 64.dp).clip(CutCornerShape(12.dp)).background(RebuildPalette.Panel),
                contentAlignment = Alignment.Center) {
                when(profileId) {
                    "eek" -> Image(painterResource(R.drawable.eek_portrait),
                        contentDescription = "Eek, mechanical owl portrait", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp))
                    "local-player", "remote-stat-test-v1" -> Image(painterResource(R.drawable.astravar_armor_front),
                        contentDescription = "Approved Astravar armor artwork", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp))
                    else -> ArcaneIcon("Emblem", Modifier.size(32.dp), RebuildPalette.Ice)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontFamily = InterfaceFace,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(level?.let { "Level $it" } ?: "Level unknown", color = RebuildPalette.Muted,
                    style = MaterialTheme.typography.bodySmall)
                if (profile?.currentHp != null || profile?.maximumHp != null) {
                    Text("HP ${profile.currentHp ?: "?"} / ${profile.maximumHp ?: "?"}",
                        color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
                }
            }
            PlayerTextButton(onClick = { profileSnapshotJson = profile?.let { Json.encodeToString(it) }; profileEditorOpen = true },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit status") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CharacterSummaryTile("Armor Class", profile?.armorClass?.toString() ?: "?", Modifier.weight(1f))
            CharacterSummaryTile(if(profileId == "local-player" || profileId == "remote-stat-test-v1") "Astravar ATK" else "Attack Bonus", signedModifier(confirmedWeapon?.attack), Modifier.weight(1f))
            CharacterSummaryTile("Spell Save DC", confirmedWeapon?.saveDc?.toString() ?: "?", Modifier.weight(1f))
        }
        // One compact read-only line; independent of the editable character sheet.
        if (confirmedWeapon != null) Text(
            "Weapon profile · Artificer ${confirmedWeapon.level} · INT ${signedModifier(confirmedWeapon.intelligence)} · PB +${confirmedWeapon.proficiency} · DC ${confirmedWeapon.saveDc}",
            style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Ice
        )
        if (conflicts.isNotEmpty()) Text("Source conflict · ${conflicts.joinToString(". ")}. Review Party and Rules & profile; saved values are retained.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("character-weapon-conflict"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ability scores", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
            abilityNames.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (key, fullName) ->
                        val ability = abilities.firstOrNull { it.profileId == profileId && it.key == key }
                        AbilityTile(key, fullName, ability?.score, ability?.totalModifier,
                            modifier = Modifier.weight(1f).testTag("ability-$key").semantics(mergeDescendants = true) {
                                contentDescription = "$fullName score ${ability?.score ?: "unknown"}; base modifier ${signedModifier(ability?.baseModifier)}; magical modifier ${ability?.magicModifier?.let { signedModifier(it) } ?: "unknown"}; total ${signedModifier(ability?.totalModifier)}"
                            }, onClick = {
                                abilitySnapshotJson = ability?.let { Json.encodeToString(it) }; abilityEditorKey = key
                            })
                    }
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
    }
    if (profileEditorOpen) CharacterProfileDialog(
        snapshot = profileSnapshotJson?.let { Json.decodeFromString<CharacterProfile>(it) }, latest = profile, records = records,
        profileId = profileId,
        onDismiss = { profileEditorOpen = false }, onSaved = { profileEditorOpen = false; notice = "Character status saved" }
    )
    val editingKey = abilityEditorKey
    if (editingKey != null) CharacterAbilityDialog(
        key = editingKey, snapshot = abilitySnapshotJson?.let { Json.decodeFromString<CharacterAbility>(it) },
        latest = abilities.firstOrNull { it.profileId == profileId && it.key == editingKey }, profileId = profileId,
        profileExists = profile != null, records = records, onDismiss = { abilityEditorKey = null },
        onSaved = { abilityEditorKey = null; notice = "Ability saved" }
    )
}

@Composable
private fun CharacterSummaryTile(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, color = RebuildPalette.Panel, shape = CutCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontFamily = InterfaceFace, fontWeight = FontWeight.SemiBold, fontSize = 25.sp,
                color = RebuildPalette.Gold, textAlign = TextAlign.Center)
            Text(label, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AbilityTile(key: String, label: String, score: Int?, modifierValue: Int?, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null) {
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Edit $label", onClick = onClick) else Modifier
    Column(modifier.heightIn(min = 48.dp).clip(CutCornerShape(10.dp)).background(RebuildPalette.Panel).then(click).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ApprovedAbilityMedallion(key, Modifier.size(22.dp))
            Text(key, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
        }
        Surface(color = RebuildPalette.Elevated, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
            Text(score?.toString() ?: "?", modifier = Modifier.padding(vertical = 2.dp), color = RebuildPalette.Gold,
                fontFamily = InterfaceFace, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Surface(shape = RoundedCornerShape(50), color = RebuildPalette.Active,
            border = BorderStroke(.7.dp, RebuildPalette.Cyan.copy(alpha = .5f))) {
            Text(signedModifier(modifierValue), color = RebuildPalette.Ice, modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CharacterNumberField(label: String, value: String, change: (String) -> Unit, enabled: Boolean = true, signed: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CharacterProfileDialog(snapshot: CharacterProfile?, latest: CharacterProfile?, records: CampaignRecordsRepository,
    profileId: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(snapshot?.name.orEmpty()) }
    var hp by rememberSaveable { mutableStateOf(snapshot?.currentHp?.toString().orEmpty()) }
    var maximum by rememberSaveable { mutableStateOf(snapshot?.maximumHp?.toString().orEmpty()) }
    var temporary by rememberSaveable { mutableStateOf(snapshot?.temporaryHp?.toString().orEmpty()) }
    var ac by rememberSaveable { mutableStateOf(snapshot?.armorClass?.toString().orEmpty()) }
    var level by rememberSaveable { mutableStateOf(snapshot?.level?.toString().orEmpty()) }
    var source by rememberSaveable { mutableStateOf(snapshot?.source.orEmpty()) }
    val actions = rememberLocalRecordActions()
    val stale = latest?.revision != snapshot?.revision
    val dirty = name != snapshot?.name.orEmpty() || hp != snapshot?.currentHp?.toString().orEmpty() ||
        maximum != snapshot?.maximumHp?.toString().orEmpty() || temporary != snapshot?.temporaryHp?.toString().orEmpty() ||
        ac != snapshot?.armorClass?.toString().orEmpty() || level != snapshot?.level?.toString().orEmpty() || source != snapshot?.source.orEmpty()
    PlayerSheet("Edit character status", onDismiss, dirty = dirty, actions = {
        if (!dirty) PlayerTextButton(onClick = LocalPlayerDismiss.current, enabled = !actions.busy) { Text("Cancel") }
        PlayerButton(onClick = {
            actions.run {
                val model = (snapshot ?: CharacterProfile(id = profileId)).copy(name = name.trim(), currentHp = optionalNumber(hp, "Current HP"),
                    maximumHp = optionalNumber(maximum, "Maximum HP"), temporaryHp = optionalNumber(temporary, "Temporary HP"),
                    armorClass = optionalNumber(ac, "Armor class"), level = optionalNumber(level, "Level"))
                records.saveProfile(model, snapshot?.revision, source.trim()); onSaved(); "Saved locally."
            }
        }, enabled = !actions.busy && !stale && source.isNotBlank()) { Text("Save character status") }
    }) {
        if (stale) Text("Status changed. Close and reopen to review it.", color = MaterialTheme.colorScheme.error)
        Field("Character name (optional)", name, { name = it }, enabled = !actions.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { CharacterNumberField("Current HP (blank = unknown)", hp, { hp = it }, !actions.busy) }
            Column(Modifier.weight(1f)) { CharacterNumberField("Maximum HP (blank = unknown)", maximum, { maximum = it }, !actions.busy) }
        }
        CharacterNumberField("Temporary HP (blank = unknown)", temporary, { temporary = it }, !actions.busy)
        CharacterNumberField("Armor class (blank = unknown)", ac, { ac = it }, !actions.busy)
        CharacterNumberField("Character level (blank = unknown)", level, { level = it }, !actions.busy)
        Field("Character status source (required)", source, { source = it }, single = false, enabled = !actions.busy)
        Text("Blank values stay unknown. Combat settings stay separate.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (actions.message.isNotBlank()) Text(actions.message, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CharacterAbilityDialog(key: String, snapshot: CharacterAbility?, latest: CharacterAbility?, profileExists: Boolean,
    profileId: String, records: CampaignRecordsRepository, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var score by rememberSaveable(key) { mutableStateOf(snapshot?.score?.toString().orEmpty()) }
    var magic by rememberSaveable(key) { mutableStateOf(snapshot?.magicModifier?.toString() ?: "0") }
    var notes by rememberSaveable(key) { mutableStateOf(snapshot?.notes.orEmpty()) }
    var source by rememberSaveable(key) { mutableStateOf(snapshot?.source.orEmpty()) }
    var notesOpen by rememberSaveable(key) { mutableStateOf(false) }
    val actions = rememberLocalRecordActions()
    val stale = latest?.revision != snapshot?.revision
    val scorePreview = score.toIntOrNull()?.takeIf { it in 0..1000 }
    val magicPreview = magic.toIntOrNull()?.takeIf { it in -1000..1000 }
    val basePreview = scorePreview?.let { Math.floorDiv(it - 10, 2) }
    val totalPreview = if (basePreview != null && magicPreview != null) basePreview + magicPreview else null
    val dirty = score != snapshot?.score?.toString().orEmpty() || magic != (snapshot?.magicModifier?.toString() ?: "0") ||
        notes != snapshot?.notes.orEmpty() || source != snapshot?.source.orEmpty()
    PlayerSheet("Edit ${abilityNames[key] ?: key}", onDismiss, dirty = dirty, actions = {
        if (!dirty) PlayerTextButton(onClick = LocalPlayerDismiss.current, enabled = !actions.busy) { Text("Cancel") }
        PlayerButton(onClick = {
            actions.run {
                val raw = optionalNumber(score, "Ability score")
                val magical = magic.toIntOrNull() ?: error("Magical modifier must be a whole number.")
                val model = (snapshot ?: CharacterAbility(key = key, profileId = profileId)).copy(score = raw, magicModifier = magical, notes = notes)
                records.saveAbility(model, snapshot?.revision, source.trim()); onSaved(); "Saved locally."
            }
        }, enabled = !actions.busy && !stale && profileExists && source.isNotBlank()) { Text("Save ability") }
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AbilityTile(key, abilityNames[key] ?: key, scorePreview, totalPreview, Modifier.width(92.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Base ${signedModifier(basePreview)}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("Magic ${signedModifier(magicPreview)}", color = RebuildPalette.Ice, style = MaterialTheme.typography.bodyMedium)
                Text("Total ${signedModifier(totalPreview)}", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (!profileExists) Text("Save a sourced character status first.", color = RebuildPalette.Ice)
        if (stale) Text("This ability changed. Close and reopen before saving.", color = MaterialTheme.colorScheme.error)
        CharacterNumberField("Raw ability score (blank = unknown)", score, { score = it }, !actions.busy)
        CharacterNumberField("Separate magical modifier", magic, { magic = it }, !actions.busy, signed = true)
        Field("Ability source (required)", source, { source = it }, single = false, enabled = !actions.busy)
        PlayerTextButton(onClick = { notesOpen = !notesOpen }) { Text(if (notesOpen) "Hide modifier sources" else "Modifier sources") }
        if (notesOpen) {
            Field("Ability / magic source notes", notes, { notes = it }, single = false, enabled = !actions.busy)
            Text("Check- or save-only bonuses stay in source notes. These display values do not change the weapon engine.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (actions.message.isNotBlank()) Text(actions.message, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
    }
}
