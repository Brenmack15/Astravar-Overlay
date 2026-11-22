package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlayableLoadoutProjectionTest {
    @Test fun itemGateAndExplicitAttunedConditionAgreeWithPublishedCalculation() {
        val source = RuleSource("fixture-source", "Reviewed calibration", Acceptance.ACCEPTED_HOMEBREW)
        val bonus = EffectDefinition("focus-effect", "Focus", source, EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.ADD, 1, "INT"))
        for (itemGate in listOf(false, true)) for (effectGate in listOf(false, true)) {
            for (explicit in listOf(false, true)) for (attuned in listOf(false, true)) {
                val effect = bonus.copy(requiresAttunement = effectGate,
                    conditions = if (explicit) listOf(EffectCondition(ConditionKind.ATTUNED)) else emptyList())
                val def = ItemDefinition("focus", "Practice focus", listOf(effect), requiresAttunement = itemGate)
                val actor = ReforgeCharacter("student", "Student", revision = 4, baseAbilities = mapOf(Stat.INT to 14),
                    itemDefinitions = listOf(def), items = listOf(ItemInstance("focus-one", def.id, ItemState.WORN, attuned = attuned)))
                val expected = attuned || (!explicit && !(itemGate && effectGate))
                val projection = CharacterCalculator.calculate(actor)
                assertEquals(if (expected) 15 else 14, projection.abilities.getValue(Stat.INT).effective.value)
                val item = PlayableLoadoutProjection.records(actor, GenesisActorRole.PLAYER)
                    .single { it.fields["subject"] == JsonPrimitive("ITEM") }.fields.getValue("data").jsonObject
                val status = campaignJson.decodeFromJsonElement<List<EffectStatus>>(item.getValue("effectStatuses")).single()
                assertEquals(projection.effectStatuses.single(), status)
                assertEquals(expected, status.active)
            }
        }
    }

    @Test fun nativeAuthoredEffectsHaveSourceActivationAndScopeOnEachPhysicalInstance() {
        val effect = EffectDefinition("workshop-effect", "Calibration", RuleSource("workshop", "Original", Acceptance.ACCEPTED_HOMEBREW),
            EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.ADD, 1, "INT"))
        val definition = ItemDefinition("fixture-definition", "Fixture focus", listOf(effect))
        val actor = ReforgeCharacter("fixture", "Sample", revision = 4, baseAbilities = mapOf(Stat.INT to 14),
            itemDefinitions = listOf(definition), items = listOf(ItemInstance("held", definition.id, ItemState.HELD),
                ItemInstance("packed", definition.id, ItemState.STORED)), resources = listOf(ResourcePool("uses", "Uses", 3, null)),
            attacks = listOf(AttackDefinition("tap", "Tap")), notes = "local-only-note")
        val rows = PlayableLoadoutProjection.records(actor, GenesisActorRole.PLAYER)
        val scopes = PlayableLoadoutProjection.readScopes
        for (missing in scopes) assertTrue(CampaignConnectionProjection.project(rows, scopes - missing).isEmpty())
        assertEquals(rows, CampaignConnectionProjection.project(rows, scopes))
        assertTrue(rows.none { it.fields.toString().contains(actor.notes) })
        val items = rows.filter { it.fields["subject"] == JsonPrimitive("ITEM") }.map { it.fields.getValue("data").jsonObject }
        assertEquals(2, items.size)
        for (data in items) {
            assertEquals(definition, campaignJson.decodeFromJsonElement<ItemDefinition>(data.getValue("definition")))
            val item = campaignJson.decodeFromJsonElement<ItemInstance>(data.getValue("instance"))
            val status = campaignJson.decodeFromJsonElement<List<EffectStatus>>(data.getValue("effectStatuses")).single()
            assertEquals(item.id, status.itemInstanceId)
            assertEquals(item.id == "held", status.active)
        }
        val pool = rows.single { it.fields["subject"] == JsonPrimitive("RESOURCE") }.fields.getValue("data").jsonObject
        assertEquals(JsonNull, pool.getValue("pool").jsonObject["current"])
        assertEquals(3, pool.getValue("effectiveMaximum").jsonPrimitive.int)
        assertEquals(5, rows.size)
    }
}
