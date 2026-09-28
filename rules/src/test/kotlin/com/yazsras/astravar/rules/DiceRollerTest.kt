package com.yazsras.astravar.rules

import org.junit.Test
import org.junit.Assert.*

class DiceRollerTest {
    private fun seed(profile: Profile=Profile())=Campaign(onboarded=true,attuned=true,profile=profile,installedId="core",cores=listOf(Core("core","Core One")),slots=mapOf(2 to 2))
    private fun dice(vararg values: Int): (Int)->Int {
        val queue=ArrayDeque(values.toList())
        return {sides->check(queue.isNotEmpty()) {"Unexpected extra d$sides"};queue.removeFirst().also {check(it in 1..sides)}}
    }
    private fun plan(ability: Ability=Ability.ARCANE)=Engine.plan(seed(),"shot",ability,elements=if(ability==Ability.ELEMENTAL) listOf(DamageType.FIRE) else emptyList())
    @Test fun standardAttackPreservesEveryFaceModifierTypeAndTotal() {
        val p=plan();val r=DiceRoller.roll(p,die=dice(14,7),atMillis=1234)
        assertEquals(listOf(14),r.attackFaces);assertEquals(14,r.natural);assertEquals(22,r.attackTotal)
        assertEquals(listOf(7),r.damage.getValue("direct").faces);assertEquals(5,r.damage.getValue("direct").flat);assertEquals(12,r.damage.getValue("direct").total)
        assertEquals(DamageType.FORCE,Engine.components(p).single().damage.type)
        assertEquals("7 + 5 = 12",r.damage.getValue("direct").detail());assertEquals(1234L,r.rolledAtMillis)
        assertEquals(Outcome.UNCONFIRMED,DiceRoller.resolution(p,r).outcome)
    }
    @Test fun naturalTwentyDoublesDiceAndAddsFlatOnce() {
        val p=plan();val r=DiceRoller.roll(p,die=dice(20,7,4))
        assertEquals(20,r.natural);assertTrue(r.critical);assertEquals(Outcome.CRITICAL,r.suggestedOutcome)
        assertEquals(listOf(7,4),r.damage.getValue("direct").faces);assertEquals(16,r.damage.getValue("direct").total)
        assertEquals(28,r.attackTotal)
    }
    @Test fun naturalOneMissesDespiteModifierAndArmorClass() {
        val p=plan();val r=DiceRoller.roll(p,RollOptions(armorClass=1),dice(1,10))
        assertEquals(1,r.natural);assertEquals(9,r.attackTotal);assertEquals(Outcome.MISS,r.suggestedOutcome)
        assertEquals(15,r.damage.getValue("direct").total)
        assertTrue(DiceRoller.resolution(p,r).rolls.isEmpty())
        assertEquals(0,Engine.damage(p,DiceRoller.resolution(p,r)).values.sum())
    }
    @Test fun advantageKeepsBothDiceAndUsesHigherOnce() {
        val r=DiceRoller.roll(plan(),RollOptions(advantage=true),dice(17,6,4))
        assertEquals(listOf(17,6),r.attackFaces);assertEquals(17,r.natural);assertEquals(25,r.attackTotal)
    }
    @Test fun disadvantageKeepsBothDiceAndUsesLowerOnce() {
        val r=DiceRoller.roll(plan(),RollOptions(disadvantage=true),dice(17,6,4))
        assertEquals(listOf(17,6),r.attackFaces);assertEquals(6,r.natural);assertEquals(14,r.attackTotal)
    }
    @Test fun unselectedNaturalTwentyIsNotCritical() {
        val r=DiceRoller.roll(plan(),RollOptions(disadvantage=true),dice(20,6,4))
        assertFalse(r.critical);assertEquals(1,r.damage.getValue("direct").faces.size)
    }
    @Test fun unselectedNaturalOneDoesNotOverrideAdvantage() {
        val r=DiceRoller.roll(plan(),RollOptions(advantage=true,armorClass=20),dice(1,17,4))
        assertEquals(Outcome.HIT,r.suggestedOutcome);assertEquals(17,r.natural)
    }
    @Test fun longRangeAddsDisadvantageAndCancelsAdvantage() {
        val p=Engine.plan(seed(),"long",Ability.ARCANE,distance=90)
        val normal=DiceRoller.roll(p,die=dice(18,3,5));assertEquals(RollMode.DISADVANTAGE,normal.mode);assertEquals(11,normal.attackTotal)
        val cancelled=DiceRoller.roll(p,RollOptions(advantage=true),dice(12,5));assertEquals(RollMode.NORMAL,cancelled.mode);assertEquals(listOf(12),cancelled.attackFaces)
    }
    @Test fun suppliedAdvantageAndDisadvantageCancel() {
        val r=DiceRoller.roll(plan(),RollOptions(true,true),dice(10,5));assertEquals(RollMode.NORMAL,r.mode)
    }
    @Test fun acDeterminesHitAndMissWithoutChangingDice() {
        assertEquals(Outcome.HIT,DiceRoller.roll(plan(),RollOptions(armorClass=22),dice(14,7)).suggestedOutcome)
        assertEquals(Outcome.MISS,DiceRoller.roll(plan(),RollOptions(armorClass=23),dice(14,7)).suggestedOutcome)
        assertNull(DiceRoller.roll(plan(),die=dice(14,7)).suggestedOutcome)
    }
    @Test fun elementalDamageStaysTypedAndCalibrationIsAppliedOnce() {
        val p=plan(Ability.ELEMENTAL);val r=DiceRoller.roll(p,die=dice(16,7,4))
        val totals=Engine.damage(p,DiceRoller.resolution(p,r))
        assertEquals(mapOf(DamageType.FORCE to 12,DamageType.FIRE to 4),totals);assertEquals(16,totals.values.sum())
        assertEquals(0,r.damage.getValue("element0").flat)
    }
    @Test fun criticalSpellDoublesEligibleDiceOnlyAndConsumesStoredPayloadOnce() {
        val template=Template("cold","Cold spell",2,2,listOf(TypedDice(Dice(3,6,2),DamageType.COLD)),DamageType.COLD,true)
        val initial=Engine.charge(seed(),"core",template,"payload",SourceKind.SLOT,true)
        val p=Engine.plan(initial,"shot",Ability.SPELL,listOf("payload"))
        assertEquals(1,initial.core("core").payloads.size)
        val after=DiceRoller.fire(initial,p,die={if(it==20) 20 else 1})
        assertEquals(1,after.slots[2]);assertTrue(after.core("core").payloads.isEmpty())
        val r=after.pending.single().rolled!!
        assertEquals(2,r.damage.getValue("direct").faces.size);assertEquals(6,r.damage.getValue("payload0_0").faces.size);assertEquals(4,r.damage.getValue("overcharge0").faces.size)
        assertEquals(5,r.damage.getValue("direct").flat);assertEquals(2,r.damage.getValue("payload0_0").flat)
        try {DiceRoller.fire(after,p);fail("duplicate shot accepted")} catch(_:IllegalArgumentException) {}
    }
    @Test fun noTargetDiceAreRolledForBlastAndSuccessfulSaveHalvesOneTotal() {
        val p=Engine.plan(seed(),"blast",Ability.DISCHARGE,point="Archway")
        val requested=mutableListOf<Int>();val values=ArrayDeque(listOf(10,10,10,9,1,1,1,1))
        val r=DiceRoller.roll(p,die={requested+=it;values.removeFirst()})
        assertEquals(List(8) {10},requested);assertTrue(r.attackFaces.isEmpty());assertNull(r.attackTotal)
        assertEquals(43,r.damage.getValue("blast").total)
        val targets=listOf(TargetSave("Saved",true),TargetSave("Failed",false))
        val result=DiceRoller.resolution(p,r,targets=targets)
        assertEquals(21,Engine.damage(p,result,targets[0])[DamageType.FORCE]);assertEquals(43,Engine.damage(p,result,targets[1])[DamageType.FORCE])
    }
    @Test fun directImpactCriticalDoesNotDoubleTheBlast() {
        val p=Engine.plan(seed(),"blast",Ability.SHATTER,direct=true,point="Gate")
        val r=DiceRoller.roll(p,die={if(it==20) 20 else 1})
        assertEquals(2,r.damage.getValue("direct").faces.size);assertEquals(24,r.damage.getValue("blast").faces.size)
        assertEquals(5,r.damage.getValue("direct").flat);assertEquals(0,r.damage.getValue("blast").flat)
    }
    @Test fun unknownAcAllowsAnotherAttackWithoutManualEntry() {
        var s=seed()
        repeat(2) {i->s=DiceRoller.fire(s,Engine.plan(s,"shot$i",Ability.ARCANE),die={if(it==20) 14 else 7})}
        assertEquals(2,s.pending.size);assertTrue(s.pending.all {it.result?.outcome==Outcome.UNCONFIRMED})
    }
    @Test fun backupRoundTripKeepsDiceCorePayloadTimeAndMode() {
        val s=DiceRoller.fire(seed(),plan(),RollOptions(advantage=true),dice(17,6,4))
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=s))).campaign
        assertEquals(s,restored);val roll=restored.pending.single().rolled!!
        assertEquals("Core One",roll.coreName);assertTrue(roll.rolledAtMillis>0);assertEquals(RollMode.ADVANTAGE,roll.mode)
        assertTrue(shotSummary(restored.pending.single()).contains("17 / 6"))
        assertTrue(shotSummary(restored.pending.single()).contains("AC unknown"))
    }
    @Test fun legacySavedShotsWithoutNewFieldStillRestore() {
        val saved=Engine.commit(seed(),plan())
        val json=BackupCodec.encode(Backup(campaign=saved)).replace(",\"rolled\":null","")
        assertNull(BackupCodec.decode(json).campaign.pending.single().rolled)
    }
    @Test fun invalidRecordedDiceAreRejectedWithoutInventingAReplacement() {
        val p=plan();val r=DiceRoller.roll(p,die=dice(14,7))
        try {DiceRoller.validate(p,r.copy(attackFaces=listOf(21)));fail("invalid face accepted")} catch(_:IllegalArgumentException) {}
        try {DiceRoller.roll(p,die={0});fail("invalid random source accepted")} catch(_:IllegalArgumentException) {}
    }
    @Test fun rollingPreviouslyCommittedShotDoesNotSpendResourcesAgain() {
        val s=seed();val p=Engine.plan(s,"blast",Ability.DISCHARGE,point="Door")
        val committed=Engine.commit(s,p);val after=DiceRoller.rollPending(committed,p.id,die={1})
        assertEquals(600L,after.core("core").exhaustedUntil);assertEquals(committed.turn,after.turn)
        try {DiceRoller.rollPending(after,p.id);fail("reroll accepted")} catch(_:IllegalArgumentException) {}
    }
    @Test fun recentRollsKeepTwentyNewestWithoutDeletingAuditHistory() {
        var state=Campaign(onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Test core")))
        repeat(25) {n->state=DiceRoller.fire(state,Engine.plan(state,"shot-$n",Ability.ARCANE),die={1})}
        assertEquals(25,state.pending.size)
        assertEquals(20,state.recentRolls().size)
        assertEquals("shot-24",state.recentRolls().first().plan.id)
        assertEquals("shot-5",state.recentRolls().last().plan.id)
    }
}
