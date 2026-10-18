package com.yazsras.astravar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Creates fictional ledger data through ordinary product controls on a fresh CI emulator. */
@RunWith(AndroidJUnit4::class)
class UpdateFixtureInstrumentationTest {
    @Test fun seedFictionalInventoryThroughNativeEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val product = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
        fun find(text: String): androidx.test.uiautomator.UiObject2 {
            for (direction in listOf(Direction.DOWN, Direction.UP)) repeat(14) {
                device.findObject(By.text(text).pkg(product))?.let { return it }
                device.findObject(By.scrollable(true).pkg(product))?.scroll(direction, .6f)
                device.waitForIdle()
            }
            error("Missing native inventory control: $text")
        }
        fun enter(label: String, value: String) {
            find(label).click()
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText").focused(true).pkg(product)), 10000)
            assertNotNull("Focused field for $label", input)
            input.text = value
            device.pressBack()
        }
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("ASTRAVAR").pkg(product)), 60000))
        repeat(3) { device.findObject(By.text("Close").pkg(product))?.let { it.click(); device.waitForIdle() } }
        device.findObjects(By.text("Campaign").pkg(product)).last().click()
        find("Inventory").click()
        find("Add Item").click()
        enter("Item name", "CI FICTIONAL update-preservation crystal")
        enter("Source / checkpoint", "Fictional emulator fixture; no real campaign data")
        find("Save new item").click()
        assertTrue(device.wait(Until.hasObject(By.text("Item saved").pkg(product)), 15000))
        // The default blank quantity deliberately remains unknown through upgrade.
        find("Unknown quantity · Unknown")
    }
}
