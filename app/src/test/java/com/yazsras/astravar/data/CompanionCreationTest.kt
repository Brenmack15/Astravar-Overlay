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
