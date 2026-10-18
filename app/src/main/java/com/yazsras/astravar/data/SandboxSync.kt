package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Deliberately separate public FICTIONAL store. This file never accepts a campaign item or actor. */
object PublicSandbox {
    const val DEFAULT_ORIGIN = "https://astravar-public-disposable.fantasy-layer.workers.dev"
    const val CAMPAIGN = "astravar-public-disposable"
    const val CONNECTION = "sandbox-merchant-0001"
    const val MODE = "PUBLIC_DISPOSABLE_SANDBOX"
    const val BASE = "/api/v1/campaigns/$CAMPAIGN/connections/$CONNECTION"
    val templates = linkedMapOf(
        "new_fact" to "Disposable Test Merchant recorded a fictional Astravar Android connectivity test.",
        "agreement" to "Disposable Test Merchant agrees to exchange fictional test notes with the Android sandbox.",
        "shared_interest" to "Disposable Test Merchant and the Android sandbox share an interest in fictional clockwork."
    )
    fun origin(value: String): String {
        val u = URI(value.trim())
        require(u.scheme == "https" && u.host?.endsWith(".workers.dev") == true && u.port == -1 &&
            u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
            (u.rawPath.isNullOrEmpty() || u.rawPath == "/")) {
            "Use an HTTPS workers.dev origin only, without path, credentials, query or port."
        }
        return "https://${u.host.lowercase()}"
    }
    fun fingerprint(kind: String, detail: String, revision: Long): String {
        // The deployed v1 API canonical field order. Never alter a persisted command on retry.
        val canonical = "{\"v\":1,\"campaignId\":\"$CAMPAIGN\",\"connectionId\":\"$CONNECTION\",\"kind\":" +
            JSONObject.quote(kind) + ",\"detail\":" + JSONObject.quote(detail) +
            ",\"expectedRevision\":$revision,\"confirmed\":true}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Entity(tableName = "public_sandbox_settings")
data class SandboxSettings(@PrimaryKey val id: Int = 1, val origin: String = PublicSandbox.DEFAULT_ORIGIN)

@Entity(tableName = "public_sandbox_endpoints")
data class SandboxEndpoint(
    @PrimaryKey val origin: String,
    val cursor: Long = 1,
    val remoteRevision: Long? = null,
    val name: String = "Disposable Test Merchant",
    val lastSyncedAt: Long? = null,
    val status: String = "Not connected · synthetic sandbox only"
)

@Entity(tableName = "public_sandbox_outbox", indices = [Index("origin")])
data class SandboxCommand(
    @PrimaryKey val commandId: String,
    val origin: String,
    val expectedRevision: Long,
    val kind: String,
    val detail: String,
    val body: String,
    val fingerprint: String,
    val createdAt: Long,
    val status: String = "PENDING",
    val receiptJson: String? = null,
    val lastError: String = ""
)

@Entity(tableName = "public_sandbox_events", primaryKeys = ["origin", "eventId"],
    indices = [Index(value = ["origin", "resultingRevision"], unique = true)])
data class SandboxEvent(
    val origin: String, val eventId: String, val commandId: String, val kind: String,
    val detail: String, val priorRevision: Long, val resultingRevision: Long, val recordedAt: String
)

@Dao
interface SandboxDao {
    @Query("SELECT * FROM public_sandbox_settings WHERE id=1") fun settings(): Flow<SandboxSettings?>
    @Query("SELECT * FROM public_sandbox_settings WHERE id=1") suspend fun getSettings(): SandboxSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun settings(value: SandboxSettings)
    @Query("SELECT * FROM public_sandbox_endpoints WHERE origin=:origin") fun endpoint(origin: String): Flow<SandboxEndpoint?>
    @Query("SELECT * FROM public_sandbox_endpoints WHERE origin=:origin") suspend fun getEndpoint(origin: String): SandboxEndpoint?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun endpoint(value: SandboxEndpoint)
    @Query("SELECT * FROM public_sandbox_outbox WHERE origin=:origin ORDER BY createdAt DESC") fun commands(origin: String): Flow<List<SandboxCommand>>
    @Query("SELECT * FROM public_sandbox_outbox WHERE origin=:origin AND status='PENDING' ORDER BY createdAt") suspend fun pending(origin: String): List<SandboxCommand>
    @Query("SELECT * FROM public_sandbox_outbox WHERE commandId=:id") suspend fun command(id: String): SandboxCommand?
    @Insert suspend fun insert(value: SandboxCommand)
    @Update suspend fun update(value: SandboxCommand)
    @Query("SELECT * FROM public_sandbox_events WHERE origin=:origin ORDER BY resultingRevision DESC") fun events(origin: String): Flow<List<SandboxEvent>>
    @Query("SELECT * FROM public_sandbox_events WHERE origin=:origin AND eventId=:id") suspend fun event(origin: String, id: String): SandboxEvent?
    @Insert suspend fun event(value: SandboxEvent)
}

@Database(entities = [SandboxSettings::class, SandboxEndpoint::class, SandboxCommand::class, SandboxEvent::class], version = 1, exportSchema = true)
abstract class SandboxSyncDb : RoomDatabase() {
    abstract fun sandbox(): SandboxDao
    companion object {
        fun open(context: Context): SandboxSyncDb = Room.databaseBuilder(context.applicationContext,
            SandboxSyncDb::class.java, "astravar-public-sandbox.db").build()
    }
}

data class SandboxHttpResponse(val status: Int, val json: JSONObject)
fun interface SandboxTransport {
    suspend fun request(origin: String, path: String, body: String?): SandboxHttpResponse
}
class SandboxFailure(val status: Int, val code: String, message: String) : IOException(message)

/** Bounded native HTTPS; never follows redirects or sends cookies, credentials, or arbitrary headers. */
class SandboxHttpTransport : SandboxTransport {
    override suspend fun request(origin: String, path: String, body: String?): SandboxHttpResponse = withContext(Dispatchers.IO) {
        val request = URL(PublicSandbox.origin(origin) + path).openConnection() as HttpURLConnection
        request.requestMethod = if (body == null) "GET" else "POST"
        request.instanceFollowRedirects = false
        request.connectTimeout = 8000
        request.readTimeout = 8000
        request.setRequestProperty("Accept", "application/json")
        request.setRequestProperty("User-Agent", "AstravarOverlaySandbox/0.1")
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 8192) { "Sandbox request exceeds its limit." }
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/json")
                request.setFixedLengthStreamingMode(bytes.size)
                request.outputStream.use { it.write(bytes) }
            }
            val status = request.responseCode
            if (request.contentType?.startsWith("application/json") != true) {
                throw SandboxFailure(status, "EDGE_OR_NON_JSON", "Hosting edge returned a non-JSON response; reconnect later.")
            }
            val stream = if (status in 200..299) request.inputStream else request.errorStream
            val text = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 262144) { "Sandbox response exceeded its limit." }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            } ?: throw SandboxFailure(status, "EMPTY_RESPONSE", "Empty sandbox response.")
            SandboxHttpResponse(status, JSONObject(text))
        } finally { request.disconnect() }
    }
}

/** Outbox and cursor belong ONLY to the public disposable API, never CampaignLedgerRepository. */
class SandboxSyncRepository(private val db: SandboxSyncDb, private val transport: SandboxTransport = SandboxHttpTransport()) {
    private val dao = db.sandbox()
    private val lock = Mutex()
    val settings = dao.settings()
    fun endpoint(origin: String) = dao.endpoint(origin)
    fun commands(origin: String) = dao.commands(origin)
    fun events(origin: String) = dao.events(origin)

    suspend fun configure(value: String) = lock.withLock {
        val origin = PublicSandbox.origin(value)
        db.withTransaction {
            if (dao.getEndpoint(origin) == null) dao.endpoint(SandboxEndpoint(origin))
            dao.settings(SandboxSettings(origin = origin))
        }
    }
    private suspend fun selected(): String = dao.getSettings()?.origin ?: PublicSandbox.DEFAULT_ORIGIN
    private suspend fun endpointValue(origin: String) = dao.getEndpoint(origin) ?: SandboxEndpoint(origin)
    private suspend fun status(origin: String, message: String) {
        dao.endpoint(endpointValue(origin).copy(status = message.take(500)))
    }
    private suspend fun request(origin: String, path: String, body: String? = null): JSONObject {
        val response = transport.request(origin, path, body)
        val value = response.json
        require(value.optString("mode") == PublicSandbox.MODE) { "Unexpected service mode; sandbox stopped." }
        if (response.status !in 200..299) {
            val error = value.optJSONObject("error")
            throw SandboxFailure(response.status, error?.optString("code") ?: "HTTP_ERROR",
                error?.optString("message") ?: "Sandbox request failed.")
        }
        return value
    }
    private fun scope(value: JSONObject) {
        require(value.getString("campaignId") == PublicSandbox.CAMPAIGN &&
            value.getString("connectionId") == PublicSandbox.CONNECTION) { "Unexpected sandbox scope." }
    }
    private suspend fun refresh(origin: String) {
        val health = request(origin, "/health")
        require(health.optBoolean("disposable") && health.optString("authentication") == "NONE" &&
            !health.optBoolean("realCampaignAccess", true)) { "Service is not the disposable public sandbox." }
        val connection = request(origin, PublicSandbox.BASE)
        scope(connection)
        require(connection.optBoolean("disposable"))
        val revision = connection.getLong("revision")
        require(revision in 1..9_007_199_254_740_990L)
        dao.endpoint(endpointValue(origin).copy(remoteRevision = revision, name = connection.getString("name")))
        // Process pages, never skip ahead to the freshly read remote revision.
        repeat(25) {
            val before = endpointValue(origin)
            val page = request(origin, PublicSandbox.BASE + "/changes?afterRevision=${before.cursor}&limit=50")
            scope(page)
            val current = page.getLong("currentRevision")
            val cursor = page.getLong("nextCursor")
            val more = page.getBoolean("hasMore")
            val array = page.getJSONArray("milestones")
            val entries = (0 until array.length()).map { index ->
                val event = array.getJSONObject(index)
                SandboxEvent(origin, UUID.fromString(event.getString("eventId")).toString(),
                    UUID.fromString(event.getString("commandId")).toString(), event.getString("kind"),
                    event.getString("detail"), event.getLong("priorRevision"), event.getLong("resultingRevision"), event.getString("recordedAt"))
            }
            var expected = before.cursor
            entries.forEach { event ->
                require(event.priorRevision == expected && event.resultingRevision == expected + 1) { "History gap; cursor was preserved." }
                expected = event.resultingRevision
            }
            require(cursor == expected && cursor <= current && (!more || entries.isNotEmpty()) && (more || cursor == current)) { "Invalid sync cursor." }
            db.withTransaction {
                entries.forEach { event ->
                    val old = dao.event(origin, event.eventId)
                    if (old == null) dao.event(event) else require(old == event) { "Immutable event changed." }
                }
                dao.endpoint(endpointValue(origin).copy(cursor = cursor, remoteRevision = current,
                    lastSyncedAt = System.currentTimeMillis(), status = if (more) "Reading next history page…" else "Remote read verified · fictional sandbox"))
            }
            if (!more) return
        }
        error("History page limit reached; retry continues from the durable cursor.")
    }
    suspend fun refresh() = lock.withLock {
        val origin = selected()
        try { refresh(origin) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status(origin, "Disconnected / sync paused: ${e.message}"); throw e }
    }
    /** Fixed templates prevent accidentally pasting real campaign text into a public service. */
    suspend fun queueSynthetic(kind: String): SandboxCommand = lock.withLock {
        val detail = PublicSandbox.templates[kind] ?: error("Choose a fictional test template.")
        val origin = selected()
        db.withTransaction {
            check(dao.pending(origin).isEmpty()) { "Recover the existing pending command first." }
            val revision = endpointValue(origin).remoteRevision ?: error("Read the sandbox before confirming a test.")
            val id = UUID.randomUUID().toString()
            val body = JSONObject().put("campaignId", PublicSandbox.CAMPAIGN).put("connectionId", PublicSandbox.CONNECTION)
                .put("kind", kind).put("detail", detail).put("commandId", id).put("expectedRevision", revision).put("confirmed", true).toString()
            val command = SandboxCommand(id, origin, revision, kind, detail, body,
                PublicSandbox.fingerprint(kind, detail, revision), System.currentTimeMillis())
            dao.insert(command)
            status(origin, "Saved locally · fictional command pending remote verification")
            command
        }
    }
    private suspend fun committed(command: SandboxCommand, receipt: JSONObject) {
        scope(receipt)
        require(receipt.getString("status") == "COMMITTED" && receipt.getString("commandId") == command.commandId &&
            receipt.getString("payloadFingerprint") == command.fingerprint && receipt.getLong("priorRevision") == command.expectedRevision &&
            receipt.getLong("resultingRevision") == command.expectedRevision + 1) { "Receipt did not match the durable command; outcome remains pending." }
        UUID.fromString(receipt.getString("eventId"))
        require(receipt.getString("recordedAt").isNotBlank())
        dao.update(command.copy(status = "COMMITTED", receiptJson = receipt.toString(), lastError = ""))
    }
    private fun validateDurableCommand(command: SandboxCommand) {
        // Defense in depth: corrupted/imported rows cannot turn this public outbox into a campaign uploader.
        val value = JSONObject(command.body)
        val keys = value.keys().asSequence().toSet()
        require(keys == setOf("campaignId", "connectionId", "kind", "detail", "commandId", "expectedRevision", "confirmed"))
        scope(value)
        require(value.getString("commandId") == command.commandId &&
            UUID.fromString(command.commandId).toString() == command.commandId &&
            value.getLong("expectedRevision") == command.expectedRevision &&
            command.expectedRevision in 1..9_007_199_254_740_990L && value.getBoolean("confirmed") &&
            value.getString("kind") == command.kind && value.getString("detail") == command.detail &&
            PublicSandbox.templates[command.kind] == command.detail &&
            command.fingerprint == PublicSandbox.fingerprint(command.kind, command.detail, command.expectedRevision) &&
            PublicSandbox.origin(command.origin) == command.origin) { "Invalid durable synthetic command; nothing was sent." }
    }
    /** Receipt lookup comes first after EVERY uncertain outcome; original UUID/body remain untouched. */
    suspend fun retryPending() = lock.withLock {
        val origin = selected()
        for (command in dao.pending(origin)) {
            try {
                validateDurableCommand(command)
                val recovered = try {
                    request(origin, "/api/v1/campaigns/${PublicSandbox.CAMPAIGN}/commands/${command.commandId}/receipt")
                } catch (e: SandboxFailure) {
                    if (e.status == 404 && e.code == "COMMAND_NOT_FOUND") null else throw e
                }
                committed(command, recovered ?: request(origin, PublicSandbox.BASE + "/milestones", command.body))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val definitive = e is SandboxFailure && (e.status in listOf(400, 403, 409, 413, 415))
                dao.update(command.copy(status = if (definitive) "REJECTED" else "PENDING", lastError = (e.message ?: "Outcome unknown").take(500)))
                status(origin, if (definitive) "Rejected · read latest state and review before a NEW fictional command: ${e.message}"
                    else "Offline / outcome unknown · pending UUID preserved. Retry later: ${e.message}")
                throw e
            }
        }
        try { refresh(origin) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status(origin, "Receipt stored; history refresh paused: ${e.message}"); throw e }
    }
}
