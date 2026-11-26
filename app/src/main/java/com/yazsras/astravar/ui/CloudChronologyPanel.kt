package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

private fun JsonObject?.chronText(key:String)=(this?.get(key) as? JsonPrimitive)?.contentOrNull
private val chronologyFields=listOf("summary","state","worldStart","worldEnd","source","eventIds")
private val arcFields=listOf("classification","parentArcId")
private val sessionFields=listOf("playedOn","previousSessionId","arcId","openingCheckpointId","closingCheckpointId")

internal fun chronologyDraft(kind:String,id:String,record:JsonObject?=null,parent:String?=null,previous:String?=null):JsonObject{
    require(kind=="campaign_session"||kind=="story_arc")
    val session=kind=="campaign_session"
    val data=record?.get("data") as? JsonObject
    if(record!=null)require(record.chronText("kind")==kind && record.chronText("id")==id)
    return buildJsonObject{
        put(if(session)"sessionId" else "arcId",id)
        put("title",record.chronText("title").orEmpty())
        for(field in chronologyFields+(if(session)sessionFields else arcFields)){
            put(field,data?.get(field)?:when(field){
                "state"->JsonPrimitive("unresolved")
                "eventIds"->JsonArray(emptyList())
                "source"->JsonPrimitive("")
                "classification"->JsonPrimitive("story_arc")
                "parentArcId","arcId"->parent?.let(::JsonPrimitive)?:JsonNull
                "previousSessionId"->previous?.let(::JsonPrimitive)?:JsonNull
                else->JsonNull
            })
        }
        if(record!=null){put("expectedRevision",record.getValue("revision"));put("reason","")}
    }
}

@Composable internal fun CloudChronologyPanel(kind:String,client:CloudCampaignClient,snapshot:CloudSnapshot,
    online:Boolean,query:String,open:RecordOperation,inspect:(String)->Unit){
    val session=kind=="campaign_session"
    var selected by rememberSaveable(kind){mutableStateOf<String?>(null)}
    fun begin(target:String,parent:String?=null,previous:String?=null){
        selected=null
        open(if(target=="campaign_session")"record_campaign_session" else "define_story_arc",
            chronologyDraft(target,UUID.randomUUID().toString(),parent=parent,previous=previous))
    }
    PlayerButton({begin(kind)},enabled=online){Text(if(session)"Record session" else "Create adventure or arc")}
    CachedRecordList(client,snapshot,setOf(kind),null,query){selected=it.chronText("id")}
    selected?.let{id->key(id,snapshot.version){
        var row by remember{mutableStateOf<JsonObject?>(null)}
        val linked=remember{mutableStateMapOf<String,String>()}
        var loading by remember{mutableStateOf(true)}
        LaunchedEffect(client,id,snapshot){
            try{
                row=client.record(id)
                val d=row?.get("data") as? JsonObject ?:return@LaunchedEffect
                val ids=(if(session)sessionFields else arcFields).mapNotNull{key->
                    if(key.endsWith("Id"))d.chronText(key) else null
                }+(d["eventIds"] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}
                ids.distinct().forEach{recordId->client.record(recordId)?.chronText("title")?.let{linked[recordId]=it}}
            }catch(e:CancellationException){throw e}catch(_:Exception){row=null}finally{loading=false}
        }
        PlayerSheet(row.chronText("title") ?: if(session)"Session" else "Story arc",{selected=null}){
            val record=row;val d=record?.get("data") as? JsonObject
            if(record==null||d==null) Text(if(loading)"Reading…" else "The record is unavailable.")
            else{
                Text(cloudLabel(d.chronText("state").orEmpty()),style=MaterialTheme.typography.titleMedium)
                d.chronText("summary")?.let{Text(it)}
                if(session)Text("Table date · ${d.chronText("playedOn")?:"Unknown"}")
                Text("In-world start · ${d.chronText("worldStart")?:"Unknown"}")
                Text("In-world end · ${d.chronText("worldEnd")?:"Unknown"}")
                val relationNames=if(session)mapOf("arcId" to "Adventure or arc","previousSessionId" to "Previous session",
                    "openingCheckpointId" to "Opening checkpoint","closingCheckpointId" to "Closing checkpoint") else mapOf("parentArcId" to "Parent adventure or arc")
                relationNames.forEach{(key,label)->d.chronText(key)?.let{target->
                    PlayerTextButton({selected=null;inspect(target)},enabled=target in linked){Text("$label · ${linked[target]?:"Unavailable"}")}
                }}
                (d["eventIds"] as? JsonArray).orEmpty().forEach{v->val target=v.jsonPrimitive.content
                    PlayerTextButton({selected=null;inspect(target)},enabled=target in linked){Text("Event · ${linked[target]?:"Unavailable"}")}
                }
                Text("Source · ${d.chronText("source")?:"Unknown"}")
                PlayerOutlinedButton({selected=null;open(if(session)"revise_campaign_session" else "revise_story_arc",
                    chronologyDraft(kind,id,record))},enabled=online){Text(if(session)"Review session correction" else "Review arc correction")}
                if(session)PlayerTextButton({begin("campaign_session",d.chronText("arcId"),id)},enabled=online){Text("Add following session")}
                else{
                    PlayerTextButton({begin("campaign_session",id)},enabled=online){Text("Record session in this arc")}
                    PlayerTextButton({begin("story_arc",id)},enabled=online){Text("Create nested story arc")}
                }
                PlayerDisclosure("Record details"){Text("Revision ${record.chronText("revision")}");Text("Originally recorded ${d.chronText("recordedAt")?:"Unknown"}")}
            }
        }
    }}
}
