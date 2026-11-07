package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Synthetic fixtures only. Zerg is a fixture label, never the owner's actual campaign. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeLargeCampaignSelectionTest {
    @Test fun selectionAndReviewedAdoptionWorkWhenTheCompleteCampaignExceeds32MillionCharacters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
                    it.contains("ledger_events", true) || it.contains("campaign_audit", true)
            })
            val saved = reforge.adopt(legacy, preview, commandId = "synthetic-reviewed-adoption")
            assertEquals("qa-zerg", saved.character.id)
            assertEquals(preview.sourceJson, saved.origin.sourceSnapshotJson)
            assertEquals(before, campaignSerializedHash(LedgerExport.serializer(), CampaignBackup(original).snapshot()))
        } finally { original.close(); gameplay.close() }
    }
}
