package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native Android clipboard/Compose verification, independent of backend or owner credentials. */
@RunWith(AndroidJUnit4::class)
class PairingCodeClipboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager get() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun emulatorOnly() {
        assertEquals("Native clipboard fixtures require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        // Earlier independent fixtures exercise screen-off and SystemUI notifications.
        // A resumed Compose tree alone does not establish native window/input focus.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("cmd statusbar collapse")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitWindowFocus()
    }

    private fun awaitWindowFocus() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.runOnUiThread { compose.activity.hasWindowFocus() }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AstravarTheme {
                // Match the production page's safe viewport; native touch coordinates must
                // not land inside API36's status bar at the top of an edge-to-edge window.
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
            }
        }
        awaitWindowFocus()
    }

    private fun assertCopied(code: String) {
        // UiDevice injects through system_server. Compose main-thread idleness alone is
        // not evidence that a native key event or clipboard access has completed.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("pairing-code-copied").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pairing-code-copied").assertTextEquals("Copied to clipboard.").assertIsDisplayed()
        var observedClip: ClipData? = null
        compose.waitUntil(timeoutMillis = 10_000) {
            observedClip = compose.runOnUiThread {
                if (compose.activity.hasWindowFocus()) clipboard.primaryClip else null
            }
            observedClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString() == code
        }
        // Assert the actual focused binder read rather than racing another clipboard read
        // against a later system-window transition.
        val copied = requireNotNull(observedClip) { "Focused Android clipboard read must return the copied code" }
        assertEquals(1, copied.itemCount)
        assertEquals(code, copied.getItemAt(0).text.toString())
        assertNotEquals("Pairing code: $code", copied.getItemAt(0).text.toString())
    }

    private fun withFocusDiagnostics(name: String, block: () -> Unit) {
        try { block() }
        finally {
            // Retain actual focus/clipboard conditions without storing clipboard contents.
            runCatching {
                val state = compose.runOnUiThread {
                    "windowFocused=${compose.activity.hasWindowFocus()}\n" +
                        "lifecycle=${compose.activity.lifecycle.currentState}\n" +
                        "clipboardPresent=${clipboard.hasPrimaryClip()}\n"
                }
                File(context.getExternalFilesDir(null), "pairing-$name-focus.txt")
                    .writeText("screenOn=${device.isScreenOn}\n" + state)
            }
        }
    }

    @Test fun tap_copies_exact_displayed_code_and_announces_feedback() = withFocusDiagnostics("tap") {
        val code = "00aB-09Zx"
        show { PairingCodeCopy(code) }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("previous", "previous")) }
        awaitWindowFocus()
        compose.onNodeWithTag("pairing-code-copy").assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Copy Pairing code: $code").performClick()
        assertCopied(code)
    }

    @Test fun keyboard_activation_and_large_text_preserve_the_exact_code() = withFocusDiagnostics("keyboard") {
        val code = "0000-ZzAa-1"
        lateinit var inputModeManager: InputModeManager
        show {
            inputModeManager = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                PairingCodeCopy(code)
            }
        }
        val button = compose.onNodeWithTag("pairing-code-copy")
        button.assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {
            // Material buttons intentionally refuse keyboard focus while Android is in touch mode.
            assertTrue("Native keyboard input mode must be available", inputModeManager.requestInputMode(InputMode.Keyboard))
            assertEquals(InputMode.Keyboard, inputModeManager.inputMode)
        }
        // Android may initially focus its decor or the Compose root. Navigate through the
        // native focus order, with a strict bound, rather than bypassing it through semantics.
        awaitWindowFocus()
        assertTrue("Native Tab navigation must be delivered", device.pressKeyCode(KeyEvent.KEYCODE_TAB))
        compose.waitForIdle()
        repeat(2) {
            if (compose.onAllNodes(hasTestTag("pairing-code-copy") and isFocused()).fetchSemanticsNodes().isEmpty()) {
                assertTrue("Native Tab navigation must be delivered", device.pressKeyCode(KeyEvent.KEYCODE_TAB))
                compose.waitForIdle()
            }
        }
        button.assertIsFocused()
        awaitWindowFocus()
        assertTrue("Native Enter activation must be delivered", device.pressKeyCode(KeyEvent.KEYCODE_ENTER))
        compose.waitForIdle()
        assertCopied(code)
    }
}
