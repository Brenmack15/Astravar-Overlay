package com.yazsras.astravar.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
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
class ChatGptToolkitTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun searchFindsRecoveryInstructionsAndCopyKeepsTheCompleteEvergreenPrompt() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ChatGptPromptsPage() } }
            compose.onNodeWithText("Find a prompt").performTextInput("last resolved event")
            compose.onNodeWithText("Continue a campaign in a new chat").assertExists()
            compose.onNodeWithText("Read prompt").performScrollTo().performClick()
            compose.onNodeWithText("Act as DM for my established solo campaign.", substring = true).assertExists()
            compose.onNodeWithText("Copy handoff prompt").performScrollTo().performClick()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(text.contains("through the connected authorized integration when available"))
            assertTrue(text.contains("Never invent actions, rolls, HP, currency"))
            assertFalse(text.contains("28c890"))
            compose.onNodeWithText("Copied — paste in ChatGPT").assertExists()
        } finally { activity.pause().stop().destroy() }
    }
}
