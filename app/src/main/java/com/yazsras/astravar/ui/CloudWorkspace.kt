package com.yazsras.astravar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable internal fun CloudAuthorityRoute(app: AstravarApp, legacy: @Composable () -> Unit) {
    val client = remember(app) { CloudCampaignClient.open(app) }
    val seq by client.changes.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CloudSnapshot?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(client, seq, retry) {
        try { selected = client.workspace(); failed = false; loaded = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; loaded = false }
    }
    if (loaded && selected == null) { legacy(); return }
    AstravarTheme {
        Surface(Modifier.fillMaxSize(), color = RebuildPalette.Canvas) {
            val snapshot = selected
            if (failed) Page {
                Text("Campaign authority could not be read on this installation.")
                PlayerButton({ retry++ }) { Text("Retry") }
            } else if (!loaded || snapshot == null) Box { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else CloudWorkspace(client, snapshot)
        }
    }
}

@Composable internal fun OpenCloudWorkspace() {
    val app = LocalContext.current.applicationContext
    val client = remember(app) { CloudCampaignClient.open(app) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PlayerOutlinedButton(onClick = {
        if (loading) return@PlayerOutlinedButton
        loading = true; message = null
        scope.launch {
            try { client.refresh(openExisting = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = cloudError(e) }
            finally { loading = false }
        }
    }, enabled = !loading) { Text("Open existing cloud campaign") }
    message?.let { Text(it, color = RebuildPalette.Muted) }
}

internal fun cloudError(e: Exception): String = when ((e as? OwnerSyncFailure)?.code) {
    "CLOUD_NOT_SELECTED","CLOUD_CONTINUITY_UNKNOWN","CLOUD_AUTHORITY_REQUIRED","CLOUD_HTTP_404" -> "A cloud-authoritative campaign has not been verified for this pairing."
    "CLOUD_DEVICE_GRANT_REVOKED" -> "This device’s cloud access was revoked."
    "CLOUD_DEVICE_GRANT_EXPIRED" -> "This device’s cloud grant expired."
    "AUTHENTICATION_REQUIRED","CLOUD_BINDING_CHANGED","CLOUD_GRANT_DENIED","CLOUD_GRANT_UNVERIFIED","CREDENTIAL_REVOKED_OR_EXPIRED" -> "Access is unavailable. Check this device’s campaign and cloud grant."
    "SNAPSHOT_CHANGED","CLOUD_REVIEW_STALE","EXPECTED_REVISION_CONFLICT" -> "The campaign changed. Refresh and review the current values."
    "CLOUD_RATE_LIMITED" -> (e as? OwnerSyncFailure)?.retryAfterSeconds?.let { "Cloud requests are paused. Retry in $it seconds using the original request." }
        ?: "Cloud requests are paused. Retry the original request after a short wait."
    "CLOUD_UNSUBMITTED_STALE" -> "The server confirmed no receipt for the original request at its closed revision. Review a new request against the current records."
    "CLOUD_PRIOR_COMMAND_UNVERIFIED" -> "A previous request is awaiting verification. Retry its original command to retrieve the outcome."
    "CLOUD_NO_TERMINAL_RECEIPT" -> "This request is pending. Its final receipt will be available after an authoritative decision."
    "COMMAND_UNKNOWN" -> "The server has no receipt for this command."
    "NETWORK_UNAVAILABLE","NETWORK_TIMEOUT","TLS_FAILURE" -> "Cloud unavailable. Any previously downloaded records may be stale."
    else -> (e as? OwnerSyncFailure)?.code?.let { "Cloud request incomplete · ${cloudLabel(it.lowercase())}" }
        ?: "The cloud response could not be verified."
}

private val categoryScopes = mapOf(
    "Character" to setOf("character","resources","combat","rolls","spells"),
    "Inventory" to setOf("inventory","economy","crafting"), "Connections" to setOf("world"), "Chronicle" to setOf("chronicle","combat")
)

@Composable internal fun CloudWorkspace(client: CloudCampaignClient, snapshot: CloudSnapshot) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clipboard = LocalClipboardManager.current
    val syncPreferences=remember(context) { CampaignSyncPreferences(context) }
    var automaticPaused by remember(syncPreferences) { mutableStateOf(syncPreferences.paused) }
    DisposableEffect(syncPreferences) {
        val listener=syncPreferences.observeControls { automaticPaused=syncPreferences.paused }
        onDispose { listener.close() }
    }
    var tab by rememberSaveable { mutableStateOf("Character") }
    var inboxFromTools by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    var actorId by rememberSaveable(snapshot.binding) { mutableStateOf<String?>(null) }
    var initialFields by remember { mutableStateOf(buildJsonObject {}) }
    var menuScope by remember { mutableStateOf<Set<String>?>(null) }
    var inspected by rememberSaveable { mutableStateOf<String?>(null) }
    var historicalId by rememberSaveable(snapshot.binding) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var connectionFailure by remember(snapshot.binding) { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    var caps by remember { mutableStateOf<JsonObject?>(null) }
    var records by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var more by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var operation by remember { mutableStateOf<JsonObject?>(null) }
    var reviewed by remember { mutableStateOf<CloudSnapshot?>(null) }
    val operations = (caps?.get("operations") as? JsonArray).orEmpty().map { it.jsonObject }
    val uncertain = rememberedUnconfirmedCommand(client, snapshot)

    suspend fun loadRecords(append: Boolean = false): Boolean {
        try {
            val page = client.records(if (append) records.lastOrNull()?.cloudText("id").orEmpty() else "", query=if (search) query else "")
            records = if (append) records + page else page
            more = page.size == 50
            pending = client.pending()
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { records = emptyList(); pending = emptyList(); more = false; verified = false; error = cloudError(e); return false }
    }
    suspend fun refresh() {
        if (busy) return
        busy = true; verified = false; count = null; error = null
        try {
            val received = client.refresh()
            val capabilities = client.capabilities()
            require(capabilities.cloudText("continuityId") == received.binding.continuity &&
                capabilities.cloudText("subject") == received.binding.subject && capabilities.getValue("epoch").jsonPrimitive.long == received.version.epoch &&
                capabilities.getValue("grantRevision").jsonPrimitive.long == received.version.grantRevision)
            caps = capabilities
            verified = loadRecords()
            if (verified) connectionFailure=null
            try { count = client.reviewCount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { count = null }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            caps = null; connectionFailure=(e as? OwnerSyncFailure)?.code ?: "UNKNOWN"
            error = cloudError(e); loadRecords()
        }
        finally { busy = false }
    }
    LaunchedEffect(snapshot.binding, lifecycle, automaticPaused) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loadRecords()
            if (automaticPaused) { verified=false; count=null; return@repeatOnLifecycle }
            refresh()
            while (true) { delay(30_000); if (!busy) refresh() }
        }
    }
    LaunchedEffect(search, query, snapshot.version, snapshot.visible) {
        if (search && snapshot.visible) loadRecords()
    }
    LaunchedEffect(snapshot.visible, snapshot.version.grantExpiresAt, snapshot.binding) {
        if (!snapshot.visible) {
            records = emptyList(); pending = emptyList(); caps = null; result = null; inspected = null; operation = null; count = null; verified = false
        } else {
            val until = try { client.validUntil() } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L }
            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
            records = emptyList(); pending = emptyList(); caps = null; result = null; inspected = null; operation = null; count = null; verified = false
            loadRecords()
        }
    }
    fun ownerPage(access:Boolean=false) {
        val route="/owner/ascension/${snapshot.binding.continuity}"+if(access)"/access" else ""
        val url = verifiedOwnerPage(snapshot.binding.origin+route,snapshot.binding.origin)
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
        catch (_: android.content.ActivityNotFoundException) { error = "A browser is required for owner review." }
    }
    fun command(work: suspend () -> JsonObject) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                result = work(); operation = null; tool = "Result"; loadRecords()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = cloudError(e); verified = false; loadRecords() }
            finally { busy = false }
        }
    }
    fun openOperation(name: String, args: JsonObject) {
        val item = operations.firstOrNull { it.cloudText("name") == name }
        if (item == null || !verified) { error = "The current grant or connection cannot perform this operation."; return }
        reviewed = snapshot; initialFields = args; operation = item; tool = null; error = null
    }

    val showDice = tab == "Character" && !search && snapshot.visible && actorId != null
    Scaffold(containerColor = RebuildPalette.Canvas, topBar = {
        RebuildAppHeader(false, search = { search = !search; query = "" }, tools = { tool = "Tools" },
            notifications = { inboxFromTools = false; tool = "Inbox" }, actionable = count)
    }, bottomBar = { RebuildPrimaryNavigation(tab) { tab=it; inspected=null; search=false; query="" } },
        floatingActionButton = {
            if (showDice) FloatingActionButton(
                onClick = { tool = "Dice" }, shape = CircleShape, containerColor = RebuildPalette.Panel,
                modifier = Modifier.semantics { contentDescription = "Open Roll Manager" }
            ) { ArcaneIcon("D20",Modifier.size(30.dp),RebuildPalette.GoldBright) }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(bottom=if(showDice) 72.dp else 0.dp)
            .padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            PlayerHeading(if (search) "Find" else tab) {
                PlayerTextButton({ scope.launch { refresh() } }, enabled=!busy) { Text("Refresh") }
            }
            Text(if (verified && snapshot.visible) "Cloud · revision ${snapshot.version.revision}" else if (snapshot.visible) "Cached · may be stale" else "Cloud access unavailable",
                color=RebuildPalette.GoldBright, style=MaterialTheme.typography.labelMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if (uncertain != null && snapshot.visible && uncertain.cloudText("operation") !in setOf("cast_spell", "complete_rest")) {
                CloudCommandRecovery(uncertain, !busy) { id -> command { client.retry(id) } }
            }
            if (snapshot.visible) {
                if (search) {
                    PlayerSearch(query,{query=it},"Find a campaign record")
                    LazyColumn { items(records,key={it.cloudText("id")}) { row ->
                        PlayerEntry(row.cloudText("title"),cloudLabel(row.cloudText("kind")),"Find",{ inspected=row.cloudText("id") })
                    }; if (more) item { PlayerTextButton({ scope.launch { loadRecords(true) } }) { Text("Load more") } } }
                } else if (tab == "Character") {
                    CloudActorWorkspace(client,snapshot,verified,actorId,{ actorId=it },::openOperation,{ inspected=it },
                        { name,args -> command { client.submit(name,args,snapshot) } })
                } else key(tab,snapshot.binding) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        CloudRecordScreen(tab,actorId,client,snapshot,verified,::openOperation,{ inspected=it })
                        if (operations.any { it.cloudText("scope") in categoryScopes.getValue(tab) })
                            PlayerOutlinedButton({ menuScope=categoryScopes[tab];tool="Operations" }, enabled=verified && !busy) { Text("Manage $tab") }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
    val inspectId = inspected
    if (inspectId != null && snapshot.visible) {
        CloudCanonicalRecordInspector(client, snapshot, inspectId, verified, historicalId == null, { inspected = null }, { historicalId = it })
    }
    if (snapshot.visible) historicalId?.let { id ->
        CloudHistoricalReceipt(client, snapshot, id) { historicalId = null }
    }
    when(tool) {
        "Tools" -> PlayerSheet("Owner connection", { tool=null }) {
            CloudConnectionStatus(snapshot,verified,automaticPaused,connectionFailure,count,result?.cloudText("status"))
            PlayerTextButton({ scope.launch { refresh() } },enabled=!busy) { Text("Check connection now") }
            PlayerOutlinedButton({ inboxFromTools = true; tool = "Inbox" }) { Text("Pending owner reviews") }
            PlayerButton({ ownerPage(access=true) }) { Text("Manage campaign access") }
            PlayerTextButton({tool="Settings"}) {Text("Campaign settings")}
            PlayerTextButton({tool="Roster"}) {Text("Campaign roster")}
            PlayerTextButton({ tool="Device" }) { Text("Manage device connection") }
            PlayerTextButton({ tool="Archive" },enabled=verified && !busy) { Text("Archive manager") }
            PlayerTextButton({ tool="Recovery" }) { Text("Local backup and recovery") }
            for (utility in listOf("ChatGPT Prompts", "Appearance & feedback", "Help & diagnostics")) {
                PlayerTextButton({ tool = utility }) { Text(utility) }
            }
            PlayerTextButton({ tool="History" }) { Text("Check submitted commands") }
            PlayerTextButton({ menuScope=null; tool="Operations" },enabled=verified && !busy) { Text("All permitted operations") }
        }
        "ChatGPT Prompts", "Appearance & feedback", "Help & diagnostics" -> CloudDeviceTools(requireNotNull(tool)) { tool = "Tools" }
        "Roster" -> if (snapshot.visible) CloudRoster(client,snapshot,verified && !busy,::openOperation){tool=null}
        "Settings" -> PlayerSheet("Campaign settings",{tool=null}) {
            var metadata by remember { mutableStateOf<JsonObject?>(null) }
            var loaded by remember { mutableStateOf(false) }
            var issue by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(snapshot.version,verified,snapshot.visible) {
                metadata=null;loaded=false;issue=null
                try {
                    val record=if(verified)client.projection(snapshot,"settings")["profile"] as? JsonObject else client.record(snapshot.binding.continuity)
                    metadata=record?.takeIf { it.cloudText("kind")=="continuity_profile" };loaded=true
                }catch(e:CancellationException){throw e}catch(e:Exception){issue=cloudError(e)}
            }
            val value=metadata
            if(value!=null){
                Text(value.cloudText("title"),style=MaterialTheme.typography.titleMedium)
                Text(value["data"]?.jsonObject?.get("notes")?.jsonPrimitive?.contentOrNull ?: "Notes unknown")
                PlayerDisclosure("Source"){RecordFields(value.getValue("data"))}
            }else if(loaded)Text("No campaign settings available in this view.")
            issue?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            PlayerButton({ownerPage(access=true)}){Text("Manage settings in owner account")}
        }
        "Archive" -> if(snapshot.visible)CloudArchiveManager(client,snapshot,verified,::openOperation){tool=null}
        "Device" -> RebuildToolSheet("Device connection",{tool=null}) { CampaignConnectionPage() }
        "Recovery" -> RebuildToolSheet("Local recovery store",{tool=null}) {
            CloudRecoveryTools()
        }
        "Dice" -> actorId?.let { if (snapshot.visible) CloudRollManager(client,snapshot,it,verified) { tool=null } }
        "Inbox" -> CloudApprovalCenter(client,snapshot,verified, onCount = { count = it }) { tool = if (inboxFromTools) "Tools" else null }
        "Operations" -> PlayerSheet("Campaign operations",{tool=null}) {
            operations.filter { menuScope==null || it.cloudText("scope") in menuScope.orEmpty() }.sortedBy { it.cloudText("name") }.forEach { op ->
                PlayerTextButton({ openOperation(op.cloudText("name"),buildJsonObject {}) },enabled=verified && !busy) {
                    Text(cloudLabel(op.cloudText("name")))
                }
            }
        }
        "History" -> if (historicalId == null) PlayerSheet("Submitted commands",{tool=null}) {
            var lookup by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(lookup, { lookup = it.take(128) }, label = { Text("Command ID") }, singleLine = true)
            PlayerTextButton({ historicalId = lookup }, enabled = verified && cloudIdentifier.matches(lookup)) {
                Text("View historical receipt")
            }
            if (pending.isEmpty()) Text("No locally pending commands.")
            pending.forEach { entry ->
                Text(cloudLabel(entry.cloudText("operation")))
                PlayerTextButton({ command { client.verify(entry.cloudText("commandId")) } },enabled=!busy) { Text("Check receipt") }
                PlayerTextButton({ command { client.retry(entry.cloudText("commandId")) } },enabled=!busy) { Text("Retry original command") }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
        "Result" -> result?.let { r -> PlayerSheet("Transaction result",{ tool=null }) {
            Text(cloudLabel(r.cloudText("status").lowercase()))
            val receipt=r["receipt"] as? JsonObject
            if(receipt!=null && r.cloudText("status")=="COMMITTED") CreatedEntityArtwork(client,snapshot,verified,receipt)
            val outcome=receipt?.get("outcome") as? JsonObject
            val lines = outcome?.let(::namedCloudResults).orEmpty()
            if (lines.isEmpty()) {
                RecordFields(outcome ?: r)
            } else {
                for (line in lines) {
                    val copyResult = Modifier.fillMaxWidth()
                        .clickable(onClickLabel = "Copy roll result") { clipboard.setText(AnnotatedString(line)) }
                        .padding(vertical = 12.dp)
                    Text(line, modifier = copyResult, style = MaterialTheme.typography.titleMedium)
                }
            }
            outcome?.let { CloudAttackRiderStatus(it) }
            if(outcome!=null && "appliedDamage" in outcome && outcome["appliedDamage"]==JsonNull)
                Text("Potential damage · awaiting target resolution")
            PlayerButton({ clipboard.setText(AnnotatedString(if(lines.isEmpty()) (outcome ?: r).toString() else lines.joinToString("\n"))) }) {
                Text(if(lines.size>1)"Copy All" else "Copy result")
            }
            receipt?.let { PlayerDisclosure("Transaction receipt"){RecordFields(it)} }
        } }
    }
    val target=operation
    val baseline=reviewed
    if (target!=null && baseline!=null)
        CloudCommandForm(target,busy,{ operation=null },{ args,id -> command { client.submit(target.cloudText("name"),args,baseline,id) } },error,initialFields)
}

internal fun namedCloudResults(outcome:JsonObject):List<String> {
    val direct=(outcome["text"] as? JsonPrimitive)?.contentOrNull
    if(!direct.isNullOrBlank())return listOf(direct)
    return listOf("hit","damage","healing","rolls","result","results","components").flatMap { key->
        when(val values=outcome[key]){
            is JsonObject -> namedCloudResults(values)
            is JsonArray -> values.mapNotNull { it as? JsonObject }.flatMap(::namedCloudResults)
            else -> emptyList()
        }
    }
}

@Composable internal fun RecordFields(value: JsonElement) {
    when (value) {
        is JsonObject -> value.forEach { (key,v) ->
            if (v is JsonPrimitive) Text("${cloudLabel(key)} · ${if (v == JsonNull) "Unknown" else v.content}")
            else PlayerDisclosure(cloudLabel(key)) { RecordFields(v) }
        }
        is JsonArray -> value.forEach { RecordFields(it) }
        is JsonPrimitive -> Text(if (value == JsonNull) "Unknown" else value.content)
    }
}
