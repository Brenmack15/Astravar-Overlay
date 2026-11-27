package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID

private fun JsonObject.str(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull
private val assistanceDice=SecureRandom()
private suspend fun <T> careRead(fallback:T,read:suspend ()->T):T=try{read()}catch(e:CancellationException){throw e}catch(_:Exception){fallback}

internal fun castAidEvidence(evidence:JsonObject,rules:JsonArray?,kind:String,physicalFaces:Map<String,Int> = emptyMap()):JsonObject {
    val active=rules.orEmpty().map{it.jsonObject}.filter{it.getValue("applies").jsonArray.any{entry->entry.jsonPrimitive.content==kind}}
    if(active.isEmpty())return evidence
    return JsonObject(evidence + ("aidDice" to buildJsonArray {active.forEach{rule->add(buildJsonObject{
        put("family",rule.cloudText("family"))
        if(evidence.cloudText("origin")=="app")put("face",1+assistanceDice.nextInt(rule.getValue("sides").jsonPrimitive.int))
        else physicalFaces[rule.cloudText("family")]?.let{put("face",it)}
    })}}))
}

@Composable internal fun CloudCompanionCare(client:CloudCampaignClient,snapshot:CloudSnapshot,actor:JsonObject,online:Boolean,open:(String,JsonObject)->Unit){
    val actorId=actor.cloudText("id")
    val data=actor.getValue("data").jsonObject
    val protocolId=data.str("recoveryProtocolId") ?: return
    var expanded by rememberSaveable(actorId){mutableStateOf(false)}
    PlayerOutlinedButton({expanded=!expanded}){Text("Companion care")}
    if(!expanded)return
    val protocol by produceState<JsonObject?>(null,protocolId,snapshot.version){
        value=try{client.record(protocolId)?.get("data") as? JsonObject}catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    val d=protocol?:return
    val operatorId=d.cloudText("operatorId")
    val state=data.str("activityState")?: "unknown"
    Text("Current state · "+cloudLabel(state))
    val parameters=buildJsonObject{put("actorId",actorId);put("operatorId",operatorId);put("protocolId",protocolId)}
    val section=when(state){"disabled"->"reconstruct";"active"->"repair";else->null}
    if(section!=null){
        val rebuild=section=="reconstruct"
        Text(if(rebuild)"Recorded tool-assisted reconstruction" else "Recorded spell repair")
        val poolId=d.cloudText(if(rebuild)"reconstructionPoolId" else "repairPoolId")
        val pool by produceState<JsonObject?>(null,poolId,snapshot.version){value=careRead(null as JsonObject?){client.record(poolId)?.get("data") as? JsonObject}}
        Text("Uses · "+(pool?.str("current") ?: "Unknown")+" / "+(pool?.str("maximum") ?: "Unknown"))
        Text("Required time · "+(d.str(if(rebuild)"reconstructionSeconds" else "repairSeconds") ?: "Unknown")+" seconds")
        val spellId=d.cloudText("spellId")
        val casts by produceState<List<JsonObject>>(emptyList(),snapshot.version,spellId){
            // Recent pending casts are suggestions. The complete typed form also accepts an older exact cast ID.
            value=careRead(emptyList()){client.actorHistory(operatorId,setOf("cast_instance"),50)}.filter{r->
                val cast=r.getValue("data").jsonObject
                cast.str("spellId")==spellId && cast.str("outcome")=="pending" && (cast["targets"] as? JsonArray)?.contains(JsonPrimitive(actorId))==true
            }
        }
        var castId by rememberSaveable(actorId,section,snapshot.version){mutableStateOf<String?>(null)}
        casts.forEach{r->val id=r.cloudText("id");PlayerTextButton({castId=id}){Text((if(castId==id)"Selected cast · " else "Use cast · ")+r.cloudText("title")+" · "+id)}}
        val tools by produceState<List<JsonObject>>(emptyList(),snapshot.version,rebuild){if(rebuild)value=careRead(emptyList()){client.records(kinds=setOf("item_instance"),actorId=operatorId)}.filter{r->
            val tool=r.getValue("data").jsonObject;tool.str("definitionId")==d.str("toolDefinitionId") && tool.str("custodianId")==operatorId && tool.str("holding")=="carried"
        }}
        var toolId by rememberSaveable(actorId,section,snapshot.version){mutableStateOf<String?>(null)}
        tools.forEach{r->val id=r.cloudText("id");PlayerTextButton({toolId=id}){Text((if(id==toolId)"Selected tools · " else "Use tools · ")+r.cloudText("title"))}}
        var seconds by rememberSaveable(actorId,section){mutableStateOf("")}
        var faces by rememberSaveable(actorId,section){mutableStateOf("")}
        var uninterrupted by rememberSaveable(actorId,section){mutableStateOf(false)}
        OutlinedTextField(seconds,{seconds=it.take(5)},label={Text("Actual elapsed seconds")},singleLine=true)
        if(!rebuild)OutlinedTextField(faces,{faces=it.take(50)},label={Text("Actual repair dice")},singleLine=true)
        PlayerTextButton({uninterrupted=!uninterrupted}){Text(if(uninterrupted)"Uninterrupted procedure confirmed" else "Confirm uninterrupted procedure")}
        PlayerButton({
            val body=buildJsonObject{parameters.forEach{(k,v)->put(k,v)};put("kind",section)
                castId?.let{put("castId",it)};seconds.toIntOrNull()?.let{put("elapsedSeconds",it)}
                if(uninterrupted)put("uninterrupted",true);toolId?.let{put("toolItemId",it)}
                if(!rebuild&&faces.isNotBlank())putJsonObject("dice"){put("origin","player_physical");put("faces",buildJsonArray{
                    faces.trim().split(Regex("[,\\s]+")).forEach{s->s.toIntOrNull()?.let{add(it)}}})}
            }
            open("complete_actor_care",body)
        },enabled=online){Text(if(rebuild)"Review reconstruction" else "Review repair")}
    }
    if(state=="dormant"||state=="active")PlayerTextButton({
        open("set_actor_dormancy",JsonObject(parameters+("state" to JsonPrimitive(if(state=="active")"dormant" else "active"))))
    },enabled=online){Text(if(state=="active")"Review dormancy" else "Review reactivation")}
}

@Composable internal fun CloudCompanionFeature(client:CloudCampaignClient,snapshot:CloudSnapshot,feature:JsonObject,open:(String,JsonObject)->Unit){
    val d=feature.getValue("data").jsonObject
    val actorId=feature.cloudText("actorId")
    (d["infusionBond"] as? JsonObject)?.let{bond->
        val information by produceState<List<JsonObject?>>(emptyList(),snapshot.version,bond){
            value=careRead(emptyList()){listOf("operatorId","knownFeatureId","dedicatedPoolId").map{k->client.record(bond.cloudText(k))}}
        }
        Text("Dedicated companion infusion")
        information.getOrNull(0)?.let{Text("Operator · "+it.cloudText("title"))}
        information.getOrNull(1)?.let{Text("Known infusion · "+it.cloudText("title"))}
        information.getOrNull(2)?.get("data")?.jsonObject?.let{Text("Dedicated slots available · "+(it.str("current") ?: "Unknown")+" / "+(it.str("maximum") ?: "Unknown"))}
    }
    (d["includedScore"] as? JsonObject)?.let{Text("Included in base "+it.cloudText("ability")+" · "+it.str("before")+" → "+it.str("after"))}
    (d["speech"] as? JsonObject)?.let{speech->
        val languages=speech.getValue("languages").jsonArray.map{it.jsonPrimitive.content}
        Text("Installed languages · "+languages.joinToString());Text("Unassigned packages · "+(speech.getValue("capacity").jsonPrimitive.int-languages.size))
        if(speech["fullSentences"]?.jsonPrimitive?.booleanOrNull==true)Text("Independent full-sentence speech")
        if(speech["translation"]?.jsonPrimitive?.booleanOrNull==true)Text("Interprets heard speech or visible writing between installed languages for listeners who understand the output.")
    }
    (d["telepathy"] as? JsonObject)?.let{t->Text(if(t["twoWay"]?.jsonPrimitive?.booleanOrNull==true)"Two-way private telepathy" else "One-way telepathy")
        if(t["sharedSenses"]?.jsonPrimitive?.booleanOrNull==false)Text("Senses remain independent")}
    if(d.str("socialCheck")!=null){
        Text("Advantage requires a nonhostile audience that perceives the actor. Use Performance to entertain, or Persuasion for harmless goodwill.")
        for(skill in listOf("performance","persuasion"))PlayerTextButton({open("record_check",buildJsonObject{
            put("actorId",actorId);put("rollId",UUID.randomUUID().toString());putJsonObject("check"){
                put("kind","skill");put("key",skill);put("mode","normal");putJsonObject("social"){
                    put("featureId",feature.cloudText("id"));put("purpose",if(skill=="performance")"entertain" else "goodwill")
                }
            }
        })}){Text("Review "+cloudLabel(skill)+" check")}
    }
}

@Composable internal fun CloudDisplacementAction(row:JsonObject,actorId:String,enabled:Boolean,commit:(String,JsonObject)->Unit){
    val rule=row["destinationRequirements"] as? JsonObject ?: return
    val title=row.cloudText("title")
    var show by rememberSaveable(actorId,row.cloudText("id")){mutableStateOf(false)}
    PlayerTextButton({show=true},enabled=enabled){Text("Use $title")}
    if(!show)return
    val sizes=listOf("tiny","small","medium","large","huge","gargantuan")
    val minimum=sizes.indexOf(rule.str("minimumSize"))
    val grounded=rule["groundedOnly"]?.jsonPrimitive?.booleanOrNull==true
    val range=rule.getValue("range").jsonPrimitive.int
    var name by rememberSaveable{mutableStateOf("")}
    var distance by rememberSaveable{mutableStateOf("")}
    var size by rememberSaveable{mutableStateOf<String?>(null)}
    var ground by rememberSaveable{mutableStateOf<Boolean?>(null)}
    var clear by rememberSaveable{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)}
    val allowed=enabled && name.isNotBlank() && distance.toIntOrNull() in 0..range &&
        size!=null && sizes.indexOf(size)>=minimum && ground!=null && (!grounded || ground==true) && clear
    PlayerSheet(title,{show=false}) {
        Text("Range · $range ft")
        if(grounded)Text("This form requires a grounded destination.")
        rule.str("minimumSize")?.let{Text("Required space · "+cloudLabel(it))}
        OutlinedTextField(name,{name=it.take(200)},label={Text("Destination")},singleLine=true)
        OutlinedTextField(distance,{distance=it.take(4)},label={Text("Distance in feet")},singleLine=true)
        androidx.compose.foundation.layout.Box {
            PlayerOutlinedButton({menu=true}){Text(size?.let{cloudLabel(it)+" space"} ?: "Choose available space")}
            DropdownMenu(menu,{menu=false}){for(s in sizes)DropdownMenuItem(text={Text(cloudLabel(s))},onClick={size=s;menu=false})}
        }
        PlayerTextButton({ground=true}){Text(if(ground==true)"Grounded selected" else "Grounded")}
        PlayerTextButton({ground=false}){Text(if(ground==false)"Airborne selected" else "Airborne")}
        PlayerTextButton({clear=!clear}){Text(if(clear)"Destination and passage confirmed" else "Confirm visible, unoccupied space, clear passage and no passengers")}
        PlayerButton({
            val destination=buildJsonObject{put("label",name.trim());put("distanceFeet",distance.toInt());put("grounded",ground==true)
                put("availableSize",requireNotNull(size));put("visible",true);put("unoccupied",true);put("permittedByBarriers",true);put("passengers",0)}
            show=false
            commit("use_action",buildJsonObject{put("actorId",actorId);put("actionId",row.cloudText("id"));put("useId",UUID.randomUUID().toString());put("declared",true);put("destination",destination)})
        },enabled=allowed){Text("Confirm displacement")}
    }
}
