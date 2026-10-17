package com.yazsras.astravar.rules

import org.junit.Assert.*
import org.junit.Test

class WeaponWorkflowTest {
    private fun seed()=Campaign(onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Resonant crystal")),encounter=true,slots=mapOf(2 to 2))
    @Test fun oldAdjudicatedHistorySurvivesButNextRollIgnoresLegacyAc() {
        val s=seed();val p=Engine.plan(s,"old",Ability.ARCANE)
        val r=DiceRoller.roll(p,die={if(it==20) 14 else 5}).copy(options=RollOptions(armorClass=18),suggestedOutcome=Outcome.HIT)
        DiceRoller.validate(p,r)
        val old=PendingShot(p,DiceRoller.resolution(p,r),r)
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=s.copy(pending=listOf(old))))).campaign
        assertEquals(old,restored.pending.single())
        val next=DiceRoller.fire(restored,Engine.plan(restored,"new",Ability.ARCANE),r.options,die={if(it==20) 2 else 5})
        assertEquals(Outcome.UNCONFIRMED,next.pending.last().result!!.outcome)
        assertNull(next.pending.last().rolled!!.options.armorClass)
        assertEquals(old,next.pending.first())
    }
    @Test fun confirmingNextWeaponTurnResetsOnlyActionBudgetNeverRecoveryOrResources() {
        val s=seed().copy(gameSeconds=90,turn=Turn(17,attacks=2,bonus=true,spellShots=1),cores=listOf(Core("core","Resonant crystal",exhaustedUntil=600)))
        val next=Engine.resetWeaponActions(s)
        assertEquals(s.copy(turn=Turn(17)),next)
        assertFalse(next.installed!!.usable(next.gameSeconds))
    }
    @Test fun ordinaryDamageIsPotentialWhileExistingWeaponActionLimitStillApplies() {
        var s=seed()
        repeat(2) {i->s=DiceRoller.fire(s,Engine.plan(s,"shot$i",Ability.ARCANE),die={if(it==20) 14 else 7})}
        assertTrue(s.pending.all {it.result!!.outcome==Outcome.UNCONFIRMED})
        assertEquals(2,s.turn.attacks)
        assertThrows(IllegalArgumentException::class.java) {Engine.plan(s,"third",Ability.ARCANE)}
        val next=Engine.resetWeaponActions(s)
        assertEquals(s.pending,next.pending);Engine.plan(next,"third",Ability.ARCANE)
    }
    @Test fun spellIsSpentOnFiringForUnknownHitCriticalAndNaturalOne() {
        val t=Template("ice","Ice spell",2,2,listOf(TypedDice(Dice(3,6),DamageType.COLD)),DamageType.COLD,true)
        for(natural in listOf(1,14,20)) {
            val s=seed().copy(encounter=false)
            val charged=Engine.charge(s,"core",t,"payload",SourceKind.SLOT,true)
            val after=DiceRoller.fire(charged,Engine.plan(charged,"fire",Ability.SPELL,listOf("payload")),die={if(it==20) natural else 3})
            assertEquals(1,after.slots[2]);assertTrue(after.core("core").payloads.isEmpty())
            val roll=after.pending.single().rolled!!
            assertEquals(if(natural==20) 4 else 2,roll.damage.getValue("direct").faces.size)
            assertEquals(5,roll.damage.getValue("direct").flat)
            assertFalse(roll.damage.keys.any {it.startsWith("element")})
        }
    }
    @Test fun directImpactStillRequiresOnlyDmOutcomeAndSavesNeverEnemyDefenses() {
        val s=seed();val p=Engine.plan(s,"blast",Ability.DISCHARGE,direct=true,point="Door")
        val r=DiceRoller.roll(p,RollOptions(armorClass=1),die={if(it==20) 14 else 5})
        assertNull(r.suggestedOutcome)
        assertThrows(IllegalStateException::class.java) {DiceRoller.resolution(p,r)}
        val targets=listOf(TargetSave("Impact",true,directTarget=true),TargetSave("Other",false))
        val result=DiceRoller.resolution(p,r,Outcome.HIT,targets)
        assertEquals(30,Engine.damage(p,result,targets[0])[DamageType.FORCE])
        assertEquals(30,Engine.damage(p,result,targets[1])[DamageType.FORCE])
    }
}
