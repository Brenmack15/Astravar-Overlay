package com.yazsras.astravar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

internal const val UPDATE_ITEM_SOURCE = "Fictional emulator fixture; no real campaign data"
internal const val UPDATE_ITEM_ID_EVIDENCE = "native-update-item-identity.txt"

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
        fun tap(text: String) = ui.tapText(text, product, scrollAttempts = 14)
        fun enter(label: String, value: String) {
            tap(label)
            // IME changes can leave UiAutomator's cached Compose focus stale.
            // Observe actual focus afresh; never retry the tap or weaken input checks.
            assertNotNull("Focused field for $label", ui.bounds(By.clazz("android.widget.EditText").focused(true).pkg(product), label, timeoutMs = 10_000, refreshCache = true))
            ui.setFocusedField(value, product, label)
            if (label == "Source / checkpoint (optional)") {
                ui.snapshot(By.clazz("android.widget.EditText").focused(true).pkg(product), "provenance remains visible above keyboard", refreshCache = true) { field ->
                    val keyboard = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        ?.let { Rect().also(it::getBoundsInScreen) }
                    val bounds = field.visibleBounds
                    true.takeIf { keyboard != null && !keyboard.isEmpty && !bounds.isEmpty &&
                        bounds.top >= 0 && bounds.bottom <= keyboard.top }
                }
                assertTrue(device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "native-item-provenance-keyboard.png")))
            }
            device.pressBack()
            ui.assertFocusedField(value, product, label)
        }
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
        ui.campaignItems(product)
        tap("Add Item")
        enter("Item name", "CI FICTIONAL update-preservation crystal")
        tap("Sources & advanced details")
        enter("Source / checkpoint (optional)", UPDATE_ITEM_SOURCE)
        tap("Save new item")
        assertTrue(device.wait(Until.hasObject(By.text("Item saved").pkg(product)), 15000))
        // The default blank quantity deliberately remains unknown through upgrade.
        find("Quantity not set")
        find("Status not set")
        if (ui.isAscension(product)) {
            ui.expandDisclosure(product, "Sources & record details")
            find(UPDATE_ITEM_SOURCE)
            find("Saved revision")
            val uuid = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
            val id = ui.texts(By.text(uuid).pkg(product), "Exact selected source item identity").single()
            assertEquals(id, UUID.fromString(id).toString())
            File(instrumentation.targetContext.getExternalFilesDir(null), UPDATE_ITEM_ID_EVIDENCE).writeText(id)
        }
    }
}
