package com.yazsras.astravar.data

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yazsras.astravar.ui.AstravarTheme
import com.yazsras.astravar.ui.CloudActorWorkspace
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AscensionCompanionActionsNativeTest : AscensionCloudGuest() {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val actor="actor-one"
    private val transactions=ConcurrentHashMap<String,String>()
    private val denied=linkedMapOf<String,String>()
    private val drawn=mutableListOf<String>()
    private val name=AtomicReference("")
    private val failure=AtomicReference<Throwable?>(null)

    private fun query(tool:String,args:JsonObject):JsonObject {
        val result=owner("/mcp",buildJsonObject{
            put("jsonrpc","2.0");put("id",1);put("method","tools/call")
            putJsonObject("params"){put("name",tool);putJsonObject("arguments"){
                put("continuityId",scope);args.forEach{(k,v)->put(k,v)}
            }}
        }).getValue("result").jsonObject
        assertFalse("Registered DM refused $tool",result.getValue("isError").jsonPrimitive.boolean)
        return result.getValue("structuredContent").jsonObject
    }
    private fun verify(id:String,result:JsonObject):JsonObject{
        assertEquals("COMMITTED",result.cloudText("status"))
        assertEquals(result,query("get_cloud_receipt",buildJsonObject{put("commandId","eq37-"+id)}))
        val receipt=result.getValue("receipt").jsonObject
        transactions[id]=receipt.cloudText("transactionId")
        return receipt.getValue("outcome").jsonObject
    }
    private fun find(m:SemanticsMatcher):SemanticsNodeInteraction{
        compose.waitUntil(45000){compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()}
        val inScroll=compose.onAllNodes(m and hasAnyAncestor(hasScrollAction()))
        return if(inScroll.fetchSemanticsNodes().isEmpty())compose.onAllNodes(m).onFirst() else inScroll.onFirst().performScrollTo()
    }
    private fun press(text:String)=find(hasText(text) and hasClickAction() and isEnabled()).performClick()
    private fun text(label:String,value:String)=find(hasText(label) and hasSetTextAction()).performTextInput(value)

    @Test fun fiveSourceAttacksAndActiveEffectsAcrossADeployedMinute():Unit=runBlocking {
        val plan=Json.parseToJsonElement(File(context.filesDir,"companion-instrumented-plan.json").readText()).jsonObject
        val vault=OwnerSyncCredentialVault(context)
        val credential=requireNotNull(vault.credential(origin,scope))
        val scopes=listOf("character","combat","resources","rolls","spells","inventory","economy","chronicle","world")
            .flatMap{listOf(it+".read",it+".write")}+"approval.read"
        owner("$base/grants/device:"+credential.credentialId,buildJsonObject{
            put("scopes",JsonArray(scopes.map(::JsonPrimitive)));put("auto",JsonArray(scopes.filter{it.endsWith(".write")}.map(::JsonPrimitive)))
            putJsonArray("actors"){add("*")};put("expectedGrantRevision",1);put("expiresAt",System.currentTimeMillis()+86400000);put("revoked",false)
        })
        var client=CloudCampaignClient.open(context)
        var current=client.refresh(openExisting=true)
        val baseline=current.version.revision
        var visible by mutableStateOf(false)
        var display by mutableStateOf(current)
        compose.setContent {
            val coroutine=rememberCoroutineScope()
            if(visible)AstravarTheme{Column{CloudActorWorkspace(client,display,true,actor,{}, {op,_->error("Unexpected editor $op")}, {}, {op,args->
                coroutine.launch {
                    try {
                        val id=name.get()
                        check(id.isNotEmpty() && !transactions.containsKey(id))
                        val saved=client.submit(op,args,display,"eq37-"+id)
                        val receipt=saved.getValue("receipt").jsonObject
                        display=requireNotNull(client.workspace())
                        transactions[id]=receipt.cloudText("transactionId")
                    }catch(t:Exception){failure.set(t)}
                }
            })}}
        }
        suspend fun applyGroup(group:String){
            for(input in plan.getValue(group).jsonArray){
                val cmd=input.jsonObject;val id=cmd.cloudText("id");val op=cmd.cloudText("op")
                val args=cmd.getValue("args").jsonObject
                val before=current.version.revision
                if("refused" in cmd){
                    val payload=buildJsonObject{put("commandId","eq37-"+id);put("version",1);put("epoch",current.version.epoch);put("expectedRevision",before);put("operation",op);put("args",args)}
                    val resp=OwnerConnectionHttpsTransport().request(origin,"$base/commands",credential,vault.serviceAccess(origin,scope),payload.toString())
                    assertTrue("Expected refused $id, HTTP "+resp.status,resp.status in 400..499)
                    val error=resp.json.getValue("error").jsonObject.cloudText("code")
                    assertEquals(id,cmd.cloudText("refused"),error);denied[id]=error
                    assertEquals(before,query("get_cloud_state",buildJsonObject{put("limit",1)}).getValue("revision").jsonPrimitive.long)
                }else{
                    val result=client.submit(op,args,current,"eq37-"+id)
                    verify(id,result);current=requireNotNull(client.workspace())
                    assertEquals(id,before+1,current.version.revision)
                }
            }
        }
        suspend fun readItem(id:String)=requireNotNull(client.record(id)).getValue("data").jsonObject
        suspend fun pool(id:String)=readItem(id).getValue("current").jsonPrimitive.int
        suspend fun mountActions() {
            compose.runOnIdle{display=current;visible=true}
            press("Actions")
        }
        suspend fun listen(id:String):JsonObject{
            compose.waitUntil(75000){failure.get()!=null||transactions.containsKey(id)}
            failure.get()?.let{throw it}
            current=requireNotNull(client.workspace())
            return verify(id,client.verify("eq37-"+id))
        }
        suspend fun attacks(ids:List<String>,ineligible:List<String>,aided:Boolean){
            ids.forEach{id->
                name.set("ui-"+id)
                find(hasContentDescription("Roll $id attack") and hasClickAction() and isEnabled()).performClick()
                val hit=listen("ui-"+id)
                assertEquals(id,hit.cloudText("attackId"));assertEquals(if(hit.getValue("hit").jsonObject.getValue("natural").jsonPrimitive.int==1)"miss" else "pending",hit.cloudText("outcome"))
                assertEquals(JsonNull,hit["damage"]);assertEquals(JsonNull,hit["appliedDamage"])
                val actual=hit.getValue("hit").jsonObject
                assertEquals("app",actual.cloudText("origin"))
                assertEquals(aided,"aidDice" in actual)
                val held=readItem(hit.cloudText("attemptId"))
                assertEquals(hit["actorId"],held["actorId"]);assertEquals(hit["formId"],held["formId"])
                drawn+=id
            }
            for(id in ineligible)assertTrue("Hidden incompatible family $id",compose.onAllNodes(hasContentDescription("Roll $id attack")).fetchSemanticsNodes().isEmpty())
        }
        suspend fun blink(large:Boolean) {
            val id=if(large)"ui-heavy-shift" else "ui-base-shift"
            name.set(id);press("Use Short displacement")
            text("Destination",if(large)"Synthetic landing" else "Synthetic perch");text("Distance in feet","30")
            press("Choose available space");press("Tiny");press("Airborne")
            press("Confirm visible, unoccupied space, clear passage and no passengers")
            if(large) {
                find(hasText("Confirm displacement")).assertIsNotEnabled()
                press("Tiny space");press("Large");find(hasText("Confirm displacement")).assertIsNotEnabled()
                press("Grounded")
            }
            press("Confirm displacement");listen(id)
            assertEquals(if(large)1 else 2,pool("blink"))
        }
        fun unmount(){compose.runOnIdle{visible=false};compose.waitForIdle()}
        suspend fun projection():JsonObject {
            val sheet=client.projection(current,"sheet/$actor").getValue("sheet").jsonObject
            val components=client.projection(current,"actions/$actor").getValue("rows").jsonArray
            val resourceIds=listOf("blink","dial-pool","deploy","rig-integrity","roar","operator-slot")
            val balances=resourceIds.associateWith{pool(it)}
            val book=client.projection(current,"spellbook/$actor")
            assertTrue(book.getValue("slots").jsonArray.isEmpty());assertTrue(book.getValue("rows").jsonArray.isEmpty())
            return buildJsonObject{
                for(k in listOf("formId","size","currentHp","maxHp","ac","abilities","movement","senses","skills","saves","mobilityRules","communications","rollAids","activeEffects","defenses","rollModes"))put(k,sheet.getValue(k))
                put("actions",components);put("companionSpellSlots",0);putJsonObject("pools"){balances.forEach{(id,n)->put(id,n)}}
            }
        }
        applyGroup("before")
        val ordinary=projection()
        mountActions();for(label in listOf("Normal pair","Interpret message"))find(hasText(label)).assertExists()
        attacks(listOf("rake","nip","pulse"),listOf("large-bite","large-rake"),true);blink(false);unmount()
        applyGroup("deployment")
        client=CloudCampaignClient.open(context);current=client.refresh()
        val heavy=projection()
        mountActions();for(label in listOf("Large pair","Selective cry"))find(hasText(label)).assertExists()
        attacks(listOf("large-bite","large-rake"),listOf("rake","nip","pulse"),false);blink(true);unmount()
        applyGroup("after")
        val shellBeforeRecovery=pool("rig-integrity");assertEquals(63,shellBeforeRecovery)
        applyGroup("recovery")
        assertEquals(0,pool("dial-pool"))
        val restoredActor=readItem(actor);val dawn=buildJsonObject{put("actorId","actor-one");put("eventId","eq37-dawn");put("gameSeconds",restoredActor.getValue("gameSeconds"));put("confirmed",true);put("source","Synthetic observed dawn")}
        verify("dawn",client.submit("complete_dawn_recovery",dawn,current,"eq37-dawn"))
        client=CloudCampaignClient.open(context);current=client.refresh();assertEquals(1,pool("dial-pool"))
        val final=projection()
        assertEquals(53,final.getValue("currentHp").jsonPrimitive.int)
        assertEquals(JsonNull,final.getValue("formId"));assertEquals(0,pool("rig-integrity"))
        evidence("companion-actions-device",buildJsonObject{
            put("api",Build.VERSION.SDK_INT);put("androidRelease",Build.VERSION.RELEASE);put("emulator",true);put("package",context.packageName)
            put("baseRevision",baseline);put("finalRevision",current.version.revision);put("drawn",JsonArray(drawn.map(::JsonPrimitive)))
            put("ordinary",ordinary);put("heavy",heavy);put("final",final);put("shellBeforeRecovery",shellBeforeRecovery);put("reopened",true)
            putJsonObject("transactions"){transactions.toSortedMap().forEach{(k,v)->put(k,v)}}
            putJsonObject("denied"){denied.forEach{(k,v)->put(k,v)}}
        })
    }
}
