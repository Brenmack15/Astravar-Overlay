package com.yazsras.astravar.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@Composable private fun CampaignArchiveTestThemeV21(content: @Composable () -> Unit) {
    AstravarTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground, content = content) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignArchiveUiV21Test {
    @get:Rule val compose = createEmptyComposeRule()
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
    private fun record(id: String, title: String, body: String, category: String = "Reference", links: List<String> = emptyList()) = CampaignArchiveRecord(
        externalId = id, title = title, category = category, body = body, propertiesJson = "{\"originalField\":\"Preserved unknown\"}",
        sourceUrl = "https://example.invalid/source/$id", sourceRevision = "synthetic-revision-2", contentSha256 = campaignHash(body), relationIds = links, visibility = "player")
    private fun fixture(rows: List<CampaignArchiveRecord>) = CampaignArchivePayload("synthetic-ui-campaign", "Synthetic UI archive", 1,
        "https://example.invalid/synthetic-root", "synthetic-root-revision", rows,
        issues = listOf("Synthetic maximum HP remains unresolved."),
        attachments = listOf(CampaignArchiveAttachment("synthetic-url-file", "Synthetic attachment.txt", "text/plain", "https://example.invalid/attachment")))
    private fun import(repo: CampaignLedgerRepository, payload: CampaignArchivePayload) = runBlocking {
        val serialized = campaignJson.encodeToString(payload)
        val envelope = campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive", 1, campaignHash(serialized), serialized))
        val preview = repo.archive.preview(envelope)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        repo.archive.exportPreImportBackup(preview.fingerprint)
        repo.archive.commit(preview, confirmed = true)
    }

    @Test fun importEntryOpensOnlyAndroidDocumentPickerWithoutWritingCampaignRecords() {
        val database = db(); val repo = CampaignLedgerRepository(database)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { CampaignArchiveTestThemeV21 { CampaignArchiveImportV21(repo) } }
            compose.onNodeWithText("Choose campaign archive").performScrollTo().performClick()
            val picker = shadowOf(activity.get()).nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
            assertTrue(picker.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
            runBlocking {
                assertTrue(database.archiveDao().allReferences().isEmpty())
                assertTrue(database.archiveDao().allReceipts().isEmpty())
                assertTrue(database.ledgerDao().allItems().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); database.close() }
    }

    @Test fun previewExposesCategoriesOriginalSourcesAndEveryConflictWithoutApplyingThem() {
        val rows = listOf(record("synthetic-character", "Synthetic character dossier", "HP Unknown", "Dossier"),
            record("synthetic-history", "Synthetic chronicle", "Historical purchase", "Chronicle"))
        val candidate = CampaignArchivePreview(false, "Synthetic archive review", listOf("Synthetic preserved local conflict"),
            listOf("Synthetic maximum HP remains unresolved."), "0".repeat(64), "1".repeat(64), fixture(rows))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { CampaignArchiveTestThemeV21 { Page { CampaignArchivePreviewContentsV21(candidate) } } }
            compose.onNodeWithText("2 source records · 1 attachment records").performScrollTo().assertExists()
            compose.onNodeWithText("Dossier · 1").performScrollTo().assertExists()
            compose.onNodeWithText("Inspect original source records (2)").performScrollTo().performClick()
            compose.onNodeWithText("Synthetic character dossier · Dossier").performScrollTo().performClick()
            compose.onNodeWithText("HP Unknown").performScrollTo().assertExists()
            compose.onNodeWithText("Synthetic preserved local conflict").performScrollTo().assertExists()
            compose.onNodeWithText("Synthetic maximum HP remains unresolved.").performScrollTo().assertExists()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun librarySearchRetainsBodyPropertiesRelationsUnresolvedTargetsAndSourceRevision() {
        val database = db(); val repo = CampaignLedgerRepository(database)
        val receipt = import(repo, fixture(listOf(
            record("synthetic-owl", "Synthetic owl dossier", "Unresolved: needlememory battery", "Dossier", listOf("synthetic-blueprint", "synthetic-missing")),
            record("synthetic-blueprint", "Synthetic proposed blueprint", "Proposed only; no inventory output.", "Invention"))))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { CampaignArchiveTestThemeV21 { CampaignReferenceLibraryV21(repo) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic owl dossier").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Search campaign references").performTextInput("needlememory")
            compose.onNodeWithText("1 of 2 references").performScrollTo().assertExists()
            compose.onNodeWithText("Synthetic owl dossier").performClick()
            compose.onNode(hasText("Unresolved: needlememory battery") and hasAnyAncestor(isDialog())).performScrollTo().assertExists()
            compose.onNodeWithText("Source properties").performScrollTo().performClick()
            compose.onNodeWithText("{\"originalField\":\"Preserved unknown\"}").performScrollTo().assertExists()
            compose.onNodeWithText("notion-relation · unresolved reference: synthetic-ui-campaign::synthetic-missing").performScrollTo().assertExists()
            compose.onNodeWithText("Synthetic proposed blueprint").performScrollTo().performClick()
            compose.onNodeWithText("Proposed only; no inventory output.").performScrollTo().assertExists()
            compose.onNodeWithText("Source revision\nsynthetic-revision-2").performScrollTo().assertExists()
            runBlocking {
                assertTrue(repo.archive.verify(receipt.packageHash).valid)
                assertEquals(2, database.archiveDao().allReferences().size)
                assertTrue(database.ledgerDao().allItems().isEmpty())
                assertTrue(database.recordsDao().allCharacterProfiles().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); database.close() }
    }

    @Test fun originalTextPaginationExposesTailWithoutTruncatingStoredContent() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val text = "Synthetic start " + "x".repeat(12_020) + " Synthetic preserved tail"
        try {
            activity.get().setContent { CampaignArchiveTestThemeV21 { Page { ArchiveOriginalTextV21(text, "source text", "synthetic-long-source") } } }
            compose.onNodeWithText("Show more source text").performScrollTo().performClick()
            compose.onNodeWithText(text).assertExists()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun consentRequiresAnExplicitEnabledPlayerTap() {
        var approved = false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { CampaignArchiveTestThemeV21 {
                var consent by remember { mutableStateOf(false) }
                ArchiveConsentV21("Synthetic explicit consent", consent, enabled = true) { consent = it; approved = it }
            } }
            assertFalse(approved)
            compose.onNodeWithText("Synthetic explicit consent").performClick()
            compose.runOnIdle { assertTrue(approved) }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun onlyExplicitHttpsSourcesCanBeOpened() {
        assertEquals("https://example.invalid/path", safeCampaignSourceLinkV21("https://example.invalid/path"))
        listOf("javascript:alert(1)", "file:///private/data", "content://private", "http://example.invalid", "https://user:password@example.invalid", "https:///missing-host")
            .forEach { assertNull(it, safeCampaignSourceLinkV21(it)) }
    }
}
