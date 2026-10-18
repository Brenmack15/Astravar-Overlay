package com.yazsras.astravar.data

import android.content.Context
import androidx.room.*
import com.yazsras.astravar.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.util.UUID

/** A public, unauthenticated FICTIONAL test protocol. Its source label is not sender authentication. */
data class RemoteAbilityPayload(
    val commandId: String, val sessionId: String, val ability: String,
    val expectedRevision: Long, val score: Int
)

object RemoteStatProtocol {
    const val PROFILE_ID = "remote-stat-test-v1"
    const val SOURCE = "ChatGPT fictional test"
    const val TYPE = "astravar.remote-ability"
    const val RESULT_TYPE = "astravar.remote-ability-result"
    private val abilities = setOf("STR", "DEX", "CON", "INT", "WIS", "CHA")
    private const val MAX_REVISION = 9_007_199_254_740_990L
    private fun uuid(value: String) = UUID.fromString(value).toString() == value
    private fun validate(value: RemoteAbilityPayload) {
        require(uuid(value.commandId) && uuid(value.sessionId)) { "Use canonical UUIDs." }
        require(value.ability in abilities && value.expectedRevision in 0..MAX_REVISION && value.score in 0..1000)
    }
    fun encode(value: RemoteAbilityPayload): String {
        validate(value)
        return "{\"type\":\"$TYPE\",\"v\":1,\"commandId\":\"${value.commandId}\",\"sessionId\":\"${value.sessionId}\"," +
            "\"profileId\":\"$PROFILE_ID\",\"ability\":\"${value.ability}\",\"expectedRevision\":${value.expectedRevision}," +
            "\"score\":${value.score},\"source\":\"$SOURCE\"}"
    }
    /** Canonical byte equality also rejects duplicate keys, extra fields and number/type coercion. */
    fun parse(detail: String): RemoteAbilityPayload? {
        if (!detail.contains(TYPE)) return null
        require(detail.length <= 1000) { "Remote update exceeds the fictional test limit." }
        val json = Json.parseToJsonElement(detail) as? JsonObject ?: error("Expected a remote-update object.")
        if ((json["type"] as? JsonPrimitive)?.content != TYPE) return null
        fun string(key: String): String {
            val value = json[key] as? JsonPrimitive ?: error("Missing $key.")
            require(value.isString) { "Expected text for $key." }; return value.content
        }
        fun integer(key: String): Long {
            val value = json[key] as? JsonPrimitive ?: error("Missing $key.")
            require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*"))) { "Expected a nonnegative integer for $key." }
            return value.content.toLong()
        }
        require(integer("v") == 1L && string("profileId") == PROFILE_ID && string("source") == SOURCE)
        val score = integer("score"); require(score <= 1000)
        val result = RemoteAbilityPayload(string("commandId"), string("sessionId"), string("ability"), integer("expectedRevision"), score.toInt())
        require(encode(result) == detail) { "Remote payload must use the exact canonical v1 schema." }
        return result
    }
    fun outcome(commandId: String, sessionId: String, status: String): String {
        require(uuid(commandId) && uuid(sessionId) && status in setOf("APPLIED", "REJECTED"))
        return "{\"type\":\"$RESULT_TYPE\",\"v\":1,\"commandId\":\"$commandId\",\"sessionId\":\"$sessionId\",\"status\":\"$status\"}"
    }
    fun localCommandId(commandId: String, sessionId: String): String = UUID.nameUUIDFromBytes(
        "astravar-remote-stat-v1:$sessionId:$commandId".toByteArray(Charsets.UTF_8)).toString()
}

@Entity(tableName = "remote_stat_settings")
data class RemoteStatSettings(
    @PrimaryKey val id: Int = 1, val enabled: Boolean = false, val sessionId: String = "",
    val origin: String = PublicSandbox.DEFAULT_ORIGIN, val status: String = "Off · disposable profile only"
)

@Entity(tableName = "remote_stat_inbox", indices = [Index("sessionId")])
data class RemoteStatCommand(
    @PrimaryKey val commandId: String, val sessionId: String, val origin: String, val eventId: String,
    val payload: String, val ability: String, val proposedScore: Int, val expectedRevision: Long,
    val source: String = RemoteStatProtocol.SOURCE,
    val currentScore: Int? = null, val currentRevision: Long = 0, val currentMagicModifier: Int = 0,
    val receivedAt: Long, val status: String = "PENDING", val message: String = "Awaiting player confirmation",
    val approvedAbilityJson: String? = null, val decidedAt: Long? = null, val ackStatus: String = "NONE"
)

/** Each attempt keeps its original UUID/body even if a response is lost. */
@Entity(tableName = "remote_stat_ack_attempts", indices = [Index("commandId")])
data class RemoteStatAck(
    @PrimaryKey val attemptId: String, val commandId: String, val origin: String, val expectedRevision: Long,
    val detail: String, val body: String, val fingerprint: String, val createdAt: Long,
    val status: String = "PENDING", val receiptJson: String? = null, val error: String = ""
)

@Dao
interface RemoteStatDao {
    @Query("SELECT * FROM remote_stat_settings WHERE id=1") fun settings(): kotlinx.coroutines.flow.Flow<RemoteStatSettings?>
    @Query("SELECT * FROM remote_stat_settings WHERE id=1") suspend fun getSettings(): RemoteStatSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun settings(value: RemoteStatSettings)
    @Query("SELECT * FROM remote_stat_inbox ORDER BY receivedAt DESC,commandId") fun commands(): kotlinx.coroutines.flow.Flow<List<RemoteStatCommand>>
    @Query("SELECT * FROM remote_stat_inbox WHERE commandId=:id") suspend fun command(id: String): RemoteStatCommand?
    @Query("SELECT * FROM remote_stat_inbox WHERE status='APPLYING' ORDER BY receivedAt,commandId") suspend fun applying(): List<RemoteStatCommand>
    @Query("SELECT * FROM remote_stat_inbox WHERE ackStatus='PENDING' ORDER BY decidedAt,commandId") suspend fun needingAck(): List<RemoteStatCommand>
    @Insert suspend fun insert(value: RemoteStatCommand)
    @Update suspend fun update(value: RemoteStatCommand)
    @Query("SELECT * FROM remote_stat_ack_attempts WHERE commandId=:id ORDER BY createdAt,attemptId") suspend fun acks(id: String): List<RemoteStatAck>
    @Insert suspend fun insert(value: RemoteStatAck)
    @Update suspend fun update(value: RemoteStatAck)
}

/** Additive, separate fictional inbox; existing campaign/sandbox schemas and migrations are untouched. */
@Database(entities = [RemoteStatSettings::class, RemoteStatCommand::class, RemoteStatAck::class], version = 1, exportSchema = true)
abstract class RemoteStatDb : RoomDatabase() {
    abstract fun remote(): RemoteStatDao
    companion object {
        fun open(context: Context): RemoteStatDb = Room.databaseBuilder(context.applicationContext,
            RemoteStatDb::class.java, "astravar-remote-stat-fictional.db").build()
    }
}

class RemoteStatRepository(
    private val db: RemoteStatDb,
    private val records: CampaignRecordsRepository,
    private val sandbox: SandboxSyncRepository,
    private val transport: SandboxTransport = SandboxHttpTransport(),
    debugEnabled: Boolean = BuildConfig.DEBUG,
    private val afterCampaignSave: suspend () -> Unit = {}
) {
    private val dao = db.remote()
    private val lock = Mutex()
    private val available = BuildConfig.DEBUG && debugEnabled
    val settings = dao.settings()
    val commands = dao.commands()
    private fun requireDebug() { check(available) { "Remote stat tests are available only in debug builds." } }
    private suspend fun requireEnabled(): RemoteStatSettings {
        requireDebug(); return requireNotNull(dao.getSettings()).also { check(it.enabled) { "Remote updates are off." } }
    }
    private suspend fun ability(key: String) = records.characterAbilities.first().firstOrNull {
        it.profileId == RemoteStatProtocol.PROFILE_ID && it.key == key
    }
    private suspend fun status(value: String) {
        dao.settings((dao.getSettings() ?: RemoteStatSettings()).copy(status = value.take(300)))
    }
    suspend fun setEnabled(enabled: Boolean) = lock.withLock {
        requireDebug()
        val old = dao.getSettings() ?: RemoteStatSettings()
        if (!enabled) { dao.settings(old.copy(enabled = false, status = "Off · pending decisions preserved")); return@withLock }
        val selected = sandbox.settings.first()?.origin ?: PublicSandbox.DEFAULT_ORIGIN
        require(selected == PublicSandbox.DEFAULT_ORIGIN) { "Select the existing public disposable sandbox origin first." }
        // A deterministic receipt makes interrupted opt-in safe, without creating any scores or mechanics.
        if (records.characterProfiles.first().none { it.id == RemoteStatProtocol.PROFILE_ID }) {
            records.saveProfile(CharacterProfile(id = RemoteStatProtocol.PROFILE_ID, name = "Disposable stat test"),
                source = "Player enabled a disposable remote-stat test", commandId = "remote-stat-profile-v1")
        }
        dao.settings(old.copy(enabled = true, sessionId = old.sessionId.ifEmpty { UUID.randomUUID().toString() },
            status = "On · waiting for a fictional update"))
    }
    private suspend fun ingest(setting: RemoteStatSettings) {
        for (event in sandbox.events(setting.origin).first().sortedBy { it.resultingRevision }) {
            val payload = try { RemoteStatProtocol.parse(event.detail) } catch (_: Exception) { null } ?: continue
            if (payload.sessionId != setting.sessionId || event.kind != "new_fact" || event.commandId != payload.commandId) continue
            val old = dao.command(payload.commandId)
            if (old != null) {
                check(old.payload == event.detail && old.sessionId == setting.sessionId && old.origin == event.origin) {
                    "Remote command ID collision; existing decision preserved."
                }
                continue
            }
            val current = ability(payload.ability)
            dao.insert(RemoteStatCommand(payload.commandId, payload.sessionId, event.origin, event.eventId, event.detail,
                payload.ability, payload.score, payload.expectedRevision, currentScore = current?.score,
                currentRevision = current?.revision ?: 0, currentMagicModifier = current?.magicModifier ?: 0,
                receivedAt = System.currentTimeMillis(),
                message = if ((current?.revision ?: 0) != payload.expectedRevision) "Local value changed · reject and send a new command" else "Awaiting player confirmation"))
        }
    }
    private fun validateCommand(command: RemoteStatCommand) {
        val payload = requireNotNull(RemoteStatProtocol.parse(command.payload))
        require(command.origin == PublicSandbox.DEFAULT_ORIGIN && command.source == RemoteStatProtocol.SOURCE &&
            payload.commandId == command.commandId && payload.sessionId == command.sessionId &&
            payload.ability == command.ability && payload.score == command.proposedScore &&
            payload.expectedRevision == command.expectedRevision) { "Invalid durable fictional proposal; nothing was changed." }
    }
    private fun provenance(command: RemoteStatCommand) = "Player-confirmed ${RemoteStatProtocol.SOURCE} · command ${command.commandId}"
    private suspend fun finishApply(command: RemoteStatCommand): RemoteStatCommand {
        validateCommand(command)
        val frozen = campaignJson.decodeFromString<CharacterAbility>(requireNotNull(command.approvedAbilityJson))
        check(frozen.profileId == RemoteStatProtocol.PROFILE_ID && frozen.key == command.ability && frozen.score == command.proposedScore)
        val localId = RemoteStatProtocol.localCommandId(command.commandId, command.sessionId)
        try {
            records.saveAbility(frozen, command.expectedRevision.takeUnless { it == 0L }, provenance(command), localId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Optimistic concurrency remains inside saveAbility's atomic Room transaction.
            val current = ability(command.ability)
            if (records.receipt(localId) == null && (current?.revision ?: 0) != command.expectedRevision) {
                return command.copy(status = "CONFLICT", approvedAbilityJson = null, currentScore = current?.score,
                    currentRevision = current?.revision ?: 0, currentMagicModifier = current?.magicModifier ?: 0,
                    message = "Local value changed · nothing applied. Reject and send a new command.").also { dao.update(it) }
            }
            throw e // Durable APPLYING intent/receipt can safely recover after a restart.
        }
        afterCampaignSave()
        return command.copy(status = "APPLIED", ackStatus = "PENDING", message = "Applied locally · confirmation pending delivery")
            .also { dao.update(it) }
    }
    suspend fun apply(commandId: String): RemoteStatCommand = lock.withLock {
        val setting = requireEnabled()
        val command = requireNotNull(dao.command(commandId))
        validateCommand(command)
        check(command.sessionId == setting.sessionId && command.origin == setting.origin)
        if (command.status == "APPLIED") return@withLock command
        if (command.status == "APPLYING") return@withLock finishApply(command)
        check(command.status == "PENDING") { "This proposal cannot be applied; reject it and send a new command." }
        val current = ability(command.ability)
        if ((current?.revision ?: 0) != command.expectedRevision) {
            return@withLock command.copy(status = "CONFLICT", currentScore = current?.score,
                currentRevision = current?.revision ?: 0, currentMagicModifier = current?.magicModifier ?: 0,
                message = "Local value changed · nothing applied. Reject and send a new command.").also { dao.update(it) }
        }
        val frozen = (current ?: CharacterAbility(command.ability, RemoteStatProtocol.PROFILE_ID)).copy(score = command.proposedScore)
        val approved = command.copy(status = "APPLYING", approvedAbilityJson = campaignJson.encodeToString(frozen),
            decidedAt = System.currentTimeMillis(), message = "Player approved · recovering local transaction")
        // Persist approval BEFORE the campaign transaction. Its own receipt closes the cross-database crash window.
        dao.update(approved)
        finishApply(approved)
    }
    suspend fun reject(commandId: String): RemoteStatCommand = lock.withLock {
        val setting = requireEnabled()
        val command = requireNotNull(dao.command(commandId))
        validateCommand(command)
        check(command.sessionId == setting.sessionId && command.origin == setting.origin)
        if (command.status == "REJECTED") return@withLock command
        check(command.status in setOf("PENDING", "CONFLICT")) { "An approved transaction cannot be rejected after saving." }
        command.copy(status = "REJECTED", ackStatus = "PENDING", decidedAt = System.currentTimeMillis(),
            message = "Rejected · confirmation pending delivery").also { dao.update(it) }
    }
    private suspend fun request(origin: String, path: String, body: String? = null): JSONObject {
        val response = transport.request(origin, path, body)
        require(response.json.optString("mode") == PublicSandbox.MODE)
        if (response.status !in 200..299) {
            val e = response.json.optJSONObject("error")
            throw SandboxFailure(response.status, e?.optString("code") ?: "HTTP_ERROR", e?.optString("message") ?: "Sandbox request failed.")
        }
        return response.json
    }
    private fun validateAttempt(command: RemoteStatCommand, ack: RemoteStatAck) {
        validateCommand(command)
        require(command.origin == PublicSandbox.DEFAULT_ORIGIN && ack.origin == command.origin)
        require(command.status in setOf("APPLIED", "REJECTED") && ack.commandId == command.commandId)
        val detail = RemoteStatProtocol.outcome(command.commandId, command.sessionId, command.status)
        require(ack.detail == detail && ack.expectedRevision in 1..9_007_199_254_740_990L)
        require(UUID.fromString(ack.attemptId).toString() == ack.attemptId)
        val expectedBody = JSONObject().put("campaignId", PublicSandbox.CAMPAIGN).put("connectionId", PublicSandbox.CONNECTION)
            .put("kind", "new_fact").put("detail", detail).put("commandId", ack.attemptId)
            .put("expectedRevision", ack.expectedRevision).put("confirmed", true).toString()
        require(ack.body == expectedBody && ack.fingerprint == PublicSandbox.fingerprint("new_fact", detail, ack.expectedRevision)) {
            "Invalid fictional receipt outbox; nothing was uploaded."
        }
    }
    private suspend fun flushAcks(setting: RemoteStatSettings) {
        for (command in dao.needingAck()) {
            check(command.sessionId == setting.sessionId && command.origin == setting.origin)
            var pending = dao.acks(command.commandId).lastOrNull { it.status == "PENDING" }
            if (pending == null) {
                val revision = sandbox.endpoint(setting.origin).first()?.remoteRevision ?: error("Read the sandbox before sending a confirmation.")
                val detail = RemoteStatProtocol.outcome(command.commandId, command.sessionId, command.status)
                val id = UUID.randomUUID().toString()
                val body = JSONObject().put("campaignId", PublicSandbox.CAMPAIGN).put("connectionId", PublicSandbox.CONNECTION)
                    .put("kind", "new_fact").put("detail", detail).put("commandId", id)
                    .put("expectedRevision", revision).put("confirmed", true).toString()
                pending = RemoteStatAck(id, command.commandId, setting.origin, revision, detail, body,
                    PublicSandbox.fingerprint("new_fact", detail, revision), System.currentTimeMillis())
                dao.insert(pending)
            }
            val ack = pending
            validateAttempt(command, ack)
            try {
                val receipt = try {
                    request(ack.origin, "/api/v1/campaigns/${PublicSandbox.CAMPAIGN}/commands/${ack.attemptId}/receipt")
                } catch (e: SandboxFailure) { if (e.status == 404 && e.code == "COMMAND_NOT_FOUND") null else throw e }
                    ?: request(ack.origin, PublicSandbox.BASE + "/milestones", ack.body)
                require(receipt.getString("campaignId") == PublicSandbox.CAMPAIGN && receipt.getString("connectionId") == PublicSandbox.CONNECTION &&
                    receipt.getString("status") == "COMMITTED" && receipt.getString("commandId") == ack.attemptId &&
                    receipt.getString("payloadFingerprint") == ack.fingerprint && receipt.getLong("priorRevision") == ack.expectedRevision &&
                    receipt.getLong("resultingRevision") == ack.expectedRevision + 1)
                require(UUID.fromString(receipt.getString("eventId")).toString() == receipt.getString("eventId") && receipt.getString("recordedAt").isNotBlank())
                db.withTransaction {
                    dao.update(ack.copy(status = "COMMITTED", receiptJson = receipt.toString(), error = ""))
                    dao.update(command.copy(ackStatus = "COMMITTED", message = "${if (command.status == "APPLIED") "Applied" else "Rejected"} · fictional confirmation delivered"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val conflict = e is SandboxFailure && e.status == 409 && e.code == "REVISION_CONFLICT"
                dao.update(ack.copy(status = if (conflict) "REVISION_CONFLICT" else "PENDING", error = (e.message ?: "Outcome unknown").take(300)))
                // Only a definitive revision rejection permits a fresh receipt attempt on the NEXT check.
                throw e
            }
            // This read is outside the receipt try/catch: an offline history refresh must never
            // downgrade a verified committed receipt or change its original UUID/body.
            sandbox.refresh()
        }
    }
    /** Foreground opt-in polling; no background service, campaign export or automatic stat application. */
    suspend fun refresh() = lock.withLock {
        requireDebug()
        val setting = dao.getSettings() ?: return@withLock
        if (!setting.enabled) return@withLock
        try {
            // Both local recovery and cached delivery work before attempting the network.
            dao.applying().forEach { finishApply(it) }
            ingest(setting)
            require((sandbox.settings.first()?.origin ?: PublicSandbox.DEFAULT_ORIGIN) == setting.origin) {
                "Sandbox origin changed · remote updates paused."
            }
            sandbox.refresh()
            ingest(setting)
            flushAcks(setting)
            status("Connected · disposable updates only")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status("Offline / paused · decisions preserved: ${e.message}"); throw e }
    }
}
