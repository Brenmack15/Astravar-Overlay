package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable fun RulesPage(s: Campaign,a: Actions) {
    val p=s.profile
    var level by remember(p.version) { mutableStateOf(p.level.toString()) }
    var intelligence by remember(p.version) { mutableStateOf(p.intelligence.toString()) }
    var proficiency by remember(p.version) { mutableStateOf(p.proficiency.toString()) }
    var dc by remember(p.version) { mutableStateOf(p.saveDc.toString()) }
    var calibration by remember(p.version) { mutableStateOf(p.calibration.toString()) }
    var enabled by remember(p.version) { mutableStateOf(p.calibrationEnabled) }
    var progression by remember(p.version) { mutableStateOf(p.proposedCalibrationProgression) }
    var confirmed by remember(p.version) { mutableStateOf(p.confirmed) }
    var capacity by remember(p.version) { mutableStateOf(p.capacityOverride?.toString() ?: "") }
    var elementCapacity by remember(p.version) { mutableStateOf(p.elementCapacityOverride?.toString() ?: "") }
    var release by remember(p.version) { mutableStateOf(p.releaseOverride?.toString() ?: "") }
    var limit by remember(p.version) { mutableStateOf(p.spellShotsPerTurn.toString()) }
    var spellLevel by remember(p.version) { mutableStateOf(p.emergencySpellLevelOverride?.toString() ?: "") }
    var replacement by remember(p.version) { mutableStateOf(p.fastReplacementLevel.toString()) }
    var syntheticGp by remember(p.version) { mutableStateOf(p.syntheticGp?.toString() ?: "") }
    var syntheticDays by remember(p.version) { mutableStateOf(p.syntheticDays?.toString() ?: "") }
    Page {
        Section("${p.name} · version ${p.version}") {
            Text("ACCEPTED: attunement and a usable core are required. 1d10 + INT + calibration Force; 60/180 ft. Extra Attack gives two ordinary attacks within one Attack action. Selector changes are free. Explosions always take one Action.")
            Text("Discharge: ${p.dischargeDice}d10 Force, 60 ft range / 15 ft radius. Shatter: ${p.dischargeDice*3}d10 Force, 120 ft range / 60 ft RADIUS. Success halves the rolled Force, not the dice. No selector or flat bonus on the blast.")
            Text("PROVISIONAL: one spell-powered firing per turn; spell shots replace all free selector dice; one stored payload until confirmed dual storage at level 12, then two. Loaded magic persists through rests and swaps.")
            Text("ASSUMPTION: starter +1 calibration. PROPOSED, disabled by default: +2 at 10 / +3 at 15. Four/five payloads at level 20 are not finalized.")
        }
        Section("Edit next profile version") {
            Field("Artificer level",level,{level=it}); Field("INT modifier",intelligence,{intelligence=it}); Field("Proficiency bonus",proficiency,{proficiency=it}); Field("Spell save DC (independent)",dc,{dc=it})
            Toggle("Calibration enabled · assumption",enabled) {enabled=it}; Field("Calibration bonus",calibration,{calibration=it})
            Toggle("Enable PROPOSED +2/+3 calibration progression",progression) {progression=it}
            Text("Eligible does not mean installed. Confirm engineering upgrades independently; level gates still apply.")
            Upgrade.entries.forEach { u -> Toggle("${u.name.replace('_',' ')} · ${if(p.has(u)) "active" else "not active"}",u in confirmed) {v -> confirmed=if(v) confirmed+u else confirmed-u} }
            Field("Provisional spell firings per turn",limit,{limit=it})
            Field("Explicit storage capacity override (blank = milestone rules)",capacity,{capacity=it})
            Field("Proposed selector capacity override (blank = accepted 1/2)",elementCapacity,{elementCapacity=it})
            Field("Explicit release limit override (blank = milestone rules)",release,{release=it})
            Field("Emergency Artificer spell-level override (blank = pinned progression)",spellLevel,{spellLevel=it})
            Field("Fast replacement threshold · approximately level 15",replacement,{replacement=it})
            Field("Synthetic core gp (blank = unresolved)",syntheticGp,{syntheticGp=it}); Field("Synthetic core days (blank = unresolved)",syntheticDays,{syntheticDays=it})
            Action("Save new rules version",!a.busy) { a.change("Saved explicit profile version ${p.version+1}; past shots and stored payloads preserved") {
                it.copy(profile=p.copy(version=p.version+1,level=level.intValue("Level",5,20),intelligence=intelligence.intValue("INT",-5,10),proficiency=proficiency.intValue("Proficiency",0,10),saveDc=dc.intValue("DC",1,40),calibration=calibration.intValue("Calibration",0,10),calibrationEnabled=enabled,proposedCalibrationProgression=progression,confirmed=confirmed,spellShotsPerTurn=limit.intValue("Turn limit",1,20),elementCapacityOverride=elementCapacity.takeIf {x->x.isNotBlank()}?.intValue("Selector capacity",1,9),capacityOverride=capacity.takeIf { x->x.isNotBlank() }?.intValue("Capacity",1,12),releaseOverride=release.takeIf { x->x.isNotBlank() }?.intValue("Release",1,12),emergencySpellLevelOverride=spellLevel.takeIf { x->x.isNotBlank() }?.intValue("Spell level",1,9),fastReplacementLevel=replacement.intValue("Replacement level",5,20),syntheticGp=syntheticGp.takeIf { x->x.isNotBlank() }?.intValue("GP",0,1000000),syntheticDays=syntheticDays.takeIf { x->x.isNotBlank() }?.intValue("Days",1,10000)))
            } }
        }
    }
}
@Composable fun EncounterPage(s: Campaign,history: List<HistoryEntry>,a: Actions) {
    var seconds by remember { mutableStateOf("600") }; var slotLevel by remember { mutableStateOf(1) }; var slotCount by remember { mutableStateOf("") }
    var notes by remember(s.notes) { mutableStateOf(s.notes) }; var undo by remember { mutableStateOf(false) }
    val c=LocalContext.current
    Page {
        Section("Encounter · turn ${s.turn.number}") {
            Toggle("Track turn action limits",s.encounter) {v -> a.change("Encounter tracking set to $v; turn budget reset") { it.copy(encounter=v,turn=Turn()) } }
            Text("Attacks ${s.turn.attacks}/2 · Action spent ${s.turn.fullAction} · Bonus Action spent ${s.turn.bonus} · spell firings ${s.turn.spellShots}/${s.profile.spellShotsPerTurn} (provisional)")
            Text("Game clock ${s.gameSeconds}s. Wall time and app restarts never recover resources.")
            Action("Advance round / next turn · +6 seconds",!a.busy) {a.change("Player advanced round +6 seconds") {Engine.advance(it,6,true)}}
            Field("Game seconds to advance",seconds,{seconds=it})
            Action("Confirm in-game time advancement",!a.busy) {a.change("Player advanced game time") {Engine.advance(it,seconds.intValue("Seconds",0,31536000).toLong())}}
            Action("Record Long Rest · preserve payloads and counters",!a.busy) {a.change("Long Rest recorded; time and slots require manual reconciliation") {it}}
        }
        Section("Spell slots · manually reconciled") {
            Text("Unknown levels have no counter. A rest never silently refills slots; enter your actual remaining count.")
            Text((1..5).joinToString(" · ") { "L$it: ${s.slots[it] ?: "unknown"}" })
            Choices("Spell level",(1..9).toList(),slotLevel) {slotLevel=it}; Field("Confirmed remaining slots",slotCount,{slotCount=it})
            Action("Reconcile slot count",!a.busy) {a.change("Manual slot reconciliation L$slotLevel") {it.copy(slots=it.slots+(slotLevel to slotCount.intValue("Slots",0,99)))}}
            Action("Mark this level unknown",!a.busy) {a.change("Slot level $slotLevel marked unknown") {it.copy(slots=it.slots-slotLevel)}}
        }
        Section("Campaign notes") { Field("Player-safe notes",notes,{notes=it},single=false); Action("Save notes",!a.busy) {a.change("Updated campaign notes") {it.copy(notes=notes)}} }
        s.pending.filter { it.result!=null }.asReversed().take(50).forEach { shot ->
            val r=shot.result!!
            Section("${shot.plan.ability} · ${r.outcome}") {
                Text("${r.provenance} · profile v${shot.plan.profile.version}")
                if(shot.plan.blast) r.targets.forEach { t -> Text("${t.name} · ${if(t.saved==true) "saved" else "failed"}: ${Engine.damage(shot.plan,r,t).entries.joinToString { "${it.value} ${it.key}" }}") }
                else Text(Engine.damage(shot.plan,r).entries.joinToString {"${it.value} ${it.key}"})
                TextButton(onClick={val value="${shot.plan.ability}: ${r.outcome}; ${r.rolls}. Player-entered results; no sheet synchronization."; (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Astravar result",value)); a.message="Result copied"}) { Text("Copy result") }
            }
        }
        Section("Accounting history") {
            Action("Undo last operation with logged correction",!a.busy && history.isNotEmpty(),danger=true) {undo=true}
            history.take(100).forEach { Text("Game ${it.gameSeconds}s · ${it.origin}${if(it.undone) " · UNDONE" else ""}\n${it.label}"); HorizontalDivider() }
        }
    }
    if(undo) AlertDialog(onDismissRequest={undo=false},title={Text("Restore the previous state?")},text={Text("The latest operation will be reversed, including its resources and pending results. A correction event is retained.\n${history.firstOrNull()?.label}")},confirmButton={TextButton(onClick={a.run("Logged correction applied") {a.repo.undo()};undo=false}) {Text("Confirm correction")}},dismissButton={TextButton(onClick={undo=false}) {Text("Cancel")}})
}
@Composable fun BackupPage(s: Campaign,a: Actions) {
    val c=LocalContext.current
    var incoming by remember { mutableStateOf<Pair<String,Backup>?>(null) }
    var backupSaved by remember { mutableStateOf(false) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri -> if(uri!=null) a.run("Versioned backup saved") {
        val text=a.repo.export(); withContext(Dispatchers.IO) { c.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) } }; backupSaved=true
    } }
    val session=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {uri -> if(uri!=null) a.run("Session export saved") { withContext(Dispatchers.IO) {c.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(BackupCodec.session(s).toByteArray())}} } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null) a.run("Backup validated; review before replacing") {
        val text=withContext(Dispatchers.IO) {c.contentResolver.openInputStream(uri)!!.use { stream ->
            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val n=stream.read(buffer); if(n<0) break; require(out.size()+n<=BackupCodec.MAX_BYTES) {"Backup exceeds 8 MiB"};out.write(buffer,0,n) }
            out.toString("UTF-8")
        }}
        incoming=text to BackupCodec.decode(text); backupSaved=false
    } }
    Section("Portable backups") {
        Text("Versioned JSON contains campaign state and accounting history. Files stay where you choose. Import validates size, IDs, dice, levels, clocks, and references before an atomic replacement. Nothing is merged implicitly.")
        Action("Export complete JSON backup",!a.busy) {export.launch("astravar-backup.json")}
        Action("Export player-safe session Markdown",!a.busy) {session.launch("astravar-session.md")}
        Action("Choose JSON backup to preview",!a.busy) {import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
        incoming?.let { (text,b) ->
            Text("IMPORT PREVIEW\n${b.campaign.profile.character} · ${if(b.campaign.demo) "DEMO" else "campaign"}\n${b.campaign.cores.size} cores · ${b.campaign.templates.size} templates · ${b.campaign.pending.count {it.result==null}} unresolved operations · ${b.history.size} archived events\nThis replaces current campaign state. Local history is preserved with an undo point; imported history is retained as an archive and included in future exports.")
            if(!backupSaved) Text("Save your current backup before replacing. This is required to preserve both histories.")
            Action("Back up current state first",!a.busy) {export.launch("astravar-before-import.json")}
            val id=remember(text) {UUID.randomUUID().toString()}
            Action("Replace campaign with validated import",!a.busy && backupSaved,danger=true) {a.run("Campaign imported once") {a.repo.import(text,id)};incoming=null}
            TextButton(onClick={incoming=null}) {Text("Cancel import")}
        }
    }
}
@Composable fun SettingsPage(app: AstravarApp,s: Campaign,a: Actions,start: ()->Unit,grant: ()->Unit,stop: ()->Unit) {
    val prefs by app.preferences.flow.collectAsState(initial=com.yazsras.astravar.data.OverlayPrefs())
    val c=LocalContext.current
    val image=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null) a.run("Artwork selected") {
        c.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.preferences.image(uri.toString())
    } }
    var resetDemo by remember {mutableStateOf(false)}
    Page {
        Section("Floating panel") {
            Text("The crystal can be dragged and snaps to an edge. Portrait and landscape positions are saved independently. Outside touches reach your other app. Text entry belongs to explicit result editors.")
            Action("Allow display over other apps",onClick=grant); Action("Start overlay session",onClick=start); Action("Stop session",onClick=stop)
            Text("Overlay scale: ${"%.0f".format(prefs.scale*100)}%")
            Slider(prefs.scale,onValueChange={v -> a.run("Panel scale saved") {app.preferences.scale(v)}},valueRange=.85f..1.3f)
            Action("Reset overlay positions") {a.run("Positions reset; restart overlay to apply") {app.preferences.position(false,0,160);app.preferences.position(true,0,80)}}
        }
        Section("Feedback & advanced controls") {
            Toggle("Haptic feedback",prefs.haptics) {v->a.run("Feedback updated") {app.preferences.haptics(v)}}
            Toggle("Advanced: manual roll override",prefs.manualOverrides) {v->a.run("Manual override updated") {app.preferences.manualOverrides(v)}}
            if(prefs.manualOverrides) Text("Combat target settings now includes external dice. Manual entry is recorded separately from Astravar's dice.")
        }
        Section("Artwork") { Text("Approved Astravar pistol artwork is included. A personal image can be selected using Android's file picker."); Action("Choose personal artwork") {image.launch(arrayOf("image/*"))}; Action("Use included Astravar artwork") {a.run("Original artwork restored") {app.preferences.image("")}} }
        if(s.demo) Section("Leave demo") {Text("Your demo has its own labeled state. Export it if needed before starting a real campaign. The correction history remains available.");Action("End demo and begin empty real campaign",danger=true) {resetDemo=true}}
    }
    if(resetDemo) AlertDialog(onDismissRequest={resetDemo=false},title={Text("End demo?")},text={Text("Remove demo resources and return to profile confirmation. No real resources will be assumed.")},confirmButton={TextButton(onClick={a.change("Left labeled demo; empty real campaign") {Campaign()};resetDemo=false}) {Text("End demo")}},dismissButton={TextButton(onClick={resetDemo=false}) {Text("Cancel")}})
}
