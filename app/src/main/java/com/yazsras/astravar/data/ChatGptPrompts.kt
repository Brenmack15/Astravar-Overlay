package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** App-wide editable instructions, separate from campaign facts and gameplay receipts. */
@Serializable
@Entity(tableName = "chatgpt_prompts", indices = [Index("name"), Index("category")])
data class ChatGptPrompt(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val content: String,
    val category: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1
)

data class ChatGptPromptDraft(
    val name: String,
    val content: String,
    val description: String = "",
    val category: String = ""
)

data class ChatGptPromptSeed(
    val id: String,
    val name: String,
    val description: String,
    val content: String,
    val category: String
)

/** A durable catalog marker prevents deleted defaults from being resurrected on restart. */
@Entity(tableName = "prompt_catalog_state")
data class ChatGptPromptCatalogState(
    @PrimaryKey val id: String,
    val initializedAt: Long,
    val recordCount: Int
)

@Dao
interface ChatGptPromptDao {
    @Query("SELECT * FROM chatgpt_prompts ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observePrompts(): Flow<List<ChatGptPrompt>>

    @Query("SELECT * FROM chatgpt_prompts WHERE id=:id")
    suspend fun get(id: String): ChatGptPrompt?

    @Query("SELECT * FROM chatgpt_prompts ORDER BY id ASC")
    suspend fun all(): List<ChatGptPrompt>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(prompt: ChatGptPrompt)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaults(prompts: List<ChatGptPrompt>)

    @Update
    suspend fun update(prompt: ChatGptPrompt): Int

    @Query("DELETE FROM chatgpt_prompts WHERE id=:id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM prompt_catalog_state WHERE id=:id")
    suspend fun catalogState(id: String): ChatGptPromptCatalogState?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCatalogState(state: ChatGptPromptCatalogState)
}

/** The bundled catalog is an installation seed only; consumers always read Room afterwards. */
fun loadChatGptPromptSeeds(context: Context): List<ChatGptPromptSeed> =
    context.assets.open("astravar_chatgpt_prompts.json").bufferedReader(Charsets.UTF_8).use { stream ->
        val root = Json.parseToJsonElement(stream.readText()).jsonObject
        require(root.getValue("type").jsonPrimitive.content == "astravar-reusable-prompt-catalog") {
            "The bundled prompt catalog has an unsupported format."
        }
        root.getValue("templates").jsonArray.map { entry ->
            val row = entry.jsonObject
            ChatGptPromptSeed(
                id = row.getValue("id").jsonPrimitive.content,
                name = row.getValue("title").jsonPrimitive.content,
                description = row.getValue("action").jsonPrimitive.content,
                content = row.getValue("body").jsonPrimitive.content,
                category = row.getValue("section").jsonPrimitive.content
            )
        }
    }

/** Only named tokens are interpreted. JSON/object braces and user values are never executable. */
object ChatGptPromptRenderer {
    const val MAX_VARIABLES = 64
    const val MAX_RENDERED_CHARACTERS = 100_000
    private val token = Regex("\\{\\{([A-Za-z][A-Za-z0-9_]{0,63})\\}\\}|\\{([A-Za-z][A-Za-z0-9_]{0,63})\\}")

    fun placeholders(content: String): List<String> = token.findAll(content)
        .mapNotNull { it.groups[2]?.value }.distinct().take(MAX_VARIABLES + 1).toList()

    fun render(content: String, variables: Map<String, String> = emptyMap()): String {
        require(content.isNotBlank() && content.length <= ChatGptPromptRepository.MAX_CONTENT_CHARACTERS) {
            "Prompt content must contain 1–100,000 characters."
        }
        val names = placeholders(content)
        require(names.size <= MAX_VARIABLES) { "A prompt can use at most 64 different variables." }
        val missing = names.filter { it !in variables }
        require(missing.isEmpty()) { "Enter a value for: ${missing.joinToString()}." }
        // Preflight all expansions with Long arithmetic before allocating the rendered text.
        var renderedLength = content.length.toLong()
        token.findAll(content).forEach { match ->
            val replacementLength = match.groups[1]?.let { it.value.length + 2 }
                ?: variables.getValue(match.groups[2]!!.value).length
            renderedLength += replacementLength.toLong() - match.value.length
        }
        require(renderedLength <= MAX_RENDERED_CHARACTERS) {
            "The completed prompt exceeds 100,000 characters. Shorten the prompt or variable values."
        }
        // Match replacement is literal and single-pass: values such as "$1" or "{other}" stay unchanged.
        val result = token.replace(content) { match ->
            match.groups[1]?.let { "{${it.value}}" } ?: variables.getValue(match.groups[2]!!.value)
        }
        require(result.isNotBlank()) { "The completed prompt is empty. Enter a variable value or edit the prompt." }
        return result
    }
}

/** The same Room/Flow, transaction and optimistic-edit patterns used by inventory records. */
class ChatGptPromptRepository(
    private val db: CampaignLedgerDb,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val dao = db.promptDao()
    val prompts: Flow<List<ChatGptPrompt>> = dao.observePrompts()

    companion object {
        private const val CATALOG_ID = "astravar-chatgpt-catalog-v1"
        const val MAX_CONTENT_CHARACTERS = 100_000
        private val identity = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
    }

    private fun validId(id: String) {
        require(identity.matches(id)) { "Identifier must contain 1–120 letters, numbers, dots, underscores or hyphens." }
    }

    private fun validate(draft: ChatGptPromptDraft): ChatGptPromptDraft {
        val clean = draft.copy(name = draft.name.trim(), category = draft.category.trim())
        require(clean.name.length in 1..120) { "Prompt name must contain 1–120 characters." }
        require(clean.description.length <= 4_000) { "Description must contain at most 4,000 characters." }
        require(clean.category.length <= 80) { "Category must contain at most 80 characters." }
        require(clean.content.isNotBlank() && clean.content.length <= MAX_CONTENT_CHARACTERS) {
            "Prompt content must contain 1–100,000 characters."
        }
        require(ChatGptPromptRenderer.placeholders(clean.content).size <= ChatGptPromptRenderer.MAX_VARIABLES) {
            "A prompt can use at most 64 different variables."
        }
        return clean
    }

    /** All defaults and the marker commit together, or neither does. Existing IDs are never overwritten. */
    suspend fun initialize(defaults: List<ChatGptPromptSeed>) = initialize { defaults }

    /** Once initialized, even a missing later seed asset cannot disable the saved prompt library. */
    suspend fun initialize(loadDefaults: () -> List<ChatGptPromptSeed>) = db.withTransaction {
        if (dao.catalogState(CATALOG_ID) != null) return@withTransaction
        val defaults = loadDefaults()
        require(defaults.isNotEmpty()) { "The bundled prompt catalog is empty. No initialization was saved." }
        require(defaults.map { it.id }.distinct().size == defaults.size) { "The bundled prompt catalog contains duplicate identifiers." }
        val now = clock()
        require(now >= 0) { "The current date is unavailable." }
        val records = defaults.map { seed ->
            validId(seed.id)
            val draft = validate(ChatGptPromptDraft(seed.name, seed.content, seed.description, seed.category))
            ChatGptPrompt(seed.id, draft.name, draft.description, draft.content, draft.category, now, now)
        }
        dao.insertDefaults(records)
        dao.insertCatalogState(ChatGptPromptCatalogState(CATALOG_ID, now, records.size))
    }

    suspend fun create(input: ChatGptPromptDraft, id: String = UUID.randomUUID().toString()): ChatGptPrompt = db.withTransaction {
        validId(id)
        val draft = validate(input)
        require(dao.get(id) == null) { "That prompt identifier already exists. Choose a different identifier." }
        val now = clock()
        require(now >= 0) { "The current date is unavailable." }
        ChatGptPrompt(id, draft.name, draft.description, draft.content, draft.category, now, now).also { dao.insert(it) }
    }

    suspend fun update(id: String, expectedRevision: Long, input: ChatGptPromptDraft): ChatGptPrompt = db.withTransaction {
        validId(id)
        val draft = validate(input)
        val before = checkNotNull(dao.get(id)) { "This prompt no longer exists. Close the editor and choose another prompt." }
        check(before.revision == expectedRevision) { "This prompt changed since editing began. Reopen it before saving." }
        check(before.revision < Long.MAX_VALUE && before.updatedAt < Long.MAX_VALUE) { "This prompt's revision or date cannot advance." }
        val after = before.copy(name = draft.name, description = draft.description, content = draft.content,
            category = draft.category, updatedAt = maxOf(clock(), before.updatedAt + 1), revision = before.revision + 1)
        check(dao.update(after) == 1) { "This prompt could not be saved. Reopen it before trying again." }
        after
    }

    suspend fun delete(id: String, expectedRevision: Long) = db.withTransaction {
        validId(id)
        val before = checkNotNull(dao.get(id)) { "This prompt no longer exists." }
        check(before.revision == expectedRevision) { "This prompt changed since deletion was requested. Review it again before deleting." }
        check(dao.delete(id) == 1) { "This prompt could not be deleted. Review it again before trying." }
    }

    suspend fun get(id: String): ChatGptPrompt? = dao.get(id)

    /** Re-read by immutable identifier for every use; never send a previously cached UI body. */
    suspend fun render(id: String, variables: Map<String, String> = emptyMap()): String {
        val prompt = checkNotNull(dao.get(id)) { "This prompt is unavailable or was deleted. Choose or create another prompt." }
        validate(ChatGptPromptDraft(prompt.name, prompt.content, prompt.description, prompt.category))
        return ChatGptPromptRenderer.render(prompt.content, variables)
    }
}

/** Additive only: the campaign tables, row values and existing backup formats are untouched. */
val CAMPAIGN_MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chatgpt_prompts (id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, content TEXT NOT NULL, category TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chatgpt_prompts_name ON chatgpt_prompts(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chatgpt_prompts_category ON chatgpt_prompts(category)")
        db.execSQL("CREATE TABLE IF NOT EXISTS prompt_catalog_state (id TEXT NOT NULL, initializedAt INTEGER NOT NULL, recordCount INTEGER NOT NULL, PRIMARY KEY(id))")
    }
}
