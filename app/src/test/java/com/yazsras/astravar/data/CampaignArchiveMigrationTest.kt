package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignArchiveMigrationTest {
    @Test fun frozenSchemaThreeRowsAndNullsSurviveAdditiveSchemaFourMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fictional-archive-migration-v3.db"
        context.deleteDatabase(name)
        val schemaPath = "schemas/com.yazsras.astravar.data.CampaignLedgerDb/3.json"
        val schema = JSONObject(listOf(File(schemaPath), File("app/$schemaPath")).first { it.exists() }.readText()).getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val nameSql = entity.getString("tableName")
            fun sql(value: String) = value.replace("\${TABLE_NAME}", nameSql)
            old.execSQL(sql(entity.getString("createSql")))
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (i in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(i).getString("createSql")))
        }
        old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        old.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
        old.execSQL("INSERT INTO character_profiles VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("Fictional existing actor", null, 30, null, null, 6, "fictional-existing", 9, "Fictional old source", 1))
        old.execSQL("INSERT INTO party_wallets VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("fictional-existing-wallet", "fictional-existing", "Fictional purse", null, 2, 3, 7, "Fictional old source", 1, "fictional-campaign"))
        old.version = 3; old.close()
        val migrated = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name)
            .addMigrations(CAMPAIGN_MIGRATION_3_4, CAMPAIGN_MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            assertEquals(5, migrated.openHelper.writableDatabase.version)
            assertNull(migrated.recordsDao().allCharacterProfiles().single().currentHp)
            assertEquals(9L, migrated.recordsDao().allCharacterProfiles().single().revision)
            assertNull(migrated.partyDao().allWallets().single().copper)
            assertEquals(3L, migrated.partyDao().allWallets().single().gold)
            assertTrue(migrated.archiveDao().allReferences().isEmpty())
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
