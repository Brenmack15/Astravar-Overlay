package com.yazsras.astravar.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URI

private fun JsonObject.reviewText(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.reviewRows(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().map { it.jsonObject }

internal fun exactOwnerReviewPage(binding: CloudBinding, commandId: String, digest: String, intent: String): URI {
    require(cloudIdentifier.matches(binding.continuity) && intent in setOf("verify", "refuse"))
    approvalViewSuffix(commandId, digest)
    return verifiedOwnerPage("${binding.origin}/owner/ascension/${binding.continuity}#request=$commandId&digest=$digest&intent=$intent", binding.origin)
}
private fun readiness(code: String?) = when (code) {
    "READY" -> "Ready for owner review"
    "REVIEW_BASELINE_CHANGED" -> "Campaign state changed since submission"
    "SUBMITTER_GRANT_CHANGED" -> "The requester's permissions changed"
    "SUBMITTER_GRANT_INACTIVE" -> "The requester's access expired or was revoked"
    else -> "Validation requires review"
}

internal data class ReviewFieldDelta(val path: String, val submitted: JsonElement?, val current: JsonElement?, val proposed: JsonElement?)

internal fun reviewFieldDeltas(submitted: JsonElement?, current: JsonElement?, proposed: JsonElement?): List<ReviewFieldDelta> {
    val changed = mutableListOf<ReviewFieldDelta>()
    fun visit(path: String, a: JsonElement?, b: JsonElement?, c: JsonElement?) {
        if (a == b && a == c) return
        val values = listOfNotNull(a, b, c)
        if (values.isNotEmpty() && values.all { it is JsonObject }) {
            val keys = values.flatMap { it.jsonObject.keys }.distinct().sorted()
            if (keys.isNotEmpty()) {
                keys.forEach { k -> visit(if (path.isEmpty()) k else "$path.$k",
                    (a as? JsonObject)?.get(k), (b as? JsonObject)?.get(k), (c as? JsonObject)?.get(k)) }
                return
            }
        }
        changed += ReviewFieldDelta(path, a, b, c)
    }
    visit("", submitted, current, proposed)
    return changed
}

private fun fieldValue(value: JsonElement?): String = when(value) {
    null -> "Absent"
    JsonNull -> "Unknown"
    is JsonPrimitive -> value.content
    else -> value.toString()
}

@Composable
internal fun CloudApprovalCenter(client: CloudCampaignClient, snapshot: CloudSnapshot, online: Boolean, close: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable(snapshot.binding.continuity, snapshot.binding.subject) { mutableStateOf<String?>(null) }
    var digest by rememberSaveable(snapshot.binding.continuity, snapshot.binding.subject) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val callback = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycle.addObserver(callback)
        onDispose { lifecycle.removeObserver(callback) }
    }
    fun back() { if (selected == null) close() else { selected = null; digest = null; reload++ } }
    PlayerSheet(if (selected == null) "Notification Center" else "Review request", ::back, scrollResetKey = selected) {
        if (!snapshot.visible || !online) {
            Text("Reconnect with an authorized owner device to view current requests.")
            return@PlayerSheet
        }
        val command = selected
        val exact = digest
        if (command != null && exact != null) {
            key(snapshot.binding, snapshot.version, command, exact, reload) {
                CloudRequestDetail(client, snapshot, command, exact, reload = { reload++ }, back = ::back)
            }
        } else {
            key(snapshot.binding, snapshot.version, reload) {
                CloudRequestList(client, snapshot, retry = { reload++ }) { id, hash -> selected = id; digest = hash }
            }
        }
    }
}

@Composable
private fun CloudRequestList(client: CloudCampaignClient, snapshot: CloudSnapshot, retry: () -> Unit, select: (String, String) -> Unit) {
    var after by remember { mutableStateOf<String?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var actionable by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(after) {
        waiting = true
        try {
            val suffix = "review-inbox?limit=20" + (after?.let { "&after=$it" } ?: "")
            require(validApprovalViewSuffix(suffix))
            val page = client.projection(snapshot, suffix)
            actionable = page.getValue("actionableCount").jsonPrimitive.int
            count = page.getValue("pendingCount").jsonPrimitive.int
            require(actionable in 0..count)
            next = page.reviewText("nextAfter")?.also { require(cloudIdentifier.matches(it)) }
            val found = page.reviewRows("requests")
            found.forEach { approvalViewSuffix(it.cloudText("commandId"), it.cloudText("digest")) }
            rows = (rows + found).distinctBy { it.cloudText("commandId") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { rows = emptyList(); next = null; error = cloudError(e) }
        finally { waiting = false }
    }
    if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error != null) {
        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        PlayerOutlinedButton(retry) { Text("Refresh requests") }
        return
    }
    if (!waiting || rows.isNotEmpty()) Text(if (count == 0) "No pending owner requests." else "$actionable ready for approval · ${count - actionable} require reconciliation")
    rows.forEach { row ->
        PlayerOutlinedButton({ select(row.cloudText("commandId"), row.cloudText("digest")) }, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(cloudLabel(row.cloudText("operation")), style = MaterialTheme.typography.titleSmall)
                Text(readiness(row.reviewText("readiness")), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    next?.let { cursor -> PlayerOutlinedButton({ after = cursor }, enabled = !waiting) { Text("More requests") } }
}

@Composable
private fun CloudRequestDetail(
    client: CloudCampaignClient, snapshot: CloudSnapshot, id: String, digest: String, reload: () -> Unit, back: () -> Unit
) {
    val context = LocalContext.current
    var response by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var technical by remember { mutableStateOf(false) }
    LaunchedEffect(client, snapshot, id, digest) {
        try {
            val approved = client.projection(snapshot, approvalViewSuffix(id, digest))
            require(approved.cloudText("commandId") == id && approved.cloudText("digest") == digest)
            response = approved
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { response = null; error = cloudError(e) }
    }
    fun owner(intent: String) {
        val url = exactOwnerReviewPage(snapshot.binding, id, digest, intent)
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
        catch (_: ActivityNotFoundException) { error = "A browser is required for the authenticated owner decision." }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error); PlayerOutlinedButton(reload) { Text("Refresh request") } }
    val value = response
    if (value == null) {
        if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(back) { Text("Back to requests") }
        return
    }
    Text(cloudLabel(value.cloudText("operation")), style = MaterialTheme.typography.titleLarge)
    if (value.cloudText("status") != "PENDING_APPROVAL") {
        Text(cloudLabel(value.cloudText("status").lowercase()), style = MaterialTheme.typography.titleMedium)
        value.reviewText("reason")?.let { Text(cloudLabel(it.lowercase())) }
        val receipt = value.getValue("receipt").jsonObject
        Text("Authoritative revision ${receipt.getValue("revision")}")
        Text("Transaction ${receipt.cloudText("transactionId")}", style = MaterialTheme.typography.labelSmall)
        TextButton(back) { Text("Back to requests") }
        return
    }
    val ready = value.cloudText("readiness") == "READY"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerButton({ owner("verify") }, Modifier.weight(1f), enabled = ready) { Text("Verify") }
        PlayerOutlinedButton({ owner("refuse") }, Modifier.weight(1f)) { Text("Refuse") }
    }
    Text("Confirm the decision for this request in your owner account.", style = MaterialTheme.typography.bodySmall)
    Text(readiness(value.reviewText("readiness")), style = MaterialTheme.typography.titleSmall)
    Text(if (value.reviewText("reviewReason") == "OWNER_CONFIRMATION_REQUIRED") "This operation requires explicit owner confirmation."
        else "The requesting party's Auto-Sync settings require review for this change.")
    val command = value.getValue("command").jsonObject
    Text("Requested by ${value.cloudText("subject")} · Submitted at revision ${command.getValue("expectedRevision")} · Current revision ${value.getValue("revision")}")
    if (!ready) PlayerOutlinedButton({ owner("verify") }) { Text("Resolve request status") }
    val plan = value.getValue("plan").jsonObject
    val current = value.getValue("current").jsonObject
    plan.reviewRows("changes").forEach { change ->
        val before = change["before"] as? JsonObject
        val after = change["after"] as? JsonObject
        val target = after ?: before ?: return@forEach
        Text(target.cloudText("title"), style = MaterialTheme.typography.titleMedium)
        Text(cloudLabel(target.cloudText("kind")), style = MaterialTheme.typography.labelMedium)
        val now = current[target.cloudText("id")] as? JsonObject
        if (now == null) Text("No current record.")
        if (after == null) Text("Proposed permanent deletion")
        remember(before, now, after) { reviewFieldDeltas(before, now, after) }.forEach { field ->
            Text(field.path.removePrefix("data.").split(".").joinToString(" · ") { cloudLabel(it) }, style = MaterialTheme.typography.titleSmall)
            if (field.submitted != field.current) Text("At submission · " + fieldValue(field.submitted))
            Text("Current · " + fieldValue(field.current))
            Text("Proposed · " + fieldValue(field.proposed))
        }
    }
    Text("Costs and outcome", style = MaterialTheme.typography.titleMedium)
    RecordFields(plan.getValue("result"))
    val links = plan.getValue("edges").jsonArray
    if (links.isNotEmpty()) { Text("Affected relationships", style = MaterialTheme.typography.titleMedium); RecordFields(links) }
    TextButton({ technical = !technical }) { Text(if (technical) "Hide request details" else "Source and request details") }
    if (technical) {
        Text("Request $id"); Text("Digest $digest")
        RecordFields(command.getValue("args"))
        RecordFields(plan.getValue("access"))
    }
    TextButton(back) { Text("Back to requests") }
}
