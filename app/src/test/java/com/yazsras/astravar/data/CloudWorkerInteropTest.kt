package com.yazsras.astravar.data

import androidx.test.core.app.ApplicationProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import com.yazsras.astravar.ui.AstravarTheme
import com.yazsras.astravar.ui.CloudWorkspace
import com.yazsras.astravar.ui.createAstravarComposeRule
import org.robolectric.Robolectric
import org.junit.Rule
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
    @get:Rule val compose=createAstravarComposeRule()
    @Test fun nativeCacheAndActualWorkerConvergeAcrossDisconnectRetryAndRevocation(): Unit = runBlocking {
        val driver = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
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
            fun client() = CloudCampaignClient(cache,{ CloudSession(credential,null) },transport,
                mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
            assertEquals("COMMITTED",dmCommit(0,"define_actor",defineActor("person")).cloudText("status"))
            fun gp(n:Int) = buildJsonObject { put("cp",0);put("sp",0);put("ep",0);put("gp",n);put("pp",0) }
            fun commitFromDm(rev:Int,op:String,properties:JsonObject): JsonObject {
                val result=dmCommit(rev,op,properties)
                assertEquals("COMMITTED",result.cloudText("status"))
                assertEquals(result,dm("get_cloud_receipt",buildJsonObject { put("commandId",result.cloudText("commandId")) }))
                return result
            }
            commitFromDm(1,"define_wallet",buildJsonObject {
                put("walletId","wallet");put("title","Test coins");put("holderId","person");put("shared",false)
                put("coins",gp(11));put("source","Fixture")
            })
            commitFromDm(2,"define_item_definition",buildJsonObject {
                put("definitionId","lens-type");put("name","Fixture lens");put("category","tool");put("description","Test lens");put("rarity",JsonNull)
                put("stackable",false);put("wearable",false);put("handheld",true);put("attunement","none");put("source","Fixture")
            })
            commitFromDm(3,"purchase_item",buildJsonObject {
                put("itemId","lens");put("definitionId","lens-type");put("ownerId","person");put("quantity",1);put("source","Fixture purchase")
                put("walletId","wallet");put("price",gp(4));put("sellerId",JsonNull)
            })
            commitFromDm(4,"define_resource",buildJsonObject {
                put("actorId","person");put("poolId","charge");put("name","Fixture charge");put("type","charge")
                put("maximum",4);put("current",3);put("recovery","none");put("source","Fixture")
            })
            commitFromDm(5,"spend_resource",buildJsonObject { put("actorId","person");put("poolId","charge");put("amount",1) })
            commitFromDm(6,"record_chronicle_event",buildJsonObject {
                put("eventId","visit");put("title","Fixture visit");put("narrative","An isolated visitor returned the lens.")
                put("category","conversation");put("status","confirmed");put("worldTime",JsonNull);put("correctsId",JsonNull)
                putJsonArray("subjects"){addJsonObject{put("kind","actor");put("id","person")}};put("source","Fixture")
            })
            assertEquals(0, deviceRequests)
            var android = client()
            val first = android.refresh(openExisting=true)
            assertEquals(7L,first.version.revision)
            assertEquals(7,android.record("wallet")!!.getValue("data").jsonObject.getValue("coins").jsonObject.getValue("gp").jsonPrimitive.int)
            assertEquals(2,android.record("charge")!!.getValue("data").jsonObject.getValue("current").jsonPrimitive.int)
            assertEquals(1,android.records(kinds=setOf("item_instance")).count{it.cloudText("id")=="lens"})
            assertEquals(1,android.records(kinds=setOf("timeline_event")).count{it.cloudText("id")=="visit"})
            assertEquals("person",android.records(kinds=setOf("actor")).single().cloudText("id"))
            cache.close()
            dmCommit(7,"set_actor_ability",change(15))
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
            assertEquals(9L,receipt.getValue("revision").jsonPrimitive.long)
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
            assertEquals("COMMITTED",dmCommit(9,"define_attack",definition).cloudText("status"))
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
            call(buildJsonObject{put("call","requireReview")})
            val beforeReview=deviceRequests
            val proposed=dmCommit(damage.getValue("revision").jsonPrimitive.int,"set_actor_ability",change(18))
            assertEquals("PENDING_APPROVAL",proposed.cloudText("status"))
            assertEquals(beforeReview,deviceRequests)
            val proposalId=proposed.cloudText("commandId")
            assertEquals(1,android.reviewCount())
            assertEquals("PENDING_APPROVAL",android.verify(proposalId).cloudText("status"))
            assertEquals(17,android.record("person")!!.getValue("data").jsonObject.getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
            val approved=call(buildJsonObject{
                put("call","ownerVerify")
                putJsonObject("args"){put("commandId",proposalId);put("digest",proposed.cloudText("digest"))}
            })
            assertEquals("COMMITTED",approved.cloudText("status"))
            assertEquals(approved,android.verify(proposalId))
            assertEquals(0,android.reviewCount())
            assertEquals(18,android.record("person")!!.getValue("data").jsonObject.getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val latest=requireNotNull(android.workspace())
                activity.get().setContent { AstravarTheme { CloudWorkspace(android,latest) } }
                compose.waitUntil(20_000){compose.onAllNodesWithText("Fixture actor").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("navigation-Inventory").performClick()
                compose.waitUntil(20_000){compose.onAllNodesWithText("Fixture lens").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Party quantity · 1").assertExists()
                compose.onNodeWithTag("navigation-Chronicle").performClick()
                compose.waitUntil(20_000){compose.onAllNodesWithText("Fixture visit").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Fixture visit").assertExists()
            } finally {activity.pause().stop().destroy()}
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
