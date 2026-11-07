package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignCharacterSnapshotTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var gameplay: ReforgeDb
    private lateinit var legacy: CampaignLedgerRepository
    private lateinit var reforge: ReforgeRepository
    private val transactions = CopyOnWriteArrayList<Boolean>()

    @Before fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> if (sql.startsWith("SELECT", true)) transactions += db.inTransaction() }, Executor { it.run() }).build()
        gameplay = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        legacy = CampaignLedgerRepository(db); reforge = ReforgeRepository(gameplay)
        db.recordsDao().put(CharacterProfile("Synthetic character", id = "selected", revision = 7, level = 9,
            source = "Original exact profile provenance"))
        db.recordsDao().put(CharacterProfile("Other character", id = "other"))
        db.recordsDao().put(CharacterAbility("CON", "selected", 14, revision = 3, source = "Exact ability provenance"))
        db.recordsDao().put(CharacterAbility("STR", "other", 21))
        db.partyDao().put(PartySheetRecord("selected", campaignJson.encodeToString(PartySheetData(
            unresolvedConflicts = listOf("Keep this unresolved"))), revision = 9, source = "Exact sheet provenance"))
        db.partyDao().put(PartySheetRecord("other", campaignJson.encodeToString(PartySheetData())))
        listOf("selected-item", "other-item").forEach { id ->
            db.ledgerDao().insertItem(LedgerItem(id, id, quantity = 1, createdAt = 11, updatedAt = 22,
                revision = 6, source = "Exact item provenance"))
            db.partyDao().put(ItemPlacement(id, if (id == "selected-item") "selected" else "other", revision = 4,
                source = "Exact placement provenance"))
            db.recordsDao().put(ItemDetails(id, notes = "Exact definition", revision = 5))
            db.recordsDao().put(ItemProperty(id, "Exact property", "Unparsed original text", id = "$id-property", revision = 8))
        }
        db.recordsDao().put(CampaignLink("character-profile", "selected", "item", "other-item", "Incoming/outgoing identity",
            id = "selected-link", revision = 11, source = "Exact relationship provenance"))
        db.recordsDao().put(CampaignLink("item", "other-item", "character-profile", "other", "Unrelated", id = "other-link"))
        db.partyDao().put(ExternalRecordReference("selected-ref", "notion", "same-external-id", "character-profile",
            "selected", revision = 14, source = "Exact external provenance", campaignId = "selected-campaign"))
        db.partyDao().put(ExternalRecordReference("item-ref", "notion", "item-external-id", "item", "selected-item",
            campaignId = "selected-campaign"))
        listOf("selected-reference", "cross-campaign-reference").forEach { id ->
            db.archiveDao().insert(CampaignReferenceRecord(id,
                if (id == "selected-reference") "selected-campaign" else "other-campaign", "same-external-id", id,
                "Character", "Exact narrative", "{}", "https://example.invalid/source", "original-revision", "original-hash", "original-package"))
            db.archiveDao().insert(CampaignReferenceRelation("$id-edge", id, "unresolved-target", resolved = false))
        }
    }
    @After fun close() { db.close(); gameplay.close() }

    @Test fun exactScopedRowsAndIncidentRelationshipsAreReadInOneTransaction() = runBlocking {
        transactions.clear()
        val value = legacy.readCharacterSnapshot("selected")
        assertEquals(db.recordsDao().characterProfile("selected"), value.profile)
        assertEquals(listOf("CON"), value.abilities.map { it.key })
        assertEquals(db.partyDao().sheet("selected"), value.sheet)
        assertEquals(listOf("selected-item"), value.items.map { it.id })
        assertEquals(listOf("selected-item"), value.placements.map { it.itemId })
        assertEquals(listOf("selected-item"), value.itemDetails.map { it.itemId })
        assertEquals(listOf("selected-item-property"), value.itemProperties.map { it.id })
        assertEquals(listOf("selected-link"), value.links.map { it.id })
        assertEquals(listOf("item-ref", "selected-ref"), value.externalReferences.map { it.id })
        assertEquals(listOf("selected-reference"), value.referenceMetadata.map { it.id })
        assertEquals(listOf("selected-reference-edge"), value.referenceRelations.map { it.id })
        assertFalse(value.referenceRelations.single().resolved)
        // Only the snapshot's eleven SELECTs count; independent expected-value reads above are outside it.
        assertEquals(11, transactions.take(11).size)
        assertTrue("All component reads must share the Room transaction", transactions.take(11).all { it })
        assertEquals(legacy.readCharacterSnapshot("selected"), value)
    }

    @Test fun changingAnyReviewedSourceCategoryRejectsAdoptionWithoutPartialGameplayState() = runBlocking {
        val mutations: List<suspend () -> Unit> = listOf(
            { db.recordsDao().put(db.recordsDao().characterProfile("selected")!!.copy(revision = 8)) },
            { db.recordsDao().put(db.recordsDao().characterAbility("selected", "CON")!!.copy(revision = 4)) },
            { db.partyDao().put(db.partyDao().sheet("selected")!!.copy(revision = 10)) },
            { db.ledgerDao().updateItem(db.ledgerDao().getItem("selected-item")!!.copy(revision = 7)) },
            { db.partyDao().put(db.partyDao().placement("selected-item")!!.copy(revision = 5)) },
            { db.recordsDao().put(db.recordsDao().details("selected-item")!!.copy(revision = 6)) },
            { db.recordsDao().put(db.recordsDao().property("selected-item-property")!!.copy(revision = 9)) },
            { db.recordsDao().put(db.recordsDao().link("selected-link")!!.copy(revision = 12)) },
            { db.partyDao().put(db.partyDao().reference("selected-ref")!!.copy(revision = 15)) },
            { db.archiveDao().insert(CampaignReferenceRelation("new-reviewed-edge", "selected-reference", "other-target")) },
            { db.openHelper.writableDatabase.execSQL("UPDATE campaign_references SET sourceRevision='new-revision' WHERE id='selected-reference'") },
            { db.partyDao().put(ItemPlacement("other-item", "selected", revision = 2)) }
        )
        mutations.forEachIndexed { index, mutate ->
            val preview = reforge.readLegacyPreview(legacy, "selected")
            mutate()
            val afterCorrection = campaignSerializedHash(LedgerExport.serializer(), CampaignBackup(db).snapshot())
            try { reforge.adopt(legacy, preview, commandId = "stale-$index"); fail("Changed source category $index accepted") }
            catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("changed after review")) }
            assertTrue(reforge.observeAll().first().isEmpty())
            assertEquals(afterCorrection, campaignSerializedHash(LedgerExport.serializer(), CampaignBackup(db).snapshot()))
        }
    }

    @Test fun unrelatedChangesDoNotInvalidateReviewAndMissingProfileCannotBeAdopted() = runBlocking {
        val preview = reforge.readLegacyPreview(legacy, "selected")
        db.recordsDao().put(db.recordsDao().characterProfile("other")!!.copy(revision = 2))
        db.archiveDao().insert(CampaignArchiveSource("unrelated-source", "Unrelated retained payload"))
        assertEquals(preview.legacyFingerprint, reforge.readLegacyPreview(legacy, "selected").legacyFingerprint)
        assertEquals(preview.sourceJson, reforge.adopt(legacy, preview).origin.sourceSnapshotJson)
        try { reforge.readLegacyPreview(legacy, "missing"); fail("Missing profile accepted") }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("no longer available")) }
    }

    @Test fun moreThanSqliteParameterLimitOfAssignedItemsNeedsNoTruncationOrDynamicInList() = runBlocking {
        repeat(1100) { number ->
            val id = "many-$number"
            db.ledgerDao().insertItem(LedgerItem(id, id, quantity = null, createdAt = 1, updatedAt = 1))
            db.partyDao().put(ItemPlacement(id, "selected"))
        }
        val preview = reforge.readLegacyPreview(legacy, "selected")
        val source = reforgeJson.decodeFromString<ReforgeLegacySnapshot>(preview.sourceJson)
        assertEquals(1101, source.items.size)
        assertEquals(1101, source.placements.size)
        assertEquals(1100, source.items.count { it.quantity == null })
        assertTrue(preview.warnings.any { it.contains("unknown quantities") })
    }
}
