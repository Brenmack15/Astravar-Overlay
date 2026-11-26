package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudGameplayWorkflowsInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
    private fun fixture() = AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>())
    private fun amount(cp: Int = 0, sp: Int = 0, gp: Int = 0) =
        json("""{"cp":$cp,"sp":$sp,"ep":0,"gp":$gp,"pp":0}""")
    private fun AscensionWorkerFixture.seed(op: String, raw: String) {
        val r = command(op, json(raw))
        assertEquals("$op $r", "COMMITTED", r["status"]?.jsonPrimitive?.content)
    }
    private fun AscensionWorkerFixture.actor(id: String, label: String, role: String = "player") =
        seed("define_actor","""{"actorId":"$id","name":"$label","role":"$role","rulesProfile":"revised_2024","level":5,"abilities":{"STR":10,"DEX":10,"CON":12,"INT":14,"WIS":10,"CHA":10},"maxHp":40,"currentHp":40,"temporaryHp":0,"baseAc":13,"gameSeconds":0,"source":"Synthetic encounter"}""")
    private fun AscensionWorkerFixture.item(id: String, label: String, who: String, qty: Int) {
        seed("define_item_definition","""{"definitionId":"def-$id","name":"$label","category":"gear","description":"Fixture stock","rarity":null,"stackable":${qty>1},"wearable":false,"handheld":false,"attunement":"none","source":"Fixture catalog"}""")
        seed("acquire_item","""{"itemId":"$id","definitionId":"def-$id","ownerId":"$who","quantity":$qty,"source":"Fixture acquisition"}""")
    }
    private fun AscensionWorkerFixture.wallet(id: String, label: String, who: String, coins: JsonObject) =
        seed("define_wallet","""{"walletId":"$id","title":"$label","holderId":"$who","shared":false,"coins":$coins,"source":"Fixture count"}""")
    private fun AscensionWorkerFixture.data(id: String) = requireNotNull(record(id)) { id }.obj("data")
    private fun AscensionWorkerFixture.links(id: String) = dm("get_cloud_record_links",json("""{"id":"$id","limit":100}""")).rows("links")
    private fun AscensionWorkerFixture.link(id: String, relation: String, other: String) {
        assertTrue("Missing $id $relation $other",links(id).any { it.value("from_id")==id && it.value("relation")==relation && it.value("to_id")==other })
    }
    private fun AscensionWorkerFixture.native(op: String, raw: String): JsonObject = runBlocking {
        val result = client.submit(op,json(raw),requireNotNull(client.workspace()))
        assertEquals("$op $result","COMMITTED",result.value("status"))
        assertEquals(result,receipt(result.value("commandId")))
        result
    }
    private fun AscensionWorkerFixture.nativeData(id: String) = runBlocking { requireNotNull(client.record(id)).obj("data") }
    private fun AscensionWorkerFixture.view(view: String, id: String) =
        dm("get_cloud_record_view",json("""{"view":"$view","id":"$id"}""")).rows("events")

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(10000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst()
    }
    private fun press(label: String) = node(hasText(label) and hasClickAction()).performClick()
    private fun side(index: Int, label: String, field: Boolean = false): SemanticsNodeInteraction {
        val action = if (field) hasSetTextAction() else hasClickAction()
        return node(hasAnyAncestor(hasTestTag("trade-offer-$index")) and hasText(label) and action)
    }
    private fun AscensionWorkerFixture.mount(
        saved: MutableList<JsonObject>, inspected: MutableList<String> = mutableListOf(),
        content: @Composable (CloudSnapshot,(String,JsonObject)->Unit,(String)->Unit)->Unit
    ): ActivityController<ComponentActivity> {
        val worker = this
        val initial = runBlocking { refresh() }
        val operations = runBlocking { client.capabilities() }.rows("operations")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            var snapshot by remember { mutableStateOf(initial) }
            var editor by remember { mutableStateOf<Pair<String,JsonObject>?>(null) }
            AstravarTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    content(snapshot,{ op,args -> editor=op to args },{inspected.add(it)})
                }
                editor?.let { (op,args) ->
                    CloudCommandForm(operations.single { it.value("name")==op },false,{editor=null},{ reviewed,id ->
                        val result=runBlocking { worker.client.submit(op,reviewed,snapshot,id) }
                        saved.add(result)
                        snapshot=runBlocking { requireNotNull(worker.client.workspace()) }
                        editor=null
                    },null,args)
                }
            }
        }
        return activity
    }

    @Test fun reviewedNativeTwoSidedTradeCommitsEveryLegOnceAndBothPartiesSeeTheSameRecord() = fixture().use { f ->
        f.actor("maker","Workshop maker"); f.actor("courier","Workshop courier","companion")
        f.item("wire","Measured wire","maker",8); f.item("tool","Hand gauge","courier",1)
        f.wallet("maker-purse","Maker purse","maker",amount(gp=10))
        f.wallet("courier-purse","Courier purse","courier",amount(cp=7))
        f.signal("deviceTradeReview")
        val saved = mutableListOf<JsonObject>()
        val activity = f.mount(saved) { snapshot,open,inspect ->
            CloudRecordScreen("Inventory",null,f.client,snapshot,true,open,inspect)
        }
        try {
            press("Wallets"); press("Trade between party members")
            side(0,"Workshop maker").performClick(); side(1,"Workshop courier").performClick()
            for ((index,label,quantity) in listOf(Triple(0,"Measured wire","3"),Triple(1,"Hand gauge","1"))) {
                side(index,"Offer a holding").performClick(); side(index,label).performClick()
                side(index,"Quantity offered",true).performTextInput(quantity); side(index,"Add to offer").performClick()
                side(index,"Wallet and offered coins").performClick()
                side(index,if(index==0)"Maker purse" else "Courier purse").performClick()
                side(index,if(index==0)"GP offered · 10 available" else "CP offered · 7 available",true).performTextInput("2")
            }
            node(hasText("Reason") and hasSetTextAction()).performTextInput("Exchange of inspected supplies")
            press("Review exchange"); node(hasText("Exchange party assets")).assertExists()
            assertTrue(saved.isEmpty()); assertEquals("8",f.data("wire").value("quantity"))
            press("Submit")
            val proposal=saved.single()
            assertEquals("PENDING_APPROVAL",proposal.value("status"))
            val id=proposal.value("commandId")
            assertEquals(proposal,f.receipt(id))
            assertEquals(amount(gp=10),f.data("maker-purse").obj("coins")); assertEquals(amount(cp=7),f.data("courier-purse").obj("coins"))
            assertEquals("courier",f.data("tool").value("ownerId")); assertEquals("8",f.data("wire").value("quantity"))
            val decision=f.signal("ownerVerify", buildJsonObject { put("commandId",id);put("digest",proposal.getValue("digest")) })
            val verified=runBlocking { f.client.verify(id) }
            assertEquals("COMMITTED",verified.value("status"))
            assertEquals(decision.obj("receipt"), verified.obj("receipt"));assertEquals(verified,f.receipt(id))
            val outcome=verified.obj("receipt").obj("outcome")
            val trade=outcome.value("tradeId")
            assertEquals(2,outcome.rows("holdings").size)
            val inventory=f.dm("get_cloud_record_view",json("""{"view":"inventory","id":"def-wire"}""")).rows("items")
            val split=inventory.single { it.value("custodianId")=="courier" }.value("id")
            assertEquals("3",f.data(split).value("quantity")); assertEquals("5",f.data("wire").value("quantity"))
            assertEquals("maker",f.data("tool").value("ownerId"))
            assertEquals(amount(cp=2,gp=8),f.data("maker-purse").obj("coins"))
            assertEquals(amount(cp=5,gp=2),f.data("courier-purse").obj("coins"))
            for (entity in listOf("maker","courier","wire",split,"tool","maker-purse","courier-purse")) {
                assertEquals(listOf(trade),f.view("timeline",entity).filter { it.obj("data")["commandId"]?.jsonPrimitive?.content==id }.map { it.value("id") })
            }
            assertEquals("maker",f.view("item_history","tool").first().obj("data").obj("after").value("ownerId"))
            assertEquals(verified,runBlocking { f.client.retry(id) })
            f.resetCache();runBlocking { f.refresh() }
            for(entity in listOf("maker-purse","courier-purse","wire",split,"tool",trade))assertEquals(f.data(entity),f.nativeData(entity))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun aRealTargetInterruptsTheCurrentCastAndTheNativePhysicalSaveResolvesAgainstItsRecordedDc() = fixture().use { f ->
        f.actor("caster","Test caster");f.actor("opponent","Visible opponent","npc")
        f.seed("define_resource","""{"actorId":"caster","poolId":"slot","name":"First-level slots","type":"spell_slot","maximum":2,"current":2,"slotLevel":1,"recovery":"long_rest","source":"Fixture rules"}""")
        f.seed("define_spell","""{"actorId":"caster","spellId":"binding","name":"Beacon binding","baseLevel":1,"concentration":true,"ritual":false,"resolution":"automatic","timing":"action","components":[],"source":"Fixture rule"}""")
        f.seed("grant_spell","""{"actorId":"caster","spellId":"binding","grantId":"grant","route":"slot","levels":[1],"preparation":"prepared","ability":"INT","source":"Fixture grant"}""")
        f.seed("define_attack","""{"actorId":"opponent","attackId":"impact","name":"Measured impact","source":"Fixture rule","timing":"attack","delivery":"melee","resolution":"attack","fixedHit":5,"components":[{"id":"force","name":"Force","type":"force","dice":0,"sides":6,"flat":24}]}""")
        runBlocking { f.refresh() }
        val cast=f.native("cast_spell","""{"actorId":"caster","grantId":"grant","castId":"running-cast","level":1,"slotPoolId":"slot"}""")
        f.native("resolve_attack","""{"actorId":"opponent","attackId":"impact","attemptId":"attempt","mode":"normal","includeDamage":true,"targets":["caster"],"hit":{"origin":"dm","faces":[15]},"damage":[{"id":"force","evidence":{"origin":"dm","faces":[]}}]}""")
        assertEquals("40",f.data("caster").value("currentHp"))
        val result=f.native("resolve_attack_target","""{"actorId":"opponent","attemptId":"attempt","targetActorId":"caster","source":"Observed fixture outcome"}""")
        val checkId=result.obj("receipt").obj("outcome").obj("hp").value("concentrationCheckId")
        assertEquals("16",f.data("caster").value("currentHp"));assertEquals("12",f.data(checkId).value("dc"))
        assertEquals("running-cast",f.data("caster").value("concentrationCastId")); f.link(checkId,"for_cast","running-cast"); f.link(checkId,"triggering_attack","attempt")
        val saved=mutableListOf<JsonObject>()
        val activity=f.mount(saved) { snapshot,open,_ -> CloudSpellRest("Spells","caster",f.client,snapshot,true,open) }
        try {
            node(hasText("Constitution save · DC 12")).assertExists()
            node(hasText("Review physical save") and hasClickAction()).assertIsNotEnabled()
            assertTrue(saved.isEmpty())
            node(hasText("Physical d20 faces") and hasSetTextAction()).performTextInput("4")
            press("Review physical save");press("Submit")
            val first=saved.single()
            assertEquals(first,f.receipt(first.value("commandId")))
            val roll=first.obj("receipt").obj("outcome").value("rollId")
            f.link(roll,"for_concentration",checkId)
            assertEquals("running-cast",f.data("caster").value("concentrationCastId"))
            val duplicate=f.command("record_check",json("""{"actorId":"caster","rollId":"second-save","forConcentrationId":"$checkId","check":{"kind":"save","key":"CON","mode":"normal"},"evidence":{"origin":"player_physical","faces":[20]}}"""))
            assertEquals("CONCENTRATION_SAVE_ALREADY_RECORDED",duplicate["error"]?.jsonPrimitive?.content)
            assertNull(f.record("second-save"))
            press("Resolve recorded save · 5");press("Submit")
            val resolved=saved.last()
            assertEquals(resolved,f.receipt(resolved.value("commandId")))
            assertEquals("ended",resolved.obj("receipt").obj("outcome").value("status"));f.link(checkId,"resolved_save",roll)
            assertEquals(JsonNull,f.data("caster")["concentrationCastId"])
            assertEquals("1",f.data("slot").value("current"))
            assertEquals("4",f.data(roll).rows("faces").single().value("value"))
            assertEquals(cast,f.receipt(cast.value("commandId")))
            assertEquals(resolved,runBlocking { f.client.retry(resolved.value("commandId")) })
            f.resetCache();runBlocking { f.refresh() }
            for (id in listOf("slot","caster","attempt",checkId,roll,"running-cast")) assertEquals(f.data(id),f.nativeData(id))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun nativeTwoStageInventionLinksActualResearchRollMaterialPaymentAndResultAfterCacheReplacement() = fixture().use { f ->
        f.actor("researcher","Bench researcher")
        f.item("stock","Wire segments","researcher",7);f.item("assembly","Inspection frame","researcher",1)
        f.wallet("purse","Project purse","researcher",amount(sp=4,gp=6))
        runBlocking { f.refresh() }
        val check=f.native("record_check","""{"actorId":"researcher","rollId":"observation","check":{"kind":"ability","key":"INT","mode":"normal"},"evidence":{"origin":"player_physical","faces":[14]}}""")
        f.native("record_research","""{"researchId":"finding","title":"Gauge measurements","actorId":"researcher","finding":"The fixture wire fits the measured opening.","evidence":"confirmed","itemId":"stock","rollId":"observation","source":"Synthetic bench log"}""")
        f.native("propose_invention","""{"projectId":"project","title":"Bench gauge","actorId":"researcher","intent":"Fit the fixture","requirements":["Inspect the wire and assemble"],"researchIds":["finding"],"source":"Fixture plan"}""")
        f.native("record_blueprint_version","""{"blueprintId":"drawing","projectId":"project","title":"Measured drawing","specification":"Fit the measured fixture in two stages.","supersedesId":null,"source":"Fixture plan"}""")
        f.native("define_construction_stage","""{"stageId":"measure","projectId":"project","blueprintId":"drawing","title":"Measure the wire","prerequisites":[],"requiredWork":"Measure both ends","checkRequired":true,"source":"Fixture plan"}""")
        f.native("define_construction_stage","""{"stageId":"fit","projectId":"project","blueprintId":"drawing","title":"Fit the frame","prerequisites":["measure"],"requiredWork":"Fit two wire segments","checkRequired":false,"materials":[{"definitionId":"def-stock","quantity":2,"method":"material_consumed"}],"minimumPayment":${amount(sp=2,gp=3)},"source":"Fixture plan"}""")
        val stageTwo="""{"stageId":"fit","outcome":"completed","worldTimeSpent":"Twenty recorded minutes","rollId":null,"evidence":"Fixture assembly inspected","source":"Synthetic bench log"}"""
        val rejected=f.command("resolve_construction_stage",json(stageTwo))
        assertEquals("UNFINISHED_PREREQUISITE",rejected["error"]?.jsonPrimitive?.content)
        assertEquals("planned",f.data("fit").value("status"))
        val constructionRoll=f.native("record_check","""{"actorId":"researcher","rollId":"measurement","check":{"kind":"ability","key":"DEX","mode":"normal"},"evidence":{"origin":"player_physical","faces":[13]}}""")
        f.native("resolve_construction_stage","""{"stageId":"measure","outcome":"completed","worldTimeSpent":"Fifteen recorded minutes","rollId":"measurement","evidence":"Gauge reading accepted","source":"Synthetic bench log"}""")
        f.native("resolve_construction_stage",stageTwo)
        val finish="""{"projectId":"project","outputItemId":"assembly","stageIds":["measure","fit"],"source":"Confirmed inspection"}"""
        val noMaterial=f.command("link_completed_invention",json(finish))
        assertEquals("STAGE_MATERIAL_EVIDENCE_REQUIRED",noMaterial["error"]?.jsonPrimitive?.content)
        val integrated=f.native("integrate_component","""{"eventId":"fitting","projectId":"project","stageId":"fit","donorItemId":"stock","outputItemId":"assembly","method":"material_consumed","quantity":2,"evidence":"Two wire segments used","source":"Synthetic bench log"}""")
        val noPayment=f.command("link_completed_invention",json(finish))
        assertEquals("STAGE_PAYMENT_EVIDENCE_REQUIRED",noPayment["error"]?.jsonPrimitive?.content)
        val paid=f.native("pay_construction_cost","""{"projectId":"project","stageId":"fit","walletId":"purse","coins":${amount(sp=2,gp=3)},"reason":"Confirmed labor payment","source":"Synthetic bench log"}""")
        val last=f.native("link_completed_invention",finish)
        for (recorded in listOf(integrated,paid,last)) assertEquals(recorded,runBlocking { f.client.retry(recorded.value("commandId")) })
        val paymentId=paid.obj("receipt").obj("outcome").value("transactionRecordId")
        f.link(paymentId,"paid_stage","fit");f.link("measure","adjudicated_roll","measurement");f.link("finding","research_roll","observation")
        f.link("fitting","source_component","stock"); f.link("fitting","resulting_item","assembly"); f.link("project","completed_output","assembly")
        assertEquals(listOf("fitting"),f.view("genealogy","stock").map { it.value("id") })
        val lineage=f.view("genealogy","assembly").single().obj("data")
        assertEquals("7",lineage.value("beforeQuantity"));assertEquals("5",lineage.value("afterQuantity"))
        assertEquals("5",f.data("stock").value("quantity"));assertEquals(amount(sp=2,gp=3),f.data("purse").obj("coins"))
        assertEquals(check,f.receipt(check.value("commandId")));assertEquals(constructionRoll,f.receipt(constructionRoll.value("commandId")))
        assertEquals("14",f.data("observation").rows("faces").single().value("value"))
        f.resetCache(); runBlocking { f.refresh() }
        for(id in listOf("project","measure","fit","fitting","purse","assembly","stock","observation","measurement","drawing","finding",paymentId))
            assertEquals(f.data(id),f.nativeData(id))
        val inspected=mutableListOf<String>()
        val activity=f.mount(mutableListOf(),inspected) { snapshot,open,inspect ->
            CloudRecordScreen("Inventory",null,f.client,snapshot,true,open,inspect)
        }
        try {
            press("Projects");press("Bench gauge")
            node(hasText("Completed")).assertExists();node(hasText("Measure the wire")).assertExists();node(hasText("Fit the frame")).assertExists()
            press("Open resulting item");assertEquals(listOf("assembly"),inspected)
        } finally {activity.pause().stop().destroy()}
    }
}
