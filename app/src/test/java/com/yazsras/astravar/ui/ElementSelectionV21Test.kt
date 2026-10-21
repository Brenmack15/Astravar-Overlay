package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yazsras.astravar.rules.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ElementSelectionV21Test {
    @get:Rule val compose = createEmptyComposeRule()
    private val profile = Profile(character = "Synthetic element review", level = 10,
        confirmed = setOf(Upgrade.ADVANCED_ELEMENTS, Upgrade.DUAL_RESONANCE))
    private val pair = listOf(DamageType.FIRE, DamageType.COLD)

    @Test fun allUnlockedAdvancedElementsRemainSelectableAlongsideDualResonance() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            for (type in listOf(DamageType.RADIANT, DamageType.PSYCHIC, DamageType.NECROTIC)) {
                var selected = pair
                var closed = false
                activity.get().setContent { AstravarTheme {
                    ElementWheelV2(profile, pair, { closed = true }) { selected = elementSelectionAfterChoice(profile, pair, it) }
                } }
                compose.onNodeWithContentDescription("${type.name.lowercase().replaceFirstChar(Char::titlecase)} element").assertIsEnabled().performScrollTo().performClick()
                compose.runOnIdle { assertEquals(listOf(type), selected); assertTrue(closed) }
                val s = Campaign(onboarded = true, attuned = true, profile = profile,
                    installedId = "synthetic-nexus", cores = listOf(Core("synthetic-nexus", "Synthetic nexus")))
                assertEquals(listOf(type), Engine.plan(s, "synthetic-${type.name}", Ability.ELEMENTAL, elements = selected).elements)
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun basicPairsAndReturnFromAdvancedSelectionFollowAcceptedCombinationRules() {
        assertEquals(pair, elementSelectionAfterChoice(profile, listOf(DamageType.FIRE), DamageType.COLD))
        assertEquals(listOf(DamageType.COLD, DamageType.ACID), elementSelectionAfterChoice(profile, pair, DamageType.ACID))
        for (advanced in listOf(DamageType.RADIANT, DamageType.PSYCHIC, DamageType.NECROTIC)) {
            assertEquals(listOf(DamageType.THUNDER), elementSelectionAfterChoice(profile, listOf(advanced), DamageType.THUNDER))
            assertEquals(emptyList<DamageType>(), elementSelectionAfterChoice(profile, listOf(advanced), DamageType.FORCE))
        }
    }

    @Test fun selectionStillRequiresBothAcceptedAdvancedUnlockGates() {
        for (locked in listOf(profile.copy(level = 7), profile.copy(confirmed = setOf(Upgrade.DUAL_RESONANCE)))) {
            for (advanced in listOf(DamageType.RADIANT, DamageType.PSYCHIC, DamageType.NECROTIC)) {
                assertThrows(IllegalArgumentException::class.java) { elementSelectionAfterChoice(locked, pair, advanced) }
            }
        }
    }
}
