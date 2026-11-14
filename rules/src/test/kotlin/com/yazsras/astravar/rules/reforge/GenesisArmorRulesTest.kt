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
        val owner = adopted().copy(spells = listOf(SpellDefinition("spell", "Fictional bolt", 1, preparation = SpellPreparation.PREPARED, castingTime = "Bonus Action")))
        val plan = GenesisArmorRules.plan(owner, "imbue", Ability.SPELL, immediate = spell)
        val next = GenesisArmorRules.commitRolled(owner, plan, DiceRoller.roll(plan, die = { 1 }))
        assertEquals(1, next.spellSlots[1]) // cost applies even on natural 1
        assertTrue(next.turn.spellSlotSpent)
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.plan(next, "twice", Ability.SPELL, immediate = spell) }
        val blocked = assertThrows(IllegalArgumentException::class.java) { GameplayRules.previewCast(next, "spell", 1) }
        assertTrue(blocked.message.orEmpty().contains("spell slot has already been spent"))
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

    @Test fun exhaustionChangesAccuracyOnlyAndSavedCriticalRetainsConditionSnapshot() {
        val ordinary = adopted()
        val exhausted = ordinary.copy(exhaustionLevel = 2)
        val normalPlan = GenesisArmorRules.plan(ordinary, "normal", Ability.ARCANE)
        val impaired = GenesisArmorRules.plan(exhausted, "exhausted-critical", Ability.ARCANE)
        assertEquals(normalPlan.attackBonus - 4, impaired.attackBonus)
        assertEquals(normalPlan.profile.base, impaired.profile.base)
        assertEquals(normalPlan.profile.saveDc, impaired.profile.saveDc)
        val guardian = GenesisArmorRules.plan(exhausted, "guardian", Ability.GUARDIAN, distance = 5)
        assertEquals(3, guardian.attackBonus)
        assertEquals(Dice(1, 8, 5), guardian.profile.melee!!.thunder)
        val rolled = DiceRoller.roll(impaired, die = { sides -> if (sides == 20) 20 else 1 })
        val committed = GenesisArmorRules.commitRolled(exhausted, impaired, rolled)
        assertTrue(rolled.critical)
        assertEquals(26, rolled.attackTotal)
        val rested = committed.copy(exhaustionLevel = 0)
        assertEquals(10, GenesisArmorRules.runtime(rested).profile.attack)
        assertEquals(-4, rested.armor!!.campaign.pending.single().plan.profile.conditionAttackAdjustment)
        val legacy = BackupCodec.json.encodeToString(Profile())
        assertFalse(legacy.contains("conditionAttackAdjustment"))
        assertEquals(0, BackupCodec.json.decodeFromString<Profile>(legacy).conditionAttackAdjustment)
    }

    @Test fun reservoirLoadAndPurgeRespectConditionsAndUnknownSlotAccounting() {
        val template = Template("second-level", "Fictional burst", 2, 2,
            listOf(TypedDice(Dice(2, 6), DamageType.FIRE)), DamageType.FIRE, approved = true)
        val owner = adopted(source().copy(templates = listOf(template)))
        assertThrows(IllegalArgumentException::class.java) {
            GenesisArmorRules.load(owner.copy(conditions = setOf("stunned")), template.id, "blocked", SourceKind.SLOT, manualSlot = true)
        }
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.load(owner, template.id, "unknown", SourceKind.SLOT) }
        val loaded = GenesisArmorRules.load(owner, template.id, "loaded", SourceKind.SLOT, manualSlot = true)
        assertNull(loaded.spellSlots[2])
        assertTrue(loaded.turn.spellSlotSpent)
        assertFalse(loaded.turn.bonusActionAvailable)
        assertThrows(IllegalArgumentException::class.java) {
            GenesisArmorRules.plan(loaded, "second-slot", Ability.SPELL, immediate = template, manualSlotConfirmed = true)
        }
        val next = GameplayRules.nextTurn(loaded)
        assertThrows(IllegalArgumentException::class.java) { GenesisArmorRules.purge(next.copy(conditions = setOf("paralyzed")), "loaded") }
        val purged = GenesisArmorRules.purge(next, "loaded")
        assertNull(purged.spellSlots[2])
        assertTrue(purged.armor!!.campaign.installed!!.payloads.isEmpty())
        assertFalse(purged.turn.bonusActionAvailable)
    }
}
