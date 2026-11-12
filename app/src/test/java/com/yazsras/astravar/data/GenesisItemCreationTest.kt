package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisItemCreationTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var playable: ReforgeRepository
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        authority = GenesisAuthorityRepository(db); playable = ReforgeRepository(db)
    }
    @After fun close() { db.close() }
    private suspend fun actor(id: String = "batch-owner") = authority.createActor("Fictional $id", GenesisActorRole.PLAYER,
        playable = ReforgeCharacter(id, "Fictional $id", level = 3, baseAbilities = Stat.entries.associateWith { 10 },
            baseMaxHp = 20, baselineConstitutionModifier = 0, currentHp = 12, temporaryHp = 0), commandId = "$id-create").playable!!
    private fun definition() = ItemDefinition("batch-cloak-definition", "Fictional vital cloak", requiresAttunement = true, equipmentSlot = "cloak",
        effects = listOf(EffectDefinition("batch-vitality", "Accepted fictional vitality",
            RuleSource("fictional-cloak-source", "Accepted fictional fixture", Acceptance.ACCEPTED_HOMEBREW),
            EffectPayload.Number(NumericTarget.MAX_HP, NumberOperation.ADD, 5), conditions = listOf(EffectCondition(ConditionKind.EQUIPPED)))))
    private fun instances() = listOf(ItemInstance("batch-equipped", definition().id, ItemState.WORN, quantity = 1, attuned = true),
        ItemInstance("batch-remainder", definition().id, ItemState.CARRIED, quantity = 3))
    private suspend fun rejectUnchanged(block: suspend () -> Unit) {
        val before = authority.backup.snapshot()
        assertNotNull(runCatching { block() }.exceptionOrNull())
        assertEquals(before, authority.backup.snapshot())
    }

    @Test fun equippedStackCreatesTwoPhysicalRecordsOneAttunementAndRecoverableCapacityChange() = runBlocking {
        val owner = actor()
        val created = authority.createItems(owner.character.id, owner.revision, definition(), instances(), "batch-create")
        assertEquals(instances(), created.character.items)
        assertEquals(4, created.character.items.sumOf { it.quantity })
        assertEquals(1, created.character.items.count { it.attuned })
        assertEquals(25, CharacterCalculator.calculate(created.character).maximumHp.value)
        assertEquals(17, created.character.currentHp)
        assertEquals(8, created.character.hpDamage)
        assertEquals(25, db.recordsDao().characterProfile(owner.character.id)?.maximumHp)
        assertEquals(17, db.recordsDao().characterProfile(owner.character.id)?.currentHp)
        assertEquals("Equipped", db.ledgerDao().getItem("batch-equipped")?.itemState)
        assertEquals("Carried", db.ledgerDao().getItem("batch-remainder")?.itemState)
        instances().forEach { item ->
            assertEquals("batch-create", readGenesisAuthorityItem(db, item.id)?.activationCommandId)
            assertEquals(owner.character.id, db.partyDao().placement(item.id)?.holderProfileId)
        }
        val document = authority.backup.exportWithProof().text
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val recovery = GenesisAuthorityBackupRepository(target)
            val preview = recovery.previewRestore(document)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "batch-restore")
            assertEquals(created, ReforgeRepository(target).get(owner.character.id))
            assertEquals(genesisAuthorityBackupRows(db), genesisAuthorityBackupRows(target))
        } finally { target.close() }
    }

    @Test fun batchRetryRetainsOriginalOutcomeWhileChangedRequestOrStaleNewCommandRejects() = runBlocking {
        val owner = actor()
        val created = authority.createItems(owner.character.id, owner.revision, definition(), instances(), "batch-retry")
        val later = playable.save(owner.character.id, created.revision, created.character.copy(notes = "Later fictional edit"), "Fictional notes", "batch-later")
        val before = authority.backup.snapshot()
        assertEquals(created, authority.createItems(owner.character.id, owner.revision, definition(), instances(), "batch-retry"))
        assertEquals(later, playable.get(owner.character.id))
        assertEquals(before, authority.backup.snapshot())
        rejectUnchanged { authority.createItems(owner.character.id, owner.revision, definition(), instances().map { if (it.id == "batch-remainder") it.copy(quantity = 2) else it }, "batch-retry") }
        rejectUnchanged { authority.createItems(owner.character.id, owner.revision, definition(), instances(), "batch-stale") }
    }

    @Test fun duplicatePhysicalIdentityOrOccupiedSlotCannotPartiallyCreateBatch() = runBlocking {
        val owner = actor()
        rejectUnchanged { authority.createItems(owner.character.id, owner.revision, definition(), listOf(instances().first(), instances().first()), "batch-duplicate") }
        rejectUnchanged { authority.createItems(owner.character.id, owner.revision, definition(), instances().map { it.copy(state = ItemState.WORN, quantity = 1) }, "batch-two-cloaks") }
        val other = actor("other-batch-owner")
        authority.createItems(other.character.id, other.revision, definition(), listOf(instances().last()), "other-batch-create")
        rejectUnchanged { authority.createItems(owner.character.id, owner.revision, definition(), instances(), "batch-conflicting-owner") }
        assertTrue(playable.get(owner.character.id)!!.character.items.isEmpty())
        assertNull(db.ledgerDao().getItem("batch-equipped"))
    }

    @Test fun failureAfterFirstPhysicalProjectionRollsBackBothItemsAndAllHistory() = runBlocking {
        val owner = actor()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fictional_reject_batch_second
            BEFORE INSERT ON genesis_item_authority WHEN NEW.itemId='batch-remainder'
            BEGIN SELECT RAISE(ABORT, 'fictional second physical item fault'); END""")
        rejectUnchanged { authority.createItems(owner.character.id, owner.revision, definition(), instances(), "batch-fault") }
        assertNull(playable.commandReceipt(genesisChild("batch-fault", "playable")))
        assertNull(readGenesisAuthorityReceipt(db, "batch-fault"))
        assertTrue(db.ledgerDao().allItems().isEmpty())
        assertTrue(db.genesisAuthorityDao().allItems().isEmpty())
    }
}
