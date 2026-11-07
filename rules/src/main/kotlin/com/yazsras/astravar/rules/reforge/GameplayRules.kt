package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.Serializable

@Serializable data class CastPreview(
    val characterId: String, val revision: Long, val spellId: String, val slotLevel: Int?,
    val resourceId: String?, val resourceCost: Int, val replacesConcentration: String?,
    val replaceConcentration: Boolean, val ritual: Boolean, val summary: String
)
@Serializable data class EquipmentChangePreview(val before:CharacterProjection,val after:CharacterProjection,val character:ReforgeCharacter)
@Serializable data class HitDiePreview(
    val characterId:String,val revision:Long,val resourceId:String,val dieFace:Int,
    val healing:Int,val previousHp:Int,val nextHp:Int,val previousUses:Int,val nextUses:Int
)

/** All changes are pure values. The repository owns atomic revision checks and idempotent receipts. */
object GameplayRules {
    fun previewEquipment(character:ReforgeCharacter,itemId:String,state:ItemState):EquipmentChangePreview {
        val next=equip(character,itemId,state)
        return EquipmentChangePreview(CharacterCalculator.calculate(character),CharacterCalculator.calculate(next),next)
    }
    fun equip(character:ReforgeCharacter,itemId:String,state:ItemState):ReforgeCharacter {
        CharacterCalculator.validate(character)
        val item=character.items.singleOrNull { it.id==itemId } ?: error("Item not found.")
        val definition=character.itemDefinitions.singleOrNull { it.id==item.definitionId } ?: error("Item definition is missing.")
        if(state in setOf(ItemState.WORN,ItemState.HELD)) {
            require(item.quantity==1) { "Split this stack before equipping an individual item." }
            require(character.items.none { itemId in it.donorInstanceIds }) { "This donor item is integrated into a composite item." }
            require(character.level>=definition.minimumLevel) { "Requires level ${definition.minimumLevel}." }
            val abilities=CharacterCalculator.calculate(character).abilities
            require(definition.minimumAbilities.all { (stat,min)->(abilities[stat]?.effective?.value ?: -1)>=min }) { "An ability requirement is unmet or unknown." }
            require(definition.equipmentSlot==null || character.items.none { other->other.id!=itemId && other.state in setOf(ItemState.WORN,ItemState.HELD) && character.itemDefinitions.find { it.id==other.definitionId }?.equipmentSlot==definition.equipmentSlot }) { "That equipment slot is occupied. Unequip its item first." }
        }
        return preserveCapacities(character,character.copy(items=character.items.map { if(it.id==itemId) it.copy(state=state) else it }))
    }
    fun attune(character:ReforgeCharacter,itemId:String,attuned:Boolean):ReforgeCharacter {
        CharacterCalculator.validate(character)
        val item=character.items.singleOrNull { it.id==itemId } ?: error("Item not found.")
        val definition=character.itemDefinitions.singleOrNull { it.id==item.definitionId } ?: error("Item definition is missing.")
        if(attuned) {
            require(definition.requiresAttunement) { "This item does not require attunement." }
            require(item.quantity==1 && item.state!=ItemState.STORED) { "Choose one accessible item to attune." }
            require(character.items.none { itemId in it.donorInstanceIds }) { "Attune the composite item instead of its integrated donor." }
            val used=character.items.filter { it.id!=itemId && it.attuned }.sumOf { i->character.itemDefinitions.find { it.id==i.definitionId }?.attunementCost ?: character.attunementLimit+1 }
            require(used+definition.attunementCost<=character.attunementLimit) { "Attunement capacity would be exceeded." }
        }
        return preserveCapacities(character,character.copy(items=character.items.map { if(it.id==itemId) it.copy(attuned=attuned) else it }))
    }

    /** Preserve outstanding damage rather than treating a capacity change as healing or a rest. */
    fun preserveCapacities(before:ReforgeCharacter,after:ReforgeCharacter):ReforgeCharacter {
        val previous=CharacterCalculator.calculate(before)
        val next=CharacterCalculator.calculate(after)
        val damage=before.hpDamage ?: previous.maximumHp.value?.let { maximum->previous.currentHp?.let { maxOf(0,maximum-it) } }
        val current=if(before.currentHp==null) null else if(damage!=null && next.maximumHp.value!=null) maxOf(0,next.maximumHp.value-damage) else before.currentHp
        return after.copy(currentHp=current,hpDamage=damage,resources=after.resources.map { pool->
            val cap=next.resourceMaxima[pool.id]
            pool.copy(current=pool.current?.let { if(cap!=null) minOf(it,cap) else it })
        })
    }
    fun damage(character:ReforgeCharacter,amount:Int):ReforgeCharacter {
        require(amount in 0..1_000_000) { "Damage must be a nonnegative whole number." }
        val p=CharacterCalculator.calculate(character)
        require(p.currentHp!=null && character.temporaryHp!=null) { "Current and temporary HP must be known before applying damage." }
        val absorbed=minOf(amount,character.temporaryHp)
        val lost=character.hpDamage ?: p.maximumHp.value?.let { maxOf(0,it-p.currentHp) }
        val nextLost=lost?.let { Math.addExact(it,amount-absorbed) }
        val current=if(nextLost!=null && p.maximumHp.value!=null) maxOf(0,p.maximumHp.value-nextLost) else maxOf(0,p.currentHp-(amount-absorbed))
        return character.copy(currentHp=current,temporaryHp=character.temporaryHp-absorbed,hpDamage=nextLost)
    }
    fun heal(character:ReforgeCharacter,amount:Int):ReforgeCharacter {
        require(amount in 0..1_000_000) { "Healing must be a nonnegative whole number." }
        val p=CharacterCalculator.calculate(character)
        require(p.currentHp!=null && p.maximumHp.value!=null) { "Current and maximum HP must be known before healing." }
        val lost=character.hpDamage ?: maxOf(0,p.maximumHp.value-p.currentHp)
        val nextLost=maxOf(0,lost-amount)
        val current=maxOf(0,p.maximumHp.value-nextLost)
        return character.copy(currentHp=current,hpDamage=nextLost)
    }
    fun previewHitDie(character:ReforgeCharacter,resourceId:String,dieFace:Int,confirmedEligibleRest:Boolean):HitDiePreview {
        require(confirmedEligibleRest) { "Confirm this Hit Die is being spent during an eligible completed Short Rest." }
        val p=CharacterCalculator.calculate(character)
        val pool=character.resources.singleOrNull { it.id==resourceId } ?: error("Hit Dice pool not found.")
        val sides=pool.dieSides ?: error("Configure this pool's Hit Die size first.")
        require(dieFace in 1..sides) { "The entered Hit Die face is outside 1–$sides." }
        require(pool.current!=null && pool.current>0) { "Available Hit Dice are empty or unknown." }
        require(p.currentHp!=null && p.maximumHp.value!=null) { "Current and maximum HP must be known before spending Hit Dice." }
        val con=p.abilities.getValue(Stat.CON).modifier ?: error("Constitution is unknown.")
        val healing=maxOf(0,Math.addExact(dieFace,con))
        return HitDiePreview(character.id,character.revision,resourceId,dieFace,healing,p.currentHp,heal(character,healing).currentHp!!,pool.current,pool.current-1)
    }
    fun spendHitDie(character:ReforgeCharacter,resourceId:String,dieFace:Int,confirmedEligibleRest:Boolean):ReforgeCharacter {
        val preview=previewHitDie(character,resourceId,dieFace,confirmedEligibleRest)
        val healed=heal(character,preview.healing)
        return healed.copy(resources=healed.resources.map { if(it.id==resourceId) it.copy(current=preview.nextUses) else it })
    }
    fun grantTemporaryHp(character:ReforgeCharacter,amount:Int,replaceExisting:Boolean):ReforgeCharacter {
        require(amount in 0..1_000_000)
        require(character.temporaryHp==null || character.temporaryHp==0 || replaceExisting) { "Choose whether to replace the existing temporary HP; they do not stack." }
        return character.copy(temporaryHp=amount)
    }

    fun previewCast(character:ReforgeCharacter,spellId:String,slotLevel:Int?=null,replaceConcentration:Boolean=false,ritual:Boolean=false):CastPreview {
        val p=CharacterCalculator.calculate(character)
        val spell=character.spells.singleOrNull { it.id==spellId } ?: error("Spell not found.")
        require(spell.level==0 || spell.preparation!=SpellPreparation.KNOWN) { "Prepare this spell before casting." }
        require(ConditionRules.activeConditions(character,p.conditionImmunities).none { it in setOf("incapacitated","unconscious","stunned","paralyzed","petrified") }) { "The current condition prevents spellcasting." }
        require(!ritual || spell.ritual) { "This spell is not configured for ritual casting." }
        require(!spell.concentration || character.concentrationSpellId==null || replaceConcentration) { "Confirm replacing the current concentration spell." }
        if(character.turn.tracking && !ritual) checkAction(character,spell.castingTime)
        val consumesSlot=spell.level>0 && spell.slotRequired && !ritual
        val usedLevel=if(consumesSlot) slotLevel ?: spell.level else null
        if(consumesSlot) {
            require(!character.turn.tracking || character.rules.core!=RuleFamily.REVISED_2024 || !character.turn.spellSlotSpent) { "A spell slot has already been spent this turn under the revised core rules." }
            require(usedLevel!! in spell.level..9 && (p.slotMaxima[usedLevel] ?: 0)>0) { "That casting level is not available." }
            require((character.spellSlots[usedLevel] ?: -1)>0) { "Spell-slot availability is empty or unknown." }
        } else require(slotLevel==null) { "This casting does not use a spell slot." }
        spell.resourceId?.let { resourceId ->
            require((character.resources.singleOrNull { it.id==resourceId }?.current ?: -1)>=spell.resourceCost) { "Required charges are unavailable or unknown." }
        }
        return CastPreview(character.id,character.revision,spellId,usedLevel,spell.resourceId,spell.resourceCost,
            if(spell.concentration) character.concentrationSpellId else null,replaceConcentration,ritual,
            buildString {
                append(spell.name);usedLevel?.let { append(" · consumes one level-$it slot") }
                spell.resourceId?.let { append(" · consumes ${spell.resourceCost} charge(s)") }
                if(spell.concentration) append(" · Concentration")
                if(ritual) append(" · ritual: add 10 minutes; no slot")
            })
    }
    fun commitCast(character:ReforgeCharacter,preview:CastPreview):ReforgeCharacter {
        require(character.id==preview.characterId && character.revision==preview.revision) { "Character changed; review a fresh casting preview." }
        val checked=previewCast(character,preview.spellId,preview.slotLevel,preview.replaceConcentration,preview.ritual)
        require(checked==preview) { "Casting preview changed; review again." }
        val spell=character.spells.single { it.id==preview.spellId }
        return character.copy(
            spellSlots=preview.slotLevel?.let { character.spellSlots+(it to (character.spellSlots.getValue(it)!!-1)) } ?: character.spellSlots,
            resources=character.resources.map { pool->if(pool.id==preview.resourceId) pool.copy(current=pool.current!!-preview.resourceCost) else pool },
            concentrationSpellId=if(spell.concentration) spell.id else character.concentrationSpellId,
            turn=(if(preview.ritual) character.turn else spendAction(character,spell.castingTime)).let { if(preview.slotLevel!=null) it.copy(spellSlotSpent=true) else it }
        )
    }
    fun prepareSpell(character:ReforgeCharacter,spellId:String,prepared:Boolean):ReforgeCharacter {
        val spell=character.spells.singleOrNull { it.id==spellId } ?: error("Spell not found.")
        require(spell.preparation!=SpellPreparation.ALWAYS_PREPARED && spell.level>0) { "Always-prepared spells and cantrips do not use preparation slots." }
        val limit=CharacterCalculator.calculate(character).preparationLimit
        if(prepared && spell.preparation!=SpellPreparation.PREPARED && limit!=null) require(character.spells.count { it.level>0 && it.preparation==SpellPreparation.PREPARED }<limit) { "Prepared-spell limit reached." }
        return character.copy(spells=character.spells.map { if(it.id==spellId) it.copy(preparation=if(prepared) SpellPreparation.PREPARED else SpellPreparation.KNOWN) else it })
    }
    fun setCondition(character:ReforgeCharacter,condition:String,enabled:Boolean):ReforgeCharacter {
        require(condition.isNotBlank() && condition.length<=100)
        val key=condition.lowercase()
        if(enabled) require(CharacterCalculator.calculate(character).conditionImmunities.none { it.equals(condition,true) }) { "This character is immune to that condition." }
        return character.copy(conditions=if(enabled) character.conditions+key else character.conditions.filterNot { it.equals(key,true) }.toSet(),
            concentrationSpellId=if(enabled && key in setOf("incapacitated","unconscious","stunned","paralyzed","petrified")) null else character.concentrationSpellId)
    }
    fun activateStance(character:ReforgeCharacter,stance:String,enabled:Boolean):ReforgeCharacter = preserveCapacities(character,
        character.copy(activeStances=if(enabled) character.activeStances+stance else character.activeStances-stance))
    fun advanceTime(character:ReforgeCharacter,seconds:Long,confirmed:Boolean):ReforgeCharacter {
        require(confirmed && seconds>=0 && seconds<=31_536_000) { "Confirm actual elapsed game time." }
        return preserveCapacities(character,character.copy(gameSeconds=Math.addExact(character.gameSeconds,seconds)))
    }
    fun nextTurn(character:ReforgeCharacter):ReforgeCharacter=character.copy(turnNumber=Math.addExact(character.turnNumber,1),turn=character.turn.copy(actionAvailable=true,bonusActionAvailable=true,reactionAvailable=true,attacksUsed=0,spellSlotSpent=false))

    /** Rest resources follow their own trigger. Unknown availability stays unknown unless explicitly configured. */
    fun rest(character:ReforgeCharacter,kind:Recharge,confirmed:Boolean,elapsedSeconds:Long=0):ReforgeCharacter {
        require(confirmed && kind in setOf(Recharge.SHORT_REST,Recharge.LONG_REST,Recharge.DAWN)) { "Confirm a completed Short Rest, Long Rest, or dawn." }
        val minimum=when(kind) { Recharge.SHORT_REST->3600L;Recharge.LONG_REST->28800L;else->0L }
        require(elapsedSeconds>=minimum && elapsedSeconds<=31_536_000) { "Record at least $minimum seconds of actual eligible rest time." }
        val projection=CharacterCalculator.calculate(character)
        val eligible:(Recharge)->Boolean = { recharge->recharge==kind || (kind==Recharge.LONG_REST && recharge==Recharge.SHORT_REST) }
        val recovered=character.resources.map { pool->
            val max=projection.resourceMaxima[pool.id]
            if(!eligible(pool.recharge) || max==null || (pool.current==null && !pool.restoreUnknownOnRest)) pool else {
                val current=if(pool.recoveryAmount==null) max else pool.current?.let { minOf(max,it+pool.recoveryAmount) }
                pool.copy(current=current)
            }
        }
        val next=character.copy(resources=recovered,
            spellSlots=if(kind==Recharge.LONG_REST) character.spellSlots.mapValues { (level,current)->if(current==null) null else projection.slotMaxima[level] ?: current } else character.spellSlots,
            effects=character.effects.map { if(eligible(it.expiresOn)) it.copy(enabled=false) else it },
            itemDefinitions=character.itemDefinitions.map { it.copy(effects=it.effects.map { effect->if(eligible(effect.expiresOn)) effect.copy(enabled=false) else effect }) },
            gameSeconds=Math.addExact(character.gameSeconds,elapsedSeconds),
            concentrationSpellId=if(kind==Recharge.LONG_REST) null else character.concentrationSpellId,
            temporaryHp=if(kind==Recharge.LONG_REST && character.temporaryHp!=null) 0 else character.temporaryHp,
            exhaustionLevel=if(kind==Recharge.LONG_REST) maxOf(0,character.exhaustionLevel-1) else character.exhaustionLevel)
        val preserved=preserveCapacities(character,next)
        val maximum=CharacterCalculator.calculate(preserved).maximumHp.value
        return if(kind==Recharge.LONG_REST && preserved.currentHp!=null && maximum!=null)
            preserved.copy(currentHp=maximum,hpDamage=0) else preserved
    }
    internal fun checkAction(character:ReforgeCharacter,label:String) {
        if(!character.turn.tracking) return
        val lower=label.lowercase()
        when { "bonus" in lower->require(character.turn.bonusActionAvailable) { "Bonus Action already used." };"reaction" in lower->require(character.turn.reactionAvailable) { "Reaction already used." };"action" in lower->require(character.turn.actionAvailable && character.turn.attacksUsed==0) { "Action already used." } }
    }
    internal fun spendAction(character:ReforgeCharacter,label:String):TurnEconomy {
        if(!character.turn.tracking) return character.turn
        checkAction(character,label)
        val lower=label.lowercase()
        return when { "bonus" in lower->character.turn.copy(bonusActionAvailable=false);"reaction" in lower->character.turn.copy(reactionAvailable=false);"action" in lower->character.turn.copy(actionAvailable=false);else->character.turn }
    }
}
