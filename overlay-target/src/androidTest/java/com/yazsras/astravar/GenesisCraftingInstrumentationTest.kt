package com.yazsras.astravar

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

/** Ordered after GenesisJourney and its independent SAF proof; fictional emulator writes only. */
@RunWith(AndroidJUnit4::class)
class GenesisCraftingInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private val hero = "Genesis Native Hero"
    private val material = "Genesis Native Craft Material"
    private val invention = "Genesis Native Craft Design"
    private val requirement = "Genesis Native Craft Requirement"
    private val output = "Genesis Native Crafted Token"
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
    private fun captureDialog(name: String, title: String, vararg labels: String) {
        labels.forEach(::find)
        ui.captureStableDialog(File(files, "genesis-crafting-$name.png"), product, title,
            labels.map { By.text(it).pkg(product) })
    }

    @Test fun confirmedCraftConsumesOnceAndKeepsOneConnectedOutput() {
        assertEquals("Craft write fixtures require an emulator", "1",
            device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Character")
        // The preceding journey deliberately restores the older update fixture.
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
        tap(hero)
        find(hero)
        ui.ensurePrimaryTab(product, "Inventory")
        tap("Add item")
        enter("Item name", material)
        enter("Quantity", "3")
        tap("Review & save")
        tap("Save item")
        ui.ensurePrimaryTab(product, "Inventory")
        tap(material)
        find("Quantity 3 · carried")
        tap("Close")

        // A real source invention and linked requirement are configured in the
        // retained Journal UI. Merely proposing a recipe consumes no inventory.
        ui.campaignJournal(product)
        tap("Inventions")
        tap("Add Invention")
        enter("Invention name", invention)
        tap("Save invention")
        ui.expandDisclosure(product, "Required materials")
        tap("Add material")
        enter("Material requirement", requirement)
        enter("Required quantity (blank = unknown)", "1")
        tap("Choose…")
        tap(material)
        tap("Add material requirement")
        find("Requirement saved · nothing consumed")
        ui.expandDisclosure(product, "Required materials")
        find("1 required · 3 available")
        ui.expandDisclosure(product, "Physical output")
        tap("Confirm a resolved craft")
        find("Record connected craft")
        tap("Consume $requirement")
        enter("Actual quantity: $requirement", "1")
        tap("Choose output")
        enter("Crafted item name", output)
        enter("Output quantity", "1")
        enter("Output description", "Fictional plain token with no mechanical effects")
        enter("Confirmed scene or source", "Fictional emulator resolved craft")
        find("Choose an owner")
        tap(hero) // The output owner is deliberately selected, never inferred.
        find("No container")
        tap("Review connected craft")
        find("$hero · 1 consumed · 3 → 2")
        find("1 × $output")
        tap("I confirm this craft is resolved and these quantities are correct")
        tap("Confirm resolved craft") // Exactly one submit; all subsequent work reads its receipt.
        assertEquals("Committed craft opens its saved receipt", "Craft saved",
            ui.snapshot(By.text("Craft saved").pkg(product), "Saved craft receipt header", refreshCache = true) { node ->
                node.text?.takeIf { value -> value == "Craft saved" && !node.visibleBounds.isEmpty &&
                    !device.hasObject(By.text("Confirm resolved craft").pkg(product)) }
            })
        captureDialog("saved", "Craft saved", "1 × $output", "Return to invention")

        // Leave the saved receipt unacknowledged across real Activity recreation.
        // The editor must recover that outcome without issuing another craft.
        try {
            device.setOrientationLeft()
            find("Craft saved")
            find("1 × $output")
            find("Return to invention")
            assertFalse("A recovered saved result must not offer another submission",
                device.hasObject(By.text("Confirm resolved craft").pkg(product)))
            captureDialog("rotated-receipt", "Craft saved", "1 × $output", "Return to invention")
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        find("Craft saved")
        tap("Return to invention")
        find("Craft saved · $output")
        find(output)
        tap("Close")

        restart()
        ui.ensurePrimaryTab(product, "Inventory")
        find(output)
        assertEquals("One crafted physical item has one consolidated inventory row", 1,
            device.findObjects(By.text(output).pkg(product)).size)
        tap(material)
        find("Quantity 2 · carried")
        tap("Open $hero's item")
        find("Carried · Quantity 2")
        tap("Close")
        ui.ensurePrimaryTab(product, "Inventory")
        tap(output)
        find("Quantity 1 · carried")
        tap("Open $hero's item")
        find("Carried · Quantity 1")
        captureDialog("character-output", output, "Carried · Quantity 1", "Fictional plain token with no mechanical effects")
        tap("Close")
        ui.campaignJournal(product)
        tap("Inventions")
        tap(invention)
        ui.expandDisclosure(product, "Required materials")
        find("1 required · 2 available")
        ui.expandDisclosure(product, "Physical output")
        find("Confirmed craft output")
        find(output)
        captureDialog("project-output", invention, "Confirmed craft output", output)
        tap("Close")
        // Preserve the selected fixture expected by later update/import stages.
        ui.ensurePrimaryTab(product, "Character")
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
        tap("CI Native Hero Revised")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds("HP 31 / 37", product)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: Fictional connected craft created a carried material stack of three, linked an invention requirement, explicitly reviewed one consumed and one output owner, submitted once, recovered its unacknowledged result across rotation, acknowledged the saved receipt, and reopened material quantity two plus output quantity one through character and campaign views after restart. Independent host SQLite proof is a separate required stage.\n")
        })
    }
}
