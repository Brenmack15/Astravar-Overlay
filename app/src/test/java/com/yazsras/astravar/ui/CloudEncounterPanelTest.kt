package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
@Config(sdk=[35],qualifiers="w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudEncounterPanelTest {
    @get:Rule val compose=createAstravarComposeRule()
    @Test fun startBindsActualRollIdsToDistinctSelectedActors() {
        val r=reviewedEncounterStart("event-one"," First encounter ",linkedMapOf("one" to "roll-1","two" to "roll-2"))
        val roster=r.getValue("participants").jsonArray.map{it.jsonObject}
        assertEquals("First encounter",r.getValue("name").jsonPrimitive.content)
        assertEquals(listOf("roll-1","roll-2"),roster.map{it.getValue("initiativeRollId").jsonPrimitive.content})
        assertTrue(roster.none{"total" in it})
        val linked=reviewedEncounterStart("event","Attached order",mapOf("one" to "roll","companion" to ""),mapOf("companion" to "one"))
        assertFalse("initiativeRollId" in linked.getValue("participants").jsonArray.last().jsonObject)
        assertThrows(IllegalArgumentException::class.java){reviewedEncounterStart("bad","Independent",mapOf("two" to ""))}
        assertThrows(IllegalArgumentException::class.java){reviewedEncounterStart("bad","Title",mapOf("one" to "same","two" to "same"))}
    }

    @Test fun currentTurnOpensAReviewWithoutRollingOrChangingState() {
        val now=System.currentTimeMillis()
        val cred=OwnerSyncCredential("device-a","qa-ui","https://owner.example.test",now+3600000,"a".repeat(44))
        val cache=CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(),null)
        val common=buildJsonObject {put("authority","cloud");put("continuityId","qa-ui");put("subject","device:device-a")
            put("epoch",1);put("revision",8);put("grantRevision",1);put("grantExpiresAt",now+3600000)}
        fun record(id:String,kind:String,title:String,data:JsonObject)=buildJsonObject{
            put("id",id);put("kind",kind);put("actorId",if(kind=="actor")JsonPrimitive(id) else JsonNull);put("title",title)
            put("life","active");put("revision",4);put("data",data)}
        val hero=record("actor-one","actor","Fixture ranger",buildJsonObject{put("role","player");put("currentHp",12);put("maxHp",15)})
        val encounter=record("combat-one","encounter","Fixture encounter",buildJsonObject{
            put("active",true);put("round",3);put("index",0);putJsonArray("initiative"){addJsonObject{
                put("actorId","actor-one");put("rollId","recorded-roll");put("total",16)}}
            putJsonObject("economy"){putJsonObject("actor-one"){put("action",false);put("reaction",true)}}})
        val client=CloudCampaignClient(cache,{CloudSession(cred,null)},OwnerConnectionTransport{_,path,_,_,body->
            assertNull(body)
            OwnerSyncResponse(200,when {
                path.endsWith("/capabilities")->common
                path.endsWith("/sheet/actor-one")->JsonObject(common+buildJsonObject{putJsonObject("sheet"){put("maxHp",19)}})
                else->JsonObject(common+buildJsonObject{put("nextAfter",JsonNull);put("records",JsonArray(listOf(hero,encounter)))})
            })
        },{now},mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
        val snap=runBlocking{client.refresh(true)}
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val reviewed=mutableListOf<Pair<String,JsonObject>>()
        try{
            activity.get().setContent { AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudEncounterPanel(client,snap,true,""){op,args->reviewed+=op to args} } } }
            compose.waitUntil(10000){compose.onAllNodesWithText("Fixture encounter").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Fixture encounter").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Round 3").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(10000){compose.onAllNodesWithText("Hit points · 12 / 19").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(reviewed.isEmpty())
            compose.onNodeWithText("Review next turn").performScrollTo().performClick()
            compose.waitUntil(10000){reviewed.isNotEmpty()}
            assertEquals("advance_encounter_turn",reviewed.single().first)
            assertEquals("actor-one",reviewed.single().second.getValue("currentActorId").jsonPrimitive.content)
            assertEquals("combat-one",reviewed.single().second.getValue("encounterId").jsonPrimitive.content)
            assertEquals(8L,snap.version.revision)
        }finally{activity.pause().stop().destroy();cache.close()}
    }
}
