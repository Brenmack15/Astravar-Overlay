package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    Text("PRIVATE LOCAL RECORDS · NOT UPLOADED", color = Frost, style = MaterialTheme.typography.labelSmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotBlank()) Text(message, color = Brass, style = MaterialTheme.typography.bodyMedium)
}

@Composable internal fun ExpandableRecordSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    ArcanePanel {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Brass, style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "−" else "+", color = Frost)
            }
        }
        if (expanded) content()
    }
}

@Composable private fun SourceField(value: String, change: (String) -> Unit, enabled: Boolean = true) {
    Field("Source / resolved scene / checkpoint", value, change, single = false, enabled = enabled)
    Text("Record what actually happened. Proposals do not consume resources or create owned items.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable fun InventionsPageV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val projects by records.projects.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    val actions = rememberLocalRecordActions()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var draftKey by remember { mutableIntStateOf(0) }
    val selected = projects.firstOrNull { it.id == selectedId }
    var historyLimit by remember(selectedId) { mutableIntStateOf(40) }
    var editorProject by remember { mutableStateOf<InventionProject?>(null) }
    var discardTarget by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Proposed") }
    var source by remember { mutableStateOf("") }
    var stageTitle by remember { mutableStateOf("") }
    var stageNotes by remember { mutableStateOf("") }
    var stageStatus by remember { mutableStateOf("Planned") }
    var materialId by remember { mutableStateOf<String?>(null) }
    var materialLabel by remember { mutableStateOf("") }
    var materialQuantity by remember { mutableStateOf("") }
    var materialNotes by remember { mutableStateOf("") }
    var outputTitle by remember { mutableStateOf("") }
    var outputQuantity by remember { mutableStateOf("1") }
    var outputDescription by remember { mutableStateOf("") }
    var craftReview by remember { mutableStateOf<CraftCommand?>(null) }
    var craftReviewLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var consumedSelections by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedStage by remember { mutableStateOf<ProjectStage?>(null) }
    var selectedRequirement by remember { mutableStateOf<MaterialRequirement?>(null) }
    val stagesFlow = remember(selectedId) { selectedId?.let { records.stagesFor(it) } }
    val requirementsFlow = remember(selectedId) { selectedId?.let { records.requirementsFor(it) } }
    val stages = stagesFlow?.collectAsState(initial = emptyList())?.value.orEmpty()
    val requirements = requirementsFlow?.collectAsState(initial = emptyList())?.value.orEmpty()

    val profileDirty = title != editorProject?.title.orEmpty() || description != editorProject?.description.orEmpty() ||
        status != (editorProject?.status ?: "Proposed") || source != editorProject?.source.orEmpty()
    val auxiliaryDirty = stageTitle != selectedStage?.title.orEmpty() || stageNotes != selectedStage?.notes.orEmpty() ||
        stageStatus != (selectedStage?.status ?: "Planned") || materialId != selectedRequirement?.itemId ||
        materialLabel != selectedRequirement?.label.orEmpty() || materialQuantity != selectedRequirement?.quantity?.toString().orEmpty() ||
        materialNotes != selectedRequirement?.notes.orEmpty() || outputTitle.isNotBlank() || outputDescription.isNotBlank() ||
        outputQuantity != "1" || consumedSelections.isNotEmpty()
    val stale = selected != null && editorProject != null && selected.revision != editorProject?.revision
    fun navigateTo(id: String?) {
        if (profileDirty || auxiliaryDirty) { discardTarget = id; showDiscard = true }
        else { selectedId = id; if (id == null) draftKey++; actions.message = "" }
    }
    LaunchedEffect(selectedId, selected?.revision, draftKey) {
        if (editorProject?.id == selectedId && selected != null && editorProject != null && (profileDirty || auxiliaryDirty)) return@LaunchedEffect
        editorProject = selected
        title = selected?.title.orEmpty(); description = selected?.description.orEmpty()
        status = selected?.status ?: "Proposed"; source = selected?.source.orEmpty()
        stageTitle = ""; stageNotes = ""; stageStatus = "Planned"; selectedStage = null
        materialId = null; materialLabel = ""; materialQuantity = ""; materialNotes = ""; selectedRequirement = null
        outputTitle = ""; outputQuantity = "1"; outputDescription = ""; craftReview = null; craftReviewLabels = emptyList(); consumedSelections = emptyMap()
    }
    Page(resetKey = selectedId to draftKey) {
        SectionCaption("INVENTIONS", "DESIGNS · RESEARCH · CONSTRUCTION")
        Section("Workshop") {
            LocalRecordStatus(actions.message, actions.busy)
            Field("Search inventions", query, { query = it }, enabled = !actions.busy)
            OutlinedButton(onClick = { navigateTo(null) }, enabled = !actions.busy) { Text("New invention") }
            if (projects.isEmpty()) Text("Your workshop is empty. Record a design without creating an owned item.", color = Muted)
            projects.filter { query.isBlank() || "${it.title} ${it.description} ${it.status}".contains(query, true) }.forEach { project ->
                TextButton(onClick = { navigateTo(project.id) }, enabled = !actions.busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(project.title, color = Brass)
                        Text("${project.status} · revision ${project.revision}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Section(if (selected == null) "New invention record" else "Design: ${selected.title}") {
            if (stale) {
                Text("A newer revision exists. Your draft is preserved; reopen this design before saving.", color = Frost, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { editorProject = null; draftKey++ }, enabled = !actions.busy) { Text("Reload latest invention") }
            }
            Field("Invention name", title, { title = it }, enabled = !actions.busy)
            Choices("Development status", listOf("Proposed", "Research", "Blueprint", "Prototype", "Construction", "Completed", "Paused"), status, enabled = !actions.busy) { status = it }
            Field("Blueprint / design / research notes", description, { description = it }, single = false, enabled = !actions.busy)
            SourceField(source, { source = it }, enabled = !actions.busy)
            Text("A completed design is not an owned item. Only a confirmed craft transaction records material consumption and its physical output.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Action(if (selected == null) "Save invention" else "Save invention changes", !actions.busy && !stale && title.isNotBlank() && source.isNotBlank()) {
                val before = editorProject
                val model = before?.copy(title = title, description = description, status = status)
                    ?: InventionProject(title = title, description = description, status = status)
                val provenance = source
                actions.run {
                    records.saveProject(model, before?.revision, provenance)
                    editorProject = model.copy(revision = (before?.revision ?: 0) + 1, source = provenance)
                    selectedId = model.id
                    "Saved locally: ${model.title}. No resources consumed."
                }
            }
        }
        if (selected != null) {
            ExpandableRecordSection("Research & construction stages", true) {
                if (stages.isEmpty()) Text("No stages recorded. Add only the work that is actually planned or resolved.", color = Muted)
                stages.forEach { stage ->
                    TextButton(onClick = { selectedStage = stage; stageTitle = stage.title; stageNotes = stage.notes; stageStatus = stage.status }, enabled = !actions.busy) {
                        Text("${stage.title} · ${stage.status}", color = Brass)
                    }
                }
                Field("Stage title", stageTitle, { stageTitle = it }, enabled = !actions.busy)
                Field("Research outcome / construction notes", stageNotes, { stageNotes = it }, single = false, enabled = !actions.busy)
                Choices("Stage status", listOf("Planned", "In progress", "Completed", "Blocked"), stageStatus, enabled = !actions.busy) { stageStatus = it }
                Action(if (selectedStage == null) "Add stage" else "Save stage", !actions.busy && stageTitle.isNotBlank() && source.isNotBlank()) {
                    val before = selectedStage
                    val model = before?.copy(title = stageTitle, notes = stageNotes, status = stageStatus)
                        ?: ProjectStage(projectId = selected.id, title = stageTitle, notes = stageNotes, status = stageStatus, sortOrder = stages.size)
                    val provenance = source
                    actions.run {
                        records.saveStage(model, before?.revision, provenance)
                        selectedStage = null; stageTitle = ""; stageNotes = ""; stageStatus = "Planned"
                        "Stage saved locally. Resources unchanged."
                    }
                }
                if (selectedStage != null) TextButton(onClick = { selectedStage = null; stageTitle = ""; stageNotes = ""; stageStatus = "Planned" }, enabled = !actions.busy) { Text("New stage") }
            }
            ExpandableRecordSection("Required materials", true) {
                Text("Requirements describe the design; they do not reserve or spend inventory. Link a real item when its identity is known.", color = Muted, style = MaterialTheme.typography.bodySmall)
                requirements.forEach { requirement ->
                    TextButton(onClick = {
                        selectedRequirement = requirement; materialId = requirement.itemId
                        materialLabel = requirement.label; materialQuantity = requirement.quantity?.toString().orEmpty(); materialNotes = requirement.notes
                    }, enabled = !actions.busy) {
                        Text("${requirement.label} · ${requirement.quantity?.toString() ?: "quantity unknown"}", color = Brass)
                    }
                }
                Choices("Inventory material (optional)", listOf<String?>(null) + items.map { it.id }, materialId,
                    display = { id -> items.firstOrNull { it.id == id }?.title ?: "Unlinked requirement" }, enabled = !actions.busy) { materialId = it }
                Field("Material requirement", materialLabel, { materialLabel = it }, enabled = !actions.busy)
                Field("Required quantity (blank = unknown)", materialQuantity, { materialQuantity = it }, enabled = !actions.busy)
                Field("Requirement / tool / handling notes", materialNotes, { materialNotes = it }, single = false, enabled = !actions.busy)
                Action(if (selectedRequirement == null) "Add material requirement" else "Save material requirement", !actions.busy && materialLabel.isNotBlank() && source.isNotBlank()) {
                    val raw = materialQuantity.trim(); val before = selectedRequirement
                    val model = before?.copy(itemId = materialId, label = materialLabel, quantity = raw.toIntOrNull(), notes = materialNotes)
                        ?: MaterialRequirement(projectId = selected.id, itemId = materialId, label = materialLabel, quantity = raw.toIntOrNull(), notes = materialNotes)
                    val provenance = source
                    actions.run {
                        require(raw.isEmpty() || raw.toIntOrNull()?.let { it > 0 } == true) { "Material quantity must be positive or unknown." }
                        records.saveRequirement(model, before?.revision, provenance)
                        selectedRequirement = null; materialId = null; materialLabel = ""; materialQuantity = ""; materialNotes = ""
                        "Requirement saved locally. Nothing consumed."
                    }
                }
                if (selectedRequirement != null) TextButton(onClick = { selectedRequirement = null; materialId = null; materialLabel = ""; materialQuantity = ""; materialNotes = "" }, enabled = !actions.busy) { Text("New requirement") }
            }
            ExpandableRecordSection("Confirm a resolved craft") {
                Text("Use only after the craft has actually succeeded. This spends the linked, known quantities below and creates one new physical output. It does not roll success, advance time, or spend character resources.", color = Muted, style = MaterialTheme.typography.bodySmall)
                requirements.forEach { requirement ->
                    val material = items.firstOrNull { it.id == requirement.itemId }
                    Text("${requirement.label}: ${requirement.quantity?.toString() ?: "unknown"} required · ${material?.quantity?.toString() ?: "unknown"} available", style = MaterialTheme.typography.bodySmall)
                    Toggle("Actually consume ${requirement.label}", consumedSelections.containsKey(requirement.id), enabled = !actions.busy) { selectedUse ->
                        consumedSelections = if (selectedUse) consumedSelections + (requirement.id to requirement.quantity?.toString().orEmpty()) else consumedSelections - requirement.id
                    }
                    if (consumedSelections.containsKey(requirement.id)) Field("Actual consumed quantity: ${requirement.label}", consumedSelections[requirement.id].orEmpty(), { consumedSelections = consumedSelections + (requirement.id to it) }, enabled = !actions.busy)
                }
                Text("Leave reusable tools and retained components unchecked. Only checked, confirmed quantities are consumed.", color = Frost, style = MaterialTheme.typography.bodySmall)
                Field("Crafted item name", outputTitle, { outputTitle = it }, enabled = !actions.busy)
                Field("Output quantity", outputQuantity, { outputQuantity = it }, enabled = !actions.busy)
                Field("Output description / resolved outcome", outputDescription, { outputDescription = it }, single = false, enabled = !actions.busy)
                Text("Current source: ${source.ifBlank { "Provide a source in the design editor." }}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Action("Review material consumption", !actions.busy && outputTitle.isNotBlank() && source.isNotBlank() && consumedSelections.isNotEmpty()) {
                    try {
                        val count = outputQuantity.toIntOrNull() ?: error("Output quantity must be a whole number.")
                        require(count > 0) { "Output quantity must be positive." }
                        val chosenRequirements = requirements.filter { consumedSelections.containsKey(it.id) }
                        require(chosenRequirements.isNotEmpty()) { "Select the actual materials consumed." }
                        val consumed = chosenRequirements.map { requirement ->
                            val material = items.firstOrNull { it.id == requirement.itemId }
                                ?: error("Link every consumed requirement to its real inventory item first.")
                            val amount = consumedSelections[requirement.id]?.toIntOrNull()
                                ?: error("Confirm actual consumed quantities before crafting.")
                            require(amount > 0) { "Consumed quantities must be positive." }
                            MaterialConsumption(itemId = material.id, expectedRevision = material.revision, quantity = amount)
                        }
                        val combined = consumed.groupBy { it.itemId }.map { (_, uses) ->
                            val total = uses.sumOf { it.quantity.toLong() }
                            require(total <= Int.MAX_VALUE) { "Consumed quantity is too large." }
                            uses.first().copy(quantity = total.toInt())
                        }
                        craftReviewLabels = combined.map { use -> "${items.first { it.id == use.itemId }.title}: ${use.quantity}" }
                        craftReview = CraftCommand(projectId = selected.id, expectedProjectRevision = selected.revision,
                            materials = combined, output = LedgerDraft(title = outputTitle, category = "Other", owner = "Unknown", itemState = "Unknown",
                                quantity = count, description = outputDescription, source = source), source = source)
                    } catch (e: Exception) { actions.message = "Cannot review craft: ${e.message ?: "Check the quantities and linked materials."}" }
                }
            }
            ExpandableRecordSection("Connected records & history") {
                val related = links.filter { (it.fromType == "project" && it.fromId == selected.id) || (it.toType == "project" && it.toId == selected.id) }
                if (related.isEmpty()) Text("No linked physical output or equipment yet.", color = Muted)
                related.forEach { link ->
                    val itemId = if (link.fromType == "item") link.fromId else if (link.toType == "item") link.toId else null
                    Text("${link.label} · ${items.firstOrNull { it.id == itemId }?.title ?: "Linked record"}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Persistent design ID: ${selected.id}", color = Muted, style = MaterialTheme.typography.labelSmall)
                val relatedIds = setOf(selected.id) + stages.map { it.id } + requirements.map { it.id } + related.map { it.id }
                val history = audit.filter { it.recordId in relatedIds }
                if (history.isEmpty()) Text("No local audit entries yet.", color = Muted)
                history.take(historyLimit).forEach { event ->
                    Text("${event.kind} · revision ${event.priorRevision} → ${event.resultingRevision}", style = MaterialTheme.typography.bodySmall)
                    Text("Source: ${event.source}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (history.size > historyLimit) OutlinedButton(onClick = { historyLimit += 40 }, enabled = !actions.busy) {
                    Text("Show more invention history (${history.size - historyLimit} remaining)")
                }
            }
        }
    }
    if (showDiscard) ArcaneDialog(
        onDismissRequest = { showDiscard = false },
        title = { Text("Discard unsaved design edits?") },
        text = { Text("The saved design remains unchanged. Continue editing to keep this draft.") },
        confirmButton = { TextButton(onClick = { selectedId = discardTarget; editorProject = null; draftKey++; showDiscard = false }) { Text("Discard draft") } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } }
    )
    val reviewedCraft = craftReview
    if (reviewedCraft != null) ArcaneDialog(
        onDismissRequest = { if (!actions.busy) craftReview = null },
        title = { Text("Commit confirmed craft?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Create ${reviewedCraft.output.quantity} × ${reviewedCraft.output.title} and consume these confirmed quantities. Revisions are frozen at this review; newer changes will reject the transaction.")
                craftReviewLabels.forEach { Text(it) }
                Text("Source: ${reviewedCraft.source}", color = Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                craftReview = null
                actions.run {
                    records.confirmCraft(reviewedCraft)
                    outputTitle = ""; outputDescription = ""; consumedSelections = emptyMap()
                    "Craft recorded locally. Materials and physical output saved together."
                }
            }, enabled = !actions.busy) { Text("Confirm resolved craft") }
        },
        dismissButton = { TextButton(onClick = { craftReview = null }, enabled = !actions.busy) { Text("Cancel") } }
    )
}

@Composable fun EekPageV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val companions by records.companionStates.collectAsState(initial = emptyList())
    val resources by records.companionResources.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val state = companions.firstOrNull { it.companionId == "eek" }
    var editorState by remember { mutableStateOf<CompanionState?>(null) }
    var reloadState by remember { mutableIntStateOf(0) }
    val actions = rememberLocalRecordActions()
    var condition by remember { mutableStateOf("Unknown") }
    var notes by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var resourceName by remember { mutableStateOf("") }
    var resourceCurrent by remember { mutableStateOf("") }
    var resourceMax by remember { mutableStateOf("") }
    var selectedResource by remember { mutableStateOf<CompanionResource?>(null) }
    var itemId by remember { mutableStateOf<String?>(null) }
    var linkKind by remember { mutableStateOf("Equipment") }
    val stateDirty = condition != (editorState?.condition ?: "Unknown") || notes != editorState?.notes.orEmpty() || source != editorState?.source.orEmpty()
    val staleState = state != null && editorState != null && state.revision != editorState?.revision
    LaunchedEffect(state?.revision, reloadState) {
        if (editorState != null && stateDirty) return@LaunchedEffect
        editorState = state
        condition = state?.condition ?: "Unknown"; notes = state?.notes.orEmpty(); source = state?.source.orEmpty()
    }
    Page {
        SectionCaption("EEK", "COMPANION · MANUAL KNOWN STATE")
        Section("Mechanical owl companion") {
            LocalRecordStatus(actions.message, actions.busy)
            Text("Brass mechanical owl · folds into a compact sphere. Current HP, build, equipment and recovery availability are not inferred from old sheets.", color = Muted, style = MaterialTheme.typography.bodySmall)
            StatusPill(state?.condition ?: "Current condition unknown", state?.condition == "Active")
            if (staleState) {
                Text("A newer companion status exists. Your draft is preserved; reload before changing status.", color = Frost, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { editorState = null; reloadState++ }, enabled = !actions.busy) { Text("Reload latest Eek status") }
            }
            Choices("Confirmed condition", listOf("Unknown", "Active", "Dormant", "Disabled", "Other"), condition, enabled = !actions.busy) { condition = it }
            Field("Current status / upgrade notes", notes, { notes = it }, single = false, enabled = !actions.busy)
            SourceField(source, { source = it }, enabled = !actions.busy)
            Action("Save Eek status", !actions.busy && !staleState && source.isNotBlank()) {
                val before = editorState
                val model = before?.copy(condition = condition, notes = notes)
                    ?: CompanionState(companionId = "eek", name = "Eek", condition = condition, notes = notes)
                val provenance = source
                actions.run {
                    val receipt = records.saveCompanionState(model, before?.revision, provenance)
                    editorState = model.copy(revision = receipt.resultingRevision, source = provenance, updatedAt = receipt.createdAt)
                    "Eek status saved locally. No resources restored."
                }
            }
        }
        ExpandableRecordSection("Known resources", true) {
            Text("Record only confirmed values. Blank is unknown. HP, Reaction, Mending Repairs and Reconstruction are separate counters; no rest or time-based refill happens here.", color = Muted, style = MaterialTheme.typography.bodySmall)
            val eekResources = resources.filter { it.companionId == "eek" }
            if (eekResources.isEmpty()) Text("No current resource values have been entered.", color = Muted)
            eekResources.forEach { resource ->
                TextButton(onClick = {
                    selectedResource = resource; resourceName = resource.name
                    resourceCurrent = resource.current?.toString().orEmpty(); resourceMax = resource.maximum?.toString().orEmpty()
                }, enabled = !actions.busy) { Text("${resource.name}: ${resource.current?.toString() ?: "?"} / ${resource.maximum?.toString() ?: "?"}", color = Brass) }
            }
            Field("Resource name", resourceName, { resourceName = it }, enabled = !actions.busy)
            Field("Confirmed current value (blank = unknown)", resourceCurrent, { resourceCurrent = it }, enabled = !actions.busy)
            Field("Confirmed maximum (blank = unknown)", resourceMax, { resourceMax = it }, enabled = !actions.busy)
            if (state == null) Text("Save Eek's sourced status before adding resources.", color = Frost, style = MaterialTheme.typography.bodySmall)
            Action(if (selectedResource == null) "Save known resource" else "Update known resource", !actions.busy && state != null && resourceName.isNotBlank() && source.isNotBlank()) {
                val before = selectedResource; val current = resourceCurrent.trim(); val maximum = resourceMax.trim(); val provenance = source
                val model = before?.copy(name = resourceName, current = current.toIntOrNull(), maximum = maximum.toIntOrNull())
                    ?: CompanionResource(companionId = "eek", name = resourceName, current = current.toIntOrNull(), maximum = maximum.toIntOrNull())
                actions.run {
                    require(current.isEmpty() || current.toIntOrNull()?.let { it >= 0 } == true) { "Current value must be nonnegative or unknown." }
                    require(maximum.isEmpty() || maximum.toIntOrNull()?.let { it >= 0 } == true) { "Maximum must be nonnegative or unknown." }
                    records.saveCompanionResource(model, before?.revision, provenance)
                    selectedResource = null; resourceName = ""; resourceCurrent = ""; resourceMax = ""
                    "Confirmed resource value saved locally. No recovery or roll performed."
                }
            }
            if (selectedResource != null) TextButton(onClick = { selectedResource = null; resourceName = ""; resourceCurrent = ""; resourceMax = "" }, enabled = !actions.busy) { Text("New resource") }
        }
        ExpandableRecordSection("Equipment & accepted upgrades", true) {
            Text("Links point to existing item IDs; Eek's view never creates a duplicate item or silently transfers ownership. Record the actual owner and state in Inventory.", color = Muted, style = MaterialTheme.typography.bodySmall)
            val equipped = links.filter { (it.toType == "companion" && it.toId == "eek") || (it.fromType == "companion" && it.fromId == "eek") }
            if (equipped.isEmpty()) Text("No equipment links recorded.", color = Muted)
            equipped.forEach { link ->
                val id = if (link.fromType == "item") link.fromId else link.toId
                Text("${items.firstOrNull { it.id == id }?.title ?: "Related record"} · ${link.label}", style = MaterialTheme.typography.bodyMedium)
            }
            Choices("Existing physical item", items.map { it.id }, itemId, display = { id -> items.firstOrNull { it.id == id }?.title ?: "Choose item" }, enabled = !actions.busy) { itemId = it }
            Choices("Relationship", listOf("Equipment", "Installed upgrade", "Carried", "Research subject"), linkKind, enabled = !actions.busy) { linkKind = it }
            Action("Link existing item to Eek", !actions.busy && state != null && itemId != null && source.isNotBlank()) {
                val id = itemId ?: return@Action; val relation = linkKind; val provenance = source
                actions.run {
                    records.saveLink(CampaignLink(fromType = "item", fromId = id, toType = "companion", toId = "eek", label = relation), null, provenance)
                    "Item linked locally by its persistent identity. Ownership unchanged."
                }
            }
        }
        ExpandableRecordSection("Accepted homebrew reference") {
            Text("HB-01 · Dedicated companion infusion slot. Eek remains Zerg's Homunculus Servant, not a generic familiar or Steel Defender.", style = MaterialTheme.typography.bodySmall)
            Text("HB-03 · Eek acts immediately after Zerg; commands do not cost Zerg a Bonus Action. This screen does not automate turns or dice.", style = MaterialTheme.typography.bodySmall)
            Text("HB-04 · Maximum HP = 6 + 4 × Artificer level. AC = 13 + PB. Force Strike = 1d6 + PB force; attack uses Zerg's base spell attack modifier. Evasion follows the accepted Dexterity-save rule.", style = MaterialTheme.typography.bodySmall)
            Text("HB-05 · One Reaction per round. Channel Magic requires the established touch-spell range and reach. Protective Deflection requires Eek within 5 ft. and reduces damage by PB.", style = MaterialTheme.typography.bodySmall)
            Text("HB-06 · At 0 HP Eek folds into a Disabled sphere. Active Mending Repairs: 2d6 HP up to maximum, once per Short Rest. Disabled Reconstruction: 10 minutes with Tinker's Tools and Mending, once per Long Rest. Time or a rest alone does not automatically reactivate or refill Eek.", style = MaterialTheme.typography.bodySmall)
            Text("Reference: accepted 06_HOMEBREW_AND_EEK.md. Historical example levels do not establish the current build. Later accepted amendments take precedence.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
