package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.GenesisArmorRules
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Inline spellbook content. Every operation is one revision-checked character transaction. */
@Composable internal fun GenesisSpellReservoir(character: ReforgeCharacter,
    onSave: (ReforgeCharacter, String) -> Unit) {
    if (character.armor == null) return
    val available = runCatching { GenesisArmorRules.runtime(character) }
    val campaign = available.getOrNull()
    if (campaign == null) {
        PlayerEmpty("Reservoir configuration", (available.exceptionOrNull()?.message ?: "Review your character setup before using the reservoir.") + " Open Sheet → Character setup to review it.")
        character.armor?.campaign?.installed?.payloads.orEmpty().forEach { payload ->
            PlayerDisclosure(payload.template.name) { GenesisPayloadFormula(payload.template) }
        }
        return
    }
    val feedback = LocalGenesisEditorFeedback.current
    var dialog by rememberSaveable(character.id) { mutableStateOf<String?>(null) }
    var templateId by rememberSaveable(character.id) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(character.id) { mutableStateOf("") }
    var submittedAt by remember { mutableStateOf<Long?>(null) }
    var actionError by remember { mutableStateOf("") }
    LaunchedEffect(character.revision) {
        if (submittedAt != null && character.revision > submittedAt!!) { dialog = null; submittedAt = null }
    }
    fun save(next: ReforgeCharacter, label: String) {
        submittedAt = character.revision
        onSave(next, label)
    }
    RebirthSectionLabel("Spell reservoir")
    PlayerCard {
        Text(campaign.installed?.name ?: "Internal nexus unavailable", style = MaterialTheme.typography.titleMedium)
        Text(campaign.installed?.readiness(campaign.gameSeconds) ?: "Review the retained nexus record in Resources.", color = RebuildPalette.Muted)
        Text("${campaign.installed?.payloads?.size ?: 0} / ${campaign.profile.capacity} stored spells", color = RebuildPalette.Purple)
        campaign.installed?.payloads.orEmpty().forEach { payload ->
            PlayerDisclosure(payload.template.name) {
                GenesisPayloadFormula(payload.template)
                Text("Loaded from ${payload.sourceReference.ifBlank { payload.source.name.lowercase() }}", color = RebuildPalette.Muted)
                Text("Loaded at game second ${payload.loadedAt}. This stored snapshot retains its original damage.",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                PlayerTextButton(enabled = !feedback.busy, onClick = { dialog = "purge:${payload.id}" }) { Text("Purge stored spell") }
            }
        }
        if (campaign.installed?.payloads.isNullOrEmpty()) Text("No spell loaded", color = RebuildPalette.Muted)
        PlayerOutlinedButton(enabled = !feedback.busy && campaign.installed != null,
            onClick = { dialog = "charge" }, modifier = Modifier.fillMaxWidth().testTag("genesis-load-reservoir")) { Text("Load spell into reservoir") }
    }
    PlayerDisclosure("Payload library · ${campaign.templates.size} templates") {
        Text("Damage templates describe approved stored magic. Linking a template to a spell keeps its spellbook identity; loaded spells keep their own snapshots.", color = RebuildPalette.Muted)
        PlayerSearch(query, { query = it }, "Find a payload template")
        val visible = campaign.templates.filter { it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: Template -> it.name }.thenBy { it.id })
        visible.forEach { template ->
            val linked = character.armor?.templateSpellIds?.get(template.id)?.let { id -> character.spells.firstOrNull { it.id == id } }
            PlayerEntry(template.name, "Level ${template.castingLevel} · ${if (template.approved) "Approved" else "Needs ruling"}" +
                (linked?.let { " · Linked to ${it.name}" } ?: ""), "Payload", {
                templateId = template.id; dialog = "template"
            })
        }
        if (visible.isEmpty()) Text(if (campaign.templates.isEmpty()) "No payload templates recorded" else "No matching templates", color = RebuildPalette.Muted)
        PlayerOutlinedButton(enabled = !feedback.busy, onClick = { templateId = null; dialog = "template" }) { Text("Create payload template") }
        PlayerTextButton(enabled = !feedback.busy, onClick = { dialog = "scrolls" }) { Text("Scroll sources · ${campaign.scrolls.sumOf { it.quantity }}") }
    }
    when {
        dialog == "charge" -> GenesisReservoirLoad(character, { dialog = null }, ::save)
        dialog == "template" -> GenesisPayloadEditor(character, templateId, { dialog = null }, ::save)
        dialog == "scrolls" -> GenesisScrollSources(character, { dialog = null }, ::save)
        dialog?.startsWith("purge:") == true -> {
            val payload = campaign.installed?.payloads?.firstOrNull { it.id == dialog?.substringAfter(':') }
            if (payload != null) PlayerSheet("Purge ${payload.template.name}?", { dialog = null }, actions = {
                PlayerButton(enabled = !feedback.busy, onClick = {
                    try {
                        save(GenesisArmorRules.apply(character, Engine.purge(campaign, payload.coreId, payload.id)), "Purged stored spell · Bonus Action")
                    } catch (failure: Exception) { actionError = failure.message ?: "Unable to purge this spell." }
                }) { Text("Purge · no refund") }
            }) {
                Text("Remove only this stored spell and spend a Bonus Action. No slot or scroll is refunded.")
                GenesisPayloadFormula(payload.template)
                if (actionError.isNotBlank()) Text(actionError, color = MaterialTheme.colorScheme.error)
                if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable private fun GenesisReservoirLoad(character: ReforgeCharacter, close: () -> Unit,
    onSave: (ReforgeCharacter, String) -> Unit) {
    val campaign = GenesisArmorRules.runtime(character)
    val feedback = LocalGenesisEditorFeedback.current
    val baseline = rememberSaveable { character.revision }
    val payloadId = rememberSaveable { UUID.randomUUID().toString() }
    var templateId by rememberSaveable { mutableStateOf(campaign.templates.firstOrNull { it.approved }?.id) }
    var source by rememberSaveable { mutableStateOf(SourceKind.SLOT) }
    var scrollId by rememberSaveable { mutableStateOf<String?>(null) }
    var overwrite by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    val template = campaign.templates.firstOrNull { it.id == templateId }
    PlayerSheet("Load spell into reservoir", close, dirty = true, showCancel = true, actions = {
        PlayerButton(enabled = !feedback.busy && character.revision == baseline && template?.approved == true,
            modifier = Modifier.testTag("genesis-confirm-reservoir-load"), onClick = {
                try {
                    val selected = template ?: error("Choose an approved template.")
                    val after = Engine.charge(campaign, campaign.installedId ?: error("The internal nexus is unavailable."),
                        selected, payloadId, source, true, false, scrollId.takeIf { source == SourceKind.SCROLL }, overwrite)
                    onSave(GenesisArmorRules.apply(character, after), "Loaded ${selected.name.take(90)} · Bonus Action")
                } catch (failure: Exception) { error = failure.message ?: "Unable to load this spell." }
            }) { Text(if (overwrite == null) "Load · spend source" else "Overwrite · spend source") }
    }) {
        Text("Loading spends a Bonus Action and the selected source once. Casting from your spellbook and loading the reservoir use the same spell-slot balance.", color = RebuildPalette.Muted)
        Choices("Approved payload template", campaign.templates.filter { it.approved }.map { it.id }, templateId,
            display = { id -> campaign.templates.first { it.id == id }.name }, enabled = !feedback.busy) { templateId = it; scrollId = null }
        template?.let { GenesisPayloadFormula(it) }
        Choices("Source", SourceKind.entries.toList(), source, enabled = !feedback.busy) { source = it }
        if (source == SourceKind.SLOT) {
            Text(template?.let { "Level ${it.castingLevel} slots: ${character.spellSlots[it.castingLevel]?.toString() ?: "Not set"}" } ?: "Select a template", color = RebuildPalette.Purple)
            Text("If the count is unknown, close this panel and set current slots in the spellbook before loading.", color = RebuildPalette.Muted)
        } else {
            val scrolls = campaign.scrolls.filter { it.quantity > 0 && it.templateId == templateId }
            Choices("Confirmed scroll", scrolls.map { it.id }, scrollId,
                display = { id -> scrolls.first { it.id == id }.let { "${it.documentedTemplate?.name ?: template?.name} · ${it.quantity} remaining" } },
                enabled = !feedback.busy) { scrollId = it }
            if (scrolls.isEmpty()) Text("No matching scrolls recorded. Add a confirmed source in Payload library.", color = RebuildPalette.Muted)
        }
        Choices("Replace a stored spell", listOf<String?>(null) + campaign.installed?.payloads.orEmpty().map { it.id }, overwrite,
            display = { id -> campaign.installed?.payloads?.firstOrNull { it.id == id }?.template?.name ?: "Keep existing spells" }, enabled = !feedback.busy) { overwrite = it }
        if (overwrite != null) Text("The selected stored spell is removed without a refund.", color = MaterialTheme.colorScheme.error)
        GenesisSpellDraftStatus(character.revision, baseline, error)
    }
}

@Composable private fun GenesisPayloadEditor(character: ReforgeCharacter, existingId: String?, close: () -> Unit,
    onSave: (ReforgeCharacter, String) -> Unit) {
    val campaign = GenesisArmorRules.runtime(character)
    val snapshot = remember { campaign.templates.firstOrNull { it.id == existingId } }
    val baseline = rememberSaveable { character.revision }
    val id = rememberSaveable { snapshot?.id ?: UUID.randomUUID().toString() }
    val feedback = LocalGenesisEditorFeedback.current
    var name by rememberSaveable { mutableStateOf(snapshot?.name.orEmpty()) }
    var base by rememberSaveable { mutableStateOf(snapshot?.baseLevel?.toString().orEmpty()) }
    var level by rememberSaveable { mutableStateOf(snapshot?.castingLevel?.toString().orEmpty()) }
    var linkedSpellId by rememberSaveable { mutableStateOf(character.armor?.templateSpellIds?.get(id)) }
    var componentsJson by rememberSaveable { mutableStateOf(Json.encodeToString(snapshot?.damage ?: emptyList<TypedDice>())) }
    val components = remember(componentsJson) { Json.decodeFromString<List<TypedDice>>(componentsJson) }
    var count by rememberSaveable { mutableStateOf("") }
    var sides by rememberSaveable { mutableStateOf("") }
    var flat by rememberSaveable { mutableStateOf("0") }
    var type by rememberSaveable { mutableStateOf(DamageType.FORCE) }
    var overcharge by rememberSaveable { mutableStateOf(snapshot?.overchargeType ?: DamageType.FORCE) }
    var approved by rememberSaveable { mutableStateOf(snapshot?.approved ?: false) }
    var notes by rememberSaveable { mutableStateOf(snapshot?.approvalNotes.orEmpty()) }
    var error by remember { mutableStateOf("") }
    PlayerSheet(if (snapshot == null) "Create payload template" else "Edit payload template", close, dirty = true, showCancel = true, actions = {
        PlayerButton(enabled = !feedback.busy && character.revision == baseline, onClick = {
            try {
                val template = Template(id, name.trim(), base.intValue("Base level", 1, 9), level.intValue("Casting level", 1, 9), components,
                    overcharge, approved, notes.trim()).also { it.validate() }
                require(!approved || notes.isNotBlank()) { "Add the accepted source and eligibility notes before approving this template." }
                val next = GenesisArmorRules.apply(character, campaign.copy(templates = campaign.templates.filterNot { it.id == id } + template))
                val armor = requireNotNull(next.armor)
                val links = armor.templateSpellIds - id
                onSave(next.copy(armor = armor.copy(templateSpellIds = linkedSpellId?.let { links + (id to it) } ?: links)), "Saved payload template; loaded snapshots retained")
            } catch (failure: Exception) { error = failure.message ?: "Check this template." }
        }) { Text("Save template") }
    }) {
        Field("Payload name", name, { name = it }, enabled = !feedback.busy)
        Choices("Linked spellbook record", listOf<String?>(null) + character.spells.map { it.id }, linkedSpellId,
            display = { id -> character.spells.firstOrNull { it.id == id }?.name ?: "No linked spell" }, enabled = !feedback.busy) { linkedSpellId = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Base spell level", base, { base = it }, Modifier.weight(1f), enabled = !feedback.busy)
            Field("Casting level", level, { level = it }, Modifier.weight(1f), enabled = !feedback.busy)
        }
        Text("Approved instantaneous damage", style = MaterialTheme.typography.titleMedium)
        components.forEachIndexed { index, component ->
            PlayerTextButton(enabled = !feedback.busy, onClick = { componentsJson = Json.encodeToString(components.filterIndexed { i, _ -> i != index }) }) {
                Text("${component.dice} ${component.type.name.lowercase()} · remove")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Dice count", count, { count = it }, Modifier.weight(1f), enabled = !feedback.busy)
            Field("Die sides", sides, { sides = it }, Modifier.weight(1f), enabled = !feedback.busy)
        }
        Field("Flat damage", flat, { flat = it }, enabled = !feedback.busy)
        Choices("Damage type", DamageType.entries.toList(), type, enabled = !feedback.busy) { type = it }
        PlayerOutlinedButton(enabled = !feedback.busy, onClick = {
            try {
                val dice = Dice(count.intValue("Dice count", 0, 200), sides.intValue("Die sides", 4, 100), flat.intValue("Flat damage", -100, 100)).also { it.validate() }
                componentsJson = Json.encodeToString(components + TypedDice(dice, type)); error = ""
            } catch (failure: Exception) { error = failure.message ?: "Check these dice." }
        }) { Text("Add damage component") }
        Choices("Overcharge type · 1d8 per actual level", DamageType.entries.toList(), overcharge, enabled = !feedback.busy) { overcharge = it }
        Field("Accepted source and eligibility notes", notes, { notes = it }, single = false, enabled = !feedback.busy)
        Toggle("Instantaneous damage and source eligibility approved", approved, !feedback.busy) { approved = it }
        Text("Conditions, concentration, ongoing damage, forced movement and summons do not transfer. Editing affects future loads; existing stored spells retain their snapshots.", color = RebuildPalette.Muted)
        GenesisSpellDraftStatus(character.revision, baseline, error)
    }
}

@Composable private fun GenesisScrollSources(character: ReforgeCharacter, close: () -> Unit,
    onSave: (ReforgeCharacter, String) -> Unit) {
    val campaign = GenesisArmorRules.runtime(character)
    val feedback = LocalGenesisEditorFeedback.current
    val baseline = rememberSaveable { character.revision }
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var templateId by rememberSaveable { mutableStateOf<String?>(null) }
    var count by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    PlayerSheet("Scroll sources", close, dirty = count.isNotBlank() || templateId != null, showCancel = true, actions = {
        PlayerButton(enabled = !feedback.busy && character.revision == baseline && templateId != null && count.isNotBlank(), onClick = {
            try {
                val template = campaign.templates.firstOrNull { it.id == templateId } ?: error("Choose a documented payload.")
                val scroll = Scroll(id, template.id, count.intValue("Confirmed quantity", 1, 100000), template)
                onSave(GenesisArmorRules.apply(character, campaign.copy(scrolls = campaign.scrolls + scroll)), "Recorded confirmed reservoir scroll source")
            } catch (failure: Exception) { error = failure.message ?: "Check the scroll source." }
        }) { Text("Record confirmed scrolls") }
    }) {
        campaign.scrolls.forEach { scroll -> Text("${scroll.documentedTemplate?.name ?: campaign.templates.firstOrNull { it.id == scroll.templateId }?.name ?: "Preserved source"} · ${scroll.quantity}") }
        if (campaign.scrolls.isEmpty()) Text("No confirmed scrolls", color = RebuildPalette.Muted)
        Text("Record scrolls you actually own. The recorded payload stays fixed if its template is edited later.", color = RebuildPalette.Muted)
        Choices("Documented payload", campaign.templates.map { it.id }, templateId,
            display = { value -> campaign.templates.first { it.id == value }.name }, enabled = !feedback.busy) { templateId = it }
        Field("Additional confirmed scrolls", count, { count = it }, enabled = !feedback.busy)
        GenesisSpellDraftStatus(character.revision, baseline, error)
    }
}

@Composable internal fun GenesisPayloadFormula(template: Template) {
    Text(template.damage.joinToString(" + ") { "${it.dice} ${it.type.name.lowercase()}" }, color = RebuildPalette.Silver)
    Text("+ ${template.castingLevel}d8 ${template.overchargeType.name.lowercase()} overcharge", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
}

@Composable private fun GenesisSpellDraftStatus(revision: Long, baseline: Long, error: String) {
    val feedback = LocalGenesisEditorFeedback.current
    if (revision != baseline) Text("Your character changed. Close and reopen this panel before saving.", color = MaterialTheme.colorScheme.error)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
}
