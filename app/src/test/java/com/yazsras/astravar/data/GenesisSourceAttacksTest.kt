package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisSourceAttacksTest {
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java).build()
    private val id = "fictional-source-review"
    private fun attack() = SheetAttack("Fictional frozen ray", 7, "2d6+2", "Cold", "60 feet",
        evidenceStatus = "Confirmed", source = "Fictional reviewed source", id = "fictional-ray")
    private val choices = GenesisSourceAttackChoices("Full Action", "ranged", true, true, true)
    private suspend fun seed(legacy: CampaignLedgerRepository, local: ReforgeRepository,
        attacks: List<SheetAttack> = listOf(attack())): ReforgeSavedCharacter {
        legacy.records.saveProfile(CharacterProfile(id = id, name = "Fictional historian", level = 8, currentHp = 9), source = "Fictional source")
        legacy.party.saveSheet(id, PartySheetData(attacks = attacks), source = "Fictional source sheet")
        val preview = local.readLegacyPreview(legacy, id)
        val adopted = local.adopt(legacy, preview, preview.character.copy(spellSlots = mapOf(1 to 1),
            resources = listOf(ResourcePool("fixture-resource", "Fixture charge", 4, 2)),
            turn = TurnEconomy(tracking = true, attacksPerAction = 2)), "adopt-fixture")
        return local.save(id, adopted.revision, adopted.character.copy(notes = "Already adopted, edited character"), "Later edit")
    }

    @Test fun alreadyAdoptedReviewPreservesExactAttacksAndOriginalEvidenceAcrossDatabaseReopenBackupAndIdempotentRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "fictional-source-attack-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, CampaignLedgerDb::class.java, databaseName).build()
        val restoredDb = db()
        try {
            val legacy = CampaignLedgerRepository(database); val local = ReforgeRepository(database)
            val before = seed(legacy, local)
            val sourceBefore = legacy.readCharacterSnapshot(id)
            val review = local.reviewSourceAttacks(legacy, before)
            assertEquals(before, local.get(id))
            val next = local.commitSourceAttack(legacy, review, attack().id, choices, "review-command")
            val exact = next.character.attacks.single()
            assertEquals(7, exact.fixedAttackBonus)
            assertEquals(SecureDice.parse("2d6+2"), exact.damage.single().formula)
            assertEquals(DamageKind.COLD, exact.damage.single().type)
            assertTrue(exact.damage.single().magical); assertFalse(exact.addAbilityToDamage)
            assertEquals("60 feet", exact.range); assertEquals(setOf("ranged"), exact.tags)
            assertEquals(attack(), reforgeJson.decodeFromString<SheetAttack>(exact.recordedSource!!.attackJson))
            assertEquals(sourceBefore, legacy.readCharacterSnapshot(id))
            assertEquals(before.character.copy(revision = next.revision, attacks = listOf(exact)), next.character)
            val preview = AttackRules.preview(next.character, exact.id)
            assertEquals(7, preview.attackBonus.value)
            val spent = AttackRules.commitHit(next.character, preview)
            assertFalse(spent.turn.actionAvailable); assertEquals(0, spent.turn.attacksUsed)
            assertEquals(before.character.spellSlots, spent.spellSlots)
            database.close()
            database = Room.databaseBuilder(context, CampaignLedgerDb::class.java, databaseName).build()
            val reopened = ReforgeRepository(database)
            val reopenedLegacy = CampaignLedgerRepository(database)
            assertEquals(next, reopened.get(id))
            assertEquals(sourceBefore, reopenedLegacy.readCharacterSnapshot(id))
            val edited = reopened.save(id, next.revision, next.character.copy(notes = "Later state"), "Later edit")
            assertEquals(next, reopened.commitSourceAttack(reopenedLegacy, review, attack().id, choices, "review-command"))
            assertEquals(edited, reopened.get(id))
            assertTrue(runCatching { reopened.commitSourceAttack(reopenedLegacy, review, attack().id, choices.copy(magical = false), "review-command") }.isFailure)
            val fresh = reopened.reviewSourceAttacks(reopenedLegacy, edited)
            assertTrue(runCatching { reopened.commitSourceAttack(reopenedLegacy, fresh, attack().id, choices) }.isFailure)
            val restored = ReforgeRepository(restoredDb)
            val restore = restored.previewRestore(reopened.export())
            assertTrue(restore.conflicts.toString(), restore.valid)
            restored.restore(restore, "restore-source-fixture")
            assertEquals(edited, restored.get(id))
        } finally { database.close(); restoredDb.close(); context.deleteDatabase(databaseName) }
    }

    @Test fun unsupportedSourceRowsStayVisibleAndCannotBeActivated() = runBlocking {
        val database = db()
        try {
            val legacy = CampaignLedgerRepository(database); val local = ReforgeRepository(database)
            val base = attack()
            val sources = listOf(base.copy(id = "zero", damageFormula = "0"),
                base.copy(id = "proposed", evidenceStatus = "Proposed"),
                base.copy(id = "cost", resourceCost = "1 charge"),
                base.copy(id = "mixed", damageFormula = "2d6 cold + 1d4 fire"),
                base.copy(id = "critical", criticalRule = "Triple dice"),
                base.copy(id = "multi", multiattack = "Two strikes"),
                base.copy(id = "unknown", attackBonus = null))
            val before = seed(legacy, local, sources)
            val review = local.reviewSourceAttacks(legacy, before)
            val rows = sourceAttackCandidates(review)
            assertEquals(7, rows.size); assertEquals(listOf("zero"), rows.filter { it.problem == null }.map { it.source.id })
            assertTrue(runCatching { local.commitSourceAttack(legacy, review, "cost", choices) }.isFailure)
            assertTrue(runCatching { local.commitSourceAttack(legacy, review, "zero", choices.copy(noAdditionalCost = false)) }.isFailure)
            assertTrue(runCatching { local.commitSourceAttack(legacy, review, "zero", choices.copy(actionType = "")) }.isFailure)
            assertEquals(before, local.get(id))
            assertEquals(0, sourceAttackDefinition(review, "zero", choices).damage.single().formula.flat)
            // The source repository already rejects duplicate IDs. Exercise the importer's
            // independent defensive check with a constructed malformed snapshot, not a bad save.
            val duplicateSheet = review.sheet.copy(contentJson = campaignJson.encodeToString(
                PartySheetData(attacks = listOf(base.copy(id = "duplicate"), base.copy(id = "duplicate")))))
            val malformed = review.copy(sheet = duplicateSheet, fingerprint = reforgeHash(reforgeJson.encodeToString(duplicateSheet)))
            assertTrue(sourceAttackCandidates(malformed).all { it.problem == "The source repeats this attack identity." })
            assertTrue(runCatching { sourceAttackDefinition(malformed, "duplicate", choices) }.isFailure)
            assertEquals(before, local.get(id))
        } finally { database.close() }
    }

    @Test fun changedSourceCharacterCollisionsAndAutomaticBonusesFailWithoutMutation() = runBlocking {
        val database = db()
        try {
            val legacy = CampaignLedgerRepository(database); val local = ReforgeRepository(database)
            val before = seed(legacy, local)
            val oldReview = local.reviewSourceAttacks(legacy, before)
            val originalSheet = legacy.readCharacterSnapshot(id).sheet!!
            legacy.party.saveSheet(id, originalSheet.sheet.copy(role = "Reviewed correction"), originalSheet.revision, "Fictional correction")
            assertTrue(runCatching { local.commitSourceAttack(legacy, oldReview, attack().id, choices) }.isFailure)
            assertEquals(before, local.get(id))
            val current = local.reviewSourceAttacks(legacy, before)
            val incoming = sourceAttackDefinition(current, attack().id, choices)
            assertNotNull(assessSourceAttack(before.character.copy(attacks = listOf(incoming.copy(name = "Other name", recordedSource = null))), incoming).problem)
            assertNotNull(assessSourceAttack(before.character.copy(attacks = listOf(incoming.copy(id = "other-id", recordedSource = null))), incoming).problem)
            val effect = EffectDefinition("fixture-equipment-bonus", "Fixture focus", RuleSource("fixture", "Fictional accepted effect", Acceptance.ACCEPTED_HOMEBREW),
                EffectPayload.Number(NumericTarget.ATTACK, NumberOperation.ADD, 1), requiresAttunement = false)
            val affected = local.save(id, before.revision, before.character.copy(effects = listOf(effect)), "Fixture effect")
            assertTrue(runCatching { local.commitSourceAttack(legacy, current, attack().id, choices) }.isFailure)
            val affectedReview = local.reviewSourceAttacks(legacy, affected)
            val assessment = assessSourceAttack(affected.character, sourceAttackDefinition(affectedReview, attack().id, choices))
            assertEquals(8, assessment.preview!!.attackBonus.value); assertNotNull(assessment.problem)
            assertTrue(runCatching { local.commitSourceAttack(legacy, affectedReview, attack().id, choices) }.isFailure)
            assertEquals(affected, local.get(id))
            val damageEffect = effect.copy(id = "fixture-damage", payload = EffectPayload.DamageBonus(DamageComponent("bonus", "Fixture", DamageKind.FIRE, SecureDice.parse("1d4"))))
            assertNotNull(assessSourceAttack(before.character.copy(effects = listOf(damageEffect)), incoming).problem)
            assertEquals(7, incoming.fixedAttackBonus); assertEquals(SecureDice.parse("2d6+2"), incoming.damage.single().formula)
        } finally { database.close() }
    }

    @Test fun defaultSerializationRemainsUnchangedAndSourceTamperingIsRejected() = runBlocking {
        val plain = AttackDefinition("fixture-plain", "Fixture")
        assertFalse(reforgeJson.encodeToString(plain).contains("recordedSource"))
        val database = db()
        try {
            val legacy = CampaignLedgerRepository(database); val local = ReforgeRepository(database)
            val before = seed(legacy, local)
            val review = local.reviewSourceAttacks(legacy, before)
            val attack = sourceAttackDefinition(review, attack().id, choices)
            val corrupt = attack.copy(recordedSource = attack.recordedSource!!.copy(attackJson = "{}"))
            assertTrue(runCatching { local.save(id, before.revision, before.character.copy(attacks = listOf(corrupt)), "Bad source") }.isFailure)
            assertEquals(before, local.get(id))
        } finally { database.close() }
    }

    @Test fun attackAndMagicTimingLabelsRequireExplicitChoicesAndUnrelatedSheetConflictsRequireReview() = runBlocking {
        val database = db()
        try {
            val legacy = CampaignLedgerRepository(database); val local = ReforgeRepository(database)
            val sourceAttack = attack().copy(id = "fictional-strike", name = "Fictional strike", actionEconomy = "Attack")
            val sourceMagic = attack().copy(id = "fictional-cantrip", name = "Fictional cantrip", actionEconomy = "Magic")
            val before = seed(legacy, local, listOf(sourceAttack, sourceMagic))
            val old = legacy.readCharacterSnapshot(id).sheet!!
            val conflicts = listOf("Fictional maximum HP unknown", "Fictional action prerequisites need review")
            legacy.party.saveSheet(id, old.sheet.copy(unresolvedConflicts = conflicts), old.revision, "Fictional source review")
            val review = local.reviewSourceAttacks(legacy, before)
            assertTrue(sourceAttackCandidates(review).all { it.problem == null })
            assertTrue(runCatching { sourceAttackDefinition(review, sourceMagic.id, choices) }.isFailure)
            val reviewed = choices.copy(sourceConflictsReviewed = true)
            assertTrue(runCatching { sourceAttackDefinition(review, sourceMagic.id, reviewed.copy(actionType = "Action")) }.isFailure)
            val oneAttack = sourceAttackDefinition(review, sourceAttack.id, reviewed.copy(actionType = "Action"))
            assertEquals("Action", oneAttack.actionType)
            val wholeAction = sourceAttackDefinition(review, sourceMagic.id, reviewed)
            assertEquals("Full Action", wholeAction.actionType)
            assertEquals(conflicts, wholeAction.recordedSource!!.reviewedSheetConflicts)
            val added = local.commitSourceAttack(legacy, review, sourceMagic.id, reviewed)
            assertEquals(sourceMagic, reforgeJson.decodeFromString<SheetAttack>(added.character.attacks.single().recordedSource!!.attackJson))
            assertEquals(conflicts, legacy.readCharacterSnapshot(id).sheet!!.sheet.unresolvedConflicts)
        } finally { database.close() }
    }
}
