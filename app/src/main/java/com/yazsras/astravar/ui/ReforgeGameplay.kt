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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

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

internal fun reforgeSigned(value: Int?): String = value?.let { if (it >= 0) "+$it" else "$it" } ?: "Not set"
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
    val draftCharacterId = rememberSaveable { character?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(character?.name ?: "") }
    var level by rememberSaveable { mutableStateOf(character?.level?.toString() ?: "1") }
    var species by rememberSaveable { mutableStateOf(character?.species.orEmpty()) }
    var className by rememberSaveable { mutableStateOf(character?.className.orEmpty()) }
    var subclass by rememberSaveable { mutableStateOf(character?.subclass.orEmpty()) }
    val backgroundDraft = rememberReforgeDraft(character?.background.orEmpty(), String.serializer())
    var background by backgroundDraft
    var maxHp by rememberSaveable { mutableStateOf(character?.baseMaxHp?.toString() ?: "") }
    val initialCurrentHp = remember(character) { character?.let { CharacterCalculator.calculate(it).currentHp } }
    var hp by rememberSaveable { mutableStateOf(initialCurrentHp?.toString() ?: "") }
    var temp by rememberSaveable { mutableStateOf(character?.temporaryHp?.toString() ?: "") }
    var ac by rememberSaveable { mutableStateOf(character?.baseAc?.toString() ?: "") }
    var speed by rememberSaveable { mutableStateOf(character?.speed?.toString() ?: "") }
    var casterLevel by rememberSaveable { mutableStateOf(character?.spellcastingClassLevel?.toString() ?: "") }
    var castingAbility by rememberSaveable { mutableStateOf(character?.spellcastingAbility ?: Stat.INT) }
    var attunementLimit by rememberSaveable { mutableStateOf(character?.attunementLimit?.toString() ?: "3") }
    var armorDex by rememberSaveable { mutableStateOf(character?.armorUsesDexterity ?: false) }
    var armorDexCap by rememberSaveable { mutableStateOf(character?.armorDexterityCap?.toString() ?: "") }
    var rules by rememberSaveable { mutableStateOf(character?.rules?.classVersion ?: "unconfigured") }
    var scores by rememberSaveable { mutableStateOf(Stat.entries.associate { it.name to (character?.baseAbilities?.get(it)?.toString() ?: "") }) }
    var error by remember { mutableStateOf("") }
    val dirty = name != character?.name.orEmpty() || level != (character?.level?.toString() ?: "1") ||
        species != character?.species.orEmpty() || className != character?.className.orEmpty() ||
        subclass != character?.subclass.orEmpty() || background != character?.background.orEmpty() ||
        maxHp != character?.baseMaxHp?.toString().orEmpty() || hp != initialCurrentHp?.toString().orEmpty() ||
        temp != character?.temporaryHp?.toString().orEmpty() || ac != character?.baseAc?.toString().orEmpty() ||
        speed != character?.speed?.toString().orEmpty() || casterLevel != character?.spellcastingClassLevel?.toString().orEmpty() ||
        castingAbility != (character?.spellcastingAbility ?: Stat.INT) || attunementLimit != (character?.attunementLimit?.toString() ?: "3") ||
        armorDex != (character?.armorUsesDexterity ?: false) || armorDexCap != character?.armorDexterityCap?.toString().orEmpty() ||
        rules != (character?.rules?.classVersion ?: "unconfigured") ||
        scores.any { (key, value) -> value != Stat.valueOf(key).let { character?.baseAbilities?.get(it)?.toString().orEmpty() } }
    GenesisEditorSheet(if (character == null) "Create character" else "Character setup",
        sections = listOf("Identity", "Ability scores", "Health & defense", "Spellcasting", "Review"),
        creating = character == null, context = if (character == null) "Create your character. Optional values can stay unknown until you know them." else "Editing ${character.name}. Review the sections you want to change.",
        onDismiss = onDismiss, dirty = dirty, saveLabel = "Save character", saveEnabled = name.isNotBlank() && backgroundDraft.canSave, error = error,
        onSave = {
                try {
                    require(name.isNotBlank()) { "Enter a character name" }
                    val parsedScores = Stat.entries.associateWith { optionalInt(scores[it.name].orEmpty(), it.name, 0, 100) }
                    val conMod = parsedScores[Stat.CON]?.let { Math.floorDiv(it - 10, 2) }
                    val base = character ?: ReforgeCharacter(id = draftCharacterId, name = name.trim())
                    val baselineEdited = maxHp != (character?.baseMaxHp?.toString() ?: "")
                    val hpEdited = hp != (initialCurrentHp?.toString() ?: "")
                    val candidate = base.copy(name = name.trim(), species = species.trim(), className = className.trim(),
                        subclass = subclass.trim(), background = background.trim(),
                        level = optionalInt(level, "Level", 1, 20) ?: error("Enter a level"),
                        baseAbilities = parsedScores, baseMaxHp = optionalInt(maxHp, "Baseline maximum HP", 1, 10000),
                        baselineConstitutionModifier = if (character == null || baselineEdited) conMod else base.baselineConstitutionModifier,
                        currentHp = if (hpEdited || character == null) optionalInt(hp, "Current HP", 0, 10000) else base.currentHp,
                        hpDamage = if (hpEdited) null else base.hpDamage, temporaryHp = optionalInt(temp, "Temporary HP", 0, 10000),
                        baseAc = optionalInt(ac, "Baseline Armor Class", 0, 100), speed = optionalInt(speed, "Speed", 0, 1000),
                        armorUsesDexterity = armorDex, armorDexterityCap = optionalInt(armorDexCap, "Armor Dexterity cap", 0, 100),
                        attunementLimit = optionalInt(attunementLimit, "Attunement capacity", 0, 100) ?: error("Enter attunement capacity"),
                        spellcastingAbility = castingAbility,
                        spellcastingClassLevel = optionalInt(casterLevel, "Artificer spellcasting level", 1, 20),
                        rules = base.rules.copy(classVersion = rules.trim()))
                    if (candidate.attunementLimit != base.attunementLimit) {
                        val used = candidate.items.filter { it.attuned }.sumOf { item ->
                            candidate.itemDefinitions.find { it.id == item.definitionId }?.attunementCost
                                ?: error("An attuned item is missing its definition. Review equipment before changing capacity.")
                        }
                        require(used <= candidate.attunementLimit) { "End attunement to enough items before reducing capacity below $used." }
                    }
                    val preserved = if (character != null && !hpEdited) GameplayRules.preserveCapacities(base, candidate) else candidate
                    val projected = CharacterCalculator.calculate(preserved)
                    val currentValue = preserved.currentHp
                    val maximumValue = projected.maximumHp.value
                    require(currentValue == null || maximumValue == null || currentValue <= maximumValue) { "Current HP exceeds the new maximum" }
                    onSave(preserved)
                } catch (e: Exception) { error = e.message ?: "Check these values" }
        }) { section, select ->
        ReforgeDraftRecovery(backgroundDraft)
        when (section) {
            0 -> {
                if (character == null) LocalGenesisCharacterCreationFields.current()
                Field("Name", name, { name = it })
                Field("Level", level, { level = it })
                Field("Species", species, { species = it })
                Field("Class", className, { className = it })
                PlayerDisclosure("Subclass & background") {
                    Field("Subclass", subclass, { subclass = it })
                    Field("Background", background, { background = it }, single = false)
                }
                Text("Class and species describe your character. Their name alone never grants statistics, features, or spell slots.", color = RebuildPalette.Muted)
            }
            1 -> {
                Text("Record permanent scores before equipment. Leave any unknown score blank.", color = RebuildPalette.Muted)
                Stat.entries.chunked(if (LocalDensity.current.fontScale >= 1.5f) 2 else 3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { stat -> Field(stat.name, scores[stat.name].orEmpty(), { scores = scores + (stat.name to it) }, Modifier.weight(1f)) }
                } }
            }
            2 -> {
                Text("These are your baseline values before equipment effects. No healing or rest is assumed.", color = RebuildPalette.Muted)
                Field("Unequipped maximum HP", maxHp, { maxHp = it })
                Field("Current HP (blank = unknown)", hp, { hp = it })
                Field("Temporary HP", temp, { temp = it })
                Field("Unequipped Armor Class", ac, { ac = it })
                Toggle("Add Dexterity modifier to Armor Class", armorDex) { armorDex = it }
                if (armorDex) Field("Maximum Dexterity bonus (blank = no cap)", armorDexCap, { armorDexCap = it })
                Field("Walking speed (ft.)", speed, { speed = it })
                Field("Accepted attunement capacity", attunementLimit, { attunementLimit = it })
            }
            3 -> {
                Text("The Infusions-based Artificer is preserved. Setting its class level enables its slot/preparation table; leave blank for other classes until their capacity is configured.", color = RebuildPalette.Muted)
                Field("Legacy Artificer class level", casterLevel, { casterLevel = it })
                Choices("Spellcasting ability", Stat.entries, castingAbility) { castingAbility = it }
                Field("Pinned class version", rules, { rules = it })
            }
            else -> {
                if (character == null) LocalGenesisCharacterCreationReview.current()
                GenesisReviewSection("Identity", { select(0) }) {
                    GenesisReviewValue("Name", name)
                    GenesisReviewValue("Level", level)
                    GenesisReviewValue("Species / class", listOf(species, className, subclass).filter(String::isNotBlank).joinToString(" · "))
                    GenesisReviewValue("Background", background)
                }
                GenesisReviewSection("Ability scores", { select(1) }) {
                    Text(Stat.entries.joinToString(" · ") { "${it.name} ${scores[it.name]?.ifBlank { "?" } ?: "?"}" })
                }
                GenesisReviewSection("Health & defense", { select(2) }) {
                    GenesisReviewValue("Current / baseline maximum HP", "${hp.ifBlank { "?" }} / ${maxHp.ifBlank { "?" }}")
                    GenesisReviewValue("Temporary HP", temp)
                    GenesisReviewValue("Baseline AC", ac)
                    GenesisReviewValue("Dexterity to AC", if (armorDex) "Added · cap ${armorDexCap.ifBlank { "none" }}" else "Not added")
                    GenesisReviewValue("Walking speed (ft.)", speed)
                    GenesisReviewValue("Attunement capacity", attunementLimit)
                }
                GenesisReviewSection("Spellcasting", { select(3) }) {
                    GenesisReviewValue("Artificer spellcasting level", casterLevel)
                    GenesisReviewValue("Casting ability / rule version", "${castingAbility.name} · ${rules.ifBlank { "Not set" }}")
                }
                Text("Blank values stay unknown. Saving does not refill resources or complete a rest.", color = RebuildPalette.Muted)
            }
        }
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
    val trainingDraft = rememberReforgeDraft(character.skills, MapSerializer(String.serializer(), SkillTraining.serializer()))
    val savesDraft = rememberReforgeDraft(character.saveProficiencies, SetSerializer(Stat.serializer()))
    var training by trainingDraft
    var saves by savesDraft
    PlayerSheet("Proficiencies & Expertise", onDismiss, dirty = training != character.skills || saves != character.saveProficiencies,
        actions = { PlayerButton(enabled = trainingDraft.canSave && savesDraft.canSave, onClick = { onSave(character.copy(skills = training, saveProficiencies = saves)) }) { Text("Save training") } }) {
        ReforgeDraftRecovery(trainingDraft, savesDraft)
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

/** Ordinary inventory entry takes two fields; only mechanical effects need an approval step. */
@Composable internal fun ReforgeItemEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit,
    onSave: (ReforgeCharacter) -> Unit, existingItemId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.items.firstOrNull { it.id == existingItemId } }
    val originalDefinition = remember { character.itemDefinitions.firstOrNull { it.id == original?.definitionId } }
    var name by rememberSaveable { mutableStateOf(originalDefinition?.name ?: "") }
    val descriptionDraft = rememberReforgeDraft(originalDefinition?.description.orEmpty(), String.serializer())
    var description by descriptionDraft
    var attune by rememberSaveable { mutableStateOf(originalDefinition?.requiresAttunement ?: false) }
    var slot by rememberSaveable { mutableStateOf(originalDefinition?.equipmentSlot ?: "") }
    var quantity by rememberSaveable { mutableStateOf(original?.quantity?.toString() ?: "1") }
    var favorite by rememberSaveable { mutableStateOf(original?.favorite ?: false) }
    var state by rememberSaveable { mutableStateOf(original?.state ?: ItemState.CARRIED) }
    var target by rememberSaveable { mutableStateOf(NumericTarget.ABILITY) }
    var operation by rememberSaveable { mutableStateOf(NumberOperation.SET) }
    var ability by rememberSaveable { mutableStateOf(Stat.STR) }
    var amount by rememberSaveable { mutableStateOf("") }
    var effectName by rememberSaveable { mutableStateOf("") }
    val effectsDraft = rememberReforgeDraft(originalDefinition?.effects ?: emptyList(), ListSerializer(EffectDefinition.serializer()))
    var effects by effectsDraft
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var effectType by rememberSaveable { mutableStateOf("Statistic") }
    var damageKind by rememberSaveable { mutableStateOf(DamageKind.COLD) }
    var damageDice by rememberSaveable { mutableStateOf("1d6") }
    var defense by rememberSaveable { mutableStateOf(DefenseKind.RESISTANCE) }
    var edge by rememberSaveable { mutableStateOf(RollEdge.ADVANTAGE) }
    var rollKind by rememberSaveable { mutableStateOf(RollKind.ABILITY_CHECK) }
    var qualifier by rememberSaveable { mutableStateOf<String?>(null) }
    var resourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var chargeCost by rememberSaveable { mutableStateOf("1") }
    var once by rememberSaveable { mutableStateOf(false) }
    var critical by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val definitionId = rememberSaveable { originalDefinition?.id ?: UUID.randomUUID().toString() }
    val instanceId = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    val dirty = name != originalDefinition?.name.orEmpty() || description != originalDefinition?.description.orEmpty() ||
        quantity != (original?.quantity?.toString() ?: "1") || slot != (originalDefinition?.equipmentSlot ?: "") ||
        state != (original?.state ?: ItemState.CARRIED) || favorite != (original?.favorite ?: false) ||
        attune != (originalDefinition?.requiresAttunement ?: false) || effects != originalDefinition?.effects.orEmpty()
    GenesisEditorSheet(if (original == null) "Add equipment" else "Edit equipment",
        sections = listOf("Item", "Equipment", "Effects", "Review"), creating = original == null,
        context = "Inventory owner · ${character.name}", onDismiss = onDismiss,
        dirty = dirty || effectName.isNotBlank() || amount.isNotBlank(),
        saveLabel = if (effects.isEmpty()) "Save item" else "Save reviewed item",
        saveEnabled = name.isNotBlank() && (effects.isEmpty() || reviewed) && effectsDraft.canSave && descriptionDraft.canSave, error = error,
        onSave = {
            try {
                val definition = (originalDefinition ?: ItemDefinition(definitionId, name)).copy(name = name.trim(), effects = effects,
                    requiresAttunement = attune, equipmentSlot = slot.trim().ifBlank { null }, description = description)
                val item = (original ?: ItemInstance(instanceId, definitionId)).copy(quantity = optionalInt(quantity, "Quantity", 0, 100000) ?: error("Enter quantity"), favorite = favorite)
                val candidate = character.copy(itemDefinitions = character.itemDefinitions.filterNot { it.id == definitionId } + definition,
                    items = character.items.filterNot { it.id == instanceId } + item)
                val equipped = GameplayRules.equip(candidate, instanceId, state)
                onSave(GameplayRules.preserveCapacities(character, equipped))
            } catch (e: Exception) { error = e.message ?: "Check item" }
    }) { section, select ->
        ReforgeDraftRecovery(effectsDraft, descriptionDraft)
        when (section) {
        0 -> {
            Field("Item name", name, { name = it; reviewed = false })
            Field("Description", description, { description = it }, single = false)
            Field("Quantity", quantity, { quantity = it; reviewed = false })
            Text("Create once in ${character.name}'s inventory. You can transfer or move the saved item from its details.", color = RebuildPalette.Muted)
        }
        1 -> {
            Field("Slot (optional)", slot, { slot = it; reviewed = false })
            Choices("Location / equipment", ItemState.entries, state) { state = it; reviewed = false }
            Toggle("Favorite", favorite) { favorite = it }
            Toggle("Requires attunement", attune) { attune = it; reviewed = false }
        }
        2 -> {
            Text("Only effects you explicitly review can change your character's statistics.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            effects.forEachIndexed { index, effect ->
                Text("${index + 1}. ${effect.name} · ${reforgeEffectSummary(effect)}${if (attune && !effect.requiresAttunement) " · available without attunement" else ""}", style = MaterialTheme.typography.bodySmall)
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
                        payload, conditions, requiresAttunement = true,
                        chargeResourceId = if (effectType == "Damage dice") resourceId else null,
                        chargeCost = if (effectType == "Damage dice" && resourceId != null) optionalInt(chargeCost, "Charge cost", 1, 1000) ?: error("Enter charge cost") else 0)
                    reviewed = false; amount = ""; effectName = ""; error = ""
                } catch (e: Exception) { error = e.message ?: "Check effect" }
            }) { Text("Add effect to review") }
            }
        }
        else -> {
            GenesisReviewSection("Item", { select(0) }) {
                GenesisReviewValue("Name", name)
                GenesisReviewValue("Quantity", quantity)
                GenesisReviewValue("Description", description)
            }
            GenesisReviewSection("Equipment", { select(1) }) {
                GenesisReviewValue("Owner", character.name)
                GenesisReviewValue("Location", state.name.lowercase().replaceFirstChar { it.uppercase() })
                GenesisReviewValue("Slot", slot, "No specific slot")
                GenesisReviewValue("Attunement", if (attune) "Required · attune separately after saving" else "Not required")
                GenesisReviewValue("Favorite", if (favorite) "Yes" else "No")
            }
            GenesisReviewSection("Effects", { select(2) }) {
                if (effects.isEmpty()) Text("No mechanical effects.", color = RebuildPalette.Muted)
                effects.forEach { effect ->
                    Text("${effect.name} · ${reforgeEffectSummary(effect)}", style = MaterialTheme.typography.bodyMedium)
                }
                if (effects.isNotEmpty()) Toggle("I reviewed these mechanics", reviewed) { reviewed = it }
            }
            Text("Saving equipment recalculates eligible effects. It does not attune the item or restore spent resources.", color = RebuildPalette.Muted)
        }
        }
    }
}

@Composable internal fun ReforgeAttackEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, existingAttackId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.attacks.firstOrNull { it.id == existingAttackId } }
    val draftId = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var ability by rememberSaveable { mutableStateOf(original?.ability ?: Stat.STR) }
    var formula by rememberSaveable { mutableStateOf(original?.damage?.firstOrNull()?.formula?.let(SecureDice::format) ?: "") }
    var kind by rememberSaveable { mutableStateOf(original?.damage?.firstOrNull()?.type ?: DamageKind.BLUDGEONING) }
    var range by rememberSaveable { mutableStateOf(original?.range ?: "5 ft.") }
    var proficient by rememberSaveable { mutableStateOf(original?.proficient ?: true) }
    var independent by rememberSaveable { mutableStateOf(original?.fixedAttackBonus != null) }
    var fixedBonus by rememberSaveable { mutableStateOf(original?.fixedAttackBonus?.toString().orEmpty()) }
    var timing by rememberSaveable { mutableStateOf(original?.actionType ?: "Action") }
    var addAbility by rememberSaveable { mutableStateOf(original?.addAbilityToDamage ?: true) }
    var error by remember { mutableStateOf("") }
    val dirty = name != original?.name.orEmpty() || ability != (original?.ability ?: Stat.STR) ||
        formula != (original?.damage?.firstOrNull()?.formula?.let(SecureDice::format) ?: "") ||
        kind != (original?.damage?.firstOrNull()?.type ?: DamageKind.BLUDGEONING) ||
        range != (original?.range ?: "5 ft.") || proficient != (original?.proficient ?: true) ||
        independent != (original?.fixedAttackBonus != null) || fixedBonus != original?.fixedAttackBonus?.toString().orEmpty() ||
        timing != (original?.actionType ?: "Action") || addAbility != (original?.addAbilityToDamage ?: true)
    GenesisEditorSheet(if (original == null) "Add attack" else "Edit attack",
        sections = listOf("Attack", "Accuracy", "Damage", "Review"), creating = original == null,
        context = "Attacker · ${character.name}", onDismiss = onDismiss, dirty = dirty,
        saveLabel = "Save attack", saveEnabled = name.isNotBlank(), error = error, onSave = {
            try {
                val id = draftId
                val dice = SecureDice.parse(formula)
                val component = (original?.damage?.firstOrNull() ?: DamageComponent("$id:base", name.trim(), kind, dice))
                    .copy(label = name.trim(), type = kind, formula = dice)
                val attack = (original ?: AttackDefinition(id, name.trim())).copy(name = name.trim(), ability = ability, proficient = proficient,
                    fixedAttackBonus = if (independent) optionalInt(fixedBonus, "Independent attack bonus", -1000, 1000) ?: error("Enter the independent attack bonus") else null,
                    damage = listOf(component) + (original?.damage?.drop(1) ?: emptyList()),
                    addAbilityToDamage = addAbility, range = range, actionType = timing)
                onSave(character.copy(attacks = character.attacks.filterNot { it.id == attack.id } + attack))
            } catch (e: Exception) { error = e.message ?: "Check attack" }
    }) { section, select ->
        when (section) {
        0 -> {
            Field("Name", name, { name = it })
            Choices("Action type", (listOf("Action", "Bonus Action", "Reaction") + listOfNotNull(original?.actionType)).distinct(), timing) { timing = it }
            Field("Range", range, { range = it })
        }
        1 -> {
        Toggle("Use independent attack bonus", independent) { independent = it }
        if (independent) {
            Field("Independent attack bonus", fixedBonus, { fixedBonus = it })
            Text("This is the complete to-hit bonus. Ability and proficiency are not added again.", color = RebuildPalette.Muted)
        } else {
            Choices("Attack ability", Stat.entries, ability) { ability = it }
            Toggle("Proficient", proficient) { proficient = it }
        }
        }
        2 -> {
        Field("Base damage dice (for example 1d8)", formula, { formula = it })
        Choices("Damage type", DamageKind.entries, kind) { kind = it }
        Toggle("Add ability modifier to damage", addAbility) { addAbility = it }
        Text("Critical hits double eligible dice. Damage bonuses are resolved through active structured effects.", color = RebuildPalette.Muted)
        if ((original?.damage?.size ?: 0) > 1) Text("${original!!.damage.size - 1} additional saved damage components are retained.", color = RebuildPalette.Muted)
        }
        else -> {
            GenesisReviewSection("Attack", { select(0) }) {
                GenesisReviewValue("Name", name)
                GenesisReviewValue("Timing / range", "$timing · $range")
            }
            GenesisReviewSection("Accuracy", { select(1) }) {
                GenesisReviewValue("To hit", if (independent) "Independent bonus ${fixedBonus.ifBlank { "Not set" }}" else "${ability.name}${if (proficient) " + proficiency" else ""}")
            }
            GenesisReviewSection("Damage", { select(2) }) {
                GenesisReviewValue("Base damage", "${formula.ifBlank { "Not set" }} ${kind.name.lowercase()}${if (addAbility) " + ${ability.name} modifier" else ""}")
                original?.damage?.drop(1)?.forEach { Text("Retained · ${SecureDice.format(it.formula)} ${it.type.name.lowercase()}") }
            }
        }
        }
    }
}

@Composable internal fun ReforgeSpellEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, existingSpellId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.spells.firstOrNull { it.id == existingSpellId } }
    val draftId = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var level by rememberSaveable { mutableStateOf(original?.level?.toString() ?: "") }
    var preparation by rememberSaveable { mutableStateOf(original?.preparation ?: SpellPreparation.KNOWN) }
    var concentration by rememberSaveable { mutableStateOf(original?.concentration ?: false) }
    val descriptionDraft = rememberReforgeDraft(original?.description.orEmpty(), String.serializer())
    var description by descriptionDraft
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
    val dirty = name != original?.name.orEmpty() || level != original?.level?.toString().orEmpty() ||
        preparation != (original?.preparation ?: SpellPreparation.KNOWN) || concentration != (original?.concentration ?: false) ||
        description != original?.description.orEmpty() || components != original?.components.orEmpty() ||
        range != original?.range.orEmpty() || duration != original?.duration.orEmpty() ||
        castingTime != (original?.castingTime ?: "1 Action") || sourceVersion != (original?.sourceVersion ?: "user-owned") ||
        slotRequired != (original?.slotRequired ?: true) || ritual != (original?.ritual ?: false) ||
        resourceId != original?.resourceId || resourceCost != (original?.resourceCost?.toString() ?: "1")
    GenesisEditorSheet(if (original == null) "Add spell" else "Edit spell",
        sections = listOf("Spell", "Casting", "Reference", "Review"), creating = original == null,
        context = "Spellbook · ${character.name}", onDismiss = onDismiss, dirty = dirty,
        saveLabel = "Save spell", saveEnabled = name.isNotBlank() && descriptionDraft.canSave, error = error, onSave = {
            try {
                val spellLevel = optionalInt(level, "Spell level", 0, 9) ?: error("Enter a spell level")
                val spell = SpellDefinition(draftId, name.trim(), spellLevel, preparation, concentration, castingTime = castingTime,
                    range = range, components = components, duration = duration, description = description, slotRequired = spellLevel > 0 && slotRequired, sourceVersion = sourceVersion, ritual = ritual, resourceId = resourceId, resourceCost = optionalInt(resourceCost, "Resource cost", 0, 1000) ?: 1)
                onSave(character.copy(spells = character.spells.filterNot { it.id == spell.id } + spell))
            } catch (e: Exception) { error = e.message ?: "Check spell" }
    }) { section, select ->
        ReforgeDraftRecovery(descriptionDraft)
        when (section) {
        0 -> {
        Field("Name", name, { name = it }); Field("Level (0 = cantrip)", level, { level = it })
        Choices("Preparation", SpellPreparation.entries, preparation) { preparation = it }
        Text("Use your accepted spell description and rules. Preparation determines availability on this character.", color = RebuildPalette.Muted)
        }
        1 -> {
        Toggle("Concentration", concentration) { concentration = it }
        Field("Casting time", castingTime, { castingTime = it })
        if (level.trim().toIntOrNull() != 0) Toggle("Requires a spell slot", slotRequired) { slotRequired = it }
        else Text("Cantrip · no spell slot required", color = RebuildPalette.Muted)
        Toggle("Ritual casting allowed", ritual) { ritual = it }
        Choices("Item or feature resource", listOf<String?>(null) + character.resources.map { it.id }, resourceId,
            display = { id -> character.resources.firstOrNull { it.id == id }?.name ?: "No extra resource" }) { resourceId = it }
        if (resourceId != null) Field("Resource cost", resourceCost, { resourceCost = it })
        }
        2 -> {
        Field("Rule version / owned source", sourceVersion, { sourceVersion = it })
        Field("Range", range, { range = it }); Field("Components", components, { components = it }); Field("Duration", duration, { duration = it })
        Field("Description", description, { description = it }, single = false)
        }
        else -> {
            GenesisReviewSection("Spell", { select(0) }) {
                GenesisReviewValue("Name", name)
                GenesisReviewValue("Level / availability", "${level.ifBlank { "Not set" }} · ${preparation.name.lowercase().replace('_', ' ')}")
            }
            GenesisReviewSection("Casting", { select(1) }) {
                GenesisReviewValue("Casting time", castingTime)
                GenesisReviewValue("Spell slot", if (level.trim().toIntOrNull() != 0 && slotRequired) "Required" else "Not required")
                GenesisReviewValue("Concentration / ritual", "${if (concentration) "Concentration" else "No concentration"} · ${if (ritual) "Ritual allowed" else "No ritual"}")
                GenesisReviewValue("Additional resource", resourceId?.let { id -> "${character.resources.firstOrNull { it.id == id }?.name ?: "Missing resource"} · $resourceCost" }.orEmpty(), "None")
            }
            GenesisReviewSection("Reference", { select(2) }) {
                GenesisReviewValue("Source", sourceVersion)
                GenesisReviewValue("Range / components / duration", listOf(range, components, duration).filter(String::isNotBlank).joinToString(" · "))
                GenesisReviewValue("Description", description)
            }
            Text("Saving adds the spell to this spellbook. Casting and resource spending are separate actions.", color = RebuildPalette.Muted)
        }
        }
    }
}

@Composable internal fun ReforgeResourceEditor(latestCharacter: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit, existingResourceId: String? = null) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val original = remember { character.resources.firstOrNull { it.id == existingResourceId } }
    val draftId = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var maximum by rememberSaveable { mutableStateOf(original?.maximum?.toString() ?: "") }
    var current by rememberSaveable { mutableStateOf(original?.current?.toString() ?: "") }
    var recharge by rememberSaveable { mutableStateOf(original?.recharge ?: Recharge.NONE) }
    var recovery by rememberSaveable { mutableStateOf(original?.recoveryAmount?.toString() ?: "") }
    var dieSides by rememberSaveable { mutableStateOf(original?.dieSides?.toString() ?: "") }
    var error by remember { mutableStateOf("") }
    val dirty = name != original?.name.orEmpty() || maximum != original?.maximum?.toString().orEmpty() ||
        current != original?.current?.toString().orEmpty() || recharge != (original?.recharge ?: Recharge.NONE) ||
        recovery != original?.recoveryAmount?.toString().orEmpty() || dieSides != original?.dieSides?.toString().orEmpty()
    GenesisEditorSheet(if (original == null) "Add resource" else "Edit resource",
        sections = listOf("Resource", "Recovery", "Review"), creating = original == null,
        context = "Resource owner · ${character.name}", onDismiss = onDismiss, dirty = dirty,
        saveLabel = "Save resource", saveEnabled = name.isNotBlank(), error = error, onSave = {
            try {
                val pool = ResourcePool(draftId, name.trim(), optionalInt(maximum, "Capacity", 0, 10000),
                    optionalInt(current, "Current uses", 0, 10000), recharge, optionalInt(recovery, "Recovered uses", 0, 10000), restoreUnknownOnRest = original?.restoreUnknownOnRest ?: false, dieSides = optionalInt(dieSides, "Hit Die sides", 2, 1000))
                val currentValue = pool.current
                val maximumValue = pool.maximum
                require(currentValue == null || maximumValue == null || currentValue <= maximumValue) { "Current uses exceed capacity" }
                onSave(character.copy(resources = character.resources.filterNot { it.id == pool.id } + pool))
            } catch (e: Exception) { error = e.message ?: "Check resource" }
    }) { section, select ->
        when (section) {
        0 -> {
        Field("Name", name, { name = it }); Field("Capacity", maximum, { maximum = it }); Field("Current uses (blank = unknown)", current, { current = it })
        Text("Capacity and current uses are separate values. Leave either blank when unknown.", color = RebuildPalette.Muted)
        }
        1 -> {
        Field("Hit Die sides (optional, for example 8)", dieSides, { dieSides = it })
        Choices("Recharge", Recharge.entries, recharge) { recharge = it }
        Field("Uses recovered (blank = full capacity)", recovery, { recovery = it })
        Text("Creating or editing this resource does not complete a rest. Unknown current values remain unknown after recovery.", color = RebuildPalette.Muted)
        }
        else -> {
            GenesisReviewSection("Resource", { select(0) }) {
                GenesisReviewValue("Name", name)
                GenesisReviewValue("Current / capacity", "${current.ifBlank { "?" }} / ${maximum.ifBlank { "?" }}")
            }
            GenesisReviewSection("Recovery", { select(1) }) {
                GenesisReviewValue("Recharge", recharge.name.lowercase().replace('_', ' '))
                GenesisReviewValue("Uses recovered", recovery, "Full known capacity")
                GenesisReviewValue("Hit Die sides", dieSides, "Not a Hit Die")
            }
            Text("Unknown current values stay unknown. No rest happens when you save this resource.", color = RebuildPalette.Muted)
        }
        }
    }
}
