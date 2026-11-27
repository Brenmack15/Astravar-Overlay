package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
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
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudPresetManagementInteropTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val inventor = "researcher"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(m: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(15_000) { compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(m).onFirst()
    }
    private fun press(text: String) = waitFor(hasText(text) and hasClickAction() and isEnabled() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
    private fun enter(label: String, text: String) =
        waitFor(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(text)
    private data class Configured(val id: String, val name: String, val entries: List<Pair<String,String>>, val workshop: Boolean)

    private fun createAndEdit(presets: () -> List<Configured>, rollCount: () -> Int) {
        val initialRolls = rollCount()
        val selector = waitFor(hasTestTag("roll-preset-selector"))
        val modes = waitFor(hasText("Dice") and hasClickAction())
        assertTrue(selector.fetchSemanticsNode().boundsInRoot.bottom <= modes.fetchSemanticsNode().boundsInRoot.top)
        selector.performClick()
        waitFor(hasText("3 normal")).assertIsDisplayed()
        waitFor(hasText("3 advantage")).assertIsDisplayed()
        waitFor(hasTestTag("roll-preset-option-template-research")).performClick()
        val roll = waitFor(hasText("Roll All") and hasClickAction()).performScrollTo()
        val save = waitFor(hasText("Save as preset") and hasClickAction()).performScrollTo()
        val left = roll.fetchSemanticsNode().boundsInRoot
        val right = save.fetchSemanticsNode().boundsInRoot
        assertEquals(left.center.y, right.center.y, 2f)
        assertTrue(left.right <= right.left)
        save.performClick()
        enter("New preset name", "Discard this draft"); press("Cancel")
        assertTrue(presets().isEmpty()); assertEquals(initialRolls, rollCount())
        press("Save as preset"); enter("New preset name", "Survey standard"); press("Save new preset")
        compose.waitUntil(15_000) { presets().any { it.name == "Survey standard" } }
        val standard = presets().single()
        assertEquals(listOf("investigation","arcana","history"), standard.entries.map { it.first.lowercase() })
        assertTrue(standard.entries.all { it.second == "normal" })
        assertFalse(standard.workshop)
        press("Manage presets")
        waitFor(hasText("Duplicate preset") and hasClickAction()).assertIsEnabled()
        press("Duplicate preset")
        compose.waitUntil(15_000) { presets().size == 2 }
        val copy = presets().single { it.id != standard.id }
        enter("Preset name", "Workshop survey")
        press("Requires Pocket Workshop")
        press("Skills")
        for (skill in listOf("Investigation","Arcana","History")) {
            waitFor(hasContentDescription("$skill advantage, unselected")).performScrollTo().performClick()
        }
        press("Manage presets")
        press("Update preset")
        compose.waitUntil(15_000) { presets().any { it.id == copy.id && it.name == "Workshop survey" && it.workshop } }
        val workshop = presets().single { it.id == copy.id }
        assertEquals(standard.entries.map { it.first }, workshop.entries.map { it.first })
        assertTrue(workshop.entries.all { it.second == "advantage" })
        assertEquals(standard, presets().single { it.id == standard.id })
        press("Roll Queue (3)")
        press("Save as preset"); enter("New preset name", "Second survey"); press("Save new preset")
        compose.waitUntil(15_000) { presets().size == 3 }
        assertEquals(initialRolls, rollCount())
        assertEquals(workshop, presets().single { it.id == workshop.id })
        selector.performScrollTo().performClick()
        waitFor(hasTestTag("roll-preset-option-${standard.id}")).performClick()
        waitFor(hasText("1. Investigation")).assertExists()
        press("Manage presets"); press("Delete preset permanently"); press("Cancel removal")
        assertEquals(3, presets().size); assertEquals(initialRolls, rollCount())
    }

    @Test fun retainedRoomPresetsHaveDiscoverableModesAndSaveTheWholeQueueAsANewIdentity() {
        val oldDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val local = ReforgeRepository(db)
        val actor = ReforgeCharacter(inventor, "Research fixture", level = 5, baseAbilities = Stat.entries.associateWith { 14 })
        val historical = runBlocking {
            local.create(actor)
            local.appendRoll(ReforgeRollRecord(profileId = inventor, characterRevision = requireNotNull(local.get(inventor)).revision,
                label = "Prior check", formula = "1d20 + 2", manual = true,
                components = listOf(ReforgeRollComponent("Prior check", listOf(ReforgeRollDie(20, 9)), 2))))
        }
        fun presets() = runBlocking { local.get(inventor) }!!.character.rollPresets.map { p ->
            Configured(p.id,p.name,p.entries.map { it.key to it.mode.name.lowercase() },p.workshop)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val campaign = CampaignLedgerRepository(oldDb)
            activity.get().setContent { AstravarTheme { ReforgeCharacterSheet(campaign, local) } }
            waitFor(hasContentDescription("Roll Manager")).performClick()
            createAndEdit(::presets) { runBlocking { local.rollHistory(inventor).first() }.size }
            press("Delete preset permanently"); press("Confirm permanent deletion of Survey standard")
            compose.waitUntil(15_000) { presets().size == 2 }
            assertTrue(presets().none { it.name == "Survey standard" })
            assertEquals(actor.baseAbilities, runBlocking { local.get(inventor) }!!.character.baseAbilities)
            assertEquals(listOf(historical), runBlocking { local.rollHistory(inventor).first() })
        } finally { activity.pause().stop().destroy(); db.close(); oldDb.close() }
    }

    @Test fun enrolledQueueKeepsOriginalConfigAndReadsRealEditedModesAfterCacheReplacement() {
        AscensionWorkerFixture.open(context).use { f ->
            fun rows(kind: String) = f.dm("get_cloud_state",buildJsonObject { put("kind",kind);put("limit",50) }).getValue("records").jsonArray.map { it.jsonObject }
            fun presets() = rows("roll_preset").map { r ->
                val data = r.getValue("data").jsonObject
                val entries = data.getValue("entries").jsonArray.map { it.jsonObject }
                Configured(r.cloudText("id"),r.cloudText("title"),entries.map { it.cloudText("key") to it.cloudText("mode") },
                    entries.any { e -> (e["contextTags"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == "pocket-workshop" } })
            }
            f.command("define_actor",buildJsonObject {
                put("actorId",inventor);put("name","Research fixture");put("role","player");put("level",5)
                put("rulesProfile","legacy_2014");putJsonObject("abilities") { Stat.entries.forEach { put(it.name,14) } }
            })
            val priorCheck = f.command("record_check", Json.parseToJsonElement(
                """{"actorId":"$inventor","rollId":"prior-check","check":{"kind":"ability","key":"INT","mode":"normal"},"evidence":{"origin":"player_physical","faces":[9]}}"""
            ).jsonObject)
            val historical = requireNotNull(f.record("prior-check"))
            val original = f.record(inventor)
            fun mount() = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { activity ->
                val initial = runBlocking { f.refresh() }
                activity.get().setContent {
                    val changes by f.client.changes.collectAsState()
                    var view by remember { mutableStateOf(initial) }
                    LaunchedEffect(changes) { f.client.workspace()?.let { view=it } }
                    AstravarTheme { CloudWorkspace(f.client,view) }
                }
            }
            val activity = mount()
            try {
                waitFor(hasContentDescription("Open Roll Manager")).performClick()
                createAndEdit(::presets) { rows("roll").size }
                val last = f.signal("observedDeviceCommand",buildJsonObject { put("operation","save_roll_preset") })
                val id = last.cloudText("commandId")
                assertEquals(f.receipt(id),f.signal("redeliverDeviceCommand",buildJsonObject { put("commandId",id) }))
            } finally { activity.pause().stop().destroy() }
            val saved = presets()
            f.resetCache()
            val fresh = mount()
            try {
                waitFor(hasContentDescription("Open Roll Manager")).performClick()
                waitFor(hasTestTag("roll-preset-selector")).performClick()
                val standard = saved.single { it.name == "Survey standard" }
                waitFor(hasTestTag("roll-preset-option-${standard.id}")).performClick()
                waitFor(hasText("1. Investigation")).assertExists()
                assertEquals(saved, presets()); assertEquals(original, f.record(inventor))
                assertEquals(listOf(historical), rows("roll"))
                val faceField = waitFor(hasText("Die faces in queue order, one roll per line") and hasSetTextAction())
                faceField.performScrollTo().performTextReplacement("12\n8\n14")
                press("Roll All"); waitFor(hasText("Copy All"))
                val batch = rows("roll_batch").single()
                val batchCommand = batch.getValue("data").jsonObject.cloudText("commandId")
                val originalBatchReceipt = f.receipt(batchCommand)
                val originalRolls = rows("roll")
                assertEquals(4,originalRolls.size)
                fun confirmLifecycle(operation: String): JsonObject {
                    waitFor(hasText("pending approval"))
                    val frozen = f.signal("observedDeviceCommand",buildJsonObject { put("operation",operation) })
                    val id = frozen.cloudText("commandId")
                    val pending = runBlocking { f.client.verify(id) }
                    assertEquals("PENDING_APPROVAL",pending.cloudText("status"))
                    val approved = f.signal("ownerVerify",buildJsonObject { put("commandId",id);put("digest",pending.getValue("digest")) })
                    assertEquals("COMMITTED",approved.cloudText("status"))
                    assertEquals(approved,f.receipt(id))
                    assertEquals(approved,runBlocking { f.client.verify(id) })
                    return approved
                }
                fun archived() = f.dm("get_cloud_archive",buildJsonObject { put("kind","roll_preset") }).getValue("records").jsonArray.map { it.jsonObject }
                press("Manage presets"); press("Delete preset permanently"); press("Confirm permanent deletion of Survey standard")
                waitFor(hasText("DEPENDENT_RECORDS_RETAINED",substring=true,ignoreCase=true))
                assertEquals(saved,presets())
                press("Cancel removal"); press("Archive preset"); press("Confirm archive of Survey standard")
                confirmLifecycle("archive_records")
                assertEquals("archived",archived().single { it.cloudText("id")==standard.id }.cloudText("life"))
                val dependencies=f.dm("get_cloud_archive",buildJsonObject { put("id",standard.id) }).getValue("dependencies").jsonObject
                assertTrue(dependencies.getValue("links").jsonArray.any { it.jsonObject.cloudText("relation")=="used_preset" })
                assertEquals(saved.filter { it.id != standard.id },presets())
                val unused = saved.single { it.name == "Second survey" }
                waitFor(hasTestTag("roll-preset-selector")).performScrollTo().performClick()
                waitFor(hasTestTag("roll-preset-option-${unused.id}")).performClick()
                press("Manage presets"); press("Delete preset permanently"); press("Confirm permanent deletion of Second survey")
                confirmLifecycle("permanently_delete_records")
                assertNull(f.record(unused.id)); assertTrue(archived().none { it.cloudText("id")==unused.id })
                assertEquals(saved.filter { it.id != standard.id && it.id != unused.id },presets())
                assertEquals(originalRolls,rows("roll"))
                assertEquals(batch,rows("roll_batch").single())
                assertEquals(originalBatchReceipt,f.receipt(batchCommand))
                assertEquals(priorCheck,f.receipt(priorCheck.cloudText("commandId")))
                assertEquals(original,f.record(inventor))
            } finally { fresh.pause().stop().destroy() }
        }
    }
    @Test fun mixedQueueAndSavedPresetFreezeOnlyApplicablePhysicalOrAppAidFacesWithoutRecasting() {
        AscensionWorkerFixture.open(context).use { f ->
            f.signal("seedCompanionAcceptance")
            val cast = f.command("cast_spell",Json.parseToJsonElement(
                """{"actorId":"ally","castId":"support-live","grantId":"aid-grant","level":1,"slotPoolId":"operator-slot","targets":["actor-one"]}"""
            ).jsonObject)
            val slot = f.record("operator-slot")
            val originalCast = f.record("support-live")
            val target = f.record("actor-one")
            fun results(op: String): JsonArray {
                val frozen = f.signal("observedDeviceCommand",buildJsonObject { put("operation",op) })
                val commandId = frozen.cloudText("commandId")
                val batchId = frozen.getValue("args").jsonObject.cloudText("batchId")
                val stored = requireNotNull(f.record(batchId)).getValue("data").jsonObject
                assertEquals(commandId,stored.cloudText("commandId"))
                val receipt = f.receipt(commandId)
                assertEquals("COMMITTED",receipt.cloudText("status"))
                assertEquals(receipt,f.signal("redeliverDeviceCommand",buildJsonObject { put("commandId",commandId) }))
                val values = stored.getValue("results").jsonArray
                for (entry in values.map { it.jsonObject }) {
                    val roll = requireNotNull(f.record(entry.cloudText("rollId"))).getValue("data").jsonObject
                    assertEquals(entry["total"],roll["total"]);assertEquals(entry["aidDice"],roll["aidDice"])
                }
                return values
            }
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val initial=runBlocking { f.refresh() }
            try {
                activity.get().setContent {
                    val changes by f.client.changes.collectAsState()
                    var snapshot by remember { mutableStateOf(initial) }
                    LaunchedEffect(changes) { f.client.workspace()?.let { snapshot=it } }
                    AstravarTheme { CloudRollManager(f.client,snapshot,"actor-one",true,{}) }
                }
                press("Saves")
                waitFor(hasContentDescription("Wisdom saving throw Selection, unselected")).performScrollTo().performClick()
                press("Abilities")
                waitFor(hasContentDescription("Dexterity check Selection, unselected")).performScrollTo().performClick()
                press("Roll Queue (2)")
                enter("Die faces in queue order, one roll per line","13\n8")
                val aidField = "1. Wisdom saving throw · actual Support d4"
                waitFor(hasText(aidField) and hasSetTextAction()).assertExists()
                assertTrue(compose.onAllNodes(hasText("Dexterity check · actual",substring=true)).fetchSemanticsNodes().isEmpty())
                val roll = waitFor(hasText("Roll All") and hasClickAction())
                roll.assertIsNotEnabled()
                enter(aidField,"5");roll.assertIsNotEnabled()
                assertEquals(0,f.dm("get_cloud_state",buildJsonObject { put("kind","roll_batch") }).getValue("records").jsonArray.size)
                enter(aidField,"3");press("Roll All");waitFor(hasText("Copy All"))
                val physical = results("record_roll_batch")
                val actual = physical.first().jsonObject
                assertEquals("save",actual.cloudText("kind"));assertEquals(13,actual.getValue("natural").jsonPrimitive.int)
                assertEquals(17,actual.getValue("total").jsonPrimitive.int)
                val bonus = actual.getValue("aidDice").jsonArray.single().jsonObject
                assertEquals("support-live",bonus.cloudText("castId"));assertEquals("player_physical",bonus.cloudText("origin"))
                assertEquals(3,bonus.getValue("face").jsonPrimitive.int)
                val plain = physical.last().jsonObject
                assertEquals(8,plain.getValue("natural").jsonPrimitive.int);assertFalse("aidDice" in plain)
                press("Queue");press("Save as preset");enter("New preset name","Saving and checking");press("Save new preset")
                waitFor(hasText("Committed and verified"))
                press("Roll in app");press("Roll All");waitFor(hasText("Copy All"))
                val digital=results("execute_roll_preset")
                val digitalSave=digital.first().jsonObject
                val die=digitalSave.getValue("aidDice").jsonArray.single().jsonObject
                assertEquals("app",die.cloudText("origin"));assertEquals("support-live",die.cloudText("castId"))
                assertTrue(die.getValue("face").jsonPrimitive.int in 1..4)
                assertEquals(digitalSave.getValue("beforeAid").jsonPrimitive.int+die.getValue("face").jsonPrimitive.int,digitalSave.getValue("total").jsonPrimitive.int)
                assertFalse("aidDice" in digital.last().jsonObject)
                assertEquals(slot,f.record("operator-slot"));assertEquals(originalCast,f.record("support-live"))
                assertEquals(target,f.record("actor-one"));assertEquals(cast,f.receipt(cast.cloudText("commandId")))
            } finally {activity.pause().stop().destroy()}
        }
    }

    @Test fun queuedPresetRecalculatesAfterEquipmentAndFormChangesAndCannotCrossActors() {
        AscensionWorkerFixture.open(context).use { f ->
            fun commitFixture(op:String, raw:String):JsonObject {
                val r=f.command(op,Json.parseToJsonElement(raw).jsonObject)
                assertEquals(op+" "+r,"COMMITTED",r.cloudText("status"))
                assertEquals(r,f.receipt(r.cloudText("commandId")));return r
            }
            for (id in listOf("scholar","other")) commitFixture("define_actor","""{"actorId":"$id","name":"$id","role":"player","rulesProfile":"revised_2024","level":3,"abilities":{"INT":12}}""")
            commitFixture("define_item_definition","""{"definitionId":"lens-def","name":"Research lens","category":"instrument","description":"Verified fixture","rarity":null,"stackable":false,"wearable":true,"handheld":false,"attunement":"none","source":"Test source"}""")
            commitFixture("acquire_item","""{"itemId":"lens","definitionId":"lens-def","ownerId":"scholar","quantity":1,"source":"Test source"}""")
            commitFixture("define_effect","""{"actorId":"scholar","effectId":"lens-check","name":"Reviewed lens bonus","target":"ability_check","key":"INT","operation":"add","amount":2,"sourceItemId":"lens","source":"Test source"}""")
            commitFixture("define_form","""{"actorId":"scholar","formId":"investigator","name":"Investigator","timing":"other","activationSeconds":0,"abilityOverrides":{"INT":18},"source":"Test source"}""")
            val config=commitFixture("save_roll_preset","""{"actorId":"scholar","presetId":"reused","name":"Researcher config","update":false,"entries":[{"kind":"ability","key":"INT","mode":"normal"}]}""")
            val initial=f.record("reused")
            val current=mutableStateOf(runBlocking{f.refresh()})
            val who=mutableStateOf("scholar")
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            fun refresh(){val s=runBlocking{f.refresh()};compose.runOnIdle{current.value=s}}
            fun openPreset(){ waitFor(hasTestTag("roll-preset-selector")).performScrollTo().performClick(); waitFor(hasTestTag("roll-preset-option-reused")).performScrollTo().performClick() }
            fun count()=f.dm("get_cloud_state",buildJsonObject{put("kind","roll")}).getValue("records").jsonArray.size
            fun expected(value:Int) {
                waitFor(hasText("1d20+"+value)).assertExists()
                val sheet=f.dm("get_cloud_actor_sheet",buildJsonObject{put("id","scholar")}).getValue("sheet").jsonObject
                assertEquals(value,sheet.getValue("checks").jsonObject.getValue("INT").jsonObject.getValue("value").jsonPrimitive.int)
            }
            try {
                activity.get().setContent{AstravarTheme{CloudRollManager(f.client,current.value,who.value,true,{})}}
                openPreset();expected(1);assertEquals(0,count())
                compose.runOnIdle{who.value="other"}
                waitFor(hasTestTag("roll-preset-selector")).performClick()
                waitFor(hasTestTag("roll-preset-option-template-research")).assertExists()
                compose.onAllNodesWithTag("roll-preset-option-reused").assertCountEquals(0)
                compose.runOnIdle{who.value="scholar"}
                openPreset()
                commitFixture("equip_item","""{"itemId":"lens","equipment":"worn","source":"Synthetic equip"}""");refresh();expected(3)
                commitFixture("change_form","""{"actorId":"scholar","formId":"investigator","elapsedSeconds":0,"willing":true}""");refresh();expected(6)
                assertEquals(0,count())
                commitFixture("equip_item","""{"itemId":"lens","equipment":"packed","source":"Synthetic removal"}""");refresh();expected(4)
                enter("Die faces in queue order, one roll per line","21")
                press("Roll All");assertEquals(0,count())
                enter("Die faces in queue order, one roll per line","16");press("Roll All")
                waitFor(hasText("(16) + 4 = 20",substring=true)).assertExists()
                val frozen=f.signal("observedDeviceCommand",buildJsonObject{put("operation","execute_roll_preset")})
                val id=frozen.cloudText("commandId");val receipt=f.receipt(id)
                assertEquals(receipt,f.signal("redeliverDeviceCommand",buildJsonObject{put("commandId",id)}))
                assertEquals(1,count());assertEquals(initial,f.record("reused"))
                f.resetCache();runBlocking{f.refresh()}
                assertEquals(initial,runBlocking{f.client.record("reused")});assertEquals(config,f.receipt(config.cloudText("commandId")))
                assertEquals(1,count())
            } finally {activity.pause().stop().destroy()}
        }
    }
}
