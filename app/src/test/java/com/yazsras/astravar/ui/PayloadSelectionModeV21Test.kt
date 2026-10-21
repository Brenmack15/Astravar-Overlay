package com.yazsras.astravar.ui

import com.yazsras.astravar.rules.*
import org.junit.Assert.*
import org.junit.Test

class PayloadSelectionModeV21Test {
    @Test fun selectingStoredPayloadPreservesEachChosenSpellBearingMode() {
        for (mode in listOf(Ability.SPELL, Ability.SPELL_GAUNTLET, Ability.DISCHARGE)) {
            assertEquals(mode, payloadModeAfterSelection(mode))
        }
    }

    @Test fun selectingMagicFromAnOrdinaryAttackChoosesSpellShotWithoutRetiredMechanics() {
        for (mode in listOf(Ability.ARCANE, Ability.ELEMENTAL, Ability.GAUNTLET, Ability.GUARDIAN, Ability.SHATTER)) {
            assertEquals(Ability.SPELL, payloadModeAfterSelection(mode))
        }
    }

    @Test fun preservedPayloadModesPlanTheRequestedAttackWithTheSameStoredIdentity() {
        val template = Template("synthetic-template", "Synthetic payload", 1, 1,
            listOf(TypedDice(Dice(1, 6), DamageType.COLD)), DamageType.COLD, approved = true)
        val payload = Payload("synthetic-payload", template, SourceKind.SLOT, "synthetic-nexus", 0)
        val campaign = Campaign(onboarded = true, attuned = true, installedId = "synthetic-nexus",
            cores = listOf(Core("synthetic-nexus", "Synthetic nexus", payloads = listOf(payload))),
            profile = Profile(character = "Synthetic payload review", melee = MeleeProfile(7, Dice(2, 6, 3), 0, 2, 4, "Synthetic accepted profile")))
        for (mode in listOf(Ability.SPELL, Ability.SPELL_GAUNTLET, Ability.DISCHARGE)) {
            val selected = payloadModeAfterSelection(mode)
            val plan = Engine.plan(campaign, "synthetic-${mode.name}", selected, payloadIds = listOf(payload.id),
                distance = if (mode == Ability.SPELL_GAUNTLET) 5 else 30,
                point = if (mode == Ability.DISCHARGE) "Synthetic declared target" else "")
            assertEquals(mode, plan.ability)
            assertEquals(payload, plan.selectedPayloads.single())
            assertTrue(plan.hasSpell)
        }
    }
}
