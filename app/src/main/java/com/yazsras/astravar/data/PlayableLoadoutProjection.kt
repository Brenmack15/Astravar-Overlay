package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.json.*

internal object PlayableLoadoutProjection {
    const val KIND = "playable-loadout"
    val readScopes = setOf("character", "companions", "inventory", "resources", "combat", "hp")

    fun records(c: ReforgeCharacter, role: GenesisActorRole): List<CampaignSyncRecord> = buildList {
        fun addEntry(subject: String, id: String, data: JsonObject) {
            addAll(playableReadEnvelope(KIND, "loadout", c, role, subject, id, data))
        }
        val projection = CharacterCalculator.calculate(c)
        val statuses = projection.effectStatuses.groupBy { it.itemInstanceId }
        val definitions = c.itemDefinitions.associateBy { it.id }
        addEntry("SUMMARY", "", buildJsonObject {
            put("rules", campaignJson.encodeToJsonElement(c.rules))
            put("level", c.level)
            put("className", c.className)
            put("subclass", c.subclass)
            put("conditions", campaignJson.encodeToJsonElement(c.conditions))
            put("stances", campaignJson.encodeToJsonElement(c.activeStances))
            put("turn", campaignJson.encodeToJsonElement(c.turn))
            put("turnNumber", c.turnNumber)
            put("gameSeconds", c.gameSeconds)
            put("exhaustionLevel", c.exhaustionLevel)
            put("attunementLimit", c.attunementLimit)
            put("itemCount", c.items.size)
            put("resourceCount", c.resources.size)
            put("attackCount", c.attacks.size)
            put("featureCount", c.features.size)
            put("actorEffects", campaignJson.encodeToJsonElement(c.effects))
            put("actorEffectStatuses", campaignJson.encodeToJsonElement(statuses[null].orEmpty()))
        })
        for (item in c.items) addEntry("ITEM", item.id, buildJsonObject {
            put("instance", campaignJson.encodeToJsonElement(item))
            put("definition", campaignJson.encodeToJsonElement(definitions.getValue(item.definitionId)))
            put("effectStatuses", campaignJson.encodeToJsonElement(statuses[item.id].orEmpty()))
        })
        for (pool in c.resources) addEntry("RESOURCE", pool.id, buildJsonObject {
            put("pool", campaignJson.encodeToJsonElement(pool))
            put("effectiveMaximum", JsonPrimitive(projection.resourceMaxima[pool.id]))
        })
        for (attack in c.attacks) addEntry("ATTACK", attack.id, buildJsonObject {
            // Publish current mechanics and a source identity without copying a historical sheet snapshot.
            put("definition", campaignJson.encodeToJsonElement(attack.copy(recordedSource = null)))
            attack.recordedSource?.let {
                put("sourceProfileId", it.profileId)
                put("sourceAttackId", it.attackId)
                put("sourceRevision", it.sheetRevision)
                put("sourceFingerprint", it.fingerprint)
            }
        })
        for (feature in c.features) addEntry("FEATURE", feature.id, campaignJson.encodeToJsonElement(feature).jsonObject)
    }
}
