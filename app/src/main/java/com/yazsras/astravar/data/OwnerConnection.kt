package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

object OwnerConnectionProtocol {
    const val MODE = "ASTRAVAR_OWNER_BRIDGE_V1"
    const val CAMPAIGN = "synthetic-owner-acceptance"
    const val PROFILE = "owner-sync-fixture"
    const val RECORD = "owner-sync-fixture-int"
    const val SOURCE = "Player-created fictional owner connection fixture"
    fun localCommand(origin: String, campaign: String, commandId: String): String = UUID.nameUUIDFromBytes(
        "astravar-owner-approval-v1:$origin:$campaign:$commandId".toByteArray(Charsets.UTF_8)).toString()
    fun proposalFingerprint(campaign: String, value: JsonObject): String = OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(buildJsonObject {
        put("campaignId", campaign); put("commandId", value.getValue("commandId")); put("recordId", value.getValue("recordId"))
        put("expectedRevision", value.getValue("expectedRevision")); put("fields", value.getValue("fields")); put("source", value.getValue("source"))
    }))
}

@Entity(tableName = "owner_connection_settings")
data class OwnerConnectionSettings(@PrimaryKey val id: Int = 1, val origin: String = "", val campaignId: String = OwnerConnectionProtocol.CAMPAIGN,
    val deviceName: String = "", val deviceId: String = "", val accountLabel: String = "", val scopesJson: String = "[]",
    val deviceCode: String = "", val userCode: String = "", val verificationUri: String = "", val pairingExpiresAt: Long = 0,
    val lastSyncAt: Long = 0, val status: String = "Not paired", val publishBody: String? = null, val published: Boolean = false,
    val revokePending: Boolean = false, val remoteRevision: Long = 0, val remoteFieldsJson: String? = null)
@Entity(tableName = "owner_connection_proposals")
data class OwnerConnectionProposal(@PrimaryKey val commandId: String, val origin: String, val campaignId: String,
    val recordId: String, val expectedRevision: Long, val proposedScore: Int, val source: String, val fingerprint: String,
    val body: String, val receivedAt: Long, val currentScore: Int? = null, val currentRevision: Long = 0,
    val state: String = "PENDING", val message: String = "Awaiting your approval", val approvedAbilityJson: String? = null,
    val decisionBody: String? = null, val receiptJson: String? = null)
@Entity(tableName = "owner_connection_publications")
data class OwnerConnectionPublication(@PrimaryKey val commandId: String, val origin: String, val campaignId: String,
    val body: String, val state: String = "PENDING", val receiptJson: String? = null)
@Dao interface OwnerConnectionDao {
    @Query("SELECT * FROM owner_connection_settings WHERE id=1") suspend fun settings(): OwnerConnectionSettings?
    @Upsert suspend fun settings(value: OwnerConnectionSettings)
    @Query("SELECT * FROM owner_connection_proposals ORDER BY receivedAt,commandId") suspend fun proposals(): List<OwnerConnectionProposal>
    @Query("SELECT * FROM owner_connection_proposals WHERE commandId=:id") suspend fun proposal(id: String): OwnerConnectionProposal?
    @Insert suspend fun insert(value: OwnerConnectionProposal)
    @Update suspend fun update(value: OwnerConnectionProposal)
    @Query("SELECT * FROM owner_connection_publications ORDER BY commandId") suspend fun publications(): List<OwnerConnectionPublication>
    @Query("SELECT * FROM owner_connection_publications WHERE commandId=:id") suspend fun publication(id: String): OwnerConnectionPublication?
    @Insert suspend fun publication(value: OwnerConnectionPublication)
    @Update suspend fun update(value: OwnerConnectionPublication)
}
/** Approval journal stays outside campaign authority; CampaignRecords' immutable receipt closes each crash window. */
@Database(entities = [OwnerConnectionSettings::class, OwnerConnectionProposal::class, OwnerConnectionPublication::class], version = 1, exportSchema = true)
abstract class OwnerConnectionDb : RoomDatabase() {
    abstract fun connection(): OwnerConnectionDao
    companion object { fun open(context: Context): OwnerConnectionDb = Room.databaseBuilder(context.applicationContext,
        OwnerConnectionDb::class.java, "astravar-owner-connection.db").build() }
}

fun interface OwnerConnectionTransport {
    suspend fun request(origin: String, path: String, credential: OwnerSyncCredential?, serviceAccess: String?, body: String?): OwnerSyncResponse
}
/** Device credentials and optional Site credentials are sent only to the enrolled, HTTPS origin. */
class OwnerConnectionHttpsTransport : OwnerConnectionTransport {
    override suspend fun request(origin: String, path: String, credential: OwnerSyncCredential?, serviceAccess: String?, body: String?): OwnerSyncResponse = withContext(Dispatchers.IO) {
        val endpoint = OwnerSyncProtocol.origin(origin)
        require(path.matches(Regex("/v1/[A-Za-z0-9_./-]+")) && !path.contains(".."))
        credential?.let { require(it.origin == endpoint && it.expiresAt > System.currentTimeMillis()) }
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 8000; connection.readTimeout = 8000
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Cache-Control", "no-store")
        credential?.let { connection.setRequestProperty("Authorization", "Bearer ${it.bearer}"); connection.setRequestProperty("X-Astravar-Credential", it.credentialId) }
        serviceAccess?.let { require(it.none { c -> c == '\r' || c == '\n' }); connection.setRequestProperty("OAI-Sites-Authorization", "Bearer $it") }
        try {
            body?.let { val bytes = it.toByteArray(Charsets.UTF_8); require(bytes.size <= OwnerSyncProtocol.MAX_BODY_BYTES)
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { out -> out.write(bytes) } }
            val status = connection.responseCode
            if (status in 300..399) throw OwnerSyncFailure("REDIRECT_DENIED")
            if (connection.contentType?.substringBefore(';')?.trim() != "application/json") throw OwnerSyncFailure("NON_JSON_RESPONSE")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input -> val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (out.size() + count > 1_048_576) throw OwnerSyncFailure("RESPONSE_TOO_LARGE"); out.write(buffer, 0, count) }; out.toByteArray() }
                ?: throw OwnerSyncFailure("EMPTY_RESPONSE")
            OwnerSyncResponse(status, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        } finally { connection.disconnect() }
    }
}

/** Real native adapter, deliberately restricted to one opt-in fictional ability until live acceptance is authorized. */
class OwnerConnectionClient internal constructor(private val context: Context, private val db: OwnerConnectionDb,
    private val campaignDb: CampaignLedgerDb, private val vault: OwnerSyncCredentialVault,
    private val transport: OwnerConnectionTransport, private val now: () -> Long = System::currentTimeMillis,
    private val afterLocalCommit: suspend () -> Unit = {},
    private val bootstrap: OwnerConnectionBootstrap = OwnerConnectionBootstrap(context)) {
    constructor(context: Context) : this(context.applicationContext, OwnerConnectionDb.open(context), CampaignLedgerDb.open(context),
        OwnerSyncCredentialVault(context), OwnerConnectionHttpsTransport(), System::currentTimeMillis, {})
    private val dao = db.connection(); private val records = CampaignRecordsRepository(campaignDb)
    companion object { private val lock = Mutex() }
    private suspend fun setting() = dao.settings() ?: OwnerConnectionSettings()
    private fun checked(value: OwnerConnectionSettings): OwnerConnectionSettings = value.also {
        OwnerSyncProtocol.origin(it.origin); require(it.campaignId == OwnerConnectionProtocol.CAMPAIGN) { "Live campaign acceptance has not been authorized." }
    }
    private suspend fun ability() = records.characterAbilities.first().firstOrNull { it.profileId == OwnerConnectionProtocol.PROFILE && it.key == "INT" }
    private suspend fun credential(value: OwnerConnectionSettings): OwnerSyncCredential =
        (vault.credential(value.origin, value.campaignId) ?: throw OwnerSyncFailure("AUTHENTICATION_REQUIRED")).also {
            if (it.credentialId == "pairing-pending") throw OwnerSyncFailure("PAIRING_PENDING") }
    private suspend fun request(value: OwnerConnectionSettings, path: String, body: String? = null, authenticated: Boolean = true): JsonObject {
        checked(value)
        val response = transport.request(value.origin, path, if (authenticated) credential(value) else null,
            vault.serviceAccess(value.origin, value.campaignId), body)
        if (response.status !in 200..299) {
            val code = response.json["error"]?.let { (it as? JsonObject)?.get("code")?.jsonPrimitive?.content }
            if (authenticated && response.status == 401 && code == "CREDENTIAL_REVOKED_OR_EXPIRED" &&
                response.json["mode"]?.jsonPrimitive?.content == OwnerConnectionProtocol.MODE) {
                vault.forget(value.origin, value.campaignId)
                dao.settings(setting().copy(deviceId = "", status = "Server rejected expired or revoked device access · pair again"))
                OwnerConnectionSyncJob.cancel(context)
            }
            throw OwnerSyncFailure(when (response.status) {
                401 -> "CREDENTIAL_REVOKED_OR_EXPIRED"; 403 -> "ACCESS_DENIED"; 429 -> "QUOTA_REACHED"
                409 -> if (code == "COMMAND_ID_REUSED") "COMMAND_ID_REUSED" else "REVISION_CONFLICT"
                in 500..599 -> "REMOTE_UNAVAILABLE"
                else -> code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "INVALID_RESPONSE"
            })
        }
        require(response.json["mode"]?.jsonPrimitive?.content == OwnerConnectionProtocol.MODE)
        require(response.json["campaignId"]?.jsonPrimitive?.content == value.campaignId)
        return response.json
    }
    suspend fun snapshot(): JsonObject = lock.withLock { snapshotUnlocked() }
    private suspend fun snapshotUnlocked(): JsonObject {
        val value = setting(); val secret = if (value.origin.isBlank()) null else vault.credential(value.origin, value.campaignId)
        val current = ability(); val publications = dao.publications(); val proposals = dao.proposals().filter { it.origin == value.origin && it.campaignId == value.campaignId }
        return buildJsonObject {
            put("origin", value.origin); put("campaignId", value.campaignId); put("deviceName", value.deviceName); put("deviceId", value.deviceId)
            put("connected", secret != null && secret.credentialId != "pairing-pending"); put("accountLabel", value.accountLabel)
            put("scopes", Json.parseToJsonElement(value.scopesJson)); put("lastSyncAt", value.lastSyncAt); put("status", value.status)
            put("published", value.published); put("revokePending", value.revokePending)
            put("fixture", buildJsonObject { put("score", current?.score?.let(::JsonPrimitive) ?: JsonNull); put("revision", current?.revision ?: 0); put("remoteRevision", value.remoteRevision); put("remoteScore", value.remoteFieldsJson?.let { Json.parseToJsonElement(it).jsonObject["score"] } ?: JsonNull); put("published", value.published)
                put("synchronized", current != null && current.revision == value.remoteRevision && value.remoteFieldsJson == OwnerSyncProtocol.canonical(abilityFields(current))) })
            put("pairing", buildJsonObject { put("userCode", value.userCode); put("verificationUri", value.verificationUri); put("expiresAt", value.pairingExpiresAt) })
            put("pending", buildJsonArray { proposals.filter { it.state in setOf("PENDING", "CONFLICT", "APPROVING", "APPLIED_PENDING", "REJECTED_PENDING") }.forEach { p -> add(buildJsonObject {
                put("commandId", p.commandId); put("recordId", p.recordId); put("expectedRevision", p.expectedRevision); put("source", p.source)
                put("currentScore", current?.score?.let(::JsonPrimitive) ?: JsonNull); put("currentRevision", current?.revision ?: 0)
                put("proposedScore", p.proposedScore); put("field", "INT score"); put("ability", "Intelligence"); put("state", p.state); put("message", p.message); put("decisionQueued", p.state in setOf("APPROVING", "APPLIED_PENDING", "REJECTED_PENDING"))
            }) } })
            put("history", buildJsonArray {
                publications.filter { (it.origin == value.origin && it.campaignId == value.campaignId) || it.state == "REPAIR_REVIEW" }
                    .sortedByDescending { it.state == "REPAIR_REVIEW" }.take(50).forEach { p -> add(buildJsonObject {
                    put("commandId", p.commandId); put("state", p.state); put("origin", p.origin); put("receiptJson", p.receiptJson?.let(::JsonPrimitive) ?: JsonNull)
                }) }
                proposals.filter { it.state != "PENDING" }.reversed().take(50).forEach { p -> add(buildJsonObject {
                put("commandId", p.commandId); put("state", p.state); put("receiptJson", p.receiptJson?.let(::JsonPrimitive) ?: JsonNull)
                p.receiptJson?.let { put("resultingRevision", Json.parseToJsonElement(it).jsonObject.getValue("resultingRevision")) }
            }) } })
        }
    }
    suspend fun beginPairing(origin: String, campaign: String, deviceName: String): JsonObject = lock.withLock {
        require(campaign == OwnerConnectionProtocol.CAMPAIGN && deviceName.isNotBlank() && deviceName.length <= 120)
        val old = setting(); if (old.origin.isNotBlank()) require(vault.credential(old.origin, old.campaignId)?.credentialId.let { it == null || it == "pairing-pending" }) { "Revoke or disconnect the current device before pairing again." }
        val value = OwnerConnectionSettings(origin = OwnerSyncProtocol.origin(origin), campaignId = campaign, deviceName = deviceName)
        val secret = android.util.Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val body = OwnerSyncProtocol.canonical(buildJsonObject { put("campaignId", campaign); put("deviceName", deviceName); put("deviceSecretHash", OwnerSyncProtocol.hash(secret)) })
        val response = request(value, "/v1/pairings", body, false)
        val expiry = response.getValue("expiresAt").jsonPrimitive.long
        vault.enroll(OwnerSyncCredential("pairing-pending", campaign, value.origin, expiry, secret))
        val sameScope = old.origin == value.origin && old.campaignId == value.campaignId
        db.withTransaction {
            quarantineActivePublication(old)
            dao.settings(value.copy(deviceCode = response.getValue("deviceCode").jsonPrimitive.content, userCode = response.getValue("userCode").jsonPrimitive.content,
                verificationUri = response["verificationUri"]?.jsonPrimitive?.content ?: value.origin + "/owner", pairingExpiresAt = expiry, status = "Waiting for owner authorization",
                published = sameScope && old.published, remoteRevision = if (sameScope) old.remoteRevision else 0,
                remoteFieldsJson = old.remoteFieldsJson.takeIf { sameScope }))
        }
        snapshotUnlocked()
    }
    /** An authenticated owner page supplies optional outer Site access; it is never accepted from a conversation. */
    internal suspend fun acceptServiceAccess(origin: String, campaign: String, bearer: String) = lock.withLock {
        require(campaign == OwnerConnectionProtocol.CAMPAIGN); vault.storeServiceAccess(origin, campaign, bearer)
    }
    /** Browser-mediated encrypted bootstrap; the transport key never enters chat or a plaintext setup file. */
    fun startServiceAuthorization(origin: String, campaign: String): String {
        require(campaign == OwnerConnectionProtocol.CAMPAIGN)
        return bootstrap.begin(OwnerSyncProtocol.origin(origin), campaign)
    }
    suspend fun importServiceAuthorization(bytes: ByteArray): JsonObject = lock.withLock {
        val enrollment = bootstrap.importEncryptedBundle(bytes)
        require(enrollment.campaignId == OwnerConnectionProtocol.CAMPAIGN)
        val current = setting()
        if (current.origin.isNotBlank() && current.origin != enrollment.origin) require(vault.credential(current.origin, current.campaignId) == null) { "Revoke this device before changing services." }
        vault.storeServiceAccess(enrollment.origin, enrollment.campaignId, enrollment.serviceAccessBearer)
        val changedOrigin = current.origin.isNotBlank() && current.origin != enrollment.origin
        db.withTransaction {
            if (changedOrigin) {
                quarantineActivePublication(current)
                dao.settings(OwnerConnectionSettings(origin = enrollment.origin, campaignId = enrollment.campaignId,
                    status = "New service pairing required · previous queued publication retained for review"))
            } else dao.settings(current.copy(origin = enrollment.origin, campaignId = enrollment.campaignId,
                status = if (current.deviceId.isNotBlank()) "Private service access renewed · sync to verify" else "Private service access imported · pairing still required"))
        }
        if (changedOrigin) vault.forgetServiceAccess(current.origin, current.campaignId)
        if (vault.credential(enrollment.origin, enrollment.campaignId)?.credentialId.let { it != null && it != "pairing-pending" }) OwnerConnectionSyncJob.schedule(context)
        snapshotUnlocked()
    }
    suspend fun finishPairing(): JsonObject = lock.withLock {
        val value = checked(setting()); check(value.deviceCode.isNotEmpty())
        if (value.pairingExpiresAt <= now()) {
            vault.forget(value.origin, value.campaignId)
            dao.settings(value.copy(deviceCode = "", userCode = "", verificationUri = "", pairingExpiresAt = 0, status = "Pairing expired · start again"))
            throw OwnerSyncFailure("PAIRING_EXPIRED")
        }
        val pending = vault.credential(value.origin, value.campaignId) ?: throw OwnerSyncFailure("PAIRING_EXPIRED")
        require(pending.credentialId == "pairing-pending")
        val response = request(value, "/v1/pairings/token", OwnerSyncProtocol.canonical(buildJsonObject { put("deviceCode", value.deviceCode); put("deviceSecret", pending.bearer) }), false)
        if (response["status"]?.jsonPrimitive?.content == "PENDING") return@withLock snapshotUnlocked()
        require(response.getValue("status").jsonPrimitive.content == "PAIRED")
        val id = response.getValue("credentialId").jsonPrimitive.content
        val scopes = response.getValue("scopes").jsonArray
        require(scopes.map { it.jsonPrimitive.content }.toSet().containsAll(setOf("character:read", "character:publish", "proposal:decide")))
        vault.enroll(OwnerSyncCredential(id, value.campaignId, value.origin, response.getValue("expiresAt").jsonPrimitive.long, pending.bearer))
        dao.settings(value.copy(deviceId = id, accountLabel = response.getValue("ownerLabel").jsonPrimitive.content, scopesJson = scopes.toString(),
            deviceCode = "", userCode = "", verificationUri = "", pairingExpiresAt = 0, status = "Paired · no campaign data uploaded"))
        OwnerConnectionSyncJob.schedule(context); snapshotUnlocked()
    }
    private fun base(value: OwnerConnectionSettings) = "/v1/campaigns/${value.campaignId}"
    suspend fun createFictionalFixture(): JsonObject = lock.withLock {
        var value = checked(setting()); credential(value)
        if (value.publishBody == null && !value.published) {
            if (records.characterProfiles.first().none { it.id == OwnerConnectionProtocol.PROFILE }) records.saveProfile(
                CharacterProfile(id = OwnerConnectionProtocol.PROFILE, name = "Fictional connection explorer"), source = OwnerConnectionProtocol.SOURCE, commandId = "owner-sync-fixture-profile-v1")
            if (ability() == null) records.saveAbility(CharacterAbility("INT", OwnerConnectionProtocol.PROFILE, 16, notes = "Fictional connectivity fixture"),
                source = OwnerConnectionProtocol.SOURCE, commandId = "owner-sync-fixture-int-v1")
            val profile = records.characterProfiles.first().single { it.id == OwnerConnectionProtocol.PROFILE }; val current = requireNotNull(ability())
            require(current.revision == 1L) { "Fixture has a saved local history; reconcile it before first publication." }
            val body = OwnerSyncProtocol.canonical(buildJsonObject {
                put("commandId", UUID.randomUUID().toString()); put("expectedRevision", 0)
                put("profile", campaignJson.encodeToJsonElement(profile)); put("ability", buildJsonObject {
                    put("recordId", OwnerConnectionProtocol.RECORD); put("kind", "character_ability"); put("revision", current.revision)
                    put("fields", abilityFields(current))
                })
            })
            value = value.copy(publishBody = body, status = "Fictional fixture queued for publication"); queuePublication(value, body)
        }
        syncUnlocked(); snapshotUnlocked()
    }
    /** Explicit native edit of the fixed fictional fixture. This does not enqueue or send a network write. */
    suspend fun saveFictionalAbility(score: Int, expectedLocalRevision: Long): JsonObject = lock.withLock {
        require(score in 1..30)
        val value = checked(setting()); credential(value)
        val current = requireNotNull(ability())
        require(current.revision == expectedLocalRevision) { "The fictional ability changed after review." }
        records.saveAbility(current.copy(score = score), expectedLocalRevision, "Player-edited fictional owner connection ability")
        dao.settings(value.copy(status = "Fictional ability saved locally · review publication separately"))
        snapshotUnlocked()
    }
    /** Only this explicit review action publishes a local edit; ordinary sync never scans campaign records. */
    suspend fun publishFictionalAbility(expectedLocalRevision: Long): JsonObject = lock.withLock {
        require(ability()?.revision == expectedLocalRevision) { "The local ability changed after review. Review it again before publishing." }
        var value = checked(setting()); credential(value); require(value.published)
        require(dao.proposals().none { it.origin == value.origin && it.campaignId == value.campaignId && it.state in setOf("APPROVING", "APPLIED_PENDING") }) { "Deliver the approved decision before publishing a local edit." }
        if (value.publishBody == null) {
            // Offline review may use the last independently verified server revision; CAS still decides on reconnect.
            val expected = try {
                val remote = request(value, base(value) + "/characters/${OwnerConnectionProtocol.PROFILE}")
                val remoteAbility = remote.getValue("abilities").jsonArray.map { it.jsonObject }.single { it.getValue("recordId").jsonPrimitive.content == OwnerConnectionProtocol.RECORD }
                remoteAbility.getValue("revision").jsonPrimitive.long
            } catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) { if (e.code != "REMOTE_UNAVAILABLE") throw e; require(value.remoteRevision > 0); value.remoteRevision }
            catch (e: java.io.IOException) { require(value.remoteRevision > 0); value.remoteRevision }
            val current = requireNotNull(ability())
            require(current.revision == expected + 1) { "Local and remote revisions need explicit conflict review; no automatic rebase." }
            val profile = records.characterProfiles.first().single { it.id == OwnerConnectionProtocol.PROFILE }
            val body = OwnerSyncProtocol.canonical(buildJsonObject {
                put("commandId", UUID.randomUUID().toString()); put("expectedRevision", expected); put("profile", campaignJson.encodeToJsonElement(profile))
                put("ability", buildJsonObject { put("recordId", OwnerConnectionProtocol.RECORD); put("kind", "character_ability"); put("revision", current.revision); put("fields", abilityFields(current)) })
            })
            value = value.copy(publishBody = body, status = "Reviewed fictional local edit queued"); queuePublication(value, body)
        }
        try { syncUnlocked() } catch (e: CancellationException) { throw e } catch (_: Exception) {
            dao.settings(setting().copy(status = "Reviewed fictional edit remains queued · retry delivery"))
        }
        snapshotUnlocked()
    }
    /** Called inside the settings transaction when changing enrollment or service scope. */
    private suspend fun quarantineActivePublication(value: OwnerConnectionSettings) {
        value.publishBody?.let { attempted ->
            val id = Json.parseToJsonElement(attempted).jsonObject.getValue("commandId").jsonPrimitive.content
            val publication = requireNotNull(dao.publication(id))
            require(publication.origin == value.origin && publication.campaignId == value.campaignId && publication.body == attempted)
            dao.update(publication.copy(state = "REPAIR_REVIEW"))
        }
    }
    private suspend fun queuePublication(value: OwnerConnectionSettings, body: String) = db.withTransaction {
        val id = Json.parseToJsonElement(body).jsonObject.getValue("commandId").jsonPrimitive.content
        val old = dao.publication(id)
        if (old == null) dao.publication(OwnerConnectionPublication(id, value.origin, value.campaignId, body))
        else require(old.body == body && old.origin == value.origin && old.campaignId == value.campaignId)
        dao.settings(value)
    }
    private fun abilityFields(value: CharacterAbility) = buildJsonObject {
        put("profileId", value.profileId); put("key", value.key); put("score", value.score?.let(::JsonPrimitive) ?: JsonNull)
        put("magicModifier", value.magicModifier); put("notes", value.notes); put("source", value.source); put("updatedAt", value.updatedAt)
    }
    suspend fun refresh(): JsonObject = sync()
    suspend fun sync(): JsonObject = lock.withLock {
        try { syncUnlocked(); snapshotUnlocked() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { val value = setting(); dao.settings(value.copy(status = when (e) {
            is OwnerSyncFailure -> "Connection needs attention (${e.code})"; else -> "Offline or unverified response · saved work retained" }))
            if (e is OwnerSyncFailure && e.code !in setOf("REMOTE_UNAVAILABLE", "QUOTA_REACHED")) OwnerConnectionSyncJob.cancel(context)
            throw e }
    }
    private suspend fun syncUnlocked() {
        var value = checked(setting()); credential(value)
        if (value.revokePending) { revokeUnlocked(value); return }
        value.publishBody?.let { body ->
            val sent = Json.parseToJsonElement(body).jsonObject
            val id = sent.getValue("commandId").jsonPrimitive.content
            val receipt = try { request(value, base(value) + "/characters", body).getValue("receipt").jsonObject }
            catch (e: OwnerSyncFailure) {
                if (e.code !in setOf("REVISION_CONFLICT", "COMMAND_ID_REUSED")) throw e
                // Preserve the exact rejected command and local audit, but unblock independent reads.
                value = value.copy(publishBody = null, status = "Publication conflict requires explicit review")
                db.withTransaction {
                    val publication = requireNotNull(dao.publication(id)); require(publication.body == body)
                    dao.update(publication.copy(state = if (e.code == "COMMAND_ID_REUSED") "REPLAY_BLOCKED" else "CONFLICT"))
                    dao.settings(value)
                }
                return@let
            }
            require(receipt.getValue("commandId").jsonPrimitive.content == id && receipt.getValue("recordId").jsonPrimitive.content == OwnerConnectionProtocol.RECORD &&
                receipt.getValue("status").jsonPrimitive.content == "PUBLISHED" && receipt.getValue("priorRevision") == sent.getValue("expectedRevision") &&
                receipt.getValue("resultingRevision") == sent.getValue("ability").jsonObject.getValue("revision") && receipt.getValue("fingerprint").jsonPrimitive.content == OwnerSyncProtocol.hash(body))
            val independentReceipt = request(value, base(value) + "/receipts/$id").getValue("receipt").jsonObject
            require(independentReceipt == receipt) { "Published command receipt independent readback differs." }
            val remote = request(value, base(value) + "/characters/${OwnerConnectionProtocol.PROFILE}")
            val record = remote.getValue("abilities").jsonArray.map { it.jsonObject }.single { it.getValue("recordId").jsonPrimitive.content == OwnerConnectionProtocol.RECORD }
            val publishedRevision = sent.getValue("ability").jsonObject.getValue("revision").jsonPrimitive.long
            val remoteRevision = record.getValue("revision").jsonPrimitive.long
            require(remoteRevision >= publishedRevision) { "Published ability readback revision regressed." }
            val remoteNewer = remoteRevision > publishedRevision
            if (!remoteNewer) require(record == sent.getValue("ability")) { "Published ability independent readback differs." }
            value = value.copy(publishBody = null, published = true, remoteRevision = record.getValue("revision").jsonPrimitive.long, remoteFieldsJson = OwnerSyncProtocol.canonical(record.getValue("fields")))
            db.withTransaction {
                val publication = requireNotNull(dao.publication(id)); require(publication.body == body)
                publication.receiptJson?.let { require(it == OwnerSyncProtocol.canonical(receipt)) }
                dao.update(publication.copy(state = if (remoteNewer) "REMOTE_NEWER" else "VERIFIED", receiptJson = OwnerSyncProtocol.canonical(receipt))); dao.settings(value)
            }
        }
        for (proposal in dao.proposals().filter { it.origin == value.origin && it.campaignId == value.campaignId }) {
            if (proposal.state == "APPROVING") finishLocal(proposal)
        }
        // A missing independent receipt is read again without ever resending its quarantined decision.
        for (proposal in dao.proposals().filter { it.origin == value.origin && it.campaignId == value.campaignId && it.state == "DECISION_CONFLICT" && it.receiptJson == null }.take(50)) quarantineDecision(value, proposal)
        for (proposal in dao.proposals().filter { it.origin == value.origin && it.campaignId == value.campaignId && it.state in setOf("APPLIED_PENDING", "REJECTED_PENDING") }) flushDecision(value, proposal)
        val response = request(value, base(value) + "/pending")
        for (element in response.getValue("proposals").jsonArray) ingest(value, element.jsonObject)
        // A successful sync is an actual bounded authenticated response, never a countdown.
        value = setting()
        if (value.published || ability() != null) {
            val currentRemote = request(value, base(value) + "/characters/${OwnerConnectionProtocol.PROFILE}")
            val remoteAbility = currentRemote.getValue("abilities").jsonArray.map { it.jsonObject }.single { it.getValue("recordId").jsonPrimitive.content == OwnerConnectionProtocol.RECORD }
            value = value.copy(published = true, remoteRevision = remoteAbility.getValue("revision").jsonPrimitive.long, remoteFieldsJson = OwnerSyncProtocol.canonical(remoteAbility.getValue("fields")))
        }
        val blockedPublication = dao.publications().any { it.state == "REPAIR_REVIEW" || (it.origin == value.origin && it.campaignId == value.campaignId && it.state in setOf("CONFLICT", "REPLAY_BLOCKED")) }
        val blockedDecision = dao.proposals().any { it.origin == value.origin && it.campaignId == value.campaignId && it.state == "DECISION_CONFLICT" }
        dao.settings(value.copy(lastSyncAt = now(), status = if (blockedPublication || blockedDecision) "Authenticated service read succeeded · delivery conflict needs review" else "Authenticated service read succeeded"))
    }
    private suspend fun ingest(value: OwnerConnectionSettings, data: JsonObject) {
        val id = data.getValue("commandId").jsonPrimitive.content; require(UUID.fromString(id).toString() == id)
        require(data.getValue("recordId").jsonPrimitive.content == OwnerConnectionProtocol.RECORD)
        val fields = data.getValue("fields").jsonObject; require(fields.keys == setOf("score"))
        val score = fields.getValue("score").jsonPrimitive.int; require(score in 1..30 && !fields.getValue("score").jsonPrimitive.isString)
        val revision = data.getValue("expectedRevision").jsonPrimitive.long; require(revision in 1..9_007_199_254_740_990L)
        val source = data.getValue("source").jsonPrimitive.content; require(source.isNotBlank() && source.length <= 1900)
        val fingerprint = data.getValue("fingerprint").jsonPrimitive.content
        require(fingerprint == OwnerConnectionProtocol.proposalFingerprint(value.campaignId, data))
        val old = dao.proposal(id)
        if (old != null) { require(old.fingerprint == fingerprint && old.origin == value.origin && old.campaignId == value.campaignId); return }
        val current = ability(); require(current != null)
        val baselineMatches = abilityFields(current) == data.getValue("currentFields")
        val localId = OwnerConnectionProtocol.localCommand(value.origin, value.campaignId, id)
        // A restored Campaign backup retains its original command receipt even when device pairing/journal is new.
        val saved = records.receipt(localId)
        val restored = saved?.let {
            val outcome = campaignJson.decodeFromString<CharacterAbility>(it.outcomeJson)
            require(it.commandId == localId && it.kind == "character-ability" && it.recordId == "${OwnerConnectionProtocol.PROFILE}:INT" &&
                it.resultingRevision == revision + 1 && outcome.profileId == OwnerConnectionProtocol.PROFILE && outcome.key == "INT" &&
                outcome.revision == revision + 1 && outcome.score == score && outcome.source == "Player-approved owner proposal $id · $source")
            true
        } ?: false
        val proposal = OwnerConnectionProposal(id, value.origin, value.campaignId, OwnerConnectionProtocol.RECORD, revision, score, source,
            fingerprint, OwnerSyncProtocol.canonical(data), now(), current.score, current.revision,
            state = if (restored) "APPLIED_PENDING" else if (current.revision != revision || !baselineMatches) "CONFLICT" else "PENDING",
            message = if (restored) "Original local approval receipt recovered" else if (current.revision != revision || !baselineMatches) "Local record differs from the proposed baseline · review and reject" else "Awaiting your approval")
        dao.insert(if (restored) proposal.copy(decisionBody = decisionBody(proposal, true, localId)) else proposal)
    }
    private fun validateProposal(p: OwnerConnectionProposal) {
        val data = Json.parseToJsonElement(p.body).jsonObject
        require(OwnerConnectionProtocol.proposalFingerprint(p.campaignId, data) == p.fingerprint &&
            data.getValue("commandId").jsonPrimitive.content == p.commandId && data.getValue("recordId").jsonPrimitive.content == p.recordId &&
            data.getValue("expectedRevision").jsonPrimitive.long == p.expectedRevision && data.getValue("source").jsonPrimitive.content == p.source &&
            data.getValue("fields").jsonObject.keys == setOf("score") && data.getValue("fields").jsonObject.getValue("score").jsonPrimitive.int == p.proposedScore)
        require(p.campaignId == OwnerConnectionProtocol.CAMPAIGN && p.recordId == OwnerConnectionProtocol.RECORD && p.proposedScore in 1..30 && p.source.isNotBlank() && p.source.length <= 1900)
    }
    suspend fun decide(commandId: String, approve: Boolean): JsonObject = lock.withLock {
        val value = checked(setting()); credential(value)
        val proposal = requireNotNull(dao.proposal(commandId)); validateProposal(proposal); require(proposal.origin == value.origin && proposal.campaignId == value.campaignId)
        if (proposal.receiptJson != null || proposal.state in setOf("VERIFIED", "REJECTED", "APPLIED_PENDING", "REJECTED_PENDING")) return@withLock snapshotUnlocked()
        if (!approve) {
            require(proposal.state in setOf("PENDING", "CONFLICT")); dao.update(proposal.copy(state = "REJECTED_PENDING", decisionBody = decisionBody(proposal, false, ""), message = "Rejected locally · receipt delivery pending"))
        } else {
            require(proposal.state == "PENDING"); val current = requireNotNull(ability())
            if (current.revision != proposal.expectedRevision || abilityFields(current) != Json.parseToJsonElement(proposal.body).jsonObject.getValue("currentFields")) { dao.update(proposal.copy(state = "CONFLICT", message = "Local record differs from the proposed baseline · nothing applied")); return@withLock snapshotUnlocked() }
            val approved = proposal.copy(state = "APPROVING", approvedAbilityJson = campaignJson.encodeToString(current.copy(score = proposal.proposedScore)), message = "Approval saved · committing local transaction")
            dao.update(approved); finishLocal(approved)
        }
        // Local approval/rejection survives network loss; the queue is retried independently.
        try { dao.proposal(commandId)?.let { flushDecision(value, it) } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { dao.settings(setting().copy(status = "Decision saved locally · retry delivery")) }
        snapshotUnlocked()
    }
    private suspend fun decisionBody(p: OwnerConnectionProposal, approved: Boolean, receiptId: String): String {
        val fields = if (approved) {
            val receipt = requireNotNull(records.receipt(receiptId))
            val saved = campaignJson.decodeFromString<CharacterAbility>(receipt.outcomeJson)
            require(receipt.resultingRevision == p.expectedRevision + 1 && saved.profileId == OwnerConnectionProtocol.PROFILE &&
                saved.key == "INT" && saved.score == p.proposedScore && saved.revision == p.expectedRevision + 1)
            abilityFields(saved)
        } else JsonNull
        return OwnerSyncProtocol.canonical(buildJsonObject {
            put("commandId", p.commandId); put("approved", approved)
            put("localAppliedRevision", if (approved) JsonPrimitive(p.expectedRevision + 1) else JsonNull)
            put("localReceiptId", if (approved) JsonPrimitive(receiptId) else JsonNull); put("proposalFingerprint", p.fingerprint)
            put("localFields", fields)
        })
    }
    private suspend fun finishLocal(p: OwnerConnectionProposal) {
        validateProposal(p)
        require(p.state == "APPROVING" && p.approvedAbilityJson != null)
        val frozen = campaignJson.decodeFromString<CharacterAbility>(p.approvedAbilityJson)
        require(frozen.profileId == OwnerConnectionProtocol.PROFILE && frozen.key == "INT" && frozen.score == p.proposedScore && frozen.revision == p.expectedRevision)
        val id = OwnerConnectionProtocol.localCommand(p.origin, p.campaignId, p.commandId)
        try { records.saveAbility(frozen, p.expectedRevision, "Player-approved owner proposal ${p.commandId} · ${p.source}", id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (records.receipt(id) == null && ability()?.revision != p.expectedRevision) { dao.update(p.copy(state = "CONFLICT", message = "Local revision changed · nothing applied")); return }
            throw e
        }
        afterLocalCommit()
        dao.update(p.copy(state = "APPLIED_PENDING", decisionBody = decisionBody(p, true, id), message = "Saved locally · server receipt pending"))
    }
    private suspend fun flushDecision(value: OwnerConnectionSettings, p: OwnerConnectionProposal) {
        validateProposal(p)
        require(p.state in setOf("APPLIED_PENDING", "REJECTED_PENDING")); val approved = p.state == "APPLIED_PENDING"
        val body = requireNotNull(p.decisionBody)
        require(body == decisionBody(p, approved, if (approved) OwnerConnectionProtocol.localCommand(p.origin, p.campaignId, p.commandId) else ""))
        val response = try { request(value, base(value) + "/decisions", body) }
        catch (e: OwnerSyncFailure) {
            if (e.code !in setOf("REVISION_CONFLICT", "COMMAND_ID_REUSED")) throw e
            quarantineDecision(value, p)
            return
        }
        val receipt = response.getValue("receipt").jsonObject
        require(receipt.getValue("commandId").jsonPrimitive.content == p.commandId && receipt.getValue("recordId").jsonPrimitive.content == p.recordId &&
            receipt.getValue("fingerprint").jsonPrimitive.content == p.fingerprint)
        val status = receipt.getValue("status").jsonPrimitive.content
        require(status == "CONFLICT" || (approved && status == "APPLIED") || (!approved && status == "REJECTED"))
        val prior = receipt.getValue("priorRevision").jsonPrimitive.long
        val resulting = receipt.getValue("resultingRevision").jsonPrimitive.long
        require(prior >= 1 && resulting == if (status == "APPLIED") p.expectedRevision + 1 else prior)
        if (status == "APPLIED") require(prior == p.expectedRevision)
        else require(prior >= p.expectedRevision)
        require(receipt["localReceiptId"] == if (approved) JsonPrimitive(OwnerConnectionProtocol.localCommand(p.origin, p.campaignId, p.commandId)) else JsonNull)
        val eventId = receipt.getValue("eventId").jsonPrimitive.content
        require(UUID.fromString(eventId).toString() == eventId && receipt.getValue("recordedAt").jsonPrimitive.long > 0)
        val independent = request(value, base(value) + "/receipts/${p.commandId}").getValue("receipt").jsonObject
        require(independent == receipt) { "Independent receipt readback differs." }
        var remoteNewer = false
        if (approved && status == "APPLIED") {
            val approvedFields = Json.parseToJsonElement(body).jsonObject.getValue("localFields")
            require(receipt.getValue("localRecordFingerprint").jsonPrimitive.content == OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(approvedFields)))
            val remote = request(value, base(value) + "/characters/${OwnerConnectionProtocol.PROFILE}")
            val record = remote.getValue("abilities").jsonArray.map { it.jsonObject }.single { it.getValue("recordId").jsonPrimitive.content == p.recordId }
            val remoteRevision = record.getValue("revision").jsonPrimitive.long
            require(remoteRevision >= resulting) { "Approved ability readback revision regressed." }
            remoteNewer = remoteRevision > resulting
            if (!remoteNewer) require(record.getValue("fields") == approvedFields)
            dao.settings(setting().copy(remoteRevision = remoteRevision, remoteFieldsJson = OwnerSyncProtocol.canonical(record.getValue("fields"))))
        }
        val currentMatches = if (status == "APPLIED") ability()?.let { current ->
            current.revision == resulting && abilityFields(current) == Json.parseToJsonElement(body).jsonObject.getValue("localFields")
        } ?: false else true
        dao.update(p.copy(state = if (status == "APPLIED") if (remoteNewer) "REMOTE_NEWER" else if (currentMatches) "VERIFIED" else "LOCAL_NEWER" else status, receiptJson = OwnerSyncProtocol.canonical(receipt),
            message = when (status) { "APPLIED" -> if (remoteNewer) "Original approved receipt verified · remote record has a later revision requiring review" else if (currentMatches) "Local and remote fields, revision and receipt verified" else "Original approved receipt verified · current local record has a later edit requiring review"
                "REJECTED" -> "Rejection independently verified"; else -> "Server revision conflicted · local receipt preserved for review" }))
    }
    /** A competing immutable decision cannot be retried as this device's original request. */
    private suspend fun quarantineDecision(value: OwnerConnectionSettings, p: OwnerConnectionProposal) {
        val blocked = p.copy(state = "DECISION_CONFLICT", message = "Decision delivery conflicted · original attempt and local approval receipt preserved for review")
        // Quarantine before reads so an outage cannot re-activate the conflicting write.
        dao.update(blocked)
        try {
            val receipt = request(value, base(value) + "/receipts/${p.commandId}").getValue("receipt").jsonObject
            val status = receipt.getValue("status").jsonPrimitive.content
            val prior = receipt.getValue("priorRevision").jsonPrimitive.long
            val resulting = receipt.getValue("resultingRevision").jsonPrimitive.long
            val event = receipt.getValue("eventId").jsonPrimitive.content
            require(receipt.getValue("commandId").jsonPrimitive.content == p.commandId && receipt.getValue("recordId").jsonPrimitive.content == p.recordId &&
                receipt.getValue("fingerprint").jsonPrimitive.content == p.fingerprint && status in setOf("APPLIED", "REJECTED", "CONFLICT") &&
                prior >= p.expectedRevision && resulting == if (status == "APPLIED") prior + 1 else prior)
            if (status == "APPLIED") require(prior == p.expectedRevision)
            require(UUID.fromString(event).toString() == event && receipt.getValue("recordedAt").jsonPrimitive.long > 0)
            dao.update(blocked.copy(receiptJson = OwnerSyncProtocol.canonical(receipt)))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The exact attempted body and independent local receipt remain durable. */ }
        try {
            val remote = request(value, base(value) + "/characters/${OwnerConnectionProtocol.PROFILE}")
            val record = remote.getValue("abilities").jsonArray.map { it.jsonObject }.single { it.getValue("recordId").jsonPrimitive.content == p.recordId }
            val revision = record.getValue("revision").jsonPrimitive.long; require(revision >= 1)
            dao.settings(setting().copy(remoteRevision = revision, remoteFieldsJson = OwnerSyncProtocol.canonical(record.getValue("fields")),
                status = "Decision delivery conflicted · independent remote state read; local approval retained"))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Ordinary future sync can still read; this command will never auto-retry. */ }
    }
    suspend fun revoke(): JsonObject = lock.withLock {
        val value = checked(setting()); credential(value); dao.settings(value.copy(revokePending = true, status = "Server revocation pending"))
        revokeUnlocked(setting()); snapshotUnlocked()
    }
    private suspend fun revokeUnlocked(value: OwnerConnectionSettings) {
        val response = request(value, base(value) + "/devices/revoke", "{}")
        require(response["status"]?.jsonPrimitive?.content == "REVOKED" && response["revoked"]?.jsonPrimitive?.boolean == true &&
            response["credentialId"]?.jsonPrimitive?.content == value.deviceId)
        vault.forget(value.origin, value.campaignId); vault.forgetServiceAccess(value.origin, value.campaignId)
        dao.settings(value.copy(revokePending = false, status = "Server confirmed device revocation", deviceId = "")); OwnerConnectionSyncJob.cancel(context)
    }
    suspend fun forgetLocal(): JsonObject = lock.withLock {
        val value = checked(setting()); vault.forget(value.origin, value.campaignId); vault.forgetServiceAccess(value.origin, value.campaignId)
        dao.settings(value.copy(status = "Local credential removed · server revocation unconfirmed", deviceId = "")); OwnerConnectionSyncJob.cancel(context); snapshotUnlocked()
    }
    fun close() { db.close(); campaignDb.close() }
}
