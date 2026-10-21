package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampaignLedgerTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var repo: CampaignLedgerRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CampaignLedgerDb::class.java)
            .allowMainThreadQueries().build()
        repo = CampaignLedgerRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun createUsesStableIdOnRetry() = runBlocking {
        val commandId = "purchase-item-create-1"
        val draft = LedgerDraft(title = "Test prism", quantity = 1, owner = "Zerg")
        val a = repo.create(draft, commandId)
        val b = repo.create(draft, commandId)
        assertEquals(a.id, b.id)
        assertEquals(1, repo.items.first().size)
        assertEquals(1, repo.eventsForItem(a.id).first().size)
    }

    @Test fun duplicateCommandWithDifferentPayloadFailsWithoutMutation() = runBlocking {
        repo.create(LedgerDraft(title = "Test prism"), "duplicate-id")
        try {
            repo.create(LedgerDraft(title = "Different item"), "duplicate-id")
            fail("Same command id with new content should be rejected")
        } catch (_: IllegalArgumentException) {}
        assertEquals(1, repo.items.first().size)
    }

    @Test fun unknownQuantityIsNotZero() = runBlocking {
        val item = repo.create(LedgerDraft(title = "Uncounted crystals"))
        assertNull(item.quantity)
        assertTrue(repo.export().contains("\"quantity\":null"))
    }

    @Test fun confirmedDiscoveryIsAppendedAndAudited() = runBlocking {
        val first = repo.create(LedgerDraft(title = "Unknown lens", knownProperties = "Glows in the dark"))
        val result = repo.discover(first.id, first.revision, "Confirmed", "Reacts to Force", "Arcana check: 19", "discovery-1")
        assertEquals(2, result.revision)
        assertTrue(result.knownProperties.contains("Glows in the dark"))
        assertTrue(result.knownProperties.contains("Reacts to Force"))
        assertEquals(2, repo.eventsForItem(first.id).first().size)
        val reissued = repo.discover(first.id, first.revision, "Confirmed", "Reacts to Force", "Arcana check: 19", "discovery-1")
        assertEquals(result.revision, reissued.revision)
        assertEquals(2, repo.eventsForItem(first.id).first().size)
    }

    @Test fun hypothesesStaySeparateFromConfirmedProperties() = runBlocking {
        val first = repo.create(LedgerDraft(title = "Dragon scale"))
        val result = repo.discover(first.id, first.revision, "Possible use", "Maybe useful in shielding", "Zerg theory")
        assertTrue(result.knownProperties.isBlank())
        assertTrue(result.possibleUses.contains("shielding"))
        assertEquals("Possible use", repo.eventsForItem(first.id).first().first().evidenceStatus)
    }

    @Test fun staleEditFailsWithoutLosingExistingDiscovery() = runBlocking {
        val item = repo.create(LedgerDraft(title = "Rare gear"))
        repo.discover(item.id, item.revision, "Limitation", "Fragile near fire", "Research")
        try {
            repo.update(item.id, item.revision, LedgerDraft(title = "Rare gear", description = "Old draft"), "Stale editor")
            fail("Stale editor should not overwrite research")
        } catch (_: IllegalStateException) {}
        val saved = repo.items.first().single()
        assertTrue(saved.limitations.contains("Fragile near fire"))
        assertEquals(2, saved.revision)
    }

    @Test fun exportIncludesImageAndImmutableHistory() = runBlocking {
        val photo = "ZmFrZS1pbWFnZS1ieXRlcw=="
        val item = repo.create(LedgerDraft(title = "Illustrated staff", imageBase64 = photo))
        repo.discover(item.id, item.revision, "Confirmed", "Found runes", "Study")
        val data = repo.export()
        assertTrue(data.contains("astravar-campaign-items"))
        assertTrue(data.contains(photo))
        assertTrue(data.contains("Found runes"))
        assertTrue(data.contains("\"schema\":5"))
        assertTrue(repo.exportLegacy().contains("\"schema\":1"))
    }

    @Test fun discoveryCommandCannotCollideAcrossDetailAndSourceSeparators() = runBlocking {
        val item = repo.create(LedgerDraft(title = "Lens"))
        repo.discover(item.id, item.revision, "Confirmed", "Glows|blue", "Study", "same-command")
        try {
            repo.discover(item.id, item.revision, "Confirmed", "Glows", "blue|Study", "same-command")
            fail("Different note/source fields must not replay the first discovery")
        } catch (_: IllegalArgumentException) {}
        assertEquals(2, repo.eventsForItem(item.id).first().size)
        assertEquals("Glows|blue", repo.items.first().single().knownProperties)
    }

    @Test fun fileBackedLedgerSurvivesReopenWithoutChangingWeaponDatabase() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val weapon = AstravarDb.open(ctx)
        try {
            weapon.openHelper.writableDatabase
            val weaponVersion = weapon.openHelper.writableDatabase.version
            val weaponIdentity = weapon.openHelper.writableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id=42"
            ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
            var ledger = CampaignLedgerDb.open(ctx)
            val item = CampaignLedgerRepository(ledger).create(LedgerDraft(title = "Persistent test item"))
            ledger.close()
            ledger = CampaignLedgerDb.open(ctx)
            try {
                assertEquals(item, CampaignLedgerRepository(ledger).items.first().single())
                assertEquals(1, CampaignLedgerRepository(ledger).eventsForItem(item.id).first().size)
            } finally { ledger.close() }
            assertEquals(3, weaponVersion)
            assertEquals(weaponVersion, weapon.openHelper.writableDatabase.version)
            weapon.openHelper.writableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id=42"
            ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(weaponIdentity, cursor.getString(0)) }
            weapon.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE name IN ('ledger_items','ledger_events')"
            ).use { cursor -> assertEquals(0, cursor.count) }
        } finally {
            weapon.close()
            ctx.deleteDatabase("astravar-campaign-ledger.db")
        }
    }
}
