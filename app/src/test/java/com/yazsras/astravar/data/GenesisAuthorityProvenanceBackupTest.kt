package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Altered portable fixtures only; no installed campaign or original proof is modified. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisAuthorityProvenanceBackupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var sourceDb: CampaignLedgerDb
    private lateinit var targetDb: CampaignLedgerDb
    private lateinit var source: GenesisAuthorityBackupRepository
    private lateinit var target: GenesisAuthorityBackupRepository
    private val actorId = "fictional-portable-provenance-actor"
    private val editCommand = "fictional-portable-provenance-edit"

    @Before fun open() {
        sourceDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        source = GenesisAuthorityBackupRepository(sourceDb)
        target = GenesisAuthorityBackupRepository(targetDb)
    }
    @After fun close() { sourceDb.close(); targetDb.close() }

    private suspend fun fixture(): GenesisAuthorityBackup {
        val authority = GenesisAuthorityRepository(sourceDb)
        val campaign = authority.createCampaign("Fictional provenance campaign", commandId = "fictional-provenance-campaign")
        val created = authority.createActor("Fictional provenance keeper", GenesisActorRole.PLAYER, campaign.id,
            playable = ReforgeCharacter(actorId, "Fictional provenance keeper", currentHp = 8, baseMaxHp = 12),
            commandId = "fictional-provenance-activation")
        val initial = requireNotNull(created.playable)
        ReforgeRepository(sourceDb).save(actorId, initial.revision, initial.character.copy(notes = "An explicitly saved later note"),
            "Edit fictional notes", editCommand)
        source.exportWithProof()
        return source.snapshot()
    }

    private suspend fun rejectCoherentAlteration(alter: (ReforgeSavedCharacter) -> ReforgeSavedCharacter) {
        val original = fixture()
        val row = original.playable.characters.single()
        val current = row.saved()
        val changed = alter(current)
        assertEquals(current.character, changed.character)
        assertEquals(current.revision, changed.revision)
        val latest = original.playable.changes.single { it.commandId == editCommand }
        assertEquals("CHARACTER", latest.outcomeType)
        assertEquals(current, reforgeChangeCharacter(latest))
        val portable = original.copy(playable = original.playable.copy(
            characters = listOf(row.copy(originJson = reforgeJson.encodeToString(changed.origin), createdAt = changed.createdAt)),
            changes = original.playable.changes.map { if (it.commandId == editCommand)
                it.copy(afterJson = reforgeJson.encodeToString(changed)) else it }))
        // This is not a broken latest-row/receipt chain. The unchanged activation
        // proof is the independent evidence that rejects the altered provenance.
        validateReforgeBackup(portable.playable)
        assertEquals(original.authority, portable.authority)
        assertEquals(original.campaign, portable.campaign)
        assertEquals(latest.beforeJson, portable.playable.changes.single { it.commandId == editCommand }.beforeJson)
        val beforeTarget = target.snapshot()
        val preview = target.previewRestore(genesisAuthorityBackupJson(portable))
        assertFalse(preview.valid)
        assertTrue(preview.conflicts.joinToString(), preview.conflicts.any {
            it.contains("Connected actor origin differs from its activation decision.")
        })
        assertNotNull(runCatching { target.restore(preview, "rejected-provenance-restore") }.exceptionOrNull())
        assertEquals(beforeTarget, target.snapshot())
        assertEquals(original, source.snapshot())
    }

    @Test fun coherentAlteredOriginCannotReplaceOriginalManagedProvenance(): Unit = runBlocking {
        rejectCoherentAlteration { current ->
            current.copy(origin = current.origin.copy(label = "Fictional altered origin",
                sourceSnapshotJson = "{\"unapproved\":\"fictional replacement evidence\"}"))
        }
    }

    @Test fun coherentAlteredCreationTimeCannotReplaceOriginalManagedProvenance(): Unit = runBlocking {
        rejectCoherentAlteration { current -> current.copy(createdAt = Math.addExact(current.createdAt, 1L)) }
    }

    @Test fun legitimateLaterEditRestoresAndRemainsUsableWithOriginalProvenance(): Unit = runBlocking {
        val original = fixture()
        val expected = original.playable.characters.single().saved()
        val preview = target.previewRestore(genesisAuthorityBackupJson(original))
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        target.restore(preview, "valid-provenance-restore")
        assertEquals(expected, ReforgeRepository(targetDb).get(actorId))
        val actor = requireNotNull(targetDb.genesisAuthorityDao().actor(actorId))
        GenesisAuthorityRepository(targetDb).updateActorAssociation(actor.actorId, actor.revision,
            actor.role, actor.campaignId, actor.ownerActorId, "valid-restored-association")
        assertEquals(expected, ReforgeRepository(targetDb).get(actorId))
        assertEquals(original.playable, target.snapshot().playable)
        target.exportWithProof()
        assertEquals(original, source.snapshot())
    }
}
