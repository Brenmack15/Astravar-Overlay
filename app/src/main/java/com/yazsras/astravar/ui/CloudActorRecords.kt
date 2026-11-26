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
    var reviewedItem by rememberSaveable(actorId) { mutableStateOf<String?>(null) }
    var selection by rememberSaveable(actorId,section){mutableStateOf<String?>(null)}
    var mode by rememberSaveable{mutableStateOf("normal")}
    var error by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(false)}
    var currentForm by remember(actorId){mutableStateOf<String?>(null)}
    var pendingForm by remember(actorId, snapshot.binding){mutableStateOf<JsonObject?>(null)}
    var pendingDeploymentId by remember(actorId,snapshot.binding){mutableStateOf<String?>(null)}
    var latestAttempts by rememberSaveable(actorId,snapshot.binding){mutableStateOf("{}")}
    suspend fun load(after:String?=null){
        loading=true
        try{
            val rows=if(section=="Actions"&&online){
                val v=snapshot.version
                val suffix="actions/$actorId"+(after?.let{"?after=$it&atRevision=${v.revision}&atEpoch=${v.epoch}&grantRevision=${v.grantRevision}"}?:"")
                val page=client.projection(snapshot,suffix); more=page.textValue("nextAfter");currentForm=page.textValue("formId")
                page.rowsAt("rows")
            }else{
                if(section=="Forms"){
                    val actorData=client.record(actorId)?.get("data") as? JsonObject
                    pendingDeploymentId=actorData?.textValue("activeDeploymentId")
                    val deployment=pendingDeploymentId?.let{client.record(it)}
                    val target=(deployment?.get("data") as? JsonObject)?.textValue("formId")
                    pendingForm=target?.let{client.record(it)}
                }
                val kinds=when(section){
                    "Forms"->setOf("form");"Actions"->setOf("attack","action_definition")
                    else->setOf("form_deployment","roll","roll_batch","rest_report","cast_instance","action_use","attack_attempt","item_event","coin_transaction")
                }
                val page=if(section=="History")client.actorHistory(actorId,kinds,50,
                    if(after==null)null else records.lastOrNull()?.let(::cloudHistoryCursor),snapshot)
                    else client.records(after.orEmpty(),kinds,actorId)
                more=if(page.size==50)page.last().cloudText("id") else null;page
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
    fun perform(row:JsonObject,withDamage:Boolean,standalone:Boolean=false,requested:String="normal",charges:List<String> = emptyList(),targetId:String?=null) {
        if(!online||row.rowsAt("components").isEmpty() && standalone)return
        val id=row.cloudText("id");val d=row.getValue("configuration").jsonObject
        val components=row.rowsAt("components")
        val operation=if(standalone)"roll_damage_only" else "resolve_attack"
        val newAttempt=UUID.randomUUID().toString()
        if (d["payloadRule"] is JsonObject) {
            // The player must select the stored payload or the exact eligible slot before the dice can be determined.
            open(operation, buildJsonObject {
                put("attackId", id)
                if (standalone) put("rollId", newAttempt) else {
                    put("attemptId", newAttempt); put("mode", requested); put("includeDamage", withDamage)
                    if(charges.isNotEmpty())put("chargedEffectIds",JsonArray(charges.map(::JsonPrimitive)))
                    if(targetId!=null)put("targets",buildJsonArray{add(targetId)})
                }
                putJsonObject("payload") {}
            })
            return
        }
        if(!standalone && !withDamage) latestAttempts=JsonObject(Json.parseToJsonElement(latestAttempts).jsonObject+(id to JsonPrimitive(newAttempt))).toString()
        val body=buildJsonObject{
            put("actorId",actorId);put("attackId",id)
            if(standalone){put("rollId",UUID.randomUUID().toString());put("damage",damageEvidence(components,false))}
            else{
                put("attemptId",newAttempt);put("includeDamage",withDamage)
                if(charges.isNotEmpty())put("chargedEffectIds",JsonArray(charges.map(::JsonPrimitive)))
                if(targetId!=null)put("targets",buildJsonArray{add(targetId)})
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
    fun damageClick(row:JsonObject){
        val attack=row.cloudText("id")
        val prior=Json.parseToJsonElement(latestAttempts).jsonObject.textValue(attack)
        if(prior==null){perform(row,true,true);return}
        scope.launch{
            try{
                val attempt=client.record(prior)
                val data=attempt?.get("data") as? JsonObject
                if(attempt==null || data==null){error="The preceding attack has no verified result yet. Check the submitted command.";return@launch}
                if(data.textValue("attackId")==attack && data.textValue("formId")==currentForm && data["damage"]==JsonNull){
                    commit("roll_attack_damage",buildJsonObject{
                        put("actorId",actorId);put("attemptId",prior)
                        put("damage",damageEvidence(data.rowsAt("components"),data["critical"]?.jsonPrimitive?.booleanOrNull==true))
                    })
                }else perform(row,true,true)
            }catch(e:CancellationException){throw e}catch(e:Exception){error=cloudError(e)}
        }
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
                    val damageAvailable=online && "configuration" in row && ((row["damageReasons"] as? JsonArray).orEmpty().isEmpty() ||
                        Json.parseToJsonElement(latestAttempts).jsonObject.containsKey(row.cloudText("id")))
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
                            Box(Modifier.widthIn(min=48.dp,max=128.dp).heightIn(min=48.dp).combinedClickable(enabled=damageAvailable,role=Role.Button,onClickLabel="Roll damage",
                                onLongClickLabel="Damage options",onLongClick={selection=id;details="Damage options"},onClick={damageClick(row)}),contentAlignment=Alignment.Center){Text(formula.ifBlank{"Damage"},style=MaterialTheme.typography.labelMedium)}
                        }else PlayerTextButton({open("use_action",buildJsonObject{put("actionId",id);put("useId",UUID.randomUUID().toString())})},enabled=enabled){Text("Use")}
                    }
                }
            }
        }
    }else{
        if(section=="Forms"){
            pendingForm?.let{form->
                PlayerEntry("Deployment in progress",form.cloudText("title"),"Journal",{
                    val id=form.cloudText("id")
                    selection=id;details="Preview form"
                })
            }
            pendingDeploymentId?.let{id->PlayerTextButton({inspect(id)}){Text("Inspect deployment record")}}
            PlayerOutlinedButton({open("define_form",buildJsonObject{put("formId",UUID.randomUUID().toString())})},enabled=online){Text("Create form")}
            PlayerTextButton({open("change_form",buildJsonObject{put("formId",JsonNull);put("elapsedSeconds",0)})},enabled=online && pendingForm==null){Text("Revert form")}
        }
        records.forEach{row->
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
        PlayerButton({reviewedItem=null;selection=null;details="Build attack"},enabled=online){Text("Create Attack")}
        PlayerTextButton({details="Source attacks"}){Text("Review Source Attacks")}
        PlayerTextButton({open("define_action",buildJsonObject{put("actionId",UUID.randomUUID().toString())})},enabled=online){Text("Create action")}
    }
    if(details=="Source attacks")PlayerSheet("Review Source Attacks",{details=null}){
        if(online)CloudItemAttackSourceReview(actorId,client,snapshot){draft->
            reviewedItem=Json.encodeToString(CloudAttackDraft.serializer(),draft);selection=null;details="Build attack"
        }
        records.filter{it.cloudText("kind")=="attack"}.forEach{r->
            val data=(r["configuration"]?:r["data"])!!.jsonObject
            PlayerEntry(r.cloudText("title"),data.textValue("source").orEmpty(),"Emblem",{details=null;inspect(r.cloudText("id"))})
            PlayerTextButton({selection=r.cloudText("id");details="Build attack"},enabled=online){Text("Edit ${r.cloudText("title")}")}
        }
    }
    if(details=="Build attack")key(selection,reviewedItem) {
        val id=selection
        var source by remember { mutableStateOf<JsonObject?>(null) }
        LaunchedEffect(id,snapshot.version) { if(id!=null)source=client.record(id) }
        if(id==null || source!=null)CloudAttackBuilder(actorId,source,online,{details=null;selection=null},
            {op,body->details=null;selection=null;commit(op,body)},client,
            reviewedItem?.takeIf{id==null}?.let{Json.decodeFromString(CloudAttackDraft.serializer(),it)})
    }
    val row=records.find{it.cloudText("id")==selection} ?: pendingForm?.takeIf{section=="Forms" && it.cloudText("id")==selection}
    if(row!=null && details in setOf("Attack options","Damage options","Preview form"))PlayerSheet(details!!,{details=null;selection=null}){
        Text(row.cloudText("title"),style=MaterialTheme.typography.titleLarge)
        when(details){
            "Preview form"->{
                var evaluated by remember(snapshot.version,row.cloudText("id")){mutableStateOf<JsonObject?>(null)}
                var previewError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(online,actorId,row.cloudText("id"),snapshot.version){
                    if(online)try{evaluated=client.projection(snapshot,"forms/$actorId/${row.cloudText("id")}")}catch(e:CancellationException){throw e}catch(e:Exception){previewError=cloudError(e)}
                }
                CloudFormDeploymentControls(actorId,row.cloudText("id"),evaluated,online,::open,commit)
                evaluated?.let{projection->PlayerDisclosure("Effective and retained values",initiallyOpen=true){
                    projection.filterKeys{it !in setOf("epoch","revision","grantRevision","actorId","deployment","trackedEncounter")}.forEach{(name,v)->
                        Text(cloudLabel(name),style=MaterialTheme.typography.titleSmall);RecordFields(v)
                    }
                }}
                previewError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                CloudEntityImage(client,snapshot,online,row.cloudText("id"),"form",row.cloudText("title"));RecordFields(row.getValue("data"))
                PlayerOutlinedButton({open("revise_form",buildJsonObject{
                    put("formId",row.cloudText("id"));put("name",row.cloudText("title"))
                })},enabled=online){Text("Edit form")}
            }
            "Attack options"->{val d=row.getValue("configuration").jsonObject
                if(d.textValue("resolution")=="attack"){PlayerTabs(listOf("Normal","Advantage","Disadvantage"),cloudLabel(mode),{mode=it.lowercase()})
                    Text("d20 ${row.textValue("hit")?.let{if(it.startsWith('-'))it else "+$it"}.orEmpty()}")}
                Text(row.rowsAt("components").joinToString(" + ",transform=::formula))
                val offered=row.rowsAt("optionalCharges")
                val selectedCharges=remember(selection,snapshot.version){mutableStateListOf<String>()}
                var targetId by remember(selection,snapshot.version){mutableStateOf<String?>(null)}
                var targets by remember(selection,snapshot.version){mutableStateOf<List<JsonObject>>(emptyList())}
                val needsTarget=offered.any{it.textValue("id") in selectedCharges && (it["charge"] as? JsonObject)?.textValue("trigger")=="confirmed_hit"}
                CloudChargeSelection(offered,selectedCharges)
                LaunchedEffect(needsTarget,snapshot.version){
                    if(needsTarget)try{
                        val loaded=mutableListOf<JsonObject>();var after=""
                        do {
                            val page=client.records(after=after,kinds=setOf("actor"));loaded.addAll(page)
                            after=page.lastOrNull()?.textValue("id").orEmpty()
                        }while(page.size==50)
                        targets=loaded
                    }catch(e:CancellationException){throw e}catch(e:Exception){error=cloudError(e)}
                }
                if(needsTarget){
                    var menu by remember {mutableStateOf(false)}
                    Box{
                        PlayerOutlinedButton({menu=true}){Text(targets.firstOrNull{it.textValue("id")==targetId}?.textValue("title")?:"Choose target")}
                        DropdownMenu(menu,{menu=false}){targets.forEach{target->
                            DropdownMenuItem(text={Text(target.cloudText("title"))},onClick={targetId=target.textValue("id");menu=false})
                        }}
                    }
                }
                val ready=online && (!needsTarget||targetId!=null)
                PlayerButton({perform(row,false,requested=mode,charges=selectedCharges.toList(),targetId=targetId)},enabled=ready){Text("Roll Attack Only")}
                PlayerOutlinedButton({perform(row,true,requested=mode,charges=selectedCharges.toList(),targetId=targetId)},enabled=ready){Text("Roll Attack & Damage")}}
            else->{PlayerTextButton({perform(row,true,true)},enabled=online && (row["damageReasons"] as? JsonArray).orEmpty().isEmpty()){Text("Roll standalone damage")}
                row.getValue("configuration").jsonObject.rowsAt("components").forEach{component->
                Text(component.cloudText("name"));val variants=(component["variants"] as? JsonArray).orEmpty()
                if(variants.isEmpty())Text(component.cloudText("type"))
                variants.forEach{v->PlayerTextButton({open("select_damage_variant",buildJsonObject{
                    put("attackId",row.cloudText("id"));put("componentId",component.cloudText("id"));put("type",v)
                })},enabled=online){Text(cloudLabel(v.jsonPrimitive.content))}}
            }}
        }
    }
}
