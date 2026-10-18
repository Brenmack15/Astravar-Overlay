package com.yazsras.astravar.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.savePresentationCapture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Native Compose + real Dialog-window drawings. Every record here is a synthetic fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignVisualAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var oldRequestedSize: String? = null
    private val source = "Synthetic UI acceptance fixture · not campaign data"
    private val longItemName = "Mithral focusing assembly with a deliberately long synthetic inventory name"
    private val projectName = "Prismatic lens assembly"
    private val connectionName = "Synthetic Mira"

    @Before fun requestedWindowSizes() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
    }
    @After fun restoreWindowSizes() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private inner class Fixture(populated: Boolean = true) : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val ledger = CampaignLedgerRepository(db)
        val sandboxDb = Room.inMemoryDatabaseBuilder(context, SandboxSyncDb::class.java).build()
        val networkCalls = AtomicInteger()
        val sync = SandboxSyncRepository(sandboxDb, SandboxTransport { _, _, _ ->
            networkCalls.incrementAndGet()
            throw IOException("Synthetic offline transport")
        })
        val activity: ActivityController<ComponentActivity>
        init {
            if (populated) runBlocking {
                val item = ledger.create(LedgerDraft(title = longItemName, category = "Material", owner = "Fixture owner",
                    quantity = null, itemState = "Carried", location = "Test workbench", evidenceStatus = "Unverified",
                    description = "A fixed fictional object for layout checks. Its quantity remains unknown.",
                    knownProperties = "Reflects a narrow blue light.", possibleUses = "Proposed optical experiment; no active mechanics.", source = source))
                ledger.records.saveItemProperty(ItemProperty(itemId = item.id, label = "Material", value = "Mithral", evidenceStatus = "Confirmed"), source = source)
                val copper = ledger.create(LedgerDraft(title = "Test copper", category = "Material", quantity = 6, source = source))
                val project = InventionProject(title = projectName, status = "Blueprint", description = "A fictional approved design record. It has no owned output.")
                ledger.records.saveProject(project, source = source)
                ledger.records.saveStage(ProjectStage(projectId = project.id, title = "Optical research", status = "Completed", notes = "Confirmed synthetic test observation."), source = source)
                ledger.records.saveRequirement(MaterialRequirement(projectId = project.id, label = "Copper material", itemId = copper.id, quantity = 2), source = source)
                val connection = ConnectionEntity(name = connectionName, aliases = "Ashkeeper", notes = "Player-known archivist in a fictional test scene.", relationship = "Returned a borrowed map")
                ledger.records.saveEntity(connection, source = source)
                ledger.records.saveFact(ConnectionFact(entityId = connection.id, detail = "Knows the western road.", evidenceStatus = "Confirmed"), source = source)
                ledger.records.saveMilestone(ConnectionMilestone(entityId = connection.id, title = "Returned the map", detail = "A meaningful resolved fictional obligation."), source = source)
                ledger.records.saveCompanionState(CompanionState(condition = "Dormant", notes = "Sourced synthetic status only."), source = source)
                ledger.records.saveCompanionResource(CompanionResource(name = "HP", current = null, maximum = 30), source = source)
                ledger.records.saveCompanionResource(CompanionResource(name = "Reaction", current = 1, maximum = 1), source = source)
                ledger.records.saveCompanionResource(CompanionResource(name = "Mending Repairs", current = null, maximum = null), source = source)
                ledger.records.saveLink(CampaignLink(fromType = "item", fromId = item.id, toType = "companion", toId = "eek", label = "Research subject"), source = source)
            }
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        }
        fun render(destination: String = "Campaign", fontScale: Float = 1f, content: @Composable Fixture.() -> Unit) {
            activity.get().setContent {
                val density = LocalDensity.current
                val configuration = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalConfiguration provides configuration) {
                    AstravarTheme {
                        Scaffold(containerColor = RebuildPalette.Canvas,
                            topBar = { RebuildAppHeader(false) },
                            bottomBar = { RebuildPrimaryNavigation(destination) {} }) { insets ->
                            Column(Modifier.fillMaxSize().padding(insets)) {
                                if (destination == "Campaign") Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    TextButton(onClick = {}) { Text("‹ Campaign") }
                                }
                                Box(Modifier.weight(1f)) { content() }
                            }
                        }
                    }
                }
            }
        }
        override fun close() { activity.pause().stop().destroy(); db.close(); sandboxDb.close() }
    }
    private fun settle(condition: () -> Boolean) {
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        compose.waitForIdle()
    }
    private fun primary(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        textFullyVisible(text, singleLine = true)
    }
    private fun textFullyVisible(text: String, singleLine: Boolean = false) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            assertTrue("Text layout must be inspectable: $text", node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            val layout = layouts.single()
            val metrics = "text=$text; size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                "overflowWidth=${layout.didOverflowWidth}; overflowHeight=${layout.didOverflowHeight}; lines=${layout.lineCount}; " +
                "constraints=${layout.layoutInput.constraints}; font=${layout.layoutInput.style.fontSize}; lineHeight=${layout.layoutInput.style.lineHeight}; " +
                "density=${layout.layoutInput.density}; bounds=${node.boundsInRoot}; " +
                (0 until layout.lineCount).joinToString { "line$it=${layout.getLineLeft(it)},${layout.getLineRight(it)},bottom${layout.getLineBottom(it)}" }
            // Compose can retain a wider paragraph container after measuring a label
            // tightly: e.g. paragraph640px, label111px, actual line ink110.92578px.
            // hasVisualOverflow then flags the unused container. Check the actual
            // laid-out lines, ellipsis and ancestor clipping instead.
            for (line in 0 until layout.lineCount) {
                assertFalse("Control label must not be ellipsized: $metrics", layout.isLineEllipsized(line))
                assertTrue("Line extends left of text allocation: $metrics", layout.getLineLeft(line) >= -0.5f)
                assertTrue("Line extends right of text allocation: $metrics", layout.getLineRight(line) <= layout.size.width + 0.5f)
                assertTrue("Line extends above text allocation: $metrics", layout.getLineTop(line) >= -0.5f)
                assertTrue("Line extends below text allocation: $metrics", layout.getLineBottom(line) <= layout.size.height + 0.5f)
            }
            assertTrue("Text vertically clipped by ancestor: $text", node.boundsInRoot.height + 1f >= layout.size.height)
            assertTrue("Text horizontally clipped by ancestor: $text", node.boundsInRoot.width + 1f >= layout.size.width)
            if (singleLine) assertEquals("Short action label should fit one line: $text", 1, layout.lineCount)
        }
    }
    private fun capture(name: String, activity: ComponentActivity, modal: Boolean = false, note: String = "") {
        compose.waitForIdle()
        // Real view invalidation and a delivered frame refresh native display lists
        // after asynchronous Room recomposition. The warm-up draw is discarded;
        // every saved pixel still comes from the unmodified Android view hierarchy.
        compose.runOnIdle {
            fun invalidateTree(view: View) {
                view.invalidate()
                if (view is ViewGroup) for (index in 0 until view.childCount) invalidateTree(view.getChildAt(index))
            }
            WindowInspector.getGlobalWindowViews().forEach(::invalidateTree)
        }
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        val labelBounds = listOf("ASTRAVAR", "Armor", "Combat", "Campaign", "Config", "Add", "Add Item", "Add Invention").associateWith { label ->
            compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        }
        val geometry = labelBounds.map { (label, bounds) -> "$label: ${bounds.joinToString()}" }
        compose.runOnIdle {
            val decor = activity.window.decorView
            val windows = WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            if (modal) assertTrue("Capture must include a genuine Android Dialog window", windows.any { it !== decor })
            val directory = System.getProperty("astravar.screenshots") ?: return@runOnIdle
            check(decor.width > 0 && decor.height > 0)
            val warmup = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                val warmupCanvas = Canvas(warmup)
                decor.draw(warmupCanvas)
                windows.filter { it !== decor }.forEach { it.draw(warmupCanvas) }
            } finally { warmup.recycle() }
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val locations = mutableListOf<String>()
            try {
                val canvas = Canvas(bitmap); decor.draw(canvas)
                val base = IntArray(2).also { decor.getLocationOnScreen(it) }
                windows.filter { it !== decor }.forEach { window ->
                    val position = IntArray(2).also { window.getLocationOnScreen(it) }
                    val params = window.layoutParams as? WindowManager.LayoutParams
                    if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                        canvas.drawColor(Color.argb((params.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
                    canvas.save(); canvas.translate((position[0] - base[0]).toFloat(), (position[1] - base[1]).toFloat())
                    window.draw(canvas); canvas.restore()
                    locations += "${window.javaClass.simpleName}: ${window.width}x${window.height} at ${position[0] - base[0]},${position[1] - base[1]}"
                }
                // Preserve the exact failing native draw too, so a pixel assertion never
                // hides the diagnostic evidence needed to distinguish cache from layout.
                savePresentationCapture(bitmap, name)
                if (!modal) listOf("ASTRAVAR", "Armor", "Combat", "Campaign", "Config").forEach { label ->
                    val bounds = requireNotNull(labelBounds.getValue(label).maxByOrNull { it.top }) { "Shell label missing from semantics: $label" }
                    var visibleInk = 0
                    val left = bounds.left.toInt().coerceIn(0, bitmap.width)
                    val right = bounds.right.toInt().coerceIn(0, bitmap.width)
                    val top = bounds.top.toInt().coerceIn(0, bitmap.height)
                    val bottom = bounds.bottom.toInt().coerceIn(0, bitmap.height)
                    for (y in top until bottom) for (x in left until right) {
                        val pixel = bitmap.getPixel(x, y)
                        if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= 100) visibleInk++
                    }
                    // Header/footer background channels are below100; this verifies actual
                    // light glyph pixels exist, beyond their semantic/layout presence.
                    assertTrue("Native bitmap omitted shell label: $label ($visibleInk foreground pixels)", visibleInk >= 4)
                }
                Files.write(Path.of(directory, "$name-windows.txt"),
                    ("Robolectric API35 native drawing; activity ${decor.width}x${decor.height}\nReal view invalidation, delivered frame and discarded native warm-up draw before capture.\n$note\n" + locations.joinToString("\n") + "\n" + geometry.joinToString("\n")).toByteArray())
            } finally { bitmap.recycle() }
        }
    }

    @Test(timeout = 90_000) fun inventoryEmptyAddActionIsVisibleAndDoesNotCreateRecords() {
        Fixture(populated = false).use { fixture ->
            fixture.render { CampaignLedgerPage(ledger) }
            compose.onNodeWithText("Your inventory is ready").assertIsDisplayed()
            compose.onNodeWithText("Item name").assertDoesNotExist()
            capture("rebuild-inventory-empty", fixture.activity.get())
            primary("Add Item")
            assertTrue(runBlocking { fixture.db.ledgerDao().allItems().isEmpty() })
            assertTrue(runBlocking { fixture.db.ledgerDao().allEvents().isEmpty() })
        }
    }

    @Test(timeout = 90_000) fun populatedInventoryAndItemOverlayPreserveLongNamesAndUnknownQuantity() {
        Fixture().use { fixture ->
            fixture.render { CampaignLedgerPage(ledger) }
            settle { compose.onAllNodesWithText(longItemName).fetchSemanticsNodes().isNotEmpty() }
            primary("Add Item")
            compose.onNodeWithText(longItemName).assertIsDisplayed()
            capture("rebuild-inventory-400", fixture.activity.get())
            val before = runBlocking { fixture.db.ledgerDao().allItems() }
            compose.onNodeWithText(longItemName).performClick()
            compose.onNodeWithText("Overview").assertIsDisplayed()
            primary("Edit item")
            compose.onNodeWithText("Close").assertIsDisplayed()
            capture("rebuild-inventory-item-overlay", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Close").performClick()
            assertEquals(before, runBlocking { fixture.db.ledgerDao().allItems() })
            assertNull(before.first { it.title == longItemName }.quantity)
        }
    }

    @Test(timeout = 90_000) fun inventionsOverviewAndProjectOverlayNeverCreateOutputFromBlueprint() {
        Fixture().use { fixture ->
            fixture.render { InventionsPageV2(ledger) }
            settle { compose.onAllNodesWithText(projectName).fetchSemanticsNodes().isNotEmpty() }
            primary("Add Invention")
            compose.onNodeWithText(projectName).assertIsDisplayed()
            capture("rebuild-inventions-400", fixture.activity.get())
            val before = runBlocking { fixture.db.ledgerDao().allItems() }
            compose.onNodeWithText(projectName).performClick()
            compose.onNodeWithText("Concept & Blueprint").assertIsDisplayed()
            primary("Edit invention")
            compose.onNodeWithText("Close").assertIsDisplayed()
            capture("rebuild-invention-project-overlay", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Close").performClick()
            assertEquals(before, runBlocking { fixture.db.ledgerDao().allItems() })
            assertTrue(runBlocking { fixture.db.recordsDao().allCrafts().isEmpty() })
            assertTrue(runBlocking { fixture.db.recordsDao().allConsumptions().isEmpty() })
        }
    }

    @Test(timeout = 90_000) fun connectionsDirectoryAndProfileOverlayRetainKnownInformationAndMilestone() {
        Fixture().use { fixture ->
            fixture.render { ConnectionsPageV2(ledger) }
            settle { compose.onAllNodesWithText(connectionName).fetchSemanticsNodes().isNotEmpty() }
            primary("Add")
            textFullyVisible("ASTRAVAR", singleLine = true)
            listOf("Armor", "Combat", "Campaign", "Config").forEach { textFullyVisible(it, singleLine = true) }
            compose.onNodeWithText("Returned the map").assertIsDisplayed()
            capture("rebuild-connections-400", fixture.activity.get())
            val before = runBlocking { fixture.db.recordsDao().allEntities() }
            compose.onNodeWithText(connectionName).performClick()
            compose.onNodeWithText("Known Information").assertIsDisplayed()
            compose.onNodeWithText("Knows the western road.").assertIsDisplayed()
            compose.onNodeWithText("Connection name").assertDoesNotExist()
            compose.onNodeWithText("Close").assertIsDisplayed()
            capture("rebuild-connection-profile-overlay", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Close").performClick()
            assertEquals(before, runBlocking { fixture.db.recordsDao().allEntities() })
            assertEquals(1, runBlocking { fixture.db.recordsDao().allMilestones().size })
        }
    }

    @Test(timeout = 90_000) fun eekCompactResourcesAndCapabilityOverlayDoNotRefillAnything() {
        Fixture().use { fixture ->
            fixture.render { EekPageV2(ledger) }
            settle { compose.onAllNodesWithText("Dormant").fetchSemanticsNodes().isNotEmpty() }
            primary("Edit status")
            compose.onNodeWithText("HP").assertIsDisplayed()
            compose.onNodeWithText("? / 30").assertIsDisplayed()
            val before = runBlocking { fixture.db.recordsDao().allCompanionResources() }
            capture("rebuild-eek-400", fixture.activity.get())
            compose.onNodeWithText("Capabilities").performScrollTo().performClick()
            compose.onNodeWithText("Protective Deflection").performScrollTo().performClick()
            compose.onNodeWithText("Close").assertIsDisplayed()
            capture("rebuild-eek-capability-overlay", fixture.activity.get(), modal = true)
            assertEquals(before, runBlocking { fixture.db.recordsDao().allCompanionResources() })
            assertEquals("Dormant", runBlocking { fixture.db.recordsDao().allCompanionStates().single().condition })
        }
    }

    @Test(timeout = 90_000) fun configAndOfflineSyncShowPendingReceiptStateWithoutNetworkOrCampaignWrites() {
        Fixture().use { fixture ->
            val command = runBlocking {
                fixture.sync.configure(PublicSandbox.DEFAULT_ORIGIN)
                fixture.sandboxDb.sandbox().endpoint(SandboxEndpoint(PublicSandbox.DEFAULT_ORIGIN, remoteRevision = 10, cursor = 10))
                val queued = fixture.sync.queueSynthetic("new_fact")
                try { fixture.sync.retryPending(); fail("Injected offline transport must fail") } catch (_: IOException) {}
                queued
            }
            fixture.render(destination = "Config") {
                var tool by remember { mutableStateOf<String?>(null) }
                RebuildConfigHub { tool = it }
                if (tool == "Fictional sync") RebuildToolSheet("Fictional sync", { tool = null }) { SandboxSyncPage(sync) }
            }
            compose.onNodeWithText("Spell reservoir").assertIsDisplayed()
            compose.onNodeWithText("Core management").assertDoesNotExist()
            capture("rebuild-config-400", fixture.activity.get())
            compose.onNodeWithText("Fictional sync").performScrollTo().performClick()
            settle { compose.onAllNodesWithText("Offline").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 pending · saved on this device").assertIsDisplayed()
            capture("rebuild-sync-offline", fixture.activity.get(), modal = true,
                note = "Injected IOException transport. No network call; this screenshot tests failure presentation, not live service availability.")
            primary("Read sandbox"); primary("Retry pending")
            val calls = fixture.networkCalls.get()
            compose.onNodeWithText("Retry pending").performClick()
            settle { fixture.networkCalls.get() > calls && compose.onAllNodes(hasText("Retry pending") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            runBlocking {
                val after = fixture.sandboxDb.sandbox().command(command.commandId)!!
                assertEquals(command.commandId, after.commandId); assertEquals(command.body, after.body)
                assertEquals(command.fingerprint, after.fingerprint); assertEquals("PENDING", after.status)
                assertEquals(10L, fixture.sandboxDb.sandbox().getEndpoint(command.origin)!!.cursor)
                assertNull(after.receiptJson)
            }
        }
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowLargerTextInventoryEditorKeepsActionsVisibleWithFocusedInputAndSimulatedImeInsets() {
        Fixture(populated = false).use { fixture ->
            fixture.render(fontScale = 1.3f) { CampaignLedgerPage(ledger) }
            primary("Add Item")
            capture("rebuild-inventory-360-font130", fixture.activity.get())
            compose.onNodeWithText("Add Item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Uncommitted synthetic draft")
            compose.onNodeWithText("Item name").assertIsFocused()
            val inset = (220 * fixture.context.resources.displayMetrics.density).toInt()
            compose.runOnIdle {
                val simulated = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, inset))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build()
                WindowInspector.getGlobalWindowViews().forEach { ViewCompat.dispatchApplyWindowInsets(it, simulated) }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Close").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            compose.onNodeWithText("Save new item").assertIsDisplayed().assertIsEnabled()
            textFullyVisible("Save new item")
            textFullyVisible("Cancel", singleLine = true)
            capture("rebuild-item-editor-360-font130-simulated-ime", fixture.activity.get(), modal = true,
                note = "Focused native text field plus synthetic 220dp IME WindowInsets. No keyboard surface was rendered; physical IME behavior still requires device testing.")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Discard").performClick()
            assertTrue(runBlocking { fixture.db.ledgerDao().allItems().isEmpty() })
        }
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowLargerTextConnectionEditorAndEekKeepPrimaryControlsReachable() {
        Fixture(populated = false).use { fixture ->
            fixture.render(fontScale = 1.3f) { ConnectionsPageV2(ledger) }
            primary("Add")
            compose.onNodeWithContentDescription("Add connection").performClick()
            compose.onNodeWithText("Connection name").performScrollTo().performTextInput("Unsaved synthetic person")
            compose.onNodeWithText("Close").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            compose.onNodeWithText("Save new connection").assertIsDisplayed().assertIsNotEnabled()
            textFullyVisible("Save new connection")
            textFullyVisible("Cancel", singleLine = true)
            capture("rebuild-connection-editor-360-font130", fixture.activity.get(), modal = true)
            compose.onNodeWithText("Cancel").performClick(); compose.onNodeWithText("Discard").performClick()
            assertTrue(runBlocking { fixture.db.recordsDao().allEntities().isEmpty() })
        }
    }
}
