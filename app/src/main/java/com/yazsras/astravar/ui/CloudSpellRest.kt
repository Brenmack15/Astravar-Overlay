package com.yazsras.astravar.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID
import java.security.SecureRandom

private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.rows(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.strings(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.details() = get("data") as? JsonObject ?: JsonObject(emptyMap())
private fun levelName(level: Int) = if (level == 0) "Cantrips" else "Level $level"
private fun formula(c: JsonObject): String {
    val dice = c.num("dice") ?: 0
    val bonus = c.num("flat") ?: 0
    val base = if (dice == 0) bonus.toString() else "${dice}d${c.num("sides")}" + when {
        bonus > 0 -> " + $bonus"
        bonus < 0 -> " − ${-bonus}"
        else -> ""
    }
    return "$base ${c.str("type").orEmpty()}"
}

@Composable private fun PoolCounter(value: Int?, max: Int?) {
    val balance = "${value ?: "?"}/${max ?: "?"}"
    Row(Modifier.semantics { contentDescription = "$balance remaining" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (max != null && value != null && max in 1..10) repeat(max) { i ->
            val shape = RoundedCornerShape(2.dp)
            Box(Modifier.size(12.dp).border(1.dp, RebuildPalette.Gold, shape).then(
                if (i < value) Modifier.background(RebuildPalette.Gold, shape) else Modifier
            ))
        }
        Text(balance, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun CloudSpellRest(
    tab: String, actorId: String, client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean,
    openOperation: (String, JsonObject) -> Unit
) {
    val scope = rememberCoroutineScope()
    var records by remember(actorId, snapshot.binding) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var book by remember(actorId, snapshot.binding) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var actor by remember(actorId, snapshot.binding) { mutableStateOf<JsonObject?>(null) }
    var sheet by remember(actorId, snapshot.binding) { mutableStateOf<JsonObject?>(null) }
    var error by remember(actorId) { mutableStateOf<String?>(null) }
    var busy by remember(actorId) { mutableStateOf(false) }
    var result by remember(actorId) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(tab, actorId, snapshot.version, snapshot.visible, online) {
        error = null; records = emptyList(); book = emptyList(); sheet = null; actor = null
        if (!snapshot.visible) return@LaunchedEffect
        try {
            actor = client.record(actorId)
            val kinds = if (tab == "Spells") setOf("spell","spell_grant","resource_pool","cast_instance") else setOf("resource_pool","rest_report")
            val loaded = mutableListOf<JsonObject>()
            var tail = ""
            do {
                val page = client.records(after = tail, kinds = kinds, actorId = actorId)
                loaded.addAll(page)
                tail = page.lastOrNull()?.str("id").orEmpty()
            } while (page.size == 50)
            records = loaded
            if (online && tab == "Spells") {
                val all = mutableListOf<JsonObject>()
                var after: String? = null
                do {
                    val suffix = after?.let { "?after=$it&atRevision=${snapshot.version.revision}&atEpoch=${snapshot.version.epoch}&grantRevision=${snapshot.version.grantRevision}" }.orEmpty()
                    val page = client.projection(snapshot,"spellbook/$actorId$suffix")
                    all.addAll(page.rows("rows"))
                    after = page.str("nextAfter")
                } while (after != null)
                book = all
            } else if (online && tab == "Rest") {
                sheet = client.projection(snapshot,"sheet/$actorId")["sheet"] as? JsonObject
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Campaign data is unavailable." }
    }
    fun submit(op: String, args: JsonObject) {
        if (!online || busy) return
        busy = true; error = null
        scope.launch {
            try { result = client.submit(op, args, snapshot) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Awaiting authoritative verification." }
            finally { busy = false }
        }
    }
    val pools = records.filter { it.str("kind") == "resource_pool" && it.str("life") == "active" }
    when (tab) {
        "Spells" -> SpellRows(actorId, book, pools, records, online && !busy, openOperation, ::submit)
        "Resources" -> {
            pools.forEach { row ->
                val d = row.details()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(row.str("title").orEmpty())
                        Text(d.str("recovery").orEmpty().replace('_',' '), style = MaterialTheme.typography.labelSmall)
                    }
                    PoolCounter(d.num("current"),d.num("maximum"))
                }
                HorizontalDivider()
            }
            PlayerOutlinedButton({openOperation("define_resource",buildJsonObject { put("actorId",actorId) })},enabled=online) { Text("Add resource") }
        }
        "Rest" -> RestPlan(actorId, actor?.details(), sheet, pools, online && !busy, ::submit)
    }
    records.filter { it.str("kind") == if (tab == "Spells") "cast_instance" else "rest_report" }
        .sortedByDescending { it.details().get("recordedAt")?.jsonPrimitive?.longOrNull ?: 0L }
        .take(5).forEach { r -> PlayerTextButton({result = r.details()}) { Text(r.str("title") ?: "View result") } }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    result?.let { value ->
        val clipboard = LocalClipboardManager.current
        val receipt = value["receipt"] as? JsonObject
        val actual = receipt?.get("outcome") as? JsonObject ?: value
        val text = buildString {
            appendLine(if (tab=="Spells") "Casting result" else "Rest result")
            if(receipt!=null) appendLine("Revision ${receipt.num("revision")} · ${receipt.str("transactionId")}")
            append(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),actual))
        }
        AlertDialog(onDismissRequest={result=null},title={Text(value.str("status") ?: if(tab=="Spells") "Casting result" else "Rest result")},
            text={Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) }},
            confirmButton={TextButton({clipboard.setText(AnnotatedString(text))}){Text(if(tab=="Spells") "Copy casting result" else "Copy rest result")}},
            dismissButton={TextButton({result=null}){Text("Close")}})
    }
}

@Composable private fun SpellRows(
    actorId:String, rows:List<JsonObject>, pools:List<JsonObject>, cached:List<JsonObject>, online:Boolean,
    openOperation:(String,JsonObject)->Unit, submit:(String,JsonObject)->Unit
) {
    var query by rememberSaveable(actorId){mutableStateOf("")}
    var filter by rememberSaveable(actorId){mutableIntStateOf(-1)}
    var inspect by remember(actorId){mutableStateOf<JsonObject?>(null)}
    var choice by remember(actorId){mutableStateOf<JsonObject?>(null)}
    var manage by rememberSaveable(actorId){mutableStateOf(false)}
    val levels=rows.mapNotNull { it.num("level") }.distinct().sorted()
    OutlinedTextField(query,{query=it},label={Text("Search spells")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        var levelMenu by remember{mutableStateOf(false)}
        Box {
            PlayerOutlinedButton({levelMenu=true}){Text(if(filter<0)"All levels" else levelName(filter))}
            DropdownMenu(levelMenu,{levelMenu=false}){ (listOf(-1)+levels).forEach { l ->
                DropdownMenuItem(text={Text(if(l<0)"All levels" else levelName(l))},onClick={filter=l;levelMenu=false})
            } }
        }
        PlayerOutlinedButton({manage=true},enabled=online){Text("Manage spells")}
    }
    fun perform(row:JsonObject,slot:String?,replace:Boolean,ritual:Boolean) {
        submit("cast_spell",buildJsonObject {
            put("actorId",actorId);put("grantId",row.str("grantId"));put("castId",UUID.randomUUID().toString());put("level",row.num("level"))
            if(slot!=null)put("slotPoolId",slot)
            if(replace)put("replaceConcentration",true)
            if(ritual)put("confirmedRitualTime",true)
        })
        choice=null;inspect=null
    }
    fun cast(row:JsonObject) {
        val slots=row.strings("eligibleSlotIds")
        if((row.str("route")=="slot"&&slots.size>1)||row.str("route")=="ritual"||row["requiresConcentrationDecision"]?.jsonPrimitive?.booleanOrNull==true)choice=row
        else perform(row,if(row.str("route")=="slot")slots.singleOrNull() else null,false,false)
    }
    if(!online && rows.isEmpty()) cached.filter { it.str("kind")=="spell"&&it.str("title").orEmpty().contains(query,true) }.forEach {
        Text(it.str("title").orEmpty(), Modifier.padding(vertical=6.dp))
    }
    for(level in levels.filter{filter<0||it==filter}) {
        val visible=rows.filter{it.num("level")==level && it.str("name").orEmpty().contains(query,true)}.sortedBy{it.str("name")}
        if(visible.isEmpty())continue
        Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(levelName(level),style=MaterialTheme.typography.titleMedium)
            pools.filter{it.details().num("slotLevel")==level}.forEach{p->PoolCounter(p.details().num("current"),p.details().num("maximum"))}
        }
        visible.forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).clickable { inspect=row }.padding(vertical=6.dp)) {
                    Text(row.str("name").orEmpty())
                    Text(listOfNotNull(row.str("timing"),row.str("range"),row.num("hit")?.let{"${if(it>=0)"+" else ""}$it to hit"},row.num("dc")?.let{"${row.str("saveAbility")} DC $it"}).joinToString(" · "),style=MaterialTheme.typography.labelSmall)
                    Text(row.rows("components").joinToString(" + ",transform=::formula).ifEmpty{row.str("source").orEmpty()},style=MaterialTheme.typography.labelMedium)
                }
                val verb=when(row.str("route")){"ritual"->"As ritual";"at_will"->"At will";"item","limited"->"Use";else->"Cast"}
                PlayerTextButton({cast(row)},enabled=online && row.strings("unavailable").isEmpty()){Text(verb)}
            }
            HorizontalDivider()
        }
    }
    inspect?.let { original ->
        val options=rows.filter{it.str("grantId")==original.str("grantId")}.sortedBy{it.num("level")}
        AlertDialog(onDismissRequest={inspect=null},title={Text(original.str("name").orEmpty())},text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(original.str("description").orEmpty())
                Text("${original.str("source")} · ${original.str("preparation")?.replace('_',' ')}")
                var select by remember{mutableStateOf(false)}
                Box{TextButton({select=true}){Text(levelName(original.num("level")?:0))};DropdownMenu(select,{select=false}){options.forEach{v->DropdownMenuItem({Text(levelName(v.num("level")?:0))},{inspect=v;select=false})}}}
                original.rows("components").forEach{Text("${it.str("name")} · ${formula(it)}")}
                original.strings("unavailable").forEach{Text(it.lowercase().replace('_',' '))}
            }
        },confirmButton={TextButton({cast(original)},enabled=online&&original.strings("unavailable").isEmpty()){Text("Cast")}},dismissButton={TextButton({inspect=null}){Text("Close")}})
    }
    choice?.let{row->
        val valid=row.strings("eligibleSlotIds")
        var slot by remember(row){mutableStateOf(if(valid.size==1)valid.single() else null)}
        val ritual=row.str("route")=="ritual"
        val concentration=row["requiresConcentrationDecision"]?.jsonPrimitive?.booleanOrNull==true
        AlertDialog(onDismissRequest={choice=null},title={Text(row.str("name").orEmpty())},text={
            Column {
                if(row.str("route")=="slot"&&valid.size>1)valid.forEach{id->
                    Row(Modifier.clickable{slot=id}){RadioButton(slot==id,{slot=id});Text(pools.find{it.str("id")==id}?.str("title")?:id)}
                }
                if(concentration)Text("Casting ends the current concentration effect.")
                if(ritual)Text("Confirm that the ritual casting time has elapsed.")
            }
        },confirmButton={TextButton({perform(row,if(row.str("route")=="slot")slot else null,concentration,ritual)},enabled=online&&(row.str("route")!="slot"||slot!=null)){Text("Confirm cast")}},dismissButton={TextButton({choice=null}){Text("Cancel")}})
    }
    if(manage)AlertDialog(onDismissRequest={manage=false},title={Text("Manage spells")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PlayerOutlinedButton({manage=false;openOperation("define_spell",buildJsonObject{put("actorId",actorId)})}){Text("Define spell")}
            PlayerOutlinedButton({manage=false;openOperation("grant_spell",buildJsonObject{put("actorId",actorId)})}){Text("Add casting source")}
            rows.distinctBy{it.str("grantId")}.forEach{g->
                val prepared=g.str("preparation")
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(g.str("name").orEmpty(),Modifier.weight(1f))
                    if(prepared in setOf("known","prepared"))TextButton({manage=false;submit("prepare_spell",buildJsonObject{put("actorId",actorId);put("grantId",g.str("grantId"));put("prepared",prepared=="known")})}){Text(if(prepared=="known")"Prepare" else "Unprepare")}
                    else Text(prepared.orEmpty().replace('_',' '),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    },confirmButton={TextButton({manage=false}){Text("Done")}})
}

@Composable private fun RestPlan(
    actorId:String, actor:JsonObject?, sheet:JsonObject?, pools:List<JsonObject>, online:Boolean,
    submit:(String,JsonObject)->Unit
) {
    var kind by rememberSaveable(actorId){mutableStateOf("short_rest")}
    var minutes by rememberSaveable(actorId){mutableStateOf("60")}
    var review by rememberSaveable(actorId,kind){mutableStateOf(false)}
    var origin by rememberSaveable(actorId){mutableStateOf("player_physical")}
    val entropy=remember { SecureRandom() }
    val origins=mapOf("player_physical" to "Physical dice", "player_digital" to "Player digital dice", "app" to "Roll in Astravar", "delegated" to "Delegated DM dice")
    val selected=remember(actorId,kind){mutableStateMapOf<String,Int>()}
    val faces=remember(actorId,kind){mutableStateMapOf<String,String>()}
    val restored=remember(actorId,kind){mutableStateMapOf<String,Int>()}
    val dice=pools.filter{it.details().str("type")=="hit_die"}
    val legacy=kind=="long_rest" && actor?.str("rulesProfile")=="legacy_2014"
    val eligible=dice.filter{it.details().str("recovery")=="edition_hit_dice"}
    val allocation=legacy && eligible.size>1 && eligible.all{it.details().num("maximum")!=null && it.details().num("current")!=null}
    val target=if(allocation)minOf(maxOf(1,eligible.sumOf{it.details().num("maximum")?:0}/2),
        eligible.sumOf{(it.details().num("maximum")?:0)-(it.details().num("current")?:0)}) else 0
    val rawMod=sheet?.get("abilities") as? JsonObject
    val con=(rawMod?.get("CON") as? JsonObject)?.num("modifier")
    val duration=minutes.toIntOrNull()
    var legal=duration!=null && duration in (if(kind=="long_rest")480 else 60)..525600 &&
        actor?.str("encounterId")==null && actor!=null && (!allocation||restored.values.sum()==target)
    val selectedData=buildJsonArray {
        if(kind=="short_rest")dice.forEach{p->
            val id=p.str("id").orEmpty();val wanted=selected[id]?:0
            if(wanted>0){
                val d=p.details();val rolls=faces[id].orEmpty().trim().split(Regex("[,\\s]+")).map{it.toIntOrNull()}
                if(wanted>(d.num("current")?:0)||(d.num("dieSides")?:0)<2||origin!="app"&&(rolls.size!=wanted||rolls.any{it==null||it !in 1..(d.num("dieSides")?:0)}))legal=false
                add(buildJsonObject{put("poolId",id);put("origin",origin);put("faces",buildJsonArray{rolls.filterNotNull().forEach{add(it)}})})
            }
        }
    }
    Text("Hit points ${actor?.num("currentHp")?:"?"}/${actor?.num("maxHp")?:"?"}",style=MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        listOf("short_rest" to "Short rest","long_rest" to "Long rest").forEach{(key,label)->
            FilterChip(selected=kind==key,onClick={kind=key;minutes=if(key=="long_rest")"480" else "60"},label={Text(label)})
        }
    }
    OutlinedTextField(minutes,{minutes=it},label={Text("Actual elapsed minutes")},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(kind=="short_rest") {
        Text("Hit Dice",style=MaterialTheme.typography.titleSmall)
        var sources by remember { mutableStateOf(false) }
        Box {
            PlayerOutlinedButton({sources=true}){Text(origins.getValue(origin))}
            DropdownMenu(sources,{sources=false}) {
                origins.forEach{(key,label)->DropdownMenuItem(text={Text(label)},onClick={origin=key;sources=false})}
            }
        }
        if(con!=null)Text("Healing modifier per die ${if(con>=0)"+" else ""}$con")
        dice.forEach{p->
            val d=p.details();val id=p.str("id").orEmpty();val n=selected[id]?:0
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Column(Modifier.weight(1f)){Text(p.str("title").orEmpty());PoolCounter(d.num("current"),d.num("maximum"))}
                TextButton({selected[id]=n-1},enabled=n>0){Text("−")};Text("$n")
                TextButton({selected[id]=n+1},enabled=n<(d.num("current")?:0)){Text("+")}
            }
            if(n>0 && origin!="app")OutlinedTextField(faces[id].orEmpty(),{faces[id]=it},label={Text("Actual d${d.num("dieSides")} faces ($n)")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }
    } else if(allocation && target>0){
        Text("Restore $target Hit Dice across these pools.")
        eligible.forEach{p->
            val id=p.str("id").orEmpty();val d=p.details();val n=restored[id]?:0
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(p.str("title").orEmpty(),Modifier.weight(1f))
                TextButton({restored[id]=n-1},enabled=n>0){Text("−")};Text("$n")
                TextButton({restored[id]=n+1},enabled=restored.values.sum()<target && n<(d.num("maximum")?:0)-(d.num("current")?:0)){Text("+")}
            }
        }
    }
    val recovery=pools.filter{p->val rule=p.details().str("recovery")
        rule==kind||rule=="either_rest"||(kind=="long_rest"&&rule in setOf("short_rest","edition_hit_dice"))}
    if(recovery.isNotEmpty()){
        Text("Recovery preview",style=MaterialTheme.typography.titleSmall)
        recovery.forEach{p->
            val d=p.details();val old=d.num("current");val max=d.num("maximum")
            val n=if(d.str("recovery")=="edition_hit_dice"&&legacy){
                if(allocation)restored[p.str("id")]?:0 else max?.let{maxOf(1,it/2)}
            }else d.num("recoveryAmount")?:max
            val after=if(max!=null&&old!=null&&n!=null)minOf(max,old+n) else null
            Text("${p.str("title")} · ${old?:"?"} → ${after?:"?"} / ${max?:"?"}")
        }
    }
    PlayerButton({review=true},enabled=online&&legal){Text("Complete ${kind.replace('_',' ')}")}
    if(review)AlertDialog(onDismissRequest={review=false},title={Text("Complete ${kind.replace('_',' ')}?")},
        text={Text("$duration minutes elapsed. ${selected.values.sum()} Hit Dice selected.")},
        confirmButton={TextButton({
            review=false
            submit("complete_rest",buildJsonObject{put("actorId",actorId);put("kind",kind);put("elapsedSeconds",(duration?:0)*60);put("confirmed",true)
                val rollInApp = origin=="app" && kind=="short_rest"
                put("hitDice",if(!rollInApp) selectedData else buildJsonArray {
                    dice.forEach{p->
                        val id=p.str("id").orEmpty();val count=selected[id]?:0
                        if(count>0)add(buildJsonObject{put("poolId",id);put("origin","app");put("faces",buildJsonArray{
                            repeat(count){add(1+entropy.nextInt(requireNotNull(p.details().num("dieSides"))))}
                        })})
                    }
                })
                if(allocation)put("restoreHitDice",buildJsonArray{restored.entries.filter{it.value>0}.forEach{e->add(buildJsonObject{put("poolId",e.key);put("amount",e.value)})}})
            })
        },enabled=online&&legal){Text("Complete rest")}},dismissButton={TextButton({review=false}){Text("Cancel")}})
}
