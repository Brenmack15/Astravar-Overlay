package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

private data class GenesisInventoryView(
    val entries: List<GenesisInventoryEntry>, val profiles: List<ReforgeSavedCharacter>,
    val activeId: String?, val ownerNames: Map<String, String>, val companionIds: Set<String>
)

/** One projection of saved physical identities. Browsing never copies, approves or mutates records. */
@Composable fun GenesisInventoryHub(repository: CampaignLedgerRepository, local: ReforgeRepository,
    open: (ReforgeSearchDestination) -> Unit, openCampaignRecords: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { ReforgePreferences.open(context) }
    var error by remember { mutableStateOf<String?>(null) }
    val authority = LocalGenesisConnectedEnvironment.current?.authority
    val flow = remember(repository, local, preferences, authority) {
        val campaign = combine(repository.records.characterProfiles, repository.records.companionStates,
            authority?.actorAssociations ?: flowOf(emptyList())) { profiles, states, associations ->
            profiles.associate { it.id to it.name } to partyRoster(profiles, states, associations).map { it.id }.filterNot { it == "local-player" }.toSet()
        }
        combine(local.observeInventory(repository), local.observeAll(), preferences.activeProfileId, campaign) { entries, profiles, selected, records ->
            val active = profiles.filterNot { it.character.archived }
            GenesisInventoryView(entries, profiles,
                ReforgePreferences.chooseProfile(selected, active.map { it.character.id }),
                records.first + profiles.associate { it.character.id to it.character.name }, records.second)
        }.catch { error = "Inventory could not be opened. Your saved records are unchanged. ${it.message.orEmpty()}" }
    }
    val view by flow.collectAsState(initial = null)
    val current = view
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerHeading("Inventory")
            if (error == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening saved items…") }
            else Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
            PlayerOutlinedButton(onClick = openCampaignRecords) { Text("Campaign items") }
        }
    } else GenesisInventoryContent(current.entries, current.profiles, current.activeId, current.ownerNames,
        current.companionIds, open, openCampaignRecords)
}

internal fun genesisInventoryBelongsTo(entry: GenesisInventoryEntry, profileId: String): Boolean =
    entry.playableOwners.any { it.profileId == profileId } || entry.sourcePlacement?.holderProfileId == profileId

internal fun genesisInventoryOwnerRoute(owner: GenesisInventoryOwner, companionIds: Set<String>): ReforgeSearchDestination.Character? =
    if (owner.archived) null else ReforgeSearchDestination.Character(owner.profileId, "Equipment", "InspectItem:${owner.instance.id}", owner.profileId in companionIds)

@Composable internal fun GenesisInventoryContent(entries: List<GenesisInventoryEntry>, profiles: List<ReforgeSavedCharacter>,
    activeProfileId: String?, ownerNames: Map<String, String>, companionIds: Set<String>,
    open: (ReforgeSearchDestination) -> Unit, openCampaignRecords: () -> Unit) {
    val active = profiles.firstOrNull { it.character.id == activeProfileId && !it.character.archived }?.character
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable(activeProfileId) { mutableStateOf(if (active == null) "All items" else "My items") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(entries, active?.id, query, filter, ownerNames) {
        entries.filter { entry ->
            (filter == "All items" || active?.id?.let { genesisInventoryBelongsTo(entry, it) } == true) &&
                (query.isBlank() || listOf(entry.displayName, entry.sourceItem?.category.orEmpty(),
                    entry.sourceItem?.description.orEmpty(), entry.playableOwners.joinToString { it.name },
                    entry.sourcePlacement?.holderProfileId?.let { ownerNames[it] }.orEmpty()).joinToString(" ").contains(query.trim(), true))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerHeading("Inventory") {
                PlayerButton(enabled = active != null, onClick = {
                    active?.let { open(ReforgeSearchDestination.Character(it.id, "Equipment", "Item", it.id in companionIds)) }
                }) { Text("Add item") }
            }
            Text(active?.let { "${it.name}'s equipment and campaign records" }
                ?: "Create or review a character before adding playable equipment.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium)
            PlayerSearch(query, { query = it }, "Find an item or owner")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PlayerTabs(if (active == null) listOf("All items") else listOf("My items", "All items"), filter, { filter = it }) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${visible.size} ${if (visible.size == 1) "item" else "items"}", style = MaterialTheme.typography.labelMedium, color = RebuildPalette.Muted)
                PlayerTextButton(onClick = openCampaignRecords) { Text("Campaign items") }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("genesis-inventory-list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (visible.isEmpty()) item {
                PlayerEmpty(if (query.isNotBlank()) "No matching items" else "No items here yet",
                    if (query.isNotBlank()) "Try another name or owner." else if (filter == "My items") "Show all items to find campaign equipment and review its owner."
                    else "Create equipment or open campaign items to review saved source records.", "Bag")
            }
            items(visible, key = { it.itemId }) { entry ->
                Column(Modifier.fillMaxWidth().testTag("genesis-inventory-item-${entry.itemId}")
                    .clickable(role = Role.Button, onClickLabel = "Inspect ${entry.displayName}") { selectedId = entry.itemId }
                    .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ArcaneIcon("Bag", Modifier.size(28.dp), RebuildPalette.Gold)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(genesisInventoryOwnerLabel(entry, ownerNames), style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                        }
                        Text(entry.currentQuantity?.let { "×$it" } ?: "?", style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Silver)
                    }
                    val status = when (entry.authority) {
                        GenesisInventoryAuthority.PLAYABLE -> "In play"
                        GenesisInventoryAuthority.CAMPAIGN_SOURCE -> "Campaign record"
                        GenesisInventoryAuthority.REVIEW_REQUIRED -> "Ownership needs review"
                    }
                    Text(status + if (entry.reviewIssues.isNotEmpty() && entry.authority != GenesisInventoryAuthority.REVIEW_REQUIRED) " · Check details" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.reviewIssues.isEmpty()) RebuildPalette.Ice else RebuildPalette.GoldBright)
                    HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .4f))
                }
            }
        }
    }
    selectedId?.let { id ->
        val entry = entries.firstOrNull { it.itemId == id }
        PlayerSheet(entry?.displayName ?: "Item unavailable", { selectedId = null }) {
            if (entry == null) {
                PlayerEmpty("This item is no longer in the inventory view", "Reopen the list to review its latest location.")
            } else {
                if (entry.reviewIssues.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().background(RebuildPalette.Elevated, RoundedCornerShape(8.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (entry.authority == GenesisInventoryAuthority.REVIEW_REQUIRED) "Review ownership before moving this item" else "Details to review",
                            style = MaterialTheme.typography.titleMedium, color = RebuildPalette.GoldBright)
                        entry.reviewIssues.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                entry.playableOwners.forEach { owner ->
                    Text(owner.name, style = MaterialTheme.typography.titleMedium)
                    Text("Quantity ${owner.instance.quantity} · ${owner.instance.state.name.lowercase()}${if (owner.instance.attuned) " · attuned" else ""}", color = RebuildPalette.Muted)
                    if (owner.definition.description.isNotBlank()) ReforgeRichText(owner.definition.description)
                    val target = genesisInventoryOwnerRoute(owner, companionIds)
                    if (target != null) PlayerButton(onClick = { selectedId = null; open(target) }) { Text("Open ${owner.name}'s item") }
                    else Text("This owner is archived. Restore the saved sheet before changing this item.", color = RebuildPalette.GoldBright)
                }
                entry.sourceItem?.let { source ->
                    HorizontalDivider(color = RebuildPalette.Outline)
                    Text("Campaign record", style = MaterialTheme.typography.titleMedium)
                    Text("${source.evidenceStatus} · Quantity ${source.quantity ?: "unknown"}", color = RebuildPalette.Muted)
                    Text("Kept for source review. Opening it does not approve effects or replace current equipment.", color = RebuildPalette.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    PlayerOutlinedButton(onClick = { selectedId = null; open(ReforgeSearchDestination.CampaignItem(entry.itemId)) }) { Text("Open campaign record") }
                }
            }
        }
    }
}

private fun genesisInventoryOwnerLabel(entry: GenesisInventoryEntry, names: Map<String, String>): String = when {
    entry.playableOwners.isNotEmpty() -> entry.playableOwners.joinToString { it.name + if (it.archived) " (archived)" else "" }
    entry.sourcePlacement?.holderProfileId != null -> names[entry.sourcePlacement.holderProfileId].orEmpty().ifBlank { "Saved campaign owner" }
    else -> "Owner not assigned"
}
