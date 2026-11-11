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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Navigation only: opening a search result never approves mechanics or executes gameplay. */
sealed interface ReforgeSearchDestination {
    data class Character(val profileId: String, val section: String = "Sheet", val detailDialog: String? = null,
        val companion: Boolean = false) : ReforgeSearchDestination
    data class Companion(val profileId: String) : ReforgeSearchDestination
    data class CampaignItem(val itemId: String) : ReforgeSearchDestination
    data class Connection(val entityId: String) : ReforgeSearchDestination
    data class Invention(val projectId: String) : ReforgeSearchDestination
}

internal enum class ReforgeSearchOrigin { PLAYABLE, CAMPAIGN, REFERENCE }
internal data class ReforgeSearchEntry(
    val document: ReforgeSearchDocument,
    val sourceId: String,
    val origin: ReforgeSearchOrigin,
    val context: String,
    val destination: ReforgeSearchDestination? = null
)

// IDs are opaque. Length-prefixed segments prevent colons in imported IDs from
// joining an unrelated actor or record; destinations retain the exact originals.
private fun searchIdentity(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

internal fun reforgeSearchCatalog(
    items: List<LedgerItem> = emptyList(), people: List<ConnectionEntity> = emptyList(),
    projects: List<InventionProject> = emptyList(), profiles: List<CharacterProfile> = emptyList(),
    sources: List<CampaignReferenceRecord> = emptyList(), characters: List<ReforgeSavedCharacter> = emptyList(),
    companions: List<CompanionState> = emptyList()
): List<ReforgeSearchEntry> = buildList {
    val companionIds = partyRoster(profiles, companions).map { it.id }.filterNot { it == "local-player" }.toSet()
    fun addEntry(namespace: String, id: String, kind: String, title: String, body: String,
        origin: ReforgeSearchOrigin, context: String, destination: ReforgeSearchDestination? = null,
        ownerId: String = "") {
        add(ReforgeSearchEntry(ReforgeSearchDocument(searchIdentity(namespace, ownerId, id), kind, title, body),
            id, origin, context, destination))
    }
    items.filterNot { it.archived }.forEach {
        addEntry("campaign-item", it.id, "Item", it.title, "${it.description}\n${it.knownProperties}\n${it.limitations}",
            ReforgeSearchOrigin.CAMPAIGN, "Campaign inventory · ${it.evidenceStatus}", ReforgeSearchDestination.CampaignItem(it.id))
    }
    people.forEach {
        addEntry("connection", it.id, it.kind, it.name, "${it.relationship}\n${it.notes}",
            ReforgeSearchOrigin.CAMPAIGN, "Campaign connection", ReforgeSearchDestination.Connection(it.id))
    }
    projects.forEach {
        addEntry("invention", it.id, "Invention", it.title, "${it.status}\n${it.description}",
            ReforgeSearchOrigin.CAMPAIGN, "Crafting project · ${it.status}", ReforgeSearchDestination.Invention(it.id))
    }
    profiles.forEach {
        addEntry("profile-source", it.id, if (it.id in companionIds) "Companion source" else "Character source",
            it.name.ifBlank { "Unnamed character" }, it.level?.let { level -> "Level $level" }.orEmpty(),
            ReforgeSearchOrigin.CAMPAIGN, "Saved campaign profile · original source",
            if (it.id in companionIds) ReforgeSearchDestination.Companion(it.id) else null)
    }
    // Some historical companions have status records but no character profile.
    companions.filter { state -> profiles.none { it.id == state.companionId } }.forEach {
        addEntry("companion-source", it.companionId, "Companion source", it.name,
            "${it.condition}\n${it.notes}", ReforgeSearchOrigin.CAMPAIGN, "Saved companion record",
            ReforgeSearchDestination.Companion(it.companionId))
    }
    sources.forEach {
        addEntry("reference", it.id, it.category.ifBlank { "Reference" }, it.title, it.body,
            ReforgeSearchOrigin.REFERENCE, "Source reference · approval not inferred")
    }
    characters.filterNot { it.character.archived }.forEach { saved ->
        val c = saved.character
        val companion = c.id in companionIds
        fun route(section: String = "Sheet", dialog: String? = null) =
            ReforgeSearchDestination.Character(c.id, section, dialog, companion)
        fun characterEntry(namespace: String, id: String, kind: String, title: String, body: String,
            destination: ReforgeSearchDestination? = null) =
            addEntry(namespace, id, kind, title, body, ReforgeSearchOrigin.PLAYABLE,
                "${c.name} · saved playable record", destination, c.id)
        characterEntry("playable", c.id, if (companion) "Playable companion" else "Playable character", c.name, "Level ${c.level}", route())
        c.spells.forEach { characterEntry("spell", it.id, "Spell", it.name,
            "${it.castingTime} · ${it.range} · ${it.duration}\n${it.description}", route("Spells", "InspectSpell:${it.id}")) }
        c.attacks.forEach { characterEntry("attack", it.id, "Attack", it.name,
            "${it.actionType} · ${it.range}", route("Combat", "Attack:${it.id}")) }
        c.items.forEach { instance ->
            c.itemDefinitions.firstOrNull { it.id == instance.definitionId }?.let { definition ->
                characterEntry("equipment", instance.id, "Equipment", definition.name,
                    "${instance.state} · Quantity ${instance.quantity}\n${definition.description}", route("Equipment", "InspectItem:${instance.id}"))
            }
        }
        // Keep unused definitions searchable without pretending that they are owned items.
        c.itemDefinitions.filter { definition -> c.items.none { it.definitionId == definition.id } }.forEach {
            characterEntry("equipment-definition", it.id, "Equipment definition", it.name, it.description)
        }
        c.resources.forEach { characterEntry("resource", it.id, "Resource", it.name,
            "${it.current ?: "Unknown"}/${it.maximum ?: "Unknown"} · ${it.recharge}", route("Resources", "Resource:${it.id}")) }
        c.features.forEach { characterEntry("feature", it.id, "Feature", it.name,
            "${it.actionType}\n${it.description}", route("Features")) }
    }
}

/** Ambiguous cross-store links stay unresolved instead of selecting another record by accident. */
internal fun reforgeSearchLinkedEntry(entries: List<ReforgeSearchEntry>, from: ReforgeSearchEntry, id: String): ReforgeSearchEntry? {
    val matches = entries.filter { it.sourceId == id }
    return matches.filter { it.origin == from.origin }.singleOrNull() ?: matches.singleOrNull()
}

/** Background search can finish after a record has been removed or archived. */
internal fun reforgeCurrentSearchHits(hits: List<ReforgeSearchHit>, entries: Map<String, ReforgeSearchEntry>) =
    hits.mapNotNull { hit -> entries[hit.document.id]?.let { hit to it } }

private data class SearchCampaignRecords(val items: List<LedgerItem>, val people: List<ConnectionEntity>,
    val projects: List<InventionProject>, val profiles: List<CharacterProfile>, val sources: List<CampaignReferenceRecord>)

/** Player-known local search. No network calls or external search service. */
@Composable fun ReforgeGlobalSearch(repository: CampaignLedgerRepository, open: ((ReforgeSearchDestination) -> Unit)? = null) {
    val context = LocalContext.current
    val reforge = remember(context.applicationContext) { ReforgeRepository.open(context) }
    val entriesFlow = remember(repository, reforge) {
        val legacy = combine(repository.items, repository.records.entities, repository.records.projects,
            repository.records.characterProfiles, repository.archive.references, ::SearchCampaignRecords)
        combine(legacy, reforge.observeAll(), repository.records.companionStates) { records, characters, companions ->
            reforgeSearchCatalog(records.items, records.people, records.projects, records.profiles, records.sources, characters, companions)
        }.flowOn(Dispatchers.Default)
    }
    val entries by entriesFlow.collectAsState(initial = emptyList())
    ReforgeSearchResults(entries, open)
}

@Composable internal fun ReforgeSearchResults(entries: List<ReforgeSearchEntry>, open: ((ReforgeSearchDestination) -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val documents = remember(entries) { entries.map { it.document } }
    val byId = remember(entries) { entries.associateBy { it.document.id } }
    val hits by produceState<List<ReforgeSearchHit>>(emptyList(), documents, query) {
        value = withContext(Dispatchers.Default) { ReforgeSearchIndex.search(documents, query) }
    }
    val currentHits = remember(hits, byId) { reforgeCurrentSearchHits(hits, byId) }
    val selected = byId[selectedKey]
    fun select(entry: ReforgeSearchEntry) {
        val target = entry.destination
        if (target != null && open != null) open(target) else selectedKey = entry.document.id
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerHeading("Search everything")
        PlayerSearch(query, { query = it.take(300) }, "Items, people, places, spells…")
        Text(if (query.isBlank()) "Search ${documents.size} saved entries" else "${currentHits.size} matches",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
        if (query.isBlank()) Text("Open a result to continue where it belongs. Source references remain available to read.", color = RebuildPalette.Muted)
        if (query.isNotBlank() && currentHits.isEmpty()) PlayerEmpty("No matching records", "Try fewer words or a different name.", "History")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(currentHits, key = { it.first.document.id }) { (hit, entry) ->
                Column(Modifier.fillMaxWidth().testTag("search-result-${entry.document.id}")
                    .clickable(role = Role.Button, onClickLabel = if (entry.destination != null && open != null) "Open ${hit.document.title}" else "Read ${hit.document.title}") { select(entry) }
                    .padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ArcaneIcon(when (hit.document.kind) {
                            "Item", "Equipment", "Equipment definition" -> "Bag"
                            "Spell" -> "Spells"; "Location" -> "Location"
                            "Playable character", "Playable companion", "Character source", "Companion source" -> "People"
                            else -> "Journal"
                        }, tint = RebuildPalette.Gold)
                        Column(Modifier.weight(1f)) {
                            Text(hit.document.kind, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelSmall)
                            Text(hit.document.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("›", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(entry.context, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelMedium)
                    Text(remember(hit.excerpt) { reforgeSearchSnippet(hit.excerpt) }, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .4f))
                }
            }
        }
    }
    selected?.let { entry ->
        PlayerSheet(entry.document.title, { selectedKey = null }) {
            Text(entry.document.kind, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
            Text(entry.context, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (entry.origin == ReforgeSearchOrigin.REFERENCE)
                Text("Reading a source does not approve it for play or change a character's mechanics.", color = RebuildPalette.GoldBright)
            ReforgeRichText(entry.document.body.ifBlank { "No further details recorded." }, onRecordLink = { id ->
                reforgeSearchLinkedEntry(entries, entry, id)?.let(::select)
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
