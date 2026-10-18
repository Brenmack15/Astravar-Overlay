package com.yazsras.astravar.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.yazsras.astravar.*
import com.yazsras.astravar.rules.*

@Composable fun AstravarScreen(app: AstravarApp,startOverlay: ()->Unit,grantOverlay: ()->Unit,stopOverlay: ()->Unit) {
    val s by app.repository.state.collectAsState()
    val error by app.repository.error.collectAsState()
    val history by app.repository.history.collectAsState()
    val prefs by app.preferences.flow.collectAsState(initial=com.yazsras.astravar.data.OverlayPrefs())
    val scope=rememberCoroutineScope()
    val actions=remember { Actions(app.repository,scope) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Astravar") }
    var armorTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Armor") }
    var campaignTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Connections") }
    var inventoryTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Cores") }
    var morePage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var elementPicker by remember { mutableStateOf(false) }
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(actions.message) {if(actions.message.isNotBlank()) {snackbar.showSnackbar(actions.message);actions.message=""}}
    AstravarTheme {
        Scaffold(
            snackbarHost={SnackbarHost(snackbar)},
            topBar={AstravarHeader(s?.demo==true) {tab="More";morePage="Settings"}},
            bottomBar={CampaignNavigationV2(tab) {tab=it;morePage=null}}
        ) {insets->
            Column(Modifier.padding(insets).imePadding()) {
                if(error!=null) Text(error!!,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
                val current=s
                if(current==null) {LinearProgressIndicator(Modifier.fillMaxWidth());return@Column}
                if(actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when(tab) {
                    "Inventory" -> CampaignLedgerPage(app.campaignLedger)
                    "Campaign" -> {
                        V2SectionTabs(listOf("Inventions","Connections","Eek"),campaignTab) {campaignTab=it}
                        when(campaignTab) {
                            "Inventions" -> InventionsPageV2(app.campaignLedger)
                            "Eek" -> EekPageV2(app.campaignLedger)
                            else -> ConnectionsPageV2(app.campaignLedger)
                        }
                    }
                    "Astravar" -> {
                        V2SectionTabs(listOf("Armor","Combat","Systems"),armorTab) {armorTab=it}
                        when {
                            armorTab=="Armor" -> ArmorOverviewV2(current,app.campaignLedger,
                                combat={armorTab="Combat"},systems={armorTab="Systems"},
                                eek={tab="Campaign";campaignTab="Eek"},inventory={tab="Inventory"},
                                elements=if(prefs.mode==Ability.ARCANE || prefs.mode==Ability.SPELL) emptyList() else prefs.elements,
                                selectElement={elementPicker=true})
                            !current.onboarded -> WeaponSetupV2(current,actions,
                                rules={tab="More";morePage="Rules"},restore={tab="More";morePage="Backup"})
                            armorTab=="Combat" -> Dashboard(current,actions,startOverlay,grantOverlay,stopOverlay,app.preferences,prefs)
                            else -> {
                                V2SectionTabs(listOf("Cores","Payloads","History"),inventoryTab) {inventoryTab=it}
                                when(inventoryTab) {
                                    "Cores" -> CoresPage(current,actions)
                                    "Payloads" -> PayloadsPage(current,actions,app.preferences,prefs.selectedPayloads)
                                    else -> CombatHistoryPage(current)
                                }
                            }
                        }
                    }
                    else -> {
                        if(morePage!=null) TextButton(onClick={morePage=null}) {Text("‹ More")}
                        when(morePage) {
                            "Sync" -> SandboxSyncPage(app.sandboxSync)
                            "Campaign Backup" -> CampaignBackupPageV2(app.campaignLedger)
                            "Resources" -> ResourcesPage(current,history,actions)
                            "Rules" -> RulesPage(current,actions,if(prefs.mode in listOf(Ability.ARCANE,Ability.SPELL)) emptyList() else prefs.elements)
                            "Backup" -> Page {BackupPage(current,actions)}
                            "Settings" -> SettingsPage(app,current,actions,startOverlay,grantOverlay,stopOverlay)
                            "Help" -> HelpPage()
                            else -> V2ControlRoom {morePage=it}
                        }
                    }
                }
            }
        }
        if(elementPicker && s!=null) ElementWheelV2(s!!.profile, prefs.elements, {elementPicker=false}) {type ->
            actions.run("") {
                val blast = prefs.mode in listOf(Ability.DISCHARGE, Ability.SHATTER)
                if(type==DamageType.FORCE) {
                    app.preferences.elements(emptyList())
                    if(!blast) app.preferences.mode(Ability.ARCANE)
                } else {
                    val selected=if(s!!.profile.elementCapacity==1) listOf(type)
                        else if(type in prefs.elements) prefs.elements-type
                        else (prefs.elements+type).takeLast(s!!.profile.elementCapacity)
                    app.preferences.elements(selected)
                    if(!blast) app.preferences.mode(Ability.ELEMENTAL)
                }
            }
            elementPicker=false
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
    Page {
        Section("Device diagnostics") {
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nBuild ${Build.DISPLAY}\nApp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nPackage ${c.packageName}\nDisplay-over-apps permission: ${Settings.canDrawOverlays(c)}")
        }
        Section("Dice & your character sheet") {
            Text("Astravar does not read, control, or synchronize D&D Beyond. Tap Roll to generate attack and damage dice, or choose external dice. Individual faces are saved and never rerolled on restart. The DM determines ordinary hits; potential damage is clearly labeled. Natural 20s are criticals and natural 1s automatically miss. Reconcile your sheet manually. Damage averages are estimates, never rolled results. The optional public connectivity lab sends fixed fictional test messages only. Your local campaign records are not uploaded. No analytics, account, accessibility access, or screen capture.")
            Text("The floating crystal is a genuine Android overlay. Some apps and protected screens hide overlays. Start sessions while Astravar is visible; sessions do not restart at boot or after you stop them. Locking the phone hides the panel; resume from its notification.")
            Text("If Samsung pauses a session, first reopen Astravar and start it again. Check the app's battery settings only if sessions repeatedly stop; no blanket security changes are required.")
            Text("Unofficial tabletop companion. Not affiliated with or endorsed by Wizards of the Coast, D&D Beyond, or Samsung.")
        }
    }
}
