package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import com.yazsras.astravar.rules.reforge.Stat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeCampaignIdentityTest {
    private fun fixture(block: suspend (ReforgeRepository, CampaignLedgerRepository, CampaignLedgerDb) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playable = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        try { block(ReforgeRepository(playable), CampaignLedgerRepository(campaign), campaign) }
        finally { playable.close(); campaign.close() }
    }
    private suspend fun rejects(work: suspend () -> Unit) {
        try { work(); fail("Expected a guarded identity rejection") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun reviewedNameOnlyIdentityKeepsMechanicsUnknownAndEnablesRealCharacterRelationship() = fixture { local, campaign, db ->
        val saved = local.create(ReforgeCharacter("fictional-new-player", "Fictional new player", level = 9,
            currentHp = 45, baseMaxHp = 66, baseAbilities = mapOf(Stat.INT to 20)))
        assertNull(local.inspectCampaignIdentity(campaign, saved))
        assertTrue(campaign.records.characterProfiles.first().isEmpty())
        val identity = local.createReviewedCampaignIdentity(campaign, saved)
        assertEquals(saved.character.id, identity.profile.id)
        assertEquals(saved.character.name, identity.profile.name)
        assertNull(identity.profile.currentHp); assertNull(identity.profile.maximumHp)
        assertNull(identity.profile.temporaryHp); assertNull(identity.profile.armorClass); assertNull(identity.profile.level)
        assertTrue(db.recordsDao().allCharacterAbilities().isEmpty())
        assertEquals(saved, local.get(saved.character.id))
        assertEquals(identity, local.createReviewedCampaignIdentity(campaign, saved))
        assertEquals(1, db.recordsDao().allReceipts().size)
        val companionId = campaign.records.createCompanion("Fictional ally")
        val link = CampaignLink("companion", companionId, "character-profile", identity.profile.id, "Fictional companion of")
        campaign.records.saveLink(link, source = "Fictional identity acceptance")
        assertEquals(identity.profile.id, campaign.records.links.first().single().toId)
        val export = campaignDecodeBackup(campaign.export())
        assertTrue(export.records.receipts.any { it.commandId == identity.originalReceipt!!.commandId })
    }

    @Test fun staleReviewCannotCreateButSuccessfulSourceWriteRecoversAfterPlayableRename() = fixture { local, campaign, db ->
        val first = local.create(ReforgeCharacter("fictional-recovery", "Reviewed original name"))
        val second = local.save(first.character.id, first.revision, first.character.copy(name = "Reviewed latest name"), "Rename")
        rejects { local.createReviewedCampaignIdentity(campaign, first) }
        assertTrue(campaign.records.characterProfiles.first().isEmpty())
        val identity = local.createReviewedCampaignIdentity(campaign, second)
        val third = local.save(second.character.id, second.revision, second.character.copy(name = "Independent playable name"), "Rename again")
        assertEquals(identity, local.createReviewedCampaignIdentity(campaign, second))
        assertEquals(identity, local.inspectCampaignIdentity(campaign, third))
        assertEquals("Reviewed latest name", campaign.records.characterProfiles.first().single().name)
        assertEquals(third, local.get(first.character.id))
        assertEquals(1, db.recordsDao().allReceipts().size)
    }

    @Test fun occupiedIdAndForgedProofCannotAliasOrOverwriteUnrelatedCampaignProfile() = fixture { local, campaign, db ->
        val saved = local.create(ReforgeCharacter("fictional-collision", "Same name"))
        campaign.records.saveProfile(CharacterProfile(id = saved.character.id, name = "Same name", currentHp = 7), source = "Unrelated fixture")
        val before = db.recordsDao().characterProfile(saved.character.id)
        rejects { local.createReviewedCampaignIdentity(campaign, saved) }
        assertEquals(before, db.recordsDao().characterProfile(saved.character.id))
        assertEquals(saved, local.get(saved.character.id))
        val other = local.create(ReforgeCharacter("fictional-forged-proof", "Fictional proof"))
        campaign.records.saveProfile(CharacterProfile(id = other.character.id, name = other.character.name),
            source = "Unrelated operation using the reserved command", commandId = reforgeIdentityCommand(other.character.id))
        rejects { local.createReviewedCampaignIdentity(campaign, other) }
        assertEquals(2, db.recordsDao().allReceipts().size)
    }

    @Test fun adoptedSourceIdentityResolvesReadOnlyAndIsNeverReplacedWithNameOnlyCopy() = fixture { local, campaign, db ->
        val original = CharacterProfile(id = "fictional-adopted", name = "Original source name", currentHp = 8, maximumHp = 12)
        campaign.records.saveProfile(original, source = "Fictional adopted source")
        val preview = local.readLegacyPreview(campaign, original.id)
        val adopted = local.adopt(campaign, preview)
        val identity = local.createReviewedCampaignIdentity(campaign, adopted)
        assertEquals(8, identity.profile.currentHp)
        assertNull(identity.originalReceipt)
        assertEquals(1, db.recordsDao().allReceipts().size)
        assertEquals(adopted, local.get(adopted.character.id))
    }

    @Test fun cleanCampaignAndPlayableRestoreRetainVerifiableIdentityProof() = fixture { local, campaign, _ ->
        val saved = local.create(ReforgeCharacter("fictional-restored-proof", "Fictional restored identity", currentHp = 11))
        val identity = local.createReviewedCampaignIdentity(campaign, saved)
        val campaignText = campaign.export()
        val playableText = local.export()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playableTarget = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val campaignTarget = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        try {
            val nextLocal = ReforgeRepository(playableTarget)
            val nextCampaign = CampaignLedgerRepository(campaignTarget)
            val sourcePreview = nextCampaign.previewImport(campaignText)
            assertTrue(sourcePreview.conflicts.toString(), sourcePreview.valid)
            nextCampaign.restore(sourcePreview, "restore-fictional-campaign")
            val playablePreview = nextLocal.previewRestore(playableText)
            assertTrue(playablePreview.conflicts.toString(), playablePreview.valid)
            nextLocal.restore(playablePreview, "restore-fictional-playable")
            val restored = requireNotNull(nextLocal.get(saved.character.id))
            assertEquals(saved, restored)
            val readback = requireNotNull(nextLocal.inspectCampaignIdentity(nextCampaign, restored))
            assertEquals(identity, readback)
            assertEquals(identity.originalReceipt, nextCampaign.records.receipt(reforgeIdentityCommand(saved.character.id)))
            assertNull(readback.profile.currentHp)
            assertEquals(11, restored.character.currentHp)
        } finally { playableTarget.close(); campaignTarget.close() }
    }

    @Test fun reviewedCreationSerializesConcurrentRenameAndArchiveUntilSourceReceiptCommits() = runBlocking {
        for (archive in listOf(false, true)) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val insideLockedReview = CompletableDeferred<Unit>()
            val releaseSourceRead = CountDownLatch(1)
            val reads = AtomicInteger()
            val playableDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
            val campaignDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java)
                .setQueryCallback({ sql, _ ->
                    if (sql.contains("FROM command_receipts", true) && reads.incrementAndGet() == 2) {
                        insideLockedReview.complete(Unit)
                        check(releaseSourceRead.await(15, TimeUnit.SECONDS)) { "Fictional identity test gate timed out" }
                    }
                }, Executor { it.run() }).build()
            try {
                val local = ReforgeRepository(playableDb)
                val campaign = CampaignLedgerRepository(campaignDb)
                val saved = local.create(ReforgeCharacter("fictional-race-$archive", "Reviewed before mutation"))
                coroutineScope {
                    val creating = async(Dispatchers.Default) { local.createReviewedCampaignIdentity(campaign, saved) }
                    withTimeout(10_000) { insideLockedReview.await() }
                    val mutating = async(start = CoroutineStart.UNDISPATCHED) {
                        if (archive) local.setArchived(saved.character, true, "concurrent-archive") else
                            local.save(saved.character.id, saved.revision, saved.character.copy(name = "Later playable rename"), "Concurrent rename", "concurrent-rename")
                    }
                    try {
                        assertNull("A playable mutation must wait for the reviewed source copy", withTimeoutOrNull(250) { mutating.await() })
                    } finally { releaseSourceRead.countDown() }
                    val identity = withTimeout(10_000) { creating.await() }
                    val changed = withTimeout(10_000) { mutating.await() }
                    assertEquals("Reviewed before mutation", identity.profile.name)
                    assertEquals(1L, identity.copiedFromRevision)
                    assertEquals(2L, changed.revision)
                    assertEquals(archive, changed.character.archived)
                    assertEquals(identity, local.inspectCampaignIdentity(campaign, changed))
                }
            } finally { releaseSourceRead.countDown(); playableDb.close(); campaignDb.close() }
        }
    }
}
