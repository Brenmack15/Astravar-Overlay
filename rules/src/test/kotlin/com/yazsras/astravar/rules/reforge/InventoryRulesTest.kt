package com.yazsras.astravar.rules.reforge

import kotlin.test.*
import org.junit.Test

class InventoryRulesTest {
    private val source = RuleSource("fixture", "Fictional item rule", Acceptance.ACCEPTED_HOMEBREW)
    private val definition = ItemDefinition("fixture-definition", "Fictional ring", effects = listOf(
        EffectDefinition("hp-effect", "Fictional vitality", source,
            EffectPayload.Number(NumericTarget.MAX_HP, NumberOperation.ADD, 10), requiresAttunement = false)))
    private fun actor(id: String) = ReforgeCharacter(id, id, level = 3, baseMaxHp = 20,
        currentHp = 14, temporaryHp = 0, baseAbilities = mapOf(Stat.CON to 10))

    @Test fun splitPreservesTotalQuantityAndDefinitionIdentity() {
        val before = actor("sender").copy(itemDefinitions = listOf(definition),
            items = listOf(ItemInstance("stack", definition.id, quantity = 5)))
        val after = InventoryRules.split(before, "stack", 1, "single")
        assertEquals(5, after.items.sumOf { it.quantity })
        assertEquals(listOf(4, 1), after.items.map { it.quantity })
        assertEquals(setOf(definition.id), after.items.map { it.definitionId }.toSet())
        assertEquals(before.itemDefinitions, after.itemDefinitions)
        assertFailsWith<IllegalArgumentException> { InventoryRules.split(before, "stack", 5, "bad") }
        assertFailsWith<IllegalArgumentException> { InventoryRules.split(before, "stack", 1, "stack") }
    }

    @Test fun transferPreservesIdentityAndDamageAndDoesNotActivateReceiverEffects() {
        val before = actor("sender").copy(itemDefinitions = listOf(definition),
            items = listOf(ItemInstance("owned", definition.id, state = ItemState.WORN, attuned = true)))
        val (sender, receiver) = InventoryRules.transfer(before, actor("receiver"), "owned")
        assertTrue(sender.items.isEmpty())
        assertEquals(definition, receiver.itemDefinitions.single())
        assertEquals("owned", receiver.items.single().id)
        assertEquals(ItemState.CARRIED, receiver.items.single().state)
        assertFalse(receiver.items.single().attuned)
        assertEquals(16, sender.hpDamage)
        assertEquals(4, CharacterCalculator.calculate(sender).currentHp)
        assertEquals(14, CharacterCalculator.calculate(receiver).currentHp)
        assertEquals(20, CharacterCalculator.calculate(receiver).maximumHp.value)
    }

    @Test fun transferRejectsDefinitionCollisionsAndCompositeOrContainerDependencies() {
        val before = actor("sender").copy(itemDefinitions = listOf(definition),
            items = listOf(ItemInstance("owned", definition.id)))
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.transfer(before, actor("receiver").copy(itemDefinitions = listOf(definition.copy(name = "Conflicting"))), "owned")
        }
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.remove(before.copy(items = before.items + ItemInstance("child", definition.id, containerId = "owned")), "owned")
        }
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.remove(before.copy(items = before.items + ItemInstance("composite", definition.id, donorInstanceIds = setOf("owned"))), "owned")
        }
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.transfer(before.copy(attacks = listOf(AttackDefinition("attack", "Fictional attack", requiredItemId = "owned"))), actor("receiver"), "owned")
        }
    }
}
