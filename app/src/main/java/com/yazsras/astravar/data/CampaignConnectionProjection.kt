package com.yazsras.astravar.data

import kotlinx.serialization.json.*

/** Read scopes apply before bytes leave Android, including committed command outcomes. */
object CampaignConnectionProjection {
    fun project(records: List<CampaignSyncRecord>, scopes: Set<String>): List<CampaignSyncRecord> {
        CampaignConnectionProtocol.scopes(scopes)
        return records.mapNotNull { record ->
            val f = record.fields
            val projected: JsonObject = when (record.kind) {
                "character-profile" -> {
                    if (scopes.none { it in setOf("character", "hp") }) return@mapNotNull null
                    val keys = setOf("id", "revision", "source", "updatedAt") +
                        (if ("character" in scopes) setOf("name", "armorClass", "level") else emptySet()) +
                        (if ("hp" in scopes) setOf("currentHp", "maximumHp", "temporaryHp") else emptySet())
                    JsonObject(f.filterKeys(keys::contains))
                }
                "party-sheet" -> {
                    if (scopes.none { it in setOf("character", "hp", "resources") }) return@mapNotNull null
                    val sheet = Json.parseToJsonElement(f.getValue("contentJson").jsonPrimitive.content).jsonObject
                    val selected = buildJsonObject {
                        if ("character" in scopes) sheet.filterKeys { it !in setOf("resources", "conditions", "portraitBase64") }.forEach { (k,v) -> put(k,v) }
                        if ("resources" in scopes) sheet["resources"]?.let { put("resources", it) }
                        if ("hp" in scopes) sheet["conditions"]?.let { put("conditions", it) }
                    }
                    buildJsonObject { f.filterKeys { it != "contentJson" }.forEach { (k,v) -> put(k,v) }; put("contentJson", OwnerSyncProtocol.canonical(selected)) }
                }
                "item", "connection" -> {
                    if (CampaignSyncDomain.category(record.kind) !in scopes) return@mapNotNull null
                    JsonObject(f.filterKeys { it != "imageBase64" })
                }
                "companion-resource" -> { if ("companions" !in scopes) return@mapNotNull null; f }
                "audit" -> { if (!scopes.containsAll(CampaignConnectionProtocol.categories)) return@mapNotNull null; f }
                "reference" -> {
                    // Original archive bodies can contain records from every category. Metadata-only references do not.
                    val fullArchive = record.recordId.startsWith("archive:") || f.containsKey("body") || f.containsKey("bodyText") || f.containsKey("content")
                    if ((fullArchive && !scopes.containsAll(CampaignConnectionProtocol.categories)) || "continuity" !in scopes) return@mapNotNull null
                    f
                }
                else -> { if (CampaignSyncDomain.category(record.kind) !in scopes) return@mapNotNull null; f }
            }
            record.copy(fields = projected)
        }
    }
}
