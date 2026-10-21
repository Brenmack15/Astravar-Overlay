package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.rules.Profile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp")
@LooperMode(LooperMode.Mode.PAUSED)
class WeaponHistoryBackupUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun restoreConfirmationRequiresACompleteCurrentBackupAndUnchangedPreview() {
        val backedUp = mutableStateOf(false)
        val stale = mutableStateOf(false)
        val busy = mutableStateOf(false)
        var restoreRequests = 0
        var cancelRequests = 0
        var backupRequests = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page {
                WeaponHistoryRestoreReview(Campaign(profile = Profile(character = "Synthetic restore")),
                    archiveCount = 40, archiveBytes = 4096, originalFileCount = 1, originalBytes = 8192,
                    legacyJson = true, backedUp = backedUp.value, stale = stale.value, busy = busy.value,
                    backUp = { backupRequests++ }, restore = { restoreRequests++ }, cancel = { cancelRequests++ })
            } } }
            compose.onNodeWithText("Review restore confirmation").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Back up current complete history").performScrollTo().performClick()
            assertEquals(1, backupRequests)
            assertEquals(0, restoreRequests)
            compose.runOnIdle { backedUp.value = true; stale.value = true }
            compose.onNodeWithText("Review restore confirmation").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { stale.value = false; busy.value = true }
            compose.onNodeWithText("Review restore confirmation").assertIsNotEnabled()
            compose.runOnIdle { busy.value = false }
            compose.onNodeWithText("Review restore confirmation").assertIsEnabled().performClick()
            assertEquals(1, restoreRequests)
            compose.onNodeWithText("Cancel weapon restore").performScrollTo().performClick()
            assertEquals(1, cancelRequests)
            assertEquals(1, restoreRequests)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun legacyPreviewExplainsCompleteOriginalHistorySeparateFromRecentRollView() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page {
                WeaponHistoryRestoreReview(Campaign(profile = Profile(character = "Synthetic legacy restore")),
                    archiveCount = 75, archiveBytes = 12288, originalFileCount = 2, originalBytes = 65536,
                    legacyJson = true, backedUp = false, stale = false, busy = false,
                    backUp = {}, restore = { fail("Preview must not apply a restore") }, cancel = {})
            } } }
            compose.onNodeWithText("Full history: 75 archive entries (12288 bytes) · 2 original files (65536 bytes)").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("Legacy JSON: exact original bytes are preserved", substring = true)).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("Only the recent-roll view is limited to 20", substring = true)).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("discarded", substring = true)).assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test(timeout = 90_000)
    @Config(sdk = [35], qualifiers = "w360dp-h780dp")
    fun narrowLargeTextReviewKeepsExplanationsAndGuardedControlsReachable() {
        val backedUp = mutableStateOf(false)
        val stale = mutableStateOf(false)
        var restoreRequests = 0
        var backupRequests = 0
        var cancelRequests = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun assertReadable(matcher: SemanticsMatcher) {
            val interaction = compose.onNode(matcher, useUnmergedTree = true)
            interaction.performScrollTo().assertIsDisplayed()
            val node = interaction.fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle {
                assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                val layout = layouts.single()
                assertFalse("Explanatory text must retain every line", layout.multiParagraph.didExceedMaxLines)
                assertFalse("Text must not be clipped vertically", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("Text must not be ellipsized", layout.isLineEllipsized(line))
                    assertTrue("Text ink must fit its width", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                }
                assertTrue("Scrolled text must fit inside its visible ancestors", node.boundsInRoot.height + 1f >= layout.size.height)
                assertTrue("Text must not be clipped horizontally", node.boundsInRoot.width + 1f >= layout.size.width)
            }
        }
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    AstravarTheme { Page {
                        WeaponHistoryRestoreReview(Campaign(profile = Profile(character = "Synthetic narrow restore")),
                            archiveCount = 75, archiveBytes = 12288, originalFileCount = 2, originalBytes = 65536,
                            legacyJson = true, backedUp = backedUp.value, stale = stale.value, busy = false,
                            backUp = { backupRequests++ }, restore = { restoreRequests++ }, cancel = { cancelRequests++ })
                    } }
                }
            }
            assertReadable(hasText("Full history: 75 archive entries", substring = true))
            assertReadable(hasText("Legacy JSON: exact original bytes are preserved", substring = true))
            assertReadable(hasText("Only the recent-roll view is limited to 20", substring = true))
            compose.onNodeWithText("Review restore confirmation").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
            compose.onNodeWithText("Back up current complete history").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, backupRequests)
            assertEquals(0, restoreRequests)
            compose.runOnIdle { backedUp.value = true; stale.value = true }
            assertReadable(hasText("Weapon records changed after this preview", substring = true))
            compose.onNodeWithText("Review restore confirmation").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { stale.value = false }
            compose.onNodeWithText("Review restore confirmation").performScrollTo().assertIsEnabled().performClick()
            assertEquals(1, restoreRequests)
            compose.onNodeWithText("Cancel weapon restore").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, cancelRequests)
            assertEquals(1, restoreRequests)
        } finally { activity.pause().stop().destroy() }
    }

}
