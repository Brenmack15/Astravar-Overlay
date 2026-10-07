package com.yazsras.astravar.rules

import org.junit.Test
import kotlin.test.*

class PrimaryForceTest {
    private fun seed(n: Int=4,m: Int=2)=Campaign(onboarded=true,attuned=true,installedId="core",
        profile=Profile(baseDamageDiceCount=n,elementalSelectorDiceCount=m,level=10,confirmed=setOf(Upgrade.DUAL_RESONANCE)),
        cores=listOf(Core("core","Crystal")),slots=mapOf(2 to 2))
    private val lightning=listOf(DamageType.LIGHTNING)

    @Test fun directRollRequestsExactlyIndependentD12sIncludingTwelve() {
        for(n in listOf(1,2,4,5,10)) {
            val p=Engine.plan(seed(n),"direct",Ability.ARCANE)
            val requests=mutableListOf<Int>()
            val r=DiceRoller.roll(p,die={requests+=it;if(it==20) 14 else 12})
            assertEquals(listOf(20)+List(n) {12},requests)
            assertEquals(List(n) {12},r.damage.getValue("direct").faces)
            assertEquals(12*n+5,r.damage.getValue("direct").total)
        }
        val values=ArrayDeque(listOf(14,10,4,7,12))
        val p=Engine.plan(seed(),"example",Ability.ARCANE)
        assertEquals(38,DiceRoller.roll(p,die={values.removeFirst()}).damage.getValue("direct").total)
        assertTrue(values.isEmpty())
    }

    @Test fun selectorAppearsOnceAtEachForceMultiplierAndNeverGainsWeaponFlat() {
        for((a,n) in listOf(Ability.ELEMENTAL to 4,Ability.DISCHARGE to 12,Ability.SHATTER to 84)) {
            val p=Engine.plan(seed(),"shot",a,elements=lightning,point="Gate")
            val c=Engine.components(p)
            assertEquals(listOf(Dice(n,12,if(p.blast) 0 else 5),Dice(2,8)),c.map {it.damage.dice})
            assertEquals(listOf(DamageType.FORCE,DamageType.LIGHTNING),c.map {it.damage.type})
            assertEquals(Engine.previewComponents(p.profile,a,lightning),c)
        }
    }

    @Test fun neutralAndDualResonanceBlastsPreserveFullIndependentSelectors() {
        for(a in listOf(Ability.DISCHARGE,Ability.SHATTER)) {
            val s=seed(m=10)
            val neutral=Engine.plan(s,"neutral",a,point="Gate")
            assertEquals(listOf("blast"),Engine.components(neutral).map {it.id})
            val p=Engine.plan(seed(),"dual",a,elements=listOf(DamageType.FIRE,DamageType.LIGHTNING),point="Gate")
            for(critical in listOf(false,true)) {
                assertEquals(listOf(Dice(2,8),Dice(2,8)),Engine.components(p,critical).drop(1).map {it.damage.dice})
                assertEquals(listOf(DamageType.FIRE,DamageType.LIGHTNING),Engine.components(p,critical).drop(1).map {it.damage.type})
            }
        }
    }

    @Test fun eachTypedSaveHalvesActualOddTotalsWithoutAnyNewRandomRequests() {
        val p=Engine.plan(seed(),"save",Ability.DISCHARGE,elements=lightning,point="Gate")
        val faces=List(11) {6}+5+listOf(4,5) // 71 Force and 9 Lightning
        val queue=ArrayDeque(faces);val requests=mutableListOf<Int>()
        val roll=DiceRoller.roll(p,die={requests+=it;queue.removeFirst()})
        val targets=listOf(TargetSave("Failed",false),TargetSave("Saved",true))
        val r=DiceRoller.resolution(p,roll,targets=targets)
        assertEquals(mapOf(DamageType.FORCE to 71,DamageType.LIGHTNING to 9),Engine.damage(p,r,targets[0]))
        assertEquals(mapOf(DamageType.FORCE to 35,DamageType.LIGHTNING to 4),Engine.damage(p,r,targets[1]))
        assertEquals(List(12) {12}+List(2) {8},requests);assertTrue(queue.isEmpty())
    }

    @Test fun directImpactCriticalDoublesOnlyWeaponAndMissStillAppliesSelectors() {
        for(a in listOf(Ability.DISCHARGE,Ability.SHATTER)) for(natural in listOf(1,20)) {
            val p=Engine.plan(seed(),"impact",a,elements=lightning,direct=true,point="Gate")
            val r=DiceRoller.roll(p,die={if(it==20) natural else 3})
            val count=if(a==Ability.DISCHARGE) 12 else 84
            assertEquals(if(natural==20) 8 else 4,r.damage.getValue("direct").faces.size)
            assertEquals(5,r.damage.getValue("direct").flat)
            assertEquals(count,r.damage.getValue("blast").faces.size)
            assertEquals(listOf(3,3),r.damage.getValue("element0").faces)
            val target=TargetSave("Target",true,directTarget=true)
            val result=DiceRoller.resolution(p,r,targets=listOf(target))
            assertEquals(mapOf(DamageType.FORCE to count*3/2+(if(natural==20) 29 else 0),DamageType.LIGHTNING to 3),Engine.damage(p,result,target))
        }
    }

    @Test fun payloadAndOverchargeKeepOwnDiceSizesAndSaveRulesWithSelector() {
        val spell=Template("spell","Approved d10 spell",2,2,listOf(TypedDice(Dice(2,10,1),DamageType.FIRE)),DamageType.COLD,true)
        for(a in listOf(Ability.SPELL,Ability.DISCHARGE,Ability.SHATTER)) {
            val p=Engine.plan(seed(m=10),"magic",a,immediate=spell,elements=lightning,point="Gate")
            for(crit in listOf(false,true)) {
                val c=Engine.components(p,crit)
                assertEquals(Dice(if(crit && !p.blast) 4 else 2,10,1),c.first {it.id=="payload0_0"}.damage.dice)
                assertEquals(Dice(if(crit && !p.blast) 4 else 2,8),c.first {it.id=="overcharge0"}.damage.dice)
                assertEquals(if(p.blast) listOf(Dice(10,8)) else emptyList(),c.filter {it.label=="Selector"}.map {it.damage.dice})
            }
            if(p.blast) {
                val r=DiceRoller.roll(p,die={2});val target=TargetSave("Saved",true)
                val damage=Engine.damage(p,DiceRoller.resolution(p,r,targets=listOf(target)),target)
                assertEquals(0,damage[DamageType.FIRE]);assertEquals(0,damage[DamageType.COLD]);assertEquals(10,damage[DamageType.LIGHTNING])
            }
        }
    }

    @Test(timeout=5000) fun largeShattersKeepEveryFaceAndRoundTripCompleteHistory() {
        for((n,count) in listOf(4 to 84,5 to 105,10 to 210)) {
            val s=seed(n);val p=Engine.plan(s,"large-$n",Ability.SHATTER,elements=lightning,point="Gate")
            val requests=mutableListOf<Int>();var index=0
            val after=DiceRoller.fire(s,p,die={sides->requests+=sides;(index++%sides)+1})
            val shot=after.pending.single();val roll=shot.rolled!!
            assertEquals(List(count) {12}+List(2) {8},requests)
            assertEquals(List(count) {it%12+1},roll.damage.getValue("blast").faces)
            assertEquals((0 until count).sumOf {it%12+1},roll.damage.getValue("blast").total)
            assertEquals(0,roll.damage.getValue("blast").flat)
            val maximum=DiceRoller.roll(p,die={it});assertEquals(count*12,maximum.damage.getValue("blast").total)
            val event=HistoryEntry("e","Large Shatter",0,s,after)
            val backup=Backup(campaign=after,history=listOf(event),archives=listOf(HistoryArchive("a",listOf(event))))
            val restored=BackupCodec.decode(BackupCodec.encode(backup))
            assertEquals(backup,restored);assertTrue(shotSummary(restored.campaign.pending.single()).contains("${count}d12"))
        }
    }

    @Test fun emergencyElementsKeepUnlockCapacityAndBasicDualValidation() {
        for(a in listOf(Ability.DISCHARGE,Ability.SHATTER)) {
            assertFails {Engine.plan(seed().copy(profile=Profile()),"locked",a,elements=listOf(DamageType.FIRE,DamageType.LIGHTNING),point="Gate")}
            assertFails {Engine.plan(seed(),"force",a,elements=listOf(DamageType.FORCE),point="Gate")}
            assertFails {Engine.plan(seed(),"duplicate",a,elements=listOf(DamageType.FIRE,DamageType.FIRE),point="Gate")}
            val advanced=seed().copy(profile=seed().profile.copy(confirmed=setOf(Upgrade.DUAL_RESONANCE,Upgrade.ADVANCED_ELEMENTS)))
            assertFails {Engine.plan(advanced,"advanced-dual",a,elements=listOf(DamageType.FIRE,DamageType.RADIANT),point="Gate")}
        }
    }
}
