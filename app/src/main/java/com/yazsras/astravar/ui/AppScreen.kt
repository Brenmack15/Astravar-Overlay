package com.yazsras.astravar.ui

import android.os.Build
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
import com.yazsras.astravar.data.ReforgeRepository
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
    val storageStatus by app.genesisStorage.status.collectAsState()
    if (storageStatus != com.yazsras.astravar.data.GenesisStorageStatus.Ready) {
        AstravarTheme {
            Surface(Modifier.fillMaxSize(), color = RebuildPalette.Canvas) { GenesisStorageRecovery(app, storageStatus) }
        }
        return
    }
    val s by app.repository.state.collectAsState()
    val error by app.repository.error.collectAsState()
    val history by app.repository.history.collectAsState()
    val prefs by app.preferences.flow.collectAsState(initial=com.yazsras.astravar.data.OverlayPrefs())
    val scope=rememberCoroutineScope()
    val actions=remember { Actions(app.repository,scope) }
    val localContext = LocalContext.current.applicationContext
    val playableRepository = remember(localContext) { ReforgeRepository.open(localContext) }
    val playerPreferences = remember(localContext) { com.yazsras.astravar.data.ReforgePreferences.open(localContext) }
    val campaignSelection by remember(playerPreferences) { playerPreferences.selectedCampaignId.map { true to it } }
        .collectAsState(initial = false to null)
    val connected = GenesisConnectedEnvironment(app.genesisStorage.authority, app.campaignLedger,
        playableRepository, playerPreferences, campaignSelection.second, campaignSelection.first)
    var tab by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf("Character")}
    LaunchedEffect(Unit) {
        tab = when (tab) {
            "Party" -> "Companions"
            "Armor", "Combat", "More", "Config" -> "Character"
            in rebuildDestinations -> tab
            else -> "Character"
        }
    }
    var configSection by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Settings") }
    var campaignPage by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    var tool by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    var requestedInventory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("My gear") }
    var inventoryRoute by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var companionPlayId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var companionPlaySection by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Sheet") }
    var searchProfileId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var searchSection by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Sheet") }
    var searchDetail by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var searchRouteToken by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var searchIsCompanion by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searchCampaignItemId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var encounterReturn by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val searchRoute = searchProfileId?.let { GenesisCharacterRoute(it, searchSection, searchDetail, searchRouteToken) }
    fun openSearchDestination(destination: ReforgeSearchDestination) {
        encounterReturn = false
        tool = null; campaignPage = null
        when (destination) {
            is ReforgeSearchDestination.Character -> {
                searchProfileId = destination.profileId; searchSection = destination.section
                searchDetail = destination.detailDialog; searchRouteToken++
                searchIsCompanion = destination.companion
                companionPlayId = if (destination.companion) destination.profileId else null
                companionPlaySection = destination.section
                tab = if (destination.companion) "Companions" else "Character"
            }
            is ReforgeSearchDestination.Companion -> {
                searchProfileId = null
                companionPlayId = destination.profileId; companionPlaySection = "Sheet"; tab = "Companions"
            }
            is ReforgeSearchDestination.CampaignItem -> {
                searchCampaignItemId = destination.itemId; tool = "Campaign item"
            }
            is ReforgeSearchDestination.Connection -> {
                companionPlayId = null; tab = "Campaign"; campaignPage = "Connections:${destination.entityId}"
            }
            is ReforgeSearchDestination.Invention -> {
                companionPlayId = null; tab = "Campaign"; campaignPage = "Inventions:${destination.projectId}"
            }
        }
    }
    fun returnToEncounter() {
        tool = null; searchProfileId = null; companionPlayId = null
        encounterReturn = false; campaignPage = "Encounters"; tab = "Campaign"
    }
    LaunchedEffect(ownerConnectionRequest) {
        if (ownerConnectionRequest > 0) {
            companionPlayId = null
            tab = "Character"
            tool = "Owner connection"
        }
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
            (tab == "Character" && !armorModalOpen)) {
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
    androidx.activity.compose.BackHandler(enabled = tab != "Character" &&
        (tab != "Campaign" || campaignPage == null) &&
        (tab != "Companions" || companionPlayId == null) && tool == null) { tab = "Character" }
    androidx.activity.compose.BackHandler(enabled = tab == "Inventory" && requestedInventory == "Campaign items" &&
        tool == null) { requestedInventory = "My gear"; inventoryRoute++ }
    androidx.activity.compose.BackHandler(enabled=tab=="Campaign" && campaignPage!=null && tool==null) {campaignPage=null}
    // Register the route fallback before the sheet: its pager closes Combat to Sheet first.
    androidx.activity.compose.BackHandler(enabled = encounterReturn && tab in listOf("Character", "Companions") && tool == null) {
        returnToEncounter()
    }
    AstravarTheme {
        CompositionLocalProvider(LocalGenesisConnectedEnvironment provides connected) {
        Scaffold(containerColor=RebuildPalette.Canvas,
            snackbarHost={SnackbarHost(snackbar)},
            topBar={RebuildAppHeader(s?.demo==true, search = { tool = "Global search" }, tools = { tool = "Tools" })},
            bottomBar={RebuildPrimaryNavigation(tab) { next ->
                encounterReturn = false
                if (next == "Inventory") { requestedInventory = "My gear"; inventoryRoute++ }
                if (searchIsCompanion) searchProfileId = null
                companionPlayId = null
                tab = next
            }}
        ) {insets->
            Column(Modifier.padding(insets).imePadding()) {
                if(error!=null) Text(error!!,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
                val current=s
                if(current==null) {
                    StorageRecoveryPanel(error, open = { tool = it })
                    // Diagnostics and the independent stores must remain reachable
                    // when the specialized weapon store cannot initialize.
                    tool?.let { name -> RebuildToolSheet(name, { tool = null }) {
                        when (name) {
                            "Tools" -> Page {
                                PlayerHeading("Recovery tools")
                                PlayerEntry("Help & diagnostics", "Inspect this installation", "More", { tool = "Help & diagnostics" })
                                PlayerEntry("Complete backup", "Recover connected campaign and character records", "History", { tool = "Complete backup" })
                                PlayerEntry("Character backup", "Recover playable character records", "History", { tool = "Character backup" })
                                PlayerEntry("Campaign backup", "Recover original campaign records", "History", { tool = "Campaign backup" })
                            }
                            "Complete backup" -> GenesisUnifiedBackupPage(app.genesisStorage.backup)
                            "Character backup" -> ReforgeBackupPage(playableRepository)
                            "Campaign backup" -> CampaignBackupPageV2(app.campaignLedger)
                            "Global search" -> ReforgeGlobalSearch(app.campaignLedger, open = ::openSearchDestination)
                            else -> HelpPage()
                        }
                    } }
                    return@Column
                }
                if(actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (encounterReturn && tab in listOf("Character", "Companions")) {
                    PlayerTextButton(onClick = ::returnToEncounter) { Text("‹ Return to encounter") }
                }
                if (remoteTestEnabled) Row(Modifier.fillMaxWidth().background(RebuildPalette.Active).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if(tab=="Character") "Disposable profile" else "Remote test on", Modifier.weight(1f), color = RebuildPalette.Ice,
                        style = MaterialTheme.typography.labelLarge)
                    PlayerTextButton(onClick = {
                        val next = waitingRemote.firstOrNull()
                        if (next == null) tool = "Remote Updates"
                        else { tool = null; remoteReviewId = next.commandId }
                    }) { Text(if (waitingRemote.isEmpty()) "Remote Updates" else "Review (${waitingRemote.size})") }
                }
                val playId = companionPlayId
                if (tab == "Companions" && playId != null && !remoteTestEnabled) {
                    key(playId, companionPlaySection) {
                        androidx.activity.compose.BackHandler(enabled = tool == null && !encounterReturn) { companionPlayId = null; if (searchIsCompanion) searchProfileId = null }
                        ReforgeCharacterSheet(app.campaignLedger, playableRepository,
                            initialSection = companionPlaySection, companionProfileId = playId,
                            onBackToCompanions = { companionPlayId = null; if (searchIsCompanion) searchProfileId = null },
                            navigationRequest = searchRoute?.takeIf { searchIsCompanion && it.profileId == playId })
                    }
                } else screenStates.SaveableStateProvider(tab) {
                    when(tab) {
                        "Character" -> if (remoteTestEnabled) Page { CharacterOverviewV2(app.campaignLedger, current, RemoteStatProtocol.PROFILE_ID) }
                            else ReforgeCharacterSheet(app.campaignLedger, playableRepository,
                                navigationRequest = searchRoute?.takeUnless { searchIsCompanion },
                                onOpenArmor = { tool = "Armor weapon" })
                        "Armor" -> ArmorOverviewV2(current,app.campaignLedger,
                            combat={tool="Armor weapon"},systems={tool="Spell reservoir"},
                            eek={tab="Campaign";campaignPage="Eek"},inventory={tab="Campaign";campaignPage="Inventory"},
                            elements=if(prefs.mode==Ability.ARCANE || prefs.mode==Ability.SPELL) emptyList() else prefs.elements,
                            selectElement={elementPicker=true},
                            characterProfileId=if(remoteTestEnabled) RemoteStatProtocol.PROFILE_ID else "local-player",
                            onOverlayVisibilityChanged={armorModalOpen=it},
                            openReferences={tab="Campaign";campaignPage="Reference Library"})
                        "Combat" -> ReforgeCharacterSheet(app.campaignLedger, initialSection = "Combat")
                        "Inventory" -> if (requestedInventory == "Campaign items") Column(Modifier.fillMaxSize()) {
                            PlayerTextButton(onClick = { requestedInventory = "My gear" }) { Text("‹ Inventory") }
                            Box(Modifier.weight(1f)) { CampaignLedgerPage(app.campaignLedger,
                                openReferences = { tab = "Campaign"; campaignPage = "Reference Library" }) }
                        } else GenesisInventoryHub(app.campaignLedger, playableRepository,
                            open = ::openSearchDestination,
                            openCampaignRecords = { requestedInventory = "Campaign items"; inventoryRoute++ })
                        "Companions" -> if (remoteTestEnabled) Page { Text("Party records are isolated from the disposable Remote Stat Test. Disable Remote Updates to edit party records.") }
                            else PartyPageV2(app.campaignLedger, current,
                                openReferences = { tab = "Campaign"; campaignPage = "Reference Library" },
                                openPlayable = { id, section ->
                                    if (searchIsCompanion) searchProfileId = null
                                    companionPlayId = id; companionPlaySection = section
                                },
                                playableRepository = playableRepository) {
                                    requestedInventory = "Campaign items"; inventoryRoute++; tab = "Inventory"
                                }
                        "Party" -> if (remoteTestEnabled) Page { Text("Party records are isolated from the disposable Remote Stat Test. Disable Remote Updates to edit party records.") }
                            else PartyPageV2(app.campaignLedger, current,
                                openReferences={tab="Campaign";campaignPage="Reference Library"}) {tab="Campaign";campaignPage="Inventory"}
                        "Campaign" -> {
                            val page=campaignPage
                            if(page==null) screenStates.SaveableStateProvider("campaign-hub") {
                                GenesisCampaignHub(connected, open = { campaignPage = it },
                                    openActor = { id, companion -> openSearchDestination(ReforgeSearchDestination.Character(id, "Sheet", companion = companion)) })
                            } else Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    PlayerTextButton(onClick={campaignPage=null}) {Text("‹ Campaign")}
                                    Spacer(Modifier.weight(1f))
                                }
                                Box(Modifier.weight(1f)) {screenStates.SaveableStateProvider("campaign-$page") {
                                    when(page) {
                                        "Search everything" -> ReforgeGlobalSearch(app.campaignLedger, open = ::openSearchDestination)
                                        "Inventory" -> CampaignLedgerPage(app.campaignLedger) {campaignPage="Reference Library"}
                                        "Inventions" -> InventionsPageV2(app.campaignLedger)
                                        "Encounters" -> GenesisEncountersPage(connected) { destination ->
                                            openSearchDestination(destination); encounterReturn = true
                                        }
                                        "Checkpoints" -> CampaignCheckpointPanel(app.campaignLedger)
                                        "Eek" -> EekPageV2(app.campaignLedger)
                                        "Reference Library" -> CampaignReferenceLibraryV21(app.campaignLedger)
                                        "Campaign import" -> CampaignArchiveImportV21(app.campaignLedger)
                                        else -> when {
                                            page.startsWith("Connections:") -> ConnectionsPageV2(app.campaignLedger, initialEntityId = page.removePrefix("Connections:"))
                                            page.startsWith("Inventions:") -> InventionsPageV2(app.campaignLedger, initialProjectId = page.removePrefix("Inventions:"))
                                            else -> ConnectionsPageV2(app.campaignLedger)
                                        }
                                    }
                                }}
                            }
                        }
                        else -> RebuildConfigHub { tool = it }
                    }
                }
                tool?.let {name->RebuildToolSheet(name,{tool=null}) {
                    when(name) {
                        "Tools" -> RebuildConfigHub { tool = it }
                        "Global search" -> ReforgeGlobalSearch(app.campaignLedger, open = ::openSearchDestination)
                        "Campaign item" -> CampaignLedgerPage(app.campaignLedger, initialItemId = searchCampaignItemId)
                        "Fictional sync" -> SandboxSyncPage(app.sandboxSync)
                        "Remote Updates" -> if(BuildConfig.DEBUG) RemoteUpdatesPage(app.remoteStats, characterAbilities) {id->
                            tool=null;remoteReviewId=id
                        }
                        "Owner connection" -> CampaignConnectionPage()
                        "Complete backup" -> GenesisUnifiedBackupPage(app.genesisStorage.backup)
                        "Campaign backup" -> CampaignBackupPageV2(app.campaignLedger)
                        "Character backup" -> ReforgeBackupPage(playableRepository)
                        "Campaign import" -> CampaignArchiveImportV21(app.campaignLedger)
                        "Weapon backup" -> Page {BackupPage(current,actions)}
                        "Armor weapon" -> if(current.onboarded) Dashboard(current,actions,startOverlay,grantOverlay,stopOverlay,app.preferences,prefs)
                            else WeaponSetupV2(current,actions,{tool="Rules & profile"},{tool="Weapon backup"})
                        "Rules & profile" -> RulesPage(current,actions,if(prefs.mode in listOf(Ability.ARCANE,Ability.SPELL)) emptyList() else prefs.elements)
                        "Resources & recovery" -> ResourcesPage(current,history,actions)
                        "Spell reservoir" -> CoresPage(current,actions)
                        "ChatGPT Prompts", "ChatGPT toolkit" -> ChatGptPromptsPage(app.campaignLedger.prompts)
                        "Spell library" -> PayloadsPage(current,actions,app.preferences,prefs.selectedPayloads)
                        "Roll history" -> CombatHistoryPage(current)
                        "Appearance", "Overlay & appearance" -> SettingsPage(app,current,actions,startOverlay,grantOverlay,stopOverlay)
                        "Companions" -> PartyPageV2(app.campaignLedger, current, openReferences={tool=null;tab="Campaign";campaignPage="Reference Library"}) {
                            tool = null; requestedInventory = "Campaign items"; inventoryRoute++; tab = "Inventory"
                        }
                        "Character sources" -> Page { CharacterOverviewV2(app.campaignLedger, current) }
                        "Armor systems" -> ArmorOverviewV2(current,app.campaignLedger,combat={tool="Armor weapon"},systems={tool="Spell reservoir"},eek={tool="Companions"},inventory={tool=null;tab="Inventory"},elements=prefs.elements,selectElement={elementPicker=true},
                            openReferences={tool=null;tab="Campaign";campaignPage="Reference Library"})
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
        ControlRoomLink("Appearance","Artwork and feedback preferences","Settings") {open("Settings")}
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
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nBuild ${Build.DISPLAY}\nPackage ${c.packageName}\nStandalone mode · overlay retired")
        }
        Section("Dice & your character sheet") {
            Text("Your character sheet includes Combat, abilities, spells, and live equipment. Tap a score, skill, attack or spell to use its calculated modifiers; each saved roll retains its dice and explanation. Nothing rolls automatically. The DM determines ordinary hits. Damage previews are potential damage, and averages are estimates. Tools → Armor weapon retains the accepted specialized Astravar weapon engine. Complete backup preserves connected campaign and playable records together; partial backups remain available for unconnected records.")
            Text("Astravar does not read, control, or synchronize D&D Beyond. Imported source records remain available for review and reconciliation. Playable character changes are saved on this device; their presence does not establish authoritative campaign synchronization. The optional public connectivity lab sends fixed fictional test messages only.")
            Text("Astravar runs as a standalone application. Overlay permissions, floating controls and the persistent overlay service are retired.")
            Text("Unofficial tabletop companion. Not affiliated with or endorsed by Wizards of the Coast, D&D Beyond, or Samsung.")
        }
    }
}
