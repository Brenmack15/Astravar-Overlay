package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeSourceSyncTest {
    @Test fun sourcedActorAndEquipmentCommitRollbackReplayRestartRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "native-source-test.db"
        fun open() = Room.databaseBuilder(context,CampaignLedgerDb::class.java,filename).allowMainThreadQueries().build()
        var db = open()
        val restored = Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val authority = GenesisAuthorityRepository(db)
            val campaign = authority.createCampaign("Fixture",commandId="fixture-campaign").id
            val owner = authority.createActor("Traveler",GenesisActorRole.PLAYER,campaign,
                playable=ReforgeCharacter("traveler","Traveler"),commandId="fixture-player")
            val origin = NativeSourceSync.Evidence("fixture","owl","https://example.invalid/owl","1")
            val profile = buildJsonObject {
                put("name","Owl"); put("level",4); put("rules",reforgeJson.encodeToJsonElement(RuleVersion(classVersion="independent creature")))
                put("baseMaxHp",18); put("baseAc",13); put("currentHp",7)
                put("baseAbilities",reforgeJson.encodeToJsonElement(mapOf(Stat.DEX to 14)))
                put("attacks",reforgeJson.encodeToJsonElement(listOf(AttackDefinition("claw","Claw"))))
                put("resources",reforgeJson.encodeToJsonElement(listOf(ResourcePool("blink","Blink",3,null))))
            }
            val create = NativeSourceSync.Request(1,"MIGRATION","CREATE",origin,owner.actor.actorId,
                requireNotNull(owner.playable).revision,"owl",GenesisActorRole.COMPANION,0,owner.actor.actorId,profile=profile)
            fun op(type:String,id:String,request:NativeSourceSync.Request) =
                CampaignSyncOperation(type,id,"reference",0,campaignJson.encodeToJsonElement(request).jsonObject)
            val actor = op(NativeSourceSync.ACTOR,"owl-reference",create)
            val source = RuleSource("fixture","Fixture",Acceptance.ACCEPTED_HOMEBREW)
            val definition = ItemDefinition("garment","Garment",requiresAttunement=true,effects=
                listOf(NumericTarget.ARMOR_CLASS,NumericTarget.SAVE).map {
                    EffectDefinition("effect-"+it.name,it.name,source,EffectPayload.Number(it,NumberOperation.ADD,1),
                        listOf(EffectCondition(ConditionKind.WORN)))
                },forms=listOf(WearableForm("hybrid","Hybrid",mapOf("walk" to 30,"fly" to 40))))
            val request = create.copy(evidence=origin.copy(externalId="garment"),actorRevision=1,profile=null,ownerId=null,
                definition=definition,instance=ItemInstance("garment-1",definition.id,ItemState.WORN,true))
            val item = op(NativeSourceSync.ITEM,"garment-reference",request)
            val domain = CampaignSyncDomain(db)
            assertTrue(runCatching { domain.apply(campaign,"stale-review","Fixture",listOf(actor,
                op(NativeSourceSync.ITEM,"garment-reference",request.copy(actorRevision=8)))) }.isFailure)
            assertNull(db.genesisAuthorityDao().actor("owl"))
            assertNull(db.partyDao().reference("owl-reference"))
            val operations = listOf(actor,item)
            val receipt=domain.apply(campaign,"good-review","Fixture",operations)
            assertEquals(receipt,domain.apply(campaign,"good-review","Fixture",operations))
            assertTrue(runCatching { domain.apply(campaign,"other-review","Fixture",operations) }.isFailure)
            val backup = authority.backup.exportWithProof()
            db.close(); db=open()
            val restore=GenesisAuthorityBackupRepository(restored)
            val preview=restore.previewRestore(backup.text)
            assertTrue(preview.conflicts.joinToString(),preview.valid)
            restore.restore(preview,"fixture-restore")
            for (database in listOf(db,restored)) {
                val sheet=requireNotNull(ReforgeRepository(database).get("owl")).character
                val stats=CharacterCalculator.calculate(sheet)
                assertEquals(14,stats.armorClass.value);assertEquals(3,stats.saves.getValue(Stat.DEX).value)
                assertEquals(7,stats.currentHp);assertNull(sheet.resources.single().current)
                assertEquals("owl",database.partyDao().placement("garment-1")?.holderProfileId)
                assertEquals(receipt,database.recordsDao().receipt(receipt.commandId))
                val hybrid=GameplayRules.changeWearableForm(sheet.copy(turn=TurnEconomy(tracking=true)),"garment-1","hybrid")
                assertEquals(40,CharacterCalculator.calculate(hybrid).movement["fly"])
                assertFalse(hybrid.turn.actionAvailable)
                assertTrue(runCatching { GameplayRules.changeWearableForm(sheet.copy(turn=TurnEconomy(tracking=true,attacksPerAction=2,attacksUsed=1)),"garment-1","hybrid") }.isFailure)
                val shared=sheet.copy(turn=TurnEconomy(tracking=true),resources=listOf(ResourcePool("breath","Breath",1,0,rechargeRoll=RechargeRoll(6,5))))
                val missed=GameplayRules.resolveRecharge(shared,"breath",4)
                assertEquals(0,missed.resources.single().current)
                assertTrue(runCatching { GameplayRules.resolveRecharge(missed,"breath",6) }.isFailure)
                assertEquals(1,GameplayRules.resolveRecharge(GameplayRules.nextTurn(missed),"breath",5).resources.single().current)
                assertNull(GameplayRules.equip(hybrid,"garment-1",ItemState.CARRIED).items.single().formId)
            }
        } finally { db.close();restored.close();context.deleteDatabase(filename) }
    }

    @Test fun itemModesConsumeOnlyTheirOwnChargesAndExpireOnTheCampaignClock() {
        val source=RuleSource("fixture","Fixture",Acceptance.ACCEPTED_HOMEBREW)
        val defense=EffectDefinition("warding","Protection",source,EffectPayload.Defense(DamageKind.COLD,DefenseKind.IMMUNITY),
            listOf(EffectCondition(ConditionKind.ITEM_MODE,"ward")))
        val ability=ItemActivation("guard","Guard","Action",mapOf("use" to 1),mapOf("ward" to 60),
            temporaryHp=DiceFormula(listOf(DiceTerm(3,6))))
        val garment=ItemDefinition("suit","Suit",listOf(defense),true,activations=listOf(ability))
        val item=ItemInstance("suit-1","suit",ItemState.WORN,true,pools=listOf(ResourcePool("use","Guard",1,1,Recharge.LONG_REST)))
        val initial=ReforgeCharacter("synthetic","Traveler",temporaryHp=9,itemDefinitions=listOf(garment),items=listOf(item),turn=TurnEconomy(tracking=true))
        assertTrue(runCatching { GameplayRules.activateItem(initial,item.id,ability.id,19) }.isFailure)
        val active=GameplayRules.activateItem(initial,item.id,ability.id,7)
        assertEquals(9,active.temporaryHp);assertEquals(0,active.items.single().pools.single().current)
        assertFalse(active.turn.actionAvailable);assertTrue(CharacterCalculator.calculate(active).defenses.isNotEmpty())
        assertTrue(CharacterCalculator.calculate(active.copy(gameSeconds=60)).defenses.isEmpty())
        assertTrue(GameplayRules.equip(active,item.id,ItemState.CARRIED).items.single().modesUntil.isEmpty())
        assertEquals(1,GameplayRules.rest(active,Recharge.LONG_REST,true,28800).items.single().pools.single().current)
    }

    @Test fun rehearsesSuppliedPrivateCommandsOnlyOnDisposableRestore() = runBlocking {
        val path=System.getenv("ASTRAVAR_NATIVE_SOURCE_REHEARSAL")
        org.junit.Assume.assumeTrue(!path.isNullOrEmpty())
        val dir=File(requireNotNull(path))
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val backup=GenesisAuthorityBackupRepository(db)
            val preview=backup.previewRestore(File(dir,"backup.json").readText())
            assertTrue(preview.conflicts.joinToString(),preview.valid)
            backup.restore(preview,"private-disposable-restore")
            val envelope=campaignJson.parseToJsonElement(File(dir,"rehearsal.json").readText()).jsonObject
            val ops=campaignJson.decodeFromJsonElement<List<CampaignSyncOperation>>(envelope.getValue("operations"))
            val domain=CampaignSyncDomain(db)
            val campaign=envelope.getValue("campaignId").jsonPrimitive.content
            val id="private-disposable-only"
            val receipt=domain.apply(campaign,id,"Private reviewed source rehearsal",ops)
            assertEquals(receipt,domain.apply(campaign,id,"Private reviewed source rehearsal",ops))
            val outcomes=domain.snapshot(campaign,mechanics=true,loadout=true)
            val after=backup.exportWithProof()
            File(dir,"result.json").writeText(buildJsonObject {
                put("fingerprint",after.currentFingerprint)
                put("rows",campaignJson.encodeToJsonElement(outcomes.filter { it.kind in setOf("character-profile","playable-stat","playable-equipment","companion-resource") }))
            }.toString())
        } finally { db.close() }
    }
}
