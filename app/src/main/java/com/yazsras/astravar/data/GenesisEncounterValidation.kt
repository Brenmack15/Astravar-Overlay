package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.serialization.encodeToString

internal fun genesisValidateEncounterSnapshot(value: GenesisEncounterSnapshot) {
    val record = value.record
    listOf(record.id, record.campaignId, record.creationCommandId, record.lastCommandId).forEach(::checkId)
    require(record.title.isNotBlank() && record.title == record.title.trim() && record.title.length <= 120 && record.notes.length <= 12_000)
    require(record.revision > 0 && record.createdAt >= 0 && record.updatedAt >= record.createdAt)
    require(value.participants.size <= 100 && value.participants.map { it.actorId }.distinct().size == value.participants.size)
    require(value.participants.map { it.position } == value.participants.indices.toList()) { "Encounter order must be explicit, unique and contiguous." }
    value.participants.forEach {
        checkId(it.actorId)
        require(it.encounterId == record.id && (it.initiative == null || it.initiative in -1000..1000)) { "Invalid encounter participant or recorded initiative." }
    }
    if (record.status == GenesisEncounterStatus.DRAFT) require(record.round == 0 && record.currentActorId == null)
    else require(record.round in 1..1_000_000_000 && value.participants.any { it.actorId == record.currentActorId }) {
        "An active or ended encounter must retain its recorded round and participant."
    }
}

/** Replays tracker metadata only. This function cannot reset an actor's turn, roll, HP or resources. */
internal fun genesisEncounterTransition(request: GenesisEncounterRequest, before: GenesisEncounterSnapshot?,
    members: List<GenesisEncounterMember>, commandId: String, now: Long): GenesisEncounterSnapshot {
    checkId(request.encounterId); checkId(commandId)
    require(now >= 0)
    before?.let {
        genesisValidateEncounterSnapshot(it)
        require(it.record.id == request.encounterId && it.record.revision == request.expectedRevision)
        require(now > it.record.updatedAt) { "Encounter history timestamps must advance." }
    } ?: require(request.expectedRevision == null && request.operation == GenesisEncounterOperation.SAVE_DRAFT)
    val revision = before?.record?.revision?.let { Math.addExact(it, 1L) } ?: 1L
    fun memberOrder(actorIds: List<String>, campaignId: String) {
        require(members.map { it.actorId } == actorIds && members.all { it.campaignId == campaignId && it.authorityRevision > 0 }) {
            "Encounter participants require exact original campaign membership proof."
        }
    }
    val next = when (request.operation) {
        GenesisEncounterOperation.SAVE_DRAFT -> {
            require(request.startReview == null && (before == null || before.record.status == GenesisEncounterStatus.DRAFT)) {
                "End the encounter and explicitly reopen setup before changing its roster."
            }
            val draft = requireNotNull(request.draft)
            checkId(draft.campaignId)
            memberOrder(draft.participants.map { it.actorId }, draft.campaignId)
            require(draft.participants.zip(members).all { (input, member) -> input.expectedAuthorityRevision == member.authorityRevision })
            val record = GenesisEncounterRecord(request.encounterId, draft.campaignId, draft.title, draft.notes,
                revision = revision, creationCommandId = before?.record?.creationCommandId ?: commandId,
                lastCommandId = commandId, createdAt = before?.record?.createdAt ?: now, updatedAt = now)
            GenesisEncounterSnapshot(record, draft.participants.mapIndexed { position, input ->
                GenesisEncounterParticipant(record.id, input.actorId, input.initiative, position)
            })
        }
        GenesisEncounterOperation.START -> {
            val current = requireNotNull(before)
            require(request.draft == null && current.record.status == GenesisEncounterStatus.DRAFT && current.participants.isNotEmpty()) {
                "Choose at least one participant and review the encounter setup before starting."
            }
            val review = requireNotNull(request.startReview)
            require(review.snapshot == current && review.members == members && review.fingerprint == genesisEncounterReviewFingerprint(review)) {
                "The encounter start review changed. Review the latest setup."
            }
            memberOrder(current.participants.map { it.actorId }, current.record.campaignId)
            current.copy(record = current.record.copy(status = GenesisEncounterStatus.ACTIVE, round = 1,
                currentActorId = current.participants.first().actorId, revision = revision, lastCommandId = commandId, updatedAt = now))
        }
        GenesisEncounterOperation.NEXT, GenesisEncounterOperation.END, GenesisEncounterOperation.REOPEN_SETUP -> {
            val current = requireNotNull(before)
            require(request.draft == null && request.startReview == null && members.isEmpty())
            val record = current.record.copy(revision = revision, lastCommandId = commandId, updatedAt = now)
            val changed = when (request.operation) {
                GenesisEncounterOperation.NEXT -> {
                    require(record.status == GenesisEncounterStatus.ACTIVE) { "Only an active encounter can move to its next participant." }
                    val position = current.participants.indexOfFirst { it.actorId == record.currentActorId }
                    val nextPosition = (position + 1) % current.participants.size
                    record.copy(currentActorId = current.participants[nextPosition].actorId,
                        round = if (nextPosition == 0) Math.addExact(record.round, 1) else record.round)
                }
                GenesisEncounterOperation.END -> {
                    require(record.status == GenesisEncounterStatus.ACTIVE) { "Only an active encounter can be ended." }
                    record.copy(status = GenesisEncounterStatus.ENDED)
                }
                else -> {
                    require(record.status == GenesisEncounterStatus.ENDED) { "Only an ended encounter can explicitly reopen setup." }
                    record.copy(status = GenesisEncounterStatus.DRAFT, round = 0, currentActorId = null)
                }
            }
            current.copy(record = changed)
        }
    }
    genesisValidateEncounterSnapshot(next)
    return next
}

/** Validate the frozen decision itself; later membership and gameplay are deliberately irrelevant. */
internal fun genesisEncounterMemberAuthority(receipt: GenesisAuthorityReceipt): GenesisActorAuthority {
    checkId(receipt.commandId)
    require(receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind, receipt.requestJson))
    rejectDuplicateJsonMembers(receipt.requestJson); rejectDuplicateJsonMembers(receipt.outcomeJson)
    fun request() = campaignJson.decodeFromString<List<String?>>(receipt.requestJson).also {
        require(it.size == 5) { "Unsupported historical membership request shape." }
    }
    return when (receipt.kind) {
        "create-actor", "register-actor", "accept-actor" -> {
            val outcome = campaignJson.decodeFromString<GenesisActorCreationOutcome>(receipt.outcomeJson)
            val actor = outcome.actor
            require(actor.activationCommandId == receipt.commandId && actor.lastCommandId == receipt.commandId &&
                actor.actorId == outcome.profile.id && actor.playable == (outcome.playable != null)) {
                "Historical membership is not bound to its original activation."
            }
            outcome.playable?.let { require(it.character.id == actor.actorId && actor.playableRevision == it.revision) }
            // These are the same frozen request/outcome invariants used by complete
            // authority backup validation, without reading today's actor or campaign.
            when (receipt.kind) {
                "create-actor" -> {
                    val input = request()
                    require(outcome.profile.name == input[0] && actor.role.name == input[1] &&
                        actor.campaignId == input[2] && actor.ownerActorId == input[3] && actor.revision == 1L) {
                        "Historical membership differs from its creation request."
                    }
                    val requested = input[4]?.let { reforgeJson.decodeFromString<ReforgeCharacter>(it) }
                    require((requested == null) == (outcome.playable == null))
                    requested?.let { require(outcome.playable?.character == it.copy(revision = 1)) {
                        "Historical activation changed its requested mechanics."
                    } }
                }
                "register-actor" -> {
                    val input = request()
                    require(actor.actorId == input[0] && actor.role.name == input[2] && actor.campaignId == input[3] &&
                        actor.ownerActorId == input[4] && actor.revision == 1L && !actor.playable &&
                        outcome.profile.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L)) {
                        "Historical membership differs from its registration request."
                    }
                }
                else -> {
                    val review = campaignJson.decodeFromString<GenesisActorAuthorityReview>(receipt.requestJson)
                    require(review.fingerprint == genesisActorAuthorityReviewFingerprint(review))
                    genesisValidateLegacyModifierResolutions(review.playable.character, review.source?.abilities.orEmpty(), review.legacyModifierResolutions)
                    require(actor.actorId == review.actorId && actor.role == review.role && actor.campaignId == review.campaignId &&
                        actor.ownerActorId == review.ownerActorId && actor.revision == Math.addExact(review.expectedAuthorityRevision ?: 0L, 1L)) {
                        "Historical membership differs from its reviewed association."
                    }
                    val result = requireNotNull(outcome.playable)
                    require(result.character == review.playable.character.copy(revision = Math.addExact(review.playable.revision, 1L)) &&
                        result.origin == review.playable.origin && result.createdAt == review.playable.createdAt) {
                        "Historical activation changed unreviewed mechanics or provenance."
                    }
                }
            }
            actor
        }
        "update-association" -> {
            val input = request()
            campaignJson.decodeFromString<GenesisActorAuthority>(receipt.outcomeJson).also { actor ->
                require(actor.actorId == input[0] && actor.role.name == input[2] && actor.campaignId == input[3] &&
                    actor.ownerActorId == input[4] && actor.revision == Math.addExact(requireNotNull(input[1]).toLong(), 1L)) {
                    "Historical membership differs from its association request."
                }
            }
        }
        else -> error("Encounter membership does not reference an actor association decision.")
    }.also { require(it.lastCommandId == receipt.commandId) }
}

internal fun genesisValidateEncounterReceipt(receipt: GenesisAuthorityReceipt,
    membershipReceipt: (String) -> GenesisAuthorityReceipt?): GenesisEncounterOutcome {
    require(receipt.kind == GENESIS_ENCOUNTER_COMMAND && receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind, receipt.requestJson))
    rejectDuplicateJsonMembers(receipt.requestJson); rejectDuplicateJsonMembers(receipt.outcomeJson)
    val request = campaignJson.decodeFromString<GenesisEncounterRequest>(receipt.requestJson)
    val outcome = campaignJson.decodeFromString<GenesisEncounterOutcome>(receipt.outcomeJson)
    require(genesisEncounterTransition(request, outcome.before, outcome.members, receipt.commandId, receipt.createdAt) == outcome.after) {
        "Encounter outcome differs from its original tracker command."
    }
    outcome.members.forEach { member ->
        checkId(member.membershipCommandId)
        val actor = genesisEncounterMemberAuthority(requireNotNull(membershipReceipt(member.membershipCommandId)) {
            "Encounter participant lost its original campaign membership decision."
        })
        require(actor.actorId == member.actorId && actor.revision == member.authorityRevision && actor.role == member.role && actor.campaignId == member.campaignId) {
            "Encounter participant differs from its frozen campaign membership."
        }
    }
    return outcome
}

internal fun genesisEncounterBackupErrors(authority: GenesisAuthorityExport): List<String> {
    val errors = mutableListOf<String>()
    fun verify(label: String, body: () -> Unit) { try { body() } catch (e: Exception) { errors += "$label: ${e.message ?: "Invalid encounter history"}" } }
    val receipts = authority.receipts.associateBy { it.commandId }
    val records = authority.encounters.associateBy { it.id }
    val participantsByEncounter = authority.encounterParticipants.groupBy { it.encounterId }
    val actorIds = authority.actors.map { it.actorId }.toSet()
    val campaignIds = authority.campaigns.map { it.id }.toSet()
    val outcomes = mutableMapOf<String, GenesisEncounterOutcome>()
    authority.receipts.filter { it.kind == GENESIS_ENCOUNTER_COMMAND }.forEach { receipt -> verify("Encounter receipt ${receipt.commandId}") {
        val outcome = genesisValidateEncounterReceipt(receipt, receipts::get)
        require(outcome.after.record.id in records) { "Encounter history is missing its retained tracker record." }
        listOfNotNull(outcome.before, outcome.after).forEach { snapshot ->
            require(snapshot.record.campaignId in campaignIds && snapshot.participants.all { it.actorId in actorIds }) { "Historical encounter references are missing." }
        }
        outcomes[receipt.commandId] = outcome
    } }
    val histories = outcomes.values.groupBy { it.after.record.id }
    records.values.forEach { record -> verify("Encounter ${record.id}") {
        val current = GenesisEncounterSnapshot(record, participantsByEncounter[record.id].orEmpty().sortedBy { it.position })
        genesisValidateEncounterSnapshot(current)
        require(record.campaignId in campaignIds && current.participants.all { it.actorId in actorIds })
        val history = histories[record.id].orEmpty().sortedBy { it.after.record.revision }
        require(history.isNotEmpty() && history.first().before == null && history.first().after.record.revision == 1L)
        history.zipWithNext().forEach { (prior, next) -> require(next.before == prior.after) { "Encounter history is incomplete or branches." } }
        require(history.last().after == current && history.last().after.record.lastCommandId == record.lastCommandId) {
            "Current encounter differs from its latest immutable outcome."
        }
    } }
    if (authority.encounterParticipants.any { it.encounterId !in records }) errors += "Encounter participant has no retained tracker."
    return errors
}
