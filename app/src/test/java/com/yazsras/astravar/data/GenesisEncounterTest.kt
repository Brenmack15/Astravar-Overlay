package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@Serializable private data class PreEncounterAuthorityExport(
    val campaigns: List<GenesisCampaignRecord> = emptyList(), val actors: List<GenesisActorAuthority> = emptyList(),
    val items: List<GenesisItemAuthority> = emptyList(), val receipts: List<GenesisAuthorityReceipt> = emptyList())
@Serializable private data class PreEncounterBackup(val format: String = "astravar-connected-campaign", val schema: Int = 1,
    val exportedAt: Long, val campaign: LedgerExport, val playable: ReforgeBackup, val authority: PreEncounterAuthorityExport)

/** Fictional tracking only; saved mechanics and original campaign truth must not change. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisEncounterTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val filename = "fictional-genesis-encounter-lifecycle.db"
    private lateinit var db: CampaignLedgerDb
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var encounters: GenesisEncounterRepository
    private lateinit var world: GenesisCampaignRecord
    private lateinit var otherWorld: GenesisCampaignRecord
    private lateinit var hero: GenesisActorCreationOutcome
    private lateinit var companion: GenesisActorCreationOutcome
    private lateinit var npc: GenesisActorCreationOutcome
    private val id = "fictional-encounter"
    @Before fun open() { context.deleteDatabase(filename); reopen() }
    private fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, filename).allowMainThreadQueries().build()
        authority = GenesisAuthorityRepository(db); encounters = authority.encounters
    }
    @After fun close() { db.close(); context.deleteDatabase(filename) }
    private suspend fun fixture() {
        world = authority.createCampaign("Fictional encounter campaign", commandId = "encounter-world-create")
        otherWorld = authority.createCampaign("Fictional other campaign", commandId = "other-encounter-world-create")
        val character = ReforgeCharacter("fictional-encounter-hero", "Fictional hero", level = 3,
            baseAbilities = Stat.entries.associateWith { 10 }, baseMaxHp = 20, baselineConstitutionModifier = 0,
            currentHp = 11, hpDamage = 9, temporaryHp = 2, conditions = setOf("Poisoned"),
            resources = listOf(ResourcePool("fictional-charge", "Recorded charge", maximum = 4, current = 1)),
            slotMaximaOverride = mapOf(1 to 3), spellSlots = mapOf(1 to 1), gameSeconds = 777, turnNumber = 12)
        hero = authority.createActor(character.name, GenesisActorRole.PLAYER, world.id, playable = character, commandId = "encounter-hero-create")
        companion = authority.createActor("Fictional owl", GenesisActorRole.COMPANION, world.id, hero.actor.actorId,
            playable = ReforgeCharacter("fictional-encounter-owl", "Fictional owl", currentHp = null, baseMaxHp = null,
                resources = listOf(ResourcePool("fictional-owl-power", "Unknown power", maximum = null, current = null))), commandId = "encounter-companion-create")
        npc = authority.createActor("Fictional source-only contact", GenesisActorRole.NPC, world.id, commandId = "encounter-npc-create")
    }
    private fun inputs() = listOf(
        GenesisEncounterParticipantInput(npc.actor.actorId, npc.actor.revision, null),
        GenesisEncounterParticipantInput(hero.actor.actorId, hero.actor.revision, 15),
        GenesisEncounterParticipantInput(companion.actor.actorId, companion.actor.revision, 15))
    private suspend fun draft(command: String = "encounter-create") = encounters.saveDraft(id, null, world.id,
        "Fictional recorded encounter", "Only a tracker; no turn or time is inferred", inputs(), command)
    private suspend fun rejectUnchanged(block: suspend () -> Unit): Throwable {
        val before = authority.backup.snapshot()
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("Rejected tracker command must throw", failure)
        assertEquals("Rejected tracker command must preserve every saved domain", before, authority.backup.snapshot())
        return failure!!
    }
    private fun unchangedMechanics(before: GenesisAuthorityBackup, after: GenesisAuthorityBackup) {
        assertEquals("Source quantities, profiles, resources and histories are untouched", before.campaign, after.campaign)
        assertEquals("Playable HP, slots, effects, turn, time and histories are untouched", before.playable, after.playable)
        assertEquals(before.authority.actors, after.authority.actors)
        assertEquals(before.authority.items, after.authority.items)
        assertEquals(before.authority.campaigns, after.authority.campaigns)
    }

    @Test fun explicitOrderUnknownInitiativeLifecycleAndRestartNeverChangeActorMechanics() = runBlocking {
        fixture()
        val original = authority.backup.snapshot()
        val created = draft()
        assertEquals(inputs().map { it.actorId }, created.participants.map { it.actorId })
        assertEquals(listOf(null, 15, 15), created.participants.map { it.initiative })
        val beforeReview = authority.backup.snapshot()
        val review = encounters.reviewStart(id, created.record.revision)
        assertEquals(beforeReview, authority.backup.snapshot())
        val started = encounters.start(review, "encounter-start")
        assertEquals(npc.actor.actorId, started.record.currentActorId); assertEquals(1, started.record.round)
        assertNull(ReforgeRepository(db).get(npc.actor.actorId))
        var current = encounters.nextTurn(id, started.record.revision, "encounter-next-hero")
        assertEquals(hero.actor.actorId, current.record.currentActorId)
        current = encounters.nextTurn(id, current.record.revision, "encounter-next-owl")
        assertEquals(companion.actor.actorId, current.record.currentActorId)
        current = encounters.nextTurn(id, current.record.revision, "encounter-next-round")
        assertEquals(npc.actor.actorId, current.record.currentActorId); assertEquals(2, current.record.round)
        val ended = encounters.end(id, current.record.revision, "encounter-end")
        assertEquals(GenesisEncounterStatus.ENDED, ended.record.status)
        assertEquals(current.record.round, ended.record.round); assertEquals(current.record.currentActorId, ended.record.currentActorId)
        val complete = authority.backup.snapshot()
        unchangedMechanics(original, complete)
        reopen()
        assertEquals(complete, authority.backup.snapshot())
        assertEquals(ended, encounters.get(id))
        assertEquals(listOf(ended), encounters.observe(world.id).first())
        assertTrue(encounters.observe(otherWorld.id).first().isEmpty())
        val setup = encounters.reopenSetup(id, ended.record.revision, "encounter-reopen-setup")
        assertEquals(GenesisEncounterStatus.DRAFT, setup.record.status); assertEquals(0, setup.record.round); assertNull(setup.record.currentActorId)
        assertEquals(ended.participants, setup.participants)
        val reordered = encounters.saveDraft(id, setup.record.revision, world.id, "Reviewed second setup", "Preserve previous history",
            inputs().reversed(), "encounter-second-setup")
        assertEquals(inputs().reversed().map { it.actorId }, reordered.participants.map { it.actorId })
        assertEquals(ended, campaignJson.decodeFromString<GenesisEncounterOutcome>(readGenesisAuthorityReceipt(db, "encounter-end")!!.outcomeJson).after)
        unchangedMechanics(original, authority.backup.snapshot())
    }

    @Test fun identicalRetriesReturnOriginalOutcomeAfterLaterTurnsAndResetWithoutReapplying(): Unit = runBlocking {
        fixture()
        val created = draft()
        val review = encounters.reviewStart(id, created.record.revision)
        val started = encounters.start(review, "retry-start")
        val next = encounters.nextTurn(id, started.record.revision, "retry-next")
        val ended = encounters.end(id, next.record.revision, "retry-end")
        val setup = encounters.reopenSetup(id, ended.record.revision, "retry-reopen")
        val beforeRetries = authority.backup.snapshot()
        assertEquals(created, draft())
        assertEquals(started, encounters.start(review, "retry-start"))
        assertEquals(next, encounters.nextTurn(id, started.record.revision, "retry-next"))
        assertEquals(ended, encounters.end(id, next.record.revision, "retry-end"))
        assertEquals(setup, encounters.reopenSetup(id, ended.record.revision, "retry-reopen"))
        assertEquals(beforeRetries, authority.backup.snapshot())
        rejectUnchanged { encounters.nextTurn(id, next.record.revision, "retry-next") }
        rejectUnchanged { encounters.end(id, started.record.revision, "retry-next") }
        rejectUnchanged { encounters.saveDraft(id, null, world.id, "Different content", "", inputs(), "encounter-create") }
        rejectUnchanged { encounters.saveDraft("another-id", null, world.id, "Collision", "", inputs(), "encounter-world-create") }
    }

    @Test fun draftEditsAndMembershipChangesInvalidateFrozenStartWithoutPartialWrites() = runBlocking {
        fixture()
        val created = draft()
        val stale = encounters.reviewStart(id, created.record.revision)
        val edited = encounters.saveDraft(id, created.record.revision, world.id, "Changed draft", "", inputs(), "edit-draft")
        rejectUnchanged { encounters.start(stale, "stale-start") }
        rejectUnchanged { encounters.saveDraft(id, created.record.revision, world.id, "Stale draft", "", inputs(), "stale-edit") }
        val beforeMembership = encounters.reviewStart(id, edited.record.revision)
        val moved = authority.updateActorAssociation(npc.actor.actorId, npc.actor.revision, GenesisActorRole.NPC, otherWorld.id, null, "move-selected-npc")
        rejectUnchanged { encounters.start(beforeMembership, "moved-actor-start") }
        rejectUnchanged { encounters.reviewStart(id, edited.record.revision) }
        authority.updateActorAssociation(moved.actorId, moved.revision, GenesisActorRole.NPC, world.id, null, "return-selected-npc")
        rejectUnchanged { encounters.start(beforeMembership, "changed-membership-start") }
        rejectUnchanged { encounters.saveDraft(id, edited.record.revision, world.id, "Stale member", "", inputs(), "stale-member-save") }
        val fresh = encounters.reviewStart(id, edited.record.revision)
        assertEquals(GenesisEncounterStatus.ACTIVE, encounters.start(fresh, "fresh-membership-start").record.status)
    }

    @Test fun laterActorMoveRenameAndArchiveRetainActiveAndHistoricalParticipation() = runBlocking {
        fixture()
        val created = draft()
        val started = encounters.start(encounters.reviewStart(id, created.record.revision), "historical-start")
        authority.updateActorAssociation(npc.actor.actorId, npc.actor.revision, GenesisActorRole.NPC, otherWorld.id, null, "historical-move")
        val saved = ReforgeRepository(db).get(hero.actor.actorId)!!
        ReforgeRepository(db).save(saved.character.id, saved.revision, saved.character.copy(name = "Later fictional name", archived = true),
            "Explicit later actor edit", "historical-archive")
        val afterActorEdits = authority.backup.snapshot()
        val next = encounters.nextTurn(id, started.record.revision, "historical-next")
        assertEquals(hero.actor.actorId, next.record.currentActorId)
        val ended = encounters.end(id, next.record.revision, "historical-end")
        assertEquals(3, ended.participants.size)
        unchangedMechanics(afterActorEdits, authority.backup.snapshot())
        val document = authority.backup.exportWithProof().text
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            val preview = recovery.previewRestore(document)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "historical-encounter-restore")
            assertEquals(ended, GenesisAuthorityRepository(target).encounters.get(id))
            assertEquals("Later fictional name", ReforgeRepository(target).get(hero.actor.actorId)?.character?.name)
            assertEquals(otherWorld.id, target.genesisAuthorityDao().actor(npc.actor.actorId)?.campaignId)
            val setup = GenesisAuthorityRepository(target).encounters.reopenSetup(id, ended.record.revision, "historical-reopen")
            assertNotNull(runCatching { GenesisAuthorityRepository(target).encounters.reviewStart(id, setup.record.revision) }.exceptionOrNull())
        } finally { target.close() }
    }

    @Test fun invalidRosterAndLifecycleRequestsPreserveAllRecords(): Unit = runBlocking {
        fixture()
        val foreign = authority.createActor("Foreign fictional NPC", GenesisActorRole.NPC, otherWorld.id, commandId = "foreign-npc")
        val invalid = listOf(inputs() + inputs().first(), listOf(GenesisEncounterParticipantInput("missing-actor", 1)),
            listOf(GenesisEncounterParticipantInput(foreign.actor.actorId, foreign.actor.revision)),
            listOf(inputs().first().copy(initiative = 1001)), listOf(inputs().first().copy(initiative = -1001)))
        invalid.forEachIndexed { index, input -> rejectUnchanged {
            encounters.saveDraft(id, null, world.id, "Invalid roster", "", input, "invalid-roster-$index")
        } }
        rejectUnchanged { encounters.saveDraft(id, null, "missing-campaign", "Missing campaign", "", emptyList(), "missing-campaign-draft") }
        val empty = encounters.saveDraft(id, null, world.id, "Plan before selecting actors", "", emptyList(), "empty-draft")
        rejectUnchanged { encounters.reviewStart(id, empty.record.revision) }
        rejectUnchanged { encounters.nextTurn(id, empty.record.revision, "draft-next") }
        rejectUnchanged { encounters.end(id, empty.record.revision, "draft-end") }
        rejectUnchanged { encounters.reopenSetup(id, empty.record.revision, "draft-reopen") }
        val filled = encounters.saveDraft(id, empty.record.revision, world.id, empty.record.title, "", inputs(), "filled-draft")
        val started = encounters.start(encounters.reviewStart(id, filled.record.revision), "invalid-state-start")
        rejectUnchanged { encounters.saveDraft(id, started.record.revision, world.id, "Active roster edit", "", inputs().reversed(), "active-edit") }
        rejectUnchanged { encounters.reopenSetup(id, started.record.revision, "active-reset") }
    }

    @Test fun failureAfterParticipantReplacementRollsBackRecordRosterAndReceipt() = runBlocking {
        fixture()
        val created = draft()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_encounter_receipt BEFORE INSERT ON genesis_authority_receipts
            WHEN NEW.commandId='faulted-roster-edit' BEGIN SELECT RAISE(ABORT, 'fictional encounter receipt fault'); END""")
        rejectUnchanged { encounters.saveDraft(id, created.record.revision, world.id, "Changed roster", "", inputs().reversed(), "faulted-roster-edit") }
        assertNull(readGenesisAuthorityReceipt(db, "faulted-roster-edit"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_encounter_receipt")
        val saved = encounters.saveDraft(id, created.record.revision, world.id, "Changed roster", "", inputs().reversed(), "faulted-roster-edit")
        assertEquals(inputs().reversed().map { it.actorId }, saved.participants.map { it.actorId })
    }

    @Test fun preEncounterBackupWireAndFingerprintRemainUnchangedAndRestoreIsCompatible() = runBlocking {
        fixture()
        val current = authority.backup.snapshot()
        val oldAuthority = PreEncounterAuthorityExport(current.authority.campaigns, current.authority.actors,
            current.authority.items, current.authority.receipts)
        val old = PreEncounterBackup(exportedAt = 0, campaign = current.campaign, playable = current.playable, authority = oldAuthority)
        val bytes = ByteArrayOutputStream().also { campaignEncodeJson(PreEncounterBackup.serializer(), old, it, asciiStrings = true) }.toByteArray()
        val originalDocument = String(bytes, Charsets.US_ASCII)
        assertEquals(originalDocument, genesisAuthorityBackupJson(current))
        assertEquals(campaignHash(originalDocument), genesisAuthorityBackupFingerprint(current))
        assertFalse(originalDocument.contains("\"encounterParticipants\""))
        assertEquals(current, decodeGenesisAuthorityBackup(originalDocument))
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            val preview = recovery.previewRestore(originalDocument)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "pre-encounter-restore")
            assertTrue(target.genesisEncounterDao().allEncounters().isEmpty())
            assertTrue(target.genesisEncounterDao().allParticipants().isEmpty())
        } finally { target.close() }
    }

    @Test fun backupRestoresEveryLifecycleStateAndOriginalCommandRetries() = runBlocking {
        fixture()
        val created = draft()
        val review = encounters.reviewStart(id, created.record.revision)
        val checkpoints = mutableListOf(created to authority.backup.exportWithProof().text)
        val started = encounters.start(review, "portable-start")
        checkpoints += started to authority.backup.exportWithProof().text
        val ended = encounters.end(id, started.record.revision, "portable-end")
        checkpoints += ended to authority.backup.exportWithProof().text
        val reopened = encounters.reopenSetup(id, ended.record.revision, "portable-reopen")
        checkpoints += reopened to authority.backup.exportWithProof().text
        checkpoints.forEachIndexed { index, (expected, document) ->
            val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
            try {
                val recovery = GenesisAuthorityBackupRepository(target)
                val preview = recovery.previewRestore(document)
                assertTrue(preview.conflicts.joinToString(), preview.valid)
                recovery.restore(preview, "portable-restore-$index")
                val recovered = GenesisAuthorityRepository(target).encounters
                assertEquals(expected, recovered.get(id))
                assertEquals(created, recovered.saveDraft(id, null, world.id, created.record.title, created.record.notes, inputs(), "encounter-create"))
                if (index > 0) assertEquals(started, recovered.start(review, "portable-start"))
                assertEquals(expected, recovered.get(id))
            } finally { target.close() }
        }
    }

    @Test fun alteredPortableRowsTransitionsAndMissingHistoryCannotBeRestored() = runBlocking {
        fixture()
        val created = draft()
        val started = encounters.start(encounters.reviewStart(id, created.record.revision), "tamper-start")
        encounters.nextTurn(id, started.record.revision, "tamper-next")
        val current = authority.backup.snapshot()
        val rows = current.authority
        val nextReceipt = rows.receipts.single { it.commandId == "tamper-next" }
        val nextOutcome = campaignJson.decodeFromString<GenesisEncounterOutcome>(nextReceipt.outcomeJson)
        val corruptions = listOf(
            rows.copy(encounters = emptyList()), rows.copy(encounterParticipants = rows.encounterParticipants.drop(1)),
            rows.copy(receipts = rows.receipts.filterNot { it.commandId == "encounter-create" }),
            rows.copy(encounters = rows.encounters.map { it.copy(notes = "Unrecorded change") }),
            rows.copy(receipts = rows.receipts.map { if (it.commandId != "tamper-next") it else nextReceipt.copy(
                outcomeJson = campaignJson.encodeToString(nextOutcome.copy(after = nextOutcome.after.copy(record = nextOutcome.after.record.copy(round = 9))))) }))
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            corruptions.forEach { authorityRows ->
                val preview = recovery.previewRestore(genesisAuthorityBackupJson(current.copy(authority = authorityRows)))
                assertFalse("Incomplete or altered encounter history must reject", preview.valid)
            }
            assertTrue(target.genesisEncounterDao().allEncounters().isEmpty())
            assertTrue(target.genesisAuthorityDao().allActors().isEmpty())
            assertEquals(current, authority.backup.snapshot())
        } finally { target.close() }
    }

    @Test fun rehashedStartCannotClaimDifferentHistoricalParticipantMembership() = runBlocking {
        fixture()
        val created = draft()
        encounters.start(encounters.reviewStart(id, created.record.revision), "membership-proof-start")
        val current = authority.backup.snapshot()
        val original = current.authority.receipts.single { it.commandId == "membership-proof-start" }
        val request = campaignJson.decodeFromString<GenesisEncounterRequest>(original.requestJson)
        val outcome = campaignJson.decodeFromString<GenesisEncounterOutcome>(original.outcomeJson)
        val changedMembers = outcome.members.map { if (it.actorId == hero.actor.actorId) it.copy(role = GenesisActorRole.NPC) else it }
        val changedReview = request.startReview!!.copy(members = changedMembers)
        val forgedRequest = request.copy(startReview = changedReview.copy(fingerprint = genesisEncounterReviewFingerprint(changedReview)))
        val json = campaignJson.encodeToString(forgedRequest)
        val forged = original.copy(requestJson = json, fingerprint = genesisAuthorityFingerprint(original.kind, json),
            outcomeJson = campaignJson.encodeToString(outcome.copy(members = changedMembers)))
        val backup = current.copy(authority = current.authority.copy(receipts = current.authority.receipts.map { if (it.commandId == original.commandId) forged else it }))
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val preview = GenesisAuthorityBackupRepository(target).previewRestore(genesisAuthorityBackupJson(backup))
            assertFalse(preview.valid)
            assertTrue(preview.conflicts.joinToString(), preview.conflicts.any { it.contains("frozen campaign membership") })
            assertEquals(current, authority.backup.snapshot())
        } finally { target.close() }
    }

    @Test fun olderMissingReceiptStopsReadAdvanceEndAndExactRetryWithoutFurtherWrites(): Unit = runBlocking {
        fixture()
        val created = draft()
        val review = encounters.reviewStart(id, created.record.revision)
        val started = encounters.start(review, "gap-start")
        val next = encounters.nextTurn(id, started.record.revision, "gap-next")
        val later = encounters.nextTurn(id, next.record.revision, "gap-later")
        db.openHelper.writableDatabase.execSQL("DELETE FROM genesis_authority_receipts WHERE commandId=?", arrayOf("encounter-create"))
        rejectUnchanged { encounters.get(id) }
        rejectUnchanged { encounters.nextTurn(id, later.record.revision, "extend-gap") }
        rejectUnchanged { encounters.end(id, later.record.revision, "end-gap") }
        rejectUnchanged { encounters.start(review, "gap-start") }
        rejectUnchanged { encounters.nextTurn(id, next.record.revision, "gap-later") }
    }

    @Test fun standaloneAuditIdentityCannotBecomeAnEncounterOrAuthorityParent() = runBlocking {
        fixture()
        val source = CampaignLedgerRepository(db)
        val item = source.create(LedgerDraft("Fictional audit collision item", quantity = 1, source = "Fictional source"), "audit-item-create")
        source.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = npc.actor.actorId,
            source = "Fictional explicit transfer", commandId = "audit-source-move"))
        val auditId = "audit-source-move:placement"
        assertTrue(genesisAuditIdentityExists(db, auditId))
        assertNull(db.recordsDao().receipt(auditId)); assertNull(db.ledgerDao().event(auditId))
        authority.backup.exportWithProof() // The prior source history is independently valid.
        rejectUnchanged { encounters.saveDraft(id, null, world.id, "Audit collision", "", inputs(), auditId) }
        rejectUnchanged { authority.createCampaign("Audit collision campaign", commandId = auditId) }
        assertNull(readGenesisAuthorityReceipt(db, auditId))
    }

    @Test fun sameIdentityBackupConflictCannotOverwriteAChangedEncounterOrPartiallyMerge() = runBlocking {
        fixture()
        val created = draft()
        val draftDocument = authority.backup.exportWithProof().text
        encounters.start(encounters.reviewStart(id, created.record.revision), "conflict-source-start")
        val laterDocument = authority.backup.exportWithProof().text
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            recovery.restore(recovery.previewRestore(draftDocument), "conflict-initial-restore")
            GenesisAuthorityRepository(target).encounters.saveDraft(id, created.record.revision, world.id,
                "Different explicit local edit", "", inputs().reversed(), "conflict-local-edit")
            val before = recovery.snapshot()
            val preview = recovery.previewRestore(laterDocument)
            assertFalse(preview.valid)
            assertTrue(preview.conflicts.joinToString(), preview.conflicts.any { it.contains("Encounter") && it.contains("differs") })
            assertNotNull(runCatching { recovery.restore(preview, "blocked-encounter-restore") }.exceptionOrNull())
            assertEquals(before, recovery.snapshot())
        } finally { target.close() }
    }
}
