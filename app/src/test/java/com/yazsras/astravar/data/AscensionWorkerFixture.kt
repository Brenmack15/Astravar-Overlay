package com.yazsras.astravar.data

import android.content.Context
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Both clients address the registered Worker. Only the synthetic transport uses stdio. */
internal class AscensionWorkerFixture private constructor(private val context: Context, private val process: Process) : Closeable {
    private val input = process.inputStream.bufferedReader()
    private val output = process.outputStream.bufferedWriter()
    private val pairing = read()
    private val credential = OwnerSyncCredential(pairing.cloudText("device"), pairing.cloudText("continuity"),
        pairing.cloudText("origin"), pairing.getValue("expiresAt").jsonPrimitive.long, pairing.cloudText("bearer"))
    private val database = "ascension-fixture-${UUID.randomUUID()}.db"
    private var cache = CloudSnapshotCache(context, database)
    private val transport = OwnerConnectionTransport { origin, path, token, _, payload ->
        require(origin == credential.origin && validCloudDevicePath(path) && token != null)
        val reply = call(buildJsonObject {
            put("call", "request"); put("route", path); put("credentialId", token.credentialId); put("token", token.bearer)
            if (payload != null) put("body", Json.parseToJsonElement(payload))
        })
        OwnerSyncResponse(reply.getValue("status").jsonPrimitive.int, reply.getValue("json").jsonObject)
    }
    private fun makeClient() = CloudCampaignClient(cache, { CloudSession(credential, null) }, transport,
        mediaTransport = { session, target, kind, upload, bytes ->
            val path = if (upload == null) "primary/$target/thumbnail" else "uploads/$upload?targetKind=$kind&targetId=$target"
            val reply = call(buildJsonObject {
                put("call", "media"); put("route", "/v3/continuities/${session.binding.continuity}/media/$path")
                put("credentialId", session.credential.credentialId); put("token", session.credential.bearer)
                putJsonObject("args") { if (bytes != null) {
                    put("bytes", Base64.getEncoder().encodeToString(bytes)); put("sha256", cloudDigest(bytes))
                } }
            })
            val status = reply.getValue("status").jsonPrimitive.int
            if (status !in 200..299) throw OwnerSyncFailure("MEDIA_HTTP_$status")
            val returned = Base64.getDecoder().decode(reply.cloudText("bytes"))
            if (upload == null) {
                check(reply.cloudText("contentType").substringBefore(';') == "image/png" && reply.cloudText("sha256") == cloudDigest(returned))
            }
            returned
        })
    var client = makeClient()
        private set
    private fun read(): JsonObject {
        val value = Json.parseToJsonElement(requireNotNull(input.readLine()) { "Isolated Worker closed the channel." }).jsonObject
        check("fixtureError" !in value) { "Synthetic Worker could not process this request: ${value["fixtureError"]}" }
        return value
    }
    @Synchronized private fun call(message: JsonObject): JsonObject {
        output.write(message.toString()); output.newLine(); output.flush()
        return read()
    }
    fun signal(name: String, args: JsonObject = buildJsonObject {}): JsonObject = call(buildJsonObject {
        put("call", name); put("args", args)
    })
    fun dm(name: String, args: JsonObject = buildJsonObject {}): JsonObject = call(buildJsonObject {
        put("call", "dm"); put("name", name); put("args", args)
    })
    fun command(operation: String, args: JsonObject, commandId: String = UUID.randomUUID().toString()): JsonObject {
        val state = dm("get_cloud_state", buildJsonObject { put("limit", 1) })
        return dm("submit_cloud_command", buildJsonObject { putJsonObject("command") {
            put("version", 1); put("commandId", commandId); put("epoch", state.getValue("epoch"))
            put("expectedRevision", state.getValue("revision")); put("operation", operation); put("args", args)
        } })
    }
    fun receipt(id: String): JsonObject = dm("get_cloud_receipt", buildJsonObject { put("commandId", id) })
    fun record(id: String): JsonObject? = dm("get_cloud_state", buildJsonObject { put("id", id) })
        .getValue("records").jsonArray.singleOrNull()?.jsonObject
    suspend fun refresh(): CloudSnapshot = client.refresh(openExisting = true)
    fun resetCache() {
        cache.close()
        check(context.deleteDatabase(database))
        cache = CloudSnapshotCache(context, database)
        client = makeClient()
    }
    override fun close() {
        output.close()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        input.close(); cache.close(); context.deleteDatabase(database)
    }
    companion object {
        fun open(context: Context): AscensionWorkerFixture {
            val entry = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
                .map { File(it, "backend/owner-bridge/fixtures/native-cloud-wire.mjs") }.first(File::isFile)
            val process = ProcessBuilder("node", entry.absolutePath, "--exercise-gameplay")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try { return AscensionWorkerFixture(context, process) }
            catch (failure: Throwable) { process.destroyForcibly(); throw failure }
        }
    }
}
