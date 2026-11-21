package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EquipmentDefenseMatrixTest {
    private fun actorId(kind: DefenseKind) = "defense-actor-" + kind.name
    private fun itemId(kind: DefenseKind) = "defense-item-" + kind.name

    private suspend fun createEquipment(db: CampaignLedgerDb, campaign: String, kind: DefenseKind) {
        val authority = GenesisAuthorityRepository(db)
        val id = actorId(kind)
        val actor = requireNotNull(authority.createActor(id, GenesisActorRole.PLAYER, campaign,
            playable = ReforgeCharacter(id, id), commandId = "create-" + id).playable)
        val source = RuleSource("fixture-defense-matrix", "Reviewed synthetic ward", Acceptance.ACCEPTED_HOMEBREW)
        val effects = DamageKind.entries.map { damage ->
            val payload = EffectPayload.Defense(damage, kind)
            val encoded = campaignJson.encodeToJsonElement<EffectPayload>(payload).jsonObject
            assertEquals(damage.name, encoded.getValue("damageType").jsonPrimitive.content)
            assertNotEquals(damage.name, encoded.getValue("type").jsonPrimitive.content)
            assertEquals(payload, campaignJson.decodeFromJsonElement<EffectPayload>(encoded))
            EffectDefinition("matrix-" + kind.name + "-" + damage.name, damage.name, source,
                payload, requiresAttunement = false)
        }
        val definition = ItemDefinition("definition-" + kind.name, kind.name, effects)
        authority.createItem(id, actor.revision, definition,
            ItemInstance(itemId(kind), definition.id, ItemState.WORN), "grant-" + id)
    }

    private suspend fun setOperating(db: CampaignLedgerDb, campaign: String, kind: DefenseKind, operating: Boolean) {
        val actor = requireNotNull(ReforgeRepository(db).get(actorId(kind)))
        val item = requireNotNull(readGenesisAuthorityItem(db, itemId(kind)))
        val op = CampaignSyncOperation(PlayableMechanicsSync.STATE, item.itemId, PlayableMechanicsSync.EQUIPMENT,
            item.revision, buildJsonObject { put("actorRevision", actor.revision); put("operating", operating) })
        val commandId = "matrix-toggle-" + kind.name + "-" + operating
        val domain = CampaignSyncDomain(db)
        val reason = "Synthetic equipment operating state"
        val receipt = domain.apply(campaign, commandId, reason, listOf(op))
        assertEquals(receipt, db.recordsDao().receipt(CampaignSyncDomain.receiptId(commandId)))
        assertEquals(receipt, domain.apply(campaign, commandId, reason, listOf(op)))
    }

    private suspend fun verify(db: CampaignLedgerDb, campaign: String, operating: Boolean) {
        val snapshot = CampaignSyncDomain(db).snapshot(campaign, mechanics = true)
        val visible = CampaignConnectionProjection.project(snapshot, PlayableMechanicsSync.readScopes)
        for (kind in DefenseKind.entries) {
            val id = actorId(kind)
            val c = requireNotNull(ReforgeRepository(db).get(id)).character
            assertEquals(operating, c.items.single().operating)
            assertEquals(DamageKind.entries.size, c.itemDefinitions.single().effects.size)
            val actual = visible.filter { it.kind == PlayableMechanicsSync.STATS &&
                it.fields["profileId"] == JsonPrimitive(id) && it.fields["target"] == JsonPrimitive("DAMAGE_DEFENSE")
            }.map { it.fields.getValue("key").jsonPrimitive.content }.sorted()
            val expected = if (operating) DamageKind.entries.map { it.name + ":" + kind.name + ":ALL" }.sorted() else emptyList()
            assertEquals(expected, actual)
            val expectedDamage = if (!operating) 9 else when (kind) {
                DefenseKind.RESISTANCE -> 4
                DefenseKind.IMMUNITY -> 0
                DefenseKind.VULNERABILITY -> 18
            }
            for (damage in DamageKind.entries) {
                assertEquals(kind.name + " / " + damage.name, expectedDamage, AttackRules.resolveIncoming(c, damage, 9).total)
            }
        }
    }

    @Test fun eachDefenseActivatesPersistsPublishesAndRestores() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "equipment-defense-matrix"
        fun open() = Room.databaseBuilder(context, CampaignLedgerDb::class.java, filename).allowMainThreadQueries().build()
        var db = open()
        val restored = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val campaign = GenesisAuthorityRepository(db).createCampaign("Synthetic defense matrix", commandId = "matrix-campaign").id
            DefenseKind.entries.forEach { createEquipment(db, campaign, it) }
            verify(db, campaign, true)
            DefenseKind.entries.forEach { setOperating(db, campaign, it, false) }
            verify(db, campaign, false)
            DefenseKind.entries.forEach { setOperating(db, campaign, it, true) }
            verify(db, campaign, true)
            db.close()
            db = open()
            verify(db, campaign, true)

            val backup = GenesisAuthorityBackupRepository(db).exportWithProof()
            val recovery = GenesisAuthorityBackupRepository(restored)
            val preview = recovery.previewRestore(backup.text)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            recovery.restore(preview, "restore-defense-matrix")
            verify(restored, campaign, true)
        } finally {
            db.close()
            restored.close()
            context.deleteDatabase(filename)
        }
    }
}
