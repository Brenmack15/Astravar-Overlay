package com.yazsras.astravar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

private val cloudPrefix = Regex("/v3/continuities/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/")
private val cloudReadRoute = Regex("(?:capabilities|commands|review-status|context|(?:receipts|sheet|links)/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}|state\\?limit=[0-9]{1,3}(?:&after=[A-Za-z0-9][A-Za-z0-9._:-]{0,127}&atRevision=[0-9]+&atEpoch=[0-9]+&grantRevision=[0-9]+|&id=[A-Za-z0-9][A-Za-z0-9._:-]{0,127}&activeOnly=false&atRevision=[0-9]+&atEpoch=[0-9]+&grantRevision=[0-9]+)?)")

private val actorProjectionPath = Regex("(?:spellbook|actions)/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}(?:\\?after=[A-Za-z0-9][A-Za-z0-9._:-]{0,127}&atRevision=[0-9]+&atEpoch=[0-9]+&grantRevision=[0-9]+)?")

internal fun validCloudDevicePath(path: String): Boolean {
    if (path.contains("..")) return false
    val prefix = cloudPrefix.matchAt(path, 0) ?: return false
    val route = path.substring(prefix.range.last + 1)
    return cloudReadRoute.matches(route) || actorProjectionPath.matches(route) || validRecordViewSuffix(route)
}

internal class CloudSession(val credential: OwnerSyncCredential, val access: String?) {
    val binding = CloudBinding(credential.origin, credential.campaignId, "device:${credential.credentialId}")
}

/** v3 uses one authenticated origin and a separately scoped cloud grant. There is deliberately no cutover request here. */
internal class CloudCampaignClient(
    internal val cache: CloudSnapshotCache,
    private val session: suspend () -> CloudSession?,
    private val transport: OwnerConnectionTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val mediaTransport: suspend (CloudSession,String,String,String?,ByteArray?) -> ByteArray = { s,id,kind,upload,bytes -> transferCloudImage(s,id,kind,upload,bytes) }
) {
    private val lock = Mutex()
    private val changed = MutableStateFlow(0L)
    val changes = changed.asStateFlow()
    private fun notifyCache() { changed.value++ }

    private suspend fun current(explicit: Boolean = false): CloudSession {
        val saved = cache.workspace()
        val current = session()
        if (current == null || current.credential.expiresAt <= now()) {
            if (saved?.visible == true) { cache.hide(); notifyCache() }
            throw OwnerSyncFailure("AUTHENTICATION_REQUIRED")
        }
        if (saved != null && saved.binding != current.binding) {
            if (saved.visible) { cache.hide(); notifyCache() }
            if (!explicit) throw OwnerSyncFailure("CLOUD_BINDING_CHANGED")
        }
        return current
    }

    private suspend fun request(s: CloudSession, suffix: String, body: String? = null): JsonObject {
        val path = "/v3/continuities/${s.binding.continuity}/$suffix"
        require(validCloudDevicePath(path))
        val result = transport.request(s.binding.origin, path, s.credential, s.access, body)
        if (result.status in 200..299) return result.json
        if (result.status == 401 || result.status in setOf(403, 404) && suffix.startsWith("state?limit=20")) {
            if (cache.workspace()?.visible == true) { cache.hide(); notifyCache() }
        }
        val code = (result.json["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        throw OwnerSyncFailure(code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "CLOUD_HTTP_${result.status}")
    }

    suspend fun workspace(): CloudSnapshot? = withContext(Dispatchers.IO) { cache.workspace() }

    suspend fun refresh(openExisting: Boolean = false): CloudSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current(explicit = openExisting)
            if (!openExisting && cache.workspace()?.binding != s.binding) throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            try { refreshLocked(s, openExisting) }
            catch (failure: OwnerSyncFailure) {
                if (failure.code != "SNAPSHOT_CHANGED") throw failure
                refreshLocked(s, openExisting)
            }
        }
    }

    private suspend fun refreshLocked(s: CloudSession, explicit: Boolean): CloudSnapshot {
        val token = UUID.randomUUID().toString()
        var expected: CloudVersion? = null
        var after: String? = null
        cache.clearIncomplete()
        try {
            do {
                val v = expected
                val path = if (v == null) "state?limit=20" else
                    "state?limit=20&after=$after&atRevision=${v.revision}&atEpoch=${v.epoch}&grantRevision=${v.grantRevision}"
                val response = request(s, path)
                val page = cloudPage(response, s.binding, after, v, now())
                val old = cache.workspace()
                if (old?.visible == true && (old.binding != s.binding ||
                    page.version.grantRevision != old.version.grantRevision || page.version.epoch != old.version.epoch)) {
                    cache.hide(); notifyCache()
                }
                expected = page.version
                cache.stage(token, page)
                after = page.nextAfter
            } while (after != null)
            if (current(explicit).binding != s.binding) throw OwnerSyncFailure("CLOUD_BINDING_CHANGED")
            cache.publish(s.binding, requireNotNull(expected), now(), token, explicit)
            notifyCache()
            return requireNotNull(cache.workspace())
        } finally { cache.discard(token) }
    }

    /** Offline reads still require this installation's same, locally valid credential and last observed grant. */
    suspend fun validUntil(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            minOf(w.version.grantExpiresAt, s.credential.expiresAt)
        }
    }

    suspend fun record(id: String): JsonObject? = withContext(Dispatchers.IO) {
        lock.withLock { cache.record(current().binding, now(), id) }
    }

    suspend fun records(after: String = "", kinds: Set<String> = emptySet(), actorId: String? = null, query: String = ""): List<JsonObject> = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (w.version.grantExpiresAt <= now() || !w.visible) {
                if (w.visible) { cache.hide(); notifyCache() }
                throw OwnerSyncFailure("CLOUD_GRANT_UNVERIFIED")
            }
            cache.records(s.binding, now(), after, kinds = kinds, actorId = actorId, query = query)
        }
    }

    suspend fun pending(): List<JsonObject> = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace()
            if (w?.visible != true || w.version.grantExpiresAt <= now()) emptyList() else cache.pending(s.binding)
        }
    }

    /** Re-read the submitted command and current state separately. A dropped response leaves the original local intent for inspection. */
    suspend fun submit(operation: String, args: JsonObject, reviewed: CloudSnapshot): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (!w.visible || w.version != reviewed.version || w.binding != reviewed.binding ||
                w.version.grantExpiresAt <= now()) throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            val command = buildJsonObject {
                put("version", 1); put("commandId", UUID.randomUUID().toString())
                put("epoch", w.version.epoch); put("expectedRevision", w.version.revision)
                put("operation", operation); put("args", args)
            }
            cache.remember(s.binding, command)
            notifyCache()
            val response = request(s, "commands", command.toString())
            require(response.cloudText("commandId") == command.cloudText("commandId"))
            if (response.cloudText("status") == "PENDING_APPROVAL") response else verifyLocked(s, command.cloudText("commandId"))
        }
    }

    suspend fun retry(id: String): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val command = cache.pending(s.binding).firstOrNull { it.cloudText("commandId") == id }
                ?: throw OwnerSyncFailure("COMMAND_UNKNOWN")
            try { return@withLock verifyLocked(s, id) } catch (failure: OwnerSyncFailure) {
                if (failure.code != "COMMAND_UNKNOWN") throw failure
            }
            val state = refreshLocked(s, false)
            if (state.version.epoch != command.getValue("epoch").jsonPrimitive.long ||
                state.version.revision != command.getValue("expectedRevision").jsonPrimitive.long)
                throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            val result = request(s, "commands", command.toString())
            require(result.cloudText("commandId") == id)
            if (result.cloudText("status") == "PENDING_APPROVAL") result else verifyLocked(s, id)
        }
    }

    suspend fun reviewCount(): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val path = "/v3/continuities/${s.binding.continuity}/review-status"
            val response = transport.request(s.binding.origin, path, s.credential, s.access, null)
            if (response.status != 200) throw OwnerSyncFailure("REVIEW_COUNT_UNAVAILABLE")
            response.json.getValue("actionableCount").jsonPrimitive.int.also { require(it >= 0) }
        }
    }

    suspend fun verify(id: String): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock { verifyLocked(current(), id) }
    }

    private suspend fun verifyLocked(s: CloudSession, id: String): JsonObject {
        require(cloudIdentifier.matches(id))
        val response = request(s, "receipts/$id")
        require(response.cloudText("commandId") == id)
        if (response.cloudText("status") == "PENDING_APPROVAL") return response
        val receipt = response["receipt"] as? JsonObject ?: throw OwnerSyncFailure("CLOUD_RECEIPT_UNVERIFIED")
        require(receipt.cloudText("commandId") == id && receipt.cloudText("status") == response.cloudText("status"))
        require(receipt.cloudText("transactionId").isNotBlank())
        val state = refreshLocked(s, false)
        val epoch = receipt.getValue("epoch").jsonPrimitive.long
        val revision = receipt.getValue("revision").jsonPrimitive.long
        require(state.version.epoch == epoch && state.version.revision >= revision)
        if (receipt.cloudText("status") == "COMMITTED") {
            for (change in receipt.getValue("changes").jsonArray) {
                val after = change.jsonObject.getValue("after")
                val before = change.jsonObject["before"] as? JsonObject
                val expected = after as? JsonObject
                val recordId = (expected ?: requireNotNull(before)).cloudText("id")
                val actual = cache.record(s.binding, now(), recordId)
                if (expected == null) require(actual == null)
                else if (expected.cloudText("life") == "archived" && actual == null) {
                    val v = state.version
                    val page = request(s, "state?limit=1&id=$recordId&activeOnly=false&atRevision=${v.revision}&atEpoch=${v.epoch}&grantRevision=${v.grantRevision}")
                    val found = cloudPage(page, s.binding, null, v, now()).records.singleOrNull()
                    // The independent active view proves removal. Verify the archived body wherever lifecycle permission permits.
                    if (found != null) require(found.getValue("revision").jsonPrimitive.long > expected.getValue("revision").jsonPrimitive.long || found == expected)
                } else {
                    requireNotNull(actual)
                    val observed = actual.getValue("revision").jsonPrimitive.long
                    val recorded = expected.getValue("revision").jsonPrimitive.long
                    require(observed > recorded || actual == expected) { "Cloud readback differs from the command receipt." }
                }
            }
        }
        cache.forget(id); notifyCache()
        return response
    }

    suspend fun projection(snapshot: CloudSnapshot, suffix: String): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (!w.visible || w.version.grantExpiresAt <= now() || snapshot.binding != s.binding || w.version != snapshot.version)
                throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            require(suffix == "context" || suffix.startsWith("sheet/") || actorProjectionPath.matches(suffix) || suffix.startsWith("links/") || validRecordViewSuffix(suffix))
            val result = request(s, suffix)
            if (result.getValue("epoch").jsonPrimitive.long != w.version.epoch ||
                result.getValue("revision").jsonPrimitive.long != w.version.revision) throw OwnerSyncFailure("SNAPSHOT_CHANGED")
            val check = request(s, "capabilities")
            if (check.cloudText("subject") != s.binding.subject || check.cloudText("continuityId") != s.binding.continuity ||
                check.getValue("grantRevision").jsonPrimitive.long != w.version.grantRevision) {
                cache.hide(); notifyCache(); throw OwnerSyncFailure("CLOUD_GRANT_UNVERIFIED")
            }
            result
        }
    }

    suspend fun image(snapshot: CloudSnapshot, target: String, kind: String, uploadId: String? = null, bytes: ByteArray? = null): ByteArray =
        withContext(Dispatchers.IO) { lock.withLock {
            val s=current()
            val w=cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (!w.visible || w.binding!=snapshot.binding || w.version!=snapshot.version || w.version.grantExpiresAt<=now())
                throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            val result=mediaTransport(s,target,kind,uploadId,bytes)
            val read=request(s,"capabilities")
            if(read.cloudText("subject")!=w.binding.subject || read.getValue("grantRevision").jsonPrimitive.long!=w.version.grantRevision){
                cache.hide();notifyCache();throw OwnerSyncFailure("CLOUD_GRANT_UNVERIFIED")
            }
            if(read.getValue("epoch").jsonPrimitive.long!=w.version.epoch || read.getValue("revision").jsonPrimitive.long!=w.version.revision)
                throw OwnerSyncFailure("SNAPSHOT_CHANGED")
            result
        } }

    suspend fun capabilities(): JsonObject = withContext(Dispatchers.IO) { lock.withLock { request(current(), "capabilities") } }

    companion object {
        @Volatile private var instance: CloudCampaignClient? = null
        fun open(context: Context): CloudCampaignClient = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val enrollment = CampaignConnectionDb.open(app).connection()
                val vault = OwnerSyncCredentialVault(app)
                CloudCampaignClient(CloudSnapshotCache(app), {
                    val local = enrollment.settings()
                    if (local == null || local.revokePending || local.deviceId.isBlank()) null
                    else vault.credential(local.origin, local.campaignId)?.takeIf { it.credentialId == local.deviceId }
                        ?.let { CloudSession(it, vault.serviceAccess(local.origin, local.campaignId)) }
                }, OwnerConnectionHttpsTransport()).also { instance = it }
            }
        }

        fun fencesLegacy(context: Context, continuity: String): Boolean =
            continuity.isNotBlank() && open(context).cache.fenced(continuity)
    }
}
