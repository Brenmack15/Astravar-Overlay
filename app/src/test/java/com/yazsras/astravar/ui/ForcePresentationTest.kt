package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import com.yazsras.astravar.rules.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h1200dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ForcePresentationTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun neutralAndDualPreviewsFollowSameProfileComponents() {
        val elements=mutableStateOf(emptyList<DamageType>())
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {WeaponDamageUpgrades(Profile(baseDamageDiceCount=4,level=10,confirmed=setOf(Upgrade.DUAL_RESONANCE)),elements=elements.value,onBaseCountSelected={},onSelectorCountSelected={})}}
            compose.onNodeWithText("NEUTRAL SHOT").assertIsDisplayed()
            compose.onNodeWithText("Discharge: 12d12 Force").assertIsDisplayed()
            compose.onNodeWithText("Shatter: 84d12 Force").assertIsDisplayed()
            compose.runOnIdle {elements.value=listOf(DamageType.FIRE,DamageType.LIGHTNING)}
            compose.onNodeWithText("4d12+5 Force + 2d8 Fire + 2d8 Lightning").assertIsDisplayed()
            compose.onNodeWithText("Discharge: 12d12 Force + 2d8 Fire + 2d8 Lightning").assertIsDisplayed()
            compose.onNodeWithText("Shatter: 84d12 Force + 2d8 Fire + 2d8 Lightning").assertIsDisplayed()
        } finally {activity.pause().stop().destroy()}
    }

    @Test(timeout=60000) fun largePoolStartsSummarizedAndExpandsOneTextComponentPerDamageType() {
        val s=Campaign(onboarded=true,attuned=true,installedId="core",profile=Profile(baseDamageDiceCount=10),cores=listOf(Core("core","Core")))
        val p=Engine.plan(s,"large",Ability.SHATTER,elements=listOf(DamageType.LIGHTNING),point="Gate")
        val rolled=DiceRoller.fire(s,p,die={it}).pending.single()
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {AstravarTheme {Column {DiceDetails(rolled)}}}
            compose.onNodeWithText("Show individual dice").assertIsDisplayed()
            compose.onAllNodes(hasText("Force blast",substring=true)).assertCountEquals(0)
            compose.onNodeWithText("Show individual dice").performClick()
            compose.onAllNodes(hasText("Force blast · 210d12 force",substring=true)).assertCountEquals(1)
            compose.onAllNodes(hasText("Selector · 2d8 lightning",substring=true)).assertCountEquals(1)
            compose.onNodeWithText("Hide individual dice").performClick()
            compose.onAllNodes(hasText("Force blast",substring=true)).assertCountEquals(0)
        } finally {activity.pause().stop().destroy()}
    }
}
