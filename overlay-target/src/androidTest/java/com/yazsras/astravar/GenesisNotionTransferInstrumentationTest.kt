package com.yazsras.astravar

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Actual SAF selection and protected preview on the disposable connected-journey actor. */
@RunWith(AndroidJUnit4::class)
class GenesisNotionTransferInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private fun tap(label: String) = ui.tapText(label, product, scrollAttempts = 18, preferLast = true)
    private fun find(label: String) = ui.textBounds(label, product, scrollAttempts = 18)
    private fun restart() {
        device.executeShellCommand("am force-stop $product")
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
    }
    private fun enabled(label: String): Boolean = ui.snapshot(By.text(label).pkg(product),
        "enabled state of $label", refreshCache = true) {
        var node: UiObject2? = device.findObjects(By.text(label).pkg(product)).lastOrNull()
        repeat(12) {
            val current = node ?: return@snapshot null
            if (current.isClickable) return@snapshot current.isEnabled
            node = current.parent
        }
        null
    }

    @Test fun selectedFilePreviewRequiresBackupAndCancelPreservesAllRecords() {
        assertEquals("Notion preview fixtures require an emulator", "1",
            device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Character")
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
        tap("Genesis Native Hero")
        find("Genesis Native Hero")
        ui.selectSection(product, "Equipment")
        tap("Import character & inventory from Notion file")
        find("Import Notion character records")
        tap("Choose private Notion transfer file")
        val roots = By.desc(Pattern.compile("(?i)show (roots|navigation drawer)"))
        val picker = ui.snapshot(roots, "Notion document picker locations", timeoutMs = 10_000,
            refreshCache = true) { it.applicationPackage?.takeIf { name -> name != product } }
        ui.tap(roots.pkg(picker), "Show document picker locations", timeoutMs = 10_000, refreshCache = true)
        val rootsList = By.res(picker, "roots_list")
        ui.tapWithin(rootsList, By.text("Downloads").pkg(picker), "Downloads document root", timeoutMs = 10_000)
        ui.snapshot(By.res(picker, "breadcrumb_text").text("Downloads"), "Notion fixture Downloads directory",
            timeoutMs = 10_000, refreshCache = true) { node ->
            true.takeIf { !node.visibleBounds.isEmpty && !device.hasObject(rootsList) }
        }
        ui.tap(By.text("astravar-native-notion-preview.json").pkg(picker), "Select prepared fictional Notion transfer",
            timeoutMs = 10_000, refreshCache = true)
        find("Source: Genesis Native Hero, level 1, 2026-11-16")
        find("Preview: 1 new items · 0 already present · 1 intentionally deferred.")
        assertFalse("Unbacked import cannot commit", enabled("Apply reviewed transfer"))
        tap("Items to add (1)")
        find("Native Notion Preview Lantern")
        tap("Not imported as playable items (1)")
        find("Native Notion Consumed Chalk (Consumed; Genesis Native Hero; Confirmed)")
        find("Save verified current backup")
        assertFalse("Reviewing items does not bypass verified backup or consent", enabled("Apply reviewed transfer"))
        tap("Close")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Native Hero")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "\nCHECKPOINT: native Notion file selection, exact-character preview, eligible/deferred items, disabled unbacked commit, cancel and restart passed. Independent host proof must confirm every campaign table unchanged. Native apply was not performed.\n") })
    }
}
