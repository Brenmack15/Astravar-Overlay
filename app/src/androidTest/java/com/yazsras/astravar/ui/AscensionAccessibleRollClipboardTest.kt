package com.yazsras.astravar.ui

import android.accessibilityservice.AccessibilityServiceInfo
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
import androidx.lifecycle.Lifecycle
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

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return found
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && found.size < 2048) {
            val n = queue.removeFirst()
            found += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return found
    }

    @Test fun androidAccessibilityOptionsOpenWithoutRolling() = exercise(platformOptions = true)

    @Test fun aSecondPackagePastesTheOriginalNamedRollFromTheSystemClipboard() = exercise(platformOptions = false)

    private fun exercise(platformOptions: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals(37, Build.VERSION.SDK_INT)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15000) { compose.runOnUiThread { compose.activity.hasWindowFocus() } }
        val automation=instrumentation.uiAutomation
        val service=automation.serviceInfo
        val originalFlags=service.flags
        val originalFeedback=service.feedbackType
        service.flags=service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        service.feedbackType=originalFeedback or AccessibilityServiceInfo.FEEDBACK_SPOKEN
        automation.serviceInfo=service
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
            compose.onNodeWithTag("reforge-check-STR").performScrollTo().assertIsDisplayed().assertContentDescriptionEquals("Strength check +3")
            if (platformOptions) {
            var exported: AccessibilityNodeInfo? = null
            var details = ""
            try {
                compose.waitUntil(18000) {
                    val roots=automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                    val visible=roots.flatMap(::nodes)
                    val described=visible.filter { n->n.packageName==context.packageName && !n.contentDescription.isNullOrBlank() }
                    details=described.joinToString("\n"){ n->"${n.className} ${n.contentDescription} visible=${n.isVisibleToUser} actions=${n.actionList.joinToString { it.id.toString()+"/"+it.label }}" }.take(8000)
                    val controls=described.filter { it.contentDescription.toString()=="Strength check +3" }
                        .flatMap { label -> generateSequence(label) { it.parent }.take(6).toList() }
                    details=controls.joinToString("\n") { n ->
                        "${n.className} ${n.contentDescription} ${n.text} ${n.actionList.joinToString { it.id.toString()+"/"+it.label }}"
                    } + "\n" + details
                    exported=controls.firstOrNull { n -> n.isVisibleToUser && n.actionList.any {
                        it.id==AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString()=="Options for Strength check"
                    } }
                    exported != null
                }
            } catch (failure: AssertionError) {
                throw AssertionError("Framework options absent. Focused package ${device.currentPackageName}.\n$details", failure)
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Framework options absent. Focused package ${device.currentPackageName}.\n$details", failure)
            }
            assertTrue(requireNotNull(exported).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            } else {
                compose.onNodeWithTag("reforge-check-STR").performTouchInput { longClick(center) }
            }
            val manual = hasText("Enter physical dice faces") and hasClickAction()
            compose.waitUntil(12000) { compose.onAllNodes(manual).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(runBlocking { repo.rollHistory(actor.id).first() }.isEmpty())
            if(platformOptions) {
                assertEquals(before,runBlocking { requireNotNull(repo.get(actor.id)) })
                return
            }
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
            val previous = "Prior synthetic receiver text"
            input.text = previous
            assertTrue(device.wait(Until.hasObject(By.pkg(app).clazz("android.widget.EditText").text(previous)), 5000))
            input.clear()
            compose.waitUntil(8000) {
                nodes(automation.rootInActiveWindow).any { field ->
                    field.packageName == app && field.className == "android.widget.EditText" && field.isEditable &&
                        (field.text.isNullOrEmpty() || field.isShowingHintText)
                }
            }
            assertTrue(device.wait(Until.hasObject(By.pkg(app).clazz("android.widget.EditText").focused(true)),8000))
            assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_PASTE))
            val expected = "Strength check: (17) + 3 = 20"
            assertTrue("The second package received the original named roll",device.wait(Until.hasObject(By.pkg(app).clazz("android.widget.EditText").text(expected)),10000))
            assertEquals(listOf(only),runBlocking { repo.rollHistory(actor.id).first() })
            assertEquals(before,runBlocking { requireNotNull(repo.get(actor.id)) })
        } finally {
            service.flags=originalFlags
            service.feedbackType=originalFeedback
            automation.serviceInfo=service
            device.pressBack()
            ledger.close()
            local.close()
        }
    }
}
