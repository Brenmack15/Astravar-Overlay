package com.yazsras.astravar.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yazsras.astravar.*
import com.yazsras.astravar.data.RemoteStatProtocol
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

@Suppress("DEPRECATION")
private fun installedCertificateFingerprints(context: android.content.Context): String = runCatching {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners.orEmpty()
    } else context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures.orEmpty()
    signatures.map { signature -> java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) } }.sorted().joinToString("\n").ifBlank { "Unavailable" }
}.getOrDefault("Unavailable")

@Composable fun AstravarScreen(app: AstravarApp,startOverlay: ()->Unit,grantOverlay: ()->Unit,stopOverlay: ()->Unit, ownerConnectionRequest: Long = 0L) {
    val s by app.repository.state.collectAsState()
    val error by app.repository.error.collectAsState()
    val history by app.repository.history.collectAsState()
    val prefs by app.preferences.flow.collectAsState(initial=com.yazsras.astravar.data.OverlayPrefs())
    val scope=rememberCoroutineScope()
    val actions=remember { Actions(app.repository,scope) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf("Armor")}
    var configSection by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Settings") }
    var campaignPage by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    var tool by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(ownerConnectionRequest) {
        if (ownerConnectionRequest > 0) { tab = "Config"; configSection = "Settings"; tool = "Owner connection" }
    }
    var elementPicker by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(false)}
    val screenStates=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val snackbar=remember {SnackbarHostState()}
    val remoteSettingsFlow = remember(app) { if (BuildConfig.DEBUG) app.remoteStats.settings else flowOf(null) }
    val remoteCommandsFlow = remember(app) { if (BuildConfig.DEBUG) app.remoteStats.commands else flowOf(emptyList()) }
    val remoteSettings by remoteSettingsFlow.collectAsState(initial = null)
    val remoteCommands by remoteCommandsFlow.collectAsState(initial = emptyList())
    val characterAbilities by app.campaignLedger.records.characterAbilities.collectAsState(initial = emptyList())
    val remoteTestEnabled = BuildConfig.DEBUG && remoteSettings?.enabled == true
    var remoteReviewId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var deferredRemoteCommands by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(listOf<String>()) }
    var remoteReviewBusy by remember { mutableStateOf(false) }
    var remoteReviewError by remember { mutableStateOf("") }
    var armorModalOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val waitingRemote = remoteCommands.filter { it.status in listOf("PENDING", "CONFLICT") }
    val reviewCommand = remoteCommands.firstOrNull { it.commandId == remoteReviewId }
    LaunchedEffect(remoteTestEnabled, lifecycleOwner) {
        if (remoteTestEnabled) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try { app.remoteStats.refresh() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Durable status and inbox remain available while offline. */ }
                delay(15_000)
            }
        }
    }
    LaunchedEffect(remoteTestEnabled, remoteCommands, tab, tool, elementPicker, armorModalOpen, remoteReviewId) {
        if (!remoteTestEnabled) remoteReviewId = null
        else if (reviewCommand?.status in listOf("APPLIED", "REJECTED")) remoteReviewId = null
        else if (remoteReviewId == null && tool == null && !elementPicker &&
            (tab == "Config" || (tab == "Armor" && !armorModalOpen))) {
            remoteReviewId = waitingRemote.firstOrNull { it.commandId !in deferredRemoteCommands }?.commandId
        }
    }
    LaunchedEffect(remoteReviewId) { remoteReviewError = "" }
    fun remoteDecision(action: suspend () -> Unit) {
        if (remoteReviewBusy || !remoteTestEnabled) return
        remoteReviewBusy = true; remoteReviewError = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { remoteReviewError = e.message ?: "Unable to complete this decision. Your proposal is retained." }
            finally { remoteReviewBusy = false }
        }
    }
    LaunchedEffect(actions.message) {if(actions.message.isNotBlank()) {snackbar.showSnackbar(actions.message);actions.message=""}}
    androidx.activity.compose.BackHandler(enabled=tab=="Campaign" && campaignPage!=null && tool==null) {campaignPage=null}
    AstravarTheme {
        Scaffold(containerColor=RebuildPalette.Canvas,
            snackbarHost={SnackbarHost(snackbar)},
            topBar={RebuildAppHeader(s?.demo==true)},
            bottomBar={RebuildPrimaryNavigation(tab) {tab=it}}
        ) {insets->
            Column(Modifier.padding(insets).imePadding()) {
                if(error!=null) Text(error!!,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
                val current=s
                if(current==null) {LinearProgressIndicator(Modifier.fillMaxWidth());return@Column}
                if(actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (remoteTestEnabled) Row(Modifier.fillMaxWidth().background(RebuildPalette.Active).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if(tab=="Armor") "Disposable profile" else "Remote test on", Modifier.weight(1f), color = RebuildPalette.Ice,
                        style = MaterialTheme.typography.labelLarge)
                    PlayerTextButton(onClick = {
                        val next = waitingRemote.firstOrNull()
                        if (next == null) tool = "Remote Updates"
                        else { tool = null; remoteReviewId = next.commandId }
                    }) { Text(if (waitingRemote.isEmpty()) "Remote Updates" else "Review (${waitingRemote.size})") }
                }
                screenStates.SaveableStateProvider(tab) {
                    when(tab) {
                        "Armor" -> ArmorOverviewV2(current,app.campaignLedger,
                            combat={tab="Combat"},systems={tool="Spell reservoir"},
                            eek={tab="Campaign";campaignPage="Eek"},inventory={tab="Campaign";campaignPage="Inventory"},
                            elements=if(prefs.mode==Ability.ARCANE || prefs.mode==Ability.SPELL) emptyList() else prefs.elements,
                            selectElement={elementPicker=true},
                            characterProfileId=if(remoteTestEnabled) RemoteStatProtocol.PROFILE_ID else "local-player",
                            onOverlayVisibilityChanged={armorModalOpen=it},
                            openReferences={tab="Campaign";campaignPage="Reference Library"})
                        "Combat" -> if(current.onboarded) Dashboard(current,actions,startOverlay,grantOverlay,stopOverlay,app.preferences,prefs)
                            else WeaponSetupV2(current,actions,{tool="Rules & profile"},{tool="Weapon backup"})
                        "Party" -> if (remoteTestEnabled) Page { Text("Party records are isolated from the disposable Remote Stat Test. Disable Remote Updates to edit party records.") }
                            else PartyPageV2(app.campaignLedger, current,
                                openReferences={tab="Campaign";campaignPage="Reference Library"}) {tab="Campaign";campaignPage="Inventory"}
                        "Campaign" -> {
                            val page=campaignPage
                            if(page==null) RebuildCampaignHub {campaignPage=it}
                            else Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    PlayerTextButton(onClick={campaignPage=null}) {Text("‹ Campaign")}
                                    Spacer(Modifier.weight(1f))
                                }
                                Box(Modifier.weight(1f)) {screenStates.SaveableStateProvider("campaign-$page") {
                                    when(page) {
                                        "Inventory" -> CampaignLedgerPage(app.campaignLedger) {campaignPage="Reference Library"}
                                        "Inventions" -> InventionsPageV2(app.campaignLedger)
                                        "Checkpoints" -> CampaignCheckpointPanel(app.campaignLedger)
                                        "Eek" -> EekPageV2(app.campaignLedger)
                                        "Reference Library" -> CampaignReferenceLibraryV21(app.campaignLedger)
                                        "Campaign import" -> CampaignArchiveImportV21(app.campaignLedger)
                                        else -> ConnectionsPageV2(app.campaignLedger)
                                    }
                                }}
                            }
                        }
                        else -> Column(Modifier.fillMaxSize()) {
                            V2SectionTabs(listOf("Settings", "ChatGPT"), configSection) { configSection = it }
                            if (configSection == "ChatGPT") ChatGptPromptsPage(app.campaignLedger.prompts)
                            else RebuildConfigHub {tool=it}
                        }
                    }
                }
                tool?.let {name->RebuildToolSheet(name,{tool=null}) {
                    when(name) {
                        "Fictional sync" -> SandboxSyncPage(app.sandboxSync)
                        "Remote Updates" -> if(BuildConfig.DEBUG) RemoteUpdatesPage(app.remoteStats, characterAbilities) {id->
                            tool=null;remoteReviewId=id
                        }
                        "Owner connection" -> CampaignConnectionPage()
                        "Campaign backup" -> CampaignBackupPageV2(app.campaignLedger)
                        "Campaign import" -> CampaignArchiveImportV21(app.campaignLedger)
                        "Weapon backup" -> Page {BackupPage(current,actions)}
                        "Rules & profile" -> RulesPage(current,actions,if(prefs.mode in listOf(Ability.ARCANE,Ability.SPELL)) emptyList() else prefs.elements)
                        "Resources & recovery" -> ResourcesPage(current,history,actions)
                        "Spell reservoir" -> CoresPage(current,actions)
                        "ChatGPT Prompts", "ChatGPT toolkit" -> ChatGptPromptsPage(app.campaignLedger.prompts)
                        "Spell library" -> PayloadsPage(current,actions,app.preferences,prefs.selectedPayloads)
                        "Roll history" -> CombatHistoryPage(current)
                        "Overlay & appearance" -> SettingsPage(app,current,actions,startOverlay,grantOverlay,stopOverlay)
                        else -> HelpPage()
                    }
                }}
            }
        }
        if(elementPicker && s!=null) ElementWheelV2(s!!.profile,prefs.elements,{elementPicker=false}) {type->
            actions.run("") {
                val blast=prefs.mode in listOf(Ability.DISCHARGE,Ability.SHATTER)
                if(type==DamageType.FORCE) {app.preferences.elements(emptyList());if(!blast) app.preferences.mode(Ability.ARCANE)}
                else {
                    app.preferences.elements(elementSelectionAfterChoice(s!!.profile,prefs.elements,type))
                    if(!blast) app.preferences.mode(Ability.ELEMENTAL)
                }
            };elementPicker=false
        }
        if (remoteTestEnabled && reviewCommand != null && reviewCommand.status in listOf("PENDING", "APPLYING", "CONFLICT")) {
            val command = reviewCommand
            RemoteStatReviewOverlay(command,
                current=characterAbilities.firstOrNull {it.profileId==RemoteStatProtocol.PROFILE_ID && it.key==command.ability},
                enabled=remoteTestEnabled,busy=remoteReviewBusy,error=remoteReviewError,
                onApply={remoteDecision {app.remoteStats.apply(command.commandId)}},
                onReject={remoteDecision {app.remoteStats.reject(command.commandId)}},
                onDismiss={deferredRemoteCommands=deferredRemoteCommands+command.commandId;remoteReviewId=null})
        }
    }
}

@Composable fun InventoryTabs(selected: String,change: (String)->Unit) {
    Box(Modifier.fillMaxWidth().instrumentSurface().padding(horizontal=16.dp,vertical=8.dp)) {
        InstrumentTabs(listOf("Cores","Payloads"),selected,change)
    }
}

@Composable fun ControlRoomPage(open: (String)->Unit) {
    Page {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            InstrumentMedallion("More",Modifier.size(54.dp),Brass)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("ASTRAVAR SYSTEMS",style=MaterialTheme.typography.labelSmall,color=Brass)
                Text("Control room",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurface)
                Text("Tune Astravar. Preserve your progress.",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
        SectionCaption("WEAPON SYSTEMS")
        ControlRoomLink("Weapon resources & recovery","Action limits, core recovery and spell slots","Core") {open("Resources")}
        ControlRoomLink("Rules & upgrades","Damage sliders, profile and approved upgrades","Weapon") {open("Rules")}
        ControlRoomLink("Overlay & appearance","Permissions, panel size and artwork","Settings") {open("Settings")}
        if (com.yazsras.astravar.BuildConfig.DEBUG) {
            SectionCaption("CAMPAIGN RECORDS")
            ControlRoomLink("Campaign inventory · local beta", "Item profiles, artwork and research history", "History") { open("Campaign Inventory") }
        }
        SectionCaption("SAFEKEEPING & SUPPORT")
        ControlRoomLink("Backup & restore","Export or restore your weapon data","History") {open("Backup")}
        ControlRoomLink("Help & diagnostics","Device information and guidance","Help") {open("Help")}
    }
}

@Composable fun HelpPage() {
    val c=LocalContext.current
    val certificates = remember(c) { installedCertificateFingerprints(c) }
    Page {
        Section("App version") {
            Text("Astravar ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}")
            Text("Developer · Yazsras Studios")
            Text("Installed package · ${c.packageName}")
            Text("Installed signing certificate SHA-256")
            androidx.compose.foundation.text.selection.SelectionContainer { Text(certificates, style=MaterialTheme.typography.bodySmall) }
            Text("Future updates require this package identity, a compatible signing certificate and an increased build number. Export backups before installing an update.", style=MaterialTheme.typography.bodySmall)
        }
        Section("Device diagnostics") {
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nBuild ${Build.DISPLAY}\nPackage ${c.packageName}\nDisplay-over-apps permission: ${Settings.canDrawOverlays(c)}")
        }
        Section("Dice & your character sheet") {
            Text("Astravar does not read, control, or synchronize D&D Beyond. Tap Roll to generate attack and damage dice, or choose external dice. Individual faces are saved and never rerolled on restart. The DM determines ordinary hits; potential damage is clearly labeled. Natural 20s are criticals and natural 1s automatically miss. Reconcile your sheet manually. Damage averages are estimates, never rolled results. The optional public connectivity lab sends fixed fictional test messages only. Your local campaign records are not uploaded. No analytics, account, accessibility access, or screen capture.")
            Text("The floating crystal is a genuine Android overlay. Some apps and protected screens hide overlays. Start sessions while Astravar is visible; sessions do not restart at boot or after you stop them. Locking the phone hides the panel; resume from its notification.")
            Text("If Samsung pauses a session, first reopen Astravar and start it again. Check the app's battery settings only if sessions repeatedly stop; no blanket security changes are required.")
            Text("Unofficial tabletop companion. Not affiliated with or endorsed by Wizards of the Coast, D&D Beyond, or Samsung.")
        }
    }
}
