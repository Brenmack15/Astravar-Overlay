package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun EncounterStrikeForm(snapshot: GenesisEncounterSnapshot, characters: List<ReforgeSavedCharacter>,
    repository: EncounterStrikeRepository) {
    val attacker = characters.firstOrNull { it.character.id == snapshot.record.currentActorId }?.character ?: return
    val targets = characters.map { it.character }.filter { c -> !c.archived && c.id != attacker.id &&
        snapshot.participants.any { it.actorId == c.id } }
    if (attacker.archived || attacker.attacks.isEmpty() || targets.isEmpty()) return

    var open by rememberSaveable(snapshot.record.id,attacker.id) { mutableStateOf(false) }
    PlayerTextButton(onClick = { open = !open }) { Text(if (open) "Close attack" else "Resolve attack") }
    if (!open) return
    var targetId by rememberSaveable(snapshot.record.id,attacker.id) { mutableStateOf(targets.first().id) }
    var attackId by rememberSaveable(attacker.id) { mutableStateOf(attacker.attacks.first().id) }
    Choices("Target",targets.map { it.id },targetId,display = { id -> targets.firstOrNull { it.id == id }?.name ?: id },
        change = { targetId = it })
    Choices("Attack",attacker.attacks.map { it.id },attackId,display = { id -> attacker.attacks.firstOrNull { it.id == id }?.name ?: id },
        change = { attackId = it })
    val target = targets.firstOrNull { it.id == targetId } ?: return
    var d20 by rememberSaveable(attacker.id,targetId,attackId) { mutableStateOf("") }
    var finish by rememberSaveable(snapshot.record.id,attacker.id) { mutableStateOf(false) }
    val face = d20.toIntOrNull()
    val attack = remember(attacker,attackId,face == 20) { runCatching { AttackRules.preview(attacker,attackId,critical=face == 20) } }
    val preview = attack.getOrNull()
    if (preview == null) { Text(attack.exceptionOrNull()?.message ?: "Attack unavailable"); return }
    val ac = CharacterCalculator.calculate(target).armorClass.value
    val bonus = preview.attackBonus.value
    if (ac == null || bonus == null) { Text("The target AC and attack bonus must be known."); return }
    Field("Natural d20",d20,{ d20 = it })
    val damage = remember(attacker.revision,attackId,targetId,face == 20) { mutableStateMapOf<String,String>() }
    val validFace = face != null && face in 1..20
    val hit = validFace && encounterStrikeHits(requireNotNull(face),bonus,ac)
    if (validFace) Text("Attack total " + (requireNotNull(face) + bonus) + " against AC " + ac + if (hit) " · Hit" else " · Miss")
    if (hit) preview.components.forEach { part ->
        Field(part.label + " · " + SecureDice.format(part.formula),damage[part.id].orEmpty(),{ damage[part.id] = it })
    }
    PlayerTextButton(onClick = { finish = !finish }) { Text(if (finish) "Finish the turn with this attack" else "Keep the current turn") }
    val rolled = if (hit) preview.components.mapNotNull { part -> damage[part.id]?.toIntOrNull()?.let { part.id to it } }.toMap() else emptyMap()
    val request = if (validFace && (!hit || rolled.size == preview.components.size)) EncounterStrikeRequest(snapshot.record.id,
        snapshot.record.revision,attacker.id,attacker.revision,target.id,target.revision,attackId,requireNotNull(face),rolled,finish) else null
    var reviewed by remember(request) { mutableStateOf<EncounterStrikeResult?>(null) }
    var error by remember(request) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val command = remember(request) { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    reviewed?.let { Text("Damage " + it.damage.total + " · " + target.name + " HP " + (it.targetHp ?: "unknown") +
        " · Temporary HP " + (it.targetTempHp ?: "unknown")) }
    if (reviewed?.concentrationDecisionPending == true) Text("The target is concentrating. Resolve its concentration save separately.")
    if (error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    PlayerButton(enabled = request != null && !busy,onClick = {
        val confirmed = request ?: return@PlayerButton
        busy = true
        scope.launch {
            try {
                if (reviewed == null) reviewed = repository.preview(confirmed)
                else { repository.commit(confirmed,command); open = false }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Review the current encounter and both actors."; reviewed = null }
            finally { busy = false }
        }
    }) { Text(if (reviewed == null) "Preview result" else "Commit attack") }
}
