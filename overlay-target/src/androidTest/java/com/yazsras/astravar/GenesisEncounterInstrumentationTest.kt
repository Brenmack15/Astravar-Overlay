package com.yazsras.astravar

import android.os.Bundle
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

/** Fictional tracker-only writes, ordered after the independent connected crafting proof. */
@RunWith(AndroidJUnit4::class)
class GenesisEncounterInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private val hero = "Genesis Native Hero"
    private val npc = "Genesis Native Encounter Witness"
    private val encounter = "Genesis Native Manual Encounter"
    private fun tap(label: String) = ui.tapText(label, product, scrollAttempts = 18, preferLast = true)
    private fun find(label: String) = ui.textBounds(label, product, scrollAttempts = 18)
    private fun savedPhase(label: String, completedAction: String) {
        // Each committed transition replaces the upper status immediately.
        // Observe it before any traversal can scroll that status out of view.
        assertEquals("Saved encounter phase after $completedAction", label,
            ui.snapshot(By.text(label).pkg(product), "Saved encounter phase: $label", refreshCache = true) { node ->
                node.text?.takeIf { value -> value == label && !node.visibleBounds.isEmpty &&
                    !device.hasObject(By.text(completedAction).pkg(product)) }
            })
    }
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
        ui.snapshot(By.text(label).pkg(product), "encounter actor selected $label", refreshCache = true) { node ->
            true.takeIf { generateSequence(node) { it.parent }.take(12).any { it.isSelected && it.isEnabled } }
        }
    }
    private fun openEncounter() {
        ui.ensurePrimaryTab(product, "Campaign")
        if (device.hasObject(By.text("‹ Campaign").pkg(product))) tap("‹ Campaign")
        ui.textBounds("Encounters", product, scrollAttempts = 18, firstDirection = Direction.UP)
        tap("Encounters")
        tap(encounter)
    }
    private fun capture(name: String, vararg markers: String) {
        markers.forEach(::find)
        ui.captureStableDialog(File(files, "genesis-encounter-$name.png"), product, encounter,
            markers.map { By.text(it).pkg(product) })
    }

    @Test fun manualOrderRoundsAndReopeningNeverChangeActorMechanics() {
        assertEquals("Encounter fixtures require a disposable emulator", "1",
            device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Campaign")
        if (device.hasObject(By.text("‹ Campaign").pkg(product))) tap("‹ Campaign")
        ui.selectSection(product, "People")
        find("Genesis Native Campaign")
        tap("Create NPC")
        enter("NPC name", npc)
        tap("Review & save")
        tap("Create NPC")
        find("NPC saved in campaign records")
        find(npc)
        ui.textBounds("Encounters", product, scrollAttempts = 18, firstDirection = Direction.UP)
        tap("Encounters")
        tap("Create encounter")
        enter("Encounter name", encounter)
        enter("Encounter notes", "Fictional manual tracker without mechanical changes")
        tap("Participants")
        tap("Add $npc")
        tap("Add $hero")
        enter("Initiative: $npc", "19")
        enter("Initiative: $hero", "7")
        tap("Move $hero up") // Deliberately place the lower initiative first; never auto-sort.
        tap("Review & save")
        find("1. $hero")
        find("2. $npc")
        tap("Save encounter")
        savedPhase("Setup · confirm the order before starting", "Save encounter")
        tap("Review start")
        find("Initiative: 7")
        find("Initiative: 19")
        tap("I confirm this turn order")
        tap("Start encounter")
        savedPhase("Round 1", "Start encounter")
        find("Current turn: $hero")
        tap("View $npc's source")
        find("This participant remains a source record. Encounter tracking does not create a playable sheet or invent statistics.")
        assertFalse("A source-only participant has no invented playable action route",
            device.hasObject(By.text("Open $npc's actions").pkg(product)))
        tap("Close")

        tap("Open $hero's actions")
        find(hero)
        assertSection("Combat")
        find("Turn economy")
        ui.descriptionBounds("HP 14 / 20", product)
        device.pressBack()
        assertSection("Sheet")
        ui.descriptionBounds("HP 14 / 20", product)
        device.pressBack()
        find(encounter)
        find("Round 1")
        find("Current turn: $hero")
        tap("Next turn")
        find("Round 1")
        find("Current turn: $npc")
        tap("Next turn")
        find("Round 2")
        find("Current turn: $hero")
        capture("round-two", "Round 2", "Current turn: $hero")
        tap("End encounter")
        find("End encounter?")
        tap("End encounter")
        savedPhase("Ended · round 2", "End encounter")
        tap("Close")

        restart()
        openEncounter()
        find("Ended · round 2")
        capture("ended-after-restart", "Ended · round 2", "Reopen setup")
        tap("Reopen setup")
        find("Reopen setup?")
        tap("Reopen setup")
        savedPhase("Setup · confirm the order before starting", "Reopen setup")
        find("1. $hero")
        find("2. $npc")
        tap("Close")
        restart()
        openEncounter()
        find("Setup · confirm the order before starting")
        assertFalse("Ordinary reopening does not start or advance the tracker",
            device.hasObject(By.textStartsWith("Current turn:").pkg(product)))
        capture("setup-after-restart", "Setup · confirm the order before starting", "Review start")
        tap("Close")
        ui.ensurePrimaryTab(product, "Character")
        ui.tap(By.desc("Switch character").pkg(product), "Switch character")
        tap("CI Native Hero Revised")
        ui.selectSection(product, "Sheet")
        ui.descriptionBounds("HP 31 / 37", product)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: Fictional same-campaign hero and source-only NPC entered manually ordered initiative7/19, explicitly started, navigated to actor Combat and back through Sheet, advanced through two participants to round2, ended, restarted, explicitly reopened setup, and restarted again without automatic turns. Independent host proof must verify all actor/source mechanics and prior histories unchanged.\n")
        })
    }
}
