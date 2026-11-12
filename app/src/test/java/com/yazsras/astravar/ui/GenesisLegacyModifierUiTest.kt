package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisLegacyModifierUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private val id = "fictional-ui-modifier-actor"
    private val effectId = "fictional-ui-strength"
    private val candidateTag = "genesis-modifier-effect-STR-$effectId"
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun contentClick(text: String) = button(text).performScrollTo().performClick()
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun fixture(score: Int? = 8, acceptance: Acceptance = Acceptance.ACCEPTED_HOMEBREW,
        block: (GenesisConnectedEnvironment, CampaignLedgerDb, ReforgeSavedCharacter) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        val preferenceFile = File(context.cacheDir, "genesis-modifier-ui-${UUID.randomUUID()}.preferences_pb")
        val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = ReforgePreferences(PreferenceDataStoreFactory.create(scope = preferenceScope) { preferenceFile })
        val env = GenesisConnectedEnvironment(GenesisAuthorityRepository(db), CampaignLedgerRepository(db), ReforgeRepository(db), preferences)
        val saved = runBlocking {
            env.campaign.records.saveProfile(CharacterProfile(id = id, name = "Fictional modifier review", currentHp = 8, maximumHp = 12, level = 2),
                source = "Fictional source", commandId = "modifier-ui-source")
            env.campaign.records.saveAbility(CharacterAbility("STR", id, score, 5, notes = "Retain original review"),
                source = "Fictional source", commandId = "modifier-ui-ability")
            val preview = env.local.readLegacyPreview(env.campaign, id)
            val effect = EffectDefinition(effectId, "Fictional accepted strength", RuleSource("fictional-ui", "Reviewed character effect", acceptance),
                EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 19, "STR"))
            env.local.adopt(env.campaign, preview, preview.character.copy(effects = listOf(effect)), "modifier-ui-adopt")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { GenesisActorMembershipEditor(env, id, {}) } }
            awaitText("Fictional modifier review")
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Review shared values") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            button("Review shared values").performClick()
            awaitText("I reviewed these values and membership")
            block(env, db, saved)
        } finally {
            activity.pause().stop().destroy()
            runBlocking { preferenceScope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close(); preferenceFile.delete()
        }
    }
    private fun chooseAndExplain() {
        compose.onNodeWithTag(candidateTag).performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("genesis-modifier-declaration").performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
        contentClick("Review modifier explanation")
        awaitText("Modifier explanation reviewed")
    }

    @Test fun uncheckedEffectsAndGlobalAcknowledgmentAloneCannotAcceptThenExplicitProofResetsApproval() = fixture { env, db, original ->
        val before = runBlocking { env.authority.backup.snapshot() }
        compose.onNodeWithTag(candidateTag).performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp)
        button("Review modifier explanation").assertIsNotEnabled()
        contentClick("I reviewed these values and membership")
        button("Accept reviewed values").assertIsNotEnabled()
        assertEquals(before, runBlocking { env.authority.backup.snapshot() })
        chooseAndExplain()
        // Explanation changes the frozen review; earlier global acknowledgment must be discarded.
        button("Accept reviewed values").assertIsNotEnabled()
        assertEquals(before, runBlocking { env.authority.backup.snapshot() })
        contentClick("I reviewed these values and membership")
        button("Accept reviewed values").assertIsEnabled().performClick()
        awaitText("Shared values connected and saved")
        runBlocking {
            val stored = env.local.get(id)!!
            val receipt = db.genesisAuthorityDao().allReceipts().single { it.kind == "accept-actor" }
            val review = campaignJson.decodeFromString<GenesisActorAuthorityReview>(receipt.requestJson)
            val proof = review.legacyModifierResolutions.single()
            assertEquals(8, proof.sourceAbility.score); assertEquals(5, proof.sourceAbility.magicModifier)
            assertEquals(-1, proof.withoutSelectedModifier); assertEquals(19, proof.effectiveScore); assertEquals(4, proof.effectiveModifier)
            assertEquals(original.character.effects, stored.character.effects)
            assertEquals(original.origin, stored.origin)
            assertNull(stored.character.baselineConstitutionModifier)
            assertEquals(19, db.recordsDao().characterAbility(id, "STR")!!.score)
            assertEquals(0, db.recordsDao().characterAbility(id, "STR")!!.magicModifier)
            assertEquals(original.origin.sourceSnapshotJson, stored.origin.sourceSnapshotJson)
        }
    }

    @Test fun unknownSourceScoreRemainsVisibleUnresolvedAndCannotBeAccepted() = fixture(score = null) { env, db, _ ->
        val before = runBlocking { env.authority.backup.snapshot() }
        compose.onNodeWithText("STR's original score is unknown. Keep this modifier unresolved until its source is reviewed.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(candidateTag).assertDoesNotExist()
        button("Review modifier explanation").assertIsNotEnabled()
        contentClick("I reviewed these values and membership")
        button("Accept reviewed values").assertIsNotEnabled()
        assertEquals(before, runBlocking { env.authority.backup.snapshot() })
        assertNull(runBlocking { db.recordsDao().characterAbility(id, "STR")!!.score })
    }

    @Test fun proposedEffectNeverBecomesASelectableExplanationOrApprovedMechanic() = fixture(acceptance = Acceptance.PROPOSED) { env, _, original ->
        val before = runBlocking { env.authority.backup.snapshot() }
        compose.onNodeWithText("No accepted active effect explains STR. Review the existing mechanics before connecting these values.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(candidateTag).assertDoesNotExist()
        button("Review modifier explanation").assertIsNotEnabled()
        contentClick("I reviewed these values and membership")
        button("Accept reviewed values").assertIsNotEnabled()
        assertEquals(before, runBlocking { env.authority.backup.snapshot() })
        assertEquals(Acceptance.PROPOSED, runBlocking { env.local.get(id)!! }.character.effects.single().source.acceptance)
        assertEquals(original, runBlocking { env.local.get(id) })
    }

    @Test fun reviseReviewClearsProofChoicesAndAcknowledgmentWithoutWritingRecords() = fixture { env, _, _ ->
        val before = runBlocking { env.authority.backup.snapshot() }
        chooseAndExplain()
        contentClick("I reviewed these values and membership")
        button("Accept reviewed values").assertIsEnabled()
        button("Revise review").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(candidateTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(candidateTag).performScrollTo().assertIsOff()
        compose.onNodeWithTag("genesis-modifier-declaration").performScrollTo().assertIsOff()
        button("Review modifier explanation").assertIsNotEnabled()
        button("Accept reviewed values").assertIsNotEnabled()
        assertEquals(before, runBlocking { env.authority.backup.snapshot() })
    }

    @Test fun changingSelectedEffectsRequiresDeclarationAgain() = fixture { env, _, _ ->
        val before = runBlocking { env.authority.backup.snapshot() }
        compose.onNodeWithTag(candidateTag).performScrollTo().performClick()
        compose.onNodeWithTag("genesis-modifier-declaration").performScrollTo().performClick()
        button("Review modifier explanation").assertIsEnabled()
        compose.onNodeWithTag(candidateTag).performScrollTo().performClick().performClick()
        compose.onNodeWithTag("genesis-modifier-declaration").performScrollTo().assertIsOff()
        button("Review modifier explanation").assertIsNotEnabled()
        // This preview still has the same fingerprint: refreshing must discard pending selections too.
        button("Revise review").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(candidateTag).fetchSemanticsNodes().singleOrNull()
            ?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off }
        compose.onNodeWithTag(candidateTag).performScrollTo().assertIsOff()
        assertEquals(before, runBlocking { env.authority.backup.snapshot() })
    }
}
