package com.yazsras.astravar.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun legacyShot(): Pair<Campaign,Campaign> {
        val before=Campaign(onboarded=true,attuned=true,profile=Profile(baseDamageDiceCount=1,forceRulesVersion=1),installedId="core",cores=listOf(Core("core","Legacy core")),notes="keep this campaign note")
        val plan=Engine.plan(before,"legacy-shot",Ability.ARCANE)
        val after=DiceRoller.fire(before,plan,die={if(it==20) 14 else 7})
        return before to after
    }

    private fun withoutBaseCount(element: JsonElement): JsonElement = when(element) {
        is JsonObject -> JsonObject(element.filterKeys {it!="baseDamageDiceCount"}.mapValues {withoutBaseCount(it.value)})
        is JsonArray -> JsonArray(element.map(::withoutBaseCount))
        else -> element
    }

    @Test fun currentAndHistoricalProfilesMigrateWithTheirOwnCorrectDefaults() {
        val (before,after)=legacyShot()
        val entry=HistoryEntry("legacy-event","Legacy one-die roll",0,before,after)
        val source=Backup(campaign=after,history=listOf(entry),archives=listOf(HistoryArchive("archive",listOf(entry))))
        val oldJson=withoutBaseCount(BackupCodec.json.parseToJsonElement(BackupCodec.encode(source))).toString()

        val restored=BackupCodec.decode(oldJson)
        assertEquals(2,restored.campaign.profile.baseDamageDiceCount)
        assertTrue(restored.campaign.onboarded);assertTrue(restored.campaign.attuned)
        assertEquals("keep this campaign note",restored.campaign.notes)
        val pending=restored.campaign.pending.single()
        assertEquals(1,pending.plan.profile.baseDamageDiceCount)
        assertEquals(listOf(7),pending.rolled!!.damage.getValue("direct").faces)
        assertTrue(shotSummary(pending).contains("1d10+5"))
        val historical=restored.history.single()
        assertEquals(2,historical.before.profile.baseDamageDiceCount)
        assertEquals(2,historical.after.profile.baseDamageDiceCount)
        assertEquals(listOf(7),historical.after.pending.single().rolled!!.damage.getValue("direct").faces)
        assertEquals(1,historical.after.pending.single().plan.profile.baseDamageDiceCount)
        assertEquals(2,restored.archives.single().history.single().after.profile.baseDamageDiceCount)
        BackupCodec.validate(restored.campaign)
    }

    @Test fun newBackupRoundTripPreservesFourDiceAndHistorySnapshots() {
        val (before,after)=legacyShot()
        val current=after.copy(profile=after.profile.copy(baseDamageDiceCount=4,version=2))
        val history=listOf(HistoryEntry("legacy-event","Legacy one-die roll",0,before,after))
        val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=current,history=history)))
        assertEquals(4,restored.campaign.profile.baseDamageDiceCount)
        assertEquals(1,restored.campaign.pending.single().plan.profile.baseDamageDiceCount)
        assertEquals(1,restored.history.single().after.profile.baseDamageDiceCount)
        assertEquals(1,restored.history.single().after.pending.single().rolled!!.damage.getValue("direct").faces.size)
    }

    private fun withoutBlastSnapshot(element: JsonElement): JsonElement = when(element) {
        is JsonObject -> JsonObject(element.filterKeys {it!="blastDiceCount"}.mapValues {withoutBlastSnapshot(it.value)})
        is JsonArray -> JsonArray(element.map(::withoutBlastSnapshot))
        else -> element
    }

    @Test fun preSliderBlastHistoryKeepsOldExpressionsRawFacesAndResultsInAllSnapshots() {
        for(count in listOf(1,2,4)) for(level in listOf(5,9,13,17)) for(ability in listOf(Ability.DISCHARGE,Ability.SHATTER)) {
            val before=legacyShot().first.copy(profile=Profile(level=level,baseDamageDiceCount=count,forceRulesVersion=1))
            val newPlan=Engine.plan(before,"old-blast",ability,direct=true,point="Old gate")
            val oldCount=(if(level>=17) 14 else if(level>=13) 12 else if(level>=9) 10 else 8) * if(ability==Ability.SHATTER) 3 else 1
            val oldPlan=newPlan.copy(blastDiceCount=oldCount)
            val roll=DiceRoller.roll(oldPlan,die={if(it==20) 20 else 3},atMillis=123)
            val result=DiceRoller.resolution(oldPlan,roll,targets=listOf(TargetSave("Target",true,directTarget=true)))
            val shot=PendingShot(oldPlan,result,roll)
            val after=Engine.commit(before,newPlan).copy(pending=listOf(shot))
            val history=HistoryEntry("old-event","Old blast",0,before,after)
            val source=Backup(campaign=after,history=listOf(history),archives=listOf(HistoryArchive("old-archive",listOf(history))))
            val text=withoutBlastSnapshot(BackupCodec.json.parseToJsonElement(BackupCodec.encode(source))).toString()
            val restored=BackupCodec.decode(text)
            val shots=listOf(restored.campaign.pending.single(),restored.history.single().after.pending.single(),restored.archives.single().history.single().after.pending.single())
            shots.forEach {
                assertEquals(oldCount,it.plan.blastDiceCount)
                assertEquals(shot, it)
                assertEquals(shotSummary(shot),shotSummary(it))
            }
            assertEquals(before.profile.dischargeDice,restored.campaign.profile.dischargeDice)
            assertEquals(restored,BackupCodec.decode(BackupCodec.encode(restored)))
        }
    }

    @Test fun oldUnrolledBlastKeepsOriginalLevelOverrideWhenRolledAfterUpgrade() {
        val before=legacyShot().first.copy(profile=Profile(baseDamageDiceCount=4,emergencySpellLevelOverride=5,forceRulesVersion=1))
        val plan=Engine.plan(before,"pending-blast",Ability.SHATTER,point="Gate")
        val committed=Engine.commit(before,plan)
        val oldJson=withoutBlastSnapshot(BackupCodec.json.parseToJsonElement(BackupCodec.encode(Backup(campaign=committed)))).toString()
        val migrated=BackupCodec.decode(oldJson).campaign
        assertEquals(42,migrated.pending.single().plan.blastDiceCount)
        assertEquals(84,migrated.profile.shatterDice)
        val rolled=DiceRoller.rollPending(migrated,plan.id,die={1})
        assertEquals(42,rolled.pending.single().rolled!!.damage.getValue("blast").faces.size)
        assertEquals(committed.cores,rolled.cores)
    }

    @Test fun backupsAtSevenAndTenPreserveNewDamageAndHistoricalSnapshots() {
        for(count in listOf(7,10)) {
            val before=legacyShot().first.copy(profile=Profile(baseDamageDiceCount=count))
            val after=DiceRoller.fire(before,Engine.plan(before,"new-blast",Ability.DISCHARGE,point="Gate"),die={3})
            val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=after))).campaign
            assertEquals(after,restored)
            assertEquals(count,restored.profile.baseDamageDiceCount)
            assertEquals(3*count,restored.pending.single().plan.blastDiceCount)
        }
    }

    @Test fun fractionalDamageCountAndInvalidBlastSnapshotsAreRejected() {
        val source=BackupCodec.encode(Backup(campaign=Campaign()))
        try {BackupCodec.decode(source.replace("\"baseDamageDiceCount\":2","\"baseDamageDiceCount\":2.5"));fail("Fractional dice accepted")} catch(_:IllegalArgumentException) {}
        val before=legacyShot().first
        val plan=Engine.plan(before,"blast",Ability.DISCHARGE,point="Gate")
        for(count in listOf(0,-1,211)) {
            val invalid=before.copy(pending=listOf(PendingShot(plan.copy(blastDiceCount=count))))
            try {BackupCodec.decode(BackupCodec.encode(Backup(campaign=invalid)));fail("Invalid blast count accepted")} catch(_:IllegalArgumentException) {}
        }
    }
}
