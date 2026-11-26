package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

private fun JsonObject.optionalText(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

@Composable internal fun CloudProgression(client: CloudCampaignClient, snapshot: CloudSnapshot, actor: JsonObject,
    online: Boolean, dismiss: () -> Unit, review: (String,JsonObject) -> Unit) {
    val actorId=actor.cloudText("id")
    val data=actor.getValue("data").jsonObject
    val level=data["level"]?.jsonPrimitive?.intOrNull
    val tracks=(data["classTracks"] as? JsonArray).orEmpty().map { it.jsonObject }
    var selected by rememberSaveable(actorId,level) { mutableStateOf<String?>(null) }
    var newClass by rememberSaveable(actorId,level) { mutableStateOf(false) }
    val newClassId=rememberSaveable(actorId,level){UUID.randomUUID().toString()}
    var className by rememberSaveable(actorId,level){mutableStateOf("")}
    var classEdition by rememberSaveable(actorId,level){mutableStateOf<String?>(null)}
    var classSource by rememberSaveable(actorId,level){mutableStateOf("")}
    var subclassName by rememberSaveable(actorId,selected,newClass){mutableStateOf("")}
    var subclassSource by rememberSaveable(actorId,selected,newClass){mutableStateOf("")}
    var source by rememberSaveable(actorId,level){mutableStateOf("")}
    var hp by rememberSaveable(actorId,level){mutableStateOf("")}
    var increaseCurrent by rememberSaveable(actorId,level){mutableStateOf(false)}
    var poolId by rememberSaveable(actorId,level,selected){mutableStateOf<String?>(null)}
    var pools by remember(actorId,snapshot.version) {mutableStateOf(emptyList<JsonObject>())}
    var poolError by remember(actorId,snapshot.version){mutableStateOf<String?>(null)}
    val track=tracks.find {it.optionalText("classId")==selected}
    val pool=pools.find {it.optionalText("id")==poolId}
    val poolData=pool?.get("data") as? JsonObject
    val maximum=poolData?.get("maximum")?.jsonPrimitive?.intOrNull
    LaunchedEffect(actorId,snapshot.version){
        try {
            pools=(data["poolIds"] as? JsonArray).orEmpty().mapNotNull { client.record(it.jsonPrimitive.content) }
                .filter { (it["data"] as? JsonObject)?.optionalText("type")=="hit_die" }
        }catch(e:CancellationException){throw e}catch(e:Exception){poolError=cloudError(e)}
    }
    PlayerSheet("Level advancement",dismiss) {
        Text(actor.cloudText("title"),style=MaterialTheme.typography.titleLarge)
        Text("Character level · " + (level?.toString() ?: "Unknown"))
        data.optionalText("rulesProfile")?.let { Text("Actor rules · ${cloudLabel(it)}") }
        Text("HP · ${data.optionalText("currentHp") ?: "Unknown"} / ${data.optionalText("maxHp") ?: "Unknown"}")
        tracks.forEach { item ->
            val id=item.cloudText("classId")
            PlayerOutlinedButton({selected=id;newClass=false},enabled=online && level in 1..19){
                Text("${item.cloudText("name")} · level ${item.getValue("level").jsonPrimitive.int}")
            }
            Text("${cloudLabel(item.cloudText("edition"))} · ${item.cloudText("source")}")
            (item["subclass"] as? JsonObject)?.let { Text("${it.cloudText("name")} · ${it.cloudText("source")}") }
        }
        if(tracks.isEmpty()) {
            Text("Class levels are unknown.")
            PlayerTextButton({review("set_actor_profile",buildJsonObject {put("actorId",actorId);put("classTracks",JsonArray(emptyList()))})},
                enabled=online){Text("Record existing class levels")}
        }else if(level in 1..19){
            PlayerTextButton({newClass=true;selected=null},enabled=online){Text("Advance in a new class")}
        }
        if(newClass){
            OutlinedTextField(className,{className=it},label={Text("New class name")},singleLine=true)
            for(edition in listOf("legacy_2014","revised_2024","custom")){
                PlayerTextButton({classEdition=edition}){ Text(if(edition==classEdition) "${cloudLabel(edition)} · selected" else cloudLabel(edition)) }
            }
            OutlinedTextField(classSource,{classSource=it},label={Text("Class rules source")},singleLine=true)
        }else if(track!=null)Text("Advancing · ${track.cloudText("name")}")
        if((track!=null||newClass) && track?.get("subclass") !is JsonObject){
            PlayerDisclosure("Record a newly earned subclass") {
                OutlinedTextField(subclassName,{subclassName=it},label={Text("Subclass name")},singleLine=true)
                OutlinedTextField(subclassSource,{subclassSource=it},label={Text("Subclass rules source")},singleLine=true)
            }
        }
        if(level in 1..19){
            OutlinedTextField(source,{source=it},label={Text("Advancement source")},singleLine=true)
            OutlinedTextField(hp,{hp=it},label={Text("Confirmed maximum HP gain")},singleLine=true)
            if(hp.toIntOrNull()!=null && data.optionalText("currentHp")!=null)PlayerTextButton({increaseCurrent=!increaseCurrent}){
                Text(if(increaseCurrent)"Apply gain to current HP · selected" else "Also apply gain to current HP")
            }
            for(row in pools){
                val d=row.getValue("data").jsonObject
                val id=row.cloudText("id")
                if(selected==null||selected!=d.optionalText("classId"))continue
                val balance=d["current"]?.jsonPrimitive?.intOrNull;val max=d["maximum"]?.jsonPrimitive?.intOrNull
                Text("${row.cloudText("title")} · ${balance ?: "Unknown"} / ${max ?: "Unknown"}")
                PlayerTextButton({poolId=if(poolId==id)null else id},enabled=online && balance!=null && max!=null){
                    Text("Grant one ${row.cloudText("title")}" + if(poolId==id)" · selected" else "")
                }
            }
            poolError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            val classReady=tracks.isEmpty() || track!=null || newClass&&className.isNotBlank()&&classEdition!=null&&classSource.isNotBlank()
            val valid=source.isNotBlank() && source.length<=300 && (hp.isEmpty()||hp.toIntOrNull() in 0..1000000) &&
                subclassName.isBlank()==subclassSource.isBlank() && classReady
            PlayerButton({
                review("advance_actor_level",buildJsonObject {
                    put("actorId",actorId);put("eventId",UUID.randomUUID().toString());put("fromLevel",level!!);put("toLevel",level+1);put("source",source.trim())
                    if(track!=null||newClass)putJsonObject("classGain"){
                        put("classId",track?.cloudText("classId")?:newClassId);put("fromClassLevel",track?.get("level")?.jsonPrimitive?.int?:0)
                        if(newClass)putJsonObject("newClass"){put("name",className.trim());put("edition",classEdition);put("source",classSource.trim())}
                        if(subclassName.isNotBlank())putJsonObject("subclass"){put("name",subclassName.trim());put("source",subclassSource.trim())}
                    }
                    hp.toIntOrNull()?.let{put("hitPointGain",it);if(increaseCurrent)put("increaseCurrentHp",true)}
                    if(pool!=null&&maximum!=null)putJsonArray("capacities"){
                        addJsonObject {put("poolId",pool.cloudText("id"));put("previous",maximum);put("maximum",maximum+1);put("granted",1)}
                    }
                })
            },enabled=online&&valid){Text("Review advancement")}
        }
    }
}
