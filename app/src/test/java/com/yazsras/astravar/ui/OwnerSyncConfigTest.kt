package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.OwnerSyncDb
import kotlinx.coroutines.runBlocking
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
class OwnerSyncConfigTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun default_connection_is_not_enrolled_and_remote_actions_are_disabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("astravar-owner-sync-settings", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { OwnerSyncConfigPage() } }
            compose.onNodeWithText("Real campaign sync is not activated").assertExists()
            compose.onNodeWithText("Device not enrolled").assertExists()
            compose.onNodeWithText("Read remote test history").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Send fictional pending changes").performScrollTo().assertIsNotEnabled()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun fictional_offline_change_is_durable_without_an_enrolled_service() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("astravar-owner-sync-queue.db")
        context.getSharedPreferences("astravar-owner-sync-settings", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { OwnerSyncConfigPage() } }
            compose.onNodeWithText("Verified service HTTPS origin").performTextInput("https://verified-owner-service.example")
            compose.onNodeWithText("Save test address").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fictional test scope saved. No information was sent.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Prepare fictional offline test").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fictional change saved locally. It has not been uploaded.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Send fictional pending changes").performScrollTo().assertIsNotEnabled()
        } finally { activity.pause().stop().destroy() }
        val db = OwnerSyncDb.open(context)
        try {
            val pending = runBlocking { db.sync().pending("https://verified-owner-service.example", "synthetic-owner-acceptance") }
            assertEquals(1, pending.size)
            assertEquals("PENDING", pending.single().state)
        } finally { db.close(); context.deleteDatabase("astravar-owner-sync-queue.db") }
    }
}
