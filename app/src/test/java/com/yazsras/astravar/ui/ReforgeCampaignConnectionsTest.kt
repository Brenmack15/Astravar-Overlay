package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.flow.first
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
class ReforgeCampaignConnectionsTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun characterConnectionsRequiresExplicitNameOnlyReviewAndReadsBackIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playable = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val campaignDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val local = ReforgeRepository(playable)
        val campaign = CampaignLedgerRepository(campaignDb)
        val saved = runBlocking { local.create(ReforgeCharacter("fictional-connections-player", "Fictional connections player", level = 9, currentHp = 45)) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ReforgeCharacterSheet(campaign, local) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Campaign connections").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Campaign connections").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("I reviewed this name-only campaign identity").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create campaign identity").assertIsNotEnabled()
            assertTrue(runBlocking { campaign.records.characterProfiles.first() }.isEmpty())
            compose.onNodeWithText("I reviewed this name-only campaign identity").performScrollTo().performClick()
            compose.onNodeWithText("Create campaign identity").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Campaign identity ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fictional connections player · Character").assertExists()
            val profile = runBlocking { campaign.records.characterProfiles.first() }.single()
            assertEquals(saved.character.id, profile.id)
            assertNull(profile.currentHp); assertNull(profile.maximumHp); assertNull(profile.level)
            assertEquals(saved, runBlocking { local.get(saved.character.id) })
        } finally { activity.pause().stop().destroy(); playable.close(); campaignDb.close() }
    }

    @Test fun recreatedReviewResetsApprovalAndDisplaysLatestPlayableSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playable = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val campaignDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val local = ReforgeRepository(playable)
        val campaign = CampaignLedgerRepository(campaignDb)
        var saved = runBlocking { local.create(ReforgeCharacter("fictional-recreated-review", "Initially reviewed name")) }
        val visible = mutableStateOf(true)
        var registry = SaveableStateRegistry(null) { true }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme { ReforgeCampaignConnectionsDialog(saved, local, campaign, {}) }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("I reviewed this name-only campaign identity").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("I reviewed this name-only campaign identity").performScrollTo().performClick()
            compose.onNodeWithText("Create campaign identity").assertIsEnabled()
            var restoredState: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { restoredState = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            saved = runBlocking { local.save(saved.character.id, saved.revision, saved.character.copy(name = "Latest name after recreation"), "Fictional rename during recreation") }
            compose.runOnIdle { registry = SaveableStateRegistry(restoredState) { true }; visible.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("I reviewed this name-only campaign identity").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Latest name after recreation").assertExists()
            compose.onNodeWithText("Create campaign identity").assertIsNotEnabled()
            assertTrue(runBlocking { campaign.records.characterProfiles.first() }.isEmpty())
        } finally { activity.pause().stop().destroy(); playable.close(); campaignDb.close() }
    }
}
