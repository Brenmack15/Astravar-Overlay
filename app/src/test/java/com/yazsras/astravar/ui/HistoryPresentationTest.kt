package com.yazsras.astravar.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yazsras.astravar.rules.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h1200dp")
@LooperMode(LooperMode.Mode.PAUSED)
class HistoryPresentationTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test(timeout=60000) fun twentyLargeRollsOpenNewestFirstAndFullDetailsRequireAnOverlay() {
        val seed=Campaign(onboarded=true,attuned=true,installedId="core",profile=Profile(baseDamageDiceCount=10),cores=listOf(Core("core","Core")))
        val shots=(1..100).map {i->
            val p=Engine.plan(seed,"roll-$i",Ability.SHATTER,elements=listOf(DamageType.LIGHTNING),point="Gate $i")
            val r=DiceRoller.roll(p,die={it},atMillis=i.toLong()).copy(coreName="Crystal $i")
            PendingShot(p,DiceRoller.resolution(p,r,targets=listOf(TargetSave("Target",false))),r)
        }
        val s=HistoryRetention.campaign(seed.copy(pending=shots))
        val showCurrent=mutableStateOf(false)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {if(showCurrent.value) RollResultCard(s.pending.last()) else CombatHistoryPage(s)}}
            compose.onNodeWithText("Recent rolls").assertIsDisplayed()
            compose.onNodeWithText("Crystal 100 · No payload").assertIsDisplayed()
            compose.onNodeWithText("Crystal 80 · No payload").assertDoesNotExist()
            compose.onAllNodes(hasText(" · Shatter",substring=true)).assertCountEquals(HistoryRetention.MAX_HISTORY_ENTRIES)
            compose.onAllNodes(hasText("Force blast:",substring=true)).assertCountEquals(0)
            compose.onNodeWithText("Crystal 100 · No payload").performClick()
            compose.onNodeWithText("Roll details").assertIsDisplayed()
            val fullSummary=shotSummary(s.pending.last())
            compose.onNodeWithText(fullSummary).assertExists()
            compose.onAllNodes(hasText("Force blast: 210d12",substring=true)).assertCountEquals(1)
            compose.onNodeWithText("Copy full result").performClick()
            compose.runOnIdle {
                val clipboard=activity.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(fullSummary,clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            compose.onNodeWithText("Close").performClick()
            compose.onAllNodes(hasText("Force blast:",substring=true)).assertCountEquals(0)
            compose.runOnIdle {showCurrent.value=true}
            compose.onNodeWithText("Latest result").assertIsDisplayed()
            compose.onNodeWithText("SAVE DC").assertIsDisplayed()
            compose.onNodeWithText("15").assertIsDisplayed()
            compose.onNodeWithText(s.pending.last().rolled!!.damage.values.sumOf {it.total}.toString()).assertIsDisplayed()
            compose.onNodeWithText("Latest result").performClick()
            compose.onNodeWithText("Roll details").assertIsDisplayed()
            compose.onNodeWithText(fullSummary).assertExists()
            assertEquals(2520,s.pending.last().rolled!!.damage.getValue("blast").total)
            assertEquals(210,s.pending.last().rolled!!.damage.getValue("blast").faces.size)
        } finally {activity.pause().stop().destroy()}
    }
}
