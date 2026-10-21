package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Android clipboard/Compose verification, independent of backend or owner credentials. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PairingCodeClipboardTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

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
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                AstravarTheme { PairingCodeCopy(code) }
            }
        }
        val button = compose.onNodeWithTag("pairing-code-copy")
        button.assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        button.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Copied to clipboard.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }
}
