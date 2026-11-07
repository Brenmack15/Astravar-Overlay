package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.reforge.*
import java.util.UUID

internal val reforgeSkills = linkedMapOf(
    "Acrobatics" to Stat.DEX, "Animal Handling" to Stat.WIS, "Arcana" to Stat.INT,
    "Athletics" to Stat.STR, "Deception" to Stat.CHA, "History" to Stat.INT,
    "Insight" to Stat.WIS, "Intimidation" to Stat.CHA, "Investigation" to Stat.INT,
    "Medicine" to Stat.WIS, "Nature" to Stat.INT, "Perception" to Stat.WIS,
    "Performance" to Stat.CHA, "Persuasion" to Stat.CHA, "Religion" to Stat.INT,
    "Sleight of Hand" to Stat.DEX, "Stealth" to Stat.DEX, "Survival" to Stat.WIS
)
/** Keep the opened revision through recreation without putting a full campaign aggregate in Bundle state. */
@Composable internal fun rememberReforgeSnapshot(value: ReforgeCharacter?): ReforgeCharacter? {
    val revision = rememberSaveable { value?.revision ?: -1L }
    val opened = remember { value }
    return opened?.copy(revision = if (revision >= 0) revision else opened.revision)
}

internal fun reforgeSigned(value: Int?): String = value?.let { if (it >= 0) "+$it" else "$it" } ?: "?"
internal fun optionalInt(value: String, label: String, min: Int = 0, max: Int = 1000): Int? =
    value.trim().takeIf { it.isNotEmpty() }?.let { text ->
        (text.toIntOrNull() ?: error("$label must be a whole number")).also {
            require(it in min..max) { "$label must be $min–$max" }
        }
    }

@Composable internal fun ReforgeCharacterEditor(
    latestCharacter: ReforgeCharacter?, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit
) {
    val character = rememberReforgeSnapshot(latestCharacter)
    var name by rememberSaveable { mutableStateOf(character?.name ?: "") }
    var level by rememberSaveable { mutableStateOf(character?.level?.toString() ?: "") }
    var maxHp by rememberSaveable { mutableStateOf(character?.baseMaxHp?.toString() ?: "") }
    val initialCurrentHp = remember(character) { character?.let { CharacterCalculator.calculate(it).currentHp } }
    var hp by rememberSaveable { mutableStateOf(initialCurrentHp?.toString() ?: "") }
    var temp by rememberSaveable { mutableStateOf(character?.temporaryHp?.toString() ?: "") }
    var ac by rememberSaveable { mutableStateOf(character?.baseAc?.toString() ?: "") }
    var speed by rememberSaveable { mutableStateOf(character?.speed?.toString() ?: "") }
    var casterLevel by rememberSaveable { mutableStateOf(character?.spellcastingClassLevel?.toString() ?: "") }
    var rules by rememberSaveable { mutableStateOf(character?.rules?.classVersion ?: "unconfigured") }
    var scores by rememberSaveable { mutableStateOf(Stat.entries.associate { it.name to (character?.baseAbilities?.get(it)?.toString() ?: "") }) }
    var error by remember { mutableStateOf("") }
    PlayerSheet(if (character == null) "Create character" else "Character setup", onDismiss, dirty = true,
        actions = {
            PlayerButton(onClick = {
                try {
                    require(name.isNotBlank()) { "Enter a character name" }
                    val parsedScores = Stat.entries.associateWith { optionalInt(scores[it.name].orEmpty(), it.name, 0, 100) }
                    val conMod = parsedScores[Stat.CON]?.let { Math.floorDiv(it - 10, 2) }
                    val base = character ?: ReforgeCharacter(id = UUID.randomUUID().toString(), name = name.trim())
                    val baselineEdited = maxHp != (character?.baseMaxHp?.toString() ?: "")
                    val hpEdited = hp != (initialCurrentHp?.toString() ?: "")
                    val candidate = base.copy(name = name.trim(), level = optionalInt(level, "Level", 1, 20) ?: error("Enter a level"),
                        baseAbilities = parsedScores, baseMaxHp = optionalInt(maxHp, "Baseline maximum HP", 1, 10000),
                        baselineConstitutionModifier = if (character == null || baselineEdited) conMod else base.baselineConstitutionModifier,
                        currentHp = if (hpEdited || character == null) optionalInt(hp, "Current HP", 0, 10000) else base.currentHp,
                        hpDamage = if (hpEdited) null else base.hpDamage, temporaryHp = optionalInt(temp, "Temporary HP", 0, 10000),
                        baseAc = optionalInt(ac, "Baseline Armor Class", 0, 100), speed = optionalInt(speed, "Speed", 0, 1000),
                        spellcastingClassLevel = optionalInt(casterLevel, "Artificer spellcasting level", 1, 20),
                        rules = base.rules.copy(classVersion = rules.trim()))
                    val preserved = if (character != null && !hpEdited) GameplayRules.preserveCapacities(base, candidate) else candidate
                    val projected = CharacterCalculator.calculate(preserved)
                    val currentValue = preserved.currentHp
                    val maximumValue = projected.maximumHp.value
                    require(currentValue == null || maximumValue == null || currentValue <= maximumValue) { "Current HP exceeds the new maximum" }
                    onSave(preserved)
                } catch (e: Exception) { error = e.message ?: "Check these values" }
            }) { Text("Save character") }
        }) {
        Text("Record permanent abilities and an unequipped HP/AC baseline. Blank means unknown. Equipment effects belong on items.", color = RebuildPalette.Muted)
        Field("Name", name, { name = it })
        Field("Level", level, { level = it })
        Stat.entries.chunked(if (LocalDensity.current.fontScale >= 1.5f) 2 else 3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { stat -> Field(stat.name, scores[stat.name].orEmpty(), { scores = scores + (stat.name to it) }, Modifier.weight(1f)) }
        } }
        Field("Unequipped maximum HP", maxHp, { maxHp = it })
        Field("Current HP (blank = unknown)", hp, { hp = it })
        Field("Temporary HP", temp, { temp = it })
        Field("Unequipped Armor Class", ac, { ac = it })
        Field("Walking speed (ft.)", speed, { speed = it })
        PlayerDisclosure("Spellcasting & rule versions") {
            Text("The Infusions-based Artificer is preserved. Setting its class level enables its slot/preparation table; leave blank for other classes until their capacity is configured.", color = RebuildPalette.Muted)
            Field("Legacy Artificer class level", casterLevel, { casterLevel = it })
            Field("Pinned class version", rules, { rules = it })
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeAbilityInspector(latestCharacter: ReforgeCharacter, projection: CharacterProjection, stat: Stat,
    onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, onRoll: (String, Int) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val value = projection.abilities.getValue(stat)
    var score by rememberSaveable { mutableStateOf(character.baseAbilities[stat]?.toString() ?: "") }
    var error by remember { mutableStateOf("") }
    PlayerSheet("${stat.name} · ${value.effective.value ?: "?"} (${reforgeSigned(value.modifier)})", onDismiss,
        dirty = score != (character.baseAbilities[stat]?.toString() ?: ""), actions = {
            PlayerTextButton(onClick = { ConditionRules.abilityCheckModifier(character, stat)?.let { onRoll("${stat.name} check", it) } }, enabled = value.modifier != null) { Text("Roll check") }
            PlayerButton(onClick = {
                try { onSave(GameplayRules.preserveCapacities(character, character.copy(baseAbilities = character.baseAbilities + (stat to optionalInt(score, stat.name, 0, 100))))) }
                catch (e: Exception) { error = e.message ?: "Check score" }
            }) { Text("Save score") }
        }) {
        Text("Permanent ${value.permanent ?: "unknown"} → effective ${value.effective.value ?: "unknown"}", style = MaterialTheme.typography.titleMedium)
        Field("Permanent base score", score, { score = it })
        Text("Active calculation", color = RebuildPalette.Ice)
        value.effective.explanation.forEach { line ->
            Text("${if (line.applied) "•" else "○"} ${line.label}${line.value?.let { ": $it" } ?: ""}${line.reason.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", color = if (line.applied) MaterialTheme.colorScheme.onSurface else RebuildPalette.Muted)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeTrainingEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    var training by remember { mutableStateOf(character.skills) }
    var saves by remember { mutableStateOf(character.saveProficiencies) }
    PlayerSheet("Proficiencies & Expertise", onDismiss, dirty = training != character.skills || saves != character.saveProficiencies,
        actions = { PlayerButton(onClick = { onSave(character.copy(skills = training, saveProficiencies = saves)) }) { Text("Save training") } }) {
        Text("Saving throws", style = MaterialTheme.typography.titleMedium)
        Stat.entries.forEach { stat -> Toggle(stat.name, stat in saves) { saves = if (it) saves + stat else saves - stat } }
        Text("Skills", style = MaterialTheme.typography.titleMedium)
        reforgeSkills.forEach { (name, ability) ->
            val current = training[name] ?: SkillTraining(ability)
            Choices(name, listOf("Untrained", "Proficient", "Expertise", "Half proficiency"), when {
                current.expertise -> "Expertise"; current.proficient -> "Proficient"; current.halfProficiency -> "Half proficiency"; else -> "Untrained"
            }) { choice -> training = training + (name to SkillTraining(ability, choice == "Proficient" || choice == "Expertise", choice == "Expertise", choice == "Half proficiency")) }
        }
    }
}

internal fun reforgeEffectSummary(effect: EffectDefinition): String = when (val payload = effect.payload) {
    is EffectPayload.Number -> "${payload.target.name.lowercase().replace('_', ' ')} ${payload.key}: ${payload.operation.name.lowercase()} ${payload.amount}"
    is EffectPayload.DamageBonus -> "+${SecureDice.format(payload.component.formula)} ${payload.component.type.name.lowercase()} damage"
    is EffectPayload.Defense -> "${payload.type.name.lowercase()} ${payload.kind.name.lowercase()}"
    is EffectPayload.Edge -> "${payload.edge.name.lowercase()} on ${payload.kind.name.lowercase().replace('_', ' ')}"
    is EffectPayload.Proficiency -> if (payload.expertise) "Expertise · ${payload.skill ?: payload.save}" else "Proficiency · ${payload.skill ?: payload.save}"
    is EffectPayload.ConditionImmunity -> "Immune to ${payload.condition}"
    is EffectPayload.DamageConversion -> "${payload.from} becomes ${payload.to}"
}

/** Typed effect entry requires explicit review; narrative text never activates a modifier. */
@Composable internal fun ReforgeItemEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit,
    onSave: (ReforgeCharacter) -> Unit, existingItemId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.items.firstOrNull { it.id == existingItemId } }
    val originalDefinition = remember { character.itemDefinitions.firstOrNull { it.id == original?.definitionId } }
    var name by rememberSaveable { mutableStateOf(originalDefinition?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(originalDefinition?.description ?: "") }
    var attune by rememberSaveable { mutableStateOf(originalDefinition?.requiresAttunement ?: false) }
    var slot by rememberSaveable { mutableStateOf(originalDefinition?.equipmentSlot ?: "") }
    var quantity by rememberSaveable { mutableStateOf(original?.quantity?.toString() ?: "1") }
    var favorite by rememberSaveable { mutableStateOf(original?.favorite ?: false) }
    var state by remember { mutableStateOf(original?.state ?: ItemState.CARRIED) }
    var target by remember { mutableStateOf(NumericTarget.ABILITY) }
    var operation by remember { mutableStateOf(NumberOperation.SET) }
    var ability by remember { mutableStateOf(Stat.STR) }
    var amount by rememberSaveable { mutableStateOf("") }
    var effectName by rememberSaveable { mutableStateOf("") }
    var effects by remember { mutableStateOf(originalDefinition?.effects ?: listOf()) }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var effectType by rememberSaveable { mutableStateOf("Statistic") }
    var damageKind by remember { mutableStateOf(DamageKind.COLD) }
    var damageDice by rememberSaveable { mutableStateOf("1d6") }
    var defense by remember { mutableStateOf(DefenseKind.RESISTANCE) }
    var edge by remember { mutableStateOf(RollEdge.ADVANTAGE) }
    var rollKind by remember { mutableStateOf(RollKind.ABILITY_CHECK) }
    var qualifier by rememberSaveable { mutableStateOf<String?>(null) }
    var resourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var chargeCost by rememberSaveable { mutableStateOf("1") }
    var once by rememberSaveable { mutableStateOf(false) }
    var critical by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val definitionId = remember { originalDefinition?.id ?: UUID.randomUUID().toString() }
    val instanceId = remember { original?.id ?: UUID.randomUUID().toString() }
    PlayerSheet(if (original == null) "Add equipment" else "Edit equipment", onDismiss, dirty = true, actions = {
        PlayerButton(enabled = name.isNotBlank() && reviewed, onClick = {
            try {
                val definition = (originalDefinition ?: ItemDefinition(definitionId, name)).copy(name = name.trim(), effects = effects,
                    requiresAttunement = attune, equipmentSlot = slot.trim().ifBlank { null }, description = description)
                val item = (original ?: ItemInstance(instanceId, definitionId)).copy(quantity = optionalInt(quantity, "Quantity", 0, 100000) ?: error("Enter quantity"), favorite = favorite)
                val candidate = character.copy(itemDefinitions = character.itemDefinitions.filterNot { it.id == definitionId } + definition,
                    items = character.items.filterNot { it.id == instanceId } + item)
                val equipped = GameplayRules.equip(candidate, instanceId, state)
                onSave(GameplayRules.preserveCapacities(character, equipped))
            } catch (e: Exception) { error = e.message ?: "Check item" }
        }) { Text("Save reviewed item") }
    }) {
        Field("Item name", name, { name = it; reviewed = false })
        Field("Description", description, { description = it }, single = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Quantity", quantity, { quantity = it; reviewed = false }, Modifier.weight(1f))
            Field("Slot (optional)", slot, { slot = it; reviewed = false }, Modifier.weight(1f))
        }
        Choices("Location / equipment", ItemState.entries, state) { state = it; reviewed = false }
        Toggle("Favorite", favorite) { favorite = it }
        Toggle("Requires attunement", attune) { attune = it; reviewed = false }
        Text("Mechanical effects", style = MaterialTheme.typography.titleMedium)
        effects.forEachIndexed { index, effect ->
            Text("${index + 1}. ${effect.name} · ${reforgeEffectSummary(effect)}", style = MaterialTheme.typography.bodySmall)
            PlayerTextButton(onClick = { effects = effects - effect; reviewed = false }) { Text("Remove effect") }
        }
        PlayerDisclosure("Add a structured effect") {
            Field("Effect name", effectName, { effectName = it })
            Choices("Effect type", listOf("Statistic", "Damage dice", "Damage defense", "Advantage / Disadvantage"), effectType) { effectType = it }
            when (effectType) {
                "Statistic" -> {
                    Choices("Statistic", listOf(NumericTarget.ABILITY, NumericTarget.ARMOR_CLASS, NumericTarget.MAX_HP, NumericTarget.INITIATIVE, NumericTarget.SPEED, NumericTarget.SPELL_ATTACK, NumericTarget.SPELL_DC), target) { target = it }
                    if (target == NumericTarget.ABILITY) Choices("Ability", Stat.entries, ability) { ability = it }
                    Choices("Operation", NumberOperation.entries, operation) { operation = it }
                    Field("Value", amount, { amount = it })
                }
                "Damage dice" -> {
                    Field("Additional dice", damageDice, { damageDice = it })
                    Choices("Damage type", DamageKind.entries, damageKind) { damageKind = it }
                    Toggle("Double dice on a critical hit", critical) { critical = it }
                    Choices("Qualifying attack", listOf<String?>(null) + character.attacks.map { it.id }, qualifier,
                        display = { id -> character.attacks.firstOrNull { it.id == id }?.name ?: "All attacks" }) { qualifier = it }
                    Choices("Charge resource", listOf<String?>(null) + character.resources.map { it.id }, resourceId,
                        display = { id -> character.resources.firstOrNull { it.id == id }?.name ?: "No charge" }) { resourceId = it }
                    if (resourceId != null) Field("Charges per successful hit", chargeCost, { chargeCost = it })
                    Toggle("Once per turn", once) { once = it }
                }
                "Damage defense" -> { Choices("Damage type", DamageKind.entries, damageKind) { damageKind = it }; Choices("Defense", DefenseKind.entries, defense) { defense = it } }
                else -> { Choices("Roll", RollKind.entries, rollKind) { rollKind = it }; Choices("Edge", RollEdge.entries, edge) { edge = it } }
            }
            PlayerOutlinedButton(onClick = {
                try {
                    val id = UUID.randomUUID().toString()
                    val payload = when (effectType) {
                        "Statistic" -> EffectPayload.Number(target, operation, optionalInt(amount, "Effect value", -100, 10000) ?: error("Enter a value"), if (target == NumericTarget.ABILITY) ability.name else if (target == NumericTarget.SPEED) "walk" else "")
                        "Damage dice" -> EffectPayload.DamageBonus(DamageComponent("$id:damage", effectName.ifBlank { "Additional damage" }, damageKind, SecureDice.parse(damageDice), critical), AttackQualifier(attackIds = qualifier?.let { setOf(it) } ?: emptySet()))
                        "Damage defense" -> EffectPayload.Defense(damageKind, defense)
                        else -> EffectPayload.Edge(rollKind, edge)
                    }
                    val conditions = if (effectType == "Damage dice") listOf(EffectCondition(ConditionKind.SUCCESSFUL_HIT)) + if (once) listOf(EffectCondition(ConditionKind.ONCE_PER_TURN)) else emptyList() else emptyList()
                    effects = effects + EffectDefinition(id, effectName.trim().ifBlank { effectType }, RuleSource("user:$id", name.ifBlank { "User-entered equipment" }, Acceptance.ACCEPTED_HOMEBREW),
                        payload, conditions, requiresAttunement = attune,
                        chargeResourceId = if (effectType == "Damage dice") resourceId else null,
                        chargeCost = if (effectType == "Damage dice" && resourceId != null) optionalInt(chargeCost, "Charge cost", 1, 1000) ?: error("Enter charge cost") else 0)
                    reviewed = false; amount = ""; effectName = ""; error = ""
                } catch (e: Exception) { error = e.message ?: "Check effect" }
            }) { Text("Add effect to review") }
        }
        Text("Equipment requirements, attunement and active conditions are checked by the character engine. This edits the existing item identity when applicable.", color = RebuildPalette.Muted)
        Toggle("I reviewed these mechanics", reviewed) { reviewed = it }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeAttackEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, existingAttackId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.attacks.firstOrNull { it.id == existingAttackId } }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var ability by remember { mutableStateOf(original?.ability ?: Stat.STR) }
    var formula by rememberSaveable { mutableStateOf(original?.damage?.firstOrNull()?.formula?.let(SecureDice::format) ?: "") }
    var kind by remember { mutableStateOf(original?.damage?.firstOrNull()?.type ?: DamageKind.BLUDGEONING) }
    var range by rememberSaveable { mutableStateOf(original?.range ?: "5 ft.") }
    var proficient by rememberSaveable { mutableStateOf(original?.proficient ?: true) }
    var addAbility by rememberSaveable { mutableStateOf(original?.addAbilityToDamage ?: true) }
    var error by remember { mutableStateOf("") }
    PlayerSheet(if (original == null) "Add attack" else "Edit attack", onDismiss, dirty = name.isNotBlank(), actions = {
        PlayerButton(enabled = name.isNotBlank(), onClick = {
            try {
                val id = original?.id ?: UUID.randomUUID().toString()
                val dice = SecureDice.parse(formula)
                val attack = (original ?: AttackDefinition(id, name.trim())).copy(name = name.trim(), ability = ability, proficient = proficient,
                    damage = listOf(DamageComponent(original?.damage?.firstOrNull()?.id ?: "$id:base", name.trim(), kind, dice)) + (original?.damage?.drop(1) ?: emptyList()),
                    addAbilityToDamage = addAbility, range = range)
                onSave(character.copy(attacks = character.attacks.filterNot { it.id == attack.id } + attack))
            } catch (e: Exception) { error = e.message ?: "Check attack" }
        }) { Text("Save attack") }
    }) {
        Field("Name", name, { name = it })
        Choices("Attack ability", Stat.entries, ability) { ability = it }
        Field("Base damage dice (for example 1d8)", formula, { formula = it })
        Choices("Damage type", DamageKind.entries, kind) { kind = it }
        Toggle("Proficient", proficient) { proficient = it }
        Toggle("Add ability modifier to damage", addAbility) { addAbility = it }
        Field("Range", range, { range = it })
        Text("Critical hits double eligible dice. Damage bonuses are resolved through active structured effects.", color = RebuildPalette.Muted)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeSpellEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, existingSpellId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.spells.firstOrNull { it.id == existingSpellId } }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var level by rememberSaveable { mutableStateOf(original?.level?.toString() ?: "") }
    var preparation by remember { mutableStateOf(original?.preparation ?: SpellPreparation.KNOWN) }
    var concentration by rememberSaveable { mutableStateOf(original?.concentration ?: false) }
    var description by rememberSaveable { mutableStateOf(original?.description ?: "") }
    var components by rememberSaveable { mutableStateOf(original?.components ?: "") }
    var range by rememberSaveable { mutableStateOf(original?.range ?: "") }
    var duration by rememberSaveable { mutableStateOf(original?.duration ?: "") }
    var castingTime by rememberSaveable { mutableStateOf(original?.castingTime ?: "1 Action") }
    var sourceVersion by rememberSaveable { mutableStateOf(original?.sourceVersion ?: "user-owned") }
    var slotRequired by rememberSaveable { mutableStateOf(original?.slotRequired ?: true) }
    var ritual by rememberSaveable { mutableStateOf(original?.ritual ?: false) }
    var resourceId by rememberSaveable { mutableStateOf(original?.resourceId) }
    var resourceCost by rememberSaveable { mutableStateOf(original?.resourceCost?.toString() ?: "1") }
    var error by remember { mutableStateOf("") }
    PlayerSheet(if (original == null) "Add spell" else "Edit spell", onDismiss, dirty = name.isNotBlank(), actions = {
        PlayerButton(enabled = name.isNotBlank(), onClick = {
            try {
                val spellLevel = optionalInt(level, "Spell level", 0, 9) ?: error("Enter a spell level")
                val spell = SpellDefinition(original?.id ?: UUID.randomUUID().toString(), name.trim(), spellLevel, preparation, concentration, castingTime = castingTime,
                    range = range, components = components, duration = duration, description = description, slotRequired = spellLevel > 0 && slotRequired, sourceVersion = sourceVersion, ritual = ritual, resourceId = resourceId, resourceCost = optionalInt(resourceCost, "Resource cost", 0, 1000) ?: 1)
                onSave(character.copy(spells = character.spells.filterNot { it.id == spell.id } + spell))
            } catch (e: Exception) { error = e.message ?: "Check spell" }
        }) { Text("Save spell") }
    }) {
        Text("Enter your own spell reference. No commercial compendium is copied or fetched.", color = RebuildPalette.Muted)
        Field("Name", name, { name = it }); Field("Level (0 = cantrip)", level, { level = it })
        Choices("Preparation", SpellPreparation.entries, preparation) { preparation = it }
        Toggle("Concentration", concentration) { concentration = it }
        Field("Casting time", castingTime, { castingTime = it })
        Toggle("Requires a spell slot", slotRequired) { slotRequired = it }
        Toggle("Ritual casting allowed", ritual) { ritual = it }
        Choices("Item or feature resource", listOf<String?>(null) + character.resources.map { it.id }, resourceId,
            display = { id -> character.resources.firstOrNull { it.id == id }?.name ?: "No extra resource" }) { resourceId = it }
        if (resourceId != null) Field("Resource cost", resourceCost, { resourceCost = it })
        Field("Rule version / owned source", sourceVersion, { sourceVersion = it })
        Field("Range", range, { range = it }); Field("Components", components, { components = it }); Field("Duration", duration, { duration = it })
        Field("Description", description, { description = it }, single = false)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeResourceEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, existingResourceId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.resources.firstOrNull { it.id == existingResourceId } }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var maximum by rememberSaveable { mutableStateOf(original?.maximum?.toString() ?: "") }
    var current by rememberSaveable { mutableStateOf(original?.current?.toString() ?: "") }
    var recharge by remember { mutableStateOf(original?.recharge ?: Recharge.NONE) }
    var recovery by rememberSaveable { mutableStateOf(original?.recoveryAmount?.toString() ?: "") }
    var dieSides by rememberSaveable { mutableStateOf(original?.dieSides?.toString() ?: "") }
    var error by remember { mutableStateOf("") }
    PlayerSheet(if (original == null) "Add resource" else "Edit resource", onDismiss, dirty = name.isNotBlank(), actions = {
        PlayerButton(enabled = name.isNotBlank(), onClick = {
            try {
                val pool = ResourcePool(original?.id ?: UUID.randomUUID().toString(), name.trim(), optionalInt(maximum, "Capacity", 0, 10000),
                    optionalInt(current, "Current uses", 0, 10000), recharge, optionalInt(recovery, "Recovered uses", 0, 10000), restoreUnknownOnRest = original?.restoreUnknownOnRest ?: false, dieSides = optionalInt(dieSides, "Hit Die sides", 2, 1000))
                val currentValue = pool.current
                val maximumValue = pool.maximum
                require(currentValue == null || maximumValue == null || currentValue <= maximumValue) { "Current uses exceed capacity" }
                onSave(character.copy(resources = character.resources.filterNot { it.id == pool.id } + pool))
            } catch (e: Exception) { error = e.message ?: "Check resource" }
        }) { Text("Save resource") }
    }) {
        Field("Name", name, { name = it }); Field("Capacity", maximum, { maximum = it }); Field("Current uses (blank = unknown)", current, { current = it })
        Field("Hit Die sides (optional, for example 8)", dieSides, { dieSides = it })
        Choices("Recharge", Recharge.entries, recharge) { recharge = it }
        Field("Uses recovered (blank = full capacity)", recovery, { recovery = it })
        Text("Creating or editing this resource does not complete a rest. Unknown current values remain unknown after recovery.", color = RebuildPalette.Muted)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
