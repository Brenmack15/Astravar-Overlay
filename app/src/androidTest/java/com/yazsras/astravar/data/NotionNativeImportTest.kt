package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production Context wiring on a disposable emulator; never run against owner data. */
@RunWith(AndroidJUnit4::class)
class NotionNativeImportTest {
    @Test fun publicContextApplyBackupReimportAndRollbackPreserveIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        assertEquals("1", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("getprop ro.kernel.qemu").trim())
        val started = System.nanoTime()
        val suffix = UUID.randomUUID().toString().take(8)
        val storage = GenesisApplicationStorage.open(context)
        val importer = NotionTransferRepository(context)
        val campaign = storage.authority.createCampaign("Synthetic Notion campaign $suffix", commandId = "notion-campaign-$suffix")
        val original = requireNotNull(storage.authority.createActor("Synthetic maker $suffix", GenesisActorRole.PLAYER,
            campaign.id, playable = ReforgeCharacter("native-notion-maker-$suffix", "Synthetic maker $suffix",
                currentHp = 7, baseMaxHp = 20, baseAbilities = Stat.entries.associateWith { 14 },
                baselineConstitutionModifier = 2, temporaryHp = 3, hpDamage = 13, spellSlots = mapOf(1 to 1)),
            commandId = "notion-actor-$suffix").playable)
        val source = "https://app.notion.com/p/33333333333333333333333333333333"
        val transfer = NotionCharacterTransfer(NOTION_CHARACTER_TRANSFER_FORMAT, "native-notion-transfer-$suffix", "2026-11-21",
            NotionCharacterFacts(original.character.name, "Maker", source, 1, "Gnome", "Artificer", "Armorer",
                Stat.entries.associateWith { 10 }),
            listOf(NotionInventoryFact("native-lantern-$suffix", source, "Synthetic lantern", "Equipment", "Maker",
                "Carried", "Confirmed", 1, knownProperties = "Synthetic accepted provenance; no inferred magic.")),
            listOf(NotionCurrencyFact("native-currency-$suffix", "Synthetic estimate", 20, "Confirmed", source)), 20)
        val choices = NotionTransferChoices(stats = false)
        val stale = storage.backup.exportWithProof()
        assertTrue(storage.backup.previewRestore(stale.text).valid)
        val wallet = PartyWallet("native-wallet-$suffix", original.character.id, "Existing purse", gold = 7, campaignId = campaign.id)
        PartyRecordsRepository(storage.db).saveWallet(wallet, source = "Synthetic existing balance", commandId = "notion-wallet-$suffix")
        val before = storage.backup.snapshot()
        assertTrue(runCatching { importer.commit(original, transfer, choices, false,
            stale.currentFingerprint, "notion-stale-$suffix") }.isFailure)
        assertEquals(before, storage.backup.snapshot())
        val proof = storage.backup.exportWithProof().currentFingerprint
        // Wallet rejection follows item/reference/playable writes, so it tests the entire transaction.
        assertTrue(runCatching { importer.commit(original, transfer, choices, true,
            proof, "notion-wallet-reject-$suffix") }.isFailure)
        assertEquals("Late failure rolls back every store and immutable receipt", before, storage.backup.snapshot())
        val command = "notion-success-$suffix"
        val first = withTimeout(30_000) { importer.commit(original, transfer, choices, false, proof, command) }
        assertEquals(1, first.itemsAdded)
        assertEquals(original.character.currentHp, first.profile.character.currentHp)
        assertEquals(original.character.hpDamage, first.profile.character.hpDamage)
        assertEquals(original.character.temporaryHp, first.profile.character.temporaryHp)
        assertEquals(original.character.spellSlots, first.profile.character.spellSlots)
        assertEquals(original.character.baseAbilities, first.profile.character.baseAbilities)
        assertEquals(7L, storage.db.partyDao().allWallets().single { it.id == wallet.id }.gold)
        assertEquals(first, importer.commit(original, transfer, choices, false, proof, command))
        val second = withTimeout(30_000) { importer.commit(first.profile, transfer, choices, false,
            storage.backup.exportWithProof().currentFingerprint, "notion-repeat-$suffix") }
        assertEquals(0, second.itemsAdded)
        assertEquals(1, second.itemsPreviouslyPresent)
        assertEquals(first.profile, second.profile)
        val itemId = "notion-instance-native-lantern-$suffix"
        assertEquals(1, storage.db.ledgerDao().allItems().count { it.id == itemId })
        assertEquals(1, storage.db.partyDao().allReferences().count { it.recordId == itemId })
        assertEquals(original.character.id, storage.db.partyDao().placement(itemId)?.holderProfileId)
        assertTrue(requireNotNull(storage.db.ledgerDao().getItem(itemId)).description.contains(source))

        // Inject a native SQLite failure at the final parent receipt, after all nested writes.
        val failedCommand = "notion-final-rollback-$suffix"
        val next = transfer.copy(transferId = "native-next-$suffix", inventory = listOf(transfer.inventory.single()
            .copy(sourceId = "native-next-item-$suffix", name = "Synthetic second lantern")))
        val beforeFault = storage.backup.snapshot()
        val faultProof = storage.backup.exportWithProof().currentFingerprint
        storage.db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER native_notion_abort BEFORE INSERT ON genesis_authority_receipts WHEN NEW.commandId='$failedCommand' BEGIN SELECT RAISE(ABORT, 'Synthetic final receipt interruption'); END")
        try {
            assertTrue(runCatching { importer.commit(second.profile, next, choices, false, faultProof, failedCommand) }.isFailure)
        } finally {
            storage.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS native_notion_abort")
        }
        assertEquals(beforeFault, storage.backup.snapshot())
        assertNull(storage.db.ledgerDao().getItem("notion-instance-native-next-item-$suffix"))
        assertNull(storage.db.genesisAuthorityDao().receipt(failedCommand))
        // The identical failed command can succeed after the transient storage fault clears.
        val recovered = importer.commit(second.profile, next, choices, false, faultProof, failedCommand)
        assertEquals(1, recovered.itemsAdded)
        assertEquals(recovered, importer.commit(second.profile, next, choices, false, faultProof, failedCommand))

        val exported = storage.backup.exportWithProof()
        assertTrue(storage.backup.previewRestore(exported.text).valid)
        val restoreName = "native-notion-restore-$suffix.sqlite"
        var restored = Room.databaseBuilder(context, CampaignLedgerDb::class.java, restoreName).build()
        try {
            val backup = GenesisAuthorityBackupRepository(restored)
            val preview = backup.previewRestore(exported.text)
            assertTrue(preview.valid)
            backup.restore(preview, "notion-independent-restore-$suffix")
            assertEquals(recovered.profile, ReforgeRepository(restored).get(original.character.id))
            assertEquals(storage.db.ledgerDao().getItem(itemId), restored.ledgerDao().getItem(itemId))
            assertEquals(storage.db.partyDao().placement(itemId), restored.partyDao().placement(itemId))
            assertEquals(storage.db.genesisAuthorityDao().receipt(command), restored.genesisAuthorityDao().receipt(command))
            assertTrue(backup.previewRestore(backup.exportWithProof().text).valid)
            restored.close()
            restored = Room.databaseBuilder(context, CampaignLedgerDb::class.java, restoreName).build()
            assertEquals(recovered.profile, ReforgeRepository(restored).get(original.character.id))
            assertEquals(7L, restored.partyDao().allWallets().single { it.id == wallet.id }.gold)
        } finally { restored.close() }
        File(context.getExternalFilesDir(null), "notion-native-import.json").writeText(
            """{"syntheticOnly":true,"publicContextConstructor":true,"backupValidated":true,"repeatIdempotent":true,"lateFailureAtomic":true,"retryRecovered":true,"independentRestoreReopened":true,"durationMs":${(System.nanoTime() - started) / 1_000_000}}""")
    }
}
