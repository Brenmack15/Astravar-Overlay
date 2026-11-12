package com.yazsras.astravar.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp", application = AstravarApp::class)
@LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisEncounterNavigationUiTest {
    @get:Rule val compose = createAstravarComposeRule()
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String, scroll: Boolean = false, last: Boolean = false) {
        val selector = hasText(text) and hasClickAction()
        compose.waitUntil(15_000) { compose.onAllNodes(selector and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val node = if (last) compose.onAllNodes(selector).onLast() else compose.onNode(selector)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }
    private fun selected(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun state(controller: ActivityController<ComponentActivity>): Bundle {
        val state = Bundle(); controller.pause().saveInstanceState(state).resume()
        val writer = Parcel.obtain()
        val bytes = try { state.writeToParcel(writer, 0); writer.marshall() } finally { writer.recycle() }
        val reader = Parcel.obtain()
        return try {
            reader.unmarshall(bytes, 0, bytes.size); reader.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(reader).also { it.classLoader = ComponentActivity::class.java.classLoader }
        } finally { reader.recycle() }
    }

    @OptIn(androidx.room.ExperimentalRoomApi::class)
    @Test(timeout = 90_000) fun directEncounterEntryAndRecreatedActorBackPreserveMainSelectionAndTurn() {
        val app = ApplicationProvider.getApplicationContext<AstravarApp>()
        val serviceIntent = Intent(app, MultiInstanceInvalidationService::class.java)
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(serviceIntent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), service.get().onBind(serviceIntent))
        val authority = app.genesisStorage.authority
        val prefs = ReforgePreferences.open(app)
        val hero = "fictional-route-hero"
        val owl = "fictional-route-owl"
        val encounter = runBlocking {
            val campaign = authority.createCampaign("Fictional route campaign")
            authority.createActor("Route Hero", GenesisActorRole.PLAYER, campaign.id,
                playable = ReforgeCharacter(hero, "Route Hero", baseMaxHp = 10, currentHp = 4))
            authority.createActor("Route Owl", GenesisActorRole.COMPANION, campaign.id, hero,
                ReforgeCharacter(owl, "Route Owl", baseMaxHp = 5, currentHp = 2))
            val npc = authority.createActor("Route Witness", GenesisActorRole.NPC, campaign.id)
            val draft = authority.encounters.saveDraft("fictional-route-encounter", null, campaign.id, "Fictional route encounter", "",
                listOf(hero, owl, npc.actor.actorId).map { GenesisEncounterParticipantInput(it, requireNotNull(authority.actor(it)).revision) })
            prefs.selectCampaign(campaign.id); prefs.selectProfile(hero)
            authority.encounters.start(authority.encounters.reviewStart(draft.record.id, draft.record.revision))
        }
        val heroBefore = runBlocking { app.genesisStorage.playable.get(hero) }
        val owlBefore = runBlocking { app.genesisStorage.playable.get(owl) }
        var controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun mount() { controller.get().setContent { AstravarScreen(app, {}, {}, {}) } }
        fun back() { compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() } }
        try {
            mount()
            compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); app.repository.state.value != null }
            val weaponBefore = app.repository.state.value
            compose.onNodeWithTag("navigation-Campaign").performClick()
            click("Encounters")
            click("Fictional route encounter", scroll = true)
            awaitText("Current turn: Route Hero")
            click("View Route Witness's source", scroll = true)
            awaitText("This participant remains a source record. Encounter tracking does not create a playable sheet or invent statistics.")
            compose.onNodeWithText("Open Route Witness's actions").assertDoesNotExist()
            assertEquals(hero, runBlocking { prefs.activeProfileId.first() })
            click("Close", last = true)

            click("Open Route Owl's actions", scroll = true)
            selected("navigation-Companions"); selected("section-Combat")
            assertEquals(hero, runBlocking { prefs.activeProfileId.first() })
            val saved = state(controller)
            controller.pause().stop().destroy()
            controller = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
            mount()
            selected("section-Combat")
            back(); selected("section-Sheet")
            compose.onNodeWithTag("navigation-Companions").assertIsSelected()
            compose.onNodeWithText("‹ Return to encounter").assertIsDisplayed()
            back(); selected("navigation-Campaign")
            awaitText("Current turn: Route Hero")
            assertEquals(hero, runBlocking { prefs.activeProfileId.first() })

            click("Open Route Hero's actions", scroll = true)
            selected("navigation-Character"); selected("section-Combat")
            back(); selected("section-Sheet")
            back(); selected("navigation-Campaign")
            awaitText("Current turn: Route Hero")
            click("Open Route Owl's actions", scroll = true)
            selected("section-Combat")
            click("‹ Return to encounter")
            selected("navigation-Campaign"); awaitText("Current turn: Route Hero")
            assertEquals(hero, runBlocking { prefs.activeProfileId.first() })
            assertEquals(encounter, runBlocking { authority.encounters.get(encounter.record.id) })
            assertEquals(heroBefore, runBlocking { app.genesisStorage.playable.get(hero) })
            assertEquals(owlBefore, runBlocking { app.genesisStorage.playable.get(owl) })
            assertEquals(weaponBefore, app.repository.state.value)
        } finally { controller.pause().stop().destroy(); service.destroy() }
    }
}
