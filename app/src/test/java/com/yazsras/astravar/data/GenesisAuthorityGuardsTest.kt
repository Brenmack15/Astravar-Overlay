package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/** Synthetic ownership boundaries; no existing campaign or owner connection is accessed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenesisAuthorityGuardsTest {
    private lateinit var context: Context
    private lateinit var db: CampaignLedgerDb
    private lateinit var hpDb: LiveHpConnectionDb
    private lateinit var ledger: CampaignLedgerRepository
    private val source = "Explicit fictional authority guard fixture"
    private val now = 1_900_000_000_000L

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        hpDb = Room.inMemoryDatabaseBuilder(context, LiveHpConnectionDb::class.java).allowMainThreadQueries().build()
        ledger = CampaignLedgerRepository(db)
    }
    @After fun close() { hpDb.close(); db.close() }

    private suspend fun profile(id: String) = ledger.records.saveProfile(
        CharacterProfile(id = id, name = "Fictional $id", currentHp = 8, maximumHp = 12), source = source)

    // This fixture isolates membership reads from creation/projection commands, which have their own tests.
    private suspend fun association(id: String, campaignId: String?, role: GenesisActorRole = GenesisActorRole.NPC) {
        if (campaignId != null && db.genesisAuthorityDao().campaign(campaignId) == null)
            db.genesisAuthorityDao().insert(GenesisCampaignRecord(campaignId, "Fictional campaign", createdAt = now,
                updatedAt = now, commandId = "fixture-campaign-$campaignId"))
        db.genesisAuthorityDao().insert(GenesisActorAuthority(id, role, campaignId = campaignId,
            activationCommandId = "fixture-actor-$id", lastCommandId = "fixture-actor-$id", createdAt = now, updatedAt = now))
    }
    private suspend fun oldReference(id: String, type: String, campaignId: String = "local-campaign") {
        ledger.party.saveExternalReference(ExternalRecordReference("ref-$type-$id-$campaignId", "fixture-$type-$campaignId",
            id, type, id, campaignId = campaignId), source = source)
    }
    private suspend fun snapshot(campaign: String) = CampaignSyncDomain(db).snapshot(campaign, includeReadOnly = false)

    @Test fun explicitUnassignedMembershipOverridesOldSheetAndSourceReferencesWithoutDeletingThem() = runBlocking {
        val actor = "guard-unassigned"
        profile(actor)
        ledger.party.saveSheet(actor, PartySheetData(), source = source)
        ledger.records.saveCompanionState(CompanionState("Fictional owl", companionId = actor), source = source)
        ledger.records.saveEntity(ConnectionEntity("Fictional contact", id = actor), source = source)
        ledger.party.saveWallet(PartyWallet("wallet-unassigned", actor, "Fictional purse", gold = 2), source = source)
        oldReference(actor, "character-profile"); oldReference(actor, "connection")
        association(actor, null, GenesisActorRole.COMPANION)

        val result = snapshot("local-campaign")
        assertFalse(result.any { it.recordId == actor && it.kind in setOf("character-profile", "party-sheet", "companion-state", "connection") })
        assertFalse(result.any { it.kind == "wallet" && it.recordId == "wallet-unassigned" })
        assertNotNull(db.recordsDao().characterProfile(actor)); assertNotNull(db.partyDao().sheet(actor))
        assertNotNull(db.recordsDao().companion(actor)); assertNotNull(db.recordsDao().entity(actor))
        assertEquals(2, db.partyDao().allReferences().size)
    }

    @Test fun explicitCampaignIncludesSourceOnlyNpcWithoutInventingPlayableMechanics() = runBlocking {
        val actor = "guard-npc"; val campaign = "guard-campaign"
        ledger.records.saveProfile(CharacterProfile(id = actor, name = "Fictional archivist"), source = source)
        ledger.records.saveEntity(ConnectionEntity("Fictional archivist", id = actor), source = source)
        association(actor, campaign)
        val named = snapshot(campaign)
        assertEquals(setOf("character-profile", "connection"), named.filter { it.recordId == actor }.map { it.kind }.toSet())
        assertFalse(snapshot("local-campaign").any { it.recordId == actor })
        assertNull(db.partyDao().sheet(actor)); assertNull(db.reforgeDao().character(actor))
        assertNull(db.recordsDao().characterProfile(actor)?.level)
        assertNull(db.recordsDao().characterProfile(actor)?.maximumHp)
    }

    @Test fun absentAuthorityKeepsLegacyFallbackEditableAndRetryReturnsOriginalAfterLaterEdit() = runBlocking {
        val actor = "guard-legacy"; profile(actor)
        val original = requireNotNull(db.recordsDao().characterProfile(actor))
        val request = original.copy(name = "Fictional renamed traveller")
        val first = ledger.records.saveProfile(request, 1, source, "guard-old-retry")
        ledger.records.saveProfile(requireNotNull(db.recordsDao().characterProfile(actor)).copy(currentHp = 7), 2, source, "guard-later-edit")
        assertEquals(first, ledger.records.saveProfile(request, 1, source, "guard-old-retry"))
        assertEquals(7, db.recordsDao().characterProfile(actor)?.currentHp)
        assertEquals(3L, db.recordsDao().characterProfile(actor)?.revision)
        assertTrue(snapshot("local-campaign").any { it.recordId == actor && it.kind == "character-profile" })
    }

    @Test fun managedPhysicalItemCannotLeakThroughOldCampaignReferenceAfterMembershipChanges() = runBlocking {
        val actor = "guard-item-owner"; profile(actor)
        val item = ledger.create(LedgerDraft("Fictional lens", quantity = 1, source = source), "guard-item-create")
        ledger.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = actor, source = source,
            commandId = "guard-item-place"))
        oldReference(item.id, "item")
        association(actor, "guard-next-campaign")
        db.genesisAuthorityDao().insert(GenesisItemAuthority(item.id, actor,
            definitionJson = reforgeJson.encodeToString(ItemDefinition("guard-scope-definition", "Fictional lens")),
            instanceJson = reforgeJson.encodeToString(ItemInstance(item.id, "guard-scope-definition")),
            activationCommandId = "guard-item-authority", updatedAt = now))
        assertFalse(snapshot("local-campaign").any { it.recordId == item.id && it.kind in setOf("item", "item-placement") })
        val named = snapshot("guard-next-campaign")
        assertTrue(named.any { it.recordId == item.id && it.kind == "item" })
        assertTrue(named.any { it.recordId == item.id && it.kind == "item-placement" })
        assertEquals(1, db.ledgerDao().allItems().size)
    }

    @Test fun hpPairingRejectsExplicitUnassignedActorBeforeAnyNetworkRequest() = runBlocking {
        val actor = "guard-hp-unassigned"; profile(actor); association(actor, null)
        var requests = 0
        val transport = OwnerConnectionTransport { _, _, _, _, _ -> requests++; error("No request is authorized") }
        val vault = OwnerSyncCredentialVault(context, { now }) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        val client = LiveHpConnectionClient(context, hpDb, db, vault, transport, { now })
        val failure = runCatching { client.beginPairing("https://guard.example", actor, "Fictional device") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Assign this actor to a campaign"))
        assertEquals(0, requests); assertNull(hpDb.connection().settings())
        val listed = client.snapshot().getValue("profiles").jsonArray.single().jsonObject
        assertEquals(actor, listed.getValue("id").jsonPrimitive.content)
        assertEquals(JsonNull, listed.getValue("campaignId"))
        assertTrue(listed.getValue("pairingIssue").jsonPrimitive.content.contains("Assign this actor"))
        vault.forgetAll()
    }

    @Test fun reassignedActorCannotPublishFreshHpThroughOldCampaignGrant() = runBlocking {
        val actor = "guard-hp-reassigned"; val oldCampaign = "guard-prior-campaign"
        profile(actor); association(actor, "guard-new-campaign")
        var requests = 0
        val transport = OwnerConnectionTransport { _, _, _, _, _ -> requests++; error("No current HP may leave under the old grant") }
        val vault = OwnerSyncCredentialVault(context, { now }) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        vault.forgetAll()
        vault.enroll(OwnerSyncCredential("guard-device", oldCampaign, "https://guard.example", now + 600_000, "g".repeat(43)))
        hpDb.connection().settings(LiveHpSettings(origin = "https://guard.example", campaignId = oldCampaign,
            profileId = actor, deviceId = "guard-device", deviceName = "Fictional device"))
        val original = db.recordsDao().characterProfile(actor)
        val result = LiveHpConnectionClient(context, hpDb, db, vault, transport, { now }).sync()
        assertEquals(0, requests)
        assertTrue(result.getValue("status").jsonPrimitive.content.contains("Campaign membership changed"))
        assertEquals(original, db.recordsDao().characterProfile(actor))
        assertNotNull(vault.credential("https://guard.example", oldCampaign))
        vault.forgetAll()
    }

    @Test fun hpPairingUsesFirstClassCampaignIdentityInsteadOfLegacyVirtualScope() = runBlocking {
        val actor = "guard-hp-assigned"; val campaign = "guard-real-campaign"
        profile(actor); association(actor, campaign)
        var requestedCampaign: String? = null
        val transport = OwnerConnectionTransport { _, path, _, _, body ->
            assertEquals("/v1/pairings", path)
            requestedCampaign = Json.parseToJsonElement(requireNotNull(body)).jsonObject.getValue("campaignId").jsonPrimitive.content
            OwnerSyncResponse(200, buildJsonObject {
                put("mode", OwnerConnectionProtocol.MODE); put("campaignId", campaign)
                put("deviceCode", "guard-device-code"); put("userCode", "GUARD-CODE"); put("expiresAt", now + 600_000)
            })
        }
        val vault = OwnerSyncCredentialVault(context, { now }) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        val client = LiveHpConnectionClient(context, hpDb, db, vault, transport, { now })
        client.beginPairing("https://guard.example", actor, "Fictional device")
        assertEquals(campaign, requestedCampaign); assertEquals(campaign, hpDb.connection().settings()?.campaignId)
        vault.forgetAll()
    }

    @Test fun explicitMembershipScopesOldSheetAndWalletWithoutRewritingHistoricalCampaignFields() = runBlocking {
        val actor = "guard-effective-sheet"; profile(actor)
        ledger.party.saveSheet(actor, PartySheetData(conditions = "Fictional condition"), source = source)
        ledger.party.saveWallet(PartyWallet("guard-effective-wallet", actor, "Fictional purse", gold = 3), source = source)
        val oldSheet = db.partyDao().sheet(actor); val oldWallet = db.partyDao().wallet("guard-effective-wallet")
        association(actor, "guard-assigned-campaign")
        val current = snapshot("guard-assigned-campaign")
        assertEquals("guard-assigned-campaign", current.single { it.kind == "party-sheet" }.fields.getValue("campaignId").jsonPrimitive.content)
        assertEquals("guard-assigned-campaign", current.single { it.kind == "wallet" }.fields.getValue("campaignId").jsonPrimitive.content)
        assertFalse(snapshot("local-campaign").any { it.kind in setOf("party-sheet", "wallet") })
        assertEquals(oldSheet, db.partyDao().sheet(actor)); assertEquals(oldWallet, db.partyDao().wallet("guard-effective-wallet"))
    }

    private suspend fun managed(id: String = "guard-managed"): GenesisActorCreationOutcome {
        val authority = GenesisAuthorityRepository(db)
        val campaign = authority.createCampaign("Fictional guard campaign", "Explicit test fixture", "campaign-$id")
        return authority.createActor("Fictional managed traveller", GenesisActorRole.PLAYER, campaign.id, null,
            ReforgeCharacter(id, "Fictional managed traveller", baseAbilities = Stat.entries.associateWith { 10 },
                baseMaxHp = 12, baselineConstitutionModifier = 0, currentHp = 8, temporaryHp = 0, baseAc = 12), "create-$id")
    }

    @Test fun managedProfileHpAndNoOpKeepOrdinaryCallerReceiptsAndRetryAfterLaterChanges() = runBlocking {
        val created = managed(); val id = created.actor.actorId
        val original = requireNotNull(db.recordsDao().characterProfile(id))
        val noOp = ledger.records.saveProfile(original, original.revision, source, "guard-hp-no-op")
        assertEquals("character-profile", noOp.kind); assertEquals("guard-hp-no-op", noOp.commandId)
        assertEquals(original.revision + 1, noOp.resultingRevision)
        assertEquals(original.currentHp, db.recordsDao().characterProfile(id)?.currentHp)
        val beforeHp = requireNotNull(db.recordsDao().characterProfile(id))
        val request = beforeHp.copy(currentHp = 6)
        val changed = ledger.records.saveProfile(request, beforeHp.revision, source, "guard-managed-hp")
        assertEquals(beforeHp.revision + 1, changed.resultingRevision)
        assertEquals(6, ReforgeRepository(db).get(id)?.character?.currentHp)
        val afterHp = requireNotNull(db.recordsDao().characterProfile(id))
        ledger.records.saveProfile(afterHp.copy(name = "Fictional later name"), afterHp.revision, source, "guard-managed-name")
        assertEquals(changed, ledger.records.saveProfile(request, beforeHp.revision, source, "guard-managed-hp"))
        assertEquals(noOp, ledger.records.saveProfile(original, original.revision, source, "guard-hp-no-op"))
        assertEquals("Fictional later name", ReforgeRepository(db).get(id)?.character?.name)
        assertEquals(6, db.recordsDao().characterProfile(id)?.currentHp)
    }

    @Test fun managedDerivedProfileEditFailsWithoutChangingEitherRepresentationOrCreatingReceipt() = runBlocking {
        val id = managed().actor.actorId
        val oldProfile = requireNotNull(db.recordsDao().characterProfile(id)); val oldPlayable = ReforgeRepository(db).get(id)
        val failure = runCatching { ledger.records.saveProfile(oldProfile.copy(armorClass = 31), oldProfile.revision,
            source, "guard-derived-change") }.exceptionOrNull()
        assertNotNull("Derived AC cannot safely be treated as a base AC edit", failure)
        assertTrue(failure?.message.orEmpty().isNotBlank())
        assertEquals(oldProfile, db.recordsDao().characterProfile(id)); assertEquals(oldPlayable, ReforgeRepository(db).get(id))
        assertNull(db.recordsDao().receipt("guard-derived-change"))
    }

    @Test fun sourceOnlyNpcRenameKeepsIdentityConnectedAndAllowsDescriptiveNotes() = runBlocking {
        val authority = GenesisAuthorityRepository(db)
        val created = authority.createActor("Fictional archivist", GenesisActorRole.NPC, null, null, null, "guard-create-npc")
        val old = requireNotNull(db.recordsDao().entity(created.actor.actorId))
        val request = old.copy(name = "Fictional librarian", notes = "Confirmed descriptive history")
        val receipt = ledger.records.saveEntity(request, old.revision, source, "guard-rename-npc")
        assertEquals("Fictional librarian", db.recordsDao().characterProfile(created.actor.actorId)?.name)
        assertEquals("Confirmed descriptive history", db.recordsDao().entity(created.actor.actorId)?.notes)
        assertNull(ReforgeRepository(db).get(created.actor.actorId))
        assertNull(db.recordsDao().characterProfile(created.actor.actorId)?.level)
        assertEquals(receipt, ledger.records.saveEntity(request, old.revision, source, "guard-rename-npc"))
    }

    @Test fun managedQuantityRoutesBothRepresentationsAndOriginalRetryDoesNotSpendTwice() = runBlocking {
        val created = managed(); val actor = created.actor.actorId
        val authority = GenesisAuthorityRepository(db); val playable = requireNotNull(ReforgeRepository(db).get(actor))
        authority.createItem(actor, playable.revision, ItemDefinition("guard-definition", "Fictional lens"),
            ItemInstance("guard-physical-lens", "guard-definition", quantity = 3), commandId = "guard-create-lens")
        val item = requireNotNull(db.ledgerDao().getItem("guard-physical-lens"))
        val command = QuantityCommand(item.id, item.revision, -1, source, "guard-consume-lens")
        val receipt = ledger.party.changeQuantity(command)
        assertEquals(2, db.ledgerDao().getItem(item.id)?.quantity)
        assertEquals(2, ReforgeRepository(db).get(actor)?.character?.items?.single { it.id == item.id }?.quantity)
        assertEquals(receipt, ledger.party.changeQuantity(command))
        assertEquals(2, db.ledgerDao().getItem(item.id)?.quantity)
        val unchangedItem = db.ledgerDao().getItem(item.id); val unchangedActor = ReforgeRepository(db).get(actor)
        assertTrue(runCatching { ledger.party.confirmQuantity(QuantityConfirmationCommand(item.id, requireNotNull(unchangedItem).revision,
            null, source, "guard-unknown-managed-quantity")) }.isFailure)
        assertEquals(unchangedItem, db.ledgerDao().getItem(item.id)); assertEquals(unchangedActor, ReforgeRepository(db).get(actor))
    }


    @Test fun enrolledCompanionOwnerLinkCannotBeUnlinkedOrRelabeledThroughLegacyMutation() = runBlocking {
        val owner = managed(); val authority = GenesisAuthorityRepository(db)
        val companion = authority.createActor("Fictional familiar", GenesisActorRole.COMPANION,
            owner.actor.campaignId, owner.actor.actorId, null, "guard-linked-companion")
        val link = db.recordsDao().activeLinks().single { it.fromId == companion.actor.actorId && it.label == "Companion of" }
        assertTrue(runCatching { ledger.records.unlink(link.id, link.revision, source, "guard-owner-unlink") }.isFailure)
        assertTrue(runCatching { ledger.records.saveLink(link.copy(label = "Unrelated friend"), link.revision,
            source, "guard-owner-relabel") }.isFailure)
        assertEquals(link, db.recordsDao().link(link.id))
        assertTrue(db.recordsDao().activeLinks().any { it == link })
        assertEquals(owner.actor.actorId, db.genesisAuthorityDao().actor(companion.actor.actorId)?.ownerActorId)
        assertNull(db.recordsDao().receipt("guard-owner-unlink")); assertNull(db.recordsDao().receipt("guard-owner-relabel"))
        val state = requireNotNull(db.recordsDao().companion(companion.actor.actorId))
        ledger.records.saveCompanionState(state.copy(name = "Fictional named familiar", notes = "Known descriptive capability"), state.revision,
            source, "guard-companion-description")
        assertEquals("Fictional named familiar", db.recordsDao().characterProfile(companion.actor.actorId)?.name)
        assertEquals("Known descriptive capability", db.recordsDao().companion(companion.actor.actorId)?.notes)
        assertNull(ReforgeRepository(db).get(companion.actor.actorId))
    }


    @Test fun typedSyncCompoundHpAndConditionsSharesAuthorityAndKeepsOriginalParentReceipt() = runBlocking {
        val created = managed(); val actor = created.actor.actorId
        val profile = requireNotNull(db.recordsDao().characterProfile(actor)); val sheet = requireNotNull(db.partyDao().sheet(actor))
        val domain = CampaignSyncDomain(db)
        val operations = listOf(
            CampaignSyncOperation("adjust_hp", actor, "character-profile", profile.revision, buildJsonObject { put("delta", -1) }),
            CampaignSyncOperation("set_status", actor, "party-sheet", sheet.revision, buildJsonObject { put("conditions", "Poisoned") })
        )
        val receipt = domain.apply(requireNotNull(created.actor.campaignId), "guard-managed-sync", source, operations)
        val playable = requireNotNull(ReforgeRepository(db).get(actor))
        assertEquals(7, playable.character.currentHp)
        assertEquals(setOf("Poisoned"), playable.character.conditions)
        assertEquals(7, db.recordsDao().characterProfile(actor)?.currentHp)
        assertEquals("Poisoned", db.partyDao().sheet(actor)?.sheet?.conditions)
        assertEquals(profile.revision + 1, db.recordsDao().characterProfile(actor)?.revision)
        assertEquals(sheet.revision + 1, db.partyDao().sheet(actor)?.revision)
        assertEquals(receipt, domain.apply(requireNotNull(created.actor.campaignId), "guard-managed-sync", source, operations))
    }


    @Test fun removedManagedItemCanBeReassignedWithExactAcceptedEffectsQuantityAndPhysicalIdentity() = runBlocking {
        val first = managed("guard-former-owner"); val second = managed("guard-next-owner")
        val local = ReforgeRepository(db); val authority = GenesisAuthorityRepository(db)
        val original = requireNotNull(local.get(first.actor.actorId))
        val definition = ItemDefinition("retained-lens-definition", "Fictional accepted lens", effects = listOf(
            EffectDefinition("retained-lens-effect", "Accepted fictional ward", RuleSource("fictional-ruling", "Reviewed fixture", Acceptance.ACCEPTED_HOMEBREW),
                EffectPayload.Number(NumericTarget.ARMOR_CLASS, NumberOperation.ADD, 2), requiresAttunement = false)))
        val item = ItemInstance("retained-physical-lens", definition.id, quantity = 3)
        authority.createItem(original.character.id, original.revision, definition, item, "guard-retained-create")
        val owned = requireNotNull(local.get(first.actor.actorId))
        local.save(owned.character.id, owned.revision, InventoryRules.remove(owned.character, item.id), "Remove retained fixture", "guard-retained-remove")
        assertNull(db.genesisAuthorityDao().item(item.id)?.ownerActorId)
        val sourceItem = requireNotNull(db.ledgerDao().getItem(item.id)); val placement = requireNotNull(db.partyDao().placement(item.id))
        val move = ItemMoveCommand(item.id, sourceItem.revision, placement.revision, second.actor.actorId,
            state = "Carried", source = source, commandId = "guard-retained-reassign")
        val receipt = ledger.party.moveItem(move)
        assertEquals(receipt, ledger.party.moveItem(move))
        val next = requireNotNull(local.get(second.actor.actorId)).character
        assertEquals(definition, next.itemDefinitions.single { it.id == definition.id })
        assertEquals(item, next.items.single { it.id == item.id })
        assertEquals(3, db.ledgerDao().getItem(item.id)?.quantity)
        assertEquals(second.actor.actorId, db.partyDao().placement(item.id)?.holderProfileId)
        assertEquals(second.actor.actorId, db.genesisAuthorityDao().item(item.id)?.ownerActorId)
        assertFalse(requireNotNull(local.get(first.actor.actorId)).character.items.any { it.id == item.id })
        assertEquals(1, db.ledgerDao().allItems().count { it.id == item.id })
    }


    @Test fun companionResourceRenameSpendAndBackupReopenPreserveItsBoundSourceIdentity() = runBlocking {
        val owner = managed("guard-resource-owner"); val authority = GenesisAuthorityRepository(db)
        val created = authority.createActor("Fictional clockwork owl", GenesisActorRole.COMPANION, owner.actor.campaignId,
            owner.actor.actorId, ReforgeCharacter("guard-resource-companion", "Fictional clockwork owl",
                resources = listOf(ResourcePool("owl-charge-pool", "Old charge name", 4, 3))), "guard-resource-create")
        val actorId = created.actor.actorId; val local = ReforgeRepository(db)
        val prior = db.recordsDao().allCompanionResources().single { it.companionId == actorId }
        val old = requireNotNull(local.get(actorId))
        local.save(actorId, old.revision, old.character.copy(resources = old.character.resources.map {
            if (it.id == "owl-charge-pool") it.copy(name = "Renamed charges") else it
        }), "Rename configured fictional resource", "guard-resource-rename")
        val renamed = requireNotNull(db.recordsDao().resource(prior.id))
        assertEquals("Renamed charges", renamed.name)
        assertEquals(listOf("Renamed charges"), db.partyDao().sheet(actorId)?.sheet?.resources?.map { it.name })
        val receipt = ledger.records.saveCompanionResource(renamed.copy(current = 2), renamed.revision, source, "guard-resource-spend")
        assertEquals(receipt, ledger.records.saveCompanionResource(renamed.copy(current = 2), renamed.revision, source, "guard-resource-spend"))
        assertEquals(2, local.get(actorId)?.character?.resources?.single()?.current)
        assertEquals(2, db.partyDao().sheet(actorId)?.sheet?.resources?.single()?.current)
        assertEquals(prior.id, db.recordsDao().allCompanionResources().single { it.companionId == actorId }.id)
        val backup = authority.backup.exportWithProof().text
        val reopened = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(reopened)
            val preview = recovery.previewRestore(backup)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "guard-resource-restore")
            assertEquals(2, ReforgeRepository(reopened).get(actorId)?.character?.resources?.single()?.current)
            assertEquals("Renamed charges", reopened.recordsDao().resource(prior.id)?.name)
            assertEquals(listOf("Renamed charges"), reopened.partyDao().sheet(actorId)?.sheet?.resources?.map { it.name })
            assertEquals(prior.id, reopened.recordsDao().allCompanionResources().single { it.companionId == actorId }.id)
        } finally { reopened.close() }
    }

}
