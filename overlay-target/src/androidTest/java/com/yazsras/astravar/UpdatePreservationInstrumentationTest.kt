package com.yazsras.astravar

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Readback after adb install -r, without seeding or resetting the product. */
@RunWith(AndroidJUnit4::class)
class UpdatePreservationInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    @Test fun retainedRollAndPartyNavigationAfterSameKeyUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val product = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
        val files = instrumentation.targetContext.getExternalFilesDir(null)!!
        val ui = ObservedUi(device)
        fun find(text: String): Rect = ui.textBounds(text, product, scrollAttempts = 14)
        fun tap(text: String, preferLast: Boolean = false) {
            if (preferLast) ui.ensurePrimaryTab(product, text)
            else ui.tapText(text, product, scrollAttempts = 14)
        }
        fun revealCaptureMarkers(markers: List<BySelector>, description: String) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            val margin = maxOf(4, (8 * instrumentation.targetContext.resources.displayMetrics.density).toInt())
            var gestures = 0
            var lastObserved = "No viewport observation"
            while (SystemClock.uptimeMillis() < deadline) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) break
                val (viewport, bounds) = ui.snapshot(By.clazz("android.widget.ScrollView").pkg(product),
                    "Capture viewport for $description", timeoutMs = remaining, refreshCache = true) {
                    val area = device.findObjects(By.clazz("android.widget.ScrollView").pkg(product))
                        .map { node -> Rect(node.visibleBounds) }.filter { rect -> !rect.isEmpty }
                        .maxByOrNull { rect -> rect.width().toLong() * rect.height() } ?: return@snapshot null
                    area to markers.map { selector -> device.findObject(selector)?.let { node -> Rect(node.visibleBounds) } }
                }
                val interior = Rect(viewport).apply { inset(0, margin) }
                lastObserved = "viewport=$viewport; marker bounds=$bounds"
                require(!interior.isEmpty) { "Capture viewport has no readable interior: $lastObserved" }
                if (bounds.all { rect -> rect != null && !rect.isEmpty && interior.contains(rect) }) return
                val visible = bounds.filterNotNull().filter { rect -> !rect.isEmpty }
                val clippedAbove = visible.any { rect -> rect.top < interior.top }
                val clippedBelow = visible.any { rect -> rect.bottom > interior.bottom }
                check(!(clippedAbove && clippedBelow)) {
                    "$description markers cannot fit together in the actual viewport: $lastObserved"
                }
                check(gestures < 8) { "Could not fully reveal $description in eight bounded scrolls: $lastObserved" }
                val firstVisible = bounds.indexOfFirst { rect -> rect != null && !rect.isEmpty }
                val down = when {
                    clippedBelow -> true
                    clippedAbove -> false
                    firstVisible > 0 -> false
                    else -> true
                }
                // A partially clipped AX node is not a fully readable marker.
                // Small observed-viewport gestures avoid overshooting this nearby
                // summary; no product control or saved value is touched.
                val distance = maxOf(32, viewport.height() / 4)
                val start = viewport.centerY() + if (down) distance / 2 else -distance / 2
                val end = start + if (down) -distance else distance
                assertTrue("Reveal $description with one bounded native scroll",
                    device.swipe(viewport.centerX(), start, viewport.centerX(), end, 60))
                gestures++
                val idleRemaining = deadline - SystemClock.uptimeMillis()
                if (idleRemaining > 0) device.waitForIdle(minOf(1_000L, idleRemaining))
            }
            error("No fully visible $description within 30 seconds: $lastObserved")
        }
        fun captureArmor(name: String) {
            find("Active element"); find("Change element")
            val markers = listOf(By.text("Active element").pkg(product), By.text("Change element").pkg(product))
            revealCaptureMarkers(markers, "active element summary and action")
            ui.captureStablePage(File(files, name), product, "Character",
                markers)
        }
        fun captureParty(name: String) {
            val markers = listOf(By.text("CI Native Ally").pkg(product))
            revealCaptureMarkers(markers, "saved companion name")
            ui.captureStablePage(File(files, name), product, "Companions", markers)
        }
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
        assertTrue("The saved labeled demo survived", device.hasObject(By.text("DEMO").pkg(product)))
        ui.ensurePrimaryTab(product, "Character")
        ui.tap(By.desc("Switch character").pkg(product), "Select the adopted armor owner")
        tap("CI Native Hero Revised")
        ui.selectSection(product, "Sheet")
        ui.textBounds("Pinned · A–Z", product, scrollAttempts = 14, firstDirection = Direction.UP)
        find("Alchemy native")
        ui.bounds(By.desc("Unpin Alchemy native custom skill").pkg(product), "custom skill and pin survive APK update")
        find("Arcana")
        ui.bounds(By.desc("Unpin Arcana").pkg(product), "standard skill pin survives APK update")
        ui.selectSection(product, "Spells")
        find("1 / 1 stored spells")
        find("CI Native Stored Bolt")
        ui.selectSection(product, "Equipment")
        ui.selectSection(product, "Favorites")
        find("CI Native Strength Band"); find("Carried · Quantity 1")
        ui.bounds(By.desc("Remove CI Native Strength Band from favorites").pkg(product), "equipment favorite survives APK update")
        ui.selectSection(product, "History")
        find("Roll history")
        ui.selectSection(product, "Armor")
        val firstFormula = File(files, "saved-first-roll.txt").readText()
        val savedFormula = File(files, "saved-roll.txt").readText()
        val expectedFormulas = listOf(firstFormula, savedFormula,
            File(files, "consolidation-first-roll.txt").readText(), File(files, "consolidation-second-roll.txt").readText())
        val historyFormulas = expectedFormulas.map { expected ->
            find(expected)
            val matches = ui.texts(By.text(expected).pkg(product), "unique retained armor roll after APK update")
            assertEquals("Each historical or current attack appears once", listOf(expected), matches)
            matches.single()
        }
        assertEquals("Both original and both consolidated attacks survive the APK update", 4, historyFormulas.size)
        assertEquals("The APK update preserves original and consolidated raw rolls and modes",
            expectedFormulas.sorted(), historyFormulas.sorted())
        assertEquals("The first attack retains its one-shot advantage", 1,
            historyFormulas.take(2).count { it.contains("advantage") })
        assertEquals("The second attack retains its normal single-d20 result", 1,
            historyFormulas.take(2).count { !it.contains(" / ") && !it.contains("advantage") })
        find(savedFormula)
        assertTrue(device.takeScreenshot(File(files, "same-key-update-history.png")))
        ui.campaignItems(product)
        tap("CI FICTIONAL update-preservation crystal")
        find("Quantity not set")
        find("Status not set")
        assertTrue(device.takeScreenshot(File(files, "same-key-update-inventory.png")))
        tap("Close")
        tap("Companions", preferLast = true)
        if (device.hasObject(By.text("‹ Party").pkg(product))) tap("‹ Party")
        find("Companions")
        // The previous native journey created this arbitrary companion through
        // product controls. A new installation must not invent owner companions.
        find("CI Native Ally")
        assertNotNull("The shared companion source HP survives the ordinary APK update",
            ui.bounds(By.textContains("HP 6 / 12").pkg(product), "retained fictional companion HP"))
        tap("CI Native Ally")
        find("6 / 12")
        tap("Open playable sheet")
        ui.descriptionBounds("HP 6 / 12", product)
        tap("Back to companions")
        tap("‹ Party")
        // Layout acceptance captures are native emulator output, not a physical Galaxy test.
        device.executeShellCommand("wm size 1080x2400")
        for (scale in listOf("1.0", "1.3", "2.0")) {
            device.executeShellCommand("settings put system font_scale $scale")
            device.waitForIdle()
            ui.ensurePrimaryTab(product, "Character")
            ui.selectSection(product, "Combat")
            captureArmor("armor-font-$scale.png")
            tap("Companions", preferLast = true)
            find("CI Native Ally")
            captureParty("party-font-$scale.png")
        }
        device.setOrientationLeft()
        device.waitForIdle()
        find("CI Native Ally")
        captureParty("party-landscape-large-font.png")
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.executeShellCommand("settings put system font_scale 1.0")
        device.executeShellCommand("wm density 432")
        for (width in listOf(1080, 972)) {
            device.executeShellCommand("wm size ${width}x2400")
            device.waitForIdle()
            ui.ensurePrimaryTab(product, "Character")
            ui.selectSection(product, "Combat")
            captureArmor("element-width-$width-armor.png")
            device.dumpWindowHierarchy(File(files, "element-width-$width-armor.xml"))
            tap("Change element")
            find("Choose an element")
            val outer = listOf("Fire", "Cold", "Lightning", "Acid", "Poison", "Thunder", "Necrotic", "Psychic", "Radiant")
            outer.forEach { name ->
                val bounds = ui.bounds(By.descStartsWith("$name ").pkg(product), "Outer wheel element $name at width $width")
                assertNotNull("Outer wheel element $name remains discoverable at width $width", bounds)
                assertTrue("$name touch bounds remain on screen", bounds.let {
                    it.left >= 0 && it.top >= 0 && it.right <= device.displayWidth && it.bottom <= device.displayHeight
                })
            }
            assertNotNull(ui.bounds(By.desc("Neutral Force").pkg(product), "Neutral Force at width $width"))
            assertTrue(device.takeScreenshot(File(files, "element-wheel-width-$width.png")))
            tap("Radiant, Psychic & Necrotic unlocks")
            assertTrue(device.wait(Until.hasObject(By.textContains("Artificer level 8").pkg(product)), 5000))
            tap("Close")
            assertTrue("One Close must dismiss the element chooser before another display resize at width $width",
                device.wait(Until.gone(By.text("Choose an element").pkg(product)), 5000))
            assertTrue(device.takeScreenshot(File(files, "element-wheel-closed-width-$width.png")))
            device.dumpWindowHierarchy(File(files, "element-wheel-closed-width-$width.xml"))
        }
        device.executeShellCommand("wm density reset")
        device.executeShellCommand("wm size reset")
        device.waitForIdle()
        ui.openTool(product, "ChatGPT Prompts")
        tap("Find a prompt")
        val query = ui.bounds(By.clazz("android.widget.EditText").focused(true).pkg(product), "focused ChatGPT prompt search", timeoutMs = 10_000)
        assertNotNull(query)
        ui.setFocusedField("Resume", product, "ChatGPT prompt search")
        device.pressBack()
        ui.assertFocusedField("Resume", product, "ChatGPT prompt search after keyboard dismissal")
        tap("Copy handoff prompt")
        find("Copied — paste in ChatGPT")
        assertTrue(device.takeScreenshot(File(files, "native-chatgpt-prompt-copy.png")))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCHECKPOINT: same-key version update retained raw dice, history and demo; native party/armor large-font navigation exercised\n")
        })
    }
}
