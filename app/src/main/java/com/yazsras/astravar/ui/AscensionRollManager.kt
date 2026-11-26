package com.yazsras.astravar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class ManagerOption(val check: QueuedCheck, val bonus: Int?)
internal data class ManagerFormula(val formula: DiceFormula, val mode: DiceMode, val d20Count: Int = 1)
@Serializable internal data class PreparedCheck(val id: String, val check: QueuedCheck, val result: DiceResult)
@Serializable internal data class PreparedRollBatch(val id: String, val revision: String, val rolls: List<PreparedCheck>, val workshop: Boolean, val presetId: String? = null)
internal data class SavedRollBatch(val lines: List<String>, val status: String)
private val rollRandom = SecureRandom()
private val modes = listOf(DiceMode.NORMAL, DiceMode.ADVANTAGE, DiceMode.DISADVANTAGE)

internal fun composeRollEvidence(formula: ManagerFormula, entered: String?, draw: (Int) -> Int = { rollRandom.nextInt(it) + 1 }): DiceResult {
    val faces = entered?.trim()?.let { raw -> if (raw.isEmpty()) emptyList() else raw.split(Regex("[\\s,]+" )).map { value ->
        requireNotNull(value.toIntOrNull()) { "Enter whole die faces separated by spaces." }
    } }
    if (formula.d20Count == 3) {
        require(formula.mode == DiceMode.ADVANTAGE && formula.formula.dice == listOf(DiceTerm(1, 20)))
        val values = faces ?: List(3) { draw(20) }
        require(values.size == 3 && values.all { it in 1..20 }) { "Enter three d20 faces." }
        val kept = values.indexOf(values.maxOrNull()!!)
        return DiceResult(formula.formula, formula.mode, values.mapIndexed { i, v -> DiceFace(20, v, i == kept) },
            formula.formula.flat + values[kept], faces != null)
    }
    return if (faces == null) SecureDice.roll(formula.formula, formula.mode, draw) else SecureDice.manual(formula.formula, faces, formula.mode)
}

@Composable internal fun AscensionRollManager(character: ReforgeCharacter, projection: CharacterProjection,
    repository: ReforgeRepository, onDismiss: () -> Unit, onAdvancedRoll: () -> Unit) {
    val options = remember(character, projection) { buildList {
        SkillRules.entries(character, projection).forEach { add(ManagerOption(QueuedCheck(RollKind.SKILL, it.id, it.name), it.calculation.value)) }
        Stat.entries.forEach { stat ->
            add(ManagerOption(QueuedCheck(RollKind.SAVE, stat.name, "${stat.fullLabel} saving throw"), projection.saves[stat]?.value))
            add(ManagerOption(QueuedCheck(RollKind.ABILITY_CHECK, stat.name, "${stat.fullLabel} check"), projection.abilityChecks[stat]?.value))
        }
        add(ManagerOption(QueuedCheck(RollKind.INITIATIVE, "initiative", "Initiative"), projection.initiative.value))
    } }
    val history by remember(repository, character.id) { repository.rollHistory(character.id) }.collectAsState(initial = emptyList())
    val recent = remember(history) { history.take(12).flatMap { roll -> roll.components.map { it.copyResult(if(roll.components.size == 1) roll.label else "${roll.label} · ${it.label}") } } }
    RollManagerPanel(character.id, character.revision.toString(), options, character.rollPresets, recent, editable = !character.archived,
        workshopEligible = "pocket-workshop" in character.activeStances, onDismiss = onDismiss, advanced = onAdvancedRoll,
        resolve = { entry, workshop ->
            val context = CalculationContext(tags = if (workshop) setOf("pocket-workshop") else emptySet())
            if (entry.kind == RollKind.CUSTOM) ManagerFormula(SecureDice.parse(requireNotNull(entry.customFormula)), entry.mode)
            else {
                ConditionRules.rollRestriction(character, entry.kind, entry.key, context)?.let { error(it) }
                val p = if (workshop) CharacterCalculator.calculate(character, context) else projection
                val bonus = when (entry.kind) {
                    RollKind.ABILITY_CHECK -> p.abilityChecks[Stat.valueOf(entry.key)]?.value
                    RollKind.SAVE -> p.saves[Stat.valueOf(entry.key)]?.value
                    RollKind.INITIATIVE -> p.initiative.value
                    RollKind.SKILL -> (p.skills[entry.key] ?: p.customSkills[entry.key])?.value
                    else -> null
                }
                requireNotNull(bonus) { "${entry.label} has an unknown modifier." }
                val mode = CharacterCalculator.rollMode(character, entry.kind, entry.key, context,
                    advantage = entry.mode == DiceMode.ADVANTAGE, disadvantage = entry.mode == DiceMode.DISADVANTAGE)
                ManagerFormula(DiceFormula(listOf(DiceTerm(1, 20)), bonus), mode, if (mode == DiceMode.NORMAL) 1 else 2)
            }
        }, commit = { batch ->
            val revision = batch.revision.toLong()
            val form = CharacterCalculator.activeForm(character)?.id
            val records = batch.rolls.mapIndexed { index, r ->
                val result = r.result
                val component = ReforgeRollComponent(r.check.label, result.faces.map { ReforgeRollDie(it.sides, it.face, it.kept, it.sign) },
                    result.formula.flat, mode = result.mode, batchId = batch.id, subjectId = r.check.key, checkKind = r.check.kind.name, formId = form,
                    batchMemberIds = if (index == 0) batch.rolls.mapIndexed { i, member -> if (i == 0) batch.id else member.id } else emptyList())
                ReforgeRollRecord(id = if (index == 0) batch.id else r.id, profileId = character.id, characterRevision = revision, label = r.check.label,
                    formula = SecureDice.format(result.formula), components = listOf(component), manual = result.manual)
            }
            val saved = repository.appendRollBatch(batch.id, records)
            SavedRollBatch(saved.map { it.components.single().copyResult(it.label) }, "Recorded ${saved.size} rolls")
        }, savePreset = { preset ->
            val current = character.rollPresets
            val next = if (current.any { it.id == preset.id }) current.map { if (it.id == preset.id) preset else it } else current + preset
            repository.save(character.id, character.revision, character.copy(rollPresets = next), "Save roll preset")
            "Preset saved"
        }, deletePreset = { id ->
            repository.save(character.id, character.revision, character.copy(rollPresets = character.rollPresets.filterNot { it.id == id }), "Delete roll preset")
            "Preset deleted"
        })
}

@Composable internal fun RollManagerPanel(actorId: String, revision: String, options: List<ManagerOption>, presets: List<RollPreset>,
    history: List<String>, editable: Boolean, workshopEligible: Boolean, onDismiss: () -> Unit, advanced: (() -> Unit)? = null, information: String = "",
    resolve: (QueuedCheck, Boolean) -> ManagerFormula, commit: suspend (PreparedRollBatch) -> SavedRollBatch,
    savePreset: suspend (RollPreset) -> String, deletePreset: suspend (String) -> String) {
    var tab by rememberSaveable(actorId) { mutableStateOf("Dice") }
    var queueJson by rememberSaveable(actorId) { mutableStateOf("[]") }
    val queue = remember(queueJson) { Json.decodeFromString<List<QueuedCheck>>(queueJson) }
    var preparedJson by rememberSaveable(actorId) { mutableStateOf<String?>(null) }
    val prepared = remember(preparedJson) { preparedJson?.let { Json.decodeFromString<PreparedRollBatch>(it) } }
    var manual by rememberSaveable(actorId) { mutableStateOf(true) }
    var faceText by rememberSaveable(actorId) { mutableStateOf("") }
    var custom by rememberSaveable(actorId) { mutableStateOf("1d20") }
    var customName by rememberSaveable(actorId) { mutableStateOf("") }
    var name by rememberSaveable(actorId) { mutableStateOf("") }
    var selected by rememberSaveable(actorId) { mutableStateOf<String?>(null) }
    var workshop by rememberSaveable(actorId) { mutableStateOf(false) }
    var deleteReview by rememberSaveable(actorId) { mutableStateOf<String?>(null) }
    var lastResults by rememberSaveable(actorId) { mutableStateOf(arrayListOf<String>()) }
    var status by rememberSaveable(actorId) { mutableStateOf("") }
    var busy by remember(actorId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val mutable = !busy && prepared == null
    fun update(entries: List<QueuedCheck>) {
        if (!mutable) return
        if (entries.size > 24) { status = "A batch supports up to 24 rolls."; return }
        queueJson = Json.encodeToString(entries); faceText = ""; status = ""
    }
    fun launchAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "Unable to finish this operation." }
            finally { busy = false }
        }
    }
    fun load(p: RollPreset, template: Boolean) {
        if (!mutable) return
        val entries = if (template) p.entries.map { raw ->
            options.firstOrNull { it.check.kind == raw.kind && it.check.key.equals(raw.key, true) }?.check?.copy(mode = raw.mode) ?: raw
        } else p.entries
        update(entries); workshop = p.workshop; selected = if (template) null else p.id; name = p.name; tab = "Queue"
    }
    fun formula(check: QueuedCheck) = runCatching { resolve(check, workshop) }.getOrNull()?.let {
        val expression = SecureDice.format(it.formula)
        if (it.mode == DiceMode.NORMAL) expression else "$expression · ${it.mode.name.lowercase()} (${it.d20Count}d20)"
    } ?: "Unavailable"
    fun perform() = launchAction {
        val batch = prepared ?: run {
            require(!workshop || workshopEligible) { "The current character has no verified Pocket Workshop context." }
            val entered = if (manual) faceText.lines() else emptyList()
            require(!manual || entered.size == queue.size) { "Enter one line of actual die faces for each queued roll." }
            val formulas = queue.map { resolve(it, workshop) }
            val rows = queue.mapIndexed { index, check ->
                PreparedCheck(UUID.randomUUID().toString(), check, composeRollEvidence(formulas[index], if (manual) entered[index] else null))
            }
            val presetId = presets.firstOrNull { it.id == selected && it.entries == queue && it.workshop == workshop }?.id
            PreparedRollBatch(UUID.randomUUID().toString(), revision, rows, workshop, presetId).also { preparedJson = Json.encodeToString(it) }
        }
        val outcome = commit(batch)
        if (outcome.lines.isNotEmpty()) {
            lastResults = ArrayList(outcome.lines); preparedJson = null; faceText = ""; tab = "Results"
        }
        status = outcome.status
    }
    val tabs = listOf("Dice", "Skills", "Saves", "Abilities", "Queue", "Presets", "Results")
    PlayerSheet("Roll Manager", onDismiss, dismissEnabled = !busy) {
        if (information.isNotEmpty()) Text(information, color = RebuildPalette.Muted)
        PlayerTabs(tabs, tab, { tab = it })
        when (tab) {
            "Dice" -> {
                Field("Dice formula", custom, { custom = it }, enabled = mutable)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(4,6,8,10,12,20,100).forEach { sides ->
                        TextButton({ custom = "1d$sides" }, modifier = Modifier.weight(1f), enabled = mutable,
                            contentPadding = PaddingValues(0.dp)) { Text("d$sides") }
                    }
                }
                Field("Roll name", customName, { customName = it }, enabled = mutable)
                val parsed = remember(custom) { runCatching { SecureDice.parse(custom) }.getOrNull() }
                PlayerOutlinedButton({ update(queue + QueuedCheck(RollKind.CUSTOM, UUID.randomUUID().toString(),
                    customName.trim().ifEmpty { "Dice ${SecureDice.format(requireNotNull(parsed))}" }, customFormula = custom, primary = false)) },
                    enabled = mutable && parsed != null && customName.length <= 120) { Text("Add to queue") }
                advanced?.let { PlayerTextButton(onClick = it, enabled = mutable) { Text("Advanced roll") } }
            }
            "Skills", "Saves", "Abilities" -> options.filter { option -> when (tab) {
                "Skills" -> option.check.kind == RollKind.SKILL
                "Saves" -> option.check.kind == RollKind.SAVE
                else -> option.check.kind in setOf(RollKind.ABILITY_CHECK, RollKind.INITIATIVE)
            } }.forEach { o ->
                val checked = queue.firstOrNull { it.primary && it.kind == o.check.kind && it.key == o.check.key }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.check.label, Modifier.weight(1f))
                    Text(o.bonus?.let { if (it < 0) "$it" else "+$it" } ?: "?", color = RebuildPalette.GoldBright)
                    listOf(null, DiceMode.ADVANTAGE, DiceMode.DISADVANTAGE).forEach { mode ->
                        val marked = if (mode == null) checked != null else checked?.mode == mode
                        val label = if (mode == null) "Selection" else mode.name.lowercase()
                        IconButton(onClick = { if (checked != null || queue.size < 24) update(RollManagerRules.select(queue, o.check, mode)) },
                            modifier = Modifier.semantics { contentDescription = "${o.check.label} $label, ${if (marked) "selected" else "unselected"}" },
                            enabled = mutable && o.bonus != null) {
                            Text(when (mode) { null -> if (marked) "●" else "○"; DiceMode.ADVANTAGE -> "↑"; else -> "↓" },
                                color = if (marked) RebuildPalette.GoldBright else RebuildPalette.Muted)
                        }
                    }
                }
            }
            "Queue" -> {
                if (queue.isEmpty()) Text("Your roll queue is empty.")
                queue.forEachIndexed { i, entry ->
                    PlayerCard {
                        Text("${i + 1}. ${entry.label}", style = MaterialTheme.typography.titleMedium)
                        Text(formula(entry))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            PlayerTextButton({ update(RollManagerRules.move(queue, i, i - 1)) }, enabled = mutable && i > 0) { Text("Up") }
                            PlayerTextButton({ update(RollManagerRules.move(queue, i, i + 1)) }, enabled = mutable && i < queue.lastIndex) { Text("Down") }
                            PlayerTextButton({ update(queue + entry.copy(primary = false)) }, enabled = mutable && queue.size < 24) { Text("Repeat") }
                            PlayerTextButton({ update(queue.filterIndexed { j, _ -> j != i }) }, enabled = mutable) { Text("Remove") }
                        }
                        if (entry.kind != RollKind.CUSTOM) PlayerTabs(modes.map { it.name.lowercase() }, entry.mode.name.lowercase(), { value ->
                            update(queue.mapIndexed { j, item -> if (j == i) item.copy(mode = DiceMode.valueOf(value.uppercase())) else item })
                        })
                    }
                }
                if (queue.isNotEmpty()) {
                    PlayerTabs(listOf("Physical dice", "Roll in app"), if (manual) "Physical dice" else "Roll in app", { if (mutable) manual = it == "Physical dice" })
                    if (manual) Field("Die faces in queue order, one roll per line", faceText, { faceText = it }, single = false, enabled = mutable)
                    if (workshop && !workshopEligible) Text("Pocket Workshop eligibility needs a confirmed source for this actor.")
                    PlayerButton({ perform() }, enabled = editable && !busy && (!workshop || workshopEligible)) {
                        Text(if (prepared == null) "Roll All" else "Verify recorded batch") }
                    if (prepared != null) PlayerTextButton({ preparedJson = null; status = "Batch editor released. Check History or pending commands for its authoritative status." }, enabled = !busy) { Text("Return to editing") }
                }
            }
            "Presets" -> {
                Text("Research templates", style = MaterialTheme.typography.titleMedium)
                RollManagerRules.templates().forEach { p -> PlayerTextButton({ load(p, true) }, enabled = mutable) { Text(p.name) } }
                if (presets.isNotEmpty()) Text("Saved presets", style = MaterialTheme.typography.titleMedium)
                presets.forEach { p -> PlayerOutlinedButton({ load(p, false) }, enabled = mutable) { Text("${p.name} · ${p.entries.size}") } }
                Field("Preset name", name, { name = it }, enabled = mutable)
                val canSave = editable && mutable && queue.isNotEmpty() && name.isNotBlank() && name.trim().length <= 120
                PlayerButton({ val preset = RollPreset(selected ?: UUID.randomUUID().toString(), name.trim(), queue, workshop)
                    launchAction { status = savePreset(preset); selected = preset.id } }, enabled = canSave) { Text(if (selected == null) "Save queue as preset" else "Update preset") }
                selected?.let { id ->
                    PlayerOutlinedButton({ val p = RollPreset(UUID.randomUUID().toString(), (name.trim().take(113) + " (copy)"), queue, workshop)
                        launchAction { status = savePreset(p); selected = p.id; name = p.name } }, enabled = canSave) { Text("Duplicate preset") }
                    if (deleteReview == id) {
                        PlayerOutlinedButton({ launchAction { status = deletePreset(id); deleteReview = null; selected = null } }, enabled = editable && mutable) { Text("Confirm removal of $name") }
                        PlayerTextButton({ deleteReview = null }) { Text("Cancel removal") }
                    } else PlayerTextButton({ deleteReview = id }, enabled = editable && mutable) { Text("Remove saved preset") }
                }
            }
            "Results" -> {
                if (lastResults.isNotEmpty()) {
                    Text("Last batch", style = MaterialTheme.typography.titleMedium)
                    lastResults.forEach { line -> Text(line, Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Copy result") { clipboard.setText(AnnotatedString(line)) }.padding(vertical = 8.dp)) }
                    PlayerOutlinedButton({ clipboard.setText(AnnotatedString(lastResults.joinToString("\n"))) }) { Text("Copy All") }
                }
                Text("Recorded rolls", style = MaterialTheme.typography.titleMedium)
                if (history.isEmpty()) Text("No recorded rolls.")
                history.forEach { line -> Text(line, Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Copy result") { clipboard.setText(AnnotatedString(line)) }.padding(vertical = 8.dp)) }
            }
        }
        if (tab !in setOf("Queue", "Results")) PlayerTextButton({ tab = "Queue" }) { Text("Roll Queue (${queue.size})") }
        if (status.isNotEmpty()) Text(status, color = RebuildPalette.GoldBright)
    }
}
