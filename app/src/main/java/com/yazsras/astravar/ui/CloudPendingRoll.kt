package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private data class RequestedDice(val sides: List<Int>?, val mode: String)

private fun requestedDice(check: JsonObject, sheet: JsonObject): RequestedDice {
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    val kind = check.string("kind")
    val mode = check.string("mode") ?: "normal"
    if (kind == "custom") {
        val terms = check["terms"] as? JsonArray
        if (terms != null) return RequestedDice(terms.flatMap { element ->
            val term = element.jsonObject
            List(term.getValue("count").jsonPrimitive.int) { term.getValue("sides").jsonPrimitive.int }
        }, mode)
        val sides = check.getValue("sides").jsonPrimitive.int
        val count = check.getValue("dice").jsonPrimitive.int
        if (count != 1 || sides != 20) return RequestedDice(List(count) { sides }, mode)
    }
    val contexts = (check["contextTags"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    val projection = when (contexts.size) {
        0 -> sheet
        1 -> (sheet["rollContexts"] as? JsonObject)?.get(contexts.single()) as? JsonObject
        else -> null
    }
    val subject = "$kind:${check.string("key")}"
    val table = (projection?.get("rollModes") as? JsonObject)?.get(subject) as? JsonObject
    val effective = table?.get(mode) as? JsonObject
    val count = effective?.get("count")?.jsonPrimitive?.intOrNull
    return RequestedDice(count?.let { List(it) { 20 } }, effective?.string("mode") ?: mode)
}

@Composable
internal fun CloudPendingRoll(
    client: CloudCampaignClient, snapshot: CloudSnapshot, actorId: String, row: JsonObject,
    online: Boolean, commit: (String, JsonObject) -> Unit
) {
    val data = row.getValue("data").jsonObject
    val rollId = row.getValue("id").jsonPrimitive.content
    val batchId = (data["batchId"] as? JsonPrimitive)?.contentOrNull ?: return
    val check = data["check"] as? JsonObject ?: return
    val name = row.getValue("title").jsonPrimitive.content
    var open by rememberSaveable(rollId) { mutableStateOf(false) }
    PlayerTextButton({ open = true }, enabled = online) { Text("Enter requested dice · $name") }
    if (!open) return
    var origin by rememberSaveable(rollId) { mutableStateOf("player_physical") }
    var faces by rememberSaveable(rollId) { mutableStateOf("") }
    var requested by remember(snapshot.version, rollId) { mutableStateOf<RequestedDice?>(null) }
    var error by remember(snapshot.version, rollId) { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot.version, online, rollId) {
        if (!online) return@LaunchedEffect
        try {
            val sheet = client.projection(snapshot, "sheet/$actorId").getValue("sheet").jsonObject
            requested = requestedDice(check, sheet)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = cloudError(e) }
    }
    PlayerSheet("Requested roll · $name", { open = false }) {
        val dice = requested
        val sizes = dice?.sides
        if (dice != null) {
            Text("Roll mode · ${cloudLabel(dice.mode)}")
            if (sizes != null) {
                val groups = sizes.fold(mutableListOf<Pair<Int,Int>>()) { acc, sides ->
                    val previous = acc.lastOrNull()
                    if (previous?.second == sides) acc[acc.lastIndex] = previous.first + 1 to sides
                    else acc += 1 to sides
                    acc
                }
                Text(if (groups.isEmpty()) "Flat result · no dice required" else groups.joinToString(" then ") { (count, sides) -> "${count}d$sides" })
            } else Text("Enter the dice requested for the current circumstances.")
        }
        PlayerTabs(listOf("Physical dice", "Player digital dice"), if (origin == "player_physical") "Physical dice" else "Player digital dice", {
            origin = if (it == "Physical dice") "player_physical" else "player_digital"
        })
        if (sizes?.isEmpty() != true) Field("Actual die faces", faces, { faces = it.take(1000) })
        val entries = faces.trim().takeIf(String::isNotEmpty)?.split(Regex("[,\\s]+")).orEmpty()
        val parsed = entries.mapNotNull(String::toIntOrNull)
        val valid = dice != null && entries.size == parsed.size && parsed.size <= 200 &&
            (sizes == null && parsed.isNotEmpty() && parsed.all { it in 1..20 } ||
                sizes != null && parsed.size == sizes.size && parsed.indices.all { parsed[it] in 1..sizes[it] })
        error?.let { Text(it) }
        PlayerButton({
            open = false
            commit("complete_pending_check", buildJsonObject {
                put("actorId", actorId); put("rollId", rollId); put("batchId", batchId)
                putJsonObject("evidence") { put("origin", origin); put("faces", JsonArray(parsed.map(::JsonPrimitive))) }
            })
        }, enabled = online && valid) { Text("Record requested result") }
    }
}
