package com.yazsras.astravar.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Actual system TalkBack owns focus and handles every gesture. UiAutomation only observes or sends raw input. */
@RunWith(AndroidJUnit4::class)
class AscensionStockTalkBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val systemReader = "com.google.android.marvin.talkback"
    private val label = "Strength check +3"
    private val qaId = "qa-reader-actor"
    private lateinit var observer: UiAutomation

    private fun status(s: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nTalkBack QA $s\n") })
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(observer.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun roots(): List<AccessibilityNodeInfo> = (observer.windows.mapNotNull { it.root } + listOfNotNull(observer.rootInActiveWindow))
    private fun descendants(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { yieldAll(descendants(it)) }
    }
    private fun nodeText(n: AccessibilityNodeInfo): List<String> = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    private fun windowText() = roots().joinToString(" | ") { root ->
        root.packageName.toString() + ": " + descendants(root).filter { it.isVisibleToUser }.flatMap { nodeText(it).asSequence() }.take(28).joinToString("/")
    }.take(2100)
    private fun focus(): AccessibilityNodeInfo? = roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }
    private fun focusText() = focus()?.let { n ->
        listOf(n.packageName.toString(), bounds(n).toShortString(), descendants(n).flatMap { nodeText(it).asSequence() }.take(10).joinToString("/")).joinToString(" ")
    } ?: "none"
    private fun visible(text: String): AccessibilityNodeInfo? = roots().asSequence().filter { it.packageName==context.packageName }
        .flatMap(::descendants).firstOrNull { it.isVisibleToUser && text in nodeText(it) }
    private fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeout
        do { observer.clearCache(); if (condition()) return true; SystemClock.sleep(100) } while (SystemClock.uptimeMillis()<end)
        return false
    }
    private fun requireObservation(description: String, condition: () -> Boolean) {
        if (!waitFor(15000, condition)) throw AssertionError("$description. Focus ${focusText()}. Windows ${windowText()}")
    }
    private fun point(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        try { event.source=InputDevice.SOURCE_TOUCHSCREEN; assertTrue(observer.injectInputEvent(event,true)) }
        finally { event.recycle() }
    }
    private fun tap(x: Float,y: Float) {
        val down=SystemClock.uptimeMillis()
        point(MotionEvent.ACTION_DOWN,down,x,y);SystemClock.sleep(38);point(MotionEvent.ACTION_UP,down,x,y)
    }
    private fun hasTargetFocus(): Boolean {
        val n=focus() ?: return false
        val target=visible(label) ?: return false
        val a=bounds(n);val b=bounds(target)
        return n.packageName==context.packageName && a.contains(b.centerX(),b.centerY()) &&
            a.width()*a.height() <= b.width()*b.height()*4 && descendants(n).any { label in nodeText(it) }
    }
    private fun focusTarget(): Rect {
        requireObservation("Visible quick-roll control") { visible(label)!=null }
        val r=bounds(requireNotNull(visible(label)))
        if(!hasTargetFocus()) tap(r.exactCenterX(),r.exactCenterY())
        requireObservation("TalkBack must accessibility-focus the actual modifier") { hasTargetFocus() }
        return r
    }

    @Test fun genuineReaderFocusOptionsAndOneExplicitQuickRoll() {
        assertEquals(37,Build.VERSION.SDK_INT)
        assertEquals("com.yazsras.astravar.v2uidebug",context.packageName)
        observer=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertEquals("1",shell("getprop ro.kernel.qemu"))
        val info=observer.serviceInfo
        info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        observer.serviceInfo=info
        val manager=context.getSystemService(AccessibilityManager::class.java)
        fun actualService()=manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .singleOrNull { it.resolveInfo.serviceInfo.packageName==systemReader && it.resolveInfo.serviceInfo.name.endsWith(".TalkBackService") }
        requireObservation("Stock TalkBack bound with touch exploration") { actualService()!=null && manager.isTouchExplorationEnabled }
        val service=requireNotNull(actualService())
        val pkg: PackageInfo = requireNotNull(context.packageManager.getPackageArchiveInfo(service.resolveInfo.serviceInfo.applicationInfo.sourceDir,PackageManager.GET_SIGNING_CERTIFICATES))
        assertEquals(systemReader,pkg.packageName)
        val signing=requireNotNull(pkg.signingInfo).apkContentsSigners.single().toByteArray()
        val cert=MessageDigest.getInstance("SHA-256").digest(signing).joinToString(""){"%02x".format(it)}
        status("stock service ${service.id}, package ${pkg.versionName}, signing certificate $cert")
        val db=Room.inMemoryDatabaseBuilder(context,ReforgeDb::class.java).build()
        val ledger=Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).build()
        val repo=ReforgeRepository(db)
        val actor=ReforgeCharacter(qaId,"QA artisan",level=4,baseAbilities=Stat.entries.associateWith{if(it==Stat.STR)16 else 10},baseMaxHp=20,currentHp=16)
        runBlocking { repo.create(actor) }
        val before=runBlocking { requireNotNull(repo.get(qaId)) }
        fun history()=runBlocking { repo.rollHistory(qaId).first() }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
                activity.onActivity { a -> a.setContent { AstravarTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) { ReforgeCharacterSheet(CampaignLedgerRepository(ledger),repo) } } } }
                var r=focusTarget()
                val focusLabel=focusText()
                status("focused $focusLabel")
                assertTrue(history().isEmpty())
                val control=generateSequence(requireNotNull(visible(label))) { it.parent }.take(5).first { n ->
                    n.actionList.any { it.id==AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString()=="Options for Strength check" }
                }
                assertTrue(control.isEnabled)
                val moves=shell("input swipe 300 1100 800 1100 120")
                requireObservation("TalkBack next-item swipe must navigate away from Strength") {
                    val n=focus()
                    n!=null && n.packageName==context.packageName && !hasTargetFocus()
                }
                val adjacent=focusText()
                status("swipe navigated to $adjacent; Android input: $moves")
                r=focusTarget()
                val shown="Enter physical dice faces"
                val shortcuts=listOf(
                    "KEYCODE_META_LEFT KEYCODE_SHIFT_LEFT KEYCODE_SPACE",
                    "KEYCODE_ALT_LEFT KEYCODE_SHIFT_LEFT KEYCODE_ENTER",
                    "KEYCODE_META_LEFT KEYCODE_SHIFT_LEFT KEYCODE_ENTER"
                )
                var activated: String?=null
                for (keys in shortcuts) {
                    assertTrue("The same roll control remains focused",hasTargetFocus())
                    shell("input keycombination -t 90 $keys")
                    if(waitFor(2200){visible(shown)!=null}) {activated=keys;break}
                    assertTrue("Shortcut exploration must not roll",history().isEmpty())
                }
                assertNotNull("TalkBack shortcut did not open native options. Focus ${focusText()}. Windows ${windowText()}",activated)
                assertTrue("Opening options must not record a roll", history().isEmpty())
                status("real reader shortcut $activated opened $shown; 0 rolls")
                shell("input keyevent KEYCODE_BACK")
                requireObservation("Native options dismissed") { visible(shown)==null && visible(label)!=null }
                r=focusTarget()
                val x=r.exactCenterX();val y=r.exactCenterY()
                tap(x,y);SystemClock.sleep(75);tap(x,y)
                requireObservation("Genuine TalkBack double-tap must save one roll") { history().size==1 }
                val only=history().single();val component=only.components.single();val die=component.dice.single()
                assertEquals(qaId,only.profileId);assertEquals(before.revision,only.characterRevision)
                assertEquals("Strength check",only.label);assertEquals("Player",only.ownership);assertFalse(only.manual)
                assertEquals("STR",component.subjectId);assertEquals(RollKind.ABILITY_CHECK.name,component.checkKind)
                assertEquals(DiceMode.NORMAL,component.mode);assertEquals(3,component.modifier);assertEquals(20,die.sides);assertTrue(die.kept);assertTrue(die.face in 1..20)
                assertEquals(die.face+3,only.total);assertEquals(before,runBlocking { repo.get(qaId) });assertEquals(listOf(only),history())
                assertNotNull(actualService());assertTrue(manager.isTouchExplorationEnabled)
                val proof=JSONObject().put("api",Build.VERSION.SDK_INT).put("service",service.id).put("version",pkg.versionName)
                    .put("certificateSha256",cert).put("touchExplorationEnabled",manager.isTouchExplorationEnabled)
                    .put("focused",focusLabel).put("adjacent",adjacent).put("frameworkActionLabel","Options for Strength check")
                    .put("talkBackKeys",activated).put("visibleOptions",shown).put("rollsAfterOpeningOptions",0)
                    .put("rollsAfterDoubleTap",1).put("actorId",qaId).put("subject",component.subjectId).put("rollId",only.id)
                    .put("mode",component.mode!!.name).put("owner",only.ownership).put("formula",only.formula).put("face",die.face)
                    .put("modifier",component.modifier).put("total",only.total).put("actorRevision",before.revision)
                File(context.getExternalFilesDir(null),"talkback-proof.json").writeText(proof.toString())
                status("ACTUAL_READER_RESULT $proof")
            }
        } finally {db.close();ledger.close()}
    }
}
