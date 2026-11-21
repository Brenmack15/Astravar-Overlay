package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlayableSpellbookProjectionTest {
    @Test fun castingAndSpellReadsAreCompleteBoundedAndScopeChecked() {
        val spell = SpellDefinition("glint", "Known glint", 1, SpellPreparation.ALWAYS_PREPARED, concentration = true,
            description = "💫\"\n".repeat(7000), sourceVersion = "fixture-source-r1")
        val actor = ReforgeCharacter("fixture-spells", "Caster", revision = 7, spells = listOf(spell), concentrationSpellId = spell.id,
            slotMaximaOverride = mapOf(1 to 4, 2 to 2, 3 to 1), spellSlots = mapOf(1 to 0, 2 to null))
        val rows = PlayableSpellbookProjection.records(actor, GenesisActorRole.COMPANION)
        val allowed = PlayableSpellbookProjection.readScopes
        for (missing in allowed) assertTrue(CampaignConnectionProjection.project(rows, allowed - missing).isEmpty())
        assertEquals(rows, CampaignConnectionProjection.project(rows, allowed))
        assertTrue(rows.all { it.revision == 7L && it.fields["actorRole"] == JsonPrimitive("COMPANION") })
        val state = rows.single { it.fields["subject"] == JsonPrimitive("CASTING") }.fields.getValue("data").jsonObject
        assertEquals(spell.id, state.getValue("concentrationSpellId").jsonPrimitive.content)
        assertEquals(listOf(JsonPrimitive(0), JsonNull, JsonNull), state.getValue("slots").jsonArray.map { it.jsonObject.getValue("current") })
        val header = rows.single { it.fields["part"] == JsonPrimitive("header") && it.fields["subject"] == JsonPrimitive("SPELL") }
        val pieces = rows.filter { it.fields["headerId"] == JsonPrimitive(header.recordId) }.sortedBy { it.fields.getValue("index").jsonPrimitive.int }
        assertEquals(header.fields.getValue("parts").jsonPrimitive.int, pieces.size)
        assertTrue(pieces.size > 1)
        val decoded = pieces.joinToString("") {
            val text = it.fields.getValue("text").jsonPrimitive.content
            assertEquals(campaignHash(text), it.fields.getValue("partSha256").jsonPrimitive.content)
            text
        }
        assertEquals(header.fields.getValue("contentSha256").jsonPrimitive.content, campaignHash(decoded))
        assertEquals(spell, campaignJson.decodeFromString<SpellDefinition>(decoded))
        assertTrue(rows.all { campaignJson.encodeToString(CampaignSyncRecord.serializer(), it).toByteArray(Charsets.UTF_8).size < 48_000 })
        val renamed = PlayableSpellbookProjection.records(actor.copy(spells = listOf(spell.copy(name = "Renamed", description = ""))), GenesisActorRole.COMPANION)
        assertTrue(renamed.any { it.recordId == header.recordId && it.fields["data"]?.jsonObject?.get("name") == JsonPrimitive("Renamed") })
    }
}
