package com.yazsras.astravar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.CampaignLink
import com.yazsras.astravar.data.ConnectionEntity
import com.yazsras.astravar.data.ConnectionFact
import com.yazsras.astravar.data.ConnectionMilestone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun connectionKindLabel(kind: String) = when (kind) {
    "Organization" -> "Group"
    "Creature" -> "Being"
    else -> kind
}

private fun connectionStoredKind(label: String) = when (label) {
    "Group" -> "Organization"
    "Being" -> "Creature"
    else -> label
}

/** Player-known connections only. This page never infers hidden attitudes or gameplay effects. */
@Composable
fun ConnectionsPageV2(repository: CampaignLedgerRepository) {
    val records = repository.records
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entities by records.entities.collectAsState(initial = emptyList())
    val facts by records.facts.collectAsState(initial = emptyList())
    val milestones by records.milestones.collectAsState(initial = emptyList())
    val links by records.links.collectAsState(initial = emptyList())
    val projects by records.projects.collectAsState(initial = emptyList())
    val items by repository.items.collectAsState(initial = emptyList())
    val audit by records.audit.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf("All") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<ConnectionEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Person") }
    var notes by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("") }
    var factCategory by remember { mutableStateOf("Known information") }
    var factEvidence by remember { mutableStateOf("Unverified") }
    var factDetail by remember { mutableStateOf("") }
    var factSource by remember { mutableStateOf("") }
    var milestoneTitle by remember { mutableStateOf("") }
    var milestoneDetail by remember { mutableStateOf("") }
    var milestoneSource by remember { mutableStateOf("") }
    var linkType by remember { mutableStateOf("Inventory item") }
    var linkTargetId by remember { mutableStateOf<String?>(null) }
    var linkLabel by remember { mutableStateOf("") }
    var linkSource by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var pageReset by remember { mutableIntStateOf(0) }
    var historyLimit by remember(selectedId) { mutableIntStateOf(40) }
    var directoryLimit by remember(query, kindFilter) { mutableIntStateOf(40) }
    var imageTargetPage by remember { mutableIntStateOf(0) }

    val profileDirty = name != loaded?.name.orEmpty() || kind != connectionKindLabel(loaded?.kind ?: "Person") ||
        notes != loaded?.notes.orEmpty() || aliases != loaded?.aliases.orEmpty() ||
        relationship != loaded?.relationship.orEmpty() || image != loaded?.imageBase64 ||
        source != loaded?.source.orEmpty()
    val detailDirty = factDetail.isNotBlank() || factSource.isNotBlank() ||
        milestoneTitle.isNotBlank() || milestoneDetail.isNotBlank() || milestoneSource.isNotBlank() ||
        linkLabel.isNotBlank() || linkSource.isNotBlank() || linkTargetId != null
    val latest = entities.firstOrNull { it.id == selectedId }
    val stale = loaded != null && latest != null && loaded?.revision != latest.revision

    fun loadSelection(id: String?) {
        val entry = entities.firstOrNull { it.id == id }
        selectedId = entry?.id
        loaded = entry
        name = entry?.name.orEmpty(); kind = connectionKindLabel(entry?.kind ?: "Person")
        notes = entry?.notes.orEmpty(); aliases = entry?.aliases.orEmpty()
        relationship = entry?.relationship.orEmpty(); image = entry?.imageBase64
        source = entry?.source.orEmpty()
        factDetail = ""; factSource = ""; milestoneTitle = ""; milestoneDetail = ""
        milestoneSource = ""; linkTargetId = null; linkLabel = ""; linkSource = ""
        status = ""; failed = false; pageReset++
    }
    fun chooseSelection(id: String?) {
        if (busy) return
        if (profileDirty || detailDirty) {
            pendingSelection = id
            showDiscard = true
        } else loadSelection(id)
    }
    fun runLocal(operation: suspend () -> String) {
        if (busy) return
        busy = true; failed = false; status = "Saving locally…"
        scope.launch {
            try { status = operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; status = "Not saved: ${e.message ?: "Please try again."}" }
            finally { busy = false }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) {
            runLocal {
                check(imageTargetPage == pageReset) { "The open connection changed. Select its image again." }
                val prepared = compressIcon(context, uri)
                check(imageTargetPage == pageReset) { "The open connection changed. Select its image again." }
                image = prepared
                "Image prepared. Save the connection to keep it."
            }
        }
    }
    if (showDiscard) AlertDialog(
        onDismissRequest = { showDiscard = false },
        title = { Text("Leave unsaved connection notes?") },
        text = { Text("The current edits and unfinished entries have not been saved. Stay here to save them, or discard these edits.") },
        confirmButton = { TextButton(onClick = { showDiscard = false; loadSelection(pendingSelection) }) { Text("Discard edits") } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } }
    )

    val filtered = entities.filter { entity ->
        (kindFilter == "All" || connectionKindLabel(entity.kind) == kindFilter) &&
            (query.isBlank() || listOf(entity.name, entity.aliases, entity.notes, entity.relationship)
                .any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedBy { it.name.lowercase() }
    Page(resetKey = pageReset) {
        SectionCaption("CONNECTIONS", "LOCAL CAMPAIGN RECORDS")
        Section("People, groups & beings") {
            Text("Keep what your character knows: conversations, agreements and shared history. Descriptions do not reveal hidden motives or change gameplay.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            if (status.isNotBlank()) Text(status,
                color = if (failed) MaterialTheme.colorScheme.error else Frost,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Field("Search names, aliases and known notes", query, { query = it })
            ConnectionChoice("Directory type", listOf("All", "Person", "Group", "Faction", "Being", "Other"), kindFilter, !busy) { kindFilter = it }
            Action("New connection", !busy) { chooseSelection(null) }
            Text("${filtered.size} connection${if (filtered.size == 1) "" else "s"}", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            if (filtered.isEmpty()) Text(if (entities.isEmpty()) "No local connections yet. Add only information your character knows."
                else "No connections match this search.", color = Muted)
            filtered.take(directoryLimit).forEach { entry ->
                TextButton(onClick = { chooseSelection(entry.id) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LedgerIcon(entry.imageBase64, Modifier.size(48.dp), "Connection artwork for ${entry.name}")
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = Brass)
                            Text("${connectionKindLabel(entry.kind)} · revision ${entry.revision}", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                            if (entry.aliases.isNotBlank()) Text(entry.aliases, color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (selectedId == entry.id) Text("Open", color = Frost)
                    }
                }
                HorizontalDivider(color = Brass.copy(alpha = .15f))
            }
            if (filtered.size > directoryLimit) OutlinedButton(onClick = { directoryLimit += 40 }, enabled = !busy) {
                Text("Show more connections (${filtered.size - directoryLimit} remaining)")
            }
        }
        Section(if (selectedId == null) "Create a connection" else "Connection profile") {
            if (loaded != null) Text("Stable record: ${loaded!!.id} · revision ${loaded!!.revision}",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            if (stale) {
                Text("This record changed after you opened it. Your edits are still here. Reload the current record before saving.",
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { chooseSelection(selectedId) }, enabled = !busy) { Text("Reload current record") }
            }
            ConnectionField("Connection name", name, !busy) { name = it }
            ConnectionChoice("Connection type", listOf("Person", "Group", "Faction", "Being", "Other"), kind, !busy) { kind = it }
            ConnectionField("Aliases / other known names (optional)", aliases, !busy) { aliases = it }
            ConnectionField("Description / appearance (optional)", notes, !busy, single = false) { notes = it }
            ConnectionField("Known relationship context (optional)", relationship, !busy, single = false) { relationship = it }
            Text("Describe your actual relationship and its source. There is no numerical trust or disposition score.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LedgerIcon(image, Modifier.size(76.dp), "Connection artwork preview")
                Column(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { imageTargetPage = pageReset; imagePicker.launch("image/*") }, enabled = !busy) { Text("Choose connection image") }
                    if (image != null) TextButton(onClick = { image = null }, enabled = !busy) { Text("Remove connection image") }
                }
            }
            ConnectionField("Profile source / scene / checkpoint (required)", source, !busy, single = false) { source = it }
            Action(if (selectedId == null) "Save new connection" else "Save connection changes",
                !busy && !stale && name.isNotBlank() && source.isNotBlank() && (selectedId == null || profileDirty)) {
                val before = loaded
                val src = source.trim()
                val draft = (before ?: ConnectionEntity(name = name.trim())).copy(name = name.trim(), kind = connectionStoredKind(kind),
                    notes = notes, aliases = aliases, relationship = relationship, imageBase64 = image)
                runLocal {
                    val receipt = records.saveEntity(draft, expectedRevision = before?.revision, source = src)
                    loaded = draft.copy(revision = receipt.resultingRevision, source = src, updatedAt = receipt.createdAt)
                    selectedId = draft.id; source = src; name = draft.name
                    "Connection saved locally (revision ${receipt.resultingRevision})."
                }
            }
        }
        val connectionId = selectedId
        if (connectionId != null) {
            ExpandableRecordSection("Relationship context") {
                Text("Record player-known agreements, promises, favors, conflicts and shared interests with their evidence. New entries append to the record.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                facts.filter { it.entityId == connectionId }.forEach { fact ->
                    Text("${fact.category} · ${fact.evidenceStatus}", color = Frost,
                        style = MaterialTheme.typography.labelLarge)
                    Text(fact.detail)
                    Text("Source: ${fact.source}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                ConnectionChoice("Context category", listOf("Known information", "Agreements", "Promises", "Favors", "Conflicts", "Shared interests"), factCategory, !busy) { factCategory = it }
                ConnectionChoice("Context evidence", listOf("Unverified", "Confirmed", "Proposed", "Disputed"), factEvidence, !busy) { factEvidence = it }
                ConnectionField("Known context / agreement / promise", factDetail, !busy, single = false) { factDetail = it }
                ConnectionField("Context source (required)", factSource, !busy, single = false) { factSource = it }
                Action("Append relationship context", !busy && factDetail.isNotBlank() && factSource.isNotBlank()) {
                    val entry = ConnectionFact(entityId = connectionId, detail = factDetail.trim(), category = factCategory,
                        evidenceStatus = factEvidence)
                    val src = factSource.trim()
                    runLocal {
                        records.saveFact(entry, source = src)
                        factDetail = ""; factSource = ""
                        "Relationship context saved locally."
                    }
                }
            }
            ExpandableRecordSection("Meaningful milestones") {
                Text("Append what happened and why it mattered. Existing milestones remain in history; corrections should be new, explicitly identified entries.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                val entries = milestones.filter { it.entityId == connectionId }
                if (entries.isEmpty()) Text("No milestones recorded.", color = Muted)
                entries.forEach { milestone ->
                    Text(milestone.title, color = Brass)
                    if (milestone.detail.isNotBlank()) Text(milestone.detail)
                    Text("Source: ${milestone.source}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                ConnectionField("Milestone title", milestoneTitle, !busy) { milestoneTitle = it }
                ConnectionField("What happened / significance", milestoneDetail, !busy, single = false) { milestoneDetail = it }
                ConnectionField("Milestone source (required)", milestoneSource, !busy, single = false) { milestoneSource = it }
                Action("Append milestone", !busy && milestoneTitle.isNotBlank() && milestoneSource.isNotBlank()) {
                    val entry = ConnectionMilestone(entityId = connectionId, title = milestoneTitle.trim(), detail = milestoneDetail)
                    val src = milestoneSource.trim()
                    runLocal {
                        records.saveMilestone(entry, source = src)
                        milestoneTitle = ""; milestoneDetail = ""; milestoneSource = ""
                        "Milestone saved locally."
                    }
                }
            }
            ExpandableRecordSection("Related inventory & inventions") {
                val related = links.filter { (it.fromType == "connection" && it.fromId == connectionId) ||
                    (it.toType == "connection" && it.toId == connectionId) }
                if (related.isEmpty()) Text("No related records yet.", color = Muted)
                related.forEach { link ->
                    val outgoing = link.fromType == "connection" && link.fromId == connectionId
                    val targetType = if (outgoing) link.toType else link.fromType
                    val targetId = if (outgoing) link.toId else link.fromId
                    val targetName = when (targetType) {
                        "item" -> items.firstOrNull { it.id == targetId }?.title
                        "project" -> projects.firstOrNull { it.id == targetId }?.title
                        else -> null
                    }
                    Text("${link.label}: ${targetName ?: targetType}", color = Frost)
                    Text("Stable record: $targetId", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("Source: ${link.source}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                Text("Links reference existing records. They do not transfer ownership, create duplicates or apply mechanics.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                ConnectionChoice("Related record type", listOf("Inventory item", "Invention"), linkType, !busy) {
                    linkType = it; linkTargetId = null
                }
                val targets = if (linkType == "Inventory item") items.map { it.id to it.title }
                    else projects.map { it.id to it.title }
                if (targets.isEmpty()) Text("Create a local ${if (linkType == "Inventory item") "inventory item" else "invention"} before linking it.", color = Muted)
                else ConnectionTargetChoice(targets, linkTargetId, !busy) { linkTargetId = it }
                ConnectionField("Relationship to this record", linkLabel, !busy) { linkLabel = it }
                ConnectionField("Link source (required)", linkSource, !busy, single = false) { linkSource = it }
                Action("Link existing record", !busy && linkTargetId != null && linkLabel.isNotBlank() && linkSource.isNotBlank()) {
                    val targetId = linkTargetId ?: return@Action
                    val targetType = if (linkType == "Inventory item") "item" else "project"
                    val entry = CampaignLink(fromType = "connection", fromId = connectionId,
                        toType = targetType, toId = targetId, label = linkLabel.trim())
                    val src = linkSource.trim()
                    runLocal {
                        records.saveLink(entry, source = src)
                        linkTargetId = null; linkLabel = ""; linkSource = ""
                        "Related record linked locally."
                    }
                }
            }
            ExpandableRecordSection("Revision & event history") {
                Text("Profile revisions and appended entries retain their sources. History is separate from weapon roll history.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                val factIds = facts.filter { it.entityId == connectionId }.map { it.id }.toSet()
                val milestoneIds = milestones.filter { it.entityId == connectionId }.map { it.id }.toSet()
                val linkIds = links.filter { (it.fromType == "connection" && it.fromId == connectionId) ||
                    (it.toType == "connection" && it.toId == connectionId) }.map { it.id }.toSet()
                val history = audit.filter { event ->
                    when (event.recordType) {
                        "connection" -> event.recordId == connectionId
                        "fact" -> event.recordId in factIds
                        "milestone" -> event.recordId in milestoneIds
                        "link" -> event.recordId in linkIds
                        else -> false
                    }
                }
                if (history.isEmpty()) Text("No saved events for this connection yet.", color = Muted)
                history.take(historyLimit).forEach { event ->
                    Text("${event.recordType.replaceFirstChar { it.uppercase() }} · ${event.kind} · revision ${event.priorRevision} → ${event.resultingRevision}",
                        color = Frost, style = MaterialTheme.typography.labelLarge)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.createdAt)),
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("Source: ${event.source}", style = MaterialTheme.typography.bodySmall)
                    Text("Record: ${event.recordId}\nCommand: ${event.commandId}",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                if (history.size > historyLimit) OutlinedButton(onClick = { historyLimit += 40 }, enabled = !busy) {
                    Text("Show more history (${history.size - historyLimit} remaining)")
                }
            }
        }
    }
}

@Composable
private fun ConnectionField(label: String, value: String, enabled: Boolean, single: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = single,
        shape = androidx.compose.foundation.shape.CutCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brass,
            unfocusedBorderColor = Brass.copy(alpha = .3f), focusedContainerColor = Color(0xFF11161A),
            unfocusedContainerColor = Color(0xFF101417)))
}

@Composable
private fun ConnectionChoice(label: String, options: List<String>, selected: String, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box {
        MetalButton(selected, enabled = enabled, modifier = Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; change(option) }) }
        }
    }
}

@Composable
private fun ConnectionTargetChoice(options: List<Pair<String, String>>, selected: String?, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text("Existing record", style = MaterialTheme.typography.labelLarge)
    Box {
        MetalButton(options.firstOrNull { it.first == selected }?.second ?: "Choose an existing record",
            enabled = enabled, modifier = Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Column { Text(title); Text(id.take(8), style = MaterialTheme.typography.bodySmall, color = Muted) } },
                    onClick = { expanded = false; change(id) })
            }
        }
    }
}
