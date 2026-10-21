package com.yazsras.astravar.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PairingCodeCopyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun copy_uses_only_the_visible_code_without_normalizing_characters() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { PairingCodeCopy("00aB-09Zx") } }
            compose.onNodeWithTag("pairing-code-copy").performClick()
            compose.onNodeWithText("Copied to clipboard.").assertExists()
            compose.runOnIdle {
                val manager = activity.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("00aB-09Zx", manager.primaryClip!!.getItemAt(0).text.toString())
            }
        } finally { activity.pause().stop().destroy() }
    }
}
