package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import java.util.UUID

/** Review a frozen source; confirmation never silently replaces it with a fresher proposal. */
@Composable internal fun GenesisCampaignItemPicker(saved: ReforgeSavedCharacter,
    local: ReforgeRepository, campaign: CampaignLedgerRepository, dismiss: () -> Unit) {
    val items by campaign.items.collectAsState(initial = emptyList())
    val placements by campaign.party.placements.collectAsState(initial = emptyList())
    val profiles by local.observeAll().collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var commandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val reviewDraft = rememberReforgeDraft<GenesisInventoryReview?>(null, GenesisInventoryReview.serializer().nullable)
    var review by reviewDraft
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun action(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "The item could not be saved. Previous records are retained." }
            finally { busy = false }
        }
    }
    val preview = review
    PlayerSheet("Add from campaign", dismiss, dismissEnabled = !busy, actions = {
        if (preview != null) PlayerButton(enabled = !busy && reviewDraft.canSave && preview.profileId == saved.character.id, onClick = {
            action {
                check(preview.profileId == saved.character.id) { "The selected character changed. Review the item for its intended owner." }
                local.acceptCampaignItem(campaign, preview, commandId); dismiss()
            }
        }) { Text(if (busy) "Saving…" else "Add reviewed item") }
    }) {
        ReforgeDraftRecovery(reviewDraft)
        Text("For ${saved.character.name}", style = MaterialTheme.typography.titleMedium)
        if (preview == null) {
            PlayerSearch(query, { query = it }, "Find assigned campaign item")
            val assigned = placements.filter { it.holderProfileId == saved.character.id }.map { it.itemId }.toSet()
            val held = profiles.flatMap { it.character.items }.map { it.id }.toSet()
            val available = items.filter { it.id in assigned && it.id !in held && !it.archived &&
                it.quantity != null && it.quantity > 0 && it.itemState !in setOf("Consumed", "Sold", "Lost") }
            val matches = available.filter { it.title.contains(query.trim(), ignoreCase = true) }
            if (matches.isEmpty()) Text("No matching assigned items. Use Campaign records to assign the item's holder first. Items already on a playable sheet are opened or transferred there.", color = RebuildPalette.Muted)
            matches.take(50).forEach { item ->
                PlayerEntry(item.title, "Quantity ${item.quantity} · ${item.evidenceStatus}", "Bag", {
                    action {
                        review = local.reviewCampaignItem(campaign, saved.character.id, item.id)
                        commandId = UUID.randomUUID().toString()
                    }
                })
            }
            if (matches.size > 50) Text("Refine your search to see more assigned items.", color = RebuildPalette.Muted)
        } else {
            Text(preview.definition.name, style = MaterialTheme.typography.titleLarge)
            Text("Quantity ${preview.instance.quantity} · ${preview.source.placement.state}", color = RebuildPalette.GoldBright)
            Text(preview.source.item.description.ifBlank { "No description recorded." })
            Text("Source evidence: ${preview.source.item.evidenceStatus}", color = RebuildPalette.Muted)
            if (preview.instance.containerId != null) Text("Keeps its assigned container.", color = RebuildPalette.Muted)
            Text("This adds reviewed playable equipment with the same physical identity. The campaign source is retained. Attunement and magical effects need their own review; future source edits are not automatically applied.", color = RebuildPalette.Muted)
            PlayerTextButton(enabled = !busy, onClick = { review = null; error = "" }) { Text("Choose another item") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
