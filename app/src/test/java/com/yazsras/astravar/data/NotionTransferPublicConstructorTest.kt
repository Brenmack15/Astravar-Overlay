package com.yazsras.astravar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The installed app's constructor, with only fictional records in Robolectric's app sandbox. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class NotionTransferPublicConstructorTest {
    private val singletonField = GenesisApplicationStorage::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousStorage: Any? = null
    private var fixtureStorage: GenesisApplicationStorage? = null

    @Before fun isolateCanonicalStorage() {
        // Robolectric reuses sandbox classloaders. Do not retain this test's DB in an app singleton.
        previousStorage = singletonField.get(null)
        singletonField.set(null, null)
    }

    @After fun restoreCanonicalStorage(): Unit = runBlocking {
        try {
            fixtureStorage?.let { storage ->
                val scopeField = GenesisApplicationStorage::class.java.getDeclaredField("scope").apply { isAccessible = true }
                val scope = scopeField.get(storage) as CoroutineScope
                scope.coroutineContext[Job]?.cancelAndJoin()
                storage.db.close()
            }
        } finally { singletonField.set(null, previousStorage) }
    }

    @Test fun publicImportUsesOneTransactionForBackupRollbackAndRepeatedApply() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = GenesisApplicationStorage.open(context).also { fixtureStorage = it }
        val importer = NotionTransferRepository(context)
        val dbField = NotionTransferRepository::class.java.getDeclaredField("db").apply { isAccessible = true }
        assertSame("Public constructor must join the canonical Room transaction", storage.db, dbField.get(importer))
        val campaign = storage.authority.createCampaign("Synthetic import campaign", commandId = "constructor-campaign")
        val original = requireNotNull(storage.authority.createActor("Synthetic maker", GenesisActorRole.PLAYER,
            campaign.id, playable = ReforgeCharacter("constructor-maker", "Synthetic maker", currentHp = 7,
                baseMaxHp = 20, baseAbilities = Stat.entries.associateWith { 14 }, baselineConstitutionModifier = 2,
                temporaryHp = 3, hpDamage = 13, spellSlots = mapOf(1 to 1)), commandId = "constructor-actor").playable)
        val source = "https://app.notion.com/p/22222222222222222222222222222222"
        val transfer = NotionCharacterTransfer(NOTION_CHARACTER_TRANSFER_FORMAT, "constructor-transfer", "2026-11-21",
            NotionCharacterFacts("Synthetic maker", "Maker", source, 1, "Gnome", "Artificer", "Armorer", Stat.entries.associateWith { 10 }),
            listOf(NotionInventoryFact("constructor-lantern", source, "Synthetic lantern", "Equipment", "Maker",
                "Carried", "Confirmed", 1, knownProperties = "Synthetic provenance")),
            listOf(NotionCurrencyFact("constructor-currency", "Synthetic estimate", 20, "Confirmed", source)), 20)
        val choices = NotionTransferChoices(stats = false)
        val initial = storage.backup.exportWithProof()
        assertTrue(storage.backup.previewRestore(initial.text).valid)
        PartyRecordsRepository(storage.db).saveWallet(PartyWallet("constructor-wallet", original.character.id,
            "Existing purse", gold = 7, campaignId = campaign.id), source = "Synthetic fixture", commandId = "constructor-wallet-create")
        val before = storage.backup.snapshot()
        assertNotNull(runCatching { importer.commit(original, transfer, choices, false,
            initial.currentFingerprint, "constructor-stale-proof") }.exceptionOrNull())
        assertEquals(before, storage.backup.snapshot())
        val proof = storage.backup.exportWithProof().currentFingerprint
        // Wallet conflict is checked after item/reference/sheet writes: they must all roll back.
        assertNotNull(runCatching { importer.commit(original, transfer, choices, true,
            proof, "constructor-wallet-conflict") }.exceptionOrNull())
        assertEquals(before, storage.backup.snapshot())
        assertEquals(original, storage.playable.get(original.character.id))
        val first = importer.commit(original, transfer, choices, false, proof, "constructor-first-import")
        assertEquals(1, first.itemsAdded)
        assertEquals(7, first.profile.character.currentHp)
        assertEquals(3, first.profile.character.temporaryHp)
        assertEquals(13, first.profile.character.hpDamage)
        assertEquals(original.character.spellSlots, first.profile.character.spellSlots)
        assertEquals(7L, storage.db.partyDao().allWallets().single().gold)
        assertEquals(first, importer.commit(original, transfer, choices, false, proof, "constructor-first-import"))
        val second = importer.commit(first.profile, transfer, choices, false,
            storage.backup.exportWithProof().currentFingerprint, "constructor-repeat-import")
        assertEquals(0, second.itemsAdded)
        assertEquals(1, second.itemsPreviouslyPresent)
        assertEquals(first.profile, second.profile)
        assertEquals(1, storage.db.ledgerDao().allItems().size)
        assertEquals(1, storage.db.partyDao().allReferences().size)
        assertTrue(storage.backup.previewRestore(storage.backup.exportWithProof().text).valid)
    }
}
