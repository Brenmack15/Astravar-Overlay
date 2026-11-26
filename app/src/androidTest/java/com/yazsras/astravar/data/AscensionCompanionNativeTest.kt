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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Actual installed API 37 debug client, using the original paired QA credential after the reconnect gate. */
@RunWith(AndroidJUnit4::class)
class AscensionCompanionNativeTest : AscensionCloudGuest() {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val actorId="api37-frame"
    private val formId="rig-form"
    private val prefix="qa37-form-"
    private fun JsonObject.n(key:String)=getValue(key).jsonPrimitive.int
    private fun body(block:JsonObjectBuilder.()->Unit)=buildJsonObject{put("actorId",actorId);block()}
    private fun mcp(name:String,args:JsonObject):JsonObject=owner("/mcp",buildJsonObject{
        put("jsonrpc","2.0");put("id",1);put("method","tools/call");putJsonObject("params"){
            put("name",name);putJsonObject("arguments"){put("continuityId",scope);args.forEach{(k,v)->put(k,v)}}
        }
    }).getValue("result").jsonObject
    private fun rpc(name:String,args:JsonObject):JsonObject {
        val result=mcp(name,args);assertFalse("Registered MCP refused $name",result.getValue("isError").jsonPrimitive.boolean)
        return result.getValue("structuredContent").jsonObject
    }
    private fun find(matcher:SemanticsMatcher):SemanticsNodeInteraction{
        compose.waitUntil(45000){compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        val scrolling=compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction()))
        return if(scrolling.fetchSemanticsNodes().isEmpty())compose.onAllNodes(matcher).onFirst() else scrolling.onFirst().performScrollTo()
    }
    private fun press(text:String)=find(hasText(text) and hasClickAction()).performClick()
    private fun preview(){find(hasContentDescription("Open forms and configurations")).performClick();find(hasText("Heavy frame") and hasText("Form") and hasClickAction()).performClick()}

    @Test fun timedFormSharedDisplacementAndSingleOverflow():Unit=runBlocking {
        val vault=OwnerSyncCredentialVault(context)
        val credential=requireNotNull(vault.credential(origin,scope))
        val categories=listOf("character","resources","combat","inventory","rolls","spells","economy","chronicle","world")
        val scopes=categories.map{"$it.read"}+listOf("character.write","resources.write","combat.write","approval.read")
        owner("$base/grants/device:"+credential.credentialId,buildJsonObject{
            put("scopes",JsonArray(scopes.map(::JsonPrimitive)));put("auto",JsonArray(scopes.filter{it.endsWith(".write")}.map(::JsonPrimitive)))
            putJsonArray("actors"){add("*")};put("expectedGrantRevision",1);put("expiresAt",System.currentTimeMillis()+86400000);put("revoked",false)
        })
        var client=CloudCampaignClient.open(context)
        var snapshot=client.refresh(openExisting=true)
        val baseline=snapshot.version.revision
        val transactions=ConcurrentHashMap<String,String>()
        val refused=mutableListOf<String>()
        val observations=linkedMapOf<String,Int>()
        val uiError=AtomicReference<Throwable?>(null)
        fun observe(key:String,value:Int,expected:Int){assertEquals(key,expected,value);observations[key]=value}
        fun witness(step:String,result:JsonObject):JsonObject {
            assertEquals("COMMITTED",result.cloudText("status"))
            val dm=rpc("get_cloud_receipt",buildJsonObject{put("commandId",prefix+step)})
            assertEquals("Cross-client receipt for $step",dm,result)
            val receipt=result.getValue("receipt").jsonObject
            transactions[step]=receipt.cloudText("transactionId")
            return receipt.getValue("outcome").jsonObject
        }
        suspend fun apply(step:String,operation:String,args:JsonObject):JsonObject{
            val result=client.submit(operation,args,snapshot,prefix+step)
            snapshot=requireNotNull(client.workspace())
            return witness(step,result)
        }
        suspend fun sheet()=client.projection(snapshot,"sheet/$actorId").getValue("sheet").jsonObject
        suspend fun pool(id:String)=requireNotNull(client.record(id)).getValue("data").jsonObject.n("current")
        fun envelope(step:String,op:String,args:JsonObject,revision:Long)=buildJsonObject{
            put("version",1);put("epoch",1);put("expectedRevision",revision);put("commandId",prefix+step);put("operation",op);put("args",args)
        }
        suspend fun clock(step:String,seconds:Int){
            val command=envelope(step,"advance_actor_time",body{put("elapsedSeconds",seconds);put("confirmed",true)},snapshot.version.revision)
            val result=rpc("submit_cloud_command",buildJsonObject{put("command",command)})
            witness(step,result);snapshot=client.refresh()
        }
        fun denied(step:String,op:String,args:JsonObject,code:String){
            val before=snapshot.version.revision
            val result=mcp("submit_cloud_command",buildJsonObject{put("command",envelope(step,op,args,before))})
            assertTrue(result.getValue("isError").jsonPrimitive.boolean)
            val detail=Json.parseToJsonElement(result.getValue("content").jsonArray.first().jsonObject.cloudText("text")).jsonObject
            assertEquals(code,detail.cloudText("error"));refused.add(code)
            assertEquals(before,rpc("get_cloud_state",buildJsonObject{put("limit",1)}).getValue("revision").jsonPrimitive.long)
        }
        fun blink(id:String,grounded:Boolean,size:String)=body{
            put("actionId","shift");put("useId","api37-blink-"+id);put("declared",true)
            putJsonObject("destination"){put("label","Test ledge "+id);put("distanceFeet",30);put("visible",true);put("unoccupied",true)
                put("passengers",0);put("permittedByBarriers",true);put("grounded",grounded);put("availableSize",size)}
        }
        observe("initialHp",sheet().n("currentHp"),56);observe("initialIntegrity",pool("rig-integrity"),75)
        assertEquals(3,pool("blink"));assertEquals(1,pool("deploy"))
        apply("blink-base","use_action",blink("ordinary",false,"tiny"));observe("ordinaryBlink",pool("blink"),2)

        var surface by mutableStateOf(0)
        var displayed by mutableStateOf(snapshot)
        compose.setContent {
            val coroutine=rememberCoroutineScope()
            key(surface){AstravarTheme{Column{CloudActorWorkspace(client,displayed,true,actorId,{}, {_,_->}, {}, {op,args->
                coroutine.launch {
                    try{
                        val step=when(op){"begin_form_deployment"->"begin";"change_form"->"complete";else->error("Unexpected Forms operation $op")}
                        val result=client.submit(op,args,displayed,prefix+step)
                        displayed=requireNotNull(client.workspace())
                        transactions[step]=result.getValue("receipt").jsonObject.cloudText("transactionId")
                    }catch(ex:Exception){uiError.set(ex)}
                }
            })}}}
        }
        suspend fun awaitNative(step:String):JsonObject {
            compose.waitUntil(60000){uiError.get()!=null||transactions.containsKey(step)};uiError.get()?.let{throw it}
            snapshot=requireNotNull(client.workspace());return witness(step,client.verify(prefix+step))
        }
        suspend fun reopen() {
            client=CloudCampaignClient.open(context);snapshot=client.refresh()
            compose.runOnIdle {displayed=snapshot;surface++}
        }
        preview();find(hasText("Begin deployment")).assertIsNotEnabled()
        assertEquals(1,pool("deploy"));assertEquals(56,sheet().n("currentHp"))
        find(hasText("This actor is willing to transform")).performClick();press("Begin deployment")
        val deployment=awaitNative("begin").cloudText("deploymentId")
        assertEquals(1,pool("deploy"))
        clock("clock-30",30);reopen();preview()
        find(hasText("30 of 60 seconds recorded")).assertExists();find(hasText("Confirm uninterrupted deployment")).assertIsNotEnabled()
        fun completion()=body{put("formId",formId);put("deploymentId",deployment);put("elapsedSeconds",0);put("uninterrupted",true);put("willing",true)}
        denied("early","change_form",completion(),"DEPLOYMENT_TIME_REMAINING")
        val projected=client.projection(snapshot,"forms/$actorId/$formId").getValue("preview").jsonObject
        observe("halfwaySeconds",projected.getValue("deployment").jsonObject.n("elapsedSeconds"),30)
        clock("clock-60",30);reopen();preview()
        find(hasText("60 of 60 seconds recorded")).assertExists();find(hasText("This actor is willing to transform")).performClick()
        press("Confirm uninterrupted deployment");awaitNative("complete")
        val heavy=sheet();assertEquals(formId,heavy.cloudText("formId"));observe("deployedAc",heavy.getValue("ac").jsonObject.n("value"),18)
        observe("deployedStrength",heavy.getValue("abilities").jsonObject.getValue("STR").jsonObject.n("effective"),20)
        assertEquals(listOf(40,0),listOf("walk","fly").map{heavy.getValue("movement").jsonObject.n(it)})
        observe("completedSeconds",requireNotNull(client.record(deployment)).getValue("data").jsonObject.n("elapsedSeconds"),60)
        assertEquals(0,pool("deploy"));assertEquals(75,pool("rig-integrity"));assertEquals(56,heavy.n("currentHp"))
        denied("air","use_action",blink("air",false,"large"),"GROUNDED_DESTINATION_REQUIRED")
        denied("tight","use_action",blink("tight",true,"tiny"),"DESTINATION_TOO_SMALL")
        apply("blink-heavy","use_action",blink("heavy",true,"large"));observe("deployedBlink",pool("blink"),1)
        apply("hit-shell","apply_hp_change",body{put("kind","damage");put("amount",11)})
        assertEquals(56,sheet().n("currentHp"));observe("shellAfterHit",pool("rig-integrity"),64)
        clock("almost-expired",3599)
        val almost=requireNotNull(client.record(actorId)).getValue("data").jsonObject
        assertEquals(formId,sheet().cloudText("formId"));observe("remainingAt3599",almost.n("formExpiresAt")-almost.n("gameSeconds"),1)
        clock("expired",1);assertEquals(JsonNull,sheet().getValue("formId"));observe("afterNaturalExpiryHp",sheet().n("currentHp"),56)
        assertEquals(64,pool("rig-integrity"));assertEquals(0,pool("deploy"))
        apply("rest","complete_rest",body{put("kind","long_rest");put("elapsedSeconds",28800);put("confirmed",true);putJsonArray("hitDice") {}})
        observe("restoredOrdinaryHp",sheet().n("currentHp"),60);assertEquals(1,pool("deploy"));assertEquals(3,pool("blink"))
        apply("redeploy","change_form",body{put("formId",formId);put("elapsedSeconds",60);put("uninterrupted",true);put("willing",true)})
        observe("redeployedIntegrity",pool("rig-integrity"),64)
        val damage=body{put("kind","damage");put("amount",70)};val at=snapshot.version.revision
        val ejection=apply("eject","apply_hp_change",damage)
        observe("absorbedOnEjection",ejection.n("absorbedByIntegrity"),64);observe("overflow",ejection.n("beforeHp")-ejection.n("afterHp"),6)
        val redelivered=OwnerConnectionHttpsTransport().request(origin,"$base/commands",credential,vault.serviceAccess(origin,scope),envelope("eject","apply_hp_change",damage,at).toString())
        assertEquals(200,redelivered.status);assertEquals(client.verify(prefix+"eject"),redelivered.json)
        reopen();val ordinary=sheet()
        observe("finalHp",ordinary.n("currentHp"),54);observe("finalIntegrity",pool("rig-integrity"),0)
        assertEquals(JsonNull,ordinary.getValue("formId"));assertEquals(listOf(5,60),listOf("walk","fly").map{ordinary.getValue("movement").jsonObject.n(it)})
        assertEquals(at+1,snapshot.version.revision);assertEquals(0,pool("deploy"));assertEquals(12,transactions.size)
        find(hasText("Original form")).assertExists()
        evidence("companion-device",buildJsonObject{
            put("api",Build.VERSION.SDK_INT);put("androidRelease",Build.VERSION.RELEASE);put("emulator",true)
            put("baseRevision",baseline);putJsonObject("transactions"){transactions.toSortedMap().forEach{(k,v)->put(k,v)}}
            putJsonObject("observed"){observations.forEach{(k,v)->put(k,v)}};put("refused",JsonArray(refused.map(::JsonPrimitive)))
            put("exactRedelivery",true);put("reopenedNativeCache",true)
        })
    }
}
