package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudWorldChronicleInteropTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.obj(name: String) = getValue(name).jsonObject
    private fun JsonObject.rows(name: String) = getValue(name).jsonArray.map(JsonElement::jsonObject)
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            compose.waitUntil(12_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Missing $matcher\n"+compose.onAllNodes(isRoot()).printToString(maxDepth=5), error)
        }
        return compose.onAllNodes(matcher).onLast()
    }
    private fun see(text: String) = await(hasText(text)).assertExists()
    private fun click(text: String) {
        val element=await(hasText(text) and hasClickAction() and isEnabled())
        try { element.performScrollTo() } catch (_: AssertionError) { /* Fixed sheet control. */ }
        element.performClick()
    }
    private fun type(label: String,value: String) {
        await(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(value)
    }
    private fun data(f: AscensionWorkerFixture, id: String) = requireNotNull(f.record(id)).obj("data")
    private fun links(f: AscensionWorkerFixture, id: String) = f.dm("get_cloud_record_links",json("""{"id":"$id","limit":100}""")).rows("links")
    private fun assertEdge(f: AscensionWorkerFixture, id: String, relation: String, other: String) {
        assertTrue("$id / $relation / $other", links(f,id).any { it.cloudText("from_id")==id && it.cloudText("relation")==relation && it.cloudText("to_id")==other })
    }
    private fun seed(f: AscensionWorkerFixture, operation: String, args: String) =
        f.command(operation,json(args)).also { assertEquals(operation,"COMMITTED",it.cloudText("status")) }

    @Test fun nativeWorldAndChronicleRetainKnownRelationshipsSourceCorrectionsAndProjectMilestones() {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>(),lifecycleMedia=true).use { f ->
            for ((id,role) in listOf("hero" to "player","companion" to "companion"))
                seed(f,"define_actor","""{"actorId":"$id","name":"Member $id","role":"$role","level":4,"rulesProfile":"legacy_2014","abilities":{"STR":12,"INT":12}}""")
            fun connection(id: String,kind: String,title: String,actor: String?) {
                seed(f,"define_connection","""{"entityId":"$id","actorId":${actor?.let { "\"$it\"" }?:"null"},"name":"$title","category":"$kind","knownDescription":"Known to the party","source":"Isolated notes"}""")
            }
            connection("guide","being","Guiding companion","companion")
            connection("keeper","person","Map keeper",null)
            connection("guild","faction","Chart society",null)
            connection("harbor","place","Western wharf",null)
            val portrait = f.signal("sample",json("""{"shade":20}""")).cloudText("bytes")
            val portraitBytes=Base64.getDecoder().decode(portrait); val hash=cloudDigest(portraitBytes)
            val imageArgs=json("""{"uploadId":"keeper-candidate","targetId":"keeper","targetKind":"world_entity","sha256":"$hash"}""")
            val staged=f.dm("stage_cloud_image",JsonObject(imageArgs+("bytesBase64" to JsonPrimitive(portrait))))
            assertEquals("CANDIDATE_READY",staged.cloudText("status"))
            val image=f.command("assign_primary_image",JsonObject(imageArgs+("assetId" to JsonPrimitive("keeper-art"))))
            assertEquals("PENDING_APPROVAL",image.cloudText("status"))
            val preview=f.signal("ownerPreview",json("""{"uploadId":"keeper-candidate"}"""))
            assertArrayEquals(portraitBytes,Base64.getDecoder().decode(preview.cloudText("bytes")))
            val approved=f.signal("ownerVerify",buildJsonObject{put("commandId",image.getValue("commandId"));put("digest",image.getValue("digest"))})
            assertEquals("COMMITTED",approved.cloudText("status"))
            val pact=seed(f,"record_relationship","""{"relationshipId":"pact","from":{"kind":"world_entity","id":"keeper"},"to":{"kind":"world_entity","id":"guild"},"relationship":"Chart agreement","terms":"Read the public chart","status":"current","source":"Fixture negotiation"}""")
            seed(f,"define_wallet","""{"walletId":"purse","title":"Survey purse","holderId":"hero","shared":false,"coins":{"cp":0,"sp":0,"ep":0,"gp":8,"pp":0},"source":"Fixture holdings"}""")
            seed(f,"define_item_definition","""{"definitionId":"sheet","name":"Blank survey chart","category":"tool","description":"Fixture chart","rarity":null,"stackable":false,"wearable":false,"handheld":true,"attunement":"none","source":"Fixture catalog"}""")
            val purchase=seed(f,"purchase_item","""{"itemId":"paper","definitionId":"sheet","ownerId":"hero","quantity":1,"walletId":"purse","price":{"cp":0,"sp":0,"ep":0,"gp":2,"pp":0},"sellerId":"keeper","source":"Confirmed fixture payment"}""")
            val purchaseEvent=purchase.obj("receipt").obj("outcome").cloudText("itemEventId")
            val entry=seed(f,"record_chronicle_event","""{"eventId":"observed","title":"Journal at the wharf","narrative":"The keeper identified the north jetty.","category":"conversation","status":"confirmed","worldTime":null,"subjects":[{"kind":"world_entity","id":"keeper"},{"kind":"world_entity","id":"harbor"},{"kind":"item_event","id":"$purchaseEvent"}],"correctsId":null,"source":"Original journal"}""")
            seed(f,"define_story_arc","""{"arcId":"voyage","classification":"story_arc","title":"The western route","parentArcId":null,"summary":"Mapping the approaches","state":"active","worldStart":null,"worldEnd":null,"eventIds":["observed"],"source":"Fixture itinerary"}""")
            seed(f,"record_campaign_session","""{"sessionId":"session","arcId":"voyage","previousSessionId":null,"playedOn":null,"title":"At the harbor","summary":"The party checks the chart","state":"complete","worldStart":null,"worldEnd":null,"eventIds":["observed"],"openingCheckpointId":null,"closingCheckpointId":null,"source":"Fixture session notes"}""")
            seed(f,"propose_invention","""{"projectId":"atlas","title":"Survey instrument","actorId":"hero","intent":"Measure the approaches","requirements":[],"researchIds":[],"source":"Fixture plans"}""")
            val goal=seed(f,"define_objective","""{"objectiveId":"task","title":"Confirm the chart","actorId":"hero","category":"research","status":"active","knownRequirements":["Locate a known chart"],"linkedProjectId":"atlas","source":"Fixture objective"}""")
            val projectBefore=f.record("atlas")
            val root = mutableStateOf(runBlocking { f.refresh() });val generation=mutableIntStateOf(0)
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme { key(generation.intValue) { CloudWorkspace(f.client,root.value) } } }
                fun tab(section:String,panel:String) {
                    val now=runBlocking { requireNotNull(f.client.workspace()) }
                    compose.runOnIdle { root.value=now;generation.intValue++ }
                    see("Cloud · revision "+now.version.revision)
                    compose.onNodeWithTag("navigation-$section").performClick()
                    click(panel)
                }
                fun submitted(op:String, ownerReview:Boolean=false):JsonObject {
                    click("Submit");see("Transaction result")
                    val frozen=f.signal("observedDeviceCommand",json("""{"operation":"$op"}"""))
                    val id=frozen.cloudText("commandId")
                    if (ownerReview) {
                        val pending=runBlocking { f.client.verify(id) }
                        assertEquals("PENDING_APPROVAL",pending.cloudText("status"))
                        val owned=f.signal("ownerVerify",buildJsonObject {
                            put("commandId",id);put("digest",pending.getValue("digest"))
                        })
                        assertEquals("COMMITTED",owned.cloudText("status"))
                    }
                    val result=runBlocking {f.client.verify(id)}
                    assertEquals("COMMITTED",result.cloudText("status"))
                    assertEquals(result,f.receipt(id))
                    assertEquals(result,f.signal("redeliverDeviceCommand",json("""{"commandId":"$id"}""")))
                    return frozen
                }
                tab("Connections","Companions");click("Member companion")
                assertEquals("companion",requireNotNull(f.record("companion")).cloudText("id"))
                val members=runBlocking { f.client.records(kinds=setOf("actor")) }
                assertEquals(setOf("hero","companion"),members.map {it.cloudText("id")}.toSet())
                assertEdge(f,"guide","represents_actor","companion")
                tab("Connections","Beings");click("Guiding companion");see("Known to the party")
                tab("Connections","Factions");click("Chart society");see("Known to the party")
                tab("Connections","Places");click("Western wharf");see("Known to the party")
                tab("Connections","Relationships");click("Chart agreement");see("Read the public chart")
                click("Edit relationship");type("Terms","The party can copy the harbor chart")
                submitted("update_relationship")
                assertEquals("The party can copy the harbor chart",data(f,"pact").cloudText("terms"))
                assertEdge(f,"pact","from_subject","keeper");assertEdge(f,"pact","to_subject","guild")
                assertEquals(pact,f.receipt(pact.cloudText("commandId")))

                tab("Connections","Beings");click("Map keeper")
                await(hasTestTag("primary-image-keeper")).performClick()
                see("Replace image")
                await(hasContentDescription("Image preview for Map keeper")).assertExists()
                val shown=runBlocking { f.client.image(requireNotNull(f.client.workspace()),"keeper","world_entity") }
                val association=f.dm("get_cloud_primary_image",json("""{"id":"keeper"}"""))
                assertEquals(cloudDigest(shown),association.cloudText("thumbnailSha256"))
                assertEquals("keeper-art",association.cloudText("assetId"));click("Cancel")
                click("Link known document")
                click("document");click("Letter")
                type("Title","Harbor keeper letter");type("Detail","The signed letter names the west jetty.")
                click("Select");click("Confirmed")
                val document=submitted("record_known_fact").obj("args").cloudText("factId")
                assertEquals("letter",data(f,document).cloudText("referenceKind"))
                assertEquals(JsonNull,data(f,document)["originalWorldTime"])
                assertEdge(f,document,"known_about","keeper")
                tab("Chronicle","Lore")
                type("Search lore","Harbor keeper letter");click("Harbor keeper letter")
                see("The signed letter names the west jetty.")
                click("Linked records")
                await(hasText("Map keeper",substring=true) and hasClickAction()).assertExists()

                tab("Chronicle","Timeline");click("Journal at the wharf");see("The keeper identified the north jetty.")
                click("Record correction");type("Title","Corrected wharf account");type("Narrative","The keeper identified the west jetty.")
                click("Select");click("Confirmed")
                val corrected=submitted("record_chronicle_event").obj("args").cloudText("eventId")
                assertEquals(entry,f.receipt(entry.cloudText("commandId")))
                assertEquals("The keeper identified the north jetty.",data(f,"observed").cloudText("narrative"))
                assertEdge(f,corrected,"corrects","observed")
                assertEdge(f,"observed","event_subject",purchaseEvent)
                assertEquals(purchase,f.receipt(purchase.cloudText("commandId")))
                assertEquals(6,data(f,"purse").obj("coins").getValue("gp").jsonPrimitive.int)
                assertEquals(1,data(f,"paper").getValue("quantity").jsonPrimitive.int)
                tab("Chronicle","Timeline");click("Corrected wharf account");see("The keeper identified the west jetty.")
                tab("Chronicle","Story arcs");click("The western route");see("Mapping the approaches")
                tab("Chronicle","Sessions");click("At the harbor");see("Table date · Unknown")
                assertEdge(f,"session","session_in_arc","voyage")
                assertEdge(f,"session","includes_event","observed")

                tab("Chronicle","Objectives");click("Confirm the chart");see("Locate a known chart")
                click("Record confirmed milestone");type("Event Id",corrected)
                click("Select");click("Completed")
                submitted("record_objective_milestone")
                tab("Chronicle","Objectives");click("Confirm the chart");see("Locate a known chart")
                click("Linked records")
                await(hasText("Survey instrument",substring=true) and hasClickAction()).assertExists()
                assertEdge(f,"task","linked_project","atlas");assertEdge(f,"task","milestone",corrected)
                assertEquals("completed",data(f,"task").cloudText("status"))
                assertEquals(goal,f.receipt(goal.cloudText("commandId")))
                assertEquals(projectBefore,f.record("atlas"))

                val faction=requireNotNull(f.record("guild"))
                fun lifecycle(archiving:Boolean) {
                    tab("Connections","Factions");click("Tools");click("Archive manager")
                    if (archiving) click("Active")
                    await(hasTestTag("archive-record-guild")).performScrollTo().performClick()
                    click("Select record");click(if(archiving)"Review archive" else "Review restore")
                    submitted(if(archiving)"archive_records" else "restore_records",ownerReview=true)
                }
                lifecycle(true)
                assertNull(runBlocking { f.client.record("guild") });assertNull(f.record("guild"))
                lifecycle(false)
                assertEquals(faction.obj("data"),requireNotNull(f.record("guild")).obj("data"))
                tab("Connections","Factions");click("Chart society");see("Known to the party")
                assertEdge(f,"pact","to_subject","guild")
                val page=f.dm("get_cloud_record_links",json("""{"id":"observed","limit":1}"""))
                val after=page.cloudText("nextAfter")
                val state=runBlocking { requireNotNull(f.client.workspace()) }
                val suffix="links/observed?after=$after&atEpoch=${state.version.epoch}&atRevision=${state.version.revision}&grantRevision=${state.version.grantRevision}"
                val rest=runBlocking { f.client.projection(state,suffix) }.rows("links")
                assertTrue(rest.isNotEmpty());assertEquals(links(f,"observed"),page.rows("links")+rest)
                val prefix="/v3/continuities/${state.binding.continuity}/"
                assertTrue(validCloudDevicePath(prefix+suffix))
                assertFalse(validCloudDevicePath(prefix+suffix.replace(after,"bad~cursor")))
                assertFalse(validCloudDevicePath(prefix+suffix+"&extra=1"))

                f.signal("restrictWorldReader")
                assertEquals("companion",requireNotNull(f.record("companion")).cloudText("id"))
                assertNull(f.record(document));assertNull(f.record("keeper"));assertTrue(links(f,"keeper").isEmpty())
                val denied=assertThrows(IllegalStateException::class.java) {f.dm("get_cloud_primary_image",json("""{"id":"keeper"}"""))}
                assertTrue(denied.message.orEmpty().contains("IMAGE_UNAVAILABLE"))
                val write=assertThrows(IllegalStateException::class.java){f.command("record_known_fact",json("""{"factId":"denied","subject":{"kind":"world_entity","id":"keeper"},"title":"Rejected","detail":"Not allowed","evidence":"unknown","learnedAt":null,"originalWorldTime":null,"source":"Fixture"}"""))}
                assertTrue(write.message.orEmpty().contains("CLOUD_GRANT_DENIED"))
                val current=runBlocking { f.refresh() }
                assertEquals(state.version,current.version)
                assertNull(runBlocking { f.client.record("denied") })
                assertEquals("The signed letter names the west jetty.",runBlocking { requireNotNull(f.client.record(document)) }.obj("data").cloudText("detail"))
            } finally { activity.pause().stop().destroy() }
        }
    }
}
