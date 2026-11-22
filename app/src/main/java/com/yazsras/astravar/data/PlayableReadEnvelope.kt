package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.serialization.json.*

internal fun playableReadEnvelope(
    kind: String, prefix: String, c: ReforgeCharacter, role: GenesisActorRole,
    subject: String, key: String, data: JsonObject
): List<CampaignSyncRecord> {
    val id = "$prefix-" + campaignHash(listOf(c.id, subject, key).joinToString("\u0000"))
    val canonical = OwnerSyncProtocol.canonical(data)
    val bytes = canonical.toByteArray(Charsets.UTF_8).size
    val hash = campaignHash(canonical)
    val parts = if (bytes > 16_000) CampaignSyncReferenceProjection.parts(canonical) else emptyList()
    fun row(rowId: String, fields: JsonObjectBuilder.() -> Unit) = CampaignSyncRecord(rowId, kind, c.revision, buildJsonObject {
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
