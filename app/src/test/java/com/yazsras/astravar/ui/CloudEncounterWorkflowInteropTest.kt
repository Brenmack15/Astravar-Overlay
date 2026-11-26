package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w440dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudEncounterWorkflowInteropTest {
    @get:Rule val compose=createAstravarComposeRule()
    private fun obj(raw:String)=Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.o(key:String)=getValue(key).jsonObject
    private fun JsonObject.s(key:String)=getValue(key).jsonPrimitive.content
    private fun JsonObject.rows(key:String)=getValue(key).jsonArray.map {it.jsonObject}
    private fun node(m:SemanticsMatcher):SemanticsNodeInteraction {
        try {compose.waitUntil(10000){compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:ComposeTimeoutException){throw AssertionError("Missing $m\n"+compose.onAllNodes(isRoot()).printToString(maxDepth=8),e)}
        val scroll=compose.onAllNodes(m and hasAnyAncestor(hasScrollAction()))
        return if(scroll.fetchSemanticsNodes().isNotEmpty())scroll.onFirst().performScrollTo() else compose.onAllNodes(m).onFirst()
    }
    private fun press(label:String)=node(hasText(label) and hasClickAction()).performClick()
    private fun field(label:String,value:String)=node(hasText(label) and hasSetTextAction()).performTextReplacement(value)

    @Test fun encounterRollInterruptKnownHistoryEligibilityAndScopedDenialSurviveReconnection() {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { f ->
            fun committed(result:JsonObject,op:String):JsonObject {
                assertEquals("$op $result","COMMITTED",result["status"]?.jsonPrimitive?.content)
                assertEquals(result,f.receipt(result.s("commandId")))
                return result
            }
            fun dm(op:String,raw:String)=committed(f.command(op,obj(raw)),op)
            fun native(op:String,raw:String)=runBlocking {
                committed(f.client.submit(op,obj(raw),requireNotNull(f.client.workspace())),op)
            }
            fun data(id:String)=requireNotNull(f.record(id)).o("data")
            fun deny(op:String,raw:String,code:String){
                val denied=assertThrows(IllegalStateException::class.java){f.command(op,obj(raw))}
                assertTrue(denied.toString(),denied.message.orEmpty().endsWith("\"$code\""))
            }
            fun actor(id:String,name:String,role:String)=dm("define_actor","""{"actorId":"$id","name":"$name","role":"$role","rulesProfile":"revised_2024","level":5,"abilities":{"STR":12,"DEX":12,"CON":12,"INT":16,"WIS":10,"CHA":10},"baseAc":13,"currentHp":28,"maxHp":28,"temporaryHp":0,"gameSeconds":0,"source":"Fixture record"}""")
            actor("engineer","Survey engineer","player");actor("scout","Survey scout","companion")
            dm("define_item_definition","""{"definitionId":"focus-def","name":"Survey focus","category":"gear","description":"Physical test instrument","rarity":null,"stackable":false,"wearable":true,"handheld":false,"attunement":"none","source":"Fixture inventory"}""")
            dm("acquire_item","""{"itemId":"focus","definitionId":"focus-def","ownerId":"engineer","quantity":1,"source":"Received instrument"}""")
            dm("define_resource","""{"actorId":"engineer","poolId":"spark","name":"Guidance charges","type":"ordinary","maximum":1,"current":1,"recovery":"short_rest","source":"Fixture rule"}""")
            dm("define_form","""{"actorId":"engineer","formId":"sealed","name":"Sealed configuration","source":"Reviewed equipment fit","timing":"other","activationSeconds":0,"suppressedItemIds":["focus"]}""")
            val abilities=mapOf("measure" to "Measure reach","mark" to "Mark the range","assist" to "Guiding insight","observe" to "Inspect the joint")
            fun ability(id:String,timing:String,extra:String="")=dm("define_action","""{"actorId":"engineer","actionId":"$id","name":"${abilities[id]}","type":"${if(id=="assist")"roll_bonus" else "use"}","timing":"$timing","source":"Reviewed fixture action"$extra}""")
            ability("measure","action",","+"\"sourceItemId\":\"focus\"");ability("mark","bonus_action")
            ability("assist","reaction",","+"\"sourceItemId\":\"focus\",\"bonusAbility\":\"INT\",\"range\":30,\"cost\":{\"poolId\":\"spark\",\"amount\":1}")
            ability("observe","other")
            var snapshot by mutableStateOf(runBlocking {f.refresh()})
            fun renew(){snapshot=runBlocking { f.refresh() }}
            val requests=mutableListOf<Pair<String,JsonObject>>()
            var section by mutableStateOf("Actions")
            var generation by mutableIntStateOf(0)
            var operations=runBlocking{f.client.capabilities()}.rows("operations")
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme {key(generation){
                    var editor by remember {mutableStateOf<Pair<String,JsonObject>?>(null)}
                    val open:(String,JsonObject)->Unit={op,args->editor=op to args}
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if(section=="Actions")CloudActorRecords(section,f.client,snapshot,"engineer",true,open,{},open)
                        else CloudEncounterPanel(f.client,snapshot,true,"",open)
                    }
                    editor?.let { (op,args)->CloudCommandForm(operations.single{it.s("name")==op},false,{editor=null},{ reviewed,id ->
                        val result=runBlocking{f.client.submit(op,reviewed,snapshot,id)}
                        requests.add(op to committed(result,op));snapshot=runBlocking{requireNotNull(f.client.workspace())};editor=null
                    },null,args)}
                }} }
                fun checkActions(expected:Set<String>,depleted:Boolean=false){
                    val page=f.dm("get_cloud_actions",obj("""{"id":"engineer"}""")).rows("rows")
                    assertEquals(expected,page.map{it.s("id")}.toSet())
                    for((id,name) in abilities){
                        if(id in expected)node(hasText(name)).assertExists()
                        else compose.waitUntil(10000){compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty()}
                    }
                    val groups=page.map {it.o("configuration").s("timing")}.toSet()
                    for((id,title) in mapOf("action" to "Actions","bonus_action" to "Bonus Actions","reaction" to "Reactions","other" to "Other")){
                        if(id in groups)node(hasText(title)).assertExists()
                        else compose.onAllNodesWithText(title).assertCountEquals(0)
                    }
                    if("assist" in expected){
                        val disabled=page.single{it.s("id")=="assist"}.getValue("reasons").jsonArray.isNotEmpty()
                        assertEquals(depleted,disabled)
                        node(hasText("Choose roll") and (if(depleted)isNotEnabled() else isEnabled())).assertExists()
                    }
                }
                checkActions(setOf("mark","observe"))
                native("equip_item","""{"itemId":"focus","equipment":"worn","source":"Confirmed fixture fitting"}""");renew()
                checkActions(abilities.keys)
                native("change_form","""{"actorId":"engineer","formId":"sealed","elapsedSeconds":0}""");renew();checkActions(setOf("mark","observe"))
                native("change_form","""{"actorId":"engineer","formId":null,"elapsedSeconds":0}""");renew();checkActions(abilities.keys)
                fun roll(id:String,who:String,kind:String,key:String,face:Int)=native("record_check","""{"actorId":"$who","rollId":"$id","check":{"kind":"$kind","key":"$key","mode":"normal"},"evidence":{"origin":"player_physical","faces":[$face]}}""")
                roll("init-eng","engineer","initiative","initiative",16);roll("init-scout","scout","initiative","initiative",9);renew()
                section="Encounter"
                press("Start combat encounter");field("Encounter name","Bridge inspection")
                press("Survey engineer");node(hasText("17 · Initiative",substring=true) and hasClickAction()).performClick()
                press("Survey scout");node(hasText("10 · Initiative",substring=true) and hasClickAction()).performClick()
                press("Review encounter start");assertEquals(0,requests.size);press("Submit")
                val start=requests.last().second
                val eid=start.o("receipt").o("outcome").s("encounterId")
                assertEquals(listOf("init-eng","init-scout"),data(eid).rows("initiative").map{it.s("rollId")})
                assertEquals("engineer",start.o("receipt").o("outcome").s("currentActorId"))
                roll("earlier-check","scout","ability","STR",10);renew()
                press("Bridge inspection");node(hasText("Round 1")).assertExists()
                press("Review next turn");press("Submit")
                assertEquals("scout",requests.last().second.o("receipt").o("outcome").s("currentActorId"))
                val stale="""{"actorId":"engineer","actionId":"assist","useId":"stale-use","declared":true,"reaction":{"rollId":"earlier-check","targetActorId":"scout","distanceFeet":15,"visible":true,"windowOpen":true}}"""
                deny("use_action",stale,"ROLL_REACTION_WINDOW_CLOSED")
                val actual=roll("current-check","scout","ability","STR",4);renew()
                val before=data("current-check");assertEquals("5",before.s("total"))
                assertEquals("1",data("spark").s("current"));assertEquals("true",data(eid).o("economy").o("engineer").s("reaction"))
                // Reopen the application database on the pending decision. No actor action is selected by the read.
                f.resetCache();renew();generation++
                press("Bridge inspection");node(hasText("10 · Survey scout · Current turn")).assertExists()
                press("Close");section="Actions";checkActions(abilities.keys)
                press("Choose roll");node(hasText("Guidance charges · 1 required · 1 remaining")).assertExists()
                compose.onAllNodesWithText("Strength Check · 11").assertCountEquals(0)
                press("Strength Check · 5");node(hasText("Strength Check: (4) + 1 = 5")).assertExists()
                node(hasText("Review reaction") and hasClickAction()).assertIsNotEnabled()
                field("Distance to target in feet","31");press("Confirm target is visible");press("Confirm interrupt window is open")
                node(hasText("Review reaction") and hasClickAction()).assertIsNotEnabled()
                field("Distance to target in feet","15");press("Review reaction")
                assertEquals("1",data("spark").s("current"));assertEquals("5",data("current-check").s("total"))
                press("Submit")
                val used=requests.last().second
                val result=used.o("receipt").o("outcome");assertEquals("8",result.s("finalTotal"));assertEquals("5",result.s("originalTotal"))
                val after=data("current-check");assertEquals(before.getValue("faces"),after.getValue("faces"))
                assertEquals(result.s("useId"),after.s("appliedUseId"));assertEquals("8",after.s("total"))
                assertEquals("0",data("spark").s("current"));assertEquals("false",data(eid).o("economy").o("engineer").s("reaction"))
                assertEquals(used,f.signal("redeliverDeviceCommand",buildJsonObject{put("commandId",used.getValue("commandId"))}))
                assertEquals(actual,f.receipt(actual.s("commandId")));checkActions(abilities.keys,true)
                val edges=f.dm("get_cloud_record_links",obj("""{"id":"${result.s("useId")}"}""")).rows("links")
                assertTrue(edges.any{it.s("relation")=="adjusted_roll" && it.s("to_id")=="current-check"})
                section="Encounter";press("Bridge inspection");press("Review next turn");press("Submit")
                assertEquals("2",data(eid).s("round"));assertEquals("true",data(eid).o("economy").o("engineer").s("reaction"))
                section="Actions";checkActions(abilities.keys,true)
                section="Encounter";press("Bridge inspection");node(hasText("Round 2")).assertExists()
                press("Record outcome and end");field("Outcome","The team secured the crossing");press("Submit")
                assertEquals("false",data(eid).s("active"));assertEquals("The team secured the crossing",data(eid).s("outcome"))
                press("Record non-combat encounter");field("Title","Survey agreement");field("Narrative","The two surveyors agreed to compare their measurements.")
                field("Source","Isolated table transcript");press("unresolved");press("Confirmed");press("Submit")
                val narrative=requests.last().second.o("receipt").o("outcome").s("eventId")
                assertFalse(data(narrative).containsKey("initiative"))
                f.resetCache();renew();generation++
                press("Survey agreement");node(hasText("Non-combat encounter")).assertExists()
                node(hasText("The two surveyors agreed to compare their measurements.")).assertExists()
                compose.onAllNodesWithText("Review next turn").assertCountEquals(0);press("Close")
                press("Bridge inspection");node(hasText("Encounter completed")).assertExists();node(hasText("The team secured the crossing")).assertExists();press("Close")
                native("complete_rest","""{"actorId":"engineer","kind":"short_rest","elapsedSeconds":3600,"confirmed":true,"hitDice":[]}""");renew()
                assertEquals("1",data("spark").s("current"));section="Actions";checkActions(abilities.keys)
                section="Encounter";f.signal("scopeEncounterAudience");renew();generation++
                compose.waitUntil(10000){compose.onAllNodesWithText("Bridge inspection").fetchSemanticsNodes().isEmpty()}
                for(id in listOf("unrevealed-witness","private-initiative","restricted-watch")){
                    assertNull(f.record(id));assertNull(runBlocking{f.client.record(id)})
                }
                compose.onAllNodesWithText("Unrevealed encounter").assertCountEquals(0)
                val denied=runCatching{runBlocking{f.client.projection(snapshot,"sheet/unrevealed-witness")}}.exceptionOrNull()
                assertTrue("Hidden sheet must be denied",denied is OwnerSyncFailure)
                val dmDenied=assertThrows(IllegalStateException::class.java){f.dm("get_cloud_actor_sheet",obj("""{"id":"unrevealed-witness"}"""))}
                assertTrue(dmDenied.message.orEmpty().contains("DENIED"))
                press("Start combat encounter");node(hasText("Survey engineer")).assertExists();node(hasText("Survey scout")).assertExists()
                compose.onAllNodesWithText("Unrevealed fixture witness").assertCountEquals(0)
            } finally {activity.pause().stop().destroy()}
        }
    }
}
