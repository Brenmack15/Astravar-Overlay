package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fictional complete journeys through the shared, file-backed command boundary. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisAuthorityRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val name = "fictional-genesis-authority-repository.db"
    private val source = "Fictional reviewed source fixture"
    private lateinit var db: CampaignLedgerDb
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var campaign: CampaignLedgerRepository
    private lateinit var playable: ReforgeRepository

    @Before fun open() { context.deleteDatabase(name); reopen() }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
        authority = GenesisAuthorityRepository(db)
        campaign = CampaignLedgerRepository(db)
        playable = ReforgeRepository(db)
    }
    private fun configured(id: String, name: String = id) = ReforgeCharacter(id, name, level = 3,
        baseAbilities = Stat.entries.associateWith { 10 }, baseMaxHp = 20, baselineConstitutionModifier = 0,
        currentHp = 12, temporaryHp = 0, baseAc = 12)
    private fun cloak() = ItemDefinition("fictional-cloak-definition", "Fictional warding cloak",
        effects = listOf(EffectDefinition("fictional-cloak-ac", "Reviewed ward", RuleSource("fictional-homebrew", "Accepted fictional fixture", Acceptance.ACCEPTED_HOMEBREW),
            EffectPayload.Number(NumericTarget.ARMOR_CLASS, NumberOperation.ADD, 2),
            conditions = listOf(EffectCondition(ConditionKind.EQUIPPED)))), requiresAttunement = true, equipmentSlot = "cloak")
    private suspend fun save(current: ReforgeSavedCharacter, next: ReforgeCharacter, command: String) =
        playable.save(current.character.id, current.revision, next, "Fictional $command", command)
    private suspend fun snapshot() = authority.backup.snapshot()
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("Conflicting commands must reject without partially changing the connected graph", failure)
        return requireNotNull(failure)
    }
    private suspend fun profile(id: String) = requireNotNull(db.recordsDao().characterProfile(id))

    @Test fun campaignActorEquipmentQuantityAndTransferRemainConnectedAfterReopen() = runBlocking {
        val world = authority.createCampaign("Fictional Lantern Coast", "Synthetic campaign", "lifecycle-campaign")
        val first = authority.createActor("Fictional scout", GenesisActorRole.PLAYER, world.id,
            playable = configured("lifecycle-scout", "Fictional scout"), commandId = "lifecycle-scout-create")
        val second = authority.createActor("Fictional keeper", GenesisActorRole.PLAYER, world.id,
            playable = configured("lifecycle-keeper", "Fictional keeper"), commandId = "lifecycle-keeper-create")
        val itemId = "one-fictional-cloak"
        var current = authority.createItem(first.actor.actorId, requireNotNull(first.playable).revision, cloak(),
            ItemInstance(itemId, cloak().id), "lifecycle-cloak-create")
        assertEquals(first.actor.actorId, db.partyDao().placement(itemId)?.holderProfileId)
        assertEquals(12, profile(first.actor.actorId).armorClass)

        current = save(current, GameplayRules.equip(current.character, itemId, ItemState.WORN), "lifecycle-equip")
        assertEquals("Equipped", db.ledgerDao().getItem(itemId)?.itemState)
        assertEquals(12, profile(first.actor.actorId).armorClass)
        current = save(current, GameplayRules.attune(current.character, itemId, true), "lifecycle-attune")
        assertEquals(14, CharacterCalculator.calculate(current.character).armorClass.value)
        assertEquals(14, profile(first.actor.actorId).armorClass)
        assertTrue(reforgeJson.decodeFromString<ItemInstance>(db.genesisAuthorityDao().item(itemId)!!.instanceJson).attuned)

        val improved = current.character.itemDefinitions.single().let { definition ->
            definition.copy(effects = definition.effects.map { it.copy(payload = EffectPayload.Number(NumericTarget.ARMOR_CLASS, NumberOperation.ADD, 3)) })
        }
        current = save(current, current.character.copy(itemDefinitions = listOf(improved)), "lifecycle-improve-cloak")
        assertEquals(15, profile(first.actor.actorId).armorClass)
        current = save(current, GameplayRules.equip(GameplayRules.attune(current.character, itemId, false), itemId, ItemState.CARRIED), "lifecycle-unequip")
        assertEquals(12, profile(first.actor.actorId).armorClass)
        current = save(current, current.character.copy(items = current.character.items.map { it.copy(quantity = 3) }), "lifecycle-quantity")
        assertEquals(3, db.ledgerDao().getItem(itemId)?.quantity)

        val transferred = playable.transferItem(current.character, requireNotNull(second.playable).character, itemId, "lifecycle-transfer")
        assertTrue(transferred.first.character.items.isEmpty())
        assertEquals(3, transferred.second.character.items.single().quantity)
        assertFalse(transferred.second.character.items.single().attuned)
        assertEquals(second.actor.actorId, db.partyDao().placement(itemId)?.holderProfileId)
        assertEquals(second.actor.actorId, db.genesisAuthorityDao().item(itemId)?.ownerActorId)
        assertEquals("Fictional keeper", db.ledgerDao().getItem(itemId)?.owner)
        assertEquals(1, db.ledgerDao().allItems().count { it.id == itemId })
        assertEquals(1, db.reforgeDao().allCharacters().map { it.saved() }.count { it.character.items.any { item -> item.id == itemId } })
        val complete = snapshot()

        reopen()
        assertEquals(complete, snapshot())
        assertEquals(transferred.first, playable.get(first.actor.actorId))
        assertEquals(transferred.second, playable.get(second.actor.actorId))
        assertEquals(world.id, authority.actor(second.actor.actorId)?.campaignId)
        assertEquals(12, profile(first.actor.actorId).armorClass)
        assertEquals(12, profile(second.actor.actorId).armorClass)
    }

    @Test fun frozenReconciliationRejectsEitherSideChangingAndRetainsOriginalHistoryAndProof() = runBlocking {
        val world = authority.createCampaign("Fictional Reconciliation", commandId = "review-campaign")
        val id = "reviewed-fictional-player"
        campaign.records.saveProfile(CharacterProfile(id = id, name = "Original source name", currentHp = 7, maximumHp = 18, armorClass = 11, level = 2),
            source = source, commandId = "original-source-profile")
        campaign.records.saveAbility(CharacterAbility("INT", id, 14), source = source, commandId = "original-source-int")
        val prose = SheetCapability("Unresolved source feature", "Retain this descriptive capability", evidenceStatus = "Proposed", source = source, id = "retained-source-feature")
        campaign.party.saveSheet(id, PartySheetData(capabilities = listOf(prose), unresolvedConflicts = listOf("Original fictional uncertainty")),
            source = source, commandId = "original-source-sheet")
        val item = campaign.create(LedgerDraft("Original fictional satchel", quantity = 1, itemState = "Carried", evidenceStatus = "Proposed", source = source), "original-source-item", "reviewed-satchel")
        campaign.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = id, source = source, commandId = "original-source-placement"))
        val adopted = playable.adopt(campaign, playable.readLegacyPreview(campaign, id))
        val originJson = db.reforgeDao().character(id)!!.originJson
        val originalProfileReceipt = campaign.records.receipt("original-source-profile")
        var edited = save(adopted, adopted.character.copy(name = "Reviewed playable name", currentHp = 10, hpDamage = 10, baseMaxHp = 20,
            baselineConstitutionModifier = 0, baseAbilities = Stat.entries.associateWith { 10 }, baseAc = 13, level = 3,
            items = adopted.character.items.map { it.copy(quantity = 2) }), "review-local-edit")
        val frozen = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER, world.id)
        assertTrue(frozen.differences.any { it.field == "Current HP" && it.sourceValue == "7" && it.playableValue == "10" })
        assertTrue(frozen.differences.any { it.field.endsWith("quantity") && it.sourceValue == "1" && it.playableValue == "2" })
        assertNull(authority.actor(id))
        assertEquals(7, profile(id).currentHp)

        val beforeSource = profile(id)
        campaign.records.saveProfile(beforeSource.copy(currentHp = 6), beforeSource.revision, source, "review-source-advance")
        val afterSourceChange = snapshot()
        rejected { authority.acceptActorAuthority(frozen, "stale-source-authority") }
        assertEquals(afterSourceChange, snapshot())
        val sourceFresh = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER, world.id)
        edited = save(edited, edited.character.copy(notes = "Later fictional playable edit"), "review-playable-advance")
        val afterPlayableChange = snapshot()
        rejected { authority.acceptActorAuthority(sourceFresh, "stale-playable-authority") }
        assertEquals(afterPlayableChange, snapshot())

        val reviewed = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER, world.id)
        val accepted = authority.acceptActorAuthority(reviewed, "accept-reviewed-authority")
        assertTrue(accepted.actor.playable)
        assertEquals(10, profile(id).currentHp)
        assertEquals(13, profile(id).armorClass)
        assertEquals(2, db.ledgerDao().getItem(item.id)?.quantity)
        assertEquals(originJson, db.reforgeDao().character(id)!!.originJson)
        assertEquals(adopted.origin, accepted.playable!!.origin)
        assertEquals(originalProfileReceipt, campaign.records.receipt("original-source-profile"))
        assertEquals(listOf(prose), db.partyDao().sheet(id)!!.sheet.capabilities)
        assertEquals(listOf("Original fictional uncertainty"), db.partyDao().sheet(id)!!.sheet.unresolvedConflicts)
        val command = db.genesisAuthorityDao().receipt("accept-reviewed-authority")!!
        assertEquals(reviewed, campaignJson.decodeFromString<GenesisActorAuthorityReview>(command.requestJson))
        assertEquals(reviewed, campaignJson.decodeFromString<GenesisActorAuthorityReview>(db.genesisAuthorityDao().item(item.id)!!.sourceProofJson!!))
        assertEquals(6, reviewed.source!!.profile.currentHp)
        assertEquals(1, reviewed.source.items.single { it.id == item.id }.quantity)

        val later = save(accepted.playable, accepted.playable.character.copy(notes = "Post-acceptance edit"), "review-after-acceptance")
        val beforeRetry = snapshot()
        assertEquals(accepted, authority.acceptActorAuthority(reviewed, "accept-reviewed-authority"))
        assertEquals(later, playable.get(id))
        assertEquals(beforeRetry, snapshot())
        reopen()
        assertEquals(beforeRetry, snapshot())
        assertEquals(originJson, db.reforgeDao().character(id)!!.originJson)
    }

    @Test fun physicalOwnerAndExistingActorIdentityConflictsRollBackEveryCreatedChild() = runBlocking {
        val world = authority.createCampaign("Fictional Collision Campaign", commandId = "collision-campaign")
        val owner = authority.createActor("Fictional owner", GenesisActorRole.PLAYER, world.id,
            playable = configured("collision-owner", "Fictional owner"), commandId = "collision-owner-create")
        val definition = ItemDefinition("collision-definition", "Single fictional identity")
        val instance = ItemInstance("collision-physical-item", definition.id)
        authority.createItem(owner.actor.actorId, owner.playable!!.revision, definition, instance, "collision-item-create")
        val before = snapshot()
        val duplicate = configured("duplicate-physical-owner", "Another fictional owner").copy(itemDefinitions = listOf(definition), items = listOf(instance))

        rejected { authority.createActor(duplicate.name, GenesisActorRole.PLAYER, world.id, playable = duplicate, commandId = "rejected-duplicate-physical") }
        assertEquals(before, snapshot())
        assertNull(db.recordsDao().characterProfile(duplicate.id))
        assertNull(db.reforgeDao().character(duplicate.id))
        assertNull(db.genesisAuthorityDao().receipt("rejected-duplicate-physical"))
        rejected { authority.createActor("Conflicting fictional identity", GenesisActorRole.PLAYER, world.id,
            playable = configured(owner.actor.actorId, "Conflicting fictional identity"), commandId = "rejected-existing-actor") }
        assertEquals(before, snapshot())
        assertNull(db.genesisAuthorityDao().receipt("rejected-existing-actor"))
    }

    @Test fun creationRetriesReturnOriginalOutcomesAfterLaterEditsWithoutRepeatingWrites() = runBlocking {
        val world = authority.createCampaign("Fictional Retry Campaign", commandId = "retry-campaign")
        val request = configured("retry-actor", "Original fictional actor")
        val created = authority.createActor(request.name, GenesisActorRole.PLAYER, world.id, playable = request, commandId = "retry-actor-create")
        val definition = ItemDefinition("retry-definition", "Original fictional item")
        val instance = ItemInstance("retry-physical-item", definition.id)
        val acquired = authority.createItem(request.id, created.playable!!.revision, definition, instance, "retry-item-create")
        val changed = save(acquired, acquired.character.copy(name = "Later fictional actor", items = listOf(instance.copy(quantity = 4))), "retry-later-edit")
        val before = snapshot()

        assertEquals(world, authority.createCampaign("Fictional Retry Campaign", commandId = "retry-campaign"))
        assertEquals(created, authority.createActor(request.name, GenesisActorRole.PLAYER, world.id, playable = request, commandId = "retry-actor-create"))
        assertEquals(acquired, authority.createItem(request.id, created.playable.revision, definition, instance, "retry-item-create"))
        assertEquals(changed, playable.get(request.id))
        assertEquals(before, snapshot())
        rejected { authority.createItem(request.id, created.playable.revision, definition.copy(name = "Changed request"), instance, "retry-item-create") }
        assertEquals(before, snapshot())
    }

    @Test fun existingItemWithUnknownSourceOwnerCannotBeSilentlyAssignedDuringEnrollment() = runBlocking {
        val id = "unknown-owner-player"
        campaign.records.saveProfile(CharacterProfile(id = id, name = "Fictional source actor"), source = source, commandId = "unknown-owner-profile")
        val item = campaign.create(LedgerDraft("Unassigned fictional lens", quantity = 1, itemState = "Carried", source = source), "unknown-owner-item", "unknown-owner-lens")
        val definition = ItemDefinition(item.id, item.title)
        playable.create(configured(id, "Fictional source actor").copy(itemDefinitions = listOf(definition), items = listOf(ItemInstance(item.id, definition.id))), "unknown-owner-playable")
        val before = snapshot()

        rejected { authority.reviewActorAuthority(id, GenesisActorRole.PLAYER) }
        assertEquals(before, snapshot())
        assertNull(db.partyDao().placement(item.id))
        assertNull(authority.actor(id))
        assertNull(db.genesisAuthorityDao().item(item.id))
    }

    @Test fun sourceOnlyActorsKeepUnknownStatisticsWithoutCreatingPlayableSheets() = runBlocking {
        val world = authority.createCampaign("Fictional Source Campaign", commandId = "source-only-campaign")
        val npc = authority.createActor("Fictional unconfigured archivist", GenesisActorRole.NPC, world.id, commandId = "source-only-npc")
        assertFalse(npc.actor.playable)
        assertNull(npc.playable)
        assertNull(npc.profile.currentHp)
        assertNull(npc.profile.maximumHp)
        assertNull(npc.profile.temporaryHp)
        assertNull(npc.profile.armorClass)
        assertNull(npc.profile.level)
        assertNull(db.reforgeDao().character(npc.actor.actorId))
        assertEquals(npc.profile.name, db.recordsDao().entity(npc.actor.actorId)?.name)
        assertTrue(db.recordsDao().allCharacterAbilities().isEmpty())

        campaign.records.saveProfile(CharacterProfile(id = "existing-unconfigured-player", name = "Existing fictional record", maximumHp = 17),
            source = source, commandId = "source-only-existing")
        val prior = profile("existing-unconfigured-player")
        val registered = authority.registerActorAssociation(prior.id, prior.revision, GenesisActorRole.PLAYER, null, null, "source-only-register")
        assertNull(registered.actor.campaignId)
        assertFalse(registered.actor.playable)
        assertNull(registered.profile.currentHp)
        assertEquals(17, registered.profile.maximumHp)
        assertNull(registered.profile.armorClass)
        assertNull(db.reforgeDao().character(prior.id))
        val complete = snapshot()
        reopen()
        assertEquals(complete, snapshot())
    }

    @Test fun companionOwnerConditionsAndResourceChangesUseOnePersistentIdentity() = runBlocking {
        val world = authority.createCampaign("Fictional Companion Campaign", commandId = "companion-campaign")
        val owner = authority.createActor("Fictional keeper", GenesisActorRole.PLAYER, world.id,
            playable = configured("companion-keeper", "Fictional keeper"), commandId = "companion-owner-create")
        val request = configured("fictional-mechanical-owl", "Fictional mechanical owl").copy(
            resources = listOf(ResourcePool("owl-charge", "Owl charge", maximum = 2, current = 1)), conditions = setOf("poisoned"))
        val companion = authority.createActor(request.name, GenesisActorRole.COMPANION, world.id, owner.actor.actorId,
            request, "companion-create")
        assertEquals(owner.actor.actorId, companion.actor.ownerActorId)
        assertEquals(request.id, db.recordsDao().companion(request.id)?.companionId)
        assertTrue(db.recordsDao().activeLinks().any { it.fromId == request.id && it.toId == owner.actor.actorId && it.label == "Companion of" })
        assertEquals("poisoned", db.partyDao().sheet(request.id)?.sheet?.conditions)
        val originalResource = db.recordsDao().allCompanionResources().single { it.companionId == request.id }
        assertEquals(1, originalResource.current)
        assertEquals(2, originalResource.maximum)

        val updated = save(companion.playable!!, companion.playable.character.copy(conditions = emptySet(),
            resources = request.resources.map { it.copy(current = 0) }), "companion-spend-resource")
        val resource = db.recordsDao().allCompanionResources().single { it.companionId == request.id }
        assertEquals(originalResource.id, resource.id)
        assertEquals(0, resource.current)
        assertEquals("", db.partyDao().sheet(request.id)?.sheet?.conditions)
        val complete = snapshot()
        reopen()
        assertEquals(complete, snapshot())
        assertEquals(updated, playable.get(request.id))
        assertEquals(owner.actor.actorId, authority.actor(request.id)?.ownerActorId)
    }
    @Test fun changingManagedRoleCreatesCountersAndRemovedPoolsStayHistoricalInactive() = runBlocking {
        val initial = configured("role-resource-actor").copy(resources = listOf(ResourcePool("role-pool", "Old charges", 4, 3)))
        val created = authority.createActor(initial.name, GenesisActorRole.PLAYER, playable = initial, commandId = "role-player-create")
        val changed = authority.updateActorAssociation(initial.id, created.actor.revision, GenesisActorRole.COMPANION,
            null, null, "role-become-companion")
        assertTrue(changed.playable)
        assertEquals(created.playable?.revision, playable.get(initial.id)?.revision)
        val resource = db.recordsDao().allCompanionResources().single { it.companionId == initial.id }
        assertEquals(3, resource.current)
        var current = requireNotNull(playable.get(initial.id))
        current = save(current, current.character.copy(resources = current.character.resources.map { it.copy(name = "New charges") }), "role-resource-rename")
        assertEquals(resource.id, db.recordsDao().allCompanionResources().single { it.companionId == initial.id }.id)
        assertEquals(listOf("New charges"), db.partyDao().sheet(initial.id)?.sheet?.resources?.map { it.name })
        val retained = requireNotNull(db.recordsDao().resource(resource.id))
        save(current, current.character.copy(resources = emptyList()), "role-resource-remove")
        assertEquals(retained, db.recordsDao().resource(resource.id))
        assertTrue(requireNotNull(db.partyDao().sheet(initial.id)).sheet.resources.isEmpty())
        val beforeFailure = snapshot()
        rejected { campaign.records.saveCompanionResource(retained.copy(current = 1), retained.revision, source, "role-stale-spend") }
        assertEquals(beforeFailure, snapshot())
        val backup = authority.backup.exportWithProof().text
        assertTrue(authority.backup.previewRestore(backup).valid)
        reopen()
        assertEquals(retained, db.recordsDao().resource(resource.id))
        assertTrue(requireNotNull(playable.get(initial.id)).character.resources.isEmpty())
    }

    @Test fun frozenReviewEnumeratesSupplementalCompanionNameAndCounterChanges() = runBlocking {
        val id = "supplemental-diff-actor"
        campaign.records.saveProfile(CharacterProfile(name = "Reviewed familiar", id = id), source = source, commandId = "diff-profile")
        campaign.records.saveCompanionState(CompanionState(name = "Previous source name", companionId = id), source = source, commandId = "diff-companion")
        campaign.records.saveCompanionResource(CompanionResource("Charges", id, current = 1, maximum = 2, id = "source-diff-counter"), source = source, commandId = "diff-counter")
        playable.create(ReforgeCharacter(id, "Reviewed familiar", resources = listOf(ResourcePool("diff-pool", "Charges", 4, 3))), "diff-playable")
        val review = authority.reviewActorAuthority(id, GenesisActorRole.COMPANION)
        assertTrue(review.differences.any { it.field == "Companion name" && it.sourceValue == "Previous source name" && it.playableValue == "Reviewed familiar" })
        assertTrue(review.differences.any { it.field == "Companion Charges current" && it.sourceValue == "1" && it.playableValue == "3" })
        assertTrue(review.differences.any { it.field == "Companion Charges maximum" && it.sourceValue == "2" && it.playableValue == "4" })
        val old = requireNotNull(db.recordsDao().resource("source-diff-counter"))
        campaign.records.saveCompanionResource(old.copy(current = 2), old.revision, source, "diff-counter-concurrent")
        val before = snapshot()
        rejected { authority.acceptActorAuthority(review, "diff-reject-stale") }
        assertEquals(before, snapshot())
    }

}
