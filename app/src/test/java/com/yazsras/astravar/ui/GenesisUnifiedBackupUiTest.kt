package com.yazsras.astravar.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
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
import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
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

/** Real UI, Room transactions and provider bytes. Only the system picker's URI is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisUnifiedBackupUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private class Picker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        var selected: Uri? = null
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            dispatchResult(requestCode, selected)
        }
    }
    class CorruptingProvider : ContentProvider() {
        lateinit var destination: File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode == "r") destination.writeText("acknowledged but truncated")
            val access = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
            return ParcelFileDescriptor.open(destination, access)
        }
    }
    private fun mount(repository: GenesisAuthorityBackupRepository, picker: Picker, activity: ComponentActivity) {
        activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                AstravarTheme { GenesisUnifiedBackupPage(repository) }
            }
        }
    }
    private fun preview() {
        compose.onNodeWithTag("genesis-choose-backup").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("genesis-confirm-restore").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun protect(picker: Picker, destination: File) {
        picker.selected = Uri.fromFile(destination)
        compose.onNodeWithText("Save current backup").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("genesis-confirm-restore") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private suspend fun connectedFixture(db: CampaignLedgerDb) {
        val authority = GenesisAuthorityRepository(db)
        val campaign = authority.createCampaign("Fictional voyage", commandId = "fictional-incoming-campaign")
        val hero = authority.createActor("Fictional traveller", GenesisActorRole.PLAYER, campaign.id,
            playable = ReforgeCharacter("fictional-connected-hero", "Fictional traveller", currentHp = 7, baseMaxHp = 14),
            commandId = "fictional-incoming-hero")
        authority.createActor("Fictional owl", GenesisActorRole.COMPANION, campaign.id, hero.actor.actorId,
            commandId = "fictional-incoming-companion")
        authority.createActor("Fictional guide", GenesisActorRole.NPC, campaign.id,
            commandId = "fictional-incoming-npc")
        authority.createItem(hero.actor.actorId, requireNotNull(hero.playable).revision,
            ItemDefinition("fictional-lantern-definition", "Fictional lantern", description = "Preserve éclair, 雪 and 🦉"),
            ItemInstance("fictional-lantern", "fictional-lantern-definition", quantity = 2), "fictional-incoming-item")
    }

    @Test fun connectedMergeRequiresVerifiedBytesRetainsRelationshipsAcrossReopenAndReusesRequest() {
        val name = "fictional-genesis-ui-recovery.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).build()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val incoming = File(context.cacheDir, "fictional-genesis-ui-incoming.json")
        val current = File(context.cacheDir, "fictional-genesis-ui-current.json")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val picker = Picker()
        try {
            val expected = runBlocking {
                GenesisAuthorityRepository(db).createCampaign("Existing fictional campaign", commandId = "fictional-existing-campaign")
                connectedFixture(sourceDb)
                val source = GenesisAuthorityBackupRepository(sourceDb)
                incoming.writeText(source.exportWithProof().text)
                source.snapshot()
            }
            mount(GenesisAuthorityBackupRepository(db), picker, activity.get())
            picker.selected = Uri.fromFile(incoming)
            preview()
            compose.onNodeWithTag("genesis-confirm-restore").assertIsNotEnabled()
            assertTrue(runBlocking { db.reforgeDao().allCharacters().isEmpty() })
            protect(picker, current)
            val protected = decodeGenesisAuthorityBackup(current.readText())
            assertEquals(listOf("Existing fictional campaign"), protected.authority.campaigns.map { it.name })
            assertTrue(protected.playable.characters.isEmpty())
            compose.onNodeWithTag("genesis-confirm-restore").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Connected backup records merged and verified on this device.").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(expected.playable.characters, runBlocking { db.reforgeDao().allCharacters() })
            assertEquals(expected.authority.actors, runBlocking { db.genesisAuthorityDao().allActors() })
            assertEquals(expected.authority.items, runBlocking { db.genesisAuthorityDao().allItems() })
            assertEquals(expected.campaign.records.placements, runBlocking { db.partyDao().allPlacements() })
            // Reopening the same selected file reuses its request instead of adding a second receipt.
            picker.selected = Uri.fromFile(incoming)
            preview(); protect(picker, current)
            compose.onNodeWithTag("genesis-confirm-restore").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("genesis-confirm-restore").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, runBlocking { db.recordsDao().allReceipts().count { it.kind == "RESTORE" } })
            activity.pause().stop().destroy()
            db.close()
            db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).build()
            val restored = runBlocking { decodeGenesisAuthorityBackup(GenesisAuthorityBackupRepository(db).exportWithProof().text) }
            assertEquals(expected.playable.characters, restored.playable.characters)
            assertEquals(expected.authority.actors, restored.authority.actors)
            assertEquals(expected.authority.items, restored.authority.items)
            assertEquals(expected.campaign.records.companionStates, restored.campaign.records.companionStates)
            assertEquals(expected.campaign.records.entities, restored.campaign.records.entities)
            assertEquals(expected.campaign.records.placements, restored.campaign.records.placements)
            assertEquals(2, restored.authority.campaigns.size)
        } finally {
            if (!activity.get().isDestroyed) activity.pause().stop().destroy()
            db.close(); sourceDb.close(); context.deleteDatabase(name)
            incoming.delete(); current.delete()
        }
    }

    @Test fun successfulWriteWithCorruptedReadbackNeverEnablesMerge() {
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val incoming = File(context.cacheDir, "fictional-genesis-ui-corrupt-incoming.json")
        val destination = File(context.cacheDir, "fictional-genesis-ui-corrupt-current.json")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val picker = Picker()
        val provider = CorruptingProvider().apply {
            this.destination = destination
            attachInfo(context, ProviderInfo().apply { authority = "fictional-genesis-corrupt-backup" })
        }
        ShadowContentResolver.registerProviderInternal("fictional-genesis-corrupt-backup", provider)
        try {
            runBlocking {
                connectedFixture(sourceDb)
                incoming.writeText(GenesisAuthorityBackupRepository(sourceDb).exportWithProof().text)
            }
            mount(GenesisAuthorityBackupRepository(db), picker, activity.get())
            picker.selected = Uri.fromFile(incoming); preview()
            picker.selected = Uri.parse("content://fictional-genesis-corrupt-backup/current.json")
            compose.onNodeWithText("Save current backup").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Backup not verified:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("genesis-confirm-restore").assertIsNotEnabled()
            assertTrue(runBlocking { db.genesisAuthorityDao().allActors().isEmpty() })
            assertTrue(runBlocking { db.recordsDao().allReceipts().isEmpty() })
        } finally {
            activity.pause().stop().destroy(); db.close(); sourceDb.close(); incoming.delete(); destination.delete()
        }
    }

    @Test fun laterSavedChangeRejectsFrozenMergeAndRequiresNewProtection() {
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val incoming = File(context.cacheDir, "fictional-genesis-ui-stale-incoming.json")
        val current = File(context.cacheDir, "fictional-genesis-ui-stale-current.json")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val picker = Picker()
        try {
            runBlocking {
                connectedFixture(sourceDb)
                incoming.writeText(GenesisAuthorityBackupRepository(sourceDb).exportWithProof().text)
            }
            mount(GenesisAuthorityBackupRepository(db), picker, activity.get())
            picker.selected = Uri.fromFile(incoming); preview(); protect(picker, current)
            runBlocking { GenesisAuthorityRepository(db).createCampaign("Later fictional campaign", commandId = "fictional-later-campaign") }
            compose.onNodeWithTag("genesis-confirm-restore").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Merge could not be verified:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("genesis-confirm-restore").assertIsNotEnabled()
            assertTrue(runBlocking { db.genesisAuthorityDao().allActors().isEmpty() })
            assertEquals(listOf("Later fictional campaign"), runBlocking { db.genesisAuthorityDao().allCampaigns().map { it.name } })
            assertTrue(runBlocking { db.recordsDao().allReceipts().none { it.kind == "RESTORE" } })
            protect(picker, current)
            compose.onNodeWithTag("genesis-confirm-restore").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("genesis-confirm-restore").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, runBlocking { db.recordsDao().allReceipts().count { it.kind == "RESTORE" } })
        } finally {
            activity.pause().stop().destroy(); db.close(); sourceDb.close(); incoming.delete(); current.delete()
        }
    }
}
