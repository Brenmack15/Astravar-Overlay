package com.yazsras.astravar.rules

import org.junit.Test
import kotlin.test.*

class EmergencyScalingTest {
    @Test fun everyLevelTierAndWeaponCountCombinesBothProgressions() {
        val tiers=listOf(5..8 to 8,9..12 to 10,13..16 to 12,17..20 to 14)
        for((levels,base) in tiers) for(level in levels) for(count in 1..10) {
            val profile=Profile(level=level,baseDamageDiceCount=count)
            profile.validate()
            assertEquals(base,profile.levelBasedDischargeDice)
            assertEquals(base+2*(count-1),profile.dischargeDice)
            assertEquals(base*3+6*(count-1),profile.shatterDice)
            val state=Campaign(onboarded=true,attuned=true,profile=profile,installedId="core",cores=listOf(Core("core","Core")))
            for((ability,expected) in listOf(Ability.DISCHARGE to profile.dischargeDice,Ability.SHATTER to profile.shatterDice)) {
                val plan=Engine.plan(state,"blast",ability,direct=true,point="Door")
                assertEquals(Dice(count,10,5),Engine.components(plan).first().damage.dice)
                assertEquals(Dice(2*count,10,5),Engine.components(plan,true).first().damage.dice)
                assertEquals(Dice(expected,10),Engine.components(plan).last().damage.dice)
                assertEquals(Dice(expected,10),Engine.components(plan,true).last().damage.dice)
                assertEquals(expected*5.5,Engine.components(plan).last().damage.dice.average)
                assertEquals(count*5.5+5,profile.base.average)
                assertEquals("Action",plan.actionCost)
                assertEquals(if(ability==Ability.DISCHARGE) 60 else 120,plan.range)
                assertEquals(if(ability==Ability.DISCHARGE) 15 else 60,plan.radius)
            }
        }
    }

    @Test fun canonicalProfileIsTwoTenAndThirty() {
        assertEquals(Dice(2,10,5),Profile().base)
        assertEquals(10,Profile().dischargeDice)
        assertEquals(30,Profile().shatterDice)
        assertEquals(26,Profile(baseDamageDiceCount=10).dischargeDice)
        assertEquals(78,Profile(baseDamageDiceCount=10).shatterDice)
        assertEquals(32,Profile(level=20,baseDamageDiceCount=10).dischargeDice)
        assertEquals(96,Profile(level=20,baseDamageDiceCount=10).shatterDice)
    }

    @Test fun explicitSpellProgressionOverrideRetainsItsEffectBeforeWeaponContribution() {
        val p=Profile(level=5,baseDamageDiceCount=7,emergencySpellLevelOverride=5)
        assertEquals(14,p.levelBasedDischargeDice)
        assertEquals(26,p.dischargeDice)
        assertEquals(78,p.shatterDice)
    }

    @Test fun plannedBlastDoesNotRecalculateAfterProfileChanges() {
        val p=ShotPlan("saved",0,Ability.SHATTER,"core",Profile(),emptyList(),targetPoint="Gate")
        val changed=p.copy(profile=p.profile.copy(level=20,baseDamageDiceCount=10))
        assertEquals(30,changed.blastDiceCount)
        assertEquals(Dice(30,10),Engine.components(changed).single().damage.dice)
    }
}
