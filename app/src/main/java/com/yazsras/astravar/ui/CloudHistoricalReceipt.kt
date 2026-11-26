package com.yazsras.astravar.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@Composable internal fun CloudReceiptLink(client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean, commandId: String?) {
    if (commandId == null || !cloudIdentifier.matches(commandId)) return
    var open by remember(snapshot.binding, commandId) { mutableStateOf(false) }
    PlayerTextButton({ open = true }, enabled = online && snapshot.visible) { Text("View transaction receipt") }
    if (open && snapshot.visible) CloudHistoricalReceipt(client, snapshot, commandId) { open = false }
}

@Composable internal fun CloudHistoricalReceipt(client: CloudCampaignClient, snapshot: CloudSnapshot, id: String, close: () -> Unit) {
    val context=LocalContext.current
    var evidence by remember(snapshot.binding, snapshot.version, id) { mutableStateOf<JsonObject?>(null) }
    var error by remember(snapshot.binding, snapshot.version, id) { mutableStateOf<String?>(null) }
    LaunchedEffect(client, snapshot, id) {
        try { evidence = client.historicalReceipt(snapshot, id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = cloudError(e) }
    }
    PlayerSheet("Historical receipt", close) {
        Text("Command $id", style = MaterialTheme.typography.labelMedium)
        evidence?.let { observed ->
            val receipt = observed.getValue("receipt").jsonObject
            Text(cloudLabel(observed.cloudText("status").lowercase()), style = MaterialTheme.typography.titleMedium)
            Text("Recorded at authority epoch ${observed.getValue("epoch")} · revision ${observed.getValue("revision")}")
            Text("Current authority epoch ${snapshot.version.epoch}")
            if(observed.cloudText("status")=="CONFLICT" && receipt["reason"]?.jsonPrimitive?.contentOrNull=="REVIEW_BASELINE_CHANGED" &&
                observed["epoch"]?.jsonPrimitive?.longOrNull==snapshot.version.epoch) {
                PlayerOutlinedButton({
                    val destination=exactOwnerReviewPage(snapshot.binding,id,observed.cloudText("digest"),"replace")
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination.toString()))) }
                    catch(_:ActivityNotFoundException){error="Open the owner account to review this conflict."}
                }) { Text("Review a linked replacement") }
            }
            receipt["outcome"]?.let { RecordFields(it) }
            PlayerDisclosure("Receipt and recorded changes") { RecordFields(receipt) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (evidence == null && error == null) Text("Reading the recorded transaction…")
    }
}
