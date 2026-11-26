package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.Base64
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
    private fun AscensionWorkerFixture.expectDmRejection(op: String, raw: String, expected: String) {
        try {
            val result=command(op,json(raw))
            assertEquals(expected,result["error"]?.jsonPrimitive?.content)
        } catch (failure: IllegalStateException) {
            assertTrue(failure.toString(),failure.message.orEmpty().endsWith("\"$expected\""))
        }
    }
    private fun AscensionWorkerFixture.redeliver(result: JsonObject): JsonObject =
        signal("redeliverDeviceCommand", buildJsonObject { put("commandId", result.getValue("commandId")) })
    private fun AscensionWorkerFixture.nativeData(id: String) = runBlocking { requireNotNull(client.record(id)).obj("data") }
    private fun AscensionWorkerFixture.view(view: String, id: String) =
        dm("get_cloud_record_view",json("""{"view":"$view","id":"$id"}""")).rows("events")

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try { compose.waitUntil(10000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: ComposeTimeoutException) { throw AssertionError("Missing $matcher\n" + compose.onAllNodes(isRoot()).printToString(maxDepth=8), e) }
        val scrollable = compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction()))
        if (scrollable.fetchSemanticsNodes().isNotEmpty()) return scrollable.onFirst().performScrollTo()
        return compose.onAllNodes(matcher).onFirst()
    }
    private fun press(label: String) = node(hasText(label) and hasClickAction()).performClick()
    private fun side(index: Int, label: String, field: Boolean = false): SemanticsNodeInteraction {
        val action = if (field) hasSetTextAction() else hasClickAction()
        return node(hasAnyAncestor(hasTestTag("trade-offer-$index")) and hasText(label) and action)
    }
    private fun AscensionWorkerFixture.mount(
        saved: MutableList<JsonObject>, inspected: MutableList<String> = mutableListOf(), direct: Set<String> = emptySet(),
        issued: MutableList<String> = mutableListOf(),
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
                    content(snapshot,{ op,args ->
                        if(op in direct) {
                            saved.add(runBlocking { worker.client.submit(op,args,snapshot) });issued.add(op)
                            snapshot=runBlocking { requireNotNull(worker.client.workspace()) }
                        } else editor=op to args
                    },{inspected.add(it)})
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
            assertEquals(verified,f.redeliver(verified))
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
            f.expectDmRejection("record_check","""{"actorId":"caster","rollId":"second-save","forConcentrationId":"$checkId","check":{"kind":"save","key":"CON","mode":"normal"},"evidence":{"origin":"player_physical","faces":[20]}}""","CONCENTRATION_SAVE_ALREADY_RECORDED")
            assertNull(f.record("second-save"))
            press("Resolve recorded save · 5");press("Submit")
            val resolved=saved.last()
            assertEquals(resolved,f.receipt(resolved.value("commandId")))
            assertEquals("ended",resolved.obj("receipt").obj("outcome").value("status"));f.link(checkId,"resolved_save",roll)
            assertEquals(JsonNull,f.data("caster")["concentrationCastId"])
            assertEquals("1",f.data("slot").value("current"))
            assertEquals("4",f.data(roll).rows("faces").single().value("value"))
            assertEquals(cast,f.receipt(cast.value("commandId")))
            assertEquals(resolved,f.redeliver(resolved))
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
        f.expectDmRejection("resolve_construction_stage",stageTwo,"UNFINISHED_PREREQUISITE")
        assertEquals("planned",f.data("fit").value("status"))
        val constructionRoll=f.native("record_check","""{"actorId":"researcher","rollId":"measurement","check":{"kind":"ability","key":"DEX","mode":"normal"},"evidence":{"origin":"player_physical","faces":[13]}}""")
        f.native("resolve_construction_stage","""{"stageId":"measure","outcome":"completed","worldTimeSpent":"Fifteen recorded minutes","rollId":"measurement","evidence":"Gauge reading accepted","source":"Synthetic bench log"}""")
        f.native("resolve_construction_stage",stageTwo)
        val finish="""{"projectId":"project","outputItemId":"assembly","stageIds":["measure","fit"],"source":"Confirmed inspection"}"""
        f.expectDmRejection("link_completed_invention",finish,"STAGE_MATERIAL_EVIDENCE_REQUIRED")
        val integrated=f.native("integrate_component","""{"eventId":"fitting","projectId":"project","stageId":"fit","donorItemId":"stock","outputItemId":"assembly","method":"material_consumed","quantity":2,"evidence":"Two wire segments used","source":"Synthetic bench log"}""")
        f.expectDmRejection("link_completed_invention",finish,"STAGE_PAYMENT_EVIDENCE_REQUIRED")
        val paid=f.native("pay_construction_cost","""{"projectId":"project","stageId":"fit","walletId":"purse","coins":${amount(sp=2,gp=3)},"reason":"Confirmed labor payment","source":"Synthetic bench log"}""")
        val last=f.native("link_completed_invention",finish)
        for (recorded in listOf(integrated,paid,last)) assertEquals(recorded,f.redeliver(recorded))
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

    @Test fun renderedEquipmentAttackKeepsEachFormVariantAndApprovedArtWhileItsSecondarySaveStaysPending() =
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext(),lifecycleMedia=true).use { f ->
            f.actor("bearer","Test bearer");f.actor("target","Practice opponent","creature")
            f.seed("define_item_definition","""{"definitionId":"frame-def","name":"Calibration frame","category":"equipment","description":"A reviewed training frame","rarity":null,"stackable":false,"wearable":true,"handheld":false,"attunement":"none","source":"Fixture catalog"}""")
            f.seed("acquire_item","""{"itemId":"frame","definitionId":"frame-def","ownerId":"bearer","quantity":1,"source":"Fixture acquisition"}""")
            runBlocking { f.refresh() }
            f.native("equip_item","""{"itemId":"frame","equipment":"worn","source":"Confirmed fixture fitting"}""")
            for((id,name) in listOf("first" to "Travel configuration","second" to "Work configuration"))
                f.native("define_form","""{"actorId":"bearer","formId":"$id","name":"$name","source":"Fixture configuration","timing":"other","activationSeconds":0}""")
            fun change(id:String)=f.native("change_form","""{"actorId":"bearer","formId":"$id","elapsedSeconds":0}""")
            fun prepare(id:String)=f.native("use_action","""{"actorId":"bearer","actionId":"prime","useId":"$id","declared":true}""")
            f.native("define_action","""{"actorId":"bearer","sourceItemId":"frame","actionId":"prime","name":"Prime frame","type":"prepare_strike","timing":"bonus_action","chargeType":"thunder","chargeAbilities":["INT"],"source":"Fixture design"}""")
            f.native("define_resource","""{"actorId":"bearer","poolId":"drives","name":"Driver charges","type":"charge","maximum":12,"current":12,"recovery":"none","source":"Synthetic driver specification"}""")
            val born = f.native("define_attack","""{"actorId":"bearer","sourceItemId":"frame","attackId":"driver","name":"Calibration driver","source":"Reviewed fixture design","timing":"attack","delivery":"melee","reach":5,"resolution":"attack","fixedHit":22,"cost":{"poolId":"drives","amount":1},"chargeActionId":"prime","preparedPush":{"feet":5,"ability":"DEX","dc":13,"prone":true},"components":[{"id":"fixed","name":"Impulse","type":"force","dice":0,"sides":6,"flat":2},{"id":"element","name":"Element","type":"fire","dice":1,"sides":4,"flat":0,"variants":["fire","cold","acid"]}]}""")
            val bytes=Base64.getDecoder().decode(f.signal("sample",json("""{"shade":180}""")).value("bytes"))
            val hash=cloudDigest(bytes)
            val staged=f.dm("stage_cloud_image",buildJsonObject {
                put("targetId","driver");put("targetKind","attack");put("uploadId","driver-upload");put("creationCommandId",born.getValue("commandId"))
                put("sha256",hash);put("bytesBase64",Base64.getEncoder().encodeToString(bytes))
            })
            assertEquals(born.obj("receipt").value("transactionId"),staged.obj("creation").value("transactionId"))
            val request=f.command("assign_primary_image",json("""{"targetId":"driver","targetKind":"attack","uploadId":"driver-upload","assetId":"driver-art","sha256":"$hash"}"""))
            assertEquals("PENDING_APPROVAL",request.value("status"))
            fun art()=f.dm("get_cloud_primary_image",json("""{"id":"driver"}"""))
            assertEquals(JsonNull,art()["assetId"])
            val preview=f.signal("ownerPreview",json("""{"uploadId":"driver-upload"}"""))
            assertArrayEquals(bytes,Base64.getDecoder().decode(preview.value("bytes")))
            val decision=f.signal("ownerVerify",buildJsonObject {put("commandId",request.getValue("commandId"));put("digest",request.getValue("digest"))})
            val verified=runBlocking{f.client.verify(request.value("commandId"))}
            assertEquals(decision.obj("receipt"),verified.obj("receipt"));assertEquals(verified,f.receipt(request.value("commandId")))
            assertEquals("driver-art",art().value("assetId"))
            fun hold(text:String) {
                val eligible=hasText(text,substring=true) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
                node(eligible).performSemanticsAction(SemanticsActions.OnLongClick)
            }
            fun actionScreen(saved:MutableList<JsonObject>,issued:MutableList<String> = mutableListOf())=f.mount(saved,direct=setOf("resolve_attack","roll_attack_damage","roll_damage_only"),issued=issued) { snapshot,open,inspect ->
                CloudActorRecords("Actions",f.client,snapshot,"bearer",true,open,inspect,open)
            }
            change("first");prepare("single-preparation")
            val single=mutableListOf<JsonObject>()
            val singleOperations=mutableListOf<String>()
            val firstActivity=actionScreen(single,singleOperations)
            try {
                hold("1d4 fire");press("Cold");press("Submit")
                assertEquals(1,single.size);node(hasText("1d4 cold",substring=true)).assertExists()
                press("+22")
                val only=single.last();assertEquals(only,f.receipt(only.value("commandId")))
                val out=only.obj("receipt").obj("outcome")
                assertEquals(listOf("resolve_attack"),singleOperations);assertEquals(JsonNull,out["damage"])
                assertTrue(out.getValue("targets").jsonArray.isEmpty());assertEquals("11",f.data("drives").value("current"))
                assertEquals("40",f.data("target").value("currentHp"))
            } finally {firstActivity.pause().stop().destroy()}
            change("second");prepare("prepare-native")
            val saved=mutableListOf<JsonObject>()
            val activity=actionScreen(saved)
            try {
                hold("1d4 fire");press("Acid");press("Submit")
                assertEquals(1,saved.size);assertEquals(saved[0],f.receipt(saved[0].value("commandId")))
                val selections=f.data("driver").rows("selections").associate { it.value("formId") to it.value("type") }
                assertEquals(mapOf("first" to "cold","second" to "acid"),selections)
                node(hasText("1d4 acid",substring=true)).assertExists()
                hold("+22");press("Choose target");press("Practice opponent")
                assertEquals(1,saved.size);assertEquals("40",f.data("target").value("currentHp"))
                press("Roll Attack & Damage")
                val native=saved.last()
                assertEquals("COMMITTED",native.value("status"));assertEquals(native,f.receipt(native.value("commandId")))
                val attempt=native.obj("receipt").obj("outcome")
                val initial=attempt.value("attemptId")
                assertEquals("second",attempt.value("formId"));assertEquals("frame",attempt.value("sourceItemId"))
                assertEquals("app",attempt.obj("hit").value("origin"))
                assertEquals(listOf("force","acid","thunder"),attempt.rows("damage").map { it.value("type") })
                assertEquals(JsonNull,attempt["appliedDamage"]);assertTrue(attempt.rows("targetResults").isEmpty())
                assertEquals("40",f.data("target").value("currentHp"));assertTrue(f.data("target").getValue("conditions").jsonArray.isEmpty())
                f.link(initial,"declared_target","target");f.link(initial,"spends_preparation","prepare-native");f.link(initial,"used_attack","driver")
                fun target(id:String)=f.native("resolve_attack_target","""{"actorId":"bearer","attemptId":"$id","targetActorId":"target","source":"Resolved against fixture target AC"}""")
                var applied=target(initial)
                var hit=initial
                if(attempt.obj("hit").getValue("natural").jsonPrimitive.int==1) {
                    assertEquals("miss",applied.obj("receipt").obj("outcome").value("decision"))
                    assertEquals("40",f.data("target").value("currentHp"))
                    // A genuine natural one stands. A separately declared, newly prepared physical attack exercises the hit rider.
                    prepare("prepare-physical");hit="physical-attempt"
                    f.native("resolve_attack","""{"actorId":"bearer","attackId":"driver","attemptId":"$hit","mode":"normal","includeDamage":true,"targets":["target"],"hit":{"origin":"player_physical","faces":[15]},"damage":[{"id":"fixed","evidence":{"origin":"player_physical","faces":[]}},{"id":"element","evidence":{"origin":"player_physical","faces":[3]}},{"id":"prepared-bonus","evidence":{"origin":"player_physical","faces":[]}}]}""")
                    applied=target(hit)
                }
                val targetResult=applied.obj("receipt").obj("outcome")
                assertEquals("hit",targetResult.value("decision"));assertEquals(40-targetResult.value("amount").toInt(),f.data("target").value("currentHp").toInt())
                assertEquals("pending",targetResult.value("riderStatus"));assertEquals("13",targetResult.obj("riderSave").value("dc"))
                assertFalse(f.data(hit).rows("targetResults").single().containsKey("pushResult"))
                assertTrue(f.data("target").getValue("conditions").jsonArray.isEmpty())
                val recorded=f.command("record_check",json("""{"actorId":"target","rollId":"resist-push","forRiderAttemptId":"$hit","check":{"kind":"save","key":"DEX","mode":"normal"},"evidence":{"origin":"dm","faces":[4]}}"""))
                assertEquals("COMMITTED",recorded.value("status"))
                f.expectDmRejection("record_check","""{"actorId":"target","rollId":"repeat-push","forRiderAttemptId":"$hit","check":{"kind":"save","key":"DEX","mode":"normal"},"evidence":{"origin":"dm","faces":[20]}}""","ATTACK_RIDER_SAVE_ALREADY_RECORDED")
                runBlocking { f.refresh() }
                val rider=f.native("resolve_attack_rider","""{"actorId":"bearer","attemptId":"$hit","targetActorId":"target","rollId":"resist-push","feet":5,"destination":"Marked square","source":"Clear landing verified in isolated fixture"}""")
                assertEquals("true",rider.obj("receipt").obj("outcome").value("prone"));f.link(hit,"resolved_rider","resist-push")
                assertEquals(listOf(JsonPrimitive("prone")),f.data("target").getValue("conditions").jsonArray.toList())
                assertEquals("resolved",f.data(hit).rows("targetResults").single().value("riderStatus"))
                assertEquals("Marked square",f.data("target").value("position"))
                assertEquals(rider,f.redeliver(rider));assertEquals(native,f.redeliver(native))
            } finally {activity.pause().stop().destroy()}
            change("first")
            val damageButton=SemanticsMatcher("Roll damage control") { node ->
                node.config.contains(SemanticsActions.OnClick) && node.config[SemanticsActions.OnClick].label=="Roll damage"
            }
            var splitHit: String?=null
            // Preserve every actual die and missed attempt. Each independent declaration requires a fresh charge.
            for (number in 1..8) {
                val baseHp=f.data("target").getValue("currentHp").jsonPrimitive.int
                val basePool=f.data("drives").getValue("current").jsonPrimitive.int
                val prepared="split-prepare-$number";prepare(prepared)
                val receipts=mutableListOf<JsonObject>()
                val splitOperations=mutableListOf<String>()
                val splitActivity=actionScreen(receipts,splitOperations)
                try {
                    hold("+22");press("Choose target");press("Practice opponent");press("Roll Attack Only")
                    val first=receipts.single();val attack=first.obj("receipt").obj("outcome");val id=attack.value("attemptId")
                    assertEquals(first,f.receipt(first.value("commandId")));assertEquals(JsonNull,attack["damage"])
                    assertEquals(listOf(JsonPrimitive("target")),attack.getValue("targets").jsonArray.toList())
                    assertEquals(basePool-1,f.data("drives").getValue("current").jsonPrimitive.int)
                    assertEquals(baseHp,f.data("target").getValue("currentHp").jsonPrimitive.int)
                    node(damageButton).performClick()
                    compose.waitUntil(10000){receipts.size==2}
                    val second=receipts.last()
                    assertEquals(listOf("resolve_attack","roll_attack_damage"),splitOperations)
                    assertEquals(second,f.receipt(second.value("commandId")))
                    assertEquals(first,f.redeliver(first));assertEquals(second,f.redeliver(second))
                    assertEquals(id,second.obj("receipt").obj("outcome").value("attemptId"))
                    val linked=f.data(id)
                    assertEquals(attack.obj("hit"),linked.obj("hit"))
                    assertEquals(listOf("force","cold","thunder"),linked.rows("damage").map{it.value("type")})
                    assertEquals(1,linked.rows("costs").size);f.link(id,"spends_preparation",prepared);f.link(id,"declared_target","target")
                    assertEquals(basePool-1,f.data("drives").getValue("current").jsonPrimitive.int)
                    assertEquals(baseHp,f.data("target").getValue("currentHp").jsonPrimitive.int)
                    val applied=f.native("resolve_attack_target","""{"actorId":"bearer","attemptId":"$id","targetActorId":"target","source":"Independently confirmed split target"}""").obj("receipt").obj("outcome")
                    if(attack.obj("hit").getValue("natural").jsonPrimitive.int==1) {
                        assertEquals("miss",applied.value("decision"));assertEquals("not_triggered",applied.value("riderStatus"))
                        assertEquals(baseHp,f.data("target").getValue("currentHp").jsonPrimitive.int)
                    } else {
                        assertEquals("pending",applied.value("riderStatus"))
                        val die=f.command("record_check",json("""{"actorId":"target","rollId":"split-save","forRiderAttemptId":"$id","check":{"kind":"save","key":"DEX","mode":"normal"},"evidence":{"origin":"dm","faces":[16]}}"""))
                        assertEquals("COMMITTED",die.value("status"));runBlocking{f.refresh()}
                        val resolved=f.native("resolve_attack_rider","""{"actorId":"bearer","attemptId":"$id","targetActorId":"target","rollId":"split-save","feet":0,"source":"Recorded resisting save"}""")
                        assertEquals("true",resolved.obj("receipt").obj("outcome").value("saved"));f.link(id,"resolved_rider","split-save")
                        assertEquals("0",resolved.obj("receipt").obj("outcome").value("acceptedFeet"))
                        assertEquals(basePool-1,f.data("drives").getValue("current").jsonPrimitive.int)
                        splitHit=id
                    }
                }finally{splitActivity.pause().stop().destroy()}
                if(splitHit!=null)break
            }
            assertNotNull("At least one declared strike must hit to exercise its secondary save",splitHit)
            prepare("prepare-read")
            f.resetCache();val fresh=runBlocking { f.refresh() }
            assertNull(runBlocking { f.client.cachedImage("driver") })
            val firstForm=f.dm("get_cloud_actions",json("""{"id":"bearer"}""")).rows("rows").single { it.value("id")=="driver" }
            assertEquals("cold",firstForm.rows("components").single { it.value("id")=="element" }.value("type"))
            assertEquals(f.data("driver"),f.nativeData("driver"))
            val reopened=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                reopened.get().setContent { AstravarTheme { CreatedEntityArtwork(f.client,fresh,true,born.obj("receipt")) } }
                node(hasText("Artwork for Calibration driver")).assertExists()
                val image=hasContentDescription("Calibration driver")
                compose.waitUntil(10000){compose.onAllNodes(image,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                val cached=runBlocking { requireNotNull(f.client.cachedImage("driver")) }
                assertEquals(art().value("thumbnailSha256"),cloudDigest(cached))
                assertEquals(born,f.receipt(born.value("commandId")));assertEquals(verified,f.receipt(request.value("commandId")))
            } finally {reopened.pause().stop().destroy()}
        }
}
