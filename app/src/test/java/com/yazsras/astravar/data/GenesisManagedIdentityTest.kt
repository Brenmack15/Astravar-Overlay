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

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisManagedIdentityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CampaignLedgerDb
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var playable: ReforgeRepository
    private lateinit var campaign: CampaignLedgerRepository
    private val source = "Fictional managed identity fixture"
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        authority = GenesisAuthorityRepository(db); playable = ReforgeRepository(db); campaign = CampaignLedgerRepository(db)
    }
    @After fun close() { db.close() }
    private suspend fun actor() = authority.createActor("Fictional managed scout", GenesisActorRole.PLAYER,
        playable = ReforgeCharacter("managed-identity-scout", "Fictional managed scout"), commandId = "managed-identity-create").playable!!
    private suspend fun reject(block: suspend () -> Unit) { assertNotNull(runCatching { block() }.exceptionOrNull()) }

    @Test fun explicitCreationIdentitySurvivesRenameButRejectsStaleOrInventedOriginSnapshots() = runBlocking {
        val first = actor()
        assertEquals(first.character.id, playable.inspectCampaignIdentity(campaign, first)?.profile?.id)
        val later = playable.save(first.character.id, first.revision, first.character.copy(name = "Renamed fictional scout"), "Fictional rename", "managed-identity-rename")
        assertEquals(later.character.name, playable.inspectCampaignIdentity(campaign, later)?.profile?.name)
        val before = authority.backup.snapshot()
        reject { playable.inspectCampaignIdentity(campaign, first) }
        reject { playable.inspectCampaignIdentity(campaign, later.copy(origin = later.origin.copy(label = "Invented origin"))) }
        reject { playable.inspectCampaignIdentity(campaign, later.copy(createdAt = later.createdAt + 1)) }
        assertEquals(before, authority.backup.snapshot())
        assertNull(campaign.records.receipt(reforgeIdentityCommand(first.character.id)))
    }

    @Test fun matchingLabelsCannotEstablishAuthorityAndAnotherRoomSessionCannotSupplyProof() = runBlocking {
        val first = actor()
        val other = playable.create(ReforgeCharacter("unconnected-same-name", first.character.name), "same-name-playable")
        campaign.records.saveProfile(CharacterProfile(id = other.character.id, name = first.character.name), source = source, commandId = "same-name-source")
        reject { playable.inspectCampaignIdentity(campaign, other) }
        val secondDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            reject { playable.inspectCampaignIdentity(CampaignLedgerRepository(secondDb), first) }
            assertTrue(secondDb.reforgeDao().allCharacters().isEmpty())
            assertTrue(secondDb.recordsDao().allCharacterProfiles().isEmpty())
        } finally { secondDb.close() }
    }

    @Test fun tamperedActivationRequestAndCurrentProjectionAreRejectedWithoutAliasRepair() = runBlocking {
        val first = actor()
        val sql = db.openHelper.writableDatabase
        val receipt = readGenesisAuthorityReceipt(db, "managed-identity-create")!!
        sql.execSQL("UPDATE genesis_authority_receipts SET requestJson=? WHERE commandId=?", arrayOf("[]", receipt.commandId))
        reject { playable.inspectCampaignIdentity(campaign, first) }
        sql.execSQL("UPDATE genesis_authority_receipts SET requestJson=? WHERE commandId=?", arrayOf(receipt.requestJson, receipt.commandId))
        sql.execSQL("UPDATE character_profiles SET name='Unreviewed name' WHERE id=?", arrayOf(first.character.id))
        reject { playable.inspectCampaignIdentity(campaign, first) }
        assertEquals("Unreviewed name", db.recordsDao().characterProfile(first.character.id)?.name)
        assertEquals(first, playable.get(first.character.id))
        assertNull(campaign.records.receipt(reforgeIdentityCommand(first.character.id)))
    }

    @Test fun managedAcquisitionKeepsOriginalUtf8RecoveryBoundAndRollsBackEveryProjection() = runBlocking {
        recoveryBoundary(multilingual = true)
    }

    @Test fun managedAcquisitionKeepsOriginalCharacterRecoveryBoundAndRollsBackEveryProjection() = runBlocking {
        recoveryBoundary(multilingual = false)
    }

    private suspend fun recoveryBoundary(multilingual: Boolean) {
        val id = "managed-recovery-owner"
        campaign.records.saveProfile(CharacterProfile(id = id, name = "Fictional recovery owner"), source = source, commandId = "recovery-profile")
        val item = campaign.create(LedgerDraft("Fictional source item", quantity = 1, itemState = "Carried", source = source,
            imageBase64 = "A".repeat(900_000)), "recovery-item", "managed-recovery-item")
        campaign.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = id, source = source, commandId = "recovery-placement"))
        playable.create(ReforgeCharacter(id, "Fictional recovery owner"), "recovery-playable")
        var current = authority.acceptActorAuthority(authority.reviewActorAuthority(id, GenesisActorRole.PLAYER), "recovery-connect").playable!!
        val note = (if (multilingual) "龍" else "x").repeat(99_900)
        var recovery = reforgeJson.encodeToString(genesisPlayableBackupRows(db))
        while (recovery.toByteArray(Charsets.UTF_8).size < if (multilingual) 7_400_000 else 7_100_000) {
            current = playable.save(id, current.revision, current.character.copy(notes = "${current.revision}:$note"), "Fictional journal", "recovery-notes-${current.revision}")
            recovery = reforgeJson.encodeToString(genesisPlayableBackupRows(db))
        }
        assertTrue(recovery.length < REFORGE_MAX_JSON)
        validateGenesisBackupDocumentSize(recovery)
        val review = playable.reviewCampaignItem(campaign, id, item.id)
        val before = authority.backup.snapshot()
        val failure = runCatching { playable.acceptCampaignItem(campaign, review, "managed-recovery-overflow") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains(if (multilingual) "8 MiB" else "supported export size"))
        assertEquals(before, authority.backup.snapshot())
        assertEquals(current, playable.get(id))
        assertNull(playable.commandReceipt("managed-recovery-overflow"))
        assertNull(readGenesisAuthorityItem(db, item.id))
        assertEquals("A".repeat(900_000), db.ledgerDao().getItem(item.id)?.imageBase64)
    }

    @Test fun affectedRecordReadbackFailureRollsBackAllCreationChildrenAndReceipt() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fictional_corrupt_actor_after_authority_receipt
            AFTER INSERT ON genesis_authority_receipts WHEN NEW.commandId='managed-identity-create'
            BEGIN UPDATE character_profiles SET name='Fictional readback fault' WHERE id='managed-identity-scout'; END""")
        reject { actor() }
        assertTrue(db.genesisAuthorityDao().allActors().isEmpty())
        assertTrue(db.genesisAuthorityDao().allReceipts().isEmpty())
        assertTrue(db.reforgeDao().allCharacters().isEmpty())
        assertTrue(db.reforgeDao().allChanges().isEmpty())
        assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
        assertTrue(db.recordsDao().allAudit().isEmpty())
        assertTrue(db.recordsDao().allReceipts().isEmpty())
    }
}
