package com.yazsras.astravar.data

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** All campaign fixtures are fictional and no network services are used. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ChatGptPromptRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CampaignLedgerDb
    private var now = 1000L
    private lateinit var prompts: ChatGptPromptRepository
    private val fileName = "fictional-chatgpt-prompts.db"

    @Before fun open() {
        context.deleteDatabase(fileName)
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        prompts = ChatGptPromptRepository(db) { now }
    }

    @After fun close() { db.close(); context.deleteDatabase(fileName) }

    private suspend fun rejects(message: String, operation: suspend () -> Unit) {
        try { operation(); fail("Expected rejection: $message") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message)) }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message)) }
    }

    private fun seed(id: String = "fictional-original", body: String = "Fictional evergreen instruction") =
        ChatGptPromptSeed(id, "Fictional original prompt", "Fictional optional description", body, "Fictional category")

    @Test fun allElevenOriginalPromptsMigrateWithoutChangingAnyContentOrIdentity() = runBlocking {
        val original = loadChatGptPromptSeeds(context)
        assertEquals(11, original.size)
        prompts.initialize(original)
        assertEquals(original.map { it.id }.toSet(), db.promptDao().all().map { it.id }.toSet())
        original.forEach { seed ->
            val saved = requireNotNull(prompts.get(seed.id))
            assertEquals(seed.name, saved.name)
            assertEquals(seed.description, saved.description)
            assertEquals(seed.content, saved.content)
            assertEquals(seed.category, saved.category)
            assertEquals(seed.content, prompts.render(seed.id))
            assertEquals(1000L, saved.createdAt)
            assertEquals(saved.createdAt, saved.updatedAt)
        }
        val snapshot = db.promptDao().all()
        now = 3000
        prompts.initialize(original)
        assertEquals(snapshot, db.promptDao().all())
    }

    @Test fun initializationPreservesAnExistingIdentityRatherThanOverwritingIt() = runBlocking {
        val manual = prompts.create(ChatGptPromptDraft("Existing choice", "Existing edited instruction"), "fictional-original")
        prompts.initialize(listOf(seed(), seed("fictional-second")))
        assertEquals(manual, prompts.get(manual.id))
        assertNotNull(prompts.get("fictional-second"))
        assertEquals(2, db.promptDao().all().size)
    }

    @Test fun initializedPromptsRemainUsableWithoutReadingTheBundledCatalogAgain() = runBlocking {
        var reads = 0
        prompts.initialize { reads++; listOf(seed()) }
        prompts.initialize { error("The old bundled catalog is unavailable") }
        assertEquals(1, reads)
        assertEquals("Fictional evergreen instruction", prompts.render("fictional-original"))
    }

    @Test fun invalidInitializationLeavesNeitherPartialDefaultsNorAnInitializationMarker() = runBlocking {
        rejects("duplicate") { prompts.initialize(listOf(seed(), seed())) }
        assertTrue(db.promptDao().all().isEmpty())
        assertNull(db.promptDao().catalogState("astravar-chatgpt-catalog-v1"))
        rejects("content") { prompts.initialize(listOf(seed(), seed("fictional-bad", "   "))) }
        assertTrue(db.promptDao().all().isEmpty())
        assertNull(db.promptDao().catalogState("astravar-chatgpt-catalog-v1"))
        prompts.initialize(listOf(seed()))
        assertEquals(1, db.promptDao().all().size)
    }

    @Test fun createAndUpdatePreserveStableIdentityCreationDateAndFullText() = runBlocking {
        val text = "\nFictional complete text\n  Preserve indentation and café 星図 🧭\n"
        val created = prompts.create(ChatGptPromptDraft("  Fictional prompt  ", text, "Optional description", "  Research  "), "fictional-edit")
        assertEquals("Fictional prompt", created.name)
        assertEquals("Research", created.category)
        assertEquals(text, created.content)
        assertEquals(created, prompts.prompts.first().single())
        now = 900 // Even a corrected device clock must not move Last Updated backwards.
        val changed = prompts.update(created.id, created.revision,
            ChatGptPromptDraft("Renamed prompt", "New complete instruction", category = ""))
        assertEquals(created.id, changed.id)
        assertEquals(created.createdAt, changed.createdAt)
        assertEquals(created.updatedAt + 1, changed.updatedAt)
        assertEquals(created.revision + 1, changed.revision)
        assertEquals("", changed.description)
        assertEquals(changed, prompts.get(created.id))
    }

    @Test fun duplicateIdentifiersInvalidNamesEmptyContentAndOversizedValuesAreRejected() = runBlocking {
        val saved = prompts.create(ChatGptPromptDraft("Valid", "Valid body"), "fictional-valid")
        rejects("already exists") { prompts.create(ChatGptPromptDraft("Other", "Other body"), saved.id) }
        rejects("Identifier") { prompts.create(ChatGptPromptDraft("Valid", "Valid"), "invalid identifier") }
        rejects("name") { prompts.create(ChatGptPromptDraft("   ", "Valid")) }
        rejects("content") { prompts.create(ChatGptPromptDraft("Valid", "\n\t")) }
        rejects("content") { prompts.create(ChatGptPromptDraft("Valid", "x".repeat(100_001))) }
        rejects("Description") { prompts.create(ChatGptPromptDraft("Valid", "Valid", "x".repeat(4001))) }
        rejects("Category") { prompts.create(ChatGptPromptDraft("Valid", "Valid", category = "x".repeat(81))) }
        assertEquals(listOf(saved), db.promptDao().all())
    }

    @Test fun staleUpdatesAndDeleteConfirmationCannotOverwriteANewerEdit() = runBlocking {
        val first = prompts.create(ChatGptPromptDraft("First", "First body"), "fictional-stale")
        val second = prompts.update(first.id, first.revision, ChatGptPromptDraft("Second", "Second body"))
        rejects("changed since editing") { prompts.update(first.id, first.revision, ChatGptPromptDraft("Stale", "Stale body")) }
        rejects("changed since deletion") { prompts.delete(first.id, first.revision) }
        assertEquals(second, prompts.get(first.id))
        prompts.delete(second.id, second.revision)
        assertNull(prompts.get(first.id))
        rejects("unavailable or was deleted") { prompts.render(first.id) }
    }

    @Test fun subsequentPromptUseReadsTheLatestSavedTextInsteadOfTheBundledOrCachedBody() = runBlocking {
        prompts.initialize(listOf(seed(body = "Original instruction")))
        val original = requireNotNull(prompts.get("fictional-original"))
        assertEquals("Original instruction", prompts.render(original.id))
        prompts.update(original.id, original.revision, ChatGptPromptDraft("Edited", "Edited for {characterName}"))
        assertEquals("Edited for Fictional actor", prompts.render(original.id, mapOf("characterName" to "Fictional actor")))
        assertEquals("Edited for Another actor", ChatGptPromptRepository(db).render(original.id, mapOf("characterName" to "Another actor")))
    }

    @Test fun placeholderRenderingIsLiteralSinglePassAndPreservesJsonAndEscapedBraces() {
        val source = "For {characterName}: {context}; repeat {characterName}; literal {{characterName}}; JSON {\"x\":1}."
        val supplied = "\$1\\line\n{characterName}"
        assertEquals(listOf("characterName", "context"), ChatGptPromptRenderer.placeholders(source))
        assertEquals("For Fictional actor: $supplied; repeat Fictional actor; literal {characterName}; JSON {\"x\":1}.",
            ChatGptPromptRenderer.render(source, mapOf("characterName" to "Fictional actor", "context" to supplied)))
        assertEquals("{escaped}", ChatGptPromptRenderer.render("{{escaped}}"))
        assertEquals("Value: ", ChatGptPromptRenderer.render("Value: {context}", mapOf("context" to "")))
    }

    @Test fun missingVariablesAreReportedWithoutPartiallyRenderingOrSilentlyInventingValues() = runBlocking {
        rejects("characterName, context") { ChatGptPromptRenderer.render("For {characterName}: {context}") }
        rejects("context") { ChatGptPromptRenderer.render("For {characterName}: {context}", mapOf("characterName" to "Fictional")) }
    }

    @Test fun excessiveVariablesAndExpansionAreRejectedBeforeConstructingAnOversizedCompletedPrompt() = runBlocking {
        val tooMany = (1..65).joinToString(" ") { "{variable$it}" }
        rejects("64 different variables") { prompts.create(ChatGptPromptDraft("Too many variables", tooMany)) }
        rejects("64 different variables") { ChatGptPromptRenderer.render(tooMany, (1..65).associate { "variable$it" to "value" }) }
        rejects("exceeds 100,000") { ChatGptPromptRenderer.render("{context}".repeat(1000), mapOf("context" to "x".repeat(101))) }
        assertEquals("x".repeat(100_000), ChatGptPromptRenderer.render("{context}".repeat(1000), mapOf("context" to "x".repeat(100))))
        rejects("completed prompt is empty") { ChatGptPromptRenderer.render("{context}", mapOf("context" to "")) }
    }

    @Test fun editedCreatedAndDeletedPromptsRemainCorrectAfterDatabaseAndRepositoryReopen() = runBlocking {
        db.close()
        fun disk() = Room.databaseBuilder(context, CampaignLedgerDb::class.java, fileName).allowMainThreadQueries().build()
        db = disk()
        prompts = ChatGptPromptRepository(db) { now }
        val defaults = listOf(seed(), seed("fictional-delete"))
        prompts.initialize(defaults)
        val original = requireNotNull(prompts.get("fictional-original"))
        val edit = prompts.update(original.id, original.revision, ChatGptPromptDraft("Saved edit", "Saved editable text"))
        val deleted = requireNotNull(prompts.get("fictional-delete"))
        prompts.delete(deleted.id, deleted.revision)
        val custom = prompts.create(ChatGptPromptDraft("Saved custom", "Custom body"), "fictional-custom")
        db.close()
        db = disk()
        prompts = ChatGptPromptRepository(db) { 9999 }
        prompts.initialize(defaults)
        assertEquals(edit, prompts.get(edit.id))
        assertEquals(custom, prompts.get(custom.id))
        assertNull(prompts.get(deleted.id))
        assertEquals(2, db.promptDao().all().size)
        assertNotNull(db.promptDao().catalogState("astravar-chatgpt-catalog-v1"))
    }

    @Test fun promptChangesDoNotModifyCampaignSnapshotsOrBlockAnExistingGuardedRollback() = runBlocking {
        val campaign = CampaignLedgerRepository(db)
        val originalCampaign = CampaignBackup(db).snapshot()
        prompts.initialize(listOf(seed()))
        val beforePrompt = requireNotNull(prompts.get("fictional-original"))
        val body = "Fictional source only."
        val payload = CampaignArchivePayload("fictional-prompt-campaign", "Fictional archive", 1,
            "https://example.invalid/root", "fictional-revision", listOf(CampaignArchiveRecord("fictional-note", "Fictional note", "Chronicle",
                body, sourceUrl = "https://example.invalid/note", sourceRevision = "fictional-revision", contentSha256 = campaignHash(body), visibility = "player")))
        val json = campaignJson.encodeToString(payload)
        val text = campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive", 1, campaignHash(json), json))
        assertEquals(originalCampaign, CampaignBackup(db).snapshot())
        val preview = campaign.archive.preview(text)
        assertTrue(preview.conflicts.toString(), preview.valid)
        campaign.archive.exportPreImportBackup(preview.fingerprint)
        val receipt = campaign.archive.commit(preview, true)
        val edited = prompts.update(beforePrompt.id, beforePrompt.revision, ChatGptPromptDraft("Edited after import", "This edit must survive rollback"))
        val marker = db.promptDao().catalogState("astravar-chatgpt-catalog-v1")
        campaign.archive.rollback(receipt.packageHash, true)
        assertEquals(edited, prompts.get(edited.id))
        assertEquals(marker, db.promptDao().catalogState("astravar-chatgpt-catalog-v1"))
        assertTrue(db.archiveDao().allReferences().isEmpty())
        assertEquals(receipt, db.archiveDao().receipt(receipt.packageHash))
    }

    /** Build the actual frozen version-5 schema independently, rather than testing 6 against itself. */
    @Test fun additiveFiveToSixMigrationPreservesItemsUnknownCharacterValuesWalletsSourcesAndReceipts() = runBlocking {
        db.close()
        val schemaPath = "schemas/com.yazsras.astravar.data.CampaignLedgerDb/5.json"
        val schema = JSONObject(listOf(File(schemaPath), File("app/$schemaPath")).first { it.exists() }.readText()).getJSONObject("database")
        val old = context.openOrCreateDatabase(fileName, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
            old.execSQL(sql(entity.getString("createSql")))
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
        }
        fun insert(table: String, text: String) {
            val json = JSONObject(text)
            val values = ContentValues()
            json.keys().forEach { key ->
                when (val value = json.get(key)) {
                    JSONObject.NULL -> values.putNull(key)
                    is Boolean -> values.put(key, if (value) 1L else 0L)
                    is Number -> values.put(key, value.toLong())
                    else -> values.put(key, value.toString())
                }
            }
            assertTrue(old.insertOrThrow(table, null, values) != -1L)
        }
        val item = LedgerItem("fictional-preserved-item", "Fictional preserved item", quantity = null, createdAt = 10, updatedAt = 20)
        val character = CharacterProfile("Fictional preserved actor", currentHp = null, maximumHp = 30, id = "fictional-preserved-actor", updatedAt = 20)
        val wallet = PartyWallet("fictional-preserved-wallet", character.id, "Fictional purse", gold = 3, updatedAt = 20, campaignId = "fictional-preserved-campaign")
        val source = CampaignArchiveSource("fictional-preserved-source", "Original fictional source bytes")
        val receipt = CampaignImportReceipt("fictional-preserved-source", "fictional-preserved-campaign", "Fictional receipt", 20, 1, 0, "APPLIED", "fictional-readback", "Original immutable receipt")
        insert("ledger_items", campaignJson.encodeToString(item))
        insert("character_profiles", campaignJson.encodeToString(character))
        insert("party_wallets", campaignJson.encodeToString(wallet))
        insert("campaign_archive_sources", campaignJson.encodeToString(source))
        insert("campaign_import_receipts", campaignJson.encodeToString(receipt))
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        old.version = 5
        old.close()
        db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, fileName).allowMainThreadQueries()
            .addMigrations(CAMPAIGN_MIGRATION_5_6).build()
        prompts = ChatGptPromptRepository(db) { now }
        assertEquals(item, db.ledgerDao().getItem(item.id))
        assertEquals(character, db.recordsDao().allCharacterProfiles().single())
        assertEquals(wallet, db.partyDao().wallet(wallet.id))
        assertEquals(source, db.archiveDao().source(source.packageHash))
        assertEquals(receipt, db.archiveDao().receipt(receipt.packageHash))
        assertEquals(6, db.openHelper.readableDatabase.version)
        assertTrue(db.promptDao().all().isEmpty())
        prompts.initialize(loadChatGptPromptSeeds(context))
        assertEquals(11, db.promptDao().all().size)
        assertEquals(item, db.ledgerDao().getItem(item.id))
        assertEquals(receipt, db.archiveDao().receipt(receipt.packageHash))
    }
}
