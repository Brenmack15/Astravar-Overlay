package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fictional fixtures: reservoir, spellbook and recovery operate on one persisted character. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisReservoirRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val template = Template("fixture-payload", "Fixture flame", 1, 1,
        listOf(TypedDice(Dice(2, 6), DamageType.FIRE)), DamageType.FIRE, true, "Accepted fictional source")
    private fun fixture(): ReforgeCharacter {
        val character = ReforgeCharacter("fixture-reservoir", "Fixture artificer", level = 9,
            baseAbilities = mapOf(Stat.INT to 20, Stat.STR to 10, Stat.DEX to 14),
            spellcastingClassLevel = 9, slotMaximaOverride = mapOf(1 to 4, 2 to 3), spellSlots = mapOf(1 to 3, 2 to null),
            spells = listOf(SpellDefinition("fixture-spell", "Fixture flame", 1, SpellPreparation.PREPARED)),
            gameSeconds = 100)
        val old = Campaign(onboarded = true, attuned = true, installedId = "fixture-core", gameSeconds = 600,
            cores = listOf(Core("fixture-core", "Fixture nexus")), templates = listOf(template), slots = mapOf(1 to 9, 2 to 2))
        return GenesisArmorRules.adopt(character, old, listOf(DamageType.COLD), "Reviewed fictional armor")
    }
    @Test fun reservoirLoadAndSpellbookCastShareCountAcrossRestartAndCompleteBackup() = runBlocking {
        val name = "fixture-reservoir-persistence.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ReforgeDb::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            var repository = ReforgeRepository(db)
            val created = repository.create(fixture(), "fixture-create")
            val runtime = GenesisArmorRules.runtime(created.character)
            assertEquals(3, runtime.slots[1]); assertFalse(runtime.slots.containsKey(2))
            val loaded = GenesisArmorRules.apply(created.character,
                Engine.charge(runtime, "fixture-core", template, "fixture-load", SourceKind.SLOT, true))
            val saved = repository.save(created.character.id, created.revision, loaded, "Load fixture", "fixture-load-command")
            assertEquals(2, saved.character.spellSlots[1])
            assertEquals(9, saved.character.armor!!.originalCampaign.slots[1])
            assertNull(saved.character.spellSlots[2])
            assertEquals(saved, repository.save(created.character.id, created.revision, loaded, "Load fixture", "fixture-load-command"))
            db.close(); db = open(); repository = ReforgeRepository(db)
            val reopened = requireNotNull(repository.get(created.character.id))
            assertEquals(saved, reopened)
            val preview = GameplayRules.previewCast(reopened.character, "fixture-spell", 1)
            val cast = GameplayRules.commitCast(reopened.character, preview)
            val castSaved = repository.save(reopened.character.id, reopened.revision, cast, "Cast fixture spell", "fixture-cast")
            assertEquals(1, GenesisArmorRules.runtime(castSaved.character).slots[1])
            assertEquals("fixture-load", castSaved.character.armor!!.campaign.installed!!.payloads.single().id)
            val backup = repository.export()
            val target = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
            try {
                val restored = ReforgeRepository(target)
                val review = restored.previewRestore(backup)
                assertTrue(review.conflicts.toString(), review.valid)
                restored.restore(review, "fixture-restore")
                assertEquals(castSaved, restored.get(created.character.id))
            } finally { target.close() }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun templateEditsPreserveLoadedSnapshotsAndStaleWritesCannotSpendAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        try {
            val repository = ReforgeRepository(db)
            val created = repository.create(fixture())
            val campaign = GenesisArmorRules.runtime(created.character)
            val loaded = GenesisArmorRules.apply(created.character, Engine.charge(campaign, "fixture-core", template, "snapshot-payload", SourceKind.SLOT, true))
            val saved = repository.save(created.character.id, created.revision, loaded, "Load")
            try { repository.save(created.character.id, created.revision, loaded, "Stale load"); fail("Stale action must reject") }
            catch (_: IllegalStateException) { }
            val current = GenesisArmorRules.runtime(saved.character)
            val edited = GenesisArmorRules.apply(saved.character, current.copy(templates = listOf(template.copy(name = "Future loads", damage = listOf(TypedDice(Dice(3, 8), DamageType.COLD))))))
            val next = repository.save(saved.character.id, saved.revision, edited, "Edit future loads")
            assertEquals(template, next.character.armor!!.campaign.installed!!.payloads.single().template)
            assertEquals("Future loads", next.character.armor!!.campaign.templates.single().name)
            assertEquals(2, next.character.spellSlots[1])
            assertEquals(100L, next.character.gameSeconds)
        } finally { db.close() }
    }
}
