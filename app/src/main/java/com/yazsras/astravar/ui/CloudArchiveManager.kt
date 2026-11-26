package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

private fun JsonObject.archiveText(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.archiveRows(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

internal data class ArchiveChoice(
    val id: String, val kind: String, val revision: Long, val title: String, val life: String, val legacyReceipts: Int
) {
    companion object {
        fun reviewed(listed: JsonObject, inspection: JsonObject): ArchiveChoice {
            val row = inspection.getValue("record").jsonObject
            val id = row.cloudText("id")
            val kind = row.cloudText("kind")
            val life = row.cloudText("life")
            val revision = row.getValue("revision").jsonPrimitive.long
            require(cloudIdentifier.matches(id) && cloudIdentifier.matches(kind) && life in setOf("active", "archived") &&
                revision > 0 && id == listed.cloudText("id") && kind == listed.cloudText("kind") &&
                life == listed.cloudText("life") && revision == listed.getValue("revision").jsonPrimitive.long)
            val legacy = inspection.getValue("legacyImmutableReceipts").jsonPrimitive.int
            require(legacy >= 0)
            return ArchiveChoice(id, kind, revision, row.cloudText("title"), life, legacy)
        }
    }
}

internal fun archiveCommandArgs(
    operation: String, choices: List<ArchiveChoice>, externalAccepted: Boolean = false, legacyAccepted: Boolean = false
): JsonObject {
    require(choices.size in 1..24 && choices.distinctBy { it.id }.size == choices.size)
    when (operation) {
        "archive_records" -> require(choices.all { it.life == "active" })
        "restore_records" -> require(choices.all { it.life == "archived" })
        "permanently_delete_records" -> require(externalAccepted && (choices.none { it.legacyReceipts > 0 } || legacyAccepted))
        else -> error("Invalid lifecycle operation")
    }
    return buildJsonObject {
        put("records", buildJsonArray {
            choices.forEach { record -> add(buildJsonObject {
                put("id", record.id); put("kind", record.kind); put("revision", record.revision)
            }) }
        })
        if (operation == "permanently_delete_records") {
            put("confirmation", "PERMANENT_DELETE")
            put("acknowledgeExternalBackups", true)
            if (choices.any { it.legacyReceipts > 0 }) put("acknowledgeLegacyReceipts", true)
        }
    }
}

@Composable
internal fun CloudArchiveManager(
    client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean,
    openOperation: (String, JsonObject) -> Unit, onDismiss: () -> Unit
) {
    var mode by remember(snapshot.binding) { mutableStateOf("Archived") }
    var query by remember(snapshot.binding) { mutableStateOf("") }
    var kind by remember(snapshot.binding) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var kinds by remember(snapshot.version) { mutableStateOf(emptyList<String>()) }
    var allowed by remember(snapshot.version) { mutableStateOf(emptySet<String>()) }
    var chosen by remember(snapshot.binding, snapshot.version, online, mode) { mutableStateOf(emptyList<ArchiveChoice>()) }
    var candidate by remember(snapshot.binding, snapshot.version, online, mode) { mutableStateOf<JsonObject?>(null) }
    var deleting by remember(snapshot.binding, snapshot.version, online, mode) { mutableStateOf(false) }
    var permissionError by remember(snapshot.version, online) { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot.binding, snapshot.version, online) {
        allowed = emptySet()
        if (online && snapshot.visible) {
            try {
                val caps = client.capabilities()
                if (caps.cloudText("continuityId") != snapshot.binding.continuity ||
                    caps.cloudText("subject") != snapshot.binding.subject ||
                    caps.getValue("grantRevision").jsonPrimitive.long != snapshot.version.grantRevision) {
                    throw OwnerSyncFailure("SNAPSHOT_CHANGED")
                }
                kinds = caps.getValue("kinds").jsonArray.map { it.jsonPrimitive.content }.sorted()
                allowed = caps.archiveRows("operations").map { it.cloudText("name") }.toSet()
                permissionError = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { permissionError = cloudError(e) }
        }
    }
    fun request(operation: String, external: Boolean = false, legacy: Boolean = false) {
        if (!online || !snapshot.visible || operation !in allowed) return
        val args = archiveCommandArgs(operation, chosen, external, legacy)
        onDismiss()
        openOperation(operation, args)
    }
    PlayerSheet(
        title = when { deleting -> "Review permanent deletion"; candidate != null -> "Record review"; else -> "Archive Manager" },
        onDismiss = { when { deleting -> deleting = false; candidate != null -> candidate = null; else -> onDismiss() } },
        scrollResetKey = candidate?.archiveText("id") ?: if (deleting) "delete" else mode
    ) {
        if (!snapshot.visible) { Text("Campaign access needs verification."); return@PlayerSheet }
        if (!online) Text("Offline. Reconnect to inspect the archive or request a change.")
        permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (deleting) {
            ArchiveDeleteReview(chosen, online && "permanently_delete_records" in allowed,
                cancel = { deleting = false }, proceed = { external, legacy -> request("permanently_delete_records", external, legacy) })
            return@PlayerSheet
        }
        val inspecting = candidate
        if (inspecting != null) {
            ArchiveInspector(client, snapshot, inspecting, chosen, online,
                back = { candidate = null }, choose = { selected ->
                    chosen = if (chosen.any { it.id == selected.id }) chosen.filterNot { it.id == selected.id } else chosen + selected
                    candidate = null
                })
            return@PlayerSheet
        }
        PlayerTabs(listOf("Archived", "Active"), mode, { mode = it })
        OutlinedTextField(value = query, onValueChange = { if (it.length <= 120) query = it },
            label = { Text("Search records") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box {
            PlayerOutlinedButton({ menu = true }, enabled = kinds.isNotEmpty()) { Text(kind?.let(::cloudLabel) ?: "All categories") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("All categories") }, onClick = { kind = null; menu = false })
                kinds.forEach { value ->
                    DropdownMenuItem(text = { Text(cloudLabel(value)) }, onClick = { kind = value; menu = false })
                }
            }
        }
        if (chosen.isNotEmpty()) {
            Text("Selected records (${chosen.size})", style = MaterialTheme.typography.titleMedium)
            chosen.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, Modifier.weight(1f))
                    TextButton(onClick = { chosen = chosen.filterNot { it.id == row.id } }) { Text("Remove") }
                }
            }
            val operation = if (mode == "Active") "archive_records" else "restore_records"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerButton({ request(operation) }, Modifier.weight(1f), enabled = online && operation in allowed) {
                    Text(if (mode == "Active") "Review archive" else "Review restore")
                }
                PlayerOutlinedButton({ deleting = true }, Modifier.weight(1f), enabled = online && "permanently_delete_records" in allowed) {
                    Text("Permanent delete")
                }
            }
        }
        key(mode, kind, query, snapshot.binding, snapshot.version, online) {
            ArchiveRows(client, snapshot, online, mode == "Archived", kind, query, chosen, inspect = { candidate = it })
        }
    }
}

@Composable
private fun ArchiveRows(
    client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean, archived: Boolean,
    kind: String?, query: String, chosen: List<ArchiveChoice>, inspect: (JsonObject) -> Unit
) {
    var after by remember { mutableStateOf<String?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(after) {
        loading = true
        try {
            if (archived && !online) return@LaunchedEffect
            if (query.isNotBlank() && after == null) delay(200)
            val page = if (archived) {
                val result = client.projection(snapshot, cloudArchiveSuffix(snapshot.version, kind, query.trim(), after))
                next = result.archiveText("nextAfter")
                result.archiveRows("records")
            } else {
                val result = client.records(after.orEmpty(), kind?.let(::setOf) ?: emptySet(), query = query.trim())
                next = if (result.size == 50) result.last().cloudText("id") else null
                result.filter { it.cloudText("life") == "active" }
            }
            rows = (rows + page).distinctBy { it.cloudText("id") }
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = cloudError(e); next = null }
        finally { loading = false }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (rows.isEmpty() && !loading && error == null && (!archived || online)) Text("No matching records.")
    rows.forEach { row ->
        val selected = chosen.any { it.id == row.cloudText("id") }
        PlayerOutlinedButton({ inspect(row) }, Modifier.fillMaxWidth().testTag("archive-record-"+row.cloudText("id")), enabled = online) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(row.cloudText("title"), style = MaterialTheme.typography.titleSmall)
                Text(cloudLabel(row.cloudText("kind")) + if (selected) " · Selected" else "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    next?.let { cursor -> PlayerOutlinedButton({ after = cursor }, enabled = !loading) { Text("More records") } }
}

@Composable
private fun ArchiveInspector(
    client: CloudCampaignClient, snapshot: CloudSnapshot, listed: JsonObject, chosen: List<ArchiveChoice>, online: Boolean,
    back: () -> Unit, choose: (ArchiveChoice) -> Unit
) {
    var details by remember(listed, snapshot.version) { mutableStateOf<JsonObject?>(null) }
    var choice by remember(listed, snapshot.version) { mutableStateOf<ArchiveChoice?>(null) }
    var showFields by remember(listed) { mutableStateOf(false) }
    var error by remember(listed, snapshot.version) { mutableStateOf<String?>(null) }
    LaunchedEffect(listed, snapshot.version, online) {
        if (!online) return@LaunchedEffect
        try {
            val id = listed.cloudText("id")
            require(cloudIdentifier.matches(id))
            val inspected = client.projection(snapshot, "archive?id=$id")
            choice = ArchiveChoice.reviewed(listed, inspected)
            details = inspected
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = cloudError(e) }
    }
    Text(listed.cloudText("title"), style = MaterialTheme.typography.titleMedium)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val checked = choice
    val verified = details
    if (checked != null && verified != null) {
        Text("${cloudLabel(checked.kind)} · ${cloudLabel(checked.life)} · Revision ${checked.revision}")
        Text(checked.id, style = MaterialTheme.typography.labelSmall)
        if (verified["inUse"]?.jsonPrimitive?.booleanOrNull == true)
            Text("This record has active gameplay dependencies. Resolve them before a lifecycle change.")
        val deps = verified.getValue("dependencies").jsonObject
        val links = deps.archiveRows("links")
        val refs = deps.archiveRows("references")
        if (links.isNotEmpty() || refs.isNotEmpty()) Text("Dependencies", style = MaterialTheme.typography.titleSmall)
        links.forEach { link -> Text("${link.archiveText("fromTitle") ?: link.cloudText("from_id")} · ${cloudLabel(link.cloudText("relation"))} · ${link.archiveText("toTitle") ?: link.cloudText("to_id")}") }
        refs.forEach { row -> Text("${row.archiveText("title") ?: row.cloudText("id")} · ${cloudLabel(row.cloudText("kind"))}") }
        if (checked.legacyReceipts > 0) Text("Earlier immutable evidence contains this record in ${checked.legacyReceipts} transactions.")
        TextButton({ showFields = !showFields }) { Text(if (showFields) "Hide record details" else "Show record details") }
        if (showFields) RecordFields(verified.getValue("record").jsonObject.getValue("data"))
        val selected = chosen.any { it.id == checked.id }
        PlayerButton({ choose(checked) }, enabled = online && (selected || chosen.size < 24)) {
            Text(if (selected) "Remove from selection" else "Select record")
        }
        if (!selected && chosen.size >= 24) Text("A review can include up to 24 records.")
    } else if (online && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    TextButton(back) { Text("Back to records") }
}

@Composable
private fun ArchiveDeleteReview(
    chosen: List<ArchiveChoice>, enabled: Boolean, cancel: () -> Unit, proceed: (Boolean, Boolean) -> Unit
) {
    var external by remember(chosen) { mutableStateOf(false) }
    var legacy by remember(chosen) { mutableStateOf(false) }
    val hasLegacy = chosen.any { it.legacyReceipts > 0 }
    Text("Permanent deletion removes the selected records and their exclusive images from this campaign. The transaction retains its receipt and deletion markers.")
    chosen.forEach { row ->
        Text(row.title, style = MaterialTheme.typography.titleSmall)
        Text("${cloudLabel(row.kind)} · ${row.id} · Revision ${row.revision}", style = MaterialTheme.typography.labelSmall)
    }
    ArchiveConsent("I understand that existing backups and exported copies may retain this content.", external) { external = it }
    if (hasLegacy) ArchiveConsent("I acknowledge that earlier immutable transaction evidence can retain details of these records.", legacy) { legacy = it }
    PlayerButton({ proceed(external, legacy) }, enabled = enabled && external && (!hasLegacy || legacy)) { Text("Review deletion request") }
    TextButton(cancel) { Text("Back to selection") }
}

@Composable
private fun ArchiveConsent(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
