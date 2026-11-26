package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import kotlinx.serialization.json.*

internal fun cloudPartyMember(row: JsonObject): Boolean {
    val data = row["data"] as? JsonObject ?: return false
    val member = data["membership"] as? JsonObject
    if (member == null) return data["role"]?.jsonPrimitive?.contentOrNull in setOf("player","companion")
    return member["participation"]?.jsonPrimitive?.contentOrNull == "active" && member["inParty"]?.jsonPrimitive?.booleanOrNull == true
}

@Composable internal fun CloudRoster(
    client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean,
    open: (String,JsonObject)->Unit, close: ()->Unit
) {
    var filter by rememberSaveable { mutableStateOf("Party") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember(snapshot.binding,snapshot.version) { mutableStateOf<JsonObject?>(null) }
    PlayerSheet("Campaign roster",close) {
        val row = selected
        if (row == null) {
            PlayerTabs(listOf("Party","All actors"),filter,{filter=it})
            OutlinedTextField(query,{query=it},label={Text("Find actor")},singleLine=true)
            CachedRecordList(client,snapshot,setOf("actor"),null,query,allowed={filter!="Party" || cloudPartyMember(it)}) {selected=it}
        } else {
            val data=row.getValue("data").jsonObject
            val membership=data["membership"] as? JsonObject
            val status=membership?.get("participation")?.jsonPrimitive?.contentOrNull ?: "active"
            val party=cloudPartyMember(row)
            Text(row.getValue("title").jsonPrimitive.content,style=MaterialTheme.typography.titleMedium)
            Text("Campaign participation · $status")
            Text(if (party) "Party member" else "Outside the party")
            membership?.get("source")?.jsonPrimitive?.contentOrNull?.let { Text(it) }
            membership?.get("worldTime")?.jsonPrimitive?.contentOrNull?.let { Text("In-world time · $it") }
            PlayerOutlinedButton({
                open("set_actor_membership",buildJsonObject {
                    put("actorId",row.getValue("id"));put("participation",status);put("inParty",party);put("worldTime",JsonNull)
                })
            },enabled=online) {Text("Review membership change")}
            PlayerTextButton({selected=null}){Text("Back to roster")}
        }
    }
}
