package com.yazsras.astravar.rules

import org.junit.Assert.*
import org.junit.Test

class ElementalSelectorTest {
    private fun seed(p: Profile=Profile())=Campaign(onboarded=true,attuned=true,profile=p,installedId="core",cores=listOf(Core("core","Test core")),slots=mapOf(2 to 2))
    private fun elemental(p: Profile)=historicalPlan(seed(p),"elemental",Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING))

    @Test fun everyIndependentForceAndSelectorCountUsesGenericDiceAndAverages() {
        assertEquals(2,Profile().elementalSelectorDiceCount)
        for(n in 1..10) for(m in 1..10) {
            val profile=Profile(baseDamageDiceCount=n,elementalSelectorDiceCount=m)
            profile.validate()
            val p=elemental(profile)
            assertEquals(listOf(Dice(n,12,5),Dice(m,8)),Engine.components(p).map {it.damage.dice})
            assertEquals(listOf(Dice(2*n,12,5),Dice(2*m,8)),Engine.components(p,true).map {it.damage.dice})
            assertEquals(m*4.5,profile.selector.average,0.0)
            val sides=mutableListOf<Int>()
            val roll=DiceRoller.roll(p,die={sides+=it;if(it==20) 14 else 1})
            assertEquals(listOf(20)+List(n) {12}+List(m) {8},sides)
            assertEquals(List(m) {1},roll.damage.getValue("element0").faces)
        }
    }

    @Test fun deterministicElementalShotKeepsEachFaceModifierAndType() {
        val p=elemental(Profile(baseDamageDiceCount=2,elementalSelectorDiceCount=3))
        val values=ArrayDeque(listOf(14,7,4,6,2,8))
        val roll=DiceRoller.roll(p,RollOptions(armorClass=18),die={values.removeFirst()},atMillis=123)
        assertTrue(values.isEmpty());assertFalse(roll.critical);assertNull(roll.suggestedOutcome)
        assertEquals(listOf(7,4),roll.damage.getValue("direct").faces)
        assertEquals(listOf(6,2,8),roll.damage.getValue("element0").faces)
        assertEquals(5,roll.damage.getValue("direct").flat);assertEquals(0,roll.damage.getValue("element0").flat)
        assertEquals(mapOf(DamageType.FORCE to 16,DamageType.LIGHTNING to 16),Engine.damage(p,DiceRoller.resolution(p,roll)))
    }

    @Test fun selectorCriticalDoublesIndependentDiceAndAppliesFlatOnceForEveryCount() {
        for(m in 1..10) for(n in listOf(2,4,10)) {
            val p=elemental(Profile(baseDamageDiceCount=n,elementalSelectorDiceCount=m))
            val roll=DiceRoller.roll(p,die={if(it==20) 20 else 3})
            assertTrue(roll.critical);assertEquals(Outcome.CRITICAL,roll.suggestedOutcome)
            assertEquals(List(2*n) {3},roll.damage.getValue("direct").faces)
            assertEquals(List(2*m) {3},roll.damage.getValue("element0").faces)
            assertEquals(5,roll.damage.getValue("direct").flat)
            assertEquals(mapOf(DamageType.FORCE to 6*n+5,DamageType.LIGHTNING to 6*m),Engine.damage(p,DiceRoller.resolution(p,roll)))
        }
    }

    @Test fun neutralHasNoSelectorEvenAtTenAndNaturalOneAppliesNoDamage() {
        val s=seed(Profile(baseDamageDiceCount=3,elementalSelectorDiceCount=10))
        val p=historicalPlan(s,"neutral",Ability.ARCANE,elements=listOf(DamageType.LIGHTNING))
        assertTrue(p.elements.isEmpty());assertEquals(listOf(Dice(3,12,5)),Engine.components(p).map {it.damage.dice})
        val requested=mutableListOf<Int>()
        val roll=DiceRoller.roll(p,die={requested+=it;1})
        assertEquals(listOf(20,12,12,12),requested)
        assertEquals(Outcome.MISS,roll.suggestedOutcome)
        assertTrue(DiceRoller.resolution(p,roll).rolls.isEmpty())
    }

    @Test fun dualResonanceGivesEachBasicElementTheFullConfiguredCountAndCritical() {
        val profile=Profile(level=10,elementalSelectorDiceCount=3,confirmed=setOf(Upgrade.DUAL_RESONANCE))
        val p=historicalPlan(seed(profile),"dual",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE,DamageType.LIGHTNING))
        for(crit in listOf(false,true)) {
            val roll=DiceRoller.roll(p,die={if(it==20) {if(crit) 20 else 14} else 2})
            val count=if(crit) 6 else 3
            assertEquals(listOf(DamageType.FORCE,DamageType.FIRE,DamageType.LIGHTNING),Engine.components(p,crit).map {it.damage.type})
            assertEquals(List(count) {2},roll.damage.getValue("element0").faces)
            assertEquals(List(count) {2},roll.damage.getValue("element1").faces)
            assertEquals(mapOf(DamageType.FORCE to (if(crit) 13 else 9),DamageType.FIRE to count*2,DamageType.LIGHTNING to count*2),Engine.damage(p,DiceRoller.resolution(p,roll)))
        }
    }

    @Test fun changingSelectorDoesNotUnlockElementsOrDualResonance() {
        for(m in listOf(1,10)) {
            val p=Profile(elementalSelectorDiceCount=m)
            assertEquals(Engine.basic,Engine.elements(p));assertEquals(1,p.elementCapacity)
            assertEquals(Engine.basic,Engine.elements(p.copy(level=8)))
            assertEquals(9,Engine.elements(p.copy(level=8,confirmed=setOf(Upgrade.ADVANCED_ELEMENTS))).size)
            assertEquals(1,p.copy(level=9,confirmed=setOf(Upgrade.DUAL_RESONANCE)).elementCapacity)
            assertEquals(2,p.copy(level=10,confirmed=setOf(Upgrade.DUAL_RESONANCE)).elementCapacity)
        }
    }

    @Test fun spellShotsReplaceSelectorsAndNeutralEmergenciesKeepResourceCosts() {
        val spell=Template("spell","Approved test spell",2,2,listOf(TypedDice(Dice(3,6),DamageType.COLD)),DamageType.COLD,true)
        for(ability in listOf(Ability.SPELL,Ability.DISCHARGE,Ability.SHATTER)) for(critical in listOf(false,true)) {
            val profiles=listOf(1,10).map {Profile(level=10,elementalSelectorDiceCount=it,confirmed=setOf(Upgrade.DUAL_RESONANCE))}
            val shots=profiles.map {profile ->
                val charged=Engine.charge(seed(profile),"core",spell,"payload",SourceKind.SLOT,true)
                val p=historicalPlan(charged,"shot",ability,listOf("payload"),elements=if(ability==Ability.SPELL) listOf(DamageType.FIRE,DamageType.LIGHTNING) else emptyList(),direct=ability!=Ability.SPELL,point="Gate")
                assertTrue(p.elements.isEmpty());assertFalse(Engine.components(p,critical).any {it.label=="Selector"})
                val after=historicalFire(charged,p,die={if(it==20) {if(critical) 20 else 14} else 2})
                assertEquals(1,after.slots[2]);assertTrue(after.core("core").payloads.isEmpty())
                if(ability==Ability.DISCHARGE) assertEquals(600L,after.core("core").exhaustedUntil)
                if(ability==Ability.SHATTER) {assertTrue(after.core("core").destroyed);assertTrue(after.postShatter)}
                p to after.pending.single().rolled!!
            }
            assertEquals(Engine.components(shots[0].first,critical),Engine.components(shots[1].first,critical))
            assertEquals(shots[0].second.damage,shots[1].second.damage)
        }
    }

    @Test fun selectorNeverAffectsForceImpactOrEmergencyProgressionAtAnyTier() {
        for(level in 5..20) for(n in 1..10) for(ability in listOf(Ability.DISCHARGE,Ability.SHATTER)) {
            val plans=listOf(1,10).map {m -> historicalPlan(seed(Profile(level=level,baseDamageDiceCount=n,elementalSelectorDiceCount=m)),"impact",ability,direct=true,point="Gate")}
            for(crit in listOf(false,true)) assertEquals(Engine.components(plans[0],crit),Engine.components(plans[1],crit))
            assertEquals(Dice(n*2,12,5),Engine.components(plans[1],true).first().damage.dice)
            assertEquals(plans[1].blastDiceCount,Engine.components(plans[1],true).last().damage.dice.count)
        }
    }
}
