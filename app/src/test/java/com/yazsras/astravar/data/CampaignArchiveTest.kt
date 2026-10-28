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

    @Test fun unsupportedRelationshipCountsAreReadableAndLeaveTheDatabaseUntouched() = runBlocking {
        val perRecord = CAMPAIGN_JSON_MAX_CONTAINER_VALUES / 2 + 1
        val relationships = List(perRecord) { "fictional-target-$it" }
        val examples = listOf(
            payload(listOf(reference("fictional-wide", relations = relationships + relationships))),
            payload(listOf(reference("fictional-a", relations = relationships), reference("fictional-b", relations = relationships))))
        examples.forEach { incoming ->
            val preview = repo.archive.preview(archive(incoming))
            assertFalse(preview.valid)
            assertTrue(preview.conflicts.toString(), preview.conflicts.any { it.contains("limit") })
            assertTrue(db.archiveDao().allReferences().isEmpty())
            assertTrue(db.archiveDao().allRelations().isEmpty())
            assertTrue(db.archiveDao().allReceipts().isEmpty())
            assertTrue(db.archiveDao().allBackups().isEmpty())
            assertTrue(db.ledgerDao().allItems().isEmpty())
        }
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
    @Test fun duplicatePreviewStillReportsLocalConflictsAndHashesTheCompleteRetainedSource() = runBlocking {
        val incoming = payload(mapped = mapped())
        val receipt = import(incoming)
        val retainedSources = db.archiveDao().allSources()
        val before = repo.archive.preview(archive(incoming))
        assertTrue(before.conflicts.toString(), before.valid)
        assertTrue(before.summary.contains("Already imported"))
        val localBody = "Fictional player correction; café 星図 🧭 stays a local record."
        db.openHelper.writableDatabase.execSQL("UPDATE campaign_references SET body=?,contentSha256=? WHERE externalId=?",
            arrayOf(localBody, campaignHash(localBody), incoming.records.single().externalId))
        db.openHelper.writableDatabase.execSQL("UPDATE ledger_items SET description=? WHERE id=?",
            arrayOf("Fictional later equipment correction", incoming.mapped!!.items.single().id))
        val current = CampaignBackup(db).snapshot(includeBackups = false)
        val normalized = current.copy(exportedAt = 0, archive = current.archive.copy(receipts = emptyList(), backups = emptyList()))
        val expectedFingerprint = campaignHash(campaignJson.encodeToString(normalized))
        val duplicate = repo.archive.preview(archive(incoming))
        assertFalse(duplicate.valid)
        assertTrue(duplicate.summary.contains("Already imported"))
        assertTrue(duplicate.conflicts.toString(), duplicate.conflicts.any { it.contains("conflicts with a preserved local record") })
        assertTrue(duplicate.conflicts.toString(), duplicate.conflicts.any { it.contains("differs from local data") })
        assertEquals(expectedFingerprint, duplicate.currentFingerprint)
        assertNotEquals(before.currentFingerprint, duplicate.currentFingerprint)
        val invalid = repo.archive.preview("{}")
        assertFalse(invalid.valid)
        assertEquals(expectedFingerprint, invalid.currentFingerprint)
        assertEquals(retainedSources, db.archiveDao().allSources())
        assertEquals(receipt.packageHash, campaignHash(db.archiveDao().allSources().single().payloadJson))
        assertEquals(localBody, db.archiveDao().allReferences().single().body)
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
    @Test fun changingReviewedArchiveObjectsCannotBypassExactPayloadConfirmation() = runBlocking {
        val preview = repo.archive.preview(archive())
        repo.archive.exportPreImportBackup(preview.fingerprint)
        val changed = preview.copy(archive = requireNotNull(preview.archive).copy(title = "Different synthetic title"))
        try { repo.archive.commit(changed, true); fail("Changed reviewed content must fail") } catch (_: IllegalArgumentException) {}
        assertTrue(db.archiveDao().allSources().isEmpty())
        assertTrue(db.archiveDao().allReceipts().isEmpty())
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
    @Test fun legacyUnicodeRecoveryBytesRemainReusableForCommitAndRollback() = runBlocking {
        val prior = reference("fictional-prior", "Fictional café — 星図 🧭; an unresolved discovery stays unchanged.")
        val first = import(payload(listOf(prior)))
        val preview = repo.archive.preview(archive(payload(listOf(reference("fictional-later")))))
        assertTrue(preview.conflicts.toString(), preview.valid)
        val current = CampaignBackup(db).snapshot(includeBackups = false)
        val snapshot = current.copy(exportedAt = 0, archive = current.archive.copy(backups = emptyList()))
        // This is the exact pre-ASCII schema-four encoding used by previously saved recovery rows.
        val legacyText = campaignJson.encodeToString(snapshot)
        assertTrue(legacyText.contains("café — 星図 🧭"))
        db.archiveDao().insert(CampaignImportBackup(preview.fingerprint, legacyText, campaignHash(legacyText), preview.currentFingerprint, 1))
        assertEquals(legacyText, repo.archive.exportPreImportBackup(preview.fingerprint))
        val imported = repo.archive.commit(preview, true)
        assertTrue(repo.archive.verify(imported.packageHash).valid)
        repo.archive.rollback(imported.packageHash, true)
        assertEquals(prior.body, db.archiveDao().allReferences().single().body)
        assertEquals(legacyText, requireNotNull(db.archiveDao().backup(preview.fingerprint)).snapshotJson)
        assertTrue(repo.archive.verify(first.packageHash).valid)
    }
    @Test fun preparedBackupRemainsUsableAfterAnotherImportAndRollbackAddsOnlyReceipts() = runBlocking {
        val original = reference("fictional-original", "Fictional café 星図 🧭 history remains exact.")
        val first = import(payload(listOf(original)))
        val pending = payload(listOf(reference("fictional-pending")))
        val initialReview = repo.archive.preview(archive(pending))
        assertTrue(initialReview.conflicts.toString(), initialReview.valid)
        val savedText = repo.archive.exportPreImportBackup(initialReview.fingerprint)
        val savedRow = requireNotNull(db.archiveDao().backup(initialReview.fingerprint))
        val temporary = import(payload(listOf(reference("fictional-temporary"))))
        val temporaryRecovery = repo.archive.rollback(temporary.packageHash, true)
        assertEquals(listOf(original.externalId), db.archiveDao().allReferences().map { it.externalId })
        val receiptsBeforePending = db.archiveDao().allReceipts()
        assertEquals(setOf(first.packageHash, temporary.packageHash, temporaryRecovery.packageHash), receiptsBeforePending.map { it.packageHash }.toSet())
        val reviewedAgain = repo.archive.preview(archive(pending))
        assertTrue(reviewedAgain.conflicts.toString(), reviewedAgain.valid)
        assertEquals(initialReview.currentFingerprint, reviewedAgain.currentFingerprint)
        assertEquals(savedText, repo.archive.exportPreImportBackup(reviewedAgain.fingerprint))
        val applied = repo.archive.commit(reviewedAgain, true)
        assertTrue(repo.archive.verify(applied.packageHash).valid)
        assertEquals(savedRow, db.archiveDao().backup(reviewedAgain.fingerprint))
        repo.archive.rollback(applied.packageHash, true)
        assertEquals(original.body, db.archiveDao().allReferences().single().body)
        assertTrue(db.archiveDao().allReceipts().containsAll(receiptsBeforePending))
        assertTrue(repo.archive.verify(first.packageHash).valid)
    }
    @Test fun independentBackupByteReadbackRejectsSameLengthCorruptionBeforeCommit() = runBlocking {
        val preview = repo.archive.preview(archive(payload(mapped = mapped())))
        val original = repo.archive.exportPreImportBackup(preview.fingerprint)
        val corrupted = original.replace("astravar-campaign-items", "astravar-campaign-itfms")
        assertNotEquals(original, corrupted)
        assertEquals(original.length, corrupted.length)
        db.openHelper.writableDatabase.execSQL("UPDATE campaign_import_backups SET snapshotJson=? WHERE packageHash=?",
            arrayOf(corrupted, preview.fingerprint))
        try { repo.archive.commit(preview, true); fail("Stored backup byte corruption must block import") } catch (_: IllegalStateException) {}
        assertTrue(db.ledgerDao().allItems().isEmpty())
        assertTrue(db.partyDao().allWallets().isEmpty())
        assertTrue(db.archiveDao().allReferences().isEmpty())
        assertTrue(db.archiveDao().allReceipts().isEmpty())
        assertEquals(campaignHash(original), requireNotNull(db.archiveDao().backupMetadata(preview.fingerprint)).snapshotSha256)
    }
    @Test fun recoveryPreservesExistingBackupAndReceiptRowsWithoutDeletingThem() = runBlocking {
        import(payload(listOf(reference("fictional-prior", "Fictional café 星図 🧭 history."))))
        val second = import(payload(listOf(reference("fictional-later"))))
        val backups = db.archiveDao().allBackups()
        val receipts = db.archiveDao().allReceipts()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER preserve_backup_evidence BEFORE DELETE ON campaign_import_backups BEGIN SELECT RAISE(ABORT, 'Recovery backups are immutable'); END")
        sql.execSQL("CREATE TRIGGER preserve_receipt_evidence BEFORE DELETE ON campaign_import_receipts BEGIN SELECT RAISE(ABORT, 'Import receipts are immutable'); END")
        val recovery = repo.archive.rollback(second.packageHash, true)
        assertEquals(backups, db.archiveDao().allBackups())
        assertEquals(receipts, db.archiveDao().allReceipts().filterNot { it.packageHash == recovery.packageHash })
        assertEquals("fictional-prior", db.archiveDao().allReferences().single().externalId)
        assertEquals("Fictional café 星図 🧭 history.", db.archiveDao().allReferences().single().body)
    }
    @Test fun recoveryRefusesToDiscardSubsequentCampaignEdits() = runBlocking {
        val receipt = import()
        repo.create(LedgerDraft("Fictional later discovery"), "fictional-after-import")
        try { repo.archive.rollback(receipt.packageHash, true); fail("Later work must be protected") } catch (_: IllegalStateException) {}
        assertEquals(1, db.archiveDao().allReferences().size); assertEquals(1, db.ledgerDao().allItems().size)
    }
    @Test fun backupRequiresExplicitFormatAndSchemaDespiteSerializableDefaults() = runBlocking {
        val value = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList())
        val valid = campaignJson.encodeToString(value)
        listOf(valid.replace("\"format\":\"astravar-campaign-items\",", ""), valid.replace("\"schema\":5,", "")).forEach {
            val preview = repo.previewImport(it)
            assertFalse(preview.valid)
            assertTrue(preview.conflicts.any { error -> error.contains("declare its format and supported schema") })
        }
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
        assertEquals(5, snapshot.schema); assertEquals(2, snapshot.archive.references.size)
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
    @Test fun incomingNotionAliasBlocksAnOlderLocalItemWithAnotherTitleAndNoExternalReference() = runBlocking {
        val data = mapped()
        val item = data.items.single()
        val alias = "22222222222222222222222222222222"
        val incoming = data.copy(records = data.records.copy(externalReferences = listOf(
            ExternalRecordReference("fictional-alias", "notion", alias, "item", item.id, source = "Fictional source"))))
        assertTrue(repo.archive.preview(archive(payload(mapped = incoming))).valid)
        db.ledgerDao().insertItem(item.copy(id = "fictional-older-local", title = "Different older display title",
            notionPageId = "https://notion.so/Other-22222222-2222-2222-2222-222222222222"))
        val blocked = repo.archive.preview(archive(payload(mapped = incoming)))
        assertFalse(blocked.valid)
        assertTrue(blocked.conflicts.any { it.contains("Notion item identity") })
        assertEquals(listOf("fictional-older-local"), db.ledgerDao().allItems().map { it.id })
        assertTrue(db.archiveDao().allReceipts().isEmpty())
    }
    @Test fun preservedLocalNotionAliasBlocksAnIncomingPrimaryIdentityEvenWithAnotherTitle() = runBlocking {
        val data = mapped()
        val alias = "22222222222222222222222222222222"
        val local = data.items.single().copy(id = "fictional-existing-local", title = "Different older display title")
        db.ledgerDao().insertItem(local)
        db.partyDao().put(ExternalRecordReference("fictional-local-alias", "notion", alias, "item", local.id, source = "Fictional source"))
        val incoming = data.copy(items = listOf(data.items.single().copy(notionPageId = alias)))
        val blocked = repo.archive.preview(archive(payload(mapped = incoming)))
        assertFalse(blocked.valid)
        assertTrue(blocked.conflicts.any { it.contains("Notion item identity") })
        assertEquals(listOf(local.id), db.ledgerDao().allItems().map { it.id })
        assertTrue(db.archiveDao().allReceipts().isEmpty())
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
        val large = reference("fictional-large", "é".repeat(1_200_000)).copy(propertiesJson = "{\"fictional\":\"" + "b".repeat(1_100_000) + "\"}")
        val first = import(payload(records = listOf(large)))
        assertEquals(1, db.archiveDao().largeReferences().size)
        assertEquals(large.body, db.archiveDao().allReferences().single().body)
        assertTrue(requireNotNull(db.archiveDao().source(first.packageHash)).payloadJson.length > 2_000_000)
        val second = import(payload(records = listOf(reference("fictional-second"))))
        val backup = requireNotNull(db.archiveDao().backup(second.packageHash))
        assertTrue(backup.snapshotJson.length > 4_000_000)
        assertTrue(backup.snapshotJson.all { it.code < 128 })
        assertEquals(backup.snapshotSha256, campaignHash(backup.snapshotJson))
        repo.archive.rollback(second.packageHash, true)
        assertEquals(large.body, db.archiveDao().allReferences().single().body)
        assertEquals(large.propertiesJson, db.archiveDao().allReferences().single().propertiesJson)
        assertTrue(repo.archive.verify(first.packageHash).valid)
    }

    @Test fun oneTapConflictAuditVerifiesSavedImportWithoutChangingCampaignData() = runBlocking {
        import(payload(mapped = mapped()))
        val before = CampaignBackup(db).snapshot()
        val result = CampaignConflictVerifier(db).auditConflicts()
        assertEquals(1, result.activeImportCount)
        assertEquals(1, result.verifiedImportCount)
        assertEquals(0, result.blockedCount)
        assertEquals(0, result.reviewCount)
        assertTrue(result.noBlockingConflicts)
        assertTrue(result.noticeCount >= 1) // Original unknown source detail remains visible.
        assertFalse(result.shareableSummary().contains(reference().body))
        assertFalse(result.shareableSummary().contains("3 gold"))
        assertEquals(before, CampaignBackup(db).snapshot())
    }

    @Test fun oneTapAuditIdentifiesUnresolvedRelationsAndCorruptedReferenceHashes() = runBlocking {
        val item = reference("fictional-origin", relations = listOf("fictional-unknown"))
        import(payload(records = listOf(item)))
        val first = CampaignConflictVerifier(db).auditConflicts()
        assertEquals(0, first.blockedCount)
        assertTrue(first.reviewCount > 0)
        assertTrue(first.findings.any { it.area == "Relationships" && it.evidence.contains("fictional-unknown") })

        db.openHelper.writableDatabase.execSQL("UPDATE campaign_references SET body=? WHERE externalId=?",
            arrayOf("Fictional local alteration without its content hash", item.externalId))
        val before = CampaignBackup(db).snapshot()
        val damaged = CampaignConflictVerifier(db).auditConflicts()
        assertTrue(damaged.blockedCount > 0)
        assertTrue(damaged.findings.any { it.area == "Source integrity" })
        assertEquals(0, damaged.verifiedImportCount)
        assertEquals(before, CampaignBackup(db).snapshot())
    }

    @Test fun oneTapAuditFlagsAmbiguousItemAndWalletIdentitiesWithoutCombiningThem() = runBlocking {
        val data = mapped()
        val firstItem = data.items.single()
        db.ledgerDao().insertItem(firstItem)
        db.ledgerDao().insertItem(firstItem.copy(id = "fictional-other-object", notionPageId = "https://notion.so/Page-11111111-1111-1111-1111-111111111111"))
        db.recordsDao().put(data.records.characterProfiles.single())
        val firstWallet = data.records.wallets.single()
        db.partyDao().put(firstWallet)
        db.partyDao().put(firstWallet.copy(id = "fictional-other-wallet", name = "Fictional separate purse"))
        val snapshot = CampaignBackup(db).snapshot()
        val report = CampaignConflictVerifier(db).auditConflicts()
        assertTrue(report.findings.any { it.area == "Inventory identity" && it.level == CampaignConflictLevel.REVIEW })
        assertTrue(report.findings.any { it.area == "Currency" && it.level == CampaignConflictLevel.REVIEW })
        assertEquals(snapshot, CampaignBackup(db).snapshot())
        assertEquals(2, db.ledgerDao().allItems().size)
        assertEquals(2, db.partyDao().allWallets().size)
    }

    @Test fun oneTapAuditDetectsMissingImportEvidenceWithoutReplayingOrRepairingIt() = runBlocking {
        val receipt = import()
        db.openHelper.writableDatabase.execSQL("DELETE FROM campaign_archive_sources WHERE packageHash=?", arrayOf(receipt.packageHash))
        val before = CampaignBackup(db).snapshot()
        val report = CampaignConflictVerifier(db).auditConflicts()
        assertEquals(0, report.verifiedImportCount)
        assertTrue(report.findings.any { it.level == CampaignConflictLevel.BLOCKED && it.title.contains("source is missing") })
        assertEquals(before, CampaignBackup(db).snapshot())
        assertEquals(receipt, db.archiveDao().receipt(receipt.packageHash))
    }

}
