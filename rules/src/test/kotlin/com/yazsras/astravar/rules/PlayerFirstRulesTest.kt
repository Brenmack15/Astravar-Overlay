package com.yazsras.astravar.rules

import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.*

class PlayerFirstRulesTest {
    // Fictional configuration used to verify mechanics; never a canonical campaign import.
    private val melee=MeleeProfile(9,Dice(3,12,5),-1,2,4,"Synthetic accepted-mechanics fixture",chargedDamageType=DamageType.FORCE,guardianAttackBonus=7)
    private fun state()=Campaign(onboarded=true,attuned=true,installedId="nexus",cores=listOf(Core("nexus","Internal nexus"),Core("old","Archived spare")),
        profile=Profile(melee=melee),slots=mapOf(2 to 2),encounter=true)
    private val spell=Template("spell","Fictional approved payload",2,2,listOf(TypedDice(Dice(2,6),DamageType.COLD)),DamageType.COLD,true)

    @Test fun shatterCannotBePlannedOrCommittedEvenFromAnOldSnapshot() {
        val s=state()
        assertFailsWith<IllegalArgumentException> {Engine.plan(s,"new",Ability.SHATTER,point="Point")}
        val historic=ShotPlan("old",s.revision,Ability.SHATTER,"nexus",s.profile,emptyList(),targetPoint="Point")
        assertFailsWith<IllegalArgumentException> {Engine.commit(s,historic)}
        assertFailsWith<IllegalArgumentException> {DiceRoller.fire(s,historic)}
        val roll=DiceRoller.roll(historic,die={1})
        val saved=s.copy(pending=listOf(PendingShot(historic,rolled=roll)))
        assertEquals(saved,BackupCodec.decode(BackupCodec.encode(Backup(campaign=saved))).campaign)
        assertEquals(42,Engine.components(historic).single().damage.dice.count)
    }
    @Test fun spareCoreInstallAndChargeAreDeniedButArchivedDataSurvives() {
        val s=state()
        assertFails {Engine.install(s,"old")}
        assertFails {Engine.charge(s,"old",spell,"payload",SourceKind.SLOT,false)}
        assertEquals(s.cores,BackupCodec.decode(BackupCodec.encode(Backup(campaign=s))).campaign.cores)
        val old=s.copy(installedId=null,postShatter=true,installation=Installation("old",600))
        val advanced=Engine.advance(old,600)
        assertNull(advanced.installedId); assertEquals(old.installation,advanced.installation)
    }
    @Test fun gauntletIsOneFiveFootAttackWithSeparateForceThunderAndSelector() {
        val s=state();val p=Engine.plan(s,"punch",Ability.GAUNTLET,elements=listOf(DamageType.FIRE),distance=5)
        assertEquals(9,p.attackBonus);assertEquals(5,p.range);assertFalse(p.disadvantage)
        assertEquals(listOf(Dice(3,12,5),Dice(1,8,4),Dice(2,8)),Engine.components(p).map {it.damage.dice})
        assertEquals(listOf(Dice(6,12,5),Dice(2,8,4),Dice(4,8)),Engine.components(p,true).map {it.damage.dice})
        assertEquals(1,Engine.commit(s,p).turn.attacks)
        assertFails {Engine.plan(s,"far",Ability.GAUNTLET,distance=6)}
    }
    @Test fun incompleteOrConflictingMeleeSourcesStayLocked() {
        assertNotNull(Engine.meleeLockReason(Profile()))
        assertFails {Engine.plan(state().copy(profile=Profile()),"punch",Ability.GAUNTLET,distance=5)}
        assertNotNull(Engine.meleeLockReason(Profile(melee=melee.copy(intelligenceModifier=3))))
        assertNotNull(Engine.meleeLockReason(Profile(melee=melee.copy(approved=false))))
        assertFails {Engine.chargeGauntlet(state().copy(profile=Profile(melee=melee.copy(chargedDamageType=null))))}
    }
    @Test fun chargeSpendsBonusActionAndIsConsumedOnNaturalOneWithoutRangedUse() {
        val charged=Engine.chargeGauntlet(state())
        assertTrue(charged.turn.bonus);assertNotNull(charged.gauntletCharge)
        assertFails {Engine.chargeGauntlet(charged)}
        val ranged=Engine.commit(charged,Engine.plan(charged,"ranged",Ability.ARCANE))
        assertEquals(charged.gauntletCharge,ranged.gauntletCharge)
        val after=DiceRoller.fire(ranged,Engine.plan(ranged,"miss",Ability.GAUNTLET,distance=5),die={1})
        assertNull(after.gauntletCharge);assertEquals(Outcome.MISS,after.pending.last().result!!.outcome)
        assertEquals(0,Engine.damage(after.pending.last().plan,after.pending.last().result!!).values.sum())
        assertEquals(5,after.pending.last().rolled!!.damage.getValue("charged").flat)
    }
    @Test fun chargedCriticalNeverDoublesFlatAndNextTurnExpiresUnusedCharge() {
        val charged=Engine.chargeGauntlet(state())
        val p=Engine.plan(charged,"crit",Ability.GAUNTLET,distance=5)
        val r=DiceRoller.roll(p,RollOptions(advantage=true),die={if(it==20) 20 else 2})
        assertEquals(29,r.attackTotal);assertEquals(2,r.attackFaces.size)
        assertEquals(6,r.damage.getValue("direct").faces.size);assertEquals(2,r.damage.getValue("gauntlet").faces.size)
        assertTrue(r.damage.getValue("charged").faces.isEmpty());assertEquals(5,r.damage.getValue("charged").total)
        val next=Engine.resetWeaponActions(charged)
        assertNull(next.gauntletCharge);assertEquals(charged.gameSeconds,next.gameSeconds);assertEquals(charged.cores,next.cores)
        assertNull(Engine.advance(charged,0,true).gauntletCharge)
    }
    @Test fun chargeAndMeleeSnapshotsPersistAcrossRestartAndBackup() {
        val charged=Engine.chargeGauntlet(state())
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=charged))).campaign
        assertEquals(charged,restored)
        val after=DiceRoller.fire(restored,Engine.plan(restored,"punch",Ability.GAUNTLET,distance=5),die={if(it==20)14 else 3})
        val saved=after.pending.single()
        val edited=after.copy(profile=after.profile.copy(melee=melee.copy(force=Dice(4,12,5),version=2)))
        val decoded=BackupCodec.decode(BackupCodec.encode(Backup(campaign=edited))).campaign
        assertEquals(saved,decoded.pending.single());assertEquals(Dice(3,12,5),Engine.components(saved.plan).first().damage.dice)
    }
    @Test fun spellGauntletReplacesSelectorRetainsThunderAndConsumesOnlySelectedMagic() {
        val s=state().copy(profile=state().profile.copy(level=12,confirmed=setOf(Upgrade.DUAL_STORAGE)))
        val loaded=Engine.charge(Engine.charge(s.copy(encounter=false),"nexus",spell,"a",SourceKind.SLOT,false),"nexus",spell,"b",SourceKind.SLOT,false).copy(encounter=true)
        val p=Engine.plan(loaded,"spellpunch",Ability.SPELL_GAUNTLET,listOf("a"),elements=listOf(DamageType.FIRE),distance=5)
        assertEquals(listOf(Dice(3,12,5),Dice(1,8,4),Dice(2,6),Dice(2,8)),Engine.components(p).map {it.damage.dice})
        assertEquals(listOf(Dice(6,12,5),Dice(2,8,4),Dice(4,6),Dice(4,8)),Engine.components(p,true).map {it.damage.dice})
        val after=Engine.commit(loaded,p)
        assertEquals(listOf("b"),after.installed!!.payloads.map {it.id});assertEquals(0,after.slots[2]);assertEquals(1,after.turn.spellShots)
        assertFails {Engine.plan(after,"second",Ability.SPELL_GAUNTLET,listOf("b"),distance=5)}
    }
    @Test fun spellLimitAndChargedBonusTrackEvenWhenOptionalAttackBudgetIsOff() {
        val s=state().copy(encounter=false)
        val charged=Engine.chargeGauntlet(s);assertTrue(charged.turn.bonus)
        val after=Engine.commit(charged,Engine.plan(charged,"spell",Ability.SPELL_GAUNTLET,immediate=spell,distance=5))
        assertEquals(1,after.slots[2]);assertEquals(1,after.turn.spellShots)
        assertFails {Engine.plan(after,"second",Ability.SPELL,immediate=spell)}
        assertNotNull(Engine.plan(Engine.resetWeaponActions(after),"next",Ability.SPELL,immediate=spell))
    }
    @Test fun damageTypesHaveIndependentSourcedFormulasAndUnapprovedValuesInherit() {
        val fire=DamageFormulaProfile(2,Dice(3,6,2),"Synthetic accepted formula",true)
        val cold=DamageFormulaProfile(1,Dice(1,10),"Synthetic draft",false)
        val profile=state().profile.copy(level=10,confirmed=setOf(Upgrade.DUAL_RESONANCE),elementDamageProfiles=mapOf(DamageType.FIRE to fire,DamageType.COLD to cold))
        val p=Engine.plan(state().copy(profile=profile),"dual",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE,DamageType.COLD))
        assertEquals(listOf(Dice(3,6,2),Dice(2,8)),Engine.components(p).drop(1).map {it.damage.dice})
        assertEquals(listOf(Dice(6,6,2),Dice(4,8)),Engine.components(p,true).drop(1).map {it.damage.dice})
        assertEquals(p,BackupCodec.decodeShot(BackupCodec.json.encodeToString(PendingShot(p))).plan)
    }
    @Test fun profilesNeverUnlockAdvancedElementsOrAddNeutralDice() {
        val configured=state().profile.copy(elementDamageProfiles=mapOf(DamageType.RADIANT to DamageFormulaProfile(1,Dice(4,8),"Synthetic source",true),
            DamageType.FORCE to DamageFormulaProfile(1,Dice(4,12,7),"Synthetic approved base replacement",true)))
        val s=state().copy(profile=configured)
        assertFails {Engine.plan(s,"locked",Ability.ELEMENTAL,elements=listOf(DamageType.RADIANT))}
        assertEquals(listOf(Dice(4,12,7)),Engine.components(Engine.plan(s,"neutral",Ability.ARCANE)).map {it.damage.dice})
        val unlocked=s.copy(profile=configured.copy(level=8,confirmed=setOf(Upgrade.ADVANCED_ELEMENTS)))
        assertEquals(Dice(4,8),Engine.components(Engine.plan(unlocked,"radiant",Ability.ELEMENTAL,elements=listOf(DamageType.RADIANT))).last().damage.dice)
    }
    @Test fun dischargeRetainsInheritedFormulaAndRecoversOnlyWithExplicitGameTime() {
        val s=state();val p=Engine.plan(s,"blast",Ability.DISCHARGE,point="Synthetic point")
        assertEquals(60,p.range);assertEquals(15,p.radius);assertEquals(Dice(6,12),Engine.components(p).single().damage.dice)
        val after=Engine.commit(s,p);assertEquals(600L,after.installed!!.exhaustedUntil)
        assertFails {Engine.plan(after,"punch",Ability.GAUNTLET,distance=5)}
        assertFails {Engine.chargeGauntlet(after)}
        assertFalse(Engine.advance(after,599).installed!!.usable(599));assertTrue(Engine.advance(after,600).installed!!.usable(600))
    }
    @Test fun ordinaryGuardianWorksDuringOverloadWithoutEnhancedDamageOrChargeConsumption() {
        val charged=Engine.chargeGauntlet(state())
        val overloaded=charged.replace(charged.installed!!.copy(exhaustedUntil=600))
        val p=Engine.plan(overloaded,"guardian",Ability.GUARDIAN,elements=listOf(DamageType.FIRE),distance=5)
        assertEquals(7,p.attackBonus);assertEquals(5,p.range);assertTrue(p.elements.isEmpty())
        assertEquals(listOf(Dice(1,8,4)),Engine.components(p).map {it.damage.dice})
        assertEquals(listOf(Dice(2,8,4)),Engine.components(p,true).map {it.damage.dice})
        val after=DiceRoller.fire(overloaded,p,die={if(it==20)20 else 3})
        assertEquals(27,after.pending.last().rolled!!.attackTotal);assertEquals(charged.gauntletCharge,after.gauntletCharge)
        assertEquals(600L,after.installed!!.exhaustedUntil)
        assertFails {Engine.plan(after,"enhanced",Ability.GAUNTLET,distance=5)}
        assertNotNull(Engine.guardianLockReason(state().profile.copy(melee=melee.copy(guardianAttackBonus=null))))
    }
    @Test fun formulaValidationRejectsUnversionedUnsourcedAndUnsupportedDice() {
        assertFails {DamageFormulaProfile(0,Dice(2,8),"source",true).validate()}
        assertFails {DamageFormulaProfile(1,Dice(2,8),"",true).validate()}
        assertFails {DamageFormulaProfile(1,Dice(2,7),"source",true).validate()}
        assertFails {DamageFormulaProfile(1,Dice(11,8),"source",true).validate()}
    }
}
