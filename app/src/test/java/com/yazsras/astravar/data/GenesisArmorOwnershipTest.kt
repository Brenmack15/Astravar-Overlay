package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisArmorOwnershipTest {
    @Test fun simultaneousReviewsCannotDuplicateOriginalReservoirAcrossCharacters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReforgeDb::class.java).build()
        try {
            val repo = ReforgeRepository(db)
            val template = Template("fixture-template", "Fixture spell", 1, 1,
                listOf(TypedDice(Dice(1, 6), DamageType.FIRE)), DamageType.FIRE, true)
            val original = Campaign(onboarded = true, attuned = true, installedId = "fixture-nexus",
                cores = listOf(Core("fixture-nexus", "Fixture nexus", payloads = listOf(
                    Payload("original-payload", template, SourceKind.SLOT, "fixture-nexus", 10)))),
                templates = listOf(template), scrolls = listOf(Scroll("original-scroll", template.id, 2)))
            fun actor(id: String) = ReforgeCharacter(id, "Fictional $id", level = 9,
                baseAbilities = mapOf(Stat.INT to 20), spellSlots = mapOf(1 to 1))
            val a = repo.create(actor("owner-a")); val b = repo.create(actor("owner-b"))
            val nextA = GenesisArmorRules.adopt(a.character, original, listOf(DamageType.COLD), "Reviewed fixture A")
            val nextB = GenesisArmorRules.adopt(b.character, original, listOf(DamageType.COLD), "Reviewed fixture B")
            repo.save(a.character.id, a.revision, nextA, "Connect original systems", "fixture-adopt-a")
            try {
                repo.save(b.character.id, b.revision, nextB, "Connect original systems", "fixture-adopt-b")
                fail("The original inventory must have one character owner")
            } catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("already connected")) }
            assertEquals(original, repo.get(a.character.id)!!.character.armor!!.originalCampaign)
            assertNull(repo.get(b.character.id)!!.character.armor)
            assertEquals(b, repo.get(b.character.id))
            assertNull(repo.commandReceipt("fixture-adopt-b"))
            assertEquals(1, repo.get(a.character.id)!!.character.spellSlots[1])
        } finally { db.close() }
    }
}
