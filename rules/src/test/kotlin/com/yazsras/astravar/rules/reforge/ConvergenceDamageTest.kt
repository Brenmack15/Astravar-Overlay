package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

/** Deterministic, externally supplied fixture totals. No player rolls are generated here. */
class ConvergenceDamageTest {
    private val source = RuleSource("fixture-defense", "Accepted test defense", Acceptance.ACCEPTED_HOMEBREW)
    private fun component(type: DamageKind) = DamageComponent(type.name, type.name, type, DiceFormula(flat = 7))
    private fun character(components: List<DamageComponent>, defenses: List<EffectPayload.Defense>): ReforgeCharacter {
        val effects = defenses.map { EffectDefinition(it.type.name, it.kind.name, source, it) }
        return ReforgeCharacter("defender", "Fictional defender",
            attacks = listOf(AttackDefinition("fixture-hit", "Fixture hit", fixedAttackBonus = 0, damage = components, addAbilityToDamage = false)),
            itemDefinitions = listOf(ItemDefinition("fixture-defense", "Fixture ward", effects = effects)),
            items = listOf(ItemInstance("fixture-ward", "fixture-defense")))
    }

    private fun resolve(c: ReforgeCharacter): DamageResolution {
        val persisted = Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(c))
        val preview = AttackRules.preview(persisted, "fixture-hit")
        return AttackRules.resolveDamage(preview, preview.components.associate { it.id to 7 },
            CharacterCalculator.calculate(persisted).defenses)
    }

    @Test fun allDamageTypesAndDefensesFollowEquipmentThroughCharacterCodec() {
        for (type in DamageKind.entries) for (defense in DefenseKind.entries) {
            val base = character(listOf(component(type)), listOf(EffectPayload.Defense(type, defense)))
            assertEquals(7, resolve(base).total)
            val held = GameplayRules.equip(base, "fixture-ward", ItemState.HELD)
            val expected = when (defense) {
                DefenseKind.RESISTANCE -> 3
                DefenseKind.IMMUNITY -> 0
                DefenseKind.VULNERABILITY -> 14
            }
            assertEquals(expected, resolve(held).total, "$type / $defense")
            assertEquals(7, resolve(GameplayRules.equip(held, "fixture-ward", ItemState.STORED)).total)
        }
    }

    @Test fun simultaneousTypedComponentsKeepIndependentDefenses() {
        val defenses = listOf(EffectPayload.Defense(DamageKind.BLUDGEONING, DefenseKind.RESISTANCE),
            EffectPayload.Defense(DamageKind.COLD, DefenseKind.IMMUNITY),
            EffectPayload.Defense(DamageKind.ACID, DefenseKind.VULNERABILITY))
        val base = character(defenses.map { component(it.type) }, defenses)
        val damage = resolve(GameplayRules.equip(base, "fixture-ward", ItemState.WORN))
        assertEquals(mapOf(DamageKind.BLUDGEONING to 3, DamageKind.COLD to 0, DamageKind.ACID to 14),
            damage.components.associate { it.type to it.applied })
        assertEquals(17, damage.total)
    }
}
