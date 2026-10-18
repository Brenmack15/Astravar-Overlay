package com.yazsras.astravar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.PublicSandbox
import com.yazsras.astravar.data.SandboxSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun sandboxKindLabel(kind: String) = when (kind) {
    "new_fact" -> "New fact"
    "agreement" -> "Agreement"
    "shared_interest" -> "Shared interest"
    else -> kind.replace('_', ' ')
}

/** Presentation only. Fixed fictional payloads, command identity and receipt recovery stay in the repository. */
@Composable
fun SandboxSyncPage(repository: SandboxSyncRepository) {
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = null)
    val origin = settings?.origin ?: PublicSandbox.DEFAULT_ORIGIN
    val endpoint by remember(origin) { repository.endpoint(origin) }.collectAsState(initial = null)
    val commands by remember(origin) { repository.commands(origin) }.collectAsState(initial = emptyList())
    val events by remember(origin) { repository.events(origin) }.collectAsState(initial = emptyList())
    var url by rememberSaveable { mutableStateOf(origin) }
    var addressBaseline by rememberSaveable { mutableStateOf(origin) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var failure by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("new_fact") }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = commands.count { it.status == "PENDING" }
    val selectedCommand = commands.firstOrNull { it.commandId == selectedCommandId }
    val selectedEvent = events.firstOrNull { it.eventId == selectedEventId }
    fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""; failure = false
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = true; message = e.message ?: "Connection interrupted; retry when online." }
            finally { busy = false }
        }
    }
    fun closeSheet() {
        if (!busy) mode = when (mode) { "command", "event" -> "history"; else -> null }
    }
    val status = when {
        busy -> "Connecting…"
        endpoint?.status?.startsWith("Remote read verified") == true -> "Connected"
        endpoint?.status?.startsWith("Offline") == true -> "Offline"
        endpoint?.status?.startsWith("Disconnected") == true -> "Disconnected"
        endpoint?.status?.startsWith("Rejected") == true -> "Needs review"
        endpoint?.lastSyncedAt != null -> "Sync paused"
        else -> "Not connected"
    }
    Page {
        PlayerHeading("Fictional sync")
        PlayerCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = RebuildPalette.Ice)
                endpoint?.remoteRevision?.let { Text("Revision $it", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge) }
            }
            Text(endpoint?.lastSyncedAt?.let { "Last updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" }
                ?: "No verified read yet", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (pending > 0) Text("$pending pending · saved on this device", color = RebuildPalette.GoldBright)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Action("Read sandbox", !busy) { operation { repository.refresh() } }
            PlayerOutlinedButton(onClick = { operation { repository.retryPending() } }, enabled = !busy && pending > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retry pending") }
        }
        if (message.isNotBlank()) Text(message, color = if (failure) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val connectionError = endpoint?.status?.takeUnless { it.startsWith("Remote read verified") || it.startsWith("Not connected") }
        if (connectionError != null && message.isBlank() && !busy) Text(connectionError, color = RebuildPalette.Muted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        PlayerButton(onClick = { mode = "test" }, enabled = !busy && pending == 0 && endpoint?.remoteRevision != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review fictional test") }
        Text("Public test data only. Your campaign stays on this device.", color = RebuildPalette.Muted,
            style = MaterialTheme.typography.bodySmall)
        PlayerEntry("History & receipts", "${events.size} cached events · ${commands.count { it.status == "COMMITTED" }} verified commands",
            "History", { mode = "history" })
        PlayerEntry("Sandbox address", origin.removePrefix("https://"), "Settings", { url = origin; addressBaseline = origin; mode = "address" })
    }
    mode?.let { current ->
        val title = when (current) {
            "test" -> "Fictional test"
            "address" -> "Sandbox address"
            "history" -> "History & receipts"
            "command" -> "Command receipt"
            else -> "Fictional event"
        }
        PlayerSheet(title, onDismiss = ::closeSheet, dirty = current == "address" && url != addressBaseline,
            actions = {
                if (current == "address") PlayerButton(enabled = !busy && url != addressBaseline, onClick = {
                    operation { repository.configure(url); message = "Address saved. Read the sandbox to check it."; mode = null }
                }) { Text("Save address") }
                if (current == "test") PlayerButton(enabled = !busy && pending == 0 && endpoint?.remoteRevision != null, onClick = {
                    val selected = kind
                    mode = null
                    operation { repository.queueSynthetic(selected); repository.retryPending() }
                }) { Text("Confirm fictional test") }
            }) {
            when (current) {
                "test" -> {
                    Text("Only this fixed fictional text will be sent to the public sandbox.", color = RebuildPalette.Muted)
                    PublicSandbox.templates.keys.forEach { value ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { kind = value },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = kind == value, onClick = { kind = value }, enabled = !busy)
                            Text(sandboxKindLabel(value), Modifier.weight(1f))
                        }
                    }
                    PlayerCard { Text(PublicSandbox.templates.getValue(kind)) }
                }
                "address" -> {
                    Field("Public HTTPS workers.dev origin", url, { if (!busy) url = it }, enabled = !busy)
                    Text("Pending commands and history remain with their original address.", color = RebuildPalette.Muted)
                }
                "history" -> {
                    if (events.isEmpty() && commands.isEmpty()) PlayerEmpty("No cached history", "Read the sandbox to check for fictional events.", "History")
                    events.take(50).forEach { event ->
                        PlayerEntry(sandboxKindLabel(event.kind), event.detail, "History",
                            { selectedEventId = event.eventId; mode = "event" }, "r${event.resultingRevision}")
                    }
                    if (commands.isNotEmpty()) Text("Commands", style = MaterialTheme.typography.titleMedium)
                    commands.take(10).forEach { command ->
                        PlayerEntry(sandboxKindLabel(command.kind), command.lastError.ifBlank { command.status.lowercase().replaceFirstChar { it.uppercase() } },
                            "History", { selectedCommandId = command.commandId; mode = "command" })
                    }
                    PlayerDisclosure("Connection details") {
                        Text("History cursor: ${endpoint?.cursor ?: 1}")
                        Text("${PublicSandbox.CAMPAIGN}\n${PublicSandbox.CONNECTION}", color = RebuildPalette.Muted)
                        Text("Retries recover the original command receipt before sending again.", color = RebuildPalette.Muted)
                    }
                }
                "event" -> selectedEvent?.let { event ->
                    Text(sandboxKindLabel(event.kind), style = MaterialTheme.typography.titleMedium)
                    Text(event.detail)
                    Text("Revision ${event.priorRevision} → ${event.resultingRevision}", color = RebuildPalette.Ice)
                    Text(event.recordedAt, color = RebuildPalette.Muted)
                    PlayerDisclosure("Identifiers") {
                        Text("Event ${event.eventId}\nCommand ${event.commandId}", style = MaterialTheme.typography.bodySmall)
                    }
                } ?: Text("This event is no longer available for the selected address.")
                "command" -> selectedCommand?.let { command ->
                    Text(command.status, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Ice)
                    Text(command.detail)
                    if (command.lastError.isNotBlank()) Text(command.lastError, color = MaterialTheme.colorScheme.error)
                    Text("Expected revision ${command.expectedRevision}", color = RebuildPalette.Muted)
                    PlayerDisclosure("Immutable receipt", initiallyOpen = command.receiptJson != null) {
                        Text(command.receiptJson ?: "Not verified yet. Retry pending to recover the original receipt.", style = MaterialTheme.typography.bodySmall)
                    }
                    PlayerDisclosure("Command details") {
                        Text("Command ${command.commandId}\nAddress ${command.origin}\nFingerprint ${command.fingerprint}", style = MaterialTheme.typography.bodySmall)
                    }
                } ?: Text("This command is no longer available for the selected address.")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = if (failure) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
