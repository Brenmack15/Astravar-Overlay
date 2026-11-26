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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w440dp-h1050dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudPlayableToolsInteropTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val actor = "fixture-scholar"
    private fun define(id: String) = buildJsonObject {
        put("actorId", id); put("name", id); put("role", "player")
        put("rulesProfile", "legacy_2014"); put("level", 2); put("baseAc", 13)
        put("currentHp", 22); put("maxHp", 22); put("temporaryHp", 0)
        putJsonObject("abilities") { put("INT", 12); put("STR", 14) }
    }
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun field(text: String, value: String) {
        val field = hasText(text) and hasSetTextAction()
        compose.waitUntil(20000) { compose.onAllNodes(field).fetchSemanticsNodes().size == 1 }
        compose.onNode(field).performScrollTo().performTextReplacement(value)
    }
    private fun physical(vararg faces: Int) = buildJsonObject {
        put("origin", "player_physical"); put("faces", JsonArray(faces.map(::JsonPrimitive)))
    }

    @Test fun actionsBuilderCreatesEditsAndResolvesTheSameTargetBackedAttack() = runBlocking {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { f ->
            listOf(actor, "fixture-target").forEach { assertEquals("COMMITTED", f.command("define_actor", define(it)).cloudText("status")) }
            var snapshot by mutableStateOf(f.refresh())
            val receipts = mutableListOf<JsonObject>()
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        CloudActorRecords("Actions", f.client, snapshot, actor, true, { _,_ -> }, {}, { operation, args ->
                            runBlocking {
                                val result = f.client.submit(operation, args, snapshot)
                                receipts += result
                                snapshot = requireNotNull(f.client.workspace())
                            }
                        })
                    }
                } }
                tap("Attacks"); tap("Create Attack")
                field("Attack name", "Test practice ray"); field("Source", "Isolated engineering fixture")
                compose.onNodeWithText("Resolution").performClick()
                tap("Fixed hit modifier"); field("Hit modifier", "4")
                compose.onNodeWithText("Damage").performClick()
                tap("Add component"); field("Component name", "Impact")
                tap("Default damage type · Choose"); compose.onNodeWithText("Force").performClick()
                field("Dice count", "1"); field("Die size", "6"); field("Signed flat bonus", "2")
                compose.onNodeWithText("Review & save").performClick(); compose.onNodeWithText("Save attack").performClick()
                compose.waitUntil(20000) { receipts.size == 1 }
                assertEquals("COMMITTED", receipts.single().cloudText("status"))
                val attack = f.client.records(kinds=setOf("attack"), actorId=actor).single()
                val id = attack.cloudText("id")
                assertEquals(attack, f.record(id))
                tap("Attacks"); tap("Review Source Attacks"); tap("Edit Test practice ray")
                field("Attack name", "Renamed practice ray"); compose.onNodeWithText("Review & save").performClick(); compose.onNodeWithText("Save attack").performClick()
                compose.waitUntil(20000) { receipts.size == 2 }
                receipts.forEach { assertEquals(it, f.receipt(it.cloudText("commandId"))) }
                val edited = requireNotNull(f.record(id))
                assertEquals("Renamed practice ray", edited.cloudText("title"))
                assertEquals(attack.getValue("data").jsonObject.getValue("components"), edited.getValue("data").jsonObject.getValue("components"))
                val component = edited.getValue("data").jsonObject.getValue("components").jsonArray.single().jsonObject.cloudText("id")
                val attempt = f.client.submit("resolve_attack", buildJsonObject {
                    put("actorId", actor); put("attackId", id); put("attemptId", "native-attempt"); putJsonArray("targets") { add("fixture-target") }
                    put("mode", "normal"); put("hit", physical(15)); put("includeDamage", true)
                    putJsonArray("damage") { addJsonObject { put("id", component); put("evidence", physical(4)) } }
                }, snapshot)
                assertEquals("COMMITTED", attempt.cloudText("status"))
                assertEquals(attempt, f.receipt(attempt.cloudText("commandId")))
                assertEquals(requireNotNull(f.client.record("native-attempt")), f.record("native-attempt"))
                val target = requireNotNull(f.record("fixture-target")).getValue("data").jsonObject
                assertEquals(22, target.getValue("currentHp").jsonPrimitive.int)
                val applied = f.command("resolve_attack_target", buildJsonObject {
                    put("actorId", actor); put("attemptId", "native-attempt"); put("targetActorId", "fixture-target")
                    put("source", "Observed isolated target")
                })
                assertEquals("COMMITTED", applied.cloudText("status"))
                assertEquals(applied, f.receipt(applied.cloudText("commandId")))
                f.refresh()
                assertEquals(16, requireNotNull(f.client.record("fixture-target")).getValue("data").jsonObject.getValue("currentHp").jsonPrimitive.int)
            } finally { activity.pause().stop().destroy() }
        }
    }

    @Test fun clearedDeviceReloadsPresetAndUsesTheCurrentModifierOnPhysicalDice() = runBlocking {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { f ->
            assertEquals("COMMITTED", f.command("define_actor", define(actor)).cloudText("status"))
            val first = f.refresh()
            val preset = f.client.submit("save_roll_preset", buildJsonObject {
                put("actorId", actor); put("presetId", "fixture-preset"); put("name", "Scholar test"); put("update", false)
                putJsonArray("entries") { addJsonObject { put("kind", "ability"); put("key", "INT"); put("mode", "normal") } }
            }, first)
            assertEquals("COMMITTED", preset.cloudText("status"))
            assertEquals(preset, f.receipt(preset.cloudText("commandId")))
            val oldPreset = requireNotNull(f.record("fixture-preset"))
            f.resetCache()
            assertNull(f.client.workspace())
            assertEquals("COMMITTED", f.command("set_actor_ability", buildJsonObject {
                put("actorId", actor); put("ability", "INT"); put("score", 18)
            }).cloudText("status"))
            val restored = f.refresh()
            assertEquals(oldPreset, f.client.record("fixture-preset"))
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme { CloudRollManager(f.client, restored, actor, true, {}) } }
                compose.onNodeWithText("Presets").performClick()
                compose.waitUntil(20000) { compose.onAllNodesWithText("Scholar test · 1").fetchSemanticsNodes().isNotEmpty() }
                tap("Scholar test · 1")
                assertTrue(f.client.records(kinds=setOf("roll"), actorId=actor).isEmpty())
                compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("16")
                tap("Roll All")
                compose.waitUntil(20000) { compose.onAllNodesWithText("(16) + 4 = 20", substring=true).fetchSemanticsNodes().isNotEmpty() }
                val row = f.client.records(kinds=setOf("roll"), actorId=actor).single()
                assertEquals(row, f.record(row.cloudText("id")))
                assertEquals(20, row.getValue("data").jsonObject.getValue("total").jsonPrimitive.int)
                tap("Copy All")
                assertEquals(1, f.dm("get_cloud_state", buildJsonObject { put("kind", "roll_batch") }).getValue("records").jsonArray.size)
                assertEquals(oldPreset, f.record("fixture-preset"))
            } finally { activity.pause().stop().destroy() }
        }
    }
}
