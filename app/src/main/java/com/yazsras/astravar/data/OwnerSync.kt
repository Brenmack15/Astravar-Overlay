package com.yazsras.astravar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated protocol. No default service, public Worker, or campaign upload adapter. */
object OwnerSyncProtocol {
    const val VERSION = 1
    const val MODE = "ASTRAVAR_OWNER_SYNC_V1"
    const val MAX_BODY_BYTES = 65_536
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    fun id(value: String): String = value.also { require(identifier.matches(it)) { "Invalid scoped record ID." } }
    fun origin(value: String): String {
        val u = URI(value.trim())
        val host = u.host?.lowercase() ?: error("A verified HTTPS service origin is required.")
        require(u.scheme == "https" && u.port == -1 && u.rawUserInfo == null && u.rawQuery == null &&
            u.rawFragment == null && (u.rawPath.isNullOrEmpty() || u.rawPath == "/") &&
            host != "localhost" && !host.endsWith(".localhost") && !host.matches(Regex("[0-9.]+")) &&
            !host.contains(":") && host != "astravar-public-disposable.fantasy-layer.workers.dev") {
            "Use a verified HTTPS origin; the anonymous disposable sandbox is forbidden."
        }
        return "https://$host"
    }
    /** Stable recursive key order; integers only for revisions. Wire payload bytes remain unchanged on retry. */
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.toSortedMap().entries.joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> when {
            element is JsonNull || element.isString || element.booleanOrNull != null -> element.toString()
            else -> {
                val number = element.longOrNull ?: error("Sync v1 numeric values must be integers; use a sourced string for decimal notation.")
                require(number in -9_007_199_254_740_991L..9_007_199_254_740_991L)
                number.toString()
            }
        }
    }
    fun hash(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun command(campaign: String, commandId: String, recordId: String, expectedRevision: Long,
                fields: JsonObject, source: String, confirmed: Boolean): String {
        id(campaign); id(recordId)
        require(UUID.fromString(commandId).toString() == commandId) { "Use a canonical lowercase command UUID." }
        require(expectedRevision in 0..9_007_199_254_740_990L && confirmed && source.isNotBlank() && source.length <= 2000)
        require(fields.isNotEmpty() && fields.keys.none { it.startsWith("_") })
        val body = canonical(buildJsonObject {
            put("v", VERSION); put("campaignId", campaign); put("commandId", commandId)
            put("recordId", recordId); put("expectedRevision", expectedRevision)
            put("operation", "replace_confirmed_record"); put("confirmed", true)
            put("source", source); put("fields", fields)
        })
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "Command exceeds its size limit." }
        return body
    }
}

/** Credentials never appear in data-class toString, Room, backup exports, diagnostics, or error messages. */
class OwnerSyncCredential(val credentialId: String, val campaignId: String, val origin: String,
                          val expiresAt: Long, internal val bearer: String) {
    init {
        OwnerSyncProtocol.id(credentialId); OwnerSyncProtocol.id(campaignId)
        require(OwnerSyncProtocol.origin(origin) == origin && expiresAt > 0)
        require(bearer.matches(Regex("[A-Za-z0-9_-]{43,256}"))) { "Invalid device credential." }
    }
    override fun toString() = "OwnerSyncCredential(redacted)"
}
fun interface OwnerSyncCredentialProvider {
    suspend fun credential(origin: String, campaignId: String): OwnerSyncCredential?
}

/** Owner-approved enrollment must be delivered by an authenticated service, never through chat. */
class OwnerSyncCredentialVault(context: Context, private val now: () -> Long = System::currentTimeMillis,
    private val keyProvider: () -> SecretKey = Companion::androidKey) : OwnerSyncCredentialProvider {
    private val preferences = context.applicationContext.getSharedPreferences("astravar-owner-sync-credentials", Context.MODE_PRIVATE)
    companion object {
    private const val ALIAS = "astravar-owner-sync-device-v1"
    private fun androidKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    }
    private fun entry(origin: String, campaign: String) = OwnerSyncProtocol.hash("$origin\n$campaign")
    @Synchronized fun enroll(value: OwnerSyncCredential) {
        require(value.expiresAt > now()) { "Enrollment credential has expired." }
        val body = buildJsonObject {
            put("credentialId", value.credentialId); put("campaignId", value.campaignId)
            put("origin", value.origin); put("expiresAt", value.expiresAt); put("bearer", value.bearer)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
        val name = entry(value.origin, value.campaignId)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(body)
        val blob = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(name, blob).commit()) { "Device credential was not saved." }
        body.fill(0)
    }
    override suspend fun credential(origin: String, campaignId: String): OwnerSyncCredential? = withContext(Dispatchers.IO) {
        read(OwnerSyncProtocol.origin(origin), OwnerSyncProtocol.id(campaignId))
    }
    @Synchronized private fun read(origin: String, campaignId: String): OwnerSyncCredential? {
        val name = entry(origin, campaignId)
        val blob = preferences.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            require(bytes.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(name.toByteArray(Charsets.UTF_8))
            }
            val plaintext = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            val json = Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject
            plaintext.fill(0)
            val value = OwnerSyncCredential(json.getValue("credentialId").jsonPrimitive.content,
                json.getValue("campaignId").jsonPrimitive.content, json.getValue("origin").jsonPrimitive.content,
                json.getValue("expiresAt").jsonPrimitive.long, json.getValue("bearer").jsonPrimitive.content)
            if (value.origin != origin || value.campaignId != campaignId || value.expiresAt <= now()) {
                preferences.edit().remove(name).commit(); null
            } else value
        } catch (_: Exception) {
            // A restored encrypted preference without its hardware-bound key is deliberately unusable.
            preferences.edit().remove(name).commit(); null
        }
    }
    @Synchronized fun forget(origin: String, campaignId: String) {
        check(preferences.edit().remove(entry(OwnerSyncProtocol.origin(origin), OwnerSyncProtocol.id(campaignId))).commit())
    }
    /** Local disconnect is not proof of server revocation. The service must revoke the credential too. */
    @Synchronized fun forgetAll() { check(preferences.edit().clear().commit()) }
}

/** Queue/receipts only, never a second character/inventory authority. */
@Entity(tableName = "owner_sync_outbox", indices = [Index(value = ["origin", "campaignId", "state"])])
data class OwnerSyncQueuedCommand(@PrimaryKey val commandId: String, val origin: String, val campaignId: String,
    val recordId: String, val expectedRevision: Long, val body: String, val fingerprint: String, val createdAt: Long,
    val state: String = "PENDING", val receiptJson: String? = null, val issueCode: String = "")
@Entity(tableName = "owner_sync_cursors", primaryKeys = ["origin", "campaignId"])
data class OwnerSyncCursor(val origin: String, val campaignId: String, val cursor: Long = 0)
@Entity(tableName = "owner_sync_received", primaryKeys = ["origin", "campaignId", "cursor"],
    indices = [Index(value = ["origin", "campaignId", "commandId"], unique = true)])
data class OwnerSyncReceived(val origin: String, val campaignId: String, val cursor: Long, val commandId: String,
    val eventJson: String)
@Dao interface OwnerSyncDao {
    @Query("SELECT * FROM owner_sync_outbox WHERE origin=:origin AND campaignId=:campaign ORDER BY createdAt")
    fun observe(origin: String, campaign: String): Flow<List<OwnerSyncQueuedCommand>>
    @Query("SELECT * FROM owner_sync_outbox WHERE commandId=:id") suspend fun command(id: String): OwnerSyncQueuedCommand?
    @Query("SELECT * FROM owner_sync_outbox WHERE origin=:origin AND campaignId=:campaign AND state='PENDING' ORDER BY createdAt")
    suspend fun pending(origin: String, campaign: String): List<OwnerSyncQueuedCommand>
    @Insert suspend fun insert(value: OwnerSyncQueuedCommand)
    @Update suspend fun update(value: OwnerSyncQueuedCommand)
    @Query("SELECT * FROM owner_sync_cursors WHERE origin=:origin AND campaignId=:campaign")
    suspend fun cursor(origin: String, campaign: String): OwnerSyncCursor?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cursor(value: OwnerSyncCursor)
    @Query("SELECT * FROM owner_sync_received WHERE origin=:origin AND campaignId=:campaign ORDER BY cursor")
    suspend fun received(origin: String, campaign: String): List<OwnerSyncReceived>
    @Query("SELECT * FROM owner_sync_received WHERE origin=:origin AND campaignId=:campaign AND cursor=:cursor")
    suspend fun event(origin: String, campaign: String, cursor: Long): OwnerSyncReceived?
    @Insert suspend fun received(value: OwnerSyncReceived)
}
@Database(entities = [OwnerSyncQueuedCommand::class, OwnerSyncCursor::class, OwnerSyncReceived::class], version = 1, exportSchema = true)
abstract class OwnerSyncDb : RoomDatabase() {
    abstract fun sync(): OwnerSyncDao
    companion object {
        fun open(context: Context): OwnerSyncDb = Room.databaseBuilder(context.applicationContext,
            OwnerSyncDb::class.java, "astravar-owner-sync-queue.db").build()
    }
}

data class OwnerSyncResponse(val status: Int, val json: JsonObject)
fun interface OwnerSyncTransport {
    suspend fun request(origin: String, path: String, credential: OwnerSyncCredential, body: String?): OwnerSyncResponse
}
class OwnerSyncFailure(val code: String) : IOException("Owner synchronization stopped ($code).")

/** HTTPS only; bounded reads, no redirects/cookies, credentials sent only to their enrolled origin. */
class OwnerSyncHttpsTransport : OwnerSyncTransport {
    override suspend fun request(origin: String, path: String, credential: OwnerSyncCredential, body: String?): OwnerSyncResponse = withContext(Dispatchers.IO) {
        val verifiedOrigin = OwnerSyncProtocol.origin(origin)
        require(credential.origin == verifiedOrigin && credential.expiresAt > System.currentTimeMillis())
        require(path.startsWith("/v1/campaigns/${OwnerSyncProtocol.id(credential.campaignId)}/") &&
            !path.contains("..") && !path.contains("#"))
        val connection = URL(verifiedOrigin + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000; connection.readTimeout = 8_000
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${credential.bearer}")
        connection.setRequestProperty("X-Astravar-Credential", credential.credentialId)
        connection.setRequestProperty("Cache-Control", "no-store")
        try {
            body?.let {
                val bytes = it.toByteArray(Charsets.UTF_8)
                require(bytes.size <= OwnerSyncProtocol.MAX_BODY_BYTES)
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw OwnerSyncFailure("REDIRECT_DENIED")
            if (connection.contentType?.substringBefore(';')?.trim() != "application/json") throw OwnerSyncFailure("NON_JSON_RESPONSE")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (result.size() + count > 1_048_576) throw OwnerSyncFailure("RESPONSE_TOO_LARGE")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } ?: throw OwnerSyncFailure("EMPTY_RESPONSE")
            OwnerSyncResponse(status, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        } finally { connection.disconnect() }
    }
}

/** No service is enrolled and real campaign activation is intentionally disabled in this candidate. */
class OwnerSyncRepository(private val db: OwnerSyncDb, private val credentials: OwnerSyncCredentialProvider,
    private val transport: OwnerSyncTransport = OwnerSyncHttpsTransport(), private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex(); private val dao = db.sync()
    private fun scope(origin: String, campaign: String): String {
        OwnerSyncProtocol.id(campaign)
        check(campaign.startsWith("synthetic-")) { "Real campaign synchronization requires the separate security and cutover approval gate." }
        return OwnerSyncProtocol.origin(origin)
    }
    fun commands(origin: String, campaign: String) = dao.observe(scope(origin, campaign), campaign)
    suspend fun queue(origin: String, campaign: String, recordId: String, expectedRevision: Long,
                      fields: JsonObject, source: String, confirmed: Boolean, commandId: String = UUID.randomUUID().toString()): OwnerSyncQueuedCommand = lock.withLock {
        val endpoint = scope(origin, campaign)
        val body = OwnerSyncProtocol.command(campaign, commandId, recordId, expectedRevision, fields, source, confirmed)
        val fingerprint = OwnerSyncProtocol.hash(body)
        db.withTransaction {
            dao.command(commandId)?.let {
                check(it.origin == endpoint && it.campaignId == campaign && it.fingerprint == fingerprint) { "Command ID was reused with different content." }
                return@withTransaction it
            }
            val command = OwnerSyncQueuedCommand(commandId, endpoint, campaign, recordId, expectedRevision, body, fingerprint, now())
            dao.insert(command); command
        }
    }
    private suspend fun credential(origin: String, campaign: String): OwnerSyncCredential {
        val value = credentials.credential(origin, campaign) ?: throw OwnerSyncFailure("AUTHENTICATION_REQUIRED")
        if (value.origin != origin || value.campaignId != campaign || value.expiresAt <= now()) throw OwnerSyncFailure("CREDENTIAL_SCOPE_OR_EXPIRY")
        return value
    }
    private fun response(value: OwnerSyncResponse, campaign: String): JsonObject {
        if (value.status !in 200..299) {
            val remoteCode = value.json["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            val code = when (value.status) { 401 -> "CREDENTIAL_REVOKED_OR_EXPIRED"; 403 -> "ACCESS_DENIED"
                409 -> if (remoteCode == "COMMAND_ID_REUSED") "COMMAND_ID_REUSED" else "REVISION_CONFLICT"
                429 -> "QUOTA_REACHED"; else -> "REMOTE_UNAVAILABLE" }
            throw OwnerSyncFailure(code)
        }
        val data = value.json
        require(data["mode"]?.jsonPrimitive?.content == OwnerSyncProtocol.MODE &&
            data["campaignId"]?.jsonPrimitive?.content == campaign) { "Unexpected synchronization service or campaign." }
        return data
    }
    /** Retries use the original body and ID. A timeout leaves PENDING; conflicts never silently rebase. */
    suspend fun sendPending(origin: String, campaign: String) = lock.withLock {
        val endpoint = scope(origin, campaign)
        for (command in dao.pending(endpoint, campaign)) {
            try {
                val persisted = Json.parseToJsonElement(command.body).jsonObject
                require(OwnerSyncProtocol.command(campaign, command.commandId, command.recordId,
                    command.expectedRevision, persisted.getValue("fields").jsonObject,
                    persisted.getValue("source").jsonPrimitive.content, persisted.getValue("confirmed").jsonPrimitive.boolean) == command.body &&
                    OwnerSyncProtocol.hash(command.body) == command.fingerprint) { "Persisted command integrity check failed." }
                val value = response(transport.request(endpoint, "/v1/campaigns/$campaign/commands", credential(endpoint, campaign), command.body), campaign)
                val receipt = value.getValue("receipt").jsonObject
                require(receipt.getValue("commandId").jsonPrimitive.content == command.commandId &&
                    receipt.getValue("fingerprint").jsonPrimitive.content == command.fingerprint &&
                    receipt.getValue("recordId").jsonPrimitive.content == command.recordId &&
                    receipt.getValue("priorRevision").jsonPrimitive.long == command.expectedRevision &&
                    receipt.getValue("resultingRevision").jsonPrimitive.long == command.expectedRevision + 1 &&
                    receipt.getValue("cursor").jsonPrimitive.long > 0 &&
                    UUID.fromString(receipt.getValue("eventId").jsonPrimitive.content).toString() == receipt.getValue("eventId").jsonPrimitive.content)
                val receiptJson = OwnerSyncProtocol.canonical(receipt)
                dao.update(command.copy(state = "REMOTE_VERIFIED", receiptJson = receiptJson, issueCode = ""))
            } catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) {
                val terminal = e.code in setOf("REVISION_CONFLICT", "COMMAND_ID_REUSED", "ACCESS_DENIED", "CREDENTIAL_REVOKED_OR_EXPIRED")
                dao.update(command.copy(state = if (terminal) "BLOCKED" else "PENDING", issueCode = e.code))
                throw e
            } catch (e: Exception) {
                dao.update(command.copy(issueCode = "UNVERIFIED_RESPONSE_OR_NETWORK")); throw e
            }
        }
    }
    /** Explicit retry after owner reviews restored credentials; conflicts require a new resolved command. */
    suspend fun resumeAfterAccessRestored(commandId: String) = lock.withLock {
        val command = dao.command(commandId) ?: error("Unknown command.")
        scope(command.origin, command.campaignId)
        require(command.state == "BLOCKED" && command.issueCode in setOf("ACCESS_DENIED", "CREDENTIAL_REVOKED_OR_EXPIRED"))
        credential(command.origin, command.campaignId)
        dao.update(command.copy(state = "PENDING", issueCode = ""))
    }
    /** Durable inbox/cursor is staging only; no automatic mutation of CampaignLedgerDb or combat records. */
    suspend fun refresh(origin: String, campaign: String): List<OwnerSyncReceived> = lock.withLock {
        val endpoint = scope(origin, campaign)
        repeat(100) {
            val cursor = dao.cursor(endpoint, campaign)?.cursor ?: 0
            val value = response(transport.request(endpoint, "/v1/campaigns/$campaign/changes?after=$cursor&limit=50", credential(endpoint, campaign), null), campaign)
            val events = value.getValue("events").jsonArray
            val current = value.getValue("currentCursor").jsonPrimitive.long
            val next = value.getValue("nextCursor").jsonPrimitive.long
            val more = value.getValue("hasMore").jsonPrimitive.boolean
            var expected = cursor
            val rows = events.map { event ->
                val data = event.jsonObject
                val position = data.getValue("cursor").jsonPrimitive.long
                require(position == ++expected && data.getValue("campaignId").jsonPrimitive.content == campaign)
                val commandId = data.getValue("commandId").jsonPrimitive.content
                require(UUID.fromString(commandId).toString() == commandId)
                val prior = data.getValue("priorRevision").jsonPrimitive.long
                require(data.getValue("resultingRevision").jsonPrimitive.long == prior + 1)
                val eventId = data.getValue("eventId").jsonPrimitive.content
                require(UUID.fromString(eventId).toString() == eventId)
                val original = OwnerSyncProtocol.command(campaign, commandId, data.getValue("recordId").jsonPrimitive.content,
                    prior, data.getValue("fields").jsonObject, data.getValue("source").jsonPrimitive.content,
                    data.getValue("confirmed").jsonPrimitive.boolean)
                require(data.getValue("fingerprint").jsonPrimitive.content == OwnerSyncProtocol.hash(original)) { "Remote event integrity check failed." }
                dao.command(commandId)?.receiptJson?.let { persisted ->
                    val receipt = Json.parseToJsonElement(persisted).jsonObject
                    receipt.forEach { (key, value) -> require(data[key] == value) { "Receipt/readback mismatch." } }
                }
                OwnerSyncReceived(endpoint, campaign, position,
                    commandId, OwnerSyncProtocol.canonical(data))
            }
            require(next == expected && next <= current && (!more || rows.isNotEmpty()) && (more || next == current)) { "Synchronization history gap; cursor retained." }
            db.withTransaction {
                rows.forEach { row ->
                    val old = dao.event(endpoint, campaign, row.cursor)
                    if (old == null) dao.received(row) else require(old == row) { "Immutable remote event changed." }
                }
                dao.cursor(OwnerSyncCursor(endpoint, campaign, next))
            }
            if (!more) return@withLock dao.received(endpoint, campaign)
        }
        throw OwnerSyncFailure("PAGE_LIMIT_RETRY")
    }
}
