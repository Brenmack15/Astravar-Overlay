package com.yazsras.astravar.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun legacyShot(): Pair<Campaign,Campaign> {
        val before=Campaign(onboarded=true,attuned=true,profile=Profile(baseDamageDiceCount=1),installedId="core",cores=listOf(Core("core","Legacy core")),notes="keep this campaign note")
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
}
