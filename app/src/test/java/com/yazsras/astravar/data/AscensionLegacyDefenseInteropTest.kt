package com.yazsras.astravar.data

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.yazsras.astravar.ui.*
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35],qualifiers="w440dp-h960dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AscensionLegacyDefenseInteropTest {
    @get:Rule val compose = createAstravarComposeRule()
    @Test fun restoredHistoricalDefenseReversibleScoreAndPhysicalChargesMatchNativeAndAuthoritativeReaders(): Unit = runBlocking {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "scripts/tests/ascension_reviewed_defense.py").isFile }
        val folder = Files.createTempDirectory("astravar-defense-source-").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val cacheName = "reviewed-defense-${UUID.randomUUID()}.db"
        val cache = CloudSnapshotCache(context, cacheName)
        try {
            val repository = ReforgeRepository(db)
            val source = RuleSource("original-rule-ward", "Approved synthetic ward record", Acceptance.ACCEPTED_HOMEBREW, 7)
            val defense = EffectDefinition("source-force-ward", "Force resistance", source,
                EffectPayload.Defense(DamageKind.FORCE, DefenseKind.RESISTANCE), requiresAttunement = false)
            val bonus = EffectDefinition("source-intelligence", "Equipment Intelligence", source,
                EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.ADD, 1, "INT"), requiresAttunement = false)
            fun activation(id:String,pool:String) = ItemActivation(id,"Use "+id,"Action",mapOf(pool to 1))
            val ward = ItemDefinition("ward-def", "Fixture ward", effects = listOf(defense,bonus), description = "Reviewed fixture ward",
                activations=listOf(activation("use-ward","ward-charges")))
            val lantern = ItemDefinition("lantern-def", "Fixture lamp", description = "Reviewed fixture lamp",
                activations=listOf(activation("use-lamp","lamp-charges")))
            val char = ReforgeCharacter("recovery-actor","Historical fixture", rules = RuleVersion(RuleFamily.LEGACY_2014), level = 4,
                baseAbilities = Stat.entries.associateWith { if (it==Stat.INT) 13 else null }, currentHp = 17, baseMaxHp = 20,
                baseAc=12, reviewedProficiency=ReviewedProficiency(2,source), itemDefinitions=listOf(ward,lantern),
                items=listOf(ItemInstance("ward",ward.id,ItemState.WORN,pools=listOf(ResourcePool("ward-charges","Ward charges",4,2))),
                             ItemInstance("lantern",lantern.id,ItemState.CARRIED,pools=listOf(ResourcePool("lamp-charges","Lamp charges",3,null)))))
            val native = repository.create(char,"historical-fixture-created").character
            val sourcePath = File(folder, "character.json")
            sourcePath.writeText(repository.export())
            val playable = Json.parseToJsonElement(sourcePath.readText()).jsonObject.getValue("characters").jsonArray.single().jsonObject
            val nativeJson = Json.parseToJsonElement(playable.getValue("characterJson").jsonPrimitive.content).jsonObject
            val payload = nativeJson.getValue("itemDefinitions").jsonArray.first().jsonObject.getValue("effects").jsonArray.first()
                .jsonObject.getValue("payload").jsonObject
            assertEquals("com.yazsras.astravar.rules.reforge.EffectPayload.Defense", payload.cloudText("type"))
            assertEquals("FORCE",payload.cloudText("damageType"))
            val restored = File(folder, "isolated")
            val conversionLog = File(folder,"mapping.log")
            val conversion = ProcessBuilder("python3",File(root,"scripts/tests/ascension_reviewed_defense.py").path, sourcePath.path, restored.path)
                .redirectErrorStream(true).redirectOutput(conversionLog).start()
            try {
                assertTrue(conversion.waitFor(90,TimeUnit.SECONDS))
                assertEquals(conversionLog.readText().takeLast(2200),0,conversion.exitValue())
            } finally { conversion.destroyForcibly() }

            val process = ProcessBuilder("node",File(root,"backend/owner-bridge/fixtures/native-cloud-wire.mjs").path,
                "--reviewed-legacy",restored.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try {
                val input = process.inputStream.bufferedReader()
                val output = process.outputStream.bufferedWriter()
                fun receive(): JsonObject = Json.parseToJsonElement(requireNotNull(input.readLine())).jsonObject.also {
                    require("fixtureError" !in it) { "Isolated service rejected the native fixture" }
                }
                val linkLock=Any()
                fun call(message:JsonObject): JsonObject = synchronized(linkLock) {
                    output.write(message.toString());output.newLine();output.flush()
                    receive()
                }
                val binding = receive()
                val credential = OwnerSyncCredential(binding.cloudText("device"),binding.cloudText("continuity"),binding.cloudText("origin"),
                    binding.getValue("expiresAt").jsonPrimitive.long,binding.cloudText("bearer"))
                val transport = OwnerConnectionTransport { origin,path,token,_,body ->
                    require(origin == credential.origin && validCloudDevicePath(path) && token != null)
                    val response = call(buildJsonObject {
                        put("call","request");put("route",path);put("credentialId",token.credentialId);put("token",token.bearer)
                        if (body!=null) put("body",Json.parseToJsonElement(body))
                    })
                    OwnerSyncResponse(response.getValue("status").jsonPrimitive.int,response.getValue("json").jsonObject)
                }
                val client = CloudCampaignClient(cache,{CloudSession(credential,null)},transport,
                    mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
                fun dm(name:String,args:JsonObject) = call(buildJsonObject {put("call","dm");put("name",name);put("args",args)})
                suspend fun verifyProjection(expected: ReforgeCharacter, revision: Long) {
                    val current = client.refresh(openExisting = true)
                    assertEquals(2L,current.version.epoch)
                    assertEquals(revision,current.version.revision)
                    val sheet = client.projection(current,"sheet/${expected.id}").getValue("sheet").jsonObject
                    val independent = dm("get_cloud_actor_sheet",buildJsonObject { put("id",expected.id) }).getValue("sheet").jsonObject
                    assertEquals(independent,sheet)
                    val original = CharacterCalculator.calculate(expected)
                    val intel = sheet.getValue("abilities").jsonObject.getValue("INT").jsonObject
                    assertEquals(13,intel.getValue("base").jsonPrimitive.int)
                    assertEquals(original.abilities.getValue(Stat.INT).effective.value,intel.getValue("effective").jsonPrimitive.int)
                    val actual = sheet.getValue("defenses").jsonArray.map(JsonElement::jsonObject)
                    assertEquals(original.defenses.map { it.type.name.lowercase() to it.kind.name.lowercase() },
                        actual.map { it.cloudText("type") to it.cloudText("kind") })
                    val restoredSource = requireNotNull(client.record(defense.id)).getValue("data").jsonObject
                    assertEquals(scopeSource(source),restoredSource.getValue("sourceRecord"))
                    assertEquals("ward",restoredSource.cloudText("sourceItemId"))
                    assertEquals(actual.isNotEmpty(),original.effectStatuses.any { it.active && it.effectId == defense.id && it.itemInstanceId == "ward" })
                    if(actual.isNotEmpty()) {
                        assertEquals(defense.id,actual.single().cloudText("source"))
                        assertEquals(bonus.id,intel.getValue("sources").jsonArray.single().jsonObject.cloudText("id"))
                        assertTrue(original.abilities.getValue(Stat.INT).effective.explanation.any { it.applied && it.sourceId == source.id })
                    }
                    expected.items.forEach { item ->
                        val expectedPool = item.pools.single()
                        val instance = requireNotNull(client.record(item.id)).getValue("data").jsonObject
                        assertEquals(expected.id,instance.cloudText("ownerId"))
                        assertEquals(item.definitionId,instance.cloudText("definitionId"))
                        assertEquals(expectedPool.id,instance.cloudText("resourcePoolId"))
                        val pool = requireNotNull(client.record(expectedPool.id)).getValue("data").jsonObject
                        assertEquals(expectedPool.maximum,pool.getValue("maximum").jsonPrimitive.intOrNull)
                        assertEquals(expectedPool.current,pool.getValue("current").jsonPrimitive.intOrNull)
                    }
                    assertEquals(JsonNull,sheet.getValue("temporaryHp"))
                }
                verifyProjection(native,0)
                val packed = GameplayRules.equip(native,"ward",ItemState.CARRIED)
                for ((mode,state) in listOf("packed" to packed, "worn" to native)) {
                    val snapshot = client.refresh()
                    val result = client.submit("equip_item",buildJsonObject {
                        put("itemId","ward");put("equipment",mode);put("source","Synthetic restore parity")
                    },snapshot,"prove-ward-"+mode)
                    assertEquals("COMMITTED",result.cloudText("status"))
                    assertEquals(result,dm("get_cloud_receipt",buildJsonObject {put("commandId","prove-ward-"+mode)}))
                    verifyProjection(state,snapshot.version.revision+1)
                }
                verifySourceScreen(client,char.id,::dm)
            } finally { process.destroyForcibly() }
        } finally {
            cache.close()
            db.close()
            context.deleteDatabase(cacheName)
            folder.deleteRecursively()
        }
    }

    private suspend fun verifySourceScreen(client:CloudCampaignClient,id:String,dm:(String,JsonObject)->JsonObject) {
        var snapshot by mutableStateOf(client.refresh())
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun node(m:SemanticsMatcher):SemanticsNodeInteraction {
            try{compose.waitUntil(12000){compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()}}
            catch(e:ComposeTimeoutException){throw AssertionError(compose.onAllNodes(isRoot()).printToString(maxDepth=8),e)}
            val scrolling=compose.onAllNodes(m and hasAnyAncestor(hasScrollAction()))
            return if(scrolling.fetchSemanticsNodes().isNotEmpty())scrolling.onFirst().performScrollTo() else compose.onAllNodes(m).onFirst()
        }
        fun click(text:String){node(hasText(text) and hasClickAction()).performClick()}
        fun record()=dm("get_cloud_state",buildJsonObject{put("id",id)}).getValue("records").jsonArray.single().jsonObject.getValue("data").jsonObject
        fun scores()=record().getValue("abilities").jsonObject
        fun inspector(){
            node(hasTestTag("ability-card-INT")).performTouchInput{click(androidx.compose.ui.geometry.Offset(width/2f,4f))}
            click("Character source evidence")
        }
        try {
            activity.get().setContent {
                val seq by client.changes.collectAsState()
                LaunchedEffect(seq){client.workspace()?.let{snapshot=it}}
                AstravarTheme {Surface(Modifier.fillMaxSize(),color=RebuildPalette.Canvas){CloudWorkspace(client,snapshot)}}
            }
            inspector()
            node(hasText("Playable character · "+id));node(hasText("Source revision · 1"))
            node(hasText("Mapped values · ",substring=true))
            compose.onNodeWithText("Inspect spell sources and DCs").assertDoesNotExist()
            val refs=record().getValue("importedSources")
            assertEquals(1,refs.jsonArray.size);assertEquals("playable.characters",refs.jsonArray.single().jsonObject.cloudText("family"))
            assertEquals(13,scores().getValue("INT").jsonPrimitive.int)
            click("Edit base score")
            node(hasText("Score") and hasSetTextAction()).performTextReplacement("15")
            click("Add Source")
            node(hasText("Source") and hasSetTextAction()).performTextReplacement("Reviewed measurement")
            click("Submit");node(hasText("Transaction result"));click("Close")
            assertEquals(15,scores().getValue("INT").jsonPrimitive.int)
            val attribution=record().getValue("abilitySources").jsonObject.getValue("INT").jsonObject
            val committed=dm("get_cloud_receipt",buildJsonObject{put("commandId",attribution.getValue("commandId"))})
            assertEquals("COMMITTED",committed.cloudText("status"))
            assertEquals("Reviewed measurement",committed.getValue("receipt").jsonObject.getValue("outcome").jsonObject.cloudText("source"))
            inspector();node(hasText("Latest score source · Reviewed measurement"));click("Edit base score")
            node(hasText("Score") and hasSetTextAction()).performTextReplacement("18")
            val state=dm("get_cloud_state",buildJsonObject{put("id",id)})
            val concurrent=dm("submit_cloud_command",buildJsonObject{putJsonObject("command"){
                put("version",1);put("commandId","source-review-independent");put("operation","set_actor_ability")
                put("epoch",state.getValue("epoch"));put("expectedRevision",state.getValue("revision"))
                putJsonObject("args"){put("actorId",id);put("ability","INT");put("score",16);put("source","Newer source review")}
            }})
            assertEquals("COMMITTED",concurrent.cloudText("status"))
            click("Submit");node(hasText("The campaign changed. Refresh and review the current values."))
            assertEquals(16,scores().getValue("INT").jsonPrimitive.int)
            assertEquals(refs,record().getValue("importedSources"));assertEquals(JsonNull,scores()["STR"])
        }finally{activity.pause().stop().destroy()}
    }

    private fun scopeSource(source:RuleSource) = buildJsonObject {
        put("id",source.id);put("name",source.name);put("acceptance",source.acceptance.name);put("revision",source.revision)
    }
}
