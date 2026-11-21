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
class PlayableMechanicsSyncTest {
    @Test fun equipmentCommandsRecalculateAndSurviveRestartAndRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "convergence-disposable.db"
        fun open() = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
        var db = open()
        val target = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val authority = GenesisAuthorityRepository(db)
            val campaign = authority.createCampaign("Mechanical test", commandId = "convergence-campaign").id
            val fixture = ReforgeCharacter("tester", "Tester", baseAbilities = mapOf(Stat.INT to 21), level = 9,
                skills = mapOf("Investigation" to SkillTraining(Stat.INT, proficient = true, expertise = true)),
                customSkills = listOf(CustomSkill("skill:forensics", "Investigation", SkillTraining(Stat.INT))),
                spellSlots = mapOf(1 to 0))
            val actor = authority.createActor("Tester", GenesisActorRole.PLAYER, campaign, playable = fixture,
                commandId = "convergence-player")
            val actorId = actor.actor.actorId
            val itemId = "synthetic-stone"
            authority.createItem(actorId, requireNotNull(actor.playable).revision, ItemDefinition("test-def", "Test stone"),
                ItemInstance(itemId, "test-def"), "convergence-create")
            suspend fun read(database: CampaignLedgerDb = db) = CampaignSyncDomain(database).snapshot(campaign, mechanics = true)
            fun stat(rows: List<CampaignSyncRecord>, key: String) = rows.single { it.kind == "playable-stat" && it.fields["key"]?.jsonPrimitive?.content == key }.fields
            suspend fun operation(type: String, fields: JsonObject): CampaignSyncOperation {
                val rows = read()
                val equipment = rows.single { it.kind == "playable-equipment" }
                val revision = stat(rows, "INT").getValue("revision")
                return CampaignSyncOperation(type, itemId, equipment.kind, equipment.revision,
                    JsonObject(fields + ("actorRevision" to revision)))
            }
            suspend fun apply(command: String, op: CampaignSyncOperation) =
                CampaignSyncDomain(db).apply(campaign, command, "Resolved synthetic fixture", listOf(op))
            fun bonus(id: String, target: NumericTarget, key: String) = buildJsonObject {
                put("bonus", campaignJson.encodeToJsonElement(PlayableMechanicsSync.Bonus(id, "Test bonus", target, key, 1, true, null, null)))
            }
            val intId = "c894e825-28b1-47aa-91e9-bdd8e8646552"
            val skillId = "0ba431ab-fbba-4f39-a3d6-c3364b087b07"
            val add = operation(PlayableMechanicsSync.BONUS, bonus(intId, NumericTarget.ABILITY, "INT"))
            val receipt = apply("convergence-add", add)
            assertEquals(receipt, db.recordsDao().receipt(CampaignSyncDomain.receiptId("convergence-add")))
            assertEquals(receipt, apply("convergence-add", add))
            assertEquals(21, stat(read(), "INT").getValue("effective").jsonPrimitive.int)
            val hold = operation(PlayableMechanicsSync.STATE, buildJsonObject { put("state", "HELD") })
            apply("convergence-equip", hold)
            assertTrue(runCatching { apply("convergence-other-command", hold) }.isFailure)
            assertTrue(runCatching { apply("convergence-add", hold) }.isFailure)
            apply("convergence-skill", operation(PlayableMechanicsSync.BONUS, bonus(skillId, NumericTarget.SKILL, "Investigation")))
            val active = read()
            assertEquals(21, stat(active, "INT").getValue("base").jsonPrimitive.int)
            assertEquals(22, stat(active, "INT").getValue("effective").jsonPrimitive.int)
            assertEquals(15, stat(active, "Investigation").getValue("effective").jsonPrimitive.int)
            assertEquals(6, stat(active, "Arcana").getValue("effective").jsonPrimitive.int)
            assertEquals(6, stat(active, "skill:forensics").getValue("effective").jsonPrimitive.int)
            assertTrue(CampaignConnectionProjection.project(active, setOf("inventory")).none { it.kind.startsWith("playable-") })
            db.close()
            db = open()
            assertEquals(stat(active, "INT"), stat(read(), "INT"))
            val backup = GenesisAuthorityBackupRepository(db).exportWithProof()
            val restore = GenesisAuthorityBackupRepository(target)
            val preview = restore.previewRestore(backup.text)
            assertTrue(preview.conflicts.joinToString(), preview.valid)
            restore.restore(preview, "convergence-restore")
            assertEquals(stat(active, "INT"), stat(read(target), "INT"))
            val invalid = operation(PlayableMechanicsSync.BONUS, bonus(skillId, NumericTarget.SKILL, "absent-custom-skill"))
            assertTrue(runCatching { apply("convergence-invalid", invalid) }.isFailure)
            assertNull(db.recordsDao().receipt(CampaignSyncDomain.receiptId("convergence-invalid")))
            apply("convergence-remove", operation(PlayableMechanicsSync.BONUS, buildJsonObject { put("removeId", skillId) }))
            apply("convergence-deactivate", operation(PlayableMechanicsSync.STATE, buildJsonObject { put("operating", false) }))
            val inactive = read()
            assertEquals(21, stat(inactive, "INT").getValue("effective").jsonPrimitive.int)
            assertEquals(21, stat(inactive, "INT").getValue("base").jsonPrimitive.int)
            assertEquals(0, ReforgeRepository(db).get(actorId)!!.character.spellSlots[1])
        } finally {
            target.close(); db.close(); context.deleteDatabase(name)
        }
    }
}
