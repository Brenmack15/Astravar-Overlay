package com.yazsras.astravar.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Astravar 3.1: calm, legible fantasy UI. Art is a supporting detail, never a control. */
object RebuildPalette {
    val Void = Color(0xFF0F0C16)
    val Canvas = Color(0xFF15121F)
    val Panel = Color(0xFF1C1829)
    val Elevated = Color(0xFF252033)
    val Active = Color(0xFF383046)
    val Outline = Color(0xFF504558)
    val Purple = Color(0xFFC7AEDB)
    val Silver = Color(0xFFF1EDF3)
    val GoldShadow = Color(0xFF5D4737)
    val GoldBurnished = Color(0xFFAE8861)
    val Gold = Color(0xFFD7B486)
    val GoldBright = Color(0xFFE9CAA0)
    val GoldGlint = Color(0xFFF6DCB3)
    val BlueDeep = Color(0xFF363049)
    val BlueVivid = Color(0xFF6E6087)
    val BlueAccent = Color(0xFFBD9CD6)
    val Cyan = Color(0xFF99CEC1)
    val Ice = Color(0xFFC4E3D9)
    val Muted = Color(0xFFB5AFBE)
}

object PlayerSpace {
    val Unit = 4.dp
    val Gap = 12.dp
    val Screen = 16.dp
    val Touch = 48.dp
    val Radius = 20.dp
}

@Composable fun PlayerCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: androidx.compose.ui.unit.Dp = 16.dp, spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(PlayerSpace.Radius)
    Column(modifier.fillMaxWidth().clip(shape).background(RebuildPalette.Panel).border(1.dp, RebuildPalette.Outline.copy(alpha = .55f), shape)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

@Composable fun PlayerEntry(
    title: String, detail: String, icon: String, onClick: () -> Unit, trailing: String? = null
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(RebuildPalette.Active, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            ArcaneIcon(icon, Modifier.size(24.dp), RebuildPalette.Ice)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
        Text("›", color = RebuildPalette.Muted, fontSize = 24.sp)
    }
}

val LocalPlayerToolTitle = staticCompositionLocalOf<String?> { null }

@Composable fun PlayerHeading(title: String, action: (@Composable () -> Unit)? = null) {
    val titleInToolbar = title == LocalPlayerToolTitle.current
    if (titleInToolbar && action == null) return
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (titleInToolbar) Spacer(Modifier.weight(1f))
        else Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        action?.invoke()
    }
}

@Composable fun PlayerSearch(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value, onValueChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(placeholder) }, shape = RoundedCornerShape(14.dp),
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Text("×", style = MaterialTheme.typography.titleLarge) } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = RebuildPalette.BlueAccent,
            unfocusedContainerColor = RebuildPalette.Elevated, focusedContainerColor = RebuildPalette.Elevated))
}

@Composable fun PlayerEmpty(title: String, detail: String, icon: String = "Core") {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArcaneIcon(icon, Modifier.size(40.dp), RebuildPalette.Ice)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Forms keep their saveable drafts. All ambient dismissal routes share one discard guard. */
val LocalPlayerDismiss = staticCompositionLocalOf<() -> Unit> { {} }

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlayerSheet(
    title: String, onDismiss: () -> Unit, dirty: Boolean = false, showCancel: Boolean = dirty,
    dismissEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val close = { if (dismissEnabled) { if (dirty) discard = true else onDismiss() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalPlayerDismiss provides close) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    color = RebuildPalette.Panel, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) {
                    Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .88f).dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            PlayerTextButton(onClick = close, enabled = dismissEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                        }
                        key(title) { Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                        } }
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End) {
                            if (showCancel) PlayerTextButton(onClick = close, enabled = dismissEnabled) { Text("Cancel") }
                            actions()
                        }
                    }
                }
            }
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") },
                text = { Text("Your unsaved changes will be lost.") },
                confirmButton = { PlayerTextButton(onClick = { discard = false; onDismiss() }) { Text("Discard") } },
                dismissButton = { PlayerTextButton(onClick = { discard = false }) { Text("Keep editing") } })
        }
    }
}

@Composable fun PlayerDisclosure(title: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse $title" else "Expand $title") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "−" else "+", color = RebuildPalette.Ice, fontSize = 24.sp)
        }
        if (expanded) Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        HorizontalDivider(color = RebuildPalette.Active)
    }
}

/** Visual bounds and semantic bounds both satisfy the 48 dp minimum. */
@Composable fun PlayerTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled, content = content)
}

@Composable fun PlayerButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
}
@Composable fun PlayerOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
}
@Composable fun PlayerFilterChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    label: @Composable () -> Unit, enabled: Boolean = true) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, label = label)
}
