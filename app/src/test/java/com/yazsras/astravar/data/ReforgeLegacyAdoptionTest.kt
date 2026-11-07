package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeLegacyAdoptionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var legacyDb: CampaignLedgerDb
    private lateinit var reforgeDb: ReforgeDb
    private lateinit var legacy: CampaignLedgerRepository
    private lateinit var reforge: ReforgeRepository
    @Before fun open() {
        legacyDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        reforgeDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).allowMainThreadQueries().build()
        legacy = CampaignLedgerRepository(legacyDb); reforge = ReforgeRepository(reforgeDb)
    }
    @After fun close() { legacyDb.close(); reforgeDb.close() }
    private suspend fun seed() {
        legacy.records.saveProfile(CharacterProfile("Fictional inventor", maximumHp = 66, currentHp = null, armorClass = 19, level = 9,
            id = "fictional-old-id"), source = "Fictional verified record")
        legacy.records.saveAbility(CharacterAbility("STR", "fictional-old-id", 0, source = "Fictional raw source"), source = "Fictional source")
        legacy.records.saveAbility(CharacterAbility("CON", "fictional-old-id", 14), source = "Fictional source")
        legacy.records.saveAbility(CharacterAbility("INT", "fictional-old-id", 18, magicModifier = 1,
            notes = "An old manual modifier"), source = "Fictional source")
        legacy.records.saveEntity(ConnectionEntity("Fictional archivist", notes = "A malformed <mention url=\"https://example.org/facts\">historical note\nExternal ID: duplicate-reference", id = "fictional-person"), source = "Fictional imported source")
        legacy.party.saveSheet("fictional-old-id", PartySheetData(conditions = "Unknown", unresolvedConflicts = listOf("Fictional unresolved claim"),
            capabilities = listOf(SheetCapability("Retired feature", "Historical only; never activate", evidenceStatus = "Confirmed"))),
            source = "Fictional source")
    }
    @Test fun adoptionIsExplicitPreservesIdentityUnknownsAndOriginalBytes() = runBlocking {
        seed()
        val before = campaignDecodeBackup(legacy.export()).copy(exportedAt = 0)
        assertTrue(reforge.observeAll().first().isEmpty())
        val preview = reforge.readLegacyPreview(legacy, "fictional-old-id")
        assertTrue(reforge.observeAll().first().isEmpty())
        assertEquals(0, preview.character.baseAbilities[Stat.STR]); assertNull(preview.character.baseAbilities[Stat.DEX])
        assertNull(preview.character.currentHp); assertTrue(preview.warnings.any { "magical modifiers" in it })
        assertTrue(preview.warnings.any { "unresolved conflicts" in it })
        assertTrue(preview.character.effects.isEmpty()); assertTrue(preview.character.spells.isEmpty())
        assertNull(preview.character.spellcastingClassLevel)
        val adopted = reforge.adopt(legacy, preview, commandId = "adopt-fictional")
        assertEquals("fictional-old-id", adopted.character.id)
        assertEquals(preview.sourceJson, adopted.origin.sourceSnapshotJson)
        assertEquals(before, campaignDecodeBackup(legacy.export()).copy(exportedAt = 0))
    }
    @Test fun staleAdoptionCannotOverwriteUpdatedOriginalOrCreatePartialState() = runBlocking {
        seed()
        val preview = reforge.readLegacyPreview(legacy, "fictional-old-id")
        val old = legacyDb.recordsDao().characterAbility("fictional-old-id", "STR")!!
        legacy.records.saveAbility(old.copy(score = 8), old.revision, "Fictional correction")
        try { reforge.adopt(legacy, preview); fail("Stale adoption must reject") } catch (_: IllegalStateException) { }
        assertTrue(reforge.observeAll().first().isEmpty())
        assertEquals(8, legacyDb.recordsDao().characterAbility("fictional-old-id", "STR")!!.score)
    }
    @Test fun atomicRollAndStateSaveRollbackTogetherOnAStaleAction() = runBlocking {
        val first = reforge.create(ReforgeCharacter("fictional-action", "Fictional action", currentHp = 10, baseMaxHp = 20))
        val roll = ReforgeRollRecord(id = "atomic-roll", profileId = first.character.id, characterRevision = first.revision,
            label = "Fictional heal", formula = "1d4", components = listOf(ReforgeRollComponent("heal", listOf(ReforgeRollDie(4, 3)))))
        val changed = reforge.saveWithRoll(first.character.id, first.revision, first.character.copy(currentHp = 13), "Apply healing", roll)
        assertEquals(13, changed.character.currentHp)
        assertEquals(1, reforge.rollHistory(first.character.id).first().size)
        assertEquals(changed, reforge.saveWithRoll(first.character.id, first.revision, first.character.copy(currentHp = 13), "Apply healing", roll))
        val staleRoll = roll.copy(id = "stale-atomic-roll")
        try { reforge.saveWithRoll(first.character.id, first.revision, first.character.copy(currentHp = 19), "Stale heal", staleRoll); fail("Stale action must reject") }
        catch (_: IllegalStateException) { }
        assertEquals(1, reforge.rollHistory(first.character.id).first().size)
        assertNull(reforge.commandReceipt("stale-atomic-roll"))
        assertEquals(changed, reforge.get(first.character.id))
    }
    @Test fun actionRollHasOneImmutableCombinedOutcomeAndExportsACompleteChain() = runBlocking {
        val first = reforge.create(ReforgeCharacter("fictional-commit", "Fictional action", resources = listOf(
            ResourcePool("charge", "Fictional charge", 2, 2))))
        val roll = ReforgeRollRecord(id = "action-die", profileId = first.character.id, characterRevision = first.revision,
            label = "Fictional hit", formula = "1d6+2", components = listOf(ReforgeRollComponent("hit", listOf(ReforgeRollDie(6, 4)), modifier = 2)))
        val next = first.character.copy(resources = first.character.resources.map { it.copy(current = 1) })
        val outcome = reforge.commitRoll(first.character.id, first.revision, next, "Confirmed hit", roll)
        assertEquals(1, outcome.character.character.resources.single().current)
        assertEquals(6, outcome.roll.total)
        assertEquals("ACTION", reforge.commandReceipt(roll.id)!!.outcomeType)
        assertEquals(outcome, reforge.commitRoll(first.character.id, first.revision, next, "Confirmed hit", roll.copy(createdAt = roll.createdAt + 50)))
        try {
            reforge.commitRoll(first.character.id, first.revision, next.copy(currentHp = 5), "Confirmed hit", roll)
            fail("Changed retry must reject")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, reforge.rollHistory(first.character.id).first().size)
        assertEquals(2, reforge.history(first.character.id).first().size)
        val exported = decodeReforgeBackup(reforge.export())
        assertEquals(2, exported.changes.size)
        assertEquals(1, exported.rolls.size)
    }

}
