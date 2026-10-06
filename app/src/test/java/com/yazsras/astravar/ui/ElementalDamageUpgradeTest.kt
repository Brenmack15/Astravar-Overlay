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
@Config(sdk=[35],qualifiers="w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ElementalDamageUpgradeTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun elementalSliderHasUnitStopsAndAllTenLabelsWithIndependentLivePreview() {
        val profile=mutableStateOf(Profile(baseDamageDiceCount=3))
        val commits=mutableListOf<Int>()
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                WeaponDamageUpgrades(profile.value,onBaseCountSelected={profile.value=profile.value.copy(baseDamageDiceCount=it)},onSelectorCountSelected={
                    commits+=it;profile.value=profile.value.copy(elementalSelectorDiceCount=it)
                })
            } }
            val slider=compose.onNodeWithContentDescription("Elemental selector damage")
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(2f,1f..10f,8)))
            for(m in 1..10) {
                slider.performSemanticsAction(SemanticsActions.SetProgress) {it(m.toFloat())}
                slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"${m}d8"))
                compose.onNodeWithText("3d10+5 Force + ${m}d8 selected element").assertIsDisplayed()
                compose.onNodeWithText("Discharge: 12d10 Force").assertIsDisplayed()
                compose.onNodeWithText("Shatter: 36d10 Force").assertIsDisplayed()
                compose.runOnIdle {assertEquals(m,profile.value.elementalSelectorDiceCount);assertEquals(3,profile.value.baseDamageDiceCount)}
            }
            assertEquals((1..10).toList(),commits)
            compose.onNodeWithContentDescription("Base weapon damage").performSemanticsAction(SemanticsActions.SetProgress) {it(4f)}
            compose.onNodeWithText("4d10+5 Force + 10d8 selected element").assertIsDisplayed()
            compose.runOnIdle {assertEquals(10,profile.value.elementalSelectorDiceCount)}
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun fractionalAndOutsideInputsPersistOnlyWholeSelectorCounts() {
        val profile=mutableStateOf(Profile(baseDamageDiceCount=7))
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                WeaponDamageUpgrades(profile.value,onBaseCountSelected={},onSelectorCountSelected={profile.value=profile.value.copy(elementalSelectorDiceCount=it)})
            } }
            val slider=compose.onNodeWithContentDescription("Elemental selector damage")
            for((input,expected) in listOf(7.2f to 7,9.8f to 10,-5f to 1,30f to 10)) {
                slider.performSemanticsAction(SemanticsActions.SetProgress) {it(input)}
                compose.runOnIdle {assertEquals(expected,profile.value.elementalSelectorDiceCount);assertEquals(7,profile.value.baseDamageDiceCount);profile.value.validate()}
            }
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun eachTouchDragUpdatesCombinedPreviewBeforeItsIndependentCommit() {
        val profile=mutableStateOf(Profile())
        val baseCommits=mutableListOf<Int>();val selectorCommits=mutableListOf<Int>()
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                WeaponDamageUpgrades(profile.value,onBaseCountSelected={baseCommits+=it;profile.value=profile.value.copy(baseDamageDiceCount=it)},onSelectorCountSelected={selectorCommits+=it;profile.value=profile.value.copy(elementalSelectorDiceCount=it)})
            } }
            val selector=compose.onNodeWithContentDescription("Elemental selector damage")
            selector.performTouchInput {down(center);moveTo(Offset(width-1f,center.y),delayMillis=100)}
            compose.onNodeWithText("2d10+5 Force + 10d8 selected element").assertIsDisplayed()
            compose.onNodeWithText("Discharge: 10d10 Force").assertIsDisplayed()
            compose.runOnIdle {assertTrue(selectorCommits.isEmpty());assertTrue(baseCommits.isEmpty())}
            selector.performTouchInput {up()}
            compose.runOnIdle {assertEquals(listOf(10),selectorCommits);assertEquals(2,profile.value.baseDamageDiceCount)}
            val base=compose.onNodeWithContentDescription("Base weapon damage")
            base.performTouchInput {down(center);moveTo(Offset(width-1f,center.y),delayMillis=100)}
            compose.onNodeWithText("10d10+5 Force + 10d8 selected element").assertIsDisplayed()
            compose.runOnIdle {assertTrue(baseCommits.isEmpty());assertEquals(listOf(10),selectorCommits)}
            base.performTouchInput {up()}
            compose.runOnIdle {assertEquals(listOf(10),baseCommits);assertEquals(10,profile.value.elementalSelectorDiceCount)}
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun recompositionReadsRestoredProfileAndDisabledSlidersCannotCommit() {
        val profile=mutableStateOf(Profile(baseDamageDiceCount=4,elementalSelectorDiceCount=7))
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme {
                WeaponDamageUpgrades(profile.value,enabled=false,onBaseCountSelected={fail("Disabled Force slider committed")},onSelectorCountSelected={fail("Disabled selector committed")})
            } }
            compose.onNodeWithText("4d10+5 Force + 7d8 selected element").assertIsDisplayed()
            compose.onNodeWithContentDescription("Elemental selector damage").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Base weapon damage").assertIsNotEnabled()
            compose.runOnIdle {profile.value=profile.value.copy(elementalSelectorDiceCount=10)}
            compose.onNodeWithText("4d10+5 Force + 10d8 selected element").assertIsDisplayed()
        } finally {activity.pause().stop().destroy()}
    }
}
