package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.Serializable

@Serializable enum class Stat { STR, DEX, CON, INT, WIS, CHA }
@Serializable enum class RuleFamily { REVISED_2024, LEGACY_2014 }
@Serializable data class RuleVersion(
    val core: RuleFamily = RuleFamily.REVISED_2024,
    val classVersion: String = "unconfigured",
    val exceptions: List<String> = emptyList()
)
@Serializable enum class Acceptance { OFFICIAL, ACCEPTED_HOMEBREW, PROPOSED, UNVERIFIED, RETIRED }
@Serializable data class RuleSource(val id: String, val name: String, val acceptance: Acceptance = Acceptance.UNVERIFIED, val revision: Long = 0)
@Serializable enum class ItemState { STORED, CARRIED, WORN, HELD }
@Serializable enum class Recharge { NONE, SHORT_REST, LONG_REST, DAWN }
@Serializable data class ResourcePool(
    val id: String, val name: String, val maximum: Int?, val current: Int?,
    val recharge: Recharge = Recharge.NONE, val recoveryAmount: Int? = null,
    val restoreUnknownOnRest: Boolean = false, val dieSides: Int? = null
)
@Serializable data class SkillTraining(val ability: Stat, val proficient: Boolean = false, val expertise: Boolean = false, val halfProficiency: Boolean = false)
@Serializable enum class NumericTarget { ABILITY, ABILITY_CAP, SAVE, SKILL, INITIATIVE, ARMOR_CLASS, MAX_HP, SPEED, SPELL_ATTACK, SPELL_DC, ATTACK, DAMAGE, RESOURCE_CAPACITY }
@Serializable enum class NumberOperation { ADD, SET, MINIMUM, MAXIMUM }
@Serializable enum class DamageKind { ACID, BLUDGEONING, COLD, FIRE, FORCE, LIGHTNING, NECROTIC, PIERCING, POISON, PSYCHIC, RADIANT, SLASHING, THUNDER }
@Serializable enum class DefenseKind { RESISTANCE, IMMUNITY, VULNERABILITY }
@Serializable enum class RollEdge { ADVANTAGE, DISADVANTAGE }
@Serializable enum class RollKind { ABILITY_CHECK, SAVE, SKILL, INITIATIVE, ATTACK, SPELL_ATTACK, CUSTOM }
@Serializable enum class ConditionKind { EQUIPPED, ATTUNED, HELD, STANCE, CREATURE_TYPE, DAMAGE_TYPE, SUCCESSFUL_HIT, CHARGE_AVAILABLE, ONCE_PER_TURN, CONTEXT_TAG }
/** Conditions are data, never executable expressions. Unknown context fails closed. */
@Serializable data class EffectCondition(val kind: ConditionKind, val key: String = "", val minimum: Int = 1)
@Serializable data class DiceTerm(val count: Int, val sides: Int, val sign: Int = 1)
@Serializable data class DiceFormula(val dice: List<DiceTerm> = emptyList(), val flat: Int = 0)
@Serializable data class DamageComponent(
    val id: String, val label: String, val type: DamageKind, val formula: DiceFormula,
    val criticalDice: Boolean = true, val magical: Boolean = false
)
@Serializable data class AttackQualifier(val attackIds: Set<String> = emptySet(), val requiredTags: Set<String> = emptySet())
@Serializable sealed class EffectPayload {
    @Serializable data class Number(val target: NumericTarget, val operation: NumberOperation, val amount: Int, val key: String = "") : EffectPayload()
    @Serializable data class Proficiency(val skill: String? = null, val save: Stat? = null, val expertise: Boolean = false) : EffectPayload()
    @Serializable data class DamageBonus(val component: DamageComponent, val qualifier: AttackQualifier = AttackQualifier()) : EffectPayload()
    @Serializable data class DamageConversion(val from: DamageKind, val to: DamageKind, val qualifier: AttackQualifier = AttackQualifier()) : EffectPayload()
    @Serializable data class Defense(val type: DamageKind, val kind: DefenseKind, val nonmagicalOnly: Boolean = false) : EffectPayload()
    @Serializable data class ConditionImmunity(val condition: String) : EffectPayload()
    @Serializable data class Edge(val kind: RollKind, val edge: RollEdge, val key: String = "") : EffectPayload()
}
@Serializable data class EffectDefinition(
    val id: String, val name: String, val source: RuleSource, val payload: EffectPayload,
    val conditions: List<EffectCondition> = emptyList(), val enabled: Boolean = true,
    val priority: Int = 0, val stackingKey: String? = null,
    val requiresAttunement: Boolean = true, val expiresAtGameSeconds: Long? = null,
    val expiresOn: Recharge = Recharge.NONE, val chargeResourceId: String? = null,
    val chargeCost: Int = 0
)
@Serializable data class ItemDefinition(
    val id: String, val name: String, val effects: List<EffectDefinition> = emptyList(),
    val requiresAttunement: Boolean = false, val attunementCost: Int = 1,
    val equipmentSlot: String? = null, val minimumLevel: Int = 1,
    val minimumAbilities: Map<Stat, Int> = emptyMap(), val weight: Double? = null,
    val description: String = ""
)
@Serializable data class ItemInstance(
    val id: String, val definitionId: String, val state: ItemState = ItemState.CARRIED,
    val attuned: Boolean = false, val quantity: Int = 1, val operating: Boolean = true,
    val containerId: String? = null, val donorInstanceIds: Set<String> = emptySet(),
    val favorite: Boolean = false
)
@Serializable data class AttackDefinition(
    val id: String, val name: String, val ability: Stat = Stat.STR, val proficient: Boolean = true,
    val attackBonus: Int = 0, val tags: Set<String> = emptySet(),
    val damage: List<DamageComponent> = emptyList(), val addAbilityToDamage: Boolean = true,
    val actionType: String = "Action", val range: String = "5 ft.",
    val requiredItemId: String? = null, val fixedAttackBonus:Int? = null
)
@Serializable enum class SpellPreparation { KNOWN, PREPARED, ALWAYS_PREPARED }
@Serializable data class SpellDefinition(
    val id: String, val name: String, val level: Int, val preparation: SpellPreparation = SpellPreparation.KNOWN,
    val concentration: Boolean = false, val castingTime: String = "1 Action",
    val range: String = "", val components: String = "", val duration: String = "",
    val description: String = "", val sourceVersion: String = "user-owned",
    val resourceId: String? = null, val resourceCost: Int = 1,
    val slotRequired: Boolean = true, val ritual: Boolean = false
)
@Serializable data class TurnEconomy(
    val tracking: Boolean = false, val actionAvailable: Boolean = true,
    val bonusActionAvailable: Boolean = true, val reactionAvailable: Boolean = true,
    val attacksUsed: Int = 0, val attacksPerAction: Int = 1, val spellSlotSpent: Boolean = false
)
@Serializable enum class FeatureKind { FEAT, CLASS, SPECIES, BACKGROUND, OTHER }
@Serializable data class CharacterFeature(
    val id:String, val name:String, val source:RuleSource, val description:String = "",
    val kind:FeatureKind = FeatureKind.OTHER, val actionType:String = "", val resourceId:String? = null
)
@Serializable data class ReforgeCharacter(
    val id: String, val name: String, val revision: Long = 0, val schemaVersion: Int = 1,
    val species: String = "", val className: String = "", val subclass: String = "",
    val background: String = "", val notes: String = "",
    val portraitRef: String? = null, val languages:Set<String> = emptySet(),
    val toolProficiencies:Set<String> = emptySet(), val features:List<CharacterFeature> = emptyList(),
    val rules: RuleVersion = RuleVersion(), val level: Int = 1,
    val baseAbilities: Map<Stat, Int?> = emptyMap(), val permanentAdvancements: Map<Stat, Int> = emptyMap(),
    /** Baseline already includes baselineConstitutionModifier at every existing level. */
    val baseMaxHp: Int? = null, val baselineConstitutionModifier: Int? = null,
    val currentHp: Int? = null, val temporaryHp: Int? = null,
    /** Outstanding lost HP is retained across capacity changes, including a clamp at zero. */
    val hpDamage: Int? = null, val baseAc: Int? = null, val armorUsesDexterity: Boolean = false,
    val armorDexterityCap: Int? = null, val speed: Int? = null,
    val movement: Map<String, Int?> = emptyMap(), val senses: Map<String, Int?> = emptyMap(),
    val exhaustionLevel: Int = 0,
    val saveProficiencies: Set<Stat> = emptySet(), val skills: Map<String, SkillTraining> = emptyMap(),
    val spellcastingAbility: Stat = Stat.INT, val spellcastingClassLevel: Int? = null,
    val slotMaximaOverride: Map<Int, Int>? = null, val spellSlots: Map<Int, Int?> = emptyMap(),
    val effects: List<EffectDefinition> = emptyList(), val itemDefinitions: List<ItemDefinition> = emptyList(),
    val items: List<ItemInstance> = emptyList(), val attunementLimit: Int = 3,
    val attacks: List<AttackDefinition> = emptyList(), val spells: List<SpellDefinition> = emptyList(),
    val resources: List<ResourcePool> = emptyList(), val conditions: Set<String> = emptySet(),
    val concentrationSpellId: String? = null, val activeStances: Set<String> = emptySet(),
    val gameSeconds: Long = 0, val turnNumber: Long = 0,
    val usedEffectTurns: Map<String, Long> = emptyMap(), val turn: TurnEconomy = TurnEconomy()
)
@Serializable data class CalculationContext(
    val targetCreatureType: String? = null, val damageType: DamageKind? = null,
    val successfulHit: Boolean = false, val tags: Set<String> = emptySet(), val attackId: String? = null
)
@Serializable data class CalculationLine(val sourceId: String, val label: String, val value: Int? = null, val applied: Boolean = true, val reason: String = "")
@Serializable data class CalculatedNumber(val value: Int?, val explanation: List<CalculationLine> = emptyList())
@Serializable data class CalculatedAbility(val permanent: Int?, val effective: CalculatedNumber, val modifier: Int?)
@Serializable data class EffectStatus(val effectId: String, val name: String, val active: Boolean, val reason: String, val itemInstanceId: String? = null)
@Serializable data class CharacterProjection(
    val abilities: Map<Stat, CalculatedAbility>, val proficiencyBonus: Int,
    val saves: Map<Stat, CalculatedNumber>, val skills: Map<String, CalculatedNumber>,
    val initiative: CalculatedNumber, val armorClass: CalculatedNumber, val maximumHp: CalculatedNumber,
    val currentHp: Int?, val speed: CalculatedNumber,
    val spellAttack: CalculatedNumber, val spellSaveDc: CalculatedNumber,
    val slotMaxima: Map<Int, Int>, val preparationLimit: Int?,
    val passiveScores: Map<String, Int?>, val effectStatuses: List<EffectStatus>,
    val defenses: List<EffectPayload.Defense>, val conditionImmunities: Set<String>,
    val resourceMaxima: Map<String, Int?>, val warnings: List<String> = emptyList(),
    val movement: Map<String, Int?> = emptyMap(), val senses: Map<String, Int?> = emptyMap()
)
