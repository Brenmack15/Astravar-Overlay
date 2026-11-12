package com.yazsras.astravar.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Campaign-scoped manual tracker. Every command leaves all actor mechanics untouched. */
class GenesisEncounterRepository internal constructor(private val db: CampaignLedgerDb,
    private val ready: suspend () -> Unit = {}, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.genesisEncounterDao()
    fun observe(campaignId: String): Flow<List<GenesisEncounterSnapshot>> = flow {
        checkId(campaignId); ready()
        emitAll(dao.observe(campaignId).map { snapshots -> snapshots.map { it.ordered().also(::genesisValidateEncounterSnapshot) } })
    }
    suspend fun get(id: String): GenesisEncounterSnapshot? = transaction {
        checkId(id); dao.get(id)?.ordered()?.also { genesisRequireEncounterCurrent(db, it) }
    }
    private suspend fun <T> transaction(block: suspend () -> T): T { ready(); return db.withTransaction { block() } }

    suspend fun saveDraft(id: String, expectedRevision: Long?, campaignId: String, title: String, notes: String,
        participants: List<GenesisEncounterParticipantInput>, commandId: String = UUID.randomUUID().toString()): GenesisEncounterSnapshot = transaction {
        val draft = GenesisEncounterDraft(campaignId, title.trim(), notes, participants)
        commit(GenesisEncounterRequest(GenesisEncounterOperation.SAVE_DRAFT, id, expectedRevision, draft), commandId)
    }

    suspend fun reviewStart(id: String, expectedRevision: Long): GenesisEncounterStartReview = transaction {
        val current = requireNotNull(get(id)) { "The encounter no longer exists." }
        check(current.record.revision == expectedRevision) { "The encounter changed. Review its latest setup." }
        require(current.record.status == GenesisEncounterStatus.DRAFT && current.participants.isNotEmpty()) {
            "Choose at least one participant before reviewing the encounter start."
        }
        activeCampaign(current.record.campaignId)
        val members = current.participants.map { member(it.actorId, current.record.campaignId) }
        val review = GenesisEncounterStartReview(current, members, "")
        review.copy(fingerprint = genesisEncounterReviewFingerprint(review))
    }

    suspend fun start(review: GenesisEncounterStartReview, commandId: String = UUID.randomUUID().toString()): GenesisEncounterSnapshot = transaction {
        commit(GenesisEncounterRequest(GenesisEncounterOperation.START, review.snapshot.record.id, review.snapshot.record.revision,
            startReview = review), commandId)
    }
    suspend fun nextTurn(id: String, expectedRevision: Long, commandId: String = UUID.randomUUID().toString()): GenesisEncounterSnapshot = transaction {
        commit(GenesisEncounterRequest(GenesisEncounterOperation.NEXT, id, expectedRevision), commandId)
    }
    suspend fun end(id: String, expectedRevision: Long, commandId: String = UUID.randomUUID().toString()): GenesisEncounterSnapshot = transaction {
        commit(GenesisEncounterRequest(GenesisEncounterOperation.END, id, expectedRevision), commandId)
    }
    /** Explicitly opens a new setup; ordinary reopening through get/observe never resets the tracker. */
    suspend fun reopenSetup(id: String, expectedRevision: Long, commandId: String = UUID.randomUUID().toString()): GenesisEncounterSnapshot = transaction {
        commit(GenesisEncounterRequest(GenesisEncounterOperation.REOPEN_SETUP, id, expectedRevision), commandId)
    }

    private suspend fun activeCampaign(id: String) {
        checkId(id)
        require(db.genesisAuthorityDao().campaign(id)?.archived == false) { "Choose an existing active campaign for encounter setup." }
    }
    private suspend fun member(actorId: String, campaignId: String, expectedRevision: Long? = null): GenesisEncounterMember {
        checkId(actorId)
        val actor = requireNotNull(db.genesisAuthorityDao().actor(actorId)) { "Connect the participant's campaign identity first." }
        require(actor.campaignId == campaignId) { "Each selected participant must explicitly belong to this campaign." }
        check(expectedRevision == null || actor.revision == expectedRevision) { "Participant membership changed. Review the current roster." }
        genesisRequireAffectedActor(db, actor)
        // Archived and source-only actors remain explicit references, with no inferred playable state.
        return GenesisEncounterMember(actorId, actor.revision, actor.role, campaignId, actor.lastCommandId)
    }

    private suspend fun commit(request: GenesisEncounterRequest, commandId: String): GenesisEncounterSnapshot {
        checkId(commandId); checkId(request.encounterId)
        val json = campaignJson.encodeToString(request)
        validateGenesisAuthorityDocument(json)
        val fingerprint = genesisAuthorityFingerprint(GENESIS_ENCOUNTER_COMMAND, json)
        readGenesisAuthorityReceipt(db, commandId)?.let { previous ->
            require(previous.kind == GENESIS_ENCOUNTER_COMMAND && previous.requestJson == json && previous.fingerprint == fingerprint) {
                "Command identity was reused with different encounter content. Nothing changed."
            }
            return genesisReadEncounterHistory(db, previous).after
        }
        require(db.recordsDao().receipt(commandId) == null && db.ledgerDao().event(commandId) == null &&
            db.reforgeDao().change(commandId) == null && db.reforgeDao().roll(commandId) == null && !genesisAuditIdentityExists(db, commandId)) {
            "Encounter command identity belongs to another operation."
        }
        val before = dao.get(request.encounterId)?.ordered()
        check(before?.record?.revision == request.expectedRevision) { "The encounter changed. Reopen its current state before saving." }
        before?.let { genesisRequireEncounterCurrent(db, it) }
        val members = when (request.operation) {
            GenesisEncounterOperation.SAVE_DRAFT -> {
                val draft = requireNotNull(request.draft)
                require(draft.participants.size <= 100 && draft.participants.map { it.actorId }.distinct().size == draft.participants.size)
                activeCampaign(draft.campaignId)
                draft.participants.map { member(it.actorId, draft.campaignId, it.expectedAuthorityRevision) }
            }
            GenesisEncounterOperation.START -> {
                val reviewed = requireNotNull(request.startReview)
                check(reviewStart(request.encounterId, requireNotNull(request.expectedRevision)) == reviewed) {
                    "Encounter setup or participant membership changed. Review the start again."
                }
                reviewed.members
            }
            else -> emptyList()
        }
        val now = maxOf(clock(), before?.record?.updatedAt?.let { Math.addExact(it, 1L) } ?: 0L)
        val after = genesisEncounterTransition(request, before, members, commandId, now)
        if (before == null) dao.insert(after.record) else dao.update(after.record)
        if (before?.participants != after.participants) {
            dao.clearParticipants(after.record.id)
            after.participants.forEach { dao.insert(it) }
        }
        val outcome = GenesisEncounterOutcome(before, after, members)
        val receipt = GenesisAuthorityReceipt(commandId, GENESIS_ENCOUNTER_COMMAND, fingerprint, json,
            campaignJson.encodeToString(outcome), now)
        db.genesisAuthorityDao().insert(receipt)
        genesisRequireAuthorityCommand(db, receipt)
        return after
    }
}

internal suspend fun genesisReadEncounterOutcome(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt): GenesisEncounterOutcome {
    val candidate = campaignJson.decodeFromString<GenesisEncounterOutcome>(receipt.outcomeJson)
    val memberships = candidate.members.map { it.membershipCommandId }.distinct().associateWith { readGenesisAuthorityReceipt(db, it) }
    return genesisValidateEncounterReceipt(receipt, memberships::get)
}

internal suspend fun genesisRequireEncounterCurrent(db: CampaignLedgerDb, current: GenesisEncounterSnapshot) {
    genesisValidateEncounterSnapshot(current)
    val receipt = requireNotNull(readGenesisAuthorityReceipt(db, current.record.lastCommandId)) { "Encounter's latest history is missing." }
    val outcome = genesisReadEncounterHistory(db, receipt)
    require(outcome.after == current) { "Encounter state differs from its latest immutable command." }
}

/** Scoped ancestry prevents extending a broken tracker; unrelated campaign histories are never exported. */
internal suspend fun genesisReadEncounterHistory(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt): GenesisEncounterOutcome {
    val latest = genesisReadEncounterOutcome(db, receipt)
    val seen = mutableSetOf(receipt.commandId)
    var before = latest.before
    while (before != null) {
        val id = before.record.lastCommandId
        require(seen.add(id)) { "Encounter history contains a cycle." }
        val prior = requireNotNull(readGenesisAuthorityReceipt(db, id)) { "Encounter's previous history is missing." }
        val outcome = genesisReadEncounterOutcome(db, prior)
        require(outcome.after == before) { "Encounter history does not join its previous revision." }
        before = outcome.before
    }
    return latest
}

internal suspend fun genesisRequireEncounterCommand(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt) {
    val outcome = genesisReadEncounterOutcome(db, receipt)
    require(db.genesisEncounterDao().get(outcome.after.record.id)?.ordered() == outcome.after) { "Encounter save failed current-record readback." }
    genesisRequireEncounterCurrent(db, outcome.after)
}
