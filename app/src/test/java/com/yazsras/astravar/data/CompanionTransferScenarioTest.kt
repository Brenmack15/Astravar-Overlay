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

/** Exercises the existing wire commands at the native transaction entry point. All actors are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionTransferScenarioTest {
    @Test fun transferAndReturnThroughGameplayCommands() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "typed-companion-transfer-fixture.db"
        fun open() = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
        var db = open()
        val restored = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val authority = GenesisAuthorityRepository(db)
            val campaign = authority.createCampaign("Synthetic transfer", commandId = "transfer-campaign").id
            val player = authority.createActor("Traveler", GenesisActorRole.PLAYER, campaign, playable =
                ReforgeCharacter("traveler", "Traveler", baseAbilities = mapOf(Stat.INT to 21)), commandId = "create-traveler")
            val playerId = player.actor.actorId
            val owl = authority.createActor("Owl", GenesisActorRole.COMPANION, campaign, playerId,
                ReforgeCharacter("owl", "Owl", baseAbilities = mapOf(Stat.INT to 8), baseMaxHp = 12, currentHp = 5,
                    resources = listOf(ResourcePool("repair", "Repair", 1, 0))), "create-owl")
            val owlId = owl.actor.actorId
            val item = "test-transfer-stone"
            authority.createItem(playerId, requireNotNull(player.playable).revision, ItemDefinition("stone-definition", "Stone"),
                ItemInstance(item, "stone-definition"), "create-stone")
            val fingerprints = mutableMapOf<String, String>()
            suspend fun submit(cmd: String, op: CampaignSyncOperation) {
                val source = "Explicitly synthetic companion equipment test"
                val domain = CampaignSyncDomain(db)
                val applied = domain.apply(campaign, cmd, source, listOf(op))
                if (op.type == "move_item") {
                    val changed = campaignJson.decodeFromString<List<CampaignSyncRecord>>(applied.outcomeJson)
                    assertEquals(setOf("item", "item-placement"), changed.map { it.kind }.toSet())
                    assertTrue(changed.all { it.recordId == item })
                }
                assertEquals(applied, db.recordsDao().receipt(CampaignSyncDomain.receiptId(cmd)))
                assertEquals(applied, domain.apply(campaign, cmd, source, listOf(op)))
                fingerprints[cmd] = applied.fingerprint
            }
            suspend fun move(cmd: String, holderId: String) {
                val rows = CampaignSyncDomain(db).snapshot(campaign)
                fun row(kind: String) = rows.single { it.recordId == item && it.kind == kind }
                val sender = row("item-placement").fields.getValue("holderProfileId").jsonPrimitive.content
                val fromRevision = requireNotNull(ReforgeRepository(db).get(sender)).revision
                val toRevision = requireNotNull(ReforgeRepository(db).get(holderId)).revision
                val operation = CampaignSyncOperation("move_item", item, "item", row("item").revision, buildJsonObject {
                    put("expectedPlacementRevision", row("item-placement").revision)
                    put("holderProfileId", holderId)
                    put("state", "Carried")
                    putJsonObject("playableTransfer") {
                        put("version", 1); put("fromId", sender); put("fromRevision", fromRevision); put("toRevision", toRevision)
                    }
                })
                val proof = operation.fields.getValue("playableTransfer").jsonObject
                val stale = operation.copy(fields = JsonObject(operation.fields + ("playableTransfer" to JsonObject(proof + ("toRevision" to JsonPrimitive(toRevision + 1))))))
                assertTrue(runCatching { submit("$cmd-stale", stale) }.isFailure)
                assertNull(db.recordsDao().receipt(CampaignSyncDomain.receiptId("$cmd-stale")))
                if (sender == owlId) {
                    val legacy = operation.copy(fields = JsonObject(operation.fields - "playableTransfer"))
                    assertTrue(runCatching { submit("$cmd-unscoped", legacy) }.isFailure)
                    assertNull(db.recordsDao().receipt(CampaignSyncDomain.receiptId("$cmd-unscoped")))
                    assertEquals(sender, db.partyDao().placement(item)?.holderProfileId)
                }
                submit(cmd, operation)
                assertEquals(holderId, readGenesisAuthorityItem(db, item)?.ownerActorId)
                assertEquals(holderId, db.partyDao().placement(item)?.holderProfileId)
            }
            suspend fun edit(cmd: String, type: String, actor: String, role: String, fields: JsonObject) {
                val row = CampaignSyncDomain(db).snapshot(campaign, mechanics = true).single { it.recordId == item && it.kind == "playable-equipment" }
                val rev = requireNotNull(ReforgeRepository(db).get(actor)).revision
                submit(cmd, CampaignSyncOperation(type, item, row.kind, row.revision,
                    JsonObject(fields + mapOf("actorRole" to JsonPrimitive(role), "actorRevision" to JsonPrimitive(rev)))))
            }
            suspend fun assertState(database: CampaignLedgerDb, holder: String, owlInt: Int) {
                val characters = listOf(playerId, owlId).associateWith { requireNotNull(ReforgeRepository(database).get(it)).character }
                val other = if (holder == playerId) owlId else playerId
                assertEquals(item, characters.getValue(holder).items.single().id)
                assertTrue(characters.getValue(other).items.isEmpty())
                val companion = characters.getValue(owlId)
                assertEquals(5, companion.currentHp)
                assertEquals(0, companion.resources.single().current)
                val rows = CampaignSyncDomain(database).snapshot(campaign, mechanics = true)
                val stats = rows.single { it.kind == "playable-stat" && it.fields["profileId"] == JsonPrimitive(owlId) &&
                    it.fields["target"] == JsonPrimitive("ABILITY") && it.fields["key"] == JsonPrimitive("INT") }.fields
                assertEquals(8, stats.getValue("base").jsonPrimitive.int)
                assertEquals(owlInt, stats.getValue("effective").jsonPrimitive.int)
                assertEquals(1, database.ledgerDao().allItems().count { it.id == item })
            }
            val bonus = PlayableMechanicsSync.Bonus("5aacf7f8-2b1e-47d7-9620-c61b81f96af8", "Mind", NumericTarget.ABILITY, "INT", 1, true, null, null)
            edit("transfer-define", PlayableMechanicsSync.BONUS, playerId, "PLAYER",
                buildJsonObject { put("bonus", campaignJson.encodeToJsonElement(bonus)) })
            move("transfer-to-owl", owlId)
            assertState(db, owlId, 8)
            edit("transfer-owl-equip", PlayableMechanicsSync.STATE, owlId, "COMPANION", buildJsonObject { put("state", "HELD") })
            assertState(db, owlId, 9)
            db.close()
            db = open()
            assertState(db, owlId, 9)
            move("transfer-back", playerId)
            assertState(db, playerId, 8)
            val proof = GenesisAuthorityBackupRepository(db).exportWithProof()
            val restore = GenesisAuthorityBackupRepository(restored)
            val preview = restore.previewRestore(proof.text)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            restore.restore(preview, "restore-transfer-receipts")
            assertState(restored, playerId, 8)
            for ((cmd, fingerprint) in fingerprints) {
                assertEquals(fingerprint, restored.recordsDao().receipt(CampaignSyncDomain.receiptId(cmd))?.fingerprint)
            }
        } finally {
            db.close()
            restored.close()
            context.deleteDatabase(name)
        }
    }
}
