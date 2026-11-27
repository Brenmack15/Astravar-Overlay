package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w440dp-h920dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudCharacterContinuityInteropTest {
    @get:Rule val compose=createAstravarComposeRule()
    private fun json(raw:String)=Json.parseToJsonElement(raw).jsonObject
    private fun node(m:SemanticsMatcher):SemanticsNodeInteraction {
        try {compose.waitUntil(10000){compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:ComposeTimeoutException){throw AssertionError("Missing $m\n"+compose.onAllNodes(isRoot()).printToString(maxDepth=8),e)}
        val scroll=compose.onAllNodes(m and hasAnyAncestor(hasScrollAction()))
        return if(scroll.fetchSemanticsNodes().isEmpty())compose.onAllNodes(m).onFirst() else scroll.onFirst().performScrollTo()
    }
    private fun press(text:String)=node(hasText(text) and hasClickAction()).performClick()
    private fun enter(label:String,value:String)=node(hasText(label) and hasSetTextAction()).performTextReplacement(value)

    @Test fun actionsOpenOriginalSpellbookAndTimedConditionSourcesSurviveColdReloadAndSeparateRemoval(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { f ->
            fun seed(op:String,args:String)=f.command(op,json(args)).also {assertEquals(it.toString(),"COMMITTED",it.cloudText("status"))}
            fun state()=f.dm("get_cloud_state",json("""{"limit":1}"""))
            fun walk()=f.dm("get_cloud_actor_sheet",json("""{"id":"traveler"}""")).getValue("sheet").jsonObject.getValue("movement").jsonObject.getValue("walk").jsonPrimitive.int
            fun sources()=requireNotNull(f.record("traveler")).getValue("data").jsonObject.getValue("conditionSources").jsonArray.map{it.jsonObject}
            seed("define_actor","""{"actorId":"traveler","name":"Fixture traveler","role":"player","rulesProfile":"revised_2024","level":4,"speeds":{"walk":30},"gameSeconds":100,"abilities":{"DEX":12,"INT":16},"currentHp":20,"maxHp":25}""")
            seed("define_spell","""{"actorId":"traveler","spellId":"glow","name":"Measured light","baseLevel":0,"timing":"action","resolution":"automatic","ritual":false,"concentration":false,"components":[],"source":"Reviewed fixture spell"}""")
            seed("grant_spell","""{"actorId":"traveler","grantId":"light-grant","spellId":"glow","route":"at_will","levels":[0],"preparation":"not_required","source":"Fixture spell grant"}""")
            val stable=state().getValue("revision")
            val issued=mutableListOf<JsonObject>()
            fun mount()=Robolectric.buildActivity(ComponentActivity::class.java).setup().also { ac ->
                val first=runBlocking{f.refresh()};val ops=runBlocking{f.client.capabilities()}.getValue("operations").jsonArray.map{it.jsonObject}
                ac.get().setContent { AstravarTheme {
                    var snapshot by remember{mutableStateOf(first)}
                    var actor by remember {mutableStateOf<String?>("traveler")}
                    var editor by remember{mutableStateOf<Pair<String,JsonObject>?>(null)}
                    Column{CloudActorWorkspace(f.client,snapshot,true,actor,{actor=it},{op,args->editor=op to args},{fail("Unexpected navigation to $it")},{_,_->fail("Unexpected gameplay execution")})}
                    editor?.let{(op,args)->CloudCommandForm(ops.single{it.cloudText("name")==op},false,{editor=null},{reviewed,id->
                        val outcome=runBlocking{f.client.submit(op,reviewed,snapshot,id)}
                        assertEquals("COMMITTED",outcome.cloudText("status"));assertEquals(outcome,f.receipt(id));issued.add(outcome)
                        snapshot=runBlocking{requireNotNull(f.client.workspace())};editor=null
                    },null,args)}
                }}
            }
            val first=mount()
            try {
                press("Actions");node(hasText("Cast a spell")).assertExists()
                compose.onAllNodesWithText("Measured light").assertCountEquals(0)
                press("Cast a spell");node(hasText("Manage spells")).assertExists();node(hasText("Measured light")).assertExists()
                val grants=f.dm("get_cloud_spellbook",json("""{"id":"traveler"}""")).getValue("rows").jsonArray
                assertEquals(listOf("light-grant"),grants.map{it.jsonObject.cloudText("grantId")})
                assertEquals(stable,state().getValue("revision"))
                press("Conditions");press("Manage condition")
                enter("Key","restrained");enter(cloudLabel("sourceId"),"first-net")
                press("Add "+cloudLabel("durationSeconds"));enter(cloudLabel("durationSeconds"),"30")
                assertEquals(30,walk());press("Submit");assertEquals(0,walk())
                assertEquals(130,sources().single().getValue("expiresAt").jsonPrimitive.int)
            }finally{first.pause().stop().destroy()}
            seed("set_condition","""{"actorId":"traveler","key":"restrained","enabled":true,"sourceId":"second-net","durationSeconds":60}""")
            f.resetCache()
            val second=mount()
            try{
                press("Conditions");press("Restrained · first-net");node(hasText("30 seconds remaining")).assertExists()
                node(hasText("Source · first-net")).assertExists()
                press("Remove this source");assertEquals(2,sources().size);press("Submit")
                assertEquals(listOf("manual:second-net"),sources().map{it.cloudText("sourceId")});assertEquals(0,walk())
                assertEquals(issued.last(),f.signal("redeliverDeviceCommand",buildJsonObject{put("commandId",issued.last().getValue("commandId"))}))
            }finally{second.pause().stop().destroy()}
            seed("advance_actor_time","""{"actorId":"traveler","elapsedSeconds":59,"confirmed":true}""")
            assertEquals(0,walk());seed("advance_actor_time","""{"actorId":"traveler","elapsedSeconds":1,"confirmed":true}""")
            assertEquals(30,walk());assertTrue(sources().isEmpty());f.resetCache()
            val third=mount()
            try {
                press("Conditions");compose.onAllNodesWithText("Restrained · second-net").assertCountEquals(0)
                assertEquals(issued.first(),f.receipt(issued.first().cloudText("commandId")))
            }finally{third.pause().stop().destroy()}
        }
    }
}
