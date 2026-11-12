package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.InventoryRules
import com.yazsras.astravar.rules.reforge.ResourcePool
import com.yazsras.astravar.rules.reforge.SecureDice
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

/** Fictional complete recovery; source and target never touch installed campaign files. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisAuthorityBackupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var sourceDb: CampaignLedgerDb
    private lateinit var targetDb: CampaignLedgerDb
    private lateinit var source: GenesisAuthorityBackupRepository
    private lateinit var target: GenesisAuthorityBackupRepository
    private val sourceLabel = "Fictional connected recovery fixture"
    private val recoveryDbName = "fictional-connected-backup-restart.db"

    @Before fun open() {
        context.deleteDatabase(recoveryDbName)
        sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        source = GenesisAuthorityBackupRepository(sourceDb, clock = { 1_000L })
        target = GenesisAuthorityBackupRepository(targetDb, clock = { 2_000L })
    }
    @After fun close() { sourceDb.close(); targetDb.close(); context.deleteDatabase(recoveryDbName) }

    private suspend fun fixture(db: CampaignLedgerDb = sourceDb, id: String = "recovery-player") {
        val ledger = CampaignLedgerRepository(db)
        ledger.records.saveProfile(CharacterProfile(id = id, name = "Fictional traveller", currentHp = 7, maximumHp = 14),
            source = sourceLabel, commandId = "$id-profile")
        ledger.create(LedgerDraft("Fictional lantern", quantity = 1, source = sourceLabel,
            description = "Preserve éclair, 雪, 🦉, quotation \" and slash \\ exactly."), "$id-item-create", "$id-item")
        val playable = ReforgeRepository(db)
        val actor = playable.create(ReforgeCharacter(id, "Fictional traveller", currentHp = 7, baseMaxHp = 14), "$id-create")
        playable.appendDiceRoll(id, actor.revision, "Recorded fictional roll",
            SecureDice.manual(SecureDice.parse("1d20+2"), listOf(11)), commandId = "$id-roll")
        db.reforgeDao().insert(ReforgeNoteDraft(id, actor.revision, "Uncommitted recovery note", 1_010))
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("An invalid merge must reject before leaving partial records", failure)
        return requireNotNull(failure)
    }

    @Test fun completeRoundTripPreservesBothHistoriesDraftsAndInstallationLocalReceipt() = runBlocking {
        fixture()
        val originalLocal = GenesisStorageReceipt(sourceVersion = 1, sourceDigest = "a".repeat(64), countsJson = "{\"fixture\":1}", completedAt = 7)
        val targetLocal = originalLocal.copy(sourceDigest = "b".repeat(64), completedAt = 8)
        sourceDb.genesisStorageDao().insert(originalLocal)
        targetDb.genesisStorageDao().insert(targetLocal)
        val snapshot = source.exportWithProof()
        assertFalse(snapshot.text.contains("sourceDigest"))
        assertFalse(snapshot.text.contains("campaign-ledger-reforge-v1"))
        val decoded = decodeGenesisAuthorityBackup(snapshot.text)
        assertEquals(source.snapshot(), decoded.copy(exportedAt = 0))
        val preview = target.previewRestore(snapshot.text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        val receipt = target.restore(preview, "complete-restore")
        assertEquals(sourceDb.ledgerDao().allItems(), targetDb.ledgerDao().allItems())
        assertEquals(sourceDb.reforgeDao().allCharacters(), targetDb.reforgeDao().allCharacters())
        assertEquals(sourceDb.reforgeDao().allChanges(), targetDb.reforgeDao().allChanges())
        assertEquals(sourceDb.reforgeDao().allRolls(), targetDb.reforgeDao().allRolls())
        assertEquals(sourceDb.reforgeDao().allDrafts(), targetDb.reforgeDao().allDrafts())
        assertEquals(targetLocal, targetDb.genesisStorageDao().receipt())
        assertEquals(originalLocal, sourceDb.genesisStorageDao().receipt())
        val final = decodeGenesisAuthorityBackup(target.exportWithProof().text)
        assertEquals(receipt, final.campaign.records.receipts.single { it.commandId == "complete-restore" })
        assertEquals(1, final.campaign.records.audit.count { it.commandId == "complete-restore" })
    }

    @Test fun oldCampaignAndCharacterFilesMergeThroughCompleteReviewWithoutDroppingEitherDomain() = runBlocking {
        fixture()
        val campaignText = CampaignLedgerRepository(sourceDb).export()
        val characterText = ReforgeRepository(sourceDb).export()
        val campaignReview = target.previewRestore(campaignText)
        assertTrue(campaignReview.conflicts.joinToString(), campaignReview.valid)
        target.restore(campaignReview, "old-campaign-restore")
        val characterReview = target.previewRestore(characterText)
        assertTrue(characterReview.conflicts.joinToString(), characterReview.valid)
        target.restore(characterReview, "old-character-restore")
        assertEquals(sourceDb.ledgerDao().allItems(), targetDb.ledgerDao().allItems())
        assertEquals(sourceDb.reforgeDao().allCharacters(), targetDb.reforgeDao().allCharacters())
        assertTrue(target.exportWithProof().text.contains("astravar-connected-campaign"))
    }

    @Test fun stalePreviewAndChangedReviewPayloadRejectBeforeAnyIncomingRows() = runBlocking {
        fixture()
        val preview = target.previewRestore(source.exportWithProof().text)
        CampaignLedgerRepository(targetDb).create(LedgerDraft("Later fictional record", source = sourceLabel), "later-record")
        val before = target.snapshot()
        rejected { target.restore(preview, "stale-restore") }
        assertEquals(before, target.snapshot())
        val fresh = target.recheck(preview)
        assertTrue(fresh.valid)
        val forged = fresh.copy(backup = requireNotNull(fresh.backup).copy(authority = GenesisAuthorityExport(
            campaigns = listOf(GenesisCampaignRecord("injected", "Unreviewed", createdAt = 1, updatedAt = 1, commandId = "injected-command")))))
        rejected { target.restore(forged, "forged-restore") }
        assertEquals(before, target.snapshot())
        assertNull(targetDb.recordsDao().receipt("stale-restore"))
        assertNull(targetDb.recordsDao().receipt("forged-restore"))
    }

    @Test fun identicalRestoreRetryReturnsOriginalAfterLaterEditsButChangedRequestIsRejected() = runBlocking {
        fixture()
        val preview = target.previewRestore(source.exportWithProof().text)
        val receipt = target.restore(preview, "retry-complete-restore")
        val playable = ReforgeRepository(targetDb)
        val current = requireNotNull(playable.get("recovery-player"))
        val changed = playable.save(current.character.id, current.revision, current.character.copy(notes = "Later committed note"), "Change notes", "later-note")
        assertEquals(receipt, target.restore(preview, "retry-complete-restore"))
        assertEquals(changed, playable.get(current.character.id))
        fixture(sourceDb, "other-player")
        val different = target.previewRestore(source.exportWithProof().text)
        rejected { target.restore(different, "retry-complete-restore") }
        assertEquals(changed, playable.get(current.character.id))
    }

    @Test fun exactRowConflictsBlockWholeMergeAndKeepUnrelatedIncomingActorUnwritten() = runBlocking {
        fixture(); fixture(sourceDb, "additional-player")
        fixture(targetDb)
        val playable = ReforgeRepository(targetDb)
        val existing = requireNotNull(playable.get("recovery-player"))
        playable.save(existing.character.id, existing.revision, existing.character.copy(currentHp = 6), "Fictional injury", "local-injury")
        val before = target.snapshot()
        val candidate = target.previewRestore(source.exportWithProof().text)
        assertFalse(candidate.valid)
        assertTrue(candidate.conflicts.any { it.contains("differs", ignoreCase = true) })
        rejected { target.restore(candidate, "conflicting-restore") }
        assertEquals(before, target.snapshot())
        assertNull(targetDb.reforgeDao().character("additional-player"))
    }

    @Test fun databaseFailureAfterBothDomainsInsertRollsBackRowsHistoryAndRestoreProof() = runBlocking {
        fixture()
        val connected = GenesisAuthorityRepository(sourceDb)
        val campaign = connected.createCampaign("Fictional rollback campaign", commandId = "rollback-campaign-create")
        connected.createActor("Fictional rollback contact", GenesisActorRole.NPC, campaign.id, commandId = "rollback-contact-create")
        val preview = target.previewRestore(source.exportWithProof().text)
        val before = target.snapshot()
        targetDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fixture_reject_connected_receipt
            BEFORE INSERT ON command_receipts WHEN NEW.commandId = 'faulted-complete-restore'
            BEGIN SELECT RAISE(ABORT, 'fictional restore fault'); END""")
        rejected { target.restore(preview, "faulted-complete-restore") }
        assertEquals(before, target.snapshot())
        assertNull(targetDb.recordsDao().receipt("faulted-complete-restore"))
        assertTrue(targetDb.reforgeDao().allCharacters().isEmpty())
        assertTrue(targetDb.genesisAuthorityDao().allCampaigns().isEmpty())
        assertTrue(targetDb.genesisAuthorityDao().allActors().isEmpty())
        assertTrue(targetDb.genesisAuthorityDao().allReceipts().isEmpty())
        targetDb.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_reject_connected_receipt")
        val receipt = target.restore(preview, "faulted-complete-restore")
        assertEquals("faulted-complete-restore", receipt.commandId)
        assertEquals(sourceDb.reforgeDao().allCharacters(), targetDb.reforgeDao().allCharacters())
    }

    @Test fun missingDomainsUnknownHeadersDuplicatesAndBrokenHistoryAreRejectedInPreview() = runBlocking {
        fixture()
        val snapshot = source.exportWithProof().text
        val tree = Json.parseToJsonElement(snapshot).jsonObject
        val variants = listOf(
            JsonObject(tree - "authority").toString(),
            JsonObject(tree + ("schema" to JsonPrimitive(99))).toString(),
            JsonObject(tree + ("unsupported" to JsonPrimitive(true))).toString(),
            snapshot.replaceFirst("{", "{\"format\":\"astravar-connected-campaign\","),
            genesisAuthorityBackupJson(decodeGenesisAuthorityBackup(snapshot).let {
                it.copy(playable = it.playable.copy(changes = emptyList()))
            }))
        variants.forEach { malformed ->
            val preview = target.previewRestore(malformed)
            assertFalse(preview.summary, preview.valid)
        }
        assertTrue(targetDb.ledgerDao().allItems().isEmpty())
        assertTrue(targetDb.reforgeDao().allCharacters().isEmpty())
    }

    @Test fun oldUnreviewedDifferencesAndOpaqueProvenanceSurviveCompleteExportAndRestore() = runBlocking {
        val profile = CharacterProfile(id = "unreconciled-actor", name = "Historical source name", currentHp = 8, maximumHp = 20)
        CampaignLedgerRepository(sourceDb).records.saveProfile(profile, source = sourceLabel, commandId = "unreconciled-source")
        val opaque = """{ "historical-source" : "éclair 雪 🦉", "unrecognized-version": 37 }"""
        val origin = ReforgeOrigin(label = "Retained historical adoption, review still required", sourceProfileId = profile.id,
            sourceFingerprint = "a".repeat(64), sourceSnapshotJson = opaque, warnings = listOf("Unapproved inherited difference"))
        val actor = ReforgeRepository(sourceDb).createWithOrigin(ReforgeCharacter(profile.id, "Different playable name",
            currentHp = 3, baseMaxHp = 14), origin, "Historical adoption fixture", "unreconciled-playable")
        val text = source.exportWithProof().text
        val incoming = decodeGenesisAuthorityBackup(text)
        assertTrue(incoming.authority.actors.isEmpty())
        assertEquals(opaque, incoming.playable.characters.single().saved().origin.sourceSnapshotJson)
        val preview = target.previewRestore(text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        target.restore(preview, "unreconciled-restore")
        assertEquals(actor, ReforgeRepository(targetDb).get(profile.id))
        assertEquals("Historical source name", targetDb.recordsDao().characterProfile(profile.id)?.name)
        assertEquals(8, targetDb.recordsDao().characterProfile(profile.id)?.currentHp)
        assertTrue(targetDb.genesisAuthorityDao().allActors().isEmpty())
        assertEquals(opaque, decodeGenesisAuthorityBackup(target.exportWithProof().text).playable.characters.single().saved().origin.sourceSnapshotJson)
    }

    @Test fun originalRestoreReceiptSurvivesRestartAndRetryDoesNotOverwriteLaterState() = runBlocking {
        fixture()
        targetDb.close()
        targetDb = Room.databaseBuilder(context, CampaignLedgerDb::class.java, recoveryDbName).allowMainThreadQueries().build()
        target = GenesisAuthorityBackupRepository(targetDb)
        val preview = target.previewRestore(source.exportWithProof().text)
        val first = target.restore(preview, "disk-restore-once")
        targetDb.close()
        targetDb = Room.databaseBuilder(context, CampaignLedgerDb::class.java, recoveryDbName).allowMainThreadQueries().build()
        target = GenesisAuthorityBackupRepository(targetDb)
        val playable = ReforgeRepository(targetDb)
        val restored = requireNotNull(playable.get("recovery-player"))
        val later = playable.save(restored.character.id, restored.revision, restored.character.copy(currentHp = 5),
            "Later fictional injury", "disk-later-change")
        assertEquals(first, target.restore(preview, "disk-restore-once"))
        val reviewedAgain = target.previewRestore(source.exportWithProof().text)
        assertFalse(reviewedAgain.valid) // New merge would overwrite later state; the exact old receipt remains retrievable.
        assertEquals(first, target.restore(reviewedAgain, "disk-restore-once"))
        assertEquals(later, playable.get(restored.character.id))
        assertEquals(1, targetDb.recordsDao().allReceipts().count { it.commandId == "disk-restore-once" })
    }

    @Test fun individuallyValidBackupsCannotCreateTwoOwnersOfOnePhysicalItemOnMerge() = runBlocking {
        fun character(id: String) = ReforgeCharacter(id, id,
            itemDefinitions = listOf(ItemDefinition("shared-template", "Fictional lens")),
            items = listOf(ItemInstance("same-physical-id", "shared-template")))
        ReforgeRepository(sourceDb).create(character("incoming-owner"), "incoming-owner-create")
        ReforgeRepository(targetDb).create(character("existing-owner"), "existing-owner-create")
        val text = source.exportWithProof().text
        val before = target.snapshot()
        val preview = target.previewRestore(text)
        assertFalse(preview.valid)
        assertTrue(preview.conflicts.joinToString(), preview.conflicts.any { "Physical item" in it })
        rejected { target.restore(preview, "two-owners-restore") }
        assertEquals(before, target.snapshot())
    }

    @Test fun wideHistoricalBeforeAndAfterRowsExportAndRestoreWithoutCursorWindowReads() = runBlocking {
        val rawSource = "{\"legacy-source\":\"" + "x".repeat(1_600_000) + "雪🦉\"}"
        val playable = ReforgeRepository(sourceDb)
        val actor = playable.createWithOrigin(ReforgeCharacter("wide-history", "Fictional wide history", currentHp = 8, baseMaxHp = 12),
            ReforgeOrigin(label = "Retained large historical source", sourceSnapshotJson = rawSource), "Historical source fixture", "wide-create")
        val changed = playable.save(actor.character.id, actor.revision, actor.character.copy(currentHp = 7), "Fictional damage", "wide-save")
        val width = sourceDb.openHelper.writableDatabase.query(
            "SELECT length(CAST(beforeJson AS BLOB))+length(CAST(afterJson AS BLOB)) FROM reforge_changes WHERE commandId='wide-save'").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        assertTrue(width > 3 * 1024 * 1024)
        val original = source.snapshot()
        val exported = source.exportWithProof()
        val preview = target.previewRestore(exported.text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        target.restore(preview, "wide-history-restore")
        val recovered = target.snapshot()
        assertEquals(original.playable, recovered.playable)
        assertEquals(changed, recovered.playable.characters.single().saved())
        assertEquals(rawSource, recovered.playable.characters.single().saved().origin.sourceSnapshotJson)
        assertEquals(original.playable.changes.single { it.commandId == "wide-save" },
            recovered.playable.changes.single { it.commandId == "wide-save" })
    }

    @Test fun wideAuthorityReceiptFieldsAreReadExactlyAndMalformedSqlTextIsNotReplaced() = runBlocking {
        val receipt = GenesisAuthorityReceipt("wide-authority-read", "fixture-read-only", "a".repeat(64),
            "a".repeat(1_600_000) + "雪", "b".repeat(1_600_000) + "🦉", 1)
        // Isolate transport from semantic approval: this raw fixture is never accepted as authority.
        sourceDb.genesisAuthorityDao().insert(receipt)
        assertEquals(receipt, genesisAuthorityBackupRows(sourceDb).receipts.single())
        assertEquals(receipt, readGenesisAuthorityReceipt(sourceDb, receipt.commandId))
        assertNull(readGenesisAuthorityReceipt(sourceDb, "not-present"))
        fixture(targetDb)
        val sql = targetDb.openHelper.writableDatabase
        sql.execSQL("UPDATE reforge_note_drafts SET text=CAST(x'F08080' AS TEXT) WHERE profileId='recovery-player'")
        rejected { target.exportWithProof() }
        val bytes = sql.query("SELECT CAST(text AS BLOB) FROM reforge_note_drafts WHERE profileId='recovery-player'").use {
            assertTrue(it.moveToFirst()); it.getBlob(0)
        }
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte()), bytes)
    }

    @Test fun connectedCampaignActorsCompanionEquipmentAndReceiptsRoundTripAsOneAuthorityGraph() = runBlocking {
        val authority = GenesisAuthorityRepository(sourceDb)
        val campaign = authority.createCampaign("Fictional recovery campaign", "Unknown facts remain unknown.", "managed-campaign")
        val player = authority.createActor("Fictional player", GenesisActorRole.PLAYER, campaign.id,
            playable = ReforgeCharacter("managed-player", "Fictional player", currentHp = 8, baseMaxHp = 12), commandId = "managed-player-create")
        val companion = authority.createActor("Fictional owl", GenesisActorRole.COMPANION, campaign.id, player.actor.actorId,
            commandId = "managed-companion-create")
        val unassigned = authority.updateActorAssociation(companion.actor.actorId, companion.actor.revision, GenesisActorRole.COMPANION,
            campaign.id, null, "managed-companion-unlink")
        authority.updateActorAssociation(companion.actor.actorId, unassigned.revision, GenesisActorRole.COMPANION,
            campaign.id, player.actor.actorId, "managed-companion-relink")
        val npc = authority.createActor("Fictional archivist", GenesisActorRole.NPC, campaign.id, commandId = "managed-npc-create")
        assertNull(npc.profile.level); assertNull(npc.profile.currentHp); assertNull(npc.playable)
        val equipment = authority.createItem(player.actor.actorId, requireNotNull(player.playable).revision,
            ItemDefinition("managed-lantern-template", "Fictional lantern", description = "No unapproved mechanical effects."),
            ItemInstance("managed-lantern", "managed-lantern-template", quantity = 2), "managed-item-create")
        ReforgeRepository(sourceDb).save(equipment.character.id, equipment.revision,
            equipment.character.copy(items = equipment.character.items.map { it.copy(quantity = 3) }), "Record reviewed quantity", "managed-quantity")
        val original = source.snapshot()
        val text = source.exportWithProof().text
        val preview = target.previewRestore(text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        target.restore(preview, "managed-complete-restore")
        val recovered = target.snapshot()
        assertEquals(original.authority, recovered.authority)
        assertEquals(original.playable, recovered.playable)
        assertEquals(original.campaign.items, recovered.campaign.items)
        assertEquals(original.campaign.records.placements, recovered.campaign.records.placements)
        assertEquals(original.campaign.records.companionStates, recovered.campaign.records.companionStates)
        assertEquals(original.campaign.records.links, recovered.campaign.records.links)
        assertEquals(2, recovered.campaign.records.links.count { it.fromId == companion.actor.actorId })
        assertTrue(recovered.campaign.records.audit.any { it.recordType == "link" && it.kind == "UNLINK" })
        val badNames = recovered.campaign.copy(records = recovered.campaign.records.copy(entities = recovered.campaign.records.entities.map {
            if (it.id == npc.actor.actorId) it.copy(name = "Disagreeing NPC projection") else it
        }))
        assertTrue(genesisAuthorityBackupErrors(recovered.authority, badNames, recovered.playable).any { "NPC name differs" in it })
        val badLinks = recovered.campaign.copy(records = recovered.campaign.records.copy(links = recovered.campaign.records.links.map {
            if (it.fromId == companion.actor.actorId) it.copy(toId = npc.actor.actorId) else it
        }))
        assertTrue(genesisAuthorityBackupErrors(recovered.authority, badLinks, recovered.playable).any { "ownership link differs" in it })
        assertEquals(3, recovered.campaign.items.single { it.id == "managed-lantern" }.quantity)
        assertTrue(genesisAuthorityBackupErrors(recovered.authority, recovered.campaign, recovered.playable).isEmpty())
        assertEquals(text, source.exportWithProof().text)
    }

    @Test fun strippingAuthorityOrChangingManagedProjectionCannotDowngradeConnectedBackup() = runBlocking {
        val authority = GenesisAuthorityRepository(sourceDb)
        val created = authority.createActor("Fictional owner", GenesisActorRole.PLAYER,
            playable = ReforgeCharacter("portable-managed", "Fictional owner"), commandId = "portable-managed-create")
        authority.createItem(created.actor.actorId, requireNotNull(created.playable).revision,
            ItemDefinition("portable-definition", "Fictional compass"), ItemInstance("portable-item", "portable-definition"), "portable-item-create")
        val complete = source.snapshot()
        val corruptions = listOf(
            complete.copy(authority = GenesisAuthorityExport()),
            complete.copy(playable = complete.playable.copy(characters = emptyList(), changes = emptyList(), rolls = emptyList())),
            complete.copy(authority = complete.authority.copy(items = complete.authority.items.map {
                val instance = reforgeJson.decodeFromString<ItemInstance>(it.instanceJson)
                it.copy(instanceJson = reforgeJson.encodeToString(instance.copy(quantity = 99)))
            })),
            complete.copy(authority = complete.authority.copy(receipts = complete.authority.receipts.drop(1))))
        corruptions.forEach { value ->
            val preview = target.previewRestore(genesisAuthorityBackupJson(value))
            assertFalse(preview.conflicts.joinToString(), preview.valid)
        }
        assertFalse(target.previewRestore(campaignJson.encodeToString(complete.campaign)).valid)
        assertFalse(target.previewRestore(reforgeJson.encodeToString(complete.playable)).valid)
        assertTrue(targetDb.reforgeDao().allCharacters().isEmpty())
        assertTrue(targetDb.genesisAuthorityDao().allActors().isEmpty())
    }

    @Test fun removingAssociationRetainsOnePhysicalItemItsMechanicsAndHistoricalActivationOnRestore() = runBlocking {
        val authority = GenesisAuthorityRepository(sourceDb)
        val created = authority.createActor("Fictional owner", GenesisActorRole.PLAYER,
            playable = ReforgeCharacter("removed-owner", "Fictional owner"), commandId = "removed-owner-create")
        val equipped = authority.createItem(created.actor.actorId, requireNotNull(created.playable).revision,
            ItemDefinition("retained-template", "Retained fictional item"), ItemInstance("retained-physical-id", "retained-template"), "retained-item-create")
        ReforgeRepository(sourceDb).save(equipped.character.id, equipped.revision,
            InventoryRules.remove(equipped.character, "retained-physical-id"), "Remove ownership", "remove-connected-owner")
        val original = source.snapshot()
        val retained = original.authority.items.single()
        assertNull(retained.ownerActorId)
        assertEquals("retained-physical-id", reforgeJson.decodeFromString<ItemInstance>(retained.instanceJson).id)
        val review = target.previewRestore(source.exportWithProof().text)
        assertTrue(review.conflicts.joinToString(), review.valid)
        target.restore(review, "restore-unassigned-item")
        assertEquals(retained, target.snapshot().authority.items.single())
        assertNotNull(targetDb.ledgerDao().getItem("retained-physical-id"))
        assertTrue(requireNotNull(ReforgeRepository(targetDb).get("removed-owner")).character.items.isEmpty())
    }

    @Test fun managedAcquisitionRetainsExactUnapprovedSourceProofThroughCompleteRestore() = runBlocking {
        val campaign = CampaignLedgerRepository(sourceDb)
        val ownerId = "acquiring-managed"
        campaign.records.saveProfile(CharacterProfile(id = ownerId, name = "Fictional acquiring owner"), source = sourceLabel, commandId = "acquiring-source-profile")
        val item = campaign.create(LedgerDraft("Fictional sourced lens", quantity = 2, evidenceStatus = "Proposed",
            itemState = "Carried", source = sourceLabel, description = "This source does not approve any mechanical modifier."),
            "source-lens-create", "source-lens")
        campaign.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = ownerId,
            source = sourceLabel, commandId = "source-lens-place"))
        val playable = ReforgeRepository(sourceDb)
        playable.create(ReforgeCharacter(ownerId, "Fictional acquiring owner"), "acquiring-playable-create")
        val authority = GenesisAuthorityRepository(sourceDb)
        val created = authority.acceptActorAuthority(authority.reviewActorAuthority(ownerId, GenesisActorRole.PLAYER), "acquiring-managed-accept")
        val review = playable.reviewCampaignItem(campaign, created.actor.actorId, item.id)
        val accepted = playable.acceptCampaignItem(campaign, review, "managed-lens-acquisition")
        val managed = requireNotNull(readGenesisAuthorityItem(sourceDb, item.id))
        assertEquals("managed-lens-acquisition", managed.activationCommandId)
        assertEquals(review, reforgeJson.decodeFromString<GenesisInventoryReview>(requireNotNull(managed.sourceProofJson)))
        assertEquals("Proposed", review.source.item.evidenceStatus)
        assertTrue(review.definition.effects.isEmpty())
        val preview = target.previewRestore(source.exportWithProof().text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        target.restore(preview, "restore-managed-acquisition")
        assertEquals(managed, readGenesisAuthorityItem(targetDb, item.id))
        assertEquals(accepted, ReforgeRepository(targetDb).get(created.actor.actorId))
        assertEquals("Proposed", targetDb.ledgerDao().getItem(item.id)?.evidenceStatus)
        val corrupt = source.snapshot().let { complete -> complete.copy(authority = complete.authority.copy(items =
            complete.authority.items.map { it.copy(sourceProofJson = reforgeJson.encodeToString(review.copy(instance = review.instance.copy(attuned = true)))) })) }
        assertFalse(target.previewRestore(genesisAuthorityBackupJson(corrupt)).valid)
    }

    @Test fun successiveAcquisitionsPreserveEachExistingItemsOriginalAuthorityProofThroughRestore() = runBlocking {
        val campaign = CampaignLedgerRepository(sourceDb)
        val playable = ReforgeRepository(sourceDb)
        val authority = GenesisAuthorityRepository(sourceDb)
        val ownerId = "mixed-provenance-owner"
        campaign.records.saveProfile(CharacterProfile(id = ownerId, name = "Fictional provenance keeper"),
            source = sourceLabel, commandId = "mixed-source-profile")
        suspend fun assignedSource(id: String, name: String, evidence: String): LedgerItem {
            val item = campaign.create(LedgerDraft(name, quantity = 1, evidenceStatus = evidence,
                itemState = "Carried", source = sourceLabel, description = "Retain the exact original evidence for $name."),
                "$id-create", id)
            campaign.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = ownerId,
                source = sourceLabel, commandId = "$id-place"))
            return requireNotNull(sourceDb.ledgerDao().getItem(item.id))
        }
        // Legacy assignment happens before authority enrollment, through ordinary repositories.
        val initialSource = assignedSource("mixed-initial-item", "Initial fictional lens", "Confirmed")
        val firstSource = assignedSource("mixed-source-first", "Proposed fictional crystal", "Proposed")
        val secondSource = assignedSource("mixed-source-second", "Second fictional crystal", "Confirmed")
        playable.create(ReforgeCharacter(ownerId, "Fictional provenance keeper",
            itemDefinitions = listOf(ItemDefinition("mixed-initial-definition", initialSource.title, description = initialSource.description)),
            items = listOf(ItemInstance(initialSource.id, "mixed-initial-definition"))), "mixed-playable-create")
        val actorReview = authority.reviewActorAuthority(ownerId, GenesisActorRole.PLAYER)
        val activated = authority.acceptActorAuthority(actorReview, "mixed-actor-activation")
        val created = authority.createItem(ownerId, requireNotNull(activated.playable).revision,
            ItemDefinition("mixed-created-definition", "Created fictional compass"),
            ItemInstance("mixed-created-item", "mixed-created-definition"), "mixed-item-creation")
        val originalRows = sourceDb.genesisAuthorityDao().allItems().associateBy { it.itemId }
        assertEquals(setOf(initialSource.id, "mixed-created-item"), originalRows.keys)
        assertEquals("mixed-actor-activation", originalRows.getValue(initialSource.id).activationCommandId)
        assertEquals("mixed-item-creation", originalRows.getValue("mixed-created-item").activationCommandId)
        originalRows.values.forEach { assertNull(it.sourceProofJson) }
        val originalLedger = originalRows.keys.associateWith { requireNotNull(sourceDb.ledgerDao().getItem(it)) }
        val originalPlacements = originalRows.keys.associateWith { requireNotNull(sourceDb.partyDao().placement(it)) }
        val originalReceipts = originalRows.values.associate { row ->
            row.activationCommandId to requireNotNull(readGenesisAuthorityReceipt(sourceDb, row.activationCommandId))
        }
        suspend fun assertOriginalProofsUnchanged() {
            originalRows.forEach { (id, row) ->
                assertEquals(row, readGenesisAuthorityItem(sourceDb, id))
                assertEquals(originalLedger.getValue(id), sourceDb.ledgerDao().getItem(id))
                assertEquals(originalPlacements.getValue(id), sourceDb.partyDao().placement(id))
            }
            originalReceipts.forEach { (id, receipt) -> assertEquals(receipt, readGenesisAuthorityReceipt(sourceDb, id)) }
        }

        val firstReview = playable.reviewCampaignItem(campaign, ownerId, firstSource.id)
        assertEquals(created.revision, firstReview.expectedRevision)
        playable.acceptCampaignItem(campaign, firstReview, "mixed-first-acquisition")
        assertOriginalProofsUnchanged()
        val firstAuthority = requireNotNull(readGenesisAuthorityItem(sourceDb, firstSource.id))
        assertEquals("mixed-first-acquisition", firstAuthority.activationCommandId)
        assertEquals(reforgeJson.encodeToString(firstReview), firstAuthority.sourceProofJson)
        assertEquals("Proposed", firstReview.source.item.evidenceStatus)
        assertTrue(firstReview.definition.effects.isEmpty())

        val secondReview = playable.reviewCampaignItem(campaign, ownerId, secondSource.id)
        val finalPlayable = playable.acceptCampaignItem(campaign, secondReview, "mixed-second-acquisition")
        assertOriginalProofsUnchanged()
        assertEquals(firstAuthority, readGenesisAuthorityItem(sourceDb, firstSource.id))
        val secondAuthority = requireNotNull(readGenesisAuthorityItem(sourceDb, secondSource.id))
        assertEquals("mixed-second-acquisition", secondAuthority.activationCommandId)
        assertEquals(reforgeJson.encodeToString(secondReview), secondAuthority.sourceProofJson)
        assertNotEquals(firstAuthority.sourceProofJson, secondAuthority.sourceProofJson)

        val exported = source.exportWithProof()
        val expected = decodeGenesisAuthorityBackup(exported.text)
        val restoreReview = target.previewRestore(exported.text)
        assertTrue(restoreReview.conflicts.joinToString(), restoreReview.valid)
        target.restore(restoreReview, "mixed-provenance-restore")
        val restored = decodeGenesisAuthorityBackup(target.exportWithProof().text)
        assertEquals(expected.authority, restored.authority)
        assertEquals(expected.campaign.items, restored.campaign.items)
        assertEquals(expected.campaign.records.placements, restored.campaign.records.placements)
        assertEquals(expected.playable, restored.playable)
        assertEquals(finalPlayable, ReforgeRepository(targetDb).get(ownerId))
        assertEquals("Proposed", targetDb.ledgerDao().getItem(firstSource.id)?.evidenceStatus)
        assertEquals(firstAuthority, readGenesisAuthorityItem(targetDb, firstSource.id))
        assertEquals(secondAuthority, readGenesisAuthorityItem(targetDb, secondSource.id))
    }

    @Test fun reviewedLegacyReconciliationAndRenamedCompanionResourceKeepOriginalProofPortable() = runBlocking {
        val id = "legacy-reviewed-companion"
        val campaign = CampaignLedgerRepository(sourceDb)
        campaign.records.saveProfile(CharacterProfile(id = id, name = "Prior source owl", currentHp = 10, maximumHp = 20), source = sourceLabel, commandId = "old-owl-profile")
        campaign.records.saveCompanionState(CompanionState("Prior source owl", companionId = id), source = sourceLabel, commandId = "old-owl-state")
        campaign.records.saveCompanionResource(CompanionResource("Charge", id, current = 2, maximum = 4, id = "old-charge-identity"), source = sourceLabel, commandId = "old-owl-resource")
        val sourceItem = campaign.create(LedgerDraft("Prior lens", quantity = 1, itemState = "Carried", evidenceStatus = "Proposed", source = sourceLabel), "old-lens-create", "old-physical-lens")
        campaign.party.moveItem(ItemMoveCommand(sourceItem.id, sourceItem.revision, holderProfileId = id, source = sourceLabel, commandId = "old-lens-place"))
        val playable = ReforgeRepository(sourceDb)
        val original = playable.create(ReforgeCharacter(id, "Reviewed fictional owl", currentHp = 7, baseMaxHp = 14,
            itemDefinitions = listOf(ItemDefinition("reviewed-lens-definition", "Reviewed lens")),
            items = listOf(ItemInstance(sourceItem.id, "reviewed-lens-definition", quantity = 3)),
            resources = listOf(ResourcePool("owl-charge-pool", "Charge", maximum = 3, current = 1))), "old-owl-playable")
        val authority = GenesisAuthorityRepository(sourceDb)
        val review = authority.reviewActorAuthority(id, GenesisActorRole.COMPANION)
        assertTrue(review.differences.any { it.field == "Name" })
        assertTrue(review.supplementalSourceJson.contains("old-charge-identity"))
        val accepted = authority.acceptActorAuthority(review, "reviewed-owl-accept")
        val acceptedPlayable = requireNotNull(accepted.playable)
        assertEquals(original.origin, acceptedPlayable.origin)
        val next = playable.save(id, acceptedPlayable.revision, acceptedPlayable.character.copy(resources =
            acceptedPlayable.character.resources.map { it.copy(name = "Resonance") }), "Rename resource", "owl-resource-rename")
        val sourceResource = sourceDb.recordsDao().allCompanionResources().single { it.id == "old-charge-identity" }
        assertEquals("Resonance", sourceResource.name)
        assertEquals(1, sourceResource.current)
        val acceptedItem = requireNotNull(readGenesisAuthorityItem(sourceDb, sourceItem.id))
        assertNull(acceptedItem.sourceProofJson) // One original actor review is referenced, not copied into every item.
        assertEquals("reviewed-owl-accept", acceptedItem.activationCommandId)
        val sourceProof = requireNotNull(readGenesisAuthorityReceipt(sourceDb, acceptedItem.activationCommandId)).requestJson
        assertEquals(review, campaignJson.decodeFromString<GenesisActorAuthorityReview>(sourceProof))
        val preview = target.previewRestore(source.exportWithProof().text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        target.restore(preview, "restore-reviewed-owl")
        assertEquals(next, ReforgeRepository(targetDb).get(id))
        assertEquals(sourceResource, targetDb.recordsDao().allCompanionResources().single { it.id == "old-charge-identity" })
        assertEquals(acceptedItem, readGenesisAuthorityItem(targetDb, sourceItem.id))
        assertEquals(sourceProof, requireNotNull(readGenesisAuthorityReceipt(targetDb, acceptedItem.activationCommandId)).requestJson)
        assertEquals(readGenesisAuthorityReceipt(sourceDb, "reviewed-owl-accept"), readGenesisAuthorityReceipt(targetDb, "reviewed-owl-accept"))
    }

    @Test fun smallRowsDecodeExplicitSqlTextBytesAndPreserveNullsIntegersAndUnicode() = runBlocking {
        val campaign = GenesisCampaignRecord("small-campaign", "éclair 雪 🦉", "Quote \" slash \\ and NUL \u0000 remain exact", createdAt = 1, updatedAt = 2, commandId = "small-campaign-command")
        val actor = GenesisActorAuthority("small-actor", GenesisActorRole.NPC, campaign.id,
            activationCommandId = "small-actor-command", lastCommandId = "small-actor-command", createdAt = 3, updatedAt = 4)
        val receipt = GenesisAuthorityReceipt("small-receipt", "raw-reader-fixture", "a".repeat(64),
            "{\"text\":\"éclair 雪 🦉\"}", "{\"outcome\":null}", 5)
        sourceDb.genesisAuthorityDao().insert(campaign)
        sourceDb.genesisAuthorityDao().insert(actor)
        sourceDb.genesisAuthorityDao().insert(receipt)
        val read = genesisAuthorityBackupRows(sourceDb)
        assertEquals(campaign, read.campaigns.single())
        assertEquals(actor, read.actors.single())
        assertEquals(receipt, read.receipts.single())
        assertEquals(receipt, readGenesisAuthorityReceipt(sourceDb, receipt.commandId))
        assertEquals(sourceDb.genesisAuthorityDao().allActors(), read.actors)
    }

    @Test fun ordinaryGameplayAndSourceCommandsCannotReuseAnAuthorityIdentityAndPoisonBackup() = runBlocking {
        val authority = GenesisAuthorityRepository(sourceDb)
        val named = authority.createCampaign("Fictional command identity campaign", commandId = "reserved-campaign-authority")
        val actor = authority.createActor("Fictional command owner", GenesisActorRole.PLAYER, named.id,
            playable = ReforgeCharacter("command-owner", "Fictional command owner", currentHp = 8, baseMaxHp = 12),
            commandId = "reserved-actor-authority")
        val saved = requireNotNull(actor.playable)
        val playable = ReforgeRepository(sourceDb)
        val campaign = CampaignLedgerRepository(sourceDb)
        val before = source.snapshot()
        val backup = source.exportWithProof().text
        // Notes do not project a profile child; this must fail at command ownership itself.
        rejected { playable.save(saved.character.id, saved.revision, saved.character.copy(notes = "Uncommitted duplicate command"),
            "Change notes", "reserved-actor-authority") }
        assertEquals(before, source.snapshot())
        val roll = ReforgeRollRecord("reserved-actor-authority", saved.character.id, saved.revision, "Fictional roll", "1d20",
            listOf(ReforgeRollComponent("die", listOf(ReforgeRollDie(20, 11)))))
        rejected { playable.appendRoll(roll, "reserved-actor-authority") }
        assertEquals(before, source.snapshot())
        rejected { playable.appendRoll(roll, "distinct-roll-command") }
        assertEquals(before, source.snapshot())
        val profile = requireNotNull(sourceDb.recordsDao().characterProfile(saved.character.id))
        rejected { campaign.records.saveProfile(profile.copy(currentHp = 7), profile.revision, sourceLabel, "reserved-actor-authority") }
        assertEquals(before, source.snapshot())
        rejected { campaign.records.saveProject(InventionProject("Uncommitted project", id = "uncommitted-project"),
            source = sourceLabel, commandId = "reserved-campaign-authority") }
        assertEquals(before, source.snapshot())
        assertEquals(backup, source.exportWithProof().text)
        assertNull(sourceDb.recordsDao().project("uncommitted-project"))
        assertNull(sourceDb.reforgeDao().change("distinct-roll-command"))
        assertTrue(sourceDb.reforgeDao().allRolls().isEmpty())
    }

    @Test fun rawMultibyteInputUsesNativeByteCapAndRejectsMalformedUtf8Strings() {
        // Character length fits, but the actual native-file byte count does not.
        val multilingual = "雪".repeat(GENESIS_AUTHORITY_DOCUMENT_BYTES / 3 + 1)
        assertTrue(multilingual.length < GENESIS_AUTHORITY_DOCUMENT_BYTES)
        assertNotNull(runCatching { validateGenesisAuthorityDocument(multilingual) }.exceptionOrNull())
        assertNotNull(runCatching { validateGenesisAuthorityDocument("\uD800") }.exceptionOrNull())
    }
}
