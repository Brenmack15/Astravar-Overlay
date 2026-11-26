package com.yazsras.astravar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID

private val attackDice=SecureRandom()
private fun JsonObject.textValue(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.rowsAt(key:String)=(this[key] as? JsonArray).orEmpty().map { it.jsonObject }
private fun rawEvidence(count:Int,sides:Int)=buildJsonObject {
    require(count in 0..200 && sides in 2..1000)
    put("origin","app");put("faces",JsonArray(List(count){JsonPrimitive(attackDice.nextInt(sides)+1)}))
}
private fun damageEvidence(components:List<JsonObject>,critical:Boolean)=JsonArray(components.map { c->
    val n=c.getValue("dice").jsonPrimitive.int * if(critical && c["critical"]?.jsonPrimitive?.booleanOrNull!=false)2 else 1
    buildJsonObject{put("id",c.cloudText("id"));put("evidence",rawEvidence(n,c.getValue("sides").jsonPrimitive.int))}
})
private fun formula(c:JsonObject):String{
    val dice=c.getValue("dice").jsonPrimitive.int;val flat=c.getValue("flat").jsonPrimitive.int
    val term=if(dice==0)flat.toString() else "${dice}d${c["sides"]}"+when{flat<0->" − ${-flat}";flat>0->" + $flat";else->""}
    return "$term ${c.textValue("type").orEmpty()}"
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun CloudActorRecords(section:String,client:CloudCampaignClient,snapshot:CloudSnapshot,actorId:String,online:Boolean,
    openOperation:(String,JsonObject)->Unit,inspect:(String)->Unit,commit:(String,JsonObject)->Unit){
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    var records by remember(actorId,section,snapshot.version.grantRevision){mutableStateOf<List<JsonObject>>(emptyList())}
    var more by remember {mutableStateOf<String?>(null)}
    var details by rememberSaveable(actorId,section){mutableStateOf<String?>(null)}
    var selection by rememberSaveable(actorId,section){mutableStateOf<String?>(null)}
    var mode by rememberSaveable{mutableStateOf("normal")}
    var error by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(false)}
    suspend fun load(after:String?=null){
        loading=true
        try{
            val rows=if(section=="Actions"&&online){
                val v=snapshot.version
                val suffix="actions/$actorId"+(after?.let{"?after=$it&atRevision=${v.revision}&atEpoch=${v.epoch}&grantRevision=${v.grantRevision}"}?:"")
                val page=client.projection(snapshot,suffix); more=page.textValue("nextAfter")
                page.rowsAt("rows")
            }else{
                val kinds=when(section){
                    "Forms"->setOf("form");"Actions"->setOf("attack","action_definition")
                    else->setOf("roll","roll_batch","rest_report","cast_instance","action_use","attack_attempt","item_event","coin_transaction")
                }
                val page=client.records(after.orEmpty(),kinds,actorId);more=if(page.size==50)page.last().cloudText("id") else null;page
            }
            records=if(after==null)rows else records+rows
            error=null
        }catch(e:CancellationException){throw e}
        catch(e:Exception){error=cloudError(e);more=null;if(after==null)records=emptyList()}
        finally{loading=false}
    }
    LaunchedEffect(snapshot.version,section,actorId,online){load()}
    fun open(name:String, fields:JsonObject=buildJsonObject{}) {
        details=null;selection=null;openOperation(name,JsonObject(fields+("actorId" to JsonPrimitive(actorId))))
    }
    fun perform(row:JsonObject,withDamage:Boolean,standalone:Boolean=false,requested:String="normal") {
        if(!online||row.rowsAt("components").isEmpty() && standalone)return
        val id=row.cloudText("id");val d=row.getValue("configuration").jsonObject
        val components=row.rowsAt("components")
        val operation=if(standalone)"roll_damage_only" else "resolve_attack"
        val body=buildJsonObject{
            put("actorId",actorId);put("attackId",id)
            if(standalone){put("rollId",UUID.randomUUID().toString());put("damage",damageEvidence(components,false))}
            else{
                put("attemptId",UUID.randomUUID().toString());put("includeDamage",withDamage)
                val attack=d.textValue("resolution")=="attack";put("mode",if(attack)requested else "normal")
                var natural:Int?=null
                if(attack){
                    val policy=row.getValue("modes").jsonObject.getValue(requested).jsonObject
                    val evidence=rawEvidence(policy.getValue("count").jsonPrimitive.int,20)
                    put("hit",evidence)
                    val faces=evidence.getValue("faces").jsonArray.map{it.jsonPrimitive.int}
                    natural=when(policy.cloudText("mode")){"advantage"->faces.max();"disadvantage"->faces.min();else->faces.single()}
                }
                if(withDamage)put("damage",damageEvidence(components,natural==20))
            }
        }
        details=null;selection=null;commit(operation,body)
    }
    if(section=="Actions"){
        Text("Attacks",Modifier.clickable(role=Role.Button){details="Manage attacks"}.padding(vertical=8.dp),style=MaterialTheme.typography.titleLarge)
        val groups=listOf("action","bonus_action","reaction","other")
        for(group in groups){
            val list=records.filter{r->
                val d=(r["configuration"]?:r["data"])?.jsonObject
                val t=d?.textValue("timing")?:"other"
                (if(t=="attack")"action" else t)==group
            }
            if(list.isNotEmpty()){
                Text(when(group){"action"->"Actions";"bonus_action"->"Bonus Actions";"reaction"->"Reactions";else->"Other"},style=MaterialTheme.typography.titleMedium)
                list.forEach { row->
                    val cfg=(row["configuration"]?:row["data"])!!.jsonObject
                    val reasons=(row["reasons"] as? JsonArray).orEmpty()
                    val enabled=online && "configuration" in row && reasons.isEmpty()
                    val id=row.cloudText("id")
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f).clickable(role=Role.Button){inspect(id)}.padding(vertical=8.dp)){
                            Text(row.cloudText("title"))
                            Text(listOfNotNull(cfg.textValue("delivery"),cfg.textValue("reach")?.let{"$it ft"},cfg.textValue("range")?.let{"$it${cfg.textValue("longRange")?.let{v->"/$v"}.orEmpty()} ft"}).joinToString(" · "),style=MaterialTheme.typography.labelSmall)
                            if(reasons.isNotEmpty())Text(reasons.joinToString { cloudLabel(it.jsonPrimitive.content.lowercase()) },style=MaterialTheme.typography.labelSmall)
                        }
                        if(row.cloudText("kind")=="attack"){
                            val hit=row.textValue("hit")?.toIntOrNull()
                            val hitText=if(hit!=null){if(hit>=0)"+$hit" else "$hit"} else (row.textValue("resolvedSaveDc") ?: cfg.textValue("saveDc"))?.let{"DC $it"} ?: "Resolve"
                            Box(Modifier.sizeIn(minHeight=48.dp,minWidth=48.dp).combinedClickable(enabled=enabled,role=Role.Button,onClickLabel="Roll attack only",
                                onLongClickLabel="Attack options",onLongClick={selection=id;mode="normal";details="Attack options"},onClick={perform(row,false)}),contentAlignment=Alignment.Center){Text(hitText)}
                            val formula=row.rowsAt("components").joinToString(" + ",transform=::formula)
                            Box(Modifier.widthIn(min=48.dp,max=128.dp).heightIn(min=48.dp).combinedClickable(enabled=enabled,role=Role.Button,onClickLabel="Roll damage",
                                onLongClickLabel="Damage options",onLongClick={selection=id;details="Damage options"},onClick={perform(row,true,true)}),contentAlignment=Alignment.Center){Text(formula.ifBlank{"Damage"},style=MaterialTheme.typography.labelMedium)}
                        }else PlayerTextButton({open("use_action",buildJsonObject{put("actionId",id);put("useId",UUID.randomUUID().toString())})},enabled=enabled){Text("Use")}
                    }
                }
            }
        }
    }else{
        if(section=="Forms"){
            PlayerOutlinedButton({open("define_form",buildJsonObject{put("formId",UUID.randomUUID().toString())})},enabled=online){Text("Create form")}
            PlayerTextButton({open("change_form",buildJsonObject{put("formId",JsonNull);put("elapsedSeconds",0)})},enabled=online){Text("Revert form")}
        }
        val ordered=if(section=="History")records.sortedByDescending { it["revision"]?.jsonPrimitive?.longOrNull } else records
        ordered.forEach{row->
            val data=row.getValue("data").jsonObject;val id=row.cloudText("id")
            PlayerEntry(row.cloudText("title"),cloudLabel(row.cloudText("kind")),"Journal",{if(section=="Forms"){selection=id;details="Preview form"}else inspect(id)})
            if(section=="History"){
                data.textValue("text")?.let{line->PlayerTextButton({clipboard.setText(AnnotatedString(line))}){Text(line)}}
                if(row.cloudText("kind")=="attack_attempt"&&data["damage"]==JsonNull)
                    PlayerTextButton({commit("roll_attack_damage",buildJsonObject{put("actorId",actorId);put("attemptId",id);put("damage",damageEvidence(data.rowsAt("components"),data["critical"]?.jsonPrimitive?.booleanOrNull==true))})},enabled=online){Text("Roll linked damage")}
            }
        }
    }
    if(records.isEmpty()&&!loading)Text("No records in this view.")
    more?.let{cursor->PlayerTextButton({scope.launch{load(cursor)}},enabled=!loading){Text("More")}}
    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    if(details=="Manage attacks")PlayerSheet("Manage attacks",{details=null}){
        PlayerButton({selection=null;details="Build attack"},enabled=online){Text("Create Attack")}
        PlayerTextButton({details="Source attacks"}){Text("Review Source Attacks")}
        PlayerTextButton({open("define_action",buildJsonObject{put("actionId",UUID.randomUUID().toString())})},enabled=online){Text("Create action")}
    }
    if(details=="Source attacks")PlayerSheet("Review Source Attacks",{details=null}){
        records.filter{it.cloudText("kind")=="attack"}.forEach{r->
            val data=(r["configuration"]?:r["data"])!!.jsonObject
            PlayerEntry(r.cloudText("title"),data.textValue("source").orEmpty(),"Emblem",{details=null;inspect(r.cloudText("id"))})
            PlayerTextButton({selection=r.cloudText("id");details="Build attack"},enabled=online){Text("Edit ${r.cloudText("title")}")}
        }
    }
    if(details=="Build attack")key(selection) {
        val id=selection
        var source by remember { mutableStateOf<JsonObject?>(null) }
        LaunchedEffect(id,snapshot.version) { if(id!=null)source=client.record(id) }
        if(id==null || source!=null)CloudAttackBuilder(actorId,source,online,{details=null;selection=null},
            {op,body->details=null;selection=null;commit(op,body)},client)
    }
    val row=records.find{it.cloudText("id")==selection}
    if(row!=null && details in setOf("Attack options","Damage options","Preview form"))PlayerSheet(details!!,{details=null;selection=null}){
        Text(row.cloudText("title"),style=MaterialTheme.typography.titleLarge)
        when(details){
            "Preview form"->{CloudEntityImage(client,snapshot,online,row.cloudText("id"),"form",row.cloudText("title"));RecordFields(row.getValue("data"))
                PlayerButton({open("change_form",buildJsonObject{put("formId",row.cloudText("id"))})},enabled=online){Text("Review transformation")}}
            "Attack options"->{val d=row.getValue("configuration").jsonObject
                if(d.textValue("resolution")=="attack"){PlayerTabs(listOf("Normal","Advantage","Disadvantage"),cloudLabel(mode),{mode=it.lowercase()})
                    Text("d20 ${row.textValue("hit")?.let{if(it.startsWith('-'))it else "+$it"}.orEmpty()}")}
                Text(row.rowsAt("components").joinToString(" + ",transform=::formula))
                PlayerButton({perform(row,false,requested=mode)},enabled=online){Text("Roll Attack Only")}
                PlayerOutlinedButton({perform(row,true,requested=mode)},enabled=online){Text("Roll Attack & Damage")}}
            else->{row.getValue("configuration").jsonObject.rowsAt("components").forEach{component->
                Text(component.cloudText("name"));val variants=(component["variants"] as? JsonArray).orEmpty()
                if(variants.isEmpty())Text(component.cloudText("type"))
                variants.forEach{v->PlayerTextButton({open("select_damage_variant",buildJsonObject{
                    put("attackId",row.cloudText("id"));put("componentId",component.cloudText("id"));put("type",v)
                })},enabled=online){Text(cloudLabel(v.jsonPrimitive.content))}}
            }}
        }
    }
}
