package com.yazsras.astravar.rules

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ForceMigrationTest {
    private fun old(n: Int=4,m: Int=7)=Campaign(onboarded=true,attuned=true,installedId="core",gameSeconds=123,
        profile=Profile(baseDamageDiceCount=n,elementalSelectorDiceCount=m,forceRulesVersion=1,version=9,level=13),
        cores=listOf(Core("core","Existing core")),slots=mapOf(2 to 1),notes="Keep this campaign",projects=listOf(CraftProject("work","Core","Existing recipe",2)))
    private fun strip(e: JsonElement,keys: Set<String>): JsonElement=when(e) {
        is JsonObject -> JsonObject(e.filterKeys {it !in keys}.mapValues {strip(it.value,keys)})
        is JsonArray -> JsonArray(e.map {strip(it,keys)})
        else -> e
    }
    private fun encodeOld(b: Backup,keys: Set<String> = setOf("forceRulesVersion"))=strip(BackupCodec.json.parseToJsonElement(BackupCodec.encode(b)),keys).toString()

    @Test fun oldBackupPreservesCountsAndCampaignWhileOnlyActiveProfileAdvances() {
        for(n in 1..10) for(m in listOf(1,2,7,10)) {
            val before=old(n,m)
            val shot=Engine.plan(before,"old-element",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE))
            val after=DiceRoller.fire(before,shot,die={if(it==20) 14 else 10.coerceAtMost(it)})
            val event=HistoryEntry("old","Old shot",123,before,after)
            val decoded=BackupCodec.decode(encodeOld(Backup(campaign=after,history=listOf(event),archives=listOf(HistoryArchive("archive",listOf(event))))))
            assertEquals(after.copy(profile=after.profile.copy(forceRulesVersion=2,version=10)),decoded.campaign)
            assertEquals(HistoryRetention.compact(event),decoded.history.single());assertTrue(decoded.archives.isEmpty())
            assertEquals(Dice(n,12,5),decoded.campaign.profile.base)
            assertEquals(Dice(n,10,5),decoded.campaign.pending.single().plan.profile.base)
            assertEquals(Dice(m,8),decoded.campaign.profile.selector)
            assertEquals(decoded,BackupCodec.decode(BackupCodec.encode(decoded)))
        }
    }

    @Test fun oldCriticalAndEmergencySnapshotsKeepDiceSelectorsAndResultsExactly() {
        for(a in Ability.entries.filter {it!=Ability.SPELL}) for(crit in listOf(false,true)) {
            val before=old(m=2)
            val elements=if(a==Ability.ELEMENTAL) listOf(DamageType.FIRE) else emptyList()
            val plan=Engine.plan(before,"old",a,elements=elements,direct=a in listOf(Ability.DISCHARGE,Ability.SHATTER),point="Old gate")
            // v1.4 level 13, N=4: 12+6 = 18d10 Discharge; 36+18 = 54d10 Shatter.
            val historic=plan.copy(blastDiceCount=when(a) {Ability.DISCHARGE->18;Ability.SHATTER->54;else->0})
            val roll=DiceRoller.roll(historic,RollOptions(armorClass=18),die={if(it==20) {if(crit) 20 else 14} else 4},atMillis=25)
            val targets=if(plan.blast) listOf(TargetSave("Target",true,directTarget=true)) else emptyList()
            val result=DiceRoller.resolution(historic,roll,targets=targets)
            val saved=PendingShot(historic,result,roll)
            val after=Engine.commit(before,plan).copy(pending=listOf(saved))
            val decoded=BackupCodec.decode(encodeOld(Backup(campaign=after))).campaign
            assertEquals(saved,decoded.pending.single());assertEquals(shotSummary(saved),shotSummary(decoded.pending.single()))
            if(plan.blast) assertFalse(Engine.components(decoded.pending.single().plan,true).any {it.label=="Selector"})
            assertEquals(12,decoded.profile.primaryForceDieSides)
        }
    }

    @Test fun preSliderPendingBlastUsesItsLegacyTierAndRollsD10AfterMigration() {
        for(a in listOf(Ability.DISCHARGE,Ability.SHATTER)) {
            val before=old().copy(profile=old().profile.copy(emergencySpellLevelOverride=5))
            val plan=Engine.plan(before,"unrolled",a,point="Old gate")
            val after=Engine.commit(before,plan)
            val decoded=BackupCodec.decode(encodeOld(Backup(campaign=after),setOf("forceRulesVersion","blastDiceCount"))).campaign
            val expected=if(a==Ability.DISCHARGE) 14 else 42
            assertEquals(expected,decoded.pending.single().plan.blastDiceCount)
            val requests=mutableListOf<Int>()
            val rolled=DiceRoller.rollPending(decoded,plan.id,die={requests+=it;10})
            assertEquals(List(expected) {10},requests)
            assertEquals(after.cores,rolled.cores);assertEquals(after.slots,rolled.slots)
            assertEquals(Dice(12,12),decoded.profile.discharge);assertEquals(Dice(84,12),decoded.profile.shatter)
        }
    }

    @Test fun activeLegacyVersionMigratesEvenWhenExplicitButUnknownVersionIsRejected() {
        val s=old()
        val migrated=BackupCodec.decode(BackupCodec.encode(Backup(campaign=s))).campaign
        assertEquals(2,migrated.profile.forceRulesVersion);assertEquals(10,migrated.profile.version)
        val snapshot=BackupCodec.json.encodeToJsonElement(Campaign.serializer(),s).toString()
        assertEquals(migrated,BackupCodec.decodeCampaignSnapshot(snapshot))
        for(version in listOf(0,3)) assertFails {BackupCodec.decode(BackupCodec.encode(Backup(campaign=s.copy(profile=s.profile.copy(forceRulesVersion=version)))))}
    }

    @Test fun newBackupsPreserveRulesVersionAndHistoricD10BesideNewD12() {
        val before=old()
        val after=DiceRoller.fire(before,Engine.plan(before,"old",Ability.ARCANE),die={if(it==20) 14 else 8})
        val upgraded=BackupCodec.decode(encodeOld(Backup(campaign=after))).campaign
        val next=DiceRoller.fire(upgraded,Engine.plan(upgraded,"new",Ability.ARCANE),die={if(it==20) 14 else 12})
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=next))).campaign
        assertEquals(next,restored)
        assertEquals(listOf("4d10+5","4d12+5"),restored.pending.map {it.plan.profile.base.toString()})
        assertEquals(listOf(37,53),restored.pending.map {it.rolled!!.damage.getValue("direct").total})
    }
}
