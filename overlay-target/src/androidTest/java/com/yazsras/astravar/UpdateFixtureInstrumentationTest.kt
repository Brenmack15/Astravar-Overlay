package com.yazsras.astravar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Creates fictional ledger data through ordinary product controls on a fresh CI emulator. */
@RunWith(AndroidJUnit4::class)
class UpdateFixtureInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    @Test fun seedFictionalInventoryThroughNativeEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val product = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
        val ui = ObservedUi(device)
        fun find(text: String) = ui.textBounds(text, product, scrollAttempts = 14)
        fun tap(text: String, preferLast: Boolean = false) = ui.tapText(text, product, scrollAttempts = 14, preferLast = preferLast)
        fun enter(label: String, value: String) {
            tap(label)
            assertNotNull("Focused field for $label", ui.bounds(By.clazz("android.widget.EditText").focused(true).pkg(product), label, timeoutMs = 10_000))
            ui.enterFocusedField(value, product, label)
            device.pressBack()
        }
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
        tap("Campaign", preferLast = true)
        tap("Inventory")
        tap("Add Item")
        enter("Item name", "CI FICTIONAL update-preservation crystal")
        enter("Source / checkpoint", "Fictional emulator fixture; no real campaign data")
        tap("Save new item")
        assertTrue(device.wait(Until.hasObject(By.text("Item saved").pkg(product)), 15000))
        // The default blank quantity deliberately remains unknown through upgrade.
        find("Unknown quantity · Unknown")
    }
}
