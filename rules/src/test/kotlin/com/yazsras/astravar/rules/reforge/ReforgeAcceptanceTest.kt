package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

/** Isolated example mechanics; this fixture never seeds the owner's campaign. */
class ReforgeAcceptanceTest {
    private val source=RuleSource("fixture-ruling","Accepted fixture mechanics",Acceptance.ACCEPTED_HOMEBREW)
    private fun effect(id:String,payload:EffectPayload)=EffectDefinition(id,id,source,payload)
    private fun fixture()=ReforgeCharacter("fixture-player","Fixture Artificer",level=9,
        rules=RuleVersion(classVersion="legacy-artificer-infusions-2020"),spellcastingClassLevel=9,
        baseAbilities=mapOf(Stat.STR to 8,Stat.DEX to 15,Stat.CON to 14,Stat.INT to 21,Stat.WIS to 11,Stat.CHA to 12),
        baseMaxHp=66,baselineConstitutionModifier=2,currentHp=42,temporaryHp=0,baseAc=19,speed=25,
        itemDefinitions=listOf(ItemDefinition("armor-def","Fixture integrated armor",effects=listOf(
            effect("strength",EffectPayload.Number(NumericTarget.ABILITY,NumberOperation.SET,21,Stat.STR.name)),
            effect("constitution",EffectPayload.Number(NumericTarget.ABILITY,NumberOperation.SET,16,Stat.CON.name)),
            effect("armor-class",EffectPayload.Number(NumericTarget.ARMOR_CLASS,NumberOperation.SET,20))
        ))),items=listOf(ItemInstance("armor","armor-def")),spellSlots=mapOf(1 to 2,2 to 1,3 to 0))

    @Test fun automaticAbilityScoresAreReversibleAndPermanentValuesAreUnchanged() {
        val before=fixture();val equipped=GameplayRules.equip(before,"armor",ItemState.WORN)
        val p=CharacterCalculator.calculate(equipped)
        assertEquals(21,p.abilities.getValue(Stat.STR).effective.value)
        assertEquals(16,p.abilities.getValue(Stat.CON).effective.value)
        assertEquals(21,p.abilities.getValue(Stat.INT).effective.value)
        assertEquals(20,p.armorClass.value)
        val removed=GameplayRules.equip(equipped,"armor",ItemState.CARRIED)
        assertEquals(8,CharacterCalculator.calculate(removed).abilities.getValue(Stat.STR).effective.value)
        assertEquals(before.baseAbilities,removed.baseAbilities)
        assertEquals(before.baseAbilities,GameplayRules.equip(equipped,"armor",ItemState.WORN).baseAbilities)
    }
    @Test fun hpCapacityChangesRetainOutstandingDamageWithoutRestOrRefill() {
        val before=fixture();val equipped=GameplayRules.equip(before,"armor",ItemState.WORN)
        assertEquals(75,CharacterCalculator.calculate(equipped).maximumHp.value)
        assertEquals(51,equipped.currentHp);assertEquals(24,equipped.hpDamage)
        val removed=GameplayRules.equip(equipped,"armor",ItemState.STORED)
        assertEquals(66,CharacterCalculator.calculate(removed).maximumHp.value)
        assertEquals(42,removed.currentHp)
        assertEquals(before.spellSlots,removed.spellSlots)
    }
    @Test fun hpClampingPreservesDamageThatReturnsAfterReequip() {
        val worn=GameplayRules.equip(fixture(),"armor",ItemState.WORN).copy(currentHp=4,hpDamage=71)
        val removed=GameplayRules.equip(worn,"armor",ItemState.CARRIED)
        assertEquals(0,removed.currentHp);assertEquals(71,removed.hpDamage)
        assertEquals(4,GameplayRules.equip(removed,"armor",ItemState.WORN).currentHp)
    }
    @Test fun unknownHpNeverBecomesKnownFromCapacityChangeEvenWithRetainedDamage() {
        val unknown=fixture().copy(currentHp=null,hpDamage=24)
        val equipped=GameplayRules.equip(unknown,"armor",ItemState.WORN)
        assertNull(equipped.currentHp);assertNull(CharacterCalculator.calculate(equipped).currentHp)
    }
    @Test fun zeroScoreIsDistinctFromUnknownAndUsesFloorDivision() {
        val c=fixture().copy(baseAbilities=mapOf(Stat.STR to 0,Stat.DEX to 9))
        val p=CharacterCalculator.calculate(c)
        assertEquals(-5,p.abilities.getValue(Stat.STR).modifier)
        assertEquals(-1,p.abilities.getValue(Stat.DEX).modifier)
        assertNull(p.abilities.getValue(Stat.INT).modifier)
    }
    @Test fun explanationComesFromActiveEffectAndDisappearsWhenRemoved() {
        val p=CharacterCalculator.calculate(GameplayRules.equip(fixture(),"armor",ItemState.WORN))
        assertTrue(p.abilities.getValue(Stat.STR).effective.explanation.any { it.sourceId==source.id && it.label=="strength" && it.value==21 })
        assertTrue(CharacterCalculator.calculate(fixture()).effectStatuses.any { it.effectId=="strength" && !it.active })
    }
    @Test fun unacceptedNarrativeAndProposedEffectsNeverActivate() {
        val c=fixture().copy(effects=listOf(effect("proposal",EffectPayload.Number(NumericTarget.ABILITY,NumberOperation.ADD,100,"STR")).copy(source=source.copy(acceptance=Acceptance.PROPOSED))))
        assertEquals(8,CharacterCalculator.calculate(c).abilities.getValue(Stat.STR).effective.value)
    }
    @Test fun duplicateMagicBenefitDoesNotStackAcrossInstances() {
        val c=fixture().copy(items=listOf(ItemInstance("armor","armor-def",ItemState.WORN),ItemInstance("second","armor-def",ItemState.WORN)))
        val p=CharacterCalculator.calculate(c)
        assertEquals(21,p.abilities.getValue(Stat.STR).effective.value)
        assertEquals(3,p.effectStatuses.count { !it.active && "stacking" in it.reason })
    }
    @Test fun attunementAndCompositeDonorRulesGateEffects() {
        val f=fixture();val c=f.copy(itemDefinitions=f.itemDefinitions.map { it.copy(requiresAttunement=true) })
        val worn=GameplayRules.equip(c,"armor",ItemState.WORN)
        assertEquals(8,CharacterCalculator.calculate(worn).abilities.getValue(Stat.STR).effective.value)
        val attuned=GameplayRules.attune(worn,"armor",true)
        assertEquals(21,CharacterCalculator.calculate(attuned).abilities.getValue(Stat.STR).effective.value)
        val composite=attuned.copy(itemDefinitions=attuned.itemDefinitions+ItemDefinition("composite","Composite",attunementCost=0),items=attuned.items+ItemInstance("new","composite",donorInstanceIds=setOf("armor")))
        assertEquals(8,CharacterCalculator.calculate(composite).abilities.getValue(Stat.STR).effective.value)
        assertFailsWith<IllegalArgumentException> { GameplayRules.equip(composite,"armor",ItemState.WORN) }
    }
    @Test fun attunementLimitsIncludeExplicitCompositeExceptions() {
        val c=fixture().copy(attunementLimit=1,itemDefinitions=listOf(ItemDefinition("one","One",requiresAttunement=true),ItemDefinition("two","Two",requiresAttunement=true),ItemDefinition("free","Integrated exception",requiresAttunement=true,attunementCost=0)),items=listOf(ItemInstance("1","one",attuned=true),ItemInstance("2","two"),ItemInstance("3","free")))
        assertFailsWith<IllegalArgumentException> { GameplayRules.attune(c,"2",true) }
        assertTrue(GameplayRules.attune(c,"3",true).items.single { it.id=="3" }.attuned)
    }
    private fun attackFixture():ReforgeCharacter {
        val extra=effect("cold-rider",EffectPayload.DamageBonus(DamageComponent("cold","Cold rider",DamageKind.COLD,SecureDice.parse("1d6")),AttackQualifier(requiredTags=setOf("melee")))).copy(
            conditions=listOf(EffectCondition(ConditionKind.SUCCESSFUL_HIT),EffectCondition(ConditionKind.ONCE_PER_TURN)),chargeResourceId="charges",chargeCost=1)
        val f=fixture()
        return f.copy(itemDefinitions=f.itemDefinitions+ItemDefinition("blade-def","Frost fixture",effects=listOf(extra)),items=f.items+ItemInstance("blade","blade-def",ItemState.HELD),
            resources=listOf(ResourcePool("charges","Charges",3,2)),attacks=listOf(
                AttackDefinition("sword","Sword",tags=setOf("melee"),damage=listOf(DamageComponent("physical","Blade",DamageKind.SLASHING,SecureDice.parse("1d8")))),
                AttackDefinition("bow","Bow",ability=Stat.DEX,tags=setOf("ranged"),damage=listOf(DamageComponent("arrow","Arrow",DamageKind.PIERCING,SecureDice.parse("1d6"))))))
    }
    @Test fun qualifyingDamageDiceCriticalDefenseAndChargeRules() {
        val c=attackFixture()
        val p=AttackRules.preview(c,"sword",critical=true)
        assertEquals(2,p.components.single { it.type==DamageKind.COLD }.formula.dice.single().count)
        assertEquals(-1,p.components.single { it.type==DamageKind.SLASHING }.formula.flat)
        assertFalse(AttackRules.preview(c,"bow").components.any { it.type==DamageKind.COLD })
        val totals=p.components.associate { it.id to if(it.type==DamageKind.COLD) 7 else 9 }
        val resolved=AttackRules.resolveDamage(p,totals,listOf(EffectPayload.Defense(DamageKind.COLD,DefenseKind.RESISTANCE),EffectPayload.Defense(DamageKind.SLASHING,DefenseKind.IMMUNITY)))
        assertEquals(3,resolved.total)
        assertEquals(2,AttackRules.commitAttack(c,p,false).resources.single().current)
        val hit=AttackRules.commitHit(c,p)
        assertEquals(1,hit.resources.single().current)
        assertFalse(AttackRules.preview(hit,"sword").components.any { it.type==DamageKind.COLD })
        val next=GameplayRules.nextTurn(hit)
        assertTrue(AttackRules.preview(next,"sword").components.any { it.type==DamageKind.COLD })
        val unequipped=GameplayRules.equip(next,"blade",ItemState.CARRIED)
        assertFalse(AttackRules.preview(unequipped,"sword").components.any { it.type==DamageKind.COLD })
    }
    @Test fun mixedMagicDamageUsesOneRoundingWhenDefenseIsSame() {
        val c=fixture().copy(attacks=listOf(AttackDefinition("mixed","Mixed",addAbilityToDamage=false,damage=listOf(
            DamageComponent("magic","Magic",DamageKind.FIRE,DiceFormula(flat=1),magical=true),
            DamageComponent("mundane","Mundane",DamageKind.FIRE,DiceFormula(flat=1),magical=false)))))
        val p=AttackRules.preview(c,"mixed")
        assertEquals(1,AttackRules.resolveDamage(p,mapOf("magic" to 1,"mundane" to 1),listOf(EffectPayload.Defense(DamageKind.FIRE,DefenseKind.RESISTANCE))).total)
        assertEquals(1,AttackRules.resolveDamage(p,mapOf("magic" to 1,"mundane" to 1),listOf(EffectPayload.Defense(DamageKind.FIRE,DefenseKind.RESISTANCE,true))).total)
    }
    @Test fun staleOrChangedAttackPreviewCannotConsumeResources() {
        val c=attackFixture();val p=AttackRules.preview(c,"sword")
        assertFailsWith<IllegalArgumentException> { AttackRules.commitHit(c.copy(revision=1),p) }
        assertFailsWith<IllegalArgumentException> { AttackRules.commitHit(c.copy(items=c.items.map { it.copy(state=ItemState.CARRIED) }),p) }
    }
    @Test fun chargedConversionAndAttackBonusHaveExplicitConsumptionTiming() {
        val c=fixture().copy(resources=listOf(ResourcePool("pool","Pool",4,4)),effects=listOf(
            effect("accuracy",EffectPayload.Number(NumericTarget.ATTACK,NumberOperation.ADD,1)).copy(chargeResourceId="pool",chargeCost=1),
            effect("conversion",EffectPayload.DamageConversion(DamageKind.FIRE,DamageKind.COLD)).copy(chargeResourceId="pool",chargeCost=1)),
            attacks=listOf(AttackDefinition("test","Test",damage=listOf(DamageComponent("fire","Fire",DamageKind.FIRE,SecureDice.parse("1d6"))))))
        val p=AttackRules.preview(c,"test")
        assertEquals(DamageKind.COLD,p.components.single().type)
        assertEquals(3,AttackRules.commitAttack(c,p,false).resources.single().current)
        assertEquals(2,AttackRules.commitAttack(c,p,true).resources.single().current)
    }
    @Test fun spellSlotsStatsPreparationCastingConcentrationAndRecovery() {
        val c=fixture().copy(spells=listOf(SpellDefinition("spell","Concentration fixture",1,SpellPreparation.PREPARED,concentration=true)),effects=listOf(
            effect("focus",EffectPayload.Number(NumericTarget.SPELL_ATTACK,NumberOperation.ADD,1))))
        val p=CharacterCalculator.calculate(c)
        assertEquals(mapOf(1 to 4,2 to 3,3 to 2),p.slotMaxima)
        assertEquals(10,p.spellAttack.value);assertEquals(17,p.spellSaveDc.value);assertEquals(9,p.preparationLimit)
        val preview=GameplayRules.previewCast(c,"spell",2)
        assertEquals(1,c.spellSlots[2])
        val cast=GameplayRules.commitCast(c,preview)
        assertEquals(0,cast.spellSlots[2]);assertEquals("spell",cast.concentrationSpellId)
        assertFailsWith<IllegalArgumentException> { GameplayRules.previewCast(cast,"spell",1) }
        assertEquals("spell",GameplayRules.commitCast(cast,GameplayRules.previewCast(cast,"spell",1,true)).concentrationSpellId)
        val rested=GameplayRules.rest(cast,Recharge.LONG_REST,true,28800)
        assertEquals(mapOf(1 to 4,2 to 3,3 to 2),rested.spellSlots)
        assertNull(rested.concentrationSpellId);assertEquals(66,rested.currentHp)
    }
    @Test fun unknownSlotsHpAndPoolsDoNotBecomeKnownAfterRest() {
        val c=fixture().copy(currentHp=null,spellSlots=mapOf(1 to null),resources=listOf(ResourcePool("unknown","Unknown",4,null,Recharge.LONG_REST)))
        val rested=GameplayRules.rest(c,Recharge.LONG_REST,true,28800)
        assertNull(rested.currentHp);assertNull(rested.spellSlots[1]);assertNull(rested.resources.single().current)
    }
    @Test fun genericCharacterDoesNotSilentlyBecomeLegacyArtificer() {
        val c=ReforgeCharacter("generic","Generic",level=9)
        assertTrue(CharacterCalculator.calculate(c).slotMaxima.isEmpty())
        assertNull(CharacterCalculator.calculate(c).preparationLimit)
    }
    @Test fun concentrationAndConditionsAreReversibleAndPoisonDoesNotAffectSaves() {
        val c=GameplayRules.setCondition(fixture(),"Poisoned",true)
        assertEquals(DiceMode.DISADVANTAGE,CharacterCalculator.rollMode(c,RollKind.ATTACK))
        assertEquals(DiceMode.DISADVANTAGE,CharacterCalculator.rollMode(c,RollKind.ABILITY_CHECK,"INT"))
        assertEquals(DiceMode.NORMAL,CharacterCalculator.rollMode(c,RollKind.SAVE,"INT"))
        assertEquals(DiceMode.NORMAL,CharacterCalculator.rollMode(GameplayRules.setCondition(c,"Poisoned",false),RollKind.ATTACK))
        val restrained=GameplayRules.setCondition(c,"restrained",true)
        assertEquals(0,CharacterCalculator.calculate(restrained).speed.value)
        assertEquals(DiceMode.DISADVANTAGE,CharacterCalculator.rollMode(restrained,RollKind.SAVE,"DEX"))
        assertNull(GameplayRules.setCondition(c.copy(concentrationSpellId="spell"),"incapacitated",true).concentrationSpellId)
    }
    @Test fun activeEffectsExpireOnlyWithExplicitGameTimeOrEligibleRest() {
        val e=effect("haste",EffectPayload.Number(NumericTarget.SPEED,NumberOperation.ADD,10)).copy(expiresAtGameSeconds=60)
        val c=fixture().copy(effects=listOf(e))
        assertEquals(35,CharacterCalculator.calculate(c).speed.value)
        val expired=GameplayRules.advanceTime(c,60,true)
        assertEquals(25,CharacterCalculator.calculate(expired).speed.value)
        assertEquals(c.effects,expired.effects)
        assertFailsWith<IllegalArgumentException> { GameplayRules.advanceTime(c,60,false) }
    }
    @Test fun fullStandardSkillsAndExpertiseAreCalculatedWithoutManualTotals() {
        val c=fixture().copy(skills=mapOf("Persuasion" to SkillTraining(Stat.CHA,expertise=true)),saveProficiencies=setOf(Stat.INT))
        val p=CharacterCalculator.calculate(c)
        assertEquals(18,p.skills.size);assertEquals(9,p.skills.getValue("Persuasion").value)
        assertEquals(9,p.saves.getValue(Stat.INT).value)
    }
    @Test fun exhaustionVersionPinAndExplicitRestReductionAreApplied() {
        val revised=fixture().copy(exhaustionLevel=5,speed=20)
        assertEquals(0,CharacterCalculator.calculate(revised).speed.value)
        assertEquals(-1,CharacterCalculator.calculate(revised).spellAttack.value)
        val legacy=fixture().copy(exhaustionLevel=4,rules=RuleVersion(core=RuleFamily.LEGACY_2014))
        assertEquals(12,CharacterCalculator.calculate(legacy).speed.value)
        assertEquals(33,CharacterCalculator.calculate(legacy).maximumHp.value)
        assertEquals(3,GameplayRules.rest(legacy,Recharge.LONG_REST,true,28800).exhaustionLevel)
    }
    @Test fun compositeCyclesAndDuplicateEffectIdentitiesAreRejected() {
        val f=fixture()
        assertFailsWith<IllegalArgumentException> { CharacterCalculator.calculate(f.copy(items=listOf(ItemInstance("a","armor-def",donorInstanceIds=setOf("b")),ItemInstance("b","armor-def",donorInstanceIds=setOf("a"))))) }
        assertFailsWith<IllegalArgumentException> { CharacterCalculator.calculate(f.copy(effects=listOf(f.itemDefinitions.single().effects.first()))) }
    }
    @Test fun diceAreExplicitValidatedAndKeepTransparentAdvantageFaces() {
        val formula=SecureDice.parse("d20 + 4 + 1d4")
        val r=SecureDice.manual(formula,listOf(2,18,3),DiceMode.ADVANTAGE)
        assertEquals(25,r.total);assertEquals(18,r.naturalD20);assertFalse(r.faces.first().kept)
        assertEquals(3,SecureDice.roll(SecureDice.parse("2d6+1"),die={1}).total)
        assertFailsWith<IllegalArgumentException> { SecureDice.parse("2d6 * 3") }
        assertFailsWith<IllegalArgumentException> { SecureDice.parse("201d6") }
        assertFailsWith<IllegalArgumentException> { SecureDice.manual(SecureDice.parse("1d6"),listOf(7)) }
        assertFailsWith<IllegalArgumentException> { SecureDice.roll(SecureDice.parse("2d20"),DiceMode.ADVANTAGE) }
    }
    @Test fun serializationRoundTripPreservesTypedRulesAndUnknowns() {
        val c=attackFixture().copy(currentHp=null)
        assertEquals(c,Json.decodeFromString<ReforgeCharacter>(Json.encodeToString(c)))
    }
    @Test fun independentCompanionAttackDoesNotRequireUnknownAbilitiesOrAddProficiencyAgain() {
        val c=ReforgeCharacter("companion","Reviewed companion",attacks=listOf(AttackDefinition("rend","Rend",fixedAttackBonus=6,addAbilityToDamage=false,damage=listOf(DamageComponent("rend-damage","Rend",DamageKind.SLASHING,SecureDice.parse("2d6+3"))))))
        val p=AttackRules.preview(c,"rend")
        assertEquals(6,p.attackBonus.value)
        assertEquals(3,p.components.single().formula.flat)
    }
    @Test fun revisedSpellSlotPerTurnAndInitiativeConditionsRespectVersion() {
        val c=fixture().copy(turn=TurnEconomy(tracking=true),spells=listOf(SpellDefinition("spell","Spell",1,SpellPreparation.PREPARED,castingTime="1 Bonus Action"),SpellDefinition("reaction","Reaction spell",1,SpellPreparation.PREPARED,castingTime="1 Reaction")))
        val cast=GameplayRules.commitCast(c,GameplayRules.previewCast(c,"spell"))
        assertFailsWith<IllegalArgumentException> { GameplayRules.previewCast(cast,"reaction") }
        assertNotNull(GameplayRules.previewCast(GameplayRules.nextTurn(cast),"reaction"))
        assertEquals(DiceMode.ADVANTAGE,CharacterCalculator.rollMode(GameplayRules.setCondition(c,"invisible",true),RollKind.INITIATIVE))
        assertEquals(DiceMode.DISADVANTAGE,CharacterCalculator.rollMode(GameplayRules.setCondition(c,"stunned",true),RollKind.INITIATIVE))
        assertNotNull(ConditionRules.rollRestriction(GameplayRules.setCondition(c,"stunned",true),RollKind.SAVE,"DEX"))
    }
    @Test fun conditionImmunitySuppressesExistingConditionWithoutDeletingIt() {
        val c=fixture().copy(conditions=setOf("poisoned"),effects=listOf(effect("poison-immunity",EffectPayload.ConditionImmunity("poisoned"))))
        assertEquals(setOf("poisoned"),c.conditions)
        assertEquals(DiceMode.NORMAL,CharacterCalculator.rollMode(c,RollKind.ATTACK))
    }
    @Test fun incomingTypedDamageUsesActiveDefensesAndConditionalTypes() {
        val c=fixture().copy(effects=listOf(effect("fire-resistance",EffectPayload.Defense(DamageKind.FIRE,DefenseKind.RESISTANCE)).copy(conditions=listOf(EffectCondition(ConditionKind.DAMAGE_TYPE,"FIRE")))))
        assertEquals(4,AttackRules.resolveIncoming(c,DamageKind.FIRE,9).total)
        assertEquals(9,AttackRules.resolveIncoming(c,DamageKind.COLD,9).total)
    }
    @Test fun hitDiceArePreviewedWithoutSpendingAndThenConsumeKnownPoolAtomically() {
        val c=fixture().copy(resources=listOf(ResourcePool("hit-dice","Hit Dice",9,4,Recharge.LONG_REST,recoveryAmount=9,dieSides=8)))
        val p=GameplayRules.previewHitDie(c,"hit-dice",5,true)
        assertEquals(7,p.healing);assertEquals(42,c.currentHp);assertEquals(4,c.resources.single().current)
        val spent=GameplayRules.spendHitDie(c,"hit-dice",5,true)
        assertEquals(49,spent.currentHp);assertEquals(3,spent.resources.single().current)
        assertFailsWith<IllegalArgumentException> { GameplayRules.spendHitDie(c,"hit-dice",5,false) }
        assertFailsWith<IllegalArgumentException> { GameplayRules.spendHitDie(c.copy(currentHp=null),"hit-dice",5,true) }
    }
    @Test fun healingAndFurtherDamageDoNotEraseCapacityClampOverhang() {
        val c=fixture().copy(currentHp=0,hpDamage=71)
        assertEquals(67,GameplayRules.heal(c,4).hpDamage)
        assertEquals(0,GameplayRules.heal(c,4).currentHp)
        assertEquals(73,GameplayRules.damage(c,2).hpDamage)
        assertEquals(71,GameplayRules.damage(c,0).hpDamage)
    }
    @Test fun additiveImportOverflowFailsClosedRatherThanWrappingNegative() {
        val c=fixture().copy(effects=(1..2200).map { effect("huge-$it",EffectPayload.Number(NumericTarget.ARMOR_CLASS,NumberOperation.ADD,1_000_000)) })
        assertFailsWith<ArithmeticException> { CharacterCalculator.calculate(c) }
    }
}
