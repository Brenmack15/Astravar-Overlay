package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.CharacterCalculator
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.serialization.json.*

/** Player-known Reforge spell definitions and finite casting state from one accepted actor revision. */
internal object PlayableSpellbookProjection {
    const val KIND = "playable-spell"
    val readScopes = setOf("character", "companions", "resources", "combat")

    fun records(c: ReforgeCharacter, role: GenesisActorRole): List<CampaignSyncRecord> = buildList {
        val calculation = CharacterCalculator.calculate(c)
        val levels = (calculation.slotMaxima.keys + c.spellSlots.keys).sorted()
        val casting = buildJsonObject {
            put("castingClassLevel", JsonPrimitive(c.spellcastingClassLevel))
            put("classVersion", c.rules.classVersion)
            put("preparationLimit", JsonPrimitive(calculation.preparationLimit))
            put("concentrationSpellId", JsonPrimitive(c.concentrationSpellId))
            put("spellCount", c.spells.size)
            put("slots", buildJsonArray { for (level in levels) add(buildJsonObject {
                put("level", level); put("maximum", JsonPrimitive(calculation.slotMaxima[level]))
                put("current", JsonPrimitive(c.spellSlots[level]))
            }) })
        }
        addAll(envelope(c, role, "CASTING", "", casting))
        for (spell in c.spells) addAll(envelope(c, role, "SPELL", spell.id, campaignJson.encodeToJsonElement(spell).jsonObject))
    }

    private fun envelope(c: ReforgeCharacter, role: GenesisActorRole, subject: String, key: String,
        data: JsonObject): List<CampaignSyncRecord> {
        val id = "spell-" + campaignHash(listOf(c.id, subject, key).joinToString("\u0000"))
        val canonical = OwnerSyncProtocol.canonical(data)
        val bytes = canonical.toByteArray(Charsets.UTF_8).size
        val hash = campaignHash(canonical)
        val parts = if (bytes > 16_000) CampaignSyncReferenceProjection.parts(canonical) else emptyList()
        fun row(rowId: String, fields: JsonObjectBuilder.() -> Unit) = CampaignSyncRecord(rowId, KIND, c.revision, buildJsonObject {
            put("id", rowId); put("profileId", c.id); put("actorRole", role.name); put("schemaVersion", 1)
            put("revision", c.revision); put("subject", subject); put("source", "Android Reforge v1"); fields()
        })
        return buildList {
            add(row(id) {
                put("part", "header"); put("contentSha256", hash); put("contentBytes", bytes); put("parts", parts.size)
                if (parts.isEmpty()) put("data", data) else put("partPrefix", "$id:")
            })
            parts.forEachIndexed { index, part -> add(row("$id:$index") {
                put("part", "text"); put("headerId", id); put("index", index); put("text", part)
                put("partSha256", campaignHash(part)); put("contentSha256", hash)
            }) }
        }
    }
}
