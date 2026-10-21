package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private data class BackupReviewRow(val id: String, val label: String, val source: String, val revision: Long?, val json: () -> String)
private data class BackupReviewCategory(val title: String, val rows: List<BackupReviewRow>)
private inline fun <reified T> reviewRows(values: List<T>, crossinline id: (T) -> String,
    crossinline label: (T) -> String, crossinline source: (T) -> String = { "" },
    crossinline revision: (T) -> Long? = { null }): List<BackupReviewRow> = values.map { value ->
    BackupReviewRow(id(value), label(value), source(value), revision(value)) { campaignJson.encodeToString(value) }
}

/** Read-only inspection of every incoming record. Rendering is paged; inspection changes no authorization or data. */
@Composable internal fun CampaignBackupContentsV21(backup: LedgerExport) {
    val categories = remember(backup) {
        val r = backup.records
        val audits = r.audit.associateBy { it.id }
        listOf(
            BackupReviewCategory("Items", reviewRows(backup.items, { it.id }, { it.title }, { it.source }, { it.revision })),
            BackupReviewCategory("Inventory events", reviewRows(backup.events, { it.commandId }, { it.kind }, { it.source }, { it.resultingRevision })),
            BackupReviewCategory("Character profiles", reviewRows(r.characterProfiles, { it.id }, { it.name.ifBlank { it.id } }, { it.source }, { it.revision })),
            BackupReviewCategory("Ability scores", reviewRows(r.characterAbilities, { it.id }, { "${it.profileId} · ${it.key}" }, { it.source }, { it.revision })),
            BackupReviewCategory("Party sheets", reviewRows(r.partySheets, { it.profileId }, { it.profileId }, { it.source }, { it.revision })),
            BackupReviewCategory("Equipment placements", reviewRows(r.placements, { it.itemId }, { "${it.itemId} · ${it.state}" }, { it.source }, { it.revision })),
            BackupReviewCategory("Companion states", reviewRows(r.companionStates, { it.companionId }, { it.name }, { it.source }, { it.revision })),
            BackupReviewCategory("Companion resources", reviewRows(r.companionResources, { it.id }, { "${it.companionId} · ${it.name}" }, { it.source }, { it.revision })),
            BackupReviewCategory("Wallets", reviewRows(r.wallets, { it.id }, { it.name }, { it.source }, { it.revision })),
            BackupReviewCategory("Checkpoints", reviewRows(r.checkpoints, { it.id }, { it.title }, { it.source }, { it.revision })),
            BackupReviewCategory("External source references", reviewRows(r.externalReferences, { it.id }, { "${it.namespace} · ${it.externalId}" }, { it.source }, { it.revision })),
            BackupReviewCategory("Inventions", reviewRows(r.projects, { it.id }, { it.title }, { it.source }, { it.revision })),
            BackupReviewCategory("Construction stages", reviewRows(r.stages, { it.id }, { it.title }, { it.source }, { it.revision })),
            BackupReviewCategory("Material requirements", reviewRows(r.requirements, { it.id }, { it.label }, { it.source }, { it.revision })),
            BackupReviewCategory("Connections", reviewRows(r.entities, { it.id }, { it.name }, { it.source }, { it.revision })),
            BackupReviewCategory("Connection facts", reviewRows(r.facts, { it.id }, { it.category }, { it.source }, { it.revision })),
            BackupReviewCategory("Relationship milestones", reviewRows(r.milestones, { it.id }, { it.title }, { it.source }, { it.revision })),
            BackupReviewCategory("Item handling", reviewRows(r.itemDetails, { it.itemId }, { it.itemId }, { it.source }, { it.revision })),
            BackupReviewCategory("Item properties", reviewRows(r.itemProperties, { it.id }, { it.label }, { it.source }, { it.revision })),
            BackupReviewCategory("Relationships", reviewRows(r.links, { it.id }, { it.label }, { it.source }, { it.revision })),
            BackupReviewCategory("Craft executions", reviewRows(r.crafts, { it.commandId }, { it.projectId }, { it.source })),
            BackupReviewCategory("Material consumptions", reviewRows(r.consumptions, { it.id }, { it.itemId })),
            BackupReviewCategory("Audit history", reviewRows(r.audit, { it.id }, { "${it.recordType} · ${it.kind}" }, { it.source }, { it.resultingRevision })),
            BackupReviewCategory("Command receipts", reviewRows(r.receipts, { it.commandId }, { "${it.kind} · ${it.recordId}" }, { audits[it.commandId]?.source.orEmpty() }, { it.resultingRevision })),
            BackupReviewCategory("Campaign reference records", reviewRows(backup.archive.references, { it.id }, { it.title }, { it.sourceUrl })),
            BackupReviewCategory("Campaign source relationships", reviewRows(backup.archive.relations, { it.id }, { "${it.kind} · ${it.fromId} → ${it.toId}" })),
            BackupReviewCategory("Campaign import receipts", reviewRows(backup.archive.receipts, { it.packageHash }, { "${it.title} · ${it.state}" })),
            BackupReviewCategory("Campaign recovery snapshots", reviewRows(backup.archive.backups, { it.packageHash }, { "Verified pre-import backup" })),
            BackupReviewCategory("Campaign original source archives", reviewRows(backup.archive.sources, { it.packageHash }, { "Original import payload" }))
        ).filter { it.rows.isNotEmpty() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    Text("Incoming backup · schema ${backup.schema}", style = MaterialTheme.typography.titleMedium)
    Text("Inspect names, original IDs, sources, revisions and all fields before restoring. Unknown values remain unknown. Nothing in this review imports data.", color = RebuildPalette.Muted)
    PlayerSearch(query, { query = it }, "Find a record by name, ID or source")
    val visible = categories.map { category -> category.copy(rows = category.rows.filter {
        query.isBlank() || listOf(it.id, it.label, it.source).any { value -> value.contains(query, true) }
    }) }.filter { it.rows.isNotEmpty() }
    if (visible.isEmpty()) Text(if (query.isBlank()) "No incoming records." else "No matching incoming records.", color = RebuildPalette.Muted)
    visible.forEach { category -> key(category.title) {
        PlayerDisclosure("${category.title} (${category.rows.size})") {
            var limit by rememberSaveable(query) { mutableIntStateOf(40) }
            category.rows.take(limit).forEach { row -> key(row.id) {
                PlayerDisclosure("${row.label.ifBlank { row.id }} · ${row.id.take(12)}") {
                    RecordValue("Original ID", row.id)
                    RecordValue("Source", row.source.ifBlank { "Unknown / no source supplied" })
                    row.revision?.let { RecordValue("Revision", it.toString()) }
                    val fields = remember(row.id, row.revision, row.source) {
                        sanitizeBackupReview(Json.parseToJsonElement(row.json())).jsonObject
                    }
                    fields.forEach { (name, value) -> key(name) { BackupReviewField(name, value) } }
                }
            } }
            if (category.rows.size > limit) PlayerTextButton(onClick = { limit += 40 }) { Text("Show more ${category.title.lowercase()}") }
        }
    } }
}

private val reviewJson = Json { prettyPrint = true }
/** Embedded artwork is inspectable by its exact encoded-content digest without printing megabytes of Base64. */
private fun sanitizeBackupReview(value: JsonElement, field: String = ""): JsonElement = when {
    value is JsonNull -> value
    value is JsonPrimitive && value.isString && field in setOf("imageBase64", "portraitBase64", "base64") -> JsonPrimitive(
        "Embedded content · ${value.content.length} encoded characters · SHA-256 ${campaignHash(value.content)}")
    value is JsonPrimitive && value.isString && field in setOf("contentJson", "beforeJson", "afterJson", "outcomeJson", "payloadJson", "snapshotJson") ->
        runCatching { sanitizeBackupReview(Json.parseToJsonElement(value.content)) }.getOrDefault(value)
    value is JsonObject -> JsonObject(value.mapValues { (key, child) -> sanitizeBackupReview(child, key) })
    value is JsonArray -> JsonArray(value.map { sanitizeBackupReview(it) })
    else -> value
}

@Composable private fun BackupReviewField(name: String, value: JsonElement) {
    val text = remember(value) { when {
        value is JsonNull -> "Unknown (null)"
        value is JsonPrimitive && value.isString -> value.content.ifEmpty { "Empty / not supplied" }
        else -> reviewJson.encodeToString(JsonElement.serializer(), value)
    } }
    var characters by rememberSaveable(name, text) { mutableIntStateOf(4096) }
    RecordValue(name, text.take(characters))
    if (text.length > characters) PlayerTextButton(onClick = { characters = (characters + 4096).coerceAtMost(text.length) }) {
        Text("Show more $name (${text.length - characters} characters remaining)")
    }
}
