package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class StorageRecoveryPanelTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun failedStorageKeepsIndependentRecoveryAndDiagnosticsReachable() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val opened = mutableListOf<String>()
        try {
            activity.get().setContent { AstravarTheme { StorageRecoveryPanel("Read failed") { opened += it } } }
            compose.onNodeWithText("Saved data needs attention").assertExists()
            compose.onNodeWithText("Opening saved data…").assertDoesNotExist()
            compose.onNodeWithText("Open diagnostics").performScrollTo().performClick()
            compose.onNodeWithText("Open character recovery").performScrollTo().performClick()
            compose.onNodeWithText("Open campaign recovery").performScrollTo().performClick()
            assertEquals(listOf("Help & diagnostics", "Character backup", "Campaign backup"), opened)
        } finally { activity.pause().stop().destroy() }
    }
}
