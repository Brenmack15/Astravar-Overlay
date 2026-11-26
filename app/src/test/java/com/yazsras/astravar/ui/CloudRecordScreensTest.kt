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

    private fun awaitText(value:String) {
        compose.waitUntil(10000) {compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
    }
}
