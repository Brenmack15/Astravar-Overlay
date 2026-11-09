package com.yazsras.astravar.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.room.MultiInstanceInvalidationService
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.CampaignLedgerDb
import com.yazsras.astravar.data.CampaignLedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RebirthNavigationStateTest {
    @get:Rule val compose = createAstravarComposeRule()
    private var oldRequestedSize: String? = null

    @Before fun configureNativeDialogsAndRoomBinding() {
        oldRequestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(app, MultiInstanceInvalidationService::class.java)
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create().get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), service.onBind(intent))
    }

    @After fun restoreDialogSize() {
        oldRequestedSize?.let { System.setProperty("robolectric.windowManager.useRequestedSize", it) }
            ?: System.clearProperty("robolectric.windowManager.useRequestedSize")
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
    ).build()

    /** A parcel round trip prevents retaining live objects across the simulated Activity restart. */
    private fun savedState(controller: ActivityController<ComponentActivity>): Bundle {
        val state = Bundle()
        controller.pause().saveInstanceState(state).resume()
        val writer = Parcel.obtain()
        val bytes = try { state.writeToParcel(writer, 0); writer.marshall() } finally { writer.recycle() }
        val reader = Parcel.obtain()
        return try {
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(reader).also { it.classLoader = ComponentActivity::class.java.classLoader }
        } finally { reader.recycle() }
    }

    @Test fun activityRecreationRetainsCampaignItemsAndItsUncommittedDraft() {
        val db = database()
        val repository = CampaignLedgerRepository(db)
        var activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun mount() { activity.get().setContent { AstravarTheme { RebuildInventoryHub(repository, {}) } } }
        try {
            mount()
            compose.onNodeWithTag("section-Campaign items").performClick()
            compose.onNodeWithText("Add Item").performClick()
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Uncommitted fictional compass")
            compose.onNodeWithText("Quantity").performScrollTo().performTextInput("2")
            val saved = savedState(activity)
            activity.pause().stop().destroy()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
            mount()
            compose.waitForIdle()
            compose.onNodeWithText("Item name").performScrollTo().assertTextContains("Uncommitted fictional compass")
            compose.onNodeWithText("Quantity").performScrollTo().assertTextContains("2")
            compose.onNodeWithText("Save new item").assertIsEnabled()
            runBlocking { assertTrue(db.ledgerDao().allItems().isEmpty()); assertTrue(db.ledgerDao().allEvents().isEmpty()) }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithTag("section-Campaign items").assertIsSelected()
            compose.onNodeWithText("Add Item").assertIsDisplayed()
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun changedExternalRouteIsAppliedOnceAndOrdinaryRecompositionKeepsUserSelection() {
        val db = database()
        val repository = CampaignLedgerRepository(db)
        val requested = mutableStateOf("Campaign items")
        val token = mutableStateOf(0)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                RebuildInventoryHub(repository, {}, requested.value, token.value)
            } }
            compose.onNodeWithTag("section-Campaign items").assertIsSelected()
            compose.onNodeWithTag("section-My gear").performClick()
            compose.onNodeWithTag("section-My gear").assertIsSelected()
            compose.runOnIdle { token.value = 1 }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Campaign items").assertIsSelected()
            compose.onNodeWithText("Add Item").assertIsDisplayed()
            compose.runOnIdle { requested.value = "My gear" }
            compose.waitForIdle()
            compose.onNodeWithTag("section-Campaign items").assertIsSelected()
            compose.runOnIdle { token.value = 2 }
            compose.waitForIdle()
            compose.onNodeWithTag("section-My gear").assertIsSelected()
            runBlocking { assertTrue(db.ledgerDao().allItems().isEmpty()); assertTrue(db.ledgerDao().allEvents().isEmpty()) }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun scaledTabsKeepMultiwordLabelsOnOneLineAndRevealProgrammaticSelection() {
        val selected = mutableStateOf("My gear")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    PlayerTabs(listOf("My gear", "Campaign items", "Equipment", "Spellbook", "History"), selected.value,
                        { selected.value = it })
                }
            } }
            val layouts = mutableListOf<TextLayoutResult>()
            val label = compose.onNodeWithText("Campaign items", useUnmergedTree = true).fetchSemanticsNode()
            compose.runOnIdle {
                assertTrue(label.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertEquals(1, layouts.single().lineCount)
                selected.value = "History"
            }
            compose.waitForIdle()
            compose.onNodeWithTag("section-History").assertIsSelected().assertIsDisplayed()
            val visibleTab = compose.onNodeWithTag("section-History").fetchSemanticsNode()
            assertTrue("Selected tab must retain at least its 48dp touch height", visibleTab.boundsInRoot.height >= 48f)
        } finally { activity.pause().stop().destroy() }
    }
}
