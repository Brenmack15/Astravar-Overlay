package com.yazsras.astravar.data

import androidx.room.withTransaction
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class EncounterStrikeRequest(
    val encounterId: String, val encounterRevision: Long, val attackerId: String, val attackerRevision: Long,
    val targetId: String, val targetRevision: Long, val attackId: String,
    val naturalD20: Int, val rolledDamage: Map<String, Int>, val finishTurn: Boolean
)

@Serializable
internal data class EncounterStrikeResult(
    val encounterCommand: String, val encounterRevisionAfter: Long, val toHit: Int, val armorClass: Int,
    val hit: Boolean, val critical: Boolean, val damage: DamageResolution, val targetHp: Int?,
    val targetTempHp: Int?, val concentrationDecisionPending: Boolean, val attackerRevisionAfter: Long, val targetRevisionAfter: Long
)

internal const val ENCOUNTER_STRIKE = "encounter-strike-v1"
private const val ATTACKER_HISTORY = "Resolved encounter attack"
private const val TARGET_HISTORY = "Received encounter attack"

private data class ResolvedStrike(val result: EncounterStrikeResult, val attacker: ReforgeCharacter, val target: ReforgeCharacter)

internal fun encounterStrikeHits(face: Int, bonus: Int, ac: Int): Boolean =
    face == 20 || (face != 1 && Math.addExact(face,bonus) >= ac)

private fun resolveStrike(request: EncounterStrikeRequest, encounter: GenesisEncounterSnapshot, attacker: ReforgeCharacter,
    target: ReforgeCharacter): ResolvedStrike {
    val e = encounter.record
    require(e.id == request.encounterId && e.revision == request.encounterRevision && e.status == GenesisEncounterStatus.ACTIVE)
    require(attacker.id != target.id && e.currentActorId == attacker.id && encounter.participants.any { it.actorId == target.id })
    require(attacker.id == request.attackerId && target.id == request.targetId && !attacker.archived && !target.archived)
    require(attacker.revision == request.attackerRevision && target.revision == request.targetRevision)
    require(request.naturalD20 in 1..20)
    val crit = request.naturalD20 == 20
    val preview = AttackRules.preview(attacker, request.attackId, critical = crit)
    val attackBonus = requireNotNull(preview.attackBonus.value) { "The attack bonus is unknown." }
    val armorClass = requireNotNull(CharacterCalculator.calculate(target).armorClass.value) { "Target AC is unknown." }
    val toHit = Math.addExact(request.naturalD20, attackBonus)
    val hit = encounterStrikeHits(request.naturalD20, attackBonus, armorClass)
    val damage = if (hit) {
        val defenses = preview.components.map { it.type }.distinct().flatMap { kind ->
            CharacterCalculator.calculate(target, CalculationContext(damageType = kind)).defenses.filter { it.type == kind }
        }.distinct()
        AttackRules.resolveDamage(preview, request.rolledDamage, defenses)
    } else {
        require(request.rolledDamage.isEmpty()) { "A miss has no rolled damage." }
        DamageResolution(emptyList())
    }
    val nextAttacker = AttackRules.commitAttack(attacker, preview, hit)
    val nextTarget = if (damage.total > 0) GameplayRules.damage(target, damage.total) else target
    val hp = CharacterCalculator.calculate(nextTarget).currentHp
    val result = EncounterStrikeResult(e.lastCommandId, if (request.finishTurn) Math.addExact(e.revision,1L) else e.revision,
        toHit, armorClass, hit, crit, damage, hp, nextTarget.temporaryHp, damage.total > 0 && target.concentrationSpellId != null, Math.addExact(attacker.revision,1L),
        Math.addExact(target.revision,1L))
    return ResolvedStrike(result, nextAttacker, nextTarget)
}

internal class EncounterStrikeRepository(private val db: CampaignLedgerDb, private val ready: suspend () -> Unit = {}) {
    private suspend fun resolve(request: EncounterStrikeRequest): ResolvedStrike {
        val encounter = requireNotNull(GenesisEncounterRepository(db).get(request.encounterId))
        val campaign = requireNotNull(db.genesisAuthorityDao().campaign(encounter.record.campaignId))
        require(!campaign.archived)
        val repo = ReforgeRepository(db)
        suspend fun actor(id: String): ReforgeCharacter {
            val authority = requireNotNull(db.genesisAuthorityDao().actor(id))
            require(authority.playable && authority.campaignId == campaign.id)
            genesisRequireAffectedActor(db, authority)
            return requireNotNull(repo.get(id)).character
        }
        return resolveStrike(request, encounter, actor(request.attackerId), actor(request.targetId))
    }

    suspend fun preview(request: EncounterStrikeRequest): EncounterStrikeResult {
        ready()
        return db.withTransaction { resolve(request).result }
    }

    suspend fun commit(request: EncounterStrikeRequest, commandId: String): EncounterStrikeResult {
        ready()
        return db.withTransaction {
            checkId(commandId)
            val json = campaignJson.encodeToString(request)
            validateGenesisAuthorityDocument(json)
            val fingerprint = genesisAuthorityFingerprint(ENCOUNTER_STRIKE,json)
            readGenesisAuthorityReceipt(db,commandId)?.let {
                require(it.kind == ENCOUNTER_STRIKE && it.fingerprint == fingerprint && it.requestJson == json)
                return@withTransaction readEncounterStrike(db,it)
            }
            require(db.recordsDao().receipt(commandId) == null && db.ledgerDao().event(commandId) == null &&
                db.reforgeDao().change(commandId) == null && db.reforgeDao().roll(commandId) == null && !genesisAuditIdentityExists(db,commandId))
            val resolved = resolve(request)
            val repo = ReforgeRepository(db)
            repo.save(request.attackerId,request.attackerRevision,resolved.attacker,ATTACKER_HISTORY,genesisChild(commandId,"attacker"))
            repo.save(request.targetId,request.targetRevision,resolved.target,TARGET_HISTORY,genesisChild(commandId,"target"))
            if (request.finishTurn) GenesisEncounterRepository(db).nextTurn(request.encounterId,request.encounterRevision,genesisChild(commandId,"next"))
            val receipt = GenesisAuthorityReceipt(commandId,ENCOUNTER_STRIKE,fingerprint,json,
                campaignJson.encodeToString(resolved.result),System.currentTimeMillis())
            db.genesisAuthorityDao().insert(receipt)
            genesisRequireAuthorityCommand(db,receipt)
            resolved.result
        }
    }
}

internal suspend fun readEncounterStrike(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt): EncounterStrikeResult {
    val result = campaignJson.decodeFromString<EncounterStrikeResult>(receipt.outcomeJson)
    val encounter = requireNotNull(readGenesisAuthorityReceipt(db,result.encounterCommand))
    val before = genesisReadEncounterHistory(db,encounter).after
    val next = readGenesisAuthorityReceipt(db,genesisChild(receipt.commandId,"next"))?.let { genesisReadEncounterHistory(db,it) }
    return validateStrike(receipt,before,next,db.reforgeDao().change(genesisChild(receipt.commandId,"attacker")),
        db.reforgeDao().change(genesisChild(receipt.commandId,"target")))
}

internal fun validateStrike(receipt: GenesisAuthorityReceipt, encounter: GenesisEncounterSnapshot, next: GenesisEncounterOutcome?,
    attackerChange: ReforgeChange?, targetChange: ReforgeChange?): EncounterStrikeResult {
    require(receipt.kind == ENCOUNTER_STRIKE && receipt.fingerprint == genesisAuthorityFingerprint(receipt.kind,receipt.requestJson))
    rejectDuplicateJsonMembers(receipt.requestJson)
    rejectDuplicateJsonMembers(receipt.outcomeJson)
    val r = campaignJson.decodeFromString<EncounterStrikeRequest>(receipt.requestJson)
    val result = campaignJson.decodeFromString<EncounterStrikeResult>(receipt.outcomeJson)
    fun checked(change: ReforgeChange?, label: String, operation: String): Pair<ReforgeCharacter,ReforgeCharacter> {
        requireNotNull(change)
        require(change.commandId == genesisChild(receipt.commandId,label) && change.operation == operation)
        val before = reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(change.beforeJson))
        val after = reforgeJson.decodeFromString<ReforgeSavedCharacter>(change.afterJson)
        require(before.origin == after.origin && before.createdAt == after.createdAt && before.revision == change.priorRevision &&
            after.revision == change.resultingRevision && after.character.revision == Math.addExact(before.character.revision,1))
        return before.character to after.character
    }
    val (a0,a1) = checked(attackerChange,"attacker",ATTACKER_HISTORY)
    val (d0,d1) = checked(targetChange,"target",TARGET_HISTORY)
    val replay = resolveStrike(r,encounter,a0,d0)
    require(replay.result == result && a1 == replay.attacker.copy(revision=result.attackerRevisionAfter) &&
        d1 == replay.target.copy(revision=result.targetRevisionAfter))
    if (r.finishTurn) {
        requireNotNull(next)
        require(next.before == encounter && next.after.record.lastCommandId == genesisChild(receipt.commandId,"next") &&
            next.after.record.status == GenesisEncounterStatus.ACTIVE && next.after.record.revision == result.encounterRevisionAfter)
    } else require(next == null)
    return result
}
