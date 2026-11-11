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

/** Fictional source imports remain useful without bypassing connected gameplay authority. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisArchiveBoundaryTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var campaign: CampaignLedgerRepository
    private lateinit var authority: GenesisAuthorityRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CampaignLedgerDb::class.java)
            .allowMainThreadQueries().build()
        campaign = CampaignLedgerRepository(db)
        authority = GenesisAuthorityRepository(db)
    }
    @After fun close() = db.close()
    private suspend fun actor() = authority.createActor("Fictional connected actor", GenesisActorRole.PLAYER,
        playable = ReforgeCharacter(id = "fictional-connected-actor", name = "Fictional connected actor",
            baseMaxHp = 20, currentHp = 14, baseAc = 13), commandId = "fictional-create-actor")
    private fun archive(mapped: LedgerExport? = null): String {
        val body = "Fictional unapproved source. No gameplay effect."
        val record = CampaignArchiveRecord("fictional-source", "Fictional reference", "Chronicle", body, "{}",
            "https://example.invalid/fictional-source", "fictional-revision", campaignHash(body), emptyList(), "player")
        val payload = CampaignArchivePayload("fictional-import", "Fictional source archive", 1,
            "https://example.invalid/root", "fictional-root", listOf(record), mapped)
        val text = campaignJson.encodeToString(payload)
        return campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive", 1, campaignHash(text), text))
    }

    @Test fun disjointTypedImportAndRollbackRetainConnectedGameplayAndLaterCampaignIdentity() = runBlocking {
        val created = actor()
        val beforeActor = db.reforgeDao().character(created.actor.actorId)
        val beforeSource = db.recordsDao().characterProfile(created.actor.actorId)
        val beforeAuthority = genesisAuthorityBackupRows(db)
        val mapped = LedgerExport(exportedAt = 1, items = emptyList(), events = emptyList(), records = CampaignRecordsExport(
            characterProfiles = listOf(CharacterProfile("Fictional unreviewed NPC", id = "fictional-imported-person", source = "Fictional source"))))
        val preview = campaign.archive.preview(archive(mapped))
        assertTrue(preview.conflicts.toString(), preview.valid)
        campaign.archive.exportPreImportBackup(preview.fingerprint)
        campaign.archive.commit(preview, true)
        assertEquals(beforeActor, db.reforgeDao().character(created.actor.actorId))
        assertEquals(beforeSource, db.recordsDao().characterProfile(created.actor.actorId))
        assertEquals(beforeAuthority, genesisAuthorityBackupRows(db))
        assertNotNull(db.recordsDao().characterProfile("fictional-imported-person"))
        assertNull(db.genesisAuthorityDao().actor("fictional-imported-person"))
        // These records are outside the historical source snapshot and must survive rollback.
        val laterCampaign = authority.createCampaign("Fictional later campaign", commandId = "fictional-later-campaign")
        db.reforgeDao().insert(ReforgeNoteDraft(created.actor.actorId, created.playable!!.revision, "Fictional retained recovery draft", 42))
        campaign.archive.rollback(preview.fingerprint, true)
        assertNull(db.recordsDao().characterProfile("fictional-imported-person"))
        assertEquals(beforeActor, db.reforgeDao().character(created.actor.actorId))
        assertEquals(beforeSource, db.recordsDao().characterProfile(created.actor.actorId))
        assertEquals(laterCampaign, db.genesisAuthorityDao().campaign(laterCampaign.id))
        assertEquals("Fictional retained recovery draft", db.reforgeDao().draft(created.actor.actorId)?.text)
        assertTrue(authority.backup.exportWithProof().text.contains("Fictional later campaign"))
    }

    @Test fun partialPreviewAndStalePartialRestoreCannotBypassNewAuthority() = runBlocking {
        val legacy = campaignJson.encodeToString(LedgerExport(exportedAt = 1, items = emptyList(), events = emptyList()))
        val oldPreview = CampaignBackup(db).preview(legacy)
        assertTrue(oldPreview.valid)
        actor()
        val before = authority.backup.exportWithProof()
        val blocked = CampaignBackup(db).preview(legacy)
        assertFalse(blocked.valid)
        assertTrue(blocked.conflicts.any { it.contains("Complete") })
        try { CampaignBackup(db).restore(oldPreview, "fictional-partial-restore"); fail("Partial restore must not sever authority") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("Complete")) }
        assertEquals(before.currentFingerprint, authority.backup.exportWithProof().currentFingerprint)
    }

    @Test fun connectedProjectionCannotBeSmuggledThroughAnArchiveAsAnUnmanagedProfile() = runBlocking {
        val mapped = LedgerExport(exportedAt = 1, items = emptyList(), events = emptyList(), records = CampaignRecordsExport(
            characterProfiles = listOf(CharacterProfile("Fictional stripped authority", id = "fictional-stripped", source = GENESIS_AUTHORITY_SOURCE))))
        val preview = campaign.archive.preview(archive(mapped))
        assertFalse(preview.valid)
        assertTrue(preview.conflicts.any { it.contains("Complete") })
        assertNull(db.recordsDao().characterProfile("fictional-stripped"))
    }

    @Test fun rollbackCannotRemoveAnIdentityEnrolledAfterItsImport() = runBlocking {
        val mapped = LedgerExport(exportedAt = 1, items = emptyList(), events = emptyList(), records = CampaignRecordsExport(
            characterProfiles = listOf(CharacterProfile("Fictional newly reviewed person", id = "fictional-new-person", source = "Fictional source"))))
        val preview = campaign.archive.preview(archive(mapped))
        assertTrue(preview.valid)
        campaign.archive.exportPreImportBackup(preview.fingerprint)
        campaign.archive.commit(preview, true)
        val profile = requireNotNull(db.recordsDao().characterProfile("fictional-new-person"))
        authority.registerActorAssociation(profile.id, profile.revision, GenesisActorRole.NPC, null, null, "fictional-new-person-review")
        val before = authority.backup.exportWithProof()
        try { campaign.archive.rollback(preview.fingerprint, true); fail("Rollback must preserve later reviewed identity") }
        catch (_: IllegalStateException) { }
        assertEquals(before.currentFingerprint, authority.backup.exportWithProof().currentFingerprint)
        assertNotNull(db.genesisAuthorityDao().actor(profile.id))
    }
}
