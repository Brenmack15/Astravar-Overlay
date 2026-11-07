package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fictional local-only acceptance: no campaign fixture, network, owner key or phone data. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ReforgeDb
    private lateinit var repo: ReforgeRepository
    private var now = 1000L
    private val fileName = "fictional-reforge-persistence.db"
    private fun character(id: String = "fictional-actor") = ReforgeCharacter(id, "Fictional character", level = 9,
        baseAbilities = mapOf(Stat.STR to 0, Stat.CON to null, Stat.INT to 18), currentHp = null, baseMaxHp = 60)
    @Before fun open() {
        context.deleteDatabase(fileName)
        db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        repo = ReforgeRepository(db) { now }
    }
    @After fun close() { db.close(); context.deleteDatabase(fileName) }
    private suspend fun rejects(operation: suspend () -> Unit) {
        try { operation(); fail("Expected a guarded rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }

    @Test fun creatingStoreNeverSeedsCharacterOrResources() = runBlocking {
        assertTrue(repo.observeAll().first().isEmpty())
        assertNull(repo.get("local-player"))
        assertTrue(repo.rollHistory("local-player").first().isEmpty())
    }
    @Test fun createsTypedStateAndPreservesUnknownAndZeroAcrossReopenWithoutNetwork() = runBlocking {
        db.close()
        fun disk() = Room.databaseBuilder(context, ReforgeDb::class.java, fileName).allowMainThreadQueries().build()
        db = disk(); repo = ReforgeRepository(db) { now }
        val created = repo.create(character(), "create-once")
        val changed = repo.save(created.character.id, created.revision, created.character.copy(
            conditions = setOf("Prone"), temporaryHp = 0), "Apply condition", "save-once")
        db.close(); db = disk(); repo = ReforgeRepository(db) { now }
        val saved = requireNotNull(repo.get(created.character.id))
        assertEquals(changed, saved)
        assertEquals(0, saved.character.baseAbilities[Stat.STR])
        assertNull(saved.character.baseAbilities[Stat.CON]); assertNull(saved.character.currentHp)
        assertEquals(0, saved.character.temporaryHp)
        assertEquals(REFORGE_LOCAL_ONLY, saved.saveState)
        assertEquals(2, repo.history(created.character.id).first().size)
    }
    @Test fun staleEditsCannotOverwriteAndIdenticalCommandRetriesReturnOriginalOutcome() = runBlocking {
        val created = repo.create(character(), "same-create")
        assertEquals(created, repo.create(character(), "same-create"))
        val input = created.character.copy(name = "Accepted edit")
        val changed = repo.save(input.id, created.revision, input, "Rename", "same-edit")
        now = 900
        assertEquals(changed, repo.save(input.id, created.revision, input, "Rename", "same-edit"))
        rejects { repo.save(input.id, created.revision, created.character, "Stale edit", "different-command") }
        rejects { repo.save(input.id, created.revision, input.copy(name = "Collision"), "Rename", "same-edit") }
        assertEquals(changed, repo.get(input.id))
        assertTrue(changed.updatedAt > created.updatedAt)
    }
    @Test fun rollsPersistActualFacesDiscardedD20AndSignedDiceWithoutChangingResources() = runBlocking {
        val created = repo.create(character())
        val result = SecureDice.manual(SecureDice.parse("1d20+4-1d4"), listOf(6, 17, 3), DiceMode.ADVANTAGE)
        val saved = repo.appendDiceRoll(created.character.id, created.revision, "Fictional check", result, commandId = "record-roll")
        assertEquals(result.total, saved.total)
        assertEquals(listOf(false, true, true), saved.components.single().dice.map { it.kept })
        assertEquals(listOf(1, 1, -1), saved.components.single().dice.map { it.sign })
        assertTrue(saved.manual)
        assertEquals(created, repo.get(created.character.id))
        assertEquals(saved, repo.appendRoll(saved.copy(createdAt = saved.createdAt + 100), "record-roll"))
        assertEquals(1, repo.rollHistory(created.character.id).first().size)
        val newer = repo.save(created.character.id, created.revision, created.character.copy(name = "Newer"), "Rename")
        rejects { repo.appendDiceRoll(created.character.id, created.revision, "Stale check", result) }
        assertEquals(newer, repo.get(created.character.id))
    }
    @Test fun backupRestoreIsCompleteMergeOnlyAndGuardedAgainstStalePreviews() = runBlocking {
        val original = repo.create(character(), "fixture-create")
        repo.appendDiceRoll(original.character.id, original.revision, "d100", SecureDice.manual(SecureDice.parse("1d100"), listOf(83)), commandId = "fixture-roll")
        val text = repo.export()
        val backup = decodeReforgeBackup(text)
        assertEquals(1, backup.characters.size); assertEquals(2, backup.changes.size); assertEquals(1, backup.rolls.size)
        val target = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        try {
            val other = ReforgeRepository(target) { now }
            val preview = other.previewRestore(text)
            assertTrue(preview.conflicts.toString(), preview.valid)
            other.create(character("other"), "other-create")
            rejects { other.restore(preview, "stale-restore") }
            val fresh = other.previewRestore(text)
            val receipt = other.restore(fresh, "fresh-restore")
            assertEquals(receipt, other.restore(fresh, "fresh-restore"))
            assertEquals(original, other.get(original.character.id))
            assertEquals(83, other.rollHistory(original.character.id).first().single().total)
            assertEquals(2, other.observeAll().first().size)
            val changed = other.save(original.character.id, original.revision, original.character.copy(name = "Local edit"), "Rename")
            val conflict = other.previewRestore(text)
            assertFalse(conflict.valid)
            rejects { other.restore(conflict) }
            assertEquals(changed, other.get(original.character.id))
        } finally { target.close() }
    }
    @Test fun invalidBackupsAndBrokenHistoryCannotPartiallyRestore() = runBlocking {
        repo.create(character(), "fixture")
        val backup = decodeReforgeBackup(repo.export())
        val target = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        try {
            val other = ReforgeRepository(target)
            assertFalse(other.previewRestore(reforgeJson.encodeToString(backup.copy(changes = emptyList()))).valid)
            assertFalse(other.previewRestore(reforgeJson.encodeToString(backup.copy(characters = backup.characters + backup.characters))).valid)
            val hugeRevision = backup.copy(characters = backup.characters.map { it.copy(revision = Long.MAX_VALUE) })
            assertFalse(other.previewRestore(reforgeJson.encodeToString(hugeRevision)).valid)
            assertFalse(other.previewRestore("{\"format\":\"astravar-reforge\",\"format\":\"astravar-reforge\"}").valid)
            assertTrue(other.observeAll().first().isEmpty())
        } finally { target.close() }
    }
    @Test fun invalidContainersAndMissingDefinitionsDoNotCreateAnyState() = runBlocking {
        rejects { repo.create(character().copy(items = listOf(ItemInstance("missing", "undefined")))) }
        val definition = ItemDefinition("bag", "Fictional bag")
        rejects { repo.create(character().copy(itemDefinitions = listOf(definition), items = listOf(ItemInstance("bag", "bag", containerId = "bag")))) }
        val first = ItemInstance("first", "bag", donorInstanceIds = setOf("second"))
        val second = ItemInstance("second", "bag", donorInstanceIds = setOf("first"))
        rejects { repo.create(character().copy(itemDefinitions = listOf(definition), items = listOf(first, second))) }
        assertTrue(repo.observeAll().first().isEmpty())
    }
    @Test fun offlineEquipmentCalculationsAndActualDiceSurviveDatabaseRestart() = runBlocking {
        db.close()
        fun disk() = Room.databaseBuilder(context, ReforgeDb::class.java, fileName).allowMainThreadQueries().build()
        db = disk(); repo = ReforgeRepository(db) { now }
        val source = RuleSource("fictional-armor-rule", "Fictional accepted armor", Acceptance.ACCEPTED_HOMEBREW)
        val definition = ItemDefinition("fictional-armor", "Fictional armor", effects = listOf(
            EffectDefinition("fictional-str", "Fictional Strength", source, EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 21, "STR")),
            EffectDefinition("fictional-con", "Fictional Constitution", source, EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 16, "CON"))))
        val saved = repo.create(ReforgeCharacter("fictional-offline", "Fictional offline character", level = 9,
            baseAbilities = mapOf(Stat.STR to 8, Stat.CON to 14, Stat.INT to 21), baseMaxHp = 66,
            baselineConstitutionModifier = 2, currentHp = 55, temporaryHp = 0,
            itemDefinitions = listOf(definition), items = listOf(ItemInstance("fictional-owned-armor", definition.id))))
        val equipped = GameplayRules.equip(saved.character, "fictional-owned-armor", ItemState.WORN)
        val changed = repo.save(saved.character.id, saved.revision, equipped, "Equip armor offline")
        val result = SecureDice.roll(SecureDice.parse("1d20+5"))
        val roll = repo.appendDiceRoll(changed.character.id, changed.revision, "Offline Strength check", result)
        db.close(); db = disk(); repo = ReforgeRepository(db) { now }
        val reopened = requireNotNull(repo.get(changed.character.id))
        val projection = CharacterCalculator.calculate(reopened.character)
        assertEquals(changed, reopened)
        assertEquals(21, projection.abilities.getValue(Stat.STR).effective.value)
        assertEquals(16, projection.abilities.getValue(Stat.CON).effective.value)
        assertEquals(75, projection.maximumHp.value); assertEquals(64, projection.currentHp)
        assertEquals(roll, repo.rollHistory(changed.character.id).first().single())
        assertTrue(result.faces.single().face in 1..20)
    }

}
