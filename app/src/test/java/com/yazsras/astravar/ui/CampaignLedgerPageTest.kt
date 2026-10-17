package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.CampaignLedgerDb
import com.yazsras.astravar.data.CampaignLedgerRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CampaignLedgerPageTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun newItemClearsAnUnsavedNewItemForm() {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
        ).build()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                AstravarTheme { CampaignLedgerPage(CampaignLedgerRepository(db)) }
            }
            compose.onNodeWithText("Item name").performScrollTo().performTextInput("Unsaved draft")
            compose.onNodeWithText("New item").performScrollTo().performClick()
            compose.onNodeWithText("Item name").performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
            compose.onNodeWithText("Save new item").performScrollTo().assertIsNotEnabled()
        } finally {
            activity.pause().stop().destroy()
            db.close()
        }
    }
}
