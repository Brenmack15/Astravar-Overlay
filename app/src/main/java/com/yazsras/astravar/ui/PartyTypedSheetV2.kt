package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val partyUiJson = Json { encodeDefaults = true }
private fun known(text: String): String = text.ifBlank { "Unknown" }
private fun plus(value: Int?): String = value?.let { if (it >= 0) "+$it" else "$it" } ?: "?"
private fun lines(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

/** No review-only stat block is seeded here. Each accepted change has its own source and revision. */
@Composable internal fun PartyTypedSheetV2(repository: CampaignLedgerRepository, memberId: String, memberName: String) {
    val sheets by repository.party.sheets.collectAsState(initial = emptyList())
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    val companionResources by repository.records.companionResources.collectAsState(initial = emptyList())
    val record = sheets.firstOrNull { it.profileId == memberId }
    val sheet = record?.sheet ?: PartySheetData()
    var editing by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    val snapshotState = rememberUiDraftText("")
    var snapshotJson by snapshotState
    var snapshotRevision by rememberSaveable(memberId) { mutableStateOf<Long?>(null) }
    var snapshotSource by rememberSaveable(memberId) { mutableStateOf("") }
    val edit: (String) -> Unit = { section ->
        snapshotJson = partyUiJson.encodeToString(sheet)
        snapshotRevision = record?.revision
        snapshotSource = record?.source.orEmpty()
        editing = section
    }
    Text("Character & creature sheet", style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    if (sheet.unresolvedConflicts.isNotEmpty()) PlayerCard {
        Text("Unresolved source conflicts", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium)
        sheet.unresolvedConflicts.forEach { Text("• $it", color = RebuildPalette.Muted) }
        Text("Conflicting values are not automatically selected or recalculated.", color = RebuildPalette.Ice,
            style = MaterialTheme.typography.bodySmall)
    }
    PartySheetSection("Identity & progression", { edit("Identity") }) {
        if (sheet.portraitBase64 != null) LedgerIcon(sheet.portraitBase64, Modifier.size(104.dp), "Portrait for $memberName")
        RecordValue("Type / size", listOf(known(sheet.creatureType), known(sheet.size)).joinToString(" · "))
        RecordValue("Class / role", known(sheet.role))
        RecordValue("Creature progression / accepted upgrades", known(sheet.progression))
        RecordValue("Proficiency / initiative", "${plus(sheet.proficiencyBonus)} / ${plus(sheet.initiative)}")
        RecordValue("Armor Class sources", known(sheet.armorClassSources))
    }
    PartySheetSection("Movement & senses", { edit("Movement") }) {
        if (sheet.movement.isEmpty()) Text("Movement unknown", color = RebuildPalette.Muted)
        sheet.movement.forEach { Text("${it.type}: ${it.feet?.let { feet -> "$feet ft" } ?: "unknown"}${it.notes.takeIf(String::isNotBlank)?.let { note -> " · $note" }.orEmpty()}") }
        RecordValue("Senses", known(sheet.senses)); RecordValue("Languages", known(sheet.languages))
    }
    PartySheetSection("Saving throws & skills", { edit("Proficiencies") }) {
        RecordValue("Saving throws", sheet.savingThrows.takeIf(List<SheetModifier>::isNotEmpty)?.joinToString("\n") { modifierDescription(it) } ?: "Unknown")
        RecordValue("Skills / tool & other proficiencies", sheet.skills.takeIf(List<SheetModifier>::isNotEmpty)?.joinToString("\n") { modifierDescription(it) } ?: "Unknown")
    }
    PartySheetSection("Defences & conditions", { edit("Defences") }) {
        RecordValue("Resistances", known(sheet.resistances)); RecordValue("Immunities", known(sheet.immunities))
        RecordValue("Vulnerabilities", known(sheet.vulnerabilities)); RecordValue("Conditions", known(sheet.conditions))
    }
    PartySheetSection("Features, actions & capabilities", { edit("Capabilities") }) {
        if (sheet.capabilities.isEmpty()) Text("No sourced features recorded.", color = RebuildPalette.Muted)
        sheet.capabilities.forEach { capability ->
            PlayerCard(padding = 12.dp) {
                Text(capability.name, style = MaterialTheme.typography.titleMedium)
                Text("${capability.kind} · ${capability.evidenceStatus}", color = RebuildPalette.Ice,
                    style = MaterialTheme.typography.labelLarge)
                Text(capability.detail)
                if (capability.source.isNotBlank()) RecordValue("Source", capability.source)
            }
        }
    }
    PartySheetSection("Attacks & multiattacks", { edit("Attacks") }) {
        if (sheet.attacks.isEmpty()) Text("No attack mechanics recorded. Nothing is inferred from level.", color = RebuildPalette.Muted)
        sheet.attacks.forEach { attack ->
            PlayerCard(padding = 12.dp) {
                Text(attack.name, style = MaterialTheme.typography.titleMedium)
                Text("${attack.actionEconomy} · ${attack.evidenceStatus}", color = RebuildPalette.Ice,
                    style = MaterialTheme.typography.labelLarge)
                RecordValue("Attack / damage", "${plus(attack.attackBonus)} · ${known(attack.damageFormula)} ${attack.damageType}".trim())
                if (attack.range.isNotBlank()) RecordValue("Range / reach", attack.range)
                if (attack.criticalRule.isNotBlank()) RecordValue("Critical rule", attack.criticalRule)
                if (attack.resourceCost.isNotBlank()) RecordValue("Resource cost", attack.resourceCost)
                if (attack.multiattack.isNotBlank()) RecordValue("Multiattack / sequence", attack.multiattack)
                if (attack.source.isNotBlank()) RecordValue("Source", attack.source)
            }
        }
        Text("These sourced sheet entries do not automatically execute Astravar's weapon engine or spend a turn.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }
    PartySheetSection("Spellcasting & resources", { edit("Spellcasting") }) {
        RecordValue("Spellcasting, known spells & accepted slot rules", known(sheet.spellcasting))
        if (sheet.resources.isNotEmpty()) Text("Manual sheet resource references", style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Ice)
        sheet.resources.forEach { resource ->
            RecordValue(resource.name, "${resource.current ?: "?"} / ${resource.maximum ?: "?"}${resource.notes.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}")
            companionResources.firstOrNull { it.companionId == memberId && it.name.trim().equals(resource.name.trim(), true) }?.let { counter ->
                Text("A tracked companion counter also exists: ${counter.current ?: "?"} / ${counter.maximum ?: "?"}. Its value below remains authoritative; reconcile this sheet reference before play.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        PlayerTextButton(onClick = { edit("Resources") }) { Text("Edit spell / sheet resources") }
        Text("Companion-specific counters below retain their existing records. Editing a sheet never refills them.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }
    PartySheetSection("Sources & unresolved decisions", { edit("Sources") }) {
        if (sheet.references.isEmpty()) Text("No source references recorded.", color = RebuildPalette.Muted)
        sheet.references.forEach { Text(it, color = RebuildPalette.Ice) }
        record?.let { Text("Sheet revision ${it.revision} · ${it.source}", color = RebuildPalette.Muted,
            style = MaterialTheme.typography.bodySmall) }
    }
    editing?.let { section ->
        if (!snapshotState.canSave || snapshotJson.isNullOrBlank()) {
            PlayerSheet("Sheet draft unavailable", onDismiss = { editing = null }) {
                Text(snapshotState.error ?: "Close and reopen the saved sheet to recover its baseline.", color = MaterialTheme.colorScheme.error)
            }
            return@let
        }
        PartySheetEditorV2(repository, memberId, memberName, section, snapshotJson.orEmpty(),
            snapshotRevision, snapshotSource, record?.revision, profiles.any { it.id == memberId }, onDismiss = { editing = null })
    }
}

private fun modifierDescription(value: SheetModifier): String =
    "${value.name} ${plus(value.modifier)}" + when(value.proficient) {
        true -> " · proficient"; false -> " · not proficient"; null -> " · proficiency unknown"
    } + value.notes.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()

@Composable private fun PartySheetSection(title: String, edit: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PlayerDisclosure(title) {
        content()
        PlayerTextButton(onClick = edit) { Text("Edit $title") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PartyChoices(label: String, selected: String, choices: List<String>, change: (String) -> Unit) {
    Column {
        Text(label, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice -> PlayerFilterChip(selected == choice, onClick = { change(choice) }, label = { Text(choice) }) }
        }
    }
}

/** Invalid intermediate input stays visible and disables Save rather than silently becoming zero. */
@Composable private fun PartyNumberField(key: String, label: String, value: Int?, errors: MutableMap<String, String>, change: (Int?) -> Unit) {
    var text by rememberSaveable(key) { mutableStateOf(value?.toString().orEmpty()) }
    SideEffect {
        if (text.isBlank() || text.toIntOrNull() != null) errors.remove(key)
        else errors[key] = "$label must be a whole number or blank."
    }
    OutlinedTextField(text, { new ->
        text = new
        if (new.isBlank() || new.toIntOrNull() != null) { errors.remove(key); change(new.toIntOrNull()) }
        else errors[key] = "$label must be a whole number or blank."
    }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = errors.containsKey(key), supportingText = { Text(errors[key] ?: "Blank means unknown") })
}

@Composable private fun PartySheetEditorV2(repository: CampaignLedgerRepository, memberId: String,
    memberName: String, section: String, snapshotJson: String, expectedRevision: Long?, previousSource: String,
    latestRevision: Long?, profileExists: Boolean, onDismiss: () -> Unit) {
    val draftState = rememberUiDraftText(snapshotJson)
    var draftJson by draftState
    var source by rememberSaveable(memberId, section, snapshotJson) { mutableStateOf(previousSource) }
    val draft = draftJson?.let { runCatching { partyUiJson.decodeFromString<PartySheetData>(it) }.getOrNull() }
    if (draft == null || !draftState.canSave) {
        PlayerSheet("$memberName · $section", onDismiss) {
            Text(draftState.error ?: "This sheet draft could not be read. Close and reopen the saved sheet before editing.", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val update: (PartySheetData) -> Unit = { draftJson = partyUiJson.encodeToString(it) }
    val errors = remember(memberId, section) { mutableStateMapOf<String, String>() }
    var movementKeys by rememberSaveable(memberId, section, snapshotJson) { mutableStateOf(draft.movement.map { UUID.randomUUID().toString() }) }
    var resourceKeys by rememberSaveable(memberId, section, snapshotJson) { mutableStateOf(draft.resources.map { UUID.randomUUID().toString() }) }
    val actions = rememberLocalRecordActions()
    val commandId = rememberSaveable(memberId, section, snapshotJson) { UUID.randomUUID().toString() }
    val stale = latestRevision != expectedRevision
    val referencesState = rememberUiDraftText(draft.references.joinToString("\n"))
    var references by referencesState
    val unresolvedState = rememberUiDraftText(draft.unresolvedConflicts.joinToString("\n"))
    var unresolved by unresolvedState
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !actions.busy) actions.run {
            update(draft.copy(portraitBase64 = compressIcon(context, uri)))
            "Portrait ready; save the sheet to commit it."
        }
    }
    PlayerSheet("$memberName · $section", onDismiss,
        dirty = draftJson != snapshotJson || source != previousSource || errors.isNotEmpty(), actions = {
            PlayerButton(onClick = { actions.run {
                val receipt = repository.party.saveSheet(memberId, draft, expectedRevision, source.trim(), commandId)
                onDismiss()
                "Saved sheet revision ${receipt.resultingRevision}."
            } }, enabled = !actions.busy && !stale && profileExists && draftState.canSave && referencesState.canSave && unresolvedState.canSave && errors.isEmpty() && source.isNotBlank()) { Text("Save sheet") }
        }) {
        if (!profileExists) Text("Save a sourced character status first; unknown statistics may stay blank.", color = RebuildPalette.Ice)
        if (stale) Text("This sheet changed. Close and reopen it to review the current revision.", color = MaterialTheme.colorScheme.error)
        Text("Record confirmed changes with their source. Unapproved ideas must remain Proposed or Unverified. No level-up roll, bonus or recharge is invented.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        when (section) {
            "Identity" -> {
                if (draft.portraitBase64 != null) LedgerIcon(draft.portraitBase64, Modifier.size(104.dp), "Draft portrait for $memberName")
                PlayerTextButton(onClick = { imagePicker.launch("image/*") }, enabled = !actions.busy) { Text("Choose portrait") }
                if (draft.portraitBase64 != null) PlayerTextButton(onClick = { update(draft.copy(portraitBase64 = null)) }) { Text("Remove custom portrait") }
                Field("Creature type", draft.creatureType, { update(draft.copy(creatureType = it)) })
                Field("Size", draft.size, { update(draft.copy(size = it)) })
                Field("Class / role", draft.role, { update(draft.copy(role = it)) })
                Field("Progression and installed upgrades", draft.progression, { update(draft.copy(progression = it)) }, single = false)
                PartyNumberField("proficiency", "Confirmed proficiency bonus", draft.proficiencyBonus, errors) { update(draft.copy(proficiencyBonus = it)) }
                PartyNumberField("initiative", "Confirmed initiative modifier", draft.initiative, errors) { update(draft.copy(initiative = it)) }
                Field("Armor Class sources", draft.armorClassSources, { update(draft.copy(armorClassSources = it)) }, single = false)
            }
            "Movement" -> {
                draft.movement.forEachIndexed { index, row -> key(movementKeys[index]) {
                    PlayerCard {
                        Field("Movement type ${index + 1}", row.type, { value -> update(draft.copy(movement = draft.movement.toMutableList().apply { set(index, row.copy(type = value)) })) })
                        PartyNumberField("movement-${movementKeys[index]}", "Movement feet ${index + 1}", row.feet, errors) { value ->
                            update(draft.copy(movement = draft.movement.toMutableList().apply { set(index, row.copy(feet = value)) }))
                        }
                        Field("Movement notes ${index + 1}", row.notes, { value -> update(draft.copy(movement = draft.movement.toMutableList().apply { set(index, row.copy(notes = value)) })) }, single = false)
                        PlayerTextButton(onClick = { errors.remove("movement-${movementKeys[index]}"); movementKeys = movementKeys.filterIndexed { i, _ -> i != index }; update(draft.copy(movement = draft.movement.filterIndexed { i, _ -> i != index })) }) { Text("Remove movement ${index + 1}") }
                    }
                } }
                PlayerTextButton(onClick = { movementKeys = movementKeys + UUID.randomUUID().toString(); update(draft.copy(movement = draft.movement + SheetMovement(type = ""))) }) { Text("Add movement type") }
                Field("Senses", draft.senses, { update(draft.copy(senses = it)) }, single = false)
                Field("Languages", draft.languages, { update(draft.copy(languages = it)) }, single = false)
            }
            "Proficiencies" -> {
                PartyModifierEditor("Saving throw", draft.savingThrows, errors) { update(draft.copy(savingThrows = it)) }
                PartyModifierEditor("Skill / proficiency", draft.skills, errors) { update(draft.copy(skills = it)) }
            }
            "Defences" -> {
                Field("Resistances", draft.resistances, { update(draft.copy(resistances = it)) }, single = false)
                Field("Immunities", draft.immunities, { update(draft.copy(immunities = it)) }, single = false)
                Field("Vulnerabilities", draft.vulnerabilities, { update(draft.copy(vulnerabilities = it)) }, single = false)
                Field("Conditions", draft.conditions, { update(draft.copy(conditions = it)) }, single = false)
            }
            "Capabilities" -> {
                draft.capabilities.forEachIndexed { index, row -> key(row.id) {
                    PlayerCard {
                        val replace: (SheetCapability) -> Unit = { next -> update(draft.copy(capabilities = draft.capabilities.toMutableList().apply { set(index, next) })) }
                        Field("Feature name ${index + 1}", row.name, { replace(row.copy(name = it)) })
                        PartyChoices("Capability type", row.kind, listOf("Trait", "Passive", "Action", "Bonus action", "Reaction", "Upgrade")) { replace(row.copy(kind = it)) }
                        Field("Feature details ${index + 1}", row.detail, { replace(row.copy(detail = it)) }, single = false)
                        PartyEvidence(row.evidenceStatus) { replace(row.copy(evidenceStatus = it)) }
                        Field("Feature source ${index + 1}", row.source, { replace(row.copy(source = it)) }, single = false)
                        PlayerTextButton(onClick = { update(draft.copy(capabilities = draft.capabilities.filterNot { it.id == row.id })) }) { Text("Remove feature ${index + 1}") }
                    }
                } }
                PlayerTextButton(onClick = { update(draft.copy(capabilities = draft.capabilities + SheetCapability(name = "", detail = ""))) }) { Text("Add feature or capability") }
            }
            "Attacks" -> {
                draft.attacks.forEachIndexed { index, row -> key(row.id) {
                    PlayerCard {
                        val replace: (SheetAttack) -> Unit = { next -> update(draft.copy(attacks = draft.attacks.toMutableList().apply { set(index, next) })) }
                        Field("Attack name ${index + 1}", row.name, { replace(row.copy(name = it)) })
                        PartyNumberField("attack-${row.id}", "Attack bonus ${index + 1}", row.attackBonus, errors) { replace(row.copy(attackBonus = it)) }
                        Field("Damage formula ${index + 1}", row.damageFormula, { replace(row.copy(damageFormula = it)) })
                        Field("Damage type ${index + 1}", row.damageType, { replace(row.copy(damageType = it)) })
                        Field("Range / reach ${index + 1}", row.range, { replace(row.copy(range = it)) })
                        Field("Accepted critical rule ${index + 1}", row.criticalRule, { replace(row.copy(criticalRule = it)) }, single = false)
                        PartyChoices("Action economy", row.actionEconomy, listOf("Action", "Bonus action", "Reaction", "Other")) { replace(row.copy(actionEconomy = it)) }
                        Field("Resource cost ${index + 1}", row.resourceCost, { replace(row.copy(resourceCost = it)) }, single = false)
                        Field("Multiattack / sequence ${index + 1}", row.multiattack, { replace(row.copy(multiattack = it)) }, single = false)
                        PartyEvidence(row.evidenceStatus) { replace(row.copy(evidenceStatus = it)) }
                        Field("Attack source ${index + 1}", row.source, { replace(row.copy(source = it)) }, single = false)
                        PlayerTextButton(onClick = { errors.remove("attack-${row.id}"); update(draft.copy(attacks = draft.attacks.filterNot { it.id == row.id })) }) { Text("Remove attack ${index + 1}") }
                    }
                } }
                PlayerTextButton(onClick = { update(draft.copy(attacks = draft.attacks + SheetAttack(name = ""))) }) { Text("Add attack") }
            }
            "Spellcasting" -> Field("Spellcasting, spells and accepted slot rules", draft.spellcasting,
                { update(draft.copy(spellcasting = it)) }, single = false)
            "Resources" -> {
                Text("These are descriptive sheet / spell resource references. Existing tracked companion counters keep their original authority and are edited separately below the sheet.", color = RebuildPalette.Ice)
                draft.resources.forEachIndexed { index, row -> key(resourceKeys[index]) {
                    PlayerCard {
                        val replace: (SheetResource) -> Unit = { next -> update(draft.copy(resources = draft.resources.toMutableList().apply { set(index, next) })) }
                        Field("Resource name ${index + 1}", row.name, { replace(row.copy(name = it)) })
                        PartyNumberField("resource-current-${resourceKeys[index]}", "Resource current ${index + 1}", row.current, errors) { replace(row.copy(current = it)) }
                        PartyNumberField("resource-maximum-${resourceKeys[index]}", "Resource maximum ${index + 1}", row.maximum, errors) { replace(row.copy(maximum = it)) }
                        Field("Resource notes ${index + 1}", row.notes, { replace(row.copy(notes = it)) }, single = false)
                        PlayerTextButton(onClick = {
                            errors.remove("resource-current-${resourceKeys[index]}"); errors.remove("resource-maximum-${resourceKeys[index]}")
                            resourceKeys = resourceKeys.filterIndexed { i, _ -> i != index }
                            update(draft.copy(resources = draft.resources.filterIndexed { i, _ -> i != index }))
                        }) { Text("Remove sheet resource ${index + 1}") }
                    }
                } }
                PlayerTextButton(onClick = { resourceKeys = resourceKeys + UUID.randomUUID().toString(); update(draft.copy(resources = draft.resources + SheetResource(name = ""))) }) { Text("Add sheet resource") }
            }
            "Sources" -> {
                Field("Source references · one per line", references.orEmpty(), { references = it; update(draft.copy(references = lines(it))) }, single = false)
                Field("Unresolved conflicts · one per line", unresolved.orEmpty(), { unresolved = it; update(draft.copy(unresolvedConflicts = lines(it))) }, single = false)
            }
        }
        listOfNotNull(referencesState.error, unresolvedState.error).distinct().forEach {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Field("Change source / resolved event (required)", source, { source = it }, single = false, enabled = !actions.busy)
        Text("A saved revision preserves the earlier sheet in History. HP, level and abilities use Edit status and the six ability cards.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        LocalRecordStatus(actions.message, actions.busy)
    }
}

@Composable private fun PartyEvidence(selected: String, change: (String) -> Unit) =
    PartyChoices("Evidence", selected, listOf("Confirmed", "Unverified", "Proposed", "Disputed"), change)

@Composable private fun PartyModifierEditor(kind: String, rows: List<SheetModifier>, errors: MutableMap<String, String>, change: (List<SheetModifier>) -> Unit) {
    Text(kind, style = MaterialTheme.typography.titleMedium)
    var rowKeys by rememberSaveable(kind) { mutableStateOf(rows.map { UUID.randomUUID().toString() }) }
    rows.forEachIndexed { index, row -> key(rowKeys[index]) {
        PlayerCard {
            val replace: (SheetModifier) -> Unit = { next -> change(rows.toMutableList().apply { set(index, next) }) }
            Field("$kind name ${index + 1}", row.name, { replace(row.copy(name = it)) })
            PartyNumberField("$kind-${rowKeys[index]}", "$kind modifier ${index + 1}", row.modifier, errors) { replace(row.copy(modifier = it)) }
            PartyChoices("Proficiency", when(row.proficient) { true -> "Proficient"; false -> "Not proficient"; null -> "Unknown" },
                listOf("Unknown", "Proficient", "Not proficient")) { value -> replace(row.copy(proficient = when(value) { "Proficient" -> true; "Not proficient" -> false; else -> null })) }
            Field("$kind notes ${index + 1}", row.notes, { replace(row.copy(notes = it)) }, single = false)
            PlayerTextButton(onClick = { errors.remove("$kind-${rowKeys[index]}"); rowKeys = rowKeys.filterIndexed { i, _ -> i != index }; change(rows.filterIndexed { i, _ -> i != index }) }) { Text("Remove $kind ${index + 1}") }
        }
    } }
    PlayerTextButton(onClick = { rowKeys = rowKeys + UUID.randomUUID().toString(); change(rows + SheetModifier(name = "")) }) { Text("Add $kind") }
}
