package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Presentation state only; repositories own revision checks and transactional persistence. */
internal class LocalRecordActions(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    fun run(block: suspend () -> String) {
        if (busy) return
        busy = true
        message = "Saving locally…"
        scope.launch {
            try { message = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "Not saved: ${e.message ?: "Please review this entry and try again."}" }
            finally { busy = false }
        }
    }
}

@Composable internal fun rememberLocalRecordActions(): LocalRecordActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { LocalRecordActions(scope) }
}

@Composable internal fun LocalRecordStatus(message: String, busy: Boolean) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotBlank()) Text(message, color = Brass, style = MaterialTheme.typography.bodyMedium)
}

@Composable internal fun ExpandableRecordSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    PlayerCard {
        PlayerTextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Brass, style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "−" else "+", color = Frost)
            }
        }
        if (expanded) content()
    }
}

@Composable internal fun SourceField(value: String, change: (String) -> Unit, enabled: Boolean = true) {
    Field("Source / resolved scene / checkpoint", value, change, single = false, enabled = enabled)
}

@Composable fun InventionsPageV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val projects by records.projects.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    val actions = rememberLocalRecordActions()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("") }
    val projectDraft = rememberUiDraftText()
    var projectSnapshot by projectDraft
    val stageDraft = rememberUiDraftText()
    var stageSnapshot by stageDraft
    val requirementDraft = rememberUiDraftText()
    var requirementSnapshot by requirementDraft
    val editorProject = remember(projectSnapshot) { projectSnapshot?.let { runCatching { Json.decodeFromString<InventionProject>(it) }.getOrNull() } }
    val selectedStage = remember(stageSnapshot) { stageSnapshot?.let { runCatching { Json.decodeFromString<ProjectStage>(it) }.getOrNull() } }
    val selectedRequirement = remember(requirementSnapshot) { requirementSnapshot?.let { runCatching { Json.decodeFromString<MaterialRequirement>(it) }.getOrNull() } }
    val selected = projects.firstOrNull { it.id == selectedId } ?: editorProject
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("Proposed") }
    var source by rememberSaveable { mutableStateOf("") }
    var baselineSource by rememberSaveable { mutableStateOf("") }
    var stageTitle by rememberSaveable { mutableStateOf("") }
    var stageNotes by rememberSaveable { mutableStateOf("") }
    var stageStatus by rememberSaveable { mutableStateOf("Planned") }
    var materialId by rememberSaveable { mutableStateOf<String?>(null) }
    var materialLabel by rememberSaveable { mutableStateOf("") }
    var materialQuantity by rememberSaveable { mutableStateOf("") }
    var materialNotes by rememberSaveable { mutableStateOf("") }
    var outputTitle by rememberSaveable { mutableStateOf("") }
    var outputQuantity by rememberSaveable { mutableStateOf("1") }
    var outputDescription by rememberSaveable { mutableStateOf("") }
    val craftDraft = rememberUiDraftText()
    var craftReviewJson by craftDraft
    val craftLabelsDraft = rememberUiDraftText("[]")
    var craftLabelsJson by craftLabelsDraft
    val parsedLabels = remember(craftLabelsJson) { craftLabelsJson?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } }
    val craftReviewLabels = parsedLabels.orEmpty()
    // Large command/selection snapshots use compact file references in the Activity Bundle.
    val reviewedCraft = remember(craftReviewJson) { craftReviewJson?.let { runCatching { Json.decodeFromString<CraftCommand>(it) }.getOrNull() } }
    val consumptionDraft = rememberUiDraftText("{}")
    var consumedJson by consumptionDraft
    val parsedSelections = remember(consumedJson) { consumedJson?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() } }
    val consumedSelections = parsedSelections.orEmpty()
    var historyLimit by rememberSaveable(selectedId) { mutableIntStateOf(40) }
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    val stagesFlow = remember(selectedId) { selectedId?.let(records::stagesFor) }
    val requirementsFlow = remember(selectedId) { selectedId?.let(records::requirementsFor) }
    val stages = stagesFlow?.collectAsState(initial = emptyList())?.value.orEmpty()
    val requirements = requirementsFlow?.collectAsState(initial = emptyList())?.value.orEmpty()
    val stale = selected != null && editorProject != null && selected.revision != editorProject.revision
    val projectDraftValid = projectDraft.canSave && (projectSnapshot == null || editorProject != null) &&
        (selectedId == null || editorProject?.id == selectedId)
    val stageDraftValid = stageDraft.canSave && (stageSnapshot == null || selectedStage != null)
    val requirementDraftValid = requirementDraft.canSave && (requirementSnapshot == null || selectedRequirement != null)
    val consumptionDraftValid = consumptionDraft.canSave && parsedSelections != null
    val reviewDraftValid = projectDraftValid && consumptionDraftValid && craftDraft.canSave &&
        craftReviewJson != null && reviewedCraft != null && craftLabelsDraft.canSave && parsedLabels != null
    val draftErrors = buildList {
        projectDraft.error?.let(::add)
        if (!projectDraftValid && projectDraft.error == null) add("The saved invention draft could not be read. Close and reopen the invention before saving.")
        if (mode == "stage") {
            stageDraft.error?.let(::add)
            if (!stageDraftValid && stageDraft.error == null) add("The saved stage draft could not be read. Reopen this stage before saving.")
        }
        if (mode == "material") {
            requirementDraft.error?.let(::add)
            if (!requirementDraftValid && requirementDraft.error == null) add("The saved material draft could not be read. Reopen this requirement before saving.")
        }
        if (mode in setOf("craft", "review")) {
            consumptionDraft.error?.let(::add)
            if (!consumptionDraftValid && consumptionDraft.error == null) add("The saved material selections could not be read. Reopen crafting and review the actual quantities.")
        }
        if (mode == "review") {
            craftDraft.error?.let(::add); craftLabelsDraft.error?.let(::add)
            if (!reviewDraftValid && projectDraftValid && consumptionDraftValid && craftDraft.error == null && craftLabelsDraft.error == null) add("The saved craft review could not be read. Cancel and review the craft again before confirming.")
        }
    }.distinct()
    val dirty = when(mode) {
        "create", "edit" -> title != editorProject?.title.orEmpty() || description != editorProject?.description.orEmpty() || status != (editorProject?.status ?: "Proposed") || source != editorProject?.source.orEmpty()
        "stage" -> stageTitle != selectedStage?.title.orEmpty() || stageNotes != selectedStage?.notes.orEmpty() || stageStatus != (selectedStage?.status ?: "Planned") || source != baselineSource
        "material" -> materialId != selectedRequirement?.itemId || materialLabel != selectedRequirement?.label.orEmpty() || materialQuantity != selectedRequirement?.quantity?.toString().orEmpty() || materialNotes != selectedRequirement?.notes.orEmpty() || source != baselineSource
        "craft", "review" -> outputTitle.isNotBlank() || outputQuantity != "1" || outputDescription.isNotBlank() || consumedSelections.isNotEmpty() || source != baselineSource
        else -> false
    }
    fun load(project: InventionProject?) {
        projectSnapshot = project?.let { Json.encodeToString(it) }; selectedId = project?.id
        stageSnapshot = null; requirementSnapshot = null
        craftReviewJson = null; craftLabelsJson = "[]"; consumedJson = "{}"
        title = project?.title.orEmpty(); description = project?.description.orEmpty(); status = project?.status ?: "Proposed"
        source = project?.source.orEmpty(); baselineSource = source; actions.message = ""
    }
    fun beginCraft() {
        outputTitle = ""; outputQuantity = "1"; outputDescription = ""; consumedJson = "{}"; craftReviewJson = null; craftLabelsJson = "[]"
        source = selected?.source.orEmpty(); baselineSource = source; mode = "craft"; actions.message = ""
    }
    fun prepareCraft() {
        if (!projectDraftValid || !consumptionDraftValid) { actions.message = draftErrors.firstOrNull() ?: "Reopen the invention and review material quantities."; return }
        try {
            val project = selected ?: error("Choose an invention first.")
            val count = outputQuantity.toIntOrNull() ?: error("Output quantity must be a whole number.")
            require(count > 0) { "Output quantity must be positive." }
            val chosenRequirements = requirements.filter { consumedSelections.containsKey(it.id) }
            require(chosenRequirements.isNotEmpty()) { "Select the actual materials consumed." }
            val consumed = chosenRequirements.map { requirement ->
                val item = items.firstOrNull { it.id == requirement.itemId } ?: error("Link every consumed requirement to its real inventory item first.")
                val amount = consumedSelections[requirement.id]?.toIntOrNull() ?: error("Confirm actual consumed quantities before crafting.")
                require(amount > 0) { "Consumed quantities must be positive." }
                MaterialConsumption(itemId = item.id, expectedRevision = item.revision, quantity = amount)
            }
            val combined = consumed.groupBy { it.itemId }.map { (_, uses) ->
                val total = uses.sumOf { it.quantity.toLong() }
                require(total <= Int.MAX_VALUE) { "Consumed quantity is too large." }
                uses.first().copy(quantity = total.toInt())
            }
            craftLabelsJson = Json.encodeToString(combined.map { use -> "${items.first { it.id == use.itemId }.title}: ${use.quantity}" })
            craftReviewJson = Json.encodeToString(CraftCommand(projectId = project.id, expectedProjectRevision = project.revision,
                materials = combined, output = LedgerDraft(title = outputTitle, category = "Other", owner = "Unknown", itemState = "Unknown", quantity = count, description = outputDescription, source = source), source = source))
            mode = "review"
        } catch (e: Exception) { actions.message = "Cannot review craft: ${e.message ?: "Check the quantities and linked materials."}" }
    }
    val visible = projects.filter { (!activeOnly || it.status !in setOf("Completed", "Paused")) &&
        (query.isBlank() || "${it.title} ${it.description} ${it.status}".contains(query, true)) }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Inventions", style = MaterialTheme.typography.headlineMedium); Text(if (projects.size == 1) "1 design" else "${projects.size} designs", color = Muted, style = MaterialTheme.typography.bodySmall) }
            PlayerButton(onClick = { load(null); mode = "create" }, enabled = !actions.busy) { Text("Add Invention") }
        }
        PlayerSearch(query, { query = it }, "Search inventions")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerFilterChip(selected = !activeOnly, onClick = { activeOnly = false }, label = { Text("All designs") })
            PlayerFilterChip(selected = activeOnly, onClick = { activeOnly = true }, label = { Text("Active") })
        }
        if (actions.message.isNotBlank() && mode.isBlank()) LocalRecordStatus(actions.message, actions.busy)
        if (visible.isEmpty()) PlayerCard {
            Text(if (projects.isEmpty()) "Start with an idea" else "No matching inventions", style = MaterialTheme.typography.titleMedium)
            Text(if (projects.isEmpty()) "Keep concepts, research and construction together." else "Try another name or filter.", color = Muted)
        }
        visible.forEach { project ->
            val projectRequirements by remember(project.id) { records.requirementsFor(project.id) }.collectAsState(initial = emptyList())
            val ready = projectRequirements.count { requirement ->
                val item = items.firstOrNull { it.id == requirement.itemId }
                val sameItem = projectRequirements.filter { it.itemId == requirement.itemId }
                item?.quantity != null && sameItem.all { it.quantity != null } &&
                    item.quantity.toLong() >= sameItem.sumOf { it.quantity!!.toLong() }
            }
            PlayerCard(onClick = { load(project); mode = "detail" }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    InstrumentMedallion("Core", Modifier.size(44.dp), Frost)
                    Column(Modifier.weight(1f)) {
                        Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(project.status, color = Frost, style = MaterialTheme.typography.labelLarge)
                        Text(if (projectRequirements.isEmpty()) "No materials listed" else "$ready / ${projectRequirements.size} requirements ready", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = Frost)
                }
            }
        }
    }
    if (mode.isNotBlank()) {
        val sheetTitle = when(mode) { "create" -> "Add Invention"; "edit" -> "Edit invention"; "stage" -> if (selectedStage == null) "Add stage" else "Edit stage"; "material" -> if (selectedRequirement == null) "Add material" else "Edit material"; "craft" -> "Confirm a resolved craft"; "review" -> "Commit confirmed craft?"; "event" -> "Invention history"; else -> selected?.title ?: "Invention" }
        PlayerSheet(sheetTitle, onDismiss = { if (!actions.busy) { if (mode == "review") { craftReviewJson = null; mode = "craft" } else mode = "" } }, dirty = dirty && mode != "review", actions = {
            when(mode) {
                "create", "edit" -> PlayerButton(onClick = {
                    val before = editorProject; val provenance = source
                    val model = before?.copy(title = title, description = description, status = status) ?: InventionProject(title = title, description = description, status = status)
                    actions.run { val receipt = records.saveProject(model, before?.revision, provenance); projectSnapshot = Json.encodeToString(model.copy(revision = receipt.resultingRevision, source = provenance, updatedAt = receipt.createdAt)); selectedId = model.id; mode = "detail"; "Invention saved · no resources consumed" }
                }, enabled = !actions.busy && projectDraftValid && !stale && title.isNotBlank() && source.isNotBlank()) { Text(if (mode == "create") "Save invention" else "Save invention changes") }
                "detail" -> PlayerButton(onClick = { selected?.let(::load); mode = "edit" }, enabled = !actions.busy) { Text("Edit invention") }
                "stage" -> PlayerButton(onClick = {
                    val project = selected ?: return@PlayerButton; val before = selectedStage; val provenance = source
                    val model = before?.copy(title = stageTitle, notes = stageNotes, status = stageStatus) ?: ProjectStage(projectId = project.id, title = stageTitle, notes = stageNotes, status = stageStatus, sortOrder = stages.size)
                    actions.run { records.saveStage(model, before?.revision, provenance); mode = "detail"; "Stage saved" }
                }, enabled = !actions.busy && projectDraftValid && stageDraftValid && stageTitle.isNotBlank() && source.isNotBlank()) { Text(if (selectedStage == null) "Add stage" else "Save stage") }
                "material" -> PlayerButton(onClick = {
                    val project = selected ?: return@PlayerButton; val before = selectedRequirement; val raw = materialQuantity.trim(); val provenance = source
                    val model = before?.copy(itemId = materialId, label = materialLabel, quantity = raw.toIntOrNull(), notes = materialNotes) ?: MaterialRequirement(projectId = project.id, itemId = materialId, label = materialLabel, quantity = raw.toIntOrNull(), notes = materialNotes)
                    actions.run { require(raw.isEmpty() || raw.toIntOrNull()?.let { it > 0 } == true) { "Material quantity must be positive or unknown." }; records.saveRequirement(model, before?.revision, provenance); mode = "detail"; "Requirement saved · nothing consumed" }
                }, enabled = !actions.busy && projectDraftValid && requirementDraftValid && materialLabel.isNotBlank() && source.isNotBlank()) { Text(if (selectedRequirement == null) "Add material requirement" else "Save material requirement") }
                "craft" -> PlayerButton(onClick = ::prepareCraft, enabled = !actions.busy && projectDraftValid && consumptionDraftValid && outputTitle.isNotBlank() && source.isNotBlank() && consumedSelections.isNotEmpty()) { Text("Review material consumption") }
                "review" -> {
                    PlayerTextButton(onClick = { craftReviewJson = null; mode = "craft" }, enabled = !actions.busy) { Text("Cancel") }
                    PlayerButton(onClick = {
                        val command = reviewedCraft ?: return@PlayerButton
                        actions.run { records.confirmCraft(command); outputTitle = ""; outputQuantity = "1"; outputDescription = ""; consumedJson = "{}"; craftReviewJson = null; mode = "detail"; "Craft recorded · materials and output saved together" }
                    }, enabled = !actions.busy && reviewDraftValid) { Text("Confirm resolved craft") }
                }
            }
        }) {
            LocalRecordStatus(actions.message, actions.busy)
            draftErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            when(mode) {
                "create", "edit" -> {
                    if (stale) Text("This design changed elsewhere. Your draft is preserved; close and reopen it to load the latest revision.", color = Frost)
                    Field("Invention name", title, { title = it }, enabled = !actions.busy)
                    Choices("Development status", listOf("Proposed", "Research", "Blueprint", "Prototype", "Construction", "Completed", "Paused"), status, enabled = !actions.busy) { status = it }
                    Field("Blueprint / design / research notes", description, { description = it }, single = false, enabled = !actions.busy)
                    SourceField(source, { source = it }, enabled = !actions.busy)
                    Text("A design is separate from its physical output.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                "detail" -> selected?.let { project ->
                    Text(project.status, color = Frost, style = MaterialTheme.typography.labelLarge)
                    ExpandableRecordSection("Concept & Blueprint", true) {
                        Text(project.description.ifBlank { "No design notes recorded" }, color = if (project.description.isBlank()) Muted else MaterialTheme.colorScheme.onSurface)
                        RecordValue("Source", project.source)
                    }
                    ExpandableRecordSection("Research & Construction") {
                        if (stages.isEmpty()) Text("No stages recorded", color = Muted)
                        stages.forEach { stage ->
                            PlayerTextButton(onClick = { stageSnapshot = Json.encodeToString(stage); stageTitle = stage.title; stageNotes = stage.notes; stageStatus = stage.status; source = stage.source; baselineSource = source; mode = "stage" }) {
                                Column(Modifier.fillMaxWidth()) { Text(stage.title, color = Frost); Text(stage.status, color = Muted, style = MaterialTheme.typography.bodySmall); if (stage.notes.isNotBlank()) Text(stage.notes, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        PlayerTextButton(onClick = { stageSnapshot = null; stageTitle = ""; stageNotes = ""; stageStatus = "Planned"; source = project.source; baselineSource = source; mode = "stage" }) { Text("Add stage") }
                    }
                    ExpandableRecordSection("Required materials") {
                        if (requirements.isEmpty()) Text("No materials listed", color = Muted)
                        requirements.forEach { requirement ->
                            val item = items.firstOrNull { it.id == requirement.itemId }
                            PlayerTextButton(onClick = { requirementSnapshot = Json.encodeToString(requirement); materialId = requirement.itemId; materialLabel = requirement.label; materialQuantity = requirement.quantity?.toString().orEmpty(); materialNotes = requirement.notes; source = requirement.source; baselineSource = source; mode = "material" }) {
                                Column(Modifier.fillMaxWidth()) { Text(requirement.label, color = Frost); Text("${requirement.quantity?.toString() ?: "Unknown"} required · ${item?.quantity?.toString() ?: "Unknown"} available", color = Muted, style = MaterialTheme.typography.bodySmall); if (requirement.notes.isNotBlank()) Text(requirement.notes, color = Muted, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        PlayerTextButton(onClick = { requirementSnapshot = null; materialId = null; materialLabel = ""; materialQuantity = ""; materialNotes = ""; source = project.source; baselineSource = source; mode = "material" }) { Text("Add material") }
                    }
                    ExpandableRecordSection("Physical output") {
                        val related = links.filter { (it.fromType == "project" && it.fromId == project.id) || (it.toType == "project" && it.toId == project.id) }
                        if (related.isEmpty()) Text("No linked physical output or equipment", color = Muted)
                        related.forEach { link -> val itemId = if (link.fromType == "item") link.fromId else if (link.toType == "item") link.toId else null; RecordValue(link.label, items.firstOrNull { it.id == itemId }?.title ?: "Linked record") }
                        PlayerTextButton(onClick = ::beginCraft) { Text("Confirm a resolved craft") }
                    }
                    ExpandableRecordSection("History") {
                        val relatedIds = setOf(project.id) + stages.map { it.id } + requirements.map { it.id } + links.filter { it.fromId == project.id || it.toId == project.id }.map { it.id }
                        val history = audit.filter { it.recordId in relatedIds }
                        if (history.isEmpty()) Text("No history recorded", color = Muted)
                        history.take(historyLimit).forEach { event -> PlayerTextButton(onClick = { eventId = event.id; mode = "event" }) { Column(Modifier.fillMaxWidth()) { Text(event.kind.replace('_', ' '), color = Frost); Text(event.source, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } } }
                        if (history.size > historyLimit) PlayerTextButton(onClick = { historyLimit += 40 }) { Text("Show more invention history") }
                        Text(project.id, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                "stage" -> {
                    Field("Stage title", stageTitle, { stageTitle = it }, enabled = !actions.busy)
                    Choices("Stage status", listOf("Planned", "In progress", "Completed", "Blocked"), stageStatus, enabled = !actions.busy) { stageStatus = it }
                    Field("Research outcome / construction notes", stageNotes, { stageNotes = it }, single = false, enabled = !actions.busy)
                    SourceField(source, { source = it }, enabled = !actions.busy)
                }
                "material" -> {
                    Field("Material requirement", materialLabel, { materialLabel = it }, enabled = !actions.busy)
                    Field("Required quantity (blank = unknown)", materialQuantity, { materialQuantity = it }, enabled = !actions.busy)
                    Choices("Inventory material (optional)", listOf<String?>(null) + items.map { it.id }, materialId, display = { id -> items.firstOrNull { it.id == id }?.title ?: "Unlinked requirement" }, enabled = !actions.busy) { materialId = it }
                    Field("Requirement / tool / handling notes", materialNotes, { materialNotes = it }, single = false, enabled = !actions.busy)
                    SourceField(source, { source = it }, enabled = !actions.busy)
                    Text("Requirements do not reserve or consume inventory.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                "craft" -> {
                    Text("Record an already successful craft. Only selected materials are consumed.", style = MaterialTheme.typography.bodyMedium)
                    if (requirements.isEmpty()) Text("Add linked material requirements before recording a craft.", color = Muted)
                    requirements.forEach { requirement ->
                        val item = items.firstOrNull { it.id == requirement.itemId }
                        PlayerCard {
                            Text(requirement.label, style = MaterialTheme.typography.titleSmall)
                            Text("${requirement.quantity?.toString() ?: "Unknown"} required · ${item?.quantity?.toString() ?: "Unknown"} available", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Toggle("Actually consume ${requirement.label}", consumedSelections.containsKey(requirement.id), enabled = !actions.busy) { use -> consumedJson = Json.encodeToString(if (use) consumedSelections + (requirement.id to requirement.quantity?.toString().orEmpty()) else consumedSelections - requirement.id) }
                            if (consumedSelections.containsKey(requirement.id)) Field("Actual consumed quantity: ${requirement.label}", consumedSelections[requirement.id].orEmpty(), { consumedJson = Json.encodeToString(consumedSelections + (requirement.id to it)) }, enabled = !actions.busy)
                        }
                    }
                    Field("Crafted item name", outputTitle, { outputTitle = it }, enabled = !actions.busy)
                    Field("Output quantity", outputQuantity, { outputQuantity = it }, enabled = !actions.busy)
                    Field("Output description / resolved outcome", outputDescription, { outputDescription = it }, single = false, enabled = !actions.busy)
                    SourceField(source, { source = it }, enabled = !actions.busy)
                }
                "review" -> reviewedCraft?.let { command ->
                    Text("${command.output.quantity} × ${command.output.title}", style = MaterialTheme.typography.titleLarge)
                    Text("Materials to consume", color = Muted, style = MaterialTheme.typography.labelLarge)
                    craftReviewLabels.forEach { Text(it) }
                    RecordValue("Source", command.source)
                    Text("Newer material or project changes reject this transaction. Nothing is spent until you confirm.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                "event" -> audit.firstOrNull { it.id == eventId }?.let { event -> RecordValue("Change", event.kind); RecordValue("Source", event.source); RecordValue("Revision", "${event.priorRevision} → ${event.resultingRevision}") }
            }
        }
    }
}
