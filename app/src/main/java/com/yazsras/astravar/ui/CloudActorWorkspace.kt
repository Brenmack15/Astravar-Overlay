package com.yazsras.astravar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID

private val abilityNames = linkedMapOf("STR" to "Strength","DEX" to "Dexterity","CON" to "Constitution","INT" to "Intelligence","WIS" to "Wisdom","CHA" to "Charisma")
private val sections = listOf("Sheet","Actions","Equipment","Spells","Resources","Forms","History")
private val faceSource = SecureRandom()
private fun args(vararg fields: Pair<String,String>) = buildJsonObject { fields.forEach { put(it.first,it.second) } }
private fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject
private fun JsonObject?.value(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
private fun signed(value: String?) = value?.toIntOrNull()?.let { if (it >= 0) "+$it" else it.toString() } ?: "—"

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun CloudActorWorkspace(client:CloudCampaignClient,snapshot:CloudSnapshot,online:Boolean,actorId:String?,
    select:(String?)->Unit,openOperation:(String,JsonObject)->Unit,inspect:(String)->Unit,commit:(String,JsonObject)->Unit) {
    val scope=rememberCoroutineScope()
    var actors by remember(snapshot.binding) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var actorMore by remember { mutableStateOf(false) }
    var actorAfter by remember { mutableStateOf("") }
    var sheet by remember(snapshot.binding,actorId) { mutableStateOf<JsonObject?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var features by remember(actorId,snapshot.version.grantRevision) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var featureCursor by remember { mutableStateOf<String?>(null) }
    suspend fun loadFeatures(after:String="") {
        val id=actorId ?: return
        val page=client.records(after,kinds=setOf("feature"),actorId=id)
        features=if(after.isEmpty())page else features+page
        featureCursor=page.lastOrNull()?.cloudText("id")?.takeIf{page.size==50}
    }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var rollOption by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAbility by rememberSaveable { mutableStateOf<String?>(null) }
    var profileTab by rememberSaveable { mutableStateOf("Defenses") }
    val pager=rememberPagerState { sections.size }
    suspend fun loadActors(after:String = "") {
        // Scan granted nonplayable actors as well so a full page of NPCs cannot hide later people.
        val result=mutableListOf<JsonObject>()
        var cursor=after
        var more=true
        while (result.size<50 && more) {
            val raw=client.records(cursor,kinds=setOf("actor"))
            result+=raw.filter { cloudPartyMember(it) && it.obj("data").value("role") in setOf("player","companion") }
            more=raw.size==50
            if (raw.isEmpty()) break
            cursor=raw.last().cloudText("id")
        }
        val loaded=if (after.isEmpty()) result else actors + result
        actors=loaded.distinctBy { it.cloudText("id") }
        actorMore=more; actorAfter=cursor
        val retained = actorId?.let { id -> client.record(id) }?.takeIf {
            cloudPartyMember(it) && it.obj("data").value("role") in setOf("player", "companion")
        }
        if (after.isEmpty() && retained == null) select(actors.firstOrNull()?.cloudText("id"))
        else if (retained != null && actors.none { it.cloudText("id") == actorId }) actors = actors + retained
    }
    LaunchedEffect(snapshot.version,snapshot.visible) {
        try { loadActors() } catch(e:CancellationException){throw e} catch(e:Exception){ actors=emptyList(); select(null); notice=cloudError(e) }
    }
    LaunchedEffect(actorId,snapshot.version,overlay){if(overlay=="Profile")try{loadFeatures()}catch(e:CancellationException){throw e}catch(e:Exception){notice=cloudError(e);features=emptyList()}}
    val selected=actors.firstOrNull { it.cloudText("id")==actorId }
    val data=selected.obj("data")
    LaunchedEffect(actorId,snapshot.version,online) {
        sheet=null; notice=null
        if (actorId!=null && online) try { sheet=client.projection(snapshot,"sheet/$actorId").obj("sheet") }
        catch(e:CancellationException){throw e} catch(e:Exception){ notice=cloudError(e) }
    }
    fun roll(kind:String,key:String,mode:String="normal",physical:List<Int>?=null) {
        val id=actorId ?: return
        sheet.obj("rollRestrictions").value("$kind:$key")?.let { reason->notice=cloudLabel(reason.lowercase());return }
        val count=sheet.obj("rollModes").obj("$kind:$key").obj(mode).value("count")?.toIntOrNull()
        if (!online || count==null || count !in 1..3) { notice="A verified roll configuration is required."; return }
        if (physical!=null && (physical.size!=count || physical.any { it !in 1..20 })) { notice="Enter $count actual d20 result(s)."; return }
        val faces=physical ?: List(count) { 1 + faceSource.nextInt(20) }
        rollOption=null; notice=null
        commit("record_check",buildJsonObject {
            put("actorId",id); put("rollId",UUID.randomUUID().toString())
            putJsonObject("check") { put("kind",kind); put("key",key); put("mode",mode) }
            putJsonObject("evidence") { put("origin",if (physical==null) "app" else "player_physical"); put("faces",JsonArray(faces.map(::JsonPrimitive))) }
        })
    }
    if (selected==null || actorId==null) {
        Column { PlayerButton({ overlay="Actors" }) { Text("Choose character") }
            PlayerOutlinedButton({ openOperation("define_actor",buildJsonObject {}) },enabled=online) { Text("Create character") } }
    } else {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                PlayerTextButton({overlay="AC"}) { Text("AC ${sheet.obj("ac").value("value") ?: "—"}") }
                QuickControl("Initiative",signed(sheet.obj("initiative").value("value")),online && sheet!=null,
                    { roll("initiative","initiative") }, { rollOption="initiative:initiative" })
                PlayerTextButton({ overlay="Rest" }) { Text("Rest") }
                IconButton({ overlay="Actors" },Modifier.semantics { contentDescription="Character administration" }) {
                    ArcaneIcon("Settings",Modifier.size(22.dp),RebuildPalette.Gold)
                }
            }
            Column(Modifier.weight(1.22f),horizontalAlignment=Alignment.CenterHorizontally) {
                val form=sheet.value("formId") ?: data.value("activeFormId")
                val imageId=form ?: actorId
                CloudEntityImage(client,snapshot,online,imageId,if(form==null)"actor" else "form",selected.cloudText("title"),Modifier.size(76.dp))
                Text(selected.cloudText("title"),Modifier.clickable(role=Role.Button){overlay="Profile"}.padding(6.dp),
                    textAlign=TextAlign.Center,style=MaterialTheme.typography.titleMedium)
                val currentFormTitle by produceState<String?>(null, actorId, form, snapshot.version) {
                    value = if(form==null) null else try { client.record(form)?.cloudText("title") }
                    catch(e:CancellationException){throw e}catch(_:Exception){null}
                }
                PlayerTextButton({scope.launch{pager.animateScrollToPage(sections.indexOf("Forms"))}},
                    modifier=Modifier.semantics{contentDescription="Open forms and configurations"}) {
                    Text(if(form==null) "Original form" else currentFormTitle ?: "Current form",textAlign=TextAlign.Center)
                }
            }
            Column(Modifier.weight(1.1f),horizontalAlignment=Alignment.CenterHorizontally) {
                val hp=(sheet?:data).value("currentHp")?.toIntOrNull()
                val max=(sheet?:data).value("maxHp")?.toIntOrNull()
                val temp=(sheet?:data).value("temporaryHp")?.toIntOrNull()
                Column(Modifier.fillMaxWidth().clickable(role=Role.Button){overlay="HP"}.padding(4.dp)) {
                    Text("HP",style=MaterialTheme.typography.labelSmall)
                    Text("${hp ?: "—"} / ${max ?: "—"}",style=MaterialTheme.typography.titleMedium)
                    if(temp!=null && temp>0) Text("($temp)",color=RebuildPalette.Cyan)
                    if(hp!=null && max!=null && max>0) LinearProgressIndicator(progress={ (hp.toFloat()/max).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                }
                PlayerTextButton({overlay="Conditions"}){Text("Conditions")}
            }
        }
        notice?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        PlayerTabs(sections,sections[pager.currentPage],{ idx->scope.launch { pager.animateScrollToPage(sections.indexOf(idx)) } })
        HorizontalPager(pager,key={sections[it]},modifier=Modifier.fillMaxSize(),verticalAlignment=Alignment.Top) { i ->
            val section=sections[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                when(section){
                    "Sheet" -> {
                        for (group in abilityNames.entries.chunked(3)) Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            group.forEach { (stat,name)->
                                Column(Modifier.weight(1f).background(RebuildPalette.Panel,RoundedCornerShape(8.dp))
                                    .clickable(role=Role.Button){selectedAbility=stat}.padding(3.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                                    Text(name.uppercase(),style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center)
                                    val proficient=(data?.get("saveProficiencies") as? JsonArray)?.contains(JsonPrimitive(stat))==true
                                    QuickControl("$name saving throw", (if (proficient) "● " else "○ ")+signed(sheet.obj("saves").obj(stat).value("value")),
                                        online && sheet!=null,{roll("save",stat)},{rollOption="save:$stat"})
                                    QuickControl("$name ability check",signed(sheet.obj("abilities").obj(stat).value("modifier")),online && sheet!=null,
                                        {roll("ability",stat)},{rollOption="ability:$stat"},large=true)
                                    Text(sheet.obj("abilities").obj(stat).value("effective") ?: "—", Modifier.background(RebuildPalette.Void,CircleShape).padding(horizontal=9.dp,vertical=2.dp))
                                }
                            }
                        }
                        CloudPassiveChecks(actorId,sheet,online,{ id->client.record(id) },openOperation,inspect)
                        Text("Skills",Modifier.clickable(role=Role.Button){openOperation("set_skill_training",args("actorId" to actorId))}.padding(8.dp),style=MaterialTheme.typography.titleMedium)
                        sheet.obj("skills")?.entries?.sortedWith(compareByDescending<Map.Entry<String,JsonElement>> {
                            sheet.obj("skillDefinitions").obj(it.key).value("pinned")=="true"
                        }.thenBy { it.key })?.forEach { (key,entry)->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text(data.obj("skills").obj(key).value("name") ?: cloudLabel(key),Modifier.weight(1f)
                                    .clickable(role=Role.Button){rollOption="skill:$key"}.padding(8.dp))
                                QuickControl("Roll ${cloudLabel(key)}",signed(entry.jsonObject.value("value")),online,
                                    {roll("skill",key)},{rollOption="skill:$key"})
                            }
                        }
                    }
                    "Equipment" -> CloudRecordScreen(section,actorId,client,snapshot,online,openOperation,inspect)
                    "Spells","Resources" -> CloudSpellRest(section,actorId,client,snapshot,online,openOperation)
                    "Actions","Forms","History" -> CloudActorRecords(section,client,snapshot,actorId,online,openOperation,inspect,commit)
                }
                Spacer(Modifier.height(78.dp))
            }
        }
    }
    when(overlay) {
        "Actors" -> PlayerSheet("Character administration",{overlay=null}) {
            actors.forEach { row-> PlayerEntry(row.cloudText("title"),cloudLabel(row.obj("data").value("role").orEmpty()),"Emblem",
                { select(row.cloudText("id"));overlay=null }) }
            if(actorMore) PlayerTextButton({scope.launch { loadActors(actorAfter) }}) {Text("More characters")}
            PlayerButton({overlay=null;openOperation("define_actor",buildJsonObject {})},enabled=online){Text("Create character")}
            if(actorId!=null)PlayerTextButton({overlay=null;openOperation("advance_actor_level",args("actorId" to actorId))},enabled=online){Text("Advance selected character")}
        }
        "Rest" -> if(actorId!=null) PlayerSheet("Rest",{overlay=null}) { CloudSpellRest("Rest",actorId,client,snapshot,online,openOperation) }
        "HP" -> PlayerSheet("Hit points", { overlay = null }) {
            val current = sheet ?: data
            Text("${current.value("currentHp") ?: "Unknown"} / ${current.value("maxHp") ?: "Unknown"} HP")
            Text("Temporary HP · ${current.value("temporaryHp") ?: "Unknown"}")
            if (actorId != null) {
                for ((kind, label) in listOf("damage" to "Damage", "heal" to "Healing", "temporary" to "Temporary HP")) {
                    PlayerOutlinedButton(onClick = {
                        overlay = null; openOperation("apply_hp_change", args("actorId" to actorId, "kind" to kind))
                    }, enabled = online) { Text(label) }
                }
            }
            CloudHpMaximumSources(sheet)
        }
        "AC" -> PlayerSheet("Armor class",{overlay=null}) { RecordFields(sheet.obj("ac") ?: JsonNull) }
        "Conditions" -> PlayerSheet("Conditions",{overlay=null}) { RecordFields((sheet?:data)?.get("conditions") ?: JsonNull)
            val exhaustion=sheet.obj("exhaustion")
            val current=exhaustion.value("level")?.toIntOrNull() ?: data.value("exhaustionLevel")?.toIntOrNull()
            Text("Exhaustion level · ${current ?: "Unknown"}")
            exhaustion.value("status")?.takeIf { it in setOf("adjudication_required","fatal","suppressed") }?.let { Text(cloudLabel(it)) }
            var exhaustionMenu by remember { mutableStateOf(false) }
            Box {
                PlayerOutlinedButton({exhaustionMenu=true},enabled=online && actorId!=null){Text("Set exhaustion level")}
                DropdownMenu(exhaustionMenu,{exhaustionMenu=false}) {
                    (0..6).forEach { level ->
                        DropdownMenuItem(text={Text("Level $level")},onClick={
                            exhaustionMenu=false;overlay=null
                            openOperation("set_condition",buildJsonObject{
                                put("actorId",actorId);put("key","exhaustion");put("enabled",level>0);put("level",level)
                            })
                        })
                    }
                }
            }
            if(actorId!=null)PlayerOutlinedButton({overlay=null;openOperation("set_condition",args("actorId" to actorId))},enabled=online){Text("Manage condition")} }
        "Profile" -> PlayerSheet("Character Profile",{overlay=null}) {
            PlayerTabs(listOf("Defenses","Features & Traits","Proficiencies & Training","Background"),profileTab,{profileTab=it})
            when(profileTab){
                "Defenses" -> RecordFields(sheet?.get("defenses") ?: JsonNull)
                "Features & Traits" -> {
                    features.forEach { row->PlayerEntry(row.cloudText("title"),row.obj("data").value("source").orEmpty(),"Emblem",{overlay=null;inspect(row.cloudText("id"))}) }
                    featureCursor?.let { c->PlayerTextButton({scope.launch{loadFeatures(c)}}){Text("More features")} }
                    if(actorId!=null)PlayerTextButton({overlay=null;openOperation("define_feature",args("actorId" to actorId))},enabled=online){Text("Add feature")}
                }
                "Proficiencies & Training" -> { RecordFields(data?.get("training") ?: JsonNull);RecordFields(data?.get("skills") ?: JsonNull)
                    if(actorId!=null)PlayerTextButton({overlay=null;openOperation("set_actor_training",args("actorId" to actorId))},enabled=online){Text("Manage proficiencies")}
                    if(actorId!=null)PlayerTextButton({overlay=null;openOperation("set_skill_training",args("actorId" to actorId))},enabled=online){Text("Manage training")} }
                else -> { Text(selected?.cloudText("title").orEmpty()); RecordFields(data?.get("biography") ?: JsonNull); data.value("source")?.let { Text(it) }
                    if(actorId!=null)PlayerOutlinedButton({overlay=null;openOperation("set_actor_profile",args("actorId" to actorId))},enabled=online){Text("Edit background")} }
            }
        }
    }
    selectedAbility?.let { stat-> PlayerSheet("${abilityNames[stat]} · Inspector & Training",{selectedAbility=null}) {
        listOf("Ability" to sheet.obj("abilities").obj(stat),"Check" to sheet.obj("checks").obj(stat),"Saving throw" to sheet.obj("saves").obj(stat)).forEach { (name,entry)->
            Text(name,style=MaterialTheme.typography.titleMedium); RecordFields(entry ?: JsonNull)
        }
        if(actorId!=null){
            PlayerTextButton({ selectedAbility=null;openOperation("set_actor_ability",args("actorId" to actorId,"ability" to stat)) },enabled=online){Text("Edit base score")}
            CloudAbilityTraining(actorId,stat,data,sheet,online){name,input->selectedAbility=null;openOperation(name,input)}
        }
    } }
    rollOption?.let { ref->key(ref) {
        val kind=ref.substringBefore(':'); val key=ref.substringAfter(':')
        var mode by remember { mutableStateOf("normal") }; var faces by rememberSaveable { mutableStateOf("") }
        PlayerSheet("Roll options · ${cloudLabel(key)}",{rollOption=null}) {
            PlayerTabs(listOf("Normal","Advantage","Disadvantage"),cloudLabel(mode),{mode=it.lowercase()})
            val expected=sheet.obj("rollModes").obj(ref).obj(mode)
            Text(expected.value("count")?.let { "$it d20 · ${cloudLabel(expected.value("mode").orEmpty())}" } ?: "Roll configuration unavailable")
            PlayerButton({roll(kind,key,mode)},enabled=online && expected!=null){Text("Roll")}
            OutlinedTextField(faces,{faces=it},label={Text("Physical d20 faces, in order")})
            val values=faces.split(Regex("[,\\s]+")).filter(String::isNotBlank).mapNotNull { it.toIntOrNull() }
            PlayerOutlinedButton({roll(kind,key,mode,values)},enabled=online && expected!=null && Regex("[0-9]+(?:[,\\s]+[0-9]+)*").matches(faces.trim())){Text("Record physical dice")}
            if(kind=="skill"&&actorId!=null){
                val definition=sheet.obj("skillDefinitions").obj(key)
                val pinned=definition.value("pinned")=="true"
                PlayerTextButton({rollOption=null;openOperation("set_skill_training",args("actorId" to actorId,"skillId" to key))},enabled=online){Text("Edit skill training")}
                PlayerTextButton({rollOption=null;commit("set_skill_pin",buildJsonObject{
                    put("actorId",actorId);put("skillId",key);put("pinned",!pinned)})},enabled=online){Text(if(pinned)"Unpin skill" else "Pin skill")}
                if(definition.value("standard")=="false")PlayerTextButton({
                    rollOption=null;openOperation("remove_custom_skill",args("actorId" to actorId,"skillId" to key))
                },enabled=online){Text("Remove custom skill")}
            }
            notice?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun QuickControl(label:String,text:String,enabled:Boolean,tap:()->Unit,options:()->Unit,large:Boolean=false) {
    Box(Modifier.heightIn(min=48.dp).widthIn(min=48.dp).combinedClickable(enabled=enabled,role=Role.Button,
        onClickLabel="Roll $label",onLongClickLabel="Roll options",onClick=tap,onLongClick=options)
        .semantics { contentDescription="$label $text" },contentAlignment=Alignment.Center) {
        Text(text,style=if(large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelMedium,color=RebuildPalette.GoldBright)
    }
}
