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

/** Continues the isolated fictional player journey; never seeds owner campaign data. */
@RunWith(AndroidJUnit4::class)
class VisualRebirthJourneyInstrumentationTest {
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
    private fun restart() {
        device.executeShellCommand("am force-stop $product")
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
    }
    private fun capture(name: String, tab: String, marker: String) {
        find(marker)
        ui.captureStablePage(File(files, "rebirth-$name.png"), product, tab, listOf(By.text(marker).pkg(product)))
    }
    private fun assertTouch(text: String) {
        val bounds = ui.textBounds(text, product, scrollAttempts = 18, interactive = true)
        val minimum = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        assertTrue("$text retains a 48dp visible target", bounds.width() >= minimum && bounds.height() >= minimum)
        assertTrue("$text stays inside the native screen", bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
    }
    private fun campaignHome() {
        ui.ensurePrimaryTab(product, "Campaign")
        if (device.hasObject(By.text("‹ Campaign").pkg(product))) tap("‹ Campaign")
    }
    private fun companionRoster() {
        ui.ensurePrimaryTab(product, "Companions")
        // A process restart can restore the exact playable companion route.
        // Use its real Back action before returning from profile to roster.
        if (device.hasObject(By.text("Back to companions").pkg(product))) tap("Back to companions")
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
    }

    @Test fun resourcesCompanionGearJournalAndLargeText() {
        assertEquals("Fictional write fixtures require an emulator", "1",
            device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Sheet")
        find("CI Native Hero Revised")
        capture("character-statistics", "Character", "Saving throws")

        // Resource use is explicit; neither a rest nor a refill is inferred.
        ui.selectSection(product, "Resources")
        tap("Add resource")
        enter("Name", "CI Native Charges")
        enter("Capacity", "3")
        enter("Current uses (blank = unknown)", "2")
        tap("Save resource")
        find("CI Native Charges")
        find("2 / 3")
        find("none")
        tap("Use one")
        find("1 / 3")
        restart()
        ui.ensurePrimaryTab(product, "Character")
        ui.selectSection(product, "Resources")
        find("CI Native Charges")
        find("none")
        capture("resource-persistence", "Character", "1 / 3")

        // Gear belongs to the exact reviewed companion sheet, independently of
        // its original source record and the selected main character.
        companionRoster()
        capture("companion-selection", "Companions", "CI Native Ally")
        tap("CI Native Ally")
        tap("Open playable sheet")
        ui.selectSection(product, "Equipment")
        tap("Add item")
        enter("Item name", "CI Native Ally Charm")
        tap("Save item")
        find("CI Native Ally Charm")
        tap("Equip")
        tap("Confirm change")
        find("Worn · Quantity 1")
        restart()
        companionRoster()
        tap("CI Native Ally")
        find("4 / 12")
        tap("Open playable sheet")
        ui.selectSection(product, "Equipment")
        find("CI Native Ally Charm")
        capture("companion-equipped-after-restart", "Companions", "Worn · Quantity 1")
        tap("Back to companions")

        // A real directory edit, entered through the native keyboard, must
        // survive a process restart before its saved text is accepted.
        campaignHome()
        capture("campaign-dashboard", "Campaign", "Connections")
        tap("Connections")
        ui.tap(By.desc("Add connection").pkg(product), "Add fictional campaign connection")
        enter("Connection name", "CI Native Archivist")
        enter("Overview & appearance", "Fictional archive keeper for native verification.")
        tap("Save new connection")
        find("Connection saved locally.")
        tap("Edit")
        enter("Overview & appearance", "Fictional archive keeper; edited and retained.")
        tap("Save connection changes")
        find("Connection saved locally.")
        restart()
        campaignHome()
        tap("Connections")
        tap("CI Native Archivist")
        find("Fictional archive keeper; edited and retained.")
        ui.captureStableDialog(File(files, "rebirth-campaign-npc-after-restart.png"), product, "CI Native Archivist",
            listOf(By.text("Fictional archive keeper; edited and retained.").pkg(product)))
        tap("Close")

        // 400dp portrait width approximates a compact modern phone; these are
        // emulator layouts, never a claim of physical Galaxy S26 acceptance.
        try {
            device.executeShellCommand("wm size 1080x2400")
            device.executeShellCommand("wm density 432")
            for (scale in listOf("1.0", "1.3", "2.0")) {
                device.executeShellCommand("settings put system font_scale $scale")
                restart()
                ui.ensurePrimaryTab(product, "Character")
                ui.selectSection(product, "Sheet")
                find("CI Native Hero Revised")
                val hp = By.descStartsWith("HP 31 / 37").pkg(product)
                ui.bounds(hp, "unchanged main-character health at font scale $scale", refreshCache = true)
                ui.captureStablePage(File(files, "rebirth-character-font-$scale.png"), product, "Character", listOf(hp))
                assertTouch("Roll dice")
                capture("character-actions-font-$scale", "Character", "Roll dice")
                ui.selectSection(product, "Spells")
                capture("spellbook-font-$scale", "Character", "CI Native Signal")
                // Traversing every direct section also verifies that horizontal
                // scrolling exposes complete labels at the largest text setting.
                for (section in listOf("Equipment", "Combat", "Resources", "History", "Sheet")) {
                    ui.selectSection(product, section)
                }
            }
        } catch (error: Throwable) {
            // Preserve the exact failing density/font/viewport before cleanup can
            // recreate the activity and make its later failure XML misleading.
            runCatching { device.takeScreenshot(File(files, "rebirth-failure-before-display-reset.png")) }
            runCatching { device.dumpWindowHierarchy(File(files, "rebirth-failure-before-display-reset.xml")) }
            runCatching {
                File(files, "rebirth-failure-before-display-reset.txt").writeText(
                    device.executeShellCommand("wm size") + device.executeShellCommand("wm density") +
                        "font_scale=" + device.executeShellCommand("settings get system font_scale"))
            }
            throw error
        } finally {
            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("wm density reset")
            device.executeShellCommand("wm size reset")
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: explicit fictional resource spending, exact companion equipment, and edited campaign NPC survived restart; primary sheet, actions, spellbook and all direct sections exercised at 1.0, 1.3 and 2.0 font scales with stable native screenshots and visible 48dp roll targets\n")
        })
    }
}
