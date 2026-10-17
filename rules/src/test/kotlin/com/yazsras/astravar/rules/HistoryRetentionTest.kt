package com.yazsras.astravar.rules

import java.io.File
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class HistoryRetentionTest {
    private fun campaign()=Campaign(revision=100000,onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Preserved")),
        profile=Profile(baseDamageDiceCount=5,elementalSelectorDiceCount=7),slots=mapOf(2 to 3),notes="Preserve 🦉 resources")
    private fun shot(i: Int,n: Int=4,a: Ability=Ability.ARCANE): PendingShot {
        val s=campaign().copy(revision=i.toLong(),profile=Profile(baseDamageDiceCount=n))
        val p=Engine.plan(s,"roll-$i",a,elements=listOf(DamageType.LIGHTNING),point="Gate")
        val roll=DiceRoller.roll(p,die={if(it==20) 14 else 7},atMillis=i.toLong())
        val result=DiceRoller.resolution(p,roll,targets=if(p.blast) listOf(TargetSave("Target",true)) else emptyList())
        return PendingShot(p,result,roll)
    }
    @Test fun boundaryCountsExportAndImportKeepOnlyNewestTwentyAndNoDuplicateDice() {
        for(n in listOf(0,1,19,20,21,100)) {
            val shots=(1..n).map {shot(it)};val s=campaign().copy(pending=shots)
            val events=shots.mapIndexed {i,p->HistoryEntry("e-$i","Roll",0,campaign(),campaign().copy(revision=i.toLong(),pending=listOf(p)))}
            val b=Backup(campaign=s,history=events,archives=listOf(HistoryArchive("duplicate",events)))
            val exported=BackupCodec.encode(b);val restored=BackupCodec.decode(exported)
            assertEquals(shots.takeLast(HistoryRetention.MAX_HISTORY_ENTRIES),restored.campaign.pending)
            assertEquals(shots.takeLast(HistoryRetention.MAX_HISTORY_ENTRIES).asReversed(),restored.campaign.recentRolls())
            assertTrue(restored.history.size<=HistoryRetention.MAX_HISTORY_ENTRIES)
            assertTrue(restored.history.all {it.before.pending.isEmpty() && it.after.pending.isEmpty()})
            assertTrue(restored.archives.isEmpty());assertEquals(campaign().notes,restored.campaign.notes)
            assertEquals(minOf(n,HistoryRetention.MAX_HISTORY_ENTRIES),Regex("\\\"attackFaces\\\"").findAll(exported).count())
        }
    }
    @Test(timeout=30000) fun streamingOversizedLegacyBackupRetainsWindowWithoutBuildingAllObjects() {
        val file=File.createTempFile("astravar-legacy-", ".json")
        try {
            val base=BackupCodec.json.encodeToString(campaign()).removeSuffix("}").replace("\"pending\":[],","")
            file.bufferedWriter().use {w->
                w.write("{\"campaign\":$base,\"pending\":[")
                for(i in 1..10000) {if(i>1) w.write(",");w.write(BackupCodec.json.encodeToString(shot(i,10,Ability.SHATTER)))}
                w.write("]},\"history\":[],\"archives\":[]}")
            }
            assertTrue(file.length()>BackupCodec.MAX_BYTES)
            val restored=file.bufferedReader().use {BackupCodec.decode(it)}
            assertEquals((9981..10000).map {"roll-$it"},restored.campaign.pending.map {it.plan.id})
            assertEquals(campaign().profile,restored.campaign.profile);assertEquals(campaign().slots,restored.campaign.slots)
            restored.campaign.pending.forEach {assertEquals(210,it.rolled!!.damage.getValue("blast").faces.size)}
        } finally {file.delete()}
    }
    @Test fun malformedHistoricalEntriesAreIsolatedWithoutDroppingCampaignOrValidOlderRolls() {
        val base=BackupCodec.json.encodeToString(campaign()).replace("\"pending\":[]","\"pending\":["+(1..25).joinToString(",") {BackupCodec.json.encodeToString(shot(it))}+",{\"plan\":\"broken\"},null]")
        val restored=BackupCodec.decode("{\"campaign\":$base,\"history\":[null,{\"id\":\"bad\"}],\"archives\":[]}")
        assertEquals((6..25).map {"roll-$it"},restored.campaign.pending.map {it.plan.id})
        assertEquals(campaign().copy(pending=restored.campaign.pending),restored.campaign)
        assertTrue(restored.history.isEmpty())
    }
    @Test fun largeShatterFacesAndTypedSavesArePreservedAcrossRotationAndBackup() {
        val shots=(1..100).map {shot(it,listOf(4,5,10)[it%3],Ability.SHATTER)}
        val restored=BackupCodec.decode(BackupCodec.json.encodeToString(Backup(campaign=campaign().copy(pending=shots)))).campaign
        assertEquals(shots.takeLast(20),restored.pending)
        for(p in restored.pending) {
            assertTrue(p.plan.blastDiceCount in listOf(84,105,210))
            assertEquals(p.plan.blastDiceCount*7,p.rolled!!.damage.getValue("blast").total)
            val damage=Engine.damage(p.plan,p.result!!,p.result!!.targets.single())
            assertEquals(p.plan.blastDiceCount*7/2,damage[DamageType.FORCE]);assertEquals(7,damage[DamageType.LIGHTNING])
        }
    }
    @Test fun compactUndoRestoresResolutionWithoutDuplicatingDiceOrRevivingExpiredRolls() {
        val old=shot(1).copy(result=null);val resolved=old.copy(result=DiceRoller.resolution(old.plan,old.rolled!!))
        val e=HistoryRetention.compact(HistoryEntry("resolve","Resolve",0,campaign().copy(pending=listOf(old)),campaign().copy(pending=listOf(resolved))))
        assertTrue(e.before.pending.isEmpty());assertTrue(e.after.pending.isEmpty())
        assertEquals(listOf(old),HistoryRetention.restoreRolls(listOf(resolved),e))
        assertTrue(HistoryRetention.restoreRolls(emptyList(),e).isEmpty())
    }
    @Test fun malformedCampaignResourcesStillRejectInsteadOfResettingState() {
        val text=BackupCodec.json.encodeToString(Backup(campaign=campaign())).replace("\"baseDamageDiceCount\":5","\"baseDamageDiceCount\":1.5")
        try {BackupCodec.decode(text);fail("Invalid active profile accepted")} catch(_:IllegalArgumentException) {}
        for(text2 in listOf("{","{} trailing","{\"campaign\":{\"pending\":[{")) {
            try {BackupCodec.decode(text2);fail("Malformed structure accepted")} catch(_:IllegalArgumentException) {}
        }
    }
}
