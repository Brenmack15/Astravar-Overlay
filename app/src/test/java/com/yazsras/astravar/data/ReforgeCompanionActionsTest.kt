package com.yazsras.astravar.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReforgeCompanionActionsTest {
    private fun sourceDb() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
    private fun localDb() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReforgeDb::class.java).build()
    private fun attack(id: String = "confirmed-bite") = SheetAttack(id = id, name = "Confirmed bite", attackBonus = 6,
        damageFormula = "2d6 + 3", damageType = "Piercing", range = "10 ft.", evidenceStatus = "Confirmed", source = "Accepted synthetic mechanics")

    @Test fun strictReviewConvertsOnlyExplicitConfirmedSingleAttacksAndPreservesExactFixedBonus() = runBlocking {
        val source = sourceDb(); val localDb = localDb()
        try {
            val legacy = CampaignLedgerRepository(source); val local = ReforgeRepository(localDb)
            legacy.records.saveProfile(CharacterProfile(id = "companion", name = "Synthetic companion", level = 9), source = "Synthetic status")
            val base = attack()
            legacy.party.saveSheet("companion", PartySheetData(attacks = listOf(base,
                base.copy(id = "proposed", name = "Future breath", evidenceStatus = "Proposed"),
                base.copy(id = "charge", name = "Charged bite", resourceCost = "1 charge"),
                base.copy(id = "sequence", name = "Multiattack", multiattack = "Two bites"),
                base.copy(id = "critical", name = "Custom critical", criticalRule = "Triple dice"),
                base.copy(id = "unknown", name = "Unknown attack", attackBonus = null),
                base.copy(id = "bad-formula", name = "Mixed damage", damageFormula = "2d6 piercing + 1d6 cold"))), source = "Synthetic accepted sheet")
            val original = campaignDecodeBackup(legacy.export()).copy(exportedAt = 0)
            val review = reviewCompanionActions(local.readLegacyPreview(legacy, "companion"))
            assertEquals(1, review.candidates.size); assertEquals(6, review.unavailable.size)
            val converted = review.candidates.single().attack
            assertEquals(base.id, converted.id)
            assertEquals(6, converted.fixedAttackBonus)
            assertFalse(converted.proficient); assertFalse(converted.addAbilityToDamage)
            assertEquals(SecureDice.parse("2d6+3"), converted.damage.single().formula)
            val unknownAbilities = ReforgeCharacter("companion", "Synthetic companion", level = 9, attacks = listOf(converted))
            val preview = AttackRules.preview(unknownAbilities, converted.id)
            assertEquals(6, preview.attackBonus.value)
            assertEquals(SecureDice.parse("2d6+3"), preview.components.single().formula)
            assertEquals(SecureDice.parse("4d6+3"), AttackRules.preview(unknownAbilities, converted.id, critical = true).components.single().formula)
            assertEquals(original, campaignDecodeBackup(legacy.export()).copy(exportedAt = 0))
        } finally { source.close(); localDb.close() }
    }

    @Test fun reviewedCommitPreservesResourcesOriginalSourceAndImmutableRetryWithoutAddingTwice() = runBlocking {
        val source = sourceDb(); val localDb = localDb()
        try {
            val legacy = CampaignLedgerRepository(source); val local = ReforgeRepository(localDb)
            legacy.records.saveProfile(CharacterProfile(id = "companion", name = "Synthetic companion", level = 9, currentHp = 17, maximumHp = 48), source = "Synthetic status")
            legacy.party.saveSheet("companion", PartySheetData(attacks = listOf(attack())), source = "Accepted sheet")
            val before = campaignDecodeBackup(legacy.export()).copy(exportedAt = 0)
            val created = local.create(ReforgeCharacter("companion", "Synthetic companion", level = 9, currentHp = 17, baseMaxHp = 48,
                resources = listOf(ResourcePool("repair", "Repair", 1, 0, Recharge.SHORT_REST))))
            val review = reviewCompanionActions(local.readLegacyPreview(legacy, "companion"))
            val added = local.commitCompanionActions(legacy, review, setOf("confirmed-bite"), created.revision, "companion-attack-command")
            assertEquals(1, added.character.attacks.size)
            assertEquals(17, added.character.currentHp)
            assertEquals(created.character.resources, added.character.resources)
            val newer = local.save("companion", added.revision, added.character.copy(notes = "Later local edit"), "Local edit")
            val retry = local.commitCompanionActions(legacy, review, setOf("confirmed-bite"), created.revision, "companion-attack-command")
            assertEquals(added, retry)
            assertEquals(newer, local.get("companion"))
            assertEquals(before, campaignDecodeBackup(legacy.export()).copy(exportedAt = 0))
        } finally { source.close(); localDb.close() }
    }

    @Test fun staleSourceUnverifiedSelectionAndLocalIdentityCollisionFailClosed() = runBlocking {
        val source = sourceDb(); val localDb = localDb()
        try {
            val legacy = CampaignLedgerRepository(source); val local = ReforgeRepository(localDb)
            legacy.records.saveProfile(CharacterProfile(id = "companion", name = "Synthetic companion", level = 9), source = "Synthetic status")
            legacy.party.saveSheet("companion", PartySheetData(attacks = listOf(attack())), source = "Accepted sheet")
            val created = local.create(ReforgeCharacter("companion", "Synthetic companion", level = 9))
            val review = reviewCompanionActions(local.readLegacyPreview(legacy, "companion"))
            assertTrue(runCatching { local.commitCompanionActions(legacy, review, setOf("unverified"), created.revision) }.isFailure)
            val original = source.partyDao().sheet("companion")!!
            legacy.party.saveSheet("companion", original.sheet.copy(role = "Updated source"), original.revision, "Source correction")
            assertTrue(runCatching { local.commitCompanionActions(legacy, review, setOf("confirmed-bite"), created.revision) }.isFailure)
            assertEquals(created, local.get("companion"))
        } finally { source.close(); localDb.close() }
    }
    @Test fun bonusActionFromNativeSheetIsReviewedAndArchivedCompanionRejectsChanges() = runBlocking {
        val source = sourceDb(); val localDb = localDb()
        try {
            val legacy = CampaignLedgerRepository(source); val local = ReforgeRepository(localDb)
            legacy.records.saveProfile(CharacterProfile(id = "companion", name = "Synthetic companion"), source = "Fictional fixture")
            legacy.party.saveSheet("companion", PartySheetData(attacks = listOf(attack().copy(actionEconomy = "Bonus action"))), source = "Fictional fixture")
            val review = reviewCompanionActions(local.readLegacyPreview(legacy, "companion"))
            assertTrue(review.unavailable.isEmpty())
            assertEquals("Bonus Action", review.candidates.single().attack.actionType)
            val created = local.create(ReforgeCharacter("companion", "Synthetic companion", archived = true))
            assertTrue(runCatching { local.commitCompanionActions(legacy, review, setOf("confirmed-bite"), created.revision) }.isFailure)
            assertEquals(created, local.get("companion"))
        } finally { source.close(); localDb.close() }
    }

}
