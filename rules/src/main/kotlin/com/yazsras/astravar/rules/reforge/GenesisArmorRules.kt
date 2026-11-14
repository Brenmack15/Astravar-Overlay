package com.yazsras.astravar.rules.reforge

import com.yazsras.astravar.rules.*
import kotlinx.serialization.Serializable

/** The original snapshot is evidence, never the mutable resource authority. */
@Serializable data class GenesisArmorState(
    val campaign: Campaign,
    val originalCampaign: Campaign,
    val elements: List<DamageType> = emptyList(),
    val source: String,
    val adoptedAtCharacterRevision: Long,
    val gameTimeOffset: Long,
    val lastCharacterTurnNumber: Long,
    val templateSpellIds: Map<String, String> = emptyMap(),
    /** Exact accepted accuracy remains pinned until the owner explicitly chooses live INT/PB scaling. */
    val meleeUsesCharacterAccuracy: Boolean = false,
    val meleeAccuracyIntBaseline: Int? = null,
    val meleeAccuracyProficiencyBaseline: Int? = null,
    val configurationSources: Map<String, String> = emptyMap()
)

object GenesisArmorRules {
    fun attackContext(ability: Ability): CalculationContext = CalculationContext(attackId = "armor:${ability.name}",
        tags = if (ability in listOf(Ability.GAUNTLET, Ability.SPELL_GAUNTLET, Ability.GUARDIAN)) setOf("melee", "weapon") else setOf("ranged", "weapon"))

    private fun requireAction(character: ReforgeCharacter) {
        val projection = CharacterCalculator.calculate(character)
        require(character.exhaustionLevel < 6) { "Exhaustion has reached the fatal level; resolve the character's state with the DM." }
        require(ConditionRules.activeConditions(character, projection.conditionImmunities).none {
            it in setOf("incapacitated", "unconscious", "stunned", "paralyzed", "petrified")
        }) { "The current condition prevents armor actions." }
    }

    fun validate(armor: GenesisArmorState?) {
        if (armor == null) return
        require(armor.source.isNotBlank() && armor.source.length <= 2000 && armor.adoptedAtCharacterRevision >= 0 && armor.lastCharacterTurnNumber >= 0)
        require(armor.elements.distinct().size == armor.elements.size && armor.elements.size <= 9 && DamageType.FORCE !in armor.elements)
        require(armor.templateSpellIds.size <= 10000 && armor.templateSpellIds.all { (template, spell) -> template.isNotBlank() && spell.isNotBlank() })
        require(armor.configurationSources.size <= 30 && armor.configurationSources.all { (key, source) ->
            key.isNotBlank() && key.length <= 80 && source.isNotBlank() && source.length <= 1000
        })
        require(armor.meleeAccuracyIntBaseline == null || armor.meleeAccuracyIntBaseline in -5..10)
        require(armor.meleeAccuracyProficiencyBaseline == null || armor.meleeAccuracyProficiencyBaseline in 0..10)
        BackupCodec.validate(armor.campaign)
        BackupCodec.validate(armor.originalCampaign)
    }

    fun adopt(character: ReforgeCharacter, legacy: Campaign, elements: List<DamageType>, source: String): ReforgeCharacter {
        require(character.armor == null) { "Armor is already connected to this character." }
        require(source.isNotBlank() && source.length <= 2000) { "Record the source of these established armor records." }
        BackupCodec.validate(legacy)
        require(elements.distinct().size == elements.size && elements.all { it != DamageType.FORCE }) { "Review the saved active elements." }
        return character.copy(armor = GenesisArmorState(legacy, legacy, elements, source.trim(), character.revision,
            Math.subtractExact(legacy.gameSeconds, character.gameSeconds), character.turnNumber))
    }

    /** Pure projection: never rewrites a source, historical plan, accepted override or unknown count. */
    fun runtime(character: ReforgeCharacter): Campaign {
        val armor = requireNotNull(character.armor) { "Connect the saved armor records first." }
        val saved = armor.campaign
        val projection = CharacterCalculator.calculate(character)
        val intelligence = projection.abilities.getValue(Stat.INT).modifier
            ?: error("Set the character's Intelligence before using the armor.")
        require(character.level >= 5) { "The established armor profile requires level 5 or higher." }
        val profile = saved.profile
        val builtInConditions = ConditionRules.effects(character.copy(conditions =
            ConditionRules.activeConditions(character, projection.conditionImmunities)))
        val conditionEffects = CharacterCalculator.evaluateEffects(character, attackContext(Ability.ARCANE)).active
            .filter { it.effect in builtInConditions }
        val attackAdjustment = CharacterCalculator.number(0, NumericTarget.ATTACK, "", conditionEffects, "Active conditions").value ?: 0
        val melee = profile.melee?.let {
            val accuracyDelta = if (armor.meleeUsesCharacterAccuracy)
                intelligence - (armor.meleeAccuracyIntBaseline ?: profile.intelligence) + projection.proficiencyBonus -
                    (armor.meleeAccuracyProficiencyBaseline ?: profile.proficiency) else 0
            it.copy(intelligenceModifier = intelligence,
                strengthModifier = projection.abilities.getValue(Stat.STR).modifier ?: it.strengthModifier,
                dexterityModifier = projection.abilities.getValue(Stat.DEX).modifier ?: it.dexterityModifier,
                attackBonus = it.attackBonus + accuracyDelta,
                guardianAttackBonus = it.guardianAttackBonus?.plus(accuracyDelta))
        }
        val newTurn = armor.lastCharacterTurnNumber != character.turnNumber
        val savedTurn = if (newTurn) Turn(number = saved.turn.number + 1) else saved.turn
        return saved.copy(revision = character.revision,
            profile = profile.copy(character = character.name, level = character.level,
                intelligence = intelligence, proficiency = projection.proficiencyBonus, melee = melee,
                conditionAttackAdjustment = attackAdjustment),
            slots = character.spellSlots.mapNotNull { (level, count) -> count?.let { level to it } }.toMap(),
            gameSeconds = Math.addExact(character.gameSeconds, armor.gameTimeOffset),
            encounter = character.turn.tracking,
            turn = savedTurn.copy(attacks = character.turn.attacksUsed,
                fullAction = !character.turn.actionAvailable,
                bonus = !character.turn.bonusActionAvailable),
            gauntletCharge = if (newTurn) null else saved.gauntletCharge)
    }

    /** Project only mechanics that actually changed; the current sheet owns all common balances. */
    fun apply(character: ReforgeCharacter, after: Campaign): ReforgeCharacter {
        val armor = requireNotNull(character.armor)
        val before = runtime(character)
        require(after.revision == before.revision) { "Armor action belongs to another character revision." }
        val changedSlots = (before.slots.keys + after.slots.keys).filter { before.slots[it] != after.slots[it] }
        val slots = character.spellSlots + changedSlots.associateWith { after.slots[it] }
        val previousPayloadIds = before.cores.flatMap { it.payloads }.map { it.id }.toSet()
        val previousShotIds = before.pending.map { it.plan.id }.toSet()
        val spentSlot = changedSlots.any { level -> after.slots[level]?.let { it < (before.slots[level] ?: it) } == true } ||
            after.cores.flatMap { it.payloads }.any { it.id !in previousPayloadIds && it.source == SourceKind.SLOT } ||
            after.pending.any { it.plan.id !in previousShotIds && it.plan.immediate != null }
        if (spentSlot && character.turn.tracking && character.rules.core == RuleFamily.REVISED_2024)
            require(!character.turn.spellSlotSpent) { "A spell slot has already been spent this turn under the revised core rules." }
        val turn = character.turn.copy(
            attacksUsed = if (after.turn.attacks != before.turn.attacks) after.turn.attacks else character.turn.attacksUsed,
            actionAvailable = when {
                after.turn.fullAction && !before.turn.fullAction -> false
                after.turn.attacks != before.turn.attacks -> after.turn.attacks < character.turn.attacksPerAction
                else -> character.turn.actionAvailable
            },
            bonusActionAvailable = if (after.turn.bonus != before.turn.bonus) !after.turn.bonus else character.turn.bonusActionAvailable,
            spellSlotSpent = character.turn.spellSlotSpent || spentSlot)
        // Keep accepted profile values, including explicit fixed overrides, as the configuration baseline.
        val retained = HistoryRetention.campaign(after.copy(profile = armor.campaign.profile))
        return character.copy(spellSlots = slots, turn = turn,
            gameSeconds = Math.subtractExact(after.gameSeconds, armor.gameTimeOffset),
            armor = armor.copy(campaign = retained, lastCharacterTurnNumber = character.turnNumber))
    }

    fun plan(character: ReforgeCharacter, id: String, ability: Ability, payloadIds: List<String> = emptyList(),
        immediate: Template? = null, distance: Int = 30, point: String = "", direct: Boolean = false,
        manualSlotConfirmed: Boolean = false): ShotPlan {
        requireAction(character)
        ConditionRules.rollRestriction(character, RollKind.ATTACK, "armor:${ability.name}", attackContext(ability))?.let { error(it) }
        if (character.turn.tracking) {
            if (ability == Ability.DISCHARGE) require(character.turn.actionAvailable && character.turn.attacksUsed == 0) { "Action already spent this turn." }
            else require(character.turn.actionAvailable && character.turn.attacksUsed < character.turn.attacksPerAction) { "No attacks remain in this Action." }
        }
        val state = runtime(character)
        if (immediate != null && character.turn.tracking && character.rules.core == RuleFamily.REVISED_2024)
            require(!character.turn.spellSlotSpent) { "A spell slot has already been spent this turn under the revised core rules." }
        // Shared character rules validate its configured attack allowance; the inherited engine's two-attack default does not replace it.
        return Engine.plan(state.copy(encounter = false), id, ability, payloadIds, immediate,
            character.armor!!.elements, direct, distance, point, manualSlotConfirmed)
    }

    fun fire(character: ReforgeCharacter, plan: ShotPlan, options: RollOptions): ReforgeCharacter {
        return commitRolled(character, plan, DiceRoller.roll(plan, options))
    }

    fun charge(character: ReforgeCharacter): ReforgeCharacter {
        requireAction(character)
        val scores = CharacterCalculator.calculate(character).abilities
        require(listOf(Stat.STR, Stat.DEX, Stat.INT).all { scores.getValue(it).modifier != null }) {
            "Set current Strength, Dexterity and Intelligence before preparing Charged Gauntlet."
        }
        return apply(character, Engine.chargeGauntlet(runtime(character)))
    }

    fun load(character: ReforgeCharacter, templateId: String, payloadId: String, source: SourceKind,
        manualSlot: Boolean = false, scrollId: String? = null, overwriteId: String? = null): ReforgeCharacter {
        requireAction(character)
        val state = runtime(character)
        val template = state.templates.singleOrNull { it.id == templateId } ?: error("Select a current spell template.")
        val coreId = state.installedId ?: error("Review the internal nexus configuration first.")
        return apply(character, Engine.charge(state, coreId, template, payloadId, source, true, manualSlot, scrollId, overwriteId))
    }

    fun purge(character: ReforgeCharacter, payloadId: String): ReforgeCharacter {
        requireAction(character)
        val state = runtime(character)
        val coreId = state.installedId ?: error("Review the internal nexus configuration first.")
        return apply(character, Engine.purge(state, coreId, payloadId))
    }

    /** A retried save retains these exact faces; it never draws another set of dice. */
    fun commitRolled(character: ReforgeCharacter, plan: ShotPlan, roll: ShotRoll): ReforgeCharacter {
        val before = runtime(character)
        val checked = this.plan(character, plan.id, plan.ability, plan.selectedPayloads.map { it.id }, plan.immediate,
            plan.distance, plan.targetPoint, plan.directImpact, plan.manualSlotConfirmed)
        require(checked == plan) { "Character or attack options changed. Preview again." }
        DiceRoller.validate(plan, roll)
        val committed = Engine.commit(before.copy(encounter = false), plan)
        val result = if (!plan.blast) DiceRoller.resolution(plan, roll) else null
        val fired = committed.copy(pending = committed.pending.map {
            if (it.plan.id == plan.id) it.copy(rolled = roll, result = result) else it
        })
        val withAction = if (!character.turn.tracking) fired else fired.copy(turn = fired.turn.copy(
            attacks = if (plan.blast) before.turn.attacks else before.turn.attacks + 1,
            fullAction = plan.blast || before.turn.fullAction))
        return apply(character, withAction)
    }
}
