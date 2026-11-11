package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Campaign source checks and gameplay writes use the same actual Room transaction. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisSharedInventoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CampaignLedgerDb
    private lateinit var campaign: CampaignLedgerRepository
    private lateinit var playable: ReforgeRepository
    private val source = "Fictional shared-storage fixture"
    private val profileId = "shared-fictional-player"
    private val itemId = "shared-fictional-compass"
    private val canonicalName = "genesis-shared-canonical-fixture.db"

    @Before fun open() {
        context.deleteDatabase(canonicalName)
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        campaign = CampaignLedgerRepository(db)
        playable = ReforgeRepository(db)
    }

    @After fun close() { db.close(); context.deleteDatabase(canonicalName) }

    private suspend fun fixture(): Pair<ReforgeSavedCharacter, LedgerItem> {
        campaign.records.saveProfile(CharacterProfile(id = profileId, name = "Fictional explorer", currentHp = 12, maximumHp = 20), source = source)
        val actor = playable.adopt(campaign, playable.readLegacyPreview(campaign, profileId))
        val created = campaign.create(LedgerDraft("Fictional compass", quantity = 2, itemState = "Carried",
            evidenceStatus = "Proposed", description = "An unapproved description does not grant a mechanical effect.", source = source), recordId = itemId)
        campaign.party.moveItem(ItemMoveCommand(itemId, created.revision, holderProfileId = profileId, state = "Carried", source = source))
        return actor to requireNotNull(db.ledgerDao().getItem(itemId))
    }

    private suspend fun campaignSnapshot() = campaignDecodeBackup(campaign.export()).copy(exportedAt = 0)
    private suspend fun playableSnapshot() = decodeReforgeBackup(playable.export()).copy(exportedAt = 0)
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("A failed shared transaction must not partially commit", failure)
        return requireNotNull(failure)
    }

    @Test fun sharedTransactionFailureRollsBackSourceAdjustmentAcquisitionAndBothReceiptHistories() = runBlocking {
        val (original, item) = fixture()
        val beforeCampaign = campaignSnapshot()
        val beforePlayable = playableSnapshot()

        val failure = rejected {
            playable.withCampaignSnapshot(campaign) {
                campaign.party.changeQuantity(QuantityCommand(item.id, item.revision, 1, source, "shared-source-adjustment"))
                val review = playable.reviewCampaignItem(campaign, profileId, item.id)
                val accepted = playable.acceptCampaignItem(campaign, review, "shared-acquisition-rollback")
                assertEquals(3, accepted.character.items.single().quantity)
                assertNotNull(playable.commandReceipt("shared-acquisition-rollback"))
                error("Injected failure after both domains changed")
            }
        }

        assertEquals("Injected failure after both domains changed", failure.message)
        assertEquals(beforeCampaign, campaignSnapshot())
        assertEquals(beforePlayable, playableSnapshot())
        assertEquals(original, playable.get(profileId))
        assertNull(campaign.records.receipt("shared-source-adjustment"))
        assertNull(playable.commandReceipt("shared-acquisition-rollback"))

        val fresh = playable.reviewCampaignItem(campaign, profileId, item.id)
        assertEquals(2, playable.acceptCampaignItem(campaign, fresh, "shared-after-rollback").character.items.single().quantity)
    }

    @Test fun anotherSourceWriterWaitsUntilReviewedAcquisitionCommits() = runBlocking {
        withTimeout(15_000) {
            val (_, item) = fixture()
            val reviewHeld = CompletableDeferred<Unit>()
            val releaseAcquisition = CompletableDeferred<Unit>()
            val writerStarted = CompletableDeferred<Unit>()
            val writerFinished = CompletableDeferred<Unit>()

            val acquisition = async(Dispatchers.Default) {
                playable.withCampaignSnapshot(campaign) {
                    val review = playable.reviewCampaignItem(campaign, profileId, item.id)
                    reviewHeld.complete(Unit)
                    releaseAcquisition.await()
                    playable.acceptCampaignItem(campaign, review, "serialized-acquisition")
                }
            }
            reviewHeld.await()
            val mutation = async(Dispatchers.Default) {
                writerStarted.complete(Unit)
                campaign.party.changeQuantity(QuantityCommand(item.id, item.revision, 1, source, "serialized-source-change"))
                writerFinished.complete(Unit)
            }
            writerStarted.await()
            try {
                assertNull("A separate source transaction must remain queued while review and acceptance hold the shared transaction",
                    withTimeoutOrNull(300) { writerFinished.await() })
            } finally {
                releaseAcquisition.complete(Unit)
            }

            val accepted = acquisition.await()
            mutation.await()
            assertEquals(2, accepted.character.items.single().quantity)
            assertEquals(3, db.ledgerDao().getItem(item.id)!!.quantity)
            val receipt = requireNotNull(playable.commandReceipt("serialized-acquisition"))
            val proof = reforgeJson.decodeFromString<GenesisInventoryOutcome>(receipt.afterJson).review
            assertEquals(2, proof.source.item.quantity)
            assertEquals(item.revision, proof.source.item.revision)
            assertNotNull(campaign.records.receipt("serialized-source-change"))
        }
    }

    @Test fun staleSourceRejectionLeavesSharedCampaignAndPlayableRecordsExactlyUnchanged() = runBlocking {
        val (_, item) = fixture()
        val stale = playable.reviewCampaignItem(campaign, profileId, item.id)
        campaign.party.changeQuantity(QuantityCommand(item.id, item.revision, 1, source, "source-before-stale-review"))
        val beforeCampaign = campaignSnapshot()
        val beforePlayable = playableSnapshot()

        rejected { playable.acceptCampaignItem(campaign, stale, "rejected-shared-import") }
        assertEquals(beforeCampaign, campaignSnapshot())
        assertEquals(beforePlayable, playableSnapshot())
        assertNull(playable.commandReceipt("rejected-shared-import"))
    }

    @Test fun sharedReceiptRetryReturnsOriginalOutcomeAfterBothRecordsAdvance() = runBlocking {
        val (_, item) = fixture()
        val review = playable.reviewCampaignItem(campaign, profileId, item.id)
        val accepted = playable.acceptCampaignItem(campaign, review, "shared-idempotent-acquisition")
        val edited = playable.save(profileId, accepted.revision, accepted.character.copy(name = "Later fictional name"), "Rename fictional actor", "shared-later-rename")
        campaign.party.changeQuantity(QuantityCommand(item.id, item.revision, 1, source, "shared-later-quantity"))
        val beforeRetryCampaign = campaignSnapshot()
        val beforeRetryPlayable = playableSnapshot()

        assertEquals(accepted, playable.acceptCampaignItem(campaign, review, "shared-idempotent-acquisition"))
        assertEquals(edited, playable.get(profileId))
        assertEquals(beforeRetryCampaign, campaignSnapshot())
        assertEquals(beforeRetryPlayable, playableSnapshot())
    }

    @Test fun differentRoomInstancesForTheSameFileRejectAcquisitionBeforeAnyMutation() = runBlocking {
        withTimeout(15_000) {
            db.close()
            db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, canonicalName).build()
            campaign = CampaignLedgerRepository(db)
            playable = ReforgeRepository(db)
            val (_, item) = fixture()
            val review = playable.reviewCampaignItem(campaign, profileId, item.id)
            val beforeCampaign = campaignSnapshot()
            val beforePlayable = playableSnapshot()
            val separateRoom = Room.databaseBuilder(context, CampaignLedgerDb::class.java, canonicalName).build()
            try {
                val wrongTransactionOwner = ReforgeRepository(separateRoom)
                rejected { wrongTransactionOwner.acceptCampaignItem(campaign, review, "wrong-room-acquisition") }
                assertEquals(beforeCampaign, campaignSnapshot())
                assertEquals(beforePlayable, playableSnapshot())
                assertNull(playable.commandReceipt("wrong-room-acquisition"))
            } finally { separateRoom.close() }
        }
    }

    @Test fun creationAndObservationBothWaitUntilTheStorageGateIsReleased() = runBlocking {
        withTimeout(15_000) {
            val release = CompletableDeferred<Unit>()
            val bothWaiting = CompletableDeferred<Unit>()
            val entered = java.util.concurrent.atomic.AtomicInteger()
            val gated = ReforgeRepository(db, ready = {
                if (entered.incrementAndGet() == 2) bothWaiting.complete(Unit)
                release.await()
            })
            val creation = async(Dispatchers.Default) {
                gated.create(ReforgeCharacter("gated-fictional-player", "Fictional gated actor"), "gated-creation")
            }
            val observation = async(Dispatchers.Default) { gated.observeAll().first { it.isNotEmpty() } }
            bothWaiting.await()
            try {
                assertFalse(creation.isCompleted)
                assertFalse(observation.isCompleted)
                assertTrue(db.reforgeDao().allCharacters().isEmpty())
                assertTrue(db.reforgeDao().allChanges().isEmpty())
            } finally { release.complete(Unit) }

            val created = creation.await()
            assertEquals(listOf(created), observation.await())
            assertEquals(created, gated.get(created.character.id))
        }
    }

    @Test fun failedStorageGateCannotCreateRecordsOrPublishAnEmptyPlayableRoster() = runBlocking {
        withTimeout(15_000) {
            val gated = ReforgeRepository(db, ready = { error("Fictional storage recovery required") })
            val createFailure = rejected {
                gated.create(ReforgeCharacter("blocked-fictional-player", "Fictional blocked actor"), "blocked-creation")
            }
            val observationFailure = rejected { gated.observeAll().first() }
            assertEquals("Fictional storage recovery required", createFailure.message)
            assertEquals("Fictional storage recovery required", observationFailure.message)
            assertTrue(db.reforgeDao().allCharacters().isEmpty())
            assertTrue(db.reforgeDao().allChanges().isEmpty())
        }
    }
}
