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
private val cloudReadRoute = Regex("(?:capabilities|commands|review-status|settings|context|(?:receipts|sheet|links)/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}|state\\?limit=[0-9]{1,3}(?:&after=[A-Za-z0-9][A-Za-z0-9._:-]{0,127}&atRevision=[0-9]+&atEpoch=[0-9]+&grantRevision=[0-9]+|&id=[A-Za-z0-9][A-Za-z0-9._:-]{0,127}&activeOnly=false&atRevision=[0-9]+&atEpoch=[0-9]+&grantRevision=[0-9]+)?)")

private val formProjectionPath = Regex("forms/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val actorProjectionPath = Regex("(?:spellbook|actions|pool-history)/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}(?:\\?after=[A-Za-z0-9][A-Za-z0-9._:-]{0,127}&atRevision=[0-9]+&atEpoch=[0-9]+&grantRevision=[0-9]+)?")

internal fun validCloudDevicePath(path: String): Boolean {
    if (path.substringBefore('?').split('/').any { it == "." || it == ".." }) return false
    val prefix = cloudPrefix.matchAt(path, 0) ?: return false
    val route = path.substring(prefix.range.last + 1)
    return cloudReadRoute.matches(route) || formProjectionPath.matches(route) || actorProjectionPath.matches(route) || validRecordViewSuffix(route) || validArchiveSuffix(route) || validApprovalViewSuffix(route)
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
        val baselineRequest=suffix=="capabilities" || suffix.startsWith("state?limit=20")
        if(result.status==401 || baselineRequest && result.status in setOf(403,404)) conceal()
        else if(result.status==403 && cache.workspace()?.visible==true) {
            // An operation-level denial may simply reflect a narrower grant. Verify the grant before retaining its old rows.
            try {
                val check=request(s,"capabilities")
                validateGrant(s,check)
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){conceal()}
        }
        val code = (result.json["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        if (suffix == "commands" && body != null && result.status in setOf(400, 403, 404, 409, 422) &&
            code != null && code != "COMMAND_ID_REUSED") {
            val id = Json.parseToJsonElement(body).jsonObject.cloudText("commandId")
            cache.acknowledge(s.binding, id); notifyCache()
        }
        throw OwnerSyncFailure(code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "CLOUD_HTTP_${result.status}")
    }

    private fun conceal() {
        if(cache.workspace()?.visible==true){cache.hide();notifyCache()}
    }

    private fun validateGrant(s:CloudSession,proof:JsonObject) {
        val saved=cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
        if(saved.binding!=s.binding || proof.cloudText("subject")!=s.binding.subject ||
            proof.cloudText("continuityId")!=s.binding.continuity || proof.getValue("grantRevision").jsonPrimitive.long!=saved.version.grantRevision ||
            proof.getValue("epoch").jsonPrimitive.long!=saved.version.epoch) {
            conceal();throw OwnerSyncFailure("CLOUD_GRANT_UNVERIFIED")
        }
    }

    suspend fun workspace(): CloudSnapshot? = withContext(Dispatchers.IO) {
        lock.withLock {
            if(cache.workspace()?.visible==true) {
                try { current() } catch (_: OwnerSyncFailure) { /* current() concealed the unbound snapshot */ }
                val checked=cache.workspace()
                if(checked?.visible==true && checked.version.grantExpiresAt<=now()){cache.hide();notifyCache()}
            }
            cache.workspace()
        }
    }

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
                if(v==null && old?.visible==true && old.binding==s.binding && old.version==page.version) {
                    if(current(explicit).binding!=s.binding)throw OwnerSyncFailure("CLOUD_BINDING_CHANGED")
                    cache.checked(now())
                    return requireNotNull(cache.workspace())
                }
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

    suspend fun actorHistory(actorId: String, kinds: Set<String>, limit: Int = 50, before: CloudHistoryCursor? = null,
        reviewed: CloudSnapshot? = null): List<JsonObject> = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (!w.visible || w.version.grantExpiresAt <= now()) throw OwnerSyncFailure("CLOUD_GRANT_UNVERIFIED")
            if (reviewed != null && (reviewed.binding != w.binding || reviewed.version != w.version)) throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            cache.actorHistory(s.binding,now(),actorId,kinds,limit,before)
        }
    }

    suspend fun pending(): List<JsonObject> = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace()
            if (w?.visible != true || w.version.grantExpiresAt <= now()) emptyList() else cache.pending(s.binding)
        }
    }

    suspend fun uncertain(operation: String? = null, actorId: String? = null): JsonObject? = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val w = cache.workspace()
            if (w?.visible != true || w.version.grantExpiresAt <= now()) null else cache.uncertain(s.binding, operation, actorId)
        }
    }

    /** Re-read the submitted command and current state separately. A dropped response leaves the original local intent for inspection. */
    suspend fun submit(operation: String, args: JsonObject, reviewed: CloudSnapshot, commandId:String=UUID.randomUUID().toString()): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            require(cloudIdentifier.matches(commandId))
            val previous=cache.command(s.binding,commandId)
            if(previous!=null){
                require(previous.cloudText("operation")==operation && previous["args"]==args &&
                    previous.getValue("epoch").jsonPrimitive.long==reviewed.version.epoch &&
                    previous.getValue("expectedRevision").jsonPrimitive.long==reviewed.version.revision && reviewed.binding==s.binding)
                return@withLock retryLocked(s,previous)
            }
            val w = cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (!w.visible || w.version != reviewed.version || w.binding != reviewed.binding ||
                w.version.grantExpiresAt <= now()) throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            val actorId = args["actorId"]?.jsonPrimitive?.contentOrNull
            if (cache.uncertain(s.binding, operation, actorId) != null) throw OwnerSyncFailure("CLOUD_PRIOR_COMMAND_UNVERIFIED")
            val command = buildJsonObject {
                put("version", 1); put("commandId", commandId)
                put("epoch", w.version.epoch); put("expectedRevision", w.version.revision)
                put("operation", operation); put("args", args)
            }
            cache.remember(s.binding, command)
            notifyCache()
            val response = request(s, "commands", command.toString())
            require(response.cloudText("commandId") == command.cloudText("commandId"))
            if (response.cloudText("status") == "PENDING_APPROVAL") {
                cache.acknowledge(s.binding, commandId); notifyCache(); response
            } else verifyLocked(s, commandId)
        }
    }

    suspend fun retry(id: String): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val command = cache.command(s.binding,id) ?: throw OwnerSyncFailure("COMMAND_UNKNOWN")
            retryLocked(s,command)
        }
    }

    private suspend fun retryLocked(s:CloudSession,command:JsonObject):JsonObject {
        val id=command.cloudText("commandId")
        try { return verifyLocked(s,id) } catch (failure: OwnerSyncFailure) {
            if (failure.code != "COMMAND_UNKNOWN") throw failure
        }
        val state = refreshLocked(s,false)
        if (state.version.epoch != command.getValue("epoch").jsonPrimitive.long ||
            state.version.revision != command.getValue("expectedRevision").jsonPrimitive.long)
            throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
        val result = request(s,"commands",command.toString())
        require(result.cloudText("commandId")==id)
        return if (result.cloudText("status") == "PENDING_APPROVAL") {
            cache.acknowledge(s.binding, id); notifyCache(); result
        } else verifyLocked(s,id)
    }

    suspend fun reviewCount(): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = current()
            val response=request(s,"review-status")
            response.getValue("actionableCount").jsonPrimitive.int.also { require(it >= 0) }
        }
    }

    suspend fun verify(id: String): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock { verifyLocked(current(), id) }
    }

    private suspend fun verifyLocked(s: CloudSession, id: String): JsonObject {
        require(cloudIdentifier.matches(id))
        val response = request(s, "receipts/$id")
        require(response.cloudText("commandId") == id)
        if (response.cloudText("status") == "PENDING_APPROVAL") {
            cache.acknowledge(s.binding, id); notifyCache(); return response
        }
        val receipt = response["receipt"] as? JsonObject ?: throw OwnerSyncFailure("CLOUD_RECEIPT_UNVERIFIED")
        require(receipt.cloudText("commandId") == id && receipt.cloudText("status") == response.cloudText("status"))
        require(receipt.cloudText("transactionId").isNotBlank())
        val state = refreshLocked(s, false)
        val epoch = receipt.getValue("epoch").jsonPrimitive.long
        val revision = receipt.getValue("revision").jsonPrimitive.long
        require(state.version.epoch == epoch && state.version.revision >= revision)
        if (receipt.cloudText("status") == "COMMITTED") {
            val erased=receipt["deletedIds"] as? JsonArray ?: (receipt["outcome"] as? JsonObject)?.get("deletedIds") as? JsonArray
            for (marker in erased.orEmpty()) {
                val recordId=marker.jsonPrimitive.content
                require(cloudIdentifier.matches(recordId))
                val v=state.version
                val body=request(s,"state?limit=1&id=$recordId&activeOnly=false&atRevision=${v.revision}&atEpoch=${v.epoch}&grantRevision=${v.grantRevision}")
                require(cloudPage(body,s.binding,null,v,now()).records.isEmpty())
            }
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
            require(suffix == "context" || suffix == "settings" || formProjectionPath.matches(suffix) || suffix.startsWith("sheet/") || actorProjectionPath.matches(suffix) || suffix.startsWith("links/") || validRecordViewSuffix(suffix) || validArchiveSuffix(suffix) || validApprovalViewSuffix(suffix))
            val result = request(s, suffix)
            if (result.getValue("epoch").jsonPrimitive.long != w.version.epoch ||
                result.getValue("revision").jsonPrimitive.long != w.version.revision) throw OwnerSyncFailure("SNAPSHOT_CHANGED")
            val check = request(s, "capabilities")
            validateGrant(s,check)
            if(check.getValue("revision").jsonPrimitive.long!=w.version.revision)throw OwnerSyncFailure("SNAPSHOT_CHANGED")
            result
        }
    }

    suspend fun cachedImage(id:String):ByteArray? = withContext(Dispatchers.IO) {
        lock.withLock { cache.image(current().binding,id,now()) }
    }

    suspend fun image(snapshot: CloudSnapshot, target: String, kind: String, uploadId: String? = null, bytes: ByteArray? = null): ByteArray =
        withContext(Dispatchers.IO) { lock.withLock {
            val s=current()
            val w=cache.workspace() ?: throw OwnerSyncFailure("CLOUD_NOT_SELECTED")
            if (!w.visible || w.binding!=snapshot.binding || w.version!=snapshot.version || w.version.grantExpiresAt<=now())
                throw OwnerSyncFailure("CLOUD_REVIEW_STALE")
            val result=try { mediaTransport(s,target,kind,uploadId,bytes) } catch(err:OwnerSyncFailure) {
                if(err.code=="MEDIA_HTTP_401")conceal()
                if(err.code in setOf("MEDIA_HTTP_403","MEDIA_HTTP_404"))validateGrant(s,request(s,"capabilities"))
                throw err
            }
            val read=request(s,"capabilities")
            validateGrant(s,read)
            if(read.getValue("epoch").jsonPrimitive.long!=w.version.epoch || read.getValue("revision").jsonPrimitive.long!=w.version.revision)
                throw OwnerSyncFailure("SNAPSHOT_CHANGED")
            if(uploadId==null)cache.saveImage(w,target,result)
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
