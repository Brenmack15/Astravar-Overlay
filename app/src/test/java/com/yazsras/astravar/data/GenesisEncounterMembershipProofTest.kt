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

/** Corrupt only isolated fictional proof, never an installed campaign. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisEncounterMembershipProofTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CampaignLedgerDb
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var encounters: GenesisEncounterRepository
    private val encounterId = "fictional-membership-proof-encounter"

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        authority = GenesisAuthorityRepository(db); encounters = authority.encounters
    }
    @After fun close() { db.close() }

    private data class Fixture(val member: GenesisActorAuthority, val later: GenesisActorAuthority,
        val review: GenesisEncounterStartReview, val started: GenesisEncounterSnapshot)

    private suspend fun fixture(kind: String): Fixture {
        val world = authority.createCampaign("Fictional recorded campaign", commandId = "proof-world")
        val other = authority.createCampaign("Fictional later campaign", commandId = "proof-other-world")
        val actor = when (kind) {
            "register-actor" -> {
                val profile = CampaignLedgerRepository(db).records.saveProfile(
                    CharacterProfile(id = "fictional-registered-witness", name = "Fictional witness"),
                    source = "Fictional source-only proof", commandId = "proof-source-profile")
                authority.registerActorAssociation(profile.id, profile.revision, GenesisActorRole.NPC,
                    world.id, null, "proof-membership").actor
            }
            "accept-actor" -> {
                val playable = ReforgeRepository(db).create(
                    ReforgeCharacter("fictional-reviewed-witness", "Fictional witness", currentHp = 7, baseMaxHp = 12),
                    "proof-playable-create")
                authority.acceptActorAuthority(authority.reviewActorAuthority(playable.character.id,
                    GenesisActorRole.NPC, world.id), "proof-membership").actor
            }
            else -> {
                val created = authority.createActor("Fictional witness", GenesisActorRole.NPC, world.id,
                    commandId = if (kind == "update-association") "proof-original-creation" else "proof-membership").actor
                if (kind == "update-association") authority.updateActorAssociation(created.actorId, created.revision,
                    created.role, world.id, null, "proof-membership") else created
            }
        }
        val draft = encounters.saveDraft(encounterId, null, world.id, "Fictional tracker", "",
            listOf(GenesisEncounterParticipantInput(actor.actorId, actor.revision)), "proof-encounter-create")
        val review = encounters.reviewStart(encounterId, draft.record.revision)
        val started = encounters.start(review, "proof-encounter-start")
        // The original receipt is historical now; valid later membership must not
        // erase it or be mistaken for the association accepted at encounter start.
        val later = authority.updateActorAssociation(actor.actorId, actor.revision, actor.role,
            other.id, null, "proof-later-membership")
        return Fixture(actor, later, review, started)
    }

    private suspend fun rejectWithoutWrites(block: suspend () -> Unit) {
        val before = authority.backup.snapshot()
        assertNotNull(runCatching { block() }.exceptionOrNull())
        assertEquals(before, authority.backup.snapshot())
    }

    private suspend fun rejectCorruptedHistory(kind: String) {
        val fixture = fixture(kind)
        val receipt = requireNotNull(readGenesisAuthorityReceipt(db, fixture.member.lastCommandId))
        assertEquals(kind, receipt.kind)
        val changedRequest = if (kind == "accept-actor") {
            val review = campaignJson.decodeFromString<GenesisActorAuthorityReview>(receipt.requestJson)
            val changed = review.copy(campaignId = fixture.later.campaignId)
            campaignJson.encodeToString(changed.copy(fingerprint = genesisActorAuthorityReviewFingerprint(changed)))
        } else {
            val input = campaignJson.decodeFromString<List<String?>>(receipt.requestJson).toMutableList()
            when (kind) {
                "create-actor" -> input[0] = "Different unapproved fictional name"
                "register-actor" -> input[1] = Math.addExact(requireNotNull(input[1]).toLong(), 1L).toString()
                else -> input[3] = fixture.later.campaignId
            }
            campaignJson.encodeToString(input.toList())
        }
        val changedFingerprint = genesisAuthorityFingerprint(kind, changedRequest)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE genesis_authority_receipts SET requestJson=?,fingerprint=? WHERE commandId=?",
            arrayOf(changedRequest, changedFingerprint, receipt.commandId))
        val corrupted = requireNotNull(readGenesisAuthorityReceipt(db, receipt.commandId))
        assertEquals(receipt.outcomeJson, corrupted.outcomeJson)
        assertEquals(genesisAuthorityFingerprint(kind, corrupted.requestJson), corrupted.fingerprint)
        rejectWithoutWrites { encounters.get(encounterId) }
        rejectWithoutWrites { encounters.nextTurn(encounterId, fixture.started.record.revision, "blocked-next") }
        rejectWithoutWrites { encounters.end(encounterId, fixture.started.record.revision, "blocked-end") }
        rejectWithoutWrites { encounters.start(fixture.review, "proof-encounter-start") }
        assertNull(readGenesisAuthorityReceipt(db, "blocked-next"))
        assertNull(readGenesisAuthorityReceipt(db, "blocked-end"))
    }

    @Test fun rehashedCreationRequestCannotExtendHistoricalEncounter(): Unit = runBlocking {
        rejectCorruptedHistory("create-actor")
    }
    @Test fun rehashedRegistrationRequestCannotExtendHistoricalEncounter(): Unit = runBlocking {
        rejectCorruptedHistory("register-actor")
    }
    @Test fun rehashedAcceptanceRequestCannotExtendHistoricalEncounter(): Unit = runBlocking {
        rejectCorruptedHistory("accept-actor")
    }
    @Test fun rehashedAssociationRequestCannotExtendHistoricalEncounter(): Unit = runBlocking {
        rejectCorruptedHistory("update-association")
    }

    @Test fun laterMembershipRetainsOriginalStartRetryAncestryAndPortableHistory(): Unit = runBlocking {
        val fixture = fixture("update-association")
        val before = authority.backup.snapshot()
        assertEquals(fixture.started, encounters.get(encounterId))
        assertEquals(fixture.started, encounters.start(fixture.review, "proof-encounter-start"))
        assertEquals(before, authority.backup.snapshot())
        val next = encounters.nextTurn(encounterId, fixture.started.record.revision, "valid-next")
        assertEquals(2, next.record.round)
        val ended = encounters.end(encounterId, next.record.revision, "valid-end")
        val after = authority.backup.snapshot()
        assertEquals(before.campaign, after.campaign)
        assertEquals(before.playable, after.playable)
        assertEquals(before.authority.actors, after.authority.actors)
        val document = authority.backup.exportWithProof().text
        val restoredDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val restored = GenesisAuthorityRepository(restoredDb)
            val preview = restored.backup.previewRestore(document)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            restored.backup.restore(preview, "valid-historical-membership-restore")
            assertEquals(ended, restored.encounters.get(encounterId))
            assertEquals(fixture.later, restoredDb.genesisAuthorityDao().actor(fixture.member.actorId))
            assertEquals(fixture.started, restored.encounters.start(fixture.review, "proof-encounter-start"))
            assertEquals(ended, restored.encounters.get(encounterId))
        } finally { restoredDb.close() }
    }
}
