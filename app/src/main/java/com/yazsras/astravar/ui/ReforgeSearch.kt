package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Player-known local search. No network calls or external search service. */
@Composable fun ReforgeGlobalSearch(repository: CampaignLedgerRepository) {
    val context = LocalContext.current
    val reforge = remember(context.applicationContext) { ReforgeRepository.open(context) }
    val documentsFlow = remember(repository, reforge) {
        val legacy = combine(repository.items, repository.records.entities, repository.records.projects,
            repository.records.characterProfiles, repository.archive.references) { items, people, projects, profiles, sources ->
            buildList {
                items.filterNot { it.archived }.forEach { add(ReforgeSearchDocument(it.id, "Item", it.title,
                    "${it.description}\n${it.knownProperties}\n${it.limitations}")) }
                people.forEach { add(ReforgeSearchDocument(it.id, it.kind, it.name, "${it.relationship}\n${it.notes}")) }
                projects.forEach { add(ReforgeSearchDocument(it.id, "Invention", it.title, "${it.status}\n${it.description}")) }
                profiles.forEach { add(ReforgeSearchDocument(it.id, "Character", it.name.ifBlank { "Unnamed character" },
                    it.level?.let { level -> "Level $level" }.orEmpty())) }
                sources.forEach { add(ReforgeSearchDocument(it.id, it.category.ifBlank { "Reference" }, it.title, it.body)) }
            }
        }
        combine(legacy, reforge.observeAll()) { documents, characters ->
            documents + characters.filterNot { it.character.archived }.flatMap { saved ->
                val c = saved.character
                buildList {
                    add(ReforgeSearchDocument(c.id, "Character workspace", c.name, "Level ${c.level}"))
                    c.spells.forEach { add(ReforgeSearchDocument("${c.id}:${it.id}", "Spell", it.name,
                        "${it.castingTime} · ${it.range} · ${it.duration}\n${it.description}")) }
                    c.attacks.forEach { add(ReforgeSearchDocument("${c.id}:${it.id}", "Attack", it.name,
                        "${it.actionType} · ${it.range}")) }
                    c.itemDefinitions.forEach { add(ReforgeSearchDocument("${c.id}:${it.id}", "Equipment definition", it.name, it.description)) }
                    c.resources.forEach { add(ReforgeSearchDocument("${c.id}:${it.id}", "Resource", it.name,
                        "${it.current ?: "Unknown"}/${it.maximum ?: "Unknown"} · ${it.recharge}")) }
                }
            }
        }.flowOn(Dispatchers.Default)
    }
    val documents by documentsFlow.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val hits by produceState<List<ReforgeSearchHit>>(emptyList(), documents, query) {
        value = withContext(Dispatchers.Default) { ReforgeSearchIndex.search(documents, query) }
    }
    val selected = documents.firstOrNull { "${it.kind}:${it.id}" == selectedKey }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerHeading("Search everything")
        PlayerSearch(query, { query = it.take(300) }, "Items, people, places, spells…")
        Text(if (query.isBlank()) "Search ${documents.size} saved entries" else "${hits.size} matches",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
        if (query.isBlank()) Text("Search a name or combine words. Your campaign stays on this device.", color = RebuildPalette.Muted)
        if (query.isNotBlank() && hits.isEmpty()) PlayerEmpty("No matching records", "Try fewer words or a different name.", "History")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(hits, key = { "${it.document.kind}:${it.document.id}" }) { hit ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedKey = "${hit.document.kind}:${hit.document.id}" }
                    .padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ArcaneIcon(when (hit.document.kind) { "Item", "Equipment definition" -> "Bag"; "Spell" -> "Spells"; "Location" -> "Location"; "Character", "Character workspace" -> "People"; else -> "Journal" }, tint = RebuildPalette.Gold)
                        Column(Modifier.weight(1f)) {
                            Text(hit.document.kind, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelSmall)
                            Text(hit.document.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("›", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(remember(hit.excerpt) { reforgeSearchSnippet(hit.excerpt) }, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .4f))
                }
            }
        }
    }
    selected?.let { record ->
        PlayerSheet(record.title, { selectedKey = null }) {
            Text(record.kind, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
            ReforgeRichText(record.body.ifBlank { "No further details recorded." }, onRecordLink = { id ->
                documents.firstOrNull { it.id == id }?.let { selectedKey = "${it.kind}:${it.id}" }
            })
        }
    }
}

/** Display-only excerpt. Keep imported markup intact in storage and show readable labels here. */
internal fun reforgeSearchSnippet(raw: String): String {
    val normalized = RecordPresentation.normalize(raw)
    val links = Regex("""!?\[([^\]]*)]\([^)]+\)""").replace(normalized) { it.groupValues[1] }
    return links.replace(Regex("""(?m)^\s{0,3}(?:#{1,6}\s+|>\s*|[-*+]\s+)"""), "")
        .replace("**", "").replace("__", "").replace("`", "")
        .replace(Regex("""\s+"""), " ").trim()
}
