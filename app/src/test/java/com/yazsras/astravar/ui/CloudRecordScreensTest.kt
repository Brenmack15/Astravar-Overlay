package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CompletableDeferred
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
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudRecordScreensTest {
    @get:Rule val compose=createAstravarComposeRule()

    @Test fun projectionParametersStayBoundToTheSameReviewedSnapshot() {
        val version=CloudVersion(2,10,3,100000)
        val route=recordViewSuffix("timeline",version,"item:known","Copper & silk","9~history-id",20)
        assertTrue(validRecordViewSuffix(route))
        assertTrue(route.contains("query=Copper+%26+silk"))
        assertFalse(validRecordViewSuffix(route+"&limit=1"))
        assertFalse(validRecordViewSuffix(route.replace("records/timeline","records/../commands")))
        assertFalse(validRecordViewSuffix(route.replace("atEpoch=2","atEpoch=-1")))
        assertFalse(validRecordViewSuffix("records/item_history?limit=2&atEpoch=1&grantRevision=0&atRevision=0"))
        assertEquals("1,235.55 GP",cloudCoinsCaption(buildJsonObject {
            put("cp",5);put("sp",0);put("ep",1);put("gp",1235);put("pp",0)
        }))
        assertEquals("0.00 GP known · incomplete",cloudCoinsCaption(buildJsonObject { put("cp",JsonNull) }))
    }

    @Test fun inventoryRowsInspectGroupedHoldingsAndWalletWithoutSubmittingAnOperation() {
        val now=System.currentTimeMillis()
        val credential=OwnerSyncCredential("device-a","qa-ui","https://owner.example.test",now+3_600_000,"a".repeat(44))
        val cache=CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(),null)
        val requested=java.util.concurrent.CopyOnWriteArrayList<String>()
        val common=buildJsonObject {
            put("authority","cloud");put("continuityId","qa-ui");put("subject","device:device-a");put("epoch",1);
            put("revision",8);put("grantRevision",1);put("grantExpiresAt",now+3_600_000)
        }
        fun row(value:JsonObject)=JsonObject(common+value)
        val definition=buildJsonObject {
            put("id","known-def");put("kind","item_definition");put("actorId",JsonNull);put("title","Field supplies");put("life","active");put("revision",1)
            putJsonObject("data"){put("category","Supplies");put("rarity",JsonNull);put("description","Tied provisions");put("source","Fixture")}
        }
        val client=CloudCampaignClient(cache,{CloudSession(credential,null)},OwnerConnectionTransport { _,path,_,_,body ->
            assertNull("Opening a record should only read.",body);requested+=path
            val response=when {
                path.endsWith("/capabilities")->common
                path.contains("/state?")->row(buildJsonObject{put("nextAfter",JsonNull);put("records",JsonArray(listOf(definition)))})
                path.contains("/records/inventory?") && path.contains("id=known-def")->row(buildJsonObject {
                    put("view","inventory");put("nextAfter",JsonNull);putJsonArray("items"){addJsonObject{
                        put("id","one-stack");put("custodianName","Courier");put("quantity",4);put("holding","carried")
                    }}
                })
                path.contains("/records/inventory?")->row(buildJsonObject {
                    put("view","inventory");put("nextAfter",JsonNull);putJsonArray("groups"){addJsonObject{
                        put("title","Field supplies");put("definitionId","known-def");put("knownQuantity",4);put("unknownQuantityCount",0);put("instances",1)
                    }}
                })
                path.contains("/records/wallets?")->row(buildJsonObject {
                    put("view","wallets");put("nextAfter",JsonNull);put("wallets",JsonArray(emptyList()))
                    putJsonObject("totals"){put("knownGp","24.50");put("complete",false)}
                })
                else->error("Unexpected fixture route")
            }
            OwnerSyncResponse(200,response)
        },{now},mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
        val snapshot=runBlocking {client.refresh(true)}
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudRecordScreen("Inventory",null,client,snapshot,true,{_,_->error("Unexpected editor")},{error("Unexpected inspector")})
            } } }
            awaitText("Field supplies")
            compose.onNodeWithText("Field supplies").performClick()
            awaitText("Courier")
            compose.onNodeWithText("Tied provisions").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Wallets").performClick()
            awaitText("24.50 GP")
            compose.onNodeWithText("Known subtotal · unknown denominations or missing wallets").assertExists()
            assertFalse(requested.any { it.endsWith("commands") })
        } finally {activity.pause().stop().destroy();cache.close()}
    }

    @Test fun scopedActorEquipmentAndLoreOpenTheExactReviewedDraft() {
        val now=System.currentTimeMillis()
        val credential=OwnerSyncCredential("scoped","qa-held","https://owner.example.test",now+3_600_000,"a".repeat(44))
        val cache=CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(),null)
        val header=buildJsonObject {
            put("authority","cloud");put("continuityId","qa-held");put("subject","device:scoped");put("epoch",1)
            put("revision",8);put("grantRevision",1);put("grantExpiresAt",now+3_600_000)
        }
        fun record(id:String,kind:String,title:String,data:JsonObject)=buildJsonObject{
            put("id",id);put("kind",kind);put("actorId","bearer");put("title",title);put("life","active");put("revision",2);put("data",data)
        }
        val holding=record("held-one","item_instance","Earlier catalog name",buildJsonObject{
            put("definitionId","catalog-entry");put("ownerId","bearer");put("custodianId","bearer")
            put("quantity",1);put("holding","carried");put("equipment","packed");put("attuned",false);put("partyAccess",true)
        })
        val fact=record("known-one","known_fact","Recorded inscription",buildJsonObject{
            put("detail","An observed sequence of marks");put("evidence","confirmed");put("learnedAt",JsonNull);put("originalWorldTime",JsonNull);put("source","Known source")
        })
        val definition=record("catalog-entry","item_definition","Current catalog name",buildJsonObject{
            put("description","Known physical source");put("handheld",true);put("wearable",false);put("attunement","none")
        })
        val drafts=java.util.concurrent.CopyOnWriteArrayList<Pair<String,JsonObject>>()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val detailReads = java.util.concurrent.atomic.AtomicInteger()
        val client=CloudCampaignClient(cache,{CloudSession(credential,null)},OwnerConnectionTransport{_,path,_,_,body->
            assertNull("Command editors must open without submitting.",body)
            val data=when{
                path.endsWith("/capabilities")->buildJsonObject{}
                path.contains("/state?")->buildJsonObject{put("records",JsonArray(listOf(holding,fact)));put("nextAfter",JsonNull)}
                path.contains("/records/inventory?") && path.contains("id=bearer")->buildJsonObject{
                    put("view","inventory");put("actorId","bearer");put("items",JsonArray(listOf(JsonObject(holding+("title" to JsonPrimitive("Current catalog name"))))));put("nextAfter",JsonNull)
                }
                path.contains("/records/inventory?") && path.contains("id=held-one")->buildJsonObject{
                    val first = detailReads.incrementAndGet() == 1
                    if (first) {
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }
                    put("view","inventory");put("holding",holding);put("nextAfter",JsonNull)
                    put("definition", if (first) JsonObject(definition + ("id" to JsonPrimitive("unrelated-definition"))) else definition)
                }
                path.contains("/records/timeline?")->buildJsonObject{put("view","timeline");put("events",JsonArray(emptyList()));put("nextAfter",JsonNull)}
                else->error("Unexpected synthetic route")
            }
            OwnerSyncResponse(200,JsonObject(header+data))
        },{now},mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
        val snapshot=runBlocking {client.refresh(true)}
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val tab=mutableStateOf("Equipment")
        val online=mutableStateOf(true)
        try {
            activity.get().setContent{AstravarTheme{ Column(Modifier.verticalScroll(rememberScrollState())){
                CloudRecordScreen(tab.value,"bearer",client,snapshot,online.value,{op,a->drafts+=op to a},{error("Unexpected navigation")})
            }}}
            awaitText("Current catalog name")
            compose.onNodeWithText("Current catalog name").performClick()
            awaitText("Customize this instance")
            compose.waitForIdle()
            compose.waitUntil(5000) { readStarted.isCompleted }
            val customize = compose.onNode(hasText("Customize this instance") and hasClickAction())
            customize.performScrollTo().assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithText("Loading item definition…").assertExists()
            assertTrue(drafts.isEmpty())
            compose.onNodeWithText("Earlier catalog name").assertDoesNotExist()
            releaseRead.complete(Unit)
            awaitText("Item definition unavailable.")
            compose.onNodeWithText("Recorded holding · Earlier catalog name").assertExists()
            customize.assertIsNotEnabled()
            compose.onNodeWithText("Known physical source").assertDoesNotExist()
            compose.onNodeWithText("Retry item definition").performScrollTo().performClick()
            awaitText("Known physical source")
            customize.performScrollTo().assertIsEnabled().performClick()
            assertEquals("customize_item", drafts.single().first)
            assertEquals("held-one", drafts.single().second.cloudText("itemId"))
            assertEquals("Current catalog name", drafts.single().second.cloudText("name"))
            assertEquals("Known physical source", drafts.single().second.cloudText("description"))
            assertEquals(2, detailReads.get())
            compose.onNodeWithText("Manage in Inventory").assertDoesNotExist()
            compose.onNodeWithText("Hold").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5000){drafts.size==2}
            assertEquals("equip_item",drafts.last().first)
            assertEquals("held-one",drafts.last().second.cloudText("itemId"))
            compose.runOnIdle { online.value = false }
            awaitText("Recorded holding · Earlier catalog name")
            customize.assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithText("Known physical source").assertDoesNotExist()
            compose.onNodeWithText("Retry item definition").assertDoesNotExist()
            assertEquals(2, detailReads.get())
            assertEquals(2, drafts.size)
            compose.onNodeWithText("Close").performClick()
            compose.runOnIdle{tab.value="Chronicle"; online.value=true}
            compose.onNodeWithText("Lore").performScrollTo().performClick()
            awaitText("Recorded inscription")
            compose.onNodeWithText("Recorded inscription").performClick()
            awaitText("An observed sequence of marks")
            compose.onNodeWithText("Revise known lore").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5000){drafts.size==3}
            assertEquals("revise_known_fact",drafts.last().first)
            assertEquals("known-one",drafts.last().second.cloudText("factId"))
            assertEquals(2,drafts.last().second.getValue("expectedRevision").jsonPrimitive.int)
            assertEquals("Earlier catalog name",runBlocking{client.record("held-one")}?.cloudText("title"))
            assertEquals(3,drafts.size)
        } finally {releaseRead.complete(Unit);activity.pause().stop().destroy();cache.close()}
    }

    private fun awaitText(value:String) {
        compose.waitUntil(10000) {compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
    }
}
