package com.yazsras.astravar.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

/** A complete local archive. The digest covers the exact UTF-8 payload bytes, not reserialized JSON. */
@Serializable data class CampaignArchiveEnvelope(
    val format: String, val schema: Int, val payloadSha256: String, val payload: String
)
@Serializable data class CampaignArchiveRecord(
    val externalId: String, val title: String, val category: String, val body: String,
    val propertiesJson: String = "{}", val sourceUrl: String, val sourceRevision: String,
    val contentSha256: String, val relationIds: List<String> = emptyList(), val visibility: String
)
@Serializable data class CampaignArchiveAttachment(
    val id: String, val fileName: String, val mimeType: String = "application/octet-stream", val sourceUrl: String = "",
    val sha256: String? = null, val base64: String? = null, val status: String = "external-reference"
)
@Serializable data class CampaignArchivePayload(
    val campaignId: String, val title: String, val exportedAt: Long, val sourceRootUrl: String,
    val sourceRootRevision: String, val records: List<CampaignArchiveRecord>, val mapped: LedgerExport? = null,
    val issues: List<String> = emptyList(), val attachments: List<CampaignArchiveAttachment> = emptyList()
)
@Serializable @Entity(tableName = "campaign_references", indices = [Index("campaignId"), Index("category")])
data class CampaignReferenceRecord(
    @PrimaryKey val id: String, val campaignId: String, val externalId: String, val title: String,
    val category: String, val body: String, val propertiesJson: String, val sourceUrl: String,
    val sourceRevision: String, val contentSha256: String, val packageHash: String
)
@Serializable @Entity(tableName = "campaign_reference_relations", indices = [Index("fromId"), Index("toId")])
data class CampaignReferenceRelation(
    @PrimaryKey val id: String, val fromId: String, val toId: String, val kind: String = "notion-relation",
    val resolved: Boolean = true
)
/** Immutable application receipt. A separate rollback receipt points back to the original package. */
@Serializable @Entity(tableName = "campaign_import_receipts")
data class CampaignImportReceipt(
    @PrimaryKey val packageHash: String, val campaignId: String, val title: String, val importedAt: Long,
    val recordCount: Int, val relationCount: Int, val state: String, val readbackSha256: String, val summary: String
)
@Serializable @Entity(tableName = "campaign_import_backups")
data class CampaignImportBackup(
    @PrimaryKey val packageHash: String, val snapshotJson: String, val snapshotSha256: String,
    val sourceStateSha256: String, val createdAt: Long
)
@Serializable @Entity(tableName = "campaign_archive_sources")
data class CampaignArchiveSource(@PrimaryKey val packageHash: String, val payloadJson: String)
@Serializable data class CampaignArchiveState(
    val references: List<CampaignReferenceRecord> = emptyList(), val relations: List<CampaignReferenceRelation> = emptyList(),
    val receipts: List<CampaignImportReceipt> = emptyList(), val backups: List<CampaignImportBackup> = emptyList(),
    val sources: List<CampaignArchiveSource> = emptyList()
)
data class CampaignArchivePreview(
    val valid: Boolean, val summary: String, val conflicts: List<String>, val warnings: List<String>,
    val fingerprint: String, val currentFingerprint: String, val archive: CampaignArchivePayload? = null,
    internal val payloadJson: String = "", internal val archiveFingerprint: String = ""
)
data class CampaignArchiveVerification(val valid: Boolean, val summary: String, val errors: List<String>)

/** Cursor projections deliberately exclude potentially multi-megabyte TEXT columns. */
data class CampaignReferenceMetadata(
    val id: String, val campaignId: String, val externalId: String, val title: String, val category: String,
    val sourceUrl: String, val sourceRevision: String, val contentSha256: String, val packageHash: String,
    val bodyBytes: Long, val propertiesBytes: Long
)
data class CampaignBackupMetadata(
    val packageHash: String, val snapshotSha256: String, val sourceStateSha256: String, val createdAt: Long, val snapshotBytes: Long
)

/** Substring BLOB reads bypass Android's per-row CursorWindow limit while retaining exact UTF-8. */
private suspend fun readArchiveText(size: Long, chunk: suspend (Long, Int) -> ByteArray?): String {
    require(size in 0..128_000_000L) { "Stored archive exceeds the safe recovery text limit." }
    val output = ByteArray(size.toInt())
    var offset = 1L
    while (offset <= size) {
        val count = minOf(128 * 1024L, size - offset + 1).toInt()
        val bytes = requireNotNull(chunk(offset, count)) { "Stored archive text is missing." }
        check(bytes.size == count) { "Stored archive was truncated during readback." }
        bytes.copyInto(output, (offset - 1).toInt()); offset += count
    }
    return validatedCampaignUtf8(output)
}

@Dao interface CampaignArchiveDao {
    @Query("SELECT id FROM campaign_references ORDER BY id") fun referenceIds(): Flow<List<String>>
    @Query("SELECT * FROM campaign_references WHERE length(CAST(body AS BLOB))+length(CAST(propertiesJson AS BLOB))<=1000000 ORDER BY id")
    suspend fun smallReferences(): List<CampaignReferenceRecord>
    @Query("SELECT id,campaignId,externalId,title,category,sourceUrl,sourceRevision,contentSha256,packageHash,length(CAST(body AS BLOB)) AS bodyBytes,length(CAST(propertiesJson AS BLOB)) AS propertiesBytes FROM campaign_references WHERE length(CAST(body AS BLOB))+length(CAST(propertiesJson AS BLOB))>1000000 ORDER BY id")
    suspend fun largeReferences(): List<CampaignReferenceMetadata>
    @Query("SELECT substr(CAST(body AS BLOB),:offset,:size) FROM campaign_references WHERE id=:id")
    suspend fun referenceBodyChunk(id: String, offset: Long, size: Int): ByteArray?
    @Query("SELECT substr(CAST(propertiesJson AS BLOB),:offset,:size) FROM campaign_references WHERE id=:id")
    suspend fun referencePropertiesChunk(id: String, offset: Long, size: Int): ByteArray?
    @Transaction suspend fun allReferences(): List<CampaignReferenceRecord> {
        val rows = smallReferences().toMutableList()
        largeReferences().forEach { row ->
            val body = readArchiveText(row.bodyBytes) { offset, size -> referenceBodyChunk(row.id, offset, size) }
            val properties = readArchiveText(row.propertiesBytes) { offset, size -> referencePropertiesChunk(row.id, offset, size) }
            rows += CampaignReferenceRecord(row.id, row.campaignId, row.externalId, row.title, row.category, body, properties,
                row.sourceUrl, row.sourceRevision, row.contentSha256, row.packageHash)
        }
        return rows.sortedBy { it.id }
    }
    @Query("SELECT * FROM campaign_reference_relations ORDER BY id") fun relations(): Flow<List<CampaignReferenceRelation>>
    @Query("SELECT * FROM campaign_reference_relations ORDER BY id") suspend fun allRelations(): List<CampaignReferenceRelation>
    @Query("SELECT * FROM campaign_import_receipts ORDER BY importedAt DESC,packageHash") fun receipts(): Flow<List<CampaignImportReceipt>>
    @Query("SELECT * FROM campaign_import_receipts ORDER BY packageHash") suspend fun allReceipts(): List<CampaignImportReceipt>
    @Query("SELECT * FROM campaign_import_receipts WHERE packageHash=:hash") suspend fun receipt(hash: String): CampaignImportReceipt?
    @Query("SELECT packageHash,snapshotSha256,sourceStateSha256,createdAt,length(CAST(snapshotJson AS BLOB)) AS snapshotBytes FROM campaign_import_backups ORDER BY packageHash")
    suspend fun backupMetadata(): List<CampaignBackupMetadata>
    @Query("SELECT packageHash,snapshotSha256,sourceStateSha256,createdAt,length(CAST(snapshotJson AS BLOB)) AS snapshotBytes FROM campaign_import_backups WHERE packageHash=:hash")
    suspend fun backupMetadata(hash: String): CampaignBackupMetadata?
    @Query("SELECT substr(CAST(snapshotJson AS BLOB),:offset,:size) FROM campaign_import_backups WHERE packageHash=:hash")
    suspend fun backupChunk(hash: String, offset: Long, size: Int): ByteArray?
    @Transaction suspend fun backup(hash: String): CampaignImportBackup? {
        val meta = backupMetadata(hash) ?: return null
        val text = readArchiveText(meta.snapshotBytes) { offset, size -> backupChunk(hash, offset, size) }
        return CampaignImportBackup(meta.packageHash, text, meta.snapshotSha256, meta.sourceStateSha256, meta.createdAt)
    }
    @Transaction suspend fun allBackups(): List<CampaignImportBackup> = backupMetadata().map { meta ->
        val text = readArchiveText(meta.snapshotBytes) { offset, size -> backupChunk(meta.packageHash, offset, size) }
        CampaignImportBackup(meta.packageHash, text, meta.snapshotSha256, meta.sourceStateSha256, meta.createdAt)
    }
    @Query("SELECT packageHash FROM campaign_archive_sources ORDER BY packageHash") suspend fun sourceHashes(): List<String>
    @Query("SELECT length(CAST(payloadJson AS BLOB)) FROM campaign_archive_sources WHERE packageHash=:hash") suspend fun sourceBytes(hash: String): Long?
    @Query("SELECT substr(CAST(payloadJson AS BLOB),:offset,:size) FROM campaign_archive_sources WHERE packageHash=:hash")
    suspend fun sourceChunk(hash: String, offset: Long, size: Int): ByteArray?
    @Transaction suspend fun source(hash: String): CampaignArchiveSource? {
        val size = sourceBytes(hash) ?: return null
        return CampaignArchiveSource(hash, readArchiveText(size) { offset, count -> sourceChunk(hash, offset, count) })
    }
    @Transaction suspend fun allSources(): List<CampaignArchiveSource> = sourceHashes().map { hash -> requireNotNull(source(hash)) }
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: CampaignReferenceRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: CampaignReferenceRelation)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: CampaignImportReceipt)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: CampaignImportBackup)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: CampaignArchiveSource)
}

/** Offline-only merge and verified recovery. Never invokes gameplay, weapon state, or a network client. */
class CampaignArchiveRepository(private val db: CampaignLedgerDb) {
    private val dao = db.archiveDao()
    val references = dao.referenceIds().map { db.withTransaction { dao.allReferences().sortedWith(compareBy<CampaignReferenceRecord> { it.category }.thenBy { it.title.lowercase() }.thenBy { it.id }) } }
    val relations = dao.relations()
    val receipts = dao.receipts()
    // Retain only review fingerprints; a dismissed 20MB source must not remain cached in the repository.
    private var backupReview: Pair<String, String>? = null
    internal var afterReferenceInsertForTest: (() -> Unit)? = null

    internal suspend fun snapshot(includeBackups: Boolean = true) = CampaignArchiveState(
        dao.allReferences(), dao.allRelations(), dao.allReceipts(), if (includeBackups) dao.allBackups() else emptyList(), dao.allSources())
    private suspend fun campaignSnapshot() = CampaignBackup(db).snapshot(includeBackups = false)
    /** Preview compares typed/reference rows; retained source payloads contribute only to its full state hash. */
    private suspend fun previewConflictSnapshot(): LedgerExport = CampaignBackup(db).snapshot(includeArchive = false).copy(
        archive = CampaignArchiveState(references = dao.allReferences(), relations = dao.allRelations(), receipts = dao.allReceipts()))
    private fun stateHash(value: LedgerExport) = campaignSerializedHash(LedgerExport.serializer(), value.copy(exportedAt = 0,
        archive = value.archive.copy(receipts = emptyList(), backups = emptyList())))
    private fun recoverySnapshot(value: LedgerExport) = value.copy(exportedAt = 0,
        archive = value.archive.copy(backups = emptyList()))
    // A separate suspend frame releases the source archive before the next complete database read.
    private suspend fun currentStateHash() = stateHash(campaignSnapshot())

    /** Independently hash stored SQLite bytes without reconstructing another complete backup String. */
    private suspend fun verifyStoredBackup(metadata: CampaignBackupMetadata) {
        check(metadata.snapshotBytes in 1..CAMPAIGN_BACKUP_MAX_BYTES.toLong()) { "Recovery backup exceeds the safe recovery text limit." }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var offset = 1L
        while (offset <= metadata.snapshotBytes) {
            val count = minOf(128 * 1024L, metadata.snapshotBytes - offset + 1).toInt()
            val bytes = requireNotNull(dao.backupChunk(metadata.packageHash, offset, count)) { "Recovery backup text is missing." }
            check(bytes.size == count) { "Recovery backup was truncated during readback." }
            digest.update(bytes)
            offset += count
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == metadata.snapshotSha256) { "Recovery backup database readback failed." }
    }

    /** Keep the normal byte-exact path free of backup decoding, including earlier schema-four encodings. */
    private suspend fun matchingCurrentSnapshot(metadata: CampaignBackupMetadata, expectedState: String): LedgerExport? {
        check(metadata.sourceStateSha256 == expectedState) { "The recovery backup did not verify." }
        val current = campaignSnapshot()
        check(stateHash(current) == expectedState) { "Local data changed. Preview again before creating a recovery backup." }
        val snapshot = recoverySnapshot(current)
        if (metadata.snapshotSha256 != campaignBackupSerializedHash(snapshot) &&
            metadata.snapshotSha256 != campaignSerializedHash(LedgerExport.serializer(), snapshot)) return null
        verifyStoredBackup(metadata)
        return current
    }

    private suspend fun verifyEarlierReceiptSnapshot(metadata: CampaignBackupMetadata) {
        // A later import and rollback can add immutable receipts without changing campaign state.
        // Decode only after the current snapshot's helper frame ends, then verify the entire prior state.
        val prior = decodeVerifiedSnapshot(metadata)
        val retainedReceipts = dao.allReceipts().toSet()
        check(prior.archive.backups.isEmpty() && prior.archive.receipts.all { it in retainedReceipts }) {
            "The recovery backup contains different recovery evidence."
        }
    }

    private suspend fun verifiedCurrentSnapshot(metadata: CampaignBackupMetadata, expectedState: String): LedgerExport {
        matchingCurrentSnapshot(metadata, expectedState)?.let { return it }
        verifyEarlierReceiptSnapshot(metadata)
        // The decoded backup frame also ends before this fresh current-state readback.
        return campaignSnapshot().also {
            check(stateHash(it) == expectedState) { "Local data changed. Preview again before creating a recovery backup." }
        }
    }

    private suspend fun verifyExistingBackup(metadata: CampaignBackupMetadata, expectedState: String) {
        verifiedCurrentSnapshot(metadata, expectedState)
    }

    private suspend fun prepareBackup(packageHash: String, expectedState: String): CampaignImportBackup {
        val current = campaignSnapshot()
        check(stateHash(current) == expectedState) { "Local data changed. Preview again before creating a recovery backup." }
        // Backup bodies exclude other backup bodies to prevent exponential nesting.
        val snapshot = recoverySnapshot(current)
        val expectedHash = campaignBackupSerializedHash(snapshot)
        val text = campaignBackupJson(snapshot)
        check(campaignHash(text) == expectedHash) { "Recovery backup serialization did not verify." }
        return CampaignImportBackup(packageHash, text, expectedHash, expectedState, System.currentTimeMillis())
    }
    private fun referenceId(campaignId: String, externalId: String) = "$campaignId::$externalId"
    private fun rows(archive: CampaignArchivePayload, hash: String) = archive.records.map {
        CampaignReferenceRecord(referenceId(archive.campaignId, it.externalId), archive.campaignId, it.externalId, it.title,
            it.category, it.body, it.propertiesJson, it.sourceUrl, it.sourceRevision, it.contentSha256, hash)
    }
    private fun edges(archive: CampaignArchivePayload, known: Set<String>) = archive.records.flatMap { row ->
        row.relationIds.map { target ->
            val from = referenceId(archive.campaignId, row.externalId)
            val to = referenceId(archive.campaignId, target)
            CampaignReferenceRelation(campaignHash("$from\u0000$to"), from, to, resolved = to in known)
        }
    }.distinctBy { it.id }

    suspend fun sourceArchive(packageHash: String): CampaignArchivePayload? = db.withTransaction {
        dao.source(packageHash)?.let { source ->
            check(campaignHash(source.payloadJson) == packageHash) { "Stored source integrity failed." }
            campaignJson.decodeFromString<CampaignArchivePayload>(source.payloadJson)
        }
    }
    suspend fun attachmentBytes(packageHash: String, attachmentId: String): ByteArray {
        val attachment = requireNotNull(sourceArchive(packageHash)?.attachments?.singleOrNull { it.id == attachmentId }) { "Attachment not found." }
        val bytes = Base64.getDecoder().decode(requireNotNull(attachment.base64) { "This attachment is an external reference; no local file was archived." })
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == attachment.sha256 && attachment.status == "embedded") { "Attachment integrity failed." }
        return bytes
    }

    suspend fun preview(text: String): CampaignArchivePreview = db.withTransaction {
        // End the full source snapshot's frame before decoding another complete selected package.
        // The surrounding transaction keeps this fingerprint and later conflict rows consistent.
        val currentFingerprint = currentStateHash()
        try {
            previewValidated(text, currentFingerprint)
        } catch (e: Exception) {
            CampaignArchivePreview(false, "Campaign archive could not be validated.", listOf(e.message ?: "Invalid archive."), emptyList(), "", currentFingerprint)
        }
    }

    private suspend fun previewValidated(text: String, currentFingerprint: String): CampaignArchivePreview {
        supportedCampaignBackup(text)
        rejectDuplicateJsonMembers(text)
        val envelope = campaignJson.decodeFromString<CampaignArchiveEnvelope>(text)
        require(envelope.format == "astravar-campaign-archive" && envelope.schema == 1) { "Unsupported campaign archive format or schema." }
        require(envelope.payloadSha256.matches(Regex("[0-9a-f]{64}")) && campaignHash(envelope.payload) == envelope.payloadSha256) { "Archive integrity check failed. Select the original complete file." }
        rejectDuplicateJsonMembers(envelope.payload)
        val incoming = campaignJson.decodeFromString<CampaignArchivePayload>(envelope.payload)
        require(incoming.records.sumOf { it.relationIds.size.toLong() } <= CAMPAIGN_JSON_MAX_CONTAINER_VALUES) {
            "Archive relationships exceed the supported campaign complexity limit. No records were imported."
        }
        val conflicts = validatePayload(incoming).toMutableList()
        val current = previewConflictSnapshot()
        val incomingRows = rows(incoming, envelope.payloadSha256)
        val known = (current.archive.references + incomingRows).map { it.id }.toSet()
        val relations = edges(incoming, known)
        val state = CampaignArchiveState(references = incomingRows, relations = relations)
        conflicts += validateState(state, current.archive)
        incoming.mapped?.let {
            require(it.archive == CampaignArchiveState()) { "Mapped records cannot contain another campaign archive." }
            conflicts += CampaignBackup(db).validate(it, current)
            conflicts += validateMappedIdentities(it, current)
        }
        val warnings = incoming.issues + relations.filter { !it.resolved }.map { "Unresolved relationship: ${it.fromId} → ${it.toId}" } +
            incoming.attachments.filter { it.base64 == null }.map { "Attachment retained as external reference: ${it.fileName} (${it.status})" }
        val prior = dao.receipt(envelope.payloadSha256)
        if (dao.receipt("rollback:${envelope.payloadSha256}") != null) conflicts += "This exact package was rolled back. Review a newly exported package before reimporting."
        val summary = "${incoming.records.size} reference records, ${relations.size} relationships, ${incoming.attachments.size} attachments; " +
            "${incoming.mapped?.items?.size ?: 0} mapped inventory items, ${incoming.mapped?.records?.characterProfiles?.size ?: 0} characters, " +
            "${incoming.mapped?.records?.wallets?.size ?: 0} wallet snapshots. " +
            if (prior != null) "Already imported; repeating this import adds nothing." else "Existing different records are preserved. History does not execute gameplay."
        return CampaignArchivePreview(conflicts.isEmpty(), summary, conflicts.distinct(), warnings.distinct(), envelope.payloadSha256,
            currentFingerprint, incoming, envelope.payload, campaignSerializedHash(CampaignArchivePayload.serializer(), incoming)).also { backupReview = if (it.valid) it.fingerprint to it.currentFingerprint else null }
    }

    /** Must happen after preview and before commit; verifies serialization and retains the exact recovery snapshot. */
    suspend fun exportPreImportBackup(packageHash: String): String = db.withTransaction {
        val review = requireNotNull(backupReview?.takeIf { it.first == packageHash }) { "Preview a valid package before saving its recovery backup." }
        dao.backupMetadata(packageHash)?.let { existing ->
            verifyExistingBackup(existing, review.second)
            // The source snapshot's helper frame has ended before the saved text is allocated.
            return@withTransaction readArchiveText(existing.snapshotBytes) { offset, size -> dao.backupChunk(packageHash, offset, size) }
        }
        val record = prepareBackup(packageHash, review.second)
        dao.insert(record)
        val metadata = requireNotNull(dao.backupMetadata(packageHash))
        check(metadata.packageHash == record.packageHash && metadata.snapshotSha256 == record.snapshotSha256 &&
            metadata.sourceStateSha256 == record.sourceStateSha256 && metadata.createdAt == record.createdAt) { "Recovery backup metadata readback failed." }
        verifyStoredBackup(metadata)
        record.snapshotJson
    }

    suspend fun commit(preview: CampaignArchivePreview, confirmed: Boolean): CampaignImportReceipt = db.withTransaction {
        require(confirmed) { "Player confirmation is required before importing." }
        require(preview.valid && preview.conflicts.isEmpty()) { "Review a valid archive preview first." }
        dao.receipt(preview.fingerprint)?.let {
            check(dao.receipt("rollback:${preview.fingerprint}") == null) { "This package was rolled back; export and review a new package." }
            return@withTransaction it
        }
        val incoming = requireNotNull(preview.archive)
        require(campaignHash(preview.payloadJson) == preview.fingerprint &&
            campaignSerializedHash(CampaignArchivePayload.serializer(), incoming) == preview.archiveFingerprint) { "Preview content changed. Select the package again." }
        require(validatePayload(incoming).isEmpty()) { "Archive validation failed." }
        val backup = requireNotNull(dao.backupMetadata(preview.fingerprint)) { "Save and verify a pre-import recovery backup first." }
        val current = verifiedCurrentSnapshot(backup, preview.currentFingerprint)
        val incomingRows = rows(incoming, preview.fingerprint)
        val edges = edges(incoming, (current.archive.references + incomingRows).map { it.id }.toSet())
        val state = CampaignArchiveState(references = incomingRows, relations = edges,
            sources = listOf(CampaignArchiveSource(preview.fingerprint, preview.payloadJson)))
        val mapped = incoming.mapped ?: LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList())
        val merge = mapped.copy(archive = state, schema = 4)
        val errors = CampaignBackup(db).validate(merge, current) + validateMappedIdentities(mapped, current)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        CampaignBackup(db).mergeRows(merge, current)
        afterReferenceInsertForTest?.invoke() // Test-only failure hook runs inside the same transaction.
        val readback = verifyRecords(incoming, preview.fingerprint)
        check(readback.isEmpty()) { "Independent database readback failed: ${readback.joinToString()}" }
        // Read typed rows back independently and validate the supplied snapshots against stored identities/content.
        val after = campaignSnapshot()
        val mappedErrors = verifyMapped(mapped, after) + verifyMapped(current, after) + verifyMergedCounts(current, merge, after)
        check(mappedErrors.isEmpty()) { "Mapped-record readback failed: ${mappedErrors.joinToString()}" }
        val receipt = CampaignImportReceipt(preview.fingerprint, incoming.campaignId, incoming.title, System.currentTimeMillis(),
            incomingRows.size, edges.size, "APPLIED", stateHash(after), preview.summary)
        dao.insert(receipt)
        check(dao.receipt(receipt.packageHash) == receipt) { "Import receipt readback failed." }
        receipt
    }

    suspend fun verify(packageHash: String): CampaignArchiveVerification = db.withTransaction {
        val receipt = dao.receipt(packageHash)
            ?: return@withTransaction CampaignArchiveVerification(false, "No import receipt exists.", listOf("Package has not been imported."))
        if (dao.receipt("rollback:$packageHash") != null) return@withTransaction CampaignArchiveVerification(false, "Package was rolled back.", listOf("The imported state is no longer active."))
        // The source decoder's coroutine frame ends before state readback starts, releasing its attachment strings.
        val errors = verifySavedSource(packageHash).toMutableList()
        // Changes made after import are permitted; report those explicitly instead of falsely verifying an unchanged snapshot.
        if (stateHash(campaignSnapshot()) != receipt.readbackSha256) errors += "Campaign records changed after this import; the original receipt remains preserved."
        CampaignArchiveVerification(errors.isEmpty(), if (errors.isEmpty()) "Database readback matches every imported identity, relationship and content hash." else "Review the database readback differences.", errors.distinct())
    }

    private suspend fun verifySavedSource(packageHash: String): List<String> {
        val source = dao.source(packageHash)
        if (source == null || campaignHash(source.payloadJson) != packageHash)
            return listOf("Stored source archive failed integrity verification.")
        val archive = campaignJson.decodeFromString<CampaignArchivePayload>(source.payloadJson)
        val errors = verifyRecords(archive, packageHash).toMutableList()
        archive.mapped?.let { mapped ->
            // Mapped equality needs fresh typed rows, not another copy of every retained archive/attachment.
            errors += verifyMapped(mapped, CampaignBackup(db).snapshot(includeArchive = false))
        }
        return errors
    }

    /** Guarded full campaign recovery is allowed only before any subsequent campaign edit/import. */
    suspend fun rollback(packageHash: String, confirmed: Boolean): CampaignImportReceipt = db.withTransaction {
        require(confirmed) { "Player confirmation is required before recovery." }
        dao.receipt("rollback:$packageHash")?.let { return@withTransaction it }
        val receipt = requireNotNull(dao.receipt(packageHash)) { "No import receipt exists." }
        check(currentStateHash() == receipt.readbackSha256) { "Campaign data changed after import. Export both states and reconcile; automatic rollback would discard later work." }
        val backup = requireNotNull(dao.backupMetadata(packageHash)) { "The pre-import backup is unavailable." }
        restoreVerifiedSnapshot(backup)
        // The decoded prior snapshot's helper frame has ended before independent readback.
        check(currentStateHash() == backup.sourceStateSha256) { "Recovery database readback differs from the verified pre-import snapshot." }
        CampaignImportReceipt("rollback:$packageHash", receipt.campaignId, receipt.title, System.currentTimeMillis(),
            receipt.recordCount, receipt.relationCount, "ROLLED_BACK", backup.sourceStateSha256,
            "Recovered the verified pre-import campaign snapshot. Original import and recovery receipts are retained.").also { dao.insert(it) }
    }

    private suspend fun decodeVerifiedSnapshot(metadata: CampaignBackupMetadata): LedgerExport {
        verifyStoredBackup(metadata)
        val text = readArchiveText(metadata.snapshotBytes) { offset, size -> dao.backupChunk(metadata.packageHash, offset, size) }
        check(campaignHash(text) == metadata.snapshotSha256) { "Recovery backup integrity failed." }
        val prior = campaignDecodeBackup(text)
        check(stateHash(prior) == metadata.sourceStateSha256) { "Recovery snapshot identity failed." }
        return prior
    }

    private suspend fun restoreVerifiedSnapshot(metadata: CampaignBackupMetadata) {
        // Decoding ends in its own frame so the serialized text is released before restoring rows.
        val prior = decodeVerifiedSnapshot(metadata)
        val sql = db.openHelper.writableDatabase
        ROLLBACK_DELETE_TABLES.forEach { sql.execSQL("DELETE FROM $it") }
        val empty = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList())
        CampaignBackup(db).mergeRows(prior.copy(archive = prior.archive.copy(backups = emptyList(), receipts = emptyList())), empty)
        // Backup and receipt tables remain untouched, preserving immutable recovery evidence in place.
    }

    private suspend fun verifyRecords(archive: CampaignArchivePayload, hash: String): List<String> {
        val errors = mutableListOf<String>()
        val actual = dao.allReferences().associateBy { it.id }
        rows(archive, hash).forEach { expected ->
            val found = actual[expected.id]
            if (found == null || !sameReference(found, expected) || campaignHash(found.body) != expected.contentSha256) errors += "Reference readback differs: ${expected.externalId}"
        }
        val actualEdges = dao.allRelations().associateBy { it.id }
        edges(archive, actual.keys).forEach { expected -> if (actualEdges[expected.id] != expected) errors += "Relationship readback differs: ${expected.fromId} → ${expected.toId}" }
        return errors
    }

    private fun validateMappedIdentities(incoming: LedgerExport, current: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        incoming.items.filter { row -> current.items.none { it.id == row.id } }.forEach { row ->
            if (current.items.any { it.title.trim().equals(row.title.trim(), ignoreCase = true) })
                errors += "Possible duplicate physical item '${row.title}'. Reconcile identities before import."
        }
        incoming.records.wallets.filter { row -> current.records.wallets.none { it.id == row.id } }.forEach { row ->
            if (current.records.wallets.any { it.campaignId == row.campaignId && it.holderProfileId == row.holderProfileId })
                errors += "Possible duplicate wallet snapshot '${row.name}'. Existing balances will not be combined."
        }
        return errors
    }

    /** Identity checks read typed DAO lists; never serialize the retained source/attachments to count rows. */
    private fun verifyMergedCounts(before: LedgerExport, incoming: LedgerExport, after: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        fun <T> check(label: String, old: List<T>, added: List<T>, saved: List<T>, identity: (T) -> String) {
            val expected = (old.asSequence() + added.asSequence()).map(identity).toSet()
            val actual = saved.map(identity).toSet()
            if (expected != actual || saved.size != actual.size) errors += "$label source/destination count or identities differ."
        }
        check("items", before.items, incoming.items, after.items) { it.id }
        check("events", before.events, incoming.events, after.events) { it.commandId }
        val a = before.records; val b = incoming.records; val c = after.records
        check("projects", a.projects, b.projects, c.projects) { it.id }
        check("stages", a.stages, b.stages, c.stages) { it.id }
        check("requirements", a.requirements, b.requirements, c.requirements) { it.id }
        check("entities", a.entities, b.entities, c.entities) { it.id }
        check("facts", a.facts, b.facts, c.facts) { it.id }
        check("milestones", a.milestones, b.milestones, c.milestones) { it.id }
        check("links", a.links, b.links, c.links) { it.id }
        check("companionStates", a.companionStates, b.companionStates, c.companionStates) { it.companionId }
        check("companionResources", a.companionResources, b.companionResources, c.companionResources) { it.id }
        check("itemDetails", a.itemDetails, b.itemDetails, c.itemDetails) { it.itemId }
        check("itemProperties", a.itemProperties, b.itemProperties, c.itemProperties) { it.id }
        check("audit", a.audit, b.audit, c.audit) { it.id }
        check("receipts", a.receipts, b.receipts, c.receipts) { it.commandId }
        check("crafts", a.crafts, b.crafts, c.crafts) { it.commandId }
        check("consumptions", a.consumptions, b.consumptions, c.consumptions) { it.id }
        check("characterProfiles", a.characterProfiles, b.characterProfiles, c.characterProfiles) { it.id }
        check("characterAbilities", a.characterAbilities, b.characterAbilities, c.characterAbilities) { it.id }
        check("partySheets", a.partySheets, b.partySheets, c.partySheets) { it.profileId }
        check("placements", a.placements, b.placements, c.placements) { it.itemId }
        check("wallets", a.wallets, b.wallets, c.wallets) { it.id }
        check("checkpoints", a.checkpoints, b.checkpoints, c.checkpoints) { it.id }
        check("externalReferences", a.externalReferences, b.externalReferences, c.externalReferences) { it.id }
        val x = before.archive; val y = incoming.archive; val z = after.archive
        check("archive.references", x.references, y.references, z.references) { it.id }
        check("archive.relations", x.relations, y.relations, z.relations) { it.id }
        check("archive.receipts", x.receipts, y.receipts, z.receipts) { it.packageHash }
        check("archive.sources", x.sources, y.sources, z.sources) { it.packageHash }
        return errors
    }

    /** Complete typed equality against fresh DAO results, without building an archive-sized JSON tree. */
    private fun verifyMapped(expected: LedgerExport, actual: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        fun <T> compare(label: String, values: List<T>, local: List<T>) {
            val savedRows = local.toSet()
            values.forEachIndexed { index, row -> if (row !in savedRows) errors += "$label record $index is missing or differs during independent readback." }
        }
        compare("Inventory", expected.items, actual.items)
        compare("Inventory history", expected.events, actual.events)
        val a = expected.records; val b = actual.records
        compare("projects", a.projects, b.projects)
        compare("stages", a.stages, b.stages)
        compare("requirements", a.requirements, b.requirements)
        compare("entities", a.entities, b.entities)
        compare("facts", a.facts, b.facts)
        compare("milestones", a.milestones, b.milestones)
        compare("links", a.links, b.links)
        compare("companionStates", a.companionStates, b.companionStates)
        compare("companionResources", a.companionResources, b.companionResources)
        compare("itemDetails", a.itemDetails, b.itemDetails)
        compare("itemProperties", a.itemProperties, b.itemProperties)
        compare("audit", a.audit, b.audit)
        compare("receipts", a.receipts, b.receipts)
        compare("crafts", a.crafts, b.crafts)
        compare("consumptions", a.consumptions, b.consumptions)
        compare("characterProfiles", a.characterProfiles, b.characterProfiles)
        compare("characterAbilities", a.characterAbilities, b.characterAbilities)
        compare("partySheets", a.partySheets, b.partySheets)
        compare("placements", a.placements, b.placements)
        compare("wallets", a.wallets, b.wallets)
        compare("checkpoints", a.checkpoints, b.checkpoints)
        compare("externalReferences", a.externalReferences, b.externalReferences)
        return errors
    }

    internal suspend fun mergeState(incoming: CampaignArchiveState, current: CampaignArchiveState) {
        suspend fun <T> merge(values: List<T>, present: List<T>, key: (T) -> String, insert: suspend (T) -> Unit) {
            val keys = present.map(key).toSet(); values.filter { key(it) !in keys }.forEach { insert(it) }
        }
        merge(incoming.references, current.references, { it.id }) { dao.insert(it) }
        merge(incoming.relations, current.relations, { it.id }) { dao.insert(it) }
        merge(incoming.sources, current.sources, { it.packageHash }) { dao.insert(it) }
        merge(incoming.backups, current.backups, { it.packageHash }) { dao.insert(it) }
        merge(incoming.receipts, current.receipts, { it.packageHash }) { dao.insert(it) }
    }

    companion object {
        private fun sameReference(a: CampaignReferenceRecord, b: CampaignReferenceRecord) = a.copy(packageHash = b.packageHash) == b
        private fun validatePayload(value: CampaignArchivePayload): List<String> {
            val errors = mutableListOf<String>()
            fun text(s: String, max: Int, required: Boolean = true) = s.length <= max && (!required || s.isNotBlank())
            if (!text(value.campaignId, 120) || value.campaignId.contains("::") || !text(value.title, 1000) || !text(value.sourceRootUrl, 4000) || !text(value.sourceRootRevision, 1000) || value.exportedAt < 0) errors += "Invalid campaign identity or source revision."
            if (value.records.size !in 1..50_000 || value.records.map { it.externalId }.distinct().size != value.records.size) errors += "Archive must contain 1–50,000 uniquely identified records."
            value.records.forEach { row ->
                if (!text(row.externalId, 180) || row.externalId.contains("::") || !text(row.title, 1000) || !text(row.category, 120) || row.body.length > 2_000_000 || row.propertiesJson.length > 2_000_000 ||
                    !text(row.sourceUrl, 4000) || !text(row.sourceRevision, 1000) || row.relationIds.any { !text(it, 180) } || row.relationIds.distinct().size != row.relationIds.size) errors += "Invalid reference fields: ${row.externalId}"
                if (row.visibility != "player") errors += "Non-player-visible source rejected: ${row.externalId}"
                if (!row.contentSha256.matches(Regex("[0-9a-f]{64}")) || campaignHash(row.body) != row.contentSha256) errors += "Content integrity failed: ${row.externalId}"
                try { rejectDuplicateJsonMembers(row.propertiesJson); require(campaignJson.parseToJsonElement(row.propertiesJson) is JsonObject) } catch (_: Exception) { errors += "Original properties must be a JSON object: ${row.externalId}" }
            }
            if (value.attachments.map { it.id }.distinct().size != value.attachments.size) errors += "Attachment identities are duplicated."
            value.attachments.forEach { attachment ->
                if (!text(attachment.id, 180) || !text(attachment.fileName, 1000) || !text(attachment.sourceUrl, 4000, false) || !text(attachment.status, 120)) errors += "Invalid attachment metadata."
                if (attachment.base64 != null) try {
                    val bytes = Base64.getDecoder().decode(attachment.base64)
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    require(attachment.sha256 == hash && attachment.status == "embedded")
                } catch (_: Exception) { errors += "Attachment integrity failed: ${attachment.fileName}" }
                else if (attachment.status == "embedded") errors += "Embedded attachment is missing its bytes: ${attachment.fileName}"
            }
            return errors.distinct()
        }
        internal fun validateState(incoming: CampaignArchiveState, current: CampaignArchiveState): List<String> {
            val errors = mutableListOf<String>()
            fun <T> rows(label: String, values: List<T>, existing: List<T>, key: (T) -> String, equal: (T, T) -> Boolean = { a,b -> a == b }) {
                if (values.size > 50_000 || values.map(key).distinct().size != values.size || values.any { key(it).isBlank() }) errors += "$label contains invalid or duplicate identities."
                val local = existing.associateBy(key)
                values.forEach { row -> local[key(row)]?.let { if (!equal(row, it)) errors += "$label ${key(row)} conflicts with a preserved local record." } }
            }
            rows("Reference", incoming.references, current.references, { it.id }, ::sameReference)
            rows("Reference relationship", incoming.relations, current.relations, { it.id })
            rows("Import receipt", incoming.receipts, current.receipts, { it.packageHash })
            rows("Recovery backup", incoming.backups, current.backups, { it.packageHash })
            rows("Archive source", incoming.sources, current.sources, { it.packageHash })
            incoming.references.forEach { if (campaignHash(it.body) != it.contentSha256) errors += "Reference body hash failed: ${it.id}" }
            incoming.sources.forEach { if (campaignHash(it.payloadJson) != it.packageHash) errors += "Source payload hash failed: ${it.packageHash}" }
            incoming.backups.forEach { if (campaignHash(it.snapshotJson) != it.snapshotSha256) errors += "Recovery backup hash failed: ${it.packageHash}" }
            return errors.distinct()
        }
    }
}

private val ROLLBACK_DELETE_TABLES = listOf(
    "craft_consumptions", "craft_executions", "item_placements", "party_sheets", "party_wallets", "external_record_references",
    "character_abilities", "project_stages", "material_requirements", "connection_facts", "connection_milestones", "companion_resources",
    "item_details", "item_properties", "campaign_links", "campaign_audit", "command_receipts", "campaign_checkpoints", "ledger_events",
    "ledger_items", "character_profiles", "invention_projects", "connection_entities", "companion_states", "campaign_reference_relations",
    "campaign_references", "campaign_archive_sources"
)
val CAMPAIGN_MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) { CAMPAIGN_ARCHIVE_SCHEMA.forEach(db::execSQL) }
}
internal val CAMPAIGN_ARCHIVE_SCHEMA = listOf(
    "CREATE TABLE IF NOT EXISTS campaign_references (id TEXT NOT NULL PRIMARY KEY, campaignId TEXT NOT NULL, externalId TEXT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, body TEXT NOT NULL, propertiesJson TEXT NOT NULL, sourceUrl TEXT NOT NULL, sourceRevision TEXT NOT NULL, contentSha256 TEXT NOT NULL, packageHash TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_campaign_references_campaignId ON campaign_references(campaignId)",
    "CREATE INDEX IF NOT EXISTS index_campaign_references_category ON campaign_references(category)",
    "CREATE TABLE IF NOT EXISTS campaign_reference_relations (id TEXT NOT NULL PRIMARY KEY, fromId TEXT NOT NULL, toId TEXT NOT NULL, kind TEXT NOT NULL, resolved INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_campaign_reference_relations_fromId ON campaign_reference_relations(fromId)",
    "CREATE INDEX IF NOT EXISTS index_campaign_reference_relations_toId ON campaign_reference_relations(toId)",
    "CREATE TABLE IF NOT EXISTS campaign_import_receipts (packageHash TEXT NOT NULL PRIMARY KEY, campaignId TEXT NOT NULL, title TEXT NOT NULL, importedAt INTEGER NOT NULL, recordCount INTEGER NOT NULL, relationCount INTEGER NOT NULL, state TEXT NOT NULL, readbackSha256 TEXT NOT NULL, summary TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS campaign_import_backups (packageHash TEXT NOT NULL PRIMARY KEY, snapshotJson TEXT NOT NULL, snapshotSha256 TEXT NOT NULL, sourceStateSha256 TEXT NOT NULL, createdAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS campaign_archive_sources (packageHash TEXT NOT NULL PRIMARY KEY, payloadJson TEXT NOT NULL)"
)

/** Canonicalizes Notion compact/dashed IDs and page URLs for physical-item deduplication. */
internal fun normalizedNotionIdentity(value: String): String {
    val path = value.trim().lowercase().substringBefore('?').substringBefore('#').trimEnd('/')
    val dashed = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").findAll(path).lastOrNull()?.value
    if (dashed != null) return dashed.replace("-", "")
    val suffix = path.takeLast(32)
    return if (suffix.matches(Regex("[0-9a-f]{32}"))) suffix else path.replace("-", "")
}

internal const val CAMPAIGN_JSON_MAX_VALUES = 100_000
internal const val CAMPAIGN_JSON_MAX_CONTAINER_VALUES = 50_000
internal const val CAMPAIGN_JSON_MAX_OBJECT_MEMBERS = 1_000

/** Check complexity before typed decoding, without materializing scalar strings or a JSON tree. */
internal fun rejectDuplicateJsonMembers(text: String): Set<String> {
    var at = 0
    var values = 0
    var rootKeys: Set<String> = emptySet()
    fun spaces() { while (at < text.length && text[at] in " \t\r\n") at++ }
    fun token(key: Boolean = false): String {
        require(at < text.length && text[at] == '"') { "Malformed JSON string." }
        val start = at++
        while (at < text.length) {
            require(!key || at - start <= 4096) { "JSON member name exceeds the safe archive limit." }
            val char = text[at++]
            when {
                char == '\\' -> {
                    require(at < text.length) { "Truncated JSON escape." }
                    when (text[at++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") { "Invalid JSON Unicode escape." } }
                        else -> error("Invalid JSON escape.")
                    }
                }
                char == '"' -> return if (key) campaignJson.decodeFromString(text.substring(start, at)) else ""
                char.code < 0x20 -> error("Unescaped JSON control character.")
            }
        }
        error("Unterminated JSON string.")
    }
    fun value(depth: Int) {
        require(depth <= 128) { "JSON nesting exceeds the safe archive limit." }
        require(++values <= CAMPAIGN_JSON_MAX_VALUES) { "JSON structure exceeds the supported campaign complexity limit. No records were imported." }
        spaces(); require(at < text.length) { "Truncated JSON." }
        when (text[at]) {
            '{' -> {
                at++; spaces(); val keys = mutableSetOf<String>()
                if (depth == 0) rootKeys = keys
                if (at < text.length && text[at] == '}') { at++; return }
                while (true) {
                    require(keys.size < CAMPAIGN_JSON_MAX_OBJECT_MEMBERS) { "JSON object exceeds the supported campaign member limit. No records were imported." }
                    spaces(); val key = token(key = true)
                    require(keys.add(key)) { "Duplicate JSON member '$key' is not allowed." }
                    spaces(); require(at < text.length && text[at++] == ':')
                    value(depth + 1); spaces(); require(at < text.length)
                    val next = text[at++]; if (next == '}') break
                    require(next == ',')
                }
            }
            '[' -> {
                at++; spaces(); if (at < text.length && text[at] == ']') { at++; return }
                var count = 0
                while (true) {
                    require(++count <= CAMPAIGN_JSON_MAX_CONTAINER_VALUES) { "JSON array exceeds the supported campaign record limit. No records were imported." }
                    value(depth + 1); spaces(); require(at < text.length)
                    val next = text[at++]; if (next == ']') break
                    require(next == ',')
                }
            }
            '"' -> token()
            else -> {
                val start = at
                while (at < text.length && text[at] !in ",]} \t\r\n") {
                    require(at - start < 1024) { "JSON primitive exceeds the safe archive limit." }; at++
                }
                val primitive = text.substring(start, at)
                require(primitive in setOf("true", "false", "null") || primitive.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "Invalid JSON primitive." }
            }
        }
    }
    value(0); spaces(); require(at == text.length) { "Trailing JSON content." }
    return rootKeys
}
