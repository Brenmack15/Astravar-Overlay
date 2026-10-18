package com.yazsras.astravar

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.uiautomator.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.regex.Pattern

/** Test-only observations survive Activity/window recreation; actions are never retried. */
internal class ObservedUi(private val device: UiDevice) {
    fun <T : Any> snapshot(selector: BySelector, description: String, timeoutMs: Long = 30_000, read: (UiObject2) -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) break
            try {
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

    fun bounds(selector: BySelector, description: String, timeoutMs: Long = 30_000): Rect =
        snapshot(selector, description, timeoutMs) { Rect(it.visibleBounds) }

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
        for (direction in listOf(Direction.DOWN, Direction.UP)) repeat(scrollAttempts) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) error("Missing product control: $text")
            try {
                device.wait(Until.hasObject(selector), minOf(1_000L, remaining))
                val matches = device.findObjects(selector)
                val node = if (preferLast) matches.lastOrNull() else matches.firstOrNull()
                if (node != null) {
                    val bounds = if (interactive) interactiveBounds(node) else Rect(node.visibleBounds)
                    if (bounds != null && !bounds.isEmpty) return bounds
                }
                device.findObject(By.scrollable(true).pkg(packageName))?.scroll(direction, .6f)
            } catch (_: StaleObjectException) {
                // Scroll/read observations may refresh; no click occurs here.
            }
            device.waitForIdle()
        }
        error("Missing product control: $text")
    }

    fun tapText(text: String, packageName: String, scrollAttempts: Int = 12, preferLast: Boolean = false) =
        touch(textBounds(text, packageName, scrollAttempts, interactive = true, preferLast = preferLast), text)

    fun texts(selector: BySelector, description: String): List<String> =
        snapshot(selector, description) { device.findObjects(selector).map { node -> node.text } }

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
            snapshot(selector, description, timeoutMs = 10_000) { it.isFocused })
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
            snapshot(selector, description, timeoutMs = 10_000) { it.text.orEmpty().takeIf { current -> current == value } })
    }
}
