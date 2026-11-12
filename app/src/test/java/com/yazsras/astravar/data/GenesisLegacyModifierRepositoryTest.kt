package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fictional source -> explicit proof -> equipment changes -> portable recovery, through production commands. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisLegacyModifierRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val filename = "fictional-legacy-modifier-source.db"
    private val recoveryFilename = "fictional-legacy-modifier-recovery.db"
    private lateinit var db: CampaignLedgerDb
    private lateinit var target: CampaignLedgerDb
    private lateinit var authority: GenesisAuthorityRepository
    private lateinit var ledger: CampaignLedgerRepository
    private lateinit var playable: ReforgeRepository
    private val id = "fictional-modifier-player"
    private val itemId = "fictional-physical-gauntlets"
    private val sourceLabel = "Retained fictional campaign evidence"
    private val decision = GenesisLegacyModifierDecision.SUMMARIZED_EXISTING_EFFECTS

    @Before fun open() {
        context.deleteDatabase(filename); context.deleteDatabase(recoveryFilename)
        reopen()
        target = Room.databaseBuilder(context, CampaignLedgerDb::class.java, recoveryFilename).allowMainThreadQueries().build()
    }
    private fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, filename).allowMainThreadQueries().build()
        authority = GenesisAuthorityRepository(db); ledger = CampaignLedgerRepository(db); playable = ReforgeRepository(db)
    }
    @After fun close() { db.close(); target.close(); context.deleteDatabase(filename); context.deleteDatabase(recoveryFilename) }
    private suspend fun fixture(score: Int? = 8, acceptance: Acceptance = Acceptance.ACCEPTED_HOMEBREW): ReforgeSavedCharacter {
        ledger.records.saveProfile(CharacterProfile(id = id, name = "Fictional traveller", level = 3, currentHp = 12, maximumHp = 20),
            source = sourceLabel, commandId = "modifier-original-profile")
        ledger.records.saveAbility(CharacterAbility("STR", id, score, 5, notes = "Previously summarized gauntlets"),
            source = sourceLabel, commandId = "modifier-original-ability")
        val adopted = playable.adopt(ledger, playable.readLegacyPreview(ledger, id), commandId = "modifier-adopt")
        val effect = EffectDefinition("fictional-strength-set", "Fictional reviewed strength",
            RuleSource("fictional-source", "Fictional accepted effect", acceptance),
            EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 19, "STR"))
        val definition = ItemDefinition("fictional-gauntlets", "Fictional gauntlets", effects = listOf(effect), requiresAttunement = true)
        return playable.save(id, adopted.revision, adopted.character.copy(itemDefinitions = listOf(definition),
            items = listOf(ItemInstance(itemId, definition.id, ItemState.WORN, attuned = true)), baselineConstitutionModifier = null),
            "Configure previously reviewed fictional effects", "modifier-configure")
    }
    private suspend fun reviewed(): GenesisActorAuthorityReview {
        val initial = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER)
        val ability = initial.source!!.abilities.single { it.key == "STR" }
        val proof = genesisReviewLegacyModifier(initial.playable.character, ability,
            listOf(GenesisLegacyModifierEffectRef("fictional-strength-set", itemId)), decision)
        return authority.reviewActorAuthority(id, GenesisActorRole.PLAYER, legacyModifierResolutions = listOf(proof))
    }
    private suspend fun rejectUnchanged(block: suspend () -> Unit) {
        val before = authority.backup.snapshot()
        assertNotNull(runCatching { block() }.exceptionOrNull())
        assertEquals(before, authority.backup.snapshot())
    }

    @Test fun pendingPreviewDoesNotZeroOldModifierOrPermitUnreviewedAcceptance() = runBlocking {
        fixture()
        val before = authority.backup.snapshot()
        val pending = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER)
        assertEquals(before, authority.backup.snapshot())
        assertFalse(genesisLegacyModifiersReviewed(pending))
        assertTrue(pending.differences.any { it.field == "STR recorded magical modifier" && it.sourceValue == "5" && it.playableValue == "0" })
        rejectUnchanged { authority.acceptActorAuthority(pending, "modifier-missing-proof") }
        assertEquals(5, db.recordsDao().characterAbility(id, "STR")!!.magicModifier)
        assertNull(authority.actor(id))
    }

    @Test fun acceptanceUnequipAndCompleteRestoreRetainOriginalEvidenceAndUseFrozenHistoricalProof() = runBlocking {
        val original = fixture()
        val originJson = db.reforgeDao().character(id)!!.originJson
        val originalReceipt = ledger.records.receipt("modifier-original-ability")
        val review = reviewed()
        assertTrue(genesisLegacyModifiersReviewed(review))
        val accepted = authority.acceptActorAuthority(review, "modifier-accept")
        assertEquals(original.character.copy(revision = original.revision + 1), accepted.playable!!.character)
        assertEquals(19, db.recordsDao().characterAbility(id, "STR")!!.score)
        assertEquals(0, db.recordsDao().characterAbility(id, "STR")!!.magicModifier)
        val unequipped = playable.save(id, accepted.playable.revision,
            GameplayRules.equip(GameplayRules.attune(accepted.playable.character, itemId, false), itemId, ItemState.CARRIED),
            "Unequip fictional gauntlets", "modifier-unequip")
        assertEquals(8, CharacterCalculator.calculate(unequipped.character).abilities.getValue(Stat.STR).effective.value)
        assertEquals(8, db.recordsDao().characterAbility(id, "STR")!!.score)
        assertEquals(original.character.itemDefinitions, unequipped.character.itemDefinitions)
        assertNull(unequipped.character.baselineConstitutionModifier)
        assertEquals(originJson, db.reforgeDao().character(id)!!.originJson)
        assertEquals(originalReceipt, ledger.records.receipt("modifier-original-ability"))
        val frozenReceipt = db.genesisAuthorityDao().receipt("modifier-accept")!!
        assertEquals(review, campaignJson.decodeFromString<GenesisActorAuthorityReview>(frozenReceipt.requestJson))
        assertEquals(8, review.legacyModifierResolutions.single().sourceAbility.score)
        assertEquals(5, review.legacyModifierResolutions.single().sourceAbility.magicModifier)
        val document = authority.backup.exportWithProof()
        reopen()
        assertEquals(unequipped, playable.get(id))
        val recovery = GenesisAuthorityBackupRepository(target)
        val preview = recovery.previewRestore(document.text)
        assertTrue(preview.conflicts.joinToString(), preview.valid)
        recovery.restore(preview, "modifier-complete-restore")
        target.close()
        target = Room.databaseBuilder(context, CampaignLedgerDb::class.java, recoveryFilename).allowMainThreadQueries().build()
        assertEquals(unequipped, ReforgeRepository(target).get(id))
        assertEquals(originJson, target.reforgeDao().character(id)!!.originJson)
        assertEquals(frozenReceipt, target.genesisAuthorityDao().receipt("modifier-accept"))
        assertEquals(originalReceipt, target.recordsDao().receipt("modifier-original-ability"))
        assertTrue(GenesisAuthorityBackupRepository(target).exportWithProof().text.isNotEmpty())
    }

    @Test fun staleSourceAndPlayableProofsRejectWithoutPartialEnrollment() = runBlocking {
        fixture()
        val review = reviewed()
        val ability = db.recordsDao().characterAbility(id, "STR")!!
        ledger.records.saveAbility(ability.copy(notes = "Later source decision"), ability.revision, sourceLabel, "modifier-source-change")
        rejectUnchanged { authority.acceptActorAuthority(review, "modifier-stale-source") }
        rejectUnchanged { authority.reviewActorAuthority(id, GenesisActorRole.PLAYER, legacyModifierResolutions = review.legacyModifierResolutions) }
        val fresh = reviewed()
        val current = playable.get(id)!!
        playable.save(id, current.revision, current.character.copy(notes = "Later playable note"), "Fictional later edit", "modifier-playable-change")
        rejectUnchanged { authority.acceptActorAuthority(fresh, "modifier-stale-playable") }
    }

    @Test fun tamperedProofIsRejectedEvenWithRecomputedReviewFingerprint() = runBlocking {
        fixture()
        val good = reviewed()
        val changed = good.copy(legacyModifierResolutions = good.legacyModifierResolutions.map { it.copy(withoutSelectedScore = 9) }, fingerprint = "")
        rejectUnchanged { authority.acceptActorAuthority(changed.copy(fingerprint = genesisActorAuthorityReviewFingerprint(changed)), "modifier-tampered-proof") }
    }

    @Test fun backupRejectsMissingAndAlteredHistoricalProofEvenWithUpdatedReceiptFingerprint() = runBlocking {
        fixture()
        val review = reviewed()
        authority.acceptActorAuthority(review, "modifier-backup-accept")
        val backup = authority.backup.snapshot()
        val original = backup.authority.receipts.single { it.commandId == "modifier-backup-accept" }
        listOf(review.copy(legacyModifierResolutions = emptyList()), review.copy(legacyModifierResolutions = review.legacyModifierResolutions.map { it.copy(effectiveModifier = 5) })).forEach { altered ->
            val frozen = altered.copy(fingerprint = genesisActorAuthorityReviewFingerprint(altered))
            val request = campaignJson.encodeToString(frozen)
            val tampered = original.copy(requestJson = request, fingerprint = genesisAuthorityFingerprint(original.kind, request))
            val changed = backup.copy(authority = backup.authority.copy(receipts = backup.authority.receipts.map { if (it.commandId == original.commandId) tampered else it }))
            val targetBefore = GenesisAuthorityBackupRepository(target).snapshot()
            val preview = GenesisAuthorityBackupRepository(target).previewRestore(genesisAuthorityBackupJson(changed))
            assertFalse(preview.valid)
            assertEquals(targetBefore, GenesisAuthorityBackupRepository(target).snapshot())
        }
    }

    @Test fun acceptedCommandRetryIsBoundToOriginalProofAfterEquipmentChanges() = runBlocking {
        fixture()
        val review = reviewed()
        val accepted = authority.acceptActorAuthority(review, "modifier-bound-command")
        val saved = accepted.playable!!
        playable.save(id, saved.revision, GameplayRules.equip(saved.character, itemId, ItemState.CARRIED), "Later equipment", "modifier-retry-unequip")
        val beforeRetry = authority.backup.snapshot()
        assertEquals(accepted, authority.acceptActorAuthority(review, "modifier-bound-command"))
        assertEquals(beforeRetry, authority.backup.snapshot())
        rejectUnchanged { authority.acceptActorAuthority(review.copy(legacyModifierResolutions = emptyList()), "modifier-bound-command") }
    }

    @Test fun noModifierReceiptKeepsOldRequestShapeAndRetryAfterLaterChange() = runBlocking {
        fixture()
        val ability = db.recordsDao().characterAbility(id, "STR")!!
        ledger.records.saveAbility(ability.copy(magicModifier = 0), ability.revision, sourceLabel, "modifier-source-no-summary")
        val review = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER)
        val accepted = authority.acceptActorAuthority(review, "modifier-old-shape")
        val bytes = db.genesisAuthorityDao().receipt("modifier-old-shape")!!.requestJson
        assertFalse(bytes.contains("legacyModifierResolutions"))
        val oldDecoded = campaignJson.decodeFromString<GenesisActorAuthorityReview>(bytes)
        assertEquals(review, oldDecoded)
        val saved = accepted.playable!!
        playable.save(id, saved.revision, saved.character.copy(notes = "Later note"), "Fictional edit", "modifier-old-shape-later")
        val before = authority.backup.snapshot()
        assertEquals(accepted, authority.acceptActorAuthority(oldDecoded, "modifier-old-shape"))
        assertEquals(before, authority.backup.snapshot())
    }

    @Test fun unknownSourceScoreAndUnapprovedEffectRemainUnresolved() = runBlocking {
        fixture(score = null, acceptance = Acceptance.PROPOSED)
        val pending = authority.reviewActorAuthority(id, GenesisActorRole.PLAYER)
        assertFalse(genesisLegacyModifiersReviewed(pending))
        assertTrue(genesisLegacyModifierCandidates(pending.playable.character, pending.source!!.abilities.single()).isEmpty())
        rejectUnchanged { authority.acceptActorAuthority(pending, "modifier-unknown-reject") }
        assertNull(db.recordsDao().characterAbility(id, "STR")!!.score)
        assertEquals(5, db.recordsDao().characterAbility(id, "STR")!!.magicModifier)
    }
}
