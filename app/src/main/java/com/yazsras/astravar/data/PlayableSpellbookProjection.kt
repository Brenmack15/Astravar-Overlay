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
        addAll(playableReadEnvelope(KIND, "spell", c, role, "CASTING", "", casting))
        for (spell in c.spells) addAll(playableReadEnvelope(KIND, "spell", c, role, "SPELL", spell.id, campaignJson.encodeToJsonElement(spell).jsonObject))
    }
}
