package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

/**
 * Read-only local prompt catalogue. It never sends or retrieves campaign information.
 * User explicitly taps Copy and pastes it in an appropriate, authorized chat.
 * All source-of-truth and write gates remain controlled by the receiving integration.
 */
@Composable fun ChatGptPromptsPage() {
    val context = LocalContext.current
    val catalogResult = remember(context) {
        runCatching {
            context.assets.open("astravar_chatgpt_prompts.json").bufferedReader(Charsets.UTF_8).use { stream ->
                Json.parseToJsonElement(stream.readText()).jsonObject.getValue("templates").jsonArray.map { entry ->
                    val data = entry.jsonObject
                    PromptCard(
                        id = data.getValue("id").jsonPrimitive.content,
                        title = data.getValue("title").jsonPrimitive.content,
                        section = data.getValue("section").jsonPrimitive.content,
                        detail = data.getValue("action").jsonPrimitive.content,
                        prompt = data.getValue("body").jsonPrimitive.content
                    )
                }
            }
        }
    }
    val templates = catalogResult.getOrDefault(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("All") }
    var copied by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = listOf("All") + templates.map { it.section }.distinct()
    val filtered = templates.filter {
        (section == "All" || it.section == section) &&
            (query.isBlank() || listOf(it.title, it.detail, it.section, it.prompt).any { t -> t.contains(query, ignoreCase = true) })
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ChatGPT toolkit", style = MaterialTheme.typography.headlineSmall)
        Text("Reusable, copyable handoffs. Astravar does not silently open a conversation or upload character data.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = query, onValueChange = { query = it },
            label = { Text("Find a prompt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // A single horizontally browsable category strip preserves readable 48dp targets.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sections.forEach { category ->
                FilterChip(selected = section == category, onClick = { section = category },
                    label = { Text(category) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (catalogResult.isFailure) Text("The local prompt catalog could not be opened. Do not assume saved context has been recovered.", color = MaterialTheme.colorScheme.error)
        else if (filtered.isEmpty()) Text("No matching handoffs", color = RebuildPalette.Muted)
        filtered.forEach { template ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(template.title, style = MaterialTheme.typography.titleMedium)
                    Text(template.detail, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodyMedium)
                    Text(template.section, color = RebuildPalette.Cyan, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { expanded = if (expanded == template.id) null else template.id },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (expanded == template.id) "Hide prompt" else "Read prompt")
                    }
                    if (expanded == template.id) Text(template.prompt, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Astravar: ${template.title}", template.prompt))
                        copied = template.id
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (copied == template.id) "Copied — paste in ChatGPT" else "Copy handoff prompt")
                    }
                }
            }
        }
        Text("Prompts are instructions, not authenticated app commands. A receiving chat must verify tool availability and source revisions.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private data class PromptCard(
    val id: String,
    val title: String,
    val section: String,
    val detail: String,
    val prompt: String
)
