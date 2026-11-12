package com.yazsras.astravar

import android.os.Bundle
import android.view.KeyEvent
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
    private fun tap(text: String) = ui.tapText(text, product, scrollAttempts = 18, preferLast = true)
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
    private fun stat(prefix: String) = ui.descriptionBounds(prefix, product)
    private fun acceptSharedReview(role: String, alreadyAgree: Boolean = false) {
        find("Campaign membership")
        ui.textBounds(role, product, scrollAttempts = 18, preferLast = true)
        tap("Review shared values")
        find("Review shared values")
        ui.textBounds(role, product, scrollAttempts = 18, preferLast = true)
        if (alreadyAgree) find("The shared values already agree.")
        tap("I reviewed these values and membership")
        tap("Accept reviewed values")
        find("Shared values connected and saved")
        capture("shared-${role.lowercase()}-review")
        tap("Close")
    }

    @Test fun characterAdvancementReviewedEquipmentCompanionAndRestart() {
        assertEquals("Fictional destructive fixtures require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Character")
        if (device.hasObject(By.desc("Switch character").pkg(product))) {
            // The older-build retention fixture intentionally survives into
            // these new-character journeys; do not reset it to regain onboarding.
            ui.tap(By.desc("Switch character").pkg(product), "Switch character")
            tap("Create character or companion")
        } else tap("Create character")
        enter("Name", "CI Native Hero")
        enter("Level", "0")
        tap("Review & save")
        tap("Save character")
        assertTrue("Invalid level remains visible and does not save", device.wait(Until.hasObject(By.textContains("Level must be").pkg(product)), 10_000))
        tap("Edit Identity")
        enter("Level", "4")
        tap("Ability scores")
        for ((ability, value) in listOf("STR" to "8", "DEX" to "14", "CON" to "14", "INT" to "16", "WIS" to "10", "CHA" to "12")) enter(ability, value)
        tap("Health & defense")
        enter("Unequipped maximum HP", "30")
        enter("Current HP (blank = unknown)", "24")
        enter("Unequipped Armor Class", "14")
        tap("Review & save")
        tap("Save character")
        find("CI Native Hero")
        stat("STR 8")
        capture("created-sheet")
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
        find("Characters & companions")
        ui.captureStableDialog(File(files, "player-first-character-selection.png"), product,
            "Characters & companions", listOf(By.text("CI Native Hero").pkg(product)))
        tap("Close")
        tap("Character setup")
        enter("Name", "CI Native Hero Revised")
        tap("Save character")
        find("CI Native Hero Revised")
        ui.selectSection(product, "Features")
        tap("Level up")
        enter("Hit Die roll or fixed gain (before Constitution)", "5")
        tap("Preview advancement")
        find("Level 4 → 5\nProficiency +2 → +3\nMaximum HP 30 → 37\nCurrent HP 24 → 31")
        capture("advancement-preview")
        tap("Confirm level 5")
        // The level-up action is below the sheet; its saved level is in the
        // header above it. Search that direction within the original deadline.
        ui.textBounds("Level 5 · Proficiency +3", product, scrollAttempts = 18, firstDirection = Direction.UP)
        stat("HP 31 / 37")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        find("CI Native Hero Revised")
        assertTrue(device.wait(Until.hasObject(By.text("Level 5 · Proficiency +3").pkg(product)), 10_000))
        stat("HP 31 / 37")
        ui.selectSection(product, "Sheet")
        tap("Campaign connections")
        // The new actor already has one shared identity. Explicitly review its
        // source/playable values; no second name-only record should be created.
        acceptSharedReview("Character", alreadyAgree = true)
        find("CI Native Hero Revised")
        stat("HP 31 / 37")
        capture("reviewed-campaign-identity")

        ui.ensurePrimaryTab(product, "Inventory")
        // One inventory exposes playable gear and source records. Exercise the
        // source-tools round trip, then create through the selected owner's path.
        find("Add item")
        tap("Campaign items")
        find("Equipment, treasures & discoveries")
        tap("‹ Inventory")
        find("Add item")
        tap("Add item")
        enter("Item name", "CI Native Strength Band")
        tap("Effects")
        tap("Add a structured effect")
        enter("Effect name", "CI Strength 19")
        enter("Value", "19")
        tap("Add effect to review")
        tap("Review & save")
        tap("I reviewed these mechanics")
        tap("Save reviewed item")
        find("CI Native Strength Band")
        find("Carried · Quantity 1")
        ui.captureStablePage(File(files, "player-first-inventory.png"), product, "Character",
            listOf(By.text("CI Native Strength Band").pkg(product), By.text("Carried · Quantity 1").pkg(product)))
        tap("Equip")
        find("STR: 8 → 19")
        capture("equipment-review")
        tap("Confirm change")
        find("Worn · Quantity 1")
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        stat("STR 19")
        capture("equipped-calculation")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        stat("STR 19")
        ui.ensurePrimaryTab(product, "Inventory")
        find("CI Native Strength Band")
        tap("CI Native Strength Band")
        tap("Open CI Native Hero Revised's item")
        tap("Unequip")
        find("STR: 19 → 8")
        tap("Confirm change")
        find("Carried · Quantity 1")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Equipment")
        find("CI Native Strength Band")
        find("Carried · Quantity 1")
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        stat("STR 8")

        tap("Roll dice")
        tap("Enter physical dice faces")
        enter("Faces in order (comma separated)", "13")
        tap("Validate entered dice")
        find("13")
        tap("Save roll")
        assertTrue("Saving returns to the current sheet and confirms the committed roll",
            device.wait(Until.hasObject(By.text("Roll saved · 13").pkg(product)), 10_000))
        assertFalse("Saving a roll must not automatically navigate", device.hasObject(By.text("Roll history").pkg(product)))
        capture("roll-saved-on-sheet")
        tap("View history")
        find("Roll history")
        find("1d20 · Manual · Player")
        tap("Dice details")
        find("13 on d20 · Modifier +0")
        capture("roll-history-breakdown")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: native manual roll saved without leaving the sheet; explicit history opens the saved formula, face and modifier\n")
        })

        ui.selectSection(product, "Spells")
        tap("Add spell")
        enter("Name", "CI Native Barrier")
        enter("Level (0 = cantrip)", "1")
        tap("Review & save")
        tap("Save spell")
        find("CI Native Barrier")
        tap("Add spell")
        enter("Name", "CI Native Signal")
        enter("Level (0 = cantrip)", "0")
        tap("Casting")
        tap("Concentration")
        tap("Review & save")
        tap("Save spell")
        find("CI Native Signal")
        ui.selectSection(product, "Cantrips")
        enter("Find a spell by name", "signal")
        find("CI Native Signal")
        assertFalse(device.hasObject(By.text("CI Native Barrier").pkg(product)))
        capture("spellbook-filtered")
        tap("Cast")
        tap("Preview casting cost")
        find("CI Native Signal · Concentration")
        tap("Cast & save")
        find("Saved on this device")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Combat")
        find("CI Native Signal")
        capture("spellbook-concentration-after-restart")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: native spellbook filtered by cantrip and name, reviewed casting, and reopened the saved concentration spell after restart\n")
        })

        ui.ensurePrimaryTab(product, "Companions")
        if (device.hasObject(By.text("Back to companions").pkg(product))) tap("Back to companions")
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("+ Add companion")
        enter("Companion name", "CI Native Ally")
        tap("Association")
        tap("Unassigned owner") // This journey tests a separately created manual relationship below.
        tap("Review & save")
        tap("Create companion")
        find("CI Native Ally")
        tap("Edit status")
        enter("Current HP (blank = unknown)", "4")
        enter("Maximum HP (blank = unknown)", "12")
        tap("Save character status")
        find("4 / 12")
        capture("generic-companion")
        ui.expandDisclosure(product, "Relationships")
        tap("Add relationship")
        // The initial target type is Character, verified by the typed target label.
        tap("Choose an existing record")
        tap("CI Native Hero Revised · Character")
        enter("Relationship to this record", "Fictional native journey partner")
        tap("Save relationship")
        find("CI Native Hero Revised")
        find("Fictional native journey partner")
        capture("companion-relationship")
        restart()
        ui.ensurePrimaryTab(product, "Companions")
        // Saved navigation may retain the member; return to the roster before finding it again.
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("CI Native Ally")
        find("4 / 12")
        ui.expandDisclosure(product, "Relationships")
        find("CI Native Hero Revised")
        find("Fictional native journey partner")
        tap("Edit relationship")
        enter("Relationship to this record", "Fictional native verified partner")
        tap("Save relationship")
        find("CI Native Hero Revised")
        find("Fictional native verified partner")
        capture("reopened-companion")
        tap("Remove relationship")
        // The confirmation title and action share text; select the actual final control.
        ui.tapText("Remove relationship", product, preferLast = true)
        find("No relationships recorded. Link a character, ally or campaign record.")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: native fictional character create/invalid level/edit/advance/restart; reviewed Strength equipment equip/restart/unequip/restart; reviewed fresh playable campaign identity; arbitrary companion create/edit HP/character relationship create/restart/edit/remove passed through product controls\n") })

        tap("Review for play")
        find("CI Native Ally")
        tap("Review campaign profile")
        find("Level 1 · HP 4 / 12")
        tap("I will review training, actions and current resources before relying on this sheet")
        tap("Review baselines & create sheet")
        tap("Save character")
        // Adoption preserves the original source snapshot. Connecting its
        // reviewed mechanics is a separate explicit decision, including role.
        acceptSharedReview("Companion")
        stat("HP 4 / 12")
        tap("Back to companions")
        find("Open playable sheet")
        capture("companion-playable-controls")

        tap("Go to combat")
        find("CI Native Ally")
        find("Turn economy")
        capture("companion-direct-combat")
        tap("Damage / healing")
        enter("Damage or healing amount", "2")
        // Native keyboard focus navigation leaves the entered amount intact and
        // stops the edit caret blinking during the unchanged full-frame capture.
        assertTrue("Move focus once without applying damage or healing", device.pressKeyCode(KeyEvent.KEYCODE_TAB))
        ui.snapshot(By.clazz("android.widget.EditText").text("2").pkg(product),
            "Entered healing amount remains 2 after focus leaves the field", refreshCache = true) { field ->
            true.takeIf { !field.isFocused && field.text == "2" }
        }
        ui.captureStableDialog(File(files, "player-first-health-dialog.png"), product, "Hit points",
            listOf(By.text("Heal").pkg(product), By.text("Take damage").pkg(product)))
        tap("Heal")
        find("Saved on this device")
        stat("HP 6 / 12")
        // The pager consumes the first Back from Combat to return to Sheet.
        // Verify that destination and its saved health before leaving the actor.
        device.pressBack()
        ui.snapshot(By.text("Sheet").pkg(product), "Back from companion Combat selects Sheet", refreshCache = true) { node ->
            true.takeIf { generateSequence(node) { it.parent }.take(12).any { it.isSelected && it.isEnabled } }
        }
        stat("HP 6 / 12")
        capture("companion-back-to-sheet")
        // The second Back follows the surrounding companion route to the roster.
        device.pressBack()
        find("Go to combat")
        find("6 / 12")

        restart()
        ui.ensurePrimaryTab(product, "Character")
        find("CI Native Hero Revised")
        stat("HP 31 / 37")
        ui.ensurePrimaryTab(product, "Companions")
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        tap("CI Native Ally")
        find("6 / 12")
        tap("Go to combat")
        find("CI Native Ally")
        stat("HP 6 / 12")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: native companion adopted and explicitly connected as Companion, entered Combat directly, saved healing once and reopened matching source/playable HP 6/12; main-character selection and HP31/37 remained unchanged. Original source snapshot retention is independently checked by repository and backup tests.\n")
        })
    }
}
