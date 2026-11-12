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

    fun controlBounds(selector: BySelector, description: String, timeoutMs: Long = 30_000, refreshCache: Boolean = false): Rect =
        snapshot(selector, description, timeoutMs, refreshCache = refreshCache) { interactiveBounds(it) }

    fun touch(bounds: Rect, description: String, idleTimeoutMs: Long? = null) {
        assertTrue("Observed control must have visible on-screen bounds: $description", !bounds.isEmpty &&
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight)
        assertTrue("Touch must be dispatched once to the observed control: $description",
            device.click(bounds.centerX(), bounds.centerY()))
        if (idleTimeoutMs == null) device.waitForIdle() else device.waitForIdle(idleTimeoutMs)
    }

    fun tap(selector: BySelector, description: String, timeoutMs: Long = 30_000, refreshCache: Boolean = false) =
        touch(controlBounds(selector, description, timeoutMs, refreshCache), description)

    /** Match a control inside the current container, never a same-label node behind it. */
    fun tapWithin(container: BySelector, selector: BySelector, description: String, timeoutMs: Long = 30_000) {
        val bounds = snapshot(container, description, timeoutMs, refreshCache = true) { root ->
            root.findObject(selector)?.let(::interactiveBounds)
                ?.takeIf { Rect(root.visibleBounds).contains(it) }
        }
        touch(bounds, description)
    }

    private data class ScrollSnapshot(val viewport: Rect, val content: List<String>)

    /** Detach the current visible content before crossing a native gesture/window boundary. */
    private fun scrollSnapshot(packageName: String): ScrollSnapshot? {
        clearObservationCache()
        val candidates = device.findObjects(By.scrollable(true).pkg(packageName)).filter {
            it.className != "android.widget.EditText" && !it.visibleBounds.isEmpty
        }
        val scroll = (candidates.filter { it.className == "android.widget.ScrollView" }.ifEmpty { candidates })
            .maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() } ?: return null
        val viewport = Rect(scroll.visibleBounds)
        if (!viewport.intersect(0, 0, device.displayWidth, device.displayHeight) || viewport.height() < 32) return null
        val content = scroll.findObjects(By.pkg(packageName)).mapNotNull { node ->
            val text = node.text.orEmpty()
            val description = node.contentDescription.orEmpty()
            val bounds = Rect(node.visibleBounds)
            if ((text.isEmpty() && description.isEmpty()) || bounds.isEmpty || !Rect.intersects(viewport, bounds)) null
            else "$text\u001f$description\u001f${bounds.toShortString()}"
        }.sorted()
        return ScrollSnapshot(viewport, content)
    }

    /**
     * One slow native swipe inside observed bounds. A missing accessibility scroll event is
     * not an edge: compare fresh text/description/geometry observations after the gesture.
     * This read-only discovery never repeats a product tap or an entered value.
     */
    fun scrollForObservation(packageName: String, direction: Direction): Boolean? {
        require(direction == Direction.DOWN || direction == Direction.UP)
        val before = scrollSnapshot(packageName) ?: return null
        val viewport = before.viewport
        val inset = maxOf(4, viewport.height() / 8)
        val top = viewport.top + inset
        val bottom = viewport.bottom - inset - 1
        val start = if (direction == Direction.DOWN) bottom else top
        val end = if (direction == Direction.DOWN) top else bottom
        assertTrue("A visible native scroll gesture must be dispatched once",
            device.swipe(viewport.centerX(), start, viewport.centerX(), end, 60))
        device.waitForIdle(1_000)
        val after = scrollSnapshot(packageName)
        // Empty content cannot establish an unchanged page/edge.
        val changed = after == null || before.content.isEmpty() || before != after
        android.util.Log.d("AstravarNativeScroll", "$direction viewport=$viewport changed=$changed visibleNodes=${before.content.size}")
        return changed
    }

    fun textBounds(text: String, packageName: String, scrollAttempts: Int = 12, interactive: Boolean = false, preferLast: Boolean = false, firstDirection: Direction = Direction.DOWN): Rect {
        require(firstDirection == Direction.DOWN || firstDirection == Direction.UP)
        val selector = By.text(text).pkg(packageName)
        val deadline = SystemClock.uptimeMillis() + 30_000
        var lastObserved = "No accessibility observation yet"
        fun missing(): Nothing = error("Missing product control: $text; last observation: $lastObserved")
        val directions = if (firstDirection == Direction.UP) listOf(Direction.UP, Direction.DOWN) else listOf(Direction.DOWN, Direction.UP)
        for (direction in directions) {
            var scrolls = 0
            var unchangedSwipes = 0
            while (scrolls < scrollAttempts) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) missing()
                try {
                    // Compose scroll can leave individual descendant AX bounds
                    // cached at the old viewport even after pixels have moved.
                    // Refresh observations before looking up labels; never retry taps.
                    clearObservationCache()
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
                        // Reobserve the requested node after each movement. Two
                        // slow swipes with identical fresh content establish a
                        // stationary edge; a missing scrollFinished event does not.
                        if (unchangedSwipes >= 2) break
                        scrolls++
                        val changed = scrollForObservation(packageName, direction)
                        if (changed == null) lastObserved = "No matching node or visible scroll viewport"
                        else {
                            unchangedSwipes = if (changed) 0 else unchangedSwipes + 1
                            lastObserved = "No matching node; $direction scroll $scrolls/$scrollAttempts; changed=$changed; unchanged=$unchangedSwipes"
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

    /** A visible summary is not evidence that its saved disclosure is expanded. */
    fun expandDisclosure(packageName: String, title: String) {
        textBounds(title, packageName, scrollAttempts = 18, interactive = true)
        fun observed(label: UiObject2): Pair<Rect, Boolean>? {
            var current: UiObject2? = label
            repeat(12) {
                val row = current ?: return null
                if (row.isEnabled && row.isClickable) {
                    // The actual API36 tree exports the visible +/− children,
                    // while the platform text/state lookup did not resolve this
                    // Compose virtual disclosure. Read only this title's row;
                    // summary content elsewhere must never imply expansion.
                    val indicators = row.findObjects(By.text(Pattern.compile("[+−]")).pkg(packageName))
                    val indicator = indicators.singleOrNull() ?: return null
                    val bounds = Rect(row.visibleBounds)
                    val indicatorBounds = Rect(indicator.visibleBounds)
                    if (bounds.isEmpty || indicatorBounds.isEmpty || !bounds.contains(indicatorBounds)) return null
                    return bounds to (indicator.text == "−")
                }
                current = row.parent
            }
            return null
        }
        val selector = By.text(title).pkg(packageName)
        val before = snapshot(selector, "observed disclosure state: $title", refreshCache = true) { observed(it) }
        if (!before.second) touch(before.first, "Expand $title", idleTimeoutMs = 1_000)
        snapshot(selector, "expanded disclosure: $title", refreshCache = true) { observed(it)?.takeIf { state -> state.second } }
    }

    /** A saved statistic may be above a newly expanded skill list; reacquire it visibly. */
    fun descriptionBounds(prefix: String, packageName: String): Rect {
        val selector = By.descStartsWith(prefix).pkg(packageName)
        val deadline = SystemClock.uptimeMillis() + 30_000
        for (direction in listOf(Direction.UP, Direction.DOWN)) {
            var unchanged = 0
            for (attempt in 0 until 18) {
                check(SystemClock.uptimeMillis() < deadline) { "Missing visible statistic: $prefix" }
                try {
                    clearObservationCache()
                    device.wait(Until.hasObject(selector), 250)
                    val found = device.findObjects(selector).map { Rect(it.visibleBounds) }.firstOrNull {
                        !it.isEmpty && it.left >= 0 && it.top >= 0 &&
                            it.right <= device.displayWidth && it.bottom <= device.displayHeight
                    }
                    if (found != null) return found
                    if (unchanged >= 2) break
                    val changed = scrollForObservation(packageName, direction)
                    unchanged = if (changed == false) unchanged + 1 else 0
                } catch (_: StaleObjectException) {
                    // Retry observations only; no product action is repeated.
                }
            }
        }
        error("Missing visible statistic: $prefix")
    }

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
        require(tab in listOf("Character", "Inventory", "Companions", "Campaign")) { "Only primary tabs use tab-selection semantics" }
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

    /** Follow the same header and disclosure controls available to a player. */
    fun openTool(packageName: String, name: String) {
        tapText("Tools", packageName)
        val group = when (name) {
            "Armor weapon", "Spell reservoir", "Spell library", "Rules & profile", "Resources & recovery", "Roll history" -> "Advanced combat & magic"
            "Character sources", "Character backup", "Campaign backup", "Complete backup", "Campaign import", "Owner connection", "Weapon backup", "Fictional sync", "Remote Updates" -> "Records, backups & connection"
            else -> null
        }
        if (group != null) tapText(group, packageName)
        tapText(name, packageName)
    }

    fun campaignItems(packageName: String) {
        ensurePrimaryTab(packageName, "Inventory")
        // Genesis exposes one consolidated inventory; the original ledger is
        // an explicit source-record destination. Keep the older tab path for
        // the historical upgrade fixtures that share this observer.
        if (device.hasObject(By.text("‹ Inventory").pkg(packageName))) return
        if (device.hasObject(By.text("My gear").pkg(packageName))) selectSection(packageName, "Campaign items")
        else tapText("Campaign items", packageName)
    }

    /** Preserve source-directory journeys through the explicit Genesis Journal tab. */
    fun campaignJournal(packageName: String) {
        ensurePrimaryTab(packageName, "Campaign")
        if (device.hasObject(By.text("‹ Campaign").pkg(packageName))) tapText("‹ Campaign", packageName)
        // Older frozen builds expose the journal directly. This optional tab
        // follows only an observed new campaign selector, preserving that route.
        if (device.hasObject(By.text("Choose campaign").pkg(packageName))) selectSection(packageName, "Journal")
    }

    /** Discover a flat section strip through native scrolling, then activate its tab once. */
    fun selectSection(packageName: String, label: String) {
        // Restrict observation to the requested tab family inside its actual
        // horizontal strip. A body heading or unrelated filter is never a tab.
        val families = listOf(
            listOf("Sheet", "Combat", "Equipment", "Spells", "Features", "Resources", "History", "Currency"),
            listOf("My gear", "Campaign items"),
            listOf("My items", "All items"),
            listOf("All gear", "Equipped", "Attuned"),
            listOf("All", "Prepared", "Cantrips"),
            listOf("People", "Journal")
        )
        val family = families.singleOrNull { label in it } ?: error("Unknown tab family for $label")
        val deadline = SystemClock.uptimeMillis() + 30_000
        val stripSelector = By.clazz("android.widget.HorizontalScrollView").pkg(packageName)
        val familyPattern = Pattern.compile(family.joinToString("|", transform = Pattern::quote))
        fun matchingNodes(strip: UiObject2, pattern: Pattern): List<UiObject2> =
            strip.findObjects(By.text(pattern).pkg(packageName)) + strip.findObjects(By.desc(pattern).pkg(packageName))
        fun sectionTabState(node: UiObject2): PrimaryTabState? {
            var current: UiObject2? = node
            repeat(12) {
                val observed = current ?: return null
                if (observed.isClickable || observed.isSelected) {
                    // The actual API36 tree puts a quick action's clickable View around
                    // sibling TextView and Button nodes. Checking only this ancestor's
                    // class misidentifies its Spells label as a section tab and selects
                    // the quick-action strip. A selectable Tab has no Button descendant.
                    if (observed.className == "android.widget.Button" ||
                        observed.findObjects(By.clazz("android.widget.Button").pkg(packageName)).isNotEmpty()) return null
                    val bounds = Rect(observed.visibleBounds)
                    return bounds.takeUnless { it.isEmpty }?.let {
                        PrimaryTabState(it, observed.isEnabled, observed.isSelected, observed.isClickable)
                    }
                }
                current = observed.parent
            }
            return null
        }
        val labelPattern = Pattern.compile(Pattern.quote(label))
        fun sectionStrip() = device.findObjects(stripSelector).firstOrNull {
            !it.visibleBounds.isEmpty && matchingNodes(it, familyPattern).any { node -> sectionTabState(node) != null }
        }
        val minimumTap = (48 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
        var lastObserved = "No section strip observed"
        for (direction in listOf(Direction.LEFT, Direction.RIGHT)) {
            repeat(8) discovery@ {
                check(SystemClock.uptimeMillis() < deadline) { "Section $label exceeded its discovery budget: $lastObserved" }
                clearObservationCache()
                val strip = sectionStrip()
                if (strip == null) {
                    lastObserved = "Section strip outside vertical viewport"
                    scrollForObservation(packageName, Direction.UP)
                } else {
                    val viewport = Rect(strip.visibleBounds)
                    val state = matchingNodes(strip, labelPattern).firstNotNullOfOrNull(::sectionTabState)
                    lastObserved = "strip=$viewport; $label=$state; direction=$direction"
                    val visibleHeight = minOf(viewport.height(), state?.bounds?.height() ?: viewport.height())
                    if (visibleHeight < minimumTap) {
                        // A visible strip may still be clipped under the fixed
                        // app header. Reveal it in its observed vertical parent
                        // before testing the full 48dp tab or scrolling sideways.
                        var ancestor = strip.parent
                        var vertical: Rect? = null
                        repeat(12) ancestry@ {
                            val node = ancestor ?: return@ancestry
                            if (vertical == null && node.className == "android.widget.ScrollView") {
                                vertical = Rect(node.visibleBounds)
                            }
                            ancestor = node.parent
                        }
                        val parent = vertical
                        val clippedAtTop = parent != null && viewport.top <= parent.top
                        val clippedAtBottom = parent != null && viewport.bottom >= parent.bottom
                        if (parent != null && !parent.isEmpty && (clippedAtTop || clippedAtBottom)) {
                            val distance = minimumTap - visibleHeight + minimumTap / 2
                            val start = parent.centerY()
                            val end = (start + if (clippedAtTop) distance else -distance)
                                .coerceIn(parent.top + 4, parent.bottom - 5)
                            lastObserved += "; reveal clipped strip inside vertical parent=$parent"
                            assertTrue("Reveal the full $label touch target with one observed vertical swipe",
                                device.swipe(parent.centerX(), start, parent.centerX(), end, 20))
                            device.waitForIdle(1_000)
                            return@discovery
                        }
                    }
                    if (state != null && state.enabled && viewport.contains(state.bounds) &&
                        state.bounds.width() >= minimumTap && state.bounds.height() >= minimumTap) {
                        if (!state.selected) {
                            assertTrue("An unselected section exposes its click action: $label", state.clickable)
                            touch(state.bounds, "$label section", idleTimeoutMs = 1_000)
                        }
                        snapshot(stripSelector, "selected $label section", timeoutMs = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1), refreshCache = true) {
                            sectionStrip()?.let { selectedStrip ->
                                val selectedViewport = Rect(selectedStrip.visibleBounds)
                                matchingNodes(selectedStrip, labelPattern).firstNotNullOfOrNull { node ->
                                    sectionTabState(node)?.takeIf { it.selected && it.enabled }?.bounds
                                }
                                    ?.takeIf { bounds -> selectedViewport.contains(bounds) &&
                                        bounds.width() >= minimumTap && bounds.height() >= minimumTap }
                            }
                        }
                        return
                    }
                    val inset = maxOf(8, viewport.width() / 8)
                    val left = viewport.left + inset
                    val right = viewport.right - inset - 1
                    assertTrue("Discover $label with one native horizontal swipe",
                        device.swipe(if (direction == Direction.RIGHT) right else left, viewport.centerY(),
                            if (direction == Direction.RIGHT) left else right, viewport.centerY(), 35))
                    device.waitForIdle(1_000)
                }
            }
        }
        error("Missing enabled section $label: $lastObserved")
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

    fun assertFocusedField(value: String, packageName: String, description: String) {
        val selector = By.clazz("android.widget.EditText").focused(true).pkg(packageName)
        assertEquals("Committed editor text must remain exact after keyboard dismissal: $description", value,
            snapshot(selector, description, timeoutMs = 10_000, refreshCache = true) {
                it.text.orEmpty().takeIf { current -> current == value }
            })
    }

    /** Fixture editing uses Android key dispatch, with independent exact AX readback. */
    fun setFocusedField(value: String, packageName: String, description: String) {
        // Compose can acknowledge ACTION_SET_TEXT during an IME/scroll transition
        // without committing the text. Exercise the same InputConnection as a
        // keyboard user; each action is dispatched once and must read back exactly.
        enterFocusedField(value, packageName, description)
    }
}
