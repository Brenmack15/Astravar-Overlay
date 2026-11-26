package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.AscensionWorkerFixture
import com.yazsras.astravar.data.cloudText
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
@Config(sdk = [35], qualifiers = "w470dp-h1050dp", application = AstravarApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudInventionGenealogyInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            compose.waitUntil(12000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Missing $matcher\n" + compose.onAllNodes(isRoot()).printToString(maxDepth=7), e)
        }
        return compose.onAllNodes(matcher).onLast()
    }
    private fun press(text: String) {
        val action = node(hasText(text) and hasClickAction())
        if (action.fetchSemanticsNode().parent != null) runCatching { action.performScrollTo() }
        action.assertIsEnabled().performClick()
    }
    private fun field(label: String, value: String) {
        node(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    }
    private fun links(id: String) {
        node(hasText("Linked records") and hasAnyAncestor(hasTestTag("record-links-$id"))).performScrollTo().performClick()
    }
    private fun follow(id: String, title: String, relation: String) {
        node(hasAnyAncestor(hasTestTag("record-links-$id")) and hasText(title) and hasText(relation)).performScrollTo().performClick()
    }
    private fun confirms(value: String) { node(hasText(value, substring = true)).assertExists() }

    @Test
    fun projectBlueprintInstallationTwoSuccessorsResearchAndDiscoveredLoreStayTraversableFromInventory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AscensionWorkerFixture.open(context).use { f ->
            val source = "Synthetic workshop record"
            fun command(op: String, params: String): JsonObject {
                val reply = f.command(op, json(params))
                assertEquals(reply.toString(), "COMMITTED", reply.cloudText("status"))
                assertEquals(reply, f.receipt(reply.cloudText("commandId")))
                return reply
            }
            fun data(id: String) = requireNotNull(f.record(id)).getValue("data").jsonObject
            fun references(id: String) = f.dm("get_cloud_record_links", json("""{"id":"$id"}""")).getValue("links").jsonArray
            fun actor() = command("define_actor","""{"actorId":"owner","name":"Fixture inventor","role":"player","rulesProfile":"legacy_2014","level":4,"abilities":{"INT":14,"DEX":12}}""")
            fun item(id: String,title: String) {
                command("define_item_definition","""{"definitionId":"def-$id","name":"$title","category":"gear","description":"Reviewed fixture","rarity":null,"stackable":false,"wearable":false,"handheld":true,"attunement":"none","source":"$source"}""")
                command("acquire_item","""{"itemId":"$id","definitionId":"def-$id","ownerId":"owner","quantity":1,"source":"$source"}""")
            }
            fun proposed(id: String,title: String) = command("propose_invention","""{"projectId":"$id","title":"$title","actorId":"owner","intent":"Complete the optical fixture","requirements":[],"researchIds":["finding"],"source":"$source"}""")
            fun stage(project: String, id: String, drawing: String?) {
                val blueprint = drawing?.let { "\"$it\"" } ?: "null"
                command("define_construction_stage","""{"stageId":"$id","projectId":"$project","blueprintId":$blueprint,"title":"Stage $id","prerequisites":[],"requiredWork":"Inspect the fit","checkRequired":false,"source":"$source"}""")
                command("resolve_construction_stage","""{"stageId":"$id","outcome":"completed","rollId":null,"worldTimeSpent":"One confirmed interval","evidence":"Confirmed synthetic inspection","source":"$source"}""")
            }
            fun finish(project: String, id: String, donor: String, output: String, method: String) {
                val args = json("""{"eventId":"integration-$id","projectId":"$project","stageId":"$id","donorItemId":"$donor","outputItemId":"$output","method":"$method","quantity":1,"evidence":"Observed fixture fitting","source":"$source"}""")
                val original = runBlocking {
                    f.client.refresh()
                    f.client.submit("integrate_component", args, requireNotNull(f.client.workspace()))
                }
                assertEquals("COMMITTED",original.cloudText("status"))
                assertEquals(original,f.receipt(original.cloudText("commandId")))
                assertEquals(false,data(donor).getValue("partyAccess").jsonPrimitive.boolean)
                command("link_completed_invention","""{"projectId":"$project","outputItemId":"$output","stageIds":["$id"],"source":"$source"}""")
            }
            fun mount() = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { activity ->
                val initial = runBlocking { requireNotNull(f.client.workspace()) }
                activity.get().setContent {
                    val sequence by f.client.changes.collectAsState()
                    var selected by remember { mutableStateOf(initial) }
                    LaunchedEffect(sequence) { f.client.workspace()?.let { selected = it } }
                    AstravarTheme { CloudWorkspace(f.client, selected) }
                }
            }
            fun projects() { press("Inventory");press("Projects") }
            actor()
            item("lens","Calibrated lens");item("carrier","Optical carrier");item("viewer","Completed viewer")
            command("record_check","""{"actorId":"owner","rollId":"measurement","check":{"kind":"ability","key":"INT","mode":"normal"},"evidence":{"origin":"player_physical","faces":[14]}}""")
            f.refresh()
            val researchActivity = mount()
            try {
                press("Inventory");press("Research");press("Record finding")
                field("Research Id","finding");field("Title","Measurement notebook")
                field("Actor Id","owner");field("Finding","The sample focal mark was measured.")
                field("Item Id","lens");field("Roll Id","measurement")
                press("Select");press("Confirmed");press("Submit")
                compose.waitUntil(15000) { f.record("finding") != null }
                assertTrue(references("finding").any { it.jsonObject.cloudText("relation")=="research_roll" &&
                    it.jsonObject.cloudText("to_id")=="measurement" })
            } finally { researchActivity.pause().stop().destroy() }
            command("record_known_fact","""{"factId":"known-mark","subject":{"kind":"item_instance","id":"lens"},"title":"Known maker mark","detail":"The observed mark is an open circle.","evidence":"confirmed","learnedAt":null,"originalWorldTime":null,"source":"$source"}""")
            proposed("first","Carrier project")
            command("record_blueprint_version","""{"blueprintId":"drawing-original","projectId":"first","title":"Early drawing","specification":"The original gauge measurement","supersedesId":null,"source":"$source"}""")
            f.refresh()
            val firstActivity = mount()
            var revisionId: String
            try {
                projects();press("Carrier project");press("Record blueprint")
                field("Title","Revised carrier drawing")
                field("Specification","Fit the verified synthetic gauge")
                field("Supersedes Id","drawing-original")
                press("Submit")
                compose.waitUntil(15000) {
                    f.dm("get_cloud_state",json("""{"kind":"blueprint_version"}""")).getValue("records").jsonArray.size == 2
                }
                val row = f.dm("get_cloud_state",json("""{"kind":"blueprint_version"}""")).getValue("records").jsonArray
                    .map { it.jsonObject }.single { it.cloudText("title") == "Revised carrier drawing" }
                revisionId = row.cloudText("id")
                assertTrue(references(revisionId).any { it.jsonObject.cloudText("relation") == "supersedes_version" && it.jsonObject.cloudText("to_id") == "drawing-original" })
            } finally { firstActivity.pause().stop().destroy() }

            f.refresh()
            val stageActivity = mount()
            try {
                projects();press("Carrier project");press("Add construction stage")
                field("Stage Id","mount");field("Blueprint Id",revisionId);field("Title","Assembly fitting")
                field("Required Work","Inspect the actual fit");press("Use empty list");press("Select");press("False");press("Submit")
                compose.waitUntil(15000) { f.record("mount") != null }
                assertTrue(references("mount").any { it.jsonObject.cloudText("to_id")==revisionId })
            } finally { stageActivity.pause().stop().destroy() }
            command("resolve_construction_stage","""{"stageId":"mount","outcome":"completed","rollId":null,"worldTimeSpent":"One confirmed interval","evidence":"Confirmed inspection","source":"$source"}""")
            f.refresh()
            val integrationActivity=mount()
            try {
                projects();press("Carrier project");press("Component rules")
                confirms("Record a proposed extraction or detachment")
                val before = f.refresh().version.revision
                val proposed = json("""{"eventId":"unapproved","projectId":"first","stageId":"mount","donorItemId":"lens","outputItemId":"carrier","method":"enchantment_extraction","quantity":1,"evidence":"Proposed only","source":"$source"}""")
                val refusal = runCatching { f.command("integrate_component",proposed) }
                assertTrue(refusal.exceptionOrNull()?.message.orEmpty().contains("INVALID_COMMAND") ||
                    refusal.getOrNull()?.toString().orEmpty().contains("INVALID_COMMAND"))
                assertNull(f.record("unapproved"))
                assertTrue(data("lens").getValue("partyAccess").jsonPrimitive.boolean)
                assertEquals(before,f.refresh().version.revision)
                press("Record component integration")
                field("Event Id","integration-mount");field("Stage Id","mount")
                field("Donor Item Id","lens");field("Output Item Id","carrier")
                field("Quantity","1");field("Evidence","Observed synthetic installation")
                press("Select")
                compose.onAllNodesWithText("Enchantment extraction").assertCountEquals(0)
                press("Installed");press("Submit")
                compose.waitUntil(15000) { f.record("integration-mount") != null }
                val event=data("integration-mount")
                assertEquals("installed",event.cloudText("method"))
                assertEquals("COMMITTED",f.receipt(event.cloudText("commandId")).cloudText("status"))
                assertEquals(false,data("lens").getValue("partyAccess").jsonPrimitive.boolean)
            } finally { integrationActivity.pause().stop().destroy() }
            command("link_completed_invention","""{"projectId":"first","outputItemId":"carrier","stageIds":["mount"],"source":"$source"}""")
            proposed("second","Viewer project")
            stage("second","assemble",null)
            finish("second","assemble","carrier","viewer","incorporated")
            val independent = listOf("lens","carrier","viewer","drawing-original",revisionId,"finding","measurement","known-mark","integration-mount","integration-assemble")
                .associateWith { id -> requireNotNull(f.record(id)) }
            val currentRevision = f.refresh().version.revision
            f.resetCache(); val restored = f.refresh()
            assertEquals(currentRevision,restored.version.revision)
            independent.forEach { (id, row) -> assertEquals(row, f.client.record(id)) }
            val secondActivity = mount()
            try {
                projects();press("Viewer project");press("Open resulting item")
                links("viewer");follow("viewer","Incorporated","Integration history")
                links("integration-assemble");follow("integration-assemble","Optical carrier","Source component")
                links("carrier");follow("carrier","Installed","Integration history")
                links("integration-mount");follow("integration-mount","Calibrated lens","Source component")
                links("lens");follow("lens","Known maker mark","Discovered knowledge")
                confirms("The observed mark is an open circle.")
                press("Back to previous record")
                links("lens");follow("lens","Measurement notebook","Research about this item")
                confirms("The sample focal mark was measured.")
                links("finding");follow("finding","Carrier project","Project using this research")
                links("first");follow("first","Revised carrier drawing","Blueprint")
                links(revisionId);follow(revisionId,"Early drawing","Previous blueprint")
                confirms("The original gauge measurement")
                assertEquals(currentRevision, f.refresh().version.revision)
                assertEquals(listOf(14),data("measurement").getValue("faces").jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.int })
                assertEquals("installed",data("lens").cloudText("holding"))
                assertEquals("incorporated",data("carrier").cloudText("holding"))
            } finally { secondActivity.pause().stop().destroy() }
        }
    }
}
