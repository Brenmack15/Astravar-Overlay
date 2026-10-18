package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampaignRecordsTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var ledger: CampaignLedgerRepository
    private lateinit var repo: CampaignRecordsRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        ledger = CampaignLedgerRepository(db)
        repo = ledger.records
    }
    @After fun close() = db.close()

    private suspend fun fails(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Operation should reject without applying partial changes", failed)
    }

    @Test fun inventionStagesAndRequirementsPersistWithoutCreatingInventory() = runBlocking {
        val design = InventionProject("Synthetic wing", status = "Blueprint")
        repo.saveProject(design, source = "Player design")
        repo.saveStage(ProjectStage(design.id, "Cut plates"), source = "Planned stage")
        repo.saveRequirement(MaterialRequirement(design.id, "Uncounted plates"), source = "Unresolved requirement")
        assertEquals(design.id, repo.projects.first().single().id)
        assertNull(repo.requirementsFor(design.id).first().single().quantity)
        assertEquals("Planned", repo.stagesFor(design.id).first().single().status)
        assertTrue(ledger.items.first().isEmpty())
    }

    @Test fun originalReceiptSurvivesLaterEditsAndCommandCollisionsFail() = runBlocking {
        val entity = ConnectionEntity("Synthetic archivist")
        val receipt = repo.saveEntity(entity, source = "Introduced in test scene", commandId = "create-connection")
        repo.saveEntity(entity.copy(notes = "Later information"), 1, "Later scene", "edit-connection")
        assertEquals(receipt, repo.saveEntity(entity, source = "Introduced in test scene", commandId = "create-connection"))
        assertEquals(1, receipt.resultingRevision)
        assertEquals(2, repo.entities.first().single().revision)
        fails { repo.saveEntity(entity.copy(name = "Different entity"), source = "Introduced in test scene", commandId = "create-connection") }
        assertEquals(2, repo.audit.first().size)
    }

    @Test fun legacyInventoryRetryReturnsOriginalOutcomeWithoutRollingBackCurrentState() = runBlocking {
        val original = ledger.create(LedgerDraft("Synthetic lens"), "item-create")
        val updated = ledger.discover(original.id, 1, "Confirmed", "Synthetic observation", "Test source")
        assertEquals(original, ledger.create(LedgerDraft("Synthetic lens"), "item-create"))
        assertEquals(updated, ledger.items.first().single())
        assertEquals(2, repo.audit.first().size)
    }

    @Test fun staleUpdateKeepsConnectionFactsAndHistory() = runBlocking {
        val entity = ConnectionEntity("Synthetic guild", kind = "Organization")
        repo.saveEntity(entity, source = "Test source")
        repo.saveEntity(entity.copy(relationship = "Trade agreement"), 1, "Resolved agreement")
        fails { repo.saveEntity(entity.copy(notes = "Old editor"), 1, "Stale edit") }
        repo.saveFact(ConnectionFact(entity.id, "Delivery promised", category = "Promises", evidenceStatus = "Confirmed"), source = "Resolved scene")
        repo.saveMilestone(ConnectionMilestone(entity.id, "Alliance agreed", "Meaningful test agreement"), source = "Resolved scene")
        assertEquals("Trade agreement", repo.entities.first().single().relationship)
        assertEquals("Promises", repo.facts.first().single().category)
        assertEquals(1, repo.milestones.first().size)
        assertEquals(4, repo.audit.first().size)
    }

    @Test fun companionStartsUnknownAndLinksReferenceOnePhysicalItem() = runBlocking {
        assertTrue(repo.companionStates.first().isEmpty())
        assertTrue(repo.companionResources.first().isEmpty())
        repo.saveCompanionState(CompanionState(), source = "Player opened companion record")
        repo.saveCompanionResource(CompanionResource("HP"), source = "Current value not yet confirmed")
        assertEquals("Unknown", repo.companionStates.first().single().condition)
        assertNull(repo.companionResources.first().single().current)
        assertNull(repo.companionResources.first().single().maximum)
        val item = ledger.create(LedgerDraft("Synthetic companion lens", quantity = 1))
        repo.saveLink(CampaignLink("item", item.id, "companion", "eek", "Equipped reference"), source = "Player-confirmed link")
        repo.saveItemDetails(ItemDetails(item.id, 250), source = "Measured weight")
        repo.saveItemProperty(ItemProperty(item.id, "Proposed use", "Test shielding", "Proposed"), source = "Player theory")
        assertEquals(1, ledger.items.first().size)
        assertEquals(item.id, repo.links.first().single().fromId)
        assertEquals(250L, repo.itemDetails.first().single().weightGrams)
        assertEquals("Proposed", repo.itemProperties.first().single().evidenceStatus)
    }

    @Test fun resourceValidationDoesNotInferRefills() = runBlocking {
        repo.saveCompanionState(CompanionState(), source = "Player entry")
        val resource = CompanionResource("Repair resource", current = 0, maximum = 2)
        repo.saveCompanionResource(resource, source = "Player-confirmed current resources")
        fails { repo.saveCompanionResource(resource.copy(current = 3), 1, "Invalid state") }
        assertEquals(0, repo.companionResources.first().single().current)
        repo.saveCompanionState(CompanionState(condition = "Resting"), 1, "Player recorded rest status only")
        assertEquals(0, repo.companionResources.first().single().current)
    }

    @Test fun invalidLinkAndCrossDomainCommandCollisionDoNotMutate() = runBlocking {
        val item = ledger.create(LedgerDraft("Synthetic item"), "one-global-command")
        fails { repo.saveProject(InventionProject("Blueprint"), source = "Player entry", commandId = "one-global-command") }
        fails { repo.saveLink(CampaignLink("item", item.id, "project", "missing", "Related"), source = "Invalid target") }
        assertTrue(repo.projects.first().isEmpty())
        assertTrue(repo.links.first().isEmpty())
    }

    @Test fun confirmedCraftConsumesExactQuantitiesAndCreatesOneOutputOnRetry() = runBlocking {
        val material = ledger.create(LedgerDraft("Synthetic plates", quantity = 4, itemState = "Carried"))
        val project = InventionProject("Synthetic prototype", status = "Prototype")
        repo.saveProject(project, source = "Test design")
        val cmd = CraftCommand(project.id, 1, listOf(MaterialConsumption(material.id, 1, 3)),
            LedgerDraft("Synthetic completed device", quantity = 1, source = "Confirmed test construction"), "Confirmed test construction", "craft-once")
        val receipt = repo.confirmCraft(cmd)
        assertEquals(receipt, repo.confirmCraft(cmd))
        assertEquals(2, ledger.items.first().size)
        assertEquals(1, db.ledgerDao().getItem(material.id)?.quantity)
        assertEquals(2L, db.ledgerDao().getItem(material.id)?.revision)
        assertNotNull(db.ledgerDao().getItem(requireNotNull(receipt.outputId)))
        assertEquals("Completed", repo.projects.first().single().status)
        assertEquals(1, db.recordsDao().allCrafts().size)
        assertEquals(3, db.recordsDao().allConsumptions().single().quantity)
        fails { repo.confirmCraft(cmd.copy(output = LedgerDraft("Different output", quantity = 1))) }
        assertEquals(2, ledger.items.first().size)
    }

    @Test fun unknownOrStaleMaterialNeverCreatesOutput() = runBlocking {
        val unknown = ledger.create(LedgerDraft("Uncounted synthetic crystals"))
        val project = InventionProject("Synthetic design")
        repo.saveProject(project, source = "Test design")
        fails { repo.confirmCraft(CraftCommand(project.id, 1, listOf(MaterialConsumption(unknown.id, 1, 1)),
            LedgerDraft("Should not exist", quantity = 1), "Attempted test construction", "unknown-craft")) }
        assertEquals(1, ledger.items.first().size)
        assertEquals(1, repo.projects.first().single().revision)
        assertNull(repo.receipt("unknown-craft"))
    }

    @Test fun failureAfterOutputInsertRollsBackWholeCraftAndCanRetry() = runBlocking {
        val material = ledger.create(LedgerDraft("Synthetic material", quantity = 2))
        val project = InventionProject("Synthetic design")
        repo.saveProject(project, source = "Test design")
        val command = CraftCommand(project.id, 1, listOf(MaterialConsumption(material.id, 1, 2)),
            LedgerDraft("Synthetic output", quantity = 1), "Confirmed test result", "rollback-craft")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_craft_failure BEFORE INSERT ON craft_consumptions BEGIN SELECT RAISE(ABORT, 'Synthetic rollback failure'); END")
        fails { repo.confirmCraft(command) }
        assertEquals(1, ledger.items.first().size)
        assertEquals(2, db.ledgerDao().getItem(material.id)?.quantity)
        assertEquals(1, repo.projects.first().single().revision)
        assertTrue(db.recordsDao().allCrafts().isEmpty())
        assertNull(repo.receipt(command.commandId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_craft_failure")
        repo.confirmCraft(command)
        assertEquals(0, db.ledgerDao().getItem(material.id)?.quantity)
        assertEquals("Consumed", db.ledgerDao().getItem(material.id)?.itemState)
    }

    @Test fun fullBackupRoundTripsIdsImagesUnknownValuesHistoryAndReceipts() = runBlocking {
        val photo = "c3ludGhldGljLWltYWdl"
        val item = ledger.create(LedgerDraft("Synthetic pictured item", imageBase64 = photo), "synthetic-original-create")
        val project = InventionProject("Synthetic blueprint")
        repo.saveProject(project, source = "Test source", commandId = "synthetic-project")
        repo.saveRequirement(MaterialRequirement(project.id, "Synthetic pictured item", item.id), source = "Unconfirmed quantity")
        val entity = ConnectionEntity("Synthetic contact", aliases = "Test alias", imageBase64 = photo)
        repo.saveEntity(entity, source = "Test scene")
        repo.saveFact(ConnectionFact(entity.id, "Player-known only"), source = "Test scene")
        repo.saveLink(CampaignLink("item", item.id, "project", project.id, "Research sample"), source = "Player link")
        repo.saveCompanionState(CompanionState(), source = "Unknown current state")
        val exported = ledger.export()
        val other = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val target = CampaignLedgerRepository(other)
            val preview = target.previewImport(exported)
            assertTrue(preview.conflicts.toString(), preview.valid)
            assertTrue(target.items.first().isEmpty())
            val restored = target.restore(preview, "restore-backup")
            assertEquals(restored, target.restore(preview, "restore-backup"))
            assertEquals(item, target.items.first().single())
            assertNull(target.items.first().single().quantity)
            assertEquals(photo, target.records.entities.first().single().imageBase64)
            assertEquals(repo.projects.first(), target.records.projects.first())
            assertEquals(db.ledgerDao().allEvents(), other.ledgerDao().allEvents())
            assertEquals(repo.receipt("synthetic-project"), target.records.receipt("synthetic-project"))
            assertEquals(1, target.records.links.first().size)
        } finally { other.close() }
    }

    @Test fun legacySchemaOneImportPreservesImagesAndNulls() = runBlocking {
        val item = LedgerItem("legacy-item", "Synthetic legacy item", quantity = null, imageBase64 = "bGVnYWN5", createdAt = 10, updatedAt = 10)
        val event = LedgerEvent("legacy-command", item.id, "CREATE", "Player entry", "Legacy event", "Test", "legacy-fingerprint", 0, 1, 10,
            afterJson = campaignJson.encodeToString(item))
        val value = LedgerExport(schema = 1, exportedAt = 100, items = listOf(item), events = listOf(event))
        val preview = ledger.previewImport(campaignJson.encodeToString(value))
        assertTrue(preview.conflicts.toString(), preview.valid)
        ledger.restore(preview, "restore-legacy")
        assertEquals(item, ledger.items.first().single())
        assertEquals(event, db.ledgerDao().allEvents().single())
    }

    @Test fun restoreRejectsDifferentExistingIdsAndPreviewBecomesStale() = runBlocking {
        val item = ledger.create(LedgerDraft("Synthetic original"))
        val backup = ledger.export()
        val preview = ledger.previewImport(backup)
        assertTrue(preview.valid)
        ledger.discover(item.id, 1, "Possible use", "Synthetic theory", "Test observation")
        fails { ledger.restore(preview, "stale-restore") }
        val conflicting = ledger.previewImport(backup)
        assertFalse(conflicting.valid)
        assertTrue(conflicting.conflicts.any { it.contains("differs from local") })
        assertTrue(ledger.items.first().single().possibleUses.contains("Synthetic theory"))
    }

    @Test fun malformedPartialAndForeignTargetBackupsCannotMutate() = runBlocking {
        assertFalse(ledger.previewImport("{broken").valid)
        val invalid = LedgerExport(exportedAt = 1, items = emptyList(), events = emptyList(),
            records = CampaignRecordsExport(stages = listOf(ProjectStage("missing-project", "Invalid stage"))))
        val preview = ledger.previewImport(campaignJson.encodeToString(invalid))
        assertFalse(preview.valid)
        fails { ledger.restore(preview, "invalid-restore") }
        assertTrue(repo.projects.first().isEmpty())
        assertTrue(repo.audit.first().isEmpty())
    }

    @Test fun restoreFailureRollsBackEarlierInsertedTables() = runBlocking {
        val item = LedgerItem("synthetic-restore-item", "Synthetic restored item", createdAt = 1, updatedAt = 1)
        val entity = ConnectionEntity("Synthetic restored contact")
        val backup = LedgerExport(exportedAt = 1, items = listOf(item), events = emptyList(),
            records = CampaignRecordsExport(entities = listOf(entity), facts = listOf(ConnectionFact(entity.id, "Synthetic fact"))))
        val preview = ledger.previewImport(campaignJson.encodeToString(backup))
        assertTrue(preview.valid)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_restore_failure BEFORE INSERT ON connection_facts BEGIN SELECT RAISE(ABORT, 'Synthetic restore failure'); END")
        fails { ledger.restore(preview, "failing-restore") }
        assertTrue(ledger.items.first().isEmpty())
        assertTrue(repo.entities.first().isEmpty())
        assertTrue(repo.audit.first().isEmpty())
        assertNull(repo.receipt("failing-restore"))
    }

    @Test fun importRejectsMalformedSnapshotsAndUnlinkedReceipts() = runBlocking {
        val item = LedgerItem("synthetic-item", "Synthetic item", createdAt = 1, updatedAt = 1)
        val event = LedgerEvent("synthetic-command", item.id, "CREATE", "Player entry", "Created", "Test", "fp", 0, 1, 1,
            afterJson = "{broken")
        val malformed = LedgerExport(schema = 1, exportedAt = 1, items = listOf(item), events = listOf(event))
        val invalidEvent = ledger.previewImport(campaignJson.encodeToString(malformed))
        assertFalse(invalidEvent.valid)
        assertTrue(invalidEvent.conflicts.any { it.contains("snapshot") })
        val wrongIdentity = event.copy(afterJson = campaignJson.encodeToString(item.copy(id = "different-item")))
        assertFalse(ledger.previewImport(campaignJson.encodeToString(malformed.copy(events = listOf(wrongIdentity)))).valid)
        val receipt = CommandReceipt("orphan-receipt", "a".repeat(64), "project", "missing", 1, 1, outcomeJson = "false outcome")
        val orphan = LedgerExport(exportedAt = 1, items = emptyList(), events = emptyList(), records = CampaignRecordsExport(receipts = listOf(receipt)))
        assertFalse(ledger.previewImport(campaignJson.encodeToString(orphan)).valid)
        assertTrue(ledger.items.first().isEmpty())
        assertTrue(db.recordsDao().allReceipts().isEmpty())
    }

    @Test fun newCraftCannotReuseAChildCreateFromAnotherOperation() = runBlocking {
        val material = ledger.create(LedgerDraft("Synthetic material", quantity = 4))
        val project = InventionProject("Synthetic design")
        repo.saveProject(project, source = "Test design")
        val output = LedgerDraft("Preexisting synthetic output", quantity = 1)
        val craftId = "synthetic-child-collision"
        val existing = ledger.create(output, "craft-output-${campaignHash(craftId).take(40)}")
        fails { repo.confirmCraft(CraftCommand(project.id, 1, listOf(MaterialConsumption(material.id, 1, 2)), output, "Confirmed test result", craftId)) }
        assertEquals(4, db.ledgerDao().getItem(material.id)?.quantity)
        assertEquals(existing, db.ledgerDao().getItem(existing.id))
        assertTrue(db.recordsDao().allCrafts().isEmpty())
        assertNull(repo.receipt(craftId))
    }

    @Test fun unsupportedLargeBackupIsNotReportedAsSuccessfulExport() {
        val unsupported = "x".repeat(CAMPAIGN_BACKUP_MAX_CHARACTERS + 1)
        var rejected = false
        try { supportedCampaignBackup(unsupported) } catch (e: IllegalArgumentException) {
            rejected = true
            assertTrue(e.message.orEmpty().contains("No complete backup was produced"))
        }
        assertTrue(rejected)
    }

    @Test fun manualCharacterProfileStartsEmptyAndAbilityDoesNotInferUnknownScore() = runBlocking {
        assertTrue(repo.characterProfiles.first().isEmpty())
        assertTrue(repo.characterAbilities.first().isEmpty())
        repo.saveProfile(CharacterProfile(), source = "Player chose an unconfirmed display profile")
        repo.saveAbility(CharacterAbility(key = "INT", magicModifier = 2, notes = "Descriptive configured magic only"), source = "Player entry")
        val profile = repo.characterProfiles.first().single()
        assertNull(profile.currentHp); assertNull(profile.maximumHp); assertNull(profile.temporaryHp)
        assertNull(profile.armorClass); assertNull(profile.level)
        val ability = repo.characterAbilities.first().single()
        assertNull(ability.score); assertNull(ability.baseModifier); assertNull(ability.totalModifier)
        assertEquals(2, ability.magicModifier)
        assertTrue(ledger.items.first().isEmpty())
    }

    @Test fun abilityDisplayUsesFloorForOddLowScoresAndSeparateMagic() = runBlocking {
        repo.saveProfile(CharacterProfile(name = "Synthetic character"), source = "Synthetic fixture")
        val ability = CharacterAbility(key = "STR", score = 9, magicModifier = 2, notes = "Synthetic additive display modifier")
        val original = repo.saveAbility(ability, source = "Confirmed synthetic score", commandId = "ability-create")
        assertEquals(-1, repo.characterAbilities.first().single().baseModifier)
        assertEquals(1, repo.characterAbilities.first().single().totalModifier)
        assertEquals(9, repo.characterAbilities.first().single().score)
        repo.saveAbility(ability.copy(score = 11), 1, "Later confirmed score")
        assertEquals(original, repo.saveAbility(ability, source = "Confirmed synthetic score", commandId = "ability-create"))
        fails { repo.saveAbility(ability.copy(score = 15), 1, "Stale score editor") }
        assertEquals(11, repo.characterAbilities.first().single().score)
        assertEquals(2, repo.characterAbilities.first().single().revision)
    }

    @Test fun manualCharacterAndAbilitiesRoundTripWithoutRefillingHp() = runBlocking {
        repo.saveProfile(CharacterProfile(name = "Synthetic character", currentHp = 3, maximumHp = 20, temporaryHp = null, armorClass = 14, level = 2), source = "Player-confirmed synthetic state")
        listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").forEach { key ->
            repo.saveAbility(CharacterAbility(key, score = if (key == "INT") 16 else null), source = "Explicit synthetic score or unknown")
        }
        val backup = ledger.export()
        val targetDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val target = CampaignLedgerRepository(targetDb)
            val preview = target.previewImport(backup)
            assertTrue(preview.conflicts.toString(), preview.valid)
            target.restore(preview, "restore-character-display")
            assertEquals(repo.characterProfiles.first(), target.records.characterProfiles.first())
            assertEquals(repo.characterAbilities.first(), target.records.characterAbilities.first())
            assertEquals(3, target.records.characterProfiles.first().single().currentHp)
            assertNull(target.records.characterProfiles.first().single().temporaryHp)
            assertEquals(5, target.records.characterAbilities.first().count { it.score == null })
        } finally { targetDb.close() }
    }

    @Test fun invalidAbilityOrMissingProfileDoesNotLeaveAuditOrReceipt() = runBlocking {
        fails { repo.saveAbility(CharacterAbility(key = "INT", score = 16), source = "No display profile", commandId = "missing-profile") }
        assertTrue(repo.characterAbilities.first().isEmpty())
        assertNull(repo.receipt("missing-profile"))
        assertTrue(repo.audit.first().isEmpty())
        repo.saveProfile(CharacterProfile(), source = "Explicit profile")
        fails { repo.saveAbility(CharacterAbility(key = "LUCK", score = 16), source = "Unapproved key") }
        assertTrue(repo.characterAbilities.first().isEmpty())
        assertEquals(1, repo.audit.first().size)
    }
}
