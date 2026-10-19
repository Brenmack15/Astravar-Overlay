package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Optional local-only source validation. Supply private paths via the environment, never a source fixture. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignArchivePrivatePreviewTest {
    @Test fun configuredPrivatePackagePassesReadOnlyAndroidRoomPreview() = runBlocking {
        val source = System.getenv("ASTRAVAR_PRIVATE_ARCHIVE_PREVIEW")
        val report = System.getenv("ASTRAVAR_PRIVATE_PREVIEW_REPORT")
        assumeTrue("No private source validation requested.", !source.isNullOrBlank() && !report.isNullOrBlank())
        val bytes = File(requireNotNull(source)).readBytes()
        require(bytes.size <= CAMPAIGN_BACKUP_MAX_CHARACTERS)
        val text = validatedCampaignUtf8(bytes)
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val preview = CampaignArchiveRepository(db).preview(text)
            if (!preview.valid) {
                File(requireNotNull(report)).writeText(buildJsonObject {
                    put("valid", false); put("conflicts", JsonArray(preview.conflicts.map(::JsonPrimitive)))
                    put("warnings", JsonArray(preview.warnings.map(::JsonPrimitive))); put("importPerformed", false)
                }.toString())
                assertTrue("Private archive preview failed; see the private report path.", preview.valid)
            }
            val archive = requireNotNull(preview.archive)
            val projectedReferences = archive.records.map { row -> CampaignReferenceRecord(
                "${archive.campaignId}::${row.externalId}", archive.campaignId, row.externalId, row.title, row.category,
                row.body, row.propertiesJson, row.sourceUrl, row.sourceRevision, row.contentSha256, preview.fingerprint) }
            val known = projectedReferences.map { it.id }.toSet()
            val projectedRelations = archive.records.flatMap { row -> row.relationIds.map { target ->
                val from = "${archive.campaignId}::${row.externalId}"; val to = "${archive.campaignId}::$target"
                CampaignReferenceRelation(campaignHash("$from\u0000$to"), from, to, resolved = to in known)
            } }.distinctBy { it.id }
            val empty = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList())
            val priorText = campaignBackupJson(empty)
            val projection = (archive.mapped ?: empty).copy(schema = 4, exportedAt = 0, archive = CampaignArchiveState(
                references = projectedReferences, relations = projectedRelations,
                sources = listOf(CampaignArchiveSource(preview.fingerprint, preview.payloadJson)),
                backups = listOf(CampaignImportBackup(preview.fingerprint, priorText, campaignHash(priorText), preview.currentFingerprint, 1)),
                receipts = listOf(CampaignImportReceipt(preview.fingerprint, archive.campaignId, archive.title, 1, projectedReferences.size,
                    projectedRelations.size, "PLANNED_ESTIMATE_ONLY", "0".repeat(64), preview.summary))))
            val projectedText = campaignBackupJson(projection)
            val projectedBytes = projectedText.toByteArray(Charsets.UTF_8).size
            assertTrue("Projected initial campaign backup exceeds the document-reader byte limit.", projectedBytes <= 32 * 1024 * 1024)
            assertTrue("Projected backup must round-trip the complete schema4 model.", campaignJson.decodeFromString<LedgerExport>(projectedText) == projection)
            val referenceRows = db.archiveDao().allReferences().size
            val itemRows = db.ledgerDao().allItems().size
            val receiptRows = db.archiveDao().allReceipts().size
            File(requireNotNull(report)).writeText(buildJsonObject {
                put("valid", preview.valid); put("fingerprint", preview.fingerprint)
                put("projectedInitialBackupBytes", projectedBytes); put("projectedInitialBackupCharacters", projectedText.length)
                put("projectionBasis", "Empty local campaign; bounded production backup codec only. No real import performed.")
                put("codec", "bounded-generated-serializer-v1")
                put("recordCount", preview.archive?.records?.size ?: 0)
                put("mappedItemCount", preview.archive?.mapped?.items?.size ?: 0)
                put("attachmentCount", preview.archive?.attachments?.size ?: 0)
                put("conflicts", JsonArray(preview.conflicts.map(::JsonPrimitive)))
                put("warnings", JsonArray(preview.warnings.map(::JsonPrimitive)))
                put("referenceRowsAfterPreview", referenceRows)
                put("itemRowsAfterPreview", itemRows)
                put("receiptRowsAfterPreview", receiptRows)
            }.toString())
            assertTrue("Private archive preview failed; see the private report path.", preview.valid)
            assertTrue("A preview must not write campaign rows.", db.archiveDao().allReferences().isEmpty() && db.ledgerDao().allItems().isEmpty() && db.archiveDao().allReceipts().isEmpty())
        } finally { db.close() }
    }
}
