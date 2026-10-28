package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.ChatGptPrompt
import com.yazsras.astravar.data.ChatGptPromptDraft
import com.yazsras.astravar.data.ChatGptPromptRenderer
import com.yazsras.astravar.data.ChatGptPromptRepository
import com.yazsras.astravar.data.loadChatGptPromptSeeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Persistent local handoffs. Copy uses the current saved template; no campaign data is sent. */
@Composable
fun ChatGptPromptsPage(repository: ChatGptPromptRepository? = null) {
    val context = LocalContext.current
    val prompts = repository ?: (context.applicationContext as AstravarApp).campaignLedger.prompts
    val scope = rememberCoroutineScope()
    var saved by remember(prompts) { mutableStateOf(emptyList<ChatGptPrompt>()) }
    var loading by remember(prompts) { mutableStateOf(true) }
    var initializationError by remember(prompts) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("All") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var copiedId by rememberSaveable { mutableStateOf<String?>(null) }
    var copiedRevision by rememberSaveable { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }

    var mode by rememberSaveable { mutableStateOf("") }
    var identifier by rememberSaveable { mutableStateOf("") }
    var originalIdentifier by rememberSaveable { mutableStateOf("") }
    var expectedRevision by rememberSaveable { mutableLongStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var originalName by rememberSaveable { mutableStateOf("") }
    var originalDescription by rememberSaveable { mutableStateOf("") }
    var originalCategory by rememberSaveable { mutableStateOf("") }
    // Large prompt text and frozen editor baselines never enter the Activity Bundle.
    val contentDraft = rememberUiDraftText("")
    var content by contentDraft
    val originalContentDraft = rememberUiDraftText("")
    var originalContent by originalContentDraft
    val variableValuesDraft = rememberUiDraftText("{}")
    var variableValuesJson by variableValuesDraft
    val renderedPreviewDraft = rememberUiDraftText()
    var renderedPreview by renderedPreviewDraft
    var variableNames by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var variablePromptId by rememberSaveable { mutableStateOf("") }
    var variablePromptName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(prompts, retry) {
        loading = true
        initializationError = null
        try {
            withContext(Dispatchers.IO) { prompts.initialize { loadChatGptPromptSeeds(context) } }
            prompts.prompts.collect { records ->
                saved = records
                loading = false
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            loading = false
            initializationError = error.message ?: "Saved prompts could not be loaded."
        }
    }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        message = ""
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                failed = true
                message = error.message ?: "The prompt operation could not be completed."
            } finally { busy = false }
        }
    }

    fun edit(record: ChatGptPrompt?) {
        identifier = record?.id ?: UUID.randomUUID().toString()
        originalIdentifier = identifier
        expectedRevision = record?.revision ?: 0
        name = record?.name.orEmpty()
        description = record?.description.orEmpty()
        category = record?.category.orEmpty()
        content = record?.content.orEmpty()
        originalName = name
        originalDescription = description
        originalCategory = category
        originalContent = content
        failed = false
        message = ""
        mode = if (record == null) "create" else "edit"
    }

    fun copyText(record: ChatGptPrompt, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Astravar: ${record.name}", text))
        copiedId = record.id
        copiedRevision = record.revision
        message = "Prompt copied — paste in ChatGPT."
    }

    fun requestCopy(id: String) = act {
        val record = withContext(Dispatchers.IO) { prompts.get(id) }
            ?: error("This prompt was deleted. Choose another saved prompt or add a new one.")
        val names = ChatGptPromptRenderer.placeholders(record.content)
        if (names.isEmpty()) {
            val text = withContext(Dispatchers.IO) { prompts.render(id) }
            copyText(record, text)
        } else {
            variablePromptId = record.id
            variablePromptName = record.name
            variableNames = ArrayList(names)
            variableValuesJson = Json.encodeToString(names.associateWith { "" })
            renderedPreview = null
            mode = "variables"
        }
    }

    val categories = listOf("All") + saved.map { it.category }.filter(String::isNotBlank).distinct().sorted()
    val effectiveFilter = categoryFilter.takeIf { it in categories } ?: "All"
    val filtered = saved.filter { record ->
        (effectiveFilter == "All" || record.category == effectiveFilter) &&
            (query.isBlank() || listOf(record.id, record.name, record.description, record.category, record.content)
                .any { it.contains(query, ignoreCase = true) })
    }
    val ready = !loading && initializationError == null
    val draftReady = contentDraft.canSave && originalContentDraft.canSave
    val editorDirty = identifier != originalIdentifier || name != originalName || description != originalDescription || category != originalCategory || content != originalContent
    val editorValid = draftReady && identifier.isNotBlank() && name.isNotBlank() && !content.isNullOrBlank()
    val currentEditorRecord = saved.firstOrNull { it.id == identifier }
    val staleEditor = mode == "edit" && ready && (currentEditorRecord == null || currentEditorRecord.revision != expectedRevision)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerHeading("ChatGPT Prompts")
        Text("Create and edit reusable handoffs, then copy the current saved text into ChatGPT.", color = RebuildPalette.Muted,
            style = MaterialTheme.typography.bodySmall)
        PlayerButton(onClick = { edit(null) }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) { Text("Add Prompt") }
        PlayerSearch(query, { query = it }, "Find a prompt")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { value ->
                PlayerFilterChip(selected = value == effectiveFilter, onClick = { categoryFilter = value }, label = { Text(value) })
            }
        }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        initializationError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
            PlayerOutlinedButton(onClick = { retry++ }, enabled = !busy) { Text("Retry loading prompts") }
        }
        if (mode.isEmpty() && message.isNotBlank()) PromptStatus(message, failed)
        if (ready && filtered.isEmpty()) PlayerEmpty(
            if (saved.isEmpty()) "No saved prompts" else "No matching prompts",
            if (saved.isEmpty()) "Add a prompt to start your collection." else "Try another name, identifier, category or phrase.")
        filtered.forEach { record ->
            PlayerCard {
                Text(record.name, style = MaterialTheme.typography.titleMedium)
                if (record.description.isNotBlank()) Text(record.description, color = RebuildPalette.Muted)
                if (record.category.isNotBlank()) Text(record.category, color = RebuildPalette.Cyan, style = MaterialTheme.typography.labelSmall)
                Text("ID: ${record.id}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                PlayerTextButton(onClick = { expanded = if (expanded == record.id) null else record.id }, enabled = !busy) {
                    Text(if (expanded == record.id) "Hide prompt" else "Read prompt")
                }
                if (expanded == record.id) {
                    Text(record.content, style = MaterialTheme.typography.bodyMedium)
                    Text("Created: ${promptDate(record.createdAt)}\nLast updated: ${promptDate(record.updatedAt)}",
                        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    val names = ChatGptPromptRenderer.placeholders(record.content)
                    if (names.isNotEmpty()) Text("Variables: ${names.joinToString { "{$it}" }}", color = RebuildPalette.Ice,
                        style = MaterialTheme.typography.bodySmall)
                }
                PlayerButton(onClick = { requestCopy(record.id) }, enabled = ready && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("prompt-copy-${record.id}")) {
                    Text(if (copiedId == record.id && copiedRevision == record.revision) "Copied — paste in ChatGPT" else "Copy handoff prompt")
                }
                PlayerOutlinedButton(onClick = {
                    act {
                        val latest = withContext(Dispatchers.IO) { prompts.get(record.id) }
                            ?: error("This prompt was deleted. Refresh the list before editing.")
                        edit(latest)
                    }
                }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().testTag("prompt-edit-${record.id}")) { Text("Edit") }
                PlayerTextButton(onClick = {
                    identifier = record.id; expectedRevision = record.revision; name = record.name
                    message = ""; failed = false; mode = "delete"
                }, enabled = ready && !busy, modifier = Modifier.testTag("prompt-delete-${record.id}")) { Text("Delete") }
            }
        }
        Text("Prompts stay on this device. Copying does not send campaign information or authorize app commands.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }

    if (mode == "create" || mode == "edit") {
        PlayerSheet(if (mode == "create") "Add Prompt" else "Edit prompt", onDismiss = { if (!busy) mode = "" },
            dirty = editorDirty, showCancel = true, actions = {
                PlayerButton(onClick = {
                    act {
                        val draft = ChatGptPromptDraft(name = name, content = content.orEmpty(), description = description, category = category)
                        withContext(Dispatchers.IO) {
                            if (mode == "create") prompts.create(draft, identifier)
                            else prompts.update(identifier, expectedRevision, draft)
                        }
                        copiedId = null
                        expanded = null
                        query = ""
                        categoryFilter = "All"
                        mode = ""
                        message = "Prompt saved."
                    }
                }, enabled = ready && !busy && editorValid && !staleEditor) { Text("Save") }
            }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) PromptStatus(message, failed)
            contentDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            originalContentDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (staleEditor) Text("This prompt changed or was deleted while editing. Cancel and reopen its current saved version before saving.", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), supportingText = { Text("Required · up to 120 characters") })
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, maxLines = 5,
                enabled = !busy, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Optional · up to 4,000 characters") })
            OutlinedTextField(identifier, { identifier = it }, label = { Text("Identifier") }, singleLine = true,
                enabled = !busy && mode == "create", modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Stable unique identifier. It cannot change after saving.") })
            OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Optional · up to 80 characters") })
            OutlinedTextField(content.orEmpty(), { content = it }, label = { Text("Prompt content") }, minLines = 8, maxLines = 16,
                enabled = !busy && contentDraft.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                supportingText = { Text("${content.orEmpty().length}/${ChatGptPromptRepository.MAX_CONTENT_CHARACTERS} characters") })
            Text("Use {characterName} or {context} for reusable variables. Copy asks for each value. Use {{name}} when you want literal {name} text.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (mode == "delete") {
        PlayerSheet("Delete prompt?", onDismiss = { if (!busy) mode = "" }, showCancel = true, actions = {
            PlayerButton(onClick = {
                act {
                    withContext(Dispatchers.IO) { prompts.delete(identifier, expectedRevision) }
                    if (expanded == identifier) expanded = null
                    copiedId = null
                    mode = ""
                    message = "Prompt deleted."
                }
            }, enabled = ready && !busy) { Text("Delete prompt") }
        }) {
            Text("Delete “$name”? This removes the saved prompt and its content.")
            Text("Identifier: $identifier", color = RebuildPalette.Muted)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) PromptStatus(message, failed)
        }
    }

    if (mode == "variables") {
        val values = remember(variableValuesJson) {
            runCatching { Json.decodeFromString<Map<String, String>>(variableValuesJson ?: "{}") }.getOrNull()
        }
        fun renderCurrent(copy: Boolean) = act {
            val record = withContext(Dispatchers.IO) { prompts.get(variablePromptId) }
                ?: error("This prompt was deleted. Close this form and choose another saved prompt.")
            val currentNames = ChatGptPromptRenderer.placeholders(record.content)
            if (currentNames.any { it !in values.orEmpty() }) {
                variableNames = ArrayList(currentNames)
                variableValuesJson = Json.encodeToString(currentNames.associateWith { values?.get(it).orEmpty() })
                variablePromptName = record.name
                renderedPreview = null
                error("This prompt changed. Review its current variables, then try again.")
            }
            val rendered = withContext(Dispatchers.IO) { prompts.render(variablePromptId, requireNotNull(values)) }
            if (copy) {
                copyText(record, rendered)
                mode = ""
            } else {
                renderedPreview = rendered
                message = "Preview uses the current saved prompt."
            }
        }
        PlayerSheet("Prompt variables", onDismiss = { if (!busy) mode = "" }, showCancel = true, actions = {
            PlayerOutlinedButton(onClick = { renderCurrent(false) }, enabled = !busy && variableValuesDraft.canSave && values != null) { Text("Preview prompt") }
            PlayerButton(onClick = { renderCurrent(true) }, enabled = !busy && variableValuesDraft.canSave && values != null) { Text("Copy prompt") }
        }) {
            Text(variablePromptName, style = MaterialTheme.typography.titleMedium)
            Text("These values are used for this copy and do not change the saved template.", color = RebuildPalette.Muted)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) PromptStatus(message, failed)
            variableValuesDraft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (values == null) Text("The variable draft could not be read. Close and reopen Copy before continuing.", color = MaterialTheme.colorScheme.error)
            variableNames.forEach { variable ->
                OutlinedTextField(values?.get(variable).orEmpty(), { value ->
                    variableValuesJson = Json.encodeToString(values.orEmpty() + (variable to value))
                    renderedPreview = null
                }, label = { Text(variable) }, minLines = 2, maxLines = 6, enabled = !busy && values != null,
                    modifier = Modifier.fillMaxWidth())
            }
            renderedPreview?.let { preview ->
                Text("Prompt preview", style = MaterialTheme.typography.titleMedium)
                Text(preview)
            }
        }
    }
}

@Composable
private fun PromptStatus(message: String, failed: Boolean) {
    Text(message, color = if (failed) MaterialTheme.colorScheme.error else RebuildPalette.Ice,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun promptDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
