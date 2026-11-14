package com.yazsras.astravar

import android.graphics.Rect
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import java.util.regex.Pattern

/** Real gestures and editor writes on a new fictional actor; never an owner's device. */
@RunWith(AndroidJUnit4::class)
class GenesisJourneyInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private fun tap(label: String) = ui.tapText(label, product, scrollAttempts = 18, preferLast = true)
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
    private fun downloads(): String {
        val roots = By.desc(Pattern.compile("(?i)show (roots|navigation drawer)"))
        val picker = ui.snapshot(roots, "System document picker exposes its locations", timeoutMs = 10_000,
            refreshCache = true) { it.applicationPackage?.takeIf { name -> name != product } }
        ui.tap(roots.pkg(picker), "Show document picker locations", timeoutMs = 10_000, refreshCache = true)
        // DocumentsUI also exposes Downloads in the toolbar and breadcrumb behind
        // the drawer. Select its real root row from one fresh container snapshot.
        val rootsList = By.res(picker, "roots_list")
        ui.tapWithin(rootsList, By.text("Downloads").pkg(picker), "Downloads document root", timeoutMs = 10_000)
        assertTrue("Downloads must open and the locations drawer must close",
            ui.snapshot(By.res(picker, "breadcrumb_text").text("Downloads"), "Open Downloads directory",
                timeoutMs = 10_000, refreshCache = true) { node ->
                true.takeIf { !node.visibleBounds.isEmpty && !device.hasObject(rootsList) }
            })
        return picker
    }
    private fun saveDocument(name: String) {
        assertTrue("Actual CreateDocument opens",
            ui.snapshot(By.clazz("android.widget.EditText"), "CreateDocument filename", timeoutMs = 15_000,
                refreshCache = true) { node -> true.takeIf { node.isEnabled && !node.visibleBounds.isEmpty } })
        val picker = downloads()
        val filename = By.clazz("android.widget.EditText").pkg(picker)
        ui.tap(filename, "CreateDocument filename", timeoutMs = 10_000, refreshCache = true)
        ui.setFocusedField(name, picker, "CreateDocument filename")
        assertEquals("CreateDocument must retain the exact requested filename", name,
            ui.snapshot(filename, "CreateDocument filename before saving", timeoutMs = 10_000,
                refreshCache = true) { it.text?.takeIf { value -> value == name } })
        ui.tap(By.text(Pattern.compile("(?i)save")).pkg(picker), "Save the document", timeoutMs = 10_000,
            refreshCache = true)
    }
    private fun openBackup() {
        tap("Tools")
        tap("Records, backups & connection")
        tap("Complete backup")
        find("Keep connected records together")
    }

    @Test fun guidedCreationSwipesQuickActionsAndConnectedInventory() {
        assertEquals("Genesis write tests require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Campaign")
        if (device.hasObject(By.text("‹ Campaign").pkg(product))) tap("‹ Campaign")
        tap("Choose campaign")
        tap("Create campaign")
        enter("Campaign name", "Genesis Native Campaign")
        enter("Campaign notes", "Fictional native connected-record verification")
        tap("Review & save")
        tap("Save campaign")
        find("Genesis Native Campaign")
        tap("Create NPC")
        enter("NPC name", "Genesis Native Contact")
        tap("Review & save")
        tap("Create NPC")
        find("NPC saved in campaign records")
        find("Genesis Native Contact")
        restart()
        ui.ensurePrimaryTab(product, "Campaign")
        find("Genesis Native Campaign")
        find("Genesis Native Contact")
        capture("connected-campaign", "Campaign", "Genesis Native Campaign", "Genesis Native Contact")
        ui.ensurePrimaryTab(product, "Character")
        find("CI Native Hero Revised")
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
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
        assertSection("Sheet")
        // Roll dice belongs near the top of Sheet. The observed code50 run
        // exhausted discovery scrolling down through skills before reversing;
        // reveal this known upper control first within the same 30-second budget.
        ui.textBounds("Roll dice", product, scrollAttempts = 18, firstDirection = Direction.UP)
        capture("character-sheet", "Character", "Genesis Native Hero", "Roll dice")

        // Creation is already connected. Explicit review confirms the same identity
        // and membership, rather than silently manufacturing an independent copy.
        ui.ensurePrimaryTab(product, "Campaign")
        find("Genesis Native Hero")
        tap("Genesis Native Hero")
        tap("Review shared values")
        find("The shared values already agree.")
        assertFalse("A review requires its explicit acknowledgment", enabled("Accept reviewed values"))
        tap("I reviewed these values and membership")
        tap("Accept reviewed values")
        find("Shared values connected and saved")
        tap("Close")
        ui.ensurePrimaryTab(product, "Companions")
        if (device.hasObject(By.text("Back to companions").pkg(product))) tap("Back to companions")
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("+ Add companion")
        enter("Companion name", "Genesis Native Familiar")
        tap("Review & save")
        find("Genesis Native Campaign")
        find("Genesis Native Hero")
        tap("Create companion")
        // Creation opens the new sheet at its header. Await the exact saved
        // name before scrolling; discovery swipes can otherwise leave that
        // known header while the asynchronous save is finishing.
        assertEquals("Created companion opens with its exact saved name", "Genesis Native Familiar",
            ui.snapshot(By.text("Genesis Native Familiar").pkg(product),
                "New companion's saved name", refreshCache = true) { node ->
                node.text?.takeIf { name -> name == "Genesis Native Familiar" && !node.visibleBounds.isEmpty &&
                    device.findObject(By.text("Companion sheet").pkg(product))?.visibleBounds?.isEmpty == false &&
                    !device.hasObject(By.text("Create companion").pkg(product)) }
            })
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Native Hero")

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

        // Optional shortcuts now live in Character controls, away from the
        // primary page strip. Preserve their drag/tap and persistence coverage.
        assertFalse("The primary sheet has no redundant Health shortcut", device.hasObject(By.text("Health").pkg(product)))
        assertFalse("The primary sheet has no redundant Actions shortcut", device.hasObject(By.text("Actions").pkg(product)))
        ui.tap(By.desc("Character controls").pkg(product), "Open optional character controls")
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
        assertFalse("Restart does not restore the redundant primary shortcut row", device.hasObject(By.text("Health").pkg(product)))
        ui.tap(By.desc("Character controls").pkg(product), "Reopen saved character controls")
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

        val originalFontScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 2.0")
            device.setOrientationLeft()
            assertEquals("Actual system font scale is 200 percent", "2.0", device.executeShellCommand("settings get system font_scale").trim())
            assertSection("Features")
            find("Edit identity & features")
            val minimumTarget = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
            val hp = ui.controlBounds(By.desc("HP 14 / 20").pkg(product), "Compact health control")
            assertTrue("Compact HP remains a 48dp touch target", hp.width() >= minimumTarget && hp.height() >= minimumTarget)
            val controls = ui.controlBounds(By.desc("Character controls").pkg(product), "Compact character controls")
            assertTrue("Compact controls remain a 48dp touch target", controls.width() >= minimumTarget && controls.height() >= minimumTarget)
            capture("landscape-font200", "Character", "Edit identity & features")
            ui.touch(controls, "Open all character controls at 200 percent")
            ui.descriptionBounds("Switch character", product)
            tap("Health")
            find("Damage or healing amount")
            tap("Close")
        } finally {
            if (originalFontScale == "null") device.executeShellCommand("settings delete system font_scale")
            else {
                require(originalFontScale.toFloatOrNull() != null)
                device.executeShellCommand("settings put system font_scale $originalFontScale")
            }
            device.setOrientationNatural(); device.unfreezeRotation()
        }
        assertSection("Features")

        // Actual SAF creation, process restart, document selection, protected review
        // and one explicit merge. No product intents or direct database setup bypass.
        openBackup()
        tap("Save complete backup")
        saveDocument("astravar-genesis-native-connected.json")
        find("Complete backup saved and independently verified.")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        openBackup()
        tap("Choose backup")
        val picker = downloads()
        ui.tap(By.text("astravar-genesis-native-connected.json").pkg(picker),
            "Actual saved connected document is selectable", timeoutMs = 20_000, refreshCache = true)
        find("Review complete backup")
        assertFalse("Merge is disabled before a verified current backup", enabled("Confirm merge"))
        tap("Save current backup")
        saveDocument("astravar-genesis-native-before-merge.json")
        find("Current records have a verified backup.")
        tap("Confirm merge")
        find("Connected backup records merged and verified on this device.")
        restart()
        ui.ensurePrimaryTab(product, "Campaign")
        find("Genesis Native Campaign")
        find("Genesis Native Contact")
        find("Genesis Native Familiar")
        find("Genesis Native Hero")
        ui.ensurePrimaryTab(product, "Inventory")
        find("Genesis Native Compass Revised")
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Native Hero")
        ui.descriptionBounds("HP 14 / 20", product)

        // Leave the established fictional fixture selected for downstream
        // update-preservation stages. Genesis records remain for readback.
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
        tap("CI Native Hero Revised")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds("HP 31 / 37", product)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: Genesis native campaign/NPC/source-companion/character creation and explicit membership review; horizontal flicks, reading positions, Back, quick-action drag/tap ordering and restart; contextual inventory edits; rotation and 200 percent landscape controls; actual SAF complete backup save, protected same-file merge and restart verified on fictional emulator records. Independent host database/document proof is a separate required stage.\n")
        })
    }
}
