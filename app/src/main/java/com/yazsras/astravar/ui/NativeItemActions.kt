package com.yazsras.astravar.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yazsras.astravar.rules.reforge.*

@Composable internal fun NativeItemActions(character: ReforgeCharacter, item: ItemInstance, definition: ItemDefinition,
    busy: Boolean, onReview: (String,ReforgeCharacter) -> Unit, onError: (String) -> Unit) {
    for (pool in item.pools) Text("${pool.name} · ${pool.current ?: "Unknown"}/${pool.maximum}")
    for (action in definition.activations) key(item.id,action.id) {
        var entry by remember(character.revision) { mutableStateOf("") }
        if (action.temporaryHp != null) OutlinedTextField(value=entry,onValueChange={ entry=it },
            label={ Text("Rolled temporary HP for ${action.name}") },singleLine=true)
        PlayerOutlinedButton(onClick={
            try {
                val next=GameplayRules.activateItem(character,item.id,action.id,entry.toIntOrNull())
                onReview(action.name,next)
            } catch (e: IllegalArgumentException) { onError(e.message ?: "Activation unavailable") }
        },enabled= !busy && (action.temporaryHp == null || entry.toIntOrNull()!=null)) {
            Text("${action.name} · ${action.actionType}")
        }
    }
}
