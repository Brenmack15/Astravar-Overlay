package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReforgeIndependentAttackUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun fixedCompanionBonusCanBeCorrectedWithoutErasingDamageFlagsOrResources() {
        val damage = DamageComponent("confirmed-cold", "Cold strike", DamageKind.COLD, SecureDice.parse("2d6+3"), criticalDice = false, magical = true)
        val attack = AttackDefinition("confirmed-attack", "Fictional companion strike", proficient = false,
            damage = listOf(damage), addAbilityToDamage = false, fixedAttackBonus = 6, actionType = "Reaction")
        val original = ReforgeCharacter("independent-fixture", "Fictional companion", revision = 4,
            attacks = listOf(attack), resources = listOf(ResourcePool("charge", "Charge", 3, 1)), currentHp = null)
        var saved: ReforgeCharacter? = null
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        try {
            controller.get().setContent {
                if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme { ReforgeAttackEditor(original, {}, { saved = it }, attack.id) }
                }
            }
            compose.onNodeWithText("Attack ability").assertDoesNotExist()
            compose.onNodeWithText("Proficient").assertDoesNotExist()
            compose.onNodeWithText("Independent attack bonus").performScrollTo().performTextReplacement("10")
            compose.onNodeWithText("COLD").performScrollTo().performClick()
            compose.onNodeWithText("FIRE").performClick()
            var snapshot: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { snapshot = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry = SaveableStateRegistry(snapshot) { true }; visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("FIRE").performScrollTo().assertExists()
            compose.onNodeWithText("Save attack").assertIsDisplayed().performClick()
            val after = requireNotNull(saved)
            assertEquals(4L, after.revision)
            assertEquals(10, after.attacks.single().fixedAttackBonus)
            assertEquals(attack.id, after.attacks.single().id)
            assertEquals(damage.copy(label = attack.name, type = DamageKind.FIRE), after.attacks.single().damage.single())
            assertEquals("Reaction", after.attacks.single().actionType)
            assertEquals(original.resources, after.resources)
            assertNull(after.currentHp)
            assertEquals(10, AttackRules.preview(after, attack.id).attackBonus.value)
        } finally { controller.pause().stop().destroy() }
    }
}
