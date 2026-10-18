package com.yazsras.astravar

import android.graphics.Point
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
    private fun shell(command:String)=device.executeShellCommand(command)
    private fun checkpoint(message:String) {instrumentation.sendStatus(0,android.os.Bundle().apply {putString("stream","\nCHECKPOINT: $message\n")})}
    private fun screenshot(name:String) {assertTrue(device.takeScreenshot(File(files,"api${android.os.Build.VERSION.SDK_INT}-$name.png")))}
    private fun openMain() {
        shell("am start -W -n $appPackage/com.yazsras.astravar.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("ASTRAVAR").pkg(appPackage)),60000))
        // Saved native tool dialogs may legitimately survive activity recreation.
        repeat(3) { device.findObject(By.text("Close").pkg(appPackage))?.let { it.click(); device.waitForIdle() } }
    }
    private fun findText(text:String):UiObject2 {
        for(direction in listOf(Direction.DOWN,Direction.UP)) repeat(12) {
            device.findObject(By.text(text).pkg(appPackage))?.let {return it}
            device.findObject(By.scrollable(true).pkg(appPackage))?.scroll(direction,.6f)
            device.waitForIdle()
        }
        error("Missing product control: $text")
    }
    private fun <T> freshSnapshot(selector: BySelector, description: String, timeoutMs: Long = 30_000, read: (UiObject2) -> T): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) break
            try {
                device.wait(Until.findObject(selector), minOf(1_000L, remaining))?.let { return read(it) }
            } catch (_: StaleObjectException) {
                // Only an observation is retried; its detached value survives a
                // subsequent window replacement without retaining a stale node.
            }
            device.waitForIdle()
        }
        error("No fresh accessibility snapshot: $description")
    }
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
    private fun targetText(text:String):UiObject2 = device.wait(
        Until.findObject(By.text(text).pkg(targetPackage)),30000
    ) ?: error("Missing controlled target control: $text")
    private fun expand() {device.wait(Until.findObject(By.descStartsWith("Expand Astravar crystal")),30000).click();assertTrue(device.wait(Until.hasObject(By.desc("Drag or collapse Astravar panel")),30000))}
    private fun navigate(name:String) {
        device.findObjects(By.text(name).pkg(appPackage)).lastOrNull()?.click() ?: findText(name).click()
        device.waitForIdle()
    }
    private fun overlaySettings() { navigate("Config");findText("Overlay & appearance").click();device.waitForIdle() }
    private fun startOverlay() {shell("am force-stop $appPackage");openMain();overlaySettings();findText("Start overlay session").click();assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))}
    private fun stopPanel() {tapPanel("More");tapPanel("Stop session");assertTrue(device.wait(Until.gone(By.desc("Drag or collapse Astravar panel")),30000));assertFalse(device.hasObject(By.descStartsWith("Expand Astravar crystal")))}

    @Test fun aAutomaticRollingOutsideTouchKeyboardRotationAndProtection() {
        Configurator.getInstance().setWaitForIdleTimeout(1000).setWaitForSelectorTimeout(30000)
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW allow")
        shell("pm grant $appPackage android.permission.POST_NOTIFICATIONS")
        openMain();navigate("Combat");findText("Start labeled demo").click();device.waitForIdle()
        overlaySettings()
        val floatingControls=findText("Start overlay session")
        screenshot("combat")
        floatingControls.click()
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        shell("am start -W -n com.yazsras.astravar.target/.TargetActivity")
        device.wait(Until.findObject(By.desc("Outside tap counter")),30000).click(100)
        val outsideWorked=device.wait(Until.hasObject(By.desc("Outside tap counter").text("Outside taps: 1")),5000)
        assertTrue("Outside tap counter: ${device.findObject(By.desc("Outside tap counter"))?.text}",outsideWorked)
        screenshot("collapsed")
        expand();screenshot("expanded")
        device.findObject(By.desc("Drag or collapse Astravar panel")).drag(Point(device.displayWidth-20,160),500)
        device.waitForIdle()
        val panel=freshSnapshot(By.desc("Drag or collapse Astravar panel").pkg(appPackage), "dragged panel bounds", timeoutMs=10_000) { Rect(it.visibleBounds) }
        assertTrue(panel.left>=0 && panel.right<=device.displayWidth)
        screenshot("dragged")
        tapPanel("Adv")
        assertTrue("Advantage selection must be visibly committed before rolling", device.wait(Until.hasObject(By.text("✓ Adv").pkg(appPackage)),30000))
        tapPanel("Roll attack")
        assertTrue(device.wait(Until.hasObject(By.text("ATTACK ROLL")),30000))
        val formula=freshSnapshot(By.textStartsWith("d20 ").pkg(appPackage), "first committed attack formula") { it.text }
        assertTrue("Two visible d20s must be preserved",formula.contains(" / ") && formula.contains("advantage"))
        File(files,"saved-first-roll.txt").writeText(formula)
        assertFalse("No manual damage entry in normal combat",device.hasObject(By.clazz("android.widget.EditText").pkg(appPackage)))
        screenshot("automatic-result")
        tapPanel("Roll again")
        val normalFormula = freshSnapshot(By.text(Pattern.compile("d20 [0-9]+ \\+ -?[0-9]+ = -?[0-9]+")).pkg(appPackage), "second committed normal attack formula") { it.text }
        assertFalse("One-shot advantage must reset for the next attack",normalFormula.contains(" / ") || normalFormula.contains("advantage"))
        File(files,"saved-roll.txt").writeText(normalFormula)
        screenshot("automatic-normal-result")
        tapPanel("Collapse")
        val edit=device.findObject(By.desc("Keyboard focus test"));edit.click();edit.text="typed under Astravar"
        assertEquals("typed under Astravar",device.findObject(By.desc("Keyboard focus test")).text)
        device.pressBack();device.setOrientationLeft();device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        val rotated=freshSnapshot(By.descStartsWith("Expand Astravar crystal").pkg(appPackage), "rotated crystal bounds") { Rect(it.visibleBounds) }
        assertTrue(rotated.left>=0 && rotated.right<=device.displayWidth && rotated.bottom<=device.displayHeight)
        expand();screenshot("landscape-result");tapPanel("Collapse")
        device.setOrientationNatural();device.waitForIdle()
        shell("settings put system font_scale 1.3")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        expand();screenshot("large-text-result");tapPanel("Collapse")
        shell("settings put system font_scale 1.0");device.waitForIdle()
        targetText("Toggle protected screen").click()
        assertTrue("Protected target button must reflect its secure state",device.wait(Until.hasObject(By.text("Protected screen ON").pkg(targetPackage)),10000))
        assertTrue(device.wait(Until.gone(By.descStartsWith("Expand Astravar crystal")),30000))
        targetText("Protected screen ON").click()
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        expand();tapPanel("Weapon");stopPanel();device.unfreezeRotation()
        checkpoint("Two automatic overlay attacks, raw dice, drag, outside touch, keyboard, rotation, large text and protected screen exercised")
    }
    @Test fun bNotificationStop() {
        assertTrue("This acceptance run requires real System UI",shell("pm path com.android.systemui").contains("package:"))
        startOverlay();device.openNotification()
        if(!device.wait(Until.hasObject(By.text("Stop")),10000)) device.findObject(By.text("Astravar session active"))?.parent?.swipe(Direction.DOWN,1f)
        val stop=device.wait(Until.findObject(By.text("Stop")),30000)
        assertNotNull("Notification Stop action must be visible",stop);stop.click();device.pressBack();device.waitForIdle()
        assertTrue(device.wait(Until.gone(By.descStartsWith("Expand Astravar crystal")),30000))
    }
    @Test fun cRollRestorationMainHistoryAndLivePermissionRecovery() {
        shell("am force-stop $appPackage");openMain();navigate("Combat")
        val formula=File(files,"saved-roll.txt").readText()
        assertEquals("Raw overlay dice survive force-stop and are shared with Compose",formula,findText(formula).text)
        screenshot("restored-main-result")
        navigate("Config");findText("Roll history").click();device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.text("Recent rolls")),30000))
        val firstFormula=File(files,"saved-first-roll.txt").readText()
        val historyFormulas=device.findObjects(By.text(Pattern.compile("d20 .*")).pkg(appPackage)).map {it.text}
        assertEquals("Exactly two immutable attacks appear in history",2,historyFormulas.size)
        assertEquals("History preserves both original raw rolls and their advantage/normal modes",listOf(firstFormula,formula).sorted(),historyFormulas.sorted())
        assertEquals("Only the first attack used the one-shot advantage selection",1,historyFormulas.count {it.contains("advantage")})
        assertEquals("The second attack retained its normal single-d20 result",1,historyFormulas.count {!it.contains(" / ") && !it.contains("advantage")})
        screenshot("recent-history")
        startOverlay();shell("am start -W -n com.yazsras.astravar.target/.TargetActivity")
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW deny")
        assertTrue(device.wait(Until.gone(By.descStartsWith("Expand Astravar crystal")),30000))
        shell("am force-stop $appPackage");openMain();overlaySettings();findText("Start overlay session").click();device.waitForIdle()
        assertFalse(device.hasObject(By.descStartsWith("Expand Astravar crystal")))
        assertTrue("Denied permission should open Android's overlay settings",device.wait(Until.hasObject(By.text("Display over other apps").pkg("com.android.settings")),30000))
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW allow")
        device.pressBack();device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.text("ASTRAVAR").pkg(appPackage)),30000))
        findText("Start overlay session").click()
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("Expand Astravar crystal")),30000))
        expand();stopPanel()
        checkpoint("Identical saved dice restored in main app and history; live permission revocation and recovery exercised")
    }
}
