package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.yazsras.astravar.data.cloudText
import kotlinx.serialization.json.*
import java.util.UUID

@Composable internal fun CloudFormManagementControls(form: JsonObject, forms: List<JsonObject>, online: Boolean,
    activeFormId: String?, pendingFormId: String?, open: (String, JsonObject) -> Unit, commit: (String, JsonObject) -> Unit) {
    val id = form.cloudText("id")
    val actor = form.cloudText("actorId")
    val data = form.getValue("data").jsonObject
    val index = forms.indexOfFirst { it.cloudText("id") == id }
    fun move(other: JsonObject) = commit("move_form", buildJsonObject {
        put("actorId", actor); put("formId", id); put("adjacentFormId", other.cloudText("id"))
    })
    PlayerDisclosure("Manage configuration") {
        PlayerOutlinedButton({
            open("revise_form", buildJsonObject {
                data.filterKeys { it !in setOf("orderKey", "configurationRevision", "inheritedRuleFormIds") }.forEach { (k,v) -> put(k,v) }
                put("formId",id); put("actorId",actor); put("name",form.cloudText("title"))
            })
        }, enabled = online && activeFormId != id && pendingFormId != id) { Text("Edit form") }
        PlayerOutlinedButton({
            open("duplicate_form", buildJsonObject {
                put("actorId",actor); put("sourceFormId",id); put("formId",UUID.randomUUID().toString())
                put("name",form.cloudText("title").take(155) + " copy")
            })
        }, enabled = online) { Text("Duplicate configuration") }
        PlayerTextButton({ forms.getOrNull(index-1)?.let(::move) }, enabled = online && index > 0) { Text("Move up") }
        PlayerTextButton({ forms.getOrNull(index+1)?.let(::move) }, enabled = online && index >= 0 && index < forms.lastIndex) { Text("Move down") }
        PlayerTextButton({
            open("archive_records", buildJsonObject { putJsonArray("records") {
                addJsonObject { put("kind","form"); put("id",id); put("revision",form.getValue("revision")) }
            } })
        }, enabled = online && activeFormId != id && pendingFormId != id) { Text("Review retirement") }
    }
}

@Composable internal fun CloudFormSourceBreakdown(config: JsonObject, preview: JsonObject) {
    val sheet = preview["projection"] as? JsonObject ?: return
    fun JsonObject.child(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    fun JsonElement?.display() = (this as? JsonPrimitive)?.contentOrNull ?: "Unknown"
    PlayerDisclosure("Inherited values and overrides") {
        for ((group, projected) in listOf("abilityOverrides" to "abilities", "speeds" to "movement", "senses" to "senses")) {
            sheet.child(projected).forEach { (key, value) ->
                val label = if (group == "abilityOverrides") key else cloudLabel(key)
                val effective = if (value is JsonObject) value["effective"].display() else value.display()
                val source = if (key in config.child(group)) "Form override" else if (group == "speeds" &&
                    config["grounded"]?.jsonPrimitive?.booleanOrNull == true && key in setOf("fly","hover")) "Grounded form" else "Inherited"
                Text("$label · $source · $effective")
            }
        }
        for ((field,key) in listOf("size" to "size","creatureType" to "creatureType","baseAc" to "ac")) {
            val value = sheet[key]
            val effective = if (value is JsonObject) value["value"].display() else value.display()
            Text("${cloudLabel(key)} · ${if (field in config) "Form override" else "Inherited"} · $effective")
        }
    }
}
