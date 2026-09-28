package com.yazsras.astravar.rules

import org.junit.Test
import kotlin.test.*

class EngineTest {
    private fun state(level: Int=5)=Campaign(onboarded=true,attuned=true,profile=Profile(level=level,confirmed=Upgrade.entries.toSet()),installedId="core",cores=listOf(Core("core","Crystal"),Core("spare","Spare")),slots=mapOf(1 to 4,2 to 2))
    private val cold=Template("cold","Approved test energy",2,2,listOf(TypedDice(Dice(3,6),DamageType.COLD)),DamageType.COLD,true,"TEST ONLY instantaneous damage")
    private fun charged(s: Campaign=state(),id: String="payload")=Engine.charge(s,"core",cold,id,SourceKind.SLOT,true)
    @Test fun starterCalibrationAndNeutral() { val s=state(); assertEquals(8,s.profile.attack); assertEquals("1d10+5",s.profile.base.toString()); assertEquals(1,Engine.components(Engine.plan(s,"shot",Ability.ARCANE)).size) }
    @Test fun coldAndDualAreTyped() {
        val s=state(); val c=Engine.components(Engine.plan(s,"shot",Ability.ELEMENTAL,elements=listOf(DamageType.COLD)))
        assertEquals(Dice(1,8),c[1].damage.dice); assertEquals(DamageType.COLD,c[1].damage.type)
        val high=state(10); assertEquals(3,Engine.components(Engine.plan(high,"dual",Ability.ELEMENTAL,elements=listOf(DamageType.COLD,DamageType.FIRE))).size)
        assertFails { Engine.plan(s,"illegal",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE,DamageType.COLD)) }
    }
    @Test fun forceIsNotAFreeSelector() { assertFails {Engine.plan(state(),"bad",Ability.ELEMENTAL,elements=listOf(DamageType.FORCE))} }
    @Test fun spellShotReplacesSelectorsAndCriticalDoublesDiceOnly() {
        val s=charged(); val p=Engine.plan(s,"shot",Ability.SPELL,listOf("payload"),elements=listOf(DamageType.FIRE))
        assertEquals(listOf("1d10+5","3d6","2d8"),Engine.components(p).map {it.damage.dice.toString()})
        assertEquals(listOf("2d10+5","6d6","4d8"),Engine.components(p,true).map {it.damage.dice.toString()})
        assertEquals(3,Engine.components(p).size)
    }
    @Test fun blastScalingUsesUnlockedSpellLevel() {
        for((level,n) in listOf(5 to 8,8 to 8,9 to 10,13 to 12,17 to 14,20 to 14)) {
            val s=state(level).copy(slots=emptyMap())
            for((ability,mult) in listOf(Ability.DISCHARGE to 1,Ability.SHATTER to 3)) {
                val p=Engine.plan(s,"blast",ability,point="Declared floor point")
                assertEquals(Dice(n*mult,10),Engine.components(p,true).single().damage.dice); assertEquals("Action",p.actionCost)
            }
        }
    }
    @Test fun sharedBlast43Payload19Gives62Or21() {
        val p=Engine.plan(charged(),"blast",Ability.DISCHARGE,listOf("payload"),point="Declared point")
        val r=Resolution(Outcome.POINT,rolls=mapOf("blast" to 43,"payload0_0" to 9,"overcharge0" to 10),targets=listOf(TargetSave("A",false),TargetSave("B",true)))
        Engine.validateResult(p,r)
        assertEquals(62,Engine.damage(p,r,r.targets[0]).values.sum()); assertEquals(21,Engine.damage(p,r,r.targets[1]).values.sum())
        assertEquals(8,Engine.components(p).first {it.id=="blast"}.damage.dice.count)
    }
    @Test fun directImpactMissStillDetonates() {
        val p=Engine.plan(state(),"blast",Ability.SHATTER,direct=true,point="Declared point",distance=120)
        assertEquals(listOf(Dice(2,10,5),Dice(24,10)),Engine.components(p,true).map {it.damage.dice})
        val r=Resolution(Outcome.MISS,rolls=mapOf("blast" to 100),targets=listOf(TargetSave("Target",false,directTarget=true)))
        Engine.validateResult(p,r);assertEquals(100,Engine.damage(p,r,r.targets.single()).values.sum())
        assertFails {Engine.plan(state(),"bad",Ability.SHATTER,distance=121,point="Point")}
    }
    @Test fun blastPayloadNeverCrits() { val p=Engine.plan(charged(),"blast",Ability.DISCHARGE,listOf("payload"),direct=true,point="Point"); assertEquals(listOf("2d10+5","8d10","3d6","2d8"),Engine.components(p,true).map {it.damage.dice.toString()}) }
    @Test fun storedMagicSpendsOnMissWithoutAnotherSlot() {
        val s=charged(); assertEquals(1,s.slots[2]); val p=Engine.plan(s,"shot",Ability.SPELL,listOf("payload")); val n=Engine.commit(s,p)
        assertEquals(1,n.slots[2]); assertTrue(n.core("core").payloads.isEmpty()); assertFails {Engine.commit(n,p)}
        val resolved=Engine.resolve(n,"shot",Resolution(Outcome.MISS)); assertNotNull(resolved.pending.single().result)
    }
    @Test fun slotSnapshotDoesNotScaleRetroactively() { val s=charged(); val high=s.copy(profile=s.profile.copy(level=17)); assertEquals(2,high.core("core").payloads.single().template.castingLevel) }
    @Test fun previewAndOrdinaryFirePreserveSpells() { val s=charged(); val p=Engine.plan(s,"ordinary",Ability.ARCANE); assertEquals(1,s.core("core").payloads.size); assertEquals(1,Engine.commit(s,p).core("core").payloads.size) }
    @Test fun purgeAndOverwriteCostBonusActionWithoutRefund() {
        val s=charged().copy(encounter=true); val n=Engine.purge(s,"core","payload");assertTrue(n.turn.bonus);assertEquals(s.slots,n.slots)
        val replaced=Engine.charge(charged(),"core",cold.copy(name="New template"),"new",SourceKind.SLOT,true,overwriteId="payload")
        assertEquals(0,replaced.slots[2]);assertEquals("new",replaced.core("core").payloads.single().id)
    }
    @Test fun scrollConsumesOnlyScrollAndMustExist() {
        val s=state().copy(templates=listOf(cold),scrolls=listOf(Scroll("scroll","cold",1)))
        val n=Engine.charge(s,"core",cold,"payload",SourceKind.SCROLL,true,scrollId="scroll")
        assertEquals(s.slots,n.slots);assertEquals(0,n.scrolls.single().quantity)
        assertFails {Engine.charge(s,"core",cold,"bad",SourceKind.SCROLL,true)}
    }
    @Test fun capacityDoesNotArriveAtTen() { assertEquals(1,state(10).profile.capacity); assertEquals(2,state(12).profile.capacity);assertFails {charged(charged(),"second")} }
    @Test fun onlySelectedPayloadSpent() {
        val s=charged(charged(state(12)),"second"); val p=Engine.plan(s,"shot",Ability.SPELL,listOf("payload")); val n=Engine.commit(s,p)
        assertEquals(listOf("second"),n.core("core").payloads.map {it.id})
    }
    @Test fun shatterDestroysUnselectedPayloadsAndLocksInterface() {
        val s=charged(charged(state(12)),"second"); val p=Engine.plan(s,"blast",Ability.SHATTER,listOf("payload"),point="Point")
        val n=Engine.commit(s,p); assertTrue(n.core("core").destroyed);assertTrue(n.core("core").payloads.isEmpty());assertTrue(n.postShatter);assertNull(n.installedId)
        val installing=Engine.install(n,"spare");assertTrue(installing.postShatter);assertNull(installing.installedId);assertNotNull(installing.installation)
        assertFails {Engine.plan(installing,"no",Ability.ARCANE)}
        assertTrue(Engine.advance(installing,599).postShatter);assertEquals("spare",Engine.advance(installing,600).installedId)
    }
    @Test fun exhaustedRemovedCoreRecoversOnlyInGame() {
        val s=charged();val p=Engine.plan(s,"blast",Ability.DISCHARGE,point="Point");val n=Engine.install(Engine.commit(s,p),"spare")
        assertFalse(n.core("core").usable(n.gameSeconds));assertEquals(1,n.core("core").payloads.size)
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=n))).campaign;assertFalse(restored.core("core").usable(restored.gameSeconds));assertTrue(Engine.advance(restored,600).core("core").usable(600))
    }
    @Test fun separateSwapAndReplacementThresholds() {
        assertEquals("Action",state(5).profile.swapCost);assertEquals("Bonus Action",state(10).profile.swapCost)
        assertEquals("10 in-game minutes",state(14).profile.replacementCost);assertEquals("Action",state(15).profile.replacementCost);assertEquals("Bonus Action",state(20).profile.replacementCost)
    }
    @Test fun unearnedUpgradeDoesNotActivate() { val p=Profile(level=20);assertEquals(1,p.capacity);assertFalse(p.has(Upgrade.DUAL_RESONANCE));assertEquals(1,p.bonus) }
    @Test fun illegalCoreAndAttunementStatesCannotFire() {
        for(s in listOf(state().copy(attuned=false),state().copy(installedId=null),state().replace(Core("core","dead",true)),state().replace(Core("core","tired",exhaustedUntil=600)))) assertFails {Engine.plan(s,"bad",Ability.ARCANE)}
    }
    @Test fun twoAttacksAreNotTwoFullActions() {
        val s=state().copy(encounter=true);val one=Engine.commit(s,Engine.plan(s,"one",Ability.ARCANE));val two=Engine.commit(one,Engine.plan(one,"two",Ability.ARCANE))
        assertFails {Engine.plan(two,"three",Ability.ARCANE)};assertFails {Engine.plan(one,"blast",Ability.DISCHARGE,point="Point")}
    }
    @Test fun provisionalSpellLimitAndNextTurn() {
        val s=charged(charged(state(12)),"second").copy(encounter=true);val n=Engine.commit(s,Engine.plan(s,"first",Ability.SPELL,listOf("payload")))
        assertFails {Engine.plan(n,"secondshot",Ability.SPELL,listOf("second"))};assertNotNull(Engine.plan(Engine.advance(n,6,true),"next",Ability.SPELL,listOf("second")))
    }
    @Test fun missingCountersNeedExplicitConfirmation() {
        val s=state().copy(slots=emptyMap());assertFails {Engine.plan(s,"bad",Ability.SPELL,immediate=cold)}
        val p=Engine.plan(s,"ok",Ability.SPELL,immediate=cold,manualSlotConfirmed=true);assertTrue(Engine.commit(s,p).slots.isEmpty())
    }
    @Test fun stalePreviewCannotSpendResources() { val s=charged();val p=Engine.plan(s,"shot",Ability.SPELL,listOf("payload"));assertFails {Engine.commit(s.copy(revision=1),p)} }
    @Test fun resultBoundsAndTypedDefenses() {
        val p=Engine.plan(state(),"shot",Ability.DISCHARGE,point="Point")
        assertFails {Engine.validateResult(p,Resolution(Outcome.POINT,rolls=mapOf("blast" to 1),targets=listOf(TargetSave("X",false))))}
        val r=Resolution(Outcome.POINT,rolls=mapOf("blast" to 43),targets=listOf(TargetSave("X",true,mapOf(DamageType.FORCE to Defense.RESISTANT))))
        assertEquals(10,Engine.damage(p,r,r.targets.single())[DamageType.FORCE])
    }
    @Test fun backupRejectsDuplicatesAndBrokenReferences() {
        val s=state();assertFails {BackupCodec.decode(BackupCodec.encode(Backup(campaign=s.copy(cores=s.cores+s.cores.first()))))}
        assertFails {BackupCodec.decode(BackupCodec.encode(Backup(campaign=s.copy(installedId="missing"))))}
        assertFails {BackupCodec.decode("{\"format\":\"other\"}")}
    }
    @Test fun noResourceCreationInInitialState() {val s=Campaign();assertFalse(s.attuned);assertTrue(s.cores.isEmpty());assertTrue(s.slots.isEmpty());assertTrue(s.templates.isEmpty())}
    @Test fun impactAndBlastRoundDefensesSeparately() {
        val p=Engine.plan(state(),"impact",Ability.DISCHARGE,direct=true,point="Point")
        val target=TargetSave("Target",true,mapOf(DamageType.FORCE to Defense.RESISTANT),true)
        val r=Resolution(Outcome.HIT,rolls=mapOf("direct" to 7,"blast" to 43),targets=listOf(target))
        assertEquals(13,Engine.damage(p,r,target)[DamageType.FORCE])
    }
    @Test fun explicitFutureSelectorOverrideIsNotDefaultCanon() {
        val s=state(20).copy(profile=state(20).profile.copy(elementCapacityOverride=4))
        assertEquals(5,Engine.components(Engine.plan(s,"future",Ability.ELEMENTAL,elements=Engine.basic.take(4))).size)
        assertEquals(2,state(20).profile.elementCapacity)
    }
    @Test fun scrollCannotSilentlyUpgradeAfterTemplateEdit() {
        val s=state().copy(templates=listOf(cold),scrolls=listOf(Scroll("scroll",cold.id,1,cold)))
        assertFails {Engine.charge(s,"core",cold.copy(castingLevel=9),"bad",SourceKind.SCROLL,true,scrollId="scroll")}
    }
    @Test fun spareCoresHaveNoCampaignQuantityCap() {
        BackupCodec.validate(state().copy(cores=state().cores+(1..10001).map {Core("extra$it","Explicit fixture core $it")}))
    }
    @Test fun directHitRequiresItsTargetsSeparateSave() {
        val p=Engine.plan(state(),"direct",Ability.DISCHARGE,direct=true,point="Point")
        val r=Resolution(Outcome.HIT,rolls=mapOf("direct" to 11,"blast" to 43),targets=listOf(TargetSave("A",true)))
        assertFails {Engine.validateResult(p,r)}
        Engine.validateResult(p,r.copy(targets=listOf(TargetSave("A",true,directTarget=true))))
    }
}
