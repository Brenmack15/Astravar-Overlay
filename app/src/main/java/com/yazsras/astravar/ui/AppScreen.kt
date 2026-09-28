package com.yazsras.astravar.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.*
import com.yazsras.astravar.rules.*
import java.util.UUID

@Composable fun AstravarScreen(app: AstravarApp,startOverlay: ()->Unit,grantOverlay: ()->Unit,stopOverlay: ()->Unit) {
    val s by app.repository.state.collectAsState()
    val error by app.repository.error.collectAsState()
    val history by app.repository.history.collectAsState()
    val prefs by app.preferences.flow.collectAsState(initial=com.yazsras.astravar.data.OverlayPrefs())
    val scope=rememberCoroutineScope()
    val actions=remember { Actions(app.repository,scope) }
    var tab by remember { mutableStateOf("Combat") }
    var inventoryTab by remember { mutableStateOf("Cores") }
    var morePage by remember { mutableStateOf<String?>(null) }
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(actions.message) {if(actions.message.isNotBlank()) {snackbar.showSnackbar(actions.message);actions.message=""}}
    AstravarTheme {
        Scaffold(
            snackbarHost={SnackbarHost(snackbar)},
            topBar={Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("ASTRAVAR",style=MaterialTheme.typography.titleLarge,color=Brass)
                Text(if(s?.demo==true) "DEMO" else "${s?.profile?.character ?: "Welcome"}",style=MaterialTheme.typography.labelMedium,color=Frost)
            }},
            bottomBar={if(s?.onboarded==true) NavigationBar {
                listOf("Combat" to "◇","Inventory" to "▤","History" to "≡","More" to "⋯").forEach {(name,icon)->
                    NavigationBarItem(selected=tab==name,onClick={tab=name;morePage=null},icon={Text(icon)},label={Text(name)})
                }
            }}
        ) {insets->
            Column(Modifier.padding(insets).imePadding()) {
                if(error!=null) Text(error!!,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
                val current=s
                if(current==null) {LinearProgressIndicator(Modifier.fillMaxWidth());return@Column}
                if(actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(!current.onboarded) {
                    Page {
                        Section("Ready your Astravar") {
                            Text("Roll attack and damage dice here, or use the floating crystal while your character sheet is open.")
                            Text("Starter profile: Zerg Hasma · Artificer 5 · INT +4 · proficiency +3 · DC 15. The editable +1 calibration is an assumption.",style=MaterialTheme.typography.bodySmall)
                            Text("Confirm your profile, then add the cores and resources you actually own in Inventory.")
                            Action("Confirm starter profile",!actions.busy) {actions.change("Starter profile confirmed") {it.copy(onboarded=true)}}
                            Action("Explore separate demo campaign",!actions.busy) {actions.change("Explicit demo started") {
                                it.copy(onboarded=true,demo=true,attuned=true,installedId="demo-core",cores=listOf(Core("demo-core","DEMO crystal"),Core("demo-spare","DEMO spare")),slots=mapOf(1 to 4,2 to 2))
                            }}
                            TextButton(onClick={tab="Backup"}) {Text("Restore a saved campaign")}
                        }
                        if(tab=="Backup") BackupPage(current,actions)
                    }
                } else when(tab) {
                    "Combat" -> Dashboard(current,actions,startOverlay,grantOverlay,stopOverlay,app.preferences,prefs)
                    "Inventory" -> {
                        Choices("Inventory",listOf("Cores","Payloads"),inventoryTab,change={inventoryTab=it})
                        if(inventoryTab=="Cores") CoresPage(current,actions) else PayloadsPage(current,actions)
                    }
                    "History" -> CombatHistoryPage(current)
                    else -> {
                        if(morePage!=null) TextButton(onClick={morePage=null}) {Text("‹ More")}
                        when(morePage) {
                            "Encounter" -> EncounterPage(current,history,actions)
                            "Rules" -> RulesPage(current,actions)
                            "Backup" -> Page {BackupPage(current,actions)}
                            "Settings" -> SettingsPage(app,current,actions,startOverlay,grantOverlay,stopOverlay)
                            "Help" -> HelpPage()
                            else -> Page {
                                Section("Campaign & display") {
                                    Action("Encounter, time & spell slots") {morePage="Encounter"}
                                    Action("Rules, upgrades & provisional choices") {morePage="Rules"}
                                    Action("Overlay settings & artwork") {morePage="Settings"}
                                    Action("Backup & restore") {morePage="Backup"}
                                    Action("Help & device information") {morePage="Help"}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun HelpPage() {
    val c=LocalContext.current
    Page {
        Section("Device diagnostics") {
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nBuild ${Build.DISPLAY}\nApp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nPackage ${c.packageName}\nDisplay-over-apps permission: ${Settings.canDrawOverlays(c)}")
        }
        Section("Dice & your character sheet") {
            Text("Astravar does not read, control, or synchronize D&D Beyond. Tap Roll to generate attack and damage dice, or choose external dice. Individual faces are saved and never rerolled on restart. Leave target AC blank to see the attack total without a hit/miss judgment. Reconcile your sheet manually. Damage averages are estimates, never rolled results. No network, analytics, account, accessibility access, or screen capture.")
            Text("The floating crystal is a genuine Android overlay. Some apps and protected screens hide overlays. Start sessions while Astravar is visible; sessions do not restart at boot or after you stop them. Locking the phone hides the panel; resume from its notification.")
            Text("If Samsung pauses a session, first reopen Astravar and start it again. Check the app's battery settings only if sessions repeatedly stop; no blanket security changes are required.")
            Text("Unofficial tabletop companion. Not affiliated with or endorsed by Wizards of the Coast, D&D Beyond, or Samsung.")
        }
    }
}
