package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.ReforgeSavedCharacter
import com.yazsras.astravar.rules.reforge.*
import java.util.UUID

@Composable internal fun ReforgeItemManagementDialog(latestCharacter: ReforgeCharacter, itemId: String,
    profiles: List<ReforgeSavedCharacter>, busy: Boolean, saveError: String,
    onDismiss: () -> Unit, onChange: (String, ReforgeCharacter) -> Unit,
    onTransfer: (ReforgeCharacter, ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val item = character.items.firstOrNull { it.id == itemId } ?: return
    val definition = character.itemDefinitions.first { it.id == item.definitionId }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf("") }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    PlayerSheet("Manage ${definition.name}", onDismiss) {
        Text("Quantity ${item.quantity}. Changes here affect playable gear. Original campaign inventory and history are retained.", color = RebuildPalette.Muted)
        if (item.quantity > 1) PlayerDisclosure("Split stack") {
            Field("Quantity in the new stack", quantity, { quantity = it })
            PlayerButton(enabled = !busy, onClick = {
                try {
                    onChange("Split ${definition.name} stack", InventoryRules.split(character, item.id,
                        optionalInt(quantity, "Split quantity", 1, item.quantity - 1) ?: error("Enter a quantity"), UUID.randomUUID().toString()))
                } catch (e: Exception) { error = e.message ?: "This stack could not be split." }
            }) { Text("Split stack") }
        }
        PlayerDisclosure("Transfer to another sheet") {
            Text("Choose a saved character or companion sheet. The complete stack keeps its identity and arrives carried and unattuned.", color = RebuildPalette.Muted)
            val targets = profiles.filter { it.character.id != character.id }
            if (targets.isEmpty()) Text("Create or review another playable sheet before transferring gear.", color = RebuildPalette.Muted)
            targets.forEach { profile ->
                PlayerEntry(profile.character.name,
                    if (profile.character.id == targetId) "Selected · playable sheet" else "Playable sheet", "Emblem", {
                        targetId = profile.character.id; targetRevision = profile.revision
                    })
            }
            val target = targets.firstOrNull { it.character.id == targetId }
            if (target != null) {
                if (target.revision != targetRevision) Text("The receiving sheet changed. Select it again to review its latest state.", color = MaterialTheme.colorScheme.error)
                PlayerButton(enabled = !busy && target.revision == targetRevision, onClick = {
                    try {
                        InventoryRules.transfer(character, target.character, item.id)
                        onTransfer(character, target.character)
                    } catch (e: Exception) { error = e.message ?: "This item could not be transferred." }
                }) { Text("Transfer to ${target.character.name}") }
            }
        }
        PlayerDisclosure("Remove from this sheet") {
            Text("This removes the complete stack and its active equipment effects from this playable sheet. Its saved change history and original campaign record are retained.", color = RebuildPalette.Muted)
            Toggle("Confirm removal of ${definition.name}", confirmRemoval) { confirmRemoval = it }
            PlayerOutlinedButton(enabled = confirmRemoval && !busy, onClick = {
                try { onChange("Remove ${definition.name} from playable gear", InventoryRules.remove(character, item.id)) }
                catch (e: Exception) { error = e.message ?: "This item could not be removed." }
            }) { Text("Remove item") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error)
    }
}
