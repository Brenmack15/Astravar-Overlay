package com.yazsras.astravar

import android.os.Bundle
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Readback after adb install -r, without seeding or resetting the product. */
@RunWith(AndroidJUnit4::class)
class UpdatePreservationInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    @Test fun retainedRollAndPartyNavigationAfterSameKeyUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val product = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
        val files = instrumentation.targetContext.getExternalFilesDir(null)!!
        val ui = ObservedUi(device)
        fun find(text: String): Rect = ui.textBounds(text, product, scrollAttempts = 14)
        fun tap(text: String, preferLast: Boolean = false) = ui.tapText(text, product, scrollAttempts = 14, preferLast = preferLast)
        fun captureArmor(name: String) = ui.captureStablePage(File(files, name), product, "Armor",
            listOf(By.textStartsWith("Weapon profile ·").pkg(product), By.text("Ability scores").pkg(product)))
        fun captureParty(name: String) = ui.captureStablePage(File(files, name), product, "Party",
            listOf(By.text("Eek").pkg(product)))
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
        assertTrue("The saved labeled demo survived", device.hasObject(By.text("DEMO").pkg(product)))
        tap("Config", preferLast = true)
        tap("Roll history")
        find("Recent rolls")
        val firstFormula = File(files, "saved-first-roll.txt").readText()
        val savedFormula = File(files, "saved-roll.txt").readText()
        val historyFormulas = ui.texts(By.text(Pattern.compile("d20 .*")).pkg(product), "history after same-key APK update")
        assertEquals("Both committed attacks survive the APK update", 2, historyFormulas.size)
        assertEquals("The APK update preserves both original raw rolls and their modes",
            listOf(firstFormula, savedFormula).sorted(), historyFormulas.sorted())
        assertEquals("The first attack retains its one-shot advantage", 1,
            historyFormulas.count { it.contains("advantage") })
        assertEquals("The second attack retains its normal single-d20 result", 1,
            historyFormulas.count { !it.contains(" / ") && !it.contains("advantage") })
        find(savedFormula)
        assertTrue(device.takeScreenshot(File(files, "same-key-update-history.png")))
        tap("Close")
        tap("Campaign", preferLast = true)
        tap("Inventory")
        tap("CI FICTIONAL update-preservation crystal")
        find("Unknown quantity · Unknown")
        assertTrue(device.takeScreenshot(File(files, "same-key-update-inventory.png")))
        tap("Close")
        tap("Party", preferLast = true)
        find("Party & family")
        listOf("Zerg Hasma", "Eek", "Korr", "Veyrath").forEach { find(it) }
        // Layout acceptance captures are native emulator output, not a physical Galaxy test.
        device.executeShellCommand("wm size 1080x2400")
        for (scale in listOf("1.0", "1.3", "2.0")) {
            device.executeShellCommand("settings put system font_scale $scale")
            device.waitForIdle()
            tap("Armor", preferLast = true)
            captureArmor("armor-font-$scale.png")
            tap("Party", preferLast = true)
            find("Eek")
            captureParty("party-font-$scale.png")
        }
        device.setOrientationLeft()
        device.waitForIdle()
        find("Eek")
        captureParty("party-landscape-large-font.png")
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.executeShellCommand("settings put system font_scale 1.0")
        device.executeShellCommand("wm density 432")
        for (width in listOf(1080, 972)) {
            device.executeShellCommand("wm size ${width}x2400")
            device.waitForIdle()
            tap("Armor", preferLast = true)
            captureArmor("element-width-$width-armor.png")
            device.dumpWindowHierarchy(File(files, "element-width-$width-armor.xml"))
            tap("Active element")
            find("Choose an element")
            val outer = listOf("Fire", "Cold", "Lightning", "Acid", "Poison", "Thunder", "Necrotic", "Psychic", "Radiant")
            outer.forEach { name ->
                val bounds = ui.bounds(By.descStartsWith("$name ").pkg(product), "Outer wheel element $name at width $width")
                assertNotNull("Outer wheel element $name remains discoverable at width $width", bounds)
                assertTrue("$name touch bounds remain on screen", bounds.let {
                    it.left >= 0 && it.top >= 0 && it.right <= device.displayWidth && it.bottom <= device.displayHeight
                })
            }
            assertNotNull(ui.bounds(By.desc("Neutral Force").pkg(product), "Neutral Force at width $width"))
            assertTrue(device.takeScreenshot(File(files, "element-wheel-width-$width.png")))
            tap("Radiant, Psychic & Necrotic unlocks")
            assertTrue(device.wait(Until.hasObject(By.textContains("Artificer level 8").pkg(product)), 5000))
            tap("Close")
            assertTrue("One Close must dismiss the element chooser before another display resize at width $width",
                device.wait(Until.gone(By.text("Choose an element").pkg(product)), 5000))
            assertTrue(device.takeScreenshot(File(files, "element-wheel-closed-width-$width.png")))
            device.dumpWindowHierarchy(File(files, "element-wheel-closed-width-$width.xml"))
        }
        device.executeShellCommand("wm density reset")
        device.executeShellCommand("wm size reset")
        device.waitForIdle()
        tap("Config", preferLast = true)
        tap("ChatGPT")
        tap("Find a prompt")
        val query = ui.bounds(By.clazz("android.widget.EditText").focused(true).pkg(product), "focused ChatGPT prompt search", timeoutMs = 10_000)
        assertNotNull(query)
        ui.enterFocusedField("Resume", product, "ChatGPT prompt search")
        device.pressBack()
        tap("Copy handoff prompt")
        find("Copied — paste in ChatGPT")
        assertTrue(device.takeScreenshot(File(files, "native-chatgpt-prompt-copy.png")))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: same-key version update retained raw dice, history and demo; native party/armor large-font navigation exercised\n")
        })
    }
}
