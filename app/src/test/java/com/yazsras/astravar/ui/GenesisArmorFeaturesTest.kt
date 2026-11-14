package com.yazsras.astravar.ui

import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
import org.junit.Assert.*
import org.junit.Test

class GenesisArmorFeaturesTest {
    private fun character(): ReforgeCharacter {
        val legacy = Campaign(onboarded = true, attuned = true, installedId = "fictional-core",
            profile = Profile(level = 9, intelligence = 4, proficiency = 4, saveDc = 15,
                confirmed = setOf(Upgrade.ADVANCED_ELEMENTS, Upgrade.FAST_SWAP),
                melee = MeleeProfile(10, Dice(3, 12, 6), 3, 2, 4, "Old accepted reference", guardianAttackBonus = 9)),
            cores = listOf(Core("fictional-core", "Existing core", exhaustedUntil = 700)), gameSeconds = 200,
            slots = mapOf(1 to 2))
        return GenesisArmorRules.adopt(ReforgeCharacter("fictional-features", "Fictional armorer", level = 11,
            baseAbilities = mapOf(Stat.STR to 8, Stat.DEX to 14, Stat.CON to 14, Stat.INT to 20, Stat.WIS to 10, Stat.CHA to 12),
            spellSlots = mapOf(1 to 1, 2 to null)), legacy, listOf(DamageType.COLD), "Existing fictional armor")
    }

    @Test fun upgradeEditKeepsUnrelatedAcceptedFormulasAndHistoricalUpgrades() {
        val original = character()
        val profile = original.armor!!.campaign.profile
        val fields = genesisArmorFeatureFields(profile, "upgrades") + mapOf("source" to "New accepted ruling", "dc" to "17",
            Upgrade.DUAL_RESONANCE.name to "true", "calibration" to "2")
        val next = genesisArmorEditedProfile(original, "upgrades", fields)
        assertEquals(profile.version + 1, next.version)
        assertEquals(17, next.saveDc)
        assertTrue(Upgrade.FAST_SWAP in next.confirmed)
        assertTrue(Upgrade.DUAL_RESONANCE in next.confirmed)
        assertEquals(profile.melee, next.melee)
        assertEquals(profile.elementDamageProfiles, next.elementDamageProfiles)
        assertEquals(profile.level, next.level)
        assertEquals(profile.intelligence, next.intelligence)
        assertEquals(profile.proficiency, next.proficiency)
        assertEquals(original.armor!!.originalCampaign, original.armor!!.campaign)
    }

    @Test fun meleeEditReadsCurrentAbilitiesAndRetainsExplicitDamageAndAccuracy() {
        val original = character()
        val fields = genesisArmorFeatureFields(original.armor!!.campaign.profile, "melee") + mapOf("attack" to "12",
            "guardian" to "10", "source" to "Confirmed current gauntlets", "chargedType" to "THUNDER")
        val next = genesisArmorEditedProfile(original, "melee", fields).melee!!
        assertEquals(12, next.attackBonus)
        assertEquals(10, next.guardianAttackBonus)
        assertEquals(Dice(3, 12, 6), next.force)
        assertEquals(-1, next.strengthModifier)
        assertEquals(2, next.dexterityModifier)
        assertEquals(5, next.intelligenceModifier)
        assertEquals(DamageType.THUNDER, next.chargedDamageType)
        assertEquals(2, next.version)
    }

    @Test fun aFormulaNeverConfirmsAnUpgradeOrChangesOtherTypes() {
        val original = character()
        val profile = original.armor!!.campaign.profile
        val fields = genesisArmorFeatureFields(profile, "damage", DamageType.PSYCHIC) + mapOf("source" to "Fictional ruling",
            "count" to "3", "sides" to "6", "flat" to "1", "approved" to "false")
        val next = genesisArmorEditedProfile(original, "damage", fields, DamageType.PSYCHIC)
        assertEquals(profile.confirmed, next.confirmed)
        assertFalse(next.elementDamageProfiles.getValue(DamageType.PSYCHIC).approved)
        assertEquals(profile.selector, next.selectorFor(DamageType.PSYCHIC))
        assertEquals(Dice(3, 6, 1), next.elementDamageProfiles.getValue(DamageType.PSYCHIC).dice)
        assertEquals(profile.base, next.base)
    }

    @Test fun unsupportedDiceMissingSourceAndUnknownAbilitiesCannotSave() {
        val original = character()
        val fields = genesisArmorFeatureFields(original.armor!!.campaign.profile, "damage", DamageType.FORCE)
        assertTrue(runCatching { genesisArmorEditedProfile(original, "damage", fields, DamageType.FORCE) }.isFailure)
        assertTrue(runCatching { genesisArmorEditedProfile(original, "damage", fields + mapOf("source" to "ruling", "sides" to "7"), DamageType.FORCE) }.isFailure)
        val withoutStrength = original.copy(baseAbilities = original.baseAbilities - Stat.STR)
        val melee = genesisArmorFeatureFields(original.armor!!.campaign.profile, "melee")
        assertTrue(runCatching { genesisArmorEditedProfile(withoutStrength, "melee", melee) }.isFailure)
    }

    @Test fun restoringInheritedFormulaDoesNotDeleteTheOriginalAdoptionEvidence() {
        val original = character()
        val profile = original.armor!!.campaign.profile.copy(elementDamageProfiles = mapOf(DamageType.FIRE to
            DamageFormulaProfile(dice = Dice(4, 6), sourceReference = "Original custom fire", approved = true)))
        val source = original.copy(armor = original.armor!!.copy(campaign = original.armor!!.campaign.copy(profile = profile)))
        val next = genesisArmorEditedProfile(source, "damage", genesisArmorFeatureFields(profile, "damage", DamageType.FIRE) +
            mapOf("inherit" to "true", "source" to "Accepted return to inherited formula"), DamageType.FIRE)
        assertTrue(next.elementDamageProfiles.isEmpty())
        assertEquals(profile.elementalSelectorDiceCount, next.elementalSelectorDiceCount)
        assertEquals(original.armor!!.originalCampaign, source.armor!!.originalCampaign)
    }
}
