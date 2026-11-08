package com.yazsras.astravar

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real screen/database journeys using only new, explicitly fictional records. */
@RunWith(AndroidJUnit4::class)
class PlayerFirstJourneyInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private fun tap(text: String) = ui.tapText(text, product, scrollAttempts = 18)
    private fun find(text: String) = ui.textBounds(text, product, scrollAttempts = 18)
    private fun enter(label: String, value: String) {
        tap(label)
        ui.setFocusedField(value, product, label)
        device.pressBack()
        ui.assertFocusedField(value, product, label)
    }
    private fun capture(name: String) {
        assertTrue(device.takeScreenshot(File(files, "player-first-$name.png")))
        device.dumpWindowHierarchy(File(files, "player-first-$name.xml"))
    }
    private fun restart() {
        device.executeShellCommand("am force-stop $product")
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
    }
    private fun stat(prefix: String) = ui.bounds(By.descStartsWith(prefix).pkg(product), "calculated $prefix")

    @Test fun characterAdvancementReviewedEquipmentCompanionAndRestart() {
        assertEquals("Fictional destructive fixtures require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Character")
        tap("Create character")
        enter("Name", "CI Native Hero")
        enter("Level", "0")
        tap("Save character")
        assertTrue("Invalid level remains visible and does not save", device.wait(Until.hasObject(By.textContains("Level must be").pkg(product)), 10_000))
        enter("Level", "4")
        tap("Ability scores")
        for ((ability, value) in listOf("STR" to "8", "DEX" to "14", "CON" to "14", "INT" to "16", "WIS" to "10", "CHA" to "12")) enter(ability, value)
        tap("Health, defense & movement")
        enter("Unequipped maximum HP", "30")
        enter("Current HP (blank = unknown)", "24")
        enter("Unequipped Armor Class", "14")
        tap("Save character")
        find("CI Native Hero")
        stat("STR 8")
        capture("created-sheet")
        tap("Character setup")
        enter("Name", "CI Native Hero Revised")
        tap("Save character")
        find("CI Native Hero Revised")
        tap("Level up")
        enter("Hit Die roll or fixed gain (before Constitution)", "5")
        tap("Preview advancement")
        find("Level 4 → 5\nProficiency +2 → +3\nMaximum HP 30 → 37\nCurrent HP 24 → 31")
        capture("advancement-preview")
        tap("Confirm level 5")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        find("CI Native Hero Revised")
        assertTrue(device.wait(Until.hasObject(By.text("Level 5 · Proficiency +3").pkg(product)), 10_000))
        stat("HP 31 / 37")

        ui.ensurePrimaryTab(product, "Inventory")
        tap("My gear")
        tap("Add item")
        enter("Item name", "CI Native Strength Band")
        tap("Magic & mechanical effects")
        tap("Add a structured effect")
        enter("Effect name", "CI Strength 19")
        enter("Value", "19")
        tap("Add effect to review")
        tap("I reviewed these mechanics")
        tap("Save reviewed item")
        find("CI Native Strength Band")
        find("Carried · Quantity 1")
        tap("Equip")
        find("STR: 8 → 19")
        capture("equipment-review")
        tap("Confirm change")
        find("Worn · Quantity 1")
        ui.ensurePrimaryTab(product, "Character")
        stat("STR 19")
        capture("equipped-calculation")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        stat("STR 19")
        ui.ensurePrimaryTab(product, "Inventory")
        tap("My gear")
        find("CI Native Strength Band")
        tap("Unequip")
        find("STR: 19 → 8")
        tap("Confirm change")
        restart()
        ui.ensurePrimaryTab(product, "Inventory")
        tap("My gear")
        find("CI Native Strength Band")
        find("Carried · Quantity 1")
        ui.ensurePrimaryTab(product, "Character")
        stat("STR 8")

        ui.ensurePrimaryTab(product, "Companions")
        tap("+ Add companion")
        enter("Companion name", "CI Native Ally")
        tap("Create companion")
        find("CI Native Ally")
        tap("Edit status")
        enter("Current HP (blank = unknown)", "4")
        enter("Maximum HP (blank = unknown)", "12")
        tap("Save character status")
        capture("generic-companion")
        tap("‹ Party")
        // Character links use saved campaign identities. Create a clearly fictional
        // source character through its real editor; do not alias a playable-only ID.
        ui.openTool(product, "Character sources")
        tap("Edit status")
        enter("Character name (optional)", "CI Native Handler")
        tap("Save character status")
        find("CI Native Handler")
        tap("Close")
        ui.ensurePrimaryTab(product, "Companions")
        tap("CI Native Ally")
        tap("Relationships")
        tap("Add relationship")
        // The initial target type is Character, verified by the typed target label.
        tap("Choose an existing record")
        tap("CI Native Handler · Character")
        enter("Relationship to this record", "Fictional native journey partner")
        tap("Save relationship")
        find("character profile · Fictional native journey partner")
        capture("companion-relationship")
        restart()
        ui.ensurePrimaryTab(product, "Companions")
        // Saved navigation may retain the member; return to the roster before finding it again.
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("CI Native Ally")
        find("HP 4 / 12")
        tap("Relationships")
        find("character profile · Fictional native journey partner")
        tap("Edit relationship")
        enter("Relationship to this record", "Fictional native verified partner")
        tap("Save relationship")
        find("character profile · Fictional native verified partner")
        capture("reopened-companion")
        tap("Remove relationship")
        // The confirmation title and action share text; select the actual final control.
        ui.tapText("Remove relationship", product, preferLast = true)
        find("No relationships recorded. Link a character, ally or campaign record.")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: native fictional character create/invalid level/edit/advance/restart; reviewed Strength equipment equip/restart/unequip/restart; arbitrary companion create/edit HP/character relationship create/restart/edit/remove passed through product controls\n") })
    }
}
