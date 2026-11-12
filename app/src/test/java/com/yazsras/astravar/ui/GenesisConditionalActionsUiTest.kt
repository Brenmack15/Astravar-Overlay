package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisConditionalActionsUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun withSpell(spell: SpellDefinition, test: (ReforgeRepository, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val playableDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val repository = CampaignLedgerRepository(db)
        val playable = ReforgeRepository(playableDb)
        val actor = ReforgeCharacter("fictional-spell-visibility", "Fictional mage", spells = listOf(spell),
            spellSlots = mapOf(1 to 2), slotMaximaOverride = mapOf(1 to 2))
        runBlocking { playable.create(actor); ReforgePreferences.open(context).selectProfile(actor.id) }
        val previousSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { ReforgeCharacterSheet(repository, playable, initialSection = "Spells") } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(spell.name).fetchSemanticsNodes().isNotEmpty() }
            test(playable, actor.id)
        } finally {
            activity.pause().stop().destroy(); db.close(); playableDb.close()
            if (previousSize == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", previousSize)
        }
    }

    @Test fun knownLeveledSpellOffersPreparationThenUsesTheSameSavedSpellForCasting() =
        withSpell(SpellDefinition("ward", "Fictional ward", 1)) { playable, actorId ->
            compose.onNodeWithText("Prepare").performScrollTo().performClick()
            compose.onNode(hasText("Cast") and hasAnyAncestor(isDialog())).assertDoesNotExist()
            compose.onNodeWithText("Prepare this spell before casting.").assertIsDisplayed()
            assertEquals(1L, runBlocking { playable.get(actorId) }!!.revision)
            compose.onNode(hasText("Prepare") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { runBlocking { playable.get(actorId) }!!.character.spells.single().preparation == SpellPreparation.PREPARED }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Cast") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Cast") and hasAnyAncestor(isDialog())).assertIsEnabled()
            val saved = runBlocking { playable.get(actorId) }!!
            assertEquals("ward", saved.character.spells.single().id)
            assertEquals(2, saved.character.spellSlots[1])
            assertEquals(2L, saved.revision)
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Cast").performScrollTo().assertIsDisplayed()
        }

    @Test fun knownCantripRemainsCastableWithoutOfferingPreparation() =
        withSpell(SpellDefinition("spark", "Fictional spark", 0)) { playable, actorId ->
            compose.onNodeWithText("Prepare").assertDoesNotExist()
            compose.onNodeWithText("Cast").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Fictional spark").performClick()
            compose.onNode(hasText("Prepare") and hasAnyAncestor(isDialog())).assertDoesNotExist()
            compose.onNode(hasText("Cast") and hasAnyAncestor(isDialog())).assertIsEnabled()
            assertEquals(1L, runBlocking { playable.get(actorId) }!!.revision)
        }

    @Test fun alwaysPreparedSpellHasNoRedundantPreparationAction() =
        withSpell(SpellDefinition("gift", "Fictional gift", 1, SpellPreparation.ALWAYS_PREPARED)) { playable, actorId ->
            compose.onNodeWithText("Fictional gift").performScrollTo().performClick()
            compose.onNode(hasText("Cast") and hasAnyAncestor(isDialog())).assertIsEnabled()
            compose.onNode(hasText("Prepare") and hasAnyAncestor(isDialog())).assertDoesNotExist()
            compose.onNode(hasText("Unprepare") and hasAnyAncestor(isDialog())).assertDoesNotExist()
            assertEquals(1L, runBlocking { playable.get(actorId) }!!.revision)
        }
}
