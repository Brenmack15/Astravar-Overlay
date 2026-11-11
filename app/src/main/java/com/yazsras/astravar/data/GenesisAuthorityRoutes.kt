package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*

/** Legacy entry points keep their original receipts; managed mechanics commit in the same Room transaction. */
private suspend fun routeActor(db: CampaignLedgerDb, actorId: String, commandId: String): GenesisActorAuthority? {
    if (genesisProjectionActive(db) || db.recordsDao().receipt(commandId) != null) return null
    return db.genesisAuthorityDao().actor(actorId)
}

private suspend fun routePlayable(db: CampaignLedgerDb, actor: GenesisActorAuthority): ReforgeSavedCharacter {
    check(actor.playable) { "Review this actor's playable mechanics before changing connected gameplay." }
    val saved = requireNotNull(ReforgeRepository(db).get(actor.actorId)) { "The managed playable record is missing. Open authority review before editing." }
    check(!saved.character.archived) { "Restore the playable actor before changing connected gameplay." }
    genesisRequireProjection(db, actor.actorId, saved)
    return saved
}

private fun routeChild(commandId: String, kind: String): String = "genesis-route-${campaignHash("$kind:$commandId").take(64)}"

private suspend fun saveRouted(db: CampaignLedgerDb, saved: ReforgeSavedCharacter, next: ReforgeCharacter,
    commandId: String, operation: String) {
    val child = routeChild(commandId, "playable")
    check(db.reforgeDao().change(child) == null) { "A gameplay receipt exists without its source command. Restore or review the complete connected backup before retrying." }
    ReforgeRepository(db).save(saved.character.id, saved.revision, next, operation, child)
}

/** Descriptive NPC/companion records remain source-only, but their one identity has one name. */
private suspend fun syncActorName(db: CampaignLedgerDb, actorId: String, name: String, source: String,
    commandId: String, alreadyWritten: String) {
    val records = CampaignRecordsRepository(db)
    if (alreadyWritten != "profile") db.recordsDao().characterProfile(actorId)?.takeIf { it.name != name }?.let {
        records.saveProfile(it.copy(name = name), it.revision, source, routeChild(commandId, "profile-name"))
    }
    if (alreadyWritten != "entity") db.recordsDao().entity(actorId)?.takeIf { it.name != name }?.let {
        records.saveEntity(it.copy(name = name), it.revision, source, routeChild(commandId, "entity-name"))
    }
    if (alreadyWritten != "companion") db.recordsDao().companion(actorId)?.takeIf { it.name != name }?.let {
        records.saveCompanionState(it.copy(name = name), it.revision, source, routeChild(commandId, "companion-name"))
    }
}

internal suspend fun routeManagedProfile(db: CampaignLedgerDb, value: CharacterProfile, expectedRevision: Long?,
    source: String, commandId: String): CommandReceipt? {
    val actor = routeActor(db, value.id, commandId) ?: return null
    if (!actor.playable) return withGenesisProjection(db) {
        val receipt = CampaignRecordsRepository(db).saveProfile(value, expectedRevision, source, commandId)
        syncActorName(db, value.id, value.name, source, commandId, "profile")
        receipt
    }
    val saved = routePlayable(db, actor)
    val old = requireNotNull(db.recordsDao().characterProfile(value.id))
    require(value.maximumHp == old.maximumHp && value.armorClass == old.armorClass && value.level == old.level) {
        "Maximum HP, Armor Class and level are derived from the playable character. Open its progression, equipment or authority review to change their bases."
    }
    val maximum = CharacterCalculator.calculate(saved.character).maximumHp.value
    val damage = if (value.currentHp == old.currentHp) saved.character.hpDamage
        else value.currentHp?.let { hp -> maximum?.let { max -> (max - hp).coerceAtLeast(0) } }
    val next = saved.character.copy(name = value.name, currentHp = value.currentHp, temporaryHp = value.temporaryHp, hpDamage = damage)
    return withGenesisProjection(db, setOf("profile:${value.id}")) {
        val receipt = CampaignRecordsRepository(db).saveProfile(value, expectedRevision, source, commandId)
        saveRouted(db, saved, next, commandId, "Apply connected profile edit")
        receipt
    }
}

internal suspend fun routeManagedAbility(db: CampaignLedgerDb, value: CharacterAbility, expectedRevision: Long?,
    source: String, commandId: String): CommandReceipt? {
    val actor = routeActor(db, value.profileId, commandId) ?: return null
    if (!actor.playable) return null
    val saved = routePlayable(db, actor)
    val old = requireNotNull(db.recordsDao().characterAbility(value.profileId, value.key)) { "Review the missing ability projection before editing." }
    require(value.score == old.score && value.magicModifier == old.magicModifier) {
        "This ability is calculated from the playable character. Edit its base ability, advancement or reviewed effect there; descriptive notes remain editable here."
    }
    return withGenesisProjection(db, setOf("ability:${value.profileId}:${value.key}")) {
        val receipt = CampaignRecordsRepository(db).saveAbility(value, expectedRevision, source, commandId)
        saveRouted(db, saved, saved.character, commandId, "Update connected ability notes")
        receipt
    }
}

internal suspend fun routeManagedEntity(db: CampaignLedgerDb, value: ConnectionEntity, expectedRevision: Long?,
    source: String, commandId: String): CommandReceipt? {
    val actor = routeActor(db, value.id, commandId) ?: return null
    val saved = if (actor.playable) routePlayable(db, actor) else null
    return withGenesisProjection(db, setOf("entity:${value.id}")) {
        val receipt = CampaignRecordsRepository(db).saveEntity(value, expectedRevision, source, commandId)
        if (saved != null) saveRouted(db, saved, saved.character.copy(name = value.name), commandId, "Update connected identity")
        else syncActorName(db, value.id, value.name, source, commandId, "entity")
        receipt
    }
}

internal suspend fun routeManagedCompanionState(db: CampaignLedgerDb, value: CompanionState, expectedRevision: Long?,
    source: String, commandId: String): CommandReceipt? {
    val actor = routeActor(db, value.companionId, commandId) ?: return null
    val saved = if (actor.playable) routePlayable(db, actor) else null
    return withGenesisProjection(db, setOf("companion:${value.companionId}")) {
        val receipt = CampaignRecordsRepository(db).saveCompanionState(value, expectedRevision, source, commandId)
        if (saved != null) saveRouted(db, saved, saved.character.copy(name = value.name), commandId, "Update connected companion identity")
        else syncActorName(db, value.companionId, value.name, source, commandId, "companion")
        receipt
    }
}

private fun conditionsFromSource(character: ReforgeCharacter, text: String): ReforgeCharacter {
    val names = text.split(';').map(String::trim).filter(String::isNotEmpty)
    require(names.map { it.lowercase() }.distinct().size == names.size && names.sorted().joinToString("; ") == text) {
        "Use distinct conditions in alphabetical order separated by a semicolon and a space, or edit Conditions on the playable sheet."
    }
    var next = character
    character.conditions.filter { old -> names.none { it.equals(old, true) } }.forEach { next = GameplayRules.setCondition(next, it, false) }
    names.filter { name -> character.conditions.none { it.equals(name, true) } }.forEach { next = GameplayRules.setCondition(next, it, true) }
    require(next.conditions.map { it.lowercase() }.toSet() == names.map { it.lowercase() }.toSet()) {
        "The requested conditions conflict with the character's rules. Review Conditions on the playable sheet before saving."
    }
    // The rules normalize names while applying immunity and concentration effects. Preserve the
    // caller's display labels so its ordinary source receipt remains exact and needs no second write.
    return next.copy(conditions = names.toSet())
}

private fun resourceChange(character: ReforgeCharacter, name: String, current: Int?, maximum: Int?): ReforgeCharacter {
    val matches = character.resources.filter { it.name == name }
    require(matches.size == 1) { "This resource has no unique configured playable identity. Review its mapping on the playable sheet before editing it here." }
    val pool = matches.single()
    val capacity = CharacterCalculator.calculate(character).resourceMaxima[pool.id]
    require(maximum == capacity) { "Resource maximum is calculated. Review the configured resource or its effects instead of replacing the displayed maximum." }
    require(current == null || current >= 0 && (capacity == null || current <= capacity)) { "Resource availability is outside its known capacity." }
    return character.copy(resources = character.resources.map { if (it.id == pool.id) it.copy(current = current) else it })
}

internal suspend fun routeManagedSheet(db: CampaignLedgerDb, profileId: String, sheet: PartySheetData,
    expectedRevision: Long?, source: String, commandId: String): CommandReceipt? {
    val actor = routeActor(db, profileId, commandId) ?: return null
    if (!actor.playable) return null
    val saved = routePlayable(db, actor)
    val old = requireNotNull(db.partyDao().sheet(profileId)) { "Review the missing playable sheet projection before editing." }.sheet
    require(sheet.proficiencyBonus == old.proficiencyBonus && sheet.initiative == old.initiative &&
        sheet.movement.map { it.copy(notes = "") } == old.movement.map { it.copy(notes = "") } &&
        sheet.savingThrows.map { it.copy(notes = "") } == old.savingThrows.map { it.copy(notes = "") } &&
        sheet.skills.map { it.copy(notes = "") } == old.skills.map { it.copy(notes = "") }) {
        "These statistics are calculated from the playable character. Open Training or authority review to change their mechanical sources."
    }
    var next = if (sheet.conditions == old.conditions) saved.character else conditionsFromSource(saved.character, sheet.conditions)
    require(sheet.resources.map { it.name } == old.resources.map { it.name }) {
        "Add, remove or rename mechanical resources on the playable sheet so their identities stay connected."
    }
    sheet.resources.zip(old.resources).forEach { (requested, previous) ->
        require(requested.maximum == previous.maximum) { "Resource maxima are calculated. Review the playable resource or effects before changing them." }
        if (requested.current != previous.current) next = resourceChange(next, requested.name, requested.current, requested.maximum)
    }
    return withGenesisProjection(db, setOf("sheet:$profileId")) {
        val receipt = PartyRecordsRepository(db).saveSheet(profileId, sheet, expectedRevision, source, commandId)
        saveRouted(db, saved, next, commandId, "Apply connected sheet edit")
        receipt
    }
}

internal suspend fun routeManagedCompanionResource(db: CampaignLedgerDb, value: CompanionResource,
    expectedRevision: Long?, source: String, commandId: String): CommandReceipt? {
    val actor = routeActor(db, value.companionId, commandId) ?: return null
    if (!actor.playable) return null
    val saved = routePlayable(db, actor)
    val old = db.recordsDao().resource(value.id)
    require(old == null || old.companionId == value.companionId && old.name == value.name) {
        "Review the resource identity on the playable companion before renaming or reassigning it."
    }
    val pool = saved.character.resources.singleOrNull { genesisResourceId(actor, it.id) == value.id }
        ?: error("This source resource has no bound playable identity. Configure or review the companion's resource before editing availability here.")
    require(value.name == pool.name) { "Rename this resource on the playable companion so its original source identity stays connected." }
    if (old != null) require(old.current == pool.current && old.maximum == CharacterCalculator.calculate(saved.character).resourceMaxima[pool.id]) {
        "Source and playable resource values differ. Review the companion's authority before changing availability."
    }
    val next = resourceChange(saved.character, value.name, value.current, value.maximum)
    return withGenesisProjection(db, setOf("resource:${value.id}")) {
        val receipt = CampaignRecordsRepository(db).saveCompanionResource(value, expectedRevision, source, commandId)
        saveRouted(db, saved, next, commandId, "Apply connected companion resource")
        receipt
    }
}

private suspend fun routeItem(db: CampaignLedgerDb, itemId: String, commandId: String): GenesisItemAuthority? {
    if (genesisProjectionActive(db) || db.recordsDao().receipt(commandId) != null) return null
    return readGenesisAuthorityItem(db, itemId)
}

private suspend fun itemOwner(db: CampaignLedgerDb, item: GenesisItemAuthority): ReforgeSavedCharacter {
    val actorId = requireNotNull(item.ownerActorId) { "Assign this managed item to a reviewed playable owner before editing its gameplay state." }
    val actor = requireNotNull(db.genesisAuthorityDao().actor(actorId)) { "Review the item's missing authoritative owner." }
    val saved = routePlayable(db, actor)
    require(saved.character.items.count { it.id == item.itemId } == 1) { "Review this physical item's playable identity before editing it." }
    return saved
}

private fun quantityChange(saved: ReforgeSavedCharacter, itemId: String, quantity: Int): ReforgeCharacter {
    val old = saved.character.items.single { it.id == itemId }
    require(quantity in 0..1_000_000) { "Quantity is outside the supported range." }
    require(quantity != 0 || saved.character.items.none { it.containerId == itemId || itemId in it.donorInstanceIds }) {
        "Move container contents or separate composite links before consuming this item."
    }
    require(old.state !in setOf(ItemState.WORN, ItemState.HELD) || quantity <= 1) { "Unequip this item before increasing it to a stack." }
    return GameplayRules.preserveCapacities(saved.character, saved.character.copy(items = saved.character.items.map {
        if (it.id == itemId) it.copy(quantity = quantity, containerId = if (quantity == 0) null else it.containerId) else it
    }))
}

internal suspend fun routeManagedQuantity(db: CampaignLedgerDb, command: QuantityCommand): CommandReceipt? {
    val authority = routeItem(db, command.itemId, command.commandId) ?: return null
    val saved = itemOwner(db, authority)
    val item = saved.character.items.single { it.id == command.itemId }
    val quantity = item.quantity.toLong() + command.delta.toLong()
    require(quantity in 0..1_000_000L) { "Insufficient inventory or quantity exceeds the supported limit." }
    val next = quantityChange(saved, command.itemId, quantity.toInt())
    return withGenesisProjection(db, setOf("item:${command.itemId}", "placement:${command.itemId}")) {
        val receipt = PartyRecordsRepository(db).changeQuantity(command)
        saveRouted(db, saved, next, command.commandId, "Apply connected item quantity")
        receipt
    }
}

internal suspend fun routeManagedQuantityConfirmation(db: CampaignLedgerDb, command: QuantityConfirmationCommand): CommandReceipt? {
    val authority = routeItem(db, command.itemId, command.commandId) ?: return null
    val saved = itemOwner(db, authority)
    val quantity = requireNotNull(command.quantity) { "A playable physical item requires a confirmed quantity. Review its authority before replacing the known quantity with unknown." }
    val next = quantityChange(saved, command.itemId, quantity)
    return withGenesisProjection(db, setOf("item:${command.itemId}", "placement:${command.itemId}")) {
        val receipt = PartyRecordsRepository(db).confirmQuantity(command)
        saveRouted(db, saved, next, command.commandId, "Confirm connected item quantity")
        receipt
    }
}

internal suspend fun routeManagedMove(db: CampaignLedgerDb, command: ItemMoveCommand): CommandReceipt? {
    if (genesisProjectionActive(db) || db.recordsDao().receipt(command.commandId) != null) return null
    val authority = readGenesisAuthorityItem(db, command.itemId)
    if (authority == null) {
        require(command.holderProfileId?.let { db.genesisAuthorityDao().actor(it)?.playable } != true) {
            "Review and accept this physical item into the playable inventory before moving it to a managed actor."
        }
        return null
    }
    val targetId = requireNotNull(command.holderProfileId) { "Use inventory authority review before removing this item's playable owner." }
    if (authority.ownerActorId == null) return assignRetainedItem(db, authority, targetId, command)
    val saved = itemOwner(db, authority)
    require(command.installedProjectId == null && command.state in setOf("Carried", "Equipped", "Stored")) {
        "Project installation requires explicit mechanical review; use the playable inventory for this managed item."
    }
    if (targetId != saved.character.id) {
        require(!command.preserveOwner && command.containerItemId == null && command.state == "Carried") {
            "Transfer managed equipment as carried and unattuned, then equip it or move it into the recipient's container."
        }
        val target = requireNotNull(db.genesisAuthorityDao().actor(targetId)) { "Review the receiving actor's playable authority before transferring this item." }
        val receiver = routePlayable(db, target)
        InventoryRules.transfer(saved.character, receiver.character, command.itemId)
        return withGenesisProjection(db, setOf("item:${command.itemId}", "placement:${command.itemId}")) {
            val child = routeChild(command.commandId, "transfer")
            val hash = reforgeHash(child)
            check(db.reforgeDao().change("transfer-out:$hash") == null && db.reforgeDao().change("transfer-in:$hash") == null) {
                "A transfer receipt exists without its source command. Review or restore the complete connected backup."
            }
            val receipt = PartyRecordsRepository(db).moveItem(command)
            ReforgeRepository(db).transferItem(saved.character, receiver.character, command.itemId, child)
            receipt
        }
    }
    require(command.containerItemId == null || saved.character.items.any { it.id == command.containerItemId && it.quantity > 0 }) {
        "Choose a container in this actor's current playable inventory."
    }
    val old = saved.character.items.single { it.id == command.itemId }
    val state = when (command.state) {
        "Equipped" -> if (old.state == ItemState.HELD) ItemState.HELD else ItemState.WORN
        "Stored" -> ItemState.STORED
        else -> ItemState.CARRIED
    }
    val next = GameplayRules.equip(saved.character, command.itemId, state).let { changed ->
        changed.copy(items = changed.items.map { if (it.id == command.itemId) it.copy(containerId = command.containerItemId) else it })
    }
    return withGenesisProjection(db, setOf("item:${command.itemId}", "placement:${command.itemId}")) {
        val receipt = PartyRecordsRepository(db).moveItem(command)
        saveRouted(db, saved, next, command.commandId, "Move connected equipment")
        receipt
    }
}

/** Reassignment reuses the retained definition and physical identity, never a fresh source-text mapping. */
private suspend fun assignRetainedItem(db: CampaignLedgerDb, authority: GenesisItemAuthority, targetId: String,
    command: ItemMoveCommand): CommandReceipt {
    require(!command.preserveOwner && command.installedProjectId == null && command.state in setOf("Carried", "Stored")) {
        "Assign retained equipment as carried or stored, then review and equip it on its new owner's sheet."
    }
    val actor = requireNotNull(db.genesisAuthorityDao().actor(targetId)) { "Connect the receiving actor's playable mechanics before assigning managed equipment." }
    val receiver = routePlayable(db, actor)
    val definition = reforgeJson.decodeFromString<ItemDefinition>(authority.definitionJson)
    val retained = reforgeJson.decodeFromString<ItemInstance>(authority.instanceJson)
    require(retained.id == command.itemId && retained.definitionId == definition.id && retained.quantity > 0) {
        "Review this retained item's identity, definition and remaining quantity before assigning it."
    }
    val sourceItem = requireNotNull(db.ledgerDao().getItem(command.itemId))
    val placement = requireNotNull(db.partyDao().placement(command.itemId))
    require(sourceItem.title == definition.name && sourceItem.description == definition.description && sourceItem.quantity == retained.quantity &&
        sourceItem.itemState == "Stored" && placement.state == "Stored" && !sourceItem.archived && placement.holderProfileId == null &&
        placement.containerItemId == null && placement.installedProjectId == null) {
        "Retained item and campaign placement disagree. Review their existing authority before reassignment."
    }
    require(receiver.character.items.none { it.id == retained.id } && retained.donorInstanceIds.isEmpty()) {
        "Review conflicting ownership or composite dependencies before reassigning this physical item."
    }
    require(definition.effects.all { effect -> effect.chargeResourceId == null && when (val payload = effect.payload) {
        is EffectPayload.DamageBonus -> payload.qualifier.attackIds.isEmpty()
        is EffectPayload.DamageConversion -> payload.qualifier.attackIds.isEmpty()
        else -> true
    } }) { "This item has actor-specific attacks or charge resources. Review those mechanical dependencies before reassignment." }
    val priorDefinition = receiver.character.itemDefinitions.singleOrNull { it.id == definition.id }
    require(priorDefinition == null || priorDefinition == definition) { "The receiving actor has a different definition with this identity. Review the conflict first." }
    require(command.containerItemId == null || receiver.character.items.any { it.id == command.containerItemId && it.quantity > 0 }) {
        "Choose a container in the receiving actor's playable inventory."
    }
    val instance = retained.copy(state = if (command.state == "Stored") ItemState.STORED else ItemState.CARRIED,
        attuned = false, containerId = command.containerItemId)
    val next = GameplayRules.preserveCapacities(receiver.character, receiver.character.copy(
        itemDefinitions = if (priorDefinition == null) receiver.character.itemDefinitions + definition else receiver.character.itemDefinitions,
        items = receiver.character.items + instance))
    validateCharacter(next)
    return withGenesisProjection(db, setOf("item:${command.itemId}", "placement:${command.itemId}")) {
        val receipt = PartyRecordsRepository(db).moveItem(command)
        saveRouted(db, receiver, next, command.commandId, "Reassign retained connected item")
        receipt
    }
}

private suspend fun managedOwnerLink(db: CampaignLedgerDb, value: CampaignLink?): Boolean =
    value != null && value.label == "Companion of" && value.fromType == "companion" &&
        db.genesisAuthorityDao().actor(value.fromId) != null

internal suspend fun checkManagedLink(db: CampaignLedgerDb, value: CampaignLink, commandId: String) {
    if (genesisProjectionActive(db) || db.recordsDao().receipt(commandId) != null) return
    require(!managedOwnerLink(db, value) && !managedOwnerLink(db, db.recordsDao().link(value.id))) {
        "This companion's owner is managed. Change its owner through campaign membership and relationship review."
    }
}

internal suspend fun checkManagedUnlink(db: CampaignLedgerDb, linkId: String, commandId: String) {
    if (genesisProjectionActive(db) || db.recordsDao().receipt(commandId) != null) return
    require(!managedOwnerLink(db, db.recordsDao().link(linkId))) {
        "This companion's owner is managed. Change its owner through campaign membership and relationship review."
    }
}

internal suspend fun checkManagedCraft(db: CampaignLedgerDb, command: CraftCommand) {
    if (genesisProjectionActive(db) || db.recordsDao().receipt(command.commandId) != null) return
    require(command.materials.none { readGenesisAuthorityItem(db, it.itemId) != null }) {
        "This craft consumes managed playable equipment. Review its complete mechanical dependencies before recording the craft; no materials were consumed."
    }
}

internal suspend fun connectAssignedManagedItem(db: CampaignLedgerDb, item: LedgerItem, placement: ItemPlacement,
    commandId: String, source: String) {
    if (genesisProjectionActive(db)) return
    require(placement.holderProfileId?.let { db.genesisAuthorityDao().actor(it)?.playable } != true) {
        "This acquisition requires a reviewed playable item definition. Create the item through connected inventory or leave it unassigned for review; nothing was acquired."
    }
}
