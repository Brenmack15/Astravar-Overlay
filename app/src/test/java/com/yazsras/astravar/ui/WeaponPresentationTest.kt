package com.yazsras.astravar.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeaponPresentationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun seed()=Campaign(onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Resonant crystal")),profile=Profile(baseDamageDiceCount=4,elementalSelectorDiceCount=2))
    private fun capture(name:String,activity:ComponentActivity) {
        // Opt-in review artifacts from the real composables, never included in the APK.
        val dir=System.getProperty("astravar.screenshots") ?: return
        File(dir).mkdirs()
        compose.waitForIdle()
        compose.runOnIdle {
            val view=activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test(timeout=60000) fun weaponHasFourDestinationsReachableRollAndNoArmorClass(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actions=Actions(Repository(db,scope),scope)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation("Weapon") {}}) {padding->
                Box(Modifier.padding(padding)) {Dashboard(seed(),actions,{},{},{},Preferences(context),OverlayPrefs(mode=Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING)))}
            }}}
            compose.onNodeWithText("Roll attack").assertIsDisplayed().assertIsEnabled()
            weaponDestinations.forEach {compose.onNodeWithText(it).assertIsDisplayed()}
            compose.onNodeWithText("4d12+5").assertIsDisplayed()
            compose.onNodeWithText("4d12+5 Force + 2d8 Lightning").assertIsDisplayed()
            compose.onNodeWithText("Target Armor Class (optional)").assertDoesNotExist()
            compose.onNodeWithText("Combat").assertDoesNotExist()
            capture("weapon",activity.get())
            compose.onNodeWithText("Shot options ▾").performScrollTo().performClick()
            compose.onNodeWithText("Distance in feet").performScrollTo().assertIsDisplayed()
            compose.onAllNodes(hasText("Armor Class",substring=true)).assertCountEquals(0)
            compose.onNodeWithText("Roll attack").assertIsDisplayed()
        } finally {activity.pause().stop().destroy();scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=60000) fun resultsSeparatePotentialDamageAndShowOnlyActualNaturalIndicators() {
        val s=seed();val p=Engine.plan(s,"shot",Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING))
        fun shot(natural:Int):PendingShot {
            val r=DiceRoller.roll(p,die={if(it==20) natural else 3}).copy(coreName="Resonant crystal")
            return PendingShot(p,DiceRoller.resolution(p,r),r)
        }
        val current=mutableStateOf(shot(16));val history=mutableStateOf(false)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation(if(history.value) "History" else "Weapon") {}}) {padding->Box(Modifier.padding(padding)) {
                if(history.value) CombatHistoryPage(s.copy(pending=listOf(shot(16).copy(plan=p.copy(id="ordinary")),shot(20).copy(plan=p.copy(id="critical")),shot(1).copy(plan=p.copy(id="miss"))))) else Page {RollResultCard(current.value)}
            }}}}
            compose.onNodeWithText("24").assertIsDisplayed()
            compose.onNodeWithText("23 POTENTIAL DAMAGE").assertIsDisplayed()
            compose.onNodeWithText("HIT").assertDoesNotExist();compose.onNodeWithText("MISS").assertDoesNotExist()
            capture("result",activity.get())
            compose.runOnIdle {current.value=shot(20)}
            compose.onNodeWithText("CRITICAL HIT").assertIsDisplayed();compose.onNodeWithText("41 POTENTIAL DAMAGE").assertExists()
            capture("critical",activity.get())
            compose.runOnIdle {current.value=shot(1)}
            compose.onNodeWithText("NATURAL 1 — MISS").assertIsDisplayed();compose.onNodeWithText("0 DAMAGE").assertIsDisplayed()
            compose.runOnIdle {history.value=true}
            compose.onNodeWithText("Recent rolls").assertIsDisplayed()
            capture("history",activity.get())
        } finally {activity.pause().stop().destroy()}
    }
    @Test(timeout=60000) fun coresUseActualNamesAndKeepSetupControlsCollapsed(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actions=Actions(Repository(db,scope),scope)
        val s=seed().copy(cores=seed().cores+Core("spare","Spare resonant core",exhaustedUntil=600))
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation("Cores & Payloads") {}}) {padding->Box(Modifier.padding(padding)) {CoresPage(s,actions)}}}}
            compose.onNodeWithText("Resonant crystal").assertIsDisplayed()
            compose.onNodeWithText("EQUIPPED").assertIsDisplayed();compose.onNodeWithText("READY").assertIsDisplayed()
            compose.onNodeWithText("UNAVAILABLE").assertIsDisplayed()
            compose.onNodeWithText("Name of a completed core you actually own").assertDoesNotExist()
            compose.onNodeWithText("Storm Core").assertDoesNotExist()
            capture("cores",activity.get())
        } finally {activity.pause().stop().destroy();scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
}
