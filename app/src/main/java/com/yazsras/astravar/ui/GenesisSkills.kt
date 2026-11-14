package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Composable internal fun GenesisSkills(
    character: ReforgeCharacter, projection: CharacterProjection, busy: Boolean,
    onAdd: () -> Unit, onEdit: (String) -> Unit, onPin: (String, Boolean) -> Unit,
    onRoll: (String, Int, String) -> Unit
) {
    RebirthSectionLabel("Skills") {
        PlayerTextButton(onClick = onAdd, enabled = !busy) { Text("Add skill") }
    }
    val entries = remember(character, projection) { SkillRules.entries(character, projection) }
    val hasPins = entries.any { it.pinned }
    var previousPinned: Boolean? = null
    entries.forEach { skill ->
        if (hasPins && previousPinned != skill.pinned) {
            Text(if (skill.pinned) "Pinned · A–Z" else "Other skills · A–Z", style = MaterialTheme.typography.labelMedium,
                color = RebuildPalette.Muted, modifier = Modifier.padding(top = 8.dp))
        }
        previousPinned = skill.pinned
        key(skill.id) {
            Row(Modifier.fillMaxWidth().testTag("skill-row-${skill.id}"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPin(skill.id, !skill.pinned) }, enabled = !busy,
                    modifier = Modifier.size(48.dp).testTag("skill-pin-${skill.id}").semantics {
                        contentDescription = "${if (skill.pinned) "Unpin" else "Pin"} ${skill.name}${if (skill.custom) " custom skill" else ""}"
                        stateDescription = if (skill.pinned) "Pinned" else "Not pinned"
                    }) {
                    Text(if (skill.pinned) "★" else "☆", style = MaterialTheme.typography.titleLarge,
                        color = if (skill.pinned) RebuildPalette.GoldBright else RebuildPalette.Muted)
                }
                val detail = buildList {
                    add(skill.training.ability.name)
                    when {
                        skill.training.expertise -> add("Expertise")
                        skill.training.proficient -> add("Proficient")
                        skill.training.halfProficiency -> add("Half proficiency")
                    }
                    if (skill.custom) add("Custom")
                }.joinToString(" · ")
                Column(Modifier.weight(1f)) {
                    RebirthCheckRow(skill.name, detail, skill.calculation.value,
                        modifier = Modifier.testTag("skill-roll-${skill.id}")) {
                        skill.calculation.value?.let { onRoll(skill.name, it, skill.id) }
                    }
                }
                if (skill.custom) PlayerTextButton(onClick = { onEdit(skill.id) }, enabled = !busy,
                    modifier = Modifier.testTag("skill-edit-${skill.id}").semantics { contentDescription = "Edit ${skill.name} custom skill" }) { Text("Edit") }
            }
        }
    }
}

@Serializable private data class SkillEditorDraft(
    val name: String = "", val ability: Stat = Stat.INT, val training: String = "Untrained",
    val bonus: String = "0", val notes: String = "", val override: String = ""
)

@Composable internal fun GenesisSkillEditor(
    latestCharacter: ReforgeCharacter, existingId: String? = null, busy: Boolean = false, saveError: String = "",
    onDismiss: () -> Unit, onSave: (String, ReforgeCharacter) -> Unit
) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.customSkills.firstOrNull { it.id == existingId } }
    val id = rememberSaveable { existingId ?: "skill:${UUID.randomUUID()}" }
    val initial = remember {
        SkillEditorDraft(original?.name.orEmpty(), original?.training?.ability ?: Stat.INT, when {
            original?.training?.expertise == true -> "Expertise"
            original?.training?.proficient == true -> "Proficient"
            original?.training?.halfProficiency == true -> "Half proficiency"
            else -> "Untrained"
        }, (original?.bonus ?: 0).toString(), original?.notes.orEmpty(), original?.baselineOverride?.toString().orEmpty())
    }
    val draft = rememberReforgeDraft(initial, SkillEditorDraft.serializer())
    var fields by draft
    var error by remember { mutableStateOf("") }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val stale = character.revision != latestCharacter.revision || character.id != latestCharacter.id
    val available = existingId == null || original != null
    val canSave = draft.canSave && !busy && !stale && available
    fun next(): ReforgeCharacter = SkillRules.save(character, CustomSkill(id, fields.name, SkillTraining(fields.ability,
        proficient = fields.training == "Proficient" || fields.training == "Expertise",
        expertise = fields.training == "Expertise", halfProficiency = fields.training == "Half proficiency"),
        bonus = optionalInt(fields.bonus, "Bonus", -1000, 1000) ?: 0, notes = fields.notes,
        baselineOverride = optionalInt(fields.override, "Baseline override", -1000, 1000)))
    PlayerSheet(if (original == null) "Add custom skill" else "Edit custom skill", onDismiss,
        dirty = fields != initial, dismissEnabled = !busy, actions = {
            if (original != null) PlayerTextButton(onClick = { confirmRemove = true }, enabled = canSave) { Text("Remove skill") }
            PlayerButton(enabled = canSave, onClick = {
                try { onSave(if (original == null) "Add custom skill" else "Edit custom skill", next()) }
                catch (e: IllegalArgumentException) { error = e.message ?: "Review skill fields." }
                catch (e: IllegalStateException) { error = e.message ?: "Review skill fields." }
            }) { Text("Save skill") }
        }) {
        ReforgeDraftRecovery(draft)
        if (stale) Text("This character changed while you were editing. Close and reopen the skill to use the latest state.", color = MaterialTheme.colorScheme.error)
        if (!available) Text("This custom skill is no longer available.", color = MaterialTheme.colorScheme.error)
        Field("Skill name", fields.name, { fields = fields.copy(name = it) }, enabled = canSave)
        Choices("Governing ability", Stat.entries.map { it.name }, fields.ability.name, enabled = canSave) { fields = fields.copy(ability = Stat.valueOf(it)) }
        Choices("Training", listOf("Untrained", "Proficient", "Expertise", "Half proficiency"), fields.training, enabled = canSave) { fields = fields.copy(training = it) }
        Field("Additional bonus", fields.bonus, { fields = fields.copy(bonus = it) }, enabled = canSave)
        Field("Notes (optional)", fields.notes, { fields = fields.copy(notes = it) }, single = false, enabled = canSave)
        PlayerDisclosure("Manual override & calculation") {
            Field("Baseline override (optional)", fields.override, { fields = fields.copy(override = it) }, enabled = canSave)
            Text("An override replaces ability, training and the additional bonus. Active equipment, skill effects and conditions still apply.", color = RebuildPalette.Muted)
            runCatching { CharacterCalculator.calculate(next()).customSkills.getValue(id) }.getOrNull()?.let { calculated ->
                Text("Skill modifier · ${reforgeSigned(calculated.value)}", color = RebuildPalette.GoldBright)
                calculated.explanation.forEach { line -> Text("${line.label}${line.value?.let { ": ${reforgeSigned(it)}" }.orEmpty()}",
                    color = if (line.applied) MaterialTheme.colorScheme.onSurface else RebuildPalette.Muted) }
            }
        }
        if (error.isNotBlank() || saveError.isNotBlank()) Text(error.ifBlank { saveError }, color = MaterialTheme.colorScheme.error)
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { if (!busy) confirmRemove = false },
        title = { Text("Remove ${original?.name.orEmpty()}?") },
        text = { Text("The custom skill and its pin will be removed. Saved rolls and change history remain available.") },
        confirmButton = { PlayerTextButton(enabled = canSave, onClick = {
            onSave("Remove custom skill", SkillRules.remove(character, id)); confirmRemove = false
        }) { Text("Remove") } },
        dismissButton = { PlayerTextButton(enabled = !busy, onClick = { confirmRemove = false }) { Text("Keep skill") } })
}
