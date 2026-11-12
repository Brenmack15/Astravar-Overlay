package com.yazsras.astravar

import android.os.Bundle
import android.graphics.Rect
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
    private fun capture(name: String, tab: String, marker: String, interactiveMarker: Boolean = false) {
        // Interactive captures measure only a fully revealed native target.
        // Static text retains the existing rendered-marker acceptance checks.
        if (interactiveMarker) assertTouch(marker) else find(marker)
        ui.captureStablePage(File(files, "rebirth-$name.png"), product, tab, listOf(By.text(marker).pkg(product)))
    }
    private fun assertTouch(text: String) {
        val minimum = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        ui.textBounds(text, product, scrollAttempts = 18, interactive = true)
        repeat(6) { attempt ->
            // Accessibility can expose only the top of a button at the bottom
            // scroll edge. Detach its real target and containing viewport, then
            // reveal it before measuring; an undersized full target still fails.
            val (bounds, viewport) = ui.snapshot(By.text(text).pkg(product),
                "fresh touch target and vertical viewport for $text", refreshCache = true) { label ->
                val action = generateSequence(label) { it.parent }.take(12)
                    .firstOrNull { it.isEnabled && it.isClickable } ?: return@snapshot null
                val scroll = generateSequence(action.parent) { it.parent }.take(12)
                    .firstOrNull { it.className == "android.widget.ScrollView" }
                Rect(action.visibleBounds) to scroll?.let { Rect(it.visibleBounds) }
            }
            val fullTarget = bounds.width() >= minimum && bounds.height() >= minimum &&
                (viewport == null || viewport.contains(bounds))
            val atTop = viewport != null && bounds.top <= viewport.top
            val atBottom = viewport != null && bounds.bottom >= viewport.bottom
            if (!fullTarget && viewport != null && !viewport.isEmpty && (atTop || atBottom) && attempt < 5) {
                val distance = (minimum - bounds.height()).coerceAtLeast(0) + minimum / 2
                val start = viewport.centerY()
                val end = (start + if (atTop) distance else -distance)
                    .coerceIn(viewport.top + 4, viewport.bottom - 5)
                assertTrue("Reveal $text inside its observed vertical viewport",
                    device.swipe(viewport.centerX(), start, viewport.centerX(), end, 20))
                device.waitForIdle(1_000)
            } else {
                assertTrue("$text retains a 48dp visible target: bounds=$bounds viewport=$viewport minimum=$minimum",
                    fullTarget)
                assertTrue("$text stays inside the native screen", bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
                return
            }
        }
    }
    private fun campaignHome() {
        ui.campaignJournal(product)
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
        tap("Review & save")
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

        // Gear belongs to the exact reviewed companion and projects into its
        // shared source record without changing the selected main character.
        companionRoster()
        capture("companion-selection", "Companions", "CI Native Ally")
        tap("CI Native Ally")
        tap("Open playable sheet")
        ui.selectSection(product, "Equipment")
        tap("Add item")
        enter("Item name", "CI Native Ally Charm")
        tap("Review & save")
        tap("Save item")
        find("CI Native Ally Charm")
        tap("Equip")
        tap("Confirm change")
        find("Worn · Quantity 1")
        restart()
        companionRoster()
        tap("CI Native Ally")
        find("6 / 12")
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
                capture("character-actions-font-$scale", "Character", "Roll dice", interactiveMarker = true)
                ui.selectSection(product, "Spells")
                capture("spellbook-font-$scale", "Character", "CI Native Signal")
                // Traversing every direct section also verifies that horizontal
                // scrolling exposes complete labels at the largest text setting.
                for (section in listOf("Equipment", "Combat", "Features", "Resources", "History", "Sheet")) {
                    ui.selectSection(product, section)
                }
                // Read-only primary-destination captures retain their upper
                // headings and actions for the same enlarged-text review.
                ui.campaignItems(product)
                capture("inventory-font-$scale", "Inventory", "Equipment, treasures & discoveries")
                campaignHome()
                capture("campaign-font-$scale", "Campaign", "Your living world")
                ui.ensurePrimaryTab(product, "Character")
                ui.selectSection(product, "Sheet")
                find("CI Native Hero Revised")
                ui.bounds(hp, "main-character health retained after read-only destination visits at font scale $scale",
                    refreshCache = true)
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
            putString("stream", "\nCHECKPOINT: explicit fictional resource spending, exact companion equipment, and edited campaign NPC survived restart; primary sheet, actions, spellbook, inventory, campaign and all direct sections exercised at 1.0, 1.3 and 2.0 font scales with stable native screenshots and visible 48dp roll targets\n")
        })
    }
}
