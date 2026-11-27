package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

private fun JsonObject?.field(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject
private fun JsonObject?.list(key: String) = (this?.get(key) as? JsonArray).orEmpty()

internal fun cloudTrainingDraft(actorId: String, category: String, key: String?, actor: JsonObject?): JsonObject = buildJsonObject {
    put("actorId", actorId); put("category", category)
    if (key.isNullOrBlank()) return@buildJsonObject
    put("key", key)
    val known = actor.list("training").mapNotNull { it as? JsonObject }.firstOrNull {
        it.field("category") == category && it.field("key")?.equals(key, ignoreCase = true) == true
    }
    val saved = actor.list("saveProficiencies").any { (it as? JsonPrimitive)?.contentOrNull == key }
    put("trained", if (category == "save") saved else known != null)
    known.field("source")?.let { put("source", it) }
}

internal fun cloudSkillTrainingDraft(actorId: String, skillId: String, definition: JsonObject?): JsonObject = buildJsonObject {
    put("actorId", actorId)
    put("skillId", skillId)
    for (field in listOf("name", "ability", "rank")) {
        definition?.get(field)?.takeUnless { it == JsonNull }?.let { put(field, it) }
    }
}

@Composable internal fun CloudAbilityTraining(actorId: String, stat: String, actor: JsonObject?, sheet: JsonObject?,
    online: Boolean, open: (String, JsonObject) -> Unit) {
    val save = cloudTrainingDraft(actorId, "save", stat, actor)
    PlayerTextButton({ open("set_actor_training", save) }, enabled = online) {
        Text("Saving throw proficiency · " + if (save["trained"]?.jsonPrimitive?.booleanOrNull == true) "trained" else "untrained")
    }
    val skills = sheet.obj("skillDefinitions").orEmpty().mapNotNull { (key, item) ->
        val row = item as? JsonObject
        if (row.field("ability") == stat) key to row!! else null
    }.sortedBy { it.second.field("name") ?: it.first }
    PlayerDisclosure("Skills and expertise", initiallyOpen = false) {
        skills.forEach { (id, row) ->
            val name = row.field("name") ?: id
            PlayerTextButton({ open("set_skill_training", cloudSkillTrainingDraft(actorId, id, row)) }, enabled=online) {
                val training = when (row.field("rank")) { "2" -> "expertise"; "1" -> "proficient"; "0" -> "untrained"; else -> "training unknown" }
                Text(cloudLabel(name) + " · " + training)
            }
        }
        PlayerTextButton({ open("set_skill_training", buildJsonObject { put("actorId",actorId);put("ability",stat) }) },enabled=online) { Text("Add skill") }
    }
    PlayerDisclosure("Tools and other training", initiallyOpen = false) {
        actor.list("training").mapNotNull { it as? JsonObject }.filter { it.field("category") != "save" }.forEach { trained ->
            val category=trained.field("category") ?: return@forEach
            val key=trained.field("key") ?: return@forEach
            PlayerTextButton({ open("set_actor_training",cloudTrainingDraft(actorId,category,key,actor)) },enabled=online) {
                Text(cloudLabel(category) + " · " + key)
            }
        }
        PlayerTextButton({ open("set_actor_training",cloudTrainingDraft(actorId,"tool",null,actor)) },enabled=online) { Text("Add tool training") }
    }
}

@Composable internal fun CloudPassiveChecks(actorId: String, sheet: JsonObject?, online: Boolean,
    loadEffect: suspend (String) -> JsonObject?, open: (String, JsonObject) -> Unit, inspect: (String) -> Unit) {
    val passives = sheet.obj("passives") ?: return
    var selected by rememberSaveable(actorId) { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable(actorId) { mutableStateOf(false) }
    Text("Passive checks", style=MaterialTheme.typography.titleMedium)
    val keys = listOf("perception","investigation","insight").filter { it in passives }
    if(keys.isNotEmpty()) {
        val measurer=rememberTextMeasurer()
        val density=LocalDensity.current
        val style=MaterialTheme.typography.labelLarge
        val nameWidth=with(density) { keys.maxOf { measurer.measure(cloudLabel(it),style,softWrap=false).size.width }.toDp() }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val enoughWidth=maxWidth >= (nameWidth + 28.dp) * keys.size + 4.dp * (keys.size-1)
            if(enoughWidth) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                keys.forEach { skill ->
                    PlayerTextButton({selected=skill},modifier=Modifier.weight(1f)) {
                        Column { Text(cloudLabel(skill),style=style); Text(passives.obj(skill).field("value") ?: "Unknown") }
                    }
                }
            } else Column(Modifier.fillMaxWidth()) {
                keys.forEach { skill ->
                    PlayerTextButton({selected=skill},modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(cloudLabel(skill),style=style); Text(passives.obj(skill).field("value") ?: "Unknown")
                        }
                    }
                }
            }
        }
    }
    if(passives.size>3) PlayerTextButton({showAll=true}) { Text("All passive checks") }
    if(showAll) PlayerSheet("All passive checks",{showAll=false}) {
        passives.keys.sorted().forEach { key ->
            PlayerTextButton({showAll=false;selected=key}) { Text(cloudLabel(key)+" · "+(passives.obj(key).field("value") ?: "Unknown")) }
        }
    }
    selected?.let { skill ->
        val value=passives.obj(skill)
        val active=sheet.obj("skills").obj(skill)
        val stat=active.field("ability")
        val inactiveIds=sheet.list("suppressedEffects").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val effectIds=(sheet.list("activeEffects").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }+inactiveIds).distinct()
        var definitions by remember(actorId,skill,effectIds) { mutableStateOf<List<JsonObject>>(emptyList()) }
        var loadFailure by remember(actorId,skill,effectIds) { mutableStateOf(false) }
        LaunchedEffect(actorId,skill,effectIds) {
            try { definitions=effectIds.mapNotNull { loadEffect(it) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { loadFailure=true }
        }
        fun relevant(row: JsonObject): Boolean {
            val d = row.obj("data")
            val key = d.field("key")
            return when(d.field("target")) {
                "passive","skill" -> key in listOf(skill,"all")
                "ability_score","ability_check" -> key in listOf(stat,"all")
                "roll_edge" -> key in listOf("skill:"+skill,"all")
                else -> false
            }
        }
        PlayerSheet("Passive "+cloudLabel(skill),{selected=null}) {
            Text("Passive total · " + (value.field("value") ?: "Unknown"),style=MaterialTheme.typography.titleLarge)
            Text("Active check · " + (active.field("value") ?: "Unknown"))
            stat?.let { Text(it+" modifier · "+(sheet.obj("abilities").obj(it).field("modifier") ?: "Unknown")) }
            Text("Training · " + when(active.field("rank")) { "2" -> "Expertise"; "1" -> "Proficient"; else -> "Untrained" } +
                " · proficiency bonus " + (sheet.field("proficiency") ?: "Unknown"))
            Text("Circumstance · " + cloudLabel(value.field("mode") ?: "unknown") + " · " + (value.field("adjustment") ?: "Unknown"))
            listOf("Ability sources" to sheet.obj("abilities").obj(stat.orEmpty()),"Check sources" to sheet.obj("checks").obj(stat.orEmpty()),
                "Active skill sources" to active,"Passive-only sources" to value).forEach { (label,entry) ->
                if(entry.list("sources").isNotEmpty()) { Text(label,style=MaterialTheme.typography.titleSmall); RecordFields(entry?.get("sources") ?: JsonNull) }
            }
            definitions.filter(::relevant).forEach { row ->
                val id=row.field("id") ?: return@forEach
                val data=row.obj("data") ?: return@forEach
                val suppressed=id in inactiveIds
                val label=row.field("title") ?: id
                PlayerDisclosure(label + if(suppressed) " · inactive" else " · active",initiallyOpen=false) {
                    data.field("contextTag")?.let { Text("Required circumstance · "+it) }
                    data.field("source")?.let { Text(it) }
                    PlayerTextButton({ selected=null;inspect(id) }) { Text("Inspect source") }
                    PlayerTextButton({
                        selected=null
                        open("revise_effect",buildJsonObject {
                            data.forEach { (k,v) -> if(v != JsonNull) put(k,v) }
                            put("effectId",id);put("actorId",actorId);put("name",label)
                        })
                    },enabled=online) { Text("Edit effect") }
                }
            }
            if(loadFailure) Text("Some source details are unavailable.")
            PlayerTextButton({selected=null;open("define_effect",buildJsonObject {
                put("actorId",actorId);put("effectId",UUID.randomUUID().toString());put("target","passive");put("key",skill)
            })},enabled=online){Text("Add passive-only modifier")}
        }
    }
}
