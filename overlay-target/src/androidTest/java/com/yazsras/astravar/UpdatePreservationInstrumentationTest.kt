package com.yazsras.astravar

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Readback after adb install -r, without seeding or resetting the product. */
@RunWith(AndroidJUnit4::class)
class UpdatePreservationInstrumentationTest {
    @Test fun retainedRollAndPartyNavigationAfterSameKeyUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val product = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
        val files = instrumentation.targetContext.getExternalFilesDir(null)!!
        fun find(text: String): androidx.test.uiautomator.UiObject2 {
            for (direction in listOf(Direction.DOWN, Direction.UP)) repeat(14) {
                device.findObject(By.text(text).pkg(product))?.let { return it }
                device.findObject(By.scrollable(true).pkg(product))?.scroll(direction, .6f)
                device.waitForIdle()
            }
            error("Missing product control: $text")
        }
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("ASTRAVAR").pkg(product)), 60000))
        assertTrue("The saved labeled demo survived", device.hasObject(By.text("DEMO").pkg(product)))
        device.findObjects(By.text("Config").pkg(product)).last().click()
        find("Roll history").click()
        find("Recent rolls")
        assertEquals("Both committed attacks survive the APK update", 2,
            device.findObjects(By.text(Pattern.compile("d20 .*advantage.*")).pkg(product)).size)
        val savedFormula = File(files, "saved-roll.txt").readText()
        find(savedFormula)
        assertTrue(device.takeScreenshot(File(files, "same-key-update-history.png")))
        find("Close").click()
        device.findObjects(By.text("Campaign").pkg(product)).last().click()
        find("Inventory").click()
        find("CI FICTIONAL update-preservation crystal").click()
        find("Unknown quantity · Unknown")
        assertTrue(device.takeScreenshot(File(files, "same-key-update-inventory.png")))
        find("Close").click()
        device.findObjects(By.text("Party").pkg(product)).last().click()
        listOf("Zerg Hasma", "Eek", "Korr", "Veyrath").forEach { find(it) }
        // Layout acceptance captures are native emulator output, not a physical Galaxy test.
        device.executeShellCommand("wm size 1080x2400")
        for (scale in listOf("1.0", "1.3", "2.0")) {
            device.executeShellCommand("settings put system font_scale $scale")
            device.waitForIdle()
            device.findObjects(By.text("Armor").pkg(product)).last().click()
            assertTrue(device.takeScreenshot(File(files, "armor-font-$scale.png")))
            device.findObjects(By.text("Party").pkg(product)).last().click()
            find("Eek")
            assertTrue(device.takeScreenshot(File(files, "party-font-$scale.png")))
        }
        device.setOrientationLeft()
        device.waitForIdle()
        find("Eek")
        assertTrue(device.takeScreenshot(File(files, "party-landscape-large-font.png")))
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.executeShellCommand("settings put system font_scale 1.0")
        device.executeShellCommand("wm density 432")
        for (width in listOf(1080, 972)) {
            device.executeShellCommand("wm size ${width}x2400")
            device.waitForIdle()
            device.findObjects(By.text("Armor").pkg(product)).last().click()
            find("Active element").click()
            find("Choose an element")
            val outer = listOf("Fire", "Cold", "Lightning", "Acid", "Poison", "Thunder", "Necrotic", "Psychic", "Radiant")
            outer.forEach { name ->
                val node = device.findObject(By.descStartsWith("$name ").pkg(product))
                assertNotNull("Outer wheel element $name remains discoverable at width $width", node)
                assertTrue("$name touch bounds remain on screen", node.visibleBounds.let {
                    it.left >= 0 && it.top >= 0 && it.right <= device.displayWidth && it.bottom <= device.displayHeight
                })
            }
            assertNotNull(device.findObject(By.desc("Neutral Force").pkg(product)))
            assertTrue(device.takeScreenshot(File(files, "element-wheel-width-$width.png")))
            find("Radiant, Psychic & Necrotic unlocks").click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Artificer level 8").pkg(product)), 5000))
            find("Close").click()
        }
        device.executeShellCommand("wm density reset")
        device.executeShellCommand("wm size reset")
        device.waitForIdle()
        device.findObjects(By.text("Config").pkg(product)).last().click()
        find("ChatGPT").click()
        find("Find a prompt").click()
        val query = device.wait(Until.findObject(By.clazz("android.widget.EditText").focused(true).pkg(product)), 10000)
        assertNotNull(query)
        query.text = "Resume"
        device.pressBack()
        find("Copy handoff prompt").click()
        find("Copied — paste in ChatGPT")
        assertTrue(device.takeScreenshot(File(files, "native-chatgpt-prompt-copy.png")))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: same-key version update retained raw dice, history and demo; native party/armor large-font navigation exercised\n")
        })
    }
}
