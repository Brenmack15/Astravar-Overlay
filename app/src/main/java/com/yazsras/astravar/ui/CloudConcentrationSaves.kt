package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

@Composable internal fun CloudConcentrationSaves(actorId: String, activeCast: String, records: List<JsonObject>,
    client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean, open: (String, JsonObject) -> Unit) {
    val pending = records.filter { row ->
        val data = row["data"] as? JsonObject
        row["kind"]?.jsonPrimitive?.content == "concentration_check" && data?.get("status")?.jsonPrimitive?.content == "pending" &&
            data["castId"]?.jsonPrimitive?.content == activeCast
    }
    for (row in pending) key(row.getValue("id")) {
        val checkId = row.getValue("id").jsonPrimitive.content
        val data = row.getValue("data").jsonObject
        val dc = data["dc"]?.jsonPrimitive?.intOrNull
        val damage = data["damage"]?.jsonPrimitive?.intOrNull
        val castTitle = records.firstOrNull { it["id"]?.jsonPrimitive?.content == activeCast }?.get("title")?.jsonPrimitive?.content
        PlayerHeading("Concentration save")
        castTitle?.let { Text(it) }
        Text("Damage received · ${damage ?: "Unknown"}")
        Text(dc?.let { "Constitution save · DC $it" } ?: "Save DC requires adjudication")
        var linked by remember(snapshot.version) { mutableStateOf<List<JsonObject>?>(null) }
        var failed by remember(snapshot.version) { mutableStateOf(false) }
        LaunchedEffect(checkId, snapshot.version, online) {
            if (!online) return@LaunchedEffect
            try {
                val rolls = mutableListOf<JsonObject>()
                var after: String? = null
                do {
                    val suffix = after?.let { "?after=$it&atEpoch=${snapshot.version.epoch}&atRevision=${snapshot.version.revision}&grantRevision=${snapshot.version.grantRevision}" }.orEmpty()
                    val page = client.projection(snapshot, "links/$checkId$suffix")
                    for (edge in page.getValue("links").jsonArray.map { it.jsonObject }) {
                        if (edge["relation"]?.jsonPrimitive?.content == "for_concentration" && edge["to_id"]?.jsonPrimitive?.content == checkId) {
                            val roll = client.record(edge.getValue("from_id").jsonPrimitive.content)
                            if (roll != null) rolls.add(roll)
                        }
                    }
                    after = page["nextAfter"]?.jsonPrimitive?.contentOrNull
                } while (after != null)
                linked = rolls
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        if (failed) Text("The recorded save could not be verified.")
        val rolls = linked
        if (online && dc != null && rolls != null) {
            if (rolls.isEmpty()) {
                var faces by rememberSaveable(checkId) { mutableStateOf("") }
                Field("Physical d20 faces", faces, { faces = it.take(24) })
                val tokens = faces.trim().split(Regex("[,\\s]+"))
                val parsed = tokens.mapNotNull { it.toIntOrNull()?.takeIf { face -> face in 1..20 } }
                PlayerOutlinedButton({
                    open("record_check", buildJsonObject {
                        put("actorId", actorId); put("rollId", UUID.randomUUID().toString()); put("forConcentrationId", checkId)
                        putJsonObject("check") { put("kind", "save"); put("key", "CON"); put("mode", "normal") }
                        putJsonObject("evidence") { put("origin", "player_physical"); putJsonArray("faces") { parsed.forEach { add(it) } } }
                    })
                }, enabled = parsed.isNotEmpty() && parsed.size == tokens.size && parsed.size <= 3) { Text("Review physical save") }
            } else {
                for (roll in rolls) {
                    val total = roll["data"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull ?: continue
                    PlayerOutlinedButton({
                        open("resolve_concentration_check", buildJsonObject {
                            put("actorId", actorId); put("checkId", checkId); put("rollId", roll.getValue("id"))
                        })
                    }) { Text("Resolve recorded save · $total") }
                }
            }
        }
    }
}
