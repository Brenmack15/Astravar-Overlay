package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Isolated fictional records: never opens the installed character or authorization stores. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class NotionTransferRepositoryTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var playable: ReforgeRepository
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var backup: GenesisAuthorityBackupRepository
    private lateinit var importer: NotionTransferRepository
    private val url = "https://app.notion.com/p/11111111111111111111111111111111"
    private val choices = NotionTransferChoices(stats = false)

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        playable = ReforgeRepository(db)
        authority = GenesisAuthorityRepository(db)
        backup = GenesisAuthorityBackupRepository(db)
        importer = NotionTransferRepository(db, playable, backup)
    }
    @After fun close() { db.close() }

    private fun transfer() = NotionCharacterTransfer(
        NOTION_CHARACTER_TRANSFER_FORMAT, "fictional-import-1", "2026-11-15",
        NotionCharacterFacts("Fictional maker", "Maker", url, 1, "Gnome", "Artificer", "Armorer",
            Stat.entries.associateWith { 10 }),
        listOf(NotionInventoryFact("fictional-lantern", url, "Fictional lantern", "Equipment", "Maker",
            "Carried", "Confirmed", 1, knownProperties = "Original source retained; no inferred magic.")),
        listOf(NotionCurrencyFact("fictional-currency", "Reviewed estimate", 20, "Confirmed", url)), 20
    )

    private suspend fun actor(campaignId: String? = null, hpDamage: Int? = 13): ReforgeSavedCharacter = requireNotNull(
        authority.createActor("Fictional maker", GenesisActorRole.PLAYER, campaignId,
            playable = ReforgeCharacter("fictional-maker", "Fictional maker", currentHp = 7, baseMaxHp = 20,
                baseAbilities = Stat.entries.associateWith { 14 }, baselineConstitutionModifier = 2,
                temporaryHp = 3, hpDamage = hpDamage, spellSlots = mapOf(1 to 1)), commandId = "fictional-actor-create").playable)

    private suspend fun proof() = backup.exportWithProof().currentFingerprint
    private suspend fun rejected(block: suspend () -> Unit) {
        assertNotNull(runCatching { block() }.exceptionOrNull())
    }

    @Test fun managedImportCreatesOneAuditedPhysicalIdentityAndRestorableProjection() = runBlocking {
        val campaign = authority.createCampaign("Fictional expedition", commandId = "fictional-campaign-create")
        val original = actor(campaign.id)
        val result = importer.commit(original, transfer(), choices, false, proof(), "fictional-import-command")
        val itemId = "notion-instance-fictional-lantern"
        assertEquals(1, result.itemsAdded)
        assertEquals(original.origin, result.profile.origin)
        assertEquals(7, result.profile.character.currentHp)
        assertEquals(3, result.profile.character.temporaryHp)
        assertEquals(13, result.profile.character.hpDamage)
        assertEquals(mapOf(1 to 1), result.profile.character.spellSlots)
        assertEquals(original.character.id, db.partyDao().placement(itemId)?.holderProfileId)
        val source = requireNotNull(db.ledgerDao().getItem(itemId))
        assertTrue(source.description.contains(url))
        val physical = requireNotNull(readGenesisAuthorityItem(db, itemId))
        assertEquals("create-items", readGenesisAuthorityReceipt(db, physical.activationCommandId)?.kind)
        assertEquals("fictional-lantern", db.partyDao().allReferences().single().externalId)
        val exported = backup.exportWithProof()
        assertTrue(backup.previewRestore(exported.text).valid)
        val sync = CampaignSyncDomain(db).snapshot(campaign.id)
        assertTrue(sync.any { it.kind == "item" && it.recordId == itemId })
    }

    @Test fun repeatedImportPreservesItemIdentityRevisionAndCurrentResources() = runBlocking {
        val original = actor()
        val first = importer.commit(original, transfer(), choices, false, proof(), "fictional-first-import")
        val second = importer.commit(first.profile, transfer(), choices, false, proof(), "fictional-second-import")
        assertEquals(0, second.itemsAdded)
        assertEquals(1, second.itemsPreviouslyPresent)
        assertEquals(first.profile, second.profile)
        assertEquals(1, db.ledgerDao().allItems().size)
        assertEquals(1, second.profile.character.items.size)
    }

    @Test fun staleOrMissingBackupProofCannotCommitEvenWithUnchangedSelectedCharacter() = runBlocking {
        val original = actor()
        val stale = proof()
        CampaignLedgerRepository(db).create(LedgerDraft("Unrelated later item", source = "Fictional fixture"), "fictional-later-item")
        val before = backup.snapshot()
        rejected { importer.commit(original, transfer(), choices, false, stale, "fictional-stale-import") }
        rejected { importer.commit(original, transfer(), choices, false, "", "fictional-no-backup") }
        assertEquals(before, backup.snapshot())
    }

    @Test fun provisionalWalletUsesExplicitCampaignAndRepeatedImportDoesNotStackBalance() = runBlocking {
        val campaign = authority.createCampaign("Another expedition", commandId = "fictional-campaign-create")
        val original = actor(campaign.id)
        val first = importer.commit(original, transfer(), choices, true, proof(), "fictional-wallet-import")
        val wallet = db.partyDao().allWallets().single()
        assertEquals(campaign.id, wallet.campaignId)
        assertEquals(original.character.id, wallet.holderProfileId)
        assertEquals(20L, wallet.gold)
        assertNull(wallet.copper)
        assertNull(wallet.silver)
        assertTrue(wallet.name.contains("provisional"))
        assertTrue(wallet.source.contains("UNRECONCILED"))
        val second = importer.commit(first.profile, transfer(), choices, true, proof(), "fictional-wallet-repeat")
        assertFalse(second.walletAdded)
        assertTrue(second.walletPreviouslyPresent)
        assertEquals(listOf(wallet), db.partyDao().allWallets())
        assertTrue(backup.previewRestore(backup.exportWithProof().text).valid)
    }

    @Test fun unassignedWalletFailureRollsBackTheEntireImport() = runBlocking {
        val original = actor()
        val before = backup.snapshot()
        rejected { importer.commit(original, transfer(), choices, true, proof(), "fictional-unassigned-wallet") }
        assertEquals(before, backup.snapshot())
        assertTrue(db.ledgerDao().allItems().isEmpty())
    }

    @Test fun existingWalletFailureRollsBackItemAndStatsChanges() = runBlocking {
        val campaign = authority.createCampaign("Fictional expedition", commandId = "fictional-campaign-create")
        val original = actor(campaign.id)
        PartyRecordsRepository(db).saveWallet(PartyWallet("existing-wallet", original.character.id, "Existing purse",
            gold = 7, campaignId = campaign.id), source = "Fictional fixture", commandId = "fictional-existing-wallet")
        val before = backup.snapshot()
        rejected { importer.commit(original, transfer(), choices, true, proof(), "fictional-wallet-conflict") }
        assertEquals(before, backup.snapshot())
        assertEquals(7L, db.partyDao().allWallets().single().gold)
        assertTrue(db.ledgerDao().allItems().isEmpty())
    }

    @Test fun selectedCharacterMismatchRejectsBeforeAnySourceOrGameplayWrite() = runBlocking {
        val original = actor()
        val before = backup.snapshot()
        val other = transfer().let { it.copy(character = it.character.copy(name = "Different maker")) }
        rejected { importer.commit(original, other, choices, false, proof(), "fictional-wrong-subject") }
        assertEquals(before, backup.snapshot())
    }

    @Test fun exactRetryReturnsOriginalReceiptAfterLaterEditsAndChangedRequestCannotReuseCommand() = runBlocking {
        val original = actor()
        val frozenProof = proof()
        val first = importer.commit(original, transfer(), choices, false, frozenProof, "fictional-idempotent-import")
        val later = playable.save(first.profile.character.id, first.profile.revision,
            first.profile.character.copy(notes = "Later local gameplay note"), "Later note", "fictional-later-note")
        assertEquals(first, importer.commit(original, transfer(), choices, false, frozenProof, "fictional-idempotent-import"))
        val different = transfer().let { it.copy(inventory = listOf(it.inventory.single().copy(sourceId = "different-lantern", name = "Other lantern"))) }
        val before = backup.snapshot()
        rejected { importer.commit(later, different, choices, false, proof(), "fictional-idempotent-import") }
        assertEquals(before, backup.snapshot())
        assertEquals(later, playable.get(original.character.id))
        assertTrue(backup.previewRestore(backup.exportWithProof().text).valid)
    }

    @Test fun changedConstitutionCapacityDoesNotFillUnknownDamageOrHealStoredCurrentHp() = runBlocking {
        val original = actor(hpDamage = null)
        val effectFile = transfer().let { it.copy(inventory = listOf(it.inventory.single().copy(state = "Equipped",
            acceptedNumericEffects = mapOf("CON" to 16)))) }
        val result = importer.commit(original, effectFile, choices, false, proof(), "fictional-capacity-import")
        assertNull(result.profile.character.hpDamage)
        assertEquals(7, result.profile.character.currentHp)
        assertEquals(7, CharacterCalculator.calculate(result.profile.character).currentHp)
        assertEquals(21, CharacterCalculator.calculate(result.profile.character).maximumHp.value)
        assertEquals(7, db.recordsDao().characterProfile(original.character.id)?.currentHp)
        assertEquals(3, result.profile.character.temporaryHp)
        assertEquals(original.character.spellSlots, result.profile.character.spellSlots)
        assertTrue(backup.previewRestore(backup.exportWithProof().text).valid)
    }

    @Test fun historicalNotionSourceCannotBeDuplicatedUnderAnotherPhysicalIdentity() = runBlocking {
        val original = actor()
        CampaignLedgerRepository(db).create(LedgerDraft("Renamed source lantern", quantity = 1,
            notionPageId = "fictional-lantern", source = "Fictional earlier source"), "fictional-source-item", "original-physical-id")
        val before = backup.snapshot()
        rejected { importer.commit(original, transfer(), choices, false, proof(), "fictional-duplicate-source") }
        assertEquals(before, backup.snapshot())
        assertEquals(listOf("original-physical-id"), db.ledgerDao().allItems().map { it.id })
    }
}
