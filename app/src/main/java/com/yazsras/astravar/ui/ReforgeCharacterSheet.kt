package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalDensity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/** Decorative only; text is native, readable, selectable and responsive to font scaling. */
@Composable private fun AstravarHeroBanner(title: String, subtitle: String) =
    PlayerArtBanner("THE ADVENTURER", title, subtitle, R.drawable.rebirth_atlas)

private enum class SpellbookFilter(val label: String) {
    ALL("All"), PREPARED("Prepared"), CANTRIPS("Cantrips");

    fun includes(spell: SpellDefinition): Boolean = when (this) {
        ALL -> true
        PREPARED -> spell.level > 0 && spell.preparation != SpellPreparation.KNOWN
        CANTRIPS -> spell.level == 0
    }
}

/** A local-first playable sheet. Legacy campaign adoption is reviewed and never changes its source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ReforgeCharacterSheet(repository: CampaignLedgerRepository, localRepository: ReforgeRepository? = null,
    initialSection: String = "Sheet", sectionOnly: Boolean = false,
    companionProfileId: String? = null, onBackToCompanions: (() -> Unit)? = null,
    navigationRequest: GenesisCharacterRoute? = null, onOpenArmor: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { Preferences(context) }
    val preferencesState by preferences.flow.collectAsState(initial = OverlayPrefs())
    val local = remember(localRepository, context) { localRepository ?: ReforgeRepository.open(context) }
    val profilesState by remember(local) { local.observeAll().map { true to it } }.collectAsState(initial = false to emptyList<ReforgeSavedCharacter>())
    val all = profilesState.second
    val active = all.filterNot { it.character.archived }
    val actorPreferences = remember(context) { ReforgePreferences.open(context) }
    val actorSelection by remember(actorPreferences) { actorPreferences.activeProfileId.map { true to it } }.collectAsState(initial = false to null)
    val legacyProfiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var consumedNavigation by rememberSaveable { mutableIntStateOf(-1) }
    var unavailableRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var inspecting by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var adopting by remember { mutableStateOf<ReforgeAdoptionPreview?>(null) }
    var proposedChange by remember { mutableStateOf<Pair<String, ReforgeCharacter>?>(null) }
    var cast by remember { mutableStateOf<CastPreview?>(null) }
    var rollRequest by rememberSaveable(stateSaver = Saver<ReforgeRollRequest?, String>(
        save = { it?.let { value -> Json.encodeToString(value) } ?: "" },
        restore = { it.takeIf(String::isNotEmpty)?.let { value -> Json.decodeFromString<ReforgeRollRequest>(value) } }
    )) { mutableStateOf<ReforgeRollRequest?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val rollNotice = remember { SnackbarHostState() }
    var savedRoll by remember { mutableStateOf<ReforgeRollRecord?>(null) }
    // A roster shortcut must never fall through to another character or change the default actor.
    val pendingNavigation = navigationRequest?.takeIf { it.token != consumedNavigation }
    val chosenId = companionProfileId ?: pendingNavigation?.profileId ?: unavailableRouteId
        ?: ReforgePreferences.chooseProfile(selectedId ?: actorSelection.second, active.map { it.character.id })
    val (spellQuery, setSpellQuery) = key(chosenId) { rememberSaveable { mutableStateOf("") } }
    val (spellFilter, setSpellFilter) = key(chosenId) { rememberSaveable { mutableStateOf(SpellbookFilter.ALL) } }
    val (gearQuery, setGearQuery) = key(chosenId) { rememberSaveable { mutableStateOf("") } }
    val (gearFilter, setGearFilter) = key(chosenId) { rememberSaveable { mutableStateOf("All gear") } }
    var requestedSection by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var sectionRequest by rememberSaveable { mutableIntStateOf(0) }
    fun openSection(value: String) { requestedSection = value; sectionRequest++ }
    // A pending dialog or rolled preview belongs to the actor that opened it.
    // Restore it on recreation; dismiss it only when the selected actor actually changes.
    var lastActor by rememberSaveable { mutableStateOf(chosenId) }
    LaunchedEffect(chosenId, profilesState.first, actorSelection.first) {
        if (!profilesState.first || (companionProfileId == null && !actorSelection.first)) return@LaunchedEffect
        if (lastActor != null && lastActor != chosenId) {
            dialog = null; inspecting = null; proposedChange = null; cast = null; rollRequest = null
            status = ""; error = ""
            openSection(initialSection)
        }
        lastActor = chosenId
    }
    val saved = active.firstOrNull { it.character.id == chosenId }
    val character = saved?.character
    val projection = character?.let { remember(it) { CharacterCalculator.calculate(it) } }
    LaunchedEffect(dialog) { if (dialog != null && !busy) error = "" }
    LaunchedEffect(actorSelection) { if (actorSelection.first && selectedId == actorSelection.second) selectedId = null }
    LaunchedEffect(savedRoll, chosenId) {
        val roll = savedRoll ?: return@LaunchedEffect
        if (roll.profileId != chosenId) {
            savedRoll = null
            return@LaunchedEffect
        }
        if (rollNotice.showSnackbar("Roll saved · ${roll.displayTotal}", "View history",
                withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            openSection("History")
        }
        savedRoll = null
    }
    fun selectProfile(id: String) {
        if (companionProfileId != null) return
        unavailableRouteId = null
        selectedId = id
        scope.launch {
            try { actorPreferences.selectProfile(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "The character is saved, but the last-opened profile could not be remembered." }
        }
    }
    fun action(label: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""; status = "Saving…"
        scope.launch {
            try { work(); status = label }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "Not saved"; error = e.message ?: "This action could not be saved. Your previous state is retained." }
            finally { busy = false }
        }
    }
    fun change(label: String, next: ReforgeCharacter, close: Boolean = true) {
        val snapshot = saved ?: return
        action("Saved on this device") {
            local.save(snapshot.character.id, snapshot.revision, next, label)
            if (close) { dialog = null; inspecting = null; proposedChange = null; cast = null }
        }
    }
    fun openRoll(request: ReforgeRollRequest) {
        val snapshot = saved ?: return
        inspecting = null
        error = ""
        savedRoll = null
        rollRequest = request.copy(profileId = snapshot.character.id, characterRevision = snapshot.revision)
    }
    fun requestRoll(label: String, modifier: Int, kind: RollKind = RollKind.CUSTOM, key: String = "") =
        openRoll(ReforgeRollRequest(label, DiceFormula(listOf(DiceTerm(1, 20)), modifier), kind, key))
    fun reviewProfile(id: String) = action("Profile ready for review") {
        adopting = local.readLegacyPreview(repository, id)
        dialog = "Adopt"
    }
    fun quickAction(value: String) {
        dialog = null
        when (value) {
            "health" -> dialog = "HP"
            "attack" -> openSection("Combat")
            "spells" -> openSection("Spells")
            "inventory" -> openSection("Equipment")
            "conditions" -> dialog = "Conditions"
            "rest" -> dialog = "Rest"
            "resources" -> openSection("Resources")
            "dice" -> openRoll(ReforgeRollRequest("Custom roll", null))
        }
    }

    LaunchedEffect(pendingNavigation?.token, profilesState.first, actorSelection.first) {
        val route = pendingNavigation ?: return@LaunchedEffect
        if (!profilesState.first || !actorSelection.first) return@LaunchedEffect
        if (active.none { it.character.id == route.profileId }) {
            unavailableRouteId = route.profileId
            error = "That saved sheet is unavailable or archived. No other character was opened."
        } else {
            if (companionProfileId == null) selectProfile(route.profileId)
            openSection(route.section.takeIf { it in genesisCharacterSections } ?: "Sheet")
            val target = active.first { it.character.id == route.profileId }.character
            dialog = route.detailDialog?.takeIf { detail ->
                val id = detail.substringAfter(':', "")
                when (detail.substringBefore(':')) {
                    "Item" -> detail == "Item"
                    "InspectItem" -> target.items.any { it.id == id }
                    "InspectSpell" -> target.spells.any { it.id == id }
                    "Attack" -> target.attacks.any { it.id == id }
                    "Resource" -> target.resources.any { it.id == id }
                    else -> false
                }
            }
            if (route.detailDialog != null && dialog == null) error = "That record is no longer available. Its character is open."
        }
        consumedNavigation = route.token
    }

    CompositionLocalProvider(LocalGenesisEditorFeedback provides GenesisEditorFeedback(busy, error)) {
    // The reusable sheet owns its canvas, including transparent tabs and quick
    // actions; it must not inherit an Activity or host's default white window.
    Column(Modifier.fillMaxSize().background(RebuildPalette.Canvas)) {
        if (onBackToCompanions != null) {
            PlayerTextButton(onClick = onBackToCompanions, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Back to companions")
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val compactChrome = maxHeight < 480.dp || (LocalDensity.current.fontScale >= 1.5f && maxHeight < 640.dp)
            if (character == null || projection == null || !profilesState.first || (companionProfileId == null && !actorSelection.first)) {
                Page(resetKey = chosenId) {
                    if (!profilesState.first || (companionProfileId == null && !actorSelection.first)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Opening your saved character…", color = RebuildPalette.Muted)
                        return@Page
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reforge-error"))
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelMedium, color = RebuildPalette.Ice, modifier = Modifier.testTag("reforge-save-status"))
                    if (character == null || projection == null) {
                        if (unavailableRouteId != null && companionProfileId == null) {
                            PlayerEmpty("Sheet unavailable", "The search result is no longer available. Your selected character is unchanged.")
                            PlayerButton(onClick = { unavailableRouteId = null; error = "" }) { Text("Back to character") }
                            return@Page
                        }
                        if (companionProfileId != null) {
                            val archived = all.firstOrNull { it.character.id == companionProfileId }?.character
                            val source = legacyProfiles.firstOrNull { it.id == companionProfileId }
                            PlayerHeading(archived?.name ?: source?.name?.takeIf(String::isNotBlank) ?: "Companion sheet")
                            when {
                                archived != null -> {
                                    PlayerEmpty("Playable sheet archived", "Restore this companion's saved sheet to use it again.")
                                    PlayerButton(enabled = !busy, onClick = {
                                        action("Playable sheet restored") { local.setArchived(archived, false) }
                                    }) { Text("Restore playable sheet") }
                                }
                                source != null -> {
                                    PlayerEmpty("Ready for review", "Review this companion's saved campaign profile to create a playable sheet on this device.")
                                    PlayerButton(enabled = !busy, onClick = { reviewProfile(source.id) }) { Text("Review campaign profile") }
                                }
                                else -> PlayerEmpty("Companion sheet unavailable", "No saved profile was found for this companion.")
                            }
                            return@Page
                        }
                        PlayerHeading("Your character")
                        AstravarHeroBanner("Make your legend", "Your character, equipment and story. In one place.")
                        PlayerButton(onClick = { dialog = "Create" }) { Text("Create character") }
                        if (all.any { it.character.archived }) PlayerOutlinedButton(onClick = { dialog = "Characters" }) { Text("View archived sheets") }
                        if (legacyProfiles.isNotEmpty()) {
                            Text("Saved campaign profiles", style = MaterialTheme.typography.titleMedium)
                            legacyProfiles.filter { profile -> all.none { it.character.id == profile.id } }.forEach { profile -> PlayerEntry(profile.name.ifBlank { "Unnamed character" },
                                "Review saved values · original record retained", "Emblem", {
                                    reviewProfile(profile.id)
                                }) }
                        }
                        Text("Your playable sheets are saved on this device. Campaign sync does not include these sheets yet.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
        } else {
            key(character.id) {
                Column(Modifier.fillMaxSize()) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("reforge-error"))
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall,
                        color = RebuildPalette.Ice, modifier = Modifier.padding(horizontal = 16.dp).testTag("reforge-save-status"))
                    GenesisCharacterHeader(character, projection,
                        onSwitch = if (companionProfileId == null) ({ dialog = "Characters" }) else null,
                        onHp = { dialog = "HP" }, onDefense = { dialog = "Defense" },
                        onInitiative = { projection.initiative.value?.let { requestRoll("Initiative", it, RollKind.INITIATIVE) } },
                        compact = compactChrome, onControls = { dialog = "Controls" })
                    if (!sectionOnly && !compactChrome) GenesisQuickActions(character.id, ::quickAction)
                    GenesisCharacterPager(
                        actorId = character.id,
                        requestedSection = if (lastActor != null && lastActor != chosenId) initialSection else requestedSection,
                        requestToken = sectionRequest,
                        sectionOnly = sectionOnly,
                        showPageHint = !compactChrome,
                        modalOpen = dialog != null || inspecting != null || proposedChange != null || cast != null || rollRequest != null,
                        modifier = Modifier.weight(1f)
                    ) { section ->
                        when (section) {
                            "Sheet" -> {
                                RebirthAbilities(projection) { inspecting = it.name }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PlayerButton(onClick = { openRoll(ReforgeRollRequest("Custom roll", null)) }, modifier = Modifier.weight(1f)) {
                                        ArcaneIcon("d20", Modifier.size(20.dp), MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp)); Text("Roll dice")
                                    }
                                    PlayerTextButton(onClick = { dialog = "Setup" }, modifier = Modifier.weight(1f)) { Text("Character setup") }
                                }
                                RebirthSectionLabel("Saving throws") {
                                    PlayerTextButton(onClick = { dialog = "Training" }) { Text("Training") }
                                }
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val columns = if (maxWidth >= 360.dp && LocalDensity.current.fontScale < 1.25f &&
                                        Stat.entries.all { projection.saves[it]?.value != null }) 2 else 1
                                    Column {
                                        Stat.entries.chunked(columns).forEach { stats ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                                stats.forEach { stat ->
                                                    Column(Modifier.weight(1f)) {
                                                        val value = projection.saves[stat]?.value
                                                        RebirthCheckRow("${stat.name} save", if (stat in character.saveProficiencies) "Proficient" else "", value,
                                                            modifier = Modifier.testTag("reforge-save-${stat.name}")) {
                                                            value?.let { requestRoll("${stat.name} save", it, RollKind.SAVE, stat.name) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                RebirthSectionLabel("Skills")
                                Column {
                                    reforgeSkills.forEach { (name, stat) ->
                                        val value = projection.skills[name]?.value ?: projection.abilities[stat]?.modifier
                                        val skill = character.skills[name]
                                        RebirthCheckRow(name, when {
                                            skill?.expertise == true -> "${stat.name} · Expertise"
                                            skill?.proficient == true -> "${stat.name} · Proficient"
                                            skill?.halfProficiency == true -> "${stat.name} · Half proficiency"
                                            else -> stat.name
                                        }, value) { value?.let { requestRoll(name, it, RollKind.SKILL, name) } }
                                    }
                                }
                                PlayerDisclosure("Passive scores & movement", initiallyOpen = true) {
                                    listOf("Perception", "Investigation", "Insight").forEach { name ->
                                        val ability = reforgeSkills.getValue(name)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(name, color = RebuildPalette.Muted)
                                            Text("${projection.passiveScores[name] ?: projection.abilities[ability]?.modifier?.plus(10) ?: "Not set"}")
                                        }
                                    }
                                    Text("Walking speed · ${projection.speed.value?.let { "$it ft." } ?: "Not set"}")
                                }
                                PlayerTextButton(onClick = { dialog = "CampaignConnections" }) { Text("Campaign connections") }
                                PlayerTextButton(onClick = { openSection("Features") }) { Text("Features & background") }
                                if (saved!!.origin.sourceProfileId != null) PlayerDisclosure("Imported sheet · review before play") {
                                    Text("Training, attacks, spells, resources, and movement need your review before relying on calculated values.",
                                        color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("reforge-setup-review"))
                                    Text(saved.saveState, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                PlayerDisclosure("Rules & calculation notices") {
                                    Text("Core · ${character.rules.core}\nClass · ${character.rules.classVersion}", color = RebuildPalette.Muted)
                                    (projection.warnings + saved!!.origin.warnings).distinct().forEach { Text(it, color = RebuildPalette.GoldBright) }
                                    Text("Current resources are never inferred from capacity, equipment, an update, or opening the app.", color = RebuildPalette.Muted)
                                }
                            }
                            "Combat" -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PlayerButton(onClick = { dialog = "HP" }, modifier = Modifier.weight(1f)) { Text("Damage / healing") }
                                    PlayerOutlinedButton(onClick = { dialog = "Conditions" }, modifier = Modifier.weight(1f)) { Text("Conditions") }
                                }
                                if (character.conditions.isNotEmpty() || character.concentrationSpellId != null) {
                                    Column(Modifier.fillMaxWidth().background(RebuildPalette.Panel, RoundedCornerShape(8.dp)).padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (character.conditions.isNotEmpty()) Text("Conditions · ${character.conditions.joinToString()}", color = RebuildPalette.GoldBright)
                                        if (character.concentrationSpellId != null) {
                                            Text("Concentration", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelMedium)
                                            Text(character.spells.firstOrNull { it.id == character.concentrationSpellId }?.name ?: "Spell not set", color = RebuildPalette.Purple)
                                            PlayerTextButton(onClick = { change("End concentration", character.copy(concentrationSpellId = null), false) }) { Text("End concentration") }
                                        }
                                    }
                                }
                                if (onOpenArmor != null && companionProfileId == null && character.id == "local-player" &&
                                    saved!!.origin.sourceProfileId == "local-player") {
                                    PlayerOutlinedButton(onClick = onOpenArmor, modifier = Modifier.fillMaxWidth()) {
                                        ArcaneIcon("Weapon", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Astravar armor attacks")
                                    }
                                }
                                RebirthSectionLabel("Attacks") { PlayerTextButton(onClick = { dialog = "Attack" }) { Text("Add attack") } }
                                if (character.attacks.isEmpty()) PlayerEmpty("Set up your attacks", "Add an attack using your accepted damage dice and ability. Your specialized armor weapon remains in Tools → Armor weapon.", "Weapon")
                                Column {
                                    character.attacks.forEach { attack -> key(attack.id) {
                                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(attack.name, style = MaterialTheme.typography.titleMedium)
                                                    Text("${attack.actionType} · ${attack.range}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                                                }
                                                PlayerTextButton(onClick = { dialog = "Attack:${attack.id}" }) { Text("Edit") }
                                            }
                                            if (attack.damage.isNotEmpty()) Text("Base damage · ${attack.damage.joinToString(" + ") { "${SecureDice.format(it.formula)} ${it.type.name.lowercase()}" }}",
                                                color = RebuildPalette.Silver, style = MaterialTheme.typography.bodyMedium)
                                            PlayerOutlinedButton(onClick = { openRoll(ReforgeRollRequest(attack.name, null, RollKind.ATTACK, attack.id, attack.id)) },
                                                modifier = Modifier.fillMaxWidth()) {
                                                ArcaneIcon("d20", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Roll attack & damage")
                                            }
                                        }
                                        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .55f))
                                    } }
                                }
                                RebirthSectionLabel("Turn economy") { PlayerTextButton(onClick = { dialog = "Turn" }) { Text("Configure") } }
                                if (character.turn.tracking) {
                                    Column {
                                        listOf(
                                            Triple("Action", character.turn.actionAvailable, {
                                                change("Recorded Action used", character.copy(turn = character.turn.copy(actionAvailable = false, attacksUsed = character.turn.attacksPerAction)), false)
                                            }),
                                            Triple("Bonus Action", character.turn.bonusActionAvailable, {
                                                change("Recorded Bonus Action used", character.copy(turn = character.turn.copy(bonusActionAvailable = false)), false)
                                            }),
                                            Triple("Reaction", character.turn.reactionAvailable, {
                                                change("Recorded Reaction used", character.copy(turn = character.turn.copy(reactionAvailable = false)), false)
                                            })
                                        ).forEach { (label, available, spend) ->
                                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                                if (available) PlayerTextButton(onClick = spend) { Text("Mark $label used") }
                                                else Text("Spent", color = RebuildPalette.Muted)
                                            }
                                            HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .45f))
                                        }
                                    }
                                    Text("Attacks ${character.turn.attacksUsed} / ${character.turn.attacksPerAction} · Spell slot this turn ${if (character.turn.spellSlotSpent) "spent" else "not spent"}", color = RebuildPalette.Muted)
                                } else Text("Tracking off · saved availability retained", color = RebuildPalette.Muted)
                                PlayerOutlinedButton(onClick = { change("Confirmed next turn", GameplayRules.nextTurn(character), false) }) { Text("My next turn") }
                                Text("Next turn clears per-turn effect use. It does not complete a rest or advance game time.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            "Equipment" -> {
                                RebirthSectionLabel("Equipment") {
                                    PlayerTextButton(onClick = { dialog = "Item" }) { Text("Add item") }
                                }
                                Text("Attuned · ${character.items.filter { it.attuned }.sumOf { instance -> character.itemDefinitions.firstOrNull { it.id == instance.definitionId }?.attunementCost ?: 0 }} / ${character.attunementLimit}",
                                    color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodyMedium)
                                PlayerSearch(gearQuery, setGearQuery, "Find equipment")
                                PlayerTabs(listOf("All gear", "Equipped", "Attuned"), gearFilter, setGearFilter, Modifier.genesisHorizontalControl())
                                val visibleItems = character.items.filter { item ->
                                    val definition = character.itemDefinitions.firstOrNull { it.id == item.definitionId }
                                    (gearQuery.isBlank() || definition?.name?.contains(gearQuery.trim(), ignoreCase = true) == true) &&
                                        when (gearFilter) {
                                            "Equipped" -> item.state in setOf(ItemState.WORN, ItemState.HELD)
                                            "Attuned" -> item.attuned
                                            else -> true
                                        }
                                }
                                if (character.items.isEmpty()) PlayerEmpty("Add your equipment", "Carry, equip and manage your items here. Reviewed effects connect directly to your statistics.", "Core")
                                else if (visibleItems.isEmpty()) PlayerEmpty("No matching equipment", "Try another name or show all gear.", "Core")
                                Column {
                                    visibleItems.forEach { item -> key(item.id) {
                                        val definition = character.itemDefinitions.firstOrNull { it.id == item.definitionId }
                                        RebirthItemRow(item, definition, busy, onInspect = { dialog = "InspectItem:${item.id}" }, onEquip = {
                                            try {
                                                val next = GameplayRules.equip(character, item.id, if (item.state in setOf(ItemState.WORN, ItemState.HELD)) ItemState.CARRIED else ItemState.WORN)
                                                proposedChange = "${if (item.state in setOf(ItemState.WORN, ItemState.HELD)) "Unequip" else "Equip"} ${definition?.name ?: "item"}" to next
                                            } catch (e: Exception) { error = e.message ?: "Item requirements not met" }
                                        }, onManage = { error = ""; dialog = "ManageItem:${item.id}" })
                                    } }
                                }
                                PlayerOutlinedButton(onClick = { dialog = "ImportItem" }) {
                                    Text("Add from campaign inventory")
                                }
                                PlayerDisclosure("About campaign inventory") {
                                    Text("Equipped items affect this character's calculations. Campaign items keep the original records. Adding one here creates an explicitly reviewed copy, with no automatic magical effects.",
                                        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            "Spells" -> {
                                RebirthSectionLabel("Spellbook") {
                                    PlayerTextButton(onClick = { dialog = "Spell" }) { Text("Add spell") }
                                }
                                Row(Modifier.fillMaxWidth().background(RebuildPalette.Panel, RoundedCornerShape(8.dp)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("SPELL ATTACK", style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
                                        PlayerTextButton(enabled = projection.spellAttack.value != null, onClick = {
                                            projection.spellAttack.value?.let { requestRoll("Spell attack", it, RollKind.SPELL_ATTACK) }
                                        }, modifier = Modifier.semantics { contentDescription = "Roll spell attack" }) {
                                            Text(reforgeSigned(projection.spellAttack.value), style = MaterialTheme.typography.headlineSmall)
                                            Spacer(Modifier.width(8.dp)); ArcaneIcon("d20", Modifier.size(20.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("SAVE DC", style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
                                        Text("${projection.spellSaveDc.value ?: "Not set"}", style = MaterialTheme.typography.headlineSmall,
                                            color = RebuildPalette.Silver, modifier = Modifier.padding(vertical = 10.dp))
                                    }
                                }
                                if (projection.slotMaxima.isNotEmpty()) {
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        projection.slotMaxima.forEach { (level, max) ->
                                            Column(Modifier.widthIn(min = 92.dp).clickable(role = Role.Button) { dialog = "Slots" }
                                                .padding(vertical = 8.dp, horizontal = 4.dp)) {
                                                Text("LEVEL $level", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelSmall)
                                                Text("${character.spellSlots[level] ?: "Not set"} / $max", color = RebuildPalette.Purple,
                                                    style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                    }
                                }
                                PlayerDisclosure("Spell slots & preparation") {
                                    Text("Prepared ${character.spells.count { it.preparation == SpellPreparation.PREPARED && it.level > 0 }} / ${projection.preparationLimit ?: "not set"}", color = RebuildPalette.Muted)
                                    if (projection.slotMaxima.isEmpty()) Text("Configure your pinned class and slot capacity in Character setup.", color = RebuildPalette.GoldBright)
                                    PlayerTextButton(onClick = { dialog = "Slots" }) { Text("Set current slots") }
                                }
                                if (character.spells.isEmpty()) {
                                    PlayerEmpty("No spells yet", "Add a spell to record its rules, prepare it, or cast it from this sheet.")
                                } else {
                                    PlayerSearch(spellQuery, setSpellQuery, "Find a spell by name")
                                    PlayerTabs(SpellbookFilter.entries.map { it.label }, spellFilter.label,
                                        { label -> setSpellFilter(SpellbookFilter.entries.first { it.label == label }) }, Modifier.genesisHorizontalControl())
                                    val spellsByLevel = remember(character.spells, spellQuery, spellFilter) {
                                        val query = spellQuery.trim()
                                        character.spells
                                            .filter { spellFilter.includes(it) && it.name.contains(query, ignoreCase = true) }
                                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { spell: SpellDefinition -> spell.name })
                                            .groupBy { it.level }
                                            .toSortedMap()
                                    }
                                    if (spellsByLevel.isEmpty()) {
                                        PlayerEmpty("No matching spells", "Try a different name or show all spells.")
                                        PlayerOutlinedButton(onClick = { setSpellQuery(""); setSpellFilter(SpellbookFilter.ALL) }) { Text("Show all spells") }
                                    }
                                    spellsByLevel.forEach { (level, spells) ->
                                        Text(if (level == 0) "Level 0 · Cantrips" else "Level $level",
                                            Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Purple)
                                        Column {
                                            spells.forEach { spell -> key(spell.id) {
                                                RebirthSpellRow(spell, onInspect = { dialog = "InspectSpell:${spell.id}" }, onCast = { dialog = "Cast:${spell.id}" })
                                            } }
                                        }
                                    }
                                }
                            }
                            "Features" -> {
                                ReforgeIdentitySection(character, onEdit = { dialog = "Identity" }, onNotes = { dialog = "Notes" }, onAdvance = { dialog = "Advance" })
                                if (character.features.isEmpty()) PlayerEmpty("Your features & feats",
                                    "Add your accepted class features, feats and homebrew through Edit identity & features. Unreviewed source material does not become an active mechanic.")
                            }
                            "Resources" -> {
                                RebirthSectionLabel("Limited uses") { PlayerTextButton(onClick = { dialog = "Resource" }) { Text("Add resource") } }
                                PlayerButton(onClick = { dialog = "Rest" }) { Text("Rest & recovery") }
                                Column {
                                    character.resources.forEach { resource -> key(resource.id) {
                                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(resource.name, style = MaterialTheme.typography.titleMedium)
                                                    Text(resource.recharge.name.lowercase().replace('_', ' '), color = RebuildPalette.Muted,
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                                Text("${resource.current ?: "Not set"} / ${projection.resourceMaxima[resource.id] ?: "Not set"}",
                                                    color = RebuildPalette.GoldBright, style = MaterialTheme.typography.titleLarge)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                PlayerTextButton(onClick = { dialog = "Resource:${resource.id}" }) { Text("Edit") }
                                                if (resource.dieSides != null) PlayerOutlinedButton(onClick = { dialog = "HitDie:${resource.id}" }) { Text("Spend Hit Die") }
                                                PlayerTextButton(enabled = (resource.current ?: 0) > 0, onClick = {
                                                    change("Use ${resource.name}", character.copy(resources = character.resources.map { if (it.id == resource.id) it.copy(current = requireNotNull(it.current) - 1) else it }), false)
                                                }) { Text("Use one") }
                                            }
                                        }
                                        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .55f))
                                    } }
                                }
                                if (character.resources.isEmpty()) PlayerEmpty("No resources configured", "Add Hit Dice, charges or limited abilities with their accepted capacity and recovery trigger.")
                            }
                            "History" -> {
                                val rolls by remember(local, character.id) { local.rollHistory(character.id) }.collectAsState(initial = emptyList())
                                val changes by remember(local, character.id) { local.historySummaries(character.id) }.collectAsState(initial = emptyList())
                                PlayerHeading("Roll history")
                                if (rolls.isEmpty()) Text("No rolls yet. Rolls occur only when you tap Roll or enter a manual result.", color = RebuildPalette.Muted)
                                rolls.forEach { roll -> key(roll.id) { ReforgeSavedRollCard(roll) } }
                                PlayerDisclosure("Changes & saved revisions") { changes.take(30).forEach { Text("${it.operation} · revision ${it.priorRevision} → ${it.resultingRevision}", color = RebuildPalette.Muted) } }
                                PlayerDisclosure("Original sources & advanced details") {
                                    Text(saved!!.origin.label)
                                    Text("Record ${character.id}\nRevision ${saved!!.revision}\n${saved!!.origin.sourceFingerprint ?: "Created here"}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                                    saved!!.origin.warnings.forEach { Text(it, color = RebuildPalette.GoldBright) }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(rollNotice, Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp))
        }
    }
    if (dialog == "Create") ReforgeCharacterEditor(null, { dialog = null }) { next -> action("Character saved on this device") { local.create(next); selectProfile(next.id); dialog = null } }
    if (dialog == "Adopt") adopting?.let { preview ->
        var mechanicsReviewed by rememberSaveable(preview.legacyFingerprint) { mutableStateOf(false) }
        PlayerSheet("Review campaign profile", { dialog = null }) {
        Text(preview.character.name, style = MaterialTheme.typography.headlineSmall)
        Text("This creates a separate playable sheet. Your campaign profile, inventory history and existing synchronization stay intact.", color = RebuildPalette.Muted)
        preview.warnings.forEach { Text(it, color = RebuildPalette.GoldBright) }
        Stat.entries.forEach { Text("${it.name} · ${preview.character.baseAbilities[it] ?: "Unknown"}") }
        Text("Level ${preview.character.level} · HP ${preview.character.currentHp ?: "Not set"} / ${preview.character.baseMaxHp ?: "Not set"}")
        Toggle("I will review training, actions and current resources before relying on this sheet", mechanicsReviewed) { mechanicsReviewed = it }
        PlayerButton(onClick = { dialog = "AdoptEdit" }, enabled = mechanicsReviewed) { Text("Review baselines & create sheet") }
    } }
    if (dialog == "AdoptEdit") adopting?.let { preview -> ReforgeCharacterEditor(preview.character, { dialog = null }) { reviewed ->
        action("Reviewed sheet saved on this device") { local.adopt(repository, preview, reviewed); selectProfile(reviewed.id); dialog = null; adopting = null }
    } }
    if (dialog == "Characters") PlayerSheet("Characters & companions", { dialog = null }) {
        active.forEach { profile ->
            val summary = listOf(profile.character.species, profile.character.className).filter(String::isNotBlank).joinToString(" · ")
            PlayerEntry(profile.character.name, "Level ${profile.character.level}${if (summary.isBlank()) "" else " · $summary"}", "Emblem",
                { selectProfile(profile.character.id); dialog = null }, trailing = if (profile.character.id == chosenId) "Selected" else null)
        }
        if (active.isNotEmpty()) PlayerDisclosure("Manage saved sheets") {
            Text("Archive a sheet to put it away without losing equipment, notes or history.", color = RebuildPalette.Muted)
            active.forEach { profile ->
                PlayerTextButton(enabled = !busy, onClick = { dialog = "ArchiveCharacter:${profile.character.id}" }) { Text("Archive ${profile.character.name}") }
            }
        }
        if (all.any { it.character.archived }) PlayerDisclosure("Archived sheets", initiallyOpen = active.isEmpty()) {
            Text("Archived sheets keep all equipment, notes, source records and saved history. Restore a sheet to use it again.", color = RebuildPalette.Muted)
            all.filter { it.character.archived }.forEach { profile ->
                PlayerOutlinedButton(enabled = !busy, onClick = {
                    action("Playable sheet restored") {
                        local.setArchived(profile.character, false); selectProfile(profile.character.id); dialog = null
                    }
                }) { Text("Restore ${profile.character.name}") }
            }
        }
        legacyProfiles.filter { profile -> all.none { it.character.id == profile.id } }.forEach { profile -> PlayerEntry(profile.name.ifBlank { "Unnamed profile" }, "Review saved campaign profile", "History", {
            reviewProfile(profile.id)
        }) }
        PlayerButton(onClick = { dialog = "Create" }) { Text("Create character or companion") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    dialog?.takeIf { it.startsWith("ArchiveCharacter:") }?.substringAfter(':')?.let { id ->
        all.firstOrNull { it.character.id == id && !it.character.archived }?.let { profile ->
            val snapshot = requireNotNull(rememberReforgeSnapshot(profile.character))
            PlayerSheet("Archive ${snapshot.name}?", { dialog = "Characters" }, actions = {
                PlayerButton(enabled = !busy, onClick = {
                    action("Playable sheet archived") { local.setArchived(snapshot, true); dialog = null }
                }) { Text("Archive sheet") }
            }) {
                Text("Remove this sheet from daily play? All values, equipment, notes, source identities and saved history stay available in Archived sheets. Original campaign records are unchanged.", color = RebuildPalette.Muted)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (character != null && projection != null) {
        when (dialog) {
            "Controls" -> PlayerSheet("Character controls", { dialog = null }) {
                GenesisCharacterHeader(character, projection,
                    onSwitch = if (companionProfileId == null) ({ dialog = "Characters" }) else null,
                    onHp = { dialog = "HP" }, onDefense = { dialog = "Defense" },
                    onInitiative = { projection.initiative.value?.let { dialog = null; requestRoll("Initiative", it, RollKind.INITIATIVE) } })
                GenesisQuickActions(character.id, ::quickAction)
            }
            "Identity" -> ReforgeIdentityEditor(character, { dialog = null }) { change("Edit identity & features", it) }
            "Notes" -> ReforgeNotesEditor(saved!!, local, { dialog = null })
            "Advance" -> ReforgeAdvancementDialog(character, { dialog = null }) { change("Confirmed character advancement", it) }
            "Setup" -> ReforgeCharacterEditor(character, { dialog = null }) { change("Edit character setup", it) }
            "CampaignConnections" -> ReforgeCampaignConnectionsDialog(saved!!, local, repository, { dialog = null })
            "Training" -> ReforgeTrainingEditor(character, { dialog = null }) { change("Edit training", it) }
            "Item" -> ReforgeItemEditor(character, { dialog = null }, onSave = { change("Add reviewed equipment", it) })
            "ImportItem" -> GenesisCampaignItemPicker(saved!!, local, repository) { dialog = null }
            "Attack" -> ReforgeAttackEditor(character, { dialog = null }, onSave = { change("Add attack", it) })
            "Spell" -> ReforgeSpellEditor(character, { dialog = null }, onSave = { change("Add spell", it) })
            "Resource" -> ReforgeResourceEditor(character, { dialog = null }, onSave = { change("Add resource", it) })
            "Turn" -> ReforgeTurnDialog(character, { dialog = null }) { change("Configure turn economy", it) }
            "HP" -> ReforgeHpDialog(character, projection, { dialog = null }) { label, next -> change(label, next) }
            "Slots" -> ReforgeSlotDialog(character, projection, { dialog = null }) { change("Record current spell slots", it) }
            "Conditions" -> ReforgeConditionDialog(character, { dialog = null }) { change("Change conditions", it) }
            "Rest" -> ReforgeRestDialog(character, { dialog = null }) { label, next -> proposedChange = label to next; dialog = null }
            "Defense" -> ReforgeCalculationDialog("Defenses", projection, { dialog = null })
        }
        inspecting?.let { stat -> ReforgeAbilityInspector(character, projection, Stat.valueOf(stat), { inspecting = null }, { change("Edit permanent ${stat}", it) }) { label, modifier -> requestRoll(label, modifier, RollKind.ABILITY_CHECK, stat) } }
        dialog?.takeIf { it.startsWith("HitDie:") }?.substringAfter(':')?.let { id ->
            ReforgeHitDieDialog(character, id, { dialog = null }) { record, next ->
                action("Hit Die and healing saved on this device") {
                    local.commitRoll(character.id, saved!!.revision, next, "Spend Hit Die during eligible rest", record)
                    dialog = null
                }
            }
        }
        dialog?.takeIf { it.startsWith("Attack:") }?.substringAfter(':')?.let { id ->
            ReforgeAttackEditor(character, { dialog = null }, { change("Edit attack", it) }, existingAttackId = id)
        }
        dialog?.takeIf { it.startsWith("Spell:") }?.substringAfter(':')?.let { id ->
            ReforgeSpellEditor(character, { dialog = null }, { change("Edit spell", it) }, existingSpellId = id)
        }
        dialog?.takeIf { it.startsWith("Resource:") }?.substringAfter(':')?.let { id ->
            ReforgeResourceEditor(character, { dialog = null }, { change("Edit resource", it) }, existingResourceId = id)
        }
        dialog?.takeIf { it.startsWith("InspectItem:") }?.substringAfter(':')?.let { itemId ->
            character.items.firstOrNull { it.id == itemId }?.let { item ->
                val definition = character.itemDefinitions.firstOrNull { it.id == item.definitionId }
                PlayerSheet(definition?.name ?: "Item", { dialog = null }, actions = {
                    PlayerTextButton(onClick = { error = ""; dialog = "ManageItem:$itemId" }) { Text("Manage") }
                    PlayerButton(onClick = { dialog = "Item:$itemId" }) { Text("Edit") }
                }) {
                    Text("${item.state.name.lowercase().replaceFirstChar { it.uppercase() }}${if (item.attuned) " · Attuned" else ""} · Quantity ${item.quantity}", color = RebuildPalette.GoldBright)
                    Text(definition?.description?.takeIf(String::isNotBlank) ?: "No description recorded.", color = RebuildPalette.Silver)
                    definition?.equipmentSlot?.takeIf(String::isNotBlank)?.let { Text("Equipment slot · $it", color = RebuildPalette.Muted) }
                    definition?.weight?.let { Text("Weight · $it lb.", color = RebuildPalette.Muted) }
                    val statuses = projection.effectStatuses.filter { it.itemInstanceId == item.id }
                    if (statuses.isNotEmpty()) {
                        RebirthSectionLabel("Equipment effects")
                        statuses.forEach { Text("${if (it.active) "Active" else "Inactive"} · ${it.name}${if (it.reason.isBlank()) "" else " · ${it.reason}"}",
                            style = MaterialTheme.typography.bodyMedium, color = if (it.active) RebuildPalette.Cyan else RebuildPalette.Muted) }
                    }
                    val equipped = item.state in setOf(ItemState.WORN, ItemState.HELD)
                    val stack = !equipped && item.quantity > 1
                    if (stack || (!equipped && item.quantity == 0)) Text(
                        if (stack) "Split off one item before equipping." else "No items available to equip.",
                        color = RebuildPalette.Muted)
                    PlayerOutlinedButton(enabled = !busy && (equipped || item.quantity > 0), onClick = {
                        if (stack) {
                            error = ""; dialog = "ManageItem:$itemId"
                        } else {
                            try {
                                proposedChange = "${if (equipped) "Unequip" else "Equip"} ${definition?.name ?: "item"}" to
                                    GameplayRules.equip(character, item.id, if (equipped) ItemState.CARRIED else ItemState.WORN)
                                dialog = null
                            } catch (e: Exception) { error = e.message ?: "Item requirements not met" }
                        }
                    }) { Text(if (equipped) "Unequip" else if (stack) "Split stack" else "Equip") }
                    if (definition?.requiresAttunement == true) {
                        if (!item.attuned && item.quantity != 1) Text("Choose a single item before attuning.", color = RebuildPalette.Muted)
                        PlayerOutlinedButton(enabled = !busy && (item.attuned || item.quantity == 1), onClick = {
                            try {
                                proposedChange = "${if (item.attuned) "End" else "Confirm"} attunement" to GameplayRules.attune(character, item.id, !item.attuned)
                                dialog = null
                            } catch (e: Exception) { error = e.message ?: "Attunement unavailable" }
                        }) { Text(if (item.attuned) "Unattune" else "Attune") }
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        dialog?.takeIf { it.startsWith("InspectSpell:") }?.substringAfter(':')?.let { spellId ->
            character.spells.firstOrNull { it.id == spellId }?.let { spell ->
                PlayerSheet(spell.name, { dialog = null }, actions = {
                    PlayerTextButton(onClick = { dialog = "Spell:$spellId" }) { Text("Edit") }
                    PlayerButton(onClick = { dialog = "Cast:$spellId" }) { Text("Cast") }
                }) {
                    Text(if (spell.level == 0) "Cantrip" else "Level ${spell.level}", color = RebuildPalette.Purple, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(spell.castingTime, spell.range, spell.components, spell.duration).filter(String::isNotBlank).joinToString(" · "), color = RebuildPalette.Muted)
                    if (spell.concentration) Text("Requires concentration", color = RebuildPalette.Purple)
                    if (spell.ritual) Text("Can be cast as a ritual", color = RebuildPalette.Purple)
                    ReforgeRichText(spell.description.ifBlank { "No spell description recorded." })
                    Text(when (spell.preparation) {
                        SpellPreparation.KNOWN -> "Known"
                        SpellPreparation.PREPARED -> "Prepared"
                        SpellPreparation.ALWAYS_PREPARED -> "Always prepared"
                    }, color = RebuildPalette.Cyan)
                    if (spell.preparation != SpellPreparation.ALWAYS_PREPARED && spell.level > 0) PlayerOutlinedButton(onClick = {
                        try { change("Change spell preparation", GameplayRules.prepareSpell(character, spell.id, spell.preparation != SpellPreparation.PREPARED), false) }
                        catch (e: Exception) { error = e.message ?: "Preparation unavailable" }
                    }) { Text(if (spell.preparation == SpellPreparation.PREPARED) "Unprepare" else "Prepare") }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        dialog?.takeIf { it.startsWith("Item:") }?.substringAfter(':')?.let { itemId ->
            ReforgeItemEditor(character, { dialog = null }, { change("Edit reviewed equipment", it) }, existingItemId = itemId)
        }
        dialog?.takeIf { it.startsWith("ManageItem:") }?.substringAfter(':')?.let { itemId ->
            ReforgeItemManagementDialog(character, itemId, active, busy, error, { dialog = null },
                onChange = { label, next -> dialog = null; proposedChange = label to next },
                onTransfer = { from, to -> action("Item transferred on this device") {
                    local.transferItem(from, to, itemId); dialog = null
                } })
        }
        dialog?.takeIf { it.startsWith("Cast:") }?.substringAfter(':')?.let { spellId ->
            character.spells.firstOrNull { it.id == spellId }?.let { spell -> ReforgeCastDialog(character, projection, spell, { dialog = null }) { preview -> cast = preview; dialog = null } }
        }
        cast?.let { preview -> ReforgeCastConfirmation(preview, { cast = null }) {
            try { change("Cast spell", GameplayRules.commitCast(character, preview)) } catch (e: Exception) { error = e.message ?: "Casting preview changed" }
        } }
        proposedChange?.let { (label, next) -> ReforgeEquipmentPreview(label, character, next, { proposedChange = null }) { change(label, next) } }
        rollRequest?.takeIf { profilesState.first && actorSelection.first }?.let { request ->
            ReforgeRollDialog(character, saved!!.revision, request, { rollRequest = null }, saving = busy, saveError = error) { record, next ->
                action("Roll saved on this device") {
                    savedRoll = if (next == null) local.appendRoll(record) else {
                        local.commitRoll(record.profileId, record.characterRevision, next, "Resolved ${record.label}", record).roll
                    }
                    rollRequest = null
                    if (preferencesState.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }
    }
}
