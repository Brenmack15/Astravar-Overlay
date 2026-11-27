package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudSheetAccessibilityInteropTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun node(m: SemanticsMatcher): SemanticsNodeInteraction {
        try { compose.waitUntil(15000) { compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: ComposeTimeoutException) { throw AssertionError("Missing $m\n"+compose.onAllNodes(isRoot()).printToString(maxDepth=7), e) }
        return compose.onAllNodes(m).onFirst()
    }
    private val dialog = hasAnyAncestor(isDialog())
    private fun hit(m: SemanticsMatcher): SemanticsNodeInteraction {
        val found = node(m)
        return if (compose.onAllNodes(m and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isEmpty()) found else found.performScrollTo()
    }
    private fun press(label: String) = hit(dialog and hasText(label) and hasClickAction()).assertIsEnabled().performClick()
    private fun field(label: String) = hit(dialog and hasText(label) and hasSetTextAction())
    private fun skill(id:String) = hit(hasTestTag("skill-name-$id")).assertIsDisplayed().performClick()
    private fun select(actorName:String) {
        node(hasContentDescription("Character administration")).performClick()
        press(actorName)
        node(hasText(actorName) and hasClickAction()).assertExists()
    }
    private fun fixture(run:(AscensionWorkerFixture)->Unit) {
        AscensionWorkerFixture.open(ApplicationProvider.getApplicationContext<Context>()).use { f ->
            fun seed(op:String, raw:String) { val r=f.command(op,json(raw)); assertEquals(r.toString(),"COMMITTED",r.cloudText("status")) }
            for((id,name,role) in listOf(Triple("a-artisan","Fixture artisan","player"),Triple("b-companion","Fixture companion","companion"))) {
                seed("define_actor","""{"actorId":"$id","name":"$name","role":"$role","rulesProfile":"legacy_2014","level":5,"abilities":{"STR":21,"DEX":14,"CON":8,"INT":16,"WIS":12,"CHA":10},"currentHp":16,"maxHp":20,"temporaryHp":0}""")
            }
            seed("set_skill_training","""{"actorId":"a-artisan","skillId":"tool-kit","name":"Tinker's Tools","ability":"INT","rank":2}""")
            seed("set_skill_training","""{"actorId":"a-artisan","skillId":"precision","name":"Fine component precision calibration","ability":"DEX","rank":0}""")
            seed("set_skill_training","""{"actorId":"b-companion","skillId":"tool-kit","name":"Companion tool","ability":"WIS","rank":1}""")
            seed("set_skill_pin","""{"actorId":"a-artisan","skillId":"tool-kit","pinned":true}""")
            val first=runBlocking { f.refresh() }
            val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent {
                    val seq by f.client.changes.collectAsState()
                    var current by remember { mutableStateOf(first) }
                    LaunchedEffect(seq) { f.client.workspace()?.let{current=it} }
                    AstravarTheme { CloudWorkspace(f.client,current) }
                }
                node(hasText("Cloud · revision "+first.version.revision)).assertExists()
                run(f)
            } finally {activity.pause().stop().destroy()}
        }
    }
    private fun revision(f:AscensionWorkerFixture)=f.dm("get_cloud_state",json("""{"limit":1}""")).getValue("revision").jsonPrimitive.long
    private fun data(f:AscensionWorkerFixture,id:String)=requireNotNull(f.record(id)).getValue("data").jsonObject
    private fun rolls(f:AscensionWorkerFixture)=f.dm("get_cloud_state",json("""{"kind":"roll"}""")).getValue("records").jsonArray
    private fun countable(f:AscensionWorkerFixture,op:String):JsonObject {
        val sent=f.signal("observedDeviceCommand",json("""{"operation":"$op"}"""))
        val receipt=f.receipt(sent.cloudText("commandId"))
        assertEquals("COMMITTED",receipt.cloudText("status"))
        assertEquals(receipt,f.signal("redeliverDeviceCommand",buildJsonObject{put("commandId",sent.getValue("commandId"))}))
        return sent
    }

    @Test @Config(sdk=[35],qualifiers="w420dp-h900dp-xhdpi")
    fun selectedToolEditorPreservesTheExistingAbilityAndTrainingThroughCancelEditRollAndActorChange() = fixture { f ->
        select("Fixture artisan")
        val before=revision(f)
        val other=data(f,"b-companion")
        val initial=data(f,"a-artisan")
        val knownRows=listOf("tool-kit","precision","investigation")
        val bounds=knownRows.map{ node(hasTestTag("skill-name-$it")).getUnclippedBoundsInRoot() }
        assertTrue(bounds[0].top<bounds[1].top && bounds[0].top<bounds[2].top)
        assertEquals(bounds[0].left,bounds[1].left);assertEquals(bounds[1].left,bounds[2].left)
        compose.onAllNodesWithText("Edit",substring=false).assertCountEquals(0)
        skill("precision");node(dialog and hasText("Pin skill")).assertExists();press("Close")
        skill("investigation");node(dialog and hasText("Roll options · Investigation")).assertExists();press("Close")
        skill("tool-kit");node(dialog and hasText("Unpin skill")).assertExists();press("Edit skill training")
        field("Name").assertTextContains("Tinker's Tools");field("Rank").assertTextContains("2")
        node(dialog and hasText("INT") and hasClickAction()).assertExists()
        field("Name").performTextClearance()
        node(dialog and hasText("Submit") and hasClickAction()).assertIsNotEnabled()
        press("Cancel");press("Discard")
        assertEquals(initial,data(f,"a-artisan"));assertEquals(before,revision(f));assertTrue(rolls(f).isEmpty())
        skill("tool-kit");press("Edit skill training")
        field("Name").performTextReplacement("Fine clockwork tools")
        press("INT");hit(hasText("DEX") and hasClickAction() and hasAnyAncestor(isPopup())).performClick();press("Submit")
        node(dialog and hasText("Transaction result"));press("Close")
        val trained=countable(f,"set_skill_training").getValue("args").jsonObject
        assertEquals("a-artisan",trained.cloudText("actorId"));assertEquals("tool-kit",trained.cloudText("skillId"))
        assertEquals("DEX",trained.cloudText("ability"));assertEquals(2,trained.getValue("rank").jsonPrimitive.int)
        assertEquals(before+1,revision(f));assertEquals(other,data(f,"b-companion"))
        node(hasContentDescription("Roll Fine clockwork tools +8")).assertExists()
        skill("tool-kit");field("Physical d20 faces, in order").performTextReplacement("12");press("Record physical dice")
        node(dialog and hasText("Transaction result"));press("Close")
        val rollId=countable(f,"record_check").getValue("args").jsonObject.cloudText("rollId")
        val roll=data(f,rollId)
        assertEquals("tool-kit",roll.cloudText("key"));assertEquals(20,roll.getValue("total").jsonPrimitive.int)
        assertEquals("player_physical",roll.cloudText("origin"));assertEquals(1,rolls(f).size)
        select("Fixture companion");node(hasContentDescription("Roll Companion tool +4")).assertExists()
        skill("tool-kit");node(dialog and hasText("Pin skill")).assertExists();press("Edit skill training")
        field("Name").assertTextContains("Companion tool");field("Rank").assertTextContains("1")
        node(dialog and hasText("WIS") and hasClickAction()).assertExists();press("Cancel");press("Discard")
        assertEquals(other,data(f,"b-companion"));assertEquals(before+2,revision(f))
        assertEquals(json("""{"actorId":"a","skillId":"id"}"""),cloudSkillTrainingDraft("a","id",json("""{"rank":null,"ability":null,"name":null}""")))
        assertEquals(JsonPrimitive(2),cloudSkillTrainingDraft("a","id",json("""{"rank":"2"}"""))["rank"])
        assertFalse("rank" in cloudSkillTrainingDraft("a","id",json("""{"rank":"unrecorded"}""")))
    }

    @Test @Config(sdk=[35],qualifiers="w860dp-h430dp-land-xhdpi")
    fun landscapeCharacterSectionsKeepAnAccessibleDieAboveNavigationForEachActor() = fixture { f ->
        val start=revision(f)
        val viewport=compose.onAllNodes(isRoot()).onFirst().getUnclippedBoundsInRoot()
        assertTrue("Actual landscape viewport: $viewport",viewport.right - viewport.left > viewport.bottom - viewport.top)
        val sections=listOf("Sheet","Actions","Equipment","Spells","Resources","Forms","History")
        for(name in listOf("Fixture artisan","Fixture companion")) {
            select(name)
            for(section in sections) {
                val sectionTab=hit(hasTestTag("section-$section"))
                sectionTab.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                node(hasTestTag("section-$section") and isSelected()).assertExists()
                val fab=node(hasContentDescription("Open Roll Manager"))
                fab.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                assertTrue(fab.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
                val die=fab.getUnclippedBoundsInRoot()
                val nav=node(hasTestTag("navigation-Character")).getUnclippedBoundsInRoot()
                assertTrue("Die $die must clear navigation $nav",die.bottom<=nav.top)
                fab.performTouchInput { click(center) }
                node(dialog and hasText("Roll Manager")).assertExists();press("Close")
                node(hasTestTag("section-$section") and isSelected()).assertExists()
            }
        }
        hit(hasTestTag("section-Sheet")).performClick()
        node(hasTestTag("section-Sheet") and isSelected()).assertExists()
        val pager=SemanticsMatcher.keyIsDefined(SemanticsActions.PageRight)
        val page=node(pager).assertIsDisplayed()
        page.performTouchInput { swipeLeft() }
        node(hasTestTag("section-Actions") and isSelected()).assertExists()
        page.performTouchInput { swipeRight() }
        node(hasTestTag("section-Sheet") and isSelected()).assertExists()
        assertEquals(start,revision(f));assertTrue(rolls(f).isEmpty())
    }

    @Test @Config(sdk=[35],qualifiers="w400dp-h960dp-xhdpi")
    fun cardTitleScoreAndBorderInspectWhileNestedSaveAndCheckOwnTheirGestures() = fixture { f ->
        select("Fixture artisan")
        val before=revision(f)
        node(hasContentDescription("Strength ability check +5")).assertExists()
        val hp=node(hasContentDescription("Hit points, 16 of 20")).getUnclippedBoundsInRoot()
        val conditions=node(hasText("Conditions") and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("Conditions $conditions should sit below HP $hp",conditions.top>=hp.bottom && conditions.left<hp.right && conditions.right>hp.left)
        val card=compose.onNodeWithTag("ability-card-STR",useUnmergedTree=true)
        val outer=hasAnyAncestor(hasTestTag("ability-card-STR"))
        val actions=listOf<()->Unit>(
            {compose.onNode(outer and hasText("STRENGTH"),useUnmergedTree=true).performScrollTo().performTouchInput{click(center)}},
            {compose.onNode(outer and hasText("21"),useUnmergedTree=true).performScrollTo().performTouchInput{click(center)}},
            {card.performScrollTo().performTouchInput{click(Offset(1f,height*.50f))}}
        )
        actions.forEach { action->
            action();node(dialog and hasText("Strength · Inspector & Training")).assertExists()
            for(label in listOf("Check","Saving throw","Edit base score","Skills and expertise","Tools and other training"))
                node(dialog and hasText(label)).assertExists()
            press("Close");assertEquals(before,revision(f));assertTrue(rolls(f).isEmpty())
        }
        val check=hit(hasContentDescription("Strength ability check +5"))
        check.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput{longClick()}
        press("Disadvantage");node(dialog and hasText("2 d20 · Disadvantage")).assertExists()
        press("Close");assertEquals(before,revision(f))
        val save=hit(hasContentDescription("Strength saving throw +5, not proficient"))
        save.performSemanticsAction(SemanticsActions.OnLongClick)
        press("Advantage");field("Physical d20 faces, in order").performTextReplacement("1,17")
        press("Record physical dice");node(dialog and hasText("Transaction result"))
        compose.onAllNodesWithText("Strength · Inspector & Training").assertCountEquals(0)
        val sent=countable(f,"record_check").getValue("args").jsonObject
        val rolled=data(f,sent.cloudText("rollId"))
        assertEquals("save",rolled.cloudText("kind"));assertEquals("STR",rolled.cloudText("key"))
        assertEquals("player_physical",rolled.cloudText("origin"));assertEquals(22,rolled.getValue("total").jsonPrimitive.int)
        val faces=rolled.getValue("faces").jsonArray.map {it.jsonObject.getValue("value").jsonPrimitive.int}
        assertEquals(listOf(1,17),faces);assertEquals(1,rolls(f).size);assertEquals(before+1,revision(f))
    }
}
