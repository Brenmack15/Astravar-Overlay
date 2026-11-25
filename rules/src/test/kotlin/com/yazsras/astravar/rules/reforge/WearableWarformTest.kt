package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

/** Fully synthetic fixture: no writes to the owner's campaign, time or dice rolls. */
class WearableWarformTest {
    private val source = RuleSource("test-warform", "Synthetic accepted form", Acceptance.ACCEPTED_HOMEBREW)
    private val form = WearableForm(
        id = "heavy-warform", name = "Heavy Warform",
        movement = mapOf("walk" to 40, "fly" to null), size = "Large",
        abilityOverrides = mapOf(Stat.STR to 20), armorClassOverride = 18,
        activationSeconds = 60, durationSeconds = 3600,
        deploymentResourceId = "deployment", integrityResourceId = "integrity",
        resetResourceId = "roar", grounded = true)
    private fun character(temporaryHp:Int? = 0) = ReforgeCharacter(
        "synthetic-eek", "Synthetic owl", level = 12, baseMaxHp = 60, currentHp = 60,
        temporaryHp = temporaryHp, baseAc = 16, speed = 5,
        movement = mapOf("fly" to 60), baseAbilities = mapOf(
            Stat.STR to 8, Stat.DEX to 18, Stat.CON to 10,
            Stat.INT to 10, Stat.WIS to 12, Stat.CHA to 12),
        itemDefinitions = listOf(ItemDefinition("warform-def", "Test Warform chassis", forms = listOf(form))),
        items = listOf(ItemInstance("warform", "warform-def", state = ItemState.WORN)),
        resources = listOf(
            ResourcePool("deployment", "Deployment", 1, 1, Recharge.LONG_REST),
            ResourcePool("integrity", "Warform Integrity", 75, 75),
            ResourcePool("roar", "Roar", 1, 1)),
        features = listOf(CharacterFeature("roar", "Roar", source,
            actionType = "Bonus Action", resourceId = "roar", requiredFormId = form.id)),
        attacks = listOf(
            AttackDefinition("owl-bite", "Owl Bite", fixedAttackBonus = 8,
                damage = listOf(DamageComponent("bite","Bite",DamageKind.PIERCING,DiceFormula(flat = 4))),
                excludedFormIds = setOf(form.id)),
            AttackDefinition("war-beak", "Warform Beak", ability = Stat.STR, fixedAttackBonus = 9,
                damage = listOf(DamageComponent("beak","Beak",DamageKind.PIERCING,
                    DiceFormula(listOf(DiceTerm(2,10)),5),magical = true)),
                requiredItemId = "warform", requiredFormId = form.id)))
    private fun deploy(c:ReforgeCharacter = character()) =
        GameplayRules.changeWearableForm(c,"warform",form.id,60)

    @Test fun longWarformNeedsOneMinuteAndCannotBeTriggeredAsSingleCombatAction() {
        val before = character()
        assertFailsWith<IllegalArgumentException> { GameplayRules.changeWearableForm(before,"warform",form.id) }
        assertFailsWith<IllegalArgumentException> { GameplayRules.changeWearableForm(before,"warform",form.id,59) }
        assertFailsWith<IllegalArgumentException> {
            GameplayRules.changeWearableForm(before.copy(turn=before.turn.copy(tracking=true)),
                "warform",form.id,60)
        }
        val next = deploy(before)
        assertEquals(60,next.gameSeconds)
        assertEquals(0,next.resources.single { it.id == "deployment" }.current)
        assertEquals(75,next.resources.single { it.id == "integrity" }.current)
        assertEquals(60,next.currentHp)
        assertEquals(60,CharacterCalculator.calculate(next).maximumHp.value)
    }

    @Test fun WarformOverridesAreReversibleAndOtherEquipmentIsUnchanged() {
        val base=character()
        val changed=deploy(base)
        val p=CharacterCalculator.calculate(changed)
        assertEquals(20,p.abilities.getValue(Stat.STR).effective.value)
        assertEquals(8,p.abilities.getValue(Stat.STR).permanent)
        assertEquals(18,p.armorClass.value)
        assertEquals(40,p.movement["walk"])
        assertNull(p.movement["fly"])
        assertEquals(60,p.currentHp)
        val clone=Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(changed))
        assertEquals(changed,clone)
        assertEquals(form.id,CharacterCalculator.activeForm(clone)?.id)
        val reverted=GameplayRules.changeWearableForm(clone,"warform",null)
        assertEquals(8,CharacterCalculator.calculate(reverted).abilities.getValue(Stat.STR).effective.value)
        assertEquals(16,CharacterCalculator.calculate(reverted).armorClass.value)
        assertEquals(60,CharacterCalculator.calculate(reverted).movement["fly"])
        assertEquals(0,reverted.resources.single { it.id == "deployment" }.current)
    }

    @Test fun onlyFormAttacksAndRoarAreAvailableWhenDeployed() {
        val before=character()
        assertFailsWith<IllegalArgumentException> { AttackRules.preview(before,"war-beak") }
        assertFailsWith<IllegalArgumentException> { GameplayRules.useFeature(before,"roar") }
        val active=deploy(before)
        assertEquals(9,AttackRules.preview(active,"war-beak").attackBonus.value)
        assertFailsWith<IllegalArgumentException> { AttackRules.preview(active,"owl-bite") }
        val used=GameplayRules.useFeature(active,"roar")
        assertEquals(0,used.resources.single { it.id=="roar" }.current)
        assertFailsWith<IllegalArgumentException> { GameplayRules.useFeature(used,"roar") }
    }

    @Test fun integrityAbsorbsDamageBeforeHpAndAutoEjectsWithRealOverflow() {
        val before=deploy(character())
        val hit=GameplayRules.damage(before,50)
        assertEquals(25,hit.resources.single { it.id=="integrity" }.current)
        assertEquals(60,hit.currentHp)
        val broken=GameplayRules.damage(hit,30)
        assertEquals(0,broken.resources.single { it.id=="integrity" }.current)
        assertEquals(55,broken.currentHp)
        assertNull(CharacterCalculator.activeForm(broken))
        assertFailsWith<IllegalArgumentException> { GameplayRules.changeWearableForm(broken,"warform",form.id,60) }
    }

    @Test fun fullIntegrityCanInterceptDamageWithoutInventingUnknownTemporaryHp() {
        val active=deploy(character(temporaryHp=null))
        val hit=GameplayRules.damage(active,10)
        assertEquals(65,hit.resources.single { it.id=="integrity" }.current)
        assertNull(hit.temporaryHp)
        assertEquals(60,hit.currentHp)
        assertFailsWith<IllegalArgumentException> { GameplayRules.damage(hit,70) }
    }

    @Test fun durationExpiresWithNoFreeIntegrityHealOrDeploymentRefill() {
        val active=GameplayRules.damage(deploy(),12)
        val expired=GameplayRules.advanceTime(active,3600,true)
        assertNull(CharacterCalculator.activeForm(expired))
        assertEquals(63,expired.resources.single { it.id=="integrity" }.current)
        assertEquals(0,expired.resources.single { it.id=="deployment" }.current)
        assertEquals(60,expired.currentHp)
    }
}
