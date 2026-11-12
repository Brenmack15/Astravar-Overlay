package com.yazsras.astravar.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.zIndex

/** Astravar 3.1: calm, legible fantasy UI. Art is a supporting detail, never a control. */
object RebuildPalette {
    val Void = Color(0xFF080F18)
    val Canvas = Color(0xFF0C1521)
    val Panel = Color(0xFF142130)
    val Elevated = Color(0xFF1B2B3D)
    val Active = Color(0xFF263B50)
    val Outline = Color(0xFF41556B)
    val Purple = Color(0xFFC4B2E7)
    val Silver = Color(0xFFF1F3F5)
    val GoldShadow = Color(0xFF4B3826)
    val GoldBurnished = Color(0xFFAC8354)
    val Gold = Color(0xFFDEBA7E)
    val GoldBright = Color(0xFFECD1A1)
    val GoldGlint = Color(0xFFFFE4B5)
    val BlueDeep = Color(0xFF243D57)
    val BlueVivid = Color(0xFF437CA7)
    val BlueAccent = Color(0xFF9ACBFA)
    val Cyan = Color(0xFF88D7BE)
    val Ice = Color(0xFFBADEF5)
    val Muted = Color(0xFFABBAC9)
    val Health = Color(0xFF88D7BE)
    val Spell = Color(0xFF9ACBFA)
    val Danger = Color(0xFFFFA4A4)
}

object PlayerSpace {
    val Unit = 4.dp
    val Gap = 12.dp
    val Screen = 16.dp
    val Touch = 48.dp
    val Radius = 10.dp
}

@Composable fun PlayerCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: androidx.compose.ui.unit.Dp = 16.dp, spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(PlayerSpace.Radius)
    Column(modifier.fillMaxWidth().clip(shape).background(RebuildPalette.Panel).border(1.dp, RebuildPalette.Outline.copy(alpha = .28f), shape)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

@Composable fun PlayerEntry(
    title: String, detail: String, icon: String, onClick: () -> Unit, trailing: String? = null
) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(RebuildPalette.Gold.copy(alpha = .08f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                ArcaneIcon(icon, Modifier.size(23.dp), RebuildPalette.Gold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelLarge)
            Text("›", color = RebuildPalette.Gold, fontSize = 23.sp)
        }
        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .35f))
    }
}

val LocalPlayerToolTitle = staticCompositionLocalOf<String?> { null }

@Composable fun PlayerHeading(title: String, action: (@Composable () -> Unit)? = null) {
    val titleInToolbar = title == LocalPlayerToolTitle.current
    if (titleInToolbar && action == null) return
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (titleInToolbar) Spacer(Modifier.weight(1f))
        else Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, fontFamily = DisplayFace)
        action?.invoke()
    }
}

@Composable fun PlayerSearch(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value, onValueChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(placeholder) }, shape = RoundedCornerShape(8.dp),
        leadingIcon = { ArcaneIcon("Search", tint = RebuildPalette.Muted) },
        textStyle = MaterialTheme.typography.bodyLarge,
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Text("×", style = MaterialTheme.typography.titleLarge) } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = RebuildPalette.Outline.copy(alpha = .5f),
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
    dismissEnabled: Boolean = true, scrollResetKey: Any? = title,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    var titleOverflows by remember(title) { mutableStateOf(false) }
    val close = { if (dismissEnabled) { if (dirty) discard = true else onDismiss() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalPlayerDismiss provides close) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    color = RebuildPalette.Panel, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) {
                    Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .92f).dp)) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(RebuildPalette.Gold, RebuildPalette.Gold.copy(alpha = .06f)))))
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).then(if (titleOverflows) Modifier.clearAndSetSemantics {
                                contentDescription = title; heading()
                            } else Modifier.semantics { heading() }), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold, fontFamily = DisplayFace, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, onTextLayout = { titleOverflows = it.hasVisualOverflow })
                            PlayerTextButton(onClick = close, enabled = dismissEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                        }
                        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .4f))
                        key(scrollResetKey) { Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Keep long names readable without consuming the fixed header or hiding actions.
                                if (titleOverflows) Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                                content()
                            }
                        } }
                        // At enlarged font sizes a late action must not be
                        // measured into the narrow remainder beside Cancel/Back.
                        // Give each control a full-width measuring opportunity;
                        // the content above retains its independent scroll area.
                        FlowRow(Modifier.fillMaxWidth().background(RebuildPalette.Void.copy(alpha = .45f)).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.5f) 1 else Int.MAX_VALUE) {
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
    androidx.compose.material3.Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(8.dp), content = content)
}
@Composable fun PlayerOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(8.dp), content = content)
}
@Composable fun PlayerFilterChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    label: @Composable () -> Unit, enabled: Boolean = true) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, label = label)
}

/** One visible level of section navigation. Horizontal scrolling preserves scaled labels. */
@Composable fun PlayerTabs(labels: List<String>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val direction = LocalLayoutDirection.current
    val positions = remember(labels) { mutableStateMapOf<String, Pair<Float, Float>>() }
    var viewport by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // Reveal the selected label on this horizontal axis only. A nested filter must
    // never pull its surrounding character sheet vertically when it first appears.
    val selectedWidth = positions[selected]?.let { it.second - it.first }
    val viewportWidth = viewport?.let { it.second - it.first }
    LaunchedEffect(selected, selectedWidth, viewportWidth, scroll.maxValue, direction) {
        val bounds = positions[selected]
        val window = viewport
        if (bounds != null && window != null) {
            val delta = when {
                bounds.second - bounds.first >= window.second - window.first -> bounds.first - window.first
                bounds.first < window.first -> bounds.first - window.first
                bounds.second > window.second -> bounds.second - window.second
                else -> 0f
            }
            if (kotlin.math.abs(delta) >= 1f) {
                val logicalDelta = if (direction == LayoutDirection.Rtl) -delta else delta
                scroll.scrollTo((scroll.value + logicalDelta.toInt()).coerceIn(0, scroll.maxValue))
            }
        }
    }
    // Navigation owns its visible strip above adjacent scrolling content. In
    // Compose 1.10, a clipped body control's expanded accessibility touch bounds
    // can otherwise occlude these tabs despite its drawing staying in the page.
    Column(modifier.fillMaxWidth().zIndex(1f)) {
        Row(Modifier.fillMaxWidth().onGloballyPositioned {
            val left = it.positionInRoot().x
            viewport = left to (left + it.size.width)
        }.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEach { label ->
                val active = selected == label
                val tint by animateColorAsState(if (active) RebuildPalette.GoldBright else RebuildPalette.Muted, label = "Section selection")
                Column(Modifier.width(IntrinsicSize.Max).onGloballyPositioned {
                    val left = it.positionInRoot().x
                    positions[label] = left to (left + it.size.width)
                }.testTag("section-$label").semantics { this.selected = active }
                    .clickable(role = Role.Tab) { onSelect(label) }.heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.heightIn(min = 46.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) RebuildPalette.Gold else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .45f))
    }
}
