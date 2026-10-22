package com.yazsras.astravar.data

import android.content.Context
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID

/** The campaign database is authoritative. This journal contains delivery state, never a replacement database. */
object CampaignConnectionProtocol {
    const val MODE = "ASTRAVAR_CAMPAIGN_V2"
    val categories = setOf("hp", "resources", "inventory", "currency", "character", "companions", "engineering", "continuity", "combat")
    fun scopes(value: Set<String>): Set<String> = value.also { require(it.all(categories::contains)) { "Unknown campaign access category." } }
    fun commandId(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
    fun localCommand(origin: String, campaign: String, command: String) = UUID.nameUUIDFromBytes(
        "astravar-campaign-v2:$origin:$campaign:$command".toByteArray(Charsets.UTF_8)).toString()
    fun fingerprint(campaign: String, command: JsonObject) = OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(buildJsonObject {
        put("campaignId", campaign)
        listOf("commandId", "grantRevision", "operations", "source").forEach { put(it, command.getValue(it)) }
    }))
    fun scopeArray(values: Set<String>) = JsonArray(scopes(values).sorted().map(::JsonPrimitive))
    fun parseScopes(value: JsonElement): Set<String> {
        val list = value.jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
        require(list.distinct().size == list.size)
        return scopes(list.toSet())
    }
}

@Entity(tableName = "campaign_connection_settings")
data class CampaignConnectionSettings(@PrimaryKey val id: Int = 1, val origin: String = "", val campaignId: String = "",
    val profileId: String = "", val deviceName: String = "", val deviceId: String = "", val accountLabel: String = "",
    val deviceCode: String = "", val userCode: String = "", val verificationUri: String = "", val pairingExpiresAt: Long = 0,
    val requestedReadJson: String = "[]", val requestedWriteJson: String = "[]", val localAutoSyncJson: String = "[]",
    val localReadJson: String? = null, val localWriteJson: String? = null,
    val grantJson: String = "{}", val grantBody: String? = null, val revokePending: Boolean = false,
    val status: String = "Disconnected", val lastError: String = "", val lastSyncAt: Long = 0, val lastDeviceSyncAt: Long = 0)
@Entity(tableName = "campaign_connection_commands")
data class CampaignConnectionCommand(@PrimaryKey val commandId: String, val origin: String, val campaignId: String,
    val deviceId: String, val fingerprint: String, val body: String, val receivedAt: Long, val state: String,
    val message: String = "", val decisionBody: String? = null, val receiptJson: String? = null)
@Entity(tableName = "campaign_connection_publications")
data class CampaignConnectionPublication(@PrimaryKey val commandId: String, val origin: String, val campaignId: String,
    val deviceId: String, val body: String, val state: String = "PENDING", val receiptJson: String? = null)
@Entity(tableName = "campaign_connection_mirrors", primaryKeys = ["origin", "campaignId", "deviceId", "kind", "recordId"])
data class CampaignConnectionMirror(val origin: String, val campaignId: String, val deviceId: String,
    val kind: String, val recordId: String, val revision: Long, val fingerprint: String)
@Dao interface CampaignConnectionDao {
    @Query("SELECT * FROM campaign_connection_settings WHERE id=1") suspend fun settings(): CampaignConnectionSettings?
    @Upsert suspend fun settings(value: CampaignConnectionSettings)
    @Query("SELECT * FROM campaign_connection_commands ORDER BY receivedAt,commandId") suspend fun commands(): List<CampaignConnectionCommand>
    @Query("SELECT * FROM campaign_connection_commands WHERE commandId=:id") suspend fun command(id: String): CampaignConnectionCommand?
    @Insert suspend fun insert(value: CampaignConnectionCommand)
    @Update suspend fun update(value: CampaignConnectionCommand)
    @Query("SELECT * FROM campaign_connection_publications ORDER BY commandId") suspend fun publications(): List<CampaignConnectionPublication>
    @Insert suspend fun insert(value: CampaignConnectionPublication)
    @Update suspend fun update(value: CampaignConnectionPublication)
    @Query("SELECT * FROM campaign_connection_mirrors WHERE origin=:origin AND campaignId=:campaign AND deviceId=:device")
    suspend fun mirrors(origin: String, campaign: String, device: String): List<CampaignConnectionMirror>
    @Upsert suspend fun mirror(value: CampaignConnectionMirror)
    @Query("DELETE FROM campaign_connection_mirrors WHERE origin=:origin AND campaignId=:campaign AND deviceId=:device AND kind=:kind AND recordId=:record")
    suspend fun removeMirror(origin: String, campaign: String, device: String, kind: String, record: String)
}
@Database(entities = [CampaignConnectionSettings::class, CampaignConnectionCommand::class,
    CampaignConnectionPublication::class, CampaignConnectionMirror::class], version = 1, exportSchema = true)
abstract class CampaignConnectionDb : RoomDatabase() {
    abstract fun connection(): CampaignConnectionDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context.applicationContext,
        CampaignConnectionDb::class.java, "astravar-campaign-connection.db").build() }
}

/** One owner/device grant, bounded typed adapters, durable inbox/outbox, and independent readback. */
class CampaignConnectionClient internal constructor(private val context: Context, private val db: CampaignConnectionDb,
    private val campaignDb: CampaignLedgerDb, private val vault: OwnerSyncCredentialVault,
    private val transport: OwnerConnectionTransport, private val now: () -> Long = System::currentTimeMillis,
    private val afterLocalCommit: suspend () -> Unit = {},
    private val bootstrap: OwnerConnectionBootstrap = OwnerConnectionBootstrap(context),
    private val weaponDb: AstravarDb? = null,
    // Fixture clients use their injected transport without initializing a production
    // Pushy installation, even when running a release build.
    private val requestPushToken: ((Context, String) -> Unit)? = null,
    private val disableMessaging: (Context) -> Unit = {}) {
    constructor(context: Context) : this(context.applicationContext, CampaignConnectionDb.open(context), CampaignLedgerDb.open(context),
        OwnerSyncCredentialVault(context), OwnerConnectionHttpsTransport(), weaponDb = AstravarDb.open(context),
        requestPushToken = CampaignPushy::requestToken, disableMessaging = CampaignPushy::disable)
    private val dao = db.connection()
    private val domain = CampaignSyncDomain(campaignDb, weaponDb)
    private val pushStore = CampaignPushStore(context)
    private var automaticOperation = false
    private var syncContinuationRequired = false
    private fun requireAutomaticAllowed() {
        if (automaticOperation && CampaignSyncPreferences(context).paused) throw OwnerSyncFailure("AUTOMATIC_SYNC_PAUSED")
    }
    private suspend fun notifySync() { CampaignSyncNotifications.update(context, snapshotUnlocked()) }
    companion object { private val lock = Mutex() }
    private suspend fun setting() = dao.settings() ?: CampaignConnectionSettings()
    private fun scopes(json: String) = CampaignConnectionProtocol.parseScopes(Json.parseToJsonElement(json))
    private fun grant(value: CampaignConnectionSettings) = Json.parseToJsonElement(value.grantJson).jsonObject
    private fun activeRead(value: CampaignConnectionSettings): Set<String> {
        val granted = (grant(value)["readScopes"] as? JsonArray)?.let(CampaignConnectionProtocol::parseScopes) ?: emptySet()
        return granted.intersect(value.localReadJson?.let(::scopes) ?: scopes(value.requestedReadJson))
    }
    private fun activeWrite(value: CampaignConnectionSettings): Set<String> {
        val granted = (grant(value)["writeScopes"] as? JsonArray)?.let(CampaignConnectionProtocol::parseScopes) ?: emptySet()
        return granted.intersect(value.localWriteJson?.let(::scopes) ?: scopes(value.requestedWriteJson)).intersect(activeRead(value))
    }
    private fun pendingKey(campaign: String) = "campaign-pairing-${OwnerSyncProtocol.hash(campaign).take(40)}"
    private fun base(value: CampaignConnectionSettings) = "/v2/campaigns/${OwnerSyncProtocol.id(value.campaignId)}"
    private suspend fun credential(value: CampaignConnectionSettings): OwnerSyncCredential =
        (vault.credential(value.origin, value.campaignId) ?: throw OwnerSyncFailure("AUTHENTICATION_REQUIRED")).also {
            if (it.credentialId != value.deviceId || it.expiresAt <= now()) throw OwnerSyncFailure("CREDENTIAL_SCOPE_OR_EXPIRY")
        }
    private suspend fun campaignFor(profileId: String): String {
        OwnerSyncProtocol.id(profileId); require(profileId != OwnerConnectionProtocol.PROFILE)
        requireNotNull(campaignDb.recordsDao().characterProfile(profileId)) { "Choose an existing saved character." }
        val campaigns = campaignDb.partyDao().allSheets().filter { it.profileId == profileId }.map { it.campaignId }.distinct()
        require(campaigns.size <= 1) { "Character belongs to multiple campaigns. Resolve campaign identity before pairing." }
        return campaigns.singleOrNull()?.also { OwnerSyncProtocol.id(it); require(it != OwnerConnectionProtocol.CAMPAIGN) }
            ?: "local-campaign"
    }
    private suspend fun request(value: CampaignConnectionSettings, path: String, body: String? = null,
        authenticated: Boolean = true, delete: Boolean = false): JsonObject {
        requireAutomaticAllowed()
        OwnerSyncProtocol.origin(value.origin); OwnerSyncProtocol.id(value.campaignId)
        val auth = if (authenticated) credential(value) else null
        val result = if (delete) transport.delete(value.origin, path, auth, vault.serviceAccess(value.origin, value.campaignId))
            else transport.request(value.origin, path, auth, vault.serviceAccess(value.origin, value.campaignId), body)
        if (result.status !in 200..299) {
            val code = (result.json["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
            val expectedMode = if (path.startsWith("/v2/")) CampaignConnectionProtocol.MODE else OwnerConnectionProtocol.MODE
            if (result.status < 500 && result.json["mode"]?.jsonPrimitive?.content != expectedMode) throw OwnerSyncFailure("PRIVATE_GATEWAY_AUTH_REQUIRED")
            if (authenticated && result.status == 401 && code == "CREDENTIAL_REVOKED_OR_EXPIRED" &&
                result.json["mode"]?.jsonPrimitive?.content == CampaignConnectionProtocol.MODE) {
                vault.forget(value.origin, value.campaignId)
                pushStore.clearRegistration(); disableMessaging(context); CampaignConnectionSyncJob.cancel(context)
                dao.settings(setting().copy(localAutoSyncJson = "[]", status = "Authentication expired"))
            }
            throw OwnerSyncFailure(code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: when (result.status) {
                401 -> "AUTHENTICATION_REQUIRED"; 403 -> "ACCESS_DENIED"; 409 -> "REVISION_CONFLICT"; 429 -> "QUOTA_REACHED"
                in 500..599 -> "REMOTE_UNAVAILABLE"; else -> "INVALID_RESPONSE"
            })
        }
        val mode = if (path.startsWith("/v2/")) CampaignConnectionProtocol.MODE else OwnerConnectionProtocol.MODE
        require(result.json["mode"]?.jsonPrimitive?.content == mode && result.json["campaignId"]?.jsonPrimitive?.content == value.campaignId) { "Unverified campaign response." }
        return result.json
    }
    private fun checkedGrant(value: CampaignConnectionSettings, data: JsonObject): JsonObject {
        val g = (data["grant"] as? JsonObject) ?: (data["campaignAccess"] as? JsonObject) ?: data
        OwnerSyncProtocol.id(g.getValue("grantId").jsonPrimitive.content)
        require(g.getValue("revision").jsonPrimitive.long in 1..LiveHpConnectionProtocol.MAX_REVISION)
        val read = CampaignConnectionProtocol.parseScopes(g.getValue("readScopes"))
        val write = CampaignConnectionProtocol.parseScopes(g.getValue("writeScopes"))
        val auto = CampaignConnectionProtocol.parseScopes(g.getValue("autoSyncScopes"))
        require(read.all(scopes(value.requestedReadJson)::contains) && write.all(scopes(value.requestedWriteJson)::contains) && auto.all(write::contains)) { "Service grant exceeds requested campaign permissions." }
        if (g.getValue("expiresAt").jsonPrimitive.long <= now()) throw OwnerSyncFailure("CAMPAIGN_GRANT_EXPIRED")
        return g
    }
    suspend fun startServiceAuthorization(origin: String, profileId: String): String = lock.withLock {
        bootstrap.begin(OwnerSyncProtocol.origin(origin), campaignFor(profileId))
    }
    suspend fun importServiceAuthorization(bytes: ByteArray): JsonObject = lock.withLock {
        val enrollment = bootstrap.importEncryptedBundle(bytes)
        require(enrollment.campaignId != OwnerConnectionProtocol.CAMPAIGN)
        require(campaignDb.recordsDao().allCharacterProfiles().any { campaignFor(it.id) == enrollment.campaignId }) { "Service authorization does not match an existing campaign." }
        vault.storeServiceAccess(enrollment.origin, enrollment.campaignId, enrollment.serviceAccessBearer)
        val value = setting()
        if (value.origin.isBlank()) dao.settings(value.copy(origin = enrollment.origin, status = "Service authorization ready · pair a campaign"))
        else require(value.origin == enrollment.origin) { "Revoke the current service before replacing its authorization." }
        snapshotUnlocked()
    }
    suspend fun beginPairing(origin: String, profileId: String, deviceName: String, readScopes: Set<String>, writeScopes: Set<String>): JsonObject = lock.withLock {
        CampaignConnectionProtocol.scopes(readScopes); CampaignConnectionProtocol.scopes(writeScopes)
        require(readScopes.isNotEmpty() && writeScopes.all(readScopes::contains)) { "Select readable categories and only matching write scopes." }
        require(deviceName.isNotBlank() && deviceName.length <= 100)
        val old = setting()
        if (old.deviceId.isNotBlank()) {
            val oldCredential = vault.credential(old.origin, old.campaignId)
            val grantExpiry = runCatching { grant(old).getValue("expiresAt").jsonPrimitive.long }.getOrDefault(0)
            require(oldCredential == null || oldCredential.expiresAt <= now() || grantExpiry <= now()) {
                "Revoke the existing campaign connection before pairing another device."
            }
        }
        pushStore.clearRegistration()
        val value = CampaignConnectionSettings(origin = OwnerSyncProtocol.origin(origin), campaignId = campaignFor(profileId),
            profileId = profileId, deviceName = deviceName, requestedReadJson = CampaignConnectionProtocol.scopeArray(readScopes).toString(),
            requestedWriteJson = CampaignConnectionProtocol.scopeArray(writeScopes).toString())
        if (vault.serviceAccess(value.origin, value.campaignId) == null) vault.serviceAccess(value.origin, OwnerConnectionProtocol.CAMPAIGN)?.let {
            vault.storeServiceAccess(value.origin, value.campaignId, it)
        }
        val secret = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val response = request(value, "/v1/pairings", OwnerSyncProtocol.canonical(buildJsonObject {
            put("campaignId", value.campaignId); put("profileId", profileId); put("deviceName", deviceName)
            put("deviceSecretHash", OwnerSyncProtocol.hash(secret)); put("campaignAccess", buildJsonObject {
                put("readScopes", CampaignConnectionProtocol.scopeArray(readScopes)); put("writeScopes", CampaignConnectionProtocol.scopeArray(writeScopes))
            })
        }), false)
        val expiry = response.getValue("expiresAt").jsonPrimitive.long; require(expiry > now())
        vault.enroll(OwnerSyncCredential("pairing-pending", pendingKey(value.campaignId), value.origin, expiry, secret))
        db.withTransaction {
            for (command in dao.commands().filter { belongs(it, old) && it.state in setOf("PENDING_APPROVAL", "PENDING_DELIVERY", "APPLYING", "APPLIED_PENDING", "REJECTED_PENDING", "CONFLICT_PENDING") })
                dao.update(command.copy(state = "REPAIR_REVIEW", message = "Earlier device grant retained for review · never replayed under renewed authorization"))
            for (publication in dao.publications().filter { it.origin == old.origin && it.campaignId == old.campaignId && it.deviceId == old.deviceId && it.state == "PENDING" })
                dao.update(publication.copy(state = "REPAIR_REVIEW"))
            dao.settings(value.copy(deviceCode = response.getValue("deviceCode").jsonPrimitive.content,
            userCode = response.getValue("userCode").jsonPrimitive.content, verificationUri = response["verificationUri"]?.jsonPrimitive?.content ?: value.origin + "/owner",
            pairingExpiresAt = expiry, status = "Waiting for owner authorization"))
        }
        snapshotUnlocked()
    }
    suspend fun finishPairing(): JsonObject = lock.withLock {
        val value = setting(); require(value.deviceCode.isNotBlank())
        if (value.pairingExpiresAt <= now()) {
            vault.forget(value.origin, pendingKey(value.campaignId)); dao.settings(value.copy(deviceCode = "", userCode = "", verificationUri = "", pairingExpiresAt = 0, status = "Pairing expired")); error("Pairing expired. Start again.")
        }
        val pending = requireNotNull(vault.credential(value.origin, pendingKey(value.campaignId)))
        val response = request(value, "/v1/pairings/token", OwnerSyncProtocol.canonical(buildJsonObject {
            put("deviceCode", value.deviceCode); put("deviceSecret", pending.bearer)
        }), false)
        if (response.getValue("status").jsonPrimitive.content == "PENDING") return@withLock snapshotUnlocked()
        require(response.getValue("status").jsonPrimitive.content == "PAIRED" && response.getValue("profileId").jsonPrimitive.content == value.profileId)
        val g = checkedGrant(value, response)
        require(CampaignConnectionProtocol.parseScopes(g.getValue("autoSyncScopes")).isEmpty()) { "New enrollment must start in manual mode." }
        val id = response.getValue("credentialId").jsonPrimitive.content
        vault.enroll(OwnerSyncCredential(id, value.campaignId, value.origin, response.getValue("expiresAt").jsonPrimitive.long, pending.bearer))
        vault.forget(value.origin, pendingKey(value.campaignId))
        dao.settings(value.copy(deviceId = id, accountLabel = response.getValue("ownerLabel").jsonPrimitive.content,
            grantJson = g.toString(), localAutoSyncJson = "[]", deviceCode = "", userCode = "", verificationUri = "", pairingExpiresAt = 0,
            status = "Paired · initial synchronization pending"))
        CampaignConnectionSyncJob.schedule(context)
        try { syncUnlocked() } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
        snapshotUnlocked()
    }
    suspend fun setAutoSync(scopes: Set<String>): JsonObject = lock.withLock {
        CampaignConnectionProtocol.scopes(scopes)
        val value = setting(); credential(value); val g = checkedGrant(value, grant(value))
        require(scopes.all(activeWrite(value)::contains))
        val desired = CampaignConnectionProtocol.scopeArray(scopes).toString()
        // Preserve a pending request byte-for-byte: its first response may have been lost after commit.
        val body = value.grantBody ?: grantBody(g, scopes, activeRead(value), activeWrite(value))
        dao.settings(value.copy(localAutoSyncJson = desired, grantBody = body, status = "Authorization update pending"))
        try { flushGrant(setting()); snapshotUnlocked() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(e); snapshotUnlocked() }
    }
    /** Current permissions can be revoked independently; expanding them requires a fresh owner pairing. */
    suspend fun restrictPermissions(readScopes: Set<String>, writeScopes: Set<String>): JsonObject = lock.withLock {
        CampaignConnectionProtocol.scopes(readScopes); CampaignConnectionProtocol.scopes(writeScopes)
        val value = setting(); credential(value); val g = checkedGrant(value, grant(value))
        require(readScopes.all(activeRead(value)::contains) && writeScopes.all(activeWrite(value)::contains) && writeScopes.all(readScopes::contains)) {
            "This action only revokes permissions. Pair again to grant additional categories."
        }
        val auto = scopes(value.localAutoSyncJson).intersect(writeScopes)
        val body = value.grantBody ?: grantBody(g, auto, readScopes, writeScopes)
        // Revoke local publication and automatic writes before requesting server changes.
        dao.settings(value.copy(localReadJson = CampaignConnectionProtocol.scopeArray(readScopes).toString(),
            localWriteJson = CampaignConnectionProtocol.scopeArray(writeScopes).toString(),
            localAutoSyncJson = CampaignConnectionProtocol.scopeArray(auto).toString(), grantBody = body, status = "Permission revocation pending"))
        try { flushGrant(setting()) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
        snapshotUnlocked()
    }
    private fun grantBody(g: JsonObject, desired: Set<String>, read: Set<String>, write: Set<String>) = OwnerSyncProtocol.canonical(buildJsonObject {
        put("commandId", UUID.randomUUID().toString()); put("expectedRevision", g.getValue("revision"))
        put("autoSyncScopes", CampaignConnectionProtocol.scopeArray(desired.intersect(write)))
        if (read != CampaignConnectionProtocol.parseScopes(g.getValue("readScopes"))) put("readScopes", CampaignConnectionProtocol.scopeArray(read))
        if (write != CampaignConnectionProtocol.parseScopes(g.getValue("writeScopes"))) put("writeScopes", CampaignConnectionProtocol.scopeArray(write))
    })
    private suspend fun flushGrant(value: CampaignConnectionSettings) {
        var current = value
        // At most the preserved previous change and the explicitly selected latest change.
        repeat(2) {
            val body = current.grantBody ?: return
            val sent = Json.parseToJsonElement(body).jsonObject
            val result = checkedGrant(current, request(current, base(current) + "/grant", body))
            require(result.getValue("autoSyncScopes") == sent.getValue("autoSyncScopes"))
            for (scope in listOf("readScopes", "writeScopes")) sent[scope]?.let { require(result.getValue(scope) == it) }
            val actualRead = CampaignConnectionProtocol.parseScopes(result.getValue("readScopes"))
            val actualWrite = CampaignConnectionProtocol.parseScopes(result.getValue("writeScopes"))
            val desiredRead = current.localReadJson?.let(::scopes)?.intersect(actualRead) ?: actualRead
            val desiredWrite = (current.localWriteJson?.let(::scopes)?.intersect(actualWrite) ?: actualWrite).intersect(desiredRead)
            val desiredAuto = scopes(current.localAutoSyncJson).intersect(desiredWrite)
            val actualAuto = CampaignConnectionProtocol.parseScopes(result.getValue("autoSyncScopes"))
            val next = if (desiredAuto != actualAuto || desiredRead != actualRead || desiredWrite != actualWrite)
                grantBody(result, desiredAuto, desiredRead, desiredWrite) else null
            current = setting().copy(grantJson = result.toString(), grantBody = next,
                localAutoSyncJson = CampaignConnectionProtocol.scopeArray(desiredAuto).toString(),
                status = if (next == null) "Authorization saved" else "Authorization update pending")
            dao.settings(current)
        }
    }
    suspend fun snapshot(): JsonObject = lock.withLock { snapshotUnlocked() }
    private suspend fun snapshotUnlocked(): JsonObject {
        val value = setting()
        val commands = dao.commands().filter { belongs(it, value) }
        val current = if (value.campaignId.isBlank()) emptyList() else domain.snapshot(value.campaignId, includeReadOnly = false)
        val localById = current.associateBy { it.kind to it.recordId }
        val secret = if (value.origin.isBlank() || value.campaignId.isBlank()) null else vault.credential(value.origin, value.campaignId)
        val g = grant(value)
        return buildJsonObject {
            put("connected", secret?.credentialId == value.deviceId && value.deviceId.isNotBlank() && secret.expiresAt > now() && !value.revokePending && runCatching { checkedGrant(value, g) }.isSuccess); put("origin", value.origin)
            put("campaignId", value.campaignId); put("selectedProfileId", value.profileId); put("deviceId", value.deviceId)
            put("deviceName", value.deviceName); put("accountLabel", value.accountLabel)
            put("status", if (value.deviceId.isNotBlank() && !value.revokePending && (secret == null || secret.expiresAt <= now() || runCatching { checkedGrant(value, g) }.isFailure)) "Authentication expired" else value.status)
            put("lastError", value.lastError); put("lastSyncAt", value.lastSyncAt); put("lastDeviceSyncAt", value.lastDeviceSyncAt)
            put("syncContinuationRequired", syncContinuationRequired)
            put("serverPendingUnfetched", value.status in setOf("Pending delivery · more queued transactions", "Pending approval · additional queued transactions"))
            put("automaticSyncPaused", CampaignSyncPreferences(context).paused)
            put("pushConfigured", CampaignPushy.configured); put("pushEnabled", CampaignSyncPreferences(context).pushyEnabled)
            put("pushRegistered", CampaignSyncPreferences(context).pushyEnabled && pushStore.registration.isNotEmpty())
            put("lastPushReceivedAt", pushStore.lastReceivedAt); put("lastWorkStartedAt", pushStore.lastWorkStartedAt)
            put("lastRoomCommittedAt", pushStore.lastRoomCommittedAt)
            put("syncTimings", pushStore.timings())
            put("revokePending", value.revokePending); put("grant", g); put("grantPending", value.grantBody != null); put("localAutoSyncScopes", Json.parseToJsonElement(value.localAutoSyncJson))
            put("localReadScopes", CampaignConnectionProtocol.scopeArray(activeRead(value))); put("localWriteScopes", CampaignConnectionProtocol.scopeArray(activeWrite(value)))
            put("profiles", buildJsonArray { campaignDb.recordsDao().allCharacterProfiles().filter { it.id != OwnerConnectionProtocol.PROFILE }.forEach {
                add(buildJsonObject { put("id", it.id); put("name", it.name); put("campaignId", campaignFor(it.id)) })
            } })
            put("pairing", buildJsonObject { put("userCode", if (value.pairingExpiresAt > now()) value.userCode else ""); put("verificationUri", value.verificationUri); put("expiresAt", value.pairingExpiresAt) })
            put("pending", buildJsonArray { commands.filter { it.state in setOf("PENDING_APPROVAL", "PENDING_DELIVERY", "APPLYING", "APPLIED_PENDING", "REJECTED_PENDING", "CONFLICT", "DECISION_CONFLICT") }.forEach { command ->
                val data = Json.parseToJsonElement(command.body).jsonObject
                add(buildJsonObject {
                    put("commandId", command.commandId); put("state", command.state); put("source", data.getValue("source")); put("message", command.message)
                    put("operations", buildJsonArray { data.getValue("operations").jsonArray.forEach { raw -> val op = raw.jsonObject
                        val existing = localById[op.getValue("kind").jsonPrimitive.content to op.getValue("recordId").jsonPrimitive.content]
                        add(buildJsonObject { op.forEach { (k,v) -> put(k,v) }; put("current", existing?.fields ?: JsonNull); put("currentRevision", existing?.revision ?: 0) })
                    } })
                })
            } })
            put("history", buildJsonArray { commands.filter { it.state !in setOf("PENDING_DELIVERY", "PENDING_APPROVAL") }.takeLast(50).asReversed().forEach {
                add(buildJsonObject { put("commandId", it.commandId); put("state", it.state); put("message", it.message); put("receiptJson", it.receiptJson?.let(::JsonPrimitive) ?: JsonNull) })
            } })
        }
    }
    private fun belongs(command: CampaignConnectionCommand, value: CampaignConnectionSettings) =
        command.origin == value.origin && command.campaignId == value.campaignId && command.deviceId == value.deviceId
    /** Every automatic caller shares this pause gate, including Activity/overlay and OS recovery. */
    suspend fun foreground(manual: Boolean = false): JsonObject = lock.withLock {
        val value = setting()
        if (value.deviceId.isBlank()) return@withLock snapshotUnlocked().also { CampaignSyncNotifications.update(context, it) }
        if (!manual && CampaignSyncPreferences(context).paused) {
            CampaignConnectionSyncJob.cancel(context)
            return@withLock snapshotUnlocked().also { CampaignSyncNotifications.update(context, it) }
        }
        automaticOperation = !manual
        try {
            CampaignConnectionSyncJob.schedule(context)
            syncUnlocked()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { failure(OwnerSyncFailure("SYNC_INTERRUPTED")) }; throw e
        } catch (e: Exception) { failure(e); throw e }
        finally { automaticOperation = false; withContext(NonCancellable) { notifySync() } }
        snapshotUnlocked()
    }
    suspend fun sync(): JsonObject = foreground(manual = true)
    /** Token refresh never expands grants; registration requires current, authenticated enrollment. */
    private suspend fun registerPush(value: CampaignConnectionSettings) {
        try { registerPushOptional(value) }
        catch (e: CancellationException) { throw e }
        catch (e: OwnerSyncFailure) {
            if (e.code in setOf("CREDENTIAL_REVOKED_OR_EXPIRED", "AUTHENTICATION_REQUIRED", "CAMPAIGN_NOT_AUTHORIZED", "CAMPAIGN_GRANT_EXPIRED", "AUTOMATIC_SYNC_PAUSED")) throw e
        } catch (_: Exception) {
            // Provider SDK, optional response or local token failures cannot stop journal recovery.
        }
    }
    private suspend fun registerPushOptional(value: CampaignConnectionSettings) {

        requireAutomaticAllowed()
        val requestToken = requestPushToken ?: return
        if (!CampaignSyncPreferences(context).pushyEnabled) {
            disableMessaging(context)
            if (pushStore.registration.isNotEmpty()) {
                try { request(value, base(value) + "/push-token", delete = true); pushStore.clearRegistration() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            return
        }
        if (value.revokePending || value.deviceId.isBlank()) return
        val g = checkedGrant(value, grant(value)); val secret = credential(value)
        val binding = pushBinding(value, g, secret)
        pushStore.bindEnrollment(binding, minOf(secret.expiresAt, g.getValue("expiresAt").jsonPrimitive.long))
        // Registration is asynchronous and consent-bound; provider setup never blocks journal sync.
        requestToken(context, binding)
        val token = pushStore.token() ?: return
        val visible = CampaignSyncNotifications.canShow(context)
        val fingerprint = OwnerSyncProtocol.hash(listOf(value.origin, value.campaignId, value.deviceId,
            g.getValue("grantId").jsonPrimitive.content, g.getValue("revision").toString(), OwnerSyncProtocol.hash(token), visible.toString()).joinToString("\n"))
        if (pushStore.registration == fingerprint) return
        try {
            val result = request(value, base(value) + "/push-token", buildJsonObject {
                put("provider", "pushy"); put("token", token); put("notificationsEnabled", visible)
            }.toString())
            require(result["pushRegistered"]?.jsonPrimitive?.boolean == true)
            pushStore.registration = fingerprint
        } catch (e: CancellationException) { throw e }
        catch (e: OwnerSyncFailure) {
            if (e.code in setOf("CREDENTIAL_REVOKED_OR_EXPIRED", "AUTHENTICATION_REQUIRED", "CAMPAIGN_NOT_AUTHORIZED", "CAMPAIGN_GRANT_EXPIRED", "AUTOMATIC_SYNC_PAUSED")) throw e
            // Push is an optional wake channel; journal and 15-minute recovery remain authoritative.
            pushStore.registration = ""
        } catch (_: java.io.IOException) { pushStore.registration = "" }
    }
    private fun pushBinding(value: CampaignConnectionSettings, grant: JsonObject, secret: OwnerSyncCredential) =
        OwnerSyncProtocol.hash(listOf(value.origin, value.campaignId, value.deviceId, grant.getValue("grantId").jsonPrimitive.content,
            OwnerSyncProtocol.hash(secret.bearer)).joinToString("\n"))
    /** Read-only snapshot; persistence still requires the store's current enrollment binding CAS. */
    suspend fun currentPushBinding(): String? = runCatching {
        val value = setting()
        require(value.deviceId.isNotBlank() && !value.revokePending)
        pushBinding(value, checkedGrant(value, grant(value)), credential(value))
    }.getOrNull()
    suspend fun hasPushEnrollment(): Boolean = runCatching {
        val value = setting()
        require(value.deviceId.isNotBlank() && !value.revokePending)
        credential(value); checkedGrant(value, grant(value)); true
    }.getOrDefault(false)
    // Validation intentionally does not wait behind network synchronization. A stale wake can
    // only schedule a fresh authenticated fetch; the command pipeline rechecks the live grant.
    suspend fun acceptWake(data: Map<String, String>): Boolean {
        if (CampaignSyncPreferences(context).paused || !CampaignSyncPreferences(context).pushyEnabled) return false
        val value = setting()
        if (value.deviceId.isBlank() || value.revokePending) return false
        val verified = runCatching {
            val secret = credential(value); checkedGrant(value, grant(value))
            CampaignWakeProtocol.verified(data, OwnerSyncProtocol.hash(secret.bearer), now())
        }.getOrDefault(false)
        if (!verified || CampaignSyncPreferences(context).paused || !CampaignSyncPreferences(context).pushyEnabled || !pushStore.claimWake(data.getValue("wakeId"), data.getValue("expiresAt").toLong(), now())) return false
        pushStore.recordStage("push_received", now(), data.getValue("wakeId"))
        return true
    }
    private suspend fun failure(error: Exception) {
        val value = setting()
        val code = (error as? OwnerSyncFailure)?.code ?: "CONNECTION_UNVERIFIED"
        val status = when {
            CampaignSyncPreferences(context).paused -> "Paused"
            value.revokePending -> "Server revocation pending"
            code.contains("EXPIRED") || code in setOf("AUTHENTICATION_REQUIRED", "CREDENTIAL_REVOKED_OR_EXPIRED") -> "Authentication expired"
            code.contains("CONFLICT") -> "Conflict requires review"
            else -> "Offline · pending delivery retained"
        }
        dao.settings(value.copy(status = status, lastError = code))
    }
    private suspend fun syncUnlocked() {
        requireAutomaticAllowed()
        syncContinuationRequired = false
        var value = setting(); credential(value)
        if (value.revokePending) { revokeUnlocked(value); return }
        dao.settings(value.copy(status = "Syncing", lastError = "")); notifySync()
        flushGrant(setting()); value = setting()
        val g = checkedGrant(value, request(value, base(value) + "/grant"))
        dao.settings(value.copy(grantJson = g.toString())); value = setting()
        registerPush(value)
        // Durable commit recovery precedes any unrelated publication.
        for (c in dao.commands().filter { belongs(it, value) && it.state == "APPLYING" }) finishLocal(value, c, recoverOnly = true)
        for (c in dao.commands().filter { belongs(it, value) && it.state in setOf("APPLIED_PENDING", "REJECTED_PENDING", "CONFLICT_PENDING") }) flushDecision(value, c)
        var approvalBlocksQueue = false
        // /pending is byte-bounded. A collapsed wake burst must drain subsequent pages,
        // but an unapproved first page must never become a network/CPU retry loop.
        for (page in 0 until 4) {
            val pending = request(value, base(value) + "/pending")
            val hasMore = pending["hasMore"]?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "Invalid pending pagination metadata." }
                it.jsonPrimitive.boolean
            } ?: false
            val rows = pending.getValue("commands").jsonArray.map { it.jsonObject }
            val pageIds = rows.map { CampaignConnectionProtocol.commandId(it.getValue("commandId").jsonPrimitive.content) }
            val previouslyConfirmed = pageIds.filter { dao.command(it)?.receiptJson != null }.toSet()
            for (raw in rows) ingest(value, raw)
            value = setting()
            for (c in dao.commands().filter { belongs(it, value) && it.state == "PENDING_DELIVERY" }) {
                if (authorized(value, c)) { dao.update(c.copy(state = "APPLYING", message = "Authorized transaction committing")); finishLocal(value, requireNotNull(dao.command(c.commandId))) }
                else dao.update(c.copy(state = "PENDING_APPROVAL", message = "Manual approval required by the current grant"))
            }
            for (c in dao.commands().filter { belongs(it, value) && it.state in setOf("APPLIED_PENDING", "REJECTED_PENDING", "CONFLICT_PENDING") }) flushDecision(value, c)
            if (!hasMore) break
            val progressed = pageIds.any { it !in previouslyConfirmed && dao.command(it)?.receiptJson != null }
            if (!progressed) { approvalBlocksQueue = true; break }
            if (page == 3) syncContinuationRequired = true
        }
        publish(value)
        val conflict = dao.commands().any { belongs(it, value) && it.state in setOf("CONFLICT", "DECISION_CONFLICT") }
        val queued = dao.commands().any { belongs(it, value) && it.state in setOf("APPLIED_PENDING", "APPLYING", "REJECTED_PENDING") }
        dao.settings(setting().copy(status = when {
            syncContinuationRequired -> "Pending delivery · more queued transactions"
            conflict -> "Conflict requires review"
            approvalBlocksQueue -> "Pending approval · additional queued transactions"
            queued -> "Pending delivery"
            else -> "Connected"
        }, lastSyncAt = now(), lastError = ""))
    }
    private fun parsed(command: CampaignConnectionCommand): JsonObject = Json.parseToJsonElement(command.body).jsonObject.also {
        require(it.getValue("commandId").jsonPrimitive.content == command.commandId)
        require(CampaignConnectionProtocol.fingerprint(command.campaignId, it) == command.fingerprint)
    }
    private fun decodeOperations(data: JsonElement): List<CampaignSyncOperation> = data.jsonArray.map { raw ->
        val op = raw.jsonObject
        campaignJson.decodeFromJsonElement<CampaignSyncOperation>(JsonObject(op.filterKeys { it in setOf("type", "recordId", "kind", "expectedRevision", "fields") }))
    }
    private fun operations(command: CampaignConnectionCommand) = decodeOperations(parsed(command).getValue("operations"))
    private fun operationCategory(operation: CampaignSyncOperation): String = when (operation.type) {
        "adjust_hp", "set_status" -> "hp"; "adjust_resource" -> if (operation.kind == "companion-resource") "companions" else "resources"
        "adjust_currency" -> "currency"; "move_item", "consume_item" -> "inventory"; "set_ability" -> "character"
        "save_checkpoint" -> "continuity"; else -> CampaignSyncDomain.category(operation.kind)
    }
    private fun validateAuthorization(value: CampaignConnectionSettings, command: CampaignConnectionCommand): Set<String> {
        val data = parsed(command); val g = checkedGrant(value, grant(value))
        require(data.getValue("grantRevision") == g.getValue("revision")) { "Campaign grant changed. Review this transaction again." }
        val ops = operations(command)
        val required = ops.map(::operationCategory).toSet()
        require(required.all(activeWrite(value)::contains)) { "Write scope is disabled." }
        return required
    }
    private fun authorized(value: CampaignConnectionSettings, command: CampaignConnectionCommand): Boolean = runCatching {
        val required = validateAuthorization(value, command); val g = grant(value)
        value.grantBody == null && required.all(CampaignConnectionProtocol.parseScopes(g.getValue("autoSyncScopes"))::contains) && required.all(scopes(value.localAutoSyncJson)::contains)
    }.getOrDefault(false)
    private suspend fun ingest(value: CampaignConnectionSettings, data: JsonObject) {
        val id = CampaignConnectionProtocol.commandId(data.getValue("commandId").jsonPrimitive.content)
        require(data.getValue("campaignId").jsonPrimitive.content == value.campaignId)
        val fingerprint = data.getValue("fingerprint").jsonPrimitive.content
        require(fingerprint == CampaignConnectionProtocol.fingerprint(value.campaignId, data))
        require(data.getValue("source").jsonPrimitive.content.isNotBlank() && data.getValue("source").jsonPrimitive.content.length <= 1900)
        require(data.getValue("status").jsonPrimitive.content in setOf("PENDING_DELIVERY", "PENDING_APPROVAL"))
        val parsedOps = decodeOperations(data.getValue("operations"))
        require(parsedOps.isNotEmpty() && parsedOps.size <= 50)
        dao.command(id)?.let { require(belongs(it, value) && it.fingerprint == fingerprint); return }
        var command = CampaignConnectionCommand(id, value.origin, value.campaignId, value.deviceId, fingerprint, OwnerSyncProtocol.canonical(data), now(),
            "PENDING_APPROVAL", "Awaiting explicit approval or matching Trusted DM Auto-Sync scopes")
        val receipt = campaignDb.recordsDao().receipt(CampaignSyncDomain.receiptId(CampaignConnectionProtocol.localCommand(value.origin, value.campaignId, id)))
        if (receipt != null) {
            val output = visible(value, verifiedLocalOutcome(value, command, receipt))
            command = command.copy(state = "APPLIED_PENDING", decisionBody = decisionBody(command, "APPLIED", receipt.commandId, output), message = "Committed local transaction recovered · readback pending")
        } else if (authorized(value, command)) command = command.copy(state = "PENDING_DELIVERY", message = "Trusted DM Auto-Sync authorized")
        dao.insert(command)
    }
    suspend fun decide(commandId: String, approve: Boolean): JsonObject = lock.withLock {
        val value = setting(); credential(value)
        val command = requireNotNull(dao.command(commandId)); require(belongs(command, value))
        require(command.state in setOf("PENDING_APPROVAL", "PENDING_DELIVERY", "CONFLICT")) { "This transaction already has a decision." }
        if (approve) {
            require(command.state != "CONFLICT") { "Resolve the revision conflict before approving a new command." }
            validateAuthorization(value, command)
            dao.update(command.copy(state = "APPLYING", message = "Player-approved transaction committing")); finishLocal(value, requireNotNull(dao.command(commandId)), manual = true)
        } else dao.update(command.copy(state = "REJECTED_PENDING", decisionBody = decisionBody(command, "REJECTED", null, emptyList()), message = "Rejected locally · server confirmation pending"))
        try { dao.command(commandId)?.takeIf { it.state in setOf("APPLIED_PENDING", "REJECTED_PENDING", "CONFLICT_PENDING") }?.let { flushDecision(value, it) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
        snapshotUnlocked()
    }
    private fun verifiedLocalOutcome(value: CampaignConnectionSettings, command: CampaignConnectionCommand, receipt: CommandReceipt): List<CampaignSyncRecord> {
        val local = CampaignConnectionProtocol.localCommand(value.origin, value.campaignId, command.commandId)
        require(receipt.commandId == CampaignSyncDomain.receiptId(local) && receipt.kind == "CAMPAIGN_SYNC_V2" && receipt.recordId == value.campaignId)
        val expectedFingerprint = campaignHash("campaign-sync-v2:" + OwnerSyncProtocol.canonical(buildJsonObject {
            put("campaignId", value.campaignId); put("source", parsed(command).getValue("source")); put("operations", campaignJson.encodeToJsonElement(operations(command)))
        }))
        require(receipt.fingerprint == expectedFingerprint) { "Local immutable receipt does not match this command." }
        val records = campaignJson.decodeFromString<List<CampaignSyncRecord>>(receipt.outcomeJson)
        require(records.isNotEmpty()) { "A committed transaction must include its actual changed records." }
        return records
    }
    private suspend fun verifyBaseline(value: CampaignConnectionSettings, command: CampaignConnectionCommand) {
        val local = visible(value, domain.snapshot(value.campaignId, includeReadOnly = false)).associateBy { it.kind to it.recordId }
        for (raw in parsed(command).getValue("operations").jsonArray) {
            val operation = raw.jsonObject
            val kind = operation.getValue("kind").jsonPrimitive.content
            val id = operation.getValue("recordId").jsonPrimitive.content
            val existing = local[kind to id]
            require(operation.containsKey("currentFields")) { "Command has no verified local baseline." }
            check((existing?.revision ?: 0) == operation.getValue("expectedRevision").jsonPrimitive.long &&
                (existing?.fields ?: JsonNull) == operation.getValue("currentFields")) { "Local content differs from the published baseline." }
            for (effect in (operation["sideEffects"] as? JsonArray).orEmpty()) {
                val side = effect.jsonObject
                val old = local[side.getValue("kind").jsonPrimitive.content to side.getValue("recordId").jsonPrimitive.content]
                require(side.containsKey("currentFields")) { "Affected record has no verified local baseline." }
                check((old?.revision ?: 0) == side.getValue("expectedRevision").jsonPrimitive.long &&
                    (old?.fields ?: JsonNull) == side.getValue("currentFields")) { "Affected local record differs from its baseline." }
            }
        }
    }
    private fun comparableFields(fields: JsonObject): JsonObject = buildJsonObject {
        fields.filterKeys { it !in setOf("source", "updatedAt", "createdAt") }.forEach { (key,value) ->
            put(key, if (key == "contentJson" && value is JsonPrimitive && value.isString) Json.parseToJsonElement(value.content) else value)
        }
    }
    private fun verifyProposedOutcome(value: CampaignConnectionSettings, command: CampaignConnectionCommand, outputs: List<CampaignSyncRecord>) {
        val expected = mutableListOf<CampaignSyncRecord>()
        for (raw in parsed(command).getValue("operations").jsonArray) {
            val operation = raw.jsonObject
            expected += CampaignSyncRecord(operation.getValue("recordId").jsonPrimitive.content, operation.getValue("kind").jsonPrimitive.content,
                operation.getValue("expectedRevision").jsonPrimitive.long + 1, operation.getValue("proposedFields").jsonObject)
            for (rawEffect in (operation["sideEffects"] as? JsonArray).orEmpty()) {
                val effect = rawEffect.jsonObject
                expected += CampaignSyncRecord(effect.getValue("recordId").jsonPrimitive.content, effect.getValue("kind").jsonPrimitive.content,
                    effect.getValue("expectedRevision").jsonPrimitive.long + 1, effect.getValue("fields").jsonObject)
            }
        }
        val projected = visible(value, expected)
        require(projected.size == expected.size && outputs.size == expected.size && projected.map { it.kind to it.recordId }.distinct().size == projected.size) {
            "The proposed affected identities do not match the authorized transaction."
        }
        val byId = projected.associateBy { it.kind to it.recordId }
        for (saved in outputs) {
            val proposed = requireNotNull(byId[saved.kind to saved.recordId]) { "The domain transaction changed an unproposed record." }
            require(saved.revision == proposed.revision && comparableFields(saved.fields) == comparableFields(proposed.fields)) {
                "The validated local result differs from the proposed transaction. No records were changed."
            }
        }
    }
    private suspend fun finishLocal(value: CampaignConnectionSettings, command: CampaignConnectionCommand, manual: Boolean = false, recoverOnly: Boolean = false) {
        requireAutomaticAllowed()
        require(command.state == "APPLYING"); parsed(command)
        val localId = CampaignConnectionProtocol.localCommand(value.origin, value.campaignId, command.commandId)
        val existing = campaignDb.recordsDao().receipt(CampaignSyncDomain.receiptId(localId))
        if (existing == null && recoverOnly && !authorized(value, command)) {
            dao.update(command.copy(state = "PENDING_APPROVAL", message = "Interrupted authorization requires review")); return
        }
        if (existing == null) {
            validateAuthorization(value, command)
            require(manual || authorized(value, command)) { "Trusted DM Auto-Sync scope is disabled." }
        }
        val receipt = try {
            existing ?: campaignDb.withTransaction {
                requireAutomaticAllowed()
                verifyBaseline(value, command)
                val candidate = domain.apply(value.campaignId, localId, parsed(command).getValue("source").jsonPrimitive.content, operations(command))
                val outputs = visible(value, verifiedLocalOutcome(value, command, candidate))
                verifyProposedOutcome(value, command, outputs)
                require(decisionBody(command, "APPLIED", candidate.commandId, outputs).toByteArray(Charsets.UTF_8).size <= OwnerSyncProtocol.MAX_BODY_BYTES) {
                    "The atomic result exceeds the authenticated transport limit. No local records were changed."
                }
                requireAutomaticAllowed()
                candidate
            }
        } catch (e: CancellationException) { throw e } catch (_: IllegalArgumentException) {
            dao.update(command.copy(state = "CONFLICT_PENDING", decisionBody = decisionBody(command, "CONFLICT", null, emptyList()), message = "Local validation rejected the transaction · no partial changes")); return
        } catch (_: IllegalStateException) {
            dao.update(command.copy(state = "CONFLICT_PENDING", decisionBody = decisionBody(command, "CONFLICT", null, emptyList()), message = "Local records changed · no partial transaction applied")); return
        }
        val output = visible(value, verifiedLocalOutcome(value, command, receipt))
        pushStore.lastRoomCommittedAt = now()
        pushStore.recordStage("room_committed", now(), command.commandId)
        afterLocalCommit()
        dao.update(command.copy(state = "APPLIED_PENDING", decisionBody = decisionBody(command, "APPLIED", receipt.commandId, output), message = "Saved locally · independent server receipt/readback pending"))
    }
    private fun decisionBody(command: CampaignConnectionCommand, status: String, receiptId: String?, records: List<CampaignSyncRecord>) = OwnerSyncProtocol.canonical(buildJsonObject {
        put("commandId", command.commandId); put("fingerprint", command.fingerprint); put("status", status)
        put("localReceiptId", receiptId?.let(::JsonPrimitive) ?: JsonNull); put("records", campaignJson.encodeToJsonElement(records))
    })
    private suspend fun readRecord(value: CampaignConnectionSettings, record: CampaignSyncRecord): CampaignSyncRecord {
        val result = request(value, base(value) + "/records/${record.kind}/${java.net.URLEncoder.encode(record.recordId, "UTF-8")}")
        val raw = (result["record"] as? JsonObject) ?: result.getValue("records").jsonArray.single().jsonObject
        val decoded = campaignJson.decodeFromJsonElement<CampaignSyncRecord>(JsonObject(raw.filterKeys { it in setOf("recordId", "kind", "revision", "fields") }))
        require(decoded.recordId == record.recordId && decoded.kind == record.kind)
        return decoded
    }
    private suspend fun flushDecision(value: CampaignConnectionSettings, command: CampaignConnectionCommand) {
        val body = requireNotNull(command.decisionBody); val decision = Json.parseToJsonElement(body).jsonObject
        val frozenOutputs = campaignJson.decodeFromJsonElement<List<CampaignSyncRecord>>(decision.getValue("records"))
        if (visible(value, frozenOutputs) != frozenOutputs) {
            dao.update(command.copy(state = "DECISION_CONFLICT", message = "Read permission revoked after local commit · original receipt retained privately")); return
        }
        val result = try { request(value, base(value) + "/decisions", body).getValue("receipt").jsonObject }
        catch (e: OwnerSyncFailure) {
            if (e.code in setOf("COMMAND_ID_REUSED", "GRANT_CHANGED", "AUTO_SYNC_DISABLED", "WRITE_SCOPE_DENIED", "READ_SCOPE_DENIED", "CAMPAIGN_NOT_AUTHORIZED") || e.code.endsWith("_CONFLICT")) {
                dao.update(command.copy(state = "DECISION_CONFLICT", message = "Server authorization or revision changed · local receipt retained for recovery")); return
            }; throw e
        }
        require(result.getValue("commandId").jsonPrimitive.content == command.commandId && result.getValue("fingerprint").jsonPrimitive.content == command.fingerprint)
        require(result.getValue("status") == decision.getValue("status") && result.getValue("localReceiptId") == decision.getValue("localReceiptId"))
        require(result.getValue("deviceCredentialId").jsonPrimitive.content == value.deviceId)
        require(request(value, base(value) + "/receipts/${command.commandId}").getValue("receipt") == result)
        if (decision.getValue("status").jsonPrimitive.content == "APPLIED") {
            val saved = requireNotNull(campaignDb.recordsDao().receipt(CampaignSyncDomain.receiptId(CampaignConnectionProtocol.localCommand(value.origin, value.campaignId, command.commandId))))
            val outputs = visible(value, verifiedLocalOutcome(value, command, saved))
            require(decision.getValue("records") == campaignJson.encodeToJsonElement(outputs))
            for (record in outputs) {
                val remote = readRecord(value, record)
                require(remote == record) { "Independent committed-record readback did not match the local transaction." }
                dao.mirror(CampaignConnectionMirror(value.origin, value.campaignId, value.deviceId, record.kind, record.recordId, record.revision,
                    OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(record)))))
            }
        }
        val status = decision.getValue("status").jsonPrimitive.content
        pushStore.recordStage("receipt_verified", now(), command.commandId)
        dao.update(command.copy(state = if (status == "APPLIED") "VERIFIED" else status, receiptJson = OwnerSyncProtocol.canonical(result),
            message = if (status == "APPLIED") "Committed records and immutable receipt independently verified" else if (status == "REJECTED") "Rejection independently verified" else "Conflict independently verified · records retained"))
    }
    private fun visible(value: CampaignConnectionSettings, records: List<CampaignSyncRecord>): List<CampaignSyncRecord> =
        CampaignConnectionProjection.project(records, activeRead(value).also { checkedGrant(value, grant(value)) })
    private suspend fun publish(value: CampaignConnectionSettings) {
        for (publication in dao.publications().filter { it.origin == value.origin && it.campaignId == value.campaignId && it.deviceId == value.deviceId && it.state == "PENDING" }) flushPublication(value, publication)
        val mirrors = dao.mirrors(value.origin, value.campaignId, value.deviceId).associateBy { it.kind to it.recordId }
        val committed = visible(value, domain.snapshot(value.campaignId))
        val identities = committed.map { it.kind to it.recordId }.toSet()
        val readScopes = activeRead(value)
        val removed = mirrors.values.filter { (it.kind to it.recordId) !in identities && canReadKind(it.kind, it.recordId, readScopes) }
        for (batch in removed.chunked(80)) {
            val id = UUID.randomUUID().toString()
            val body = OwnerSyncProtocol.canonical(buildJsonObject {
                put("commandId", id); put("records", JsonArray(emptyList()))
                put("removed", buildJsonArray { batch.forEach { row -> add(buildJsonObject {
                    put("kind", row.kind); put("recordId", row.recordId); put("expectedRevision", row.revision)
                }) } })
            })
            val publication = CampaignConnectionPublication(id, value.origin, value.campaignId, value.deviceId, body)
            dao.insert(publication); flushPublication(value, publication)
        }
        val changed = committed.filter { record ->
            mirrors[record.kind to record.recordId]?.let {
                it.revision == record.revision && it.fingerprint == OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(record)))
            } != true
        }
        // Aggregate only bounded changed records; exact frozen bytes and IDs survive an interrupted upload.
        val batches = mutableListOf<List<CampaignSyncRecord>>()
        var batch = mutableListOf<CampaignSyncRecord>()
        var batchBytes = 0
        for (record in changed) {
            val size = OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(record)).toByteArray(Charsets.UTF_8).size
            require(size <= 48_000) { "A campaign record exceeds the bounded sync format. Its source must be split before publication." }
            if (batch.isNotEmpty() && (batch.size >= 80 || batchBytes + size > 48_000)) {
                batches += batch; batch = mutableListOf(); batchBytes = 0
            }
            batch += record; batchBytes += size
        }
        if (batch.isNotEmpty()) batches += batch
        for (records in batches) {
            val id = UUID.randomUUID().toString()
            val body = OwnerSyncProtocol.canonical(buildJsonObject { put("commandId", id); put("records", campaignJson.encodeToJsonElement(records)) })
            require(body.toByteArray(Charsets.UTF_8).size <= OwnerSyncProtocol.MAX_BODY_BYTES)
            val publication = CampaignConnectionPublication(id, value.origin, value.campaignId, value.deviceId, body)
            dao.insert(publication); flushPublication(value, publication)
        }
        val current = visible(value, domain.snapshot(value.campaignId))
        // The backend refreshes the whole mirror only when every current local identity/revision/hash agrees.
        // A restore or missing row therefore becomes a visible conflict, never a falsely fresh historical value.
        val heartbeat = request(value, base(value) + "/records", OwnerSyncProtocol.canonical(buildJsonObject {
            put("commandId", UUID.randomUUID().toString()); put("records", JsonArray(emptyList()))
            put("snapshotFingerprint", snapshotFingerprint(current))
        }))
        val verifiedAt = heartbeat["publishedAt"]?.jsonPrimitive?.long ?: error("Missing verified synchronization timestamp.")
        require(verifiedAt > 0 && verifiedAt <= now() + 60_000)
        dao.settings(setting().copy(lastDeviceSyncAt = verifiedAt))
    }
    private fun snapshotFingerprint(records: List<CampaignSyncRecord>): String = OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(buildJsonArray {
        records.sortedWith(compareBy({ it.kind }, { it.recordId })).forEach { record -> add(buildJsonObject {
            put("kind", record.kind); put("recordId", record.recordId); put("revision", record.revision)
            put("fingerprint", OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(record.fields)))
        }) }
    }))
    private fun decodeRecord(raw: JsonObject): CampaignSyncRecord = campaignJson.decodeFromJsonElement(JsonObject(raw.filterKeys {
        it in setOf("recordId", "kind", "revision", "fields")
    }))
    private fun canReadKind(kind: String, id: String, scopes: Set<String>): Boolean = when (kind) {
        "character-profile" -> "hp" in scopes || "character" in scopes
        "party-sheet" -> scopes.any { it in setOf("character", "hp", "resources") }
        "audit" -> scopes.containsAll(CampaignConnectionProtocol.categories)
        "reference" -> "continuity" in scopes && (!id.startsWith("archive:") || scopes.containsAll(CampaignConnectionProtocol.categories))
        else -> CampaignSyncDomain.category(kind) in scopes
    }
    private suspend fun flushPublication(value: CampaignConnectionSettings, publication: CampaignConnectionPublication) {
        val data = Json.parseToJsonElement(publication.body).jsonObject
        val records = campaignJson.decodeFromJsonElement<List<CampaignSyncRecord>>(data.getValue("records"))
        val removed = (data["removed"] as? JsonArray).orEmpty().map { it.jsonObject }
        val readScopes = activeRead(value)
        if (visible(value, records) != records || removed.any { !canReadKind(it.getValue("kind").jsonPrimitive.content, it.getValue("recordId").jsonPrimitive.content, readScopes) }) {
            dao.update(publication.copy(state = "SCOPE_REVOKED", receiptJson = null)); return
        }
        val result = request(value, base(value) + "/records", publication.body)
        val readback = request(value, base(value) + "/publications/${publication.commandId}").getValue("records").jsonArray.map { decodeRecord(it.jsonObject) }
        require(readback.sortedWith(compareBy({ it.kind }, { it.recordId })) == records.sortedWith(compareBy({ it.kind }, { it.recordId }))) { "Published revisions failed independent current-record readback." }
        for (row in removed) {
            val path = base(value) + "/records/${row.getValue("kind").jsonPrimitive.content}/${java.net.URLEncoder.encode(row.getValue("recordId").jsonPrimitive.content, "UTF-8")}"
            val error = runCatching { request(value, path) }.exceptionOrNull()
            require(error is OwnerSyncFailure && error.code == "RECORD_NOT_PUBLISHED") { "Removed mirror identity is not independently confirmed absent." }
        }
        db.withTransaction {
            for (row in removed) dao.removeMirror(value.origin, value.campaignId, value.deviceId, row.getValue("kind").jsonPrimitive.content, row.getValue("recordId").jsonPrimitive.content)
            for (record in records) dao.mirror(CampaignConnectionMirror(value.origin, value.campaignId, value.deviceId, record.kind, record.recordId, record.revision,
                OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(record)))))
            dao.update(publication.copy(state = "VERIFIED", receiptJson = OwnerSyncProtocol.canonical(result)))
        }
    }
    suspend fun revoke(): JsonObject = lock.withLock {
        val value = setting(); credential(value)
        dao.settings(value.copy(revokePending = true, localAutoSyncJson = "[]", status = "Server revocation pending"))
        try { revokeUnlocked(setting()) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
        snapshotUnlocked()
    }
    private suspend fun revokeUnlocked(value: CampaignConnectionSettings) {
        // Invalidate queued SDK callbacks before any suspension or remote request. The shared
        // store lock makes token deletion and callback persistence mutually exclusive.
        pushStore.forgetToken(); disableMessaging(context)
        // Best-effort explicit removal precedes credential destruction. Server revocation also
        // deletes registrations atomically if the optional push route is temporarily unavailable.
        try { request(value, base(value) + "/push-token", delete = true) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
        val result = request(value, base(value) + "/devices/revoke", "{}")
        require(result["revoked"]?.jsonPrimitive?.boolean == true && result["credentialId"]?.jsonPrimitive?.content == value.deviceId)
        vault.forget(value.origin, value.campaignId)
        pushStore.forgetToken(); disableMessaging(context)
        dao.settings(value.copy(deviceId = "", revokePending = false, localAutoSyncJson = "[]", grantJson = "{}", status = "Disconnected · server revocation confirmed"))
        CampaignConnectionSyncJob.cancel(context)
    }
    fun close() { db.close(); campaignDb.close(); weaponDb?.close() }
}
