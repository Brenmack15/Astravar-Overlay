package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
            "Chronicle" -> listOf("Timeline","Objectives","Encounters","Checkpoints","Lore")
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
                    "Items" -> {
                        PlayerTextButton({ openOperation("define_item_definition",args("definitionId" to id(),"source" to manual)) }, enabled=online) { Text("Create item definition") }
                        if (online) RemoteRecordPage(client,snapshot,"inventory",query=query) { page ->
                            page.rows("groups").forEach { item ->
                                val quantity=amount(item.s("knownQuantity"))+if(item.s("unknownQuantityCount")!="0") " + unknown" else ""
                                PlayerEntry(item.s("title").orEmpty(),"Party quantity · $quantity","Bag",{
                                    detail="definition";selected=item.s("definitionId")
                                },item.s("instances")?.let { "$it holdings" })
                            }
                            if(page.rows("groups").isEmpty()) Text("No matching party items.")
                        } else CachedRecordList(client,snapshot,setOf("item_instance"),null,query,
                            allowed={ it.data().s("partyAccess")=="true" }) { detail="item";selected=it.s("id") }
                    }
                    "Wallets" -> {
                        PlayerTextButton({ openOperation("define_wallet",args("walletId" to id(),"source" to manual)) }, enabled=online) { Text("Add wallet") }
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
                                event.data().s("worldTime") ?: cloudLabel(event.s("kind").orEmpty()),"Journal",{inspect(event.cloudText("id"))}) }
                            if(page.rows("events").isEmpty()) Text("No matching events.")
                        } else CachedRecordList(client,snapshot,setOf("timeline_event","item_event","coin_transaction","component_event"),null,query){ inspect(it.cloudText("id")) }
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
                                "Companions"->row.data().s("role")=="companion"
                                "Beings"->row.data().s("category") in setOf("being","person")
                                "Factions"->row.data().s("category") in setOf("organization","faction")
                                "Places"->row.data().s("category")=="place"
                                else->true
                            }
                        }) { row ->
                            if(kind=="invention_project"){detail="project";selected=row.s("id")}
                            else inspect(row.cloudText("id"))
                        }
                    }
                }
            }
        }
        val selectedId=selected
        if(selectedId!=null){
            val close={ selected=null;detail=null }
            when(detail){
                "definition" -> DefinitionSheet(client,snapshot,selectedId,online,close,openOperation) { record -> detail="item";selected=record }
                "item" -> HoldingSheet(client,snapshot,selectedId,online,close,openOperation) { record -> detail="definition";selected=record }
                "wallet" -> WalletSheet(client,snapshot,selectedId,online,close,openOperation)
                "project" -> ProjectSheet(client,snapshot,selectedId,online,close,openOperation,inspect)
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

@Composable private fun CachedRecordList(client: CloudCampaignClient,snapshot: CloudSnapshot,kinds:Set<String>,
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
    var row by remember(snapshot.binding,snapshot.version,id) { mutableStateOf<JsonObject?>(null) }
    var loaded by remember(snapshot.binding,snapshot.version,id) { mutableStateOf(false) }
    LaunchedEffect(snapshot.binding,snapshot.version,id) {
        try {row=client.record(id)}
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
    CachedRecordList(client,snapshot,setOf("item_instance"),null,query,allowed={ row ->
        val d=row.data()
        d.s("custodianId")==actorId && d.s("holding")=="carried" && d.s("partyAccess")=="true" && when(filter){
            "Equipped"->d.s("equipment") in setOf("worn","held")
            "Attuned"->d.s("attuned")=="true"
            "Favorites"->d.s("favorite")=="true"
            else->true
        }
    }) { select("item",it.cloudText("id")) }
}

@Composable private fun WalletRow(wallet:JsonObject,onClick:()->Unit) {
    val d=wallet.data()
    PlayerEntry(wallet.cloudText("title"),cloudCoinsCaption(d.obj("coins")),"Bag",onClick,
        if (d.s("shared")=="true") "Shared" else null)
}

@Composable private fun DefinitionSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,definitionId:String,online:Boolean,
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
            page.rows("items").forEach { item->
                val label=item.s("custodianName")?:item.s("ownerName")?:"Unassigned party holding"
                val quantity=amount(item.s("quantity"))
                PlayerEntry(label,"$quantity · ${cloudLabel(item.s("holding")?:"Unknown")}","Bag",{holding(item.cloudText("id"))})
            }
        } else CachedRecordList(client,snapshot,setOf("item_instance"),null,"",allowed={
            it.data().s("definitionId")==definitionId && it.data().s("partyAccess")=="true"
        }){holding(it.cloudText("id"))}
    }
}

@Composable private fun HoldingSheet(client:CloudCampaignClient,snapshot:CloudSnapshot,itemId:String,online:Boolean,
    dismiss:()->Unit,open:RecordOperation,manage:(String)->Unit) {
    var pane by rememberSaveable(itemId){mutableStateOf("Details")}
    PlayerSheet("Item inspection",dismiss) {
        SelectedRecord(client,snapshot,itemId){ item->
            val d=item.data()
            val def=d.s("definitionId")
            CloudEntityImage(client,snapshot,online,itemId,"item_instance",item.cloudText("title"))
            Text(item.cloudText("title"),style=MaterialTheme.typography.titleLarge)
            Text("${amount(d.s("quantity"))} · ${cloudLabel(d.s("holding")?:"Unknown")} · ${cloudLabel(d.s("equipment")?:"Unknown")}",color=RebuildPalette.Gold)
            PlayerTabs(listOf("Details","Transfer","History","Components"),pane,{pane=it})
            when(pane) {
                "History" -> if(online) RemoteRecordPage(client,snapshot,"item_history",id=itemId){page->
                    page.rows("events").forEach { event->
                        val body=event.data()
                        PlayerCard {
                            Text("Previous Owner · ${ownerCaption(body.obj("before"))}")
                            Text("New Owner · ${ownerCaption(body.obj("after"))}")
                            Text("Reason · ${event.cloudText("title")}",style=MaterialTheme.typography.titleSmall)
                        }
                    }
                    if(page.rows("events").isEmpty())Text("No recorded item history.")
                } else Text("Connect to retrieve linked item history.")
                "Components" -> if(online) RemoteRecordPage(client,snapshot,"genealogy",id=itemId){page->
                    page.rows("events").forEach { event->
                        val data=event.data()
                        Text("${cloudLabel(data.s("method")?:"component")} · ${amount(data.s("quantity"))}")
                        Text("${data.s("donorItemId")} → ${data.s("outputItemId")}",color=RebuildPalette.Muted)
                        data.s("evidence")?.let {Text(it)}
                    }
                    if(page.rows("events").isEmpty())Text("No linked component events.")
                }else Text("Connect to retrieve component history.")
                "Transfer" -> HoldingTransfer(client,snapshot,item,online,open)
                else->{
                    d.s("description")?.let{Text(it)}
                    Text("Owner · ${ownerCaption(d)}")
                    d.s("custodianId")?.let{Text("Carried by · $it")}
                    d.s("locationId")?.let{Text("Stored at · $it")}
                    Text(if(d.s("attuned")=="true") "Attuned" else "Unattuned")
                    if(def!=null)PlayerOutlinedButton({manage(def)}){Text("Manage in Inventory")}
                    PlayerTextButton({open("customize_item",args("itemId" to word(itemId),"name" to item.getValue("title"),
                        "condition" to word(d.s("condition")?:"standard"),"source" to manual))},enabled=online && d.s("quantity")=="1"){Text("Customize this instance")}
                    if(d.s("holding")=="carried" && d.s("quantity")=="1"){
                        if(def!=null)SelectedRecord(client,snapshot,def){ definition->
                            val spec=definition.data()
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
                    PlayerDisclosure("Source"){Text(d.s("source")?:"Unknown");Text("Instance · $itemId")}
                }
            }
        }
    }
}
private fun ownerCaption(data:JsonObject):String = data.s("ownerId")?:data.s("externalRecipientName")?:data.s("externalRecipientId")?:"Unknown"

@Composable private fun HoldingTransfer(client:CloudCampaignClient,snapshot:CloudSnapshot,item:JsonObject,online:Boolean,open:RecordOperation) {
    val itemId=item.cloudText("id")
    val stock=item.data().s("quantity")?.toIntOrNull()
    if(stock==null || stock<=0){Text("A confirmed quantity is required before moving this item.");return}
    var entered by rememberSaveable(itemId,stock){mutableStateOf(stock.toString())}
    val quantity=entered.toIntOrNull()
    if(stock>1) {
        Field("Quantity",entered,{entered=it.take(9)})
        Slider(value=(quantity?:1).coerceIn(1,stock).toFloat(),onValueChange={entered=it.roundToInt().toString()},
            valueRange=1f..stock.toFloat(),enabled=online)
    }
    Text("Selected · ${quantity?:"?"}    Remaining · ${quantity?.takeIf{it in 1..stock}?.let{stock-it}?:"?"}")
    val selectable=quantity!=null && quantity in 1..stock
    val splitId=rememberSaveable(itemId,quantity){UUID.randomUUID().toString()}
    val destinations=listOf("Party member","Storage","Unassigned","External")
    var type by rememberSaveable(itemId){mutableStateOf("Party member")}
    var target by rememberSaveable(itemId,type){mutableStateOf<String?>(null)}
    var recipient by rememberSaveable(itemId,type){mutableStateOf("")}
    var reason by rememberSaveable(itemId,type){mutableStateOf("")}
    var disposition by rememberSaveable(itemId,type){mutableStateOf("Gift")}
    if(stock>1){
        PlayerOutlinedButton({open("split_stack",args("itemId" to word(itemId),"splitId" to word(splitId),"quantity" to JsonPrimitive(quantity),"source" to manual))},
            enabled=online && selectable && quantity!=stock){Text("Split stack")}
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
        "Party member" -> CachedRecordList(client,snapshot,setOf("actor"),null,"",allowed={it.data().s("role") in setOf("player","companion")}){target=it.s("id")}
        "Storage" -> CachedRecordList(client,snapshot,setOf("world_entity"),null,"",allowed={it.data().s("category")=="place" && it.data().s("partyStorage")=="true"}){target=it.s("id")}
        "External" -> {
            PlayerTabs(listOf("Gift","Lost","Dropped"),disposition,{disposition=it})
            if(disposition=="Gift")Field("Known recipient (optional)",recipient,{recipient=it.take(180)})
        }
        else->Text("The party retains this holding with its physical location unresolved.")
    }
    if(target!=null)Text("Selected · $target",color=RebuildPalette.Gold)
    Field("Reason",reason,{reason=it.take(180)})
    val destination = when(type) {
        "Party member"->"party_actor";"Storage"->"storage";"External"->when(disposition){"Gift"->"external_gift";"Lost"->"lost";else->"dropped"};else->"unassigned"
    }
    PlayerButton({
        val dest=args("type" to word(destination),"actorId" to (target?.takeIf{type=="Party member"}?.let(::word)?:JsonNull),
            "locationId" to (target?.takeIf{type=="Storage"}?.let(::word)?:JsonNull),"recipientId" to JsonNull,
            "recipientName" to (recipient.takeIf{destination=="external_gift" && it.isNotBlank()}?.let(::word)?:JsonNull))
        open("transfer_item",args("itemId" to word(itemId),"quantity" to JsonPrimitive(quantity),
            "splitId" to (if(quantity==stock)JsonNull else word(splitId)),"destination" to dest,"reason" to word(reason),"source" to manual))
    },enabled=online && selectable && reason.isNotBlank() && (type !in setOf("Party member","Storage") || target!=null)) {Text("Review transfer")}
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
            PlayerDisclosure("Transfer coins") {
                PlayerTabs(coins.map {it.uppercase()},selected.uppercase(),{selected=it.lowercase()})
                Field("Amount",entered,{entered=it.take(12)})
                Text("Destination wallet",style=MaterialTheme.typography.titleSmall)
                CachedRecordList(client,snapshot,setOf("wallet"),null,"",allowed={it.s("id")!=walletId}){recipient=it.s("id")}
                recipient?.let{Text("Selected · $it",color=RebuildPalette.Gold)}
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
                    page.rows("events").forEach{ e->Text(e.s("title")?:"Transaction"); e.data().s("source")?.let{Text(it,color=RebuildPalette.Muted)} }
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
            PlayerDisclosure("Blueprints and stages",initiallyOpen=true) {
                CachedRecordList(client,snapshot,setOf("project_stage","blueprint_version"),null,"",allowed={it.data().s("projectId")==projectId}) {
                    if(it.s("kind")=="project_stage" && d.s("status")!="completed" && online)
                        open("resolve_construction_stage",args("stageId" to it.getValue("id"),"source" to manual))
                    else inspect(it.cloudText("id"))
                }
            }
            if(d.s("status")!="completed") {
                for((label,op,identity) in listOf(Triple("Record blueprint","record_blueprint_version","blueprintId"),Triple("Add construction stage","define_construction_stage","stageId"),
                    Triple("Record component integration","integrate_component","eventId"))) {
                    PlayerTextButton({open(op,args("projectId" to word(projectId),identity to id(),"source" to manual))},enabled=online){Text(label)}
                }
                PlayerTextButton({open("pay_construction_cost",args("projectId" to word(projectId),"source" to manual))},enabled=online){Text("Record payment")}
                PlayerTextButton({open("link_completed_invention",args("projectId" to word(projectId),"source" to manual))},enabled=online){Text("Link completed invention")}
            }
            d.s("outputId")?.let{output->PlayerOutlinedButton({inspect(output)}){Text("Open resulting item")}}
        }
    }
}
