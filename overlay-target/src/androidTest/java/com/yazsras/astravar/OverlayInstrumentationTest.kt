package com.yazsras.astravar

import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.regex.Pattern

/** Black-box successors to the retired overlay journeys. No product hooks or RNG overrides. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OverlayInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private val ui get() = ObservedUi(device)
    private fun tap(text: String) = ui.tapText(text, product)
    private fun shell(command: String) = device.executeShellCommand(command)
    private fun openMain() {
        shell("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
    }
    private fun screenshot(name: String) = assertTrue(device.takeScreenshot(File(files, "api${android.os.Build.VERSION.SDK_INT}-$name.png")))
    private fun formula(description: String, normal: Boolean = false): String {
        val pattern = if (normal) "d20 [0-9]+ \\+ -?[0-9]+ = -?[0-9]+" else "d20 .*"
        val selector = By.text(Pattern.compile(pattern)).pkg(product)
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(12) {
                instrumentation.uiAutomation.clearCache()
                val found = device.findObjects(selector).firstOrNull { !it.visibleBounds.isEmpty }
                if (found != null) return found.text
                ui.scrollForObservation(product, direction)
                device.waitForIdle(1_000)
            }
        }
        error("No visible committed formula: $description")
    }
    private fun checkpoint(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: $text\n") })

    @Test fun aAutomaticRollingKeyboardRotationAndLargeText() {
        Configurator.getInstance().setWaitForIdleTimeout(1000).setWaitForSelectorTimeout(30000)
        try {
            openMain()
            ui.openTool(product, "Armor weapon")
            tap("Start labeled demo")
            ui.textBounds("Roll attack", product)
            // Reopen after onboarding; the same tool now shows the preserved engine.
            tap("Close")
            ui.openTool(product, "Armor weapon")
            ui.textBounds("Roll attack", product)
            ui.tap(By.desc("Advantage").pkg(product), "Advantage")
            tap("Roll attack")
            val first = formula("first committed advantage formula")
            assertTrue("Both real d20s and advantage must remain visible", first.contains(" / ") && first.contains("advantage"))
            File(files, "saved-first-roll.txt").writeText(first)
            assertFalse("Automatic weapon rolls need no manual damage field", device.hasObject(By.clazz("android.widget.EditText").pkg(product)))
            screenshot("standalone-advantage-result")
            tap("Roll attack")
            val second = formula("second committed normal formula", normal = true)
            assertFalse("One-shot advantage resets after its committed attack", second.contains(" / ") || second.contains("advantage"))
            File(files, "saved-roll.txt").writeText(second)
            screenshot("standalone-normal-result")

            tap("Options")
            tap("Distance in feet")
            ui.enterFocusedField("60", product, "native distance keyboard")
            val geometry = ui.snapshot(By.clazz("android.widget.EditText").focused(true).pkg(product), "field above native IME") { field ->
                val ime = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                if (ime == null) null else {
                    val keyboard = Rect().also { ime.getBoundsInScreen(it) }
                    val bounds = Rect(field.visibleBounds)
                    if (keyboard.isEmpty || bounds.isEmpty || bounds.bottom > keyboard.top) null else bounds to keyboard
                }
            }
            assertTrue(geometry.first.bottom <= geometry.second.top)
            screenshot("standalone-keyboard")
            device.pressBack()
            // Keyboard dismissal moves the modal's footer. Observe the exact
            // typed value and settled closed-IME geometry before touching Save.
            var settledDistance: Rect? = null
            var settledSince = 0L
            ui.snapshot(By.clazz("android.widget.EditText").focused(true).pkg(product),
                "distance remains exact after native keyboard dismissal", refreshCache = true) { field ->
                val keyboardShown = instrumentation.uiAutomation.windows.any {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !Rect().also(it::getBoundsInScreen).isEmpty
                }
                val bounds = Rect(field.visibleBounds)
                if (keyboardShown || field.text != "60" || bounds.isEmpty) {
                    settledDistance = null
                    null
                } else {
                    if (settledDistance != bounds) { settledDistance = bounds; settledSince = SystemClock.uptimeMillis() }
                    true.takeIf { SystemClock.uptimeMillis() - settledSince >= 120 }
                }
            }
            tap("Save")
            // Saving preferences is asynchronous. Rotation begins only after
            // the actual save closes its modal and exposes the same raw roll.
            assertTrue("Attack options save must close its modal before rotation",
                device.wait(Until.gone(By.text("Attack options").pkg(product)), 30_000))
            ui.textBounds(second, product)
            device.setOrientationLeft()
            device.waitForIdle()
            ui.textBounds(second, product)
            screenshot("standalone-landscape-result")
            device.setOrientationNatural()
            device.unfreezeRotation()
            shell("settings put system font_scale 1.3")
            device.waitForIdle()
            ui.textBounds("Roll attack", product)
            screenshot("standalone-large-text-result")
            shell("settings put system font_scale 1.0")
            tap("Close")
            checkpoint("Two real automatic weapon attacks, raw dice, one-shot advantage, native keyboard, rotation and large text exercised through Tools")
        } catch (error: Throwable) {
            // Preserve the failing layout before cleanup changes display geometry.
            runCatching { screenshot("failure-before-configuration-cleanup") }
            runCatching { device.dumpWindowHierarchy(File(files, "failure-before-configuration-cleanup.xml")) }
            throw error
        } finally {
            // A failed landscape assertion must not change the next test's environment.
            runCatching { device.setOrientationNatural() }
            runCatching { device.unfreezeRotation() }
            runCatching { shell("settings put system font_scale 1.0") }
            runCatching { device.waitForIdle(1_000) }
        }
    }

    @Test fun bRetiredOverlayCannotRequestPermissionsOrStart() {
        val info = instrumentation.targetContext.packageManager.getPackageInfo(product, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        val permissions = info.requestedPermissions.orEmpty().toList()
        assertFalse(permissions.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(permissions.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(info.services.orEmpty().any { it.name.endsWith(".OverlayService") })
        // UiDevice shell output omits stderr, where am reports a missing service.
        // Use the Android API result directly instead of interpreting stdout text.
        val result = instrumentation.targetContext.startService(Intent().setComponent(
            ComponentName(product, "com.yazsras.astravar.overlay.OverlayService")))
        assertNull("Android must return no component for the retired overlay service", result)
        openMain()
        ui.openTool(product, "Appearance")
        ui.textBounds("Appearance & feedback", product)
        assertFalse(device.hasObject(By.text("Start overlay session").pkg(product)))
        assertFalse(device.hasObject(By.descStartsWith("Expand Astravar crystal").pkg(product)))
        screenshot("standalone-appearance")
        tap("Close")
        checkpoint("Installed native package has no overlay permission/service and Android rejects its retired start path")
    }

    @Test fun cRollRestorationMainHistoryAndPrimaryNavigation() {
        shell("am force-stop $product")
        openMain()
        ui.openTool(product, "Armor weapon")
        val saved = File(files, "saved-roll.txt").readText()
        ui.textBounds(saved, product)
        assertEquals(saved, formula("saved formula after force-stop", normal = true))
        tap("Close")
        ui.openTool(product, "Roll history")
        ui.textBounds("Recent rolls", product)
        val history = ui.texts(By.text(Pattern.compile("d20 .*" )).pkg(product), "saved history formulas")
        assertEquals("Two immutable attacks, with raw d20s, survive restart", listOf(File(files, "saved-first-roll.txt").readText(), saved).sorted(), history.sorted())
        assertEquals(1, history.count { it.contains("advantage") })
        screenshot("standalone-restored-history")
        tap("Close")
        for (destination in listOf("Character", "Inventory", "Companions", "Campaign")) {
            ui.ensurePrimaryTab(product, destination)
            screenshot("primary-${destination.lowercase()}")
        }
        checkpoint("Saved raw dice restored after force-stop and every current primary destination selected through real native touches")
    }
}
