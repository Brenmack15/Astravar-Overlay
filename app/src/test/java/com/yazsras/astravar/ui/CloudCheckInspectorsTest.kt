package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import kotlinx.serialization.json.*
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
@Config(sdk=[35],qualifiers="w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudCheckInspectorsTest {
    @get:Rule val compose=createAstravarComposeRule()
    private fun json(s: String)=Json.parseToJsonElement(s).jsonObject
    @Test fun trainingBindsOnlyOneExistingKeyAndRetainsItsSource() {
        val state=json("""{"saveProficiencies":["INT","CON"],"training":[{"category":"save","key":"INT","source":"original class"},{"category":"tool","key":"Archive tools","source":"research"}]}""")
        val save=cloudTrainingDraft("actor","save","INT",state)
        assertEquals(json("""{"actorId":"actor","category":"save","key":"INT","trained":true,"source":"original class"}"""), save)
        assertEquals("research",cloudTrainingDraft("actor","tool","Archive tools",state)["source"]?.jsonPrimitive?.content)
        val unknown=cloudTrainingDraft("actor","tool",null,state)
        assertFalse("trained" in unknown);assertFalse("source" in unknown);assertFalse("saveProficiencies" in save)
        assertEquals(2,state.getValue("saveProficiencies").jsonArray.size)
    }
    @Test fun unifiedInspectorOffersSaveExpertiseAndExistingToolWithoutReplacingTheRoster() {
        val data=json("""{"saveProficiencies":["INT","CON"],"training":[{"category":"save","key":"INT","source":"Class record"},{"category":"tool","key":"Cartography","source":"Field training"}]}""")
        val sheet=json("""{"skillDefinitions":{"history":{"name":"History","ability":"INT","rank":2},"athletics":{"name":"Athletics","ability":"STR","rank":1}}}""")
        val choices=mutableListOf<Pair<String,JsonObject>>()
        val a=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            a.get().setContent {AstravarTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudAbilityTraining("actor","INT",data,sheet,true){name,fields->choices+=name to fields}
            }}}
            compose.onNodeWithText("Saving throw proficiency · trained").performClick()
            assertEquals("set_actor_training",choices.single().first)
            assertEquals("INT",choices.single().second.getValue("key").jsonPrimitive.content)
            compose.onNodeWithText("Skills and expertise").performClick()
            compose.onNodeWithText("History · expertise").performScrollTo().performClick()
            assertEquals("set_skill_training",choices.last().first)
            assertEquals(2,choices.last().second.getValue("rank").jsonPrimitive.int)
            compose.onNodeWithText("Tools and other training").performScrollTo().performClick()
            compose.onNodeWithText("Tool · Cartography").performScrollTo().performClick()
            assertEquals("Field training",choices.last().second.getValue("source").jsonPrimitive.content)
            assertEquals("tool",choices.last().second.getValue("category").jsonPrimitive.content)
            assertTrue(choices.none{(_,args)->"training" in args || "saveProficiencies" in args})
        } finally { a.pause().stop().destroy() }
    }

    @Test fun actualPassiveUsesSeparateActiveValueAndShowsInactiveSourcesWithoutCommitting() {
        val sheet=json("""{"passives":{"perception":{"value":20,"adjustment":0,"mode":"normal","sources":[{"id":"feat","source":"Fixture feat","value":5}]},"investigation":{"value":29},"insight":{"value":19}},"skills":{"perception":{"value":5,"ability":"WIS","rank":1}},"abilities":{"WIS":{"modifier":0}},"proficiency":4,"activeEffects":["feat"],"suppressedEffects":["old"]}""")
        val sources=mapOf("feat" to json("""{"id":"feat","title":"Known feat","data":{"target":"passive","key":"perception","source":"Fixture feature"}}"""),
            "old" to json("""{"id":"old","title":"Darkness sensor","data":{"target":"skill","key":"perception","source":"Fixture optics","contextTag":"thermal"}}"""))
        val requests=mutableListOf<Pair<String,JsonObject>>()
        val a=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            a.get().setContent {AstravarTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudPassiveChecks("actor",sheet,true,{sources[it]},{op,input->requests+=op to input},{fail("Unexpected source navigation")})
            }}}
            compose.onNodeWithText("Perception").performClick()
            compose.onNodeWithText("Passive total · 20").assertIsDisplayed()
            compose.onNodeWithText("Active check · 5").assertIsDisplayed()
            compose.onNodeWithText("Passive-only sources").assertExists()
            compose.onNodeWithText("Darkness sensor · inactive").performScrollTo().performClick()
            compose.onNodeWithText("Required circumstance · thermal").assertExists()
            assertTrue(requests.isEmpty())
            compose.onNodeWithText("Add passive-only modifier").performScrollTo().performClick()
            assertEquals("define_effect",requests.single().first)
            val args=requests.single().second
            assertEquals("passive",args.getValue("target").jsonPrimitive.content)
            assertEquals("perception",args.getValue("key").jsonPrimitive.content);assertFalse("amount" in args)
        } finally { a.pause().stop().destroy() }
    }
}
