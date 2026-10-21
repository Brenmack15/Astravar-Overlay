package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Native Android clipboard/Compose verification, independent of backend or owner credentials. */
@RunWith(AndroidJUnit4::class)
class PairingCodeClipboardTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun emulatorOnly() {
        assertEquals("Native clipboard fixtures require an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
    }

    @Test fun tap_copies_exact_displayed_code_and_announces_feedback() {
        val code = "00aB-09Zx"
        compose.setContent { AstravarTheme { PairingCodeCopy(code) } }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("previous", "previous")) }
        compose.onNodeWithTag("pairing-code-copy").assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Copy Pairing code: $code").performClick()
        compose.onNodeWithText("Copied to clipboard.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertNotEquals("Pairing code: $code", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun keyboard_activation_and_large_text_preserve_the_exact_code() {
        val code = "0000-ZzAa-1"
        lateinit var inputModeManager: InputModeManager
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                AstravarTheme { PairingCodeCopy(code) }
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
        assertTrue("Native Tab navigation must be delivered", device.pressKeyCode(KeyEvent.KEYCODE_TAB))
        compose.waitForIdle()
        repeat(2) {
            if (compose.onAllNodes(hasTestTag("pairing-code-copy") and isFocused()).fetchSemanticsNodes().isEmpty()) {
                assertTrue("Native Tab navigation must be delivered", device.pressKeyCode(KeyEvent.KEYCODE_TAB))
                compose.waitForIdle()
            }
        }
        button.assertIsFocused()
        assertTrue("Native Enter activation must be delivered", device.pressKeyCode(KeyEvent.KEYCODE_ENTER))
        compose.waitForIdle()
        compose.onNodeWithText("Copied to clipboard.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }
}
