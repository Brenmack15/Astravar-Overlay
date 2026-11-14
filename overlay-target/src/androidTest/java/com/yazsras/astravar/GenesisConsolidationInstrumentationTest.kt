package com.yazsras.astravar

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Real post-upgrade controls; the retained code49 demo is evidence, never a new seeded database. */
@RunWith(AndroidJUnit4::class)
class GenesisConsolidationInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val product get() = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val ui get() = ObservedUi(device)
    private val files get() = instrumentation.targetContext.getExternalFilesDir(null)!!
    private fun tap(label: String) = ui.tapText(label, product, scrollAttempts = 24, preferLast = true)
    private fun find(label: String) = ui.textBounds(label, product, scrollAttempts = 24)
    private fun enter(label: String, value: String) {
        tap(label); ui.setFocusedField(value, product, label)
        device.pressBack(); ui.assertFocusedField(value, product, label)
    }
    private fun restart() {
        device.executeShellCommand("am force-stop $product")
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
        ui.ensurePrimaryTab(product, "Character")
        find("CI Native Hero Revised")
    }
    private fun capture(name: String) {
        assertTrue(device.takeScreenshot(File(files, "consolidation-$name.png")))
        device.dumpWindowHierarchy(File(files, "consolidation-$name.xml"))
    }
    private fun attackFormula(): String {
        find("Latest result")
        return ui.snapshot(By.text(Pattern.compile("d20 .*" )).pkg(product),
            "current armor attack's exact raw dice", refreshCache = true) { it.text?.takeIf { value -> value.startsWith("d20 ") } }
    }
    private fun assertSkillOrder(first: String, second: String, message: String) {
        find(first); find(second)
        // Reveal both rows, then compare one fresh viewport. Rectangles from
        // before and after a discovery swipe cannot establish visual order.
        val positions = ui.snapshot(By.text(second).pkg(product), message, refreshCache = true) { later ->
            val earlier = device.findObject(By.text(first).pkg(product)) ?: return@snapshot null
            val a = Rect(earlier.visibleBounds); val b = Rect(later.visibleBounds)
            (a to b).takeIf { !a.isEmpty && !b.isEmpty }
        }
        assertTrue(message, positions.first.top < positions.second.top)
    }
    private fun committedArmorRoll() {
        assertTrue("Automatic roll transaction finishes before reading its saved result",
            device.wait(Until.gone(By.text("Armor roll").pkg(product)), 30_000))
        find("Armor roll saved")
    }

    @Test fun characterArmorSkillsAndRestart() {
        assertEquals("Consolidation writes require an isolated emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        restart()
        ui.descriptionBounds("HP 31 / 37", product)

        // The new package retains the retired-overlay boundary independently of
        // the historical baseline engine journey.
        val info = instrumentation.targetContext.packageManager.getPackageInfo(product, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(info.services.orEmpty().any { it.name.endsWith(".OverlayService") })
        assertNull(instrumentation.targetContext.startService(Intent().setComponent(
            ComponentName(product, "com.yazsras.astravar.overlay.OverlayService"))))
        tap("Tools")
        find("Tools & settings")
        listOf("Armor systems", "Advanced combat & magic", "Armor weapon", "Spell reservoir", "Spell library",
            "Rules & profile", "Resources & recovery", "Roll history").forEach { label ->
            assertFalse("Retired ordinary Tools entry remains absent: $label", device.hasObject(By.text(label).pkg(product)))
        }
        capture("consolidated-tools")
        tap("Close")

        ui.selectSection(product, "Combat")
        tap("Connect saved Astravar systems")
        find("Connect Astravar to CI Native Hero Revised")
        tap("Use recorded armor slots for unknown levels")
        enter("Source or confirmation note", "Fictional retained baseline demo assigned for native acceptance")
        tap("These saved Astravar systems belong to CI Native Hero Revised")
        tap("Connect reviewed systems")
        find("Astravar armor")
        find("Active element")
        find("+7 to hit") // INT +3, proficiency +3, retained calibration +1.
        find("2d12+4 force · Weapon")
        ui.descriptionBounds("HP 31 / 37", product)
        capture("connected-armor")

        tap("Attack options")
        tap("Advantage")
        tap("Roll armor attack & damage")
        committedArmorRoll()
        val first = attackFormula()
        assertTrue("Real armor advantage retains both d20s", first.contains(" / ") && first.contains("advantage"))
        assertFalse("Automatic armor dice need no manual damage field", device.hasObject(By.text("Manual damage").pkg(product)))
        find(first)
        File(files, "consolidation-first-roll.txt").writeText(first)
        tap("Roll armor attack & damage")
        committedArmorRoll()
        val second = attackFormula()
        assertTrue("The second armor roll returns to one normal d20", second.matches(Regex("d20 [0-9]+ \\+ 7 = -?[0-9]+")))
        find(second)
        File(files, "consolidation-second-roll.txt").writeText(second)
        capture("automatic-armor-dice")

        // The native keyboard and rotation acceptance now belongs to the inline
        // Combat options instead of a legacy full-screen Options dialog.
        tap("Distance in feet")
        ui.enterFocusedField("60", product, "inline armor distance keyboard")
        val geometry = ui.snapshot(By.clazz("android.widget.EditText").focused(true).pkg(product),
            "inline distance above native IME", refreshCache = true) { field ->
            val ime = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (ime == null) null else {
                val keyboard = Rect().also { ime.getBoundsInScreen(it) }
                val bounds = Rect(field.visibleBounds)
                if (keyboard.isEmpty || bounds.isEmpty || bounds.bottom > keyboard.top) null else bounds to keyboard
            }
        }
        assertTrue(geometry.first.bottom <= geometry.second.top)
        device.pressBack()
        ui.assertFocusedField("60", product, "saved inline distance")
        assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_TAB))
        try {
            device.setOrientationLeft()
            find(second)
            capture("combat-landscape")
            device.setOrientationNatural(); device.unfreezeRotation()
            device.executeShellCommand("settings put system font_scale 1.3")
            find("Roll armor attack & damage")
            capture("combat-font130")
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            device.executeShellCommand("settings put system font_scale 1.0")
        }
        tap("Change element")
        find("Choose an element")
        ui.tap(By.descStartsWith("Cold ").pkg(product), "Select cold on the retained element wheel")
        find("Cold")
        restart()
        ui.selectSection(product, "Combat")
        find("Cold"); find(second)
        capture("element-and-roll-after-restart")

        ui.selectSection(product, "Spells")
        find("Spell reservoir"); find("0 / 1 stored spells")
        tap("Payload library · 0 templates")
        tap("Create payload template")
        enter("Payload name", "CI Native Stored Bolt")
        enter("Base spell level", "1")
        enter("Casting level", "1")
        enter("Dice count", "2"); enter("Die sides", "6")
        tap("Add damage component")
        enter("Accepted source and eligibility notes", "Fictional instantaneous damage payload for native acceptance")
        tap("Instantaneous damage and source eligibility approved")
        tap("Save template")
        find("Payload library · 1 templates")
        tap("Load spell into reservoir")
        find("Level 1 slots: 4")
        tap("Load · spend source")
        find("1 / 1 stored spells")
        restart()
        ui.selectSection(product, "Spells")
        find("1 / 1 stored spells"); find("CI Native Stored Bolt")
        ui.selectSection(product, "Combat")
        find("Cold")
        ui.selectSection(product, "Features")
        find("Armor features"); find("Engineering upgrades")
        capture("connected-armor-features")

        // Favorites are the next deferred slice: exercise the same physical
        // item and its persisted character state without changing equipment.
        ui.selectSection(product, "Equipment")
        find("CI Native Strength Band"); find("Carried · Quantity 1")
        ui.tap(By.desc("Add CI Native Strength Band to favorites").pkg(product), "Favorite existing equipment")
        ui.selectSection(product, "Favorites")
        find("CI Native Strength Band"); find("Carried · Quantity 1")
        restart(); ui.selectSection(product, "Equipment")
        ui.selectSection(product, "Favorites")
        find("CI Native Strength Band")
        ui.tap(By.desc("Remove CI Native Strength Band from favorites").pkg(product), "Unfavorite equipment")
        find("No matching equipment")
        ui.selectSection(product, "All gear")
        find("CI Native Strength Band"); find("Carried · Quantity 1")
        ui.tap(By.desc("Add CI Native Strength Band to favorites").pkg(product), "Retain favorite for complete backup")
        capture("equipment-favorite")

        ui.selectSection(product, "Sheet")
        assertFalse("No duplicate Health shortcut row", device.hasObject(By.text("Health").pkg(product)))
        tap("Add skill")
        enter("Skill name", "Alchemy native")
        tap("Untrained"); tap("Proficient")
        enter("Additional bonus", "1")
        enter("Notes (optional)", "Fictional custom skill persistence")
        tap("Save skill")
        // Editor save restores the Sheet reading position; the newly inserted
        // skill may be below the viewport. Discover its real row before touch.
        find("Alchemy native")
        ui.tap(By.desc("Pin Alchemy native custom skill").pkg(product), "Pin custom skill")
        find("Arcana")
        ui.tap(By.desc("Pin Arcana").pkg(product), "Pin standard skill")
        find("Pinned · A–Z")
        assertSkillOrder("Alchemy native", "Arcana", "Pinned skills sort alphabetically")
        restart(); ui.selectSection(product, "Sheet")
        find("Alchemy native")
        ui.bounds(By.desc("Unpin Alchemy native custom skill").pkg(product), "persisted custom pin")
        ui.bounds(By.desc("Unpin Arcana").pkg(product), "persisted standard pin")
        ui.tap(By.desc("Edit Alchemy native custom skill").pkg(product), "Edit persisted custom skill")
        enter("Skill name", "Zoology native")
        tap("Save skill")
        find("Zoology native")
        assertSkillOrder("Arcana", "Zoology native", "Renaming a pinned skill reorders within the group")
        tap("Zoology native")
        tap("Enter physical dice faces")
        enter("Faces in order (comma separated)", "13")
        tap("Validate entered dice")
        find("20") // INT +3 + proficiency +3 + custom bonus +1.
        tap("Save roll")
        find("Zoology native")
        ui.tap(By.desc("Unpin Zoology native custom skill").pkg(product), "Unpin custom skill")
        find("Zoology native")
        ui.tap(By.desc("Edit Zoology native custom skill").pkg(product), "Remove custom skill through its editor")
        tap("Remove skill"); tap("Remove")
        restart(); ui.selectSection(product, "Sheet")
        assertFalse("Removed custom skill stays removed after restart",
            device.hasObject(By.desc("Edit Zoology native custom skill").pkg(product)))
        find("Arcana")
        ui.bounds(By.desc("Unpin Arcana").pkg(product), "Official skill and its pin remain intact")
        ui.selectSection(product, "History")
        find("Zoology native")
        capture("retained-custom-skill-roll")
        ui.selectSection(product, "Sheet")
        // Leave a real custom record for the later independent complete-backup
        // and same-key-update stages, after separately proving deletion works.
        tap("Add skill")
        enter("Skill name", "Alchemy native")
        enter("Notes (optional)", "Retained custom skill for complete backup verification")
        tap("Save skill")
        find("Alchemy native")
        ui.tap(By.desc("Pin Alchemy native custom skill").pkg(product), "Retain a pinned custom skill for backup")
        ui.descriptionBounds("HP 31 / 37", product)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "\nCHECKPOINT: candidate native consolidation reviewed existing armor into one character; real attack dice, one-shot advantage, keyboard/rotation, retained element, reservoir load, Features, equipment favorites, custom skill CRUD and standard/custom pins persisted through restart. HP unchanged. Historical baseline rolls and backup/SQLite proofs remain independent stages.\n") })
    }
}
