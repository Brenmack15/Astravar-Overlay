package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yazsras.astravar.rules.Profile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class BaseDamageUpgradeTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun discreteSliderExposesAllTenValuesAndUpdatesEveryPreview() {
        val profile=mutableStateOf(Profile())
        val persisted=mutableListOf<Int>()
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                BaseDamageUpgrade(profile.value) { count ->
                    persisted+=count
                    profile.value=profile.value.copy(baseDamageDiceCount=count)
                }
            } }
            val slider=compose.onNodeWithContentDescription("Base weapon damage")
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(2f,1f..10f,8)))
            for(count in 1..10) {
                slider.performSemanticsAction(SemanticsActions.SetProgress) {it(count.toFloat())}
                slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"${count}d10"))
                compose.onNodeWithText("Base Damage: ${count}d10+5 Force").assertIsDisplayed()
                compose.onNodeWithText("Discharge: ${8+2*(count-1)}d10 Force").assertIsDisplayed()
                compose.onNodeWithText("Shatter: ${24+6*(count-1)}d10 Force").assertIsDisplayed()
                compose.runOnIdle {assertEquals(count,profile.value.baseDamageDiceCount)}
            }
            assertEquals((1..10).toList(),persisted)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun fractionalAndOutOfRangeSliderInputsOnlyCommitBoundedWholeDice() {
        val profile=mutableStateOf(Profile())
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                BaseDamageUpgrade(profile.value) {profile.value=profile.value.copy(baseDamageDiceCount=it)}
            } }
            val slider=compose.onNodeWithContentDescription("Base weapon damage")
            for((input,expected) in listOf(7.2f to 7,9.8f to 10,-5f to 1,30f to 10)) {
                slider.performSemanticsAction(SemanticsActions.SetProgress) {it(input)}
                compose.runOnIdle {assertEquals(expected,profile.value.baseDamageDiceCount);profile.value.validate()}
            }
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun touchDragPreviewsImmediatelyAndCommitsOneWholeCountOnRelease() {
        val profile=mutableStateOf(Profile())
        val persisted=mutableListOf<Int>()
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                BaseDamageUpgrade(profile.value) {
                    persisted+=it;profile.value=profile.value.copy(baseDamageDiceCount=it)
                }
            } }
            val slider=compose.onNodeWithContentDescription("Base weapon damage")
            slider.performTouchInput {down(center);moveTo(Offset(width-1f,center.y),delayMillis=100)}
            compose.onNodeWithText("Base Damage: 10d10+5 Force").assertIsDisplayed()
            compose.onNodeWithText("Discharge: 26d10 Force").assertIsDisplayed()
            compose.onNodeWithText("Shatter: 78d10 Force").assertIsDisplayed()
            compose.runOnIdle {assertTrue(persisted.isEmpty())}
            slider.performTouchInput {up()}
            compose.runOnIdle {assertEquals(listOf(10),persisted);assertEquals(10,profile.value.baseDamageDiceCount)}
        } finally {activity.pause().stop().destroy()}
    }
}
