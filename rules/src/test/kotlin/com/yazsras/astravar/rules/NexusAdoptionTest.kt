package com.yazsras.astravar.rules

import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.*

class NexusAdoptionTest {
    private val spell=Template("fixture-spell","Synthetic payload",2,2,listOf(TypedDice(Dice(2,6),DamageType.COLD)),DamageType.COLD,true)
    private fun legacy()=Campaign(revision=9,onboarded=true,attuned=false,installedId=null,postShatter=true,
        installation=Installation("intact",700),gameSeconds=100,cores=listOf(Core("destroyed","Historical core",destroyed=true),
            Core("intact","Existing intact record",exhaustedUntil=700,payloads=listOf(Payload("payload",spell,SourceKind.SLOT,"intact",50)))),
        slots=mapOf(2 to 1),scrolls=listOf(Scroll("scroll",spell.id,2)),templates=listOf(spell),
        turn=Turn(3,attacks=1,bonus=true,spellShots=1),notes="Synthetic legacy resources stay unchanged")

    @Test fun adoptionMapsExistingRecordWithoutRepairRefillOrClockAdvance() {
        val before=legacy()
        val coreBytes=BackupCodec.json.encodeToString(before.cores)
        val after=Engine.adoptPermanentNexus(before,9,"intact","Resolved synthetic conversion checkpoint",confirmed=true)
        assertEquals("intact",after.installedId);assertFalse(after.postShatter);assertNull(after.installation)
        assertEquals(coreBytes,BackupCodec.json.encodeToString(after.cores))
        assertEquals(before,after.copy(installedId=before.installedId,postShatter=before.postShatter,installation=before.installation,nexusAdoption=null))
        assertFalse(after.installed!!.usable(after.gameSeconds));assertEquals(700L,after.installed!!.exhaustedUntil)
        assertEquals(before.installation,after.nexusAdoption!!.legacyInstallation)
        assertFalse(after.nexusAdoption!!.recordedConfirmedExistingNexus)
        assertFails {Engine.plan(after,"attack",Ability.ARCANE)} // Existing attunement is still unconfirmed.
    }
    @Test fun sourceConfirmationAndObservedRevisionAreMandatory() {
        val s=legacy()
        assertFails {Engine.adoptPermanentNexus(s,9,"intact","",confirmed=true)}
        assertFails {Engine.adoptPermanentNexus(s,9,"intact","Synthetic source",confirmed=false)}
        assertFails {Engine.adoptPermanentNexus(s,8,"intact","Synthetic source",confirmed=true)}
        assertFails {Engine.adoptPermanentNexus(s,9,"destroyed","Synthetic source",confirmed=true)}
        assertFails {Engine.adoptPermanentNexus(s,9,"missing","Synthetic source",confirmed=true)}
        assertFails {Engine.adoptPermanentNexus(s,9,"extra","Synthetic source",recordConfirmedExistingNexus=true,confirmed=true)}
        assertEquals(legacy(),s)
    }
    @Test fun healthyActiveProfileCannotUseAdoptionAsASwap() {
        val s=legacy().copy(installedId="intact",postShatter=false,installation=null)
        assertFalse(Engine.needsNexusAdoption(s))
        assertFails {Engine.adoptPermanentNexus(s,9,"intact","Synthetic source",confirmed=true)}
        assertFalse(Engine.needsNexusAdoption(legacy().copy(onboarded=false)))
    }
    @Test fun allDestroyedNeedsExplicitConfirmedExistingRecordAndNeverRevivesOldCore() {
        val before=legacy().copy(installation=null,cores=listOf(legacy().cores.first()))
        assertFails {Engine.adoptPermanentNexus(before,9,"confirmed-nexus","Resolved synthetic current nexus",confirmed=true)}
        assertFails {Engine.adoptPermanentNexus(before,9,"destroyed","Resolved synthetic current nexus",recordConfirmedExistingNexus=true,confirmed=true)}
        val after=Engine.adoptPermanentNexus(before,9,"confirmed-nexus","Resolved synthetic current nexus: available, empty reservoir",
            recordConfirmedExistingNexus=true,confirmed=true)
        assertEquals(before.cores,after.cores.dropLast(1));assertTrue(after.core("destroyed").destroyed)
        assertEquals("confirmed-nexus",after.installedId);assertTrue(after.installed!!.payloads.isEmpty())
        assertEquals(before.slots,after.slots);assertEquals(before.scrolls,after.scrolls);assertEquals(before.turn,after.turn)
        assertEquals(before.gameSeconds,after.gameSeconds);assertEquals(before.attuned,after.attuned)
        assertTrue(after.nexusAdoption!!.recordedConfirmedExistingNexus)
    }
    @Test fun identicalRetryIsNoopAndDifferentSecondAdoptionIsRejected() {
        val adopted=Engine.adoptPermanentNexus(legacy(),9,"intact","Synthetic source",confirmed=true)
        assertEquals(adopted,Engine.adoptPermanentNexus(adopted,9,"intact","Synthetic source",confirmed=true))
        assertFails {Engine.adoptPermanentNexus(adopted,9,"destroyed","Synthetic source",confirmed=true)}
        assertFails {Engine.adoptPermanentNexus(adopted,9,"intact","Different source",confirmed=true)}
        assertFalse(Engine.needsNexusAdoption(adopted))
    }
    @Test fun backupRetainsAdoptionAndHistoricalShatterSnapshotExactly() {
        val before=legacy()
        val p=ShotPlan("old-shatter",0,Ability.SHATTER,"destroyed",Profile(),emptyList(),targetPoint="Synthetic historical point")
        val historical=PendingShot(p,rolled=DiceRoller.roll(p,die={1}))
        val withHistory=before.copy(pending=listOf(historical))
        val after=Engine.adoptPermanentNexus(withHistory,9,"intact","Resolved synthetic conversion",confirmed=true)
        val backup=Backup(campaign=after,history=listOf(HistoryEntry("adoption","Reviewed synthetic adoption",100,withHistory,after)))
        val restored=BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(after,restored.campaign);assertEquals(historical,restored.campaign.pending.single())
        assertEquals(before.cores,restored.campaign.cores)
        assertEquals(after.nexusAdoption,restored.history.single().after.nexusAdoption)
        assertFails {BackupCodec.validate(after.copy(installedId=null))}
        assertFails {Engine.plan(restored.campaign,"new-shatter",Ability.SHATTER,point="No new execution")}
    }
}
