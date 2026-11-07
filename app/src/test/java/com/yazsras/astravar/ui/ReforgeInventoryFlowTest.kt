package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReforgeInventoryFlowTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun itemCanBeCreatedWithoutCitationAndOriginalAttributionStaysBehindDetails() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val old = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        try {
            controller.get().setContent { AstravarTheme { CampaignLedgerPage(repo) } }
            compose.onNodeWithText("Add Item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Synthetic compass")
            compose.onNodeWithText("Source / checkpoint (optional)").assertDoesNotExist()
            compose.onNodeWithText("Save new item").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { db.ledgerDao().allItems().size == 1 } }
            val item = runBlocking { db.ledgerDao().allItems().single() }
            assertEquals("Local inventory edit", item.source)
            assertNull(item.quantity)
            assertEquals(1, runBlocking { db.ledgerDao().allEvents().size })
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Sources & record details").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Local inventory edit").assertDoesNotExist()
            compose.onNodeWithText("Sources & record details").performScrollTo().performClick()
            compose.onNodeWithText("Local inventory edit").performScrollTo().assertIsDisplayed()
            assertEquals(item, runBlocking { db.ledgerDao().getItem(item.id) })
        } finally {
            controller.pause().stop().destroy(); db.close()
            if (old == null) System.clearProperty("robolectric.windowManager.useRequestedSize") else System.setProperty("robolectric.windowManager.useRequestedSize", old)
        }
    }
}
