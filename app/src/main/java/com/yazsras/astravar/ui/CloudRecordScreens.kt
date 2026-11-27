package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

internal typealias RecordOperation = (String, JsonObject) -> Unit
private val coins = listOf("cp", "sp", "ep", "gp", "pp")
private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.rows(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.data() = obj("data")
private fun args(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*values))
private fun word(value: String) = JsonPrimitive(value)
private fun id() = word(UUID.randomUUID().toString())
private fun amount(value: String?): String = value?.toBigIntegerOrNull()?.let {
    NumberFormat.getIntegerInstance(Locale.US).format(it)
} ?: "Unknown"
private val manual = word("Owner-entered campaign record")

internal fun cloudCoinsCaption(balance: JsonObject): String {
    val rates = listOf(1L,10L,50L,100L,1000L)
    var copper = BigInteger.ZERO
    var complete = true
    for ((index,key) in coins.withIndex()) {
        val count = balance.s(key)?.toBigIntegerOrNull()
        if (count == null) complete = false else copper += count * BigInteger.valueOf(rates[index])
    }
    val (gp, cp) = copper.divideAndRemainder(BigInteger.valueOf(100))
    val formatted = NumberFormat.getIntegerInstance(Locale.US).format(gp) + "." + cp.toString().padStart(2,'0')
    return if (complete) "$formatted GP" else "$formatted GP known · incomplete"
}

@Composable internal fun CloudRecordScreen(tab: String, actorId: String?, client: CloudCampaignClient,
    snapshot: CloudSnapshot, online: Boolean, openOperation: RecordOperation, inspect: (String) -> Unit) {
    if (!snapshot.visible) return
    key(snapshot.binding, snapshot.version.epoch, snapshot.version.grantRevision) {
        var panel by rememberSaveable(tab) { mutableStateOf(when (tab) {
            "Chronicle" -> "Timeline"; "Connections" -> "Companions"; "Inventions" -> "Projects"; else -> "Items"
        }) }
        var query by rememberSaveable(tab,panel) { mutableStateOf("") }
        var selected by rememberSaveable(tab,panel,actorId) { mutableStateOf<String?>(null) }
        var detail by rememberSaveable(tab,panel,actorId) { mutableStateOf<String?>(null) }
        val tabs = when (tab) {
            "Inventory" -> listOf("Items","Wallets","Projects","Research")
            "Connections" -> listOf("Companions","Beings","Factions","Places","Relationships")
            "Chronicle" -> listOf("Timeline","Objectives","Encounters","Checkpoints","Lore","Sessions","Story arcs")
            "Inventions" -> listOf("Projects","Research")
            else -> emptyList()
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tabs.size > 1) PlayerTabs(tabs, panel, { panel = it })
            if (tab == "Equipment") {
                if (actorId == null) Text("Select a character to see equipment.")
                else ActorEquipment(client, snapshot, actorId, online, openOperation) { category, record -> detail=category; selected=record }
            } else {
                PlayerSearch(query,{ query=it.take(120) },"Search ${panel.lowercase()}")
                when (panel) {
                    "Sessions","Story arcs" -> CloudChronologyPanel(if(panel=="Sessions")"campaign_session" else "story_arc",client,snapshot,online,query,openOperation,inspect)
                    "Encounters" -> CloudEncounterPanel(client,snapshot,online,query,openOperation)
                    "Items" -> {
                        PlayerTextButton({ openOperation("define_item_definition",args("definitionId" to id(),"source" to manual)) }, enabled=online) { Text("Create item definition") }
                        if (online) RemoteRecordPage(client,snapshot,"inventory",query=query) { page ->
                            page.rows("groups").forEach { item ->
                                val quantity=amount(item.s("knownQuantity"))+if(item.s("unknownQuantityCount")!="0") " + unknown" else ""
                                PlayerEntry(item.s("title").orEmpty(),"Party quantity · $quantity","Bag",{
                                    val instance=item.s("groupId")?.takeIf{it.startsWith("i:")}?.removePrefix("i:")
                                    detail=if(instance==null)"distribution" else "item"
                                    selected=instance?:item.s("definitionId")
                                },item.s("instances")?.let { "$it holdings" })
                            }
                            if(page.rows("groups").isEmpty()) Text("No matching party items.")
                        } else CachedRecordList(client,snapshot,setOf("item_instance"),null,query,
                            allowed={ it.data().s("partyAccess")=="true" }) { detail="item";selected=it.s("id") }
                    }
                    "Wallets" -> {
                        PlayerTextButton({ openOperation("define_wallet",args("walletId" to id(),"source" to manual)) }, enabled=online) { Text("Add wallet") }
                        PlayerOutlinedButton({detail="exchange";selected="party-trade"},enabled=online){Text("Trade between party members")}
                        if (online) RemoteRecordPage(client,snapshot,"wallets",query=query) { page ->
                            val totals=page.obj("totals")
                            PlayerCard {
                                Text("Total Party Currency",style=MaterialTheme.typography.titleMedium)
                                Text("${totals.s("knownGp") ?: "Unknown"} GP",style=MaterialTheme.typography.headlineSmall,color=RebuildPalette.Gold)
                                if(totals.s("complete")!="true") Text("Known subtotal · unknown denominations or missing wallets",color=RebuildPalette.Muted)
                            }
                            page.rows("wallets").forEach { wallet -> WalletRow(wallet) { detail="wallet";selected=wallet.s("id") } }
                        } else CachedRecordList(client,snapshot,setOf("wallet"),null,query) { detail="wallet";selected=it.s("id") }
                    }
                    "Timeline" -> {
                        PlayerTextButton({ openOperation("record_chronicle_event",args("eventId" to id(),"source" to manual,"worldTime" to JsonNull,"correctsId" to JsonNull)) },enabled=online) {Text("Record event")}
                        if(online) RemoteRecordPage(client,snapshot,"timeline",query=query) { page ->
                            page.rows("events").forEach { event -> PlayerEntry(event.s("title").orEmpty(),
                                event.data().s("worldTime") ?: cloudLabel(event.s("kind").orEmpty()),"Journal",{
                                    if(event.s("kind")=="timeline_event"){detail="known";selected=event.s("id")}else inspect(event.cloudText("id"))
                                }) }
                            if(page.rows("events").isEmpty()) Text("No matching events.")
                        } else CachedRecordList(client,snapshot,setOf("timeline_event","item_event","coin_transaction","component_event","rest_report"),null,query){ inspect(it.cloudText("id")) }
                    }
                    else -> {
                        val kind=when(panel) {
                            "Projects"->"invention_project"; "Research"->"research_note"; "Companions"->"actor"
                            "Beings","Factions","Places"->"world_entity"; "Relationships"->"relationship"
                            "Encounters"->"encounter"; "Checkpoints"->"checkpoint"; "Objectives"->"objective";else->"known_fact"
                        }
                        val create=when(panel) {
                            "Projects"->Triple("Propose invention","propose_invention","projectId")
                            "Research"->Triple("Record finding","record_research","researchId")
                            "Beings","Places","Factions"->Triple("Add connection","define_connection","entityId")
                            "Relationships"->Triple("Record relationship","record_relationship","relationshipId")
                            "Objectives"->Triple("Add objective","define_objective","objectiveId")
                            "Checkpoints"->Triple("Save checkpoint","save_checkpoint","checkpointId")
                            "Lore"->Triple("Record known lore","record_known_fact","factId")
                            else->null
                        }
                        create?.let { (label,op,identity)->
                            PlayerTextButton({
                                val defaults=mutableMapOf<String,JsonElement>(identity to id(),"source" to manual)
                                if(kind=="world_entity") {
                                    defaults["category"]=word(when(panel){"Places"->"place";"Factions"->"faction";else->"being"})
                                    defaults["actorId"]=JsonNull
                                }
                                openOperation(op,JsonObject(defaults))
                            },enabled=online){Text(label)}
                        }
                        CachedRecordList(client,snapshot,setOf(kind),null,query,allowed={ row ->
                            when(panel){
                                "Companions"->row.data().s("role")=="companion" && cloudPartyMember(row)
                                "Beings"->row.data().s("category") in setOf("being","person")
                                "Factions"->row.data().s("category") in setOf("organization","faction")
                                "Places"->row.data().s("category")=="place"
                                else->true
                            }
                        }) { row ->
                            when(kind){
                                "invention_project"->{detail="project";selected=row.s("id")}
                                "world_entity","relationship","known_fact","objective","research_note"->{detail="known";selected=row.s("id")}
                                else->inspect(row.cloudText("id"))
                            }
                        }
                    }
                }
            }
        }
        val selectedId=selected
        if(selectedId!=null){
            val close={ selected=null;detail=null }
            when(detail){
                "definition","distribution" -> DefinitionSheet(client,snapshot,selectedId,detail=="distribution",online,close,openOperation) { record -> detail="item";selected=record }
                "item" -> HoldingSheet(client,snapshot,selectedId,online,close,openOperation,inspect) { record -> detail="definition";selected=record }
                "wallet" -> WalletSheet(client,snapshot,selectedId,online,close,openOperation)
                "project" -> ProjectSheet(client,snapshot,selectedId,online,close,openOperation,inspect)
                "known" -> KnownRecordSheet(client,snapshot,selectedId,online,close,openOperation,inspect)
                "exchange" -> PartyTradeSheet(client,snapshot,online,close,openOperation)
            }
        }
    }
}

@Composable private fun RemoteRecordPage(client: CloudCampaignClient,snapshot: CloudSnapshot,view: String,
    id: String?=null,query:String="",content:@Composable (JsonObject)->Unit) {
    key(snapshot.binding,snapshot.version,view,id,query) {
        var cursors by remember { mutableStateOf(listOf<String?>(null)) }
        val after=cursors.last()
        var page by remember(after) { mutableStateOf<JsonObject?>(null) }
        var error by remember(after) { mutableStateOf<String?>(null) }
        LaunchedEffect(after) {
            try {
                val response=client.projection(snapshot,recordViewSuffix(view,snapshot.version,id,query,after))
                require(response.cloudText("view")==view && response.cloudText("continuityId")==snapshot.binding.continuity &&
                    response["revision"]?.jsonPrimitive?.long==snapshot.version.revision)
                page=response
            } catch(e:CancellationException){throw e} catch(e:Exception){
                error=when((e as? OwnerSyncFailure)?.code){
                    "PARTY_AGGREGATE_SCOPE_REQUIRED","CLOUD_GRANT_DENIED" -> "Party-wide access is required for this view."
                    "SNAPSHOT_CHANGED","CLOUD_REVIEW_STALE" -> "The campaign changed. Refresh to see current records."
                    else -> "This view could not be verified. Refresh to try again."
                }
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        val current=page
        if(current==null && error==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(current!=null) {
            content(current)
            val next=current.s("nextAfter")
            if(cursors.size>1||next!=null)Row {
                PlayerTextButton({cursors=cursors.dropLast(1)},enabled=cursors.size>1){Text("Previous")}
                PlayerTextButton({next?.let { cursors=cursors+it }},enabled=next!=null){Text("Next")}
            }
        }
    }
}

@Composable internal fun CachedRecordList(client: CloudCampaignClient,snapshot: CloudSnapshot,kinds:Set<String>,
    actorId:String?,query:String,allowed:(JsonObject)->Boolean={true},onClick:(JsonObject)->Unit) {
    key(snapshot.binding,snapshot.version,kinds,actorId,query) {
        var cursors by remember {mutableStateOf(listOf(""))}
        val cursor=cursors.last()
        var page by remember(cursor){mutableStateOf<List<JsonObject>?>(null)}
        var failed by remember(cursor){mutableStateOf(false)}
        LaunchedEffect(cursor) {
            try { page=client.records(cursor,kinds,actorId,query) }
            catch(e:CancellationException){throw e} catch(_:Exception){failed=true}
        }
        if(failed)Text("The saved records are unavailable.") else if(page==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        val rows=page.orEmpty()
        rows.filter(allowed).forEach { row ->
            val data=row.data()
            if (row.s("kind")=="wallet") WalletRow(row){onClick(row)} else {
                val kind=row.cloudText("kind")
                val summary=when(kind){
                    "item_instance"->"${amount(data.s("quantity"))} · ${cloudLabel(data.s("equipment")?:"unknown")}" +
                            (if(data.s("attuned")=="true") " · Attuned" else "")
                    else->data.s("category")?:data.s("status")?:cloudLabel(kind)
                }
                PlayerEntry(row.cloudText("title"),summary,if(kind=="item_instance") "Bag" else "Journal",{onClick(row)})
            }
        }
        if(page!=null && rows.none(allowed))Text(if(rows.size==50) "No matching entries on this page." else "No matching entries.")
        if(cursors.size>1 || rows.size==50)Row {
            PlayerTextButton({cursors=cursors.dropLast(1)},enabled=cursors.size>1){Text("Previous")}
            PlayerTextButton({cursors=cursors+rows.last().cloudText("id")},enabled=rows.size==50){Text("Next")}
        }
    }
}

@Composable private fun SelectedRecord(client:CloudCampaignClient,snapshot:CloudSnapshot,id:String,render:@Composable (JsonObject)->Unit) {
    var row by remember(client,snapshot.binding,snapshot.version,id) { mutableStateOf<JsonObject?>(null) }
    var loaded by remember(client,snapshot.binding,snapshot.version,id) { mutableStateOf(false) }
    LaunchedEffect(client,snapshot.binding,snapshot.version,id) {
        try {row=client.record(id,snapshot)}
        catch(e:CancellationException){throw e} catch(_:Exception){row=null}
        finally { loaded=true }
    }
    if(row!=null)render(requireNotNull(row))
    else if(loaded)Text("Record unavailable.") else LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable private fun ActorEquipment(client:CloudCampaignClient,snapshot:CloudSnapshot,actorId:String,online:Boolean,
    openOperation:RecordOperation,select:(String,String)->Unit) {
    var filter by rememberSaveable(actorId){mutableStateOf("All gear")}
    var query by rememberSaveable(actorId){mutableStateOf("")}
    PlayerHeading("Personal wallet")
    CachedRecordList(client,snapshot,setOf("wallet"),actorId,""){ select("wallet",it.cloudText("id")) }
    PlayerTextButton({openOperation("define_wallet",args("walletId" to id(),"holderId" to word(actorId),"shared" to JsonPrimitive(false),"source" to manual))}, enabled=online){Text("Add personal wallet")}
    PlayerTabs(listOf("All gear","Equipped","Attuned","Favorites"),filter,{filter=it})
    PlayerSearch(query,{query=it.take(120)},"Search equipment")
    val eligible:(JsonObject)->Boolean={ row ->
        val d=row.data()
        d.s("custodianId")==actorId && d.s("holding")=="carried" && d.s("partyAccess")=="true" && when(filter){
            "Equipped"->d.s("equipment") in setOf("worn","held")
            "Attuned"->d.s("attuned")=="true"
            "Favorites"->d.s("favorite")=="true"
            else->true
        }
    }
    if(online)key(filter) {RemoteRecordPage(client,snapshot,"inventory",id=actorId,query=query){page->
        val entries=page.rows("items").filter(eligible)
        entries.forEach{row->
            val d=row.data()
            PlayerEntry(row.cloudText("title"),"${amount(d.s("quantity"))} · ${cloudLabel(d.s("equipment")?:"Unknown")}",
                "Bag",{select("item",row.cloudText("id"))},if(d.s("attuned")=="true")"Attuned" else null)
        }
        if(entries.isEmpty())Text(if(page.s("nextAfter")==null)"No matching equipment." else "No matching equipment on this page.")
    }} else CachedRecordList(client,snapshot,setOf("item_instance"),null,query,allowed=eligible){select("item",it.cloudText("id"))}
}

@Composable private fun WalletRow(wallet:JsonObject,onClick:()->Unit) {
    val d=wallet.data()
    PlayerEntry(wallet.cloudText("title"),cloudCoinsCaption(d.obj("coins")),"Bag",onClick,
        if (d.s("shared")=="true") "Shared" else null)
}

@Composable private fun DefinitionSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,definitionId:String,compatibleOnly:Boolean,online:Boolean,
    dismiss:()->Unit,open:RecordOperation,holding:(String)->Unit) {
    PlayerSheet("Inventory item",dismiss) {
        SelectedRecord(client,snapshot,definitionId){ entity->
            val d=entity.data()
            CloudEntityImage(client,snapshot,online,definitionId,"item_definition",entity.cloudText("title"))
            Text(entity.cloudText("title"),style=MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(d.s("category"),d.s("rarity")).joinToString(" · "),color=RebuildPalette.Gold)
            d.s("description")?.let {Text(it)}
            PlayerOutlinedButton({
                open("edit_item_definition",args("definitionId" to word(definitionId),"name" to entity.getValue("title"),
                    "description" to d.getValue("description"),"category" to d.getValue("category"),
                    "rarity" to (d["rarity"]?:JsonNull),"source" to manual))
            },enabled=online){Text("Edit shared definition")}
            PlayerTextButton({
                open("acquire_item",args("definitionId" to word(definitionId),"itemId" to id(),"source" to manual))
            },enabled=online){Text("Record acquisition")}
            PlayerTextButton({
                open("purchase_item",args("definitionId" to word(definitionId),"itemId" to id(),"sellerId" to JsonNull,"source" to manual))
            },enabled=online){Text("Record purchase")}
        }
        PlayerHeading("Carried by")
        if(online) RemoteRecordPage(client,snapshot,"inventory",id=definitionId){page->
            page.rows("items").filter { !compatibleOnly || it.s("groupId")==null || it.s("groupId")=="d:$definitionId" }.forEach { item->
                val label=item.s("custodianName")?:item.s("ownerName")?:"Unassigned party holding"
                val quantity=amount(item.s("quantity"))
                PlayerEntry(label,"$quantity · ${cloudLabel(item.s("holding")?:"Unknown")}","Bag",{holding(item.cloudText("id"))})
            }
        } else CachedRecordList(client,snapshot,setOf("item_instance"),null,"",allowed={
            it.data().s("definitionId")==definitionId && it.data().s("partyAccess")=="true"
        }){holding(it.cloudText("id"))}
    }
}

private sealed interface HoldingDefinition {
    data object Loading : HoldingDefinition
    data object Unavailable : HoldingDefinition
    data class Ready(val record: JsonObject, val canManageCatalog: Boolean) : HoldingDefinition
}

@Composable private fun holdingDefinition(
    client: CloudCampaignClient, snapshot: CloudSnapshot, item: JsonObject, online: Boolean, attempt: Int
): HoldingDefinition = key(client, snapshot.binding, snapshot.version, item, online, attempt) {
    val state by produceState<HoldingDefinition>(HoldingDefinition.Loading) {
        value = try {
            val definitionId = item.data().cloudText("definitionId")
            val catalog = client.record(definitionId, snapshot)
            val definition = catalog ?: if (online) {
                val view = client.projection(snapshot, recordViewSuffix("inventory", snapshot.version, item.cloudText("id")))
                require(view.s("view") == "inventory" && view.s("authority") == "cloud" &&
                    view.s("subject") == snapshot.binding.subject && view.s("continuityId") == snapshot.binding.continuity &&
                    view["grantRevision"]?.jsonPrimitive?.longOrNull == snapshot.version.grantRevision && view["holding"] == item)
                view["definition"] as? JsonObject
            } else null
            if (definition != null && definition.s("id") == definitionId && definition.s("kind") == "item_definition" &&
                definition.s("life") == "active" && !definition.s("title").isNullOrBlank() && definition["data"] is JsonObject)
                HoldingDefinition.Ready(definition, catalog != null)
            else HoldingDefinition.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HoldingDefinition.Unavailable
        }
    }
    state
}

@Composable private fun HoldingSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,itemId:String,online:Boolean,
    dismiss:()->Unit,open:RecordOperation,inspect:(String)->Unit,manage:(String)->Unit) {
    var pane by rememberSaveable(itemId){mutableStateOf("Details")}
    PlayerSheet("Item inspection",dismiss) {
        SelectedRecord(client,snapshot,itemId){ item->
            val d=item.data()
            var attempt by remember(itemId, snapshot.binding, snapshot.version) { mutableIntStateOf(0) }
            val source = holdingDefinition(client, snapshot, item, online, attempt)
            val ready = source as? HoldingDefinition.Ready
            val definition = ready?.record
            val name = if (d.s("customized") == "true") item.cloudText("title") else definition?.s("title")
            CloudEntityImage(client,snapshot,online,itemId,"item_instance",name ?: "Item")
            if (name != null) Text(name,style=MaterialTheme.typography.titleLarge)
            else if (source == HoldingDefinition.Unavailable)
                Text("Recorded holding · "+item.cloudText("title"),style=MaterialTheme.typography.titleMedium)
            Text("${amount(d.s("quantity"))} · ${cloudLabel(d.s("holding")?:"Unknown")} · ${cloudLabel(d.s("equipment")?:"Unknown")}",color=RebuildPalette.Gold)
            PlayerTabs(listOf("Details","Transfer","History","Components"),pane,{pane=it})
            when(pane) {
                "History" -> if(online) {
                    PlayerTextButton({open("record_historical_item_event",args("eventId" to id(),"itemId" to word(itemId),
                        "worldTime" to JsonNull,"sourcePublishedAt" to JsonNull,"previousOwner" to JsonNull,"newOwner" to JsonNull,"correctsId" to JsonNull))}){Text("Add sourced history")}
                    RemoteRecordPage(client,snapshot,"item_history",id=itemId){page->
                    page.rows("events").forEach { event->
                        val body=event.data()
                        PlayerCard {
                            val component=event.s("kind")=="component_event"
                            Text("Previous Owner · ${if(component)recordName(client,snapshot,event.s("actorId"),"Unknown")else ownerCaption(client,snapshot,body.obj("before"))}")
                            Text("New Owner · ${if(component)"Unchanged"else ownerCaption(client,snapshot,body.obj("after"))}")
                            Text("Reason · ${if(component)cloudLabel(event.cloudText("title"))else event.cloudText("title")}",style=MaterialTheme.typography.titleSmall)
                            CloudReceiptLink(client,snapshot,online,body.s("commandId"))
                            if (body.s("historical") == "true") {
                                Text(listOfNotNull(body.s("evidence")?.let(::cloudLabel),body.s("worldTime")).joinToString(" · "),color=RebuildPalette.Muted)
                                PlayerTextButton({
                                    fun ownerInput(key: String): JsonElement {
                                        val recorded = body[key] as? JsonObject ?: return JsonNull
                                        val kind = recorded.s("kind")?.takeIf { it in setOf("actor", "world_entity", "named", "unknown") } ?: return JsonNull
                                        return buildJsonObject {
                                            put("kind", kind)
                                            put("id", recorded["ownerId"]?.takeUnless { it == JsonNull } ?: recorded["externalRecipientId"] ?: JsonNull)
                                            put("name", recorded["externalRecipientName"] ?: JsonNull)
                                        }
                                    }
                                    open("record_historical_item_event", buildJsonObject {
                                        put("eventId", id()); put("itemId", itemId); put("eventType",body.getValue("type"))
                                        put("previousOwner", ownerInput("before")); put("newOwner", ownerInput("after"))
                                        put("worldTime", body["worldTime"] ?: JsonNull); put("sourcePublishedAt", JsonNull)
                                        put("evidence", body.getValue("evidence")); put("correctsId", event.cloudText("id"))
                                    })
                                }, enabled = online) { Text("Correct this historical event") }
                            }
                        }
                    }
                    if(page.rows("events").isEmpty())Text("No recorded item history.")
                }} else Text("Connect to retrieve linked item history.")
                "Components" -> if(online) RemoteRecordPage(client,snapshot,"genealogy",id=itemId){page->
                    page.rows("events").forEach { event->
                        val data=event.data()
                        Text("${cloudLabel(data.s("method")?:"component")} · ${amount(data.s("quantity"))}")
                        val donor=data.s("donorItemId")
                        val result=data.s("outputItemId")
                        if(donor!=null) PlayerTextButton({inspect(donor)}){Text("Component · "+recordName(client,snapshot,donor,"Historical component"))}
                        if(result!=null) PlayerTextButton({inspect(result)}){Text("Result · "+recordName(client,snapshot,result,"Resulting item"))}
                        data.s("evidence")?.let {Text(it)}
                    }
                    if(page.rows("events").isEmpty())Text("No linked component events.")
                }else Text("Connect to retrieve component history.")
                "Transfer" -> HoldingTransfer(client,snapshot,item,online,open)
                else->{
                    val description = d.s("description") ?: definition?.data()?.s("description")
                    description?.let { Text(it) }
                    Text("Owner · ${ownerCaption(client,snapshot,d)}")
                    d.s("custodianId")?.let{Text("Carried by · "+recordName(client,snapshot,it,"Unknown"))}
                    d.s("locationId")?.let{Text("Stored at · "+recordName(client,snapshot,it,"Unknown"))}
                    Text(if(d.s("attuned")=="true") "Attuned" else "Unattuned")
                    when (source) {
                        HoldingDefinition.Loading -> Text("Loading item definition…")
                        HoldingDefinition.Unavailable -> {
                            Text("Item definition unavailable.")
                            if (online) PlayerTextButton({ attempt++ }) { Text("Retry item definition") }
                        }
                        is HoldingDefinition.Ready -> if (source.canManageCatalog)
                            PlayerOutlinedButton({ manage(source.record.cloudText("id")) }) { Text("Manage in Inventory") }
                    }
                    val customization = if (ready != null && online && d.s("quantity") == "1" && name != null) buildJsonObject {
                        put("itemId", itemId)
                        put("name", name)
                        description?.let { put("description", it) }
                        d.s("condition")?.let { put("condition", it) }
                        put("source", manual)
                    } else null
                    PlayerTextButton({ customization?.let { open("customize_item", it) } }, enabled = customization != null) {
                        Text("Customize this instance")
                    }
                    if(d.s("holding")=="carried" && d.s("quantity")=="1"){
                        definition?.let{ linked->
                            val spec=linked.data()
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                val options=if(d.s("equipment") in setOf("held","worn")) listOf("packed")
                                    else listOfNotNull("held".takeIf{spec.s("handheld")=="true"},"worn".takeIf{spec.s("wearable")=="true"})
                                options.forEach { mode->
                                    PlayerTextButton({open("equip_item",args("itemId" to word(itemId),"equipment" to word(mode),"source" to manual))},enabled=online){
                                        Text(when(mode){"held"->"Hold";"worn"->"Wear";else->"Unequip"})
                                    }
                                }
                            }
                            if(spec.s("attunement")=="required") PlayerTextButton({
                                open("set_item_attunement",args("itemId" to word(itemId),"attuned" to JsonPrimitive(d.s("attuned")!="true"),"source" to manual))
                            },enabled=online){Text(if(d.s("attuned")=="true")"End attunement" else "Attune")}
                            if(spec.s("activation")=="manual")PlayerTextButton({
                                open("set_item_operating",args("itemId" to word(itemId),"operating" to JsonPrimitive(d.s("operating")!="true"),"source" to manual))
                            },enabled=online){Text(if(d.s("operating")=="true")"Deactivate" else "Activate")}
                        }
                    }
                    PlayerTextButton({open("set_item_favorite",args("itemId" to word(itemId),"favorite" to JsonPrimitive(d.s("favorite")!="true")))},enabled=online) {
                        Text(if(d.s("favorite")=="true")"Remove favorite" else "Favorite")
                    }
                    PlayerDisclosure("Effects and actions"){
                        CachedRecordList(client,snapshot,setOf("attack","action_definition","effect","form","spell_grant"),null,"",
                            allowed={it.data().s("sourceItemId")==itemId}){inspect(it.cloudText("id"))}
                        val holder=d.s("custodianId")
                        if(holder!=null && d.s("quantity")=="1") {
                            for((label,operation,identity) in listOf(Triple("Add item attack","define_attack","attackId"),
                                Triple("Add item action","define_action","actionId"),Triple("Add item effect","define_effect","effectId"))){
                                PlayerTextButton({open(operation,args("actorId" to word(holder),"sourceItemId" to word(itemId),
                                    "transfersWithItem" to JsonPrimitive(true),identity to id(),"source" to manual))},enabled=online){Text(label)}
                            }
                            val pool=d.s("resourcePoolId")
                            if(pool!=null)PlayerTextButton({inspect(pool)}){Text("Inspect charges")}
                            else PlayerTextButton({open("bind_item_charge_pool",args("itemId" to word(itemId),"source" to manual))},enabled=online){Text("Link charge pool")}
                        }
                    }
                    PlayerDisclosure("Source"){Text(d.s("source")?:"Unknown");Text("Instance · $itemId")}
                }
            }
        }
    }
}
@Composable private fun recordName(client:CloudCampaignClient,snapshot:CloudSnapshot,id:String?,fallback:String):String {
    if(id==null)return fallback
    return key(client,snapshot.binding,snapshot.version,id,fallback) {
        val label by produceState(fallback) {
            value=try { client.record(id,snapshot)?.s("title")?:fallback }
            catch(e:CancellationException){throw e} catch(_:Exception){fallback}
        }
        label
    }
}
@Composable private fun ownerCaption(client:CloudCampaignClient,snapshot:CloudSnapshot,data:JsonObject):String =
    if(data.s("ownerType")=="party")"Party" else recordName(client,snapshot,data.s("ownerId")?:data.s("externalRecipientId"),data.s("externalRecipientName")?:"Unknown")

@Composable private fun HoldingTransfer(client:CloudCampaignClient,snapshot:CloudSnapshot,item:JsonObject,online:Boolean,open:RecordOperation) {
    val itemId=item.cloudText("id")
    val stock=item.data().s("quantity")?.toIntOrNull()
    if(stock==null || stock<=0){Text("A confirmed quantity is required before moving this item.");return}
    var entered by rememberSaveable(itemId,stock){mutableStateOf(stock.toString())}
    val quantity=entered.toIntOrNull()
    if(stock>1) {
        Field("Quantity",entered,{entered=it.take(9)})
        Slider(value=(quantity?:1).coerceIn(1,stock).toFloat(),onValueChange={entered=it.roundToInt().toString()},
            modifier=Modifier.semantics{contentDescription="Transfer quantity"},
            valueRange=1f..stock.toFloat(),enabled=online)
    }
    Text("Selected · ${quantity?:"?"}    Remaining · ${quantity?.takeIf{it in 1..stock}?.let{stock-it}?:"?"}")
    val selectable=quantity!=null && quantity in 1..stock
    val splitId=rememberSaveable(itemId,quantity){UUID.randomUUID().toString()}
    val destinations=listOf("Party member","Storage","Unassigned","External")
    var type by rememberSaveable(itemId){mutableStateOf("Party member")}
    var target by rememberSaveable(itemId,type){mutableStateOf<String?>(null)}
    var recipient by rememberSaveable(itemId,type){mutableStateOf("")}
    var recipientId by rememberSaveable(itemId,type){mutableStateOf<String?>(null)}
    var outsideLocation by rememberSaveable(itemId,type){mutableStateOf<String?>(null)}
    var reason by rememberSaveable(itemId,type){mutableStateOf("")}
    var disposition by rememberSaveable(itemId,type){mutableStateOf("Gift")}
    var ownership by rememberSaveable(itemId,type){mutableStateOf("Give")}
    var shared by rememberSaveable(itemId,type){mutableStateOf(false)}
    if(stock>1){
        PlayerOutlinedButton({open("split_stack",args("itemId" to word(itemId),"splitId" to word(splitId),"quantity" to JsonPrimitive(quantity),"source" to manual))},
            enabled=online && selectable && quantity!=stock){Text("Split stack")}
    }
    if(type=="Storage" || type=="Unassigned")Row(Modifier.toggleable(value=shared,enabled=online,role=Role.Checkbox,onValueChange={shared=it}),verticalAlignment=Alignment.CenterVertically){
        Checkbox(checked=shared,onCheckedChange=null,enabled=online)
        Text("Party-owned after transfer")
    }
    PlayerDisclosure("Merge compatible stack") {
        CachedRecordList(client,snapshot,setOf("item_instance"),null,"",allowed={
            val a=item.data();val b=it.data()
            it.s("id")!=itemId && listOf("definitionId","ownerId","custodianId","locationId","holding","equipment","condition","resourcePoolId").all { key->a[key]==b[key] } &&
                listOf(a,b).all { row->row.s("partyAccess")=="true" && row.s("equipment")=="packed" && row.s("customized")!="true" && row.s("attuned")!="true" && row.s("operating")!="true" && row.s("resourcePoolId")==null }
        }) { merge->if(online)open("merge_stacks",args("keepId" to word(itemId),"mergeId" to merge.getValue("id"),"source" to manual)) }
    }
    PlayerHeading("Destination")
    PlayerTabs(destinations,type,{type=it})
    when(type) {
        "Party member" -> {
            PlayerTabs(listOf("Give","Lend"),ownership,{ownership=it})
            CachedRecordList(client,snapshot,setOf("actor"),null,"",allowed={cloudPartyMember(it)}){target=it.s("id")}
        }
        "Storage" -> CachedRecordList(client,snapshot,setOf("world_entity"),null,"",allowed={it.data().s("category")=="place" && it.data().s("partyStorage")=="true"}){target=it.s("id")}
        "External" -> {
            PlayerTabs(listOf("Gift","Lost","Dropped"),disposition,{disposition=it})
            if (disposition == "Gift") {
                Field("Known recipient (optional)", recipient, { recipient=it.take(180); recipientId=null })
                PlayerDisclosure("Choose a known recipient") {
                    CachedRecordList(client,snapshot,setOf("world_entity"),null,"",allowed={it.data().s("category") != "place"}) { row ->
                        recipientId = row.cloudText("id"); recipient = row.cloudText("title")
                    }
                }
            }
            PlayerDisclosure("Last known location") {
                Text(outsideLocation?.let { recordName(client,snapshot,it,"Unknown") } ?: "Location unknown")
                CachedRecordList(client,snapshot,setOf("world_entity"),null,"",allowed={it.data().s("category") == "place"}) { row ->
                    outsideLocation = row.cloudText("id")
                }
                if (outsideLocation != null) PlayerTextButton({ outsideLocation=null }) { Text("Clear location") }
            }
        }
        else->Text("The party retains this holding with its physical location unresolved.")
    }
    if(target!=null)Text("Selected · "+recordName(client,snapshot,target,"Party destination"),color=RebuildPalette.Gold)
    Field("Reason",reason,{reason=it.take(180)})
    val destination = when(type) {
        "Party member"->if(ownership=="Lend")"loan" else "party_actor";"Storage"->"storage";"External"->when(disposition){"Gift"->"external_gift";"Lost"->"lost";else->"dropped"};else->"unassigned"
    }
    PlayerButton({
        val dest=args("type" to word(destination),"actorId" to (target?.takeIf{type=="Party member"}?.let(::word)?:JsonNull),
            "locationId" to ((if(type=="External")outsideLocation else target?.takeIf{type=="Storage"})?.let(::word)?:JsonNull),
            "recipientId" to (recipientId?.takeIf{destination=="external_gift"}?.let(::word)?:JsonNull),
            "recipientName" to (recipient.takeIf{destination=="external_gift" && it.isNotBlank()}?.let(::word)?:JsonNull))
        val chosen=if(shared && type in setOf("Storage","Unassigned")) JsonObject(dest+("partyOwned" to JsonPrimitive(true))) else dest
        open("transfer_item",args("itemId" to word(itemId),"quantity" to JsonPrimitive(quantity),
            "splitId" to (if(quantity==stock)JsonNull else word(splitId)),"destination" to chosen,"reason" to word(reason),"source" to manual))
    },enabled=online && selectable && reason.isNotBlank() && (type !in setOf("Party member","Storage") || target!=null)) {Text("Review transfer")}
    if(type=="External")PlayerOutlinedButton({
        open("sell_item",args("itemId" to word(itemId),"quantity" to JsonPrimitive(quantity),"splitId" to (if(quantity==stock)JsonNull else word(splitId)),
            "buyerId" to (recipientId?.let(::word)?:JsonNull),"buyerName" to (recipient.takeIf{it.isNotBlank()}?.let(::word)?:JsonNull),
            "locationId" to (outsideLocation?.let(::word)?:JsonNull),"source" to manual))
    },enabled=online && selectable){Text("Record sale")}
}

@Composable private fun WalletSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,walletId:String,online:Boolean,
    dismiss:()->Unit,open:RecordOperation) {
    var selected by rememberSaveable(walletId){mutableStateOf("gp")}
    var entered by rememberSaveable(walletId){mutableStateOf("")}
    var recipient by rememberSaveable(walletId){mutableStateOf<String?>(null)}
    var reason by rememberSaveable(walletId){mutableStateOf("")}
    PlayerSheet("Wallet",dismiss){
        SelectedRecord(client,snapshot,walletId){wallet->
            val d=wallet.data();val balance=d.obj("coins")
            Text(wallet.cloudText("title"),style=MaterialTheme.typography.titleLarge)
            Text(coins.joinToString("  ·  "){key->"${amount(balance.s(key))} ${key.uppercase()}"})
            PlayerTextButton({open("adjust_coins",args("walletId" to word(walletId),"source" to manual))},enabled=online){Text("Record income or expense")}
            PlayerTextButton({open("exchange_coins",args("walletId" to word(walletId),"source" to manual))},enabled=online){Text("Exchange denominations")}
            PlayerDisclosure("Confirm a recorded balance"){
                coins.forEach{coin->
                    PlayerTextButton({open("reconcile_wallet_denomination",args("walletId" to word(walletId),
                        "denomination" to word(coin),"previous" to (balance[coin]?:JsonNull),"expectedRevision" to wallet.getValue("revision")))},enabled=online){
                        Text("${coin.uppercase()} · ${amount(balance.s(coin))}")
                    }
                }
            }
            PlayerDisclosure("Transfer coins") {
                PlayerTabs(coins.map {it.uppercase()},selected.uppercase(),{selected=it.lowercase()})
                Field("Amount",entered,{entered=it.take(12)})
                Text("Destination wallet",style=MaterialTheme.typography.titleSmall)
                CachedRecordList(client,snapshot,setOf("wallet"),null,"",allowed={it.s("id")!=walletId}){recipient=it.s("id")}
                recipient?.let{Text("Selected · "+recordName(client,snapshot,it,"Destination wallet"),color=RebuildPalette.Gold)}
                Field("Reason",reason,{reason=it.take(180)})
                val q=entered.toLongOrNull()
                val current=balance.s(selected)?.toLongOrNull()
                if(q!=null && current!=null && q in 1..current) Text("Remaining · ${amount((current-q).toString())} ${selected.uppercase()}")
                PlayerButton({
                    val payment=buildJsonObject {coins.forEach{put(it,if(it==selected)q?:0L else 0L)}}
                    open("transfer_coins",args("fromWalletId" to word(walletId),"toWalletId" to word(requireNotNull(recipient)),
                        "coins" to payment,"reason" to word(reason),"source" to manual))
                },enabled=online && recipient!=null && q!=null && current!=null && q in 1..current && reason.isNotBlank()){Text("Review transfer")}
            }
            if(online)PlayerDisclosure("Transaction history") {
                RemoteRecordPage(client,snapshot,"timeline",id=walletId){page->
                    page.rows("events").forEach{ event->
                        Text(event.s("title")?:"Transaction")
                        event.data().s("source")?.let{Text(it,color=RebuildPalette.Muted)}
                        CloudReceiptLink(client,snapshot,online,event.data().s("commandId"))
                    }
                    if(page.rows("events").isEmpty())Text("No recorded transactions.")
                }
            }
        }
    }
}
@Composable private fun ProjectSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,projectId:String,online:Boolean,
    dismiss:()->Unit,open:RecordOperation,inspect:(String)->Unit) {
    PlayerSheet("Invention project",dismiss){
        SelectedRecord(client,snapshot,projectId){project->
            val d=project.data()
            CloudEntityImage(client,snapshot,online,projectId,"invention_project",project.cloudText("title"))
            Text(project.cloudText("title"),style=MaterialTheme.typography.titleLarge)
            Text(cloudLabel(d.s("status")?:"Unknown"),color=RebuildPalette.Gold)
            d.s("intent")?.let{Text(it)}
            val uses=(d["requirements"] as? JsonArray).orEmpty().mapNotNull {(it as? JsonPrimitive)?.contentOrNull}
            if(uses.isNotEmpty()) {Text("Requirements",style=MaterialTheme.typography.titleSmall);uses.forEach {Text(it)}}
            CloudLinkedRecords(client, snapshot, projectId, online, inspect)
            PlayerDisclosure("Blueprints and stages",initiallyOpen=true) {
                CachedRecordList(client,snapshot,setOf("project_stage","blueprint_version"),null,"",allowed={it.data().s("projectId")==projectId}) {
                    if(it.s("kind")=="project_stage" && d.s("status")!="completed" && online)
                        open("resolve_construction_stage",args("stageId" to it.getValue("id"),"source" to manual))
                    else inspect(it.cloudText("id"))
                }
            }
            if(d.s("status")!="completed") {
                PlayerTextButton({open("revise_invention_plan",args("projectId" to word(projectId),"expectedRevision" to project.getValue("revision"),
                    "title" to project.getValue("title"),"intent" to d.getValue("intent"),"requirements" to (d["requirements"]?:JsonArray(emptyList())),
                    "additionalResearchIds" to JsonArray(emptyList()),"source" to manual))},enabled=online){Text("Edit project plan")}
                PlayerTextButton({open("link_project_dependency",args("projectId" to word(projectId),"source" to manual))},enabled=online){Text("Link prerequisite project")}
                for((label,op,identity) in listOf(Triple("Record blueprint","record_blueprint_version","blueprintId"),Triple("Add construction stage","define_construction_stage","stageId"),
                    Triple("Record component integration","integrate_component","eventId"))) {
                    PlayerTextButton({open(op,args("projectId" to word(projectId),identity to id(),"source" to manual))},enabled=online){Text(label)}
                }
                PlayerDisclosure("Component rules") {
                    Text("A confirmed stage can install or incorporate a physical component, or consume a recorded material quantity. Record a proposed extraction or detachment in the project plan for adjudication.")
                }
                PlayerTextButton({open("pay_construction_cost",args("projectId" to word(projectId),"source" to manual))},enabled=online){Text("Record payment")}
                PlayerTextButton({open("link_completed_invention",args("projectId" to word(projectId),"source" to manual))},enabled=online){Text("Link completed invention")}
            }
            d.s("outputId")?.let{output->PlayerOutlinedButton({inspect(output)}){Text("Open resulting item")}}
        }
    }
}

@Composable private fun KnownRecordSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,recordId:String,online:Boolean,
    dismiss:()->Unit,open:RecordOperation,inspect:(String)->Unit) {
    PlayerSheet("Campaign record",dismiss){
        SelectedRecord(client,snapshot,recordId){row->
            val d=row.data()
            val kind=row.cloudText("kind")
            val title=row.cloudText("title")
            if(kind=="world_entity") CloudEntityImage(client,snapshot,online,recordId,kind,title)
            Text(title,style=MaterialTheme.typography.titleLarge)
            listOf("referenceKind","category","status","evidence","worldTime","originalWorldTime").mapNotNull { d.s(it) }
                .takeIf{it.isNotEmpty()}?.let{Text(it.joinToString(" · "){s->cloudLabel(s)},color=RebuildPalette.Gold)}
            for(key in listOf("knownDescription","terms","detail","finding","narrative")) d.s(key)?.let{Text(it)}
            val requirements=(d["knownRequirements"] as? JsonArray).orEmpty().mapNotNull{(it as? JsonPrimitive)?.contentOrNull}
            if(requirements.isNotEmpty()){PlayerHeading("Known requirements");requirements.forEach{Text(it)}}
            fun prefill(identity:String,fields:List<String>,revision:Boolean=false):MutableMap<String,JsonElement> {
                val values=mutableMapOf<String,JsonElement>(identity to word(recordId),"source" to manual)
                for(field in fields)d[field]?.let{values[field]=it}
                if(revision)values["expectedRevision"]=row.getValue("revision")
                return values
            }
            val labels=when(kind){
                "world_entity"->"Edit connection";"known_fact"->"Revise known lore";"research_note"->"Revise finding";
                "objective"->"Edit objective";"timeline_event"->"Record correction";else->null
            }
            if(labels!=null)PlayerOutlinedButton({
                val operation: String
                val values:MutableMap<String,JsonElement>
                when(kind){
                    "world_entity"->{operation="edit_connection";values=prefill("entityId",listOf("category","knownDescription","partyStorage"))
                        values["name"]=word(title);values["actorId"]=row["actorId"]?:JsonNull}
                    "known_fact"->{operation="revise_known_fact";values=prefill("factId",listOf("detail","evidence","learnedAt","originalWorldTime","referenceKind"),true);values["title"]=word(title)}
                    "research_note"->{operation="revise_research_finding";values=prefill("researchId",listOf("finding","evidence"),true);values["title"]=word(title)}
                    "objective"->{operation="revise_objective";values=prefill("objectiveId",listOf("knownRequirements"),true);values["title"]=word(title)}
                    else->{operation="record_chronicle_event";values=prefill("correctsId",listOf("category","worldTime"))
                        values["eventId"]=id();values["subjects"]=JsonArray(emptyList())}
                }
                open(operation,JsonObject(values))
            },enabled=online){Text(labels)}
            if(kind=="objective")PlayerTextButton({
                open("record_objective_milestone",args("objectiveId" to word(recordId),"source" to manual))
            },enabled=online){Text("Record confirmed milestone")}
            if(kind=="relationship"){
                val from=d.s("fromId");val to=d.s("toId")
                if(from!=null)PlayerTextButton({inspect(from)}){Text("From · "+recordName(client,snapshot,from,"Known subject"))}
                if(to!=null)PlayerTextButton({inspect(to)}){Text("To · "+recordName(client,snapshot,to,"Known subject"))}
                val endpoints by produceState<Pair<String,String>?>(null,snapshot.version,recordId){
                    if(from!=null && to!=null)try{
                        val first=client.record(from)?.s("kind")
                        val second=client.record(to)?.s("kind")
                        if(first!=null && second!=null)value=first to second
                    }catch(e:CancellationException){throw e}catch(_:Exception){value=null}
                }
                endpoints?.let{(first,second)-> PlayerOutlinedButton({
                    open("update_relationship",args("relationshipId" to word(recordId),"relationship" to word(title),
                        "from" to args("id" to word(requireNotNull(from)),"kind" to word(first)),
                        "to" to args("id" to word(requireNotNull(to)),"kind" to word(second)),
                        "terms" to (d["terms"]?:JsonNull),"status" to d.getValue("status"),"source" to manual))
                },enabled=online){Text("Edit relationship")} }
            }
            d.s("source")?.let{PlayerDisclosure("Source"){Text(it)}}
            PlayerTextButton({
                open("record_known_fact",args("factId" to id(),"subject" to args("id" to word(recordId),"kind" to word(kind)),
                    "referenceKind" to word("document"),"learnedAt" to JsonNull,"originalWorldTime" to JsonNull,"source" to manual))
            },enabled=online){Text("Link known document")}
            CloudLinkedRecords(client,snapshot,recordId,online,inspect)
        }
    }
}

private fun emptyTradeSide(actor:String?=null)=buildJsonObject {
    put("actorId",actor?.let(::word)?:JsonNull);put("walletId",JsonNull)
    put("coins",buildJsonObject{coins.forEach{put(it,0)}});put("items",JsonArray(emptyList()))
}
private fun hasTradeCoins(side:JsonObject)=coins.any{(side.obj("coins").s(it)?.toLongOrNull()?:0L)>0}

@Composable private fun PartyTradeSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,online:Boolean,
    dismiss:()->Unit,open:RecordOperation) {
    var leftJson by rememberSaveable { mutableStateOf(emptyTradeSide().toString()) }
    var rightJson by rememberSaveable { mutableStateOf(emptyTradeSide().toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    val left=remember(leftJson){Json.parseToJsonElement(leftJson).jsonObject}
    val right=remember(rightJson){Json.parseToJsonElement(rightJson).jsonObject}
    val sides=listOf(left,right)
    val needsWallet=sides.any(::hasTradeCoins)
    val valid=sides.all{it.s("actorId")!=null && (hasTradeCoins(it) || it.rows("items").isNotEmpty()) && (!needsWallet || it.s("walletId")!=null)} &&
        left.s("actorId")!=right.s("actorId") && reason.isNotBlank()
    PlayerSheet("Party exchange",dismiss) {
        for((index,side) in sides.withIndex())key(index){
            Column(Modifier.testTag("trade-offer-$index")) {
                TradeOffer(client,snapshot,if(index==0)"First offer" else "Second offer",side,sides[1-index].s("actorId"),online){
                    if(index==0)leftJson=it.toString() else rightJson=it.toString()
                }
            }
        }
        Field("Reason",reason,{reason=it.take(180)})
        PlayerButton({
            fun prepared(s:JsonObject)=if(needsWallet)s else JsonObject(s+("walletId" to JsonNull))
            open("exchange_party_assets",args("left" to prepared(left),"right" to prepared(right),"reason" to word(reason),"source" to manual))
        },enabled=online && valid){Text("Review exchange")}
    }
}

@Composable private fun TradeOffer(client:CloudCampaignClient,snapshot:CloudSnapshot,label:String,side:JsonObject,
    otherActor:String?,online:Boolean,update:(JsonObject)->Unit) {
    val actor=side.s("actorId")
    PlayerHeading(label)
    PlayerDisclosure(if(actor==null)"Choose party member" else recordName(client,snapshot,actor,"Selected party member"),initiallyOpen=actor==null){
        CachedRecordList(client,snapshot,setOf("actor"),null,"",allowed={it.s("id")!=otherActor && cloudPartyMember(it)}){row->
            if(row.s("id")!=actor)update(emptyTradeSide(row.s("id")))
        }
    }
    if(actor==null)return
    val selected=side.rows("items")
    selected.forEach { leg->
        PlayerEntry(recordName(client,snapshot,leg.s("itemId"),"Selected holding"),
            "Quantity · ${amount(leg.s("quantity"))}","Bag",{
                update(JsonObject(side+("items" to JsonArray(selected.filterNot{it.s("itemId")==leg.s("itemId")}))))
            },"Remove")
    }
    PlayerDisclosure("Offer a holding"){
        var id by rememberSaveable(actor){mutableStateOf<String?>(null)}
        var maximum by rememberSaveable(actor){mutableStateOf<Int?>(null)}
        var quantity by rememberSaveable(actor,id){mutableStateOf("")}
        CachedRecordList(client,snapshot,setOf("item_instance"),null,"",allowed={row->
            val d=row.data()
            d.s("ownerId")==actor && d.s("partyAccess")=="true" && (d.s("custodianId")==null||d.s("custodianId")==actor) &&
                    selected.none{it.s("itemId")==row.s("id")}
        }){row->id=row.s("id");maximum=row.data().s("quantity")?.toIntOrNull()}
        id?.let{item->
            Text(recordName(client,snapshot,item,"Selected holding"))
            Text("Available · ${amount(maximum?.toString())}")
            Field("Quantity offered",quantity,{quantity=it.take(7)})
            val n=quantity.toIntOrNull()
            PlayerOutlinedButton({
                val leg=args("itemId" to word(item),"quantity" to JsonPrimitive(n),"splitId" to if(n==maximum)JsonNull else word(UUID.randomUUID().toString()))
                update(JsonObject(side+("items" to JsonArray(selected+leg))));id=null
            },enabled=online && n!=null && maximum?.let{n in 1..it}==true && selected.size<8){Text("Add to offer")}
        }
    }
    PlayerDisclosure("Wallet and offered coins"){
        CachedRecordList(client,snapshot,setOf("wallet"),actor,""){wallet->
            update(JsonObject(side+("walletId" to wallet.getValue("id"))))
        }
        side.s("walletId")?.let{walletId->
            SelectedRecord(client,snapshot,walletId){wallet->
                Text(wallet.cloudText("title"),style=MaterialTheme.typography.titleSmall)
                for(coin in coins){
                    val amount=side.obj("coins").s(coin)?:"0"
                    val available=wallet.data().obj("coins").s(coin)?.toLongOrNull()
                    Field("${coin.uppercase()} offered · ${available?.toString()?:"unknown"} available",if(amount=="0")"" else amount,{raw->
                        val n=if(raw.isBlank())0L else raw.toLongOrNull()
                        if(n!=null && n in 0..(available?:0L)){
                            val changed=JsonObject(side.obj("coins")+(coin to JsonPrimitive(n)))
                            update(JsonObject(side+("coins" to changed)))
                        }
                    })
                }
            }
        }
    }
}
