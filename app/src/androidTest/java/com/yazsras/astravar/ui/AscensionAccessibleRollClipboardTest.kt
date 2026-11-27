package com.yazsras.astravar.ui

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

@RunWith(AndroidJUnit4::class)
class AscensionAccessibleRollClipboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun walk(root: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && n.contentDescription?.toString() == description &&
                n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    @Test fun screenReaderOptionsEnterOnePlayerFaceAndPasteItsExactNamedResultInASecondPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals(37, Build.VERSION.SDK_INT)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val local = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val ledger = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val repo = ReforgeRepository(local)
        val actor = ReforgeCharacter("qa-accessible-clipboard","Fixture artisan",level=4,
            baseAbilities=Stat.entries.associateWith { if (it == Stat.STR) 16 else 10 },baseMaxHp=20,currentHp=16)
        runBlocking { repo.create(actor) }
        val before = runBlocking { requireNotNull(repo.get(actor.id)) }
        try {
            compose.setContent { AstravarTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                ReforgeCharacterSheet(CampaignLedgerRepository(ledger),repo)
            } } }
            compose.waitUntil(20000) { compose.onAllNodesWithTag("reforge-check-STR").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reforge-check-STR").performScrollTo().assertIsDisplayed()
            var exported: AccessibilityNodeInfo? = null
            compose.waitUntil(15000) {
                exported = walk(instrumentation.uiAutomation.rootInActiveWindow, "Strength check +3")
                exported != null
            }
            assertTrue(requireNotNull(exported).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            val manual = hasText("Enter physical dice faces") and hasClickAction()
            compose.waitUntil(12000) { compose.onAllNodes(manual).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(runBlocking { repo.rollHistory(actor.id).first() }.isEmpty())
            compose.onNode(manual).performScrollTo().performClick()
            compose.onNode(hasText("Faces in order (comma separated)") and hasSetTextAction()).performScrollTo().performTextReplacement("17")
            compose.onNodeWithText("Validate entered dice").performScrollTo().performClick()
            val line = compose.onNodeWithTag("reforge-roll-result").assertTextEquals("Strength check · 20")
            repeat(2) { line.performScrollTo().performClick() }
            assertTrue(runBlocking { repo.rollHistory(actor.id).first() }.isEmpty())
            compose.onNodeWithText("Save roll").performScrollTo().performClick()
            compose.waitUntil(10000) { runBlocking { repo.rollHistory(actor.id).first() }.size == 1 }
            val only = runBlocking { repo.rollHistory(actor.id).first() }.single()
            assertEquals(17,only.components.single().dice.single().face)
            assertEquals(3,only.components.single().modifier)
            assertEquals("Strength check",only.label)
            val app = "com.yazsras.astravar.target"
            device.executeShellCommand("am start -W -n $app/.TargetActivity")
            val input = requireNotNull(device.wait(Until.findObject(By.pkg(app).clazz("android.widget.EditText")),15000))
            input.click()
            assertTrue(device.wait(Until.hasObject(By.pkg(app).clazz("android.widget.EditText").focused(true)),8000))
            assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_PASTE))
            val expected = "Strength check: (17) + 3 = 20"
            assertTrue("The second package received the original named roll",device.wait(Until.hasObject(By.pkg(app).clazz("android.widget.EditText").text(expected)),10000))
            assertEquals(listOf(only),runBlocking { repo.rollHistory(actor.id).first() })
            assertEquals(before,runBlocking { requireNotNull(repo.get(actor.id)) })
        } finally {
            device.pressBack()
            ledger.close()
            local.close()
        }
    }
}
