package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudCharacterLayoutInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test @Config(sdk=[35], qualifiers="w360dp-h860dp-xhdpi")
    fun smallerPhoneKeepsTwoRowsAndSeparatesTheSaveFromTheAbility() = checkLayout(1.3f)

    @Test @Config(sdk=[35], qualifiers="w840dp-h1100dp-xhdpi")
    fun tabletWithLargeTextMaintainsCardAndHealthControls() = checkLayout(2f)

    private fun checkLayout(scale: Float) = runBlocking {
        val actor = "layout-actor"
        val keys = listOf("STR","DEX","CON","INT","WIS","CHA")
        val f = AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>())
        f.use {
            val args = buildJsonObject {
                put("actorId", actor); put("name", "Fixture scholar"); put("role", "player"); put("rulesProfile", "legacy_2014")
                put("level", 2); put("maxHp", 20); put("currentHp", 20); put("temporaryHp", 3); put("baseAc", 15)
                putJsonObject("abilities") { keys.forEach { put(it,14) } }; putJsonArray("saveProficiencies") { add("STR") }
            }
            assertEquals("COMMITTED", f.command("define_actor", args).cloudText("status"))
            val toolName="Clockwork mechanism calibration"
            assertEquals("COMMITTED", f.command("set_skill_training", buildJsonObject {
                put("actorId",actor); put("skillId","fixture-custom-calibration"); put("ability","INT"); put("name",toolName); put("rank",1)
            }).cloudText("status"))
            var snapshot by mutableStateOf(f.refresh())
            val actions = mutableListOf<String>()
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        AstravarTheme { Surface(Modifier.fillMaxSize(),color=RebuildPalette.Canvas) {
                            Column(Modifier.fillMaxSize()) { CloudActorWorkspace(f.client,snapshot,true,actor,{}, { op,_ -> actions+=op }, {}, { op,_ -> actions+=op }) }
                        } }
                    }
                }
                fun exact(description: String) {
                    compose.waitUntil(20000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
                }
                exact("Hit points, 20 of 20, 3 temporary hit points")
                exact("Strength saving throw +4, proficient")
                if (System.getProperty("astravar.screenshots") != null) {
                    compose.runOnIdle { invalidateNativeCaptureWindows() }
                    compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                    val filename = if (scale == 2f) "ascension-connected-tablet-large-text" else "ascension-connected-phone-large-text"
                    compose.runOnIdle { captureNativeTestWindows(filename, activity.get()) }
                }
                val cards = keys.map { compose.onNodeWithTag("ability-card-$it",useUnmergedTree=true).getUnclippedBoundsInRoot() }
                cards.chunked(3).forEach { row ->
                    assertTrue(row[0].left < row[1].left && row[1].left < row[2].left)
                    assertEquals(row[0].top, row[1].top); assertEquals(row[1].top, row[2].top)
                }
                assertTrue(cards[0].bottom <= cards[3].top)
                val small = compose.onNodeWithTag("Strength saving throw tile",useUnmergedTree=true).getUnclippedBoundsInRoot()
                val large = compose.onNodeWithTag("Strength ability check tile",useUnmergedTree=true).getUnclippedBoundsInRoot()
                assertTrue("Save $small should be smaller than modifier $large", small.right - small.left < large.right - large.left && small.bottom - small.top < large.bottom - large.top)
                compose.onNodeWithContentDescription("Strength saving throw +4, proficient").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                compose.onNodeWithContentDescription("Strength ability check +2").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                compose.onNodeWithText("STRENGTH",useUnmergedTree=true).performScrollTo().performTouchInput { click(center) }
                compose.waitUntil(20000) { compose.onAllNodesWithText("Edit base score").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Edit base score").assertExists()
                assertTrue(actions.isEmpty())
                compose.onNodeWithText("Close").performClick()
                // The passive inspector uses the same text, so target the active row through its matching roll control.
                fun checkSkillRow(key:String,name:String,modifier:String,custom:Boolean) {
                    val roll=compose.onNodeWithContentDescription("Roll $name $modifier")
                    roll.performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    val nameTarget=compose.onNodeWithTag("skill-name-$key").assertTextEquals(name)
                    nameTarget.assertHeightIsAtLeast(48.dp)
                    val labelBounds=nameTarget.getUnclippedBoundsInRoot()
                    val rollBounds=roll.getUnclippedBoundsInRoot()
                    assertTrue(labelBounds.right <= rollBounds.left && labelBounds.top < rollBounds.bottom && labelBounds.bottom > rollBounds.top)
                    nameTarget.performClick()
                    compose.onNodeWithText("Roll options · $name").assertExists()
                    compose.onNodeWithText("Edit skill training").assertExists()
                    compose.onAllNodesWithText("Remove custom skill").assertCountEquals(if(custom)1 else 0)
                    compose.onNodeWithText("Close").performClick()
                }
                checkSkillRow("investigation","Investigation","+2",false)
                checkSkillRow("fixture-custom-calibration",toolName,"+4",true)
                assertTrue(actions.isEmpty())
                for ((amount, expected) in listOf(8 to 15, 15 to 0)) {
                    val before = snapshot.version.revision
                    assertEquals("COMMITTED", f.command("apply_hp_change",buildJsonObject {
                        put("actorId",actor); put("kind","damage"); put("amount",amount)
                    }).cloudText("status"))
                    snapshot = f.refresh()
                    assertEquals(before + 1, snapshot.version.revision)
                    exact("Hit points, $expected of 20")
                    compose.onAllNodesWithText("(0)").assertCountEquals(0)
                    compose.onNodeWithContentDescription("Hit points, $expected of 20").performClick()
                    compose.onNodeWithText("Damage").assertExists()
                    compose.onNodeWithText("Healing").assertExists()
                    compose.onNodeWithText("Close").performClick()
                }
                assertTrue(actions.isEmpty())
                assertTrue(f.dm("get_cloud_state",buildJsonObject { put("kind","roll") }).getValue("records").jsonArray.isEmpty())
            } finally { activity.pause().stop().destroy() }
        }
    }
}
