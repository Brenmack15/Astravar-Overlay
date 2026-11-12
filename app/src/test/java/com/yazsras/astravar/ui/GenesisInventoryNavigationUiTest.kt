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

/** The production inventory -> restored character route that failed on native code50. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp", application = AstravarApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisInventoryNavigationUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun awaitNode(matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val trees = (0 until roots.fetchSemanticsNodes().size)
                .joinToString("\n") { roots[it].printToString() }
            throw AssertionError("Inventory route did not reach its expected control.\n" + trees, failure)
        }
    }

    private fun click(text: String, modal: Boolean = false, scroll: Boolean = false) {
        val action = hasText(text) and hasClickAction() and isEnabled()
        val matcher = if (modal) action and hasAnyAncestor(isDialog()) else action
        awaitNode(matcher)
        val node = compose.onNode(matcher)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    private fun capture(name: String, activity: ComponentActivity) {
        if (System.getProperty("astravar.screenshots") == null) return
        compose.runOnIdle { invalidateNativeCaptureWindows() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { captureNativeTestWindows(name, activity) }
    }

    private fun savedState(controller: ActivityController<ComponentActivity>): Bundle {
        val state = Bundle()
        controller.pause().saveInstanceState(state).resume()
        val writer = Parcel.obtain()
        val bytes = try { state.writeToParcel(writer, 0); writer.marshall() } finally { writer.recycle() }
        val reader = Parcel.obtain()
        return try {
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(reader).also { it.classLoader = ComponentActivity::class.java.classLoader }
        } finally { reader.recycle() }
    }

    @OptIn(androidx.room.ExperimentalRoomApi::class)
    @Test(timeout = 120_000)
    fun inventoryCreationAndRepeatedDetailRoutesRetainExactItemAcrossRecreation() {
        val app = ApplicationProvider.getApplicationContext<AstravarApp>()
        val serviceIntent = Intent(app, MultiInstanceInvalidationService::class.java)
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(serviceIntent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), service.get().onBind(serviceIntent))
        val authority = app.genesisStorage.authority
        val preferences = ReforgePreferences.open(app)
        val hero = "fictional-inventory-route-hero"
        val other = "fictional-inventory-route-other"
        runBlocking {
            val campaign = authority.createCampaign("Fictional inventory route campaign")
            authority.createActor("Inventory Route Hero", GenesisActorRole.PLAYER, campaign.id,
                playable = ReforgeCharacter(hero, "Inventory Route Hero", baseMaxHp = 20, currentHp = 14))
            authority.createActor("Unrelated Route Hero", GenesisActorRole.PLAYER, campaign.id,
                playable = ReforgeCharacter(other, "Unrelated Route Hero", baseMaxHp = 9, currentHp = 3))
            preferences.selectCampaign(campaign.id)
            preferences.selectProfile(hero)
        }
        val otherBefore = runBlocking { app.genesisStorage.playable.get(other) }
        var controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun mount() { controller.get().setContent { AstravarScreen(app, {}, {}, {}) } }
        fun requireItemDialog() {
            awaitNode(hasText("Edit") and hasClickAction() and hasAnyAncestor(isDialog()))
            compose.onNode(hasText("Fictional route compass") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("That saved sheet is unavailable or archived. No other character was opened.").assertDoesNotExist()
            compose.onNodeWithTag("navigation-Character").assertIsSelected()
            compose.onNodeWithTag("section-Equipment").assertIsSelected()
            assertEquals(hero, runBlocking { preferences.activeProfileId.first() })
        }
        try {
            mount()
            compose.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                app.repository.error.value?.let { error("Weapon repository could not open: " + it) }
                app.repository.state.value != null
            }
            val weaponBefore = app.repository.state.value
            awaitNode(hasContentDescription("HP 14 / 20"))
            compose.onNodeWithTag("section-Sheet").assertIsSelected()
            compose.onNodeWithTag("navigation-Inventory").performClick()
            awaitNode(hasTestTag("genesis-inventory-list"))
            click("Add item")
            awaitNode(hasText("Item name") and hasSetTextAction())
            compose.onNodeWithText("Item name").performTextInput("Fictional route compass")
            click("Review & save", modal = true)
            click("Save item", modal = true)
            compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            val expected = runBlocking { requireNotNull(app.genesisStorage.playable.get(hero)) }
            val physical = expected.character.items.single()
            assertEquals("Fictional route compass", expected.character.itemDefinitions.single().name)

            // Re-enter the disposed sheet through the actual inventory owner action.
            // A restored tab must wait for one coherent loaded records snapshot,
            // not consume this route against its earlier empty loading list.
            compose.onNodeWithTag("navigation-Inventory").performClick()
            awaitNode(hasTestTag("genesis-inventory-item-" + physical.id))
            compose.onNodeWithTag("genesis-inventory-item-" + physical.id).assertIsDisplayed().performClick()
            click("Open Inventory Route Hero's item", modal = true, scroll = true)
            requireItemDialog()
            capture("genesis-inventory-route-reopened-item", controller.get())
            click("Edit", modal = true)
            awaitNode(hasText("Item name") and hasSetTextAction())
            compose.onNodeWithText("Item name").assertTextContains("Fictional route compass")
            click("Close", modal = true)
            compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }

            // The second explicit route gets a fresh token, then survives a real
            // parcelled Activity recreation with its item dialog still open.
            compose.onNodeWithTag("navigation-Inventory").performClick()
            awaitNode(hasTestTag("genesis-inventory-item-" + physical.id))
            compose.onNodeWithTag("genesis-inventory-item-" + physical.id).assertIsDisplayed().performClick()
            click("Open Inventory Route Hero's item", modal = true, scroll = true)
            requireItemDialog()
            val state = savedState(controller)
            controller.pause().stop().destroy()
            controller = Robolectric.buildActivity(ComponentActivity::class.java).create(state).start().resume().visible()
            mount()
            requireItemDialog()
            capture("genesis-inventory-route-recreated-item", controller.get())
            click("Close", modal = true)
            compose.onNodeWithTag("section-Equipment").assertIsSelected()
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            awaitNode(hasTestTag("section-Sheet") and isSelected())

            runBlocking {
                assertEquals(expected, app.genesisStorage.playable.get(hero))
                assertEquals(otherBefore, app.genesisStorage.playable.get(other))
                assertEquals(hero, preferences.activeProfileId.first())
                val exported = authority.backup.exportWithProof()
                val backup = decodeGenesisAuthorityBackup(exported.text)
                assertEquals(physical.id, backup.authority.items.single().itemId)
                assertEquals(hero, backup.authority.items.single().ownerActorId)
                assertTrue(authority.backup.previewRestore(exported.text).valid)
            }
            assertEquals(weaponBefore, app.repository.state.value)
        } finally { controller.pause().stop().destroy(); service.destroy() }
    }
}
