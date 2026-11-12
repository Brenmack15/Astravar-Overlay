package com.yazsras.astravar.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Complete connected records; weapon history and installation-local storage receipts are separate. */
@Serializable data class GenesisAuthorityBackup(
    val format: String = "astravar-connected-campaign",
    val schema: Int = 1,
    val exportedAt: Long,
    val campaign: LedgerExport,
    val playable: ReforgeBackup,
    val authority: GenesisAuthorityExport
)

data class GenesisAuthorityBackupSnapshot(val text: String, val currentFingerprint: String)
data class GenesisAuthorityRestorePreview(
    val valid: Boolean,
    val summary: String,
    val conflicts: List<String>,
    val fingerprint: String,
    val currentFingerprint: String,
    val backup: GenesisAuthorityBackup? = null
)

const val GENESIS_AUTHORITY_DOCUMENT_BYTES: Int = 64 * 1024 * 1024

private fun emptyGenesisBackup() = GenesisAuthorityBackup(exportedAt = 0,
    campaign = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList()),
    playable = ReforgeBackup(exportedAt = 0, characters = emptyList(), changes = emptyList(), rolls = emptyList()),
    authority = GenesisAuthorityExport())

private fun GenesisAuthorityBackup.canonical() = copy(exportedAt = 0,
    campaign = campaign.copy(exportedAt = 0), playable = playable.copy(exportedAt = 0))

private class GenesisDocumentSize : OutputStream() {
    var bytes: Long = 0
        private set
    private fun add(count: Int) {
        bytes += count
        require(bytes <= GENESIS_AUTHORITY_DOCUMENT_BYTES) {
            "Connected backup exceeds the supported 64 MiB document limit. No complete backup was produced; existing records are unchanged."
        }
    }
    override fun write(value: Int) = add(1)
    override fun write(value: ByteArray, offset: Int, length: Int) = add(length)
}

/** Raw input has the same byte limit as native SAF; UTF-16 character counts are not a byte bound. */
internal fun validateGenesisAuthorityDocument(text: String) {
    require(text.isNotEmpty() && text.length <= GENESIS_AUTHORITY_DOCUMENT_BYTES) { "Unsupported connected backup document size." }
    val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    OutputStreamWriter(GenesisDocumentSize(), encoder).use { writer ->
        var offset = 0
        while (offset < text.length) {
            val count = minOf(8192, text.length - offset)
            writer.write(text, offset, count)
            offset += count
        }
    }
}

internal fun genesisAuthorityBackupFingerprint(value: GenesisAuthorityBackup): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(value: ByteArray, offset: Int, length: Int) = Unit
    }
    campaignEncodeJson(GenesisAuthorityBackup.serializer(), value.canonical(), DigestOutputStream(sink, digest), asciiStrings = true)
    return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

/** Measure then allocate once; no growing JSON string buffer for historical source snapshots. */
internal fun genesisAuthorityBackupJson(value: GenesisAuthorityBackup): String {
    val size = GenesisDocumentSize()
    campaignEncodeJson(GenesisAuthorityBackup.serializer(), value, size, asciiStrings = true)
    val bytes = ByteArray(size.bytes.toInt())
    val output = object : OutputStream() {
        var at = 0
        override fun write(value: Int) {
            check(at < bytes.size) { "Connected backup changed during export." }
            bytes[at++] = value.toByte()
        }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            check(length <= bytes.size - at) { "Connected backup changed during export." }
            value.copyInto(bytes, at, offset, offset + length)
            at += length
        }
        override fun close() { check(at == bytes.size) { "Connected backup export was truncated." } }
    }
    campaignEncodeJson(GenesisAuthorityBackup.serializer(), value, output, asciiStrings = true)
    output.close()
    return String(bytes, Charsets.US_ASCII).also { rejectDuplicateJsonMembers(it) }
}

/** Old supported files are read as partial inputs, never as permission to drop current authority. */
internal fun decodeGenesisAuthorityBackup(text: String): GenesisAuthorityBackup {
    validateGenesisAuthorityDocument(text)
    val header = rejectDuplicateJsonMembers(text)
    require("format" in header && "schema" in header) { "Backup must declare its format and supported schema." }
    // A tiny header reader avoids constructing a second JSON tree containing archived source text.
    val format = genesisBackupFormat(text)
    return when (format) {
        "astravar-connected-campaign" -> {
            require(header.containsAll(listOf("campaign", "playable", "authority", "exportedAt"))) { "Connected backup must include every record domain and portable authority." }
            campaignDecodeSerializable(text, GenesisAuthorityBackup.serializer()).also {
                require(it.format == format && it.schema == 1) { "Unsupported connected backup schema." }
            }
        }
        "astravar-campaign-items" -> emptyGenesisBackup().copy(campaign = campaignDecodeBackup(text))
        "astravar-reforge" -> emptyGenesisBackup().copy(playable = decodeReforgeBackup(text))
        else -> error("Unsupported backup format. Choose a connected campaign, campaign, or character backup.")
    }
}

/** Document syntax/duplicate members were checked already. Only the top-level format is decoded. */
private fun genesisBackupFormat(text: String): String {
    var at = 0
    fun spaces() { while (at < text.length && text[at].isWhitespace()) at++ }
    fun string(): String {
        val start = at
        check(text[at++] == '"')
        while (at < text.length) {
            when (text[at++]) {
                '\\' -> { if (text[at++] == 'u') at += 4 }
                '"' -> return campaignJson.decodeFromString(text.substring(start, at))
            }
        }
        error("Incomplete backup header.")
    }
    fun skipValue() {
        var depth = 0
        var inString = false
        while (at < text.length) {
            val char = text[at]
            if (inString) {
                at++
                if (char == '\\') at++ else if (char == '"') inString = false
            } else when (char) {
                '"' -> { inString = true; at++ }
                '{', '[' -> { depth++; at++ }
                '}', ']' -> { if (depth == 0) return; depth--; at++ }
                ',' -> { if (depth == 0) return; at++ }
                else -> at++
            }
        }
    }
    spaces(); check(text[at++] == '{')
    while (at < text.length) {
        spaces()
        if (text[at] == '}') break
        val key = string()
        spaces(); check(text[at++] == ':'); spaces()
        if (key == "format") {
            require(text[at] == '"') { "Backup format must be a string." }
            return string()
        }
        skipValue(); spaces()
        if (text[at] == ',') at++ else break
    }
    error("Backup format is missing.")
}

internal fun genesisAuthorityRestoreConflicts(incoming: GenesisAuthorityExport, current: GenesisAuthorityExport): List<String> {
    val errors = mutableListOf<String>()
    fun <T> compare(label: String, values: List<T>, existing: List<T>, key: (T) -> String) {
        require(values.size <= 50_000) { "$label exceeds the supported backup record limit." }
        val ids = values.map(key)
        require(ids.size == ids.toSet().size) { "$label contains duplicate identities." }
        ids.forEach(::checkId)
        val local = existing.associateBy(key)
        values.forEach { value -> local[key(value)]?.let { if (it != value) errors += "$label ${key(value)} differs from its saved record. Neither version will be replaced." } }
    }
    compare("Campaign identity", incoming.campaigns, current.campaigns) { it.id }
    compare("Actor authority", incoming.actors, current.actors) { it.actorId }
    compare("Item authority", incoming.items, current.items) { it.itemId }
    compare("Authority receipt", incoming.receipts, current.receipts) { it.commandId }
    compare("Encounter", incoming.encounters, current.encounters) { it.id }
    require(incoming.encounterParticipants.size <= 50_000) { "Encounter participants exceed the supported backup record limit." }
    val participantKeys = incoming.encounterParticipants.map { it.encounterId to it.actorId }
    require(participantKeys.distinct().size == participantKeys.size) { "Encounter contains duplicate participant identities." }
    val localParticipants = current.encounterParticipants.associateBy { it.encounterId to it.actorId }
    incoming.encounterParticipants.forEach { participant ->
        checkId(participant.encounterId); checkId(participant.actorId)
        localParticipants[participant.encounterId to participant.actorId]?.let {
            if (it != participant) errors += "Encounter participant ${participant.actorId} in ${participant.encounterId} differs from its saved record. Neither version will be replaced."
        }
    }
    return errors
}

private fun mergeGenesisAuthority(incoming: GenesisAuthorityExport, current: GenesisAuthorityExport) = GenesisAuthorityExport(
    campaigns = (current.campaigns + incoming.campaigns).distinctBy { it.id }.sortedBy { it.id },
    actors = (current.actors + incoming.actors).distinctBy { it.actorId }.sortedBy { it.actorId },
    items = (current.items + incoming.items).distinctBy { it.itemId }.sortedBy { it.itemId },
    receipts = (current.receipts + incoming.receipts).distinctBy { it.commandId }.sortedBy { it.commandId },
    encounters = (current.encounters + incoming.encounters).distinctBy { it.id }.sortedBy { it.id },
    encounterParticipants = (current.encounterParticipants + incoming.encounterParticipants).distinctBy { it.encounterId to it.actorId }
        .sortedWith(compareBy({ it.encounterId }, { it.position }))
)

private fun mergeGenesisPlayable(incoming: ReforgeBackup, current: ReforgeBackup) = current.copy(
    characters = (current.characters + incoming.characters).distinctBy { it.profileId }.sortedBy { it.profileId },
    changes = (current.changes + incoming.changes).distinctBy { it.commandId }.sortedWith(compareBy({ it.createdAt }, { it.commandId })),
    rolls = (current.rolls + incoming.rolls).distinctBy { it.id }.sortedWith(compareBy({ it.createdAt }, { it.id })),
    drafts = (current.drafts + incoming.drafts).distinctBy { it.profileId }.sortedBy { it.profileId }
)

private fun mergeGenesisCampaign(incoming: LedgerExport, current: LedgerExport): LedgerExport {
    fun <T> merge(a: List<T>, b: List<T>, key: (T) -> String) = (b + a).distinctBy(key).sortedBy(key)
    val a = incoming.records
    val b = current.records
    val records = CampaignRecordsExport(
        projects = merge(a.projects, b.projects) { it.id }, stages = merge(a.stages, b.stages) { it.id },
        requirements = merge(a.requirements, b.requirements) { it.id }, entities = merge(a.entities, b.entities) { it.id },
        facts = merge(a.facts, b.facts) { it.id }, milestones = merge(a.milestones, b.milestones) { it.id },
        links = merge(a.links, b.links) { it.id }, companionStates = merge(a.companionStates, b.companionStates) { it.companionId },
        companionResources = merge(a.companionResources, b.companionResources) { it.id },
        itemDetails = merge(a.itemDetails, b.itemDetails) { it.itemId }, itemProperties = merge(a.itemProperties, b.itemProperties) { it.id },
        audit = merge(a.audit, b.audit) { it.id }, receipts = merge(a.receipts, b.receipts) { it.commandId },
        crafts = merge(a.crafts, b.crafts) { it.commandId }, consumptions = merge(a.consumptions, b.consumptions) { it.id },
        characterProfiles = merge(a.characterProfiles, b.characterProfiles) { it.id },
        characterAbilities = merge(a.characterAbilities, b.characterAbilities) { it.id },
        partySheets = merge(a.partySheets, b.partySheets) { it.profileId }, placements = merge(a.placements, b.placements) { it.itemId },
        wallets = merge(a.wallets, b.wallets) { it.id }, checkpoints = merge(a.checkpoints, b.checkpoints) { it.id },
        externalReferences = merge(a.externalReferences, b.externalReferences) { it.id })
    val archive = CampaignArchiveState(
        references = merge(incoming.archive.references, current.archive.references) { it.id },
        relations = merge(incoming.archive.relations, current.archive.relations) { it.id },
        receipts = merge(incoming.archive.receipts, current.archive.receipts) { it.packageHash },
        backups = merge(incoming.archive.backups, current.archive.backups) { it.packageHash },
        sources = merge(incoming.archive.sources, current.archive.sources) { it.packageHash })
    return current.copy(schema = 5, items = merge(incoming.items, current.items) { it.id },
        events = merge(incoming.events, current.events) { it.commandId }, records = records, archive = archive)
}

/** All three representations and their merge receipt commit in one canonical Room transaction. */
class GenesisAuthorityBackupRepository internal constructor(
    private val db: CampaignLedgerDb,
    private val ready: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val campaignBackup get() = CampaignBackup(db)

    internal suspend fun snapshot(): GenesisAuthorityBackup {
        return GenesisAuthorityBackup(exportedAt = 0, campaign = campaignBackup.snapshot(),
            playable = genesisPlayableBackupRows(db), authority = genesisAuthorityBackupRows(db))
    }

    private fun validate(value: GenesisAuthorityBackup): List<String> {
        val errors = mutableListOf<String>()
        require(value.format == "astravar-connected-campaign" && value.schema == 1) { "Unsupported connected backup format." }
        errors += campaignBackup.validate(value.campaign, emptyGenesisBackup().campaign)
        validateReforgeBackup(value.playable)
        errors += genesisAuthorityRestoreConflicts(value.authority, GenesisAuthorityExport())
        errors += genesisAuthorityBackupErrors(value.authority, value.campaign, value.playable)
        return errors.distinct()
    }

    private fun conflicts(incoming: GenesisAuthorityBackup, current: GenesisAuthorityBackup): List<String> = buildList {
        addAll(validate(incoming))
        addAll(campaignBackup.validate(incoming.campaign, current.campaign))
        addAll(reforgeRestoreConflicts(incoming.playable, current.playable))
        addAll(genesisAuthorityRestoreConflicts(incoming.authority, current.authority))
        // Preview validates the same graph that the transaction will insert. A source-only
        // backup cannot silently create a second writable representation for a managed actor.
        if (isEmpty()) addAll(validate(current.copy(
            campaign = mergeGenesisCampaign(incoming.campaign, current.campaign),
            playable = mergeGenesisPlayable(incoming.playable, current.playable),
            authority = mergeGenesisAuthority(incoming.authority, current.authority))))
    }.distinct()

    suspend fun exportWithProof(): GenesisAuthorityBackupSnapshot = withContext(Dispatchers.Default) {
        ready()
        db.withTransaction {
            val current = snapshot()
            val errors = validate(current)
            require(errors.isEmpty()) { errors.take(10).joinToString("\n") }
            GenesisAuthorityBackupSnapshot(genesisAuthorityBackupJson(current.copy(exportedAt = clock())),
                genesisAuthorityBackupFingerprint(current))
        }
    }

    suspend fun previewRestore(text: String): GenesisAuthorityRestorePreview = withContext(Dispatchers.Default) {
        ready()
        db.withTransaction {
            val current = snapshot()
            try { preview(decodeGenesisAuthorityBackup(text), current) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { GenesisAuthorityRestorePreview(false, "Backup could not be validated.",
                listOf(e.message ?: "Unsupported backup."), "", genesisAuthorityBackupFingerprint(current)) }
        }
    }

    suspend fun recheck(candidate: GenesisAuthorityRestorePreview): GenesisAuthorityRestorePreview = withContext(Dispatchers.Default) {
        ready()
        db.withTransaction {
            val incoming = requireNotNull(candidate.backup) { "Choose the backup again for review." }
            require(genesisAuthorityBackupFingerprint(incoming) == candidate.fingerprint) { "Backup review content changed. Choose the file again." }
            preview(incoming, snapshot())
        }
    }

    private fun preview(incoming: GenesisAuthorityBackup, current: GenesisAuthorityBackup): GenesisAuthorityRestorePreview {
        val errors = conflicts(incoming, current)
        return GenesisAuthorityRestorePreview(errors.isEmpty(), summary(incoming),
            errors, genesisAuthorityBackupFingerprint(incoming), genesisAuthorityBackupFingerprint(current), incoming)
    }

    private fun summary(incoming: GenesisAuthorityBackup) =
        "${incoming.authority.campaigns.size} campaigns, ${incoming.campaign.records.characterProfiles.size} campaign actors, " +
            "${incoming.playable.characters.size} playable sheets, ${incoming.campaign.items.size} physical items, " +
            "${incoming.authority.actors.size} actor authority records and ${incoming.authority.items.size} item authority records. " +
            "Existing different records are retained and block the merge."

    suspend fun restore(candidate: GenesisAuthorityRestorePreview, commandId: String = UUID.randomUUID().toString()): CommandReceipt = withContext(Dispatchers.Default) {
        ready()
        db.withTransaction {
            checkId(commandId)
            require(commandId.length <= 120) { "Restore command identity is too long." }
            val incoming = requireNotNull(candidate.backup)
            require(genesisAuthorityBackupFingerprint(incoming) == candidate.fingerprint) { "Backup review content changed. Review it again." }
            val fingerprint = campaignHash("GENESIS_RESTORE:v1:${candidate.fingerprint}")
            db.recordsDao().receipt(commandId)?.let {
                require(it.kind == "RESTORE" && it.fingerprint == fingerprint && it.recordId == candidate.fingerprint) { "Restore command identity belongs to another operation." }
                return@withTransaction it
            }
            require(candidate.valid && candidate.conflicts.isEmpty()) { "Review a valid connected backup before merging." }
            val current = snapshot()
            check(genesisAuthorityBackupFingerprint(current) == candidate.currentFingerprint) { "Saved records changed after review. Review the backup again." }
            val errors = conflicts(incoming, current)
            require(errors.isEmpty()) { errors.take(10).joinToString("\n") }
            require((current.campaign.events + incoming.campaign.events).none { it.commandId == commandId } &&
                (current.campaign.records.receipts + incoming.campaign.records.receipts).none { it.commandId == commandId } &&
                (current.campaign.records.audit + incoming.campaign.records.audit).none { it.id == commandId } &&
                (current.playable.changes + incoming.playable.changes).none { it.commandId == commandId } &&
                (current.playable.rolls + incoming.playable.rolls).none { it.id == commandId } &&
                (current.authority.receipts + incoming.authority.receipts).none { it.commandId == commandId }) {
                "Restore command identity already belongs to a saved or incoming record."
            }
            campaignBackup.mergeRows(incoming.campaign, current.campaign)
            mergePlayableRows(incoming.playable, current.playable)
            mergeAuthorityRows(incoming.authority, current.authority)
            val now = clock()
            val summary = summary(incoming)
            db.recordsDao().insert(CampaignAudit(commandId, commandId, "backup", candidate.fingerprint,
                "RESTORE", "User-reviewed complete connected backup merge", 0, 1, now, afterJson = summary))
            val receipt = CommandReceipt(commandId, fingerprint, "RESTORE", candidate.fingerprint, 1, now, outcomeJson = summary)
            db.recordsDao().insert(receipt)
            // Recovery must remain possible after the merge, including its own immutable
            // receipt. This complete bounded encode is explicit restore work, not a second store.
            val result = snapshot()
            require(validate(result).isEmpty()) { "Merged backup proof did not validate. No records were restored." }
            genesisAuthorityBackupJson(result)
            receipt
        }
    }

    private suspend fun mergePlayableRows(incoming: ReforgeBackup, current: ReforgeBackup) {
        val target = db.reforgeDao()
        val merged = mergeGenesisPlayable(incoming, current)
        val characters = current.characters.map { it.profileId }.toSet()
        val changes = current.changes.map { it.commandId }.toSet()
        val rolls = current.rolls.map { it.id }.toSet()
        val drafts = current.drafts.map { it.profileId }.toSet()
        merged.characters.filter { it.profileId !in characters }.forEach { target.insert(it) }
        merged.changes.filter { it.commandId !in changes }.forEach { target.insert(it) }
        merged.rolls.filter { it.id !in rolls }.forEach { target.insert(it) }
        merged.drafts.filter { it.profileId !in drafts }.forEach { target.insert(it) }
    }

    private suspend fun mergeAuthorityRows(incoming: GenesisAuthorityExport, current: GenesisAuthorityExport) {
        val target = db.genesisAuthorityDao()
        val merged = mergeGenesisAuthority(incoming, current)
        val campaigns = current.campaigns.map { it.id }.toSet()
        val actors = current.actors.map { it.actorId }.toSet()
        val items = current.items.map { it.itemId }.toSet()
        val receipts = current.receipts.map { it.commandId }.toSet()
        merged.campaigns.filter { it.id !in campaigns }.forEach { target.insert(it) }
        merged.actors.filter { it.actorId !in actors }.forEach { target.insert(it) }
        merged.items.filter { it.itemId !in items }.forEach { target.insert(it) }
        merged.receipts.filter { it.commandId !in receipts }.forEach { target.insert(it) }
        val encounters = current.encounters.map { it.id }.toSet()
        val participants = current.encounterParticipants.map { it.encounterId to it.actorId }.toSet()
        merged.encounters.filter { it.id !in encounters }.forEach { db.genesisEncounterDao().insert(it) }
        merged.encounterParticipants.filter { (it.encounterId to it.actorId) !in participants }.forEach { db.genesisEncounterDao().insert(it) }
    }
}
