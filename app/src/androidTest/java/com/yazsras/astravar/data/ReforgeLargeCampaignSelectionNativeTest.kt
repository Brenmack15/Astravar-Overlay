package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Actual Android SQLite >32m selection acceptance. Isolated fictional in-memory stores only.
 * This exercises the installed native repository, not the owner's device database or UI adoption. */
@RunWith(AndroidJUnit4::class)
class ReforgeLargeCampaignSelectionNativeTest {
    @Test fun selectionAndReviewedAdoptionWorkWhenTheCompleteCampaignExceeds32MillionCharacters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Large fictional fixture may run only on an emulator", "1",
            UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("Native fixture must run in the isolated QA package", "com.yazsras.astravar.v2uidebug", context.packageName)
        val queries = CopyOnWriteArrayList<String>()
        val original = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java)
            .allowMainThreadQueries().setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()
        val gameplay = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        try {
            val legacy = CampaignLedgerRepository(original)
            val reforge = ReforgeRepository(gameplay)
            original.recordsDao().put(CharacterProfile("Zerg Hasma (synthetic QA)", id = "qa-zerg", level = 9,
                maximumHp = 66, currentHp = 39, temporaryHp = 0, revision = 17, source = "Synthetic original profile"))
            original.recordsDao().put(CharacterAbility("CON", "qa-zerg", 14, revision = 12, source = "Synthetic ability"))
            val chunk = "a".repeat(1_000_000)
            repeat(33) { original.archiveDao().insert(CampaignArchiveSource("synthetic-large-$it", chunk)) }
            val before = campaignSerializedHash(LedgerExport.serializer(), CampaignBackup(original).snapshot())
            try { legacy.export(); fail("The original complete JSON export must reproduce the reported size failure") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("32-million-character")) }

            queries.clear()
            val preview = reforge.readLegacyPreview(legacy, "qa-zerg")
            assertEquals("qa-zerg", preview.character.id)
            assertEquals(17L, preview.sourceProfileRevision)
            assertEquals(39, preview.character.currentHp)
            assertTrue("Preview cannot adopt without review", reforge.observeAll().first().isEmpty())
            assertFalse("Selection must not read retained campaign payloads", queries.any {
                it.contains("campaign_archive_sources", true) || it.contains("campaign_import_backups", true) ||
                    it.contains("ledger_events", true) ||
                    (it.contains("campaign_audit", true) && !(it.trimStart().startsWith("SELECT * FROM campaign_links", true) &&
                        it.contains("a.recordId=campaign_links.id") && it.contains("a.resultingRevision=campaign_links.revision")))
            })
            val saved = reforge.adopt(legacy, preview, commandId = "synthetic-reviewed-adoption")
            assertEquals("qa-zerg", saved.character.id)
            assertEquals(preview.sourceJson, saved.origin.sourceSnapshotJson)
            assertEquals(before, campaignSerializedHash(LedgerExport.serializer(), CampaignBackup(original).snapshot()))
            assertEquals(17L, original.recordsDao().characterProfile("qa-zerg")?.revision)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: real Android SQLite reproduced 32m full-export rejection; scoped preview and explicitly reviewed adoption retained source IDs/revisions and the full pre/post campaign snapshot hash, without archive/export queries or standalone audit scans during selection\n") })
        } finally { original.close(); gameplay.close() }
    }
}
