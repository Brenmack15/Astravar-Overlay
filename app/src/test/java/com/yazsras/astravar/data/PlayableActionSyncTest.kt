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
class PlayableActionSyncTest {
    @Test fun resolvedActionsUseTheNativeActorAndSurviveBackupRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        val db = open()
        val restored = open()
        try {
            val authority = GenesisAuthorityRepository(db)
            val campaign = authority.createCampaign("Action fixture",commandId="action-fixture-campaign").id
            val spell = SpellDefinition("glint","Glint",1,SpellPreparation.KNOWN,concentration=true,resourceId="focus")
            val c = ReforgeCharacter("test-caster","Caster",spells=listOf(spell),baseMaxHp=18,currentHp=13,hpDamage=5,
                temporaryHp=3,baseAbilities=mapOf(Stat.INT to 14),slotMaximaOverride=mapOf(1 to 2,2 to 2),
                spellSlots=mapOf(1 to 1,2 to null),resources=listOf(ResourcePool("focus","Focus",2,1,Recharge.LONG_REST)),
                turn=TurnEconomy(tracking=true))
            authority.createActor(c.name,GenesisActorRole.PLAYER,campaign,playable=c,commandId="action-fixture-actor")
            val domain=CampaignSyncDomain(db)
            val repo=ReforgeRepository(db)
            suspend fun saved() = requireNotNull(repo.get(c.id)).character
            fun fields(builder: JsonObjectBuilder.()->Unit) = buildJsonObject { put("actorRole","PLAYER"); builder() }
            suspend fun commit(type:String,f:JsonObject,revision:Long,command:String): CommandReceipt =
                domain.apply(campaign,command,"Resolved fictional action",listOf(CampaignSyncOperation(type,c.id,PlayableActionSync.KIND,revision,f)))
            val cast=fields { put("spellId",spell.id);put("slotLevel",1);put("ritual",false);put("replaceConcentration",false) }
            val definition = fields {
                put("mode", "CREATE"); put("spellId", "second-glint")
                putJsonObject("definition") { put("name", "Second glint"); put("level", 1); put("sourceVersion", "Fixture notes") }
            }
            val beforeAuthoring = saved()
            val receiptCreate = commit(PlayableActionSync.DEFINE_SPELL,definition,beforeAuthoring.revision,"create-definition")
            assertEquals(receiptCreate,commit(PlayableActionSync.DEFINE_SPELL,definition,beforeAuthoring.revision,"create-definition"))
            val afterCreate = saved()
            assertEquals(beforeAuthoring.spellSlots,afterCreate.spellSlots)
            assertTrue(runCatching { commit(PlayableActionSync.DEFINE_SPELL,definition,afterCreate.revision,"duplicate-definition") }.isFailure)
            val update = fields {
                put("mode","EDIT"); put("spellId","second-glint")
                putJsonObject("definition") { put("range","30 feet") }
            }
            assertTrue(runCatching { commit(PlayableActionSync.DEFINE_SPELL,update,beforeAuthoring.revision,"stale-definition") }.isFailure)
            assertEquals(afterCreate,saved())
            commit(PlayableActionSync.DEFINE_SPELL,update,afterCreate.revision,"edit-definition")
            assertEquals(afterCreate.spells.last().copy(range="30 feet"),saved().spells.last())
            val initial=saved().revision
            assertTrue(runCatching { commit(PlayableActionSync.CAST,cast,initial,"unprepared-fixture") }.isFailure)
            assertEquals(initial,saved().revision)
            commit(PlayableActionSync.PREPARE,fields { put("spellId",spell.id);put("prepared",true) },initial,"prepare-fixture")
            val ready=saved().revision
            assertTrue(runCatching { commit(PlayableActionSync.CAST,cast,initial,"stale-cast-fixture") }.isFailure)
            val receipt=commit(PlayableActionSync.CAST,cast,ready,"resolved-cast-fixture")
            assertEquals(receipt,commit(PlayableActionSync.CAST,cast,ready,"resolved-cast-fixture"))
            val castActor=saved()
            assertEquals(0,castActor.spellSlots[1]);assertEquals(0,castActor.resources.single().current)
            assertFalse(castActor.turn.actionAvailable);assertEquals(spell.id,castActor.concentrationSpellId)
            assertTrue(runCatching { commit(PlayableActionSync.CAST,cast,castActor.revision,"unavailable-cast-fixture") }.isFailure)
            val rest=fields { put("kind","LONG_REST");put("confirmed",true);put("elapsedSeconds",28800) }
            commit(PlayableActionSync.REST,rest,castActor.revision,"rest-fixture")
            val final=saved()
            assertEquals(18,final.currentHp);assertEquals(0,final.temporaryHp);assertEquals(28800L,final.gameSeconds)
            assertEquals(2,final.resources.single().current);assertEquals(2,final.spellSlots[1]);assertNull(final.spellSlots[2])
            assertNull(final.concentrationSpellId)
            val backup=GenesisAuthorityBackupRepository(db).exportWithProof()
            val recovery=GenesisAuthorityBackupRepository(restored);val preview=recovery.previewRestore(backup.text)
            assertTrue(preview.conflicts.joinToString(),preview.valid);recovery.restore(preview,"recover-action-fixture")
            val rows=CampaignSyncDomain(restored).snapshot(campaign,actions=true,spellbook=true)
            val header=rows.single { it.kind==PlayableActionSync.KIND }
            assertEquals(final.revision,header.revision)
            assertEquals(rest,header.fields.getValue("action").jsonObject["fields"])
            assertEquals(final,requireNotNull(ReforgeRepository(restored).get(c.id)).character)
        } finally { db.close();restored.close() }
    }
}
