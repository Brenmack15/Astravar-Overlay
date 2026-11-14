package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisSkillsUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun action(label: String) = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun capture(name: String, activity: ComponentActivity) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, activity) }
    }

    @Test fun sheetCreatesPinsRollsEditsAndRemovesCustomSkillWithoutChangingOfficialSkills() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledgerDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        val localDb = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        val repository = ReforgeRepository(localDb)
        val actorId = "fictional-skills-ui"
        val initial = ReforgeCharacter(actorId, "Fictional artisan", level = 9, baseAbilities = Stat.entries.associateWith { 20 },
            skills = mapOf("Arcana" to SkillTraining(Stat.INT, true)), currentHp = 18)
        runBlocking { repository.create(initial); ReforgePreferences.open(context).selectProfile(actorId) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { AstravarTheme { ReforgeCharacterSheet(CampaignLedgerRepository(ledgerDb), repository) } }
            waitFor("Add skill")
            compose.onNodeWithText("Add skill").performScrollTo().performClick()
            field("Skill name").performTextReplacement("Engineering")
            action("Untrained").performScrollTo().performClick()
            compose.onNodeWithText("Expertise").performClick()
            field("Additional bonus").performScrollTo().performTextReplacement("1")
            field("Notes (optional)").performScrollTo().performTextReplacement("Fictional workshop training")
            capture("genesis-custom-skill-editor", controller.get())
            action("Save skill").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(actorId)!!.character.customSkills.isNotEmpty() } }
            val created = runBlocking { repository.get(actorId)!!.character.customSkills.single() }
            assertEquals("Engineering", created.name)
            compose.onNodeWithTag("skill-pin-${created.id}").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { created.id in repository.get(actorId)!!.character.pinnedSkillIds } }
            compose.onNodeWithTag("skill-roll-${created.id}").performScrollTo()
            capture("genesis-pinned-skills", controller.get())
            compose.onNodeWithTag("skill-roll-${created.id}").performScrollTo().performClick()
            action("Enter physical dice faces").performScrollTo().performClick()
            field("Faces in order (comma separated)").performScrollTo().performTextReplacement("12")
            action("Validate entered dice").performScrollTo().performClick()
            compose.onNodeWithTag("reforge-roll-result").assertTextEquals("26")
            action("Save roll").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { repository.rollHistory(actorId).first().isNotEmpty() } }
            compose.onNodeWithTag("skill-edit-${created.id}").performScrollTo().performClick()
            field("Skill name").performTextReplacement("Runecraft")
            action("Save skill").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(actorId)!!.character.customSkills.single().name == "Runecraft" } }
            compose.onNodeWithTag("skill-edit-${created.id}").performScrollTo().performClick()
            action("Remove skill").performClick()
            compose.onNodeWithText("Remove", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { runBlocking { repository.get(actorId)!!.character.customSkills.isEmpty() } }
            runBlocking {
                val saved = repository.get(actorId)!!.character
                assertEquals(initial.skills, saved.skills)
                assertEquals(initial.currentHp, saved.currentHp)
                assertTrue(created.id !in saved.pinnedSkillIds)
                assertEquals("Engineering", repository.rollHistory(actorId).first().single().label)
                assertEquals(26, repository.rollHistory(actorId).first().single().total)
            }
        } finally { controller.pause().stop().destroy(); localDb.close(); ledgerDb.close() }
    }
}
