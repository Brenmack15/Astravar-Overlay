package com.yazsras.astravar.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest

/** A read-only, evidence-led diagnostic. Findings never authorize a campaign mutation. */
enum class CampaignConflictLevel { BLOCKED, REVIEW, NOTICE }

data class CampaignConflictFinding(
    val level: CampaignConflictLevel,
    val area: String,
    val title: String,
    val evidence: String,
    val nextStep: String
)

data class CampaignConflictAudit(
    val referenceCount: Int,
    val relationCount: Int,
    val itemCount: Int,
    val walletCount: Int,
    val activeImportCount: Int,
    val verifiedImportCount: Int,
    val blockedCount: Int,
    val reviewCount: Int,
    val noticeCount: Int,
    val findings: List<CampaignConflictFinding>,
    val omittedCount: Int
) {
    val noBlockingConflicts get() = blockedCount == 0 && reviewCount == 0

    /** Omits structured page bodies, properties, attachments, and wallet balances. Source notes may still be private. */
    fun shareableSummary(): String = buildString {
        appendLine("Astravar local conflict verification (read-only)")
        appendLine("Checked: $referenceCount source records, $relationCount relationships, $itemCount items, $walletCount wallets, $activeImportCount active import receipts")
        appendLine("Verified imports: $verifiedImportCount; integrity problems: $blockedCount; decisions needed: $reviewCount; notices: $noticeCount")
        findings.forEach {
            appendLine("[${it.level}] ${it.area}: ${it.title}")
            appendLine("Evidence: ${it.evidence}")
            appendLine("Next: ${it.nextStep}")
        }
        if (omittedCount > 0) appendLine("$omittedCount further findings omitted; review them in the app/source archive.")
        appendLine("This diagnostic is not import approval, a gameplay decision, or proof of live ChatGPT/phone sync.")
    }
}

/** Bounds the phone UI and copied report even when a damaged archive has many errors. */
internal class CampaignConflictAuditBuilder(private val findingLimit: Int = 150) {
    private val allKeys = mutableSetOf<String>()

    init { require(findingLimit >= 0) }
    private val shown = mutableListOf<CampaignConflictFinding>()
    private var blocked = 0
    private var review = 0
    private var notices = 0

    fun add(level: CampaignConflictLevel, area: String, title: String, evidence: String, nextStep: String) {
        val boundedTitle = title.take(160)
        val boundedEvidence = evidence.take(400)
        // Hash full evidence before shortening it so distinct long identities stay distinct.
        val key = campaignHash(listOf(level.name, area, title, evidence).joinToString("\u0000"))
        if (!allKeys.add(key)) return
        when (level) {
            CampaignConflictLevel.BLOCKED -> blocked++
            CampaignConflictLevel.REVIEW -> review++
            CampaignConflictLevel.NOTICE -> notices++
        }
        val finding = CampaignConflictFinding(level, area.take(80), boundedTitle, boundedEvidence, nextStep.take(400))
        if (shown.size < findingLimit) shown += finding
        else {
            // Notices encountered first must never crowd a later integrity failure out of the UI.
            val lowestPriority = shown.indices.maxByOrNull { shown[it].level.ordinal }
            if (lowestPriority != null && level.ordinal < shown[lowestPriority].level.ordinal) shown[lowestPriority] = finding
        }
    }

    fun build(state: LedgerExport, activeImports: Int, verifiedImports: Int) = CampaignConflictAudit(
        state.archive.references.size, state.archive.relations.size, state.items.size, state.records.wallets.size,
        activeImports, verifiedImports, blocked, review, notices,
        shown.sortedWith(compareBy<CampaignConflictFinding> { it.level.ordinal }.thenBy { it.area }.thenBy { it.title }),
        blocked + review + notices - shown.size
    )
}

/** Checks local identities and links; never guesses that same-named items are the same object. */
internal fun checkLocalCampaignConflicts(state: LedgerExport, report: CampaignConflictAuditBuilder) {
    val references = state.archive.references
    val knownReferences = references.map { it.id }.toSet()
    references.forEach { row ->
        if (campaignHash(row.body) != row.contentSha256) report.add(
            CampaignConflictLevel.BLOCKED, "Source integrity", row.title,
            "Saved source ${row.id} differs from its recorded SHA-256.",
            "Preserve the record and verify it against the original archive or a known-good backup."
        )
    }
    val notionId = Regex("[0-9a-f]{32}")
    references.groupBy { it.campaignId to normalizedNotionIdentity(it.externalId) }
        .filter { (key, rows) -> notionId.matches(key.second) && rows.map { it.id }.distinct().size > 1 }
        .forEach { (key, rows) -> report.add(CampaignConflictLevel.REVIEW, "Source identity", "Multiple versions of one Notion page",
            "Campaign ${key.first}, source ${key.second}: ${rows.joinToString { it.id }}.",
            "Verify the original page ID and revisions; retain both records until their relationship is established.") }

    state.archive.relations.forEach { edge ->
        if (edge.fromId !in knownReferences) report.add(CampaignConflictLevel.BLOCKED, "Relationships", "Missing relationship origin",
            "Relationship ${edge.id} points from absent record ${edge.fromId}.", "Restore or verify the original source; do not invent a replacement record.")
        if (edge.toId !in knownReferences) report.add(CampaignConflictLevel.REVIEW, "Relationships", "Unresolved relationship target",
            "${edge.fromId} → ${edge.toId}; saved resolved flag: ${edge.resolved}.",
            "Locate the original target source. Do not create an inferred character, item, or event.")
        else if (!edge.resolved) report.add(CampaignConflictLevel.NOTICE, "Relationships", "Relationship can be rechecked",
            "Target ${edge.toId} now exists, but relationship ${edge.id} remains marked unresolved.",
            "Review original source linkage before changing its historical status.")
    }

    val itemIds = state.items.map { it.id }.toSet()
    val aliases = buildList {
        state.items.forEach { item -> item.notionPageId?.takeIf(String::isNotBlank)?.let {
            add(normalizedNotionIdentity(it) to item.id)
        } }
        state.records.externalReferences.filter { it.namespace.equals("notion", ignoreCase = true) && it.recordType == "item" }
            .forEach { add(normalizedNotionIdentity(it.externalId) to it.recordId) }
    }
    aliases.groupBy { it.first }.filter { (_, rows) -> rows.map { it.second }.distinct().size > 1 }.forEach { (source, rows) ->
        report.add(CampaignConflictLevel.REVIEW, "Inventory identity", "One source maps to multiple items",
            "Notion source $source maps to item IDs ${rows.map { it.second }.distinct().joinToString()}.",
            "Check whether this is an alias or two physical objects; never merge quantities automatically.")
    }
    state.items.filterNot { it.archived }.groupBy { Triple(it.title.trim().lowercase(), it.owner.trim().lowercase(), it.location.trim().lowercase()) }
        .filter { (key, rows) -> key.first.isNotBlank() && rows.size > 1 }
        .forEach { (_, rows) -> report.add(CampaignConflictLevel.NOTICE, "Inventory identity", "Possible repeated item name: ${rows.first().title}",
            "Distinct item IDs: ${rows.joinToString { it.id }}; same recorded owner and location.",
            "Only compare if these should be one physical stack; identical names alone are not a conflict.") }
    state.events.filter { it.itemId !in itemIds }.forEach { event ->
        report.add(CampaignConflictLevel.REVIEW, "Inventory history", "Event references an absent item",
            "Event ${event.commandId} retains item identity ${event.itemId}.",
            "Locate the original item or archive; preserve the immutable event.")
    }

    state.records.wallets.groupBy { Triple(it.campaignId, it.holderProfileId, if (it.holderProfileId == null) it.name.trim().lowercase() else "") }
        .filter { (_, rows) -> rows.map { it.id }.distinct().size > 1 }
        .forEach { (key, rows) -> report.add(CampaignConflictLevel.REVIEW, "Currency", "Multiple wallets need identity review",
            "Campaign ${key.first}, holder ${key.second ?: "unassigned"}, wallet IDs ${rows.joinToString { it.id }}.",
            "Check whether these are separate purses or snapshots; never combine or infer coin balances.") }
}

/** New additive verifier; the pinned CampaignArchiveRepository and its import rules remain untouched. */
internal class CampaignConflictVerifier(private val db: CampaignLedgerDb) {
    private val dao = db.archiveDao()

    suspend fun auditConflicts(): CampaignConflictAudit = db.withTransaction {
        // Keep rows, retained sources and streamed backups in one consistent local snapshot.
        // Copy only the local rows needed for comparison; do not retain all archived source files/backups.
        val state = CampaignBackup(db).snapshot(includeArchive = false).copy(archive = CampaignArchiveState(
            references = dao.allReferences(), relations = dao.allRelations(), receipts = dao.allReceipts()))
        val report = CampaignConflictAuditBuilder()
        checkLocalCampaignConflicts(state, report)
        val receipts = state.archive.receipts
        val originals = receipts.filterNot { it.packageHash.startsWith("rollback:") }.associateBy { it.packageHash }
        val rolledBack = mutableSetOf<String>()
        receipts.forEach { receipt ->
            if (receipt.packageHash.startsWith("rollback:")) {
                val hash = receipt.packageHash.removePrefix("rollback:")
                val original = originals[hash]
                if (receipt.state != "ROLLED_BACK" || original == null || original.state != "APPLIED" ||
                    receipt.campaignId != original.campaignId || receipt.recordCount != original.recordCount ||
                    receipt.relationCount != original.relationCount) report.add(
                    CampaignConflictLevel.BLOCKED, "Saved imports", "Rollback receipt identity is inconsistent",
                    "Receipt ${receipt.packageHash} does not describe a matching applied import.",
                    "Preserve both receipts and verify the original import and recovery evidence.")
                else try {
                    // A state label alone cannot establish that an import was safely recovered.
                    val backup = requireNotNull(dao.backupMetadata(hash)) { "The retained recovery backup is missing." }
                    check(receipt.readbackSha256 == backup.sourceStateSha256) { "The rollback readback does not match its pre-import state identity." }
                    checkBackupBytes(backup)
                    rolledBack += hash
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report.add(
                    CampaignConflictLevel.BLOCKED, "Saved imports", "Rollback recovery evidence failed integrity verification",
                    "Receipt ${receipt.packageHash}: ${e.message ?: "The recovery evidence could not be verified."}",
                    "Preserve current data and both receipts; verify the original recovery backup before excluding this import.") }
            } else if (receipt.state != "APPLIED") report.add(CampaignConflictLevel.BLOCKED, "Saved imports", "Unrecognized import receipt state",
                "Package ${receipt.packageHash} has saved state ${receipt.state}.",
                "Preserve the receipt and original source; do not assume that the import was applied or rolled back.")
        }
        val active = originals.values.filter { it.state == "APPLIED" && it.packageHash !in rolledBack }
        var verified = 0

        active.forEach { receipt ->
            var passed = true
            val hash = receipt.packageHash
            fun finding(level: CampaignConflictLevel, title: String, evidence: String, next: String) {
                passed = false
                report.add(level, "Saved imports", title, "Package ${hash.take(16)}: $evidence", next)
            }
            try {
                val source = dao.source(hash)
                when {
                    source == null -> finding(CampaignConflictLevel.BLOCKED, "Original import source is missing",
                        "No stored source payload exists.", "Recover the exact archive and verified backup; do not replay an import blindly.")
                    campaignHash(source.payloadJson) != hash -> finding(CampaignConflictLevel.BLOCKED, "Original import source integrity failed",
                        "Stored bytes do not match the receipt's SHA-256.", "Restore verified evidence before attempting a new import.")
                    else -> {
                        val archive = campaignJson.decodeFromString<CampaignArchivePayload>(source.payloadJson)
                        val sourceRelationCount = archive.records.sumOf { it.relationIds.distinct().size }
                        if (archive.campaignId != receipt.campaignId || archive.records.size != receipt.recordCount ||
                            sourceRelationCount != receipt.relationCount) finding(
                            CampaignConflictLevel.BLOCKED, "Receipt does not describe its source",
                            "The campaign identity, source record count or relationship count differs from the receipt.", "Check the original receipt and source archive.")
                        compareSavedSource(archive, hash, state).forEach { error -> finding(
                            CampaignConflictLevel.REVIEW, "Imported record differs from saved state", error,
                            "Compare its original source with the local revision; never overwrite later confirmed edits automatically.") }
                        archive.issues.forEach { issue -> report.add(CampaignConflictLevel.NOTICE, "Source notes", "Unresolved source note",
                            "Import ${hash.take(16)}: $issue", "Keep the note visible until original evidence establishes a decision.") }
                        archive.attachments.filter { it.base64 == null }.forEach { attachment -> report.add(
                            CampaignConflictLevel.NOTICE, "Attachments", "External file not embedded: ${attachment.fileName}",
                            "Import ${hash.take(16)} retained a reference only.", "Retrieve the exact source file if needed; do not assume its contents were imported.") }
                    }
                }
                val backup = dao.backupMetadata(hash)
                if (backup == null) finding(CampaignConflictLevel.BLOCKED, "Recovery backup is missing",
                    "No verified pre-import snapshot is stored.", "Preserve current data and locate the external backup before any recovery.")
                else try { checkBackupBytes(backup) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { finding(CampaignConflictLevel.BLOCKED, "Recovery backup integrity failed",
                    e.message ?: "Stored recovery bytes could not be verified.", "Do not attempt rollback with this backup; preserve current data.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { finding(CampaignConflictLevel.BLOCKED, "Import could not be verified",
                e.message ?: "Stored import evidence could not be decoded.", "Preserve the original records and review the source and receipt.") }
            if (passed) verified++
        }
        report.build(state, active.size, verified)
    }

    private fun compareSavedSource(source: CampaignArchivePayload, hash: String, state: LedgerExport): List<String> {
        val errors = mutableListOf<String>()
        val actual = state.archive.references.associateBy { it.id }
        source.records.forEach { row ->
            val identity = "${source.campaignId}::${row.externalId}"
            val expected = CampaignReferenceRecord(identity, source.campaignId, row.externalId, row.title, row.category,
                row.body, row.propertiesJson, row.sourceUrl, row.sourceRevision, row.contentSha256, hash)
            val found = actual[identity]
            if (found == null || found.copy(packageHash = hash) != expected || campaignHash(found.body) != row.contentSha256)
                errors += "Reference readback differs: ${row.externalId}"
        }
        val links = state.archive.relations.associateBy { it.id }
        source.records.forEach { row -> row.relationIds.forEach { target ->
            val from = "${source.campaignId}::${row.externalId}"
            val to = "${source.campaignId}::$target"
            val expected = CampaignReferenceRelation(campaignHash("$from\u0000$to"), from, to, resolved = to in actual)
            if (links[expected.id] != expected) errors += "Relationship readback differs: $from → $to"
        } }
        source.mapped?.let { mapped ->
            fun <T> check(label: String, incoming: List<T>, local: List<T>) {
                val found = local.toSet()
                incoming.forEachIndexed { index, row ->
                    if (row !in found) errors += "$label record $index is missing or differs during readback."
                }
            }
            check("Inventory", mapped.items, state.items)
            check("Inventory history", mapped.events, state.events)
            val a = mapped.records; val b = state.records
            check("projects", a.projects, b.projects)
            check("stages", a.stages, b.stages)
            check("requirements", a.requirements, b.requirements)
            check("entities", a.entities, b.entities)
            check("facts", a.facts, b.facts)
            check("milestones", a.milestones, b.milestones)
            check("links", a.links, b.links)
            check("companionStates", a.companionStates, b.companionStates)
            check("companionResources", a.companionResources, b.companionResources)
            check("itemDetails", a.itemDetails, b.itemDetails)
            check("itemProperties", a.itemProperties, b.itemProperties)
            check("audit", a.audit, b.audit)
            check("receipts", a.receipts, b.receipts)
            check("crafts", a.crafts, b.crafts)
            check("consumptions", a.consumptions, b.consumptions)
            check("characterProfiles", a.characterProfiles, b.characterProfiles)
            check("characterAbilities", a.characterAbilities, b.characterAbilities)
            check("partySheets", a.partySheets, b.partySheets)
            check("placements", a.placements, b.placements)
            check("wallets", a.wallets, b.wallets)
            check("checkpoints", a.checkpoints, b.checkpoints)
            check("externalReferences", a.externalReferences, b.externalReferences)
        }
        return errors
    }

    /** A streaming digest avoids loading large recovery snapshots into a CursorWindow or JVM string. */
    private suspend fun checkBackupBytes(backup: CampaignBackupMetadata) {
        check(backup.snapshotBytes in 1..CAMPAIGN_BACKUP_MAX_BYTES.toLong()) { "Recovery snapshot exceeds the safe byte limit." }
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 1L
        while (offset <= backup.snapshotBytes) {
            val count = minOf(128 * 1024L, backup.snapshotBytes - offset + 1L).toInt()
            val bytes = requireNotNull(dao.backupChunk(backup.packageHash, offset, count)) { "Recovery snapshot bytes are missing." }
            check(bytes.size == count) { "Recovery snapshot was truncated." }
            digest.update(bytes)
            offset += count
        }
        val found = digest.digest().joinToString("") { "%02x".format(it) }
        check(found == backup.snapshotSha256) { "Recovery snapshot's stored SHA-256 did not match." }
    }
}
