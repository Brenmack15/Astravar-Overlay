package com.yazsras.astravar

import android.graphics.Rect
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real gestures and editor writes on a new fictional actor; never an owner's device. */
@RunWith(AndroidJUnit4::class)
class GenesisJourneyInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private fun tap(label: String) = ui.tapText(label, product, scrollAttempts = 18)
    private fun find(label: String) = ui.textBounds(label, product, scrollAttempts = 18)
    private fun enter(label: String, value: String) {
        tap(label); ui.setFocusedField(value, product, label)
        device.pressBack(); ui.assertFocusedField(value, product, label)
    }
    private fun restart() {
        device.executeShellCommand("am force-stop $product")
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
    }
    private fun assertSection(label: String) {
        ui.snapshot(By.text(label).pkg(product), "gesture-selected $label tab", refreshCache = true) { node ->
            generateSequence(node) { it.parent }.take(12).firstOrNull { it.isSelected && it.isEnabled }
                ?.let { Rect(it.visibleBounds) }?.takeUnless { it.isEmpty }
        }
    }
    private fun pageViewport(): Rect = ui.snapshot(By.clazz("android.widget.ScrollView").pkg(product),
        "visible character page's vertical viewport", refreshCache = true) {
        device.findObjects(By.clazz("android.widget.ScrollView").pkg(product))
            .map { node -> Rect(node.visibleBounds) }.filter { bounds -> !bounds.isEmpty }
            .maxByOrNull { bounds -> bounds.height() }
    }
    private fun swipePage(left: Boolean) {
        val bounds = pageViewport()
        val inset = bounds.width() / 6
        assertTrue("Dispatch one native page flick inside the observed content viewport",
            device.swipe(if (left) bounds.right - inset else bounds.left + inset, bounds.centerY(),
                if (left) bounds.left + inset else bounds.right - inset, bounds.centerY(), 25))
        device.waitForIdle(1_000)
    }
    private fun capture(name: String, primary: String, vararg labels: String) {
        labels.forEach(::find)
        ui.captureStablePage(File(files, "genesis-$name.png"), product, primary, labels.map { By.text(it).pkg(product) })
    }

    @Test fun guidedCreationSwipesQuickActionsAndConnectedInventory() {
        assertEquals("Genesis write tests require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Character")
        find("CI Native Hero Revised")
        tap("Switch character")
        tap("Create character or companion")
        enter("Name", "Genesis Native Hero")
        tap("Health & defense")
        enter("Unequipped maximum HP", "20")
        enter("Current HP (blank = unknown)", "14")
        enter("Unequipped Armor Class", "13")
        tap("Review & save")
        tap("Save character")
        find("Genesis Native Hero")
        ui.descriptionBounds("HP 14 / 20", product)
        capture("character-sheet", "Character", "Genesis Native Hero", "Roll dice")

        // Move vertically first. The header remains reachable and each section
        // retains its own offset when a real horizontal touch changes the page.
        find("Perception")
        val before = ui.bounds(By.text("Perception").pkg(product), "sheet reading position", refreshCache = true)
        ui.descriptionBounds("HP 14 / 20", product)
        swipePage(left = true)
        assertSection("Combat")
        find("Add attack")
        capture("swiped-actions", "Character", "Add attack")
        swipePage(left = false)
        assertSection("Sheet")
        val after = ui.bounds(By.text("Perception").pkg(product), "restored sheet reading position", refreshCache = true)
        assertTrue("Swiping back preserves the exact reading position", abs(before.top - after.top) <= 3)
        swipePage(left = true)
        assertSection("Combat")
        device.pressBack()
        assertSection("Sheet")

        // Both drag and accessible taps change the same persisted quick-action order.
        ui.tap(By.desc("Customize quick actions").pkg(product), "Customize quick actions")
        val health = ui.bounds(By.desc("Drag Health to reorder").pkg(product), "Health drag handle", refreshCache = true)
        val actions = ui.bounds(By.desc("Drag Actions to reorder").pkg(product), "Actions drag handle", refreshCache = true)
        assertTrue("Long-press drag reorders one quick action",
            device.drag(health.centerX(), health.centerY(), actions.centerX(), actions.centerY() + 12, 45))
        val movedHealth = ui.bounds(By.desc("Drag Health to reorder").pkg(product), "moved Health handle", refreshCache = true)
        val movedActions = ui.bounds(By.desc("Drag Actions to reorder").pkg(product), "moved Actions handle", refreshCache = true)
        assertTrue("The drag changes visible order", movedActions.top < movedHealth.top)
        ui.tap(By.desc("Move Health up").pkg(product), "Move Health up without dragging")
        ui.tap(By.desc("Move Health down").pkg(product), "Move Health down without dragging")
        tap("Save quick actions")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Native Hero")
        val quickActions = ui.bounds(By.text("Actions").pkg(product), "persisted Actions shortcut", refreshCache = true)
        val quickHealth = ui.bounds(By.text("Health").pkg(product), "persisted Health shortcut", refreshCache = true)
        assertTrue("Quick-action order survives process restart", quickActions.left < quickHealth.left)
        tap("Health")
        find("Damage or healing amount")
        tap("Close")

        // Inventory's contextual Add item opens this actor's authoritative editor.
        ui.ensurePrimaryTab(product, "Inventory")
        tap("Add item")
        enter("Item name", "Genesis Native Compass")
        tap("Review & save")
        tap("Save item")
        ui.ensurePrimaryTab(product, "Inventory")
        find("Genesis Native Compass")
        assertEquals("One physical item has one inventory row", 1,
            device.findObjects(By.text("Genesis Native Compass").pkg(product)).size)
        capture("unified-inventory", "Inventory", "Genesis Native Compass", "In play")
        tap("Genesis Native Compass")
        tap("Open Genesis Native Hero's item")
        tap("Edit")
        enter("Item name", "Genesis Native Compass Revised")
        tap("Save item")
        ui.ensurePrimaryTab(product, "Inventory")
        find("Genesis Native Compass Revised")
        assertFalse("Editing the same record does not retain a duplicate old row",
            device.hasObject(By.text("Genesis Native Compass").pkg(product)))
        restart()
        ui.ensurePrimaryTab(product, "Inventory")
        find("Genesis Native Compass Revised")
        capture("edited-item-after-restart", "Inventory", "Genesis Native Compass Revised")

        // Real Activity recreation via rotation retains the selected character page.
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Features")
        try {
            device.setOrientationLeft()
            assertSection("Features")
            capture("landscape-features", "Character", "Edit identity & features")
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        assertSection("Features")
        ui.descriptionBounds("HP 14 / 20", product)

        // Leave the established fictional fixture selected for downstream
        // update-preservation stages. Genesis records remain for readback.
        tap("Switch character")
        tap("CI Native Hero Revised")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds("HP 31 / 37", product)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: Genesis native creation, horizontal flicks, independent reading positions, Back, quick-action drag/tap ordering and restart, contextual inventory creation/edit/readback, one-row identity and Activity rotation verified on fictional emulator records.\n")
        })
    }
}
