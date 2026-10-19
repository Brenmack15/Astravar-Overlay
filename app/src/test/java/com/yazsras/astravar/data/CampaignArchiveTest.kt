package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/** Only fictional records. These tests never connect to Notion, a bridge, or the public sandbox. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignArchiveTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var repo: CampaignLedgerRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java)
            .allowMainThreadQueries().build()
        repo = CampaignLedgerRepository(db)
    }
    @After fun close() = db.close()
    private fun reference(id: String = "fictional-note", body: String = "Fictional historical scene; no rest or purchase occurs.", relations: List<String> = emptyList()) =
        CampaignArchiveRecord(id, "Fictional $id", "Chronicle", body, "{\"original\":null}", "https://example.invalid/$id", "fictional-revision-1", campaignHash(body), relations, "player")
    private fun payload(records: List<CampaignArchiveRecord> = listOf(reference()), mapped: LedgerExport? = null,
        attachments: List<CampaignArchiveAttachment> = emptyList()) = CampaignArchivePayload("fictional-campaign", "Fictional campaign", 1,
        "https://example.invalid/root", "fictional-root-revision-1", records, mapped, listOf("A fictional unresolved detail stays unknown."), attachments)
    private fun archive(value: CampaignArchivePayload = payload()): String {
        val json = campaignJson.encodeToString(value)
        return campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive", 1, campaignHash(json), json))
    }
    private suspend fun import(value: CampaignArchivePayload = payload()): CampaignImportReceipt {
        val preview = repo.archive.preview(archive(value))
        assertTrue(preview.conflicts.toString(), preview.valid)
        repo.archive.exportPreImportBackup(preview.fingerprint)
        return repo.archive.commit(preview, confirmed = true)
    }
    private fun mapped(): LedgerExport {
        val item = LedgerItem("fictional-lens", "Fictional unknown quantity lens", source = "Fictional source", notionPageId = "11111111111111111111111111111111", createdAt = 1, updatedAt = 1)
        val profile = CharacterProfile("Fictional actor", currentHp = null, maximumHp = 30, level = 6, id = "fictional-actor", source = "Fictional source", updatedAt = 1)
        val wallet = PartyWallet("fictional-wallet", profile.id, "Fictional purse", copper = null, silver = 2, gold = 3, source = "Fictional source", updatedAt = 1, campaignId = "fictional-campaign")
        val event = LedgerEvent("fictional-acquisition", item.id, "CREATE", "Confirmed", "Historical acquisition", "Fictional source", "fictional-fingerprint", 0, 1, 1,
            afterJson = campaignJson.encodeToString(item))
        return LedgerExport(exportedAt = 1, items = listOf(item), events = listOf(event), records = CampaignRecordsExport(
            characterProfiles = listOf(profile), wallets = listOf(wallet), projects = listOf(InventionProject("Fictional proposed invention", status = "Proposed", id = "fictional-proposal", source = "Fictional source")),
            checkpoints = listOf(CampaignCheckpoint("fictional-checkpoint", "Fictional unresolved checkpoint", unresolvedChoice = "Player has not decided.", source = "Fictional source"))))
    }

    @Test fun previewIsReadOnlyAndRealCommitRequiresConfirmationAndVerifiedBackup() = runBlocking {
        val preview = repo.archive.preview(archive())
        assertTrue(preview.valid)
        assertTrue(repo.archive.references.first().isEmpty())
        try { repo.archive.commit(preview, false); fail("Confirmation required") } catch (_: IllegalArgumentException) {}
        try { repo.archive.commit(preview, true); fail("Backup required") } catch (_: IllegalArgumentException) {}
        assertTrue(repo.archive.references.first().isEmpty())
        val backup = repo.archive.exportPreImportBackup(preview.fingerprint)
        assertTrue(backup.contains("astravar-campaign-items"))
        assertNotNull(db.archiveDao().backup(preview.fingerprint))
        repo.archive.commit(preview, true)
        assertEquals(1, repo.archive.references.first().size)
    }
    @Test fun completeImportPreservesUnknownsHistoryAndProposalsWithoutReplayingGameplay() = runBlocking {
        val mapped = mapped()
        val receipt = import(payload(listOf(reference("a", relations = listOf("b")), reference("b")), mapped))
        assertEquals(2, receipt.recordCount); assertEquals(1, receipt.relationCount)
        assertNull(db.ledgerDao().allItems().single().quantity)
        assertNull(db.recordsDao().allCharacterProfiles().single().currentHp)
        assertEquals(3L, db.partyDao().allWallets().single().gold)
        assertEquals("Proposed", db.recordsDao().allProjects().single().status)
        assertEquals(mapped.events, db.ledgerDao().allEvents())
        assertEquals(mapped.items, db.ledgerDao().allItems())
        assertEquals(1, db.ledgerDao().allItems().size) // proposed project did not become equipment
        assertTrue(repo.archive.verify(receipt.packageHash).valid)
    }
    @Test fun duplicateImportIsDurablyIdempotentAndDoesNotDoubleCountCurrency() = runBlocking {
        val incoming = payload(mapped = mapped())
        val a = import(incoming)
        val reopenedRepo = CampaignArchiveRepository(db)
        val preview = reopenedRepo.preview(archive(incoming))
        val b = reopenedRepo.commit(preview, true)
        assertEquals(a, b)
        assertEquals(1, db.ledgerDao().allItems().size)
        assertEquals(3L, db.partyDao().allWallets().single().gold)
        assertEquals(1, db.archiveDao().allReceipts().size)
    }
    @Test fun interruptedImportRollsBackAllRowsAndRetainsVerifiedRecoveryBackup() = runBlocking {
        val preview = repo.archive.preview(archive(payload(mapped = mapped())))
        repo.archive.exportPreImportBackup(preview.fingerprint)
        repo.archive.afterReferenceInsertForTest = { error("Fictional interrupted import") }
        try { repo.archive.commit(preview, true); fail("Interrupted transaction must fail") } catch (_: IllegalStateException) {}
        assertTrue(db.ledgerDao().allItems().isEmpty()); assertTrue(db.partyDao().allWallets().isEmpty())
        assertTrue(db.recordsDao().allCharacterProfiles().isEmpty()); assertTrue(db.archiveDao().allReferences().isEmpty())
        assertTrue(db.archiveDao().allReceipts().isEmpty()); assertNotNull(db.archiveDao().backup(preview.fingerprint))
        repo.archive.afterReferenceInsertForTest = null
        repo.archive.commit(preview, true)
        assertEquals(1, db.ledgerDao().allItems().size)
    }
    @Test fun changedLocalRecordBlocksImportAndDoesNotOverwrite() = runBlocking {
        val data = mapped(); val local = data.items.single().copy(description = "New local discovery")
        db.ledgerDao().insertItem(local)
        val preview = repo.archive.preview(archive(payload(mapped = data)))
        assertFalse(preview.valid); assertTrue(preview.conflicts.any { it.contains("differs from local") })
        assertEquals(local, db.ledgerDao().allItems().single()); assertTrue(db.archiveDao().allReferences().isEmpty())
    }
    @Test fun stalePreviewAndStaleBackupCannotImportOverLaterWork() = runBlocking {
        val preview = repo.archive.preview(archive())
        repo.archive.exportPreImportBackup(preview.fingerprint)
        repo.create(LedgerDraft("Fictional later item"), "fictional-later-command")
        try { repo.archive.commit(preview, true); fail("Stale preview must fail") } catch (_: IllegalStateException) {}
        assertTrue(db.archiveDao().allReferences().isEmpty()); assertEquals(1, db.ledgerDao().allItems().size)
    }
    @Test fun recoveryRestoresExactPreimportStateAndKeepsImmutableReceipts() = runBlocking {
        val before = repo.create(LedgerDraft("Fictional pre-existing equipment", quantity = null), "fictional-existing")
        val receipt = import(payload(mapped = mapped()))
        val recovery = repo.archive.rollback(receipt.packageHash, true)
        assertEquals("ROLLED_BACK", recovery.state)
        assertEquals(listOf(before), db.ledgerDao().allItems())
        assertTrue(db.archiveDao().allReferences().isEmpty()); assertTrue(db.partyDao().allWallets().isEmpty())
        assertEquals(2, db.archiveDao().allReceipts().size)
        assertEquals(recovery, repo.archive.rollback(receipt.packageHash, true))
        assertFalse(repo.archive.preview(archive(payload(mapped = mapped()))).valid)
    }
    @Test fun recoveryRefusesToDiscardSubsequentCampaignEdits() = runBlocking {
        val receipt = import()
        repo.create(LedgerDraft("Fictional later discovery"), "fictional-after-import")
        try { repo.archive.rollback(receipt.packageHash, true); fail("Later work must be protected") } catch (_: IllegalStateException) {}
        assertEquals(1, db.archiveDao().allReferences().size); assertEquals(1, db.ledgerDao().allItems().size)
    }
    @Test fun malformedUnsupportedTamperedAndDuplicateJsonAreRejectedReadOnly() = runBlocking {
        val valid = archive()
        val invalids = listOf("{}", valid.replace("\"schema\":1", "\"schema\":99"), valid.replace("Fictional campaign", "Tampered campaign"),
            valid.replaceFirst("{", "{\"schema\":1,"), valid.replace("\"schema\":1", "\"schema\":1,\"future\":true"))
        invalids.forEach { assertFalse(it.take(100), repo.archive.preview(it).valid) }
        val badBody = payload(records = listOf(reference().copy(contentSha256 = "0".repeat(64))))
        assertFalse(repo.archive.preview(archive(badBody)).valid)
        assertTrue(db.archiveDao().allReferences().isEmpty())
    }
    @Test fun missingRelationsRemainExplicitUnresolvedAndHistoryIsNeverDiscarded() = runBlocking {
        val incoming = payload(listOf(reference(relations = listOf("fictional-missing"))))
        val preview = repo.archive.preview(archive(incoming))
        assertTrue(preview.valid); assertTrue(preview.warnings.any { it.contains("Unresolved relationship") })
        val receipt = import(incoming)
        assertFalse(db.archiveDao().allRelations().single().resolved)
        assertEquals(incoming.records.single().body, db.archiveDao().allReferences().single().body)
        assertTrue(repo.archive.verify(receipt.packageHash).valid)
    }
    @Test fun nonPlayerVisibleRecordsAreRejectedInsteadOfLeakingSpoilers() = runBlocking {
        val preview = repo.archive.preview(archive(payload(records = listOf(reference().copy(visibility = "dm-only")))))
        assertFalse(preview.valid); assertTrue(preview.conflicts.any { it.contains("Non-player-visible") })
        assertTrue(db.archiveDao().allSources().isEmpty())
    }
    @Test fun normalSchemaFourBackupRetainsFullReferenceContentSourceAndRecoveryReceipts() = runBlocking {
        val receipt = import(payload(listOf(reference("a", relations = listOf("b")), reference("b")), mapped()))
        val exported = repo.export()
        val snapshot = campaignJson.decodeFromString<LedgerExport>(exported)
        assertEquals(4, snapshot.schema); assertEquals(2, snapshot.archive.references.size)
        assertEquals(1, snapshot.archive.backups.size); assertEquals(1, snapshot.archive.sources.size)
        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val target = CampaignLedgerRepository(other)
            val preview = target.previewImport(exported)
            assertTrue(preview.conflicts.toString(), preview.valid)
            target.restore(preview, "fictional-restore")
            assertEquals(snapshot.archive.references, other.archiveDao().allReferences())
            assertEquals(snapshot.archive.sources, other.archiveDao().allSources())
            assertEquals(receipt, other.archiveDao().receipt(receipt.packageHash))
        } finally { other.close() }
    }
    @Test fun attachmentsAreEmbeddedWithDigestOrExplicitlyExternalNeverSilentlyDropped() = runBlocking {
        val bytes = "Fictional attachment bytes".toByteArray()
        val attachment = CampaignArchiveAttachment("fictional-attachment", "fictional.txt", "text/plain", sha256 = campaignHash(String(bytes)),
            base64 = Base64.getEncoder().encodeToString(bytes), status = "embedded")
        val incoming = payload(attachments = listOf(attachment, CampaignArchiveAttachment("fictional-url", "external.pdf", sourceUrl = "https://example.invalid/external.pdf")))
        val receipt = import(incoming)
        assertArrayEquals(bytes, repo.archive.attachmentBytes(receipt.packageHash, attachment.id))
        assertEquals(incoming.attachments, repo.archive.sourceArchive(receipt.packageHash)?.attachments)
        val tampered = incoming.copy(attachments = listOf(attachment.copy(sha256 = "0".repeat(64))))
        assertFalse(repo.archive.preview(archive(tampered)).valid)
    }
    @Test fun sameNotionIdentityAndPotentialDuplicatePhysicalItemsOrWalletsAreBlocked() = runBlocking {
        val data = mapped()
        db.ledgerDao().insertItem(data.items.single().copy(id = "fictional-local-id", title = "Different display title", notionPageId = "https://notion.so/Page-11111111-1111-1111-1111-111111111111"))
        val duplicate = repo.archive.preview(archive(payload(mapped = data)))
        assertFalse(duplicate.valid); assertTrue(duplicate.conflicts.any { it.contains("Notion item identity") })
    }
    @Test fun canonicalNotionIdentityHandlesCompactDashedAndHexEndingSlugs() {
        val id = "11111111111111111111111111111111"
        assertEquals(id, normalizedNotionIdentity(id))
        assertEquals(id, normalizedNotionIdentity("11111111-1111-1111-1111-111111111111"))
        assertEquals(id, normalizedNotionIdentity("https://notion.so/Page$id?pvs=4#heading"))
        assertEquals(id, normalizedNotionIdentity("https://notion.so/Page-11111111-1111-1111-1111-111111111111"))
    }
    @Test fun possiblePhysicalDuplicateAndWalletSnapshotRequireIdentityReconciliation() = runBlocking {
        val data = mapped()
        db.ledgerDao().insertItem(data.items.single().copy(id = "fictional-existing-stack", notionPageId = null))
        val duplicateItem = repo.archive.preview(archive(payload(mapped = data)))
        assertFalse(duplicateItem.valid)
        assertTrue(duplicateItem.conflicts.any { it.contains("Possible duplicate physical item") })
        db.recordsDao().put(data.records.characterProfiles.single())
        db.partyDao().put(data.records.wallets.single().copy(id = "fictional-existing-purse", name = "Other fictional purse label"))
        val duplicateWallet = repo.archive.preview(archive(payload(mapped = data.copy(items = emptyList(), events = emptyList()))))
        assertFalse(duplicateWallet.valid)
        assertTrue(duplicateWallet.conflicts.any { it.contains("Possible duplicate wallet snapshot") })
    }
    @Test fun independentReadbackDetectsAbsentMappedRowsRatherThanInferringTheirExistence() = runBlocking {
        val receipt = import(payload(mapped = mapped()))
        db.openHelper.writableDatabase.execSQL("DELETE FROM party_wallets")
        val result = repo.archive.verify(receipt.packageHash)
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("wallets record") && it.contains("missing") })
    }

    @Test fun oversizedReferenceSourceAndRecoverySnapshotsUseBoundedBlobChunks() = runBlocking {
        val large = reference("fictional-large", "a".repeat(1_200_000)).copy(propertiesJson = "{\"fictional\":\"" + "b".repeat(1_100_000) + "\"}")
        val first = import(payload(records = listOf(large)))
        assertEquals(1, db.archiveDao().largeReferences().size)
        assertEquals(large.body, db.archiveDao().allReferences().single().body)
        assertTrue(requireNotNull(db.archiveDao().source(first.packageHash)).payloadJson.length > 2_000_000)
        val second = import(payload(records = listOf(reference("fictional-second"))))
        val backup = requireNotNull(db.archiveDao().backup(second.packageHash))
        assertTrue(backup.snapshotJson.length > 4_000_000)
        assertEquals(backup.snapshotSha256, campaignHash(backup.snapshotJson))
        repo.archive.rollback(second.packageHash, true)
        assertEquals(large.propertiesJson, db.archiveDao().allReferences().single().propertiesJson)
        assertTrue(repo.archive.verify(first.packageHash).valid)
    }

}
