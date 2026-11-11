package com.yazsras.astravar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
        enter("Quantity", "3")
        tap("Save item")
        tap("Equip")
        tap("Confirm change")
        find("Worn · Quantity 3")
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
            device.executeShellCommand("dumpsys package $product").contains("versionCode=50 "))
        ui.ensurePrimaryTab(product, "Character")
        find("Genesis Legacy Retention Actor")
        ui.descriptionBounds("HP 17 / 32", product)
        find("Temporary 3")
        ui.captureStablePage(File(files, "genesis-after-code50-sheet.png"), product, "Character",
            listOf(By.text("Genesis Legacy Retention Actor").pkg(product)))
        ui.selectSection(product, "Equipment")
        find("Genesis Legacy Retention Compass")
        find("Worn · Quantity 3")
        ui.selectSection(product, "Resources")
        find("Genesis Legacy Retention Charges")
        find("2 / 5")
        ui.ensurePrimaryTab(product, "Companions")
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("Genesis Legacy Retention Ally")
        find("7 / 18")
        ui.expandDisclosure(product, "Relationships")
        find("Genesis Legacy Retention Actor")
        find("Fictional retained ally")
        ui.captureStablePage(File(files, "genesis-upgrade-companion.png"), product, "Companions",
            listOf(By.text("Fictional retained ally").pkg(product)))
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds("HP 17 / 32", product)
    }
}
