package com.yazsras.astravar.ui

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudConflictReviewInteropTest {
    @get:Rule val compose=createAstravarComposeRule()

    @Test fun aPausedDeviceKeepsItsOldCacheUntilManuallyRefreshedAndShowsVerifiedRevocation()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val controls=CampaignSyncPreferences(app)
        val original=controls.paused
        AscensionWorkerFixture.open(app).use { f ->
            val actor="status-actor"
            f.command("define_actor",buildJsonObject {
                put("actorId",actor);put("name","Fixture status");put("role","player")
                put("level",1);put("rulesProfile","legacy_2014");putJsonObject("abilities"){put("INT",12)}
            })
            val seed=f.refresh()
            controls.paused=true
            val other=f.command("set_actor_ability",buildJsonObject {put("actorId",actor);put("ability","INT");put("score",14)})
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            fun see(label:String) {
                compose.waitUntil(15000){compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()}
            }
            try{
                activity.get().setContent {
                    val generation by f.client.changes.collectAsState()
                    var snapshot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(seed) }
                    androidx.compose.runtime.LaunchedEffect(generation){ f.client.workspace()?.let {snapshot=it} }
                    AstravarTheme { CloudWorkspace(f.client,snapshot) }
                }
                compose.waitUntil(20000){compose.onAllNodesWithText("Tools").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Tools").performClick()
                see("Automatic device refresh paused");see("Cached revision 1 · may be stale")
                assertEquals(1,f.client.workspace()!!.version.revision)
                compose.onNodeWithText("Check connection now").performScrollTo().performClick()
                see("Device access verified");see("Cache verified at revision 2")
                assertEquals(other,f.receipt(other.cloudText("commandId")))
                f.signal("revoke")
                compose.onNodeWithText("Check connection now").performScrollTo().performClick()
                see("Device grant revoked");see("Cached campaign data unavailable")
                assertNull(f.client.record(actor))
                assertEquals(1,f.dm("get_cloud_state",buildJsonObject {put("id",actor)}).getValue("records").jsonArray.size)
            }finally{activity.pause().stop().destroy();controls.paused=original}
        }
    }

    @Test fun staleOwnerNotificationAndHistoricalReceiptOpenTheExactConflictForIndependentReview()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        AscensionWorkerFixture.open(app).use { f ->
            val actor="observer"
            fun args(score:Int)=buildJsonObject { put("actorId",actor);put("ability","INT");put("score",score) }
            val created=f.command("define_actor",buildJsonObject {
                put("actorId",actor);put("name","Fixture observer");put("role","player")
                put("rulesProfile","legacy_2014");put("level",3);putJsonObject("abilities"){put("INT",12)}
            })
            assertEquals("COMMITTED",created.cloudText("status"))
            f.signal("requireReview")
            val prior=f.command("set_actor_ability",args(17),"review-actor-pending")
            val id=prior.cloudText("commandId");val digest=prior.cloudText("digest")
            val before=f.refresh()
            val edit=f.client.submit("set_actor_ability",args(16),before,"device-intervening-edit")
            assertEquals("COMMITTED",edit.cloudText("status"))
            val current=f.refresh()
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            fun waitFor(text:String) {
                compose.waitUntil(20000){ compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            }
            fun assertOwnerLink(intent:String) {
                compose.waitUntil(5000) { shadowOf(app).peekNextStartedActivity()!=null }
                val next=shadowOf(app).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW,next?.action)
                val expected=exactOwnerReviewPage(current.binding,id,digest,intent)
                assertEquals(expected.toString(),next?.dataString)
            }
            try {
                activity.get().setContent { AstravarTheme { CloudApprovalCenter(f.client,current,true,{}, {}) } }
                waitFor("0 ready for approval · 1 require reconciliation")
                compose.onNodeWithText("Set actor ability").performClick()
                waitFor("Resolve request status")
                compose.onNodeWithText("Verify").assertIsNotEnabled()
                compose.onNodeWithText("Current · 16").assertExists()
                compose.onNodeWithText("Proposed · 17").assertExists()
                compose.onNodeWithText("Resolve request status").performScrollTo().performClick()
                assertOwnerLink("verify")
                assertEquals("PENDING_APPROVAL",f.receipt(id).cloudText("status"))
                val decided=f.signal("ownerVerify",buildJsonObject {put("commandId",id);put("digest",digest)})
                assertEquals("CONFLICT",decided.cloudText("status"))
                activity.pause().resume()
                waitFor("Review a linked replacement")
                compose.onNodeWithText("Review a linked replacement").performScrollTo().performClick()
                assertOwnerLink("replace")
                activity.get().setContent { AstravarTheme { CloudHistoricalReceipt(f.client,current,id) {} } }
                waitFor("Review a linked replacement")
                compose.onNodeWithText("Review a linked replacement").performScrollTo().performClick()
                assertOwnerLink("replace")
                assertEquals(decided,f.receipt(id))
                assertEquals(16,f.record(actor)!!.getValue("data").jsonObject.getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
                assertEquals(current.version.revision,f.refresh().version.revision)
            } finally {activity.pause().stop().destroy()}
        }
    }
}
