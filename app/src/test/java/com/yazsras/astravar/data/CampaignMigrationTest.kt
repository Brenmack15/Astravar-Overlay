package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampaignMigrationTest {
    /** A frozen schema-1 database, opened through real Room migration and schema validation. */
    @Test fun schemaOneRowsImagesUnknownsAndHistorySurviveExplicitMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-campaign-migration.db"
        context.deleteDatabase(name)
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE ledger_items (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL, owner TEXT NOT NULL, itemState TEXT NOT NULL, location TEXT NOT NULL, evidenceStatus TEXT NOT NULL, quantity INTEGER, description TEXT NOT NULL, knownProperties TEXT NOT NULL, possibleUses TEXT NOT NULL, limitations TEXT NOT NULL, source TEXT NOT NULL, notionPageId TEXT, imageBase64 TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, archived INTEGER NOT NULL)")
        legacy.execSQL("CREATE INDEX index_ledger_items_title ON ledger_items(title)")
        legacy.execSQL("CREATE TABLE ledger_events (commandId TEXT NOT NULL PRIMARY KEY, itemId TEXT NOT NULL, kind TEXT NOT NULL, evidenceStatus TEXT NOT NULL, detail TEXT NOT NULL, source TEXT NOT NULL, payloadFingerprint TEXT NOT NULL, priorRevision INTEGER NOT NULL, resultingRevision INTEGER NOT NULL, createdAt INTEGER NOT NULL, beforeJson TEXT, afterJson TEXT)")
        legacy.execSQL("CREATE INDEX index_ledger_events_itemId ON ledger_events(itemId)")
        legacy.execSQL("CREATE INDEX index_ledger_events_createdAt ON ledger_events(createdAt)")
        legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        legacy.execSQL("INSERT INTO room_master_table VALUES(42,'1e34b04c53d157fde0c7ab9aee4e8cef')")
        legacy.execSQL("INSERT INTO ledger_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "synthetic-legacy-id", "Synthetic legacy lens", "Other", "Unknown", "Stored", "Legacy box", "Unverified", null,
            "Exact legacy description", "Exact legacy property", "Unverified possibility", "Exact limitation", "Synthetic fixture", null,
            "c3ludGhldGljLWxlZ2FjeS1pbWFnZQ==", 10L, 20L, 3L, 1))
        legacy.execSQL("INSERT INTO ledger_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "synthetic-legacy-command", "synthetic-legacy-id", "DISCOVERY", "Possible use", "Exact old note", "Synthetic fixture",
            "original-fingerprint", 2L, 3L, 20L, "{\"original\":\"before\"}", "{\"original\":\"after\"}"))
        legacy.version = 1
        legacy.close()
        val migrated = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name)
            .addMigrations(CAMPAIGN_MIGRATION_1_2, CAMPAIGN_MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals(3, migrated.openHelper.writableDatabase.version)
            val item = migrated.ledgerDao().allItems().single()
            assertEquals("synthetic-legacy-id", item.id)
            assertNull(item.quantity)
            assertTrue(item.archived)
            assertEquals(3, item.revision)
            assertEquals("c3ludGhldGljLWxlZ2FjeS1pbWFnZQ==", item.imageBase64)
            assertEquals("Exact legacy property", item.knownProperties)
            val event = migrated.ledgerDao().allEvents().single()
            assertEquals("original-fingerprint", event.payloadFingerprint)
            assertEquals("{\"original\":\"before\"}", event.beforeJson)
            assertEquals("{\"original\":\"after\"}", event.afterJson)
            assertTrue(migrated.recordsDao().projects().first().isEmpty())
            assertTrue(migrated.recordsDao().companionStates().first().isEmpty())
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            CampaignLedgerRepository(migrated).records.saveProject(InventionProject("Synthetic post-migration blueprint"), source = "Test")
            assertEquals(1, migrated.recordsDao().allProjects().size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
