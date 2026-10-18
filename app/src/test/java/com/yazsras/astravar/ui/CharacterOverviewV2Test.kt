package com.yazsras.astravar.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.BackupCodec
import com.yazsras.astravar.rules.Campaign
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CharacterOverviewV2Test {
    @get:Rule val compose = createEmptyComposeRule()
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CampaignLedgerDb::class.java
    ).build()

    @Test fun openingOverviewAndCancellingStatusDoesNotSeedCharacterValues() {
        val db = database()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { CharacterOverviewV2(CampaignLedgerRepository(db)) } } }
            listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").forEach {
                compose.onNodeWithTag("ability-$it").assertExists()
            }
            compose.onNodeWithTag("ability-STR").assert(hasContentDescription("Strength score unknown", substring = true))
            compose.onNodeWithText("Edit status").performScrollTo().performClick()
            compose.onNodeWithText("Save character status").assertIsNotEnabled()
            compose.onNodeWithText("Cancel").performClick()
            runBlocking {
                assertTrue(db.recordsDao().allCharacterProfiles().isEmpty())
                assertTrue(db.recordsDao().allCharacterAbilities().isEmpty())
                assertTrue(db.recordsDao().allAudit().isEmpty())
            }
        } finally { activity.pause().stop().destroy(); db.close() }
    }

    @Test fun changingRawAbilityScorePreservesMagicModifierAndWeaponState() {
        val db = database()
        val weapon = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AstravarDb::class.java
        ).build()
        val repo = CampaignLedgerRepository(db)
        val character = CharacterProfile(name = "Synthetic character")
        val originalWeapon = StateRow(body = BackupCodec.json.encodeToString(Campaign()))
        runBlocking {
            repo.records.saveProfile(character, source = "Synthetic fixture")
            repo.records.saveAbility(CharacterAbility(key = "STR", score = 16, magicModifier = 2,
                notes = "Separately confirmed magical modifier"), source = "Synthetic fixture")
            weapon.dao().put(originalWeapon)
        }
        val originalProfile = runBlocking { db.recordsDao().allCharacterProfiles().single() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { Page { CharacterOverviewV2(repo) } } }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Strength score 16", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("ability-STR").performScrollTo().performClick()
            compose.onNodeWithText("Raw ability score (blank = unknown)").performScrollTo().performTextClearance()
            compose.onNodeWithText("Raw ability score (blank = unknown)").performTextInput("18")
            compose.onNodeWithText("Separate magical modifier").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2"))
            )
            compose.onNodeWithText("Save ability").performClick()
            compose.waitUntil(10_000) { runBlocking { db.recordsDao().characterAbility("local-player", "STR")?.score == 18 } }
            runBlocking {
                val after = db.recordsDao().characterAbility("local-player", "STR")!!
                assertEquals(2, after.magicModifier)
                assertEquals(4, after.baseModifier)
                assertEquals(6, after.totalModifier)
                assertEquals("local-player:STR", after.id)
                assertEquals(originalProfile, db.recordsDao().allCharacterProfiles().single())
                assertEquals(originalWeapon, weapon.dao().state())
                assertEquals(0, weapon.dao().eventCount())
                assertEquals(0, weapon.dao().rollCount())
            }
        } finally { activity.pause().stop().destroy(); db.close(); weapon.close() }
    }
}
