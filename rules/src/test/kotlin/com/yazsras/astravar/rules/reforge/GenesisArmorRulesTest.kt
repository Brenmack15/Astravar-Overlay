package com.yazsras.astravar.rules.reforge

import com.yazsras.astravar.rules.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GenesisArmorRulesTest {
    private fun source(): Campaign {
        val melee = MeleeProfile(8, Dice(2, 12, 5), 4, 2, 4, "Accepted fixture", guardianAttackBonus = 7,
            chargedDamageType = DamageType.FORCE)
        return Campaign(revision = 7, onboarded = true, attuned = true,
            profile = Profile(melee = melee), installedId = "core", gameSeconds = 900,
            cores = listOf(Core("core", "Internal nexus")), slots = mapOf(1 to 4))
    }
    private fun character() = ReforgeCharacter("local-player", "Fictional armor owner", revision = 3, level = 11,
        baseAbilities = mapOf(Stat.STR to 19, Stat.DEX to 14, Stat.INT to 20), spellSlots = mapOf(1 to 2, 2 to null),
        gameSeconds = 100, turn = TurnEconomy(tracking = true, attacksPerAction = 3))
    private fun adopted(legacy: Campaign = source()) = GenesisArmorRules.adopt(character(), legacy, listOf(DamageType.COLD), "Saved armor reviewed")

    @Test fun adoptionRetainsEvidenceAndUsesCurrentSheetWithoutHealingOrRefilling() {
        val legacy = source().copy(cores = listOf(Core("core", "Internal nexus", exhaustedUntil = 1100)))
        val before = character()
        val after = adopted(legacy)
        val runtime = GenesisArmorRules.runtime(after)
        assertEquals(legacy, after.armor!!.originalCampaign)
        assertEquals(before.spellSlots, after.spellSlots)
        assertEquals(before.gameSeconds, after.gameSeconds)
        assertEquals(mapOf(1 to 2), runtime.slots)
        assertEquals(200L, runtime.installed!!.exhaustedUntil!! - runtime.gameSeconds)
        assertEquals(5, runtime.profile.intelligence)
        assertEquals(4, runtime.profile.proficiency)
        assertEquals(10, runtime.profile.attack)
        assertEquals(8, runtime.profile.melee!!.attackBonus) // pinned accepted override
        assertEquals(Dice(1, 8, 5), runtime.profile.melee!!.thunder)
        val restored = Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(after))
        assertEquals(after, restored)
        assertEquals(200L, GenesisArmorRules.runtime(restored).installed!!.exhaustedUntil!! - runtime.gameSeconds)
    }

    @Test fun configuredLiveMeleeAccuracyUsesExplicitCapturedBaselineOnce() {
        val base = adopted()
        val linked = base.copy(armor = base.armor!!.copy(meleeUsesCharacterAccuracy = true,
            meleeAccuracyIntBaseline = 4, meleeAccuracyProficiencyBaseline = 3))
        assertEquals(10, GenesisArmorRules.runtime(linked).profile.melee!!.attackBonus)
        val currentBaseline = linked.copy(armor = linked.armor!!.copy(meleeAccuracyIntBaseline = 5, meleeAccuracyProficiencyBaseline = 4))
        assertEquals(8, GenesisArmorRules.runtime(currentBaseline).profile.melee!!.attackBonus)
    }

    @Test fun attackUsesCharacterAllowanceAndKeepsHistoricalFacesThroughRoundTrip() {
        var owner = adopted()
        repeat(3) { index ->
            val plan = GenesisArmorRules.plan(owner, "roll-$index", Ability.ELEMENTAL)
            val rolled = DiceRoller.roll(plan, RollOptions(advantage = true, disadvantage = true), die = { sides -> minOf(10, sides) })
            owner = GenesisArmorRules.commitRolled(owner, plan, rolled)
            assertEquals(index + 1, owner.turn.attacksUsed)
            assertEquals(RollMode.NORMAL, owner.armor!!.campaign.pending.last().rolled!!.mode)
        }
        assertFalse(owner.turn.actionAvailable)
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.plan(owner, "fourth", Ability.ARCANE) }
        assertEquals(source(), owner.armor!!.originalCampaign)
        val restored = Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(owner))
        assertEquals(owner.armor!!.campaign.pending, restored.armor!!.campaign.pending)
    }

    @Test fun spellImbuingAndNormalSpellcastingShareSlotAndTurnBudgets() {
        val spell = Template("template", "Fictional bolt", 1, 1, listOf(TypedDice(Dice(2, 6), DamageType.FIRE)), DamageType.FIRE, approved = true)
        val owner = adopted().copy(spells = listOf(SpellDefinition("spell", "Fictional bolt", 1, preparation = SpellPreparation.PREPARED)))
        val plan = GenesisArmorRules.plan(owner, "imbue", Ability.SPELL, immediate = spell)
        val next = GenesisArmorRules.commitRolled(owner, plan, DiceRoller.roll(plan, die = { 1 }))
        assertEquals(1, next.spellSlots[1]) // cost applies even on natural 1
        assertTrue(next.turn.spellSlotSpent)
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.plan(next, "twice", Ability.SPELL, immediate = spell) }
        assertThrows(IllegalArgumentException::class.java) { GameplayRules.previewCast(next, "spell", 1) }
        val later = GameplayRules.nextTurn(next)
        assertEquals(0, GenesisArmorRules.runtime(later).turn.spellShots)
        assertEquals(1, later.spellSlots[1])
        assertNotNull(GenesisArmorRules.plan(later, "next-turn", Ability.SPELL, immediate = spell))
    }

    @Test fun unknownChargeModifiersBlockAndChargeExpiresOnlyOnConfirmedNextTurn() {
        val owner = adopted()
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.charge(owner.copy(baseAbilities = owner.baseAbilities - Stat.STR)) }
        val charged = GenesisArmorRules.charge(owner)
        assertFalse(charged.turn.bonusActionAvailable)
        assertEquals(11, charged.armor!!.campaign.gauntletCharge!!.profile.chargedBonus)
        val plan = GenesisArmorRules.plan(charged, "charged-miss", Ability.GAUNTLET, distance = 5)
        val next = GenesisArmorRules.commitRolled(charged, plan, DiceRoller.roll(plan, die = { 1 }))
        assertNull(next.armor!!.campaign.gauntletCharge)
        assertEquals(Outcome.MISS, next.armor!!.campaign.pending.last().result!!.outcome)
        val retained = Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(charged))
        assertNotNull(GenesisArmorRules.runtime(retained).gauntletCharge)
        assertNull(GenesisArmorRules.runtime(GameplayRules.nextTurn(retained)).gauntletCharge)
    }

    @Test fun dischargeAndExplicitElapsedTimePreserveOverloadAndPayloads() {
        val owner = adopted()
        val plan = GenesisArmorRules.plan(owner, "discharge", Ability.DISCHARGE, point = "Empty clearing")
        val after = GenesisArmorRules.commitRolled(owner, plan, DiceRoller.roll(plan, die = { 3 }))
        assertEquals(600L, GenesisArmorRules.runtime(after).installed!!.exhaustedUntil!! - GenesisArmorRules.runtime(after).gameSeconds)
        assertFalse(after.turn.actionAvailable)
        assertEquals(owner.gameSeconds, after.gameSeconds)
        val elapsed = after.copy(gameSeconds = after.gameSeconds + 600)
        assertTrue(GenesisArmorRules.runtime(elapsed).installed!!.usable(GenesisArmorRules.runtime(elapsed).gameSeconds))
        assertEquals(source(), elapsed.armor!!.originalCampaign)
    }

    @Test fun incapacitatingConditionsBlockArmorAndChargedActions() {
        val owner = adopted().copy(conditions = setOf("unconscious"))
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.plan(owner, "blocked", Ability.ARCANE) }
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.charge(owner) }
    }
}
