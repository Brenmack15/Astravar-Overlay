package com.yazsras.astravar

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Invoked in two explicit stages with ordinary Package Manager upgrade between them. */
@RunWith(AndroidJUnit4::class)
class GenesisUpgradeInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val candidateVersion get() = InstrumentationRegistry.getArguments().getString("astravarExpectedVersion")?.toInt() ?: 51
    private fun retainedHp(value: Int) = "HP $value / 32" + if (candidateVersion >= 59) ", 3 temporary" else ""
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private fun tap(label: String) = ui.tapText(label, product, scrollAttempts = 18)
    private fun find(label: String) = ui.textBounds(label, product, scrollAttempts = 18)
    private fun enter(label: String, value: String) {
        tap(label); ui.setFocusedField(value, product, label)
        device.pressBack(); ui.assertFocusedField(value, product, label)
    }
    private fun launch() {
        assertEquals("Upgrade fixtures require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
    }

    private fun enterNamed(label: String, value: String) {
        val field = By.clazz("android.widget.EditText").pkg(product).hasDescendant(By.text(label))
        find(label)
        ui.tap(field, "Focus $label")
        val editor = ui.snapshot(field, "$label has focus", refreshCache = true) { box ->
            box.takeIf { it.isFocused && it.isEnabled && !it.visibleBounds.isEmpty }
        }
        // Edit this named accessibility node once. A hardware keyboard can leave the IME hidden.
        editor.text = value
        ui.snapshot(field, "Exact input for $label", refreshCache = true) { box ->
            box.text?.takeIf { box.isFocused && it == value }
        }
        val imeVisible = instrumentation.uiAutomation.windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                !Rect().also { window.getBoundsInScreen(it) }.isEmpty
        }
        if (imeVisible) device.pressBack()
        ui.assertFocusedField(value, product, label)
        ui.snapshot(field, "Saved input for $label", refreshCache = true) { box ->
            box.text?.takeIf { it == value && box.isFocused }
        }
    }

    @Test fun seedAscensionCode58() {
        launch()
        assertTrue(device.executeShellCommand("dumpsys package $product").contains("versionCode=58 "))
        ui.ensurePrimaryTab(product, "Character")
        tap("Create character")
        enterNamed("Name", "Ascension upgrade fixture")
        tap("Next")
        listOf("STR" to "8", "DEX" to "12", "CON" to "14", "INT" to "15", "WIS" to "10", "CHA" to "10").forEach { (stat,value) -> enterNamed(stat, value) }
        tap("Next")
        enterNamed("Unequipped maximum HP", "32")
        enterNamed("Current HP (blank = unknown)", "17")
        enterNamed("Temporary HP", "3")
        enterNamed("Unequipped Armor Class", "16")
        tap("Review & save")
        find("STR 8 · DEX 12 · CON 14 · INT 15 · WIS 10 · CHA 10")
        find("17 / 32")
        tap("Save character")
        ui.descriptionBounds("HP 17 / 32", product)
        ui.captureStablePage(File(files, "api37-before-code58.png"), product, "Character",
            listOf(By.text("Ascension upgrade fixture").pkg(product)))
    }

    @Test fun verifyAscensionOnAndroid17() {
        assertEquals(37, android.os.Build.VERSION.SDK_INT)
        assertEquals("17", android.os.Build.VERSION.RELEASE)
        assertTrue(candidateVersion >= 59)
        val theme = InstrumentationRegistry.getArguments().getString("ascensionTheme")
        require(theme in setOf("light", "dark"))
        launch()
        assertTrue(device.executeShellCommand("dumpsys package $product").contains("versionCode=$candidateVersion "))
        ui.ensurePrimaryTab(product, "Character")
        find("Ascension upgrade fixture")
        ui.descriptionBounds(retainedHp(17), product)
        ui.selectSection(product, "Sheet")
        val intelligence = ui.descriptionBounds("Intelligence score 15", product)
        // The title lies above the independent check/save targets within the same card.
        assertTrue(device.click(intelligence.centerX(), intelligence.top + maxOf(1, intelligence.height() / 22)))
        find("Permanent 15 → effective 15")
        tap("Character source evidence")
        find("INT · base 15 · effective 15")
        find("Ascension upgrade fixture")
        tap("Close")
        ui.descriptionBounds(retainedHp(17), product)
        val markers = mapOf("Character" to By.desc(retainedHp(17)), "Inventory" to By.text("Inventions & research"),
            "Connections" to By.text("Companions"), "Chronicle" to By.text("Encounters"))
        for ((tab, marker) in markers) {
            ui.ensurePrimaryTab(product, tab)
            ui.captureStablePage(File(files, "api37-$theme-$tab.png"), product, tab, listOf(marker.pkg(product)))
        }
        ui.ensurePrimaryTab(product, "Character")
    }

    @Test fun seedCode49() {
        launch()
        assertTrue("Baseline is the historical code49 build",
            device.executeShellCommand("dumpsys package $product").contains("versionCode=49 "))
        ui.ensurePrimaryTab(product, "Character")
        tap("Create character")
        enter("Name", "Genesis Legacy Retention Actor")
        tap("Ability scores")
        for ((stat, value) in listOf("STR" to "12", "DEX" to "12", "CON" to "14", "INT" to "14", "WIS" to "10", "CHA" to "10")) enter(stat, value)
        tap("Health, defense & movement")
        enter("Unequipped maximum HP", "32")
        enter("Current HP (blank = unknown)", "17")
        enter("Temporary HP", "3")
        enter("Unequipped Armor Class", "16")
        tap("Save character")
        ui.descriptionBounds("HP 17 / 32", product)
        tap("Campaign connections")
        tap("I reviewed this name-only campaign identity")
        tap("Create campaign identity")
        find("Campaign identity ready")
        tap("Close")
        ui.selectSection(product, "Equipment")
        tap("Add item")
        enter("Item name", "Genesis Legacy Retention Compass")
        // Historical rules correctly forbid equipping a multi-item stack.
        // Keep worn equipment and a separate quantity-bearing carried stack.
        enter("Quantity", "1")
        tap("Save item")
        tap("Equip")
        tap("Confirm change")
        find("Worn · Quantity 1")
        tap("Add item")
        enter("Item name", "Genesis Legacy Retention Supplies")
        enter("Quantity", "3")
        tap("Save item")
        find("Carried · Quantity 3")
        ui.selectSection(product, "Resources")
        tap("Add resource")
        enter("Name", "Genesis Legacy Retention Charges")
        enter("Capacity", "5")
        enter("Current uses (blank = unknown)", "2")
        tap("Save resource")
        find("2 / 5")
        ui.ensurePrimaryTab(product, "Companions")
        tap("+ Add companion")
        enter("Companion name", "Genesis Legacy Retention Ally")
        tap("Create companion")
        tap("Edit status")
        enter("Current HP (blank = unknown)", "7")
        enter("Maximum HP (blank = unknown)", "18")
        tap("Save character status")
        find("7 / 18")
        ui.expandDisclosure(product, "Relationships")
        tap("Add relationship")
        tap("Choose an existing record")
        tap("Genesis Legacy Retention Actor · Character")
        enter("Relationship to this record", "Fictional retained ally")
        tap("Save relationship")
        find("Fictional retained ally")
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds("HP 17 / 32", product)
        ui.captureStablePage(File(files, "genesis-before-code49-sheet.png"), product, "Character",
            listOf(By.text("Genesis Legacy Retention Actor").pkg(product)))
    }

    @Test fun verifyAfterUpgrade() {
        launch()
        assertTrue("Candidate has a newer version code",
            device.executeShellCommand("dumpsys package $product").contains("versionCode=$candidateVersion "))
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Legacy Retention Actor")
        ui.descriptionBounds(retainedHp(17), product)
        if (candidateVersion < 59) find("Temp HP 3")
        ui.captureStablePage(File(files, "genesis-after-code$candidateVersion-sheet.png"), product, "Character",
            listOf(By.text("Genesis Legacy Retention Actor").pkg(product)))
        ui.selectSection(product, "Equipment")
        find("Genesis Legacy Retention Compass")
        find("Worn · Quantity 1")
        find("Genesis Legacy Retention Supplies")
        find("Carried · Quantity 3")
        ui.selectSection(product, "Resources")
        find("Genesis Legacy Retention Charges")
        if (candidateVersion >= 59) {
            find("2/5")
            ui.bounds(By.desc("2/5 remaining").pkg(product), "Accessible retained resource balance")
        } else find("2 / 5")
        val companionTab = if (candidateVersion >= 59) "Connections" else "Companions"
        ui.ensurePrimaryTab(product, companionTab)
        if (candidateVersion >= 59) ui.selectSection(product, "Companions")
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("Genesis Legacy Retention Ally")
        // Reopening a companion may restore the scroll offset from its Relationships
        // section. The retained HP is in the sheet header, above that saved offset.
        ui.textBounds("7 / 18", product, scrollAttempts = 18, firstDirection = Direction.UP)
        ui.expandDisclosure(product, "Relationships")
        find("Genesis Legacy Retention Actor")
        find("Fictional retained ally")
        ui.captureStablePage(File(files, "genesis-upgrade-companion.png"), product, companionTab,
            listOf(By.text("Fictional retained ally").pkg(product)))
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds(retainedHp(17), product)
    }

    /** Runs only after host comparison proves the untouched schema6→8 cutover. */
    @Test fun canonicalWriteSurvivesRestart() {
        launch()
        assertTrue("Canonical write proof requires the candidate build",
            device.executeShellCommand("dumpsys package $product").contains("versionCode=$candidateVersion "))
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Legacy Retention Actor")
        ui.descriptionBounds(retainedHp(17), product)
        ui.tap(By.desc(retainedHp(17)).pkg(product), "Open retained actor hit points")
        enter("Damage or healing amount", "1")
        tap("Heal")
        ui.descriptionBounds(retainedHp(18), product)
        device.executeShellCommand("am force-stop $product")
        launch()
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Legacy Retention Actor")
        ui.descriptionBounds(retainedHp(18), product)
        if (candidateVersion < 59) find("Temp HP 3")
        ui.captureStablePage(File(files, "genesis-canonical-write-after-restart.png"), product, "Character",
            listOf(By.text("Genesis Legacy Retention Actor").pkg(product), By.desc(retainedHp(18)).pkg(product)))
        // The host reads all three stopped stores next: exactly this revision
        // and its receipt must be canonical; the original file must be intact.
    }
}
