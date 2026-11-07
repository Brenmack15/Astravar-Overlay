package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import java.util.UUID

@Serializable internal data class ReforgeRollRequest(val label: String, val formula: DiceFormula?, val kind: RollKind = RollKind.CUSTOM,
    val key: String = "", val attackId: String? = null, val characterRevision: Long? = null)

@Composable internal fun ReforgeRollDialog(character: ReforgeCharacter, revision: Long, request: ReforgeRollRequest,
    onDismiss: () -> Unit, onSave: (ReforgeRollRecord, ReforgeCharacter?) -> Unit) {
    var expression by rememberSaveable { mutableStateOf(request.formula?.let(SecureDice::format) ?: "1d20") }
    var advantage by rememberSaveable { mutableStateOf(false) }
    var disadvantage by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var manualFaces by rememberSaveable { mutableStateOf("") }
    var ownership by rememberSaveable { mutableStateOf("Player") }
    var situationalBonus by rememberSaveable { mutableStateOf("") }
    var targetType by rememberSaveable { mutableStateOf("") }
    var fearVisible by rememberSaveable { mutableStateOf(false) }
    var unseen by rememberSaveable { mutableStateOf(false) }
    var notGrappler by rememberSaveable { mutableStateOf(false) }
    var requiresSight by rememberSaveable { mutableStateOf(false) }
    var requiresHearing by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by rememberSaveable(stateSaver = Saver<DiceResult?, String>(
        save = { it?.let { value -> Json.encodeToString(value) } ?: "" },
        restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<DiceResult>(value) } }
    )) { mutableStateOf<DiceResult?>(null) }
    var preview by rememberSaveable(stateSaver = Saver<AttackPreview?, String>(
        save = { it?.let { value -> Json.encodeToString(value) } ?: "" },
        restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<AttackPreview>(value) } }
    )) { mutableStateOf<AttackPreview?>(null) }
    var damage by rememberSaveable(stateSaver = Saver<List<Pair<DamageComponent, DiceResult>>, String>(
        save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) }
    )) { mutableStateOf(listOf<Pair<DamageComponent, DiceResult>>()) }
    val rollId = rememberSaveable { UUID.randomUUID().toString() }
    val stale = request.characterRevision != null && request.characterRevision != revision
    val contextTags = buildSet {
        if (fearVisible) add("fear-source-visible")
        if (unseen) add("target-cannot-see-you")
        if (notGrappler) add("target-is-not-grappler")
        if (requiresSight) add("requires-sight")
        if (requiresHearing) add("requires-hearing")
        addAll(character.attacks.firstOrNull { it.id == request.attackId }?.tags ?: emptySet())
    }
    val rollContext = CalculationContext(attackId = request.attackId, targetCreatureType = targetType.trim().ifBlank { null }, tags = contextTags)
    val restriction = ConditionRules.rollRestriction(character, request.kind, request.key, rollContext)
    val mode = CharacterCalculator.rollMode(character, request.kind, request.key, rollContext, advantage, disadvantage)
    fun record(next: ReforgeCharacter?) {
        val roll = result ?: return
        val components = listOf(ReforgeRollComponent(if (request.attackId != null) "Attack" else request.label,
            roll.faces.map { ReforgeRollDie(it.sides, it.face, it.kept, it.sign) }, roll.formula.flat)) +
            damage.map { (component, rolled) -> ReforgeRollComponent(component.label, rolled.faces.map { ReforgeRollDie(it.sides, it.face, it.kept, it.sign) }, rolled.formula.flat, component.type.name) }
        onSave(ReforgeRollRecord(id = rollId, profileId = character.id, characterRevision = request.characterRevision ?: revision,
            label = request.label, formula = SecureDice.format(roll.formula) + if (damage.isNotEmpty()) " · damage ${damage.joinToString(" + ") { SecureDice.format(it.second.formula) }}" else "",
            components = components, manual = roll.manual, ownership = ownership), next)
    }
    PlayerSheet(request.label, onDismiss) {
        if (result == null) {
            if (request.attackId == null) Field("Dice formula", expression, { expression = it }, enabled = request.formula == null)
            else Text("Uses your calculated attack bonus and active equipment damage. The DM determines the hit.", color = RebuildPalette.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = advantage, onClick = { advantage = !advantage }, label = { Text("Advantage") })
                FilterChip(selected = disadvantage, onClick = { disadvantage = !disadvantage }, label = { Text("Disadvantage") })
            }
            Text("Effective mode · ${mode.name.lowercase()}", color = RebuildPalette.Ice)
            if (advantage && disadvantage) Text("Advantage and Disadvantage cancel.", color = RebuildPalette.Muted)
            if (request.attackId == null) Toggle("Enter physical dice faces", manual) { manual = it }
            if (manual) Field("Faces in order (comma separated)", manualFaces, { manualFaces = it })
            PlayerDisclosure("Circumstances & extra dice") {
                Field("Situational bonus (optional: 1d4 or +2)", situationalBonus, { situationalBonus = it })
                Field("Target creature type (optional)", targetType, { targetType = it })
                if ("frightened" in character.conditions) Toggle("Fear source is visible", fearVisible) { fearVisible = it }
                if ("invisible" in character.conditions) Toggle("Target cannot see me", unseen) { unseen = it }
                if ("grappled" in character.conditions) Toggle("Target is not my grappler", notGrappler) { notGrappler = it }
                if (request.kind in setOf(RollKind.ABILITY_CHECK, RollKind.SKILL)) {
                    Toggle("This check requires sight", requiresSight) { requiresSight = it }
                    Toggle("This check requires hearing", requiresHearing) { requiresHearing = it }
                }
                Text("Only enter bonuses granted by your current rules or DM. The final formula records every die and modifier.", color = RebuildPalette.Muted)
            }
            PlayerDisclosure("Dice ownership") {
                Choices("Recorded owner", listOf("Player", "Companion", "DM"), ownership) { ownership = it }
                Text("Each roll requires a tap. This setting labels who owns the result and never starts autonomous rolls.", color = RebuildPalette.Muted)
            }
            if (restriction != null) Text(restriction, color = MaterialTheme.colorScheme.error)
            if (stale) Text("Character statistics changed. Close this roll and reopen it from the current sheet. Existing dice are retained until you close.", color = MaterialTheme.colorScheme.error)
            PlayerButton(enabled = restriction == null && !stale, onClick = {
                try {
                    val initial = request.attackId?.let { AttackRules.preview(character, it, rollContext) }
                    val baseFormula = initial?.attackBonus?.value?.let { DiceFormula(listOf(DiceTerm(1, 20)), it) }
                        ?: if (request.attackId != null) error("Attack bonus is unknown") else SecureDice.parse(expression)
                    val extra = situationalBonus.trim().takeIf { it.isNotBlank() }?.let(SecureDice::parse) ?: DiceFormula()
                    val formula = DiceFormula(baseFormula.dice + extra.dice, Math.addExact(baseFormula.flat, extra.flat))
                    val rolled = if (manual) SecureDice.manual(formula, manualFaces.split(',', ' ').filter { it.isNotBlank() }.map { it.toIntOrNull() ?: error("Enter whole die faces") }, mode) else SecureDice.roll(formula, mode)
                    if (request.attackId != null) {
                        val attack = AttackRules.preview(character, request.attackId, rollContext, critical = rolled.naturalD20 == 20)
                        preview = attack
                        damage = attack.components.map { it to SecureDice.roll(it.formula) }
                    }
                    result = rolled; error = ""
                } catch (e: Exception) { error = e.message ?: "Unable to roll" }
            }, modifier = Modifier.testTag("reforge-roll-now")) { Text(if (manual) "Validate entered dice" else "Roll now") }
        } else {
            val rolled = requireNotNull(result)
            Text(rolled.total.toString(), style = MaterialTheme.typography.displayLarge, color = RebuildPalette.GoldBright,
                modifier = Modifier.testTag("reforge-roll-result"))
            Text(rolled.breakdown(), color = RebuildPalette.Ice)
            if (request.attackId != null) {
                Text(when (rolled.naturalD20) { 20 -> "Natural 20 · critical damage dice"; 1 -> "Natural 1 · automatic miss"; else -> "Attack total · DM determines the hit" }, color = RebuildPalette.Muted)
                damage.forEach { (component, rolledDamage) -> Text("${component.label} · ${rolledDamage.total} ${component.type.name.lowercase()}\n${rolledDamage.breakdown()}", style = MaterialTheme.typography.bodyMedium) }
                val attack = preview
                if (attack != null) {
                    if (attack.chargeCosts.isNotEmpty()) Text("On a confirmed hit: ${attack.chargeCosts.entries.joinToString { (id, cost) -> "$cost ${character.resources.firstOrNull { it.id == id }?.name ?: "charge"}" }}", color = RebuildPalette.GoldBright)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerButton(enabled = rolled.naturalD20 != 1 && !stale, onClick = { record(AttackRules.commitAttack(character, attack, true)) }, modifier = Modifier.weight(1f)) { Text("Confirm hit") }
                        PlayerOutlinedButton(enabled = !stale, onClick = { record(AttackRules.commitAttack(character, attack, false)) }, modifier = Modifier.weight(1f)) { Text("Confirm miss") }
                    }
                    val hitStateRequired = attack.chargeCosts.isNotEmpty() ||
                        (character.effects + character.itemDefinitions.flatMap { it.effects }).any { effect ->
                            effect.id in attack.effectIds && effect.conditions.any { it.kind == ConditionKind.ONCE_PER_TURN }
                        }
                    if (!hitStateRequired) PlayerTextButton(enabled = !stale,
                        onClick = { record(AttackRules.commitAttack(character, attack, false)) }) { Text("Save unresolved roll") }
                    Text(if (hitStateRequired) "Confirm the hit or miss before saving this attack so its hit-dependent charges and per-turn effects are recorded correctly. Your generated dice are retained while you decide."
                        else "Saving any attack spends its configured attempt costs and tracked action. An unresolved attack retains potential damage; no hit-dependent resource decision remains pending.",
                        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            } else PlayerButton(enabled = !stale, onClick = { record(null) }) { Text("Save roll") }
            Text("Closing without saving discards this result. No resources are consumed until the saved action is confirmed.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (stale && result != null) Text("This roll uses an older character revision. Your saved character has changed; these dice will not be silently rebound to new statistics.", color = MaterialTheme.colorScheme.error)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
