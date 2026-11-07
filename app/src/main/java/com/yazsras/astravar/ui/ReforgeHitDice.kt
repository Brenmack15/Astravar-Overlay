package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Composable internal fun ReforgeHitDieDialog(latestCharacter: ReforgeCharacter, resourceId: String,
    onDismiss: () -> Unit, onCommit: (ReforgeRollRecord, ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val resource = character.resources.firstOrNull { it.id == resourceId }
    var eligible by rememberSaveable { mutableStateOf(false) }
    var rollJson by rememberSaveable { mutableStateOf<String?>(null) }
    val roll = remember(rollJson) { rollJson?.let { Json.decodeFromString<DiceResult>(it) } }
    val rollId = rememberSaveable { UUID.randomUUID().toString() }
    var error by remember { mutableStateOf("") }
    val preview = roll?.let { result -> runCatching { GameplayRules.previewHitDie(character, resourceId, result.faces.single().face, true) }.getOrNull() }
    PlayerSheet("Spend a Hit Die", onDismiss) {
        Text("${resource?.name ?: "Hit Dice"} · ${resource?.current ?: "?"}/${resource?.maximum ?: "?"}", style = MaterialTheme.typography.titleLarge)
        Text("Spend one known available Hit Die during an eligible rest. This action does not complete or advance a rest by itself.", color = RebuildPalette.Muted)
        if (roll == null) {
            Toggle("I am spending this die during an eligible rest", eligible) { eligible = it }
            PlayerButton(enabled = eligible && resource?.dieSides != null, onClick = {
                try {
                    GameplayRules.previewHitDie(character, resourceId, 1, confirmedEligibleRest = true)
                    val con = CharacterCalculator.calculate(character).abilities.getValue(Stat.CON).modifier ?: error("Constitution modifier is unknown")
                    rollJson = Json.encodeToString(SecureDice.roll(DiceFormula(listOf(DiceTerm(1, requireNotNull(resource?.dieSides))), con)))
                } catch (e: Exception) { error = e.message ?: "This Hit Die is unavailable" }
            }) { Text("Roll Hit Die") }
        } else {
            Text(roll.breakdown(), color = RebuildPalette.Ice)
            if (preview != null) {
                Text("Healing ${preview.healing} · HP ${preview.previousHp} → ${preview.nextHp}")
                Text("Uses ${preview.previousUses} → ${preview.nextUses}", color = RebuildPalette.Muted)
                PlayerButton(onClick = {
                    try {
                        val next = GameplayRules.spendHitDie(character, resourceId, roll.faces.single().face, confirmedEligibleRest = true)
                        onCommit(ReforgeRollRecord(id = rollId, profileId = character.id, characterRevision = character.revision,
                            label = "${resource?.name ?: "Hit Die"} recovery", formula = SecureDice.format(roll.formula),
                            components = listOf(ReforgeRollComponent("Hit Die", roll.faces.map { ReforgeRollDie(it.sides, it.face, it.kept, it.sign) }, roll.formula.flat))), next)
                    } catch (e: Exception) { error = e.message ?: "This recovery could not be committed" }
                }) { Text("Spend die & heal") }
            }
        }
        if (latestCharacter.revision != character.revision) Text("The character changed after this recovery opened. Close and review the current sheet; an old recovery will not overwrite it.", color = MaterialTheme.colorScheme.error)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
