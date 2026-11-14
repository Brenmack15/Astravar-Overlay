package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No network or owner data: read projections of explicit fictional campaign membership. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisArmorSyncProjectionTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var weapon: AstravarDb
    private lateinit var domain: CampaignSyncDomain
    private lateinit var original: Campaign
    @Before fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        weapon = Room.inMemoryDatabaseBuilder(context, AstravarDb::class.java).allowMainThreadQueries().build()
        domain = CampaignSyncDomain(db, weapon)
        val baseline = Campaign(revision = 200, onboarded = true, attuned = true, installedId = "fictional-nexus",
            cores = listOf(Core("fictional-nexus", "Fictional nexus")), slots = mapOf(1 to 4))
        val plan = Engine.plan(baseline, "fictional-original-shot", Ability.ARCANE)
        original = DiceRoller.fire(baseline, plan, die = { 2 })
        weapon.dao().put(StateRow(body = BackupCodec.json.encodeToString(original.copy(pending = emptyList()))))
        original.pending.forEachIndexed { index, shot -> weapon.dao().putRoll(RollRow(shot.plan.id, index.toLong(), BackupCodec.json.encodeToString(shot))) }
    }
    @After fun close() { db.close(); weapon.close() }
    private fun actor(id: String = "local-player", name: String = "Fictional connected artificer") = ReforgeCharacter(id, name,
        level = 9, baseAbilities = mapOf(Stat.INT to 20, Stat.STR to 8, Stat.DEX to 14), spellSlots = mapOf(1 to 1))
    private suspend fun adopt(campaignId: String? = null, id: String = "local-player"): ReforgeSavedCharacter {
        val authority = GenesisAuthorityRepository(db)
        val created = requireNotNull(authority.createActor(actor(id).name, GenesisActorRole.PLAYER, campaignId,
            playable = actor(id), commandId = "fictional-actor-$id").playable)
        return ReforgeRepository(db).save(id, created.revision,
            GenesisArmorRules.adopt(created.character, original, listOf(DamageType.COLD), "Explicit fictional adoption"), "Connect armor")
    }
    private fun List<CampaignSyncRecord>.configuration() = single { it.kind == "configuration" }

    @Test fun preAdoptionLegacySnapshotRemainsExactAndReadOnly() = runBlocking {
        val beforeState = weapon.dao().state()
        val beforeRolls = weapon.dao().rolls()
        val result = domain.snapshot("local-campaign")
        val configuration = result.configuration()
        assertEquals(200L, configuration.revision)
        assertEquals(Json.parseToJsonElement(beforeState!!.body), configuration.fields["campaign"])
        assertEquals(setOf("combat"), CampaignSyncDomain.requiredReadScopes(configuration))
        assertEquals(1, CampaignConnectionProjection.project(listOf(configuration), setOf("combat")).size)
        assertEquals(beforeState, weapon.dao().state()); assertEquals(beforeRolls, weapon.dao().rolls())
    }

    @Test fun canonicalStateAndRollsUseCurrentCharacterAndMonotonicPublicationRevisions() = runBlocking {
        val campaign = GenesisAuthorityRepository(db).createCampaign("Fictional campaign", commandId = "fictional-campaign")
        val originalState = weapon.dao().state(); val originalRolls = weapon.dao().rolls()
        val adopted = adopt(campaign.id)
        assertEquals(2L, adopted.revision)
        val first = domain.snapshot(campaign.id)
        val configuration = first.configuration()
        assertEquals("astravar-configuration", configuration.recordId)
        assertEquals(201L, configuration.revision)
        assertEquals("current", configuration.fields.getValue("status").jsonPrimitive.content)
        val state = BackupCodec.json.decodeFromJsonElement<Campaign>(configuration.fields.getValue("campaign"))
        assertEquals(5, state.profile.intelligence); assertEquals(4, state.profile.proficiency)
        assertEquals(mapOf(1 to 1), state.slots); assertEquals(201L, state.revision)
        assertTrue(state.pending.isEmpty())
        assertEquals(JsonArray(listOf(JsonPrimitive("COLD"))), configuration.fields.getValue("activeElements"))
        assertEquals(BackupCodec.json.encodeToJsonElement(original.pending.single()), first.single { it.kind == "roll-history" }.fields["roll"])
        val planned = GenesisArmorRules.plan(adopted.character, "fictional-new-shot", Ability.ELEMENTAL)
        val rolled = DiceRoller.roll(planned, die = { 3 }, atMillis = 123)
        val fired = GenesisArmorRules.commitRolled(adopted.character, planned, rolled)
        val saved = ReforgeRepository(db).save(adopted.character.id, adopted.revision, fired, "Record fictional armor attack")
        val second = domain.snapshot(campaign.id)
        assertEquals(202L, second.configuration().revision)
        val result = second.single { it.kind == "roll-history" && it.recordId == planned.id }
        assertEquals(202L, result.revision)
        assertEquals(rolled, BackupCodec.json.decodeFromJsonElement<PendingShot>(result.fields.getValue("roll")).rolled)
        assertEquals(originalState, weapon.dao().state()); assertEquals(originalRolls, weapon.dao().rolls())
        assertEquals(saved, ReforgeRepository(db).get(adopted.character.id))
    }

    @Test fun adoptedArmorDoesNotLeakAcrossCampaignsOrFallBackToTheOriginalLocalStore() = runBlocking {
        val authority = GenesisAuthorityRepository(db)
        val campaignA = authority.createCampaign("Fictional A", commandId = "fictional-a")
        val campaignB = authority.createCampaign("Fictional B", commandId = "fictional-b")
        val adopted = adopt(campaignB.id, "fictional-owner")
        CampaignRecordsRepository(db).saveProfile(CharacterProfile(name = "Old source identity", id = "local-player"), source = "Fictional original source")
        for (id in listOf("local-campaign", campaignA.id)) {
            assertTrue(domain.snapshot(id).none { it.kind in setOf("configuration", "roll-history") })
        }
        assertEquals("fictional-owner", domain.snapshot(campaignB.id).configuration().fields.getValue("profileId").jsonPrimitive.content)
        val association = requireNotNull(authority.actor(adopted.character.id))
        authority.updateActorAssociation(association.actorId, association.revision, association.role, null, null, "fictional-unassign")
        assertTrue(domain.snapshot(campaignB.id).none { it.kind in setOf("configuration", "roll-history") })
        assertTrue(domain.snapshot("local-campaign").none { it.kind in setOf("configuration", "roll-history") })
    }

    @Test fun incompleteRuntimePublishesExplicitUnavailableStateWithoutInventedValues() = runBlocking {
        val campaign = GenesisAuthorityRepository(db).createCampaign("Fictional campaign", commandId = "fictional-campaign")
        val adopted = adopt(campaign.id)
        val repo = ReforgeRepository(db)
        repo.save(adopted.character.id, adopted.revision, adopted.character.copy(baseAbilities = emptyMap()), "Record unknown abilities")
        val projected = domain.snapshot(campaign.id)
        val configuration = projected.configuration()
        assertEquals(202L, configuration.revision)
        assertEquals("unavailable", configuration.fields.getValue("status").jsonPrimitive.content)
        assertFalse(configuration.fields.containsKey("campaign"))
        assertTrue(configuration.fields.getValue("reason").jsonPrimitive.content.contains("No archived configuration"))
        assertEquals(BackupCodec.json.encodeToJsonElement(original.pending.single()), projected.single { it.kind == "roll-history" }.fields["roll"])
        assertTrue(domain.snapshot(campaign.id, includeReadOnly = false).none { it.kind in setOf("configuration", "roll-history") })
    }

    @Test fun canonicalConfigurationKeepsExistingCombatGrantAndExcludesUnrelatedCharacterFields() = runBlocking {
        val campaign = GenesisAuthorityRepository(db).createCampaign("Fictional campaign", commandId = "fictional-campaign")
        adopt(campaign.id)
        val configuration = domain.snapshot(campaign.id).configuration()
        assertEquals(setOf("combat"), CampaignSyncDomain.requiredReadScopes(configuration))
        assertTrue(CampaignConnectionProjection.project(listOf(configuration), setOf("character", "resources")).isEmpty())
        assertEquals(listOf(configuration), CampaignConnectionProjection.project(listOf(configuration), setOf("combat")))
        val text = configuration.fields.toString()
        for (field in listOf("currentHp", "temporaryHp", "customSkills", "pinnedSkillIds", "itemDefinitions")) {
            assertFalse("Unrelated field $field must not enter combat configuration", text.contains("\"$field\""))
        }
    }

    @Test fun unknownSlotsRemainExplicitlyUnknownInPublishedCombatState() = runBlocking {
        val campaign = GenesisAuthorityRepository(db).createCampaign("Fictional campaign", commandId = "fictional-campaign")
        val adopted = adopt(campaign.id)
        ReforgeRepository(db).save(adopted.character.id, adopted.revision,
            adopted.character.copy(spellSlots = mapOf(1 to null, 2 to 0)), "Record unknown first-level slots")
        val configuration = domain.snapshot(campaign.id).configuration()
        assertEquals("current", configuration.fields.getValue("status").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), configuration.fields.getValue("unknownSpellSlotLevels"))
        val state = BackupCodec.json.decodeFromJsonElement<Campaign>(configuration.fields.getValue("campaign"))
        assertFalse(state.slots.containsKey(1)); assertEquals(0, state.slots[2])
    }
}
