package com.yazsras.astravar.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SelectorMigrationTest {
    private fun withoutSelector(e: JsonElement): JsonElement=when(e) {
        is JsonObject -> JsonObject(e.filterKeys {it!="elementalSelectorDiceCount"}.mapValues {withoutSelector(it.value)})
        is JsonArray -> JsonArray(e.map(::withoutSelector))
        else -> e
    }
    private fun seed(m: Int)=Campaign(onboarded=true,attuned=true,installedId="core",profile=Profile(baseDamageDiceCount=7,elementalSelectorDiceCount=m),cores=listOf(Core("core","Existing core")),slots=mapOf(2 to 1),gameSeconds=160,notes="Keep my campaign")

    @Test fun legacyActiveProfilesMigrateToTwoWhileEveryHistoricalShotRetainsOne() {
        for(critical in listOf(false,true)) {
            val before=seed(1)
            val p=Engine.plan(before,"legacy",Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING))
            val after=DiceRoller.fire(before,p,die={if(it==20) {if(critical) 20 else 14} else 4})
            val entry=HistoryEntry("old-event","Old elemental shot",160,before,after)
            val backup=Backup(campaign=after,history=listOf(entry),archives=listOf(HistoryArchive("archive",listOf(entry))))
            val old=withoutSelector(BackupCodec.json.parseToJsonElement(BackupCodec.encode(backup))).toString()
            val restored=BackupCodec.decode(old)
            assertEquals(after.copy(profile=after.profile.copy(elementalSelectorDiceCount=2)),restored.campaign)
            val shots=listOf(restored.campaign.pending.single(),restored.history.single().after.pending.single(),restored.archives.single().history.single().after.pending.single())
            shots.forEach {assertEquals(after.pending.single(),it);assertEquals(shotSummary(after.pending.single()),shotSummary(it))}
            assertEquals(7,restored.campaign.profile.baseDamageDiceCount)
            assertEquals(restored,BackupCodec.decode(BackupCodec.encode(restored)))
        }
    }

    @Test fun backupMissingBothUpgradeFieldsKeepsOriginalWeaponAndSelectorRolls() {
        val before=seed(1).copy(profile=Profile(baseDamageDiceCount=1,elementalSelectorDiceCount=1))
        val after=DiceRoller.fire(before,Engine.plan(before,"very-old",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE)),die={if(it==20) 14 else 4})
        val encoded=withoutSelector(BackupCodec.json.parseToJsonElement(BackupCodec.encode(Backup(campaign=after)))).toString()
        val old=encoded.replace("\"baseDamageDiceCount\":1,","")
        val restored=BackupCodec.decode(old).campaign
        assertEquals(2,restored.profile.baseDamageDiceCount);assertEquals(2,restored.profile.elementalSelectorDiceCount)
        assertEquals(after.pending,restored.pending)
        assertEquals(listOf(Dice(1,10,5),Dice(1,8)),Engine.components(restored.pending.single().plan).map {it.damage.dice})
    }

    @Test fun oldCommittedUnrolledElementalShotStillRollsOneAfterMigration() {
        val before=seed(1)
        val p=Engine.plan(before,"pending",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE))
        val committed=Engine.commit(before,p)
        val old=withoutSelector(BackupCodec.json.parseToJsonElement(BackupCodec.encode(Backup(campaign=committed)))).toString()
        val restored=BackupCodec.decode(old).campaign
        val rolled=DiceRoller.rollPending(restored,p.id,die={if(it==20) 14 else 4})
        assertEquals(2,rolled.profile.elementalSelectorDiceCount)
        assertEquals(listOf(4),rolled.pending.single().rolled!!.damage.getValue("element0").faces)
        assertEquals(committed.cores,rolled.cores);assertEquals(committed.slots,rolled.slots)
    }

    @Test fun modernBackupsKeepIndependentCountsAndEarlierExpressions() {
        var s=seed(2)
        s=DiceRoller.fire(s,Engine.plan(s,"two",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE)),die={if(it==20) 14 else 3})
        s=s.copy(profile=s.profile.copy(elementalSelectorDiceCount=4))
        s=DiceRoller.fire(s,Engine.plan(s,"four",Ability.ELEMENTAL,elements=listOf(DamageType.FIRE)),die={if(it==20) 14 else 3})
        for(m in listOf(7,8,10)) {
            val current=s.copy(profile=s.profile.copy(elementalSelectorDiceCount=m))
            val restored=BackupCodec.decode(BackupCodec.encode(Backup(campaign=current))).campaign
            assertEquals(current,restored);assertEquals(7,restored.profile.baseDamageDiceCount)
            assertEquals(listOf(2,4),restored.pending.map {it.rolled!!.damage.getValue("element0").faces.size})
        }
    }

    @Test fun fractionalAndInvalidSelectorCountsAreRejectedByProfilesAndBackupImport() {
        for(m in listOf(-1,0,11,100)) {
            try {Profile(elementalSelectorDiceCount=m).validate();fail("Invalid selector count accepted")} catch(_:IllegalArgumentException) {}
        }
        val json=BackupCodec.encode(Backup(campaign=seed(2)))
        for(value in listOf("0","-1","11","2.5","null")) {
            try {BackupCodec.decode(json.replace("\"elementalSelectorDiceCount\":2","\"elementalSelectorDiceCount\":$value"));fail("Invalid selector JSON accepted: $value")} catch(_:IllegalArgumentException) {}
        }
    }
}
