package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.builtins.*
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
class ReforgeDraftRestorationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun compoundTrainingAndLargeEffectEditsSurviveFullCompositionRestoration() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(true)
        var captured: Pair<Map<String, SkillTraining>, List<EffectDefinition>>? = null
        val effect = EffectDefinition("draft-effect", "Confirmed draft", RuleSource("draft-source", "Synthetic source ".repeat(1000), Acceptance.ACCEPTED_HOMEBREW),
            EffectPayload.Number(NumericTarget.ABILITY, NumberOperation.SET, 21, "STR"))
        try {
            controller.get().setContent {
                if (visible.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AstravarTheme {
                        val training = rememberReforgeDraft(emptyMap<String, SkillTraining>(), MapSerializer(String.serializer(), SkillTraining.serializer()))
                        val effects = rememberReforgeDraft(emptyList<EffectDefinition>(), ListSerializer(EffectDefinition.serializer()))
                        Page {
                            PlayerButton(onClick = {
                                training.value = mapOf("Arcana" to SkillTraining(Stat.INT, proficient = true, expertise = true))
                                effects.value = listOf(effect)
                            }) { androidx.compose.material3.Text("Edit draft fixture") }
                            PlayerButton(enabled = training.canSave && effects.canSave, onClick = { captured = training.value to effects.value }) { androidx.compose.material3.Text("Read restored fixture") }
                        }
                    }
                }
            }
            compose.onNodeWithText("Edit draft fixture").performClick()
            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { saved = registry.performSave(); visible.value = false }
            compose.waitForIdle()
            val strings = saved.values.flatten().filterIsInstance<String>()
            assertTrue(strings.none { it.length > UiDraftTextStore.INLINE_BYTES })
            assertTrue(strings.any { it.startsWith("astravar-ui-draft:v1:f:") })
            compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("Read restored fixture").assertIsEnabled().performClick()
            assertTrue(requireNotNull(captured).first.getValue("Arcana").expertise)
            assertEquals(listOf(effect), requireNotNull(captured).second)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun failedRecoveryCannotBeEditedOrSavedAsAnEmptySuccessfulDraft() {
        val text = UiDraftTextState(null, "Synthetic missing draft")
        val draft = ReforgeTypedDraft(text, emptySet<Stat>(), SetSerializer(Stat.serializer()))
        assertFalse(draft.canSave)
        draft.value = setOf(Stat.INT)
        assertFalse(draft.canSave)
        assertTrue(draft.value.isEmpty())
        draft.reloadSavedValues()
        assertTrue(draft.canSave)
        assertTrue(draft.value.isEmpty())
    }
}
