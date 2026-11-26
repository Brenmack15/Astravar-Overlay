package com.yazsras.astravar.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudWorkerInteropTest {
    @Test fun nativeCacheAndActualWorkerConvergeAcrossDisconnectRetryAndRevocation(): Unit = runBlocking {
        val driver = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it,"backend/owner-bridge/fixtures/native-cloud-wire.mjs") }.first(File::isFile)
        val process = ProcessBuilder("node",driver.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val input = process.inputStream.bufferedReader()
        val output = process.outputStream.bufferedWriter()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheFile = "wire-${UUID.randomUUID()}.db"
        var cache = CloudSnapshotCache(context,cacheFile)
        try {
            fun read(): JsonObject {
                val obj = Json.parseToJsonElement(requireNotNull(input.readLine()) { "Worker exited before sending a response" }).jsonObject
                require("fixtureError" !in obj) { "Worker rejected the isolated protocol fixture." }
                return obj
            }
            fun call(message: JsonObject): JsonObject {
                output.write(message.toString()); output.newLine(); output.flush()
                return read()
            }
            val pairing = read()
            val credential = OwnerSyncCredential(pairing.cloudText("device"),pairing.cloudText("continuity"),pairing.cloudText("origin"),
                pairing.getValue("expiresAt").jsonPrimitive.long,pairing.cloudText("bearer"))
            fun dm(name:String,args:JsonObject) = call(buildJsonObject { put("call","dm"); put("name",name); put("args",args) })
            fun defineActor(id:String) = buildJsonObject {
                put("actorId",id); put("name","Fixture actor"); put("role","player"); put("rulesProfile","legacy_2014")
                put("level",3); putJsonObject("abilities") { put("INT",13) }
            }
            fun change(score:Int) = buildJsonObject { put("actorId","person"); put("ability","INT"); put("score",score) }
            fun dmCommit(revision:Int, operation:String, args:JsonObject) = dm("submit_cloud_command",buildJsonObject {
                putJsonObject("command") {
                    put("version",1); put("epoch",1); put("expectedRevision",revision); put("commandId",UUID.randomUUID().toString())
                    put("operation",operation); put("args",args)
                }
            })
            var dropResponse = false
            var deviceRequests = 0
            val transport = OwnerConnectionTransport { origin, path, token, _, body ->
                deviceRequests++
                require(origin == credential.origin && validCloudDevicePath(path) && token != null)
                val wire = call(buildJsonObject {
                    put("call","request"); put("route",path); put("credentialId",token.credentialId); put("token",token.bearer)
                    if (body != null) put("body",Json.parseToJsonElement(body))
                })
                if (path.endsWith("/commands") && dropResponse) { dropResponse=false; throw OwnerSyncFailure("NETWORK_UNAVAILABLE") }
                OwnerSyncResponse(wire.getValue("status").jsonPrimitive.int,wire.getValue("json").jsonObject)
            }
            fun client() = CloudCampaignClient(cache,{ CloudSession(credential,null) },transport)
            assertEquals("COMMITTED",dmCommit(0,"define_actor",defineActor("person")).cloudText("status"))
            assertEquals(0, deviceRequests)
            var android = client()
            val first = android.refresh(openExisting=true)
            assertEquals(1L,first.version.revision)
            assertEquals("person",android.records(kinds=setOf("actor")).single().cloudText("id"))
            cache.close()
            dmCommit(1,"set_actor_ability",change(15))
            cache = CloudSnapshotCache(context,cacheFile)
            android = client()
            assertEquals(13,android.record("person")!!.getValue("data").jsonObject.getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
            val second = android.refresh()
            val sheet = android.projection(second,"sheet/person").getValue("sheet").jsonObject
            assertEquals(15,sheet.getValue("abilities").jsonObject.getValue("INT").jsonObject.getValue("effective").jsonPrimitive.int)
            dropResponse = true
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { android.submit("set_actor_ability",change(17),second) } }
            val id = android.pending().single().cloudText("commandId")
            val receipt = android.retry(id)
            assertEquals("COMMITTED",receipt.cloudText("status"))
            assertEquals(3L,receipt.getValue("revision").jsonPrimitive.long)
            assertEquals(receipt,dm("get_cloud_receipt",buildJsonObject { put("commandId",id) }))
            assertEquals(17,android.record("person")!!.getValue("data").jsonObject.getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
            assertTrue(android.pending().isEmpty())
            val definition=buildJsonObject {
                put("actorId","person");put("attackId","training");put("name","Fixture action");put("source","Synthetic fixture")
                put("delivery","melee");put("resolution","attack");put("timing","attack");put("fixedHit",3);put("reach",5)
                putJsonArray("components") { addJsonObject {
                    put("id","c1");put("name","Fixture damage");put("type","force");put("dice",1);put("sides",6);put("flat",2)
                } }
            }
            assertEquals("COMMITTED",dmCommit(3,"define_attack",definition).cloudText("status"))
            val actionState=android.refresh()
            val action=android.projection(actionState,"actions/person").getValue("rows").jsonArray.single().jsonObject
            assertEquals(3,action.getValue("hit").jsonPrimitive.int)
            val damage=android.submit("roll_damage_only",buildJsonObject {
                put("actorId","person");put("attackId","training");put("rollId","observed")
                putJsonArray("damage"){addJsonObject{
                    put("id","c1");putJsonObject("evidence"){put("origin","player_physical");put("faces",JsonArray(listOf(JsonPrimitive(4))))}
                }}
            },actionState)
            assertEquals("COMMITTED",damage.cloudText("status"))
            assertTrue(android.records(kinds=setOf("attack_attempt")).isEmpty())
            assertEquals("Fixture damage: (4) + 2 = 6",android.record("observed")!!.getValue("data").jsonObject.cloudText("text"))
            call(buildJsonObject { put("call","revoke") })
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { android.refresh() } }
            assertFalse(android.workspace()!!.visible)
            assertTrue(cache.fenced(credential.campaignId)); assertNull(android.record("person"))
        } finally {
            output.close()
            if (!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly()
            input.close(); cache.close(); context.deleteDatabase(cacheFile)
        }
    }
}
