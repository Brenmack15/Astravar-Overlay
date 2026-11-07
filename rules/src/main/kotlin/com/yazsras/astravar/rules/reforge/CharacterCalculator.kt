package com.yazsras.astravar.rules.reforge

/** Pure projections. Reading a sheet never spends a resource, rolls dice, or rewrites state. */
object CharacterCalculator {
    val standardSkills:Map<String,Stat> = linkedMapOf("Acrobatics" to Stat.DEX,"Animal Handling" to Stat.WIS,"Arcana" to Stat.INT,"Athletics" to Stat.STR,"Deception" to Stat.CHA,"History" to Stat.INT,"Insight" to Stat.WIS,"Intimidation" to Stat.CHA,"Investigation" to Stat.INT,"Medicine" to Stat.WIS,"Nature" to Stat.INT,"Perception" to Stat.WIS,"Performance" to Stat.CHA,"Persuasion" to Stat.CHA,"Religion" to Stat.INT,"Sleight of Hand" to Stat.DEX,"Stealth" to Stat.DEX,"Survival" to Stat.WIS)
    internal data class ActiveEffect(val effect: EffectDefinition, val instanceId: String?)
    internal data class EvaluatedEffects(val active: List<ActiveEffect>, val statuses: List<EffectStatus>)

    fun abilityModifier(score: Int): Int = Math.floorDiv(Math.subtractExact(score,10), 2)
    fun proficiencyBonus(level: Int): Int { require(level in 1..20); return 2 + (level - 1) / 4 }

    fun calculate(character: ReforgeCharacter, context: CalculationContext = CalculationContext()): CharacterProjection {
        validate(character)
        val effects = evaluateEffects(character, context)
        val active = effects.active
        val abilities = Stat.entries.associateWith { stat ->
            val permanent = character.baseAbilities[stat]?.plus(character.permanentAdvancements[stat] ?: 0)
            val cap = number(null, NumericTarget.ABILITY_CAP, stat.name, active, "Ability maximum")
            val calculated = number(permanent, NumericTarget.ABILITY, stat.name, active, "Permanent ${stat.name}")
            val bounded = if (cap.value != null && calculated.value != null && calculated.value > cap.value) {
                calculated.copy(value=cap.value, explanation=calculated.explanation + cap.explanation +
                    CalculationLine("cap", "Ability maximum", cap.value, reason="Final value capped"))
            } else calculated
            val effective=bounded.copy(value=bounded.value?.coerceAtLeast(0))
            CalculatedAbility(permanent,effective,effective.value?.let(::abilityModifier))
        }
        val pb = proficiencyBonus(character.level)
        val saves = Stat.entries.associateWith { stat ->
            val proficient = stat in character.saveProficiencies || active.any { (it.effect.payload as? EffectPayload.Proficiency)?.save == stat }
            val base = abilities.getValue(stat).modifier?.plus(if(proficient) pb else 0)
            number(base, NumericTarget.SAVE, stat.name,active,"${stat.name} modifier${if(proficient) " + proficiency $pb" else ""}")
        }
        val trainings=standardSkills.mapValues { (_,stat)->SkillTraining(stat) }+character.skills
        val skills = trainings.mapValues { (key,training) ->
            val grants = active.mapNotNull { it.effect.payload as? EffectPayload.Proficiency }.filter { it.skill.equals(key,true) }
            val multiplier = when { training.expertise || grants.any { it.expertise } -> 2; training.proficient || grants.isNotEmpty() -> 1; else -> 0 }
            val prof = if(multiplier > 0) pb*multiplier else if(training.halfProficiency) pb/2 else 0
            number(abilities.getValue(training.ability).modifier?.plus(prof), NumericTarget.SKILL,key,active,
                "${training.ability.name} modifier${if(prof!=0) " + ${if(multiplier==2) "Expertise" else "proficiency"} $prof" else ""}")
        }
        val dex=abilities.getValue(Stat.DEX).modifier
        val acBase = character.baseAc?.let { base -> if(character.armorUsesDexterity) dex?.let { base + minOf(it,character.armorDexterityCap ?: Int.MAX_VALUE) } else base }
        val con=abilities.getValue(Stat.CON).modifier
        val hpBase = when {
            character.baseMaxHp == null -> null
            character.baselineConstitutionModifier == null -> character.baseMaxHp
            con == null -> null
            else -> Math.addExact(character.baseMaxHp,Math.multiplyExact(character.level,Math.subtractExact(con,character.baselineConstitutionModifier)))
        }
        val rawHp=number(hpBase,NumericTarget.MAX_HP,"",active,"Baseline HP${if(character.baselineConstitutionModifier!=null && con!=null) " + Constitution adjustment ${character.level*(con-character.baselineConstitutionModifier)}" else ""}")
        val hp=(if(character.rules.core==RuleFamily.LEGACY_2014 && character.exhaustionLevel>=4) rawHp.copy(value=rawHp.value?.div(2),explanation=rawHp.explanation+CalculationLine("legacy-exhaustion","Legacy Exhaustion level 4",reason="Maximum HP halved")) else rawHp).let { it.copy(value=it.value?.coerceAtLeast(0)) }
        val casting=abilities.getValue(character.spellcastingAbility).modifier
        val maxSlots = character.slotMaximaOverride ?: if(character.rules.classVersion == "legacy-artificer-infusions-2020" && character.spellcastingClassLevel != null)
            legacyArtificerSlotMaxima(character.spellcastingClassLevel) else emptyMap()
        val resources=character.resources.associate { pool -> pool.id to number(pool.maximum,NumericTarget.RESOURCE_CAPACITY,pool.id,active,pool.name).value }
        val warnings=buildList {
            if(character.baseMaxHp != null && character.baselineConstitutionModifier == null) add("HP baseline Constitution is unknown; Constitution-dependent HP changes require reviewed configuration.")
            if(character.spellcastingClassLevel!=null && character.rules.classVersion != "legacy-artificer-infusions-2020" && character.slotMaximaOverride == null) add("This class version needs explicit spell-slot capacities; legacy Artificer slots were not substituted.")
            if(character.items.any { i->character.itemDefinitions.none { it.id==i.definitionId } }) add("Some inventory definitions are missing; their effects are inactive.")
        }
        return CharacterProjection(abilities,pb,saves,skills,
            number(dex,NumericTarget.INITIATIVE,"",active,"Dexterity modifier"),
            number(acBase,NumericTarget.ARMOR_CLASS,"",active,"Armor calculation"),hp,
            if(character.currentHp==null) null else character.hpDamage?.let { lost->hp.value?.let { maxOf(0,it-lost) } } ?: character.currentHp.let { current->hp.value?.let { minOf(current,it) } ?: current },
            number(character.speed,NumericTarget.SPEED,"walk",active,"Walking speed").let { n->n.copy(value=n.value?.let { maxOf(0,if(character.rules.core==RuleFamily.LEGACY_2014 && character.exhaustionLevel>=2) it/2 else it) }) },
            number(casting?.plus(pb),NumericTarget.SPELL_ATTACK,"",active,"Spellcasting modifier + proficiency $pb"),
            number(casting?.plus(pb+8),NumericTarget.SPELL_DC,"",active,"8 + spellcasting modifier + proficiency $pb"),
            maxSlots,
            if(character.rules.classVersion=="legacy-artificer-infusions-2020" && character.spellcastingClassLevel!=null) casting?.let { maxOf(1,it+character.spellcastingClassLevel/2) } else null,
            skills.filterKeys { it in setOf("Perception","Investigation","Insight") }.mapValues { (key,value)->value.value?.let { 10+it+when(rollMode(character,RollKind.SKILL,key,context)) { DiceMode.ADVANTAGE->5;DiceMode.DISADVANTAGE->-5;else->0 } } }, effects.statuses,
            active.mapNotNull { it.effect.payload as? EffectPayload.Defense },
            active.mapNotNull { (it.effect.payload as? EffectPayload.ConditionImmunity)?.condition }.toSet(),resources,warnings,
            (character.movement+("walk" to character.speed)).mapValues { (key,base)->number(base,NumericTarget.SPEED,key,active,"$key speed").value?.let { value->maxOf(0,if(character.rules.core==RuleFamily.LEGACY_2014 && character.exhaustionLevel>=2) value/2 else value) } },character.senses)
    }

    internal fun number(base: Int?,target: NumericTarget,key: String,active: List<ActiveEffect>,baseLabel: String): CalculatedNumber {
        val relevant=active.filter { a->(a.effect.payload as? EffectPayload.Number)?.let { it.target==target && (it.key.isBlank() || it.key.equals(key,true)) }==true }
        var result=base
        val lines=mutableListOf(CalculationLine("base",baseLabel,base,reason=if(base==null) "Unknown" else "Stored baseline"))
        val sets=relevant.filter { (it.effect.payload as EffectPayload.Number).operation==NumberOperation.SET }
        val chosen=sets.sortedWith(compareByDescending<ActiveEffect>{it.effect.priority}.thenByDescending{(it.effect.payload as EffectPayload.Number).amount}.thenBy{it.effect.id}).firstOrNull()
        sets.forEach { a->val p=a.effect.payload as EffectPayload.Number; lines+=CalculationLine(a.effect.source.id,a.effect.name,p.amount,a==chosen,if(a==chosen) "Sets value" else "Replaced by higher-priority setting") }
        if(chosen!=null) result=(chosen.effect.payload as EffectPayload.Number).amount
        relevant.filter { (it.effect.payload as EffectPayload.Number).operation==NumberOperation.ADD }.sortedBy { it.effect.id }.forEach { a->
            val p=a.effect.payload as EffectPayload.Number
            result=result?.let { Math.addExact(it,p.amount) }
            lines+=CalculationLine(a.effect.source.id,a.effect.name,p.amount,reason=if(result==null) "Bonus known; baseline unknown" else "Additive bonus")
        }
        relevant.filter { (it.effect.payload as EffectPayload.Number).operation==NumberOperation.MINIMUM }.forEach { a->
            val p=a.effect.payload as EffectPayload.Number
            // A minimum is not an exact value when the baseline is unknown.
            result=result?.let { maxOf(it,p.amount) }
            lines+=CalculationLine(a.effect.source.id,a.effect.name,p.amount,reason="Minimum value${if(result==null) "; baseline unknown" else ""}")
        }
        relevant.filter { (it.effect.payload as EffectPayload.Number).operation==NumberOperation.MAXIMUM }.forEach { a->
            val p=a.effect.payload as EffectPayload.Number; result=result?.let { minOf(it,p.amount) }
            lines+=CalculationLine(a.effect.source.id,a.effect.name,p.amount,reason="Maximum value")
        }
        return CalculatedNumber(result,lines)
    }

    internal fun evaluateEffects(character: ReforgeCharacter,context: CalculationContext): EvaluatedEffects {
        val statuses=mutableListOf<EffectStatus>()
        val candidates=mutableListOf<ActiveEffect>()
        val donors=character.items.flatMap { it.donorInstanceIds }.toSet()
        fun consider(effect: EffectDefinition,instance: ItemInstance?,definition: ItemDefinition?) {
            val blocked=when {
                !effect.enabled -> "Disabled"
                effect.source.acceptance !in setOf(Acceptance.OFFICIAL,Acceptance.ACCEPTED_HOMEBREW) -> "${effect.source.acceptance.name.lowercase()} mechanics are inactive"
                effect.expiresAtGameSeconds?.let { it<=character.gameSeconds }==true -> "Expired"
                instance!=null && instance.id in donors -> "Integrated donor; composite item owns its mechanics"
                instance!=null && (!instance.operating || instance.quantity<=0) -> "Item unavailable"
                instance!=null && instance.state !in setOf(ItemState.WORN,ItemState.HELD) -> "Item is not equipped"
                definition?.requiresAttunement==true && effect.requiresAttunement && instance?.attuned!=true -> "Requires attunement"
                effect.chargeResourceId!=null && (character.resources.find { it.id==effect.chargeResourceId }?.current ?: -1)<maxOf(1,effect.chargeCost) -> "Charge unavailable or unknown"
                else -> effect.conditions.firstNotNullOfOrNull { condition -> conditionFailure(character,effect,condition,instance,context) }
            }
            statuses+=EffectStatus(effect.id,effect.name,blocked==null,blocked ?: "Active",instance?.id)
            if(blocked==null) candidates+=ActiveEffect(effect,instance?.id)
        }
        character.effects.forEach { consider(it,null,null) }
        character.items.forEach { instance->character.itemDefinitions.find { it.id==instance.definitionId }?.let { definition->definition.effects.forEach { consider(it,instance,definition) } } }
        val immunities=candidates.mapNotNull { (it.effect.payload as? EffectPayload.ConditionImmunity)?.condition }.toSet()
        val immuneConditions=if(character.conditions.any { it.equals("petrified",true) }) immunities+"poisoned" else immunities
        ConditionRules.effects(character.copy(conditions=ConditionRules.activeConditions(character,immuneConditions))).forEach { consider(it,null,null) }
        val selected=candidates.groupBy { it.effect.stackingKey ?: it.effect.id }.values.map { group ->
            group.sortedWith(compareByDescending<ActiveEffect>{it.effect.priority}.thenByDescending{(it.effect.payload as? EffectPayload.Number)?.amount ?: 0}.thenBy{it.effect.id}.thenBy{it.instanceId ?: ""}).first()
        }
        candidates.filter { it !in selected }.forEach { dropped ->
            val index=statuses.indexOfFirst { it.effectId==dropped.effect.id && it.itemInstanceId==dropped.instanceId && it.active }
            if(index>=0) statuses[index]=statuses[index].copy(active=false,reason="Same benefit already applied; stacking suppressed")
        }
        return EvaluatedEffects(selected,statuses)
    }

    private fun conditionFailure(c:ReforgeCharacter,e:EffectDefinition,condition:EffectCondition,item:ItemInstance?,context:CalculationContext):String? {
        val okay=when(condition.kind) {
            ConditionKind.EQUIPPED -> item?.state in setOf(ItemState.WORN,ItemState.HELD)
            ConditionKind.ATTUNED -> item?.attuned==true
            ConditionKind.HELD -> item?.state==ItemState.HELD
            ConditionKind.STANCE -> condition.key in c.activeStances
            ConditionKind.CREATURE_TYPE -> condition.key.equals(context.targetCreatureType,true)
            ConditionKind.DAMAGE_TYPE -> condition.key.equals(context.damageType?.name,true)
            ConditionKind.SUCCESSFUL_HIT -> context.successfulHit
            ConditionKind.CHARGE_AVAILABLE -> (c.resources.find { it.id==condition.key }?.current ?: -1)>=condition.minimum
            ConditionKind.ONCE_PER_TURN -> c.usedEffectTurns[e.id]!=c.turnNumber
            ConditionKind.CONTEXT_TAG -> condition.key in context.tags
        }
        return if(okay) null else "Requires ${condition.kind.name.lowercase().replace('_',' ')}${condition.key.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
    }

    fun rollMode(character: ReforgeCharacter,kind:RollKind,key:String="",context:CalculationContext=CalculationContext(),advantage:Boolean=false,disadvantage:Boolean=false):DiceMode {
        val edges=evaluateEffects(character,context).active.mapNotNull { it.effect.payload as? EffectPayload.Edge }.filter { it.kind==kind && (it.key.isBlank() || it.key.equals(key,true)) }
        val adv=advantage || edges.any { it.edge==RollEdge.ADVANTAGE }
        val dis=disadvantage || edges.any { it.edge==RollEdge.DISADVANTAGE }
        return when { adv && dis -> DiceMode.NORMAL; adv -> DiceMode.ADVANTAGE; dis -> DiceMode.DISADVANTAGE; else -> DiceMode.NORMAL }
    }

    /** Legacy Infusions Artificer progression, kept separate from revised class rules. */
    fun legacyArtificerSlotMaxima(level:Int):Map<Int,Int> {
        require(level in 1..20)
        val row=when(level) {
            1,2->listOf(2);3,4->listOf(3);5,6->listOf(4,2);7,8->listOf(4,3)
            9,10->listOf(4,3,2);11,12->listOf(4,3,3);13,14->listOf(4,3,3,1)
            15,16->listOf(4,3,3,2);17,18->listOf(4,3,3,3,1);else->listOf(4,3,3,3,2)
        }
        return row.mapIndexed { i,n->i+1 to n }.toMap()
    }

    fun validate(c:ReforgeCharacter) {
        require(c.id.isNotBlank() && c.id.length<=200 && c.name.length in 1..200)
        require(c.level in 1..20 && c.revision>=0 && c.schemaVersion==1)
        require(c.baseAbilities.values.all { it==null || it in 0..100 })
        require(c.permanentAdvancements.values.all { it in -100..100 })
        require(c.baseMaxHp==null || c.baseMaxHp in 0..1_000_000)
        require(c.baselineConstitutionModifier==null || c.baselineConstitutionModifier in -100..100)
        require(c.currentHp==null || c.currentHp>=0);require(c.temporaryHp==null || c.temporaryHp>=0);require(c.hpDamage==null || c.hpDamage>=0)
        require(c.attunementLimit in 0..100 && c.gameSeconds>=0 && c.turnNumber>=0)
        require(c.exhaustionLevel in 0..6 && c.turn.attacksPerAction in 1..100 && c.turn.attacksUsed>=0)
        require(c.items.map { it.id }.distinct().size==c.items.size && c.itemDefinitions.map { it.id }.distinct().size==c.itemDefinitions.size)
        require(c.items.size<=10_000 && c.itemDefinitions.size<=10_000 && c.resources.size<=10_000 && c.attacks.size<=10_000 && c.spells.size<=10_000) { "Character record exceeds the supported collection size." }
        require(c.resources.map { it.id }.distinct().size==c.resources.size && c.attacks.map { it.id }.distinct().size==c.attacks.size && c.spells.map { it.id }.distinct().size==c.spells.size)
        require(c.items.all { it.id.isNotBlank() && it.quantity in 0..1_000_000 && it.id !in it.donorInstanceIds })
        require(c.itemDefinitions.all { it.id.isNotBlank() && it.attunementCost in 0..100 && it.minimumLevel in 1..20 })
        require(c.resources.all { (it.maximum==null || it.maximum in 0..1_000_000) && (it.current==null || it.current in 0..1_000_000) && (it.recoveryAmount==null || it.recoveryAmount in 0..1_000_000) && (it.dieSides==null || it.dieSides in 2..1000) })
        require(c.spells.all { it.level in 0..9 && it.resourceCost>=0 })
        require(c.attacks.all { it.fixedAttackBonus==null || it.fixedAttackBonus in -1000..1000 })
        require(c.spellcastingClassLevel==null || c.spellcastingClassLevel in 1..20)
        require(c.slotMaximaOverride?.all { (level,max)->level in 1..9 && max in 0..100 }!=false)
        require(c.spellSlots.all { (level,count)->level in 1..9 && (count==null || count in 0..100) })
        val allEffects=c.effects+c.itemDefinitions.flatMap { it.effects }
        require(allEffects.size<=10_000 && allEffects.all { it.id.isNotBlank() && it.chargeCost in 0..1_000_000 && it.priority in -10_000..10_000 })
        require(allEffects.map { it.id }.distinct().size==allEffects.size) { "Effect IDs must be unique across definitions and character effects." }
        require(allEffects.all { ((it.payload as? EffectPayload.Number)?.amount ?: 0) in -1_000_000..1_000_000 })
        require(c.features.map { it.id }.distinct().size==c.features.size && c.features.all { it.id.isNotBlank() && it.name.isNotBlank() })
        val itemsById=c.items.associateBy { it.id }
        val counts=c.itemDefinitions.associate { it.id to it.effects.size }
        require(c.items.sumOf { counts[it.definitionId]?.toLong() ?: 0 }+c.effects.size<=50_000L) { "Character equipment expands to too many effect instances." }
        val parents=mutableMapOf<String,MutableList<String>>()
        val indegree=itemsById.mapValues { 0 }.toMutableMap()
        c.items.forEach { composite->composite.donorInstanceIds.filter { it in itemsById }.forEach { donor->
            parents.getOrPut(donor) { mutableListOf() }.add(composite.id)
            indegree[composite.id]=indegree.getValue(composite.id)+1
        } }
        val queue=java.util.ArrayDeque(indegree.filterValues { it==0 }.keys)
        var visited=0
        while(queue.isNotEmpty()) {
            val item=queue.removeFirst();visited++
            parents[item].orEmpty().forEach { parent->indegree[parent]=indegree.getValue(parent)-1;if(indegree[parent]==0) queue.add(parent) }
        }
        require(visited==c.items.size) { "Composite donor relationships contain a cycle." }
    }
}
