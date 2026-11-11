package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class GenesisEditorFeedback(val busy: Boolean = false, val error: String = "")
internal val LocalGenesisEditorFeedback = staticCompositionLocalOf { GenesisEditorFeedback() }

/**
 * One editor language: focused sections, optional detail, explicit review, and
 * the same revision-aware save callback as the caller. This stores navigation
 * only; entity drafts remain with the owning editor and repository.
 */
@Composable internal fun GenesisEditorSheet(
    title: String,
    sections: List<String>,
    creating: Boolean,
    context: String,
    onDismiss: () -> Unit,
    dirty: Boolean,
    saveLabel: String,
    saveEnabled: Boolean = true,
    busy: Boolean = false,
    error: String = "",
    onSave: () -> Unit,
    content: @Composable ColumnScope.(section: Int, select: (Int) -> Unit) -> Unit
) {
    require(sections.size >= 2 && sections.distinct().size == sections.size)
    val persistence = LocalGenesisEditorFeedback.current
    val saving = busy || persistence.busy
    val visibleError = error.ifBlank { persistence.error }
    var step by rememberSaveable(title) { mutableIntStateOf(0) }
    val selected = step.coerceIn(sections.indices)
    val review = sections.lastIndex
    PlayerSheet(title, onDismiss, dirty = dirty, dismissEnabled = !saving,
        scrollResetKey = Triple(title, selected, visibleError), actions = {
            if (selected > 0) PlayerTextButton(enabled = !saving, onClick = { step = selected - 1 }) { Text("Back") }
            if (selected < review) {
                if (selected < review - 1) PlayerTextButton(enabled = !saving, onClick = { step = review }) { Text("Review & save") }
                PlayerOutlinedButton(enabled = !saving, onClick = { step = selected + 1 }) { Text(if (selected == review - 1) "Review & save" else "Next") }
            }
            if (!creating || selected == review) PlayerButton(enabled = saveEnabled && !saving, onClick = onSave) { Text(saveLabel) }
        }) {
        Text(context, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        Text("${selected + 1} of ${sections.size} · ${sections[selected]}", style = MaterialTheme.typography.labelLarge,
            color = RebuildPalette.GoldBright, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        PlayerTabs(sections, sections[selected], { step = sections.indexOf(it) })
        if (visibleError.isNotBlank()) Text(visibleError, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        content(selected) { step = it.coerceIn(sections.indices) }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable internal fun GenesisReviewSection(title: String, onEdit: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { heading() })
            PlayerTextButton(onClick = onEdit) { Text("Edit $title") }
        }
        content()
        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .45f))
    }
}

@Composable internal fun GenesisReviewValue(label: String, value: String, unknown: String = "Not set") {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = RebuildPalette.Muted)
        Text(value.trim().ifBlank { unknown }, style = MaterialTheme.typography.bodyLarge)
    }
}
