package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
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
class CloudCompanionTraitsInteropTest {
    @get:Rule val compose=createAstravarComposeRule()
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.data()=getValue("data").jsonObject
    private fun JsonObject.tracks()=getValue("classTracks").jsonArray.map {it.jsonObject}
    private fun JsonObject.number(key:String)=getValue(key).jsonPrimitive.int
    private fun node(m:SemanticsMatcher):SemanticsNodeInteraction {
        try{compose.waitUntil(8000){compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()}}catch(e:ComposeTimeoutException){throw AssertionError("Missing $m\n"+compose.onAllNodes(isRoot()).printToString(maxDepth=8),e)}
        val inScroll=compose.onAllNodes(m and hasAnyAncestor(hasScrollAction()))
        return if(inScroll.fetchSemanticsNodes().isEmpty())compose.onAllNodes(m).onFirst() else inScroll.onFirst().performScrollTo()
    }
    private fun press(label:String)=node(hasText(label) and hasClickAction()).performClick()
    private fun input(label:String,value:String)=node(hasText(label) and hasSetTextAction()).performTextInput(value)
    private fun seed(f:AscensionWorkerFixture,op:String,raw:String){
        assertEquals("COMMITTED",f.command(op,obj(raw)).cloudText("status"))
    }
    private val actor="actor-one"
    private fun mount(f:AscensionWorkerFixture,saved:MutableList<JsonObject>) =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also {activity->
            val initial=runBlocking {f.refresh()}
            val operations=runBlocking {f.client.capabilities()}.getValue("operations").jsonArray.map {it.jsonObject}
            activity.get().setContent {
                var snapshot by remember {mutableStateOf(initial)}
                var editor by remember {mutableStateOf<Pair<String,JsonObject>?>(null)}
                fun apply(op:String,fields:JsonObject) {
                    saved.add(runBlocking{f.client.submit(op,fields,snapshot)})
                    snapshot=runBlocking{requireNotNull(f.client.workspace())}
                }
                AstravarTheme {
                    Column{CloudActorWorkspace(f.client,snapshot,true,actor,{},{op,args->editor=op to args},{},::apply)}
                    editor?.let{(op,args)->CloudCommandForm(operations.single{it.cloudText("name")==op},false,{editor=null},{reviewed,_->
                        apply(op,reviewed);editor=null
                    },null,args)}
                }
            }
        }
    private fun sheet(f:AscensionWorkerFixture)=f.dm("get_cloud_actor_sheet",obj("""{"id":"actor-one"}""")).getValue("sheet").jsonObject
    private fun result(f:AscensionWorkerFixture,saved:List<JsonObject>,count:Int):JsonObject {
        compose.waitUntil(10000){saved.size==count}
        val r=saved.last();assertEquals("COMMITTED",r.cloudText("status"));assertEquals(r,f.receipt(r.cloudText("commandId")))
        return r.getValue("receipt").jsonObject.getValue("outcome").jsonObject
    }
    private fun hp(current:Int)=node(hasContentDescription("Hit points, $current of 60")).performClick()
    private fun reviewCare(cast:String,seconds:Int,faces:String?){
        press("Companion care")
        press("Use cast · Maintenance cantrip · $cast")
        if(faces==null)press("Use tools · Test tools")
        input("Actual elapsed seconds",seconds.toString());if(faces!=null)input("Actual repair dice",faces)
        press("Confirm uninterrupted procedure")
        press(if(faces==null)"Review reconstruction" else "Review repair")
        press("Submit")
    }
    private fun castRepair(f:AscensionWorkerFixture,id:String)=seed(f,"cast_spell","""{"actorId":"ally","castId":"$id","grantId":"repair-grant","level":0,"targets":["actor-one"]}""")
    private fun damage(f:AscensionWorkerFixture,n:Int)=seed(f,"apply_hp_change","""{"actorId":"actor-one","kind":"damage","amount":$n}""")
    private fun castAid(f:AscensionWorkerFixture)=seed(f,"cast_spell","""{"actorId":"ally","castId":"support-live","grantId":"aid-grant","level":1,"slotPoolId":"operator-slot","targets":["actor-one"]}""")
    private fun castForm(f:AscensionWorkerFixture)=seed(f,"change_form","""{"actorId":"actor-one","formId":"rig-form","elapsedSeconds":60,"uninterrupted":true,"willing":true}""")
    @Test fun normalProfileDisplaysTheSingleOriginalBondIncludedScoreAndInstalledCommunications(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use{f->
            f.signal("seedCompanionAcceptance");val saved=mutableListOf<JsonObject>()
            for((title,labels) in listOf(
                "Dedicated companion bond" to listOf("Operator · Fixture ally","Known infusion · Original known pattern","Dedicated slots available · 0 / 1"),
                "Mechanical calibration" to listOf("Included in base DEX · 16 → 18"),
                "Speech packages" to listOf("Unassigned packages · 3","Independent full-sentence speech","Installed languages · Fixture A, Fixture B, Fixture C"),
                "Audience rapport" to listOf("Review Performance check","Review Persuasion check"),
                "private-link" to listOf("Two-way private telepathy","Senses remain independent")
            )){
                val activity=mount(f,saved)
                try{press("Test companion");press("Features & Traits");press(title)
                    for(label in labels)node(hasText(label)).assertExists()
                }finally{activity.pause().stop().destroy()}
            }
            assertTrue(saved.isEmpty());assertEquals(2,f.record("ordinary-capacity")!!.data().number("current"))
            assertEquals(18,f.record(actor)!!.data()["abilities"]!!.jsonObject.number("DEX"))
            assertEquals(3,runBlocking{f.client.records(kinds=setOf("actor"))}.size)
        }
    }
    @Test fun normalActionsUseTheActiveCastingAndShowSeparateAttacksResourcesAndNoCompanionSlots(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use{f->
            f.signal("seedCompanionAcceptance");castAid(f)
            val saved=mutableListOf<JsonObject>();val a=mount(f,saved)
            try{
                press("Actions");for(n in listOf("rake","nip","pulse","Normal pair","Interpret message"))node(hasText(n)).assertExists()
                for((i,id) in listOf("rake","nip","pulse").withIndex()){
                    node(hasContentDescription("Roll $id attack")).performClick()
                    val attack=result(f,saved,i+1);val hit=attack.getValue("hit").jsonObject
                    assertEquals(id,attack.cloudText("attackId"));val aid=hit.getValue("aidDice").jsonArray.single().jsonObject
                    assertEquals("app",aid.cloudText("origin"));assertEquals(4,aid.number("sides"));assertTrue(aid.number("face") in 1..4)
                    assertEquals("support-live",aid.cloudText("castId"));assertEquals(hit.number("beforeAid")+aid.number("face"),hit.number("total"))
                    assertTrue(attack["damage"]==JsonNull)
                }
                press("Use Short displacement");input("Destination","Fixture ledge");input("Distance in feet","30");press("Choose available space");press("Tiny");press("Airborne")
                press("Confirm visible, unoccupied space, clear passage and no passengers");press("Confirm displacement")
                assertEquals("shift",result(f,saved,4).cloudText("actionId"));assertEquals(2,f.record("blink")!!.data().number("current"))
                assertEquals(1,f.record("operator-slot")!!.data().number("current"))
                val spellbook=f.dm("get_cloud_spellbook",obj("""{"id":"actor-one"}"""))
                assertTrue(spellbook.getValue("slots").jsonArray.isEmpty());assertTrue(spellbook.getValue("rows").jsonArray.isEmpty())
            }finally{a.pause().stop().destroy()}
            castForm(f);val deployed=mount(f,saved)
            try{
                press("Actions");for(n in listOf("large-bite","large-rake","Large pair","Selective cry","Short displacement"))node(hasText(n)).assertExists()
                assertTrue(compose.onAllNodes(hasText("nip")).fetchSemanticsNodes().isEmpty())
                for((i,id) in listOf("large-bite","large-rake").withIndex()){
                    node(hasContentDescription("Roll $id attack")).performClick()
                    val outcome=result(f,saved,i+5);assertEquals(id,outcome.cloudText("attackId"));assertEquals("rig-form",outcome.cloudText("formId"))
                    assertEquals("rig-form",f.record(outcome.cloudText("attemptId"))!!.data().cloudText("formId"))
                    assertFalse("aidDice" in outcome.getValue("hit").jsonObject)
                }
                press("Use Short displacement");node(hasText("Required space · Large")).assertExists()
                input("Destination","Fixture landing");input("Distance in feet","30");press("Choose available space");press("Tiny");press("Airborne")
                press("Confirm visible, unoccupied space, clear passage and no passengers")
                node(hasText("Confirm displacement")).assertIsNotEnabled();press("Tiny space");press("Large")
                node(hasText("Confirm displacement")).assertIsNotEnabled();press("Grounded");press("Confirm displacement")
                val shift=result(f,saved,7);assertEquals("Fixture landing",shift.getValue("destination").jsonObject.cloudText("label"));assertEquals(1,f.record("blink")!!.data().number("current"))
                assertEquals(0,f.record("deploy")!!.data().number("current"));assertEquals(75,f.record("rig-integrity")!!.data().number("current"))
                assertEquals(60,sheet(f).number("currentHp"))
            }finally{deployed.pause().stop().destroy()}
        }
    }
    @Test fun quickSaveRecordsAnAppOwnedOrPhysicalAidDieOnlyAfterAConfirmedCasting(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use{f->
            f.signal("seedCompanionAcceptance");castAid(f)
            val saved=mutableListOf<JsonObject>();val activity=mount(f,saved)
            try{
                val save=hasContentDescription("Wisdom saving throw",substring=true) and hasClickAction()
                node(save).performClick();val rolled=result(f,saved,1)
                assertEquals("WIS",rolled.cloudText("key"));assertEquals("save",rolled.cloudText("kind"))
                val credit=rolled.getValue("aidDice").jsonArray.single().jsonObject
                assertEquals("support-live",credit.cloudText("castId"));assertEquals("app",credit.cloudText("origin"));assertTrue(credit.number("face") in 1..4)
                node(save).performTouchInput{longClick()};input("Physical d20 faces, in order","13")
                node(hasText("Record physical dice")).assertIsNotEnabled();input("Actual Support d4","2");press("Record physical dice")
                val physical=result(f,saved,2);assertEquals(14,physical.number("beforeAid"));assertEquals(16,physical.number("total"));assertEquals(13,physical.number("natural"))
                val die=physical.getValue("aidDice").jsonArray.single().jsonObject
                assertEquals("player_physical",die.cloudText("origin"));assertEquals(2,die.number("face"))
                assertEquals(f.record(physical.cloudText("rollId"))!!.data().number("total"),physical.number("total"))
            }finally{activity.pause().stop().destroy()}
        }
    }
    @Test fun normalFormsTrackTheFullMinuteOnReopenAndExpireWithoutRefillingAnItem(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use{f->
            f.signal("seedCompanionAcceptance");damage(f,4)
            val saved=mutableListOf<JsonObject>()
            fun time(seconds:Int)=seed(f,"advance_actor_time","""{"actorId":"actor-one","elapsedSeconds":$seconds,"confirmed":true}""")
            fun preview(){node(hasContentDescription("Open forms and configurations")).performClick();press("Heavy frame")}
            var activity=mount(f,saved)
            try{
                preview();node(hasText("Begin deployment")).assertIsNotEnabled()
                val normal=sheet(f);assertEquals(16,normal.getValue("ac").jsonObject.number("value"));assertEquals(8,normal.getValue("abilities").jsonObject.getValue("STR").jsonObject.number("effective"))
                assertEquals(56,normal.number("currentHp"));assertEquals(1,f.record("deploy")!!.data().number("current"))
                node(hasText("This actor is willing to transform")).performClick();press("Begin deployment")
                val begun=result(f,saved,1);assertEquals("deploying",f.record(begun.cloudText("deploymentId"))!!.data().cloudText("status"))
                assertEquals(1,f.record("deploy")!!.data().number("current"))
            }finally{activity.pause().stop().destroy()}
            time(30);f.resetCache();activity=mount(f,saved)
            try{preview();node(hasText("30 of 60 seconds recorded")).assertExists();node(hasText("Confirm uninterrupted deployment")).assertIsNotEnabled()
                assertEquals(56,sheet(f).number("currentHp"));assertEquals(1,saved.size)
            }finally{activity.pause().stop().destroy()}
            time(30);activity=mount(f,saved)
            try{preview();node(hasText("60 of 60 seconds recorded")).assertExists()
                node(hasText("This actor is willing to transform")).performClick();press("Confirm uninterrupted deployment");result(f,saved,2)
                val heavy=sheet(f);assertEquals("rig-form",heavy.cloudText("formId"));assertEquals(18,heavy.getValue("ac").jsonObject.number("value"))
                assertEquals(20,heavy.getValue("abilities").jsonObject.getValue("STR").jsonObject.number("effective"))
                assertEquals(listOf(40,0),listOf("walk","fly").map{heavy.getValue("movement").jsonObject.number(it)})
                assertEquals(0,f.record("deploy")!!.data().number("current"));assertEquals(56,heavy.number("currentHp"))
            }finally{activity.pause().stop().destroy()}
            time(3599);assertEquals("rig-form",sheet(f).cloudText("formId"));time(1);activity=mount(f,saved)
            try{
                node(hasText("Original form")).assertExists();val ended=sheet(f)
                assertEquals(JsonNull,ended["formId"]);assertEquals(16,ended.getValue("ac").jsonObject.number("value"))
                assertEquals(listOf(5,60),listOf("walk","fly").map{ended.getValue("movement").jsonObject.number(it)})
                assertEquals(56,ended.number("currentHp"));assertEquals(0,f.record("deploy")!!.data().number("current"))
                assertEquals(1,f.record("roar")!!.data().number("current"));assertEquals(75,f.record("rig-integrity")!!.data().number("current"))
                assertEquals(2,saved.size)
            }finally{activity.pause().stop().destroy()}
        }
    }
    @Test fun healthCardRepairsOrdinaryHpWhileDeployedAndRequiresASeparateRecordedReconstructionAfterEjection(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use{f->
            f.signal("seedCompanionAcceptance");damage(f,12);castRepair(f,"first-care")
            val saved=mutableListOf<JsonObject>();var activity=mount(f,saved)
            try{hp(48);reviewCare("first-care",60,"2, 4")
                val care=result(f,saved,1);assertEquals(54,care.number("afterHp"));assertEquals(6,care.number("actualHealing"))
                assertEquals(0,f.record("active-repair")!!.data().number("current"));assertEquals(1,f.record("reconstruction")!!.data().number("current"))
            }finally{activity.pause().stop().destroy()}
            seed(f,"complete_rest","""{"actorId":"actor-one","kind":"short_rest","elapsedSeconds":3600,"confirmed":true,"hitDice":[]}""")
            castForm(f);damage(f,11);castRepair(f,"second-care");activity=mount(f,saved)
            try{hp(54);reviewCare("second-care",60,"2, 2")
                val care=result(f,saved,2);assertEquals(58,care.number("afterHp"));assertEquals(64,f.record("rig-integrity")!!.data().number("current"))
                assertEquals("rig-form",sheet(f).cloudText("formId"));assertEquals(3,f.record("blink")!!.data().number("current"))
                assertEquals("resistance",sheet(f).getValue("defenses").jsonArray.map{it.jsonObject}.single{it.cloudText("type")=="force"}.cloudText("kind"))
                assertTrue(f.record("ward")!!.data().getValue("attuned").jsonPrimitive.boolean)
            }finally{activity.pause().stop().destroy()}
            damage(f,122);assertEquals("disabled",f.record(actor)!!.data().cloudText("activityState"));assertEquals(JsonNull,sheet(f)["formId"])
            castRepair(f,"third-care");f.resetCache();activity=mount(f,saved)
            try{hp(0);reviewCare("third-care",600,null)
                val care=result(f,saved,3);assertEquals("reconstruct",care.cloudText("type"));assertEquals("kit-instance",care.cloudText("toolItemId"))
                assertEquals(60,care.number("afterHp"));assertEquals("active",f.record(actor)!!.data().cloudText("activityState"))
                assertEquals(0,f.record("rig-integrity")!!.data().number("current"));assertEquals(0,f.record("deploy")!!.data().number("current"))
                assertEquals(0,f.record("active-repair")!!.data().number("current"));assertEquals(0,f.record("reconstruction")!!.data().number("current"))
                assertEquals(1,f.record("kit-instance")!!.data().number("quantity"));assertEquals("resolved",f.record("third-care")!!.data().cloudText("outcome"))
                assertEquals(saved.last(),f.signal("redeliverDeviceCommand",buildJsonObject{put("commandId",saved.last().cloudText("commandId"))}))
            }finally{activity.pause().stop().destroy()}
        }
    }
}
