package com.yazsras.astravar.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.rules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Compact entry points. The accepted engine and versioned profile remain authoritative. */
@Composable fun RulesPage(s: Campaign, a: Actions, elements: List<DamageType> = emptyList()) {
    val p = s.profile
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsDirty by remember { mutableStateOf(false) }
    var baselineJson by rememberSaveable { mutableStateOf<String?>(null) }
    val baseline = remember(baselineJson) { baselineJson?.let { BackupCodec.json.decodeFromString<Profile>(it) } }
    var level by rememberSaveable { mutableStateOf("") }
    var intelligence by rememberSaveable { mutableStateOf("") }
    var proficiency by rememberSaveable { mutableStateOf("") }
    var dc by rememberSaveable { mutableStateOf("") }
    var calibration by rememberSaveable { mutableStateOf("") }
    var calibrationEnabled by rememberSaveable { mutableStateOf(false) }
    var progression by rememberSaveable { mutableStateOf(false) }
    var confirmedNames by rememberSaveable { mutableStateOf("") }
    val confirmed = confirmedNames.split(',').mapNotNull { name -> Upgrade.entries.firstOrNull { it.name == name } }.toSet()
    var capacity by rememberSaveable { mutableStateOf("") }
    var elementCapacity by rememberSaveable { mutableStateOf("") }
    var release by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var syntheticGp by rememberSaveable { mutableStateOf("") }
    var syntheticDays by rememberSaveable { mutableStateOf("") }
    fun edit() {
        baselineJson = BackupCodec.json.encodeToString(p)
        level = p.level.toString(); intelligence = p.intelligence.toString(); proficiency = p.proficiency.toString()
        dc = p.saveDc.toString(); calibration = p.calibration.toString(); calibrationEnabled = p.calibrationEnabled
        progression = p.proposedCalibrationProgression
        confirmedNames = Upgrade.entries.filter { it in p.confirmed }.joinToString(",") { it.name }
        capacity = p.capacityOverride?.toString().orEmpty(); elementCapacity = p.elementCapacityOverride?.toString().orEmpty()
        release = p.releaseOverride?.toString().orEmpty(); limit = p.spellShotsPerTurn.toString()
        replacement = p.fastReplacementLevel.toString(); syntheticGp = p.syntheticGp?.toString().orEmpty()
        syntheticDays = p.syntheticDays?.toString().orEmpty(); a.message = ""; mode = "edit"
    }
    val stale = baseline != null && p != baseline
    val dirty = detailsDirty || (mode == "edit" && baseline?.let { b ->
        level != b.level.toString() || intelligence != b.intelligence.toString() || proficiency != b.proficiency.toString() ||
            dc != b.saveDc.toString() || calibration != b.calibration.toString() || calibrationEnabled != b.calibrationEnabled ||
            progression != b.proposedCalibrationProgression || confirmed != b.confirmed ||
            capacity != b.capacityOverride?.toString().orEmpty() || elementCapacity != b.elementCapacityOverride?.toString().orEmpty() ||
            release != b.releaseOverride?.toString().orEmpty() || limit != b.spellShotsPerTurn.toString() ||
            replacement != b.fastReplacementLevel.toString() || syntheticGp != b.syntheticGp?.toString().orEmpty() ||
            syntheticDays != b.syntheticDays?.toString().orEmpty()
    } == true)
    Page {
        PlayerHeading("Rules & profile")
        PlayerCard {
            Text(p.character, style = MaterialTheme.typography.titleLarge)
            Text("Level ${p.level} · profile ${p.version}", color = RebuildPalette.Muted)
            Text("${if (p.attack >= 0) "+" else ""}${p.attack} attack · DC ${p.saveDc}", color = RebuildPalette.Ice, style = MaterialTheme.typography.titleMedium)
            Text("${p.base} Force · ${p.selector} per selected element", style = MaterialTheme.typography.bodyMedium)
        }
        PlayerEntry("Shared damage defaults", "Inherited Primary Force and selector dice", "Weapon", { a.message = ""; detailsDirty = false; mode = "upgrades" })
        PlayerEntry("Individual damage types", "Ten independent sourced formulas and unlock explanations", "Weapon", { a.message = ""; detailsDirty = false; mode = "elements" })
        PlayerEntry("Gauntlet configuration", Engine.meleeLockReason(p) ?: "Accepted melee profile v${p.melee?.version}", "Weapon", { a.message = ""; detailsDirty = false; mode = "melee" })
        PlayerEntry("Edit weapon profile", "Level, modifiers and confirmed engineering upgrades", "Settings", ::edit)
        PlayerEntry("Mechanics reference", "Actions, blast rules, payloads and provisional settings", "Core", { mode = "reference" })
        if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
    }
    mode?.let { currentMode ->
        PlayerSheet(when (currentMode) { "upgrades" -> "Shared damage defaults"; "elements" -> "Individual damage types"; "melee" -> "Gauntlet configuration"; "edit" -> "Weapon profile"; else -> "Mechanics reference" },
            onDismiss = { if (!a.busy) mode = null }, dirty = dirty, showCancel = false, actions = {
                if (currentMode == "edit") {
                    val close = LocalPlayerDismiss.current
                    PlayerTextButton(onClick = close, enabled = !a.busy) { Text("Cancel") }
                    PlayerButton(enabled = !a.busy && !stale && dirty, onClick = {
                        val expected = baseline ?: return@PlayerButton
                        a.run("Saved explicit profile version ${expected.version + 1}; past shots and stored payloads preserved") {
                            a.repo.mutate(label = "Saved explicit profile version ${expected.version + 1}; past shots and stored payloads preserved") { current ->
                                check(current.profile == expected) { "The profile changed. Reopen the editor before saving." }
                                current.copy(profile = expected.copy(version = expected.version + 1,
                                    level = level.intValue("Level", 5, 20), intelligence = intelligence.intValue("INT", -5, 10),
                                    proficiency = proficiency.intValue("Proficiency", 0, 10), saveDc = dc.intValue("DC", 1, 40),
                                    calibration = calibration.intValue("Calibration", 0, 10), calibrationEnabled = calibrationEnabled,
                                    proposedCalibrationProgression = progression, confirmed = confirmed,
                                    spellShotsPerTurn = limit.intValue("Turn limit", 1, 20),
                                    elementCapacityOverride = elementCapacity.takeIf { it.isNotBlank() }?.intValue("Selector capacity", 1, 9),
                                    capacityOverride = capacity.takeIf { it.isNotBlank() }?.intValue("Capacity", 1, 12),
                                    releaseOverride = release.takeIf { it.isNotBlank() }?.intValue("Release", 1, 12),
                                    fastReplacementLevel = replacement.intValue("Replacement level", 5, 20),
                                    syntheticGp = syntheticGp.takeIf { it.isNotBlank() }?.intValue("GP", 0, 1000000),
                                    syntheticDays = syntheticDays.takeIf { it.isNotBlank() }?.intValue("Days", 1, 10000)))
                            }
                            mode = null
                        }
                    }) { Text("Save profile") }
                }
            }) {
            when (currentMode) {
                "elements" -> ElementDamageProfilesEditor(s, a) { detailsDirty = it }
                "melee" -> MeleeProfileEditor(s, a) { detailsDirty = it }
                "upgrades" -> WeaponDamageUpgrades(p, enabled = !a.busy, elements = elements,
                    onBaseCountSelected = { count ->
                        if (count != p.baseDamageDiceCount) a.change("Primary Force damage set to ${count}d${p.primaryForceDieSides}") {
                            it.copy(profile = it.profile.copy(version = it.profile.version + 1, baseDamageDiceCount = count))
                        }
                    }, onSelectorCountSelected = { count ->
                        if (count != p.elementalSelectorDiceCount) a.change("Elemental selector damage set to ${count}d8") {
                            it.copy(profile = it.profile.copy(version = it.profile.version + 1, elementalSelectorDiceCount = count))
                        }
                    })
                "edit" -> {
                    if (stale) Text("The profile changed while this editor was open. Close and reopen to review the current values.", color = MaterialTheme.colorScheme.error)
                    PlayerDisclosure("Character & attack", initiallyOpen = true) {
                        Field("Artificer level", level, { level = it }, enabled = !a.busy)
                        Field("INT modifier", intelligence, { intelligence = it }, enabled = !a.busy)
                        Field("Proficiency bonus", proficiency, { proficiency = it }, enabled = !a.busy)
                        Field("Spell save DC (independent)", dc, { dc = it }, enabled = !a.busy)
                    }
                    PlayerDisclosure("Calibration") {
                        Toggle("Calibration enabled · assumption", calibrationEnabled, !a.busy) { calibrationEnabled = it }
                        Field("Calibration bonus", calibration, { calibration = it }, enabled = !a.busy)
                        Toggle("Enable PROPOSED +2/+3 calibration progression", progression, !a.busy) { progression = it }
                    }
                    PlayerDisclosure("Confirmed upgrades") {
                        Text("Confirm installed upgrades independently. Level gates still apply.", color = RebuildPalette.Muted)
                        Upgrade.entries.filter { it in setOf(Upgrade.ADVANCED_ELEMENTS, Upgrade.DUAL_RESONANCE, Upgrade.DUAL_STORAGE) }.forEach { upgrade ->
                            Toggle("${upgrade.name.replace('_', ' ')} · ${if (p.has(upgrade)) "active" else "not active"}", upgrade in confirmed, !a.busy) { checked ->
                                val next = if (checked) confirmed + upgrade else confirmed - upgrade
                                confirmedNames = Upgrade.entries.filter { it in next }.joinToString(",") { it.name }
                            }
                        }
                    }
                    PlayerDisclosure("Payload & release limits") {
                        Field("Provisional spell firings per turn", limit, { limit = it }, enabled = !a.busy)
                        Field("Explicit storage capacity override (blank = milestone rules)", capacity, { capacity = it }, enabled = !a.busy)
                        Field("Proposed selector capacity override (blank = accepted 1/2)", elementCapacity, { elementCapacity = it }, enabled = !a.busy)
                        Field("Explicit release limit override (blank = milestone rules)", release, { release = it }, enabled = !a.busy)
                    }

                }
                "reference" -> {
                    PlayerDisclosure("Accepted attacks", initiallyOpen = true) {
                        Text("Attunement and a usable core are required. ${p.base} Force; 60/180 ft. Extra Attack gives two ordinary attacks within one Attack action. Selector changes are free. Explosions always take one Action.")
                    }
                    PlayerDisclosure("Discharge & permanent nexus") {
                        Text("Discharge: ${p.discharge} Force (inherited 3× Primary dice), 60 ft range / 15 ft radius. Each active selected element uses its configured formula once. Recovery is recorded in game time; no real-world countdown is used.")
                        Text("The permanent internal nexus cannot be swapped or replaced. Shatter is retired for new play; previous history remains readable.")
                        Text("A successful save halves each rolled Force/selector component, rounded down; spell payload/overcharge deals zero on success. No flat weapon bonus on explosions.")
                    }
                    PlayerDisclosure("Payloads · provisional") {
                        Text("One spell-powered firing per turn; spell shots replace all free selector dice; one stored payload until confirmed dual storage at level 12, then two. Loaded magic persists through rests.")
                    }
                    PlayerDisclosure("Assumptions & proposals") {
                        Text("Assumption: starter +1 calibration. Proposed, disabled by default: +2 at 10 / +3 at 15. Four/five payloads at level 20 are not finalized.")
                    }
                }
            }
            if (a.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
        }
    }
}

@Composable fun ResourcesPage(s: Campaign, history: List<HistorySummary>, a: Actions) {
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var seconds by rememberSaveable { mutableStateOf("600") }
    var timeBaseline by rememberSaveable { mutableLongStateOf(0L) }
    var slotLevel by rememberSaveable { mutableIntStateOf(1) }
    var slotCount by rememberSaveable { mutableStateOf("") }
    var slotBaseline by rememberSaveable { mutableStateOf<Int?>(null) }
    var requestedSlotLevel by rememberSaveable { mutableStateOf<Int?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var notesBaseline by rememberSaveable { mutableStateOf("") }
    var selectedHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val correction = history.firstOrNull { !it.undone && it.origin != "correction" }
    val historyEntry = history.firstOrNull { it.id == selectedHistoryId }
    val dirty = when (mode) {
        "time" -> seconds != "600"
        "slots" -> slotCount != slotBaseline?.toString().orEmpty()
        "notes" -> notes != notesBaseline
        else -> false
    }
    fun selectSlotLevel(next: Int) {
        slotLevel = next; slotBaseline = s.slots[next]; slotCount = slotBaseline?.toString().orEmpty()
        requestedSlotLevel = null
    }
    fun open(next: String) {
        a.message = ""
        if (next == "time") { seconds = "600"; timeBaseline = s.gameSeconds }
        if (next == "slots") { slotBaseline = s.slots[slotLevel]; slotCount = slotBaseline?.toString().orEmpty() }
        if (next == "notes") { notes = s.notes; notesBaseline = s.notes }
        mode = next
    }
    fun close() { if (!a.busy) mode = if (mode == "event" || mode == "correction") "history" else null }
    Page {
        PlayerHeading("Resources & recovery")
        PermanentNexusAdoption(s, a)
        PlayerCard {
            Text(if (s.encounter) "Weapon actions tracked" else "Weapon action limits off", style = MaterialTheme.typography.titleLarge)
            if (s.encounter) Text("${s.turn.attacks}/2 attacks used · ${if (s.turn.fullAction) "Action spent" else "Action available"}", color = RebuildPalette.Muted)
            if (s.encounter) Action("My next turn · reset weapon actions", !a.busy) {
                a.change("Player confirmed next weapon action window") { Engine.resetWeaponActions(it) }
            }
        }
        PlayerEntry("Weapon action limits", if (s.encounter) "Enforced on your turn" else "Optional manual enforcement", "Settings", { open("actions") })
        PlayerEntry("Internal nexus recovery", s.installed?.readiness(s.gameSeconds) ?: "Not configured", "Core", { open("time") })
        PlayerEntry("Spell slots", if (s.slots.isEmpty()) "Not reconciled" else s.slots.toSortedMap().entries.joinToString(" · ") { "L${it.key}: ${it.value}" }, "Payload", { open("slots") })
        PlayerEntry("Weapon notes", s.notes.ifBlank { "No notes" }, "More", { open("notes") })
        PlayerEntry("Accounting history", "${history.size} retained operations", "History", { open("history") })
        if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
    }
    mode?.let { currentMode ->
        val title = when (currentMode) {
            "actions" -> "Weapon action limits"; "time" -> "Confirm elapsed time"; "slots" -> "Spell slots"
            "notes" -> "Weapon notes"; "history" -> "Accounting history"; "correction" -> "Correct latest operation"
            else -> "Accounting event"
        }
        PlayerSheet(title, onDismiss = ::close, dirty = dirty, showCancel = false, actions = {
            val cancel = LocalPlayerDismiss.current
            if (currentMode in listOf("time", "slots", "notes", "correction")) PlayerTextButton(onClick = cancel, enabled = !a.busy) { Text("Cancel") }
            when (currentMode) {
                "time" -> PlayerButton(enabled = !a.busy && s.gameSeconds == timeBaseline, onClick = {
                    a.run("Player confirmed elapsed recovery time") {
                        a.repo.mutate(label = "Player confirmed elapsed recovery time") {
                            check(it.gameSeconds == timeBaseline) { "Game time changed. Reopen the elapsed-time editor." }
                            Engine.advance(it, seconds.intValue("Seconds", 0, 31536000).toLong())
                        }; mode = null
                    }
                }) { Text("Confirm time") }
                "slots" -> PlayerButton(enabled = !a.busy && slotCount.isNotBlank() && s.slots[slotLevel] == slotBaseline, onClick = {
                    a.run("Manual slot reconciliation L$slotLevel") {
                        a.repo.mutate(label = "Manual slot reconciliation L$slotLevel") {
                            check(it.slots[slotLevel] == slotBaseline) { "This slot count changed. Reopen the editor." }
                            it.copy(slots = it.slots + (slotLevel to slotCount.intValue("Slots", 0, 99)))
                        }; mode = null
                    }
                }) { Text("Save count") }
                "notes" -> PlayerButton(enabled = !a.busy && notes != notesBaseline && s.notes == notesBaseline, onClick = {
                    a.run("Updated campaign notes") {
                        a.repo.mutate(label = "Updated campaign notes") {
                            check(it.notes == notesBaseline) { "Notes changed. Reopen the editor before saving." }
                            it.copy(notes = notes)
                        }; mode = null
                    }
                }) { Text("Save notes") }
                "correction" -> PlayerButton(enabled = !a.busy && correction != null, onClick = {
                    a.run("Logged correction applied") { a.repo.undo(); mode = "history" }
                }) { Text("Confirm correction") }
            }
        }) {
            when (currentMode) {
                "actions" -> {
                    Toggle("Enforce Astravar actions on my turn", s.encounter, !a.busy) { enabled ->
                        a.change("Weapon action limits set to $enabled; next weapon turn confirmed") { Engine.resetWeaponActions(it).copy(encounter = enabled) }
                    }
                    Text("Tracks two ordinary attacks or one full Action, one Bonus Action, and the configured spell-firing limit. No initiative, enemy or round tracking.", color = RebuildPalette.Muted)
                }
                "time" -> {
                    Text("Advance only time that has passed at your table.", color = RebuildPalette.Muted)
                    s.cores.filter { !it.usable(s.gameSeconds) && !it.destroyed }.forEach { Text("${it.name} · ${it.readiness(s.gameSeconds)}") }
                    s.installation?.let { Text("Installation · ${(it.completeAt - s.gameSeconds).coerceAtLeast(0)} game seconds remaining") }
                    Field("Elapsed game seconds", seconds, { seconds = it }, enabled = !a.busy)
                    if (s.gameSeconds != timeBaseline) Text("Game time changed. Close and reopen this editor.", color = MaterialTheme.colorScheme.error)
                }
                "slots" -> {
                    Text("Enter actual remaining slots. Unknown levels have no counter; rests never refill them automatically.", color = RebuildPalette.Muted)
                    Choices("Spell level", (1..9).toList(), slotLevel, enabled = !a.busy) {
                        if (slotCount != slotBaseline?.toString().orEmpty()) requestedSlotLevel = it else selectSlotLevel(it)
                    }
                    requestedSlotLevel?.let { next ->
                        AlertDialog(onDismissRequest = { requestedSlotLevel = null }, title = { Text("Discard this count?") },
                            text = { Text("The unsaved count for level $slotLevel will be discarded before changing levels.") },
                            confirmButton = { PlayerTextButton(onClick = { selectSlotLevel(next) }) { Text("Discard count") } },
                            dismissButton = { PlayerTextButton(onClick = { requestedSlotLevel = null }) { Text("Keep editing") } })
                    }
                    Field("Confirmed remaining slots", slotCount, { slotCount = it }, enabled = !a.busy)
                    if (s.slots[slotLevel] != slotBaseline) Text("This count changed. Close and reopen the editor.", color = MaterialTheme.colorScheme.error)
                    PlayerOutlinedButton(enabled = !a.busy && s.slots[slotLevel] == slotBaseline, onClick = {
                        a.run("Slot level $slotLevel marked unknown") {
                            a.repo.mutate(label = "Slot level $slotLevel marked unknown") {
                                check(it.slots[slotLevel] == slotBaseline) { "This slot count changed. Reopen the editor." }
                                it.copy(slots = it.slots - slotLevel)
                            }; mode = null
                        }
                    }) { Text("Mark this level unknown") }
                }
                "notes" -> {
                    Field("Weapon / crafting notes", notes, { notes = it }, single = false, enabled = !a.busy)
                    if (s.notes != notesBaseline) Text("Notes changed. Close and reopen this editor.", color = MaterialTheme.colorScheme.error)
                }
                "history" -> {
                    if (history.isEmpty()) PlayerEmpty("No accounting history", "Weapon operations appear here after they are saved.", "History")
                    if (correction != null) PlayerOutlinedButton(onClick = { mode = "correction" }, enabled = !a.busy) { Text("Correct latest operation") }
                    history.forEach { entry ->
                        PlayerEntry(entry.label, "Game ${entry.gameSeconds}s · ${entry.origin}${if (entry.undone) " · corrected" else ""}", "History",
                            { selectedHistoryId = entry.id; mode = "event" })
                    }
                }
                "event" -> historyEntry?.let { entry ->
                    Text(entry.label, style = MaterialTheme.typography.titleMedium)
                    Text("Game ${entry.gameSeconds}s · ${entry.origin}", color = RebuildPalette.Muted)
                    if (entry.undone) Text("Reversed by a logged correction.", color = RebuildPalette.Ice)
                } ?: Text("This event is no longer in retained history.")
                "correction" -> {
                    Text(correction?.label ?: "No retained operation to undo.", style = MaterialTheme.typography.titleMedium)
                    Text("Reverses the latest retained operation, including its resources and pending results. A correction event is kept. Rolls removed by the retention limit cannot be recovered.", color = RebuildPalette.Muted)
                }
            }
            if (a.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
        }
    }
}
@Composable fun BackupPage(s: Campaign,a: Actions) {
    val c=LocalContext.current
    var incoming by remember { mutableStateOf<Backup?>(null) }
    var backupSaved by remember { mutableStateOf(false) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri -> if(uri!=null) a.run("Versioned backup saved") {
        val text=a.repo.export(); withContext(Dispatchers.IO) { c.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) } }; backupSaved=true
    } }
    val session=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {uri -> if(uri!=null) a.run("Session export saved") { withContext(Dispatchers.IO) {c.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(BackupCodec.session(s).toByteArray())}} } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null) a.run("Backup validated; review before replacing") {
        incoming=withContext(Dispatchers.IO) {c.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use {BackupCodec.decode(it)}}
        backupSaved=false
    } }
    Section("Portable backups") {
        Text("Versioned JSON contains campaign state and up to ${HistoryRetention.MAX_HISTORY_ENTRIES} recent rolls with bounded accounting history. Files stay where you choose. Import validates size, IDs, dice, levels, clocks, and references before an atomic replacement. Nothing is merged implicitly.")
        Action("Export complete JSON backup",!a.busy) {export.launch("astravar-backup.json")}
        Action("Export player-safe session Markdown",!a.busy) {session.launch("astravar-session.md")}
        Action("Choose JSON backup to preview",!a.busy) {import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
        incoming?.let { b ->
            Text("IMPORT PREVIEW\n${b.campaign.profile.character} · ${if(b.campaign.demo) "DEMO" else "campaign"}\n${b.campaign.cores.size} cores · ${b.campaign.templates.size} templates · ${b.campaign.pending.count {it.result==null}} unresolved operations · ${b.campaign.pending.size} recent rolls\nThis replaces current campaign state. Only the newest ${HistoryRetention.MAX_HISTORY_ENTRIES} imported rolls are retained. Older rolls and duplicate legacy archives are discarded; campaign resources and settings are preserved.")
            if(!backupSaved) Text("Save your current backup before replacing. This preserves a separate copy before replacement.")
            Action("Back up current state first",!a.busy) {export.launch("astravar-before-import.json")}
            val id=remember(b) {UUID.randomUUID().toString()}
            Action("Replace campaign with validated import",!a.busy && backupSaved,danger=true) {a.run("Campaign imported once") {a.repo.import(b,id)};incoming=null}
            PlayerTextButton(onClick={incoming=null}) {Text("Cancel import")}
        }
    }
}
@Composable fun SettingsPage(app: AstravarApp, s: Campaign, a: Actions, start: () -> Unit, grant: () -> Unit, stop: () -> Unit) {
    val prefs by app.preferences.flow.collectAsState(initial = com.yazsras.astravar.data.OverlayPrefs())
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var scaleBaseline by rememberSaveable { mutableFloatStateOf(1f) }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) a.run("Artwork selected") {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.preferences.image(uri.toString())
        }
    }
    Page {
        PlayerHeading("Overlay & appearance")
        PlayerCard {
            Text("Floating combat overlay", style = MaterialTheme.typography.titleLarge)
            Action("Start overlay session", onClick = start)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerOutlinedButton(onClick = grant, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Allow overlay") }
                PlayerOutlinedButton(onClick = stop, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Stop session") }
            }
        }
        PlayerEntry("Panel layout", "${"%.0f".format(prefs.scale * 100)}% scale · saved portrait and landscape positions", "Settings", {
            scale = prefs.scale; scaleBaseline = prefs.scale; a.message = ""; mode = "layout"
        })
        PlayerEntry("Feedback & advanced controls", "Haptics ${if (prefs.haptics) "on" else "off"} · manual dice ${if (prefs.manualOverrides) "on" else "off"}", "Settings", { a.message = ""; mode = "feedback" })
        PlayerEntry("Artwork", if (prefs.image.isBlank()) "Included Astravar artwork" else "Personal image selected", "Weapon", { a.message = ""; mode = "artwork" })
        if (s.demo) PlayerEntry("Leave demo", "Begin an empty campaign after review", "More", { a.message = ""; mode = "demo" })
        if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
    }
    mode?.let { currentMode ->
        PlayerSheet(when (currentMode) { "layout" -> "Panel layout"; "feedback" -> "Feedback & advanced controls"; "artwork" -> "Artwork"; else -> "End demo?" },
            onDismiss = { if (!a.busy) mode = null }, dirty = currentMode == "layout" && scale != scaleBaseline, showCancel = false,
            actions = {
                val close = LocalPlayerDismiss.current
                if (currentMode == "layout") {
                    PlayerTextButton(onClick = close, enabled = !a.busy) { Text("Cancel") }
                    PlayerButton(enabled = !a.busy && scale != scaleBaseline && prefs.scale == scaleBaseline, onClick = {
                        a.run("Panel scale saved") { app.preferences.scale(scale); mode = null }
                    }) { Text("Save scale") }
                }
                if (currentMode == "demo") {
                    PlayerTextButton(onClick = close, enabled = !a.busy) { Text("Cancel") }
                    PlayerButton(enabled = !a.busy, onClick = {
                        a.run("Left labeled demo; empty real campaign") {
                            a.repo.mutate(label = "Left labeled demo; empty real campaign") { current ->
                                check(current.demo) { "Demo is no longer active. Your campaign was kept." }
                                Campaign()
                            }; mode = null
                        }
                    }) { Text("End demo") }
                }
            }) {
            when (currentMode) {
                "layout" -> {
                    Text("Overlay scale: ${"%.0f".format(scale * 100)}%", style = MaterialTheme.typography.titleMedium)
                    Slider(scale, onValueChange = { scale = it }, valueRange = .85f..1.3f, enabled = !a.busy,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Overlay scale"
                            stateDescription = "${"%.0f".format(scale * 100)} percent"
                        })
                    if (prefs.scale != scaleBaseline) Text("Scale changed. Close and reopen to review it.", color = MaterialTheme.colorScheme.error)
                    Text("Drag the collapsed crystal to reposition it. Portrait and landscape positions are saved separately.", color = RebuildPalette.Muted)
                    PlayerOutlinedButton(enabled = !a.busy, onClick = {
                        a.run("Positions reset; restart overlay to apply") {
                            app.preferences.position(false, 0, 160); app.preferences.position(true, 0, 80)
                        }
                    }) { Text("Reset overlay positions") }
                }
                "feedback" -> {
                    Toggle("Haptic feedback", prefs.haptics, !a.busy) { value -> a.run("Feedback updated") { app.preferences.haptics(value) } }
                    Toggle("Advanced: manual roll override", prefs.manualOverrides, !a.busy) { value -> a.run("Manual override updated") { app.preferences.manualOverrides(value) } }
                    if (prefs.manualOverrides) Text("External dice become available in weapon shot options and are recorded separately from Astravar's rolls.", color = RebuildPalette.Muted)
                }
                "artwork" -> {
                    Text(if (prefs.image.isBlank()) "Included Astravar artwork is active." else "A personal image is selected.", color = RebuildPalette.Muted)
                    Action("Choose personal artwork", !a.busy) { image.launch(arrayOf("image/*")) }
                    PlayerOutlinedButton(enabled = !a.busy, onClick = { a.run("Original artwork restored") { app.preferences.image("") } }) { Text("Use included Astravar artwork") }
                }
                "demo" -> Text("Remove labeled demo resources and return to profile confirmation. Export the demo first if you want to keep it. No real resources will be assumed; correction history remains available.", color = RebuildPalette.Muted)
            }
            if (a.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
        }
    }
}
