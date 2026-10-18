package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartyMigrationTest {
    @Test fun frozenV2DatabaseSurvivesAdditiveMigrationAndReopensWithNewRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-party-v2-migration.db"
        context.deleteDatabase(name)
        val schemaPath = "schemas/com.yazsras.astravar.data.CampaignLedgerDb/2.json"
        val schemaFile = listOf(File(schemaPath), File("app/$schemaPath")).first { it.exists() }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            fun sql(value: String) = value.replace("\${TABLE_NAME}", tableName)
            old.execSQL(sql(entity.getString("createSql")))
            val indices = entity.getJSONArray("indices")
            for (i in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(i).getString("createSql")))
        }
        old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        old.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
        old.execSQL("INSERT INTO character_profiles VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "Synthetic preserved actor", 3, 20, null, 15, 2, "synthetic-preserved-actor", 4L, "Original synthetic source", 40L))
        old.execSQL("INSERT INTO character_abilities VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "INT", "synthetic-preserved-actor", 15, 1, "Exact historical modifier", 3L, "Synthetic old ruling", 30L))
        old.execSQL("INSERT INTO companion_states VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(
            "Synthetic companion", "Disabled", "Exact old companion notes", "synthetic-companion", 5L, "Synthetic old checkpoint", 50L))
        old.execSQL("INSERT INTO ledger_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "synthetic-preserved-item", "Synthetic relic", "Other", "Unknown", "Unknown", "Unknown", "Disputed", null,
            "Exact historical description", "Exact property", "Old proposal", "Old limitation", "Original source", "synthetic-private-source-id",
            "c3ludGhldGljLW9yaWdpbmFsLWJ5dGVz", 10L, 20L, 2L, 0))
        old.execSQL("INSERT INTO ledger_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "synthetic-original-event", "synthetic-preserved-item", "DISCOVERY", "Disputed", "Exact old event", "Exact source",
            "original-fingerprint", 1L, 2L, 20L, "{\"original\":\"before\"}", "{\"original\":\"after\"}"))
        old.version = 2
        old.close()
        val migrated = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name)
            .addMigrations(CAMPAIGN_MIGRATION_1_2, CAMPAIGN_MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals(3, migrated.openHelper.writableDatabase.version)
            val profile = requireNotNull(migrated.recordsDao().characterProfile("synthetic-preserved-actor"))
            assertEquals(3, profile.currentHp)
            assertNull(profile.temporaryHp)
            assertEquals(4L, profile.revision)
            assertEquals("Exact historical modifier", migrated.recordsDao().allCharacterAbilities().single().notes)
            assertEquals("Disabled", migrated.recordsDao().allCompanionStates().single().condition)
            val item = migrated.ledgerDao().allItems().single()
            assertNull(item.quantity)
            assertEquals("Disputed", item.evidenceStatus)
            assertEquals("synthetic-private-source-id", item.notionPageId)
            assertEquals("c3ludGhldGljLW9yaWdpbmFsLWJ5dGVz", item.imageBase64)
            assertEquals("{\"original\":\"before\"}", migrated.ledgerDao().allEvents().single().beforeJson)
            assertTrue(migrated.partyDao().allSheets().isEmpty())
            assertTrue(migrated.partyDao().allPlacements().isEmpty())
            assertTrue(migrated.partyDao().allWallets().isEmpty())
            assertTrue(migrated.partyDao().allCheckpoints().isEmpty())
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            CampaignLedgerRepository(migrated).party.saveSheet(profile.id, PartySheetData(role = "Synthetic migrated role"), source = "Synthetic new observation")
        } finally { migrated.close() }
        // Reopen the named fixture; no production application data is used.
        val sameName = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name)
            .addMigrations(CAMPAIGN_MIGRATION_1_2, CAMPAIGN_MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals("Synthetic migrated role", sameName.partyDao().sheets().first().single().sheet.role)
            assertEquals(3, sameName.recordsDao().characterProfile("synthetic-preserved-actor")?.currentHp)
        } finally { sameName.close(); context.deleteDatabase(name) }
    }
}
