package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.PublicSandbox
import com.yazsras.astravar.data.SandboxSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** No free-form public payload: only prewritten fictional test milestones can be sent. */
@Composable
fun SandboxSyncPage(repository: SandboxSyncRepository) {
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = null)
    val origin = settings?.origin ?: PublicSandbox.DEFAULT_ORIGIN
    val endpoint by remember(origin) { repository.endpoint(origin) }.collectAsState(initial = null)
    val commands by remember(origin) { repository.commands(origin) }.collectAsState(initial = emptyList())
    val events by remember(origin) { repository.events(origin) }.collectAsState(initial = emptyList())
    var url by remember(origin) { mutableStateOf(origin) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("new_fact") }
    var confirming by remember { mutableStateOf(false) }
    val pending = commands.count { it.status == "PENDING" }
    fun operation(action: suspend () -> Unit) {
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Connection interrupted; retry when online." }
            finally { busy = false }
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Send a fictional public test?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This unauthenticated disposable service is public. It receives only the fixed text below.")
            Text(PublicSandbox.templates.getValue(kind))
            Text("No inventory, Eek, currency, local Connections or Notion records are sent.")
        } },
        confirmButton = { TextButton(onClick = {
            confirming = false
            val selected = kind
            operation { repository.queueSynthetic(selected); repository.retryPending() }
        }) { Text("Confirm fictional test") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
    )
    Page {
        SectionCaption("SYNTHETIC CONNECTION", "PUBLIC DISPOSABLE SANDBOX · NO REAL CAMPAIGN DATA")
        Section("Connection status") {
            Text(endpoint?.status ?: "Not connected · tap Read sandbox to check availability.", color = Brass)
            Text("This is separate from your local campaign and the private DM Bridge database.", color = Muted)
            Text("${PublicSandbox.CAMPAIGN} / ${PublicSandbox.CONNECTION}", style = MaterialTheme.typography.bodySmall)
            Text("Remote revision: ${endpoint?.remoteRevision ?: "unread"} · history cursor: ${endpoint?.cursor ?: 1}")
            Text("$pending pending · ${commands.count { it.status == "COMMITTED" }} verified receipts · ${events.size} cached milestones")
            if (message.isNotBlank()) Text(message, color = Frost)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { operation { repository.refresh() } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Working…" else "Read sandbox")
                }
                OutlinedButton(onClick = { operation { repository.retryPending() } }, enabled = !busy && pending > 0, modifier = Modifier.weight(1f)) {
                    Text("Retry pending")
                }
            }
            Text("Pending commands survive app closure. A timeout is not proof of failure; retry uses the same UUID and receipt lookup.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Section("Fictional milestone test") {
            Choices("Choose a fixed test", PublicSandbox.templates.keys.toList(), kind) { if (!busy) kind = it }
            Text(PublicSandbox.templates.getValue(kind))
            Action("Review fictional test", !busy && pending == 0 && endpoint?.remoteRevision != null) { confirming = true }
            Text("No login, key, subscription or automatic polling. Public sandbox availability is not guaranteed.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Section("Verified history and receipts") {
            if (events.isEmpty()) Text("No remote history cached yet.", color = Muted)
            events.take(50).forEach { event ->
                Text("r${event.resultingRevision} · ${event.kind}", color = Brass)
                Text(event.detail)
                Text("Event ${event.eventId}\nCommand ${event.commandId}\n${event.recordedAt}",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                HorizontalDivider()
            }
            commands.take(10).forEach { command ->
                Text("${command.status} · ${command.commandId}", style = MaterialTheme.typography.bodySmall)
                if (command.lastError.isNotBlank()) Text(command.lastError, color = Muted)
                command.receiptJson?.let { receipt ->
                    Text("Original immutable receipt: $receipt", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                HorizontalDivider()
            }
        }
        Section("Sandbox address") {
            Field("Public HTTPS workers.dev origin", url, { if (!busy) url = it })
            OutlinedButton(onClick = { operation { repository.configure(url); message = "Address saved; tap Read sandbox." } }, enabled = !busy) {
                Text("Save sandbox address")
            }
            Text("Only an origin is accepted. History and pending commands stay pinned to the original address and never move when this setting changes.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
