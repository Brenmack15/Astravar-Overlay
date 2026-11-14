package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
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
class GenesisArmorAdoptionAvailabilityTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun pristineOrRevisionOnlyStateDoesNotOfferArmorAdoption() {
        assertEquals(Campaign(), Campaign()) // All default nested records are deterministic.
        assertNull(genesisArmorReviewSource(null, listOf(DamageType.COLD)))
        assertNull(genesisArmorReviewSource(Campaign(), emptyList()))
        assertNull(genesisArmorReviewSource(Campaign(revision = 12, notes = " \n\t"), emptyList()))
    }

    @Test fun partialSavedConfigurationAndResourcesAreReturnedExactlyWithoutInitialization() {
        val fixtures = listOf(
            Campaign(profile = Profile(saveDc = 17)),
            Campaign(notes = "  Existing weapon note\n"),
            Campaign(slots = mapOf(1 to 0)),
            Campaign(gameSeconds = 60),
            Campaign(turn = Turn(bonus = true)),
            Campaign(attuned = true),
            Campaign(onboarded = true)
        )
        fixtures.forEach { original ->
            assertSame(original, genesisArmorReviewSource(original, emptyList()))
            assertTrue(original.cores.isEmpty())
            assertTrue(original.templates.isEmpty())
        }
        val elementOnly = Campaign(revision = 4)
        assertSame(elementOnly, genesisArmorReviewSource(elementOnly, listOf(DamageType.COLD)))
    }

    @Test fun partialConfigurationRequiresOwnerReviewAndDoesNotInventResourcesOrReadiness() {
        val actor = ReforgeCharacter("partial-armor-owner", "Fictional partial owner", revision = 4, level = 9,
            baseAbilities = mapOf(Stat.INT to 20), currentHp = 9, spellSlots = mapOf(1 to null), gameSeconds = 12)
        val partial = Campaign(revision = 19, profile = Profile(saveDc = 17),
            notes = "Original weapon reference", slots = mapOf(1 to 3), gameSeconds = 7)
        var legacy by mutableStateOf(Campaign())
        var elements by mutableStateOf(emptyList<DamageType>())
        var adopted: ReforgeCharacter? = null
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { Page {
                GenesisArmorAdoption(actor, legacy, elements) { next, _ -> adopted = next }
            } } }
            compose.onNodeWithText("Connect saved Astravar systems").assertDoesNotExist()
            compose.runOnIdle { legacy = partial; elements = listOf(DamageType.COLD) }
            compose.onNodeWithText("Connect saved Astravar systems").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-adopt-armor").assertIsNotEnabled()
            assertNull(adopted)
            compose.onNodeWithText("Source or confirmation note").performScrollTo().performTextInput("Reviewed original partial configuration")
            compose.onNodeWithTag("genesis-adopt-armor").assertIsNotEnabled()
            compose.onNodeWithText("These saved Astravar systems belong to Fictional partial owner").performScrollTo().performClick()
            compose.onNodeWithTag("genesis-adopt-armor").performClick()
            compose.runOnIdle {
                val result = requireNotNull(adopted)
                assertEquals(actor, result.copy(armor = null))
                assertEquals(partial, result.armor!!.campaign)
                assertEquals(partial, result.armor!!.originalCampaign)
                assertEquals(listOf(DamageType.COLD), result.armor!!.elements)
                assertFalse(result.armor!!.campaign.onboarded)
                assertFalse(result.armor!!.campaign.attuned)
                assertTrue(result.armor!!.campaign.cores.isEmpty())
                assertNull(result.spellSlots[1])
            }
        } finally { controller.pause().stop().destroy() }
    }
}
