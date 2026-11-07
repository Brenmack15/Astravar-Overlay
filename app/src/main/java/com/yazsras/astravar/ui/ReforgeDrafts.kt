package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val reforgeDraftJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/** Reuse the existing verified, bounded file-backed saved-state storage for compound edits. */
internal class ReforgeTypedDraft<T>(private val text: UiDraftTextState, private val initial: T,
    private val serializer: KSerializer<T>) : MutableState<T> {
    private var last: String? = null
    private var read = false
    private var decoded: T = initial
    private var valid = false
    private fun decode() {
        val source = text.value
        if (read && source == last) return
        last = source; read = true
        if (!text.canSave || source == null) { valid = false; decoded = initial; return }
        val result = runCatching { reforgeDraftJson.decodeFromString(serializer, requireNotNull(source)) }
        valid = result.isSuccess
        decoded = result.getOrDefault(initial)
    }
    val canSave: Boolean get() { decode(); return text.canSave && valid }
    val error: String? get() { decode(); return text.error ?: if (!valid) "This draft could not be recovered. Reload saved values before continuing." else null }
    override var value: T
        get() { decode(); return decoded }
        set(value) { if (canSave) text.value = reforgeDraftJson.encodeToString(serializer, value) }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
    fun reloadSavedValues() { text.value = reforgeDraftJson.encodeToString(serializer, initial) }
}

@Composable internal fun <T> rememberReforgeDraft(initial: T, serializer: KSerializer<T>): ReforgeTypedDraft<T> {
    val initialText = remember { reforgeDraftJson.encodeToString(serializer, initial) }
    val text = rememberUiDraftText(initialText)
    return remember(text) { ReforgeTypedDraft(text, initial, serializer) }
}

@Composable internal fun ReforgeDraftRecovery(vararg drafts: ReforgeTypedDraft<*>) {
    val broken = drafts.filterNot { it.canSave }
    if (broken.isNotEmpty()) {
        Text(broken.first().error ?: "The draft could not be restored. Saved records remain intact.", color = MaterialTheme.colorScheme.error)
        PlayerOutlinedButton(onClick = { drafts.forEach { it.reloadSavedValues() } }) { Text("Reload saved values") }
    }
}
