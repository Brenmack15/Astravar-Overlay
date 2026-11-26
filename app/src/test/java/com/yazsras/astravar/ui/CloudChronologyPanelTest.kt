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
class CloudChronologyPanelTest {
    @get:Rule val compose=createAstravarComposeRule()
    @Test fun recordAndCorrectionDraftsPreserveUnknownTimeAndStableSessionEdges(){
        val create=chronologyDraft("campaign_session","s2",parent="arc",previous="s1")
        assertEquals(JsonNull,create["playedOn"]);assertEquals(JsonNull,create["worldStart"])
        assertEquals(JsonPrimitive("s1"),create["previousSessionId"])
        val row=buildJsonObject{put("kind","campaign_session");put("id","s2");put("title","Recorded session");put("revision",3)
            put("data",JsonObject(create+mapOf("playedOn" to JsonPrimitive("2026-11-26"),"source" to JsonPrimitive("Private table notes"))))}
        val edit=chronologyDraft("campaign_session","s2",row)
        assertEquals(JsonPrimitive(3),edit["expectedRevision"]);assertEquals(JsonPrimitive("2026-11-26"),edit["playedOn"])
        assertEquals(JsonPrimitive("arc"),edit["arcId"]);assertFalse("lastRecordedAt" in edit)
        assertThrows(IllegalArgumentException::class.java){chronologyDraft("story_arc","s2",row)}
    }
    @Test fun openingASessionAndPreparingItsSuccessorDoesNotCommitOrAdvanceTheCalendar(){
        val now=System.currentTimeMillis();val format=buildJsonObject{
            put("authority","cloud");put("continuityId","qa-ui");put("subject","device:device-a");put("epoch",1)
            put("revision",2);put("grantRevision",1);put("grantExpiresAt",now+3600000)}
        val credential=OwnerSyncCredential("device-a","qa-ui","https://owner.example.test",now+3600000,"a".repeat(44))
        fun row(id:String,kind:String,title:String,data:JsonObject)=buildJsonObject{
            put("id",id);put("kind",kind);put("actorId",JsonNull);put("title",title);put("life","active");put("revision",1);put("data",data)}
        val events=listOf(row("arc","story_arc","Fictional voyage",chronologyDraft("story_arc","arc")),
            row("session-one","campaign_session","Fictional session",chronologyDraft("campaign_session","session-one",parent="arc")))
        val cache=CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(),null)
        val client=CloudCampaignClient(cache,{CloudSession(credential,null)},OwnerConnectionTransport{_,path,_,_,body->
            assertNull(body)
            OwnerSyncResponse(200,if(path.endsWith("/capabilities"))format else JsonObject(format+mapOf("nextAfter" to JsonNull,"records" to JsonArray(events))))
        },{now},mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
        val snapshot=runBlocking{client.refresh(true)}
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val draft=mutableListOf<Pair<String,JsonObject>>()
        try{
            activity.get().setContent{AstravarTheme{Column(Modifier.verticalScroll(rememberScrollState())){
                CloudChronologyPanel("campaign_session",client,snapshot,true,"",{op,args->draft+=op to args},{fail("Unexpected link open")})
            }}}
            compose.waitUntil(10000){compose.onAllNodesWithText("Fictional session").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Fictional session").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Adventure or arc · Fictional voyage").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Table date · Unknown").assertExists()
            assertTrue(draft.isEmpty())
            compose.onNodeWithText("Add following session").performScrollTo().performClick()
            compose.waitUntil(10000){draft.isNotEmpty()}
            val (operation,args)=draft.single();assertEquals("record_campaign_session",operation)
            assertEquals(JsonPrimitive("session-one"),args["previousSessionId"]);assertEquals(JsonPrimitive("arc"),args["arcId"])
            assertEquals(JsonNull,args["worldStart"]);assertEquals(2L,snapshot.version.revision)
        }finally{activity.pause().stop().destroy();cache.close()}
    }
}
