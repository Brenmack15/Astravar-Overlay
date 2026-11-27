package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudItemCustomizationInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.fields(key: String) = getValue(key).jsonObject
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try { compose.waitUntil(8_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: ComposeTimeoutException) { throw AssertionError("Missing ${matcher.description}\n" + compose.onAllNodes(isRoot()).printToString(maxDepth=8), error) }
        val scrollable = compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction()))
        if (scrollable.fetchSemanticsNodes().isNotEmpty()) return scrollable.onFirst().performScrollTo()
        return compose.onAllNodes(matcher).onFirst()
    }
    private fun press(label: String) = node(hasText(label) and hasClickAction() and isEnabled() and !hasSetTextAction()).performClick()
    private fun field(label: String) = node(hasText(label) and hasSetTextAction())

    private fun AscensionWorkerFixture.seed(operation: String, raw: String) {
        val response = command(operation, json(raw))
        assertEquals("$operation $response", "COMMITTED", response.cloudText("status"))
    }

    private fun AscensionWorkerFixture.seedParty() {
        for ((id, name, role) in listOf(Triple("lead", "Survey leader", "player"), Triple("scout", "Forest scout", "companion"))) {
            seed("define_actor", """{"actorId":"$id","name":"$name","role":"$role","rulesProfile":"revised_2024","level":3,"abilities":{"STR":10,"DEX":11,"CON":12,"INT":13,"WIS":14,"CHA":10},"maxHp":20,"currentHp":18,"temporaryHp":0,"baseAc":12,"gameSeconds":0,"attunementCapacity":1,"source":"Synthetic party"}""")
        }
    }

    private fun mount(worker: AscensionWorkerFixture, tab: MutableState<String>, actor: MutableState<String>,
                      snapshot: MutableState<CloudSnapshot>, saved: MutableList<Pair<String, JsonObject>>): ActivityController<ComponentActivity> {
        val operations = runBlocking { worker.client.capabilities() }.rows("operations")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            var editor by remember { mutableStateOf<Triple<String, JsonObject, CloudSnapshot>?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            val open: RecordOperation = { op, args -> editor = Triple(op, args, snapshot.value); error = null }
            AstravarTheme {
                if (tab.value == "Archive") {
                    CloudArchiveManager(worker.client, snapshot.value, true, open) { tab.value = "Inventory" }
                } else Column(Modifier.verticalScroll(rememberScrollState())) {
                    CloudRecordScreen(tab.value, actor.value, worker.client, snapshot.value, true, open, {})
                }
                editor?.let { (op, initial, reviewed) ->
                    CloudCommandForm(operations.single { it.cloudText("name") == op }, false, { editor = null }, { values, id ->
                        try {
                            val response = runBlocking { worker.client.submit(op, values, reviewed, id) }
                            saved += op to response
                            snapshot.value = runBlocking { requireNotNull(worker.client.workspace()) }
                            editor = null
                        } catch (failure: OwnerSyncFailure) {
                            error = cloudError(failure)
                        }
                    }, error, initial)
                }
            }
        }
        return activity
    }

    @Test fun nativeGearKeepsItsRulesAndFiniteChargesAcrossLoanStorageGiftAdministrativeRestoreAndReacquisition() {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { worker ->
            worker.seedParty()
            val source = "Synthetic recovered instrument"
            worker.seed("define_item_definition", """{"definitionId":"optic-plan","name":"Survey optic","category":"instrument","description":"Adjustable optic","rarity":"rare","stackable":false,"wearable":true,"handheld":false,"attunement":"required","attunementSeconds":60,"activation":"passive","source":"$source"}""")
            worker.seed("acquire_item", """{"itemId":"optic","definitionId":"optic-plan","ownerId":"lead","quantity":1,"source":"$source"}""")
            worker.seed("define_resource", """{"actorId":"lead","poolId":"optic-pool","name":"Optical charges","type":"charge","maximum":4,"current":2,"recovery":"dawn","source":"$source"}""")
            worker.seed("bind_item_charge_pool", """{"itemId":"optic","poolId":"optic-pool","source":"$source"}""")
            val grant = """"actorId":"lead","sourceItemId":"optic","transfersWithItem":true,"requiresAttunement":true,"requiresOperating":true,"source":"$source""""
            worker.seed("define_attack", """{$grant,"attackId":"optic-ray","name":"Optical ray","timing":"action","delivery":"ranged","resolution":"attack","fixedHit":4,"components":[]}""")
            worker.seed("define_action", """{$grant,"actionId":"optic-focus","name":"Optical focus","type":"use","timing":"other","cost":{"poolId":"optic-pool","amount":1}}""")
            worker.seed("define_resource", """{"actorId":"lead","poolId":"form-charges","name":"Survey configurations","type":"ordinary","maximum":2,"current":2,"recovery":"long_rest","source":"$source"}""")
            worker.seed("define_form", """{"actorId":"lead","formId":"survey-form","name":"Survey configuration","activationSeconds":0,"timing":"other","deploymentPoolId":"form-charges","source":"$source"}""")
            worker.seed("change_form", """{"actorId":"lead","formId":"survey-form","elapsedSeconds":0,"uninterrupted":true}""")
            worker.seed("define_connection", """{"entityId":"vault","name":"Survey locker","category":"place","partyStorage":true,"actorId":null,"knownDescription":null,"source":"$source"}""")
            worker.seed("define_connection", """{"entityId":"office","name":"Known survey office","category":"organization","actorId":null,"knownDescription":null,"source":"$source"}""")
            val snapshot = mutableStateOf(runBlocking { worker.refresh() })
            val actor = mutableStateOf("lead")
            val tab = mutableStateOf("Inventory")
            val saved = mutableListOf<Pair<String,JsonObject>>()
            val activity = mount(worker, tab, actor, snapshot, saved)
            fun activeData(id: String) = requireNotNull(worker.record(id)).fields("data")
            fun reviewed(label: String, operation: String) {
                press(label); press("Submit")
                val (op, response) = saved.last()
                assertEquals(operation, op)
                assertEquals("COMMITTED", response.cloudText("status"))
                assertEquals(response, worker.receipt(response.cloudText("commandId")))
            }
            fun place(owner: String?, custody: String?, location: String?, holding: String) {
                val data = activeData("optic")
                for ((key, value) in mapOf("ownerId" to owner, "custodianId" to custody, "locationId" to location, "holding" to holding)) {
                    assertEquals(key, value, data[key]?.jsonPrimitive?.contentOrNull)
                }
                assertEquals("optic-pool", data.cloudText("resourcePoolId"))
                for (id in listOf("optic-pool", "optic-ray", "optic-focus")) {
                    assertEquals(id, custody, requireNotNull(worker.record(id))["actorId"]?.jsonPrimitive?.contentOrNull)
                }
                assertEquals(2, activeData("optic-pool").getValue("current").jsonPrimitive.int)
                assertEquals(1, activeData("form-charges").getValue("current").jsonPrimitive.int)
            }
            fun actions(id: String) = worker.dm("get_cloud_actions", buildJsonObject { put("id", id) }).rows("rows").map { it.cloudText("id") }.toSet()
            fun attune() {
                press("Attune"); field("Elapsed Seconds").performTextReplacement("60")
                press("Select"); press("True"); press("Submit")
                val (op, receipt) = saved.last()
                assertEquals("set_item_attunement", op); assertEquals("COMMITTED", receipt.cloudText("status"))
                assertEquals(receipt, worker.receipt(receipt.cloudText("commandId")))
            }
            fun move(type: String, reason: String, select: String? = null, loan: Boolean = false, outside: String = "Gift", knownPlace: String? = null) {
                fun tab(label: String) { node(hasTestTag("section-$label")).performSemanticsAction(SemanticsActions.OnClick) }
                tab("Transfer")
                node(hasText("Destination")).assertExists()
                tab(type)
                if (loan) tab("Lend")
                if (type == "External") {
                    tab(outside)
                    if (outside == "Gift") { press("Choose a known recipient"); press("Known survey office") }
                    if (knownPlace != null) { press("Last known location"); press(knownPlace) }
                }
                if (select != null) press(select)
                field("Reason").performTextReplacement(reason)
                reviewed("Review transfer", "transfer_item")
            }
            fun item() { press("Survey optic"); node(hasText("Customize this instance")).assertExists() }
            try {
                item(); reviewed("Wear", "equip_item"); attune(); reviewed("Favorite", "set_item_favorite")
                node(hasText("Remove favorite")).assertExists()
                assertTrue(actions("lead").containsAll(listOf("optic-focus", "optic-ray")))
                assertEquals("survey-form", activeData("lead").cloudText("activeFormId"))
                place("lead", "lead", null, "carried")
                move("Party member", "Loan for a measured reading", "Forest scout", loan = true)
                place("lead", "scout", null, "carried")
                assertFalse(activeData("optic").getValue("attuned").jsonPrimitive.boolean)
                assertFalse("optic-ray" in actions("lead"))
                press("Details"); reviewed("Wear", "equip_item"); attune()
                assertTrue(actions("scout").containsAll(listOf("optic-focus", "optic-ray")))
                move("Storage", "Place in locker", "Survey locker")
                place("lead", null, "vault", "storage")
                move("Unassigned", "Receiving location unconfirmed")
                place("lead", null, null, "unassigned")
                move("External", "Give to survey office")
                assertNull(worker.record("optic"))
                val external = runBlocking { worker.client.projection(snapshot.value, "archive?id=optic") }.fields("record").fields("data")
                assertEquals("external_gift", external.cloudText("disposition")); assertEquals("Known survey office", external.cloudText("externalRecipientName"))
                assertEquals("office", external.cloudText("externalRecipientId")); assertEquals(JsonNull, external["locationId"])
                assertFalse(external.getValue("partyAccess").jsonPrimitive.boolean)
                compose.runOnIdle { tab.value = "Archive" }
                node(hasTestTag("archive-record-optic")).performClick(); press("Select record"); press("Review restore"); press("Submit")
                val pending = saved.last().second
                assertEquals("PENDING_APPROVAL", pending.cloudText("status"))
                val decision = worker.signal("ownerVerify", buildJsonObject { put("commandId", pending.cloudText("commandId")); put("digest", pending.cloudText("digest")) })
                assertEquals("COMMITTED", decision.cloudText("status"))
                val fresh = runBlocking { worker.refresh() }
                compose.runOnIdle { snapshot.value = fresh; tab.value = "Inventory" }
                node(hasText("No matching party items.")).assertExists()
                assertEquals(external, activeData("optic"))
                compose.runOnIdle { tab.value = "Archive" }
                press("Active"); node(hasTestTag("archive-record-optic")).performClick()
                press("Record gameplay reacquisition"); press("Forest scout")
                field("Reacquisition reason").performTextReplacement("Office returned the inspected optic")
                field("Reacquisition source").performTextReplacement("Confirmed synthetic return")
                reviewed("Review reacquisition", "recover_item")
                place("scout", "scout", null, "carried")
                assertFalse(activeData("optic").getValue("attuned").jsonPrimitive.boolean)
                val result = saved.last().second
                assertEquals(result, worker.signal("redeliverDeviceCommand", buildJsonObject { put("commandId", result.cloudText("commandId")) }))
                worker.resetCache()
                runBlocking {
                    worker.refresh()
                    for (id in listOf("optic", "optic-ray", "optic-focus", "optic-pool", "form-charges")) assertEquals(worker.record(id), worker.client.record(id))
                }
                val history = worker.dm("get_cloud_record_view", json("""{"view":"item_history","id":"optic"}""")).rows("events")
                assertEquals("recovery", history.first().fields("data").cloudText("type"))
                assertEquals(1, history.count { it.fields("data").cloudText("type") == "recovery" })
                compose.runOnIdle { tab.value = "Equipment"; actor.value = "scout" }
                item(); move("External", "Lost near the survey locker", outside = "Lost", knownPlace = "Survey locker")
                val lost = worker.dm("get_cloud_archive", json("""{"id":"optic"}""")).fields("record").fields("data")
                assertEquals("lost", lost.cloudText("disposition")); assertFalse(lost.getValue("partyAccess").jsonPrimitive.boolean)
                assertEquals("scout", lost.cloudText("ownerId")); assertEquals("scout", lost.cloudText("lastPossessorId"))
                assertEquals("vault", lost.cloudText("locationId")); assertEquals(JsonNull, lost["externalRecipientId"])
                assertEquals(JsonNull, lost["externalRecipientName"]); assertEquals(JsonNull, lost["custodianId"])
            } finally { activity.pause().stop().destroy() }
        }
    }

    @Test fun splitPartialTransferMergeAndSourceCorrectionsPreserveStockAndExactPersonalAndSharedWallets() {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { worker ->
            worker.seedParty()
            worker.seed("define_item_definition", """{"definitionId":"supply","name":"Trail bead","category":"gear","description":"Counted beads","rarity":null,"stackable":true,"wearable":false,"handheld":false,"attunement":"none","source":"Known supply"}""")
            worker.seed("acquire_item", """{"itemId":"bag","definitionId":"supply","ownerId":"lead","quantity":10,"source":"Synthetic stock count"}""")
            worker.seed("record_historical_item_event", """{"eventId":"earlier-ledger","itemId":"bag","eventType":"acquisition","previousOwner":{"kind":"named","id":null,"name":"Workshop keeper"},"newOwner":{"kind":"actor","id":"lead","name":null},"reason":"Earlier acquisition account","worldTime":null,"sourceEventId":"source-a","sourcePublishedAt":null,"source":"First source","evidence":"uncertain","correctsId":null}""")
            val firstHistory = requireNotNull(worker.record("earlier-ledger"))
            worker.seed("define_wallet", """{"walletId":"lead-wallet","title":"Field purse","holderId":"lead","shared":false,"coins":{"cp":3,"sp":4,"ep":null,"gp":9,"pp":1},"source":"Recorded personal coins"}""")
            worker.seed("define_wallet", """{"walletId":"shared-wallet","title":"Expedition fund","holderId":null,"shared":true,"coins":{"cp":null,"sp":1,"ep":0,"gp":4,"pp":0},"source":"Recorded shared coins"}""")
            val snapshot = mutableStateOf(runBlocking { worker.refresh() })
            val tab = mutableStateOf("Inventory")
            val saved = mutableListOf<Pair<String, JsonObject>>()
            val activity = mount(worker, tab, mutableStateOf("lead"), snapshot, saved)
            fun result(op: String): JsonObject {
                val (operation, receipt) = saved.last()
                assertEquals(op, operation); assertEquals("COMMITTED", receipt.cloudText("status"))
                assertEquals(receipt, worker.receipt(receipt.cloudText("commandId")))
                return receipt.fields("receipt").fields("outcome")
            }
            fun inspectStock() = worker.dm("get_cloud_record_view", json("""{"view":"inventory","id":"supply"}""")).rows("items")
            fun quantities() = inspectStock().associate { it.cloudText("id") to it.getValue("quantity").jsonPrimitive.int }
            try {
                press("Trail bead"); press("Survey leader"); press("Transfer")
                node(hasContentDescription("Transfer quantity")).performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
                node(hasText("Selected · 4    Remaining · 6")).assertExists()
                field("Quantity").performTextReplacement("3")
                node(hasText("Selected · 3    Remaining · 7")).assertExists()
                assertEquals(mapOf("bag" to 10), quantities())
                press("Split stack"); press("Submit")
                val split = result("split_stack").cloudText("childId")
                assertEquals(mapOf("bag" to 7, split to 3), quantities())
                field("Quantity").performTextReplacement("2"); press("Forest scout")
                field("Reason").performTextReplacement("Separate survey supply")
                press("Review transfer"); press("Submit")
                val moved = result("transfer_item").cloudText("itemId")
                assertEquals(mapOf("bag" to 5, split to 3, moved to 2), quantities())
                assertEquals("scout", requireNotNull(worker.record(moved)).fields("data").cloudText("ownerId"))
                press("Merge compatible stack")
                val eligible = hasText("Trail bead") and hasText("3 · Packed") and hasClickAction()
                node(eligible).assertExists()
                compose.onAllNodes(hasText("Trail bead") and hasText("2 · Packed") and hasClickAction()).assertCountEquals(0)
                val rejected = assertThrows(IllegalStateException::class.java) {
                    worker.command("merge_stacks", json("""{"keepId":"bag","mergeId":"$moved","source":"Fixture incompatible custody"}"""))
                }
                assertTrue(rejected.message.orEmpty().contains("STACK_INCOMPATIBLE"))
                node(eligible).performClick(); press("Submit")
                assertEquals(split, result("merge_stacks").cloudText("absorbedId"))
                assertEquals(mapOf("bag" to 8, moved to 2), quantities())
                val archived = worker.dm("get_cloud_archive", buildJsonObject { put("id", split) })
                assertEquals("bag", archived.fields("record").fields("data").cloudText("mergedInto"))
                assertTrue(archived.fields("dependencies").rows("links").any { it.cloudText("relation") == "merged_into" })
                press("History")
                node(hasText("Previous Owner · Workshop keeper")).assertExists()
                node(hasText("New Owner · Survey leader")).assertExists()
                press("Correct this historical event")
                field("Source Event Id").performTextReplacement("source-b")
                field("Reason").performTextReplacement("Signed survey ledger confirms transfer")
                field("Source").performTextReplacement("Second source")
                press("uncertain"); press("Confirmed"); press("Submit")
                val corrected = result("record_historical_item_event").cloudText("eventId")
                val history = worker.dm("get_cloud_record_view", json("""{"view":"item_history","id":"bag"}""")).rows("events")
                assertEquals(corrected, history.first().cloudText("id"))
                assertEquals("confirmed", history.first().fields("data").cloudText("evidence"))
                node(hasText("Reason · Signed survey ledger confirms transfer")).assertExists()
                assertEquals(firstHistory, worker.record("earlier-ledger"))
                val correctionEdges = worker.dm("get_cloud_record_links", buildJsonObject { put("id", corrected) }).rows("links")
                assertTrue(correctionEdges.any { it.cloudText("relation") == "corrects_history" && it.cloudText("to_id") == "earlier-ledger" })
                assertEquals(mapOf("bag" to 8, moved to 2), quantities())
                press("Close")

                for ((label, wallet, denomination) in listOf(Triple("Field purse", "lead-wallet", "EP"), Triple("Expedition fund", "shared-wallet", "CP"))) {
                    if (wallet == "lead-wallet") compose.runOnIdle { tab.value = "Equipment" }
                    else { compose.runOnIdle { tab.value = "Inventory" }; press("Wallets") }
                    press(label); press("Confirm a recorded balance"); press("$denomination · Unknown")
                    field("Confirmed Balance").performTextReplacement(if (denomination == "EP") "2" else "0")
                    field("Reason").performTextReplacement("Counted physical denomination")
                    field("Source").performTextReplacement("Synthetic verified count")
                    assertEquals(JsonNull, requireNotNull(worker.record(wallet)).fields("data").fields("coins")[denomination.lowercase()])
                    press("Submit"); result("reconcile_wallet_denomination")
                    press("Transaction history")
                    node(hasText("Synthetic verified count")).assertExists()
                    press("Close")
                }
                assertEquals(json("""{"cp":3,"sp":4,"ep":2,"gp":9,"pp":1}"""), requireNotNull(worker.record("lead-wallet")).fields("data").fields("coins"))
                assertEquals(json("""{"cp":0,"sp":1,"ep":0,"gp":4,"pp":0}"""), requireNotNull(worker.record("shared-wallet")).fields("data").fields("coins"))
                worker.resetCache()
                runBlocking {
                    worker.refresh()
                    for (id in listOf("bag", moved, "lead-wallet", "shared-wallet", "earlier-ledger", corrected)) assertEquals(worker.record(id), worker.client.record(id))
                }
                for ((_, receipt) in saved) assertEquals(receipt, worker.signal("redeliverDeviceCommand", buildJsonObject { put("commandId", receipt.cloudText("commandId")) }))
            } finally { activity.pause().stop().destroy() }
        }
    }

    @Test fun sharedCatalogAndOneCustomHoldingRemainDistinctAcrossThreeOwnersAndColdCache() {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { worker ->
            val holders = listOf(Triple("lead", "Survey leader", 1), Triple("scout", "Forest scout", 3), Triple("guide", "Trail guide", 6))
            worker.seed("define_item_definition", """{"definitionId":"marker","name":"Survey marker","category":"gear","description":"Unmarked field tool","rarity":null,"stackable":true,"wearable":false,"handheld":false,"attunement":"none","source":"Synthetic catalog"}""")
            for ((actor, title, quantity) in holders) {
                val role = if (actor == "lead") "player" else "companion"
                worker.seed("define_actor", """{"actorId":"$actor","name":"$title","role":"$role","rulesProfile":"revised_2024","level":3,"abilities":{"STR":10,"DEX":11,"CON":12,"INT":13,"WIS":14,"CHA":10},"maxHp":20,"currentHp":18,"temporaryHp":0,"baseAc":12,"source":"Synthetic party"}""")
                worker.seed("acquire_item", """{"itemId":"stock-$actor","definitionId":"marker","ownerId":"$actor","quantity":$quantity,"source":"Synthetic stock count"}""")
            }
            val originals = holders.associate { (actor, _, _) -> actor to requireNotNull(worker.record("stock-$actor")) }
            val recorded = mutableListOf<Pair<String, JsonObject>>()
            fun saved() = recorded.map { it.second }
            val start = runBlocking { worker.refresh() }
            val tab = mutableStateOf("Inventory")
            val actor = mutableStateOf("lead")
            val snapshot = mutableStateOf(start)
            val activity = mount(worker, tab, actor, snapshot, recorded)
            try {
                node(hasText("Party quantity · 10")).assertExists()
                press("Survey marker")
                holders.forEach { (_, title, _) -> node(hasText(title) and hasClickAction()).assertExists() }
                press("Edit shared definition")
                field("Name").performTextReplacement("Revised survey marker")
                field("Description").performTextReplacement("Graduated field tool")
                assertEquals("Survey marker", requireNotNull(worker.record("marker")).cloudText("title"))
                press("Submit")
                node(hasText("Graduated field tool")).assertExists()
                assertEquals("shared_definition", saved().single().fields("receipt").fields("outcome").cloudText("appliesTo"))
                for ((holder, original) in originals) assertEquals(original, worker.record("stock-$holder"))

                press("Survey leader")
                node(hasText("Owner · Survey leader")).assertExists()
                press("Customize this instance")
                field("Name").assertTextContains("Revised survey marker")
                field("Description").assertTextContains("Graduated field tool")
                field("Name").performTextReplacement("Inscribed marker")
                field("Description").performTextReplacement("Guide marks on one handle")
                field("Condition").performTextReplacement("inscribed")
                assertEquals(originals.getValue("lead"), worker.record("stock-lead"))
                press("Submit")
                node(hasText("Guide marks on one handle")).assertExists()
                val customized = requireNotNull(worker.record("stock-lead"))
                assertEquals("marker", customized.fields("data").cloudText("definitionId"))
                assertEquals("Inscribed marker", customized.cloudText("title"))
                assertTrue(customized.fields("data").getValue("customized").jsonPrimitive.boolean)
                for (holder in listOf("scout", "guide")) assertEquals(originals.getValue(holder), worker.record("stock-$holder"))
                press("Customize this instance")
                field("Name").assertTextContains("Inscribed marker")
                field("Description").assertTextContains("Guide marks on one handle")
                field("Condition").assertTextContains("inscribed")
                press("Cancel"); press("Discard")
                press("History")
                node(hasText("Reason · Customization")).assertExists()
                assertEquals(2, saved().size)
                press("Close")
                node(hasText("Party quantity · 9")).assertExists()
                node(hasText("Party quantity · 1")).assertExists()
                press("Revised survey marker")
                press("Edit shared definition")
                field("Name").performTextReplacement("Calibrated marker")
                field("Description").performTextReplacement("Revised standard graduations")
                press("Submit")
                node(hasText("Revised standard graduations")).assertExists()
                press("Forest scout")
                node(hasText("Owner · Forest scout")).assertExists()
                node(hasText("Customize this instance") and hasClickAction()).assertIsNotEnabled()
                press("Close")
                node(hasText("Calibrated marker") and hasClickAction()).assertExists()
                node(hasText("Inscribed marker") and hasClickAction()).assertExists()

                assertEquals(3, saved().size)
                saved().forEachIndexed { index, response ->
                    assertEquals("COMMITTED", response.cloudText("status"))
                    assertEquals(start.version.revision + index + 1, response.getValue("revision").jsonPrimitive.long)
                    assertEquals(response, worker.receipt(response.cloudText("commandId")))
                    assertEquals(response, worker.signal("redeliverDeviceCommand", buildJsonObject { put("commandId", response.cloudText("commandId")) }))
                }
                val distribution = worker.dm("get_cloud_record_view", json("""{"view":"inventory","id":"marker"}""")).rows("items")
                assertEquals(holders.associate { (id, _, count) -> id to count }, distribution.associate { it.cloudText("ownerId") to it.getValue("quantity").jsonPrimitive.int })
                assertEquals(customized, worker.record("stock-lead"))
                val history = worker.dm("get_cloud_record_view", json("""{"view":"item_history","id":"stock-lead"}""")).rows("events")
                assertEquals(1, history.count { it.fields("data").cloudText("commandId") == saved()[1].cloudText("commandId") })
                worker.resetCache()
                runBlocking {
                    worker.refresh()
                    for (id in listOf("marker", "stock-lead", "stock-scout", "stock-guide")) {
                        assertEquals(worker.record(id), worker.client.record(id))
                    }
                }
                compose.runOnIdle { tab.value = "Equipment" }
                node(hasText("Inscribed marker") and hasClickAction()).assertExists()
                compose.runOnIdle { actor.value = "scout" }
                node(hasText("Calibrated marker") and hasClickAction()).assertExists()
                compose.onAllNodesWithText("Inscribed marker").assertCountEquals(0)

                compose.runOnIdle { actor.value = "lead" }
                press("Inscribed marker"); press("Customize this instance")
                field("Name").performTextReplacement("Unsaved new inscription")
                worker.seed("edit_item_definition", """{"definitionId":"marker","name":"Surveyor's marker","description":"Subsequent catalog revision","category":"gear","rarity":null,"source":"Independent DM review"}""")
                val latest = runBlocking { worker.refresh() }
                compose.runOnIdle { snapshot.value = latest }
                field("Name").assertTextContains("Unsaved new inscription")
                press("Submit")
                node(hasText("The campaign changed. Refresh and review the current values.")).assertExists()
                field("Name").assertTextContains("Unsaved new inscription")
                assertEquals(customized, worker.record("stock-lead"))
                assertEquals(3, saved().size)
                assertEquals(latest.version.revision, worker.dm("get_cloud_state", json("""{"limit":1}""")).getValue("revision").jsonPrimitive.long)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }
}
