package com.yazsras.astravar.data

import androidx.room.withTransaction
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.encodeToString
import java.util.UUID

internal const val GENESIS_AUTHORITY_SOURCE = "Genesis connected authority v1"
internal fun genesisChild(commandId: String, label: String) = "genesis:${campaignHash("$commandId:$label")}"
internal fun genesisAuthorityFingerprint(kind: String, request: String) =
    campaignHash(campaignJson.encodeToString(listOf("genesis-authority-v1", kind, request)))

internal fun genesisActorAuthorityReviewFingerprint(review: GenesisActorAuthorityReview) = campaignHash(campaignJson.encodeToString(review.copy(fingerprint = "")))

/** Explicit authority commands. Existing records are never enrolled by startup, names, or matching IDs. */
class GenesisAuthorityRepository(private val db: CampaignLedgerDb, private val ready: suspend () -> Unit = {}) {
    private val dao get() = db.genesisAuthorityDao()
    private val campaign get() = CampaignLedgerRepository(db)
    private val local get() = ReforgeRepository(db)
    val backup = GenesisAuthorityBackupRepository(db, ready)
    val campaigns: Flow<List<GenesisCampaignRecord>> = flow { ready(); emitAll(dao.campaigns()) }
    val actorAssociations: Flow<List<GenesisActorAuthority>> = flow { ready(); emitAll(dao.actors()) }
    suspend fun actor(actorId: String): GenesisActorAuthority? { ready(); return dao.actor(actorId) }
    private suspend fun <T> transaction(block: suspend () -> T): T { ready(); return db.withTransaction { block() } }
    private suspend fun retry(commandId: String, kind: String, request: String): GenesisAuthorityReceipt? {
        checkId(commandId)
        readGenesisAuthorityReceipt(db, commandId)?.let {
            require(it.kind == kind && it.requestJson == request && it.fingerprint == genesisAuthorityFingerprint(kind, request)) {
                "Command identity was reused with different content. Nothing changed."
            }; return it
        }
        require(db.recordsDao().receipt(commandId) == null && db.ledgerDao().event(commandId) == null &&
            db.reforgeDao().change(commandId) == null && db.reforgeDao().roll(commandId) == null) { "Command identity belongs to another operation." }
        return null
    }
    private suspend fun record(commandId: String, kind: String, request: String, outcome: String) {
        val receipt = GenesisAuthorityReceipt(commandId, kind, genesisAuthorityFingerprint(kind, request), request, outcome, System.currentTimeMillis())
        dao.insert(receipt)
        genesisRequireAuthorityCommand(db, receipt)
    }
    suspend fun createCampaign(name: String, notes: String = "", commandId: String = UUID.randomUUID().toString()): GenesisCampaignRecord = transaction {
        val clean = name.trim(); require(clean.isNotBlank() && clean.length <= 120 && notes.length <= 12_000) { "Enter a campaign name and supported notes." }
        val request = campaignJson.encodeToString(listOf(clean, notes))
        retry(commandId, "create-campaign", request)?.let { return@transaction campaignJson.decodeFromString<GenesisCampaignRecord>(it.outcomeJson) }
        val now = System.currentTimeMillis()
        val value = GenesisCampaignRecord("genesis-campaign-${campaignHash(commandId).take(48)}", clean, notes, createdAt = now, updatedAt = now, commandId = commandId)
        dao.insert(value); record(commandId, "create-campaign", request, campaignJson.encodeToString(value)); value
    }
    suspend fun createActor(name: String, role: GenesisActorRole, campaignId: String? = null, ownerActorId: String? = null,
        playable: ReforgeCharacter? = null, commandId: String = UUID.randomUUID().toString()): GenesisActorCreationOutcome = transaction {
        val clean = name.trim(); require(clean.isNotBlank() && clean.length <= 120) { "Enter an actor name of at most 120 characters." }
        val request = campaignJson.encodeToString(listOf(clean, role.name, campaignId, ownerActorId, playable?.let { reforgeJson.encodeToString(it) }))
        retry(commandId, "create-actor", request)?.let { return@transaction campaignJson.decodeFromString<GenesisActorCreationOutcome>(it.outcomeJson) }
        val id = playable?.id ?: "genesis-actor-${campaignHash(commandId).take(48)}"
        validateAssociation(id, role, campaignId, ownerActorId)
        require(db.recordsDao().characterProfile(id) == null && dao.actor(id) == null && db.reforgeDao().character(id) == null && db.recordsDao().entity(id) == null && db.recordsDao().companion(id) == null) { "This identity already exists. Review its existing records before connecting it." }
        playable?.let { require(it.name == clean && it.revision == 0L); validateCharacter(it) }
        withGenesisProjection(db) {
            campaign.records.saveProfile(CharacterProfile(name = clean, id = id), source = GENESIS_AUTHORITY_SOURCE, commandId = genesisChild(commandId, "identity"))
            ensureRole(id, clean, role, ownerActorId, commandId)
        }
        val saved = playable?.let { local.createWithOrigin(it, ReforgeOrigin(label = GENESIS_AUTHORITY_SOURCE), "$GENESIS_AUTHORITY_SOURCE: create actor", genesisChild(commandId, "playable")) }
        val now = System.currentTimeMillis()
        val association = GenesisActorAuthority(id, role, campaignId, ownerActorId, saved != null, activationCommandId = commandId,
            lastCommandId = commandId, resourceBindingsJson = genesisResourceBindings(saved?.character, db.recordsDao().allCompanionResources()), createdAt = now, updatedAt = now)
        dao.insert(association)
        if (saved != null) genesisProjectCharacter(db, null, saved, commandId, activation = true)
        val outcome = GenesisActorCreationOutcome(requireNotNull(dao.actor(id)), requireNotNull(db.recordsDao().characterProfile(id)), saved)
        record(commandId, "create-actor", request, campaignJson.encodeToString(outcome)); outcome
    }
    suspend fun createItem(ownerActorId: String, expectedPlayableRevision: Long, definition: ItemDefinition, instance: ItemInstance,
        commandId: String = UUID.randomUUID().toString()): ReforgeSavedCharacter = transaction {
        val request = campaignJson.encodeToString(listOf(ownerActorId, expectedPlayableRevision.toString(), reforgeJson.encodeToString(definition), reforgeJson.encodeToString(instance)))
        retry(commandId, "create-item", request)?.let { return@transaction reforgeJson.decodeFromString<ReforgeSavedCharacter>(it.outcomeJson) }
        require(dao.actor(ownerActorId)?.playable == true) { "Review and connect this character before creating connected equipment." }
        val current = requireNotNull(local.get(ownerActorId)) { "The playable character is missing." }
        check(current.revision == expectedPlayableRevision) { "The character changed. Review its latest inventory." }
        require(instance.definitionId == definition.id && current.character.items.none { it.id == instance.id } && db.ledgerDao().getItem(instance.id) == null && readGenesisAuthorityItem(db, instance.id) == null) { "This physical item identity already exists. Use its explicit acquisition or transfer workflow." }
        val definitions = current.character.itemDefinitions
        require(definitions.none { it.id == definition.id }) { "This definition identity already exists. Edit its existing items instead." }
        val saved = withGenesisProjection(db) { local.save(ownerActorId, expectedPlayableRevision, current.character.copy(itemDefinitions = definitions + definition,
            items = current.character.items + instance), "$GENESIS_AUTHORITY_SOURCE: create item", genesisChild(commandId, "playable")) }
        // The projector's child mutation is immutable; bind the visible creation to this parent receipt.
        readGenesisAuthorityItem(db, instance.id)?.let { dao.update(it.copy(activationCommandId = commandId)) }
        record(commandId, "create-item", request, reforgeJson.encodeToString(saved)); saved
    }
    suspend fun reviewActorAuthority(actorId: String, role: GenesisActorRole, campaignId: String? = null,
        ownerActorId: String? = null, legacyModifierResolutions: List<GenesisLegacyModifierResolution> = emptyList()): GenesisActorAuthorityReview = transaction {
        validateAssociation(actorId, role, campaignId, ownerActorId)
        val saved = requireNotNull(local.get(actorId)) { "Configure and save this actor's reviewed playable mechanics first." }
        val source = db.recordsDao().characterProfile(actorId)?.let { campaign.readCharacterSnapshot(actorId) }
        validateEnrollment(saved, source)
        // Empty initial previews retain unresolved source modifiers for explicit review.
        // Once any proof is supplied it must resolve the entire frozen set correctly.
        if (legacyModifierResolutions.isNotEmpty()) genesisValidateLegacyModifierResolutions(saved.character, source?.abilities.orEmpty(), legacyModifierResolutions)
        val projected = genesisProfile(saved.character, source?.profile)
        val sourceCompanion = db.recordsDao().companion(actorId)
        val sourceEntity = db.recordsDao().entity(actorId)
        val sourceResources = db.recordsDao().allCompanionResources().filter { it.companionId == actorId }
        val differences = buildList {
            fun difference(label: String, old: Any?, next: Any?) { if (old != next) add(GenesisFieldDifference(label, old?.toString() ?: "Unknown", next?.toString() ?: "Unknown")) }
            difference("Name", source?.profile?.name, projected.name)
            if (role == GenesisActorRole.COMPANION) {
                difference("Companion name", sourceCompanion?.name, projected.name)
                saved.character.resources.forEach { pool ->
                    val prior = sourceResources.singleOrNull { it.name == pool.name }
                    difference("Companion ${pool.name} current", prior?.current, pool.current)
                    difference("Companion ${pool.name} maximum", prior?.maximum, CharacterCalculator.calculate(saved.character).resourceMaxima[pool.id])
                }
            }
            if (role == GenesisActorRole.NPC) difference("NPC name", sourceEntity?.name, projected.name)
            difference("Current HP", source?.profile?.currentHp, projected.currentHp)
            difference("Maximum HP", source?.profile?.maximumHp, projected.maximumHp)
            difference("Temporary HP", source?.profile?.temporaryHp, projected.temporaryHp)
            difference("Armor Class", source?.profile?.armorClass, projected.armorClass)
            difference("Level", source?.profile?.level, projected.level)
            Stat.entries.forEach { stat -> difference(stat.name, source?.abilities?.find { it.key == stat.name }?.score, CharacterCalculator.calculate(saved.character).abilities.getValue(stat).effective.value) }
            source?.abilities.orEmpty().filter { it.magicModifier != 0 }.forEach { difference("${it.key} recorded magical modifier", it.magicModifier, 0) }
            saved.character.items.forEach { item ->
                val old = source?.items?.find { it.id == item.id }
                difference("${saved.character.itemDefinitions.find { it.id == item.definitionId }?.name ?: item.id} quantity", old?.quantity, item.quantity)
                difference("${old?.title ?: item.id} equipment", source?.placements?.find { it.itemId == item.id }?.state, genesisItemState(item))
            }
            val sourceSheet = source?.sheet?.sheet ?: PartySheetData()
            val nextSheet = genesisSheet(saved.character, sourceSheet)
            difference("Conditions", sourceSheet.conditions, nextSheet.conditions)
            difference("Proficiency bonus", sourceSheet.proficiencyBonus, nextSheet.proficiencyBonus)
            difference("Initiative", sourceSheet.initiative, nextSheet.initiative)
            nextSheet.resources.forEach { resource ->
                val prior = sourceSheet.resources.singleOrNull { it.name == resource.name }
                difference("${resource.name} current", prior?.current, resource.current)
                difference("${resource.name} maximum", prior?.maximum, resource.maximum)
            }
            saved.character.items.forEach { instance ->
                val definition = saved.character.itemDefinitions.single { it.id == instance.definitionId }
                val prior = source?.items?.find { it.id == instance.id }
                val placement = source?.placements?.find { it.itemId == instance.id }
                difference("${definition.name} name", prior?.title, definition.name)
                difference("${definition.name} description", prior?.description, definition.description)
                difference("${definition.name} container", placement?.containerItemId, instance.containerId)
            }
        }
        val prior = dao.actor(actorId)
        val draft = GenesisActorAuthorityReview(actorId, role, campaignId, ownerActorId, prior?.revision, saved, source, differences, "")
        val supplemental = campaignJson.encodeToString(listOf(sourceCompanion?.let { campaignJson.encodeToString(it) },
            sourceEntity?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(sourceResources)))
        draft.copy(supplementalSourceJson = supplemental, legacyModifierResolutions = legacyModifierResolutions).let { it.copy(fingerprint = genesisActorAuthorityReviewFingerprint(it)) }
    }
    suspend fun acceptActorAuthority(review: GenesisActorAuthorityReview, commandId: String = UUID.randomUUID().toString()): GenesisActorCreationOutcome = transaction {
        val request = campaignJson.encodeToString(review)
        retry(commandId, "accept-actor", request)?.let { return@transaction campaignJson.decodeFromString<GenesisActorCreationOutcome>(it.outcomeJson) }
        genesisValidateLegacyModifierResolutions(review.playable.character, review.source?.abilities.orEmpty(), review.legacyModifierResolutions)
        check(reviewActorAuthority(review.actorId, review.role, review.campaignId, review.ownerActorId, review.legacyModifierResolutions) == review) { "These records changed after review. Review the latest differences before accepting." }
        val old = dao.actor(review.actorId)
        withGenesisProjection(db) {
            if (review.source == null) campaign.records.saveProfile(CharacterProfile(name = review.playable.character.name, id = review.actorId), source = GENESIS_AUTHORITY_SOURCE, commandId = genesisChild(commandId, "identity"))
            ensureRole(review.actorId, review.playable.character.name, review.role, review.ownerActorId, commandId)
        }
        // Retain origin byte-for-byte. A new immutable no-op revision marks the explicit authority decision.
        val saved = withGenesisProjectionDeferred(db) { local.save(review.actorId, review.playable.revision, review.playable.character,
            "$GENESIS_AUTHORITY_SOURCE: accept actor", genesisChild(commandId, "playable")) }
        val now = System.currentTimeMillis()
        val next = GenesisActorAuthority(review.actorId, review.role, review.campaignId, review.ownerActorId, true,
            (old?.revision ?: 0) + 1, commandId, commandId, resourceBindingsJson = genesisResourceBindings(saved.character, db.recordsDao().allCompanionResources()), createdAt = old?.createdAt ?: now, updatedAt = now)
        if (old == null) dao.insert(next) else dao.update(next)
        genesisProjectCharacter(db, null, saved, commandId, activation = true, sourceProof = request)
        val outcome = GenesisActorCreationOutcome(requireNotNull(dao.actor(review.actorId)), requireNotNull(db.recordsDao().characterProfile(review.actorId)), saved)
        record(commandId, "accept-actor", request, campaignJson.encodeToString(outcome)); outcome
    }
    suspend fun registerActorAssociation(actorId: String, expectedProfileRevision: Long, role: GenesisActorRole,
        campaignId: String?, ownerActorId: String?, commandId: String = UUID.randomUUID().toString()): GenesisActorCreationOutcome = transaction {
        val request = campaignJson.encodeToString(listOf(actorId, expectedProfileRevision.toString(), role.name, campaignId, ownerActorId))
        retry(commandId, "register-actor", request)?.let { return@transaction campaignJson.decodeFromString<GenesisActorCreationOutcome>(it.outcomeJson) }
        validateAssociation(actorId, role, campaignId, ownerActorId)
        val profile = requireNotNull(db.recordsDao().characterProfile(actorId)) { "This source actor is missing." }
        check(profile.revision == expectedProfileRevision && dao.actor(actorId) == null) { "The source actor or its membership changed. Review it again." }
        withGenesisProjection(db) {
            campaign.records.saveProfile(profile, profile.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "identity"))
            ensureRole(actorId, profile.name, role, ownerActorId, commandId)
        }
        val now = System.currentTimeMillis()
        val value = GenesisActorAuthority(actorId, role, campaignId, ownerActorId, activationCommandId = commandId, lastCommandId = commandId, createdAt = now, updatedAt = now)
        dao.insert(value)
        val outcome = GenesisActorCreationOutcome(value, requireNotNull(db.recordsDao().characterProfile(actorId)), null)
        record(commandId, "register-actor", request, campaignJson.encodeToString(outcome)); outcome
    }
    suspend fun updateActorAssociation(actorId: String, expectedRevision: Long, role: GenesisActorRole,
        campaignId: String?, ownerActorId: String?, commandId: String = UUID.randomUUID().toString()): GenesisActorAuthority = transaction {
        val request = campaignJson.encodeToString(listOf(actorId, expectedRevision.toString(), role.name, campaignId, ownerActorId))
        retry(commandId, "update-association", request)?.let { return@transaction campaignJson.decodeFromString<GenesisActorAuthority>(it.outcomeJson) }
        validateAssociation(actorId, role, campaignId, ownerActorId)
        val old = requireNotNull(dao.actor(actorId)); check(old.revision == expectedRevision) { "This membership changed. Reopen it before saving." }
        val currentPlayable = if (old.playable) requireNotNull(local.get(actorId)).also { genesisRequireProjection(db, actorId, it) } else null
        require(dao.allActors().none { it.ownerActorId == actorId && it.campaignId != null && it.campaignId != campaignId }) { "Move or unassign this actor's companions before changing their owner's campaign." }
        withGenesisProjection(db) { ensureRole(actorId, requireNotNull(db.recordsDao().characterProfile(actorId)).name, role, ownerActorId, commandId) }
        val next = old.copy(role = role, campaignId = campaignId, ownerActorId = ownerActorId, revision = Math.addExact(old.revision, 1),
            lastCommandId = commandId, updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1))
        dao.update(next)
        if (currentPlayable != null) genesisProjectCharacter(db, currentPlayable, currentPlayable, commandId)
        val result = requireNotNull(dao.actor(actorId))
        record(commandId, "update-association", request, campaignJson.encodeToString(result)); result
    }
    private suspend fun validateAssociation(id: String, role: GenesisActorRole, campaignId: String?, owner: String?) {
        checkId(id)
        campaignId?.let { require(dao.campaign(it)?.archived == false) { "Select an existing active campaign." } }
        require(owner == null || role == GenesisActorRole.COMPANION) { "Only a companion can have a companion owner." }
        owner?.let {
            require(it != id && db.recordsDao().characterProfile(it) != null) { "Choose an existing different actor as owner." }
            require(campaignId == null || dao.actor(it)?.campaignId == campaignId) { "The owner needs explicit membership in the selected campaign." }
            var cursor: String? = it; val visited = mutableSetOf(id)
            while (cursor != null) { require(visited.add(cursor)) { "Companion ownership cannot form a cycle." }; cursor = dao.actor(cursor)?.ownerActorId }
        }
    }
    private suspend fun ensureRole(id: String, name: String, role: GenesisActorRole, owner: String?, commandId: String) {
        if (role == GenesisActorRole.NPC && db.recordsDao().entity(id) == null)
            campaign.records.saveEntity(ConnectionEntity(name, kind = "Person", id = id), source = GENESIS_AUTHORITY_SOURCE, commandId = genesisChild(commandId, "npc"))
        if (role == GenesisActorRole.COMPANION && db.recordsDao().companion(id) == null)
            campaign.records.saveCompanionState(CompanionState(name = name, companionId = id), source = GENESIS_AUTHORITY_SOURCE, commandId = genesisChild(commandId, "companion"))
        val existing = db.recordsDao().activeLinks().filter { it.fromType == "companion" && it.fromId == id && it.toType == "character-profile" && it.label == "Companion of" }
        existing.filter { role != GenesisActorRole.COMPANION || it.toId != owner }.forEach {
            campaign.records.unlink(it.id, it.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "unlink:${it.id}"))
        }
        if (role == GenesisActorRole.COMPANION && owner != null && existing.none { it.toId == owner })
            campaign.records.saveLink(CampaignLink("companion", id, "character-profile", owner, "Companion of", id = genesisChild(commandId, "owner-link")),
                source = GENESIS_AUTHORITY_SOURCE, commandId = genesisChild(commandId, "owner"))
    }
    private suspend fun validateEnrollment(saved: ReforgeSavedCharacter, source: ReforgeLegacySnapshot?) {
        require(!saved.character.archived) { "Restore this playable actor before connecting it." }
        val others = db.reforgeDao().allCharacters().filter { it.profileId != saved.character.id }.map { it.saved().character }
        validateGenesisItemOwnership(null, saved.character, others)
        saved.character.items.forEach { item ->
            require(saved.character.itemDefinitions.any { it.id == item.definitionId }) { "Every connected item needs its existing definition." }
            db.ledgerDao().getItem(item.id)?.let {
                require(db.partyDao().placement(item.id)?.holderProfileId == saved.character.id) { "Item ${it.title} has missing or different source ownership. Reconcile its placement explicitly first." }
                require(!it.archived && it.itemState !in setOf("Sold", "Lost", "Installed")) { "Item ${it.title} is unavailable in its source records. Reconcile that state first." }
            }
            readGenesisAuthorityItem(db, item.id)?.let { require(it.ownerActorId == saved.character.id) { "This physical item is already managed by another owner." } }
        }
    }
}
