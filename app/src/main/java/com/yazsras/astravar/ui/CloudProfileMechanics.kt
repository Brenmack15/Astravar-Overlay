package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@Composable internal fun CloudProfileDefenses(sheet: JsonObject?, inspect: (String)->Unit) {
    val defenses = sheet?.get("defenses") as? JsonArray
    if (defenses == null) { Text("Defense sources are unavailable."); return }
    if (defenses.isEmpty()) { Text("No active damage defenses recorded."); return }
    for (entry in defenses) {
        val row=entry.jsonObject
        Text("${cloudLabel(row.cloudText("type"))} · ${cloudLabel(row.cloudText("kind"))}",style=MaterialTheme.typography.titleMedium)
        Text((row["provenance"] as? JsonPrimitive)?.contentOrNull ?: "Unknown source")
        (row["contextTag"] as? JsonPrimitive)?.contentOrNull?.let{Text("Applies during · ${cloudLabel(it)}")}
        if(row["requiresAttunement"]?.jsonPrimitive?.booleanOrNull==true)Text("Requires attunement")
        if(row["requiresOperating"]?.jsonPrimitive?.booleanOrNull==true)Text("Requires active equipment")
        PlayerTextButton({inspect(row.cloudText("source"))}){Text((row["title"] as? JsonPrimitive)?.contentOrNull ?: "Inspect defense source")}
    }
}

@Composable internal fun CloudProfileFeature(client: CloudCampaignClient, snapshot: CloudSnapshot, feature: JsonObject, close: ()->Unit, inspect: (String)->Unit) {
    val d=feature.getValue("data").jsonObject
    PlayerSheet(feature.cloudText("title"),close) {
        (d["description"] as? JsonPrimitive)?.contentOrNull?.let {Text(it)}
        Text((d["source"] as? JsonPrimitive)?.contentOrNull ?: "Unknown source")
        val feat=d["feat"] as? JsonObject
        feat?.let {
            Text("Pinned rules · ${cloudLabel(it.cloudText("edition"))}")
            (it["includedExpertise"] as? JsonArray).orEmpty().forEach { skill->Text("Expertise · ${cloudLabel(skill.jsonPrimitive.content)}") }
        }
        (d["lipReading"] as? JsonObject)?.let { reading->
            if(reading["visibleMouth"]?.jsonPrimitive?.booleanOrNull==true && reading["understoodLanguage"]?.jsonPrimitive?.booleanOrNull==true)
                Text("Lip-reading requires a visible mouth and an understood language.")
        }
        (d["references"] as? JsonArray).orEmpty().forEach { ref->
            val row=ref.jsonObject
            val id=row.cloudText("id")
            val label by produceState<String?>(null,snapshot.binding,snapshot.version,id) {
                value=try {client.record(id)?.cloudText("title")}catch(e:CancellationException){throw e}catch(_:Exception){null}
            }
            PlayerOutlinedButton({inspect(id)},enabled=label!=null){Text(label ?: "Linked ${cloudLabel(row.cloudText("kind")).lowercase()}")}
        }
        PlayerTextButton({inspect(feature.cloudText("id"))}){Text("Full feature record")}
    }
}
