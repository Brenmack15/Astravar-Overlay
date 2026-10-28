package com.yazsras.astravar.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.room.MultiInstanceInvalidationService
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Exercises the real UI and its independent SQLite read connection, using fictional records only. */
@OptIn(androidx.room.ExperimentalRoomApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignConflictAuditUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "astravar-campaign-ledger.db"
    private lateinit var invalidationService: ServiceController<MultiInstanceInvalidationService>

    @Before fun bindRealInvalidationService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(app, MultiInstanceInvalidationService::class.java)
        invalidationService = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        // Android supplies both values. Robolectric's unconfigured binding supplies nulls,
        // so bind the real Room service without weakening production invalidation behavior.
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), invalidationService.get().onBind(intent))
    }

    @After fun destroyInvalidationService() { invalidationService.destroy() }

    private fun fixture(unresolved: Boolean = false): CampaignArchivePayload {
        val body = "Fictional private original body; no current resources are established."
        return CampaignArchivePayload("synthetic-audit-ui", "Synthetic audit archive", 1,
            "https://example.invalid/synthetic-audit", "synthetic-revision", listOf(
                CampaignArchiveRecord("synthetic-source", "Synthetic source", "Reference", body,
                    sourceUrl = "https://example.invalid/synthetic-source", sourceRevision = "synthetic-revision",
                    contentSha256 = campaignHash(body), relationIds = if (unresolved) listOf("synthetic-missing") else emptyList(),
                    visibility = "player")),
            issues = if (unresolved) listOf("Synthetic original source note") else emptyList())
    }

    private fun import(repository: CampaignLedgerRepository, payload: CampaignArchivePayload) = runBlocking {
        val json = campaignJson.encodeToString(payload)
        val envelope = campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive", 1, campaignHash(json), json))
        val preview = repository.archive.preview(envelope)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        repository.archive.exportPreImportBackup(preview.fingerprint)
        repository.archive.commit(preview, confirmed = true)
    }

    @Test fun verifyButtonChecksSavedImportAndCopiesDiagnosticWithoutChangingAnySavedRecords() {
        context.deleteDatabase(databaseName)
        val database = CampaignLedgerDb.open(context)
        val repository = CampaignLedgerRepository(database)
        import(repository, fixture())
        val before = runBlocking { CampaignBackup(database).snapshot() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CampaignArchiveImportV21(repository) } }
            compose.onNodeWithText("Verify all saved conflicts").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No blocking conflicts detected").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 sources · 0 links · 0 items · 0 wallets · 1/1 saved imports verified").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Snapshot of saved records when this check ran.", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Copy diagnostic summary for ChatGPT").performScrollTo().performClick()
            compose.runOnIdle {
                val copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied.contains("Verified imports: 1"))
                assertFalse(copied.contains(fixture().records.single().body))
            }
            assertEquals(before, runBlocking { CampaignBackup(database).snapshot() })
        } finally {
            activity.pause().stop().destroy()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun integrityAndDecisionGroupsExposeEvidenceWhileSourceNotesRequireExpansion() {
        context.deleteDatabase(databaseName)
        val database = CampaignLedgerDb.open(context)
        val repository = CampaignLedgerRepository(database)
        import(repository, fixture(unresolved = true))
        database.openHelper.writableDatabase.execSQL("UPDATE campaign_references SET body=? WHERE externalId=?",
            arrayOf("Fictional corruption solely inside isolated test database", "synthetic-source"))
        val before = runBlocking { CampaignBackup(database).snapshot() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CampaignArchiveImportV21(repository) } }
            compose.onNodeWithText("Verify all saved conflicts").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Verification needs attention").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Source integrity · Synthetic source").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Relationships · Unresolved relationship target").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Source notes · Unresolved source note").assertDoesNotExist()
            compose.onNodeWithText("Source notices (1)").performScrollTo().performClick()
            compose.onNodeWithText("Source notes · Unresolved source note").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Copy diagnostic summary for ChatGPT").performScrollTo().performClick()
            compose.runOnIdle {
                val copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied.contains("[BLOCKED] Source integrity"))
                assertTrue(copied.contains("[REVIEW] Relationships"))
                assertTrue(copied.contains("Synthetic original source note"))
                assertFalse(copied.contains(fixture().records.single().body))
                assertFalse(copied.contains("Fictional corruption solely inside isolated test database"))
            }
            assertEquals(before, runBlocking { CampaignBackup(database).snapshot() })
        } finally {
            activity.pause().stop().destroy()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
