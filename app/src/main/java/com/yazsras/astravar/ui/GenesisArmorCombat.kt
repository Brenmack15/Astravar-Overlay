package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.GenesisArmorRules
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.CharacterCalculator
import com.yazsras.astravar.rules.reforge.DiceMode
import com.yazsras.astravar.rules.reforge.RollKind
import com.yazsras.astravar.rules.reforge.EffectPayload
import com.yazsras.astravar.rules.reforge.NumericTarget
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.util.UUID

/** Inline Genesis content: no legacy screen, navigation stack or independent resource authority. */
@Composable internal fun GenesisArmorCombat(character: ReforgeCharacter,
    onSave: (ReforgeCharacter, String) -> Unit,
    onRoll: (ReforgeCharacter, ReforgeRollRecord) -> Unit,
    onHistory: () -> Unit, saving: Boolean = false) {
    val armor = character.armor ?: return
    val feedback = LocalGenesisEditorFeedback.current
    val stateResult = runCatching { GenesisArmorRules.runtime(character) }
    val state = stateResult.getOrNull()
    var ability by rememberSaveable(character.id) { mutableStateOf(if (armor.elements.isEmpty()) Ability.ARCANE else Ability.ELEMENTAL) }
    var distance by rememberSaveable(character.id) { mutableStateOf("30") }
    var point by rememberSaveable(character.id) { mutableStateOf("") }
    var direct by rememberSaveable(character.id) { mutableStateOf(false) }
    var advantage by rememberSaveable(character.id) { mutableStateOf(false) }
    var disadvantage by rememberSaveable(character.id) { mutableStateOf(false) }
    var selectedPayloads by rememberSaveable(character.id) { mutableStateOf(emptyList<String>()) }
    var immediateId by rememberSaveable(character.id) { mutableStateOf<String?>(null) }
    var manualSlot by rememberSaveable(character.id) { mutableStateOf(false) }
    var elementPicker by rememberSaveable { mutableStateOf(false) }
    var configure by rememberSaveable { mutableStateOf<String?>(null) }
    var resolve by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    val rollDraft = rememberReforgeDraft<PendingShot?>(null, PendingShot.serializer().nullable)
    var draft by rollDraft
    val melee = ability in listOf(Ability.GAUNTLET, Ability.SPELL_GAUNTLET, Ability.GUARDIAN)
    val magic = ability in listOf(Ability.SPELL, Ability.SPELL_GAUNTLET, Ability.DISCHARGE)
    val blast = ability == Ability.DISCHARGE
    val immediate = if (magic) state?.templates?.firstOrNull { it.id == immediateId } else null
    val selected = if (magic) selectedPayloads.filter { id -> state?.installed?.payloads?.any { it.id == id } == true } else emptyList()
    fun makePlan(id: String) = GenesisArmorRules.plan(character, id, ability, selected, immediate,
        if (melee) 5 else distance.intValue("Distance", 0, 180), point, direct && blast, manualSlot)
    val planResult = runCatching { makePlan("preview") }
    val plan = planResult.getOrNull()
    val latest = armor.campaign.pending.lastOrNull()
    LaunchedEffect(character.revision) {
        if (draft?.plan?.id?.let { id -> armor.campaign.pending.any { it.plan.id == id } } == true) {
            if (draft?.plan?.let { !it.blast || it.directImpact } == true) { advantage = false; disadvantage = false }
            draft = null; selectedPayloads = emptyList(); immediateId = null; manualSlot = false
        }
    }
    RebirthSectionLabel("Astravar armor") {
        PlayerTextButton(onClick = onHistory) { Text("History") }
    }
    PlayerCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ApprovedAttackMedallion(ability.approvedArt(), Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text("Active element", style = MaterialTheme.typography.labelMedium, color = RebuildPalette.Muted)
                Text(armor.elements.ifEmpty { listOf(DamageType.FORCE) }.joinToString(" + ") { it.name.lowercase().replaceFirstChar(Char::titlecase) },
                    style = MaterialTheme.typography.titleMedium)
            }
            PlayerTextButton(onClick = { elementPicker = true }, enabled = state != null && !saving) { Text("Change element") }
        }
        Choices("Armor attack", listOf(Ability.ARCANE, Ability.ELEMENTAL, Ability.GUARDIAN, Ability.GAUNTLET,
            Ability.SPELL, Ability.SPELL_GAUNTLET, Ability.DISCHARGE), ability, display = { it.title }, enabled = !saving && draft == null) {
            ability = it; error = ""
        }
        Text(if (melee) "Melee · 5 ft reach" else if (blast) "Area · 60 ft range · 15 ft radius" else "Ranged · 60 / 180 ft",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        if (plan != null) {
            if (!blast || direct) Text("${reforgeSigned(plan.attackBonus)} to hit", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("genesis-armor-accuracy"))
            if (blast) Text("DC ${plan.profile.saveDc} Dexterity · each creature's save is entered by the player")
            Engine.components(plan).forEach { component ->
                Text("${component.damage.dice} ${component.damage.type.name.lowercase()} · ${component.label}",
                    color = RebuildPalette.Silver)
            }
            Text(plan.actionCost, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        } else Text(planResult.exceptionOrNull()?.message ?: stateResult.exceptionOrNull()?.message ?: "Review armor configuration.",
            color = RebuildPalette.GoldBright)
        val unmappedEffects = genesisArmorUnmappedEffects(character, ability)
        if (unmappedEffects.isNotEmpty()) Text(
            "Review ${unmappedEffects.joinToString()}: these character effects are not yet connected to armor attack or damage formulas. The accepted armor configuration is retained.",
            color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall)
        if (melee) {
            PlayerTextButton(onClick = { configure = "melee" }) { Text("Configure gauntlets") }
            if (ability != Ability.GUARDIAN) {
                if (state?.gauntletCharge != null) Text("Charged Gauntlet ready · consumed by the next melee attempt", color = RebuildPalette.GoldBright)
                else PlayerOutlinedButton(onClick = {
                    try {
                        onSave(GenesisArmorRules.charge(character), "Prepared Charged Gauntlet · Bonus Action")
                    } catch (e: Exception) { error = e.message ?: "Review charge configuration." }
                }, enabled = !saving && state != null && state.gauntletCharge == null) { Text("Prepare Charged Gauntlet") }
            }
        }
        if (magic) PlayerDisclosure("Spell payload") {
            state?.installed?.payloads.orEmpty().forEach { payload ->
                Toggle("Stored · ${payload.template.name} (level ${payload.template.castingLevel})", payload.id in selectedPayloads, !saving) { checked ->
                    selectedPayloads = if (checked) selectedPayloads + payload.id else selectedPayloads - payload.id
                }
            }
            Choices("Use an available slot", listOf<Template?>(null) + state?.templates.orEmpty().filter { it.approved }, immediate,
                display = { it?.let { "${it.name} · level ${it.castingLevel}" } ?: "No immediate spell" }, enabled = !saving) { immediateId = it?.id }
            if (immediate != null && character.spellSlots[immediate.castingLevel] == null)
                Toggle("I confirm this unknown slot is available and spent on execution", manualSlot, !saving) { manualSlot = it }
            Text("Spell templates and reservoir contents are managed in Spells. Stored spells already paid their loading cost.", color = RebuildPalette.Muted)
        }
        PlayerDisclosure("Attack options") {
            if (!melee) Field("Distance in feet", distance, { distance = it }, enabled = !saving)
            if (blast) {
                Field("Blast target point", point, { point = it }, enabled = !saving)
                Toggle("Include direct impact", direct, !saving) { direct = it }
            }
            if (!blast || direct) {
                Toggle("Advantage", advantage, !saving) { advantage = it }
                Toggle("Disadvantage", disadvantage, !saving) { disadvantage = it }
                Text("Opposing advantage and disadvantage cancel, including long range and active character effects.", color = RebuildPalette.Muted)
            }
            PlayerTextButton(onClick = { configure = "damage" }) { Text("Configure accepted damage") }
        }
        if (!stateResult.isSuccess) Text(stateResult.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
        ReforgeDraftRecovery(rollDraft)
        PlayerButton(onClick = {
            try {
                val checked = makePlan(UUID.randomUUID().toString())
                val activeMode = CharacterCalculator.rollMode(character, RollKind.ATTACK, "armor:${ability.name}", GenesisArmorRules.attackContext(ability),
                    advantage = advantage, disadvantage = disadvantage || checked.disadvantage)
                val options = RollOptions(activeMode == DiceMode.ADVANTAGE, activeMode == DiceMode.DISADVANTAGE)
                // Distance is already included in the effective mode; a cancelled edge must remain cancelled.
                val rolled = DiceRoller.roll(checked, if (checked.disadvantage && activeMode == DiceMode.NORMAL)
                    RollOptions(advantage = true, disadvantage = true) else options)
                val pending = PendingShot(checked, rolled = rolled)
                draft = pending
                error = ""
                val next = GenesisArmorRules.commitRolled(character, checked, rolled)
                onRoll(next, genesisArmorRollRecord(character, pending))
            } catch (e: Exception) { error = e.message ?: "Review the attack." }
        }, enabled = plan != null && !saving && draft == null && rollDraft.canSave,
            modifier = Modifier.fillMaxWidth().testTag("genesis-armor-roll")) { Text(if (blast) "Roll Discharge" else "Roll armor attack & damage") }
        if (blast) Text("Discharge preserves the configured 3× primary Force formula and ten-minute game-time overload. Its conflicting later source remains a configuration review.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (latest != null) {
        RollResultCard(latest)
        if (latest.plan.blast && latest.result == null && latest.rolled != null)
            PlayerOutlinedButton(onClick = { resolve = latest.plan.id }, enabled = !saving) { Text("Record saving throws") }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (elementPicker && state != null) ElementWheelV2(state.profile, armor.elements, { elementPicker = false }) { type ->
        val elements = if (type == DamageType.FORCE) emptyList() else elementSelectionAfterChoice(state.profile, armor.elements, type)
        onSave(character.copy(armor = armor.copy(elements = elements)), "Changed active armor element")
        if (ability in listOf(Ability.ARCANE, Ability.ELEMENTAL)) ability = if (elements.isEmpty()) Ability.ARCANE else Ability.ELEMENTAL
        elementPicker = false
    }
    configure?.let { section -> GenesisArmorConfigurationEditor(character, section, { configure = null }, onSave) }
    resolve?.let { id -> armor.campaign.pending.firstOrNull { it.plan.id == id }?.let { shot ->
        GenesisArmorSaveResolution(character, shot, { resolve = null }, onSave, saving)
    } }
    draft?.let { pending ->
        val stale = pending.plan.revision != GenesisArmorRules.revision(character)
        PlayerSheet("Armor roll", { if (stale) draft = null }, dirty = false, dismissEnabled = stale && !saving,
            actions = {
                PlayerButton(enabled = !saving && !stale && rollDraft.canSave, onClick = {
                    try {
                        val next = GenesisArmorRules.commitRolled(character, pending.plan, requireNotNull(pending.rolled))
                        onRoll(next, genesisArmorRollRecord(character, pending))
                    } catch (e: Exception) { error = e.message ?: "The roll could not be saved." }
                }) { Text("Retry saving the same roll") }
            }) {
            RollResultCard(pending)
            Text(if (saving) "Saving this roll and its costs together…" else "This roll is retained until its resource transaction is saved. Retrying never rerolls the dice.", color = RebuildPalette.Muted)
            Text(pending.plan.actionCost)
            if (pending.plan.hasSpell) Text("Consumes selected payloads${pending.plan.immediate?.let { " and one level-${it.castingLevel} slot" }.orEmpty()}.")
            if (pending.plan.blast) Text("Exhausts the internal nexus for 600 game seconds. Target saves are recorded after saving this roll.")
            if (stale) Text("The character changed after this roll. These faces are retained, but this action cannot spend resources against a different revision.", color = MaterialTheme.colorScheme.error)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Surface only active effects whose application is currently unsupported by the specialized engine. */
internal fun genesisArmorUnmappedEffects(character: ReforgeCharacter, ability: Ability): List<String> {
    val context = GenesisArmorRules.attackContext(ability)
    val active = CharacterCalculator.calculate(character, context.copy(successfulHit = true)).effectStatuses.filter { it.active }.map { it.effectId }.toSet()
    val effects = character.effects + character.itemDefinitions.flatMap { it.effects }
    val key = "armor:${ability.name}"
    val tags = context.tags
    return effects.filter { it.id in active }.filter { effect ->
        when (val payload = effect.payload) {
            is EffectPayload.Number -> payload.target in setOf(NumericTarget.ATTACK, NumericTarget.DAMAGE) && (payload.key.isBlank() || payload.key == key)
            is EffectPayload.DamageBonus -> (payload.qualifier.attackIds.isEmpty() || key in payload.qualifier.attackIds) && tags.containsAll(payload.qualifier.requiredTags)
            is EffectPayload.DamageConversion -> (payload.qualifier.attackIds.isEmpty() || key in payload.qualifier.attackIds) && tags.containsAll(payload.qualifier.requiredTags)
            else -> false
        }
    }.map { it.name }.distinct()
}

internal fun genesisArmorRollRecord(character: ReforgeCharacter, pending: PendingShot): ReforgeRollRecord {
    val plan = pending.plan
    val roll = requireNotNull(pending.rolled)
    val kept = when (roll.mode) {
        RollMode.ADVANTAGE -> roll.attackFaces.indexOf(roll.attackFaces.maxOrNull())
        RollMode.DISADVANTAGE -> roll.attackFaces.indexOf(roll.attackFaces.minOrNull())
        else -> 0
    }
    val components = buildList {
        if (roll.attackFaces.isNotEmpty()) add(ReforgeRollComponent("Attack", roll.attackFaces.mapIndexed { index, face ->
            ReforgeRollDie(20, face, kept = index == kept)
        }, plan.attackBonus))
        Engine.components(plan, roll.critical).forEach { component ->
            val result = roll.damage.getValue(component.id)
            add(ReforgeRollComponent(component.label, result.faces.map { ReforgeRollDie(component.damage.dice.sides, it) },
                result.flat, component.damage.type.name))
        }
    }
    return ReforgeRollRecord(plan.id, character.id, character.revision, "Astravar · ${plan.ability.title}",
        if (roll.attackFaces.isEmpty()) "Dexterity save DC ${plan.profile.saveDc}" else "1d20${reforgeSigned(plan.attackBonus)}",
        components, createdAt = roll.rolledAtMillis)
}

@Composable private fun GenesisArmorSaveResolution(character: ReforgeCharacter, pending: PendingShot, onDismiss: () -> Unit,
    onSave: (ReforgeCharacter, String) -> Unit, saving: Boolean) {
    val targetDraft = rememberReforgeDraft(emptyList<TargetSave>(), ListSerializer(TargetSave.serializer()))
    var targets by targetDraft
    var outcome by rememberSaveable { mutableStateOf(pending.rolled?.suggestedOutcome) }
    var error by remember { mutableStateOf("") }
    val feedback = LocalGenesisEditorFeedback.current
    var submitted by remember { mutableStateOf<Resolution?>(null) }
    val revision = rememberSaveable { character.revision }
    val stale = character.revision != revision
    LaunchedEffect(character.revision, pending.result) {
        if (submitted != null && pending.result == submitted) onDismiss()
    }
    PlayerSheet("Record Discharge saves", onDismiss, dirty = targets.isNotEmpty(), dismissEnabled = !saving,
        actions = {
            PlayerButton(enabled = !saving && !stale && targetDraft.canSave, onClick = {
                try {
                    val state = GenesisArmorRules.runtime(character)
                    val result = DiceRoller.resolution(pending.plan, requireNotNull(pending.rolled), outcome, targets)
                    submitted = result
                    onSave(GenesisArmorRules.apply(character, Engine.resolve(state, pending.plan.id, result)), "Recorded Discharge saves")
                } catch (e: Exception) { error = e.message ?: "Record each actual save result." }
            }) { Text("Save target results") }
        }) {
        ReforgeDraftRecovery(targetDraft)
        Text("DC ${pending.plan.profile.saveDc} Dexterity. Enter each creature's actual result; the app does not roll those saves.")
        if (pending.plan.directImpact && pending.rolled?.suggestedOutcome == null)
            Choices("DM's direct-impact result", listOf(Outcome.HIT, Outcome.MISS), outcome) { outcome = it }
        targets.forEachIndexed { index, target ->
            PlayerCard {
                Field("Creature ${index + 1}", target.name, { text -> targets = targets.mapIndexed { i, value -> if (i == index) value.copy(name = text) else value } })
                Choices("Save result", listOf(false, true), target.saved, display = { if (it) "Passed" else "Failed" }) { saved ->
                    targets = targets.mapIndexed { i, value -> if (i == index) value.copy(saved = saved) else value }
                }
                if (pending.plan.directImpact) Toggle("Direct-impact target", target.directTarget) { direct ->
                    targets = targets.mapIndexed { i, value -> value.copy(directTarget = direct && i == index) }
                }
                PlayerTextButton(onClick = { targets = targets.filterIndexed { i, _ -> i != index } }) { Text("Remove creature") }
            }
        }
        PlayerOutlinedButton(enabled = targets.size < 200, onClick = { targets = targets + TargetSave("Creature ${targets.size + 1}", null) }) { Text("Add affected creature") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (feedback.error.isNotBlank()) Text(feedback.error, color = MaterialTheme.colorScheme.error)
        if (stale && pending.result != submitted) Text("Character changed. Your entered saves remain here; close and reopen the current result before saving.", color = MaterialTheme.colorScheme.error)
    }
}
