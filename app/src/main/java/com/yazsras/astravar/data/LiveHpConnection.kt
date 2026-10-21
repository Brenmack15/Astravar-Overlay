package com.yazsras.astravar.data

import android.content.Context
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID

/** Live authority is restricted to the profile explicitly selected during owner/device pairing. */
object LiveHpConnectionProtocol {
    const val MODE = "ASTRAVAR_OWNER_HP_V1"
    const val MAX_REVISION = 9_007_199_254_740_990L
    fun localCommand(origin: String, campaign: String, command: String) = UUID.nameUUIDFromBytes(
        "astravar-live-hp-approval-v1:$origin:$campaign:$command".toByteArray(Charsets.UTF_8)).toString()
    fun source(command: String, source: String) = "Player-approved HP connection test $command · $source"
    fun fingerprint(campaign: String, proposal: JsonObject) = OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(buildJsonObject {
        put("campaignId", campaign)
        listOf("commandId", "recordId", "expectedRevision", "fields", "source", "adjustment").forEach { put(it, proposal.getValue(it)) }
    }))
    fun fields(profile: CharacterProfile) = buildJsonObject {
        put("name", profile.name); put("currentHp", profile.currentHp?.let(::JsonPrimitive) ?: JsonNull)
        put("maximumHp", profile.maximumHp?.let(::JsonPrimitive) ?: JsonNull)
        put("temporaryHp", profile.temporaryHp?.let(::JsonPrimitive) ?: JsonNull)
        put("source", profile.source); put("updatedAt", profile.updatedAt)
    }
    internal fun integer(value: JsonElement): Long = value.jsonPrimitive.let { require(!it.isString && it.booleanOrNull == null); requireNotNull(it.longOrNull) }
    internal fun checkedFields(fields: JsonObject): JsonObject = fields.also {
        require(it.keys == setOf("name", "currentHp", "maximumHp", "temporaryHp", "source", "updatedAt"))
        require(it.getValue("name").jsonPrimitive.isString && it.getValue("name").jsonPrimitive.content.length <= 120)
        require(it.getValue("source").jsonPrimitive.isString && it.getValue("source").jsonPrimitive.content.length <= 2000)
        listOf("currentHp", "maximumHp", "temporaryHp").forEach { key -> if (it.getValue(key) != JsonNull) require(integer(it.getValue(key)) in 0..1_000_000) }
        val hp = it.getValue("currentHp").takeUnless { v -> v == JsonNull }?.let(::integer)
        val max = it.getValue("maximumHp").takeUnless { v -> v == JsonNull }?.let(::integer)
        require(hp == null || max == null || hp <= max)
        require(integer(it.getValue("updatedAt")) in 0..MAX_REVISION)
    }
}

@Entity(tableName = "live_hp_settings")
data class LiveHpSettings(@PrimaryKey val id: Int = 1, val origin: String = "", val campaignId: String = "", val profileId: String = "",
    val deviceName: String = "", val deviceId: String = "", val accountLabel: String = "", val scopesJson: String = "[]",
    val deviceCode: String = "", val userCode: String = "", val verificationUri: String = "", val pairingExpiresAt: Long = 0,
    val status: String = "Choose an existing character to connect HP", val lastSyncAt: Long = 0, val lastDeviceSyncAt: Long = 0,
    val remoteRevision: Long = 0, val remoteFieldsJson: String? = null, val publishBody: String? = null, val revokePending: Boolean = false, val initialPublicationPending: Boolean = false)
@Entity(tableName = "live_hp_commands")
data class LiveHpCommand(@PrimaryKey val commandId: String, val origin: String, val campaignId: String, val profileId: String,
    val expectedRevision: Long, val proposedHp: Int, val adjustment: Int, val source: String, val fingerprint: String,
    val body: String, val receivedAt: Long, val baselineProfileJson: String? = null, val state: String = "PENDING",
    val message: String = "Awaiting your approval", val decisionBody: String? = null, val receiptJson: String? = null)
@Entity(tableName = "live_hp_publications")
data class LiveHpPublication(@PrimaryKey val commandId: String, val origin: String, val campaignId: String, val profileId: String,
    val body: String, val state: String = "PENDING", val receiptJson: String? = null)
@Dao interface LiveHpConnectionDao {
    @Query("SELECT * FROM live_hp_settings WHERE id=1") suspend fun settings(): LiveHpSettings?
    @Upsert suspend fun settings(value: LiveHpSettings)
    @Query("SELECT * FROM live_hp_commands ORDER BY receivedAt,commandId") suspend fun commands(): List<LiveHpCommand>
    @Query("SELECT * FROM live_hp_commands WHERE commandId=:id") suspend fun command(id: String): LiveHpCommand?
    @Insert suspend fun insert(value: LiveHpCommand)
    @Update suspend fun update(value: LiveHpCommand)
    @Query("SELECT * FROM live_hp_publications ORDER BY commandId") suspend fun publications(): List<LiveHpPublication>
    @Query("SELECT * FROM live_hp_publications WHERE commandId=:id") suspend fun publication(id: String): LiveHpPublication?
    @Insert suspend fun insert(value: LiveHpPublication)
    @Update suspend fun update(value: LiveHpPublication)
}
/** Separate connection journal; no campaign schema changes or destructive migration. */
@Database(entities = [LiveHpSettings::class, LiveHpCommand::class, LiveHpPublication::class], version = 1, exportSchema = true)
abstract class LiveHpConnectionDb : RoomDatabase() {
    abstract fun connection(): LiveHpConnectionDao
    companion object { fun open(context: Context): LiveHpConnectionDb = Room.databaseBuilder(context.applicationContext,
        LiveHpConnectionDb::class.java, "astravar-live-hp-connection.db").build() }
}

class LiveHpConnectionClient internal constructor(private val context: Context, private val db: LiveHpConnectionDb,
    private val campaignDb: CampaignLedgerDb, private val vault: OwnerSyncCredentialVault, private val transport: OwnerConnectionTransport,
    private val now: () -> Long = System::currentTimeMillis, private val afterLocalCommit: suspend () -> Unit = {},
    private val bootstrap: OwnerConnectionBootstrap = OwnerConnectionBootstrap(context)) {
    constructor(context: Context) : this(context.applicationContext, LiveHpConnectionDb.open(context), CampaignLedgerDb.open(context),
        OwnerSyncCredentialVault(context), OwnerConnectionHttpsTransport())
    private val dao = db.connection()
    private val records = CampaignRecordsRepository(campaignDb)
    private var automaticOperation = false
    private fun requireAutomaticAllowed() {
        if (automaticOperation && CampaignSyncPreferences(context).paused) throw OwnerSyncFailure("AUTOMATIC_SYNC_PAUSED")
    }
    companion object { private val lock = Mutex() }
    private suspend fun setting() = dao.settings() ?: LiveHpSettings()
    private suspend fun profile(id: String) = campaignDb.recordsDao().characterProfile(id)
    private fun scoped(value: LiveHpSettings) = value.also {
        OwnerSyncProtocol.origin(it.origin); OwnerSyncProtocol.id(it.campaignId); OwnerSyncProtocol.id(it.profileId)
        require(it.campaignId != OwnerConnectionProtocol.CAMPAIGN)
    }
    private suspend fun campaignFor(profileId: String): String {
        OwnerSyncProtocol.id(profileId); require(profileId != OwnerConnectionProtocol.PROFILE) { "Select a campaign character, not a connection fixture." }; requireNotNull(profile(profileId)) { "Select an existing character." }
        val campaigns = campaignDb.partyDao().allSheets().filter { it.profileId == profileId }.map { it.campaignId }.distinct()
        return campaigns.singleOrNull()?.takeIf { it != OwnerConnectionProtocol.CAMPAIGN && runCatching { OwnerSyncProtocol.id(it) }.isSuccess }
            ?: "astravar-live-${OwnerSyncProtocol.hash(profileId).take(32)}"
    }
    private suspend fun credential(value: LiveHpSettings) = (vault.credential(value.origin, value.campaignId)
        ?: throw OwnerSyncFailure("AUTHENTICATION_REQUIRED")).also { if (it.credentialId == "pairing-pending") throw OwnerSyncFailure("PAIRING_PENDING") }
    private fun base(value: LiveHpSettings) = "/v1/hp/campaigns/${value.campaignId}"
    private suspend fun request(value: LiveHpSettings, path: String, body: String? = null, authenticated: Boolean = true): JsonObject {
        requireAutomaticAllowed()
        scoped(value)
        val expectedMode = if (path.startsWith("/v1/hp/")) LiveHpConnectionProtocol.MODE else OwnerConnectionProtocol.MODE
        val response = transport.request(value.origin, path, if (authenticated) credential(value) else null,
            vault.serviceAccess(value.origin, value.campaignId), body)
        if (response.status !in 200..299) {
            val code = (response.json["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
            if (authenticated && response.status == 401 && code == "CREDENTIAL_REVOKED_OR_EXPIRED" &&
                response.json["mode"]?.jsonPrimitive?.content == expectedMode) {
                vault.forget(value.origin, value.campaignId)
                dao.settings(setting().copy(deviceId = "", status = "Device authorization removed · pair again")); LiveHpConnectionSyncJob.cancel(context)
            }
            throw OwnerSyncFailure(when (response.status) {
                401 -> if (code == "CREDENTIAL_REVOKED_OR_EXPIRED" && response.json["mode"]?.jsonPrimitive?.content == expectedMode) "CREDENTIAL_REVOKED_OR_EXPIRED" else "PRIVATE_GATEWAY_AUTH_REQUIRED"; 403 -> "ACCESS_DENIED"; 404 -> "RECORD_NOT_FOUND"; 429 -> "QUOTA_REACHED"
                409 -> if (code == "COMMAND_ID_REUSED") "COMMAND_ID_REUSED" else "REVISION_CONFLICT"
                in 500..599 -> "REMOTE_UNAVAILABLE"; else -> code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "INVALID_RESPONSE"
            })
        }
        require(response.json["mode"]?.jsonPrimitive?.content == expectedMode && response.json["campaignId"]?.jsonPrimitive?.content == value.campaignId)
        return response.json
    }
    private suspend fun reuseServiceAccess(origin: String, campaign: String) {
        if (vault.serviceAccess(origin, campaign) == null) vault.serviceAccess(origin, OwnerConnectionProtocol.CAMPAIGN)?.let {
            vault.storeServiceAccess(origin, campaign, it)
        }
    }
    suspend fun startServiceAuthorization(origin: String, profileId: String): String = lock.withLock {
        bootstrap.begin(OwnerSyncProtocol.origin(origin), campaignFor(profileId))
    }
    suspend fun importServiceAuthorization(bytes: ByteArray): JsonObject = lock.withLock {
        val enrollment = bootstrap.importEncryptedBundle(bytes); val old = setting()
        require(enrollment.campaignId != OwnerConnectionProtocol.CAMPAIGN)
        val matches = campaignDb.recordsDao().allCharacterProfiles().filter { runCatching { campaignFor(it.id) }.getOrNull() == enrollment.campaignId }
        require(matches.isNotEmpty()) { "Authorization must match an existing selected character campaign." }
        if (old.origin.isNotBlank() && old.origin != enrollment.origin) require(vault.credential(old.origin, old.campaignId) == null) { "Revoke the current connection before changing services." }
        vault.storeServiceAccess(enrollment.origin, enrollment.campaignId, enrollment.serviceAccessBearer)
        if (old.origin.isBlank()) dao.settings(old.copy(origin = enrollment.origin, status = "Private service authorization imported · select character and pair"))
        else if (old.origin == enrollment.origin) dao.settings(old.copy(status = "Private service access renewed · sync to verify"))
        else {
            quarantinePublication(old, "REPAIR_REVIEW")
            dao.settings(LiveHpSettings(origin = enrollment.origin, status = "New service · pair again; old queued request retained for review"))
        }
        if (old.campaignId.isNotBlank() && old.origin == enrollment.origin && old.deviceId.isNotBlank()) LiveHpConnectionSyncJob.schedule(context)
        snapshotUnlocked()
    }
    suspend fun beginPairing(origin: String, profileId: String, deviceName: String): JsonObject = lock.withLock {
        require(deviceName.isNotBlank() && deviceName.length <= 100)
        val old = setting()
        if (old.campaignId.isNotBlank()) require(vault.credential(old.origin, old.campaignId)?.credentialId.let { it == null || it == "pairing-pending" }) { "Revoke the current device before pairing another character." }
        val value = LiveHpSettings(origin = OwnerSyncProtocol.origin(origin), campaignId = campaignFor(profileId), profileId = profileId, deviceName = deviceName)
        reuseServiceAccess(value.origin, value.campaignId)
        val secret = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val response = request(value, "/v1/pairings", OwnerSyncProtocol.canonical(buildJsonObject {
            put("campaignId", value.campaignId); put("profileId", profileId); put("deviceName", deviceName); put("deviceSecretHash", OwnerSyncProtocol.hash(secret))
        }), false)
        val expiry = LiveHpConnectionProtocol.integer(response.getValue("expiresAt")); require(expiry > now())
        vault.enroll(OwnerSyncCredential("pairing-pending", value.campaignId, value.origin, expiry, secret))
        db.withTransaction {
            quarantinePublication(old, "REPAIR_REVIEW")
            // New owner consent does not authorize replaying a decision frozen under an older device grant.
            for (command in dao.commands().filter { belongs(it, old) && it.state in setOf("PENDING", "CONFLICT", "APPROVING", "APPLIED_PENDING", "REJECTED_PENDING") })
                dao.update(command.copy(state = "REPAIR_REVIEW", message = "Previous device request retained for review · never replayed with new authorization"))
            dao.settings(value.copy(deviceCode = response.getValue("deviceCode").jsonPrimitive.content,
            userCode = response.getValue("userCode").jsonPrimitive.content, verificationUri = response["verificationUri"]?.jsonPrimitive?.content ?: value.origin + "/owner",
            pairingExpiresAt = expiry, status = "Waiting for owner authorization for this character's HP")) }
        snapshotUnlocked()
    }
    suspend fun finishPairing(): JsonObject = lock.withLock {
        val value = scoped(setting()); require(value.deviceCode.isNotBlank())
        if (value.pairingExpiresAt <= now()) { vault.forget(value.origin, value.campaignId); dao.settings(value.copy(deviceCode = "", userCode = "", verificationUri = "", pairingExpiresAt = 0, status = "Pairing expired · start again")); throw OwnerSyncFailure("PAIRING_EXPIRED") }
        val pending = requireNotNull(vault.credential(value.origin, value.campaignId)); require(pending.credentialId == "pairing-pending")
        val response = request(value, "/v1/pairings/token", OwnerSyncProtocol.canonical(buildJsonObject { put("deviceCode", value.deviceCode); put("deviceSecret", pending.bearer) }), false)
        if (response["status"]?.jsonPrimitive?.content == "PENDING") return@withLock snapshotUnlocked()
        require(response.getValue("status").jsonPrimitive.content == "PAIRED" && response.getValue("profileId").jsonPrimitive.content == value.profileId)
        val scopes = response.getValue("scopes").jsonArray
        require(scopes.map { it.jsonPrimitive.content }.toSet() == setOf("hp:read", "hp:publish", "hp:decide"))
        val id = response.getValue("credentialId").jsonPrimitive.content
        vault.enroll(OwnerSyncCredential(id, value.campaignId, value.origin, response.getValue("expiresAt").jsonPrimitive.long, pending.bearer))
        dao.settings(value.copy(deviceId = id, accountLabel = response.getValue("ownerLabel").jsonPrimitive.content, scopesJson = scopes.toString(),
            deviceCode = "", userCode = "", verificationUri = "", pairingExpiresAt = 0, initialPublicationPending = true, status = "Paired · synchronizing selected character HP"))
        LiveHpConnectionSyncJob.schedule(context)
        try { syncUnlocked() } catch (e: CancellationException) { throw e } catch (_: Exception) { dao.settings(setting().copy(status = "Paired · initial HP publication awaiting connection")) }
        snapshotUnlocked()
    }
    suspend fun snapshot(): JsonObject = lock.withLock { snapshotUnlocked() }
    private suspend fun snapshotUnlocked(): JsonObject {
        val value = setting(); val current = profile(value.profileId)
        val secret = if (value.campaignId.isNotBlank()) vault.credential(value.origin, value.campaignId) else null
        val commands = dao.commands().filter { it.origin == value.origin && it.campaignId == value.campaignId && it.profileId == value.profileId }
        return buildJsonObject {
            put("origin", value.origin); put("campaignId", value.campaignId); put("selectedProfileId", value.profileId)
            put("connected", secret != null && secret.credentialId != "pairing-pending"); put("deviceId", value.deviceId); put("deviceName", value.deviceName)
            put("accountLabel", value.accountLabel); put("ownerLabel", value.accountLabel); put("scopes", Json.parseToJsonElement(value.scopesJson)); put("status", value.status)
            put("lastSyncAt", value.lastSyncAt); put("lastDeviceSyncAt", value.lastDeviceSyncAt); put("revokePending", value.revokePending)
            put("profiles", buildJsonArray { campaignDb.recordsDao().allCharacterProfiles().forEach { p ->
                if (runCatching { OwnerSyncProtocol.id(p.id) }.isSuccess && p.id != OwnerConnectionProtocol.PROFILE) add(buildJsonObject {
                    put("id", p.id); put("name", p.name); put("revision", p.revision); put("campaignId", campaignFor(p.id))
                    put("currentHp", p.currentHp?.let(::JsonPrimitive) ?: JsonNull); put("maximumHp", p.maximumHp?.let(::JsonPrimitive) ?: JsonNull); put("temporaryHp", p.temporaryHp?.let(::JsonPrimitive) ?: JsonNull)
                })
            } })
            put("current", current?.let { buildJsonObject { LiveHpConnectionProtocol.fields(it).forEach { (k,v) -> put(k,v) }; put("revision", it.revision)
                put("synchronized", it.revision == value.remoteRevision && value.remoteFieldsJson == OwnerSyncProtocol.canonical(LiveHpConnectionProtocol.fields(it))) } } ?: JsonNull)
            put("pairing", buildJsonObject { put("userCode", value.userCode); put("verificationUri", value.verificationUri); put("expiresAt", value.pairingExpiresAt) })
            put("pending", buildJsonArray { commands.filter { it.state in setOf("PENDING", "CONFLICT", "APPROVING", "APPLIED_PENDING", "REJECTED_PENDING") }.forEach { p -> add(buildJsonObject {
                put("commandId", p.commandId); put("state", p.state); put("source", p.source); put("message", p.message); put("adjustment", p.adjustment)
                put("currentHp", current?.currentHp?.let(::JsonPrimitive) ?: JsonNull); put("maximumHp", current?.maximumHp?.let(::JsonPrimitive) ?: JsonNull); put("proposedHp", p.proposedHp)
                put("expectedRevision", p.expectedRevision); put("currentRevision", current?.revision ?: 0); put("decisionQueued", p.state in setOf("APPROVING", "APPLIED_PENDING", "REJECTED_PENDING"))
            }) } })
            put("history", buildJsonArray {
                dao.publications().filter { (it.origin == value.origin && it.campaignId == value.campaignId && it.profileId == value.profileId) || it.state == "REPAIR_REVIEW" }.reversed().take(50).forEach { p -> add(buildJsonObject { put("commandId", p.commandId); put("state", p.state); put("message", "HP publication ${p.state.lowercase()}"); put("receiptJson", p.receiptJson?.let(::JsonPrimitive) ?: JsonNull) }) }
                commands.filter { it.state != "PENDING" }.reversed().take(50).forEach { p -> add(buildJsonObject { put("commandId", p.commandId); put("state", p.state); put("message", p.message); put("updatedAt", p.receivedAt); put("receiptJson", p.receiptJson?.let(::JsonPrimitive) ?: JsonNull) }) }
            })
        }
    }
    suspend fun foreground(): JsonObject = lock.withLock {
        val value = setting()
        if (value.campaignId.isBlank() || value.deviceId.isBlank() || CampaignSyncPreferences(context).paused) return@withLock snapshotUnlocked()
        automaticOperation = true
        try { syncUnlocked(); snapshotUnlocked() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { recordFailure(e); throw e }
        finally { automaticOperation = false }
    }
    suspend fun refresh() = sync()
    suspend fun sync(): JsonObject = lock.withLock {
        try { syncUnlocked(); snapshotUnlocked() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { recordFailure(e); throw e }
    }
    private suspend fun recordFailure(e: Exception) {
        dao.settings(setting().copy(status = when {
            e is OwnerSyncFailure && e.code == "PRIVATE_GATEWAY_AUTH_REQUIRED" -> "Private service authorization needs renewal · device grant retained"
            e is OwnerSyncFailure -> "HP connection needs attention (${e.code})"
            else -> "Offline or unverified response · saved HP retained"
        }))
    }
    private fun belongs(p: LiveHpCommand, value: LiveHpSettings) = p.origin == value.origin && p.campaignId == value.campaignId && p.profileId == value.profileId
    private suspend fun readRemote(value: LiveHpSettings): JsonObject? = try {
        val remote = request(value, base(value) + "/characters/${value.profileId}")
        require(remote.getValue("profileId").jsonPrimitive.content == value.profileId && remote.getValue("recordId").jsonPrimitive.content == value.profileId)
        require(LiveHpConnectionProtocol.integer(remote.getValue("revision")) in 1..LiveHpConnectionProtocol.MAX_REVISION)
        LiveHpConnectionProtocol.checkedFields(remote.getValue("fields").jsonObject); remote
    } catch (e: OwnerSyncFailure) { if (e.code == "RECORD_NOT_FOUND") null else throw e }
    private fun deviceTimestamp(remote: JsonObject): Long = LiveHpConnectionProtocol.integer(remote.getValue("lastDeviceSyncAt")).also {
        require(it > 0 && it <= now() + 60_000) { "Unverified device synchronization timestamp." }
    }
    private suspend fun syncUnlocked() {
        var value = scoped(setting()); credential(value)
        if (value.revokePending) { revokeUnlocked(value); return }
        for (p in dao.commands().filter { belongs(it, value) && it.state == "APPROVING" }) finishLocal(p)
        for (p in dao.commands().filter { belongs(it, value) && it.state == "DECISION_CONFLICT" && it.receiptJson == null }) quarantineDecision(value, p)
        for (p in dao.commands().filter { belongs(it, value) && it.state in setOf("APPLIED_PENDING", "REJECTED_PENDING") }) flushDecision(value, p)
        // Read proposals before publishing: a restored approval receipt must be recovered before any publication.
        val pending = request(value, base(value) + "/pending")
        for (p in pending.getValue("proposals").jsonArray) ingest(value, p.jsonObject)
        for (entry in (pending["superseded"] as? JsonArray).orEmpty()) {
            val metadata = entry.jsonObject; val id = metadata.getValue("commandId").jsonPrimitive.content
            require(UUID.fromString(id).toString() == id && metadata.getValue("recordId").jsonPrimitive.content == value.profileId && metadata.getValue("status").jsonPrimitive.content == "SUPERSEDED_BY_REPAIR")
            val old = dao.command(id)
            if (old != null) { require(belongs(old, value)); dao.update(old.copy(state = "SUPERSEDED_BY_REPAIR", message = "Prior device proposal superseded by owner re-pairing · no server approval claimed")) }
            else if (dao.publication(id) == null) dao.insert(LiveHpPublication(id, value.origin, value.campaignId, value.profileId, OwnerSyncProtocol.canonical(metadata), "SUPERSEDED_BY_REPAIR"))
        }
        for (p in dao.commands().filter { belongs(it, value) && it.state == "APPLIED_PENDING" }) flushDecision(value, p)
        value = setting()
        value.publishBody?.let { flushPublication(value, it) }
        value = setting()
        val remote = readRemote(value); val local = requireNotNull(profile(value.profileId))
        val remoteRevision = remote?.getValue("revision")?.jsonPrimitive?.long ?: 0
        val remoteFields = remote?.getValue("fields")?.jsonObject
        val blocked = dao.commands().any { belongs(it, value) && it.state in setOf("APPROVING", "APPLIED_PENDING", "DECISION_CONFLICT", "CONFLICT", "REMOTE_NEWER", "LOCAL_NEWER") } ||
            dao.publications().any { it.origin == value.origin && it.campaignId == value.campaignId && it.profileId == value.profileId && it.state in setOf("CONFLICT", "REPLAY_BLOCKED", "REPAIR_REVIEW", "REMOTE_NEWER") }
        val same = remoteRevision == local.revision && remoteFields == LiveHpConnectionProtocol.fields(local)
        val knownBaseline = remoteRevision == value.remoteRevision && remoteFields?.let(OwnerSyncProtocol::canonical) == value.remoteFieldsJson
        if (value.publishBody == null && !blocked && (remote == null || (local.revision > remoteRevision && (knownBaseline || value.initialPublicationPending)) || (same && value.initialPublicationPending))) {
            val body = OwnerSyncProtocol.canonical(buildJsonObject { put("commandId", UUID.randomUUID().toString()); put("expectedRevision", remoteRevision)
                put("profileId", local.id); put("revision", local.revision); put("fields", LiveHpConnectionProtocol.fields(local)) })
            val id = Json.parseToJsonElement(body).jsonObject.getValue("commandId").jsonPrimitive.content
            db.withTransaction { dao.insert(LiveHpPublication(id, value.origin, value.campaignId, value.profileId, body)); dao.settings(value.copy(publishBody = body, status = "Selected character HP queued")) }
            flushPublication(setting(), body)
        } else if (same && !blocked && value.publishBody == null) {
            heartbeat(value, local)
        } else if (remote != null) dao.settings(value.copy(status = "Local and remote HP differ · approval or conflict review required"))
        value = setting()
        val finalRemote = readRemote(value)
        val finalRevision = finalRemote?.getValue("revision")?.jsonPrimitive?.long ?: 0
        val finalFields = finalRemote?.getValue("fields")?.jsonObject
        // Retain the last trusted baseline when a concurrent remote revision differs; never silently rebase local edits.
        val matching = finalRevision == profile(value.profileId)?.revision && finalFields == profile(value.profileId)?.let(LiveHpConnectionProtocol::fields)
        dao.settings(value.copy(lastSyncAt = now(), remoteRevision = if (matching || value.remoteRevision == 0L) finalRevision else value.remoteRevision,
            remoteFieldsJson = if (matching || value.remoteRevision == 0L) finalFields?.let(OwnerSyncProtocol::canonical) else value.remoteFieldsJson,
            status = if (blocked) "Authenticated HP read succeeded · conflict requires review" else if (matching) "Selected character HP and revision verified" else "Authenticated HP read succeeded · local and remote differ"))
    }
    private suspend fun heartbeat(value: LiveHpSettings, local: CharacterProfile) {
        // A fresh phone-authenticated exact snapshot proves freshness; reads never advance this timestamp.
        if (value.lastDeviceSyncAt > 0 && now() - value.lastDeviceSyncAt < 60_000) return
        val command = UUID.randomUUID().toString()
        val fingerprint = OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(LiveHpConnectionProtocol.fields(local)))
        val response = request(value, base(value) + "/heartbeat", OwnerSyncProtocol.canonical(buildJsonObject {
            put("commandId", command); put("profileId", local.id); put("revision", local.revision)
            put("fieldsFingerprint", fingerprint); put("sentAt", now())
        }))
        require(response.getValue("commandId").jsonPrimitive.content == command && response.getValue("profileId").jsonPrimitive.content == local.id &&
            LiveHpConnectionProtocol.integer(response.getValue("revision")) == local.revision && response.getValue("fieldsFingerprint").jsonPrimitive.content == fingerprint)
        val confirmed = LiveHpConnectionProtocol.integer(response.getValue("lastDeviceSyncAt"))
        require(confirmed > 0 && confirmed >= value.lastDeviceSyncAt && confirmed <= now() + 60_000)
        dao.settings(setting().copy(lastDeviceSyncAt = confirmed, initialPublicationPending = false))
    }
    private suspend fun quarantinePublication(value: LiveHpSettings, state: String) {
        value.publishBody?.let { body -> val id = Json.parseToJsonElement(body).jsonObject.getValue("commandId").jsonPrimitive.content
            val p = requireNotNull(dao.publication(id)); require(p.body == body); dao.update(p.copy(state = state)) }
    }
    private suspend fun flushPublication(value: LiveHpSettings, body: String) {
        val sent = Json.parseToJsonElement(body).jsonObject; val id = sent.getValue("commandId").jsonPrimitive.content
        val receipt = try { request(value, base(value) + "/characters", body).getValue("receipt").jsonObject }
        catch (e: OwnerSyncFailure) { if (e.code !in setOf("REVISION_CONFLICT", "COMMAND_ID_REUSED")) throw e
            db.withTransaction { quarantinePublication(value, if (e.code == "COMMAND_ID_REUSED") "REPLAY_BLOCKED" else "CONFLICT"); dao.settings(setting().copy(publishBody = null, status = "HP publication conflict · original request retained")) }; return }
        require(receipt.getValue("commandId").jsonPrimitive.content == id && receipt.getValue("recordId").jsonPrimitive.content == value.profileId && receipt.getValue("status").jsonPrimitive.content == "PUBLISHED" && receipt.getValue("deviceCredentialId").jsonPrimitive.content == value.deviceId &&
            receipt.getValue("priorRevision") == sent.getValue("expectedRevision") && receipt.getValue("resultingRevision") == sent.getValue("revision") && receipt.getValue("fingerprint").jsonPrimitive.content == OwnerSyncProtocol.hash(body))
        require(request(value, base(value) + "/receipts/$id").getValue("receipt") == receipt)
        val remote = requireNotNull(readRemote(value)); val revision = remote.getValue("revision").jsonPrimitive.long
        require(revision >= sent.getValue("revision").jsonPrimitive.long)
        val newer = revision > sent.getValue("revision").jsonPrimitive.long
        if (!newer) require(remote.getValue("fields") == sent.getValue("fields"))
        db.withTransaction {
            val publication = requireNotNull(dao.publication(id)); require(publication.body == body)
            dao.update(publication.copy(state = if (newer) "REMOTE_NEWER" else "VERIFIED", receiptJson = OwnerSyncProtocol.canonical(receipt)))
            dao.settings(setting().copy(publishBody = null, initialPublicationPending = false, remoteRevision = revision, remoteFieldsJson = OwnerSyncProtocol.canonical(remote.getValue("fields")), lastDeviceSyncAt = deviceTimestamp(remote)))
        }
    }
    private fun parsed(p: LiveHpCommand): JsonObject = Json.parseToJsonElement(p.body).jsonObject.also {
        require(LiveHpConnectionProtocol.fingerprint(p.campaignId, it) == p.fingerprint && it.getValue("commandId").jsonPrimitive.content == p.commandId &&
            it.getValue("recordId").jsonPrimitive.content == p.profileId && LiveHpConnectionProtocol.integer(it.getValue("expectedRevision")) == p.expectedRevision &&
            LiveHpConnectionProtocol.integer(it.getValue("fields").jsonObject.getValue("currentHp")) == p.proposedHp.toLong() &&
            LiveHpConnectionProtocol.integer(it.getValue("adjustment")) == p.adjustment.toLong() && it.getValue("source").jsonPrimitive.content == p.source)
    }
    private suspend fun ingest(value: LiveHpSettings, data: JsonObject) {
        val id = data.getValue("commandId").jsonPrimitive.content; require(UUID.fromString(id).toString() == id)
        require(data.getValue("recordId").jsonPrimitive.content == value.profileId)
        val fields = data.getValue("fields").jsonObject; require(fields.keys == setOf("currentHp"))
        val hp = LiveHpConnectionProtocol.integer(fields.getValue("currentHp")); require(hp in 0..1_000_000)
        val revision = LiveHpConnectionProtocol.integer(data.getValue("expectedRevision")); require(revision in 1 until LiveHpConnectionProtocol.MAX_REVISION)
        val before = LiveHpConnectionProtocol.checkedFields(data.getValue("currentFields").jsonObject)
        require(before.getValue("currentHp") != JsonNull && before.getValue("maximumHp") != JsonNull)
        val adjustment = LiveHpConnectionProtocol.integer(data.getValue("adjustment")); require(adjustment in -1_000_000..1_000_000 && adjustment != 0L)
        require(hp == LiveHpConnectionProtocol.integer(before.getValue("currentHp")) + adjustment && hp <= LiveHpConnectionProtocol.integer(before.getValue("maximumHp")))
        val source = data.getValue("source").jsonPrimitive; require(source.isString && source.content.isNotBlank() && source.content.length <= 1900)
        val fingerprint = data.getValue("fingerprint").jsonPrimitive.content; require(fingerprint == LiveHpConnectionProtocol.fingerprint(value.campaignId, data))
        dao.command(id)?.let { require(it.fingerprint == fingerprint && belongs(it, value)); return }
        val current = requireNotNull(profile(value.profileId)); val matches = current.revision == revision && LiveHpConnectionProtocol.fields(current) == before
        var command = LiveHpCommand(id, value.origin, value.campaignId, value.profileId, revision, hp.toInt(), adjustment.toInt(), source.content, fingerprint,
            OwnerSyncProtocol.canonical(data), now(), baselineProfileJson = if (matches) campaignJson.encodeToString(current) else null,
            state = if (matches) "PENDING" else "CONFLICT", message = if (matches) "Awaiting your approval" else "Local profile differs from proposal baseline · no HP applied")
        val localId = LiveHpConnectionProtocol.localCommand(value.origin, value.campaignId, id)
        records.receipt(localId)?.let { saved ->
            verifiedLocalOutcome(command, saved)
            command = command.copy(state = "APPLIED_PENDING", message = "Original local approval recovered from campaign receipt", baselineProfileJson = null)
            command = command.copy(decisionBody = decisionBody(command, true, saved))
        }
        dao.insert(command)
    }
    suspend fun decide(commandId: String, approve: Boolean): JsonObject = lock.withLock {
        val value = scoped(setting()); credential(value); val p = requireNotNull(dao.command(commandId)); require(belongs(p, value)); val data = parsed(p)
        if (p.receiptJson != null || p.state !in setOf("PENDING", "CONFLICT")) return@withLock snapshotUnlocked()
        if (!approve) dao.update(p.copy(state = "REJECTED_PENDING", decisionBody = decisionBody(p, false, null), message = "Rejected locally · confirmation pending"))
        else {
            require(p.state == "PENDING")
            val current = requireNotNull(profile(value.profileId))
            if (current.revision != p.expectedRevision || current.currentHp == null || current.maximumHp == null ||
                campaignJson.encodeToString(current) != p.baselineProfileJson || LiveHpConnectionProtocol.fields(current) != data.getValue("currentFields")) {
                dao.update(p.copy(state = "CONFLICT", message = "Local profile changed after review · nothing applied")); return@withLock snapshotUnlocked()
            }
            val intent = p.copy(state = "APPROVING", message = "Player approval saved · committing HP transaction")
            dao.update(intent); finishLocal(intent)
        }
        try { dao.command(commandId)?.takeIf { it.state in setOf("APPLIED_PENDING", "REJECTED_PENDING") }?.let { flushDecision(value, it) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { dao.settings(setting().copy(status = "Decision retained locally · delivery pending")) }
        snapshotUnlocked()
    }
    private fun verifiedLocalOutcome(p: LiveHpCommand, receipt: CommandReceipt): CharacterProfile {
        val saved = campaignJson.decodeFromString<CharacterProfile>(receipt.outcomeJson)
        val source = LiveHpConnectionProtocol.source(p.commandId, p.source)
        val payload = campaignJson.encodeToString(saved.copy(revision = 0, source = "", updatedAt = 0))
        val expectedFingerprint = campaignHash(campaignJson.encodeToString(listOf("v2", "character-profile", p.profileId, p.expectedRevision.toString(), payload, source)))
        require(receipt.commandId == LiveHpConnectionProtocol.localCommand(p.origin, p.campaignId, p.commandId) && receipt.kind == "character-profile" && receipt.recordId == p.profileId &&
            receipt.resultingRevision == p.expectedRevision + 1 && receipt.fingerprint == expectedFingerprint && saved.id == p.profileId && saved.revision == p.expectedRevision + 1 &&
            saved.currentHp == p.proposedHp && saved.source == source)
        val before = parsed(p).getValue("currentFields").jsonObject
        require(saved.name == before.getValue("name").jsonPrimitive.content && saved.maximumHp?.let(::JsonPrimitive) == before.getValue("maximumHp") &&
            (saved.temporaryHp?.let(::JsonPrimitive) ?: JsonNull) == before.getValue("temporaryHp"))
        return saved
    }
    private fun decisionBody(p: LiveHpCommand, approved: Boolean, receipt: CommandReceipt?): String = OwnerSyncProtocol.canonical(buildJsonObject {
        put("commandId", p.commandId); put("approved", approved); put("proposalFingerprint", p.fingerprint)
        put("localAppliedRevision", if (approved) JsonPrimitive(p.expectedRevision + 1) else JsonNull)
        put("localReceiptId", if (approved) JsonPrimitive(requireNotNull(receipt).commandId) else JsonNull)
        put("localFields", if (approved) LiveHpConnectionProtocol.fields(verifiedLocalOutcome(p, requireNotNull(receipt))) else JsonNull)
    })
    private suspend fun finishLocal(p: LiveHpCommand) {
        requireAutomaticAllowed()
        parsed(p); require(p.state == "APPROVING")
        val frozen = campaignJson.decodeFromString<CharacterProfile>(requireNotNull(p.baselineProfileJson)); require(frozen.id == p.profileId && frozen.revision == p.expectedRevision)
        val localId = LiveHpConnectionProtocol.localCommand(p.origin, p.campaignId, p.commandId)
        val receipt = campaignDb.withTransaction {
            val old = records.receipt(localId)
            if (old != null) { verifiedLocalOutcome(p, old); old }
            else {
                val current = profile(p.profileId)
                if (current != frozen) return@withTransaction null
                requireAutomaticAllowed()
                val committed = records.saveProfile(frozen.copy(currentHp = p.proposedHp), p.expectedRevision, LiveHpConnectionProtocol.source(p.commandId, p.source), localId)
                requireAutomaticAllowed()
                committed
            }
        }
        if (receipt == null) { dao.update(p.copy(state = "CONFLICT", message = "Local profile changed before commit · nothing applied")); return }
        afterLocalCommit()
        dao.update(p.copy(state = "APPLIED_PENDING", decisionBody = decisionBody(p, true, receipt), message = "HP saved locally · server confirmation pending"))
    }
    private fun verifiedReceipt(p: LiveHpCommand, receipt: JsonObject, approved: Boolean? = null) {
        require(receipt.getValue("commandId").jsonPrimitive.content == p.commandId && receipt.getValue("recordId").jsonPrimitive.content == p.profileId && receipt.getValue("fingerprint").jsonPrimitive.content == p.fingerprint)
        val status = receipt.getValue("status").jsonPrimitive.content; require(status in setOf("APPLIED", "REJECTED", "CONFLICT"))
        if (approved != null) require(status == "CONFLICT" || status == if (approved) "APPLIED" else "REJECTED")
        val prior = LiveHpConnectionProtocol.integer(receipt.getValue("priorRevision")); val result = LiveHpConnectionProtocol.integer(receipt.getValue("resultingRevision"))
        require(prior >= p.expectedRevision && result == if (status == "APPLIED") p.expectedRevision + 1 else prior)
        if (status == "APPLIED") require(prior == p.expectedRevision)
        val event = receipt.getValue("eventId").jsonPrimitive.content; require(UUID.fromString(event).toString() == event && LiveHpConnectionProtocol.integer(receipt.getValue("recordedAt")) > 0)
    }
    private suspend fun flushDecision(value: LiveHpSettings, p: LiveHpCommand) {
        parsed(p); val approved = p.state == "APPLIED_PENDING"; require(approved || p.state == "REJECTED_PENDING")
        val saved = if (approved) requireNotNull(records.receipt(LiveHpConnectionProtocol.localCommand(p.origin, p.campaignId, p.commandId))) else null
        val body = requireNotNull(p.decisionBody); require(body == decisionBody(p, approved, saved))
        val receipt = try { request(value, base(value) + "/decisions", body).getValue("receipt").jsonObject }
        catch (e: OwnerSyncFailure) { if (e.code !in setOf("REVISION_CONFLICT", "COMMAND_ID_REUSED")) throw e; quarantineDecision(value, p); return }
        verifiedReceipt(p, receipt, approved)
        require(receipt.getValue("deviceCredentialId").jsonPrimitive.content == value.deviceId)
        require(receipt["localReceiptId"] == if (approved) JsonPrimitive(requireNotNull(saved).commandId) else JsonNull)
        require(request(value, base(value) + "/receipts/${p.commandId}").getValue("receipt") == receipt)
        val status = receipt.getValue("status").jsonPrimitive.content
        var state = if (status == "APPLIED") "VERIFIED" else status
        if (status == "APPLIED") {
            val fields = Json.parseToJsonElement(body).jsonObject.getValue("localFields")
            require(receipt.getValue("localRecordFingerprint").jsonPrimitive.content == OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(fields)))
            val remote = requireNotNull(readRemote(value)); val revision = remote.getValue("revision").jsonPrimitive.long
            require(revision >= p.expectedRevision + 1)
            if (revision == p.expectedRevision + 1) require(remote.getValue("fields") == fields)
            val current = profile(p.profileId)
            state = if (revision > p.expectedRevision + 1) "REMOTE_NEWER" else if (current != null && current.revision == p.expectedRevision + 1 && LiveHpConnectionProtocol.fields(current) == fields) "VERIFIED" else "LOCAL_NEWER"
            dao.settings(setting().copy(remoteRevision = revision, remoteFieldsJson = OwnerSyncProtocol.canonical(remote.getValue("fields")), lastDeviceSyncAt = deviceTimestamp(remote)))
        }
        dao.update(p.copy(state = state, receiptJson = OwnerSyncProtocol.canonical(receipt), message = when (state) {
            "VERIFIED" -> "Saved HP, revision and immutable receipt independently verified"; "REJECTED" -> "Rejection independently verified"
            "LOCAL_NEWER" -> "Approval confirmed · later local edit retained for review"; "REMOTE_NEWER" -> "Approval confirmed · remote has a later revision requiring review"
            else -> "Server conflict · local HP and approval receipt retained for review"
        }))
    }
    private suspend fun quarantineDecision(value: LiveHpSettings, p: LiveHpCommand) {
        var blocked = p.copy(state = "DECISION_CONFLICT", message = "Conflicting immutable decision · exact request and local receipt retained; no automatic retry")
        dao.update(blocked)
        try { val receipt = request(value, base(value) + "/receipts/${p.commandId}").getValue("receipt").jsonObject; verifiedReceipt(p, receipt)
            blocked = blocked.copy(receiptJson = OwnerSyncProtocol.canonical(receipt)); dao.update(blocked)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { readRemote(value) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    suspend fun revoke(): JsonObject = lock.withLock {
        val value = scoped(setting()); credential(value); dao.settings(value.copy(revokePending = true, status = "Server revocation pending")); revokeUnlocked(setting()); snapshotUnlocked()
    }
    private suspend fun revokeUnlocked(value: LiveHpSettings) {
        val response = request(value, base(value) + "/devices/revoke", "{}")
        require(response["status"]?.jsonPrimitive?.content == "REVOKED" && response["revoked"]?.jsonPrimitive?.boolean == true && response["credentialId"]?.jsonPrimitive?.content == value.deviceId)
        vault.forget(value.origin, value.campaignId); vault.forgetServiceAccess(value.origin, value.campaignId)
        dao.settings(value.copy(deviceId = "", revokePending = false, status = "Server confirmed HP device revocation")); LiveHpConnectionSyncJob.cancel(context)
    }
    fun close() { db.close(); campaignDb.close() }
}
