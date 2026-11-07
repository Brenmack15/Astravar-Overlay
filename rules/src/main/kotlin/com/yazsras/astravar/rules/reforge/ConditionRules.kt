package com.yazsras.astravar.rules.reforge

/** Small, explicit rule-version aware condition projection, not narrative parsing. */
object ConditionRules {
    private val source=RuleSource("srd-5.2-condition-rules","Revised core condition rules",Acceptance.OFFICIAL)
    fun activeConditions(c:ReforgeCharacter,immunities:Set<String>):Set<String> = c.conditions.map { it.lowercase() }.filterNot { condition->immunities.any { it.equals(condition,true) } }.toSet()
    fun effects(c:ReforgeCharacter):List<EffectDefinition> = buildList {
        val conditions=c.conditions.map { it.lowercase() }.toSet()
        fun add(condition:String,suffix:String,payload:EffectPayload,requires:List<EffectCondition> = emptyList()) {
            add(EffectDefinition("condition:$condition:$suffix",condition.replaceFirstChar { it.uppercase() },source,payload,conditions=requires,requiresAttunement=false,priority=10_000))
        }
        fun disadvantage(condition:String,kind:RollKind,key:String="")=add(condition,"${kind.name}:$key",EffectPayload.Edge(kind,RollEdge.DISADVANTAGE,key))
        for(condition in conditions) {
            when(condition) {
                "poisoned" -> { disadvantage(condition,RollKind.ABILITY_CHECK);disadvantage(condition,RollKind.SKILL);disadvantage(condition,RollKind.INITIATIVE);disadvantage(condition,RollKind.ATTACK);disadvantage(condition,RollKind.SPELL_ATTACK) }
                "blinded","prone","restrained" -> { disadvantage(condition,RollKind.ATTACK);disadvantage(condition,RollKind.SPELL_ATTACK) }
                "frightened" -> {
                    val scope=listOf(EffectCondition(ConditionKind.CONTEXT_TAG,"fear-source-visible"))
                    listOf(RollKind.ABILITY_CHECK,RollKind.SKILL,RollKind.ATTACK,RollKind.SPELL_ATTACK,RollKind.INITIATIVE).forEach { add(condition,it.name,EffectPayload.Edge(it,RollEdge.DISADVANTAGE),scope) }
                }
                "invisible" -> {
                    val scope=listOf(EffectCondition(ConditionKind.CONTEXT_TAG,"target-cannot-see-you"))
                    listOf(RollKind.ATTACK,RollKind.SPELL_ATTACK).forEach { add(condition,it.name,EffectPayload.Edge(it,RollEdge.ADVANTAGE),scope) }
                    if(c.rules.core==RuleFamily.REVISED_2024) add(condition,"initiative",EffectPayload.Edge(RollKind.INITIATIVE,RollEdge.ADVANTAGE))
                }
                "grappled" -> if(c.rules.core==RuleFamily.REVISED_2024) {
                    val scope=listOf(EffectCondition(ConditionKind.CONTEXT_TAG,"target-is-not-grappler"))
                    listOf(RollKind.ATTACK,RollKind.SPELL_ATTACK).forEach { add(condition,it.name,EffectPayload.Edge(it,RollEdge.DISADVANTAGE),scope) }
                }
            }
            if(c.rules.core==RuleFamily.REVISED_2024 && condition in setOf("incapacitated","paralyzed","petrified","unconscious","stunned")) disadvantage(condition,RollKind.INITIATIVE)
            if(condition in setOf("grappled","restrained","paralyzed","petrified","unconscious"))
                add(condition,"speed",EffectPayload.Number(NumericTarget.SPEED,NumberOperation.MAXIMUM,0))
            if(condition=="restrained") disadvantage(condition,RollKind.SAVE,Stat.DEX.name)
            if(condition=="petrified") {
                DamageKind.entries.forEach { add(condition,it.name,EffectPayload.Defense(it,DefenseKind.RESISTANCE)) }
                add(condition,"poison-immunity",EffectPayload.ConditionImmunity("poisoned"))
            }
        }
        if(c.exhaustionLevel>0 && c.rules.core==RuleFamily.REVISED_2024) {
            listOf(NumericTarget.SAVE,NumericTarget.SKILL,NumericTarget.INITIATIVE,NumericTarget.ATTACK,NumericTarget.SPELL_ATTACK).forEach { target ->
                add("exhaustion",target.name,EffectPayload.Number(target,NumberOperation.ADD,-2*c.exhaustionLevel))
            }
            add("exhaustion","speed",EffectPayload.Number(NumericTarget.SPEED,NumberOperation.ADD,-5*c.exhaustionLevel))
        }
        if(c.exhaustionLevel>0 && c.rules.core==RuleFamily.LEGACY_2014) {
            listOf(RollKind.ABILITY_CHECK,RollKind.SKILL,RollKind.INITIATIVE).forEach { disadvantage("exhaustion",it) }
            if(c.exhaustionLevel>=3) listOf(RollKind.ATTACK,RollKind.SPELL_ATTACK,RollKind.SAVE).forEach { disadvantage("exhaustion",it) }
            if(c.exhaustionLevel>=5) add("exhaustion","speed",EffectPayload.Number(NumericTarget.SPEED,NumberOperation.MAXIMUM,0))
        }
    }
    fun rollRestriction(character:ReforgeCharacter,kind:RollKind,key:String="",context:CalculationContext=CalculationContext()):String? {
        val conditions=activeConditions(character,CharacterCalculator.calculate(character).conditionImmunities)
        if(kind==RollKind.SAVE && key.uppercase() in setOf("STR","DEX") && conditions.any { it in setOf("paralyzed","petrified","unconscious","stunned") })
            return "This condition automatically fails ${key.uppercase()} saving throws; no die roll is needed."
        if(kind in setOf(RollKind.ABILITY_CHECK,RollKind.SKILL) && (("blinded" in conditions && "requires-sight" in context.tags) || ("deafened" in conditions && "requires-hearing" in context.tags)))
            return "The required sense is unavailable; this check automatically fails."
        if(character.exhaustionLevel>=6) return "Exhaustion has reached the fatal level; resolve the character's state with the DM."
        return null
    }
    fun abilityCheckModifier(character:ReforgeCharacter,stat:Stat):Int? = CharacterCalculator.calculate(character).abilities.getValue(stat).modifier?.let {
        it-if(character.rules.core==RuleFamily.REVISED_2024) 2*character.exhaustionLevel else 0
    }
}
