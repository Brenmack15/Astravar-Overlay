package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private fun connectionKindLabel(kind: String) = when (kind) {
    "Organization" -> "Group"
    "Creature" -> "Being"
    else -> kind
}
private fun connectionStoredKind(kind: String) = when (kind) {
    "Group" -> "Organization"
    "Being" -> "Creature"
    else -> kind
}

/** A directory first. All record inspection and editing stays in one focused sheet. */
@Composable
fun ConnectionsPageV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val context = LocalContext.current
    val entities by records.entities.collectAsState(initial = emptyList())
    val facts by records.facts.collectAsState(initial = emptyList())
    val milestones by records.milestones.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val projects by records.projects.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    val actions = rememberLocalRecordActions()
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf("All") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val baselineDraft = rememberUiDraftText()
    var baseJson by baselineDraft
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var appendId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val decodedBaseline = remember(baseJson) { baseJson?.let { runCatching { Json.decodeFromString<ConnectionEntity>(it) } } }
    val baseline = decodedBaseline?.getOrNull()
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("Person") }
    var notes by rememberSaveable { mutableStateOf("") }
    var aliases by rememberSaveable { mutableStateOf("") }
    var relationship by rememberSaveable { mutableStateOf("") }
    val imageDraft = rememberUiDraftText()
    var image by imageDraft
    var source by rememberSaveable { mutableStateOf("") }
    var entryTitle by rememberSaveable { mutableStateOf("") }
    var entryDetail by rememberSaveable { mutableStateOf("") }
    var entrySource by rememberSaveable { mutableStateOf("") }
    var entryKind by rememberSaveable { mutableStateOf("Known information") }
    var evidence by rememberSaveable { mutableStateOf("Unverified") }
    var linkType by rememberSaveable { mutableStateOf("Inventory item") }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    var recordType by rememberSaveable { mutableStateOf("") }
    var recordId by rememberSaveable { mutableStateOf("") }
    var directoryLimit by rememberSaveable(query, kindFilter) { mutableIntStateOf(40) }
    var historyLimit by rememberSaveable(selectedId) { mutableIntStateOf(40) }
    var imageTarget by rememberSaveable { mutableStateOf("") }
    var editorGeneration by rememberSaveable { mutableIntStateOf(0) }
    val selected = entities.firstOrNull { it.id == selectedId }
    val baselineError = baselineDraft.error ?: if (decodedBaseline?.isFailure == true || (mode == "edit" && selectedId != null && baseline == null))
        "The saved connection draft could not be verified. Close and reopen this connection before saving." else null
    val profileDraftSafe = baselineError == null && imageDraft.canSave
    val stale = baseline != null && selected != null && selected.revision != baseline.revision
    val profileDirty = name != baseline?.name.orEmpty() || kind != connectionKindLabel(baseline?.kind ?: "Person") ||
        notes != baseline?.notes.orEmpty() || aliases != baseline?.aliases.orEmpty() ||
        relationship != baseline?.relationship.orEmpty() || image != baseline?.imageBase64 || source != baseline?.source.orEmpty()
    val dirty = when(mode) {
        "edit" -> profileDirty
        "fact", "milestone", "link" -> entryTitle.isNotBlank() || entryDetail.isNotBlank() || entrySource.isNotBlank() || targetId != null
        else -> false
    }
    fun edit(entry: ConnectionEntity?) {
        baseJson = entry?.let { Json.encodeToString(it) }
        draftId = entry?.id ?: UUID.randomUUID().toString()
        selectedId = entry?.id
        name = entry?.name.orEmpty(); kind = connectionKindLabel(entry?.kind ?: "Person")
        notes = entry?.notes.orEmpty(); aliases = entry?.aliases.orEmpty(); relationship = entry?.relationship.orEmpty()
        image = entry?.imageBase64; source = entry?.source.orEmpty(); editorGeneration++
        actions.message = ""; mode = "edit"
    }
    fun append(next: String) {
        appendId = UUID.randomUUID().toString()
        entryTitle = ""; entryDetail = ""; entrySource = ""; entryKind = "Known information"
        evidence = "Unverified"; targetId = null; linkType = "Inventory item"; mode = next
        actions.message = ""
    }
    fun closeSheet() { if (!actions.busy) mode = if (mode in listOf("edit", "fact", "milestone", "link", "history", "linked") && selectedId != null) "detail" else if (mode == "event") "history" else null }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !actions.busy) {
            val requested = imageTarget
            actions.run {
                check(mode == "edit" && requested == "${selectedId.orEmpty()}:$editorGeneration") { "The open connection changed. Choose its image again." }
                val prepared = compressIcon(context, uri)
                check(mode == "edit" && requested == "${selectedId.orEmpty()}:$editorGeneration") { "The open connection changed. Choose its image again." }
                image = prepared; "Image ready to save."
            }
        }
    }
    val filtered = entities.filter { entity ->
        (kindFilter == "All" || connectionKindLabel(entity.kind) == kindFilter) &&
            (query.isBlank() || listOf(entity.name, entity.aliases, entity.notes, entity.relationship).any { it.contains(query.trim(), true) })
    }.sortedBy { it.name.lowercase() }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Connections", style = MaterialTheme.typography.headlineMedium)
                Text("${entities.size} known connection${if (entities.size == 1) "" else "s"}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            PlayerButton(onClick = { edit(null) }, enabled = !actions.busy,
                modifier = Modifier.semantics { contentDescription = "Add connection" }) { Text("Add") }
        }
        PlayerSearch(query, { query = it }, "Search names, aliases and known notes")
        ConnectionChoice("Directory type", listOf("All", "Person", "Group", "Faction", "Being", "Other"), kindFilter, !actions.busy) { kindFilter = it }
        if (mode == null) {
            ConnectionSaveStatus(actions)
            baselineDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            imageDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (filtered.isEmpty()) PlayerCard {
            Text(if (entities.isEmpty()) "Your directory is empty" else "No matching connections", style = MaterialTheme.typography.titleMedium)
            Text(if (entities.isEmpty()) "Add a person, group or being." else "Try another name or filter.", color = RebuildPalette.Muted)
        }
        filtered.take(directoryLimit).forEach { entity ->
            val latestMilestone = milestones.filter { it.entityId == entity.id }.maxByOrNull { it.updatedAt }
            PlayerCard(onClick = { selectedId = entity.id; mode = "detail"; actions.message = "" }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LedgerIcon(entity.imageBase64, Modifier.size(48.dp), "Portrait for ${entity.name}")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entity.name, style = MaterialTheme.typography.titleMedium)
                        Text(entity.relationship.ifBlank { connectionKindLabel(entity.kind) }, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        latestMilestone?.let { Text(it.title, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                    Text("›", color = RebuildPalette.Cyan, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        if (filtered.size > directoryLimit) PlayerTextButton(onClick = { directoryLimit += 40 }) { Text("Show more connections") }
    }
    val currentMode = mode
    if (currentMode != null) {
        val title = when(currentMode) {
            "edit" -> if (selectedId == null) "New connection" else "Edit connection"
            "fact" -> "Add learned information"
            "milestone" -> "Append milestone"
            "link" -> "Link a record"
            "history" -> "Connection history"
            "event" -> "Event details"
            "linked" -> "Connected record"
            else -> selected?.name ?: "Connection"
        }
        PlayerSheet(title, onDismiss = { closeSheet() }, dirty = dirty, actions = {
            when(currentMode) {
                "edit" -> PlayerButton(enabled = profileDraftSafe && !actions.busy && !stale && name.isNotBlank() && source.isNotBlank() && (baseline == null || profileDirty), onClick = {
                    check(profileDraftSafe) { "Reload the saved connection or reselect its image before saving." }
                    val before = baseline
                    val src = source.trim()
                    val draft = (before ?: ConnectionEntity(name = name.trim(), id = draftId)).copy(name = name.trim(), kind = connectionStoredKind(kind), notes = notes, aliases = aliases, relationship = relationship, imageBase64 = image)
                    actions.run {
                        val receipt = records.saveEntity(draft, before?.revision, src)
                        baseJson = Json.encodeToString(draft.copy(revision = receipt.resultingRevision, source = src, updatedAt = receipt.createdAt))
                        selectedId = draft.id; source = src; name = draft.name; mode = "detail"
                        "Connection saved locally."
                    }
                }) { Text(if (selectedId == null) "Save new connection" else "Save connection changes") }
                "fact" -> PlayerButton(enabled = !actions.busy && selectedId != null && entryDetail.isNotBlank() && entrySource.isNotBlank(), onClick = {
                    val entry = ConnectionFact(entityId = selectedId!!, detail = entryDetail.trim(), category = entryKind, evidenceStatus = evidence, id = appendId)
                    val src = entrySource.trim()
                    actions.run { records.saveFact(entry, source = src); entryDetail = ""; entrySource = ""; mode = "detail"; "Information saved locally." }
                }) { Text("Append relationship context") }
                "milestone" -> PlayerButton(enabled = !actions.busy && selectedId != null && entryTitle.isNotBlank() && entrySource.isNotBlank(), onClick = {
                    val entry = ConnectionMilestone(entityId = selectedId!!, title = entryTitle.trim(), detail = entryDetail, id = appendId)
                    val src = entrySource.trim()
                    actions.run { records.saveMilestone(entry, source = src); entryTitle = ""; entryDetail = ""; entrySource = ""; mode = "detail"; "Milestone saved locally." }
                }) { Text("Append milestone") }
                "link" -> PlayerButton(enabled = !actions.busy && selectedId != null && targetId != null && entryTitle.isNotBlank() && entrySource.isNotBlank(), onClick = {
                    val entry = CampaignLink(fromType = "connection", fromId = selectedId!!, toType = if (linkType == "Inventory item") "item" else "project", toId = targetId!!, label = entryTitle.trim(), id = appendId)
                    val src = entrySource.trim()
                    actions.run { records.saveLink(entry, source = src); targetId = null; entryTitle = ""; entrySource = ""; mode = "detail"; "Record linked locally." }
                }) { Text("Link existing record") }
            }
        }) {
            ConnectionSaveStatus(actions)
            baselineError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            imageDraft.error?.let { Text("Connection image: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            when(currentMode) {
                "edit" -> {
                    if (stale) {
                        Text("A newer record exists. Your draft is preserved. Cancel and reopen to load it.", color = MaterialTheme.colorScheme.error)
                    }
                    ConnectionField("Connection name", name, !actions.busy) { name = it }
                    ConnectionChoice("Connection type", listOf("Person", "Group", "Faction", "Being", "Other"), kind, !actions.busy) { kind = it }
                    ConnectionField("Aliases / other known names (optional)", aliases, !actions.busy) { aliases = it }
                    ConnectionField("Description / appearance (optional)", notes, !actions.busy, false) { notes = it }
                    ConnectionField("Known relationship context (optional)", relationship, !actions.busy, false) { relationship = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LedgerIcon(image, Modifier.size(56.dp), "Connection artwork preview")
                        PlayerTextButton(onClick = { imageTarget = "${selectedId.orEmpty()}:$editorGeneration"; imagePicker.launch("image/*") }, enabled = !actions.busy) { Text("Choose connection image") }
                    }
                    if (image != null) PlayerTextButton(onClick = { image = null }, enabled = !actions.busy) { Text("Remove connection image") }
                    ConnectionField("Profile source / scene / checkpoint (required)", source, !actions.busy, false) { source = it }
                }
                "fact" -> {
                    ConnectionChoice("Context category", listOf("Known information", "Agreements", "Promises", "Favors", "Conflicts", "Shared interests"), entryKind, !actions.busy) { entryKind = it }
                    ConnectionChoice("Context evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), evidence, !actions.busy) { evidence = it }
                    ConnectionField("Known context / agreement / promise", entryDetail, !actions.busy, false) { entryDetail = it }
                    ConnectionField("Context source (required)", entrySource, !actions.busy, false) { entrySource = it }
                }
                "milestone" -> {
                    ConnectionField("Milestone title", entryTitle, !actions.busy) { entryTitle = it }
                    ConnectionField("What happened / significance", entryDetail, !actions.busy, false) { entryDetail = it }
                    ConnectionField("Milestone source (required)", entrySource, !actions.busy, false) { entrySource = it }
                }
                "link" -> {
                    ConnectionChoice("Related record type", listOf("Inventory item", "Invention"), linkType, !actions.busy) { linkType = it; targetId = null }
                    val targets = if (linkType == "Inventory item") items.map { it.id to it.title } else projects.map { it.id to it.title }
                    if (targets.isEmpty()) Text("No ${if (linkType == "Inventory item") "items" else "inventions"} available to link.", color = RebuildPalette.Muted)
                    else ConnectionTargetChoice(targets, targetId, !actions.busy) { targetId = it }
                    ConnectionField("Relationship to this record", entryTitle, !actions.busy) { entryTitle = it }
                    ConnectionField("Link source (required)", entrySource, !actions.busy, false) { entrySource = it }
                    Text("Links keep the existing identity and ownership.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                "history", "event" -> {
                    val factIds = facts.filter { it.entityId == selectedId }.map { it.id }.toSet()
                    val milestoneIds = milestones.filter { it.entityId == selectedId }.map { it.id }.toSet()
                    val linkIds = links.filter { (it.fromType == "connection" && it.fromId == selectedId) || (it.toType == "connection" && it.toId == selectedId) }.map { it.id }.toSet()
                    val entries = audit.filter { when(it.recordType) { "connection" -> it.recordId == selectedId; "fact" -> it.recordId in factIds; "milestone" -> it.recordId in milestoneIds; "link" -> it.recordId in linkIds; else -> false } }
                    if (currentMode == "event") {
                        entries.firstOrNull { it.id == eventId }?.let { ConnectionAuditDetail(it) }
                    } else {
                        if (entries.isEmpty()) Text("No saved history yet.", color = RebuildPalette.Muted)
                        entries.take(historyLimit).forEach { event -> PlayerEntry(event.kind.replace('_', ' '), "${event.recordType} · ${connectionTime(event.createdAt)}", "History", { eventId = event.id; mode = "event" }) }
                        if (entries.size > historyLimit) PlayerTextButton(onClick = { historyLimit += 40 }) { Text("Show more history") }
                    }
                }
                "linked" -> {
                    if (recordType == "item") items.firstOrNull { it.id == recordId }?.let { item ->
                        Text(item.title, style = MaterialTheme.typography.titleLarge)
                        Text("${item.category} · ${item.quantity?.toString() ?: "Quantity unknown"}", color = RebuildPalette.Ice)
                        Text(item.description)
                        if (item.knownProperties.isNotBlank()) Text(item.knownProperties)
                        Text("${item.owner} · ${item.itemState} · ${item.location}", color = RebuildPalette.Muted)
                        Text("Source: ${item.source}", style = MaterialTheme.typography.bodySmall)
                    }
                    else projects.firstOrNull { it.id == recordId }?.let { project ->
                        Text(project.title, style = MaterialTheme.typography.titleLarge); Text(project.status, color = RebuildPalette.Ice)
                        Text(project.description); Text("Source: ${project.source}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> selected?.let { entity ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LedgerIcon(entity.imageBase64, Modifier.size(64.dp), "Portrait for ${entity.name}")
                        Column(Modifier.weight(1f)) {
                            Text(connectionKindLabel(entity.kind), color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
                            if (entity.aliases.isNotBlank()) Text(entity.aliases, color = RebuildPalette.Muted)
                        }
                        PlayerTextButton(onClick = { edit(entity) }) { Text("Edit") }
                    }
                    if (entity.relationship.isNotBlank()) Text(entity.relationship, style = MaterialTheme.typography.titleMedium)
                    if (entity.notes.isNotBlank()) Text(entity.notes)
                    ExpandableRecordSection("Known Information", true) {
                        val known = facts.filter { it.entityId == entity.id }
                        if (known.isEmpty()) Text("No learned information yet.", color = RebuildPalette.Muted)
                        known.forEach { fact ->
                            Text(fact.detail); Text("${fact.category} · ${fact.evidenceStatus}", color = RebuildPalette.Ice, style = MaterialTheme.typography.labelMedium)
                            Text("Source: ${fact.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        PlayerTextButton(onClick = { append("fact") }) { Text("Add learned information") }
                    }
                    ExpandableRecordSection("Meaningful milestones") {
                        if (entity.relationship.isNotBlank()) Text(entity.relationship)
                        val meaningful = milestones.filter { it.entityId == entity.id }
                        if (meaningful.isEmpty()) Text("No milestones recorded.", color = RebuildPalette.Muted)
                        meaningful.forEach { milestone ->
                            Text(milestone.title, style = MaterialTheme.typography.titleSmall)
                            if (milestone.detail.isNotBlank()) Text(milestone.detail)
                            Text("Source: ${milestone.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        PlayerTextButton(onClick = { append("milestone") }) { Text("Add milestone") }
                    }
                    ExpandableRecordSection("Connected Records") {
                        val related = links.filter { (it.fromType == "connection" && it.fromId == entity.id) || (it.toType == "connection" && it.toId == entity.id) }
                        if (related.isEmpty()) Text("No linked records.", color = RebuildPalette.Muted)
                        related.forEach { link ->
                            val outgoing = link.fromType == "connection" && link.fromId == entity.id
                            val type = if (outgoing) link.toType else link.fromType
                            val id = if (outgoing) link.toId else link.fromId
                            val target = if (type == "item") items.firstOrNull { it.id == id }?.title else projects.firstOrNull { it.id == id }?.title
                            PlayerEntry(target ?: "Related $type", link.label, if (type == "item") "Core" else "Weapon", { recordType = type; recordId = id; mode = "linked" })
                            Text("Source: ${link.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        PlayerTextButton(onClick = { append("link") }) { Text("Link existing record") }
                    }
                    PlayerEntry("History", "Saved changes and sources", "History", { mode = "history" })
                    Text("Source: ${entity.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable private fun ConnectionSaveStatus(actions: LocalRecordActions) {
    if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (actions.message.isNotBlank()) Text(actions.message, color = if (actions.message.startsWith("Not saved")) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
}
private fun connectionTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
@Composable internal fun ConnectionAuditDetail(event: CampaignAudit) {
    Text(event.kind.replace('_', ' '), style = MaterialTheme.typography.titleLarge)
    Text(connectionTime(event.createdAt), color = RebuildPalette.Muted)
    Text("Source: ${event.source}")
    Text("Revision ${event.priorRevision} → ${event.resultingRevision}")
    Text("Record: ${event.recordId}\nCommand: ${event.commandId}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    event.beforeJson?.let { ExpandableRecordSection("Before") { Text(it, style = MaterialTheme.typography.bodySmall) } }
    event.afterJson?.let { ExpandableRecordSection("After") { Text(it, style = MaterialTheme.typography.bodySmall) } }
}
@Composable private fun ConnectionField(label: String, value: String, enabled: Boolean, single: Boolean = true, change: (String) -> Unit) {
    Field(label, value, change, single = single, enabled = enabled)
}
@Composable internal fun ConnectionChoice(label: String, options: List<String>, selected: String, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PlayerOutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
                Text(selected, color = RebuildPalette.Ice); Spacer(Modifier.width(8.dp)); Text("⌄")
            }
        }
        DropdownMenu(expanded && enabled, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; change(option) }) }
        }
    }
}
@Composable internal fun ConnectionTargetChoice(options: List<Pair<String, String>>, selected: String?, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PlayerOutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(options.firstOrNull { it.first == selected }?.second ?: "Choose an existing record")
        }
        DropdownMenu(expanded && enabled, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; change(id) }) }
        }
    }
}
