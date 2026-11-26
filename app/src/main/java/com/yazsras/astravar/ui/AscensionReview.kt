package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yazsras.astravar.data.CampaignConnectionClient
import com.yazsras.astravar.data.CampaignConnectionDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun legacyActionableCount(): Int? {
    val context = LocalContext.current.applicationContext
    val db = remember(context) { CampaignConnectionDb.open(context) }
    DisposableEffect(db) { onDispose { db.close() } }
    return remember(db) { db.connection().actionableReviews() }.collectAsState(initial = null).value
}

private fun reviewField(name:String):String = when(name){
    "currentHp" -> "HP"
    "maxHp" -> "maximum HP"
    "temporaryHp" -> "temporary HP"
    else -> cloudLabel(name).lowercase()
}

@Composable internal fun CampaignRequestCard(pending: CampaignPendingView, enabled: Boolean, onDecision: (String, Boolean) -> Unit) {
    PlayerCard {
        Text(pending.operations.firstOrNull()?.type?.replace('_',' ') ?: "Campaign request",
            style = MaterialTheme.typography.titleMedium)
        Text(pending.state.replace('_',' '), color = RebuildPalette.GoldBright)
        val decisionsOpen = !pending.terminalReceipt && pending.state in setOf("PENDING_APPROVAL","PENDING_DELIVERY")
        val current = pending.operations.isNotEmpty() && pending.operations.all {
            it.expectedRevision != null && it.currentRevision == it.expectedRevision && it.proposed.isNotEmpty() && it.previewVerified
        }
        if (decisionsOpen) {
            Row(Modifier.fillMaxWidth()) {
                PlayerButton({ onDecision(pending.commandId,true) }, Modifier.weight(1f), enabled && current) { Text("Verify") }
                PlayerOutlinedButton({ onDecision(pending.commandId,false) }, Modifier.weight(1f), enabled) { Text("Refuse") }
            }
        }
        Text("Requested by ${pending.source.ifBlank { "Unknown" }}")
        if (pending.message.isNotBlank()) Text(pending.message, color = RebuildPalette.Muted)
        pending.operations.forEach { op ->
            Text("${op.type.replace('_',' ')} · ${op.recordId}", style = MaterialTheme.typography.labelLarge)
            op.current.forEach { (name, value) -> Text("Current ${reviewField(name)} · $value") }
            op.proposed.forEach { (name, value) -> Text("Proposed ${reviewField(name)} · $value") }
            Text("Reviewed revision ${op.expectedRevision ?: "unknown"} · current ${op.currentRevision ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        }
        PlayerDisclosure("Technical details") { Text(pending.commandId) }
    }
}

@Composable internal fun LegacyApprovalInbox(dismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val client = remember(context) { CampaignConnectionClient(context) }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<CampaignConnectionView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(client) { onDispose { client.close() } }
    fun read(commandId: String? = null, approve: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (commandId == null) client.snapshot() else client.decide(commandId, approve)
                }
                current = campaignConnectionView(result); error = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "The decision could not be verified. Refresh the request before trying again." }
            finally { busy = false }
        }
    }
    LaunchedEffect(client) { read() }
    PlayerSheet("Notification center", dismiss) {
        val view = current
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (view != null) {
            if (!view.connected) Text("Connection unavailable. Showing requests previously received on this device.")
            val requests = view.pending.filter { !it.terminalReceipt && it.state == "PENDING_APPROVAL" }
            if (requests.isEmpty()) Text("No owner requests in the saved queue.")
            requests.forEach { CampaignRequestCard(it, view.connected && !busy) { id, approve -> read(id, approve) } }
        }
        PlayerTextButton(onClick = { read() }, enabled = !busy) { Text("Refresh") }
    }
}
