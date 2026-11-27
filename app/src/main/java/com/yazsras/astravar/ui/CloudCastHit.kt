package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.json.*
import java.security.SecureRandom

private val spellDice = SecureRandom()

@Composable
internal fun CloudCastHit(actorId: String, castId: String, data: JsonObject, name: String, online: Boolean,
    submit: (String, JsonObject) -> Unit) {
    if (data["resolution"]?.jsonPrimitive?.content != "attack" || data["hit"] != JsonNull || data.containsKey("chargeRelease")) return
    val modifier = (data["hitModifier"] as? JsonPrimitive)?.intOrNull ?: return
    val policies = data["effectivePolicy"] as? JsonObject ?: return
    var options by rememberSaveable(castId) { mutableStateOf(false) }
    var mode by rememberSaveable(castId) { mutableStateOf("normal") }
    var origin by rememberSaveable(castId) { mutableStateOf("app") }
    var entered by rememberSaveable(castId) { mutableStateOf("") }
    fun count(key: String) = (policies[key] as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull?.takeIf { it in 1..200 }
    fun record(selected: String, source: String, faces: List<Int>) {
        options = false
        submit("link_cast_roll", buildJsonObject {
            put("actorId", actorId); put("castId", castId); put("mode", selected)
            putJsonObject("hit") { put("origin", source); put("faces", JsonArray(faces.map(::JsonPrimitive))) }
        })
    }
    val signed = if (modifier >= 0) "+$modifier" else modifier.toString()
    val normalCount = count("normal")
    QuickControl("$name spell attack", signed, online && normalCount != null, {
        normalCount?.let { record("normal", "app", List(it) { spellDice.nextInt(20) + 1 }) }
    }, { mode = "normal"; origin = "app"; entered = ""; options = true })
    if (options) PlayerSheet("$name spell attack", { options = false }) {
        PlayerTabs(listOf("Normal","Advantage","Disadvantage"), cloudLabel(mode), { mode = it.lowercase() })
        val n = count(mode)
        val effective = (policies[mode] as? JsonObject)?.get("mode")?.jsonPrimitive?.content
        Text("${n ?: "?"}d20 · ${effective?.let(::cloudLabel) ?: "Unknown"} · $signed")
        val sources = mapOf("Roll in app" to "app", "Physical dice" to "player_physical", "Player digital dice" to "player_digital")
        PlayerTabs(sources.keys.toList(), sources.entries.first { it.value == origin }.key, { origin = sources.getValue(it) })
        if (origin != "app") Field("Actual d20 faces", entered, { entered = it.take(800) })
        val tokens = entered.trim().takeIf(String::isNotEmpty)?.split(Regex("[,\\s]+")).orEmpty()
        val faces = tokens.mapNotNull(String::toIntOrNull)
        val ready = n != null && (origin == "app" || tokens.size == n && faces.size == n && faces.all { it in 1..20 })
        PlayerButton({
            if (n != null) record(mode, origin, if (origin == "app") List(n) { spellDice.nextInt(20) + 1 } else faces)
        }, enabled = online && ready) { Text(if (origin=="app") "Roll spell attack" else "Record spell attack") }
    }
}
