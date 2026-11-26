package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

private fun JsonObject?.labelOf(key:String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull

@Composable internal fun CloudRollInterrupt(client:CloudCampaignClient,snapshot:CloudSnapshot,actorId:String,rule:JsonObject,
    window:String?,online:Boolean,close:()->Unit,review:(JsonObject)->Unit) {
    val configuration=rule.getValue("configuration").jsonObject
    val range=configuration.getValue("range").jsonPrimitive.int
    var after by rememberSaveable(rule["id"].toString()) { mutableStateOf("") }
    var rolls by remember(snapshot.version,after) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var names by remember(snapshot.version,after) { mutableStateOf<Map<String,String>>(emptyMap()) }
    var next by remember(snapshot.version,after) { mutableStateOf<String?>(null) }
    var error by remember(snapshot.version,after) { mutableStateOf<String?>(null) }
    var selected by remember(snapshot.version,after) { mutableStateOf<JsonObject?>(null) }
    var distance by rememberSaveable(selected?.get("id").toString()){mutableStateOf("")}
    var visible by rememberSaveable(selected?.get("id").toString()){mutableStateOf(false)}
    var open by rememberSaveable(selected?.get("id").toString()){mutableStateOf(false)}
    val cost=configuration["cost"] as? JsonObject
    var pool by remember(snapshot.version) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(snapshot.version,after,online){
        try {
            val page=client.records(after=after,kinds=setOf("roll"))
            val candidates=page.filter { row ->
                val data=row["data"] as? JsonObject
                data.labelOf("kind") in setOf("skill","save","ability") && data.labelOf("status")=="resolved" && data.labelOf("appliedUseId")==null &&
                    data.labelOf("adjudicated")!="true" && data.labelOf("reactionWindow")==window
            }
            val actors=mutableMapOf<String,String>()
            for(row in candidates){val id=row.labelOf("actorId")?:continue
                if(id !in actors)client.record(id)?.labelOf("title")?.let {actors[id]=it}
            }
            names=actors;rolls=candidates;next=if(page.size==50)page.last().labelOf("id")else null
            cost.labelOf("poolId")?.let{pool=client.record(it)}
        }catch(e:CancellationException){throw e}catch(e:Exception){error=cloudError(e)}
    }
    PlayerSheet(rule.labelOf("title")?:"Roll reaction",close){
        Text("Reaction · range $range ft")
        val poolData=pool?.get("data") as? JsonObject
        if(cost!=null)Text("${pool.labelOf("title") ?: "Resource"} · ${cost.labelOf("amount")} required · ${poolData.labelOf("current") ?: "Unknown"} remaining")
        val row=selected
        if(row==null) {
            error?.let{Text(it)}
            for(roll in rolls) {
                val data=roll.getValue("data").jsonObject
                PlayerEntry("${roll.labelOf("title")} · ${data.labelOf("total")}",listOfNotNull(names[roll.labelOf("actorId")],data.labelOf("text")).joinToString(" · "),"Die",{selected=roll})
            }
            if(rolls.isEmpty() && error==null)Text("No eligible recorded roll on this page.")
            next?.let{cursor->PlayerTextButton({after=cursor}){Text("More rolls")}}
            if(after.isNotEmpty())PlayerTextButton({after=""}){Text("First page")}
        }else{
            val data=row.getValue("data").jsonObject;val target=row.getValue("actorId").jsonPrimitive.content
            names[target]?.let{Text(it)};Text(data.labelOf("text")?:"Recorded roll")
            if(target!=actorId)Field("Distance to target in feet",distance,{distance=it.take(4)})
            val feet=if(target==actorId)0 else distance.toIntOrNull()
            PlayerTextButton({visible=!visible}){Text(if(visible)"Target visible · confirmed" else "Confirm target is visible")}
            PlayerTextButton({open=!open}){Text(if(open)"Interrupt window · confirmed" else "Confirm interrupt window is open")}
            PlayerButton({
                review(buildJsonObject{
                    put("actorId",actorId);put("actionId",rule.getValue("id"));put("useId",UUID.randomUUID().toString());put("declared",true)
                    putJsonObject("reaction"){put("rollId",row.getValue("id"));put("targetActorId",target);put("distanceFeet",requireNotNull(feet));put("visible",true);put("windowOpen",true)}
                })
            },enabled=online && visible && open && feet!=null && feet in 0..range){Text("Review reaction")}
            PlayerTextButton({selected=null}){Text("Choose another roll")}
        }
    }
}
