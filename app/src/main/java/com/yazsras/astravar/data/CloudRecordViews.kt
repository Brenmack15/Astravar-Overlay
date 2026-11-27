package com.yazsras.astravar.data

import java.net.URLDecoder
import java.net.URLEncoder

private val recordViews = setOf("inventory", "wallets", "item_history", "timeline", "genealogy")
private val viewKeys = setOf("id", "query", "after", "limit", "atRevision", "atEpoch", "grantRevision")
private val cursorPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:~\\-]{0,179}")

private fun encodeViewParameter(value: String): String = URLEncoder.encode(value, "UTF-8")

internal fun validRecordViewSuffix(suffix: String): Boolean {
    if (suffix.length > 2000 || suffix.contains('#') || !suffix.startsWith("records/")) return false
    val route = suffix.substringBefore('?').removePrefix("records/")
    if (route !in recordViews || !suffix.contains('?')) return false
    val fields = mutableMapOf<String, String>()
    for (pair in suffix.substringAfter('?').split('&')) {
        val key = pair.substringBefore('=')
        if (key !in viewKeys || key in fields || !pair.contains('=')) return false
        val value = try { URLDecoder.decode(pair.substringAfter('='), "UTF-8") } catch (_: IllegalArgumentException) { return false }
        fields[key] = value
    }
    if (!fields.keys.containsAll(setOf("limit", "atRevision", "atEpoch", "grantRevision"))) return false
    return fields.all { (key, value) ->
        when (key) {
            "id" -> cloudIdentifier.matches(value)
            "after" -> cursorPattern.matches(value)
            "query" -> value.length <= 120 && value.none(Char::isISOControl)
            "limit" -> value.toIntOrNull()?.let { it in 1..100 } == true
            else -> value.toLongOrNull()?.let { it >= 0 } == true
        }
    } && (route !in setOf("item_history", "genealogy") || fields.containsKey("id"))
}

internal fun recordViewSuffix(view: String, version: CloudVersion, id: String? = null,
    query: String = "", after: String? = null, limit: Int = 25): String {
    val params = linkedMapOf("limit" to limit.toString())
    if (id != null) params["id"] = id
    if (query.isNotBlank()) params["query"] = query
    if (after != null) params["after"] = after
    params["atRevision"] = version.revision.toString()
    params["atEpoch"] = version.epoch.toString()
    params["grantRevision"] = version.grantRevision.toString()
    val suffix = "records/$view?" + params.entries.joinToString("&") { (k,v) -> "$k=${encodeViewParameter(v)}" }
    require(validRecordViewSuffix(suffix))
    return suffix
}
