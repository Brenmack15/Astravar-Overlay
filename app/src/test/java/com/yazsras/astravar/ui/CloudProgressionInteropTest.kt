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
class CloudProgressionInteropTest {
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
    private fun mount(f:AscensionWorkerFixture,actor:String,saved:MutableList<JsonObject>,inspected:MutableList<String> = mutableListOf(), roster:Boolean=false, failures:MutableList<String>?=null) =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also {activity->
            val initial=runBlocking {f.refresh()}
            val registry=runBlocking {f.client.capabilities()}.getValue("operations").jsonArray.map {it.jsonObject}
            activity.get().setContent {
                var snapshot by remember {mutableStateOf(initial)}
                var id by remember {mutableStateOf<String?>(actor)}
                var editor by remember {mutableStateOf<Pair<String,JsonObject>?>(null)}
                var editorError by remember(editor) {mutableStateOf<String?>(null)}
                AstravarTheme {
                    if(roster && editor==null)CloudRoster(f.client,snapshot,true,{op,args->editor=op to args},{})
                    if(!roster)Column { CloudActorWorkspace(f.client,snapshot,true,id,{id=it},{op,args->editor=op to args},{inspected.add(it)},{op,args->
                        saved.add(runBlocking {f.client.submit(op,args,snapshot)})
                        snapshot=runBlocking {requireNotNull(f.client.workspace())}
                    }) }
                    editor?.let{(op,args)->CloudCommandForm(registry.single{it.cloudText("name")==op},false,{editor=null},{reviewed,key->
                        try {
                            val result=runBlocking {f.client.submit(op,reviewed,snapshot,key)}
                            saved.add(result);snapshot=runBlocking{requireNotNull(f.client.workspace())};editor=null
                        }catch(e:OwnerSyncFailure){
                            if(failures==null)throw e
                            failures.add(e.code);editorError=cloudError(e)
                        }
                    },editorError,args)}
                }
            }
        }
    private fun openProgression(){
        node(hasContentDescription("Character administration")).performClick()
        press("Advance selected character")
    }

    @Test fun twoPinnedClassesAdvanceOnlyTheSelectedClassAndKeepSpentDiceAfterFreshCachedNavigation(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use{f->
            seed(f,"define_actor","""{"actorId":"navigator","name":"Test navigator","role":"player","rulesProfile":"revised_2024","level":4,"currentHp":22,"maxHp":32,"abilities":{"CON":14},"classTracks":[{"classId":"trail","name":"Wayfinder","edition":"legacy_2014","source":"Trail edition A","level":3},{"classId":"rune","name":"Runescribe","edition":"custom","source":"Rune edition B","level":1}]}""")
            for((id,c,d,n,current) in listOf(listOf("trail-die","trail",10,3,1),listOf("rune-die","rune",8,1,0))){
                seed(f,"define_resource","""{"actorId":"navigator","poolId":"$id","name":"d$d Hit Die","type":"hit_die","classId":"$c","dieSides":$d,"current":$current,"maximum":$n,"recovery":"edition_hit_dice","source":"Synthetic class resource"}""")
            }
            val saved=mutableListOf<JsonObject>()
            val initial=f.record("navigator")!!.data()
            val activity=mount(f,"navigator",saved)
            try{
                node(hasText("Test navigator")).assertExists();openProgression()
                node(hasText("Wayfinder · level 3")).assertExists()
                node(hasText("Legacy 2014 · Trail edition A")).assertExists();node(hasText("Custom · Rune edition B")).assertExists()
                press("Runescribe · level 1")
                press("Record a newly earned subclass")
                input("Subclass name","Inscriber");input("Subclass rules source","Inscriber revision C")
                input("Advancement source","Confirmed synthetic milestone");input("Confirmed maximum HP gain","4")
                press("Grant one d8 Hit Die")
                press("Review advancement")
                node(hasText("Advance actor level")).assertExists()
                assertTrue(saved.isEmpty());assertEquals(initial,f.record("navigator")!!.data())
                press("Submit")
                compose.waitUntil(10000){saved.size==1}
                val result=saved.single();assertEquals("COMMITTED",result.cloudText("status"))
                assertEquals(result,f.receipt(result.cloudText("commandId")))
                val outcome=result.getValue("receipt").jsonObject.getValue("outcome").jsonObject
                val event=f.record(outcome.cloudText("eventId"))!!.data()
                val gained=event.getValue("classChange").jsonObject
                assertEquals("rune",gained.cloudText("classId"));assertEquals(1,gained.getValue("before").jsonObject.number("level"))
                assertEquals("Inscriber revision C",gained.getValue("after").jsonObject.getValue("subclass").jsonObject.cloudText("source"))
                assertEquals(3,outcome.number("proficiency"));assertEquals(listOf(22,36),listOf(outcome.number("hp"),outcome.number("maxHp")))
                val nativeRolls=f.dm("get_cloud_actor_sheet",obj("""{"id":"navigator"}""")).getValue("sheet").jsonObject
                assertEquals(initial.tracks()[0],nativeRolls.tracks()[0]);assertEquals(listOf(3,2),nativeRolls.tracks().map{it.number("level")})
                assertEquals("revised_2024",nativeRolls.cloudText("rulesProfile"))
                assertEquals(listOf(1,3),f.record("trail-die")!!.data().let{listOf(it.number("current"),it.number("maximum"))})
                assertEquals(listOf(1,2),f.record("rune-die")!!.data().let{listOf(it.number("current"),it.number("maximum"))})
                assertEquals(result,f.signal("redeliverDeviceCommand",buildJsonObject{put("commandId",result.cloudText("commandId"))}))
            }finally{activity.pause().stop().destroy()}
            f.resetCache()
            val reopened=mount(f,"navigator",saved)
            try{
                node(hasText("Test navigator")).assertExists();openProgression()
                node(hasText("Character level · 5")).assertExists();node(hasText("HP · 22 / 36")).assertExists()
                node(hasText("Wayfinder · level 3")).assertExists();node(hasText("Runescribe · level 2")).assertExists()
                node(hasText("Inscriber · Inscriber revision C")).assertExists()
                press("Runescribe · level 2");node(hasText("d8 Hit Die · 1 / 2")).assertExists()
                assertEquals(1,saved.size);assertEquals(f.record("navigator"),runBlocking{f.client.record("navigator")})
            }finally{reopened.pause().stop().destroy()}
        }
    }
    @Test fun rosterCreatesOnePlayableCompanionAndPreservesTheOwnersExactMembershipDecision(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use {f->
            val saved=mutableListOf<JsonObject>()
            val activity=mount(f,"",saved,roster=true)
            val actorId: String
            try {
                press("Create companion")
                input("Name","Fixture assistant");input("Level","2")
                press("Select");press("Legacy 2014")
                press("Submit");compose.waitUntil(10000){saved.size==1}
                val result=saved.single()
                assertEquals(result,f.receipt(result.cloudText("commandId")))
                actorId=result.getValue("receipt").jsonObject.getValue("outcome").jsonObject.cloudText("actorId")
                assertEquals("companion",f.record(actorId)!!.data().cloudText("role"));assertEquals(actorId,f.record(actorId)!!.cloudText("actorId"))
                press("Fixture assistant");node(hasText("Party member")).assertExists();press("Review membership change")
                input("Source","Fixture company roster")
                press("Submit");compose.waitUntil(10000){saved.size==2}
                val pending=saved.last();assertEquals("PENDING_APPROVAL",pending.cloudText("status"))
                assertNull(f.record(actorId)!!.data()["membership"])
                val committed=f.signal("ownerVerify",buildJsonObject{put("commandId",pending.cloudText("commandId"));put("digest",pending.cloudText("digest"))})
                assertEquals(committed,f.receipt(pending.cloudText("commandId")))
                assertEquals("active",f.record(actorId)!!.data().getValue("membership").jsonObject.cloudText("participation"))
                assertTrue(f.record(actorId)!!.data().getValue("membership").jsonObject.getValue("inParty").jsonPrimitive.boolean)
                assertEquals(actorId,f.dm("get_cloud_actor_sheet",buildJsonObject{put("id",actorId)}).getValue("sheet").jsonObject.cloudText("actorId"))
            } finally {activity.pause().stop().destroy()}
            f.resetCache();val back=mount(f,actorId,saved,roster=true)
            try {press("Fixture assistant");node(hasText("Fixture company roster")).assertExists()
                assertEquals(listOf(actorId),runBlocking{f.client.records(kinds=setOf("actor"))}.map{it.cloudText("id")})
            }finally{back.pause().stop().destroy()}
        }
    }

    @Test fun sourcedFeatureAndDefenseWalkthroughKeepsTheLinkedActionTrainingAndARefusedStaleConditionDistinct(){
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { f ->
            seed(f,"define_actor","""{"actorId":"reader","name":"Fixture reader","role":"player","rulesProfile":"revised_2024","level":3,"abilities":{"DEX":14,"INT":16}}""")
            seed(f,"set_skill_training","""{"actorId":"reader","skillId":"investigation","name":"Investigation","ability":"INT","rank":2}""")
            seed(f,"define_action","""{"actorId":"reader","actionId":"inspect-cipher","name":"Read cipher","type":"use","timing":"bonus_action","source":"Signal manual"}""")
            for((id,title,operation) in listOf(Triple("lining","Lined coat","resistance"),Triple("field","Field shield","immunity"))){
                seed(f,"define_effect","""{"actorId":"reader","effectId":"$id","name":"$title","source":"$title specification","target":"damage_defense","key":"cold","operation":"$operation"}""")
            }
            seed(f,"define_effect","""{"actorId":"reader","effectId":"reflex","name":"Reflex training","source":"Practice notes","target":"save","key":"DEX","operation":"add","amount":1}""")
            seed(f,"define_feature","""{"actorId":"reader","featureId":"observing","name":"Signal reader","kind":"feat","description":"Read a visible signal and perform the configured search.","source":"Field training revision B","feat":{"familyId":"signal-family","edition":"homebrew","includedExpertise":["investigation"]},"lipReading":{"visibleMouth":true,"understoodLanguage":true},"references":[{"kind":"action_definition","id":"inspect-cipher"}]}""")
            val saved=mutableListOf<JsonObject>();val inspected=mutableListOf<String>()
            val failures=mutableListOf<String>()
            val activity=mount(f,"reader",saved,inspected,failures=failures)
            try{
                press("Fixture reader");node(hasText("Cold · Resistance")).assertExists();node(hasText("Field shield specification")).assertExists()
                press("Features & Traits");press("Signal reader")
                node(hasText("Field training revision B")).assertExists();node(hasText("Pinned rules · Homebrew")).assertExists()
                node(hasText("Expertise · Investigation")).assertExists()
                node(hasText("Lip-reading requires a visible mouth and an understood language.")).assertExists()
                press("Read cipher");assertEquals(listOf("inspect-cipher"),inspected)
                val localAction=runBlocking {requireNotNull(f.client.record(inspected.single()))}
                assertEquals("bonus_action",localAction.data().cloudText("timing"));assertEquals("Signal manual",localAction.data().cloudText("source"))
                val sheet=f.dm("get_cloud_actor_sheet",obj("""{"id":"reader"}""")).getValue("sheet").jsonObject
                assertEquals(7,sheet.getValue("skills").jsonObject.getValue("investigation").jsonObject.number("value"))
                assertTrue(sheet.getValue("communications").jsonArray.any{it.jsonObject.cloudText("kind")=="lip_reading"})
                val linked=f.dm("get_cloud_record_links",obj("""{"id":"observing"}""")).getValue("links").jsonArray
                assertTrue(linked.any {it.jsonObject.cloudText("relation")=="feature_mechanic"&&it.jsonObject.cloudText("to_id")=="inspect-cipher"})
                node(hasTestTag("section-Actions")).performClick();press("Use")
                press("Select");press("True");press("Submit")
                compose.waitUntil(10000){saved.size==1}
                val use=saved.single();assertEquals(use,f.receipt(use.cloudText("commandId")))
                val useId=use.getValue("receipt").jsonObject.getValue("outcome").jsonObject.cloudText("useId")
                assertEquals("inspect-cipher",f.record(useId)!!.data().cloudText("actionId"))
                fun prepareCondition(enabled:Boolean){
                    press("Conditions");press("Manage condition");input("Key","prone")
                    press("Select");press(if(enabled)"True" else "False");input("Source Id","confirmed-fall")
                }
                prepareCondition(true);press("Submit");compose.waitUntil(10000){saved.size==2}
                val added=saved.last();assertEquals(added,f.receipt(added.cloudText("commandId")))
                prepareCondition(false)
                seed(f,"enable_effect","""{"actorId":"reader","effectId":"field","enabled":false}""")
                press("Submit");compose.waitUntil(10000){failures.isNotEmpty()}
                assertEquals(listOf("EXPECTED_REVISION_CONFLICT"),failures);assertEquals(2,saved.size)
                node(hasText("The campaign changed.",substring=true)).assertExists()
                assertEquals(listOf("prone"),f.record("reader")!!.data().getValue("conditions").jsonArray.map{it.jsonPrimitive.content})
            }finally{activity.pause().stop().destroy()}
            f.resetCache();val fresh=mount(f,"reader",saved)
            try{
                press("Fixture reader");node(hasText("Lined coat specification")).assertExists()
                compose.onNodeWithText("Field shield specification").assertDoesNotExist()
                val sheet=f.dm("get_cloud_actor_sheet",obj("""{"id":"reader"}""")).getValue("sheet").jsonObject
                assertEquals(listOf("lining"),sheet.getValue("defenses").jsonArray.map{it.jsonObject.cloudText("source")})
                assertEquals(3,sheet.getValue("saves").jsonObject.getValue("DEX").jsonObject.number("value"))
                assertEquals(f.record("reader"),runBlocking{f.client.record("reader")})
            }finally{fresh.pause().stop().destroy()}
        }
    }

}
