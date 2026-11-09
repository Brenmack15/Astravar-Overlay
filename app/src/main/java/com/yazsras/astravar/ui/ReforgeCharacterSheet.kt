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
    PlayerArtBanner("THE ADVENTURER", title, subtitle, R.drawable.astravar_atlas_banner)

/** A local-first playable sheet. Legacy campaign adoption is reviewed and never changes its source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ReforgeCharacterSheet(repository: CampaignLedgerRepository, localRepository: ReforgeRepository? = null,
    initialSection: String = "Sheet", sectionOnly: Boolean = false) {
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
    val campaignItems by repository.items.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var extraSections by rememberSaveable { mutableStateOf(false) }
    var importQuery by rememberSaveable { mutableStateOf("") }
    var importId by rememberSaveable { mutableStateOf<String?>(null) }
    var importRevision by rememberSaveable { mutableStateOf<Long?>(null) }
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
    val expandedText = LocalDensity.current.fontScale >= 1.5f
    val chosenId = ReforgePreferences.chooseProfile(selectedId ?: actorSelection.second, active.map { it.character.id })
    val saved = active.firstOrNull { it.character.id == chosenId }
    val character = saved?.character
    val projection = character?.let { remember(it) { CharacterCalculator.calculate(it) } }
    LaunchedEffect(actorSelection) { if (actorSelection.first && selectedId == actorSelection.second) selectedId = null }
    LaunchedEffect(savedRoll, chosenId) {
        val roll = savedRoll ?: return@LaunchedEffect
        if (roll.profileId != chosenId) {
            savedRoll = null
            return@LaunchedEffect
        }
        if (rollNotice.showSnackbar("Roll saved · ${roll.displayTotal}", "View history",
                withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            section = "History"
            extraSections = false
        }
        savedRoll = null
    }
    fun selectProfile(id: String) {
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

    ReforgeSheetPage("$chosenId:$section", rollNotice) {
        if (!profilesState.first || !actorSelection.first) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Opening your saved character…", color = RebuildPalette.Muted)
            return@Page
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reforge-error"))
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelMedium, color = RebuildPalette.Ice, modifier = Modifier.testTag("reforge-save-status"))
        if (character == null || projection == null) {
            PlayerHeading("Your character")
            AstravarHeroBanner("Make your legend", "Your character, equipment and story. In one place.")
            PlayerButton(onClick = { dialog = "Create" }) { Text("Create character") }
            if (all.any { it.character.archived }) PlayerOutlinedButton(onClick = { dialog = "Characters" }) { Text("View archived sheets") }
            if (legacyProfiles.isNotEmpty()) {
                Text("Saved campaign profiles", style = MaterialTheme.typography.titleMedium)
                legacyProfiles.filter { profile -> all.none { it.character.id == profile.id } }.forEach { profile -> PlayerEntry(profile.name.ifBlank { "Unnamed character" },
                    "Review saved values · original record retained", "Emblem", {
                        action("Profile ready for review") { adopting = local.readLegacyPreview(repository, profile.id); dialog = "Adopt" }
                    }) }
            }
            Text("Character sheets in Reforge are saved on this device. Existing campaign synchronization continues separately; these sheets are not synchronized yet.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            if (sectionOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${character.name}'s gear", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    PlayerTextButton(onClick = { dialog = "Characters" }) { Text("Switch") }
                }
                Text("Equipped items affect this character's calculations. Original campaign items are in the other inventory tab.",
                    color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                AstravarHeroBanner(character.name, "Level ${character.level} · Proficiency +${projection.proficiencyBonus}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerOutlinedButton(onClick = { dialog = "Characters" }) { Text("Switch character") }
                    PlayerTextButton(onClick = { dialog = "Setup" }) { Text("Character setup") }
                }
                PlayerTextButton(onClick = { dialog = "CampaignConnections" }) { Text("Campaign connections") }
                if (saved!!.origin.sourceProfileId != null) PlayerDisclosure("Imported sheet · review before play") {
                    Text("Training, attacks, spells, resources, and movement need your review before relying on calculated values.",
                        color = RebuildPalette.GoldBright, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("reforge-setup-review"))
                    Text(saved.saveState, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!sectionOnly) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Sheet", "Combat", "Equipment", "Spells").forEach { name ->
                        PlayerFilterChip(selected = name == section, onClick = { section = name }, label = { Text(name) })
                    }
                    Box {
                        PlayerFilterChip(selected = section in setOf("Resources", "History"), onClick = { extraSections = true }, label = { Text(if (section in setOf("Resources", "History")) section else "More") })
                        DropdownMenu(expanded = extraSections, onDismissRequest = { extraSections = false }) {
                            listOf("Resources", "History").forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { section = name; extraSections = false })
                            }
                        }
                    }
                }
            }
            val summaryLabels = listOf("HP", "Armor Class", "Initiative")
            (if (sectionOnly) emptyList() else summaryLabels).chunked(if (expandedText) 1 else 3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { label ->
                        val value = when (label) {
                            "HP" -> "${projection.currentHp ?: "?"} / ${projection.maximumHp.value ?: "?"}"
                            "Armor Class" -> projection.armorClass.value?.toString() ?: "?"
                            else -> reforgeSigned(projection.initiative.value)
                        }
                        ReforgeNumberCard(label, value, Modifier.weight(1f)) {
                            when (label) {
                                "HP" -> dialog = "HP"
                                "Armor Class" -> dialog = "Defense"
                                else -> projection.initiative.value?.let { requestRoll("Initiative", it, RollKind.INITIATIVE) }
                            }
                        }
                    }
                }
            }
            when (section) {
                "Sheet" -> {
                    Stat.entries.chunked(if (expandedText) 2 else 3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { stat -> val ability = projection.abilities.getValue(stat)
                            ReforgeNumberCard(stat.name, "${ability.effective.value ?: "?"}  ${reforgeSigned(ability.modifier)}", Modifier.weight(1f).testTag("reforge-${stat.name}")) { inspecting = stat.name }
                        }
                    } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerButton(onClick = { openRoll(ReforgeRollRequest("Custom roll", null)) }, modifier = Modifier.weight(1f)) { Text("Roll dice") }
                        PlayerOutlinedButton(onClick = { dialog = "Training" }, modifier = Modifier.weight(1f)) { Text("Training") }
                    }
                    PlayerDisclosure("Saving throws") {
                        Stat.entries.chunked(if (expandedText) 1 else 2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { stat ->
                            PlayerOutlinedButton(onClick = { projection.saves[stat]?.value?.let { requestRoll("${stat.name} save", it, RollKind.SAVE, stat.name) } },
                                enabled = projection.saves[stat]?.value != null, modifier = Modifier.weight(1f)) { Text("${stat.name} ${reforgeSigned(projection.saves[stat]?.value)}") }
                        } } }
                    }
                    PlayerDisclosure("Skills") {
                        reforgeSkills.forEach { (name, stat) ->
                            val value = projection.skills[name]?.value ?: projection.abilities[stat]?.modifier
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name); val skill = character.skills[name]
                                    Text(when { skill?.expertise == true -> "Expertise"; skill?.proficient == true -> "Proficient"; skill?.halfProficiency == true -> "Half proficiency"; else -> stat.name }, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                PlayerTextButton(enabled = value != null, onClick = { value?.let { requestRoll(name, it, RollKind.SKILL, name) } }) { Text(reforgeSigned(value)) }
                            }
                        }
                    }
                    PlayerDisclosure("Passive scores & movement") {
                        listOf("Perception", "Investigation", "Insight").forEach { name ->
                            val ability = reforgeSkills.getValue(name)
                            Text("$name · ${projection.passiveScores[name] ?: projection.abilities[ability]?.modifier?.plus(10) ?: "?"}")
                        }
                        Text("Walking speed · ${projection.speed.value?.let { "$it ft." } ?: "Unknown"}")
                    }
                    ReforgeIdentitySection(character, onEdit = { dialog = "Identity" }, onNotes = { dialog = "Notes" }, onAdvance = { dialog = "Advance" })
                    PlayerDisclosure("Rules & calculation notices") {
                        Text("Core · ${character.rules.core}\nClass · ${character.rules.classVersion}", color = RebuildPalette.Muted)
                        (projection.warnings + saved!!.origin.warnings).distinct().forEach { Text(it, color = RebuildPalette.GoldBright) }
                        Text("Current resources are never inferred from capacity, equipment, an update, or opening the app.", color = RebuildPalette.Muted)
                    }
                }
                "Combat" -> {
                    PlayerCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Turn economy", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            PlayerTextButton(onClick = { dialog = "Turn" }) { Text("Configure") }
                        }
                        Text(if (character.turn.tracking) "Action · ${if (character.turn.actionAvailable) "available" else "spent"}\nBonus Action · ${if (character.turn.bonusActionAvailable) "available" else "spent"}\nReaction · ${if (character.turn.reactionAvailable) "available" else "spent"}" else "Tracking off · saved availability retained", color = RebuildPalette.Ice)
                        Text("Attacks ${character.turn.attacksUsed} / ${character.turn.attacksPerAction} · Spell slot this turn ${if (character.turn.spellSlotSpent) "spent" else "not spent"}", color = RebuildPalette.Muted)
                        if (character.turn.tracking) {
                            PlayerTextButton(enabled = character.turn.actionAvailable, onClick = { change("Recorded Action used", character.copy(turn = character.turn.copy(actionAvailable = false, attacksUsed = character.turn.attacksPerAction)), false) }) { Text("Mark Action used") }
                            PlayerTextButton(enabled = character.turn.bonusActionAvailable, onClick = { change("Recorded Bonus Action used", character.copy(turn = character.turn.copy(bonusActionAvailable = false)), false) }) { Text("Mark Bonus Action used") }
                            PlayerTextButton(enabled = character.turn.reactionAvailable, onClick = { change("Recorded Reaction used", character.copy(turn = character.turn.copy(reactionAvailable = false)), false) }) { Text("Mark Reaction used") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerButton(onClick = { dialog = "HP" }, modifier = Modifier.weight(1f)) { Text("Damage / healing") }
                        PlayerOutlinedButton(onClick = { dialog = "Conditions" }, modifier = Modifier.weight(1f)) { Text("Conditions") }
                    }
                    PlayerCard {
                        Text("Concentration", style = MaterialTheme.typography.titleMedium)
                        Text(character.spells.firstOrNull { it.id == character.concentrationSpellId }?.name ?: "None recorded", color = RebuildPalette.Ice)
                        if (character.concentrationSpellId != null) PlayerTextButton(onClick = { change("End concentration", character.copy(concentrationSpellId = null), false) }) { Text("End concentration") }
                        Text("Conditions · ${character.conditions.joinToString().ifBlank { "None recorded" }}", color = RebuildPalette.Muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Attacks", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        PlayerTextButton(onClick = { dialog = "Attack" }) { Text("Add attack") }
                    }
                    if (character.attacks.isEmpty()) PlayerEmpty("Set up your attacks", "Add an attack using your accepted damage dice and ability. The established Astravar weapon engine remains available from More → Armor weapon.")
                    character.attacks.forEach { attack -> PlayerCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(attack.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            PlayerTextButton(onClick = { dialog = "Attack:${attack.id}" }) { Text("Edit") }
                        }
                        Text("${attack.actionType} · ${attack.range} · ${attack.damage.joinToString(" + ") { "${SecureDice.format(it.formula)} ${it.type.name.lowercase()}" }}", color = RebuildPalette.Muted)
                        PlayerButton(onClick = { openRoll(ReforgeRollRequest(attack.name, null, RollKind.ATTACK, attack.id, attack.id)) }) { Text("Roll attack & damage") }
                    } }
                    PlayerOutlinedButton(onClick = { change("Confirmed next turn", GameplayRules.nextTurn(character), false) }) { Text("My next turn") }
                    Text("Next turn clears per-turn effect use. It does not complete a rest or advance game time.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                "Equipment" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Equipment", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); PlayerTextButton(onClick = { dialog = "Item" }) { Text("Add item") } }
                    PlayerOutlinedButton(onClick = { importId = null; importQuery = ""; dialog = "ImportItem" }) {
                        Text("Add from campaign inventory")
                    }
                    Text("Attuned · ${character.items.filter { it.attuned }.sumOf { instance -> character.itemDefinitions.firstOrNull { it.id == instance.definitionId }?.attunementCost ?: 0 }} / ${character.attunementLimit}", color = RebuildPalette.Ice)
                    if (character.items.isEmpty()) PlayerEmpty("Add your equipment", "Items carried over from your source retain their identity and description. Mechanical effects activate only after structured review.")
                    character.items.forEach { item -> val definition = character.itemDefinitions.firstOrNull { it.id == item.definitionId }
                        PlayerCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(definition?.name ?: "Item", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                PlayerTextButton(onClick = { dialog = "Item:${item.id}" }) { Text("Edit") }
                                PlayerTextButton(onClick = { error = ""; dialog = "ManageItem:${item.id}" }) { Text("Manage") }
                            }
                            Text("${item.state.name.lowercase().replaceFirstChar { it.uppercase() }}${if (item.attuned) " · Attuned" else ""} · Quantity ${item.quantity}", color = RebuildPalette.Ice)
                            definition?.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = RebuildPalette.Muted, maxLines = 4) }
                            val statuses = projection.effectStatuses.filter { it.itemInstanceId == item.id }
                            statuses.forEach { Text("${if (it.active) "Active" else "Inactive"} · ${it.name}${if (it.reason.isBlank()) "" else " · ${it.reason}"}", style = MaterialTheme.typography.bodySmall, color = if (it.active) RebuildPalette.Ice else RebuildPalette.Muted) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlayerOutlinedButton(enabled = !busy, onClick = {
                                    try {
                                        val next = GameplayRules.equip(character, item.id, if (item.state in setOf(ItemState.WORN, ItemState.HELD)) ItemState.CARRIED else ItemState.WORN)
                                        proposedChange = "${if (item.state in setOf(ItemState.WORN, ItemState.HELD)) "Unequip" else "Equip"} ${definition?.name ?: "item"}" to next
                                    } catch (e: Exception) { error = e.message ?: "Item requirements not met" }
                                }, modifier = Modifier.weight(1f)) { Text(if (item.state in setOf(ItemState.WORN, ItemState.HELD)) "Unequip" else "Equip") }
                                if (definition?.requiresAttunement == true) PlayerOutlinedButton(enabled = !busy, onClick = {
                                    try { proposedChange = "${if (item.attuned) "End" else "Confirm"} attunement" to GameplayRules.attune(character, item.id, !item.attuned) }
                                    catch (e: Exception) { error = e.message ?: "Attunement unavailable" }
                                }, modifier = Modifier.weight(1f)) { Text(if (item.attuned) "Unattune" else "Attune") }
                            }
                        }
                    }
                }
                "Spells" -> {
                    PlayerCard {
                        Text("Spell attack ${reforgeSigned(projection.spellAttack.value)} · Save DC ${projection.spellSaveDc.value ?: "?"}", style = MaterialTheme.typography.titleMedium)
                        PlayerTextButton(enabled = projection.spellAttack.value != null, onClick = { projection.spellAttack.value?.let { requestRoll("Spell attack", it, RollKind.SPELL_ATTACK) } }) { Text("Roll spell attack") }
                        Text("Prepared ${character.spells.count { it.preparation == SpellPreparation.PREPARED && it.level > 0 }} / ${projection.preparationLimit ?: "unconfigured"}", color = RebuildPalette.Muted)
                        if (projection.slotMaxima.isEmpty()) Text("Configure your pinned class and slot capacity in Character setup.", color = RebuildPalette.GoldBright)
                        projection.slotMaxima.forEach { (level, max) -> Text("Level $level slots · ${character.spellSlots[level] ?: "?"} / $max") }
                        PlayerTextButton(onClick = { dialog = "Slots" }) { Text("Set current slots") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Spells", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); PlayerTextButton(onClick = { dialog = "Spell" }) { Text("Add spell") } }
                    character.spells.forEach { spell -> PlayerCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(spell.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            PlayerTextButton(onClick = { dialog = "Spell:${spell.id}" }) { Text("Edit") }
                        }
                        Text("${if (spell.level == 0) "Cantrip" else "Level ${spell.level}"} · ${spell.preparation.name.lowercase().replace('_', ' ')}${if (spell.concentration) " · Concentration" else ""}", color = RebuildPalette.Ice)
                        Text("${spell.castingTime} · ${spell.range} · ${spell.components}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        if (spell.description.isNotBlank()) PlayerDisclosure("Spell reference") { Text(spell.description) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerButton(onClick = { dialog = "Cast:${spell.id}" }, modifier = Modifier.weight(1f)) { Text("Cast") }
                            if (spell.preparation != SpellPreparation.ALWAYS_PREPARED && spell.level > 0) PlayerOutlinedButton(onClick = {
                                try { change("Change spell preparation", GameplayRules.prepareSpell(character, spell.id, spell.preparation != SpellPreparation.PREPARED), false) }
                                catch (e: Exception) { error = e.message ?: "Preparation unavailable" }
                            }, modifier = Modifier.weight(1f)) { Text(if (spell.preparation == SpellPreparation.PREPARED) "Unprepare" else "Prepare") }
                        }
                    } }
                }
                "Resources" -> {
                    PlayerButton(onClick = { dialog = "Rest" }) { Text("Rest & recovery") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Limited uses", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); PlayerTextButton(onClick = { dialog = "Resource" }) { Text("Add resource") } }
                    character.resources.forEach { resource -> PlayerCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(resource.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            PlayerTextButton(onClick = { dialog = "Resource:${resource.id}" }) { Text("Edit") }
                        }
                        Text("${resource.current ?: "?"} / ${projection.resourceMaxima[resource.id] ?: "?"} · ${resource.recharge.name.lowercase().replace('_', ' ')}", color = RebuildPalette.Ice)
                        if (resource.dieSides != null) PlayerButton(onClick = { dialog = "HitDie:${resource.id}" }) { Text("Spend Hit Die") }
                        PlayerTextButton(enabled = (resource.current ?: 0) > 0, onClick = {
                            change("Use ${resource.name}", character.copy(resources = character.resources.map { if (it.id == resource.id) it.copy(current = requireNotNull(it.current) - 1) else it }), false)
                        }) { Text("Use one") }
                    } }
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
    if (dialog == "Create") ReforgeCharacterEditor(null, { dialog = null }) { next -> action("Character saved on this device") { local.create(next); selectProfile(next.id); dialog = null } }
    if (dialog == "Adopt") adopting?.let { preview ->
        var mechanicsReviewed by rememberSaveable(preview.legacyFingerprint) { mutableStateOf(false) }
        PlayerSheet("Review campaign profile", { dialog = null }) {
        Text(preview.character.name, style = MaterialTheme.typography.headlineSmall)
        Text("This creates a separate playable sheet. Your campaign profile, inventory history and existing synchronization stay intact.", color = RebuildPalette.Muted)
        preview.warnings.forEach { Text(it, color = RebuildPalette.GoldBright) }
        Stat.entries.forEach { Text("${it.name} · ${preview.character.baseAbilities[it] ?: "Unknown"}") }
        Text("Level ${preview.character.level} · HP ${preview.character.currentHp ?: "?"} / ${preview.character.baseMaxHp ?: "?"}")
        Toggle("I will review training, actions and current resources before relying on this sheet", mechanicsReviewed) { mechanicsReviewed = it }
        PlayerButton(onClick = { dialog = "AdoptEdit" }, enabled = mechanicsReviewed) { Text("Review baselines & create sheet") }
    } }
    if (dialog == "AdoptEdit") adopting?.let { preview -> ReforgeCharacterEditor(preview.character, { dialog = null }) { reviewed ->
        action("Reviewed sheet saved on this device") { local.adopt(repository, preview, reviewed); selectProfile(reviewed.id); dialog = null; adopting = null }
    } }
    if (dialog == "Characters") PlayerSheet("Characters & companions", { dialog = null }) {
        active.forEach { profile ->
            PlayerEntry(profile.character.name, "Local playable sheet", "Emblem", { selectProfile(profile.character.id); dialog = null })
            PlayerTextButton(enabled = !busy, onClick = { dialog = "ArchiveCharacter:${profile.character.id}" }) { Text("Archive ${profile.character.name}") }
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
            action("Profile ready for review") { adopting = local.readLegacyPreview(repository, profile.id); dialog = "Adopt" }
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
            "Identity" -> ReforgeIdentityEditor(character, { dialog = null }) { change("Edit identity & features", it) }
            "Notes" -> ReforgeNotesEditor(saved!!, local, { dialog = null })
            "Advance" -> ReforgeAdvancementDialog(character, { dialog = null }) { change("Confirmed character advancement", it) }
            "Setup" -> ReforgeCharacterEditor(character, { dialog = null }) { change("Edit character setup", it) }
            "CampaignConnections" -> ReforgeCampaignConnectionsDialog(saved!!, local, repository, { dialog = null })
            "Training" -> ReforgeTrainingEditor(character, { dialog = null }) { change("Edit training", it) }
            "Item" -> ReforgeItemEditor(character, { dialog = null }, onSave = { change("Add reviewed equipment", it) })
            "ImportItem" -> {
                val available = campaignItems.filter { !it.archived && it.quantity != null && it.quantity > 0 &&
                    it.itemState !in setOf("Consumed", "Lost", "Sold") &&
                    character.items.none { held -> held.id == it.id } }
                val chosen = available.firstOrNull { it.id == importId }
                PlayerSheet("Add from campaign", onDismiss = { dialog = null }, actions = {
                    PlayerButton(enabled = !busy && chosen != null && chosen.revision == importRevision, onClick = {
                        val expectedId = importId ?: return@PlayerButton
                        val expectedRevision = importRevision ?: return@PlayerButton
                        val snapshot = saved ?: return@PlayerButton
                        action("Campaign item copied to your playable gear") {
                            val latest = repository.items.first().firstOrNull { it.id == expectedId }
                                ?: error("The campaign item is no longer available. Reopen this picker.")
                            check(latest.revision == expectedRevision && !latest.archived && latest.quantity != null && latest.quantity > 0 &&
                                latest.itemState !in setOf("Consumed", "Lost", "Sold")) {
                                "The campaign item changed. Reopen and review its latest values."
                            }
                            check(snapshot.character.items.none { it.id == latest.id } &&
                                snapshot.character.itemDefinitions.none { it.id == latest.id }) {
                                "This item already belongs to your playable character. No duplicate was created."
                            }
                            val (definition, instance) = campaignItemPlayableCopy(latest)
                            val updated = snapshot.character.copy(
                                itemDefinitions = snapshot.character.itemDefinitions + definition,
                                items = snapshot.character.items + instance)
                            local.save(snapshot.character.id, snapshot.revision, updated,
                                "Reviewed copy of campaign item ${latest.id}, source revision ${latest.revision}")
                            dialog = null; importId = null
                        }
                    }) { Text("Copy item to character") }
                }) {
                    Text("This creates a reviewed local copy with the original item ID and quantity. Your campaign record stays unchanged; no magical effects activate automatically.",
                        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    if (chosen == null) {
                        PlayerSearch(importQuery, { importQuery = it }, "Find a campaign item")
                        val matches = available.filter { importQuery.isBlank() || it.title.contains(importQuery, true) }
                        if (matches.isEmpty()) Text("No eligible items. Add one in Campaign items, or check its quantity and status.", color = RebuildPalette.Muted)
                        matches.take(40).forEach { item -> PlayerEntry(item.title,
                            "${item.category} · Qty ${item.quantity} · ${item.itemState}", "Core", {
                                importId = item.id; importRevision = item.revision
                            }) }
                        if (matches.size > 40) Text("Refine your search for more results.", color = RebuildPalette.Muted)
                    } else {
                        Text(chosen.title, style = MaterialTheme.typography.titleLarge)
                        Text("Quantity ${chosen.quantity} · ${chosen.category} · Original revision ${chosen.revision}", color = RebuildPalette.Ice)
                        Text(chosen.description.ifBlank { "No description recorded." }, color = RebuildPalette.Muted)
                        PlayerTextButton(onClick = { importId = null; importRevision = null }) { Text("Choose another") }
                    }
                }
            }
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

@Composable private fun ReforgeSheetPage(resetKey: String, rollNotice: SnackbarHostState, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Page(resetKey = resetKey, content = content)
        SnackbarHost(rollNotice, Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp))
    }
}

@Composable private fun ReforgeNumberCard(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.background(RebuildPalette.Elevated, RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 88.dp).padding(10.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, color = RebuildPalette.GoldBright, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, color = RebuildPalette.Muted, style = MaterialTheme.typography.labelMedium)
    }
}
