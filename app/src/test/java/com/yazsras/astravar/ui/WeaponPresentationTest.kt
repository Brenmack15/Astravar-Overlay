package com.yazsras.astravar.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.savePresentationCapture
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeaponPresentationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun seed()=Campaign(onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Resonant crystal")),profile=Profile(baseDamageDiceCount=4,elementalSelectorDiceCount=2))
    private fun capture(name:String,activity:ComponentActivity) {
        // Opt-in review artifacts from the real composables, never included in the APK.
        if(System.getProperty("astravar.screenshots")==null) return
        compose.waitForIdle()
        compose.runOnIdle {
            val view=activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {view.draw(android.graphics.Canvas(bitmap));savePresentationCapture(bitmap,name)} finally {bitmap.recycle()}
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
            listOf("Normal","Advantage","Disadvantage").forEach {compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)}
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
    @Test(timeout=60000) fun resultsSeparatePotentialDamageAndShowOnlyActualNaturalIndicators(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actions=Actions(Repository(db,scope),scope)
        val s=seed();val p=Engine.plan(s,"shot",Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING))
        fun shot(natural:Int):PendingShot {
            val r=DiceRoller.roll(p,die={if(it==20) natural else 3}).copy(coreName="Resonant crystal")
            return PendingShot(p,DiceRoller.resolution(p,r),r)
        }
        val current=mutableStateOf(shot(16));val history=mutableStateOf(false)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation(if(history.value) "History" else "Weapon") {}}) {padding->Box(Modifier.padding(padding)) {
                if(history.value) CombatHistoryPage(s.copy(pending=listOf(shot(16).copy(plan=p.copy(id="ordinary")),shot(20).copy(plan=p.copy(id="critical")),shot(1).copy(plan=p.copy(id="miss"))))) else Dashboard(s.copy(pending=listOf(current.value)),actions,{},{},{},Preferences(context),OverlayPrefs(mode=Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING)))
            }}}}
            compose.onNodeWithText("24").assertIsDisplayed()
            compose.onNodeWithText("23 POTENTIAL DAMAGE").assertIsDisplayed()
            compose.onNodeWithText("Roll again").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("HIT").assertDoesNotExist();compose.onNodeWithText("MISS").assertDoesNotExist()
            capture("result",activity.get())
            compose.runOnIdle {current.value=shot(20)}
            compose.onNodeWithText("CRITICAL HIT").assertIsDisplayed();compose.onNodeWithText("41 POTENTIAL DAMAGE").assertExists()
            capture("critical",activity.get())
            compose.runOnIdle {current.value=shot(1)}
            compose.onNodeWithText("NATURAL 1 — MISS").assertIsDisplayed();compose.onNodeWithText("0 DAMAGE").assertIsDisplayed()
            capture("natural1",activity.get())
            compose.runOnIdle {history.value=true}
            compose.onNodeWithText("Recent rolls").assertIsDisplayed()
            capture("history",activity.get())
        } finally {activity.pause().stop().destroy();scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=60000) fun coresUseActualNamesAndKeepSetupControlsCollapsed(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actions=Actions(Repository(db,scope),scope)
        val s=seed().copy(cores=seed().cores+Core("spare","Spare resonant core",exhaustedUntil=600))
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation("Cores & Payloads") {}}) {padding->Column(Modifier.padding(padding)) {InventoryTabs("Cores") {};CoresPage(s,actions)}}}}
            compose.onNodeWithText("Resonant crystal").assertIsDisplayed()
            compose.onNodeWithText("EQUIPPED").assertIsDisplayed();compose.onNodeWithText("READY").assertIsDisplayed()
            compose.onNodeWithText("UNAVAILABLE").assertIsDisplayed()
            compose.onNodeWithText("Name of a completed core you actually own").assertDoesNotExist()
            compose.onNodeWithText("Storm Core").assertDoesNotExist()
            capture("cores",activity.get())
        } finally {activity.pause().stop().destroy();scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=60000) fun storedPayloadShowsItsActualSnapshotAndSelectionIsReachable(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actions=Actions(Repository(db,scope),scope)
        // This is an approved, player-entered template snapshot, not a new built-in ability.
        val template=Template("orb","Chromatic Orb",1,1,listOf(TypedDice(Dice(3,8),DamageType.COLD)),DamageType.COLD,true,"Approved instantaneous cold damage")
        val payload=Payload("stored-orb",template,SourceKind.SLOT,"core",0)
        val s=seed().copy(cores=listOf(Core("core","Resonant crystal",payloads=listOf(payload))),templates=listOf(template))
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation("Cores & Payloads") {}}) {padding->
                Column(Modifier.padding(padding)) {
                    InventoryTabs("Payloads") {}
                    PayloadsPage(s,actions,Preferences(context),setOf(payload.id))
                }
            }}}
            compose.onNodeWithText("Chromatic Orb").assertIsDisplayed()
            compose.onNodeWithText("3d8 Cold").assertIsDisplayed()
            compose.onNodeWithText("SELECTED").assertIsDisplayed()
            compose.onNodeWithText("Deselect").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Sunspike Charge").assertDoesNotExist()
            capture("payloads",activity.get())
            compose.onNodeWithText("Review templates & edit future loads ▾").performScrollTo().assertIsDisplayed()
        } finally {activity.pause().stop().destroy();scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
    @Test(timeout=60000) fun controlRoomKeepsAllSecondaryUtilitiesReachable() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation("More") {}}) {padding->
                Box(Modifier.padding(padding)) {ControlRoomPage {}}
            }}}
            compose.onNodeWithText("Control room").assertIsDisplayed()
            listOf("Weapon resources & recovery","Rules & upgrades","Overlay & appearance","Backup & restore","Help & diagnostics").forEach {
                compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("Control room").performScrollTo()
            capture("more",activity.get())
        } finally {activity.pause().stop().destroy()}
    }
    @Test(timeout=60000)
    @Config(qualifiers="w360dp-h800dp-xhdpi")
    fun narrowPhoneRetainsVisiblePrimaryActionAndReadableNavigation(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AstravarDb::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actions=Actions(Repository(db,scope),scope)
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Scaffold(topBar={AstravarHeader(false)},bottomBar={WeaponNavigation("Weapon") {}}) {padding->
                Box(Modifier.padding(padding)) {Dashboard(seed(),actions,{},{},{},Preferences(context),OverlayPrefs(mode=Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING)))}
            }}}
            val roll=compose.onNodeWithText("Roll attack")
            roll.assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            listOf("Normal","Advantage","Disadvantage").forEach {compose.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)}
            weaponDestinations.forEach {compose.onNodeWithText(it).assertIsDisplayed()}
            capture("weapon-360dp",activity.get())
            compose.onNodeWithText("Shot options ▾").performScrollTo().performClick()
            compose.onNodeWithText("Distance in feet").performScrollTo().assertIsDisplayed()
            roll.assertIsDisplayed()
        } finally {activity.pause().stop().destroy();scope.coroutineContext[Job]!!.cancelAndJoin();db.close()}
    }
}
