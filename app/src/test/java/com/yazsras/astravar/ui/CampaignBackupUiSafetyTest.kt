package com.yazsras.astravar.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.core.app.ActivityOptionsCompat
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** Real Compose and document bytes; only the system picker's selected URI is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CampaignBackupUiSafetyTest {
    @get:Rule val compose = createAstravarComposeRule()
    private class Picker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        var selected: Uri? = null
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            dispatchResult(requestCode, selected)
        }
    }

    /** A provider may acknowledge a write yet return truncated bytes when reopened. */
    class CorruptingProvider : ContentProvider() {
        lateinit var destination: File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode == "r") destination.writeText("truncated saved document")
            val access = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
            return ParcelFileDescriptor.open(destination, access)
        }
    }

    private fun mount(repo: CampaignLedgerRepository, picker: Picker, activity: ComponentActivity) {
        activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                AstravarTheme { CampaignBackupPageV2(repo) }
            }
        }
    }
    private fun preview() {
        compose.onNodeWithText("Choose file for preview").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("campaign-confirm-restore").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForRestoreEnabled() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("campaign-confirm-restore") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun verifiedBackupProtectsItsExactSnapshotAndCannotUnlockANewerPreview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        val source = CampaignLedgerRepository(sourceDb)
        val incoming = File(context.cacheDir, "fictional-campaign-incoming.json")
        val backupFile = File(context.cacheDir, "fictional-campaign-protected.json")
        val picker = Picker()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            runBlocking {
                repo.create(LedgerDraft("Existing fictional lens"))
                source.create(LedgerDraft("Imported fictional crystal"))
                incoming.writeText(source.export())
            }
            mount(repo, picker, activity.get())
            picker.selected = Uri.fromFile(incoming)
            preview()
            compose.onNodeWithTag("campaign-confirm-restore").assertIsNotEnabled()
            picker.selected = Uri.fromFile(backupFile)
            compose.onNodeWithText("Save current backup").performScrollTo().performClick()
            waitForRestoreEnabled()
            assertEquals(listOf("Existing fictional lens"), campaignDecodeBackup(backupFile.readText()).items.map { it.title })
            compose.onNodeWithText("Cancel").performClick()

            // The old backup was valid, but a later snapshot needs its own verified protection.
            runBlocking { repo.create(LedgerDraft("Later fictional acquisition")) }
            picker.selected = Uri.fromFile(incoming)
            preview()
            compose.onNodeWithTag("campaign-confirm-restore").assertIsNotEnabled()
            assertEquals(2, runBlocking { db.ledgerDao().allItems().size })
            picker.selected = Uri.fromFile(backupFile)
            compose.onNodeWithText("Save current backup").performScrollTo().performClick()
            waitForRestoreEnabled()
            assertEquals(2, campaignDecodeBackup(backupFile.readText()).items.size)
            compose.onNodeWithTag("campaign-confirm-restore").performClick()
            compose.waitUntil(15_000) { runBlocking { db.ledgerDao().allItems().size == 3 } }
            assertEquals(1, runBlocking { db.recordsDao().allReceipts().count { it.kind == "RESTORE" } })
        } finally {
            activity.pause().stop().destroy(); db.close(); sourceDb.close()
            incoming.delete(); backupFile.delete()
        }
    }

    @Test fun acknowledgedButTruncatedProviderWriteCannotUnlockRestore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val repo = CampaignLedgerRepository(db)
        val incoming = File(context.cacheDir, "fictional-campaign-corruption-incoming.json")
        val destination = File(context.cacheDir, "fictional-campaign-corruption-protected.json")
        val picker = Picker()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val provider = CorruptingProvider().apply {
            this.destination = destination
            attachInfo(context, ProviderInfo().apply { authority = "fictional-campaign-backup" })
        }
        ShadowContentResolver.registerProviderInternal("fictional-campaign-backup", provider)
        try {
            runBlocking {
                repo.create(LedgerDraft("Existing fictional lens"))
                val source = CampaignLedgerRepository(sourceDb)
                source.create(LedgerDraft("Imported fictional crystal"))
                incoming.writeText(source.export())
            }
            mount(repo, picker, activity.get())
            picker.selected = Uri.fromFile(incoming)
            preview()
            picker.selected = Uri.parse("content://fictional-campaign-backup/protected.json")
            compose.onNodeWithText("Save current backup").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Backup not verified:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("campaign-confirm-restore").assertIsNotEnabled()
            assertEquals(listOf("Existing fictional lens"), runBlocking { db.ledgerDao().allItems().map { it.title } })
            assertTrue(runBlocking { db.recordsDao().allReceipts().none { it.kind == "RESTORE" } })
        } finally {
            activity.pause().stop().destroy(); db.close(); sourceDb.close()
            incoming.delete(); destination.delete()
        }
    }
}
