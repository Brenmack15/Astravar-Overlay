package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import com.yazsras.astravar.ui.CampaignDocumentIoV21
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisInventoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var campaignDb: CampaignLedgerDb
    private lateinit var playableDb: ReforgeDb
    private lateinit var campaign: CampaignLedgerRepository
    private lateinit var playable: ReforgeRepository
    private val source = "Fictional Genesis fixture"

    @Before fun open() {
        campaignDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        playableDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        campaign = CampaignLedgerRepository(campaignDb)
        playable = ReforgeRepository(playableDb)
    }
    @After fun close() { campaignDb.close(); playableDb.close() }

    private suspend fun actor(id: String = "fixture-player"): ReforgeSavedCharacter {
        campaign.records.saveProfile(CharacterProfile(id = id, name = id, currentHp = 12, maximumHp = 20), source = source)
        return playable.adopt(campaign, playable.readLegacyPreview(campaign, id))
    }
    private suspend fun assignedItem(holder: String, id: String = "fixture-item", state: String = "Carried", sourceImage: String? = null): LedgerItem {
        val created = campaign.create(LedgerDraft("Fictional compass", quantity = 2, itemState = "Carried",
            evidenceStatus = "Proposed", description = "No mechanical effect is implied.", source = source, imageBase64 = sourceImage), recordId = id)
        campaign.party.moveItem(ItemMoveCommand(id, created.revision, holderProfileId = holder, state = state, source = source))
        return requireNotNull(campaignDb.ledgerDao().getItem(id))
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertNotNull("Operation must reject rather than partially commit", error)
    }
    private fun gear(actorId: String) = ReforgeCharacter(actorId, actorId,
        itemDefinitions = listOf(ItemDefinition("template", "Fictional gear")),
        items = listOf(ItemInstance("one-physical-item", "template")))

    @Test fun reviewedAcquisitionRetainsSourceProofWithoutChangingCampaignAndSurvivesBackup() = runBlocking {
        val original = actor()
        val item = assignedItem(original.character.id, state = "Equipped")
        val sourceBefore = campaignDecodeBackup(campaign.export()).copy(exportedAt = 0)
        val review = playable.reviewCampaignItem(campaign, original.character.id, item.id)
        assertTrue(playable.get(original.character.id)!!.character.items.isEmpty())
        assertEquals("Proposed", review.source.item.evidenceStatus)
        assertTrue(review.definition.effects.isEmpty())
        assertFalse(review.instance.attuned)
        assertEquals(ItemState.WORN, review.instance.state)
        val accepted = playable.acceptCampaignItem(campaign, review, "accepted-item")
        assertEquals(2, accepted.character.items.single().quantity)
        assertEquals(original.origin, accepted.origin)
        assertEquals(sourceBefore, campaignDecodeBackup(campaign.export()).copy(exportedAt = 0))
        val receipt = playable.commandReceipt("accepted-item")!!
        assertEquals("INVENTORY", receipt.outcomeType)
        assertEquals(review, reforgeJson.decodeFromString<GenesisInventoryOutcome>(receipt.afterJson).review)
        val backup = playable.export()
        assertEquals(2, decodeReforgeBackup(backup).schema)
        val otherDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        try {
            val restored = ReforgeRepository(otherDb)
            val preview = restored.previewRestore(backup)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            restored.restore(preview, "restore-acquired")
            assertEquals(accepted, restored.get(original.character.id))
            assertEquals(receipt, restored.commandReceipt("accepted-item"))
            val entry = restored.observeInventory(campaign).first().single()
            assertEquals(GenesisInventoryAuthority.PLAYABLE, entry.authority)
            assertEquals(2, entry.currentQuantity)
            assertEquals(item, entry.sourceItem)
            assertTrue(entry.reviewIssues.any { "proposed" in it })
        } finally { otherDb.close() }
    }

    @Test fun acquisitionRequiresExactHolderAndNeverGuessesUnassignedOwnership() = runBlocking {
        val first = actor("first")
        actor("second")
        val elsewhere = assignedItem("second")
        rejects { playable.reviewCampaignItem(campaign, first.character.id, elsewhere.id) }
        val unassigned = campaign.create(LedgerDraft("Unassigned fixture", quantity = 1, itemState = "Carried"))
        rejects { playable.reviewCampaignItem(campaign, first.character.id, unassigned.id) }
        assertTrue(playable.get("first")!!.character.items.isEmpty())
        assertTrue(playable.get("second")!!.character.items.isEmpty())
    }

    @Test fun sourceQuantityOrPlacementChangeRejectsWithoutLocalReceipt() = runBlocking {
        val original = actor()
        actor("other-holder")
        val item = assignedItem(original.character.id)
        val review = playable.reviewCampaignItem(campaign, original.character.id, item.id)
        campaign.party.changeQuantity(QuantityCommand(item.id, item.revision, 1, source))
        rejects { playable.acceptCampaignItem(campaign, review, "stale-source") }
        assertNull(playable.commandReceipt("stale-source"))
        assertEquals(original, playable.get(original.character.id))
        val current = campaignDb.ledgerDao().getItem(item.id)!!
        val freshReview = playable.reviewCampaignItem(campaign, original.character.id, item.id)
        val placement = campaignDb.partyDao().placement(item.id)!!
        campaign.party.moveItem(ItemMoveCommand(item.id, current.revision, placement.revision, "other-holder", source = source))
        rejects { playable.acceptCampaignItem(campaign, freshReview, "stale-holder") }
        assertNull(playable.commandReceipt("stale-holder"))
        assertEquals(original, playable.get(original.character.id))
    }

    @Test fun targetChangeAndForgedEffectsRejectWithoutPartialInventory() = runBlocking {
        val original = actor()
        val item = assignedItem(original.character.id)
        val review = playable.reviewCampaignItem(campaign, original.character.id, item.id)
        val forged = review.copy(instance = review.instance.copy(attuned = true))
        rejects { playable.acceptCampaignItem(campaign, forged, "forged-activation") }
        assertEquals(original, playable.get(original.character.id))
        val newer = playable.save(original.character.id, original.revision, original.character.copy(currentHp = 11), "Fictional injury")
        rejects { playable.acceptCampaignItem(campaign, review, "stale-target") }
        assertEquals(newer, playable.get(original.character.id))
        assertNull(playable.commandReceipt("stale-target"))
        assertNull(playable.commandReceipt("forged-activation"))
    }

    @Test fun identicalAcquisitionRetryReturnsOriginalEvenAfterBothDomainsAdvance() = runBlocking {
        val original = actor()
        val item = assignedItem(original.character.id)
        val review = playable.reviewCampaignItem(campaign, original.character.id, item.id)
        val accepted = playable.acceptCampaignItem(campaign, review, "retry-once")
        val newer = playable.save(accepted.character.id, accepted.revision, accepted.character.copy(name = "Later name"), "Rename")
        campaign.party.changeQuantity(QuantityCommand(item.id, item.revision, 1, source))
        assertEquals(accepted, playable.acceptCampaignItem(campaign, review, "retry-once"))
        assertEquals(newer, playable.get(original.character.id))
        rejects { playable.acceptCampaignItem(campaign, review.copy(expectedRevision = accepted.revision), "retry-once") }
        assertEquals(3, decodeReforgeBackup(playable.export()).changes.size)
    }

    @Test fun repositoryRejectsDuplicatePhysicalIdsIncludingArchivedOwnersAndActionCommits() = runBlocking {
        val owner = playable.create(gear("owner"))
        playable.setArchived(owner.character, true)
        rejects { playable.create(gear("duplicate")) }
        val empty = playable.create(ReforgeCharacter("recipient", "Recipient"))
        val incoming = gear("recipient").copy(revision = empty.revision)
        rejects { playable.save(empty.character.id, empty.revision, incoming, "Duplicate gear", "duplicate-save") }
        val roll = ReforgeRollRecord("duplicate-roll", empty.character.id, empty.revision, "Fictional action", "1d4",
            listOf(ReforgeRollComponent("result", listOf(ReforgeRollDie(4, 2)))))
        rejects { playable.commitRoll(empty.character.id, empty.revision, incoming, "Duplicate during action", roll) }
        assertNull(playable.commandReceipt("duplicate-save"))
        assertNull(playable.commandReceipt("duplicate-roll"))
        assertTrue(playable.rollHistory(empty.character.id).first().isEmpty())
        assertEquals(empty, playable.get(empty.character.id))
    }

    @Test fun concurrentClaimsHaveExactlyOnePhysicalOwner() = runBlocking {
        val a = playable.create(ReforgeCharacter("claim-a", "A"))
        val b = playable.create(ReforgeCharacter("claim-b", "B"))
        val outcomes = listOf(a, b).map { saved -> async {
            runCatching { playable.save(saved.character.id, saved.revision,
                gear(saved.character.id).copy(revision = saved.revision), "Claim item") }
        } }.awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, playable.observeAll().first().sumOf { it.character.items.size })
        assertEquals(3, decodeReforgeBackup(playable.export()).changes.size)
    }

    @Test fun historicDuplicatesRemainExplicitAndUnrelatedEditsWorkButNewMergeCannotCreateThem() = runBlocking {
        val first = playable.create(gear("historical-a"))
        val otherDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        try {
            val other = ReforgeRepository(otherDb)
            other.create(gear("historical-b"))
            val otherBackup = other.export()
            val preview = playable.previewRestore(otherBackup)
            assertFalse(preview.valid)
            assertTrue(preview.conflicts.any { "Physical item" in it })
            // Simulate a code49 database that already contains independently created copies.
            otherDb.reforgeDao().allCharacters().forEach { playableDb.reforgeDao().insert(it) }
            otherDb.reforgeDao().allChanges().forEach { playableDb.reforgeDao().insert(it) }
            val updated = playable.save(first.character.id, first.revision,
                first.character.copy(notes = "Unrelated note remains editable"), "Edit notes")
            val entry = playable.observeInventory(campaign).first().single()
            assertEquals(GenesisInventoryAuthority.REVIEW_REQUIRED, entry.authority)
            assertEquals(setOf("historical-a", "historical-b"), entry.playableOwners.map { it.profileId }.toSet())
            assertNull(entry.currentQuantity)
            rejects { playable.save(updated.character.id, updated.revision, updated.character.copy(
                items = updated.character.items.map { it.copy(quantity = 9) }), "Edit ambiguous stock") }
            assertEquals(updated, playable.get(first.character.id))
            assertEquals(2, decodeReforgeBackup(playable.export()).characters.size)
        } finally { otherDb.close() }
    }

    @Test fun projectionUsesUniquePlayableCurrentValuesAndRetainsDifferentSourceValues() = runBlocking {
        val original = actor()
        val item = assignedItem(original.character.id)
        val accepted = playable.acceptCampaignItem(campaign,
            playable.reviewCampaignItem(campaign, original.character.id, item.id), "projection-acquire")
        playable.save(accepted.character.id, accepted.revision, accepted.character.copy(
            items = accepted.character.items.map { it.copy(quantity = 1) }), "Use fictional item")
        val entry = playable.observeInventory(campaign).first().single()
        assertEquals(GenesisInventoryAuthority.PLAYABLE, entry.authority)
        assertEquals(1, entry.currentQuantity)
        assertEquals(2, entry.sourceItem!!.quantity)
        assertTrue(entry.reviewIssues.any { "quantity differs" in it })
    }

    @Test fun archivedSourceRemainsVisibleWhenItsPlayableIdentityStillExists() = runBlocking {
        val original = actor()
        val item = assignedItem(original.character.id)
        playable.acceptCampaignItem(campaign, playable.reviewCampaignItem(campaign, original.character.id, item.id), "archive-source-acquire")
        campaign.update(item.id, item.revision, LedgerDraft(item.title, item.category, item.owner, item.itemState,
            item.location, item.evidenceStatus, item.quantity, item.description, item.knownProperties, item.possibleUses,
            item.limitations, item.source, item.notionPageId, item.imageBase64, archived = true), source)
        assertTrue(campaign.items.first().isEmpty())
        val entry = playable.observeInventory(campaign).first().single()
        assertTrue(entry.sourceItem!!.archived)
        assertEquals(GenesisInventoryAuthority.REVIEW_REQUIRED, entry.authority)
        assertNull(entry.currentQuantity)
        assertTrue(entry.reviewIssues.any { "unavailable" in it })
    }

    @Test fun oldBackupSchemaStillLoadsAndTamperedInventoryProofRejects() = runBlocking {
        val original = actor()
        val old = decodeReforgeBackup(playable.export()).copy(schema = 1)
        assertEquals(1, decodeReforgeBackup(reforgeJson.encodeToString(old)).schema)
        val item = assignedItem(original.character.id)
        playable.acceptCampaignItem(campaign, playable.reviewCampaignItem(campaign, original.character.id, item.id), "proof-receipt")
        val backup = decodeReforgeBackup(playable.export())
        val receipt = backup.changes.single { it.outcomeType == "INVENTORY" }
        val outcome = reforgeJson.decodeFromString<GenesisInventoryOutcome>(receipt.afterJson)
        val tampered = outcome.copy(review = outcome.review.copy(sourceFingerprint = "0".repeat(64)))
        val broken = backup.copy(changes = backup.changes.map { if (it.commandId == receipt.commandId)
            it.copy(afterJson = reforgeJson.encodeToString(tampered)) else it })
        rejects { decodeReforgeBackup(reforgeJson.encodeToString(broken)) }
        rejects { decodeReforgeBackup(reforgeJson.encodeToString(backup.copy(schema = 1))) }
    }

    @Test fun failedReceiptInsertRollsBackCharacterAndSameRequestCanRetry() = runBlocking {
        val original = actor()
        val item = assignedItem(original.character.id)
        val review = playable.reviewCampaignItem(campaign, original.character.id, item.id)
        playableDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_genesis_receipt BEFORE INSERT ON reforge_changes
            WHEN NEW.outcomeType='INVENTORY' BEGIN SELECT RAISE(ABORT, 'fictional disk failure'); END""")
        rejects { playable.acceptCampaignItem(campaign, review, "atomic-acquisition") }
        assertEquals(original, playable.get(original.character.id))
        assertNull(playable.commandReceipt("atomic-acquisition"))
        playableDb.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_genesis_receipt")
        val accepted = playable.acceptCampaignItem(campaign, review, "atomic-acquisition")
        assertEquals(item.id, accepted.character.items.single().id)
        assertEquals(2, decodeReforgeBackup(playable.export()).changes.size)
    }

    @Test fun acceptedItemAndImmutableSourceProofSurviveDatabaseCloseAndReopen() = runBlocking {
        val dbName = "genesis-inventory-reopen-fixture.db"
        context.deleteDatabase(dbName)
        fun disk() = Room.databaseBuilder(context, ReforgeDb::class.java, dbName).build()
        var db = disk()
        try {
            var repo = ReforgeRepository(db)
            val created = repo.create(ReforgeCharacter("disk-actor", "Fictional disk actor"))
            repo.createReviewedCampaignIdentity(campaign, created)
            val item = assignedItem(created.character.id)
            val review = repo.reviewCampaignItem(campaign, created.character.id, item.id)
            val saved = repo.acceptCampaignItem(campaign, review, "disk-acquisition")
            val receipt = repo.commandReceipt("disk-acquisition")
            db.close()
            db = disk(); repo = ReforgeRepository(db)
            assertEquals(saved, repo.get(created.character.id))
            assertEquals(receipt, repo.commandReceipt("disk-acquisition"))
            assertEquals(saved, repo.acceptCampaignItem(campaign, review, "disk-acquisition"))
            assertEquals(item.id, repo.observeInventory(campaign).first().single().itemId)
            assertEquals(2, decodeReforgeBackup(repo.export()).changes.size)
        } finally { db.close(); context.deleteDatabase(dbName) }
    }

    @Test fun acquisitionThatWouldExceedCompleteBackupLimitRollsBackAndKeepsPriorBackupUsable() = runBlocking {
        var current = actor()
        // A valid fictional history near the existing complete-backup limit. Repeated
        // explicit notes edits preserve real before/after chains rather than fake rows.
        val note = "x".repeat(99_900)
        repeat(32) { index ->
            current = playable.save(current.character.id, current.revision,
                current.character.copy(notes = "$index:$note"), "Fictional journal revision $index")
        }
        var protected = playable.exportWithProof()
        while (protected.text.length < 7_100_000) {
            current = playable.save(current.character.id, current.revision,
                current.character.copy(notes = "${current.revision}:$note"), "Fictional journal revision ${current.revision}")
            protected = playable.exportWithProof()
        }
        assertTrue(protected.text.length < REFORGE_MAX_JSON)
        val protectedBackup = decodeReforgeBackup(protected.text)
        // Existing source contracts permit up to one million base64 characters.
        // This synthetic padding represents the bulky source-image portion of proof.
        val item = assignedItem(current.character.id, sourceImage = "A".repeat(900_000))
        val review = playable.reviewCampaignItem(campaign, current.character.id, item.id)
        val sourceBefore = campaignDb.ledgerDao().getItem(item.id)
        val error = runCatching { playable.acceptCampaignItem(campaign, review, "too-large-acquisition") }.exceptionOrNull()
        assertNotNull("Complete recovery size must block this new acquisition", error)
        assertTrue(error!!.message.orEmpty().contains("complete character backup"))
        assertEquals(current, playable.get(current.character.id))
        assertNull(playable.commandReceipt("too-large-acquisition"))
        assertEquals(sourceBefore, campaignDb.ledgerDao().getItem(item.id))
        val after = playable.exportWithProof()
        assertEquals(protected.currentFingerprint, after.currentFingerprint)
        assertEquals(protectedBackup.copy(exportedAt = 0), decodeReforgeBackup(after.text).copy(exportedAt = 0))
        val recoveryDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        try {
            val recovery = ReforgeRepository(recoveryDb)
            val preview = recovery.previewRestore(protected.text)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "restore-protected-backup")
            assertEquals(current, recovery.get(current.character.id))
        } finally { recoveryDb.close() }
    }

    @Test fun multilingualAcquisitionCannotCrossNativeUtf8BackupLimitEvenBelowCharacterLimit() = runBlocking {
        var current = actor()
        val note = "龍".repeat(99_900)
        repeat(10) { index ->
            current = playable.save(current.character.id, current.revision,
                current.character.copy(notes = "$index:$note"), "Fictional multilingual journal $index")
        }
        var protected = playable.exportWithProof()
        while (CampaignDocumentIoV21.utf8Size(protected.text) < 7_200_000) {
            current = playable.save(current.character.id, current.revision,
                current.character.copy(notes = "${current.revision}:$note"), "Fictional multilingual journal ${current.revision}")
            protected = playable.exportWithProof()
        }
        assertTrue(protected.text.length < 4_000_000)
        assertTrue(CampaignDocumentIoV21.utf8Size(protected.text) <= REFORGE_DOCUMENT_BYTES)
        val item = assignedItem(current.character.id, sourceImage = "A".repeat(900_000))
        val review = playable.reviewCampaignItem(campaign, current.character.id, item.id)
        val error = runCatching { playable.acceptCampaignItem(campaign, review, "unicode-document-too-large") }.exceptionOrNull()
        assertNotNull("UTF-8 bytes must prevent committing an unexportable recovery document", error)
        assertTrue(error!!.message.orEmpty().contains("8 MiB document limit"))
        assertEquals(current, playable.get(current.character.id))
        assertNull(playable.commandReceipt("unicode-document-too-large"))
        val after = playable.exportWithProof()
        assertEquals(protected.currentFingerprint, after.currentFingerprint)
        assertTrue(CampaignDocumentIoV21.utf8Size(after.text) <= REFORGE_DOCUMENT_BYTES)
        // The earlier backup remains accepted by the actual native document codec.
        CampaignDocumentIoV21.verifySavedUtf8(protected.text,
            protected.text.toByteArray(Charsets.UTF_8).inputStream(), REFORGE_DOCUMENT_BYTES)
    }
}
