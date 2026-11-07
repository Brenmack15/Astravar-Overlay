package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.Serializable

@Serializable data class AttackPreview(
    val characterId:String, val revision:Long, val attackId:String, val attackBonus:CalculatedNumber,
    val components:List<DamageComponent>, val effectIds:List<String>, val critical:Boolean,
    val context:CalculationContext, val chargeCosts:Map<String,Int>, val actionType:String,
    val attemptChargeCosts:Map<String,Int> = emptyMap(), val attemptEffectIds:List<String> = emptyList()
)
@Serializable data class ResolvedDamageComponent(val type:DamageKind,val rolled:Int,val applied:Int,val explanation:String)
@Serializable data class DamageResolution(val components:List<ResolvedDamageComponent>) { val total:Int get()=components.sumOf { it.applied } }

object AttackRules {
    /** Incoming damage is already rolled; this only resolves the selected type and defenses. */
    fun resolveIncoming(character:ReforgeCharacter,type:DamageKind,amount:Int,magical:Boolean=false):DamageResolution {
        require(amount in 0..1_000_000) { "Damage must be a nonnegative whole number." }
        val defenses=CharacterCalculator.calculate(character,CalculationContext(damageType=type)).defenses
        val component=DamageComponent("incoming","Incoming damage",type,DiceFormula(flat=amount),criticalDice=false,magical=magical)
        val packet=AttackPreview(character.id,character.revision,"incoming",CalculatedNumber(null),listOf(component),emptyList(),false,CalculationContext(damageType=type),emptyMap(),"")
        return resolveDamage(packet,mapOf("incoming" to amount),defenses)
    }
    fun preview(character:ReforgeCharacter,attackId:String,context:CalculationContext=CalculationContext(),critical:Boolean=false):AttackPreview {
        val projection=CharacterCalculator.calculate(character,context)
        val attack=character.attacks.singleOrNull { it.id==attackId } ?: error("Attack not found.")
        require(ConditionRules.activeConditions(character,projection.conditionImmunities).none { it in setOf("incapacitated","unconscious","stunned","paralyzed","petrified") }) { "The current condition prevents attacks." }
        attack.requiredItemId?.let { id->require(character.items.any { it.id==id && it.state in setOf(ItemState.HELD,ItemState.WORN) && it.operating && it.quantity==1 } && character.items.none { id in it.donorInstanceIds }) { "Equip the required standalone item first." } }
        val attackContext=context.copy(attackId=attackId,tags=context.tags+attack.tags)
        val all=CharacterCalculator.evaluateEffects(character,attackContext.copy(successfulHit=true)).active
        val attackEffects=CharacterCalculator.evaluateEffects(character,attackContext.copy(successfulHit=false)).active
        val ability=projection.abilities.getValue(attack.ability).modifier
        val toHit=CharacterCalculator.number(attack.fixedAttackBonus ?: ability?.plus(attack.attackBonus+if(attack.proficient) projection.proficiencyBonus else 0),NumericTarget.ATTACK,attackId,attackEffects,if(attack.fixedAttackBonus!=null) "Reviewed independent attack bonus" else "${attack.ability.name} modifier + attack proficiency")
        val bonusEffects=all.filter { a->(a.effect.payload as? EffectPayload.DamageBonus)?.let { qualifies(attack,it.qualifier) }==true }
        val conversionEffects=all.filter { a->(a.effect.payload as? EffectPayload.DamageConversion)?.let { qualifies(attack,it.qualifier) }==true }
        val conversions=conversionEffects.map { it.effect.payload as EffectPayload.DamageConversion }
        val damageNumberEffects=all.filter { a->(a.effect.payload as? EffectPayload.Number)?.let { it.target==NumericTarget.DAMAGE && (it.key.isBlank() || it.key==attackId) }==true }
        var components=attack.damage+bonusEffects.map { (it.effect.payload as EffectPayload.DamageBonus).component.copy(id="effect:${it.effect.id}:${(it.effect.payload as EffectPayload.DamageBonus).component.id}") }
        require(components.size<=512) { "An attack may contain at most 512 typed damage components." }
        require(components.map { it.id }.distinct().size==components.size) { "Damage component IDs must be unique." }
        val flatBonus=CharacterCalculator.number(0,NumericTarget.DAMAGE,attackId,all,"Damage bonus").value ?: 0
        if(components.isNotEmpty()) {
            require(!attack.addAbilityToDamage || ability!=null) { "Damage ability is unknown; configure the score before rolling." }
            components=components.mapIndexed { index,component ->
                val flat=if(index==0) Math.addExact(Math.addExact(component.formula.flat,flatBonus),if(attack.addAbilityToDamage) ability!! else 0) else component.formula.flat
                var type=component.type
                conversions.forEach { if(type==it.from) type=it.to }
                component.copy(type=type,formula=component.formula.copy(flat=flat).let { if(critical && component.criticalDice) SecureDice.critical(it) else it })
            }
        }
        components.forEach { SecureDice.validate(it.formula) }
        val hitEffects=bonusEffects+conversionEffects+damageNumberEffects
        val chargeEffects=hitEffects.filter { it.effect.chargeResourceId!=null && it.effect.chargeCost>0 }
        val costs=chargeEffects.groupBy { it.effect.chargeResourceId!! }.mapValues { (_,effects)->effects.fold(0) { sum,effect->Math.addExact(sum,effect.effect.chargeCost) } }
        val attemptEffects=attackEffects.filter { a->(a.effect.payload as? EffectPayload.Number)?.let { it.target==NumericTarget.ATTACK && (it.key.isBlank() || it.key==attackId) }==true }
        val attemptCosts=attemptEffects.filter { it.effect.chargeResourceId!=null && it.effect.chargeCost>0 }.groupBy { it.effect.chargeResourceId!! }.mapValues { (_,effects)->effects.fold(0) { sum,effect->Math.addExact(sum,effect.effect.chargeCost) } }
        require((costs.keys+attemptCosts.keys).all { id->(character.resources.find { it.id==id }?.current ?: -1)>=Math.addExact(costs[id] ?: 0,attemptCosts[id] ?: 0) }) { "Combined effects exceed available charges." }
        if(character.turn.tracking) {
            if(attack.actionType.equals("Action",true)) require(character.turn.actionAvailable && character.turn.attacksUsed<character.turn.attacksPerAction) { "No attacks remain in this Action." }
            else GameplayRules.checkAction(character,attack.actionType)
        }
        return AttackPreview(character.id,character.revision,attackId,toHit,components,hitEffects.map { it.effect.id },critical,attackContext,costs,attack.actionType,attemptCosts,attemptEffects.map { it.effect.id })
    }
    private fun qualifies(attack:AttackDefinition,q:AttackQualifier)= (q.attackIds.isEmpty() || attack.id in q.attackIds) && attack.tags.containsAll(q.requiredTags)

    /** Rolled totals belong to one damage event; same-type components combine before defenses. */
    fun resolveDamage(preview:AttackPreview,rolled:Map<String,Int>,defenses:List<EffectPayload.Defense> = emptyList()):DamageResolution {
        require(rolled.keys==preview.components.map { it.id }.toSet()) { "Provide each damage component exactly once." }
        preview.components.forEach { component->
            val low=component.formula.flat+component.formula.dice.sumOf { if(it.sign>0) it.count else -it.count*it.sides }
            val high=component.formula.flat+component.formula.dice.sumOf { if(it.sign>0) it.count*it.sides else -it.count }
            require(rolled.getValue(component.id) in low..high) { "Recorded damage is outside the formula's possible range." }
        }
        // Split only when the actual applicable defenses differ; otherwise combine before rounding.
        val packets=preview.components.groupBy { component->component.type to defenses.filter { it.type==component.type && (!it.nonmagicalOnly || !component.magical) }.map { it.kind }.toSet() }
        return DamageResolution(packets.map { (key,components)->
            val (type,applicable)=key
            val total=maxOf(0,components.sumOf { rolled.getValue(it.id) })
            val immune=DefenseKind.IMMUNITY in applicable
            val resistant=DefenseKind.RESISTANCE in applicable
            val vulnerable=DefenseKind.VULNERABILITY in applicable
            var applied=if(immune) 0 else total
            if(!immune && resistant) applied/=2
            if(!immune && vulnerable) applied*=2
            ResolvedDamageComponent(type,total,applied,when { immune->"Immune";resistant && vulnerable->"Resistance (round down), then Vulnerability";resistant->"Resistance (round down)";vulnerable->"Vulnerability";else->"No applicable defense" })
        })
    }
    fun commitHit(character:ReforgeCharacter,preview:AttackPreview):ReforgeCharacter=commitAttack(character,preview,true)
    fun commitAttack(character:ReforgeCharacter,preview:AttackPreview,hit:Boolean):ReforgeCharacter {
        require(character.id==preview.characterId && character.revision==preview.revision) { "Character changed; review a fresh attack preview." }
        require(this.preview(character,preview.attackId,preview.context,preview.critical)==preview) { "Attack preview changed." }
        val turn=if(!character.turn.tracking) character.turn else if(preview.actionType.equals("Action",true)) {
            val spent=character.turn.attacksUsed+1
            character.turn.copy(attacksUsed=spent,actionAvailable=spent<character.turn.attacksPerAction)
        } else GameplayRules.spendAction(character,preview.actionType)
        val active=CharacterCalculator.evaluateEffects(character,preview.context.copy(successfulHit=true)).active
        val used=preview.attemptEffectIds+if(hit) preview.effectIds else emptyList()
        val once=active.filter { it.effect.id in used && it.effect.conditions.any { condition->condition.kind==ConditionKind.ONCE_PER_TURN } }.map { it.effect.id }
        return character.copy(turn=turn,
            resources=character.resources.map { pool->val cost=(preview.attemptChargeCosts[pool.id] ?: 0)+if(hit) preview.chargeCosts[pool.id] ?: 0 else 0; if(cost>0) pool.copy(current=pool.current!!-cost) else pool },
            usedEffectTurns=character.usedEffectTurns+once.associateWith { character.turnNumber })
    }
}
