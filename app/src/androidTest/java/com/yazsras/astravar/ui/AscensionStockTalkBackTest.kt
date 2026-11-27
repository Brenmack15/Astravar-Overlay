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

/** Actual system TalkBack owns focus and handles every gesture. UiAutomation observes windows and service state; the host sends physical gestures to QEMU. */
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
    private var inputSequence = 0
    private fun hardware(action: String) {
        // The host injects physical touch/evdev keys through the owned emulator, upstream of TalkBack.
        val sequence = ++inputSequence
        instrumentation.sendStatus(0, Bundle().apply { putString("readerHardware", "$sequence $action") })
        val ack = File(context.filesDir, "reader-hardware.ack")
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) {
            if (ack.isFile && ack.readText().trim() == sequence.toString()) return
            SystemClock.sleep(50)
        }
        error("Missing host input acknowledgement for $sequence $action")
    }
    private fun actionableLabel() = generateSequence(requireNotNull(visible(label))) { it.parent }.take(6).first { n ->
        n.isVisibleToUser && n.actionList.any { it.id==AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString()=="Options for Strength check" }
    }
    private fun hasTargetFocus(): Boolean {
        val n=focus() ?: return false
        if(visible(label)==null) return false
        val a=bounds(n);val b=bounds(actionableLabel())
        return n.packageName==context.packageName && a.contains(b.centerX(),b.centerY()) &&
            a.width()*a.height() <= b.width()*b.height()*4 && descendants(n).any { label in nodeText(it) }
    }
    private fun nextFocus(): String? {
        val before=focusText()
        for(input in listOf("NEXT_SWIPE", "CHORD KEYCODE_META_LEFT KEYCODE_DPAD_RIGHT", "CHORD KEYCODE_ALT_LEFT KEYCODE_DPAD_RIGHT")) {
            hardware(input)
            if(waitFor(850) { focus()!=null && focusText()!=before }) return input
        }
        return null
    }
    private fun calibration(): String {
        requireObservation("TalkBack already owns initial focus within the normal character screen") { focus()?.packageName==context.packageName && visible("Character controls")!=null }
        SystemClock.sleep(750)
        val r=bounds(requireNotNull(visible("Character controls")))
        hardware("EXPLORE ${r.centerX()} ${r.centerY()}")
        fun ordinary(): Boolean {
            val n=focus() ?: return false
            val labels=descendants(n).flatMap { nodeText(it).asSequence() }.toList()
            return n.packageName==context.packageName && bounds(n).height()<=220 && labels.any { it in setOf("Character controls","QA artisan","AC","Rest","Conditions","Initiative +0","HP 16 / 20","Manage QA artisan portrait") }
        }
        if(!waitFor(1600) { ordinary() }) {
            for(i in 1..22) {
                val path=nextFocus() ?: break
                status("calibration traversal $path => ${focusText()}")
                if(ordinary()) break
            }
        }
        requireObservation("Independently focus an ordinary app control with the genuine service") { ordinary() }
        return focusText()
    }
    private fun focusTarget(): Rect {
        requireObservation("Visible quick-roll control") { visible(label)!=null }
        val r=bounds(actionableLabel())
        status("aim at actionable ${r.toShortString()} from ${focusText()}")
        if(!hasTargetFocus()) {
            hardware("EXPLORE ${r.centerX()} ${r.centerY()}")
            if(!waitFor(1200) { hasTargetFocus() }) {
                for(i in 1..42) {
                    val path=nextFocus() ?: break
                    status("normal service traversal $path => ${focusText()}")
                    if(hasTargetFocus()) break
                }
            }
        }
        requireObservation("TalkBack must accessibility-focus the actual modifier") { hasTargetFocus() }
        return r
    }

    private fun activateVisible(text: String) {
        requireObservation("Reachable $text") { visible(text)!=null }
        val element=requireNotNull(visible(text))
        val r=bounds(element)
        hardware("EXPLORE ${r.centerX()} ${r.centerY()}")
        requireObservation("TalkBack focus for $text") {
            val selected=focus()
            selected!=null && selected.packageName==context.packageName &&
                bounds(selected).contains(r.centerX(),r.centerY()) &&
                descendants(selected).any { text in nodeText(it) } && bounds(selected).height()<r.height()*12
        }
        hardware("DOUBLE_TAP ${r.centerX()} ${r.centerY()}")
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
                val standardControl=calibration()
                status("ordinary control calibration $standardControl")
                var r=focusTarget()
                val focusLabel=focusText()
                status("focused $focusLabel")
                hardware("COPY_LAST")
                var spoken: String? = null
                waitFor(2200) {
                    activity.onActivity { a ->
                        val clip=a.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                        val value=clip?.takeIf { it.itemCount>0 }?.getItemAt(0)?.text?.toString()
                        if(value?.contains("Strength",ignoreCase=true)==true) spoken=value.take(160)
                    }
                    spoken!=null
                }
                assertTrue(history().isEmpty())
                val control=generateSequence(requireNotNull(visible(label))) { it.parent }.take(5).first { n ->
                    n.actionList.any { it.id==AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString()=="Options for Strength check" }
                }
                assertTrue(control.isEnabled)
                val paginationInput=nextFocus()
                assertNotNull("Service must navigate normally away from the roll control",paginationInput)
                requireObservation("TalkBack next-item swipe must navigate away from Strength") {
                    val n=focus()
                    n!=null && n.packageName==context.packageName && !hasTargetFocus()
                }
                val adjacent=focusText()
                status("TalkBack swipe navigated to $adjacent")
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
                    hardware("CHORD $keys")
                    if(waitFor(2200){visible(shown)!=null}) {activated=keys;break}
                    assertTrue("Shortcut exploration must not roll",history().isEmpty())
                }
                if(activated==null) {
                    // The outer title has its own action, separate from the two nested quick rolls.
                    activateVisible("STRENGTH")
                    activateVisible("Roll check")
                    requireObservation("Physical dice entry after normal inspector Roll check button") { visible(shown)!=null }
                    activated="TalkBack double-tap Strength ability inspector, then Roll check"
                }
                assertTrue("Opening options must not record a roll", history().isEmpty())
                status("real reader shortcut $activated opened $shown; 0 rolls")
                hardware("BACK")
                requireObservation("Native options dismissed") { visible(shown)==null }
                if(visible("Roll check")!=null) hardware("BACK")
                r=focusTarget()
                var finalInput="DOUBLE_TAP"
                hardware("DOUBLE_TAP ${r.centerX()} ${r.centerY()}")
                if(!waitFor(6000){ history().isNotEmpty() }) {
                    for(input in listOf("CHORD KEYCODE_META_LEFT KEYCODE_SPACE","CHORD KEYCODE_ALT_LEFT KEYCODE_ENTER","CHORD KEYCODE_META_LEFT KEYCODE_ENTER")) {
                        assertTrue("Scoped control remains focused before the accessible activation",hasTargetFocus())
                        assertTrue("No pending roll was committed",history().isEmpty())
                        hardware(input)
                        if(waitFor(3500){history().isNotEmpty()}) { finalInput=input; break }
                    }
                }
                requireObservation("Genuine TalkBack activation must save exactly one roll") { history().size==1 }
                val only=history().single();val component=only.components.single();val die=component.dice.single()
                assertEquals(qaId,only.profileId);assertEquals(before.revision,only.characterRevision)
                assertEquals("Strength check",only.label);assertEquals("Player",only.ownership);assertFalse(only.manual)
                assertEquals("STR",component.subjectId);assertEquals(RollKind.ABILITY_CHECK.name,component.checkKind)
                assertEquals(DiceMode.NORMAL,component.mode);assertEquals(3,component.modifier);assertEquals(20,die.sides);assertTrue(die.kept);assertTrue(die.face in 1..20)
                assertEquals(die.face+3,only.total);assertEquals(before,runBlocking { repo.get(qaId) });assertEquals(listOf(only),history())
                assertNotNull(actualService());assertTrue(manager.isTouchExplorationEnabled)
                val proof=JSONObject().put("api",Build.VERSION.SDK_INT).put("service",service.id).put("version",pkg.versionName)
                    .put("certificateSha256",cert).put("touchExplorationEnabled",manager.isTouchExplorationEnabled)
                    .put("calibratedOrdinaryControl",standardControl).put("focused",focusLabel).put("adjacent",adjacent).put("navigateInput",paginationInput).put("talkBackCopiedLastSpokenPhrase",spoken ?: JSONObject.NULL).put("frameworkActionLabel","Options for Strength check")
                    .put("talkBackKeys",activated).put("visibleOptions",shown).put("rollsAfterOpeningOptions",0)
                    .put("activationInput",finalInput).put("rollsAfterReaderActivation",1).put("actorId",qaId).put("subject",component.subjectId).put("rollId",only.id)
                    .put("mode",component.mode!!.name).put("owner",only.ownership).put("formula",only.formula).put("face",die.face)
                    .put("modifier",component.modifier).put("total",only.total).put("actorRevision",before.revision)
                File(context.getExternalFilesDir(null),"talkback-proof.json").writeText(proof.toString())
                status("ACTUAL_READER_RESULT $proof")
            }
        } finally {db.close();ledger.close()}
    }
}
