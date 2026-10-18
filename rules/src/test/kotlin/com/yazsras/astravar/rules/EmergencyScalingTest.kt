package com.yazsras.astravar.rules

import org.junit.Test
import kotlin.test.*

class EmergencyScalingTest {
    @Test fun everyLevelAndWeaponCountUsesOnlyPrimaryDiceMultipliers() {
        for(level in 5..20) for(count in 1..10) {
            val profile=Profile(level=level,baseDamageDiceCount=count)
            profile.validate()
            assertEquals(3*count,profile.dischargeDice)
            assertEquals(21*count,profile.shatterDice)
            val state=Campaign(onboarded=true,attuned=true,profile=profile,installedId="core",cores=listOf(Core("core","Core")))
            for((ability,expected) in listOf(Ability.DISCHARGE to 3*count,Ability.SHATTER to 21*count)) {
                val plan=historicalPlan(state,"blast",ability,direct=true,point="Door")
                assertEquals(Dice(count,12,5),Engine.components(plan).first().damage.dice)
                assertEquals(Dice(2*count,12,5),Engine.components(plan,true).first().damage.dice)
                assertEquals(Dice(expected,12),Engine.components(plan).last().damage.dice)
                assertEquals(Dice(expected,12),Engine.components(plan,true).last().damage.dice)
                Engine.components(plan).forEach {it.damage.dice.validate()}
                assertEquals(expected*6.5,Engine.components(plan).last().damage.dice.average)
                assertEquals(count*6.5+5,profile.base.average)
                assertEquals("Action",plan.actionCost)
                assertEquals(if(ability==Ability.DISCHARGE) 60 else 120,plan.range)
                assertEquals(if(ability==Ability.DISCHARGE) 15 else 60,plan.radius)
            }
        }
    }

    @Test fun explicitCanonicalExamplesAndMaximum() {
        assertEquals(Dice(2,12,5),Profile().base)
        for((n,d,s) in listOf(Triple(1,3,21),Triple(2,6,42),Triple(3,9,63),Triple(4,12,84),Triple(5,15,105),Triple(10,30,210))) {
            val p=Profile(baseDamageDiceCount=n)
            assertEquals(Dice(d,12),p.discharge);assertEquals(Dice(s,12),p.shatter)
        }
    }

    @Test fun obsoleteOverrideCannotChangeCurrentEmergencyDamage() {
        for(level in 5..20) for(override in 1..9) {
            val p=Profile(level=level,baseDamageDiceCount=7,emergencySpellLevelOverride=override)
            assertEquals(Dice(21,12),p.discharge);assertEquals(Dice(147,12),p.shatter)
        }
    }

    @Test fun plannedBlastCountDoesNotRecalculateAfterProfileCountChanges() {
        val p=ShotPlan("saved",0,Ability.SHATTER,"core",Profile(),emptyList(),targetPoint="Gate")
        val changed=p.copy(profile=p.profile.copy(level=20,baseDamageDiceCount=10))
        assertEquals(42,changed.blastDiceCount)
        assertEquals(Dice(42,12),Engine.components(changed).single().damage.dice)
    }
}
