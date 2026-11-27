package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** The journal, including its original dice and costs, survives leaving this pane or restarting Android. */
@Composable internal fun rememberedUnconfirmedCommand(client: CloudCampaignClient, snapshot: CloudSnapshot,
    operation: String? = null, actorId: String? = null): JsonObject? {
    val events by client.changes.collectAsState()
    var command by remember(client, snapshot.binding, operation, actorId) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(client, snapshot.visible, snapshot.binding, operation, actorId, events) {
        command = if (!snapshot.visible) null else try { client.uncertain(operation, actorId) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    return command
}

@Composable internal fun CloudCommandRecovery(command: JsonObject, enabled: Boolean, retry: (String) -> Unit) {
    val operation = cloudLabel(command.cloudText("operation"))
    Text("$operation · awaiting command verification")
    PlayerOutlinedButton({ retry(command.cloudText("commandId")) }, enabled = enabled) { Text("Retry original $operation") }
}
