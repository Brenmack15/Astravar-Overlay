package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.*

@Composable internal fun CloudConditionSources(actorId:String, actor:JsonObject?, online:Boolean,
    inspect:(String)->Unit, review:(JsonObject)->Unit) {
    val now=(actor?.get("gameSeconds") as? JsonPrimitive)?.longOrNull
    (actor?.get("conditionSources") as? JsonArray).orEmpty().forEach { entry ->
        val condition=entry.jsonObject
        val key=(condition["key"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        if(key=="exhaustion")return@forEach
        val source=(condition["sourceId"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        val manual=source.startsWith("manual:")
        val label=if(manual)source.removePrefix("manual:") else source
        val expiry=(condition["expiresAt"] as? JsonPrimitive)?.longOrNull
        PlayerDisclosure("${cloudLabel(key)} · ${label}") {
            Text("Source · $label")
            if(expiry!=null) {
                Text("Expires at game time $expiry s")
                if(now!=null)Text("${(expiry-now).coerceAtLeast(0)} seconds remaining")
            }
            if(manual)PlayerTextButton({
                review(buildJsonObject {put("actorId",actorId);put("key",key);put("sourceId",label);put("enabled",false)})
            },enabled=online){Text("Remove this source")}
            else PlayerTextButton({inspect(source)}){Text("Inspect source")}
        }
    }
}
