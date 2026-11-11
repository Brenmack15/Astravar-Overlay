package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.ReforgePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

internal val genesisQuickActionLabels = linkedMapOf(
    "health" to "Health", "attack" to "Actions", "spells" to "Spells", "inventory" to "Inventory",
    "conditions" to "Conditions", "rest" to "Rest", "resources" to "Resources", "dice" to "Dice"
)

@Composable internal fun GenesisQuickActions(profileId: String, onAction: (String) -> Unit) {
    // Replacing the actor also replaces the collector's initial state. A previous
    // actor's choices must not be briefly actionable while the new flow loads.
    key(profileId) { GenesisProfileQuickActions(profileId, onAction) }
}

@Composable private fun GenesisProfileQuickActions(profileId: String, onAction: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { ReforgePreferences.open(context) }
    val actionFlow = remember(preferences, profileId) { preferences.quickActions(profileId).map { true to it } }
    val loaded by actionFlow.collectAsState(initial = false to ReforgePreferences.DEFAULT_QUICK_ACTIONS)
    val actions = loaded.second
    var customizing by rememberSaveable(profileId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            actions.forEach { action ->
                PlayerTextButton(enabled = loaded.first, onClick = { onAction(action) }, modifier = Modifier.widthIn(min = 80.dp)
                    .testTag("quick-$action")) { Text(genesisQuickActionLabels.getValue(action)) }
            }
            }
            IconButton(enabled = loaded.first, onClick = { customizing = true }, modifier = Modifier.testTag("quick-customize")
                .semantics { contentDescription = "Customize quick actions" }) {
                ArcaneIcon("Settings", Modifier.size(20.dp), RebuildPalette.Muted)
            }
        }
    }
    if (customizing && loaded.first) key(profileId) {
        GenesisQuickActionEditor(profileId, actions, preferences) { customizing = false }
    }
}

@Composable private fun GenesisQuickActionEditor(profileId: String, initial: List<String>,
    preferences: ReforgePreferences, dismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    val original = rememberSaveable { initial }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf<String?>(null) }
    var distance by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentDraft by rememberUpdatedState(draft)
    fun move(action: String, direction: Int) {
        val from = draft.indexOf(action)
        val to = from + direction
        if (from >= 0 && to in draft.indices) {
            draft = draft.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    PlayerSheet("Quick actions", dismiss, dirty = draft != original, dismissEnabled = !busy,
        actions = {
            PlayerButton(enabled = !busy && draft.isNotEmpty(), onClick = {
                busy = true; error = ""
                scope.launch {
                    try { preferences.saveQuickActions(profileId, draft); dismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Quick actions could not be saved. Your previous choices are retained." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save quick actions") }
        }) {
        Text("Pin up to four actions. Hold a handle and drag to reorder, or use Move up and Move down.", color = RebuildPalette.Muted)
        // All rows stay at stable keyed composition positions while their visual order changes.
        // Drag owns only the handle inside this modal, never the character's paging surface.
        draft.forEach { action -> key(action) {
            Column(Modifier.fillMaxWidth().onSizeChanged { rowHeights[action] = it.height }
                .background(if (dragging == action) RebuildPalette.Active else RebuildPalette.Panel, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).testTag("quick-drag-$action")
                        .semantics { contentDescription = "Drag ${genesisQuickActionLabels.getValue(action)} to reorder" }
                        .pointerInput(action, busy) {
                            if (!busy) detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = action; distance = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDragEnd = { dragging = null; distance = 0f },
                                onDragCancel = { dragging = null; distance = 0f }
                            ) { change, delta ->
                                change.consume()
                                distance += delta.y
                                val threshold = (rowHeights[action] ?: 96).toFloat()
                                val direction = if (distance >= threshold) 1 else if (distance <= -threshold) -1 else 0
                                if (direction != 0) {
                                    val from = currentDraft.indexOf(action)
                                    if (from + direction in currentDraft.indices) {
                                        move(action, direction); distance -= direction * threshold
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else distance = 0f
                                }
                            }
                        }, contentAlignment = Alignment.Center) { Text("≡", color = RebuildPalette.Gold) }
                    Text(genesisQuickActionLabels.getValue(action), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    PlayerTextButton(enabled = !busy && draft.size > 1, onClick = { draft = draft - action },
                        modifier = Modifier.testTag("quick-remove-$action").semantics {
                            contentDescription = "Remove ${genesisQuickActionLabels.getValue(action)} quick action"
                        }) { Text("Remove") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PlayerTextButton(enabled = !busy && draft.indexOf(action) > 0, onClick = { move(action, -1) },
                        modifier = Modifier.semantics { contentDescription = "Move ${genesisQuickActionLabels.getValue(action)} up" }) { Text("Move up") }
                    PlayerTextButton(enabled = !busy && draft.indexOf(action) < draft.lastIndex, onClick = { move(action, 1) },
                        modifier = Modifier.semantics { contentDescription = "Move ${genesisQuickActionLabels.getValue(action)} down" }) { Text("Move down") }
                }
            }
        } }
        Text("Available actions", style = MaterialTheme.typography.titleMedium)
        genesisQuickActionLabels.filterKeys { it !in draft }.forEach { (id, label) ->
            PlayerOutlinedButton(enabled = !busy && draft.size < ReforgePreferences.MAX_QUICK_ACTIONS, onClick = { draft = draft + id }) { Text("Pin $label") }
        }
        if (draft.size == ReforgePreferences.MAX_QUICK_ACTIONS) Text("Remove an action to pin another.", color = RebuildPalette.Muted)
        PlayerTextButton(enabled = !busy, onClick = { draft = ReforgePreferences.DEFAULT_QUICK_ACTIONS }) { Text("Restore default actions") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
