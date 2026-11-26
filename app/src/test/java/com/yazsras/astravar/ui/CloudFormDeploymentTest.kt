package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
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
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudFormDeploymentTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun preview(elapsed: Int? = null) = buildJsonObject {
        put("activationSeconds", 60); put("sourceEligible", true); put("requiresWilling", true)
        put("activeFormId", JsonNull); put("trackedEncounter", true)
        if (elapsed != null) putJsonObject("deployment") {
            put("deploymentId", "durable-id"); put("formId", "frame"); put("formTitle", "Synthetic frame")
            put("elapsedSeconds", elapsed); put("ready", elapsed >= 60); put("basis", "encounter_rounds"); put("round", 1 + elapsed / 6)
        }
    }

    @Test fun anActualDeclarationStartsWorkAndOnlyServerRecordedElapsedTimeEnablesCompletion() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val state = mutableStateOf(preview())
        val online = mutableStateOf(true)
        val sent = mutableListOf<Pair<String,JsonObject>>()
        try {
            activity.get().setContent { AstravarTheme { Column {
                CloudFormDeploymentControls("subject","frame",state.value,online.value,{_,_->fail("Encounter timing must use tracked evidence")}) {
                    op, body -> sent.add(op to body)
                }
            } } }
            compose.onNodeWithText("Begin deployment").assertIsNotEnabled()
            compose.onNodeWithText("This actor is willing to transform").performClick()
            compose.onNodeWithText("Begin deployment").performClick()
            assertEquals("begin_form_deployment", sent.single().first)
            assertEquals("subject", sent.single().second.getValue("actorId").jsonPrimitive.content)
            assertTrue(sent.single().second.getValue("deploymentId").jsonPrimitive.content.isNotBlank())
            compose.runOnIdle { state.value = preview(54) }
            compose.onNodeWithText("54 of 60 seconds recorded").assertExists()
            compose.onNodeWithText("Confirm uninterrupted deployment").assertIsNotEnabled()
            compose.runOnIdle { state.value = preview(60) }
            compose.onNodeWithText("This actor is willing to transform").performClick()
            compose.runOnIdle { online.value = false }
            compose.onNodeWithText("Confirm uninterrupted deployment").assertIsNotEnabled()
            compose.runOnIdle { online.value = true }
            compose.onNodeWithText("Confirm uninterrupted deployment").performClick()
            val (op, done) = sent.last()
            assertEquals("change_form",op); assertEquals("durable-id",done.getValue("deploymentId").jsonPrimitive.content)
            assertEquals(0,done.getValue("elapsedSeconds").jsonPrimitive.int)
            assertTrue(done.getValue("uninterrupted").jsonPrimitive.boolean)
            assertEquals(2,sent.size)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun recordingAnInterruptionRequiresAnExplicitReasonAndKeepsTheOriginalIdentity() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val sent = mutableListOf<Pair<String,JsonObject>>()
        try {
            activity.get().setContent { AstravarTheme { Column {
                CloudFormDeploymentControls("subject","frame",preview(24),true,{_,_->}) { op, body -> sent.add(op to body) }
            } } }
            compose.onNodeWithText("Record interruption").performClick()
            compose.onNodeWithText("Save interruption").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput("Mechanism released")
            compose.onNodeWithText("Save interruption").performClick()
            val (op,args) = sent.single()
            assertEquals("interrupt_form_deployment",op); assertEquals("interrupted",args.getValue("status").jsonPrimitive.content)
            assertEquals("Mechanism released",args.getValue("reason").jsonPrimitive.content)
            assertEquals("durable-id",args.getValue("deploymentId").jsonPrimitive.content)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun normalFormsRouteReopensTheDurableDeploymentWithoutIssuingACommand() {
        val now = System.currentTimeMillis()
        val credential = OwnerSyncCredential("native-form","isolated-ui","https://owner.example.test",now+3_600_000,"x".repeat(44))
        val common = buildJsonObject {
            put("authority","cloud");put("continuityId","isolated-ui");put("subject","device:native-form")
            put("epoch",1);put("revision",23);put("grantRevision",1);put("grantExpiresAt",now+3_600_000)
        }
        fun row(id:String, kind:String, title:String, data:JsonObject) = buildJsonObject {
            put("id",id);put("actorId","subject");put("kind",kind);put("life","active");put("title",title);put("revision",2);put("data",data)
        }
        val rows = listOf(
            row("durable-id","form_deployment","Synthetic frame",buildJsonObject {put("formId","frame");put("status","deploying")}),
            row("frame","form","Synthetic frame",buildJsonObject {put("activationSeconds",60)}),
            row("subject","actor","Fixture actor",buildJsonObject {put("activeDeploymentId","durable-id")})
        )
        val cache = CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(),null)
        val routes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = CloudCampaignClient(cache,{CloudSession(credential,null)},OwnerConnectionTransport { _,path,_,_,body ->
            assertNull(body);routes+=path
            val response=when {
                path.endsWith("/capabilities")->common
                path.contains("/state?")->JsonObject(common+buildJsonObject {put("nextAfter",JsonNull);put("records",JsonArray(rows))})
                path.endsWith("/forms/subject/frame")->JsonObject(common+preview(54))
                else->error("Unexpected synthetic request $path")
            }
            OwnerSyncResponse(200,response)
        },{now},mediaTransport={_,_,_,_,_->throw OwnerSyncFailure("MEDIA_HTTP_404")})
        val snapshot=runBlocking {client.refresh(true)}
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudActorRecords("Forms",client,snapshot,"subject",true,{_,_->fail("Unexpected mutation editor")},{fail("Unexpected inspector")},{_,_->fail("Unexpected command")})
            } } }
            compose.waitUntil(10000){compose.onAllNodesWithText("Deployment in progress").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Deployment in progress").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("54 of 60 seconds recorded").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Confirm uninterrupted deployment").assertIsNotEnabled()
            assertTrue(routes.any{it.endsWith("/forms/subject/frame")})
        } finally { activity.pause().stop().destroy();cache.close() }
    }

}
