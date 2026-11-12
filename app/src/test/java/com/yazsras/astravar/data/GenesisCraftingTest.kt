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

/** Fictional connected crafting through the production transaction, including original portable proof. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisCraftingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val filename = "fictional-genesis-connected-crafting.db"
    private lateinit var db: CampaignLedgerDb
    private lateinit var campaign: CampaignLedgerRepository
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var playable: ReforgeRepository
    private lateinit var crafting: GenesisCraftingRepository
    private val source = "Fictional already-confirmed craft; no time, roll or cost is inferred"
    private val projectId = "fictional-craft-project"
    private val firstId = "fictional-craft-first"
    private val secondId = "fictional-craft-second"
    private val firstMaterial = "fictional-material-partial"
    private val secondMaterial = "fictional-material-full"
    private val sourceMaterial = "fictional-material-source-only"
    private val outputContainer = "fictional-craft-output-container"
    private val secondContainer = "fictional-material-container"

    @Before fun open() { context.deleteDatabase(filename); reopen() }
    private fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, filename).allowMainThreadQueries().build()
        campaign = CampaignLedgerRepository(db); authority = GenesisAuthorityRepository(db)
        playable = ReforgeRepository(db); crafting = GenesisCraftingRepository(db)
    }
    @After fun close() { db.close(); context.deleteDatabase(filename) }
    private suspend fun actor(id: String) = authority.createActor("Fictional $id", GenesisActorRole.PLAYER,
        playable = ReforgeCharacter(id, "Fictional $id", level = 3, baseAbilities = Stat.entries.associateWith { 10 },
            baseMaxHp = 20, baselineConstitutionModifier = 0, currentHp = 11, hpDamage = 9, temporaryHp = 2,
            resources = listOf(ResourcePool("$id-charge", "Charge", maximum = 3, current = 2)),
            slotMaximaOverride = mapOf(1 to 2), spellSlots = mapOf(1 to 1), gameSeconds = 777, turnNumber = 9), commandId = "$id-create").playable!!
    private suspend fun gear(owner: String, id: String, quantity: Int, state: ItemState = ItemState.CARRIED, container: String? = null,
        definition: ItemDefinition = ItemDefinition("$id-definition", "Fictional $id"), attuned: Boolean = false): ReforgeSavedCharacter {
        val current = requireNotNull(playable.get(owner))
        return authority.createItem(owner, current.revision, definition,
            ItemInstance(id, definition.id, state, quantity = quantity, containerId = container, attuned = attuned), "$id-create")
    }
    private suspend fun fixture() {
        campaign.records.saveProject(InventionProject("Fictional confirmed compass", status = "Construction", id = projectId), source = source, commandId = "craft-project-create")
        actor(firstId); actor(secondId)
        gear(firstId, outputContainer, 1)
        gear(firstId, firstMaterial, 5)
        gear(secondId, secondContainer, 1)
        gear(secondId, secondMaterial, 3, ItemState.STORED, secondContainer)
        campaign.create(LedgerDraft("Fictional unassigned resin", quantity = 7, itemState = "Carried", source = source),
            "source-only-material-create", sourceMaterial)
    }
    private suspend fun materials() = listOf(firstMaterial to 2, secondMaterial to 3, sourceMaterial to 2).map { (id, quantity) ->
        MaterialConsumption(id, requireNotNull(db.ledgerDao().getItem(id)).revision, quantity)
    }
    private fun output(title: String = "Fictional crafted compass") = LedgerDraft(title, quantity = 1, itemState = "Carried",
        description = "A confirmed physical output. This description grants no mechanical effects.", evidenceStatus = "Confirmed", source = source)
    private suspend fun review() = crafting.review(projectId, materials(), output(), firstId, outputContainer, source)
    private suspend fun rejectUnchanged(block: suspend () -> Unit): Throwable {
        val before = authority.backup.snapshot()
        val error = runCatching { block() }.exceptionOrNull()
        assertNotNull("Invalid crafting must preserve all source, playable and authority records", error)
        assertEquals(before, authority.backup.snapshot())
        return requireNotNull(error)
    }
    private fun unchangedGameplay(before: ReforgeSavedCharacter, after: ReforgeSavedCharacter) {
        assertEquals(before.origin, after.origin)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.character, after.character.copy(revision = before.revision,
            items = before.character.items, itemDefinitions = before.character.itemDefinitions))
    }

    @Test fun mixedOwnersAndSourceOnlyMaterialsConsumeOnceAndCreateOneOwnedOutputAfterRestart() = runBlocking {
        fixture()
        val beforeFirst = playable.get(firstId)!!; val beforeSecond = playable.get(secondId)!!
        val originalItemProofs = db.genesisAuthorityDao().allItems().associateBy { it.itemId }
        val beforeReview = authority.backup.snapshot()
        val frozen = review()
        assertEquals(beforeReview, authority.backup.snapshot())
        val outcome = crafting.accept(frozen, "connected-craft")
        assertEquals(genesisChild("connected-craft", "craft"), outcome.craftReceipt.commandId)
        assertEquals(outcome.outputId, outcome.craftReceipt.outputId)
        assertEquals(setOf(firstId, secondId), outcome.actors.map { it.character.id }.toSet())
        val afterFirst = playable.get(firstId)!!; val afterSecond = playable.get(secondId)!!
        assertEquals(beforeFirst.revision + 1, afterFirst.revision)
        assertEquals(beforeSecond.revision + 1, afterSecond.revision)
        unchangedGameplay(beforeFirst, afterFirst); unchangedGameplay(beforeSecond, afterSecond)
        assertEquals(3, afterFirst.character.items.single { it.id == firstMaterial }.quantity)
        val depleted = afterSecond.character.items.single { it.id == secondMaterial }
        assertEquals(0, depleted.quantity); assertNull(depleted.containerId)
        assertEquals(3, db.ledgerDao().getItem(firstMaterial)?.quantity)
        assertEquals(0, db.ledgerDao().getItem(secondMaterial)?.quantity)
        assertEquals(5, db.ledgerDao().getItem(sourceMaterial)?.quantity)
        originalItemProofs.forEach { (id, before) ->
            val after = readGenesisAuthorityItem(db, id)!!
            assertEquals("Existing item activation must survive crafting", before.activationCommandId, after.activationCommandId)
            assertEquals("A craft output proof must not replace an existing item's source proof", before.sourceProofJson, after.sourceProofJson)
        }
        assertEquals("Consumed", db.partyDao().placement(secondMaterial)?.state)
        assertNull(db.partyDao().placement(secondMaterial)?.containerItemId)
        assertEquals(secondId, db.partyDao().placement(secondMaterial)?.holderProfileId)
        val physical = afterFirst.character.items.single { it.id == outcome.outputId }
        val definition = afterFirst.character.itemDefinitions.single { it.id == physical.definitionId }
        assertEquals(outcome.outputId, definition.id)
        assertEquals(1, physical.quantity); assertFalse(physical.attuned); assertTrue(definition.effects.isEmpty())
        assertEquals(outputContainer, physical.containerId)
        assertEquals(firstId, db.partyDao().placement(outcome.outputId)?.holderProfileId)
        assertEquals(outputContainer, db.partyDao().placement(outcome.outputId)?.containerItemId)
        assertEquals(1, db.ledgerDao().allItems().count { it.id == outcome.outputId })
        val managed = readGenesisAuthorityItem(db, outcome.outputId)!!
        assertEquals("connected-craft", managed.activationCommandId)
        assertNotNull(managed.sourceProofJson)
        assertEquals(outcome.outputId, db.recordsDao().allCrafts().single().outputItemId)
        val consumptions = db.recordsDao().allConsumptions()
        assertEquals(3, consumptions.size)
        assertEquals(setOf(firstMaterial, secondMaterial, sourceMaterial), consumptions.map { it.itemId }.toSet())
        assertTrue(consumptions.all { it.executionId == outcome.craftReceipt.commandId })
        assertTrue(db.recordsDao().activeLinks().any { it.fromType == "project" && it.fromId == projectId && it.toType == "item" && it.toId == outcome.outputId && it.label == "Confirmed craft output" })
        val complete = authority.backup.snapshot()
        reopen()
        assertEquals(complete, authority.backup.snapshot())
        assertEquals(afterFirst, playable.get(firstId)); assertEquals(afterSecond, playable.get(secondId))
        assertEquals(outcome, crafting.accept(frozen, "connected-craft"))
        assertEquals(complete, authority.backup.snapshot())
    }

    @Test fun retryAfterTransferUsesOriginalProofAndCompleteBackupRestoresNewOwnership() = runBlocking {
        fixture()
        val frozen = review()
        val changedRequest = crafting.review(projectId, materials(), output("Different reviewed output"), firstId, outputContainer, source)
        val outcome = crafting.accept(frozen, "transferred-craft")
        val beforeOwner = playable.get(firstId)!!
        val receiver = playable.get(secondId)!!
        val transferred = playable.transferItem(beforeOwner.character, receiver.character, outcome.outputId, "transfer-crafted-output")
        assertFalse(transferred.first.character.items.any { it.id == outcome.outputId })
        assertEquals(secondId, readGenesisAuthorityItem(db, outcome.outputId)?.ownerActorId)
        val beforeRetry = authority.backup.snapshot()
        assertEquals(outcome, crafting.accept(frozen, "transferred-craft"))
        assertEquals(beforeRetry, authority.backup.snapshot())
        rejectUnchanged { crafting.accept(changedRequest, "transferred-craft") }
        val document = authority.backup.exportWithProof().text
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            val preview = recovery.previewRestore(document)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "restore-transferred-craft")
            assertEquals(transferred.first, ReforgeRepository(target).get(firstId))
            assertEquals(transferred.second, ReforgeRepository(target).get(secondId))
            assertEquals(readGenesisAuthorityItem(db, outcome.outputId), readGenesisAuthorityItem(target, outcome.outputId))
            assertEquals(db.recordsDao().allCrafts(), target.recordsDao().allCrafts())
            assertEquals(db.recordsDao().allConsumptions(), target.recordsDao().allConsumptions())
            assertEquals(outcome, GenesisCraftingRepository(target).accept(frozen, "transferred-craft"))
        } finally { target.close() }
    }

    @Test fun changedProjectSourceQuantityPlayableStateAndMembershipEachInvalidateFrozenReview() = runBlocking {
        fixture()
        var frozen = review()
        val project = db.recordsDao().project(projectId)!!
        campaign.records.saveProject(project.copy(description = "Later fictional project detail"), project.revision, source, "craft-project-changed")
        rejectUnchanged { crafting.accept(frozen, "stale-project-craft") }
        frozen = review()
        val material = db.ledgerDao().getItem(sourceMaterial)!!
        campaign.party.changeQuantity(QuantityCommand(material.id, material.revision, 1, source, "craft-source-quantity-changed"))
        rejectUnchanged { crafting.accept(frozen, "stale-material-craft") }
        frozen = review()
        val actor = playable.get(secondId)!!
        playable.save(secondId, actor.revision, actor.character.copy(notes = "Later fictional notes"), "Fictional notes", "craft-playable-changed")
        rejectUnchanged { crafting.accept(frozen, "stale-playable-craft") }
        frozen = review()
        val membership = authority.actor(firstId)!!
        authority.updateActorAssociation(firstId, membership.revision, GenesisActorRole.NPC, null, null, "craft-membership-changed")
        rejectUnchanged { crafting.accept(frozen, "stale-membership-craft") }
        assertTrue(db.recordsDao().allCrafts().isEmpty())
        assertTrue(db.recordsDao().allConsumptions().isEmpty())
    }

    @Test fun laterOutputEditsSurviveRestartAndRecoveryWithoutRewritingOriginalCraftProof() = runBlocking {
        fixture()
        val frozen = review()
        val outcome = crafting.accept(frozen, "edited-output-craft")
        val originalReceipt = readGenesisAuthorityReceipt(db, "edited-output-craft")!!
        val originalProof = readGenesisAuthorityItem(db, outcome.outputId)!!.sourceProofJson
        val originalSourceOutput = outcome.sourceOutput
        val current = playable.get(firstId)!!
        val physical = current.character.items.single { it.id == outcome.outputId }
        val edited = playable.save(firstId, current.revision, current.character.copy(
            itemDefinitions = current.character.itemDefinitions.map { definition ->
                if (definition.id == physical.definitionId) definition.copy(name = "Fictional renamed crafted compass") else definition
            },
            items = current.character.items.map { item -> if (item.id == outcome.outputId) item.copy(quantity = 2) else item }),
            "Record reviewed fictional output edits", "edit-crafted-output")
        val latestAuthority = readGenesisAuthorityItem(db, outcome.outputId)!!
        assertEquals("edited-output-craft", latestAuthority.activationCommandId)
        assertEquals(originalProof, latestAuthority.sourceProofJson)
        assertEquals("Fictional renamed crafted compass", db.ledgerDao().getItem(outcome.outputId)?.title)
        assertEquals(2, db.ledgerDao().getItem(outcome.outputId)?.quantity)
        assertEquals(1, originalSourceOutput.quantity)
        assertEquals("Fictional crafted compass", originalSourceOutput.title)
        assertEquals(originalReceipt, readGenesisAuthorityReceipt(db, "edited-output-craft"))
        assertEquals(outcome.craftReceipt, db.recordsDao().receipt(outcome.craftReceipt.commandId))
        val document = authority.backup.exportWithProof().text
        val afterEdit = authority.backup.snapshot()
        reopen()
        assertEquals(afterEdit, authority.backup.snapshot())
        assertEquals(edited, playable.get(firstId))
        assertEquals(outcome, crafting.accept(frozen, "edited-output-craft"))
        assertEquals(afterEdit, authority.backup.snapshot())
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            val preview = recovery.previewRestore(document)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "restore-edited-craft")
            assertEquals(edited, ReforgeRepository(target).get(firstId))
            assertEquals(latestAuthority, readGenesisAuthorityItem(target, outcome.outputId))
            assertEquals(originalReceipt, readGenesisAuthorityReceipt(target, "edited-output-craft"))
            val recoveredOutcome = GenesisCraftingRepository(target).accept(frozen, "edited-output-craft")
            assertEquals(outcome, recoveredOutcome)
            assertEquals(originalSourceOutput, recoveredOutcome.sourceOutput)
            assertEquals(2, target.ledgerDao().getItem(outcome.outputId)?.quantity)
            assertEquals("Fictional renamed crafted compass", target.ledgerDao().getItem(outcome.outputId)?.title)
        } finally { target.close() }
    }

    @Test fun changedOutputContainerAndNewSourcePlacementInvalidateFrozenReview() = runBlocking {
        fixture()
        val frozen = review()
        val box = db.ledgerDao().getItem(outputContainer)!!
        campaign.party.moveItem(ItemMoveCommand(box.id, box.revision, holderProfileId = firstId, state = "Stored", source = source, commandId = "craft-container-moved"))
        rejectUnchanged { crafting.accept(frozen, "stale-container-craft") }
        val next = review()
        val sourceItem = db.ledgerDao().getItem(sourceMaterial)!!
        campaign.party.moveItem(ItemMoveCommand(sourceItem.id, sourceItem.revision, state = "Stored", source = source, commandId = "craft-source-placement-added"))
        rejectUnchanged { crafting.accept(next, "stale-placement-craft") }
        assertTrue(db.recordsDao().allCrafts().isEmpty())
    }

    @Test fun databaseFailureAfterLegacyCraftRollsBackMaterialsOutputProjectAndPlayableChildren() = runBlocking {
        fixture()
        val frozen = review()
        val actor = playable.get(secondId)!!
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_second_canonical_craft
            BEFORE UPDATE ON reforge_characters WHEN NEW.profileId='$secondId' AND NEW.revision=${actor.revision + 1}
            BEGIN SELECT RAISE(ABORT, 'fictional canonical crafting fault'); END""")
        rejectUnchanged { crafting.accept(frozen, "faulted-connected-craft") }
        assertTrue(db.recordsDao().allCrafts().isEmpty()); assertTrue(db.recordsDao().allConsumptions().isEmpty())
        assertNull(db.recordsDao().receipt(genesisChild("faulted-connected-craft", "craft")))
        assertNull(readGenesisAuthorityReceipt(db, "faulted-connected-craft"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_second_canonical_craft")
        val accepted = crafting.accept(frozen, "faulted-connected-craft")
        assertNotNull(db.ledgerDao().getItem(accepted.outputId))
        assertEquals(3, db.recordsDao().allConsumptions().size)
    }

    @Test fun legacyGuardRemainsAndUnconnectedPlayableMaterialCannotBeConsumedAsSourceOnly() = runBlocking {
        fixture()
        val project = db.recordsDao().project(projectId)!!
        rejectUnchanged { campaign.records.confirmCraft(CraftCommand(projectId, project.revision, materials(), output(), source, "legacy-managed-craft")) }
        val unconnected = playable.create(ReforgeCharacter("unconnected-material-owner", "Fictional unconnected owner",
            itemDefinitions = listOf(ItemDefinition(sourceMaterial, "Fictional unassigned resin")),
            items = listOf(ItemInstance(sourceMaterial, sourceMaterial, quantity = 7))), "unconnected-material-sheet")
        rejectUnchanged { review() }
        assertEquals(unconnected, playable.get(unconnected.character.id))
        assertEquals(7, db.ledgerDao().getItem(sourceMaterial)?.quantity)
    }

    @Test fun plainSourceOnlyCraftingStillUsesExistingConfirmedLifecycle() = runBlocking {
        campaign.records.saveProject(InventionProject("Fictional source-only craft", id = projectId), source = source, commandId = "legacy-project-create")
        val material = campaign.create(LedgerDraft("Fictional source-only component", quantity = 4, source = source), "legacy-material-create")
        val command = CraftCommand(projectId, 1, listOf(MaterialConsumption(material.id, material.revision, 2)), output(), source, "legacy-source-only-craft")
        val receipt = campaign.records.confirmCraft(command)
        assertEquals(receipt, campaign.records.confirmCraft(command))
        assertEquals(2, db.ledgerDao().getItem(material.id)?.quantity)
        assertEquals(1, db.ledgerDao().getItem(receipt.outputId!!)?.quantity)
        assertTrue(db.genesisAuthorityDao().allItems().isEmpty())
        assertTrue(db.reforgeDao().allCharacters().isEmpty())
    }

    @Test fun equippedAttunedEffectBearingAndOccupiedContainerMaterialsRemainReviewOnly() = runBlocking {
        fixture()
        gear(firstId, "unsupported-equipped", 1, ItemState.WORN)
        gear(firstId, "unsupported-attuned", 1,
            definition = ItemDefinition("unsupported-attuned-definition", "Fictional attuned material", requiresAttunement = true), attuned = true)
        gear(firstId, "unsupported-effect", 1, definition = ItemDefinition("unsupported-effect-definition", "Fictional mechanical material",
            effects = listOf(EffectDefinition("unsupported-effect-bonus", "Accepted fictional passive bonus",
                RuleSource("fictional-material-effect", "Accepted fictional fixture", Acceptance.ACCEPTED_HOMEBREW),
                EffectPayload.Number(NumericTarget.ARMOR_CLASS, NumberOperation.ADD, 1)))))
        gear(firstId, "unsupported-container-content", 1, ItemState.STORED, outputContainer)
        for (id in listOf("unsupported-equipped", "unsupported-attuned", "unsupported-effect", outputContainer)) {
            val material = db.ledgerDao().getItem(id)!!
            rejectUnchanged { crafting.review(projectId, listOf(MaterialConsumption(id, material.revision, 1)), output(), firstId, null, source) }
        }
        assertTrue(db.recordsDao().allCrafts().isEmpty())
    }

    @Test fun attackAndCompositeDependenciesCannotBeConsumedByOrdinaryMaterialPath() = runBlocking {
        fixture()
        val before = playable.get(firstId)!!
        val compositeDefinition = ItemDefinition("unsupported-composite-definition", "Fictional composite")
        authority.createItem(firstId, before.revision, compositeDefinition,
            ItemInstance("unsupported-composite", compositeDefinition.id, donorInstanceIds = setOf(firstMaterial)), "unsupported-composite-create")
        val current = playable.get(firstId)!!
        playable.save(firstId, current.revision, current.character.copy(attacks = listOf(
            AttackDefinition("unsupported-required-attack", "Fictional configured tool attack", requiredItemId = firstMaterial))),
            "Configure fictional dependency", "unsupported-attack-configure")
        for (id in listOf(firstMaterial, "unsupported-composite")) {
            val material = db.ledgerDao().getItem(id)!!
            rejectUnchanged { crafting.review(projectId, listOf(MaterialConsumption(id, material.revision, 1)), output(), firstId, null, source) }
        }
        assertTrue(db.recordsDao().allCrafts().isEmpty())
    }

    @Test fun craftedPortableProofRejectsMissingLegacyReceiptConsumptionOrProjectLink() = runBlocking {
        fixture()
        val outcome = crafting.accept(review(), "portable-craft")
        val current = authority.backup.snapshot()
        val records = current.campaign.records
        val corruptions = listOf(
            records.copy(receipts = records.receipts.filterNot { it.commandId == outcome.craftReceipt.commandId }),
            records.copy(consumptions = records.consumptions.drop(1)),
            records.copy(links = records.links.filterNot { it.fromType == "project" && it.fromId == projectId && it.toId == outcome.outputId }),
            records.copy(crafts = records.crafts.map { it.copy(outputItemId = firstMaterial) }))
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            corruptions.forEach { modified ->
                val preview = recovery.previewRestore(genesisAuthorityBackupJson(current.copy(campaign = current.campaign.copy(records = modified))))
                assertFalse("Missing or changed original crafting proof must reject", preview.valid)
            }
            assertTrue(target.reforgeDao().allCharacters().isEmpty())
            assertTrue(target.genesisAuthorityDao().allItems().isEmpty())
        } finally { target.close() }
    }
}
