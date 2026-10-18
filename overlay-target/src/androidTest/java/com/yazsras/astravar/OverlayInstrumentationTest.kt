package com.yazsras.astravar

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.regex.Pattern

/** Black-box tests run outside the unchanged product APK; no product test hooks or RNG overrides. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OverlayInstrumentationTest {
    @get:Rule val failureEvidence=object: TestWatcher() {
        override fun failed(error: Throwable,description: Description) {
            runCatching {device.takeScreenshot(File(files,"failure-${description.methodName}.png"))}
            runCatching {device.dumpWindowHierarchy(File(files,"failure-${description.methodName}.xml"))}
        }
    }
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val device get()=UiDevice.getInstance(instrumentation)
    private val appPackage get()=InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val targetPackage get()="com.yazsras.astravar.target"
    private val files get()=instrumentation.targetContext.getExternalFilesDir(null)!!
    private val ui get()=ObservedUi(device)
    private fun shell(command:String)=device.executeShellCommand(command)
    private fun checkpoint(message:String) {instrumentation.sendStatus(0,android.os.Bundle().apply {putString("stream","\nCHECKPOINT: $message\n")})}
    private fun screenshot(name:String) {assertTrue(device.takeScreenshot(File(files,"api${android.os.Build.VERSION.SDK_INT}-$name.png")))}
    private fun openMain() {
        shell("am start -W -n $appPackage/com.yazsras.astravar.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("ASTRAVAR").pkg(appPackage)),60000))
        // Saved native tool dialogs may legitimately survive activity recreation.
        repeat(3) { if (device.hasObject(By.text("Close").pkg(appPackage))) tapMain("Close") }
    }
    private fun findText(text:String):Rect = ui.textBounds(text, appPackage)
    private fun tapMain(text:String, preferLast:Boolean=false) = ui.tapText(text, appPackage, preferLast=preferLast)
    private fun panelControlBounds(text: String): Rect {
        val selector = By.text(text).pkg(appPackage).enabled(true).clickable(true)
        val deadline = SystemClock.uptimeMillis() + 30_000
        val minimumTap = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        // Drag persistence and preference commits replace the overlay's window.
        // Requery fresh nodes across that handoff, and never use UiScrollable's
        // active-window search: the controlled underlying app owns input focus.
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(8) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) error("Missing enabled overlay control after fresh-window search: $text")
                device.wait(Until.findObject(selector), minOf(1_500L, remaining))
                try {
                    val header = device.findObject(By.desc("Drag or collapse Astravar panel").pkg(appPackage))
                    val panel = header?.parent
                    panel?.findObject(selector)?.let { control ->
                        val bounds = Rect(control.visibleBounds)
                        if (bounds.width() >= minimumTap && bounds.height() >= minimumTap) return bounds
                    }
                    val scroll = panel?.findObject(By.clazz("android.widget.ScrollView").pkg(appPackage))
                    scroll?.scroll(direction, .6f)
                } catch (_: StaleObjectException) {
                    // Only lookup/scroll is retried. A resource-spending click is
                    // performed once, from the verified detached bounds.
                }
                device.waitForIdle()
            }
        }
        error("Missing enabled overlay control: $text")
    }
    private fun tapPanel(text:String) {
        val bounds = panelControlBounds(text)
        val minimumTap = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        assertTrue("Overlay control must retain a visible 48 dp touch target: $text", bounds.width() >= minimumTap && bounds.height() >= minimumTap)
        assertTrue("Touch must be dispatched once to the observed overlay control: $text", device.click(bounds.centerX(), bounds.centerY()))
        device.waitForIdle()
    }
    private fun tapTarget(text:String) = ui.tap(By.text(text).pkg(targetPackage), text)
    private fun expand() {
        ui.tap(By.descStartsWith("Expand Astravar crystal").pkg(appPackage), "Expand Astravar crystal")
        assertTrue(device.wait(Until.hasObject(By.desc("Drag or collapse Astravar panel").pkg(appPackage)),30000))
    }
    private fun navigate(name:String) = tapMain(name, preferLast=true)
    private fun overlaySettings() { navigate("Config");tapMain("Overlay & appearance") }
    private fun startOverlay() {shell("am force-stop $appPackage");openMain();overlaySettings();tapMain("Start overlay session");assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))}
    private fun stopPanel() {tapPanel("More");tapPanel("Stop session");assertTrue(device.wait(Until.gone(By.desc("Drag or collapse Astravar panel")),30000));assertFalse(device.hasObject(By.descStartsWith("Expand Astravar crystal")))}

    @Test fun aAutomaticRollingOutsideTouchKeyboardRotationAndProtection() {
        Configurator.getInstance().setWaitForIdleTimeout(1000).setWaitForSelectorTimeout(30000)
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW allow")
        shell("pm grant $appPackage android.permission.POST_NOTIFICATIONS")
        openMain();navigate("Combat");tapMain("Start labeled demo")
        overlaySettings()
        findText("Start overlay session")
        screenshot("combat")
        tapMain("Start overlay session")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        shell("am start -W -n com.yazsras.astravar.target/.TargetActivity")
        ui.tap(By.desc("Outside tap counter").pkg(targetPackage), "Outside tap counter")
        val outsideWorked=device.wait(Until.hasObject(By.desc("Outside tap counter").text("Outside taps: 1")),5000)
        assertTrue("Outside tap counter: ${ui.snapshot(By.desc("Outside tap counter").pkg(targetPackage), "outside tap count") { it.text }}",outsideWorked)
        screenshot("collapsed")
        expand();screenshot("expanded")
        val dragStart=ui.controlBounds(By.desc("Drag or collapse Astravar panel").pkg(appPackage), "panel drag handle")
        assertTrue("Panel drag must dispatch once", device.drag(dragStart.centerX(),dragStart.centerY(),device.displayWidth-20,160,100))
        device.waitForIdle()
        val panel=ui.snapshot(By.desc("Drag or collapse Astravar panel").pkg(appPackage), "dragged panel bounds", timeoutMs=10_000) { Rect(it.visibleBounds) }
        assertTrue(panel.left>=0 && panel.right<=device.displayWidth)
        screenshot("dragged")
        tapPanel("Adv")
        assertTrue("Advantage selection must be visibly committed before rolling", device.wait(Until.hasObject(By.text("✓ Adv").pkg(appPackage)),30000))
        tapPanel("Roll attack")
        assertTrue(device.wait(Until.hasObject(By.text("ATTACK ROLL")),30000))
        val formula=ui.snapshot(By.textStartsWith("d20 ").pkg(appPackage), "first committed attack formula") { it.text }
        assertTrue("Two visible d20s must be preserved",formula.contains(" / ") && formula.contains("advantage"))
        File(files,"saved-first-roll.txt").writeText(formula)
        assertFalse("No manual damage entry in normal combat",device.hasObject(By.clazz("android.widget.EditText").pkg(appPackage)))
        screenshot("automatic-result")
        tapPanel("Roll again")
        val normalFormula = ui.snapshot(By.text(Pattern.compile("d20 [0-9]+ \\+ -?[0-9]+ = -?[0-9]+")).pkg(appPackage), "second committed normal attack formula") { it.text }
        assertFalse("One-shot advantage must reset for the next attack",normalFormula.contains(" / ") || normalFormula.contains("advantage"))
        File(files,"saved-roll.txt").writeText(normalFormula)
        screenshot("automatic-normal-result")
        tapPanel("Collapse")
        ui.tap(By.desc("Keyboard focus test").pkg(targetPackage), "Keyboard focus test")
        ui.enterFocusedField("typed under Astravar",targetPackage,"Keyboard focus test")
        assertEquals("typed under Astravar",ui.snapshot(By.desc("Keyboard focus test").pkg(targetPackage), "typed keyboard text") { it.text })
        device.pressBack();device.setOrientationLeft();device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        val rotated=ui.snapshot(By.descStartsWith("Expand Astravar crystal").pkg(appPackage), "rotated crystal bounds") { Rect(it.visibleBounds) }
        assertTrue(rotated.left>=0 && rotated.right<=device.displayWidth && rotated.bottom<=device.displayHeight)
        expand();screenshot("landscape-result");tapPanel("Collapse")
        device.setOrientationNatural();device.waitForIdle()
        shell("settings put system font_scale 1.3")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        expand();screenshot("large-text-result");tapPanel("Collapse")
        shell("settings put system font_scale 1.0");device.waitForIdle()
        tapTarget("Toggle protected screen")
        assertTrue("Protected target button must reflect its secure state",device.wait(Until.hasObject(By.text("Protected screen ON").pkg(targetPackage)),10000))
        assertTrue(device.wait(Until.gone(By.descStartsWith("Expand Astravar crystal")),30000))
        tapTarget("Protected screen ON")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        expand();tapPanel("Weapon");stopPanel();device.unfreezeRotation()
        checkpoint("Two automatic overlay attacks, raw dice, drag, outside touch, keyboard, rotation, large text and protected screen exercised")
    }
    @Test fun bNotificationStop() {
        assertTrue("This acceptance run requires real System UI",shell("pm path com.android.systemui").contains("package:"))
        startOverlay();device.openNotification()
        if(!device.wait(Until.hasObject(By.text("Stop")),10000)) {
            val notification=ui.snapshot(By.text("Astravar session active"), "session notification") { Rect(it.parent.visibleBounds) }
            assertTrue("Notification expansion must dispatch once", device.swipe(notification.centerX(),notification.top+notification.height()/5,notification.centerX(),notification.bottom-notification.height()/5,100))
        }
        assertTrue("Notification Stop action must be visible",device.wait(Until.hasObject(By.text("Stop")),30000))
        ui.tap(By.text("Stop"), "Notification Stop")
        device.pressBack();device.waitForIdle()
        assertTrue(device.wait(Until.gone(By.descStartsWith("Expand Astravar crystal")),30000))
    }
    @Test fun cRollRestorationMainHistoryAndLivePermissionRecovery() {
        shell("am force-stop $appPackage");openMain();navigate("Combat")
        val formula=File(files,"saved-roll.txt").readText()
        findText(formula)
        assertEquals("Raw overlay dice survive force-stop and are shared with Compose",formula,ui.snapshot(By.text(formula).pkg(appPackage), "restored main formula") { it.text })
        screenshot("restored-main-result")
        navigate("Config");tapMain("Roll history")
        assertTrue(device.wait(Until.hasObject(By.text("Recent rolls")),30000))
        val firstFormula=File(files,"saved-first-roll.txt").readText()
        val historyFormulas=ui.texts(By.text(Pattern.compile("d20 .*")).pkg(appPackage), "saved history formulas")
        assertEquals("Exactly two immutable attacks appear in history",2,historyFormulas.size)
        assertEquals("History preserves both original raw rolls and their advantage/normal modes",listOf(firstFormula,formula).sorted(),historyFormulas.sorted())
        assertEquals("Only the first attack used the one-shot advantage selection",1,historyFormulas.count {it.contains("advantage")})
        assertEquals("The second attack retained its normal single-d20 result",1,historyFormulas.count {!it.contains(" / ") && !it.contains("advantage")})
        screenshot("recent-history")
        startOverlay();shell("am start -W -n com.yazsras.astravar.target/.TargetActivity")
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW deny")
        assertTrue(device.wait(Until.gone(By.descStartsWith("Expand Astravar crystal")),30000))
        shell("am force-stop $appPackage");openMain();overlaySettings();tapMain("Start overlay session");device.waitForIdle()
        assertFalse(device.hasObject(By.descStartsWith("Expand Astravar crystal")))
        assertTrue("Denied permission should open Android's overlay settings",device.wait(Until.hasObject(By.text("Display over other apps").pkg("com.android.settings")),30000))
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW allow")
        device.pressBack();device.waitForIdle()
        // The returned modal owns accessibility; its background header is hidden.
        assertTrue("Permission settings must return to the existing overlay controls",device.wait(Until.hasObject(By.text("Overlay & appearance").pkg(appPackage)),30000))
        val restartBounds=ui.controlBounds(By.text("Start overlay session").pkg(appPackage), "enabled overlay restart after permission grant")
        assertFalse("The enabled overlay restart control must be visible",restartBounds.isEmpty)
        tapMain("Start overlay session")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        expand();stopPanel()
        checkpoint("Identical saved dice restored in main app and history; live permission revocation and recovery exercised")
    }
}
