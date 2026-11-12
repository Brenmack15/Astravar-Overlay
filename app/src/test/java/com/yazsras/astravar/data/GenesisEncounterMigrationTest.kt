package com.yazsras.astravar.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A fictional frozen database, including opaque historical evidence; migration must not reinterpret it. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisEncounterMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val name = "fictional-genesis-encounter-migration-v8.db"
    private var opened: CampaignLedgerDb? = null
    private val rawEvidence = """ { "source" : "FICTIONAL preserved evidence", "pendingReview" : true, "unknownValue" : null, "futureField" : [ "龍", 1 ] } """
    private val rawOrigin = """ { "label" : "FICTIONAL original provenance", "reviewState" : "PENDING", "futureOrigin" : [ null, "龍" ] } """
    private val rawCharacter = """ { "id" : "fictional-character-profiles-id", "name" : "Fictional retained actor", "futureCharacter" : { "unknown" : null } } """

    @Before fun prepare() { context.deleteDatabase(name) }
    @After fun close() { opened?.close(); context.deleteDatabase(name) }

    private fun frozenSchema(): JSONObject {
        val relative = "schemas/com.yazsras.astravar.data.CampaignLedgerDb/8.json"
        return JSONObject(listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()).getJSONObject("database")
    }

    private fun entities(schema: JSONObject): List<JSONObject> = schema.getJSONArray("entities").let { values ->
        (0 until values.length()).map { values.getJSONObject(it) }
    }

    private fun identity(table: String, column: String): Any =
        if (table == "genesis_storage_receipt" && column == "id") 1L
        else "fictional-${table.replace('_', '-')}-${column}"

    private fun createFrozenDatabase(): List<String> {
        val schema = frozenSchema()
        val entities = entities(schema)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (entity in entities) {
                val table = entity.getString("tableName")
                fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                old.execSQL(sql(entity.getString("createSql")))
                entity.optJSONArray("indices")?.let { indices ->
                    for (index in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(index).getString("createSql")))
                }
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))

            // Every old table has a typed row. Foreign-key identities are taken from the frozen schema,
            // while nullable gameplay values remain unknown and old JSON remains verbatim.
            for (entity in entities) seed(old, entity)
            old.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals("The historical fixture must be referentially valid", 0, it.count) }
            old.version = 8
        }
        return entities.map { it.getString("tableName") }
    }

    private fun seed(old: SQLiteDatabase, entity: JSONObject) {
        val table = entity.getString("tableName")
        val fields = entity.getJSONArray("fields")
        val values = linkedMapOf<String, Any?>()
        for (index in 0 until fields.length()) {
            val field = fields.getJSONObject(index)
            val column = field.getString("columnName")
            values[column] = when {
                !field.optBoolean("notNull") -> null
                field.getString("affinity") == "INTEGER" -> when (column) {
                    "createdAt", "completedAt", "initializedAt" -> 123L
                    "updatedAt", "importedAt" -> 456L
                    "archived", "resolved", "manual", "playable" -> 0L
                    "revision", "resultingRevision", "expectedRevision", "characterRevision" -> 7L
                    "priorRevision" -> 6L
                    else -> 1L
                }
                column == "source" -> "FICTIONAL original source; unreviewed details remain unknown"
                column.endsWith("Json") -> rawEvidence
                else -> "Fictional $table $column"
            }
        }
        entity.getJSONObject("primaryKey").getJSONArray("columnNames").let { keys ->
            for (index in 0 until keys.length()) keys.getString(index).let { values[it] = identity(table, it) }
        }
        entity.optJSONArray("foreignKeys")?.let { foreignKeys ->
            for (index in 0 until foreignKeys.length()) {
                val foreign = foreignKeys.getJSONObject(index)
                val columns = foreign.getJSONArray("columns")
                val referenced = foreign.getJSONArray("referencedColumns")
                for (part in 0 until columns.length()) values[columns.getString(part)] = identity(foreign.getString("table"), referenced.getString(part))
            }
        }
        val actor = identity("character_profiles", "id")
        val campaign = identity("genesis_campaigns", "id")
        val item = identity("ledger_items", "id")
        when (table) {
            "ledger_items" -> values.putAll(mapOf("category" to "Other", "itemState" to "Stored", "evidenceStatus" to "Unverified",
                "quantity" to null, "imageBase64" to "ZmljdGlvbmFsLWltYWdl", "description" to "Fictional retained item\n龍\u0000suffix"))
            "ledger_events" -> values.putAll(mapOf("itemId" to item, "kind" to "DISCOVERY", "evidenceStatus" to "Unverified",
                "beforeJson" to rawEvidence, "afterJson" to rawOrigin))
            "invention_projects", "project_stages" -> values["status"] = "Planning"
            "connection_entities" -> values.putAll(mapOf("kind" to "NPC", "aliases" to "Fictional alias", "relationship" to "Unknown"))
            "connection_facts", "item_properties" -> values["evidenceStatus"] = "Unverified"
            "character_profiles" -> values["name"] = "Fictional retained actor"
            "character_abilities" -> values.putAll(mapOf("key" to "STR", "magicModifier" to 2L))
            "campaign_links" -> values.putAll(mapOf("fromType" to "CHARACTER", "fromId" to actor,
                "toType" to "COMPANION", "toId" to identity("companion_states", "companionId")))
            "campaign_audit" -> values.putAll(mapOf("recordId" to actor, "beforeJson" to rawOrigin, "afterJson" to rawEvidence))
            "command_receipts" -> values.putAll(mapOf("recordId" to actor, "outcomeJson" to rawEvidence))
            "party_sheets" -> values.putAll(mapOf("campaignId" to campaign, "contentJson" to rawEvidence))
            "item_placements" -> values.putAll(mapOf("containerItemId" to null, "installedProjectId" to null, "state" to "STORED"))
            "party_wallets" -> values.putAll(mapOf("campaignId" to campaign, "copper" to null, "silver" to 2L, "gold" to 3L,
                "electrum" to null, "platinum" to null))
            "campaign_checkpoints" -> values.putAll(mapOf("campaignId" to campaign, "gameTime" to "Unknown",
                "recordReferences" to "[\"fictional-character-profiles-id\"]", "unknowns" to "[\"Fictional unresolved choice\"]"))
            "external_record_references" -> values.putAll(mapOf("campaignId" to campaign, "recordId" to actor,
                "url" to "https://example.invalid/fictional-source"))
            "campaign_references" -> values.putAll(mapOf("campaignId" to campaign, "sourceUrl" to "https://example.invalid/fictional-source",
                "propertiesJson" to rawEvidence, "body" to "Fictional unapproved source record"))
            "campaign_reference_relations" -> values.putAll(mapOf("fromId" to identity("campaign_references", "id"),
                "toId" to "fictional-unresolved-reference", "resolved" to 0L))
            "campaign_import_receipts" -> values.putAll(mapOf("campaignId" to campaign, "state" to "APPLIED"))
            "campaign_import_backups" -> values["snapshotJson"] = rawEvidence
            "campaign_archive_sources" -> values["payloadJson"] = rawOrigin
            "reforge_characters" -> values.putAll(mapOf("profileId" to actor, "characterJson" to rawCharacter, "originJson" to rawOrigin))
            "reforge_changes" -> values.putAll(mapOf("profileId" to actor, "beforeJson" to rawEvidence, "afterJson" to rawCharacter,
                "outcomeType" to "HISTORICAL_FUTURE_TYPE"))
            "reforge_rolls" -> values.putAll(mapOf("profileId" to actor, "components" to " [ { \"futureDie\" : null, \"label\" : \"Fictional 龍\" } ] ",
                "manual" to 1L, "ownership" to "Player"))
            "reforge_note_drafts" -> values.putAll(mapOf("profileId" to actor, "text" to "Uncommitted fictional draft\n龍\n"))
            "genesis_storage_receipt" -> values.putAll(mapOf("sourceVersion" to 1L, "countsJson" to " { \"reforge_characters\" : 1 } ",
                "authority" to "campaign-ledger"))
            "genesis_actor_authority" -> values.putAll(mapOf("actorId" to actor, "role" to "NPC", "campaignId" to campaign,
                "playable" to 0L, "resourceBindingsJson" to "{}"))
            "genesis_item_authority" -> values.putAll(mapOf("itemId" to item, "ownerActorId" to actor, "definitionJson" to rawEvidence,
                "instanceJson" to rawOrigin, "sourceProofJson" to rawEvidence))
            "genesis_authority_receipts" -> values.putAll(mapOf("kind" to "HISTORICAL_FUTURE_AUTHORITY_KIND",
                "requestJson" to rawEvidence, "outcomeJson" to rawOrigin))
        }
        val columns = values.keys.joinToString(",") { "\"$it\"" }
        old.execSQL("INSERT INTO \"$table\" ($columns) VALUES (${values.keys.joinToString(",") { "?" }})", values.values.toTypedArray())
    }

    private fun rawRows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList { while (it.moveToNext()) add((0 until it.columnCount).map { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }

    /** Hexadecimal scalar bytes distinguish NULL, UTF-8, embedded NUL, whitespace and integer storage. */
    private fun snapshot(tables: List<String>, query: (String) -> Cursor): Map<String, List<List<String?>>> = buildMap {
        for (table in tables) {
            val columns = query("PRAGMA table_info(\"$table\")").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            val encoded = columns.joinToString(",") { "typeof(\"$it\"),hex(CAST(\"$it\" AS BLOB))" }
            put(table, rawRows(query("SELECT $encoded FROM \"$table\" ORDER BY rowid")))
            put("$table schema", rawRows(query("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name='$table' ORDER BY type,name")))
        }
    }

    private fun openCurrent(migration: Migration = CAMPAIGN_MIGRATION_8_9): CampaignLedgerDb =
        Room.databaseBuilder(context, CampaignLedgerDb::class.java, name)
            .openHelperFactory(GenesisNonDestructiveOpenHelperFactory())
            .addMigrations(migration).allowMainThreadQueries().build().also { opened = it }

    @Test fun everyFrozenSchemaEightTableAndOpaqueHistorySurviveMigrationAndReopen() = runBlocking {
        val tables = createFrozenDatabase()
        val before = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            snapshot(tables) { old.rawQuery(it, null) }
        }
        assertTrue("Each historical table is exercised", tables.all { before.getValue(it).isNotEmpty() })
        repeat(2) {
            val migrated = openCurrent()
            val sql = migrated.openHelper.writableDatabase
            assertEquals(9, sql.version)
            assertEquals(before, snapshot(tables, sql::query))
            assertTrue("Migration does not invent encounters", migrated.genesisEncounterDao().allEncounters().isEmpty())
            assertTrue("Migration does not enroll participants", migrated.genesisEncounterDao().allParticipants().isEmpty())
            sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            sql.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNotEquals(frozenSchema().getString("identityHash"), cursor.getString(0))
            }
            migrated.close(); opened = null
        }
    }

    @Test fun failedAdditiveMigrationRollsBackWithoutDeletingOrRewritingSchemaEightRows() {
        val tables = createFrozenDatabase() + "room_master_table"
        val before = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            snapshot(tables) { old.rawQuery(it, null) }
        }
        val failed = openCurrent(object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                CAMPAIGN_MIGRATION_8_9.migrate(db)
                error("FICTIONAL failure after additive encounter migration")
            }
        })
        val failure = runCatching { failed.openHelper.writableDatabase }.exceptionOrNull()
        assertNotNull("The controlled migration must fail", failure)
        failed.close(); opened = null
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            assertEquals(8, old.version)
            assertEquals(before, snapshot(tables) { old.rawQuery(it, null) })
            old.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'genesis_encounter%'", null).use {
                assertEquals("Failed migration must not leave partial encounter tables", 0, it.count)
            }
            old.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
        }
    }
}
