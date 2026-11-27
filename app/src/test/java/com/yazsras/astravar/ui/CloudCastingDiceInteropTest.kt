package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
@Config(sdk=[35],qualifiers="w440dp-h960dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudCastingDiceInteropTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val who = "caster"
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun AscensionWorkerFixture.data(id: String) = requireNotNull(record(id)).obj("data")
    private fun AscensionWorkerFixture.revision() = dm("get_cloud_state",obj("""{"limit":1}""")).getValue("revision")
    private fun AscensionWorkerFixture.seed(op: String, raw: String) = command(op,obj(raw)).also {
        assertEquals("$op $it","COMMITTED",it.cloudText("status"))
    }
    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try { compose.waitUntil(15000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: ComposeTimeoutException) { throw AssertionError("Missing $matcher\n"+compose.onAllNodes(isRoot()).printToString(maxDepth=8),e) }
        val scroll=compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction()))
        return if(scroll.fetchSemanticsNodes().isNotEmpty()) scroll.onFirst().performScrollTo() else compose.onAllNodes(matcher).onFirst()
    }
    private fun button(title: String) = find(hasText(title) and hasClickAction())
    private fun press(title: String) { button(title).performClick() }
    private fun field(title:String,value:String) { find(hasText(title) and hasSetTextAction()).performTextReplacement(value) }
    private fun close() { find(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog())).performClick() }

    @Test fun connectedProfileCanPrepareEditAndUseItsExactSourceThenCompletePlayerDiceAndReadDelegatedRecovery() {
        val f=AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>())
        f.use {
            f.seed("define_actor","""{"actorId":"$who","name":"Fixture mage","role":"player","level":3,"rulesProfile":"legacy_2014","maxHp":30,"currentHp":20,"baseAc":12,"temporaryHp":0,"gameSeconds":0,"abilities":{"STR":10,"DEX":12,"CON":12,"INT":16,"WIS":12,"CHA":10}}""")
            f.seed("set_skill_training","""{"actorId":"$who","skillId":"glyph-craft","name":"Glyph craft","ability":"INT","rank":1}""")
            fun pool(id:String,name:String,type:String,balance:String,max:Int,recovery:String,extra:String="") {
                f.seed("define_resource","""{"actorId":"$who","poolId":"$id","name":"$name","type":"$type","current":$balance,"maximum":$max,"recovery":"$recovery","source":"Test profile"$extra}""")
            }
            pool("slot-one","First circle","spell_slot","2",2,"long_rest",""" ,"slotLevel":1""")
            pool("special","Shared focus","charge","1",2,"none")
            pool("unmeasured","Unmeasured reserve","ordinary","null",3,"none")
            pool("recovery","Wizard Hit Dice","hit_die","2",3,"edition_hit_dice",""" ,"dieSides":8""")
            fun spell(id:String,name:String,level:Int,resolution:String) {
                f.seed("define_spell","""{"actorId":"$who","spellId":"$id","name":"$name","source":"Synthetic spell library","baseLevel":$level,"ritual":false,"concentration":false,"resolution":"$resolution","timing":"action","components":[]}""")
            }
            spell("rune","Measured rune",1,"attack")
            spell("beacon","Beacon mark",6,"attack")
            f.seed("grant_spell","""{"actorId":"$who","spellId":"rune","grantId":"rune-known","source":"Study notes","route":"slot","levels":[1],"preparation":"known","ability":"INT"}""")
            f.seed("grant_spell","""{"actorId":"$who","spellId":"beacon","grantId":"beacon-grant","source":"Survey focus","route":"limited","levels":[6],"preparation":"not_required","fixedHit":7,"resourceId":"special","cost":1}""")
            f.seed("record_roll_batch","""{"actorId":"$who","batchId":"requests","entries":[{"rollId":"player-save","check":{"kind":"save","key":"WIS","mode":"advantage"}}]}""")

            var snapshot by mutableStateOf(runBlocking { f.refresh() })
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent {
                    val changed by f.client.changes.collectAsState()
                    LaunchedEffect(changed) { f.client.workspace()?.let { snapshot=it } }
                    AstravarTheme { Surface(Modifier.fillMaxSize(),color=RebuildPalette.Canvas) { CloudWorkspace(f.client,snapshot) } }
                }
                find(hasContentDescription("Hit points, 20 of 30"))
                press("Spells"); button("Cast").assertIsNotEnabled()
                press("Manage spells"); press("Prepare"); find(hasText("Spell preparation"))
                assertEquals("prepared",f.data("rune-known").cloudText("preparation"));assertEquals(2,f.data("slot-one").number("current"));close()
                press("Manage spells");press("Edit casting source · Survey focus")
                field("Source","Calibrated survey focus");press("Submit");find(hasText("Transaction result"));close()
                assertEquals("Calibrated survey focus",f.data("beacon-grant").cloudText("source"))
                find(hasText("Calibrated survey focus · not required"));press("Use");press("Confirm cast")
                find(hasText("Casting result"))
                val castCommand=f.signal("observedDeviceCommand",obj("""{"operation":"cast_spell"}"""))
                val castId=castCommand.obj("args").cloudText("castId")
                assertEquals(6,f.data(castId).number("level"))
                assertEquals(0,f.data("special").number("current"));assertEquals(2,f.data("slot-one").number("current"))
                val beforeHit=f.revision()
                find(hasContentDescription("Beacon mark spell attack +7") and hasAnyAncestor(isDialog())).performTouchInput { longClick() }
                press("Advantage");press("Physical dice");field("Actual d20 faces","3,17")
                assertEquals(beforeHit,f.revision());assertEquals(JsonNull,f.data(castId)["hit"])
                press("Record spell attack");find(hasText("Beacon mark attack:",substring=true))
                val hit=f.data(castId).obj("hit")
                assertEquals(24,hit.number("total"));assertEquals("player_physical",hit.cloudText("origin"))
                assertEquals(listOf(false,true),hit.getValue("faces").jsonArray.map { it.jsonObject.getValue("kept").jsonPrimitive.boolean })
                assertEquals(0,f.data("special").number("current"));close()
                button("Use").assertIsNotEnabled()
                press("Resources");find(hasContentDescription("0/2 remaining"));find(hasContentDescription("?/3 remaining"))
                press("History");val beforePlayer=f.revision()
                press("Enter requested dice · Wisdom Saving Throw");press("Player digital dice");field("Actual die faces","5,17")
                assertEquals(beforePlayer,f.revision());assertFalse(f.data("player-save").containsKey("total"))
                press("Record requested result");find(hasText("Wisdom Saving Throw:",substring=true))
                assertEquals("player_digital",f.data("player-save").cloudText("origin"));assertEquals(18,f.data("player-save").number("total"))
                assertEquals("resolved",f.data("requests").cloudText("status"));close()
                val delegation=f.command("set_dice_delegation",obj("""{"actorId":"$who","category":"hit_dice","enabled":true,"source":"Explicit fixture agreement"}"""))
                assertEquals("PENDING_APPROVAL",delegation.cloudText("status"))
                f.signal("ownerVerify",buildJsonObject{put("commandId",delegation.getValue("commandId"));put("digest",delegation.getValue("digest"))})
                val denied=runCatching {
                    f.command("record_check",obj("""{"actorId":"$who","rollId":"undelegated","check":{"kind":"skill","key":"perception","mode":"normal"},"evidence":{"origin":"delegated","faces":[9]}}"""))
                }
                assertTrue(denied.exceptionOrNull()?.message.orEmpty().contains("DICE_DELEGATION_REQUIRED"));assertNull(f.record("undelegated"))
                val rest=f.seed("complete_rest","""{"actorId":"$who","kind":"short_rest","confirmed":true,"elapsedSeconds":3600,"hitDice":[{"poolId":"recovery","faces":[5],"origin":"delegated"}]}""")
                assertEquals(rest,f.receipt(rest.cloudText("commandId")))
                runBlocking { f.refresh() }
                find(hasContentDescription("Hit points, 26 of 30"))
                val report=f.data(rest.obj("receipt").obj("outcome").cloudText("reportId"))
                val die=report.getValue("hitDice").jsonArray.single().jsonObject
                assertEquals("delegated",die.cloudText("origin"));assertEquals(5,die.number("face"))
                assertEquals(1,f.data("recovery").number("current"));assertEquals(JsonNull,f.data("unmeasured")["current"])
                assertEquals(0,f.data("special").number("current"));assertEquals(2,f.data("slot-one").number("current"))
                press("Spells");press("Cast");press("Confirm cast");find(hasText("Casting result"))
                val ordinary=f.signal("observedDeviceCommand",obj("""{"operation":"cast_spell"}""")).obj("args").cloudText("castId")
                find(hasContentDescription("Measured rune spell attack +5") and hasAnyAncestor(isDialog())).performClick()
                find(hasText("Measured rune attack:",substring=true))
                val oneTap=f.data(ordinary).obj("hit")
                val actual=oneTap.getValue("faces").jsonArray.single().jsonObject.number("value")
                assertTrue(actual in 1..20);assertEquals(actual+5,oneTap.number("total"));assertEquals("app",oneTap.cloudText("origin"))
                assertEquals(JsonNull,f.data(ordinary)["damage"])
                val link=f.signal("observedDeviceCommand",obj("""{"operation":"link_cast_roll"}""")).cloudText("commandId")
                assertEquals(f.receipt(link),f.signal("redeliverDeviceCommand",obj("""{"commandId":"$link"}""")))
                assertEquals(1,f.data("slot-one").number("current"));assertEquals(0,f.data("special").number("current"))
                close();press("Sheet")
                fun quick(control:String,kind:String,key:String,modifier:Int) {
                    val previous=f.revision().jsonPrimitive.long
                    find(hasContentDescription(control)).performClick()
                    find(hasText("Transaction result"))
                    val cmd=f.signal("observedDeviceCommand",obj("""{"operation":"record_check"}"""))
                    val detail=f.data(cmd.obj("args").cloudText("rollId"))
                    val face=detail.getValue("faces").jsonArray.single().jsonObject.number("value")
                    assertTrue(face in 1..20);assertEquals("app",detail.cloudText("origin"));assertEquals(kind,detail.cloudText("kind"))
                    assertEquals(key,detail.cloudText("key"));assertEquals(face+modifier,detail.number("total"))
                    assertEquals(1L,f.revision().jsonPrimitive.long-previous)
                    close()
                }
                quick("Initiative +1","initiative","initiative",1)
                quick("Dexterity ability check +1","ability","DEX",1)
                quick("Dexterity saving throw +1, not proficient","save","DEX",1)
                quick("Roll Investigation +3","skill","investigation",3)
                quick("Roll Glyph craft +5","skill","glyph-craft",5)
            } finally { activity.pause().stop().destroy() }
        }
    }
}
