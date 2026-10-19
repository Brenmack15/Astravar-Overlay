package com.yazsras.astravar.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
    internal val payloadJson: String = ""
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
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output)).toString()
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
    private suspend fun campaignSnapshot() = CampaignBackup(db).snapshot()
    private fun stateHash(value: LedgerExport) = campaignHash(campaignJson.encodeToString(value.copy(exportedAt = 0,
        archive = value.archive.copy(receipts = emptyList(), backups = emptyList()))))
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
        val current = campaignSnapshot()
        try {
            supportedCampaignBackup(text)
            rejectDuplicateJsonMembers(text)
            val envelope = campaignJson.decodeFromString<CampaignArchiveEnvelope>(text)
            require(envelope.format == "astravar-campaign-archive" && envelope.schema == 1) { "Unsupported campaign archive format or schema." }
            require(envelope.payloadSha256.matches(Regex("[0-9a-f]{64}")) && campaignHash(envelope.payload) == envelope.payloadSha256) { "Archive integrity check failed. Select the original complete file." }
            rejectDuplicateJsonMembers(envelope.payload)
            val incoming = campaignJson.decodeFromString<CampaignArchivePayload>(envelope.payload)
            val conflicts = validatePayload(incoming).toMutableList()
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
            CampaignArchivePreview(conflicts.isEmpty(), summary, conflicts.distinct(), warnings.distinct(), envelope.payloadSha256,
                stateHash(current), incoming, envelope.payload).also { backupReview = if (it.valid) it.fingerprint to it.currentFingerprint else null }
        } catch (e: Exception) {
            CampaignArchivePreview(false, "Campaign archive could not be validated.", listOf(e.message ?: "Invalid archive."), emptyList(), "", stateHash(current))
        }
    }

    /** Must happen after preview and before commit; verifies serialization and retains the exact recovery snapshot. */
    suspend fun exportPreImportBackup(packageHash: String): String = db.withTransaction {
        val review = requireNotNull(backupReview?.takeIf { it.first == packageHash }) { "Preview a valid package before saving its recovery backup." }
        val current = campaignSnapshot()
        check(stateHash(current) == review.second) { "Local data changed. Preview again before creating a recovery backup." }
        dao.backup(packageHash)?.let { existing ->
            require(existing.sourceStateSha256 == review.second && campaignHash(existing.snapshotJson) == existing.snapshotSha256) { "The saved recovery snapshot does not match this preview." }
            return@withTransaction existing.snapshotJson
        }
        // Backup bodies exclude other backup bodies to prevent exponential nesting; those remain in the local recovery store.
        val snapshot = current.copy(exportedAt = 0, archive = current.archive.copy(backups = emptyList()))
        val text = supportedCampaignBackup(campaignJson.encodeToString(snapshot))
        check(campaignJson.decodeFromString<LedgerExport>(text) == snapshot) { "Recovery backup serialization did not verify." }
        val record = CampaignImportBackup(packageHash, text, campaignHash(text), review.second, System.currentTimeMillis())
        dao.insert(record)
        val reread = requireNotNull(dao.backup(packageHash))
        check(reread == record && campaignHash(reread.snapshotJson) == record.snapshotSha256) { "Recovery backup database readback failed." }
        text
    }

    suspend fun commit(preview: CampaignArchivePreview, confirmed: Boolean): CampaignImportReceipt = db.withTransaction {
        require(confirmed) { "Player confirmation is required before importing." }
        require(preview.valid && preview.conflicts.isEmpty()) { "Review a valid archive preview first." }
        dao.receipt(preview.fingerprint)?.let {
            check(dao.receipt("rollback:${preview.fingerprint}") == null) { "This package was rolled back; export and review a new package." }
            return@withTransaction it
        }
        val incoming = requireNotNull(preview.archive)
        require(campaignHash(preview.payloadJson) == preview.fingerprint && campaignJson.decodeFromString<CampaignArchivePayload>(preview.payloadJson) == incoming) { "Preview content changed. Select the package again." }
        require(validatePayload(incoming).isEmpty()) { "Archive validation failed." }
        val current = campaignSnapshot()
        check(stateHash(current) == preview.currentFingerprint) { "Local data changed after preview. Select the file again." }
        val backup = requireNotNull(dao.backup(preview.fingerprint)) { "Save and verify a pre-import recovery backup first." }
        check(backup.sourceStateSha256 == preview.currentFingerprint && campaignHash(backup.snapshotJson) == backup.snapshotSha256 &&
            stateHash(campaignJson.decodeFromString<LedgerExport>(backup.snapshotJson)) == preview.currentFingerprint) { "The recovery backup did not verify." }
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
        val source = dao.source(packageHash)
        val errors = mutableListOf<String>()
        if (source == null || campaignHash(source.payloadJson) != packageHash) errors += "Stored source archive failed integrity verification."
        else {
            val archive = campaignJson.decodeFromString<CampaignArchivePayload>(source.payloadJson)
            errors += verifyRecords(archive, packageHash)
            archive.mapped?.let { errors += verifyMapped(it, campaignSnapshot()) }
        }
        // Changes made after import are permitted; report those explicitly instead of falsely verifying an unchanged snapshot.
        if (stateHash(campaignSnapshot()) != receipt.readbackSha256) errors += "Campaign records changed after this import; the original receipt remains preserved."
        CampaignArchiveVerification(errors.isEmpty(), if (errors.isEmpty()) "Database readback matches every imported identity, relationship and content hash." else "Review the database readback differences.", errors.distinct())
    }

    /** Guarded full campaign recovery is allowed only before any subsequent campaign edit/import. */
    suspend fun rollback(packageHash: String, confirmed: Boolean): CampaignImportReceipt = db.withTransaction {
        require(confirmed) { "Player confirmation is required before recovery." }
        dao.receipt("rollback:$packageHash")?.let { return@withTransaction it }
        val receipt = requireNotNull(dao.receipt(packageHash)) { "No import receipt exists." }
        check(stateHash(campaignSnapshot()) == receipt.readbackSha256) { "Campaign data changed after import. Export both states and reconcile; automatic rollback would discard later work." }
        val backup = requireNotNull(dao.backup(packageHash)) { "The pre-import backup is unavailable." }
        check(campaignHash(backup.snapshotJson) == backup.snapshotSha256) { "Recovery backup integrity failed." }
        val prior = campaignJson.decodeFromString<LedgerExport>(backup.snapshotJson)
        check(stateHash(prior) == backup.sourceStateSha256) { "Recovery snapshot identity failed." }
        val retainedBackups = dao.allBackups()
        val retainedReceipts = dao.allReceipts()
        val sql = db.openHelper.writableDatabase
        ROLLBACK_DELETE_TABLES.forEach { sql.execSQL("DELETE FROM $it") }
        val empty = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList())
        CampaignBackup(db).mergeRows(prior.copy(archive = prior.archive.copy(backups = emptyList(), receipts = emptyList())), empty)
        // Recovery evidence remains immutable across rollback and is included in future normal backups.
        retainedBackups.forEach { dao.insert(it) }
        retainedReceipts.forEach { dao.insert(it) }
        check(stateHash(campaignSnapshot()) == backup.sourceStateSha256) { "Recovery database readback differs from the verified pre-import snapshot." }
        CampaignImportReceipt("rollback:$packageHash", receipt.campaignId, receipt.title, System.currentTimeMillis(),
            receipt.recordCount, receipt.relationCount, "ROLLED_BACK", backup.sourceStateSha256,
            "Recovered the verified pre-import campaign snapshot. Original import and recovery receipts are retained.").also { dao.insert(it) }
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

    private fun verifyMergedCounts(before: LedgerExport, incoming: LedgerExport, after: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        val a = campaignJson.encodeToJsonElement(before).jsonObject
        val b = campaignJson.encodeToJsonElement(incoming).jsonObject
        val c = campaignJson.encodeToJsonElement(after).jsonObject
        fun identity(label: String, row: JsonElement): String {
            val value = row.jsonObject
            return when (label) {
                "events", "crafts", "receipts" -> value.getValue("commandId").jsonPrimitive.content
                "archive.receipts", "archive.sources", "archive.backups" -> value.getValue("packageHash").jsonPrimitive.content
                "companionStates" -> value.getValue("companionId").jsonPrimitive.content
                "partySheets" -> value.getValue("profileId").jsonPrimitive.content
                "placements", "itemDetails" -> value.getValue("itemId").jsonPrimitive.content
                "characterAbilities" -> value.getValue("profileId").jsonPrimitive.content + ":" + value.getValue("key").jsonPrimitive.content
                else -> value.getValue("id").jsonPrimitive.content
            }
        }
        fun check(label: String, old: JsonArray, added: JsonArray, saved: JsonArray) {
            val expected = (old + added).map { identity(label, it) }.toSet()
            val actual = saved.map { identity(label, it) }.toSet()
            if (expected != actual || saved.size != actual.size) errors += "$label source/destination count or identities differ."
        }
        listOf("items", "events").forEach { check(it, a.getValue(it).jsonArray, b.getValue(it).jsonArray, c.getValue(it).jsonArray) }
        listOf("records", "archive").forEach { group ->
            a.getValue(group).jsonObject.forEach { (key, old) ->
                check(if (group == "archive") "archive.$key" else key, old.jsonArray,
                    b.getValue(group).jsonObject.getValue(key).jsonArray, c.getValue(group).jsonObject.getValue(key).jsonArray)
            }
        }
        return errors
    }

    /** Compares complete values from new DAO queries, not validation against an inferred ID union. */
    private fun verifyMapped(expected: LedgerExport, actual: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        val source = campaignJson.encodeToJsonElement(expected).jsonObject
        val saved = campaignJson.encodeToJsonElement(actual).jsonObject
        fun compare(label: String, values: JsonArray, local: JsonArray) {
            val savedRows = local.toSet()
            values.forEachIndexed { index, row -> if (row !in savedRows) errors += "$label record $index is missing or differs during independent readback." }
        }
        compare("Inventory", source.getValue("items").jsonArray, saved.getValue("items").jsonArray)
        compare("Inventory history", source.getValue("events").jsonArray, saved.getValue("events").jsonArray)
        source.getValue("records").jsonObject.forEach { (label, rows) ->
            compare(label, rows.jsonArray, saved.getValue("records").jsonObject.getValue(label).jsonArray)
        }
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
    "campaign_references", "campaign_archive_sources", "campaign_import_backups", "campaign_import_receipts"
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

/** kotlinx JSON rejects unknown fields but accepts duplicate member names. Reject those before decoding. */
internal fun rejectDuplicateJsonMembers(text: String) {
    var at = 0
    fun spaces() { while (at < text.length && text[at].isWhitespace()) at++ }
    fun token(): String {
        require(at < text.length && text[at] == '"') { "Malformed JSON string." }
        val start = at++
        while (at < text.length) {
            when (text[at++]) {
                '\\' -> { require(at < text.length); at++ }
                '"' -> return campaignJson.decodeFromString(text.substring(start, at))
            }
        }
        error("Unterminated JSON string.")
    }
    fun value(depth: Int) {
        require(depth <= 128) { "JSON nesting exceeds the safe archive limit." }
        spaces(); require(at < text.length) { "Truncated JSON." }
        when (text[at]) {
            '{' -> {
                at++; spaces(); val keys = mutableSetOf<String>()
                if (at < text.length && text[at] == '}') { at++; return }
                while (true) {
                    spaces(); val key = token()
                    require(keys.add(key)) { "Duplicate JSON member '$key' is not allowed." }
                    spaces(); require(at < text.length && text[at++] == ':')
                    value(depth + 1); spaces(); require(at < text.length)
                    val next = text[at++]; if (next == '}') break
                    require(next == ',')
                }
            }
            '[' -> {
                at++; spaces(); if (at < text.length && text[at] == ']') { at++; return }
                while (true) {
                    value(depth + 1); spaces(); require(at < text.length)
                    val next = text[at++]; if (next == ']') break
                    require(next == ',')
                }
            }
            '"' -> token()
            else -> {
                val start = at
                while (at < text.length && text[at] !in ",]}" && !text[at].isWhitespace()) at++
                val primitive = text.substring(start, at)
                require(primitive in setOf("true", "false", "null") || primitive.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "Invalid JSON primitive." }
            }
        }
    }
    value(0); spaces(); require(at == text.length) { "Trailing JSON content." }
    campaignJson.parseToJsonElement(text) // validates scalar syntax, number grammar and control characters
}
