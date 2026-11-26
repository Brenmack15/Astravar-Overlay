package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
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
class CloudSpellRestUiTest {
    @get:Rule val compose=createAstravarComposeRule()

    @Test fun inspectAndCloseCostNothingAndSecondLevelUsesMatchingSlot() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val calls=mutableListOf<Pair<String,JsonObject>>()
        val rows=listOf(1,2).map { level -> buildJsonObject {
            put("grantId","light-grant");put("spellId","light");put("name","Fixture Light");put("level",level)
            put("baseLevel",1);put("source","Fixture source");put("route","slot");put("resolution","automatic")
            put("preparation","prepared");put("description","Measured light");put("timing","action")
            putJsonArray("eligibleSlotIds"){add("slot-$level")}
            putJsonArray("components"){addJsonObject{put("name","Beam");put("type","radiant");put("dice",level);put("sides",6);put("flat",0)}}
        } }
        try {
            activity.get().setContent { AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudSpellRows("fixture",rows,emptyList(),emptyList(),true,{_,_->}, {name,args->calls.add(name to args)})
            } } }
            compose.onAllNodesWithText("Fixture Light")[1].performClick()
            compose.onNodeWithText("Measured light").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            assertTrue(calls.isEmpty())
            compose.onAllNodesWithText("Cast")[1].performClick()
            val (op,cast)=calls.single()
            assertEquals("cast_spell", op)
            assertEquals(2,cast.getValue("level").jsonPrimitive.int)
            assertEquals("slot-2",cast.getValue("slotPoolId").jsonPrimitive.content)
            assertEquals("light-grant",cast.getValue("grantId").jsonPrimitive.content)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun inspectingResourceShowsVerifiedHistoryAndOnlyAnExplicitActionOpensSpending() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val calls = mutableListOf<Pair<String,JsonObject>>()
        val pool = buildJsonObject {
            put("id","relay"); put("title","Relay charges"); put("revision",2)
            putJsonObject("data") { put("type","charge"); put("current",1); put("maximum",3); put("recovery","none"); put("source","Fixture module") }
        }
        try {
            activity.get().setContent { AstravarTheme { Column {
                CloudResourceManagement("fixture",listOf(pool),true,{operation,args->calls.add(operation to args)}) { id,after ->
                    assertEquals("relay",id); assertNull(after)
                    buildJsonObject { putJsonArray("events"){addJsonObject { put("operation","spend_resource");put("evidenceAvailable",true)
                        put("before",3);put("after",1);put("revision",2) }} }
                }
            } } }
            compose.onNodeWithText("Relay charges").performClick()
            compose.onNodeWithText("spend resource · 3 → 1 · revision 2").assertExists()
            assertTrue(calls.isEmpty())
            compose.onNodeWithText("Spend uses").performClick()
            assertEquals("spend_resource",calls.single().first)
            assertEquals("relay",calls.single().second.getValue("poolId").jsonPrimitive.content)
            assertFalse(calls.single().second.containsKey("amount"))
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun cancelRestDoesNothingAndPhysicalHitDieIsBoundToConfirmedRest() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val calls=mutableListOf<Pair<String,JsonObject>>()
        val actor=buildJsonObject { put("currentHp",11);put("maxHp",18);put("rulesProfile","legacy_2014") }
        val sheet=buildJsonObject { putJsonObject("abilities"){putJsonObject("CON"){put("modifier",2)}} }
        val pool=buildJsonObject {put("id","hd");put("title","Fixture Hit Dice");putJsonObject("data"){
            put("type","hit_die");put("dieSides",8);put("maximum",3);put("current",2);put("recovery","edition_hit_dice")
        }}
        try {
            activity.get().setContent { AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudRestPlan("fixture",actor,sheet,listOf(pool),true){name,args->calls.add(name to args)}
            } } }
            compose.onNodeWithText("Complete short rest").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(calls.isEmpty())
            compose.onNodeWithText("+").performScrollTo().performClick()
            compose.onNodeWithText("Actual d8 faces (1)").performScrollTo().performTextInput("5")
            compose.onNodeWithText("Complete short rest").performScrollTo().performClick()
            assertTrue(calls.isEmpty())
            compose.onNodeWithText("Complete rest").performClick()
            val (op,rest)=calls.single()
            assertEquals("complete_rest",op)
            val die=rest.getValue("hitDice").jsonArray.single().jsonObject
            assertEquals("player_physical",die.getValue("origin").jsonPrimitive.content)
            assertEquals(listOf(5),die.getValue("faces").jsonArray.map{it.jsonPrimitive.int})
            assertEquals(3600,rest.getValue("elapsedSeconds").jsonPrimitive.int)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun copiedReportPreservesActualHealingAndUnknownPool() {
        val report=Json.parseToJsonElement("""{"kind":"short_rest","actorId":"actor","status":"completed","beforeHp":9,"afterHp":13,"actualHealing":4,"elapsedSeconds":3600,"hitDice":[{"poolId":"hd","origin":"player_physical","sides":8,"face":6,"modifier":2,"actual":4}],"remainingHitDice":[{"poolId":"hd","remaining":null}],"commandId":"fixture-rest"}""").jsonObject
        val text=cloudGameplayReport(report,mapOf("actor" to "Fixture actor","hd" to "Hit Dice"))
        assertTrue(text.contains("9 → 13 · healed 4"))
        assertTrue(text.contains("d8 · (6) +2"))
        assertTrue(text.contains("Unknown Hit Dice remaining"))
        assertTrue(text.contains("fixture-rest"))
    }
}
