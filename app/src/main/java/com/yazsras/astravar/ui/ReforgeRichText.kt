package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.RecordPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native text only: no WebView, executable HTML, JavaScript or implicit network fetch. */
@Composable fun ReforgeRichText(text: String, modifier: Modifier = Modifier, onRecordLink: ((String) -> Unit)? = null) {
    val blocks = if (text.length <= 24_000) remember(text) { RecordPresentation.blocks(text) }
        else {
            val loaded by produceState<Pair<String, List<RecordPresentation.Block>>?>(initialValue = null, key1 = text) {
                value = text to withContext(Dispatchers.Default) { RecordPresentation.blocks(text) }
            }
            // produceState retains its previous value across key changes. A
            // result belongs to its source and must never display for another.
            if (loaded?.first != text) { LinearProgressIndicator(modifier.fillMaxWidth()); return }
            loaded?.second.orEmpty()
        }
    var visibleBlocks by remember(text) { mutableIntStateOf(80) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.take(visibleBlocks).forEach { block ->
            when (block) {
                is RecordPresentation.Block.Heading -> ReforgeBoundedInline(block.text,
                    style = when (block.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium },
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() }, onRecordLink = onRecordLink)
                is RecordPresentation.Block.Paragraph -> ReforgeBoundedInline(block.text, onRecordLink = onRecordLink)
                is RecordPresentation.Block.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, color = RebuildPalette.Ice, modifier = Modifier.widthIn(min = 20.dp))
                    ReforgeBoundedInline(block.text, modifier = Modifier.weight(1f), onRecordLink = onRecordLink)
                }
                is RecordPresentation.Block.Quote -> Row(Modifier.fillMaxWidth().background(RebuildPalette.Active).padding(12.dp)) {
                    ReforgeBoundedInline(block.text, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), onRecordLink = onRecordLink)
                }
                is RecordPresentation.Block.Code -> {
                    var shown by remember(block.text) { mutableIntStateOf(12_000) }
                    SelectionContainer {
                        Text(block.text.take(shown), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().background(RebuildPalette.Void).padding(12.dp))
                    }
                    if (block.text.length > shown) PlayerTextButton(onClick = { shown += 12_000 }) { Text("Show more code") }
                }
                is RecordPresentation.Block.Table -> Column {
                    var visibleRows by remember(block) { mutableIntStateOf(40) }
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                    block.rows.take(visibleRows).forEachIndexed { index, row ->
                        Row(Modifier.background(if (index == 0) RebuildPalette.Active else Color.Transparent)) {
                            row.forEach { cell -> Box(Modifier.width(156.dp).padding(10.dp)) {
                                ReforgeBoundedInline(cell, style = if (index == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium, onRecordLink = onRecordLink)
                            } }
                        }
                        HorizontalDivider(color = RebuildPalette.Active)
                    }
                    }
                    if (block.rows.size > visibleRows) PlayerTextButton(onClick = { visibleRows += 40 }) { Text("Show more table rows") }
                }
                is RecordPresentation.Block.Image -> {
                    val uriHandler = LocalUriHandler.current
                    if (block.url != null) PlayerOutlinedButton(onClick = { runCatching { uriHandler.openUri(block.url) } }) {
                        Text("View image · ${block.alt.ifBlank { "Attached artwork" }}")
                    } else Text(block.alt.ifBlank { "Image unavailable" }, color = RebuildPalette.Muted)
                }
                RecordPresentation.Block.Divider -> HorizontalDivider(color = RebuildPalette.Active)
            }
        }
        if (blocks.size > visibleBlocks) PlayerTextButton(onClick = { visibleBlocks += 80 }) { Text("Show more document sections") }
    }
}

@Composable private fun ReforgeBoundedInline(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium,
    onRecordLink: ((String) -> Unit)? = null) {
    var shown by remember(text) { mutableIntStateOf(12_000) }
    Column(modifier) {
        ReforgeInline(text.take(shown), style = style, onRecordLink = onRecordLink)
        if (text.length > shown) PlayerTextButton(onClick = { shown += 12_000 }) { Text("Show more text") }
    }
}

@Composable private fun ReforgeInline(text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium, onRecordLink: ((String) -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    val ink = MaterialTheme.colorScheme.onSurface
    val linkColor = RebuildPalette.Ice
    val value = remember(text, linkColor, uriHandler, onRecordLink) {
        // Inline parsing has bounded patterns. Unsafe links keep their readable label.
        val pattern = Regex("""\[([^\]]+)]\(([^)]+)\)|\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\*([^*]+)\*|_([^_]+)_""")
        buildAnnotatedString {
            var cursor = 0
            pattern.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                when {
                    match.groupValues[1].isNotEmpty() -> {
                        val label = match.groupValues[1]
                        val target = RecordPresentation.safeLink(match.groupValues[2])
                        if (target != null) {
                            withLink(LinkAnnotation.Clickable(target,
                                styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                                linkInteractionListener = {
                                    if (target.startsWith("astravar:", ignoreCase = true)) onRecordLink?.invoke(java.net.URI(target).path.substringAfter('/').substringAfter('/'))
                                    else runCatching { uriHandler.openUri(target) }
                                })) { append(label) }
                        } else append(label)
                    }
                    match.groupValues[3].isNotEmpty() || match.groupValues[4].isNotEmpty() -> {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(match.groupValues[3].ifEmpty { match.groupValues[4] }); pop()
                    }
                    match.groupValues[5].isNotEmpty() -> {
                        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = RebuildPalette.Active)); append(match.groupValues[5]); pop()
                    }
                    else -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(match.groupValues[6].ifEmpty { match.groupValues[7] }); pop() }
                }
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    SelectionContainer {
        Text(value, modifier = modifier, style = style.copy(color = ink))
    }
}

/** Lightweight writing controls preserve Markdown source; save semantics stay with the record. */
@Composable fun ReforgeDocumentEditor(label: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean = true) {
    var preview by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Heading" to "\n## ", "Bold" to "**text**", "List" to "\n- ", "Checklist" to "\n- [ ] ").forEach { (title, insert) ->
                PlayerTextButton(onClick = { onValueChange(value + insert) }, enabled = enabled && !preview) { Text(title) }
            }
            PlayerTextButton(onClick = { preview = !preview }) { Text(if (preview) "Write" else "Preview") }
        }
        if (preview) ReforgeRichText(value.ifBlank { "Your preview appears here." })
        else OutlinedTextField(value, onValueChange, label = { Text(label) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12)
    }
}
