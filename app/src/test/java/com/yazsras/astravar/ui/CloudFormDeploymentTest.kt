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
                path.endsWith("/forms/subject/frame")->JsonObject(common+("preview" to preview(54)))
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

    @Test fun collectionControlsPreservePhysicalReferencesAndSelectOnlyTheSpecificFormForRetirement() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val online = mutableStateOf(true)
        val active = mutableStateOf<String?>("third")
        fun form(id: String) = buildJsonObject {
            put("id",id); put("title",id); put("actorId","actor"); put("revision",4)
            putJsonObject("data") { put("source","Fixture");put("sourceItemId","one-rig");put("integrityPoolId","one-integrity")
                put("timing","other");put("activationSeconds",60);put("orderKey","rank-$id");put("configurationRevision",3) }
        }
        val forms = listOf(form("first"),form("second"),form("third"))
        val opened = mutableListOf<Pair<String,JsonObject>>()
        val commits = mutableListOf<Pair<String,JsonObject>>()
        try {
            activity.get().setContent { AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudFormManagementControls(forms[1],forms,online.value,active.value,null,
                    {op,args->opened.add(op to args)},{op,args->commits.add(op to args)})
            } } }
            compose.onNodeWithText("Manage configuration").performClick()
            compose.onNodeWithText("Duplicate configuration").performClick()
            val duplicate=opened.single()
            assertEquals("duplicate_form",duplicate.first)
            assertEquals("actor",duplicate.second.cloudText("actorId"))
            assertEquals("second",duplicate.second.cloudText("sourceFormId"))
            assertNotEquals("second",duplicate.second.cloudText("formId"))
            assertTrue(commits.isEmpty())
            compose.onNodeWithText("Move down").performClick();compose.onNodeWithText("Move up").performClick()
            assertEquals(listOf("third","first"),commits.map { (op,args)->assertEquals("move_form",op);args.cloudText("adjacentFormId") })
            compose.onNodeWithText("Edit form").performClick()
            val edit=opened.last().second
            assertEquals("one-rig",edit.cloudText("sourceItemId"));assertEquals("one-integrity",edit.cloudText("integrityPoolId"))
            assertFalse("orderKey" in edit);assertFalse("configurationRevision" in edit)
            compose.onNodeWithText("Review retirement").performClick()
            val (op,retirement)=opened.last()
            assertEquals("archive_records",op)
            val exact=retirement.getValue("records").jsonArray.single().jsonObject
            assertEquals("second",exact.cloudText("id"));assertEquals(4,exact.getValue("revision").jsonPrimitive.int)
            compose.runOnIdle {active.value="second"}
            compose.onNodeWithText("Review retirement").assertIsNotEnabled()
            compose.runOnIdle {online.value=false}
            compose.onNodeWithText("Duplicate configuration").assertIsNotEnabled()
            compose.onNodeWithText("Move up").assertIsNotEnabled()
        } finally {activity.pause().stop().destroy()}
    }

}
