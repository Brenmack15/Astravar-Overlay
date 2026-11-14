package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisSkillsPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val filename = "fictional-genesis-skills.db"
    private var db: ReforgeDb? = null
    @After fun close() { db?.close(); context.deleteDatabase(filename) }
    private fun open(): ReforgeRepository {
        db = Room.databaseBuilder(context, ReforgeDb::class.java, filename).allowMainThreadQueries().build()
        return ReforgeRepository(requireNotNull(db))
    }

    @Test fun customSkillsAndPinsSurviveRestartBackupRestoreAndRemovalRetainsRolls() = runBlocking {
        context.deleteDatabase(filename)
        var repository = open()
        val original = repository.create(ReforgeCharacter("fictional-artisan", "Fictional artisan", level = 9,
            baseAbilities = mapOf(Stat.INT to 20), currentHp = 23, spellSlots = mapOf(1 to 2),
            skills = mapOf("Arcana" to SkillTraining(Stat.INT, true))))
        val skill = CustomSkill("skill:engineering", "Engineering", SkillTraining(Stat.INT, true, true), 1, "Campaign-specific training")
        val configured = repository.save(original.character.id, original.revision,
            SkillRules.pin(SkillRules.pin(SkillRules.save(original.character, skill), skill.id, true), "Arcana", true), "Add and pin skill")
        val roll = repository.appendDiceRoll(configured.character.id, configured.revision, skill.name,
            SecureDice.manual(DiceFormula(listOf(DiceTerm(1, 20)), 14), listOf(12)))
        db!!.close(); repository = open()
        val reopened = requireNotNull(repository.get(original.character.id))
        assertEquals(configured, reopened)
        assertEquals(listOf("Arcana", skill.id), SkillRules.entries(reopened.character).filter { it.pinned }.map { it.id })
        assertEquals(14, CharacterCalculator.calculate(reopened.character).customSkills.getValue(skill.id).value)
        assertEquals(23, reopened.character.currentHp); assertEquals(mapOf(1 to 2), reopened.character.spellSlots)
        val targetDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        try {
            val target = ReforgeRepository(targetDb)
            val preview = target.previewRestore(repository.export())
            assertTrue(preview.conflicts.toString(), preview.valid)
            target.restore(preview)
            val restored = requireNotNull(target.get(reopened.character.id))
            assertEquals(reopened, restored)
            assertEquals(roll, target.rollHistory(restored.character.id).first().single())
            val renamed = target.save(restored.character.id, restored.revision,
                SkillRules.save(restored.character, skill.copy(name = "Runecraft", training = SkillTraining(Stat.WIS), baselineOverride = 6)), "Edit custom skill")
            assertEquals(6, CharacterCalculator.calculate(renamed.character).customSkills.getValue(skill.id).value)
            assertTrue(skill.id in renamed.character.pinnedSkillIds)
            val removed = target.save(renamed.character.id, renamed.revision, SkillRules.remove(renamed.character, skill.id), "Remove custom skill")
            assertTrue(removed.character.customSkills.isEmpty())
            assertEquals(setOf("Arcana"), removed.character.pinnedSkillIds)
            assertEquals(original.character.skills, removed.character.skills)
            assertEquals(roll, target.rollHistory(restored.character.id).first().single())
            assertTrue(target.history(restored.character.id).first().any { it.operation == "Add and pin skill" })
            assertTrue(decodeReforgeBackup(target.export()).changes.any { it.operation == "Edit custom skill" })
        } finally { targetDb.close() }
    }

    @Test fun aStalePinCannotOverwriteNewerSkillOrResourceEdits() = runBlocking {
        context.deleteDatabase(filename)
        val repository = open()
        val original = repository.create(ReforgeCharacter("fictional", "Fictional actor", currentHp = 7))
        val updated = repository.save(original.character.id, original.revision, original.character.copy(currentHp = 6), "Record damage")
        try {
            repository.save(original.character.id, original.revision, SkillRules.pin(original.character, "Arcana", true), "Stale pin")
            fail("A stale character revision must be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(updated, repository.get(original.character.id))
    }

    @Test fun connectedCampaignBackupRetainsSkillAuthorityAndStillAllowsUnrelatedHpChanges() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val initial = requireNotNull(GenesisAuthorityRepository(source).createActor("Fictional rune scholar", GenesisActorRole.PLAYER,
                playable = ReforgeCharacter("fictional-connected-skills", "Fictional rune scholar", currentHp = 7, baseMaxHp = 14,
                    baseAbilities = mapOf(Stat.INT to 18)), commandId = "fictional-skill-actor").playable)
            val repository = ReforgeRepository(source)
            val skill = CustomSkill("skill:runes", "Runes", SkillTraining(Stat.INT, true), notes = "Sourced custom study")
            val configured = repository.save(initial.character.id, initial.revision,
                SkillRules.pin(SkillRules.save(initial.character, skill), skill.id, true), "Configure custom study")
            genesisRequireProjection(source, configured.character.id, configured)
            val backup = GenesisAuthorityBackupRepository(source).exportWithProof()
            val targetBackup = GenesisAuthorityBackupRepository(target)
            val review = targetBackup.previewRestore(backup.text)
            assertTrue(review.conflicts.toString(), review.valid)
            targetBackup.restore(review, "fictional-skill-restore")
            val restoredRepo = ReforgeRepository(target)
            val restored = requireNotNull(restoredRepo.get(configured.character.id))
            assertEquals(configured, restored)
            genesisRequireProjection(target, restored.character.id, restored)
            val updated = restoredRepo.save(restored.character.id, restored.revision, restored.character.copy(currentHp = 6), "Record unrelated damage")
            assertEquals(configured.character.customSkills, updated.character.customSkills)
            assertEquals(configured.character.pinnedSkillIds, updated.character.pinnedSkillIds)
            assertEquals(6, target.recordsDao().characterProfile(updated.character.id)?.currentHp)
            genesisRequireProjection(target, updated.character.id, updated)
        } finally { source.close(); target.close() }
    }
}
