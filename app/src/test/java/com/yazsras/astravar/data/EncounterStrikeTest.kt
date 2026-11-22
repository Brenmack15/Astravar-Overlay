package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EncounterStrikeTest {
    @Test fun hitPersistsBothActorsAndTurnAndCanBeReplayedAfterRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        val db=open()
        val twin=open()
        try {
            val authority=GenesisAuthorityRepository(db)
            val campaign=authority.createCampaign("Fictional strike",commandId="strike-fixture-world").id
            val attack=AttackDefinition("tap","Tap",fixedAttackBonus=4,addAbilityToDamage=false,
                damage=listOf(DamageComponent("cold","Cold",DamageKind.COLD,DiceFormula(listOf(DiceTerm(1,6)))),
                    DamageComponent("fire","Fire",DamageKind.FIRE,DiceFormula(flat=4))))
            val source=ReforgeCharacter("strike-actor","A",baseAc=12,baseMaxHp=17,currentHp=17,temporaryHp=0,
                baseAbilities=mapOf(Stat.STR to 12),attacks=listOf(attack),turn=TurnEconomy(tracking=true))
            val target=ReforgeCharacter("strike-target","B",baseAc=12,baseMaxHp=20,currentHp=20,temporaryHp=2,
                effects=listOf(EffectDefinition("ward","Cold ward",RuleSource("fixture","Fictional",Acceptance.ACCEPTED_HOMEBREW),
                    EffectPayload.Defense(DamageKind.COLD,DefenseKind.RESISTANCE))))
            val a=authority.createActor(source.name,GenesisActorRole.PLAYER,campaign,playable=source,commandId="strike-fixture-a").actor
            val b=authority.createActor(target.name,GenesisActorRole.COMPANION,campaign,a.actorId,playable=target,commandId="strike-fixture-b").actor
            val repo=authority.encounters
            val draft=repo.saveDraft("strike-enc",null,campaign,"Fictional duel","",
                listOf(GenesisEncounterParticipantInput(a.actorId,a.revision,15),GenesisEncounterParticipantInput(b.actorId,b.revision,5)),"strike-fixture-draft")
            val enc=repo.start(repo.reviewStart(draft.record.id,draft.record.revision),"strike-fixture-start")
            val request=EncounterStrikeRequest(enc.record.id,enc.record.revision,a.actorId,1,b.actorId,1,attack.id,15,mapOf("cold" to 5,"fire" to 4),true)
            val strikes=authority.encounterStrikes
            val raw=ReforgeRepository(db)
            val revBefore=requireNotNull(raw.get(b.actorId))
            val shown=strikes.preview(request)
            assertEquals(6,shown.damage.total);assertEquals(16,shown.targetHp);assertEquals(0,shown.targetTempHp)
            assertEquals(revBefore,raw.get(b.actorId))
            assertTrue(runCatching { strikes.commit(request.copy(rolledDamage=mapOf("fire" to 4000)),"fixture-bad") }.isFailure)
            assertTrue(runCatching { strikes.commit(request.copy(targetRevision=77),"fixture-stale") }.isFailure)
            assertEquals(revBefore,raw.get(b.actorId));assertEquals(enc,repo.get(enc.record.id))
            val miss=strikes.preview(request.copy(naturalD20=1,rolledDamage=emptyMap()))
            assertFalse(miss.hit);assertEquals(20,miss.targetHp)
            val crit=strikes.preview(request.copy(naturalD20=20,rolledDamage=mapOf("cold" to 12,"fire" to 4)))
            assertTrue(crit.critical);assertEquals(10,crit.damage.total)
            val domain=CampaignSyncDomain(db)
            val rows=domain.snapshot(campaign,loadout=true,actions=true,strikes=true)
            val roster=rows.single { it.fields["profileId"]?.jsonPrimitive?.content==a.actorId &&
                it.fields["subject"]?.jsonPrimitive?.content=="ENCOUNTER" }
            assertEquals(enc.record.revision,roster.revision)
            val fields=buildJsonObject {
                put("actorRole","PLAYER");put("targetRole","COMPANION");put("targetId",b.actorId);put("targetRevision",1)
                put("encounterId",enc.record.id);put("encounterRevision",enc.record.revision);put("attackId",attack.id)
                put("naturalD20",15);put("rolledDamage",campaignJson.encodeToJsonElement(request.rolledDamage));put("finishTurn",true)
            }
            val op=CampaignSyncOperation(PlayableActionSync.STRIKE,a.actorId,PlayableActionSync.KIND,1,fields)
            assertEquals(setOf("character","resources","combat","hp","companions"),PlayableActionSync.writeScopes(op))
            val wrongRole=op.copy(fields=JsonObject(fields+("targetRole" to JsonPrimitive("PLAYER"))))
            assertTrue(runCatching { domain.apply(campaign,"bad-role","Fictional encounter",listOf(wrongRole)) }.isFailure)
            val wire=domain.apply(campaign,"fixture-remote-strike","Fictional encounter",listOf(op))
            assertEquals(wire,domain.apply(campaign,"fixture-remote-strike","Fictional encounter",listOf(op)))
            val strikeId=db.genesisAuthorityDao().allReceipts().single { it.kind==ENCOUNTER_STRIKE }.commandId
            val accepted=strikes.commit(request,strikeId)
            assertEquals(shown,accepted)
            assertFalse(requireNotNull(raw.get(a.actorId)).character.turn.actionAvailable)
            assertEquals(16,requireNotNull(raw.get(b.actorId)).character.currentHp)
            assertEquals(b.actorId,requireNotNull(repo.get(enc.record.id)).record.currentActorId)
            assertTrue(runCatching { strikes.commit(request,"different-stale-command") }.isFailure)
            val exported=authority.backup.exportWithProof()
            val restored=GenesisAuthorityRepository(twin)
            val plan=restored.backup.previewRestore(exported.text)
            assertTrue(plan.conflicts.joinToString(),plan.valid);restored.backup.restore(plan,"recover-strike")
            assertEquals(accepted,restored.encounterStrikes.commit(request,strikeId))
        } finally { twin.close();db.close() }
    }
}
