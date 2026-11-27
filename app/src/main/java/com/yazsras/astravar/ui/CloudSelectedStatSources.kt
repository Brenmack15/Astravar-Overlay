package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@Composable internal fun CloudSelectedStatSources(client:CloudCampaignClient,snapshot:CloudSnapshot,
    actor:JsonObject,stat:String,sheet:JsonObject?,online:Boolean,openSection:(String)->Unit,inspect:(String)->Unit) {
    val data=actor.getValue("data").jsonObject
    val recent=(data["abilitySources"] as? JsonObject)?.get(stat) as? JsonObject
    val sources=(data["importedSources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val ability=(sheet?.get("abilities") as? JsonObject)?.get(stat) as? JsonObject
    PlayerDisclosure("Character source evidence") {
        Text(actor.cloudText("title"))
        Text("Actor revision ${actor.getValue("revision")} · level ${data["level"]?.jsonPrimitive?.contentOrNull ?: "Unknown"}")
        val ac=(sheet?.get("ac") as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
        Text("Armor class · ${ac ?: "Unknown"}")
        data["source"]?.jsonPrimitive?.contentOrNull?.let { Text(it) }
        val actorId=actor.cloudText("id")
        val sections by produceState(emptySet<String>(),snapshot.binding,snapshot.version,actorId) {
            value=try {
                buildSet {
                    if(client.records(kinds=setOf("attack"),actorId=actorId).isNotEmpty())add("Actions")
                    if(client.records(kinds=setOf("spell_grant"),actorId=actorId).isNotEmpty())add("Spells")
                }
            } catch(e:CancellationException){throw e} catch(_:OwnerSyncFailure){emptySet()}
        }
        if("Actions" in sections)PlayerTextButton({openSection("Actions")}){Text("Inspect attack sources")}
        if("Spells" in sections)PlayerTextButton({openSection("Spells")}){Text("Inspect spell sources and DCs")}
        if(recent!=null){
            Text("Latest score source · "+(recent["source"]?.jsonPrimitive?.contentOrNull ?: "Unknown"))
            recent["commandId"]?.jsonPrimitive?.contentOrNull?.let { id->CloudReceiptLink(client,snapshot,online,id) }
        }
        for (source in sources) {
            val family=when(source.cloudText("family")){
                "playable.characters"->"Playable character"
                "records.characterProfiles"->"Original campaign profile"
                "authority.actors"->"Campaign membership"
                else->continue
            }
            Text("$family · ${source.cloudText("sourceId")}")
            Text("Source revision · ${source["revision"]?.jsonPrimitive?.contentOrNull ?: "Unknown"}")
            val fields=(source["fields"] as? JsonArray).orEmpty().map { cloudLabel(it.jsonPrimitive.content) }
            if(fields.isNotEmpty())Text("Mapped values · ${fields.joinToString()}")
        }
        if(sources.isEmpty())Text("No reviewed source identity is available in this snapshot.")
        val applied=(ability?.get("sources") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        for (effect in applied) {
            val id=(effect["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            PlayerTextButton({inspect(id)}){ Text("Inspect ${effect["source"]?.jsonPrimitive?.contentOrNull ?: "effect source"}") }
        }
    }
}
