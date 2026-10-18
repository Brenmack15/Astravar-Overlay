package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.BuildConfig
import com.yazsras.astravar.data.CharacterAbility
import com.yazsras.astravar.data.RemoteStatCommand
import com.yazsras.astravar.data.RemoteStatProtocol
import com.yazsras.astravar.data.RemoteStatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun remoteAbilityName(key: String) = when (key) {
    "STR" -> "Strength"; "DEX" -> "Dexterity"; "CON" -> "Constitution"
    "INT" -> "Intelligence"; "WIS" -> "Wisdom"; "CHA" -> "Charisma"
    else -> key
}

/** A fictional test inbox. Neither this page nor its review overlay creates a score. */
@Composable
fun RemoteUpdatesPage(repository: RemoteStatRepository, abilities: List<CharacterAbility> = emptyList(), onReview: (String) -> Unit) {
    if (!BuildConfig.DEBUG) return
    val settings by repository.settings.collectAsState(initial = null)
    val commands by repository.commands.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    val enabled = settings?.enabled == true
    fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""; failed = false
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; message = e.message ?: "Offline. Your pending updates are retained." }
            finally { busy = false }
        }
    }
    Page {
        PlayerHeading("Remote Updates")
        PlayerCard {
            Text(if (enabled) "Test enabled" else "Off", style = MaterialTheme.typography.titleLarge,
                color = if (enabled) RebuildPalette.Ice else RebuildPalette.Muted)
            Text("Disposable stat test profile only", style = MaterialTheme.typography.titleMedium)
            Text("Public, unauthenticated fictional channel. Apply or reject each proposal. Your real character sheet stays local.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PlayerButton(onClick = { operation { repository.setEnabled(!enabled) } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("remote-test-toggle")) {
                Text(if (enabled) "Disable remote test" else "Enable remote test")
            }
            if (enabled) {
                Text(settings?.status.orEmpty(), color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                PlayerOutlinedButton(onClick = { operation { repository.refresh() } }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
            } else Text("Pending proposals are retained while disabled.", color = RebuildPalette.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) Text(message, color = if (failed) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (enabled && !settings?.sessionId.isNullOrBlank()) {
            PlayerCard {
                Text("Test session", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(settings!!.sessionId, style = MaterialTheme.typography.bodySmall) }
                PlayerTextButton(onClick = {
                    clipboard.setText(AnnotatedString(settings!!.sessionId)); message = "Test session copied"; failed = false
                }) { Text("Copy test session") }
                Text("Use this session in the ChatGPT test command. It is an isolation label, not a secret or login.",
                    color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                val testAbilities = abilities.filter { it.profileId == RemoteStatProtocol.PROFILE_ID }
                val revisions = listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").associateWith { key ->
                    testAbilities.firstOrNull { it.key == key }?.revision ?: 0L
                }
                Text("Local ability revisions", style = MaterialTheme.typography.titleSmall)
                revisions.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (key, revision) -> Text("$key · r$revision", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Ice) }
                    }
                }
                PlayerTextButton(onClick = {
                    clipboard.setText(AnnotatedString("Astravar fictional stat test\nSession: ${settings!!.sessionId}\n" +
                        "Profile: ${RemoteStatProtocol.PROFILE_ID}\n" + revisions.entries.joinToString(" · ") { "${it.key} revision ${it.value}" }))
                    message = "Fictional test details copied"; failed = false
                }) { Text("Copy test details") }
            }
        }
        val waiting = commands.filter { it.status in listOf("PENDING", "APPLYING", "CONFLICT") }
        if (waiting.isEmpty()) PlayerEmpty("No updates to review", if (enabled) "Waiting for a fictional test command." else "Enable the test to receive proposals.", "History")
        else {
            SectionCaption("Needs your decision")
            waiting.forEach { command ->
                if (enabled) PlayerEntry(remoteAbilityName(command.ability), "${command.commandId.take(8)} · Proposed ${command.proposedScore} · ${if (command.status == "CONFLICT") "Conflict" else "Awaiting confirmation"}",
                    "History", { onReview(command.commandId) }, "Review")
                else PlayerCard {
                    Text("${remoteAbilityName(command.ability)} · proposal retained", style = MaterialTheme.typography.titleMedium)
                    Text("Enable the test to review this proposal.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val decided = commands.filter { it.status in listOf("APPLIED", "REJECTED") }
        if (decided.isNotEmpty()) PlayerDisclosure("Decisions & confirmations") {
            decided.take(20).forEach { command ->
                Text("${remoteAbilityName(command.ability)} · ${command.status.lowercase().replaceFirstChar(Char::titlecase)}",
                    style = MaterialTheme.typography.titleSmall)
                Text("Command ${command.commandId.take(8)}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                Text(if (command.ackStatus == "COMMITTED") "Confirmation delivered" else "Confirmation pending · retry when online",
                    color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                if (command.message.isNotBlank()) Text(command.message, color = RebuildPalette.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Closing this overlay only defers review. Apply and Reject are explicit, distinct decisions. */
@Composable
fun RemoteStatReviewOverlay(command: RemoteStatCommand, current: CharacterAbility?, enabled: Boolean,
    busy: Boolean, error: String = "", onApply: () -> Unit, onReject: () -> Unit, onDismiss: () -> Unit) {
    val revision = current?.revision ?: 0L
    val conflicting = command.status == "CONFLICT" || revision != command.expectedRevision
    PlayerSheet("Review ability update", onDismiss = { if (!busy) onDismiss() }, showCancel = false,
        actions = {
            PlayerOutlinedButton(onClick = onReject, enabled = enabled && !busy && command.status in listOf("PENDING", "CONFLICT"),
                modifier = Modifier.testTag("remote-reject")) { Text("Reject") }
            Spacer(Modifier.width(8.dp))
            PlayerButton(onClick = onApply, enabled = enabled && !busy && !conflicting && command.status == "PENDING",
                modifier = Modifier.testTag("remote-apply")) { Text("Apply") }
        }) {
        Text(remoteAbilityName(command.ability), style = MaterialTheme.typography.headlineSmall, color = RebuildPalette.Ice)
        Text("Disposable stat test profile", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteReviewValue("Current", current?.score?.toString() ?: "Unknown", Modifier.weight(1f))
            RemoteReviewValue("Proposed", command.proposedScore.toString(), Modifier.weight(1f))
        }
        Text("Source: ${command.source}", style = MaterialTheme.typography.bodyMedium)
        Text("Command ${command.commandId.take(8)}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Public fictional test · source is not authenticated", color = RebuildPalette.Muted,
            style = MaterialTheme.typography.bodySmall)
        if (current != null) Text("Separate magical modifier ${if (current.magicModifier >= 0) "+" else ""}${current.magicModifier} is preserved.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (conflicting) Text("This ability changed after the command was prepared. Reject it and request a new command using revision $revision.",
            color = MaterialTheme.colorScheme.error)
        else Text("Local revision $revision", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (!enabled) Text("Remote updates are disabled. No changes can be applied.", color = RebuildPalette.Muted)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun RemoteReviewValue(label: String, value: String, modifier: Modifier) {
    PlayerCard(modifier = modifier, padding = 12.dp) {
        Text(label, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge)
        Text(value, style = if (value == "Unknown") MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
            color = RebuildPalette.Gold)
    }
}
