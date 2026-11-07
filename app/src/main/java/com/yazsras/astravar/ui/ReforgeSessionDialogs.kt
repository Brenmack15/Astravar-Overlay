package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*

@Composable internal fun ReforgeHpDialog(latestCharacter: ReforgeCharacter, latestProjection: CharacterProjection,
    onDismiss: () -> Unit, onSave: (String, ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val projection = remember(character) { CharacterCalculator.calculate(character) }
    var amount by rememberSaveable { mutableStateOf("") }
    var damageType by rememberSaveable { mutableStateOf("Final amount") }
    var magical by rememberSaveable { mutableStateOf(false) }
    var pendingDamage by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var current by rememberSaveable { mutableStateOf(projection.currentHp?.toString() ?: "") }
    var temporary by rememberSaveable { mutableStateOf(character.temporaryHp?.toString() ?: "") }
    var error by remember { mutableStateOf("") }
    fun run(label: String, transform: () -> ReforgeCharacter) {
        try { onSave(label, transform()) } catch (e: Exception) { error = e.message ?: "Check HP" }
    }
    PlayerSheet("Hit points", onDismiss) {
        Text("HP ${projection.currentHp ?: "?"} / ${projection.maximumHp.value ?: "?"} · Temporary ${character.temporaryHp ?: "?"}", style = MaterialTheme.typography.titleLarge)
        Field("Damage or healing amount", amount, { amount = it; pendingDamage = null })
        Choices("Damage interpretation", listOf("Final amount") + DamageKind.entries.map { it.name }, damageType,
            display = { if (it == "Final amount") "Already adjusted final amount" else it.lowercase().replaceFirstChar { c -> c.uppercase() } }) { damageType = it; pendingDamage = null }
        if (damageType != "Final amount") Toggle("Magical damage", magical) { magical = it; pendingDamage = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerOutlinedButton(onClick = {
                try {
                    val raw = optionalInt(amount, "Damage", 0, 100000) ?: error("Enter damage")
                    val resolution = if (damageType == "Final amount") null else AttackRules.resolveIncoming(character, DamageKind.valueOf(damageType), raw, magical)
                    pendingDamage = (resolution?.components?.joinToString { "${it.rolled} → ${it.applied} · ${it.explanation}" } ?: "$raw final damage") to (resolution?.total ?: raw)
                    error = ""
                } catch (e: Exception) { error = e.message ?: "Check damage" }
            }, modifier = Modifier.weight(1f)) { Text("Take damage") }
            PlayerButton(onClick = { run("Apply healing") { GameplayRules.heal(character, optionalInt(amount, "Healing", 0, 100000) ?: error("Enter healing")) } }, modifier = Modifier.weight(1f)) { Text("Heal") }
        }
        pendingDamage?.let { (summary, applied) -> PlayerCard {
            Text(summary, color = RebuildPalette.Ice)
            PlayerButton(onClick = { run("Apply confirmed damage") { GameplayRules.damage(character, applied) } }) { Text("Confirm $applied damage") }
        } }
        PlayerDisclosure("Record current HP directly") {
            Text("Use this when reconciling a confirmed value. It does not imply a rest. Blank preserves an explicitly unknown value.", color = RebuildPalette.Muted)
            Field("Current HP", current, { current = it })
            Field("Temporary HP", temporary, { temporary = it })
            PlayerButton(onClick = { run("Record confirmed HP") {
                val hp = optionalInt(current, "Current HP", 0, 100000)
                val maximumValue = projection.maximumHp.value
                require(hp == null || maximumValue == null || hp <= maximumValue) { "Current HP exceeds maximum" }
                character.copy(currentHp = hp, temporaryHp = optionalInt(temporary, "Temporary HP", 0, 100000),
                    hpDamage = hp?.let { value -> projection.maximumHp.value?.minus(value) })
            } }) { Text("Save confirmed HP") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeSlotDialog(latestCharacter: ReforgeCharacter, latestProjection: CharacterProjection,
    onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val projection = remember(character) { CharacterCalculator.calculate(character) }
    var values by rememberSaveable { mutableStateOf(projection.slotMaxima.keys.associate { it.toString() to (character.spellSlots[it]?.toString() ?: "") }) }
    var error by remember { mutableStateOf("") }
    PlayerSheet("Current spell slots", onDismiss, dirty = true, actions = {
        PlayerButton(onClick = {
            try {
                val slots = projection.slotMaxima.mapValues { (level, max) -> optionalInt(values[level.toString()].orEmpty(), "Level $level slots", 0, max) }
                onSave(character.copy(spellSlots = slots))
            } catch (e: Exception) { error = e.message ?: "Check slots" }
        }) { Text("Save slots") }
    }) {
        Text("Record current availability. Capacity is calculated separately. Blank is unknown; zero is exhausted.", color = RebuildPalette.Muted)
        projection.slotMaxima.forEach { (level, max) -> Field("Level $level · maximum $max", values[level.toString()].orEmpty(), { values = values + (level.toString() to it) }) }
        if (projection.slotMaxima.isEmpty()) Text("No slot capacity configured. Set a pinned compatible spellcasting class first.", color = RebuildPalette.GoldBright)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeConditionDialog(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    var next by remember { mutableStateOf(character) }
    var exhaustion by rememberSaveable { mutableStateOf(character.exhaustionLevel.toString()) }
    var custom by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    fun toggle(condition: String, enabled: Boolean) {
        try { next = GameplayRules.setCondition(next, condition, enabled); error = "" }
        catch (e: Exception) { error = e.message ?: "Condition unavailable" }
    }
    PlayerSheet("Conditions", onDismiss, dirty = next != character, actions = { PlayerButton(onClick = {
        try { onSave(next.copy(exhaustionLevel = optionalInt(exhaustion, "Exhaustion level", 0, 6) ?: 0)) }
        catch (e: Exception) { error = e.message ?: "Check exhaustion" }
    }) { Text("Apply conditions") } }) {
        Text("Active conditions affect applicable checks and casting. Incapacitating conditions end concentration.", color = RebuildPalette.Muted)
        (listOf("blinded", "charmed", "deafened", "frightened", "grappled", "incapacitated", "invisible", "paralyzed", "petrified", "poisoned", "prone", "restrained", "stunned", "unconscious") + next.conditions).distinct().forEach { condition ->
            Toggle(condition.replaceFirstChar { it.uppercase() }, condition in next.conditions) { toggle(condition, it) }
        }
        Field("Exhaustion level (0–6)", exhaustion, { exhaustion = it })
        Field("Other condition", custom, { custom = it })
        PlayerTextButton(enabled = custom.isNotBlank(), onClick = { toggle(custom.trim().lowercase(), true); custom = "" }) { Text("Add condition") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeRestDialog(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onPreview: (String, ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    var kind by remember { mutableStateOf(Recharge.SHORT_REST) }
    var hours by rememberSaveable { mutableStateOf("1") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    PlayerSheet("Rest & recovery", onDismiss) {
        Choices("Completed recovery", listOf(Recharge.SHORT_REST, Recharge.LONG_REST, Recharge.DAWN), kind) {
            kind = it; hours = if (it == Recharge.LONG_REST) "8" else if (it == Recharge.SHORT_REST) "1" else "0"; confirmed = false
        }
        Field("Actual in-world hours elapsed", hours, { hours = it; confirmed = false })
        Text("Eligible configured resources recover. Unknown uses and slots stay unknown. An eligible Long Rest restores known HP and slots under the selected rules. Unknown values stay unknown. Short Rests do not roll Hit Dice or infer healing. Conditions are not all cleared.", color = RebuildPalette.Muted)
        Toggle("The rest or dawn occurred in the campaign", confirmed) { confirmed = it }
        PlayerButton(enabled = confirmed, onClick = {
            try {
                val elapsed = (optionalInt(hours, "Elapsed hours", 0, 8760) ?: error("Enter elapsed hours")).toLong() * 3600
                onPreview("Confirm ${kind.name.lowercase().replace('_', ' ')}", GameplayRules.rest(character, kind, confirmed = true, elapsedSeconds = elapsed))
            } catch (e: Exception) { error = e.message ?: "Rest cannot be applied" }
        }) { Text("Preview recovery") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeCalculationDialog(title: String, projection: CharacterProjection, onDismiss: () -> Unit) {
    PlayerSheet(title, onDismiss) {
        Text("Armor Class · ${projection.armorClass.value ?: "Unknown"}", style = MaterialTheme.typography.titleLarge)
        projection.armorClass.explanation.forEach { Text("${it.label}${it.value?.let { value -> ": $value" } ?: ""}${it.reason.takeIf { value -> value.isNotBlank() }?.let { value -> " · $value" } ?: ""}", color = RebuildPalette.Muted) }
        Text("Maximum HP · ${projection.maximumHp.value ?: "Unknown"}", style = MaterialTheme.typography.titleLarge)
        projection.maximumHp.explanation.forEach { Text("${it.label}${it.value?.let { value -> ": $value" } ?: ""}", color = RebuildPalette.Muted) }
        if (projection.defenses.isEmpty()) Text("No structured damage defenses active", color = RebuildPalette.Muted)
        projection.defenses.forEach { Text("${it.type.name.lowercase()} · ${it.kind.name.lowercase()}${if (it.nonmagicalOnly) " (nonmagical)" else ""}") }
        if (projection.conditionImmunities.isNotEmpty()) Text("Condition immunities · ${projection.conditionImmunities.joinToString()}")
    }
}

@Composable internal fun ReforgeEquipmentPreview(label: String, before: ReforgeCharacter, after: ReforgeCharacter,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val old = remember(before) { CharacterCalculator.calculate(before) }
    val new = remember(after) { CharacterCalculator.calculate(after) }
    PlayerSheet(label, onDismiss, actions = { PlayerButton(onClick = onConfirm) { Text("Confirm change") } }) {
        Text("Calculated changes", style = MaterialTheme.typography.titleLarge)
        Stat.entries.forEach { stat ->
            val a = old.abilities.getValue(stat).effective.value; val b = new.abilities.getValue(stat).effective.value
            if (a != b) Text("${stat.name}: ${a ?: "?"} → ${b ?: "?"}", color = RebuildPalette.Ice)
        }
        listOf(Triple("Armor Class", old.armorClass.value, new.armorClass.value), Triple("Maximum HP", old.maximumHp.value, new.maximumHp.value),
            Triple("Current HP", old.currentHp, new.currentHp), Triple("Spell attack", old.spellAttack.value, new.spellAttack.value),
            Triple("Spell save DC", old.spellSaveDc.value, new.spellSaveDc.value)).forEach { (name, a, b) ->
            Text("$name: ${a ?: "?"} → ${b ?: "?"}", color = if (a != b) RebuildPalette.Ice else RebuildPalette.Muted)
        }
        after.resources.forEach { pool -> val prior = before.resources.firstOrNull { it.id == pool.id }; if (prior?.current != pool.current) Text("${pool.name}: ${prior?.current ?: "?"} → ${pool.current ?: "?"}", color = RebuildPalette.Ice) }
        old.slotMaxima.keys.plus(new.slotMaxima.keys).forEach { level -> if (before.spellSlots[level] != after.spellSlots[level]) Text("Level $level slots: ${before.spellSlots[level] ?: "?"} → ${after.spellSlots[level] ?: "?"}", color = RebuildPalette.Ice) }
        Text("Outstanding damage is preserved when maximum HP changes. No completed rest or healing is inferred.", color = RebuildPalette.Muted)
    }
}

@Composable internal fun ReforgeCastDialog(latestCharacter: ReforgeCharacter, latestProjection: CharacterProjection, spell: SpellDefinition,
    onDismiss: () -> Unit, onPreview: (CastPreview) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val projection = remember(character) { CharacterCalculator.calculate(character) }
    var slot by remember { mutableStateOf<Int?>(if (spell.level > 0 && spell.slotRequired) spell.level else null) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var ritual by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    PlayerSheet("Cast ${spell.name}", onDismiss) {
        Text("${spell.castingTime} · ${spell.components}\n${spell.range} · ${spell.duration}", color = RebuildPalette.Muted)
        if (spell.level > 0 && spell.slotRequired && !ritual) Choices("Spell-slot level", projection.slotMaxima.keys.filter { it >= spell.level }.sorted(), slot) { slot = it }
        if (spell.ritual) Toggle("Ritual casting (+10 minutes, no slot)", ritual) { ritual = it; slot = if (it || spell.level == 0 || !spell.slotRequired) null else spell.level }
        if (spell.concentration && character.concentrationSpellId != null) Toggle("Replace current concentration", replace) { replace = it }
        PlayerButton(onClick = {
            try { onPreview(GameplayRules.previewCast(character, spell.id, slot, replaceConcentration = replace, ritual = ritual)) }
            catch (e: Exception) { error = e.message ?: "Casting is unavailable" }
        }) { Text("Preview casting cost") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeCastConfirmation(preview: CastPreview, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PlayerSheet("Confirm spellcasting", onDismiss, actions = { PlayerButton(onClick = onConfirm) { Text("Cast & save") } }) {
        Text(preview.summary, style = MaterialTheme.typography.titleLarge)
        Text("Nothing is consumed until you confirm. The current saved revision is checked again before the result is persisted.", color = RebuildPalette.Muted)
    }
}
