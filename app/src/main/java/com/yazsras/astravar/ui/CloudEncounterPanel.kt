package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

private fun JsonObject?.encText(key:String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject?.encObject(key:String) = this?.get(key) as? JsonObject
private fun JsonObject?.encRows(key:String) = (this?.get(key) as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
private fun encounterArgs(vararg fields:Pair<String,String>)=buildJsonObject{fields.forEach { (k,v)->put(k,v) }}
internal fun reviewedEncounterStart(id:String,title:String,roster:Map<String,String>,anchors:Map<String,String> = emptyMap()):JsonObject {
    val rolls=roster.values.filter(String::isNotBlank)
    require(title.trim().length in 1..160 && roster.size in 1..16 && rolls.distinct().size==rolls.size)
    require(roster.all { (actor,roll) -> (anchors[actor] in roster.keys) == roll.isBlank() })
    return buildJsonObject {
        put("encounterId",id);put("name",title.trim());putJsonArray("participants"){
            roster.forEach { (actor,roll)->addJsonObject{put("actorId",actor);if(roll.isNotBlank())put("initiativeRollId",roll)} } }
    }
}

@Composable internal fun CloudEncounterPanel(client:CloudCampaignClient,snapshot:CloudSnapshot,online:Boolean,query:String,
    open:(String,JsonObject)->Unit) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    PlayerButton({route="new"},enabled=online) {Text("Start combat encounter")}
    PlayerTextButton({open("record_chronicle_event",buildJsonObject{
        put("eventId",UUID.randomUUID().toString());put("category","encounter");put("status","unresolved")
        put("worldTime",JsonNull);put("correctsId",JsonNull);put("subjects",JsonArray(emptyList()))
    })},enabled=online){Text("Record non-combat encounter")}
    CachedRecordList(client,snapshot,setOf("encounter","timeline_event"),null,query,allowed={ row ->
        row.encText("kind")=="encounter" || row.encObject("data").encText("category")=="encounter"
    }){row->route=row.encText("id")}
    route?.let { id->key(id){
        if(id=="new")EncounterSetup(client,snapshot,online,{route=null}){ args->route=null;open("start_encounter",args) }
        else {
            var encounter by remember(snapshot){mutableStateOf<JsonObject?>(null)}
            val actors=remember(snapshot){mutableStateMapOf<String,JsonObject>()}
            val sheets=remember(snapshot,online){mutableStateMapOf<String,JsonObject>()}
            var failure by remember(snapshot){mutableStateOf(false)}
            LaunchedEffect(id,snapshot,online){
                try{
                    val record=client.record(id);encounter=record
                    val data=record.encObject("data")
                    data.encRows("initiative").forEach { slot->slot.encText("actorId")?.let{actor->
                        client.record(actor)?.let{actors[actor]=it}
                        if(online && actor in actors)try{client.projection(snapshot,"sheet/$actor").encObject("sheet")?.let{sheets[actor]=it}}
                        catch(e:CancellationException){throw e}catch(_:Exception){sheets.remove(actor)}
                    } }
                }catch(e:CancellationException){throw e}catch(_:Exception){failure=true}
            }
            fun request(operation:String,args:JsonObject){route=null;open(operation,args)}
            val d=encounter.encObject("data");val chain=d.encRows("initiative")
            val active=d.encText("active")=="true";val index=d.encText("index")?.toIntOrNull()?:-1
            val current=chain.getOrNull(index).encText("actorId")
            PlayerSheet(encounter.encText("title")?:"Encounter",{route=null}){
                if(failure)Text("This encounter could not be read.") else if(d==null)Text("Reading encounter…")
                else if(encounter.encText("kind")=="timeline_event"){
                    Text("Non-combat encounter",style=MaterialTheme.typography.titleMedium)
                    d.encText("status")?.let{Text(cloudLabel(it))}
                    d.encText("narrative")?.let{Text(it)}
                    d.encText("worldTime")?.let{Text(it)}
                    d.encText("source")?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                }else{
                    Text(if(active)"Round ${d.encText("round")?:"Unknown"}" else "Encounter completed",style=MaterialTheme.typography.titleLarge)
                    if(!active)d.encText("outcome")?.let{Text(it)}
                    chain.forEachIndexed { i,slot ->
                        val actorId=slot.encText("actorId")?:return@forEachIndexed
                        val actor=actors[actorId];val a=actor.encObject("data")
                        val label=actor.encText("title")?:"Participant"
                        PlayerDisclosure("${slot.encText("total")?:"—"} · $label"+if(active && index==i)" · Current turn" else "",initiallyOpen=active && index==i){
                            if(a!=null){
                                val sheet=sheets[actorId]
                                Text("Hit points · ${a.encText("currentHp")?:"Unknown"} / ${sheet.encText("maxHp")?:"Unknown"}")
                                if(sheet==null)a.encText("maxHp")?.let{Text("Base HP maximum · $it",style=MaterialTheme.typography.bodySmall)}
                                a.encText("temporaryHp")?.takeIf{it.toIntOrNull()?.let { n->n>0 }==true}?.let{Text("Temporary HP · $it")}
                                listOf("conditions" to "Conditions","movement" to "Movement","concentration" to "Concentration").forEach{(key,title)->
                                    (sheet?.get(key) ?: a[key])?.takeUnless{it==JsonNull}?.let{Text(title);RecordFields(it)}
                                }
                                if(active){
                                    PlayerTextButton({request("apply_hp_change",encounterArgs("actorId" to actorId))},enabled=online){Text("Damage, healing or temporary HP")}
                                    PlayerTextButton({request("set_condition",encounterArgs("actorId" to actorId))},enabled=online){Text("Manage condition")}
                                }
                            }
                            if(active)d.encObject("economy").encObject(actorId)?.let{Text("Action economy");RecordFields(it)}
                            slot.encText("rollId")?.let {Text("Initiative roll · $it",style=MaterialTheme.typography.bodySmall)}
                            slot.encText("afterActorId")?.let{anchor->Text("Follows ${actors[anchor].encText("title")?:"participant"} in initiative")}
                        }
                    }
                    if(active && current!=null){
                        val identity=encounterArgs("encounterId" to id,"currentActorId" to current)
                        PlayerButton({request("advance_encounter_turn",identity)},enabled=online){Text("Review next turn")}
                        PlayerTextButton({request("end_encounter",identity)},enabled=online){Text("Record outcome and end")}
                    }
                }
            }
        }
    } }
}

@Composable private fun EncounterSetup(client:CloudCampaignClient,snapshot:CloudSnapshot,online:Boolean,close:()->Unit,
    review:(JsonObject)->Unit){
    val id=rememberSaveable{UUID.randomUUID().toString()}
    var name by rememberSaveable{mutableStateOf("")}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var roster by rememberSaveable{mutableStateOf("")}
    val entries=roster.lineSequence().filter{it.contains('=')}.associate{it.substringBefore('=') to it.substringAfter('=')}
    val actors=remember(snapshot){mutableStateMapOf<String,JsonObject>()}
    val anchors=actors.mapNotNull { (id,row)->row.encObject("data").encText("initiativeAfterActorId")?.let{id to it} }.toMap()
    var rolls by remember(snapshot,selected){mutableStateOf<List<JsonObject>>(emptyList())}
    var error by remember(snapshot,selected){mutableStateOf<String?>(null)}
    var query by rememberSaveable{mutableStateOf("")}
    fun change(map:Map<String,String>){roster=map.entries.joinToString("\n"){"${it.key}=${it.value}"}}
    LaunchedEffect(snapshot,selected,entries.keys){
        try{
            entries.keys.forEach{actor->client.record(actor)?.let{actors[actor]=it}}
            selected?.let{actor->
                client.record(actor)?.let{actors[actor]=it}
                val all=mutableListOf<JsonObject>()
                var after=""
                do {
                    val next=client.records(after,setOf("roll"),actor)
                    all+=next.filter{val d=it.encObject("data");d.encText("kind")=="initiative"&&d.encText("status")=="resolved"}
                    after=next.lastOrNull().encText("id").orEmpty()
                } while(next.size==50)
                rolls=all.sortedByDescending{it.encText("revision")?.toLongOrNull()}
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){error=cloudError(e)}
    }
    PlayerSheet("Start combat encounter",close){
        Field("Encounter name",name,{name=it.take(160)})
        entries.forEach{(actor,roll)->
            val anchor=anchors[actor]?.takeIf{it in entries}
            val order=when {roll.isNotBlank()->roll;anchor!=null->"Follows ${actors[anchor].encText("title")?:"selected participant"}";else->"Initiative needed"}
            Text("${actors[actor].encText("title")?:"Participant"} · $order")
            if(anchor!=null && roll.isNotBlank())PlayerTextButton({change(entries+(actor to ""))}){Text("Use configured order")}
            if(anchor==null && roll.isBlank())PlayerTextButton({selected=actor}){Text("Select initiative")}
            PlayerTextButton({change(entries-actor)}){Text("Remove participant")}
        }
        if(selected==null){
            PlayerSearch(query,{query=it},"Find participant")
            CachedRecordList(client,snapshot,setOf("actor"),null,query,
                allowed={it.encObject("data").encText("encounterId")==null && it.encText("id") !in entries}){selected=it.encText("id")}
        }else{
            PlayerTextButton({selected=null}){Text("Choose another participant")}
            val anchor=anchors[selected]?.takeIf{it in entries}
            if(anchor!=null){
                PlayerButton({selected?.let{change(entries+(it to ""))};selected=null},enabled=entries.size<16 || selected in entries){
                    Text("Follow ${actors[anchor].encText("title")?:"selected participant"} in initiative")
                }
            } else if(rolls.isEmpty())Text("A recorded initiative result is required for this participant.")
            if(anchor==null)rolls.forEach{r->
                PlayerTextButton({selected?.let {change(entries+(it to r.getValue("id").jsonPrimitive.content))};selected=null},enabled=entries.size<16){
                    Text("${r.encObject("data").encText("total")?:"—"} · Initiative · revision ${r.encText("revision")}")
                }
            }
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        val reviewed=runCatching{reviewedEncounterStart(id,name,entries,anchors)}.getOrNull()
        PlayerButton({reviewed?.let(review)},enabled=online&&reviewed!=null&&actors.keys.containsAll(entries.keys)){Text("Review encounter start")}
    }
}
