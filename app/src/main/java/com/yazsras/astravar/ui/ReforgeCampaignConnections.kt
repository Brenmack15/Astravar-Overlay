package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Campaign relationship identity is deliberately separate from playable mechanics. */
@Composable internal fun ReforgeCampaignConnectionsDialog(saved: ReforgeSavedCharacter, local: ReforgeRepository,
    campaign: CampaignLedgerRepository, onDismiss: () -> Unit) {
    val reviewed = remember { saved }
    var identity by remember { mutableStateOf<ReforgeCampaignIdentity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    // Recreation opens a fresh review; consent to a previous snapshot is never restored independently.
    var approved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reviewed.character.id) {
        try { identity = local.inspectCampaignIdentity(campaign, reviewed) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Campaign identity could not be read." }
        finally { loading = false }
    }
    PlayerSheet("Campaign connections", onDismiss, actions = {
        if (identity == null) PlayerButton(enabled = !loading && !busy && approved && error.isBlank(), onClick = {
            busy = true
            scope.launch {
                try { identity = local.createReviewedCampaignIdentity(campaign, reviewed) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Campaign identity was not created. Reopen to review its current status." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Creating identity…" else "Create campaign identity") }
    }) {
        Text(reviewed.character.name, style = MaterialTheme.typography.titleLarge)
        if (loading) Text("Checking campaign identity…", color = RebuildPalette.Muted)
        val current = identity
        if (current != null) {
            Text("Campaign identity ready", color = RebuildPalette.Ice)
            Text("${current.profile.name.ifBlank { "Unnamed character" }} · Character", style = MaterialTheme.typography.titleMedium)
            Text("Select this Character in a companion's Relationships or in Campaign → Connections. Both records keep their original identity.", color = RebuildPalette.Muted)
            Text("Playable sheet edits and campaign profile edits remain separate. Names and gameplay values are not automatically synchronized.", color = RebuildPalette.Muted)
            current.copiedFromRevision?.let { Text("Name reviewed from playable revision $it. The immutable creation receipt is retained.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted) }
        } else if (!loading) {
            Text("Create a name-only campaign identity so this new character appears in relationship selectors.", color = RebuildPalette.Muted)
            Text("Only the reviewed name and stable identity are copied. Campaign HP, level, abilities and resources stay unknown. Your playable sheet and its history stay unchanged.", color = RebuildPalette.Muted)
            Toggle("I reviewed this name-only campaign identity", approved) { approved = it }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
