package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Optional private-source rehearsal. Only disposable Room databases are constructed here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConvergencePrivateMigrationTest {
    @Test fun sourcedWorldRecordsReopenWithOriginalFactsAndRollbackOnlyTheirPackage() = runBlocking {
        val path = System.getenv("ASTRAVAR_CONVERGENCE_PRIVATE_REHEARSAL")
        assumeTrue("Supply the reviewed private rehearsal directory.", !path.isNullOrBlank())
        val dir = File(requireNotNull(path))
        val input = File(dir, "staging-world.json").readText()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "convergence-private-world-disposable.db"
        fun open() = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            CampaignRecordsRepository(db).saveEntity(ConnectionEntity("Disposable sentinel", id = "staging-sentinel"), source = "Fixture")
            val before = requireNotNull(db.recordsDao().entity("staging-sentinel"))
            val repository = CampaignArchiveRepository(db)
            val preview = repository.preview(input)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            val expected = requireNotNull(requireNotNull(preview.archive).mapped).records
            val prior = repository.exportPreImportBackup(preview.fingerprint)
            val receipt = repository.commit(preview, confirmed = true)
            assertEquals(receipt, repository.commit(repository.preview(input), confirmed = true))
            db.close()
            db = open()
            val reopened = CampaignArchiveRepository(db)
            assertTrue(reopened.verify(receipt.packageHash).valid)
            assertEquals(expected.entities.toSet() + before, db.recordsDao().allEntities().toSet())
            assertEquals(expected.facts.toSet(), db.recordsDao().allFacts().toSet())
            assertTrue(expected.facts.isNotEmpty())
            val visible = CampaignSyncDomain(db).snapshot(requireNotNull(preview.archive).campaignId)
            assertEquals(expected.entities.map { it.id }.toSet(), visible.filter { it.kind == "connection" }.map { it.recordId }.toSet())
            assertEquals(expected.facts.map { it.id }.toSet(), visible.filter { it.kind == "fact" }.map { it.recordId }.toSet())
            assertTrue(CampaignSyncDomain(db).snapshot("local-campaign").none { it.recordId in expected.entities.map { entity -> entity.id } })

            val rollback = reopened.rollback(receipt.packageHash, confirmed = true)
            assertEquals(listOf(before), db.recordsDao().allEntities())
            assertTrue(db.recordsDao().allFacts().isEmpty())
            File(dir, "staging-world-evidence.json").writeText(buildJsonObject {
                put("environment", "disposable file-backed Robolectric Room")
                put("preArchiveBackupSha256", campaignHash(prior)); put("archiveReceipt", receipt.packageHash)
                put("archiveReadback", receipt.readbackSha256); put("rollbackReceipt", rollback.packageHash)
                put("sourceReferences", receipt.recordCount); put("entities", expected.entities.size)
                put("facts", expected.facts.size); put("replayAndReopen", true); put("originalSentinelPreserved", true)
            }.toString())
        } finally {
            db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun reviewedEquipmentAndCheckpointSourcesCommitReplayAndRestoreInIsolation() = runBlocking {
        val path = System.getenv("ASTRAVAR_CONVERGENCE_PRIVATE_REHEARSAL")
        assumeTrue("Supply the reviewed private rehearsal directory.", !path.isNullOrBlank())
        val dir = File(requireNotNull(path))
        val transfer = campaignJson.decodeFromString<NotionCharacterTransfer>(File(dir, "staging-equipment.json").readText())
        val archive = File(dir, "staging-checkpoints.json").readText()
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun fixture() = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        val db = fixture()
        val restored = fixture()
        try {
            val archives = CampaignArchiveRepository(db)
            val preview = archives.preview(archive)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            val archiveBackup = archives.exportPreImportBackup(preview.fingerprint)
            val receipt = archives.commit(preview, confirmed = true)
            assertTrue(archives.verify(receipt.packageHash).valid)
            assertEquals(receipt, archives.commit(archives.preview(archive), confirmed = true))
            val checkpoint = db.partyDao().allCheckpoints().single()
            assertEquals("convergence-private-rehearsal", checkpoint.campaignId)
            assertTrue(checkpoint.unresolvedChoice.isNotBlank())
            val authority = GenesisAuthorityRepository(db)
            val campaign = authority.createCampaign("Disposable equipment rehearsal", commandId = "staging-campaign")
            val actorId = "convergence-staging-actor"
            require(transfer.character.targetProfileId == actorId)
            val base = transfer.character.permanentAbilities + (Stat.INT to 22)
            val actor = authority.createActor(transfer.character.name, GenesisActorRole.PLAYER, campaign.id,
                playable = ReforgeCharacter(actorId, transfer.character.name, level = 11, baseAbilities = base,
                    baseAc = 12, baseMaxHp = 80, baselineConstitutionModifier = 2, currentHp = 57, hpDamage = 23,
                    spellSlots = mapOf(1 to 0, 2 to null)), commandId = "staging-create-actor")
            val original = requireNotNull(actor.playable)
            val backup = GenesisAuthorityBackupRepository(db)
            val proof = backup.exportWithProof()
            val choices = NotionTransferChoices(stats = false, items = true)
            val importer = NotionTransferRepository(db)
            val outcome = importer.commit(original, transfer, choices, false, proof.currentFingerprint, "staging-first-import")
            assertEquals(1, outcome.itemsAdded)
            assertEquals(outcome, importer.commit(original, transfer, choices, false, proof.currentFingerprint, "staging-first-import"))
            val reread = requireNotNull(ReforgeRepository(db).get(actorId))
            val after = backup.exportWithProof()
            val repeated = importer.commit(reread, transfer, choices, false, after.currentFingerprint, "staging-second-import")
            assertEquals(0, repeated.itemsAdded)
            assertEquals(1, repeated.itemsPreviouslyPresent)
            val item = reread.character.items.single()
            assertEquals("notion-instance-" + transfer.inventory.single().sourceId, item.id)
            val active = CharacterCalculator.calculate(reread.character)
            assertEquals(21, active.abilities.getValue(Stat.STR).effective.value)
            assertEquals(16, active.abilities.getValue(Stat.CON).effective.value)
            assertEquals(20, active.armorClass.value)
            assertEquals(22, reread.character.baseAbilities[Stat.INT])
            assertEquals(original.character.spellSlots, reread.character.spellSlots)
            assertEquals(original.character.hpDamage, reread.character.hpDamage)
            assertTrue(db.partyDao().allWallets().isEmpty())
            val inactive = CharacterCalculator.calculate(GameplayRules.equip(reread.character, item.id, ItemState.STORED))
            assertEquals(base[Stat.STR], inactive.abilities.getValue(Stat.STR).effective.value)
            assertEquals(12, inactive.armorClass.value)
            val restore = GenesisAuthorityBackupRepository(restored)
            val restorePreview = restore.previewRestore(backup.exportWithProof().text)
            assertTrue(restorePreview.conflicts.joinToString(), restorePreview.valid)
            restore.restore(restorePreview, "private-staging-restore")
            assertEquals(reread, ReforgeRepository(restored).get(actorId))
            assertEquals(checkpoint, restored.partyDao().allCheckpoints().single())
            File(dir, "staging-evidence.json").writeText(buildJsonObject {
                put("environment", "disposable Robolectric Room")
                put("preImportBackupSha256", proof.currentFingerprint); put("postImportBackupSha256", after.currentFingerprint)
                put("preArchiveBackupSha256", campaignHash(archiveBackup))
                put("archiveReceipt", receipt.packageHash); put("archiveReadback", receipt.readbackSha256)
                put("archivedCheckpointReferences", receipt.recordCount); put("typedCheckpoints", 1)
                put("equipmentInserted", outcome.itemsAdded); put("repeatInsertions", repeated.itemsAdded)
                put("activeStrength", active.abilities.getValue(Stat.STR).effective.value)
                put("activeConstitution", active.abilities.getValue(Stat.CON).effective.value)
                put("activeAc", active.armorClass.value); put("inactiveAc", inactive.armorClass.value)
                put("stagingBaseInt", reread.character.baseAbilities[Stat.INT])
                put("fullRestoreReadback", true)
            }.toString())
        } finally {
            db.close(); restored.close()
        }
    }
}
