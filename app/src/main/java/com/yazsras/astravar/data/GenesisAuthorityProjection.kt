package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Capability is scoped to one coroutine/Room transaction, never a persisted or UI-controlled bypass. */
private class GenesisProjectionContext(val db: CampaignLedgerDb, val ignored: Set<String>, val deferred: Boolean) :
    AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<GenesisProjectionContext> }
internal suspend fun genesisProjectionActive(db: CampaignLedgerDb) = currentCoroutineContext()[GenesisProjectionContext]?.db === db
internal suspend fun genesisProjectionIgnored(db: CampaignLedgerDb): Set<String> = currentCoroutineContext()[GenesisProjectionContext]?.takeIf { it.db === db }?.ignored.orEmpty()
internal suspend fun genesisProjectionDeferred(db: CampaignLedgerDb) = currentCoroutineContext()[GenesisProjectionContext]?.let { it.db === db && it.deferred } == true
internal suspend fun <T> withGenesisProjection(db: CampaignLedgerDb, prewritten: Set<String> = emptySet(), block: suspend () -> T): T {
    val prior = currentCoroutineContext()[GenesisProjectionContext]?.takeIf { it.db === db }
    return withContext(GenesisProjectionContext(db, prior?.ignored.orEmpty() + prewritten, prior?.deferred ?: false)) { block() }
}
internal suspend fun <T> withGenesisProjectionDeferred(db: CampaignLedgerDb, block: suspend () -> T): T {
    val prior = currentCoroutineContext()[GenesisProjectionContext]?.takeIf { it.db === db }
    return withContext(GenesisProjectionContext(db, prior?.ignored.orEmpty(), true)) { block() }
}
internal fun genesisProfile(character: ReforgeCharacter, old: CharacterProfile? = null): CharacterProfile {
    val value = CharacterCalculator.calculate(character)
    return (old ?: CharacterProfile(id = character.id)).copy(name = character.name, currentHp = value.currentHp,
        maximumHp = value.maximumHp.value, temporaryHp = character.temporaryHp, armorClass = value.armorClass.value, level = character.level)
}
internal fun genesisSheet(character: ReforgeCharacter, old: PartySheetData = PartySheetData(), previous: ReforgeCharacter = character): PartySheetData {
    val projected = CharacterCalculator.calculate(character)
    require(character.resources.map { it.name }.distinct().size == character.resources.size) { "Connected resources need distinct names so source views cannot spend an ambiguous pool." }
    val known = character.resources.associateBy { it.name }
    val previousNames = previous.resources.map { it.name }.toSet()
    val current = old.resources.filter { it.name !in known && it.name !in previousNames } + character.resources.map { pool ->
        SheetResource(pool.name, pool.current, projected.resourceMaxima[pool.id], old.resources.singleOrNull { it.name == pool.name }?.notes.orEmpty())
    }
    return old.copy(conditions = character.conditions.sorted().joinToString("; "), resources = current,
        proficiencyBonus = projected.proficiencyBonus, initiative = projected.initiative.value)
}
internal fun genesisItemState(item: ItemInstance): String = if (item.quantity == 0) "Consumed" else when (item.state) {
    ItemState.STORED -> "Stored"; ItemState.CARRIED -> "Carried"; ItemState.HELD, ItemState.WORN -> "Equipped"
}
internal fun genesisResourceBindings(character: ReforgeCharacter?, sources: List<CompanionResource>): String = campaignJson.encodeToString(
    character?.resources.orEmpty().associate { pool -> pool.id to (sources.singleOrNull { it.companionId == character?.id && it.name == pool.name }?.id ?: genesisChild(requireNotNull(character).id, "resource:${pool.id}")) })
internal fun genesisResourceId(actor: GenesisActorAuthority, poolId: String): String = campaignJson.decodeFromString<Map<String, String>>(actor.resourceBindingsJson)[poolId] ?: genesisChild(actor.actorId, "resource:$poolId")
internal fun genesisProjectionFingerprint(saved: ReforgeSavedCharacter) = campaignHash(reforgeJson.encodeToString(saved.character))

/** Compare only fields explicitly managed by this authority; preserved source prose is not discarded. */
internal fun genesisProjectionErrors(actor: GenesisActorAuthority, saved: ReforgeSavedCharacter, campaign: LedgerExport,
    ignored: Set<String> = emptySet()): List<String> = buildList {
    val id = actor.actorId; val c = saved.character; val r = campaign.records
    if (actor.playableRevision != saved.revision || actor.projectionFingerprint != genesisProjectionFingerprint(saved)) add("Connected actor $id has an unverified playable revision.")
    val profile = r.characterProfiles.find { it.id == id }
    if ("profile:$id" !in ignored && (profile == null || genesisProfile(c, profile) != profile)) add("Connected profile $id differs from its authoritative mechanics.")
    val calculated = CharacterCalculator.calculate(c)
    Stat.entries.forEach { stat -> if ("ability:$id:${stat.name}" !in ignored) {
        val ability = r.characterAbilities.find { it.profileId == id && it.key == stat.name }
        if (ability == null || ability.score != calculated.abilities.getValue(stat).effective.value || ability.magicModifier != 0) add("Connected ability $id:${stat.name} differs from its authoritative mechanics.")
    } }
    if ("sheet:$id" !in ignored) {
        val sheet = r.partySheets.find { it.profileId == id }?.sheet
        if (sheet == null || genesisSheet(c, sheet) != sheet) add("Connected conditions/resources for $id differ from the playable state.")
    }
    if (actor.role == GenesisActorRole.COMPANION && "companion:$id" !in ignored && r.companionStates.find { it.companionId == id }?.name != c.name) add("Connected companion name differs for $id.")
    if (actor.role == GenesisActorRole.NPC && "entity:$id" !in ignored && r.entities.find { it.id == id }?.name != c.name) add("Connected NPC name differs for $id.")
    if (actor.role == GenesisActorRole.COMPANION) c.resources.forEach { pool ->
        val resource = r.companionResources.singleOrNull { it.companionId == id && it.id == genesisResourceId(actor, pool.id) }
        if (resource == null || ("resource:${resource.id}" !in ignored && (resource.name != pool.name || resource.current != pool.current || resource.maximum != calculated.resourceMaxima[pool.id]))) add("Connected companion resource ${pool.name} differs for $id.")
    }
    c.items.forEach { instance ->
        val definition = c.itemDefinitions.find { it.id == instance.definitionId }
        val item = campaign.items.find { it.id == instance.id }
        if ("item:${instance.id}" !in ignored && (item == null || definition == null || item.title != definition.name || item.description != definition.description || item.quantity != instance.quantity || item.itemState != genesisItemState(instance) || item.archived || item.owner != c.name || item.location != placementLocation(ItemPlacement(instance.id, id, instance.containerId?.takeIf { instance.quantity > 0 }, state = genesisItemState(instance))))) add("Connected item ${instance.id} differs from its authoritative record.")
        val placement = r.placements.find { it.itemId == instance.id }
        if ("placement:${instance.id}" !in ignored && (placement == null || placement.holderProfileId != id || placement.containerItemId != instance.containerId?.takeIf { instance.quantity > 0 } || placement.installedProjectId != null || placement.state != genesisItemState(instance))) add("Connected placement ${instance.id} differs from its authoritative owner.")
    }
}
internal suspend fun genesisRequireProjection(db: CampaignLedgerDb, actorId: String, saved: ReforgeSavedCharacter, ignored: Set<String> = emptySet()) {
    val actor = db.genesisAuthorityDao().actor(actorId)?.takeIf { it.playable } ?: return
    val source = CampaignLedgerRepository(db).readCharacterSnapshot(actorId)
    val records = CampaignRecordsExport(characterProfiles = listOf(source.profile), characterAbilities = source.abilities,
        partySheets = listOfNotNull(source.sheet), placements = source.placements,
        companionStates = listOfNotNull(db.recordsDao().companion(actorId)), entities = listOfNotNull(db.recordsDao().entity(actorId)),
        companionResources = db.recordsDao().allCompanionResources().filter { it.companionId == actorId })
    val errors = genesisProjectionErrors(actor, saved, LedgerExport(exportedAt = 0, items = source.items, events = emptyList(), records = records), ignored + genesisProjectionIgnored(db))
    require(errors.isEmpty()) { "Connected records need explicit reconciliation before this change: ${errors.joinToString(" ")}" }
}

/** Runs inside the caller's Room transaction after canonical history insertion. No second database or async dual write. */
internal suspend fun genesisProjectCharacter(db: CampaignLedgerDb, before: ReforgeSavedCharacter?, after: ReforgeSavedCharacter,
    commandId: String, activation: Boolean = false, sourceProof: String? = null) {
    val authority = db.genesisAuthorityDao().actor(after.character.id)?.takeIf { it.playable } ?: return
    val allowNew = activation || sourceProof != null || genesisProjectionActive(db)
    val c = after.character; val records = CampaignRecordsRepository(db); val party = PartyRecordsRepository(db)
    withGenesisProjection(db) {
        val oldProfile = db.recordsDao().characterProfile(c.id)
        val profile = genesisProfile(c, oldProfile)
        if (profile != oldProfile) records.saveProfile(profile, oldProfile?.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "profile:${c.id}"))
        val calculated = CharacterCalculator.calculate(c)
        Stat.entries.forEach { stat ->
            val old = db.recordsDao().characterAbility(c.id, stat.name)
            val value = (old ?: CharacterAbility(stat.name, c.id)).copy(score = calculated.abilities.getValue(stat).effective.value, magicModifier = 0)
            if (value != old) records.saveAbility(value, old?.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "ability:${c.id}:${stat.name}"))
        }
        val oldSheet = db.partyDao().sheet(c.id)
        val sheet = genesisSheet(c, oldSheet?.sheet ?: PartySheetData(), before?.character ?: c)
        if (sheet != oldSheet?.sheet) party.saveSheet(c.id, sheet, oldSheet?.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "sheet:${c.id}"))
        if (authority.role == GenesisActorRole.COMPANION) {
            val old = requireNotNull(db.recordsDao().companion(c.id)); if (old.name != c.name)
                records.saveCompanionState(old.copy(name = c.name), old.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "companion:${c.id}"))
            val existing = db.recordsDao().allCompanionResources().filter { it.companionId == c.id }
            c.resources.forEach { pool ->
                val prior = existing.singleOrNull { it.id == genesisResourceId(authority, pool.id) }
                val next = (prior ?: CompanionResource(pool.name, c.id, id = genesisResourceId(authority, pool.id))).copy(name = pool.name, current = pool.current, maximum = calculated.resourceMaxima[pool.id])
                if (next != prior) records.saveCompanionResource(next, prior?.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "resource:${pool.id}"))
            }
        }
        if (authority.role == GenesisActorRole.NPC) {
            val old = requireNotNull(db.recordsDao().entity(c.id)); if (old.name != c.name)
                records.saveEntity(old.copy(name = c.name), old.revision, GENESIS_AUTHORITY_SOURCE, genesisChild(commandId, "entity:${c.id}"))
        }
        // Resolve the final canonical owner once both halves of a transfer are staged.
        val changedIds = if (!activation && before?.character?.items == c.items && before.character.itemDefinitions == c.itemDefinitions && before.character.name == c.name) emptyList()
            else (before?.character?.items.orEmpty().map { it.id } + c.items.map { it.id }).distinct()
        val all = if (changedIds.isEmpty()) emptyList() else db.reforgeDao().allCharacters().map { it.saved() }
        val ownership = changedIds.associateWith { id -> all.filter { it.character.items.any { item -> item.id == id } } }
        require(ownership.values.all { it.size <= 1 }) { "A physical item has multiple playable owners. Review the conflicting records first." }
        // All ledger rows exist before placing containers; order does not invent ownership.
        changedIds.forEach { id ->
            val owner = ownership.getValue(id).singleOrNull()
            if (owner != null) require(db.genesisAuthorityDao().actor(owner.character.id)?.playable == true) { "Connect the receiving actor before transferring a managed item." }
            val instance = owner?.character?.items?.single { it.id == id } ?: before?.character?.items?.find { it.id == id } ?: return@forEach
            val definition = owner?.character?.itemDefinitions?.find { it.id == instance.definitionId }
                ?: before?.character?.itemDefinitions?.find { it.id == instance.definitionId } ?: error("Item definition is missing.")
            require(definition.name.isNotBlank() && definition.name.length <= 120 && definition.description.length <= 16_000) { "Connected item names support 120 characters and descriptions 16,000 characters. Edit the item before saving." }
            val priorAuthority = readGenesisAuthorityItem(db, id)
            val old = db.ledgerDao().getItem(id)
            require(priorAuthority != null || allowNew) { "Create new physical items through connected creation or explicit source review." }
            require(old == null || priorAuthority != null || activation || sourceProof != null) { "Existing campaign item $id needs an explicit acquisition review before it can be managed." }
            if (old == null) CampaignLedgerRepository(db).create(LedgerDraft(definition.name, quantity = instance.quantity,
                description = definition.description, evidenceStatus = "Confirmed", itemState = genesisItemState(instance), source = GENESIS_AUTHORITY_SOURCE), genesisChild(commandId, "item-create:$id"), id)
            val value = GenesisItemAuthority(id, owner?.character?.id, reforgeJson.encodeToString(definition), reforgeJson.encodeToString(instance),
                (priorAuthority?.revision ?: 0) + 1, priorAuthority?.activationCommandId ?: if (sourceProof != null && !activation) commandId else authority.activationCommandId,
                if (priorAuthority != null) priorAuthority.sourceProofJson else sourceProof?.takeUnless { activation }, System.currentTimeMillis())
            if (priorAuthority == null) db.genesisAuthorityDao().insert(value)
            else if (priorAuthority.copy(revision = value.revision, updatedAt = value.updatedAt) != value) db.genesisAuthorityDao().update(value)
        }
        val desiredPlacements = changedIds.mapNotNull { id -> readGenesisAuthorityItem(db, id)?.let { row ->
            val item = reforgeJson.decodeFromString<ItemInstance>(row.instanceJson)
            ItemPlacement(id, row.ownerActorId, item.containerId?.takeIf { item.quantity > 0 && row.ownerActorId != null }, state = if (row.ownerActorId == null && item.quantity > 0) "Stored" else genesisItemState(item))
        } }
        val finalPlacements = db.partyDao().allPlacements().filter { it.itemId !in changedIds } + desiredPlacements
        // Validate against final quantities/owners, not a transient transfer order.
        val finalItems = db.ledgerDao().allItems().map { old -> readGenesisAuthorityItem(db, old.id)?.takeIf { it.itemId in changedIds }?.let { row ->
            val item = reforgeJson.decodeFromString<ItemInstance>(row.instanceJson); old.copy(quantity = item.quantity, itemState = genesisItemState(item))
        } ?: old }
        desiredPlacements.forEach { validatePlacement(it, finalPlacements, finalItems, db.recordsDao().allCharacterProfiles(), db.recordsDao().allProjects()) }
        desiredPlacements.forEach { desired -> genesisProjectItem(db, requireNotNull(readGenesisAuthorityItem(db, desired.itemId)), desired, commandId) }
        db.genesisAuthorityDao().update(authority.copy(playableRevision = after.revision, projectionFingerprint = genesisProjectionFingerprint(after)))
    }
}

private suspend fun genesisProjectItem(db: CampaignLedgerDb, authority: GenesisItemAuthority, desired: ItemPlacement, parent: String) {
    val before = requireNotNull(db.ledgerDao().getItem(authority.itemId)); val priorPlacement = db.partyDao().placement(authority.itemId)
    val definition = reforgeJson.decodeFromString<ItemDefinition>(authority.definitionJson); val instance = reforgeJson.decodeFromString<ItemInstance>(authority.instanceJson)
    val owner = authority.ownerActorId?.let { db.recordsDao().characterProfile(it)?.name } ?: "Unknown"
    val next = before.copy(title = definition.name, description = definition.description, quantity = instance.quantity,
        owner = owner, itemState = desired.state, location = placementLocation(desired))
    val placementChanged = priorPlacement == null || priorPlacement.copy(holderProfileId = desired.holderProfileId,
        containerItemId = desired.containerItemId, installedProjectId = null, state = desired.state) != priorPlacement
    if (next == before && !placementChanged) return
    val command = genesisChild(parent, "item-project:${authority.itemId}")
    genesisRejectAuthorityCommandReuse(db, command, "$command:placement")
    require(db.recordsDao().receipt(command) == null && db.ledgerDao().event(command) == null) { "Projection command identity already exists." }
    val now = maxOf(System.currentTimeMillis(), before.updatedAt + 1)
    val after = next.copy(revision = Math.addExact(before.revision, 1), source = GENESIS_AUTHORITY_SOURCE, updatedAt = now)
    val fp = campaignHash(campaignJson.encodeToString(listOf("genesis-item-projection-v1", parent, reforgeJson.encodeToString(authority), campaignJson.encodeToString(before), campaignJson.encodeToString(desired))))
    val out = campaignJson.encodeToString(after)
    db.ledgerDao().updateItem(after)
    db.ledgerDao().insertEvent(LedgerEvent(command, before.id, "UPDATE", "Confirmed", "Connected physical item projection", GENESIS_AUTHORITY_SOURCE, fp, before.revision, after.revision, now, campaignJson.encodeToString(before), out))
    db.recordsDao().insert(CampaignAudit(command, command, "item", before.id, "UPDATE", GENESIS_AUTHORITY_SOURCE, before.revision, after.revision, now, campaignJson.encodeToString(before), out))
    db.recordsDao().insert(CommandReceipt(command, fp, "UPDATE", before.id, after.revision, now, outcomeJson = out))
    if (placementChanged) {
        val placement = desired.copy(revision = (priorPlacement?.revision ?: 0) + 1, source = GENESIS_AUTHORITY_SOURCE, updatedAt = now)
        db.partyDao().put(placement)
        db.recordsDao().insert(CampaignAudit("$command:placement", command, "item-placement", before.id, "UPDATE", GENESIS_AUTHORITY_SOURCE,
            priorPlacement?.revision ?: 0, placement.revision, now, priorPlacement?.let { campaignJson.encodeToString(it) }, campaignJson.encodeToString(placement)))
    }
}
