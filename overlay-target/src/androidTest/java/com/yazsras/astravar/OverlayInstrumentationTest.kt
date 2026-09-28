package com.yazsras.astravar

import android.graphics.Point
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
    private val files get()=instrumentation.targetContext.getExternalFilesDir(null)!!
    private fun shell(command:String)=device.executeShellCommand(command)
    private fun checkpoint(message:String) {instrumentation.sendStatus(0,android.os.Bundle().apply {putString("stream","\nCHECKPOINT: $message\n")})}
    private fun screenshot(name:String) {assertTrue(device.takeScreenshot(File(files,"api${android.os.Build.VERSION.SDK_INT}-$name.png")))}
    private fun openMain() {shell("am start -W -n $appPackage/com.yazsras.astravar.MainActivity");assertTrue(device.wait(Until.hasObject(By.text("ASTRAVAR")),60000))}
    private fun findText(text:String):UiObject2 {
        for(direction in listOf(Direction.DOWN,Direction.UP)) repeat(12) {
            device.findObject(By.text(text).pkg(appPackage))?.let {return it}
            device.findObject(By.scrollable(true).pkg(appPackage))?.scroll(direction,.6f)
            device.waitForIdle()
        }
        error("Missing product control: $text")
    }
    private fun tapPanel(text:String) {
        if(device.findObject(By.text(text).pkg(appPackage))==null) UiScrollable(UiSelector().className("android.widget.ScrollView")).scrollTextIntoView(text)
        val control=device.wait(Until.findObject(By.text(text).pkg(appPackage)),30000) ?: error("Missing overlay control: $text")
        control.click();device.waitForIdle()
    }
    private fun expand() {device.wait(Until.findObject(By.desc("Expand Astravar crystal")),30000).click();assertTrue(device.wait(Until.hasObject(By.desc("Drag Astravar panel")),30000))}
    private fun startOverlay() {shell("am force-stop $appPackage");openMain();findText("Floating controls").click();assertTrue(device.wait(Until.hasObject(By.desc("Expand Astravar crystal")),30000))}
    private fun stopPanel() {tapPanel("More");tapPanel("Stop session");assertTrue(device.wait(Until.gone(By.desc("Drag Astravar panel")),30000));assertFalse(device.hasObject(By.desc("Expand Astravar crystal")))}

    @Test fun aAutomaticRollingOutsideTouchKeyboardRotationAndProtection() {
        Configurator.getInstance().setWaitForIdleTimeout(1000).setWaitForSelectorTimeout(30000)
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW allow")
        shell("pm grant $appPackage android.permission.POST_NOTIFICATIONS")
        openMain();findText("Explore separate demo campaign").click();device.waitForIdle()
        val floatingControls=findText("Floating controls")
        screenshot("combat")
        floatingControls.click()
        assertTrue(device.wait(Until.hasObject(By.desc("Expand Astravar crystal")),30000))
        shell("am start -W -n com.yazsras.astravar.target/.TargetActivity")
        device.wait(Until.findObject(By.desc("Outside tap counter")),30000).click(100)
        val outsideWorked=device.wait(Until.hasObject(By.desc("Outside tap counter").text("Outside taps: 1")),5000)
        assertTrue("Outside tap counter: ${device.findObject(By.desc("Outside tap counter"))?.text}",outsideWorked)
        screenshot("collapsed")
        expand();screenshot("expanded")
        device.findObject(By.desc("Drag Astravar panel")).drag(Point(device.displayWidth-20,160),500)
        device.waitForIdle()
        val panel=(device.wait(Until.findObject(By.desc("Drag Astravar panel")),10000)
            ?: error("Dragged panel did not return after its saved-position refresh")).visibleBounds
        assertTrue(panel.left>=0 && panel.right<=device.displayWidth)
        tapPanel("Adv");tapPanel("Roll attack")
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("\\d+ TO HIT"))),30000))
        val formula=device.findObject(By.textStartsWith("d20 ")).text
        assertTrue("Two visible d20s must be preserved",formula.contains(" / ") && formula.contains("advantage"))
        assertFalse("No manual damage entry in normal combat",device.hasObject(By.clazz("android.widget.EditText").pkg(appPackage)))
        screenshot("automatic-result")
        tapPanel("Roll again")
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("\\d+ TO HIT"))),30000))
        File(files,"saved-roll.txt").writeText(device.findObject(By.textStartsWith("d20 ")).text)
        tapPanel("Collapse")
        val edit=device.findObject(By.desc("Keyboard focus test"));edit.click();edit.text="typed under Astravar"
        assertEquals("typed under Astravar",device.findObject(By.desc("Keyboard focus test")).text)
        device.pressBack();device.setOrientationLeft();device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.desc("Expand Astravar crystal")),30000))
        val rotated=device.findObject(By.desc("Expand Astravar crystal")).visibleBounds
        assertTrue(rotated.left>=0 && rotated.right<=device.displayWidth && rotated.bottom<=device.displayHeight)
        expand();screenshot("landscape-result");tapPanel("Collapse")
        device.setOrientationNatural();device.waitForIdle()
        shell("settings put system font_scale 1.3")
        assertTrue(device.wait(Until.hasObject(By.desc("Expand Astravar crystal")),30000))
        expand();screenshot("large-text-result");tapPanel("Collapse")
        shell("settings put system font_scale 1.0");device.waitForIdle()
        device.findObject(By.text("Toggle protected screen")).click()
        assertTrue(device.wait(Until.gone(By.desc("Expand Astravar crystal")),30000))
        device.findObject(By.text("Protected screen ON")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("Expand Astravar crystal")),30000))
        expand();tapPanel("Weapon");stopPanel();device.unfreezeRotation()
        checkpoint("Two automatic overlay attacks, raw dice, drag, outside touch, keyboard, rotation, large text and protected screen exercised")
    }
    @Test fun bNotificationStop() {
        assertTrue("This acceptance run requires real System UI",shell("pm path com.android.systemui").contains("package:"))
        startOverlay();device.openNotification()
        if(!device.wait(Until.hasObject(By.text("Stop")),10000)) device.findObject(By.text("Astravar session active"))?.parent?.swipe(Direction.DOWN,1f)
        val stop=device.wait(Until.findObject(By.text("Stop")),30000)
        assertNotNull("Notification Stop action must be visible",stop);stop.click();device.pressBack();device.waitForIdle()
        assertTrue(device.wait(Until.gone(By.desc("Expand Astravar crystal")),30000))
    }
    @Test fun cRollRestorationMainHistoryAndLivePermissionRecovery() {
        shell("am force-stop $appPackage");openMain()
        val formula=File(files,"saved-roll.txt").readText()
        assertEquals("Raw overlay dice survive force-stop and are shared with Compose",formula,findText(formula).text)
        screenshot("restored-main-result")
        findText("History").click();device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.text("Recent rolls")),30000))
        assertEquals("Exactly two attacks appear in history",2,device.findObjects(By.text(Pattern.compile(".*to hit · advantage"))).size)
        screenshot("recent-history")
        startOverlay();shell("am start -W -n com.yazsras.astravar.target/.TargetActivity")
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW deny")
        assertTrue(device.wait(Until.gone(By.desc("Expand Astravar crystal")),30000))
        shell("am force-stop $appPackage");openMain();findText("Floating controls").click();device.waitForIdle()
        assertFalse(device.hasObject(By.desc("Expand Astravar crystal")))
        shell("appops set $appPackage SYSTEM_ALERT_WINDOW allow");openMain();findText("Floating controls").click()
        assertTrue(device.wait(Until.hasObject(By.desc("Expand Astravar crystal")),30000))
        expand();stopPanel()
        checkpoint("Identical saved dice restored in main app and history; live permission revocation and recovery exercised")
    }
}
