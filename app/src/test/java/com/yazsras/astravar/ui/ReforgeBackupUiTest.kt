package com.yazsras.astravar.ui

import android.net.Uri
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
import java.io.File

/** Real Compose controls and local document bytes; picker selection alone is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReforgeBackupUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private class Picker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        var selected: Uri? = null
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            dispatchResult(requestCode, selected)
        }
    }
    @Test fun mergeRequiresIndependentlyVerifiedCurrentBackupAndPersistsFullImportedState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val repo = ReforgeRepository(db)
        val source = ReforgeRepository(sourceDb)
        val incomingFile = File(context.cacheDir, "fictional-reforge-incoming.json")
        val protectedFile = File(context.cacheDir, "fictional-reforge-current.json")
        val picker = Picker()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val imported = runBlocking {
                repo.create(ReforgeCharacter("fictional-existing", "Existing fictional character", notes = "Existing notes"))
                val incoming = source.create(ReforgeCharacter("fictional-imported", "Imported fictional companion", notes = "Complete imported notes"))
                source.saveNoteDraft(incoming.character.id, incoming.revision, "Recoverable imported writing")
                incomingFile.writeText(source.export(), Charsets.UTF_8)
                incoming
            }
            picker.selected = Uri.fromFile(incomingFile)
            activity.get().setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    AstravarTheme { ReforgeBackupPage(repo) }
                }
            }
            compose.onNodeWithTag("reforge-choose-backup").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reforge-confirm-restore").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reforge-confirm-restore").assertIsNotEnabled()
            assertEquals(1, runBlocking { repo.observeAll().first().size })
            picker.selected = Uri.fromFile(protectedFile)
            compose.onNodeWithText("Save current backup").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Current data has a verified backup.").fetchSemanticsNodes().isNotEmpty() }
            val protectedBackup = decodeReforgeBackup(protectedFile.readText(Charsets.UTF_8))
            assertEquals(listOf("fictional-existing"), protectedBackup.characters.map { it.profileId })
            // Byte verification can be visible while the refreshed merge preview is still busy.
            // Wait for the actual guarded action, retaining all independent persistence assertions.
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("reforge-confirm-restore") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reforge-confirm-restore").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { repo.get(imported.character.id) != null } }
            assertEquals(imported, runBlocking { repo.get(imported.character.id) })
            assertEquals("Recoverable imported writing", runBlocking { repo.readNoteDraft(imported.character.id) }!!.text)
            assertEquals("Existing notes", runBlocking { repo.get("fictional-existing") }!!.character.notes)
        } finally {
            activity.pause().stop().destroy(); db.close(); sourceDb.close()
            incomingFile.delete(); protectedFile.delete()
        }
    }
}
