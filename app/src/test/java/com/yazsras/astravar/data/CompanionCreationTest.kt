package com.yazsras.astravar.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.ui.partyRoster
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionCreationTest {
    @Test fun ownerLinkedCompanionBackupRestoresAndExactRetrySurvivesLaterEdits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val restoredDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        try {
            val repository = CampaignLedgerRepository(db)
            val records = repository.records
            records.saveProfile(CharacterProfile(id = "fixture-owner", name = "Fictional owner"), source = "Fictional owner")
            records.saveProfile(CharacterProfile(id = "other-owner", name = "Another owner"), source = "Fictional owner")
            val companionId = records.createCompanion("Glimmer", "fixture-owner", 1, "backed-up-companion")
            val owner = db.recordsDao().characterProfile("fixture-owner")!!
            records.saveProfile(owner.copy(name = "Owner renamed later"), owner.revision, "Later fictional correction")
            val companion = db.recordsDao().characterProfile(companionId)!!
            records.saveProfile(companion.copy(name = "Glimmer renamed later"), companion.revision, "Later fictional correction")
            val exported = repository.export()
            val restored = CampaignLedgerRepository(restoredDb)
            val preview = restored.previewImport(exported)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            restored.restore(preview, "restore-companion-fixture")
            assertEquals(db.recordsDao().allCharacterProfiles(), restoredDb.recordsDao().allCharacterProfiles())
            assertEquals(db.recordsDao().allCompanionStates(), restoredDb.recordsDao().allCompanionStates())
            assertEquals(db.recordsDao().allLinks(), restoredDb.recordsDao().allLinks())
            val beforeRetry = restored.export()
            val beforeAudit = restoredDb.recordsDao().allAudit()
            assertEquals(companionId, restored.records.createCompanion("Glimmer", "fixture-owner", 1, "backed-up-companion"))
            assertEquals(beforeAudit, restoredDb.recordsDao().allAudit())
            assertEquals("Glimmer renamed later", restoredDb.recordsDao().characterProfile(companionId)!!.name)
            try {
                restored.records.createCompanion("Glimmer", "other-owner", 1, "backed-up-companion")
                fail("A retry must not silently reassign a companion to another owner")
            } catch (_: IllegalArgumentException) { }
            assertEquals(beforeAudit, restoredDb.recordsDao().allAudit())
            assertEquals("fixture-owner", restoredDb.recordsDao().allLinks().single().toId)
            assertTrue(restored.previewImport(beforeRetry).valid)
        } finally { db.close(); restoredDb.close() }
    }

    @Test fun ownerlessCompanionBackupUsesOnlyExistingAuditedRecordTypes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val restoredDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        try {
            val repository = CampaignLedgerRepository(db)
            val id = repository.records.createCompanion("Lantern", commandId = "ownerless-companion")
            val receipt = repository.records.receipt("ownerless-companion")!!
            assertEquals("character-profile", receipt.kind)
            val audit = db.recordsDao().allAudit().single { it.commandId == receipt.commandId }
            assertEquals(receipt.outcomeJson, audit.afterJson)
            val restored = CampaignLedgerRepository(restoredDb)
            val preview = restored.previewImport(repository.export())
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            restored.restore(preview, "restore-ownerless-fixture")
            assertEquals(id, restored.records.createCompanion("Lantern", commandId = "ownerless-companion"))
            assertEquals(1, restoredDb.recordsDao().allCompanionStates().size)
            assertTrue(restoredDb.recordsDao().allLinks().isEmpty())
        } finally { db.close(); restoredDb.close() }
    }

    @Test fun failedOwnerLinkRollsBackProfileStateAndParentReceiptBeforeRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        try {
            val records = CampaignLedgerRepository(db).records
            records.saveProfile(CharacterProfile(id = "fixture-owner", name = "Fictional owner"), source = "Fixture")
            val priorAudit = db.recordsDao().allAudit()
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_companion_link BEFORE INSERT ON campaign_links
                WHEN NEW.label='Companion of' BEGIN SELECT RAISE(ABORT, 'fictional failure'); END""")
            assertNotNull(runCatching { records.createCompanion("Glimmer", "fixture-owner", 1, "retry-linked-companion") }.exceptionOrNull())
            assertEquals(priorAudit, db.recordsDao().allAudit())
            assertEquals(1, db.recordsDao().allCharacterProfiles().size)
            assertTrue(db.recordsDao().allCompanionStates().isEmpty())
            assertNull(records.receipt("retry-linked-companion"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_companion_link")
            val id = records.createCompanion("Glimmer", "fixture-owner", 1, "retry-linked-companion")
            assertEquals(id, records.createCompanion("Glimmer", "fixture-owner", 1, "retry-linked-companion"))
            assertEquals(1, db.recordsDao().allLinks().size)
        } finally { db.close() }
    }

    @Test fun ownerLinkedCreationIsAtomicRetryableAndRejectsStaleOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        try {
            val records = CampaignLedgerRepository(db).records
            records.saveProfile(CharacterProfile(id = "fixture-owner", name = "Fictional owner"), source = "Fictional Genesis fixture")
            val id = records.createCompanion("Glimmer", "fixture-owner", 1, "fixture-create-companion")
            assertEquals(id, records.createCompanion("Glimmer", "fixture-owner", 1, "fixture-create-companion"))
            assertEquals(1, db.recordsDao().allCompanionStates().size)
            val link = db.recordsDao().allLinks().single()
            assertEquals(id, link.fromId)
            assertEquals("companion", link.fromType)
            assertEquals("fixture-owner", link.toId)
            assertEquals("character-profile", link.toType)
            val before = db.recordsDao().allAudit()
            try {
                records.createCompanion("Different", "fixture-owner", 1, "fixture-create-companion")
                fail("Command reuse must not create another companion")
            } catch (_: IllegalArgumentException) { }
            try {
                records.createCompanion("Stale", "fixture-owner", 0, "fixture-stale-companion")
                fail("Stale owner must not create partial records")
            } catch (_: IllegalStateException) { }
            assertEquals(before, db.recordsDao().allAudit())
            assertEquals(2, db.recordsDao().allCharacterProfiles().size)
            assertEquals(1, db.recordsDao().allCompanionStates().size)
        } finally { db.close() }
    }

    @Test fun creatingCompanionCreatesConsistentRecordsAndAnAuditedIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        try {
            val repository = CampaignLedgerRepository(db)
            val id = repository.records.createCompanion("  Glimmer  ")
            val profiles = db.recordsDao().allCharacterProfiles()
            val states = db.recordsDao().allCompanionStates()
            assertTrue(id.startsWith("companion-"))
            assertEquals(id, profiles.single().id)
            assertEquals("Glimmer", profiles.single().name)
            assertNull(profiles.single().currentHp)
            assertNull(profiles.single().armorClass)
            assertEquals(id, states.single().companionId)
            assertEquals("Glimmer", states.single().name)
            assertEquals("Unknown", states.single().condition)
            assertEquals(2, db.recordsDao().allAudit().count { it.recordId == id })
            assertEquals("Glimmer", partyRoster(profiles, states).single { it.id == id }.label)
        } finally { db.close() }
    }

    @Test fun emptyCompanionNameCannotCreateHalfRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        try {
            try {
                CampaignLedgerRepository(db).records.createCompanion("  ")
                fail("Expected empty name rejection")
            } catch (_: IllegalArgumentException) { }
            assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
            assertTrue(db.recordsDao().allCompanionStates().isEmpty())
            assertTrue(db.recordsDao().allAudit().isEmpty())
        } finally { db.close() }
    }
}
