package com.yazsras.astravar

import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Build
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

class NativeFailureEvidence : TestWatcher() {
    override fun failed(error: Throwable, description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val files = instrumentation.targetContext.getExternalFilesDir(null)!!
        val name = "failure-${description.className.substringAfterLast('.')}-${description.methodName}"
        runCatching { device.takeScreenshot(File(files, "$name.png")) }
        runCatching { device.dumpWindowHierarchy(File(files, "$name.xml")) }
    }
}

/** Test-only observations survive Activity/window recreation; actions are never retried. */
internal class ObservedUi(private val device: UiDevice) {
    private fun clearObservationCache() {
        require(Build.VERSION.SDK_INT >= 34) { "Fresh native tab acceptance requires Android API34 or later" }
        assertTrue("The native accessibility observation cache must be cleared",
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache())
    }

    fun <T : Any> snapshot(selector: BySelector, description: String, timeoutMs: Long = 30_000, refreshCache: Boolean = false, read: (UiObject2) -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) break
            try {
                if (refreshCache) clearObservationCache()
                val node = device.wait(Until.findObject(selector), minOf(1_000L, remaining))
                if (node != null) read(node)?.let { return it }
            } catch (_: StaleObjectException) {
                // Retry only the observation, returning a detached Rect/String.
            }
            val idleRemaining = deadline - SystemClock.uptimeMillis()
            if (idleRemaining > 0) device.waitForIdle(minOf(1_000L, idleRemaining))
        }
        error("No fresh accessibility snapshot: $description")
    }

    private fun interactiveBounds(node: UiObject2): Rect? {
        var current: UiObject2? = node
        repeat(12) {
            val observed = current ?: return null
            if (observed.isEnabled && observed.isClickable) return Rect(observed.visibleBounds).takeUnless { it.isEmpty }
            current = observed.parent
        }
        return null
    }

    fun bounds(selector: BySelector, description: String, timeoutMs: Long = 30_000, refreshCache: Boolean = false): Rect =
        snapshot(selector, description, timeoutMs, refreshCache = refreshCache) { Rect(it.visibleBounds) }

    fun controlBounds(selector: BySelector, description: String, timeoutMs: Long = 30_000): Rect =
        snapshot(selector, description, timeoutMs) { interactiveBounds(it) }

    fun touch(bounds: Rect, description: String, idleTimeoutMs: Long? = null) {
        assertTrue("Observed control must have visible on-screen bounds: $description", !bounds.isEmpty &&
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
        assertTrue("Touch must be dispatched once to the observed control: $description",
            device.click(bounds.centerX(), bounds.centerY()))
        if (idleTimeoutMs == null) device.waitForIdle() else device.waitForIdle(idleTimeoutMs)
    }

    fun tap(selector: BySelector, description: String, timeoutMs: Long = 30_000) =
        touch(controlBounds(selector, description, timeoutMs), description)

    fun textBounds(text: String, packageName: String, scrollAttempts: Int = 12, interactive: Boolean = false, preferLast: Boolean = false): Rect {
        val selector = By.text(text).pkg(packageName)
        val deadline = SystemClock.uptimeMillis() + 30_000
        var lastObserved = "No accessibility observation yet"
        fun missing(): Nothing = error("Missing product control: $text; last observation: $lastObserved")
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            var scrolls = 0
            while (scrolls < scrollAttempts) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) missing()
                try {
                    device.wait(Until.hasObject(selector), minOf(1_000L, remaining))
                    val matches = device.findObjects(selector)
                    val node = if (preferLast) matches.lastOrNull() else matches.firstOrNull()
                    if (node != null) {
                        val bounds = if (interactive) interactiveBounds(node) else Rect(node.visibleBounds)
                        lastObserved = "${matches.size} matches; enabled=${node.isEnabled}; clickable=${node.isClickable}; nodeBounds=${Rect(node.visibleBounds)}; controlBounds=$bounds"
                        if (bounds != null && !bounds.isEmpty) return bounds
                        // Configuration can expose a label before its clickable
                        // ancestor is ready. Reobserve within the same deadline;
                        // an unready observation is not a scroll attempt.
                    } else {
                        val scroll = device.findObject(By.scrollable(true).pkg(packageName))
                        if (scroll == null) lastObserved = "No matching node or scrollable container"
                        else {
                            scrolls++
                            lastObserved = "No matching node; $direction scroll $scrolls/$scrollAttempts"
                            scroll.scroll(direction, .6f)
                        }
                    }
                } catch (_: StaleObjectException) {
                    lastObserved = "Window changed during read/scroll observation"
                }
                val idleRemaining = deadline - SystemClock.uptimeMillis()
                if (idleRemaining > 0) device.waitForIdle(minOf(1_000L, idleRemaining))
                val pollRemaining = deadline - SystemClock.uptimeMillis()
                if (pollRemaining > 0) SystemClock.sleep(minOf(100L, pollRemaining))
            }
        }
        missing()
    }

    fun tapText(text: String, packageName: String, scrollAttempts: Int = 12, preferLast: Boolean = false) =
        touch(textBounds(text, packageName, scrollAttempts, interactive = true, preferLast = preferLast), text)

    fun texts(selector: BySelector, description: String): List<String> =
        snapshot(selector, description) { device.findObjects(selector).map { node -> node.text } }

    private data class PrimaryTabState(val bounds: Rect, val enabled: Boolean, val selected: Boolean, val clickable: Boolean)

    private fun primaryTabState(node: UiObject2): PrimaryTabState? {
        var current: UiObject2? = node
        repeat(12) {
            val observed = current ?: return null
            if (observed.isClickable || observed.isSelected) {
                val bounds = Rect(observed.visibleBounds)
                if (!bounds.isEmpty) return PrimaryTabState(bounds, observed.isEnabled, observed.isSelected, observed.isClickable)
            }
            current = observed.parent
        }
        return null
    }

    private fun selectedTabBounds(node: UiObject2): Rect? =
        primaryTabState(node)?.takeIf { it.enabled && it.selected }?.bounds

    fun ensurePrimaryTab(packageName: String, tab: String) {
        require(tab in listOf("Armor", "Combat", "Party", "Campaign", "Config")) { "Only primary tabs use tab-selection semantics" }
        val deadline = SystemClock.uptimeMillis() + 30_000
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        val selector = By.text(tab).pkg(packageName)
        val before = snapshot(selector, "$tab tab state", timeoutMs = remaining(), refreshCache = true) {
            device.findObjects(selector).lastOrNull()?.let(::primaryTabState)
                ?.takeIf { state -> state.enabled && (state.selected || state.clickable) }
        }
        assertTrue("Primary tab must be enabled: $tab", before.enabled)
        // Compose deliberately omits the AX click action for a selected tab.
        // Observe its selection; only a different destination needs one touch.
        if (!before.selected) {
            assertTrue("An unselected primary tab must expose its click action: $tab", before.clickable)
            val budget = remaining()
            assertTrue("Primary navigation must stay within its 30-second budget", budget > 0)
            touch(before.bounds, "$tab primary tab", idleTimeoutMs = minOf(1_000L, budget))
        }
        val budget = remaining()
        assertTrue("Primary selection must settle within its 30-second budget", budget > 0)
        snapshot(selector, "fresh selected $tab tab", timeoutMs = budget, refreshCache = true) {
            device.findObjects(selector).lastOrNull()?.let(::selectedTabBounds)
        }
    }

    fun captureStablePage(file: File, packageName: String, tab: String, markers: List<BySelector>) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var previousFrame: Bitmap? = null
        var previousBounds: List<Rect>? = null
        var stableSince: Long? = null
        var lastObserved = "No route observation yet"
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                var sample: Bitmap? = null
                try {
                    clearObservationCache()
                    val tabBounds = device.findObjects(By.text(tab).pkg(packageName)).lastOrNull()?.let(::selectedTabBounds)
                    val bodyBounds = markers.map { selector -> device.findObject(selector)?.let { Rect(it.visibleBounds) } }
                    val geometry = listOfNotNull(tabBounds) + bodyBounds.filterNotNull()
                    val ready = tabBounds != null && bodyBounds.all { it != null } && geometry.all {
                        !it.isEmpty && it.left >= 0 && it.top >= 0 && it.right <= device.displayWidth && it.bottom <= device.displayHeight
                    }
                    lastObserved = "selected $tab=${tabBounds != null}; visible markers=${bodyBounds.count { it != null }}/${markers.size}; bounds=$geometry"
                    if (ready) {
                        // Read an unchanged software copy: the platform may return
                        // a hardware bitmap whose pixels cannot be inspected.
                        val captured = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        try {
                            sample = captured?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            captured?.recycle()
                        }
                        val frame = sample
                        val ink = frame?.let { bitmap -> bodyBounds.filterNotNull().map { marker -> foregroundInk(bitmap, marker) } }
                        val rendered = ink != null && ink.all { (count, required) -> count >= required }
                        lastObserved += "; marker foreground counts=$ink"
                        val now = SystemClock.uptimeMillis()
                        val unchanged = rendered && previousBounds == geometry && previousFrame?.sameAs(frame) == true
                        stableSince = if (unchanged) stableSince ?: now else if (rendered) now else null
                        if (frame != null && unchanged && now - (stableSince ?: now) >= 1_000) {
                            FileOutputStream(file).use { output ->
                                assertTrue("Stable $tab frame must be saved", frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                            clearObservationCache()
                            device.dumpWindowHierarchy(File(file.parentFile, file.nameWithoutExtension + ".xml"))
                            return
                        }
                        previousFrame?.recycle()
                        previousFrame = frame
                        sample = null
                        previousBounds = geometry.takeIf { rendered }
                    } else {
                        previousFrame?.recycle()
                        previousFrame = null
                        previousBounds = null
                        stableSince = null
                    }
                } catch (_: StaleObjectException) {
                    lastObserved = "Window changed while observing $tab route"
                    previousFrame?.recycle()
                    previousFrame = null
                    previousBounds = null
                    stableSince = null
                } finally {
                    sample?.recycle()
                }
                val idleRemaining = deadline - SystemClock.uptimeMillis()
                if (idleRemaining > 0) device.waitForIdle(minOf(1_000L, idleRemaining))
                val pollRemaining = deadline - SystemClock.uptimeMillis()
                if (pollRemaining > 0) SystemClock.sleep(minOf(100L, pollRemaining))
            }
        } finally {
            previousFrame?.recycle()
        }
        error("No stable $tab frame within 30 seconds; last observation: $lastObserved")
    }

    /** Dialogs own an accessibility window and can hide the selected navigation behind them. */
    fun captureStableDialog(file: File, packageName: String, title: String, markers: List<BySelector>) {
        require(markers.isNotEmpty()) { "A dialog capture needs specific body markers." }
        val deadline = SystemClock.uptimeMillis() + 30_000
        var previousFrame: Bitmap? = null
        var previousBounds: List<Rect>? = null
        var stableSince: Long? = null
        var lastObserved = "No dialog observation yet"
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                var sample: Bitmap? = null
                try {
                    clearObservationCache()
                    val close = device.findObjects(By.text("Close").pkg(packageName)).lastOrNull()
                    var root = close
                    var depth = 0
                    while (root?.parent != null && depth++ < 24) root = root?.parent
                    // All markers must belong to the same observed window as its real Close control.
                    val titleBounds = root?.findObjects(By.text(title).pkg(packageName))?.lastOrNull()?.let { Rect(it.visibleBounds) }
                    val closeBounds = close?.let(::interactiveBounds)
                    val bodyBounds = markers.map { selector -> root?.findObjects(selector)?.lastOrNull()?.let { Rect(it.visibleBounds) } }
                    val geometry = listOfNotNull(titleBounds, closeBounds) + bodyBounds.filterNotNull()
                    val ready = titleBounds != null && closeBounds != null && bodyBounds.all { it != null } && geometry.all {
                        !it.isEmpty && it.left >= 0 && it.top >= 0 && it.right <= device.displayWidth && it.bottom <= device.displayHeight
                    }
                    lastObserved = "dialog title=${titleBounds != null}; Close=${closeBounds != null}; body=${bodyBounds.count { it != null }}/${markers.size}; bounds=$geometry"
                    if (ready) {
                        val captured = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        try { sample = captured?.copy(Bitmap.Config.ARGB_8888, false) }
                        finally { captured?.recycle() }
                        val frame = sample
                        val ink = frame?.let { bitmap -> (listOfNotNull(titleBounds) + bodyBounds.filterNotNull()).map { foregroundInk(bitmap, it) } }
                        val rendered = ink != null && ink.all { (count, required) -> count >= required }
                        val now = SystemClock.uptimeMillis()
                        val unchanged = rendered && previousBounds == geometry && previousFrame?.sameAs(frame) == true
                        stableSince = if (unchanged) stableSince ?: now else if (rendered) now else null
                        lastObserved += "; marker foreground counts=$ink"
                        if (frame != null && unchanged && now - (stableSince ?: now) >= 1_000) {
                            FileOutputStream(file).use { output ->
                                assertTrue("Stable dialog frame must be saved", frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                            clearObservationCache()
                            device.dumpWindowHierarchy(File(file.parentFile, file.nameWithoutExtension + ".xml"))
                            return
                        }
                        previousFrame?.recycle()
                        previousFrame = frame
                        sample = null
                        previousBounds = geometry.takeIf { rendered }
                    } else {
                        previousFrame?.recycle(); previousFrame = null; previousBounds = null; stableSince = null
                    }
                } catch (_: StaleObjectException) {
                    lastObserved = "Window changed while observing dialog $title"
                    previousFrame?.recycle(); previousFrame = null; previousBounds = null; stableSince = null
                } finally { sample?.recycle() }
                val idleRemaining = deadline - SystemClock.uptimeMillis()
                if (idleRemaining > 0) device.waitForIdle(minOf(1_000L, idleRemaining))
                val pollRemaining = deadline - SystemClock.uptimeMillis()
                if (pollRemaining > 0) SystemClock.sleep(minOf(100L, pollRemaining))
            }
        } finally { previousFrame?.recycle() }
        error("No stable dialog $title within 30 seconds; last observation: $lastObserved")
    }

    private fun foregroundInk(frame: Bitmap, bounds: Rect): Pair<Int, Int> {
        val area = Rect(bounds)
        assertTrue("A body marker must lie inside the actual captured frame",
            !area.isEmpty && area.left >= 0 && area.top >= 0 && area.right <= frame.width && area.bottom <= frame.height)
        val pixels = IntArray(area.width() * area.height())
        frame.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
        // These page markers use light white/cyan/muted text on navy. Require
        // actual foreground pixels as well as AX text: an empty navy frame must
        // fail even if it is unchanged. No pixels are altered for the saved PNG.
        val count = pixels.count { color ->
            ((color shr 16 and 255) + (color shr 8 and 255) + (color and 255)) / 3 >= 128
        }
        val required = maxOf(16, kotlin.math.ceil(pixels.size * .002).toInt())
        return count to required
    }

    fun awaitMainAfterSavedDialogs(packageName: String) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        val header = By.text("ASTRAVAR").pkg(packageName)
        val close = By.text("Close").pkg(packageName)
        assertTrue("Astravar or its restored tool must become accessible within 60 seconds",
            device.wait(Until.hasObject(By.text(Pattern.compile("ASTRAVAR|Close")).pkg(packageName)), remaining()))
        repeat(3) {
            if (device.hasObject(close)) {
                val budget = remaining()
                assertTrue("Saved dialog cleanup must stay within the original 60-second budget", budget > 0)
                val bounds = controlBounds(close, "saved tool Close", timeoutMs = budget)
                val idleBudget = remaining()
                assertTrue("Saved dialog touch must stay within the original 60-second budget", idleBudget > 0)
                touch(bounds, "saved tool Close", idleTimeoutMs = minOf(1_000L, idleBudget))
            }
        }
        val budget = remaining()
        assertTrue("The main Astravar header must be accessible after saved dialog cleanup within 60 seconds",
            budget > 0 && device.wait(Until.hasObject(header), budget))
    }

    fun enterFocusedField(value: String, packageName: String, description: String) {
        require(value.matches(Regex("[A-Za-z0-9 .;_-]+"))) { "Native fixture input must use the controlled ASCII alphabet" }
        val selector = By.clazz("android.widget.EditText").focused(true).pkg(packageName)
        assertTrue("Native input must target the focused field: $description",
            snapshot(selector, description, timeoutMs = 10_000, refreshCache = true) { it.isFocused })
        // Accessibility may expose an empty field's hint as its text. Replace
        // through native keys once, without interpreting that hint as content.
        assertTrue("Native select-all must dispatch once: $description",
            device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON))
        assertTrue("Native delete must dispatch once: $description", device.pressKeyCode(KeyEvent.KEYCODE_DEL))
        // UiAutomation executes argv through Runtime.exec, without a POSIX
        // shell. Quotes would become literal text; %s keeps this one argument.
        val argument = value.replace(" ", "%s")
        device.executeShellCommand("input text $argument")
        assertEquals("Native keyboard input must reach the focused field: $description", value,
            snapshot(selector, description, timeoutMs = 10_000, refreshCache = true) { it.text.orEmpty().takeIf { current -> current == value } })
    }

    /** Fixture editor input through the focused native field's SET_TEXT action. */
    fun setFocusedField(value: String, packageName: String, description: String) {
        val selector = By.clazz("android.widget.EditText").focused(true).pkg(packageName)
        val field = snapshot(selector, description, timeoutMs = 10_000, refreshCache = true) {
            it.takeIf { observed -> observed.isFocused && observed.isEnabled }
        }
        // One accessibility input action avoids IME dictionary recasing fixture
        // provenance. The following fresh observation independently checks text.
        field.text = value
        assertEquals("Native editor input must reach the focused field: $description", value,
            snapshot(selector, description, timeoutMs = 10_000, refreshCache = true) {
                it.text.orEmpty().takeIf { current -> current == value }
            })
    }
}
