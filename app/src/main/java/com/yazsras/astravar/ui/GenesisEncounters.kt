package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.CharacterCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable private data class GenesisEncounterMemberDraft(val actorId: String, val authorityRevision: Long, val initiative: String = "")
@Serializable private data class GenesisEncounterUiDraft(val id: String, val revision: Long?, val campaignId: String?,
    val title: String = "", val notes: String = "", val participants: List<GenesisEncounterMemberDraft> = emptyList())

private fun encounterName(id: String, profiles: List<CharacterProfile>, loaded: Boolean = true): String {
    if (!loaded) return "Loading participant…"
    val profile = profiles.firstOrNull { it.id == id } ?: return "Unavailable participant · $id"
    return profile.name + if (profiles.count { it.name == profile.name } > 1) " · $id" else ""
}

/** Encounter order is independent of weapon action limits, HP, resources and campaign time. */
@Composable internal fun GenesisEncountersPage(environment: GenesisConnectedEnvironment,
    openActor: (ReforgeSearchDestination.Character) -> Unit) {
    val repository = environment.authority.encounters
    val campaigns by environment.authority.campaigns.collectAsState(initial = emptyList())
    val profileState by remember(environment.campaign) { environment.campaign.records.characterProfiles.map { true to it } }
        .collectAsState(initial = false to emptyList<CharacterProfile>())
    val actorState by remember(environment.authority) { environment.authority.actorAssociations.map { true to it } }
        .collectAsState(initial = false to emptyList<GenesisActorAuthority>())
    val playableState by remember(environment.local) { environment.local.observeAll().map { true to it } }
        .collectAsState(initial = false to emptyList<ReforgeSavedCharacter>())
    val profiles = profileState.second
    val actors = actorState.second
    val playable = playableState.second
    val referencesLoaded = profileState.first && actorState.first && playableState.first
    var campaignId by rememberSaveable { mutableStateOf(environment.selectedCampaignId) }
    var campaignInitialized by rememberSaveable { mutableStateOf(environment.selectionLoaded) }
    LaunchedEffect(environment.selectionLoaded, environment.selectedCampaignId) {
        if (!campaignInitialized && environment.selectionLoaded) {
            campaignId = environment.selectedCampaignId; campaignInitialized = true
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedSelection by remember { mutableStateOf<GenesisEncounterSnapshot?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmationRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceActorId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var startReview by remember { mutableStateOf<GenesisEncounterStartReview?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    val rows by remember(repository, campaignId) {
        (campaignId?.let { repository.observe(it) } ?: flowOf(emptyList())).map { true to it }
            .catch { error = it.message ?: "Encounters could not be opened. Saved records are unchanged."; emit(true to emptyList()) }
    }.collectAsState(initial = false to emptyList<GenesisEncounterSnapshot>())
    val selected = rows.second.firstOrNull { it.record.id == selectedId }
        ?: savedSelection?.takeIf { it.record.id == selectedId && it.record.campaignId == campaignId }
    fun selectCampaign(id: String?) {
        campaignId = id; campaignInitialized = true; selectedId = null; error = ""
        scope.launch {
            try { environment.preferences.selectCampaign(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Campaign context could not be saved." }
        }
    }
    fun action(block: suspend () -> String) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { message = block(); command = UUID.randomUUID().toString() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "The encounter was not changed. Review its latest state." }
            finally { busy = false }
        }
    }
    Page {
        PlayerHeading("Encounters") {
            PlayerButton(enabled = environment.selectionLoaded && !busy && campaigns.any { it.id == campaignId && !it.archived },
                onClick = { editorId = "new:${UUID.randomUUID()}" }) { Text("Create encounter") }
        }
        Text("Track participants, manual order and rounds. Use their sheets for attacks, HP and resources.", color = RebuildPalette.Muted)
        Choices("Campaign", campaigns.filterNot { it.archived }.map { it.id }, campaignId,
            display = { id -> campaigns.firstOrNull { it.id == id }?.name ?: "Campaign unavailable" }, enabled = environment.selectionLoaded && !busy, change = ::selectCampaign)
        if (!campaignInitialized) Text("Loading campaign context…", color = RebuildPalette.Muted)
        else if (campaignId == null) PlayerEmpty("Choose a campaign", "Encounters keep explicit campaign and participant relationships.")
        PlayerSearch(query, { query = it }, "Find an encounter")
        if (!rows.first || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = RebuildPalette.Ice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        val visible = rows.second.filter { query.isBlank() || it.record.title.contains(query.trim(), true) }
        if (campaignId != null && rows.first && visible.isEmpty()) PlayerEmpty("No encounters here yet", "Create a setup, add saved campaign members, then review the turn order.")
        visible.forEach { snapshot ->
            PlayerEntry(snapshot.record.title, when (snapshot.record.status) {
                GenesisEncounterStatus.DRAFT -> "Setup · ${snapshot.participants.size} participants"
                GenesisEncounterStatus.ACTIVE -> "Round ${snapshot.record.round} · ${snapshot.record.currentActorId?.let { encounterName(it, profiles, profileState.first) } ?: "Participant unavailable"}"
                GenesisEncounterStatus.ENDED -> "Ended · round ${snapshot.record.round}"
            }, "Shield", { selectedId = snapshot.record.id })
        }
    }
    if (selectedId != null && editorId == null) {
        PlayerSheet(selected?.record?.title ?: "Opening encounter", { selectedId = null; error = "" }, dismissEnabled = !busy,
            scrollResetKey = selectedId, actions = {
                selected?.let { snapshot ->
                    when (snapshot.record.status) {
                        GenesisEncounterStatus.DRAFT -> {
                            PlayerTextButton(enabled = !busy, onClick = { editorId = snapshot.record.id }) { Text("Edit setup") }
                            PlayerButton(enabled = !busy && referencesLoaded && snapshot.participants.isNotEmpty(), onClick = {
                                action {
                                    startReview = repository.reviewStart(snapshot.record.id, snapshot.record.revision)
                                    acknowledged = false; "Review the participants and turn order before starting."
                                }
                            }) { Text("Review start") }
                        }
                        GenesisEncounterStatus.ACTIVE -> {
                            PlayerTextButton(enabled = !busy, onClick = { confirmation = "end"; confirmationRevision = snapshot.record.revision; command = UUID.randomUUID().toString() }) { Text("End encounter") }
                            PlayerButton(enabled = !busy, modifier = Modifier.testTag("genesis-next-encounter-turn"), onClick = {
                                action { repository.nextTurn(snapshot.record.id, snapshot.record.revision, command); "Turn advanced" }
                            }) { Text("Next turn") }
                        }
                        GenesisEncounterStatus.ENDED -> PlayerButton(enabled = !busy, onClick = { confirmation = "reopen"; confirmationRevision = snapshot.record.revision; command = UUID.randomUUID().toString() }) { Text("Reopen setup") }
                    }
                }
            }) {
            if (selected == null) {
                if (!rows.first) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text("This encounter is unavailable in the selected campaign. Close this view and review the campaign selection.")
            } else {
                Text(when (selected.record.status) {
                    GenesisEncounterStatus.DRAFT -> "Setup · confirm the order before starting"
                    GenesisEncounterStatus.ACTIVE -> "Round ${selected.record.round}"
                    GenesisEncounterStatus.ENDED -> "Ended · round ${selected.record.round}"
                }, style = MaterialTheme.typography.titleLarge)
                if (selected.record.status == GenesisEncounterStatus.ACTIVE) Text(
                    "Current turn: ${selected.record.currentActorId?.let { encounterName(it, profiles, profileState.first) } ?: "Unavailable participant"}",
                    color = RebuildPalette.GoldBright, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (selected.record.notes.isNotBlank()) ReforgeRichText(selected.record.notes)
                Text("Next turn changes this tracker only. Resolve actions and turn-based effects on the relevant sheet; HP, resources, weapon actions and campaign time stay unchanged.", color = RebuildPalette.Muted)
                selected.participants.sortedBy { it.position }.forEachIndexed { index, member ->
                    val actor = actors.firstOrNull { it.actorId == member.actorId }
                    val saved = playable.firstOrNull { it.character.id == member.actorId && !it.character.archived }
                    val name = encounterName(member.actorId, profiles, profileState.first)
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. $name", style = MaterialTheme.typography.titleMedium,
                            color = if (selected.record.currentActorId == member.actorId) RebuildPalette.GoldBright else MaterialTheme.colorScheme.onSurface)
                        Text("Initiative: ${member.initiative?.toString() ?: "Not entered"}", color = RebuildPalette.Muted)
                        if (!referencesLoaded) Text("Loading participant records…", color = RebuildPalette.Muted)
                        else {
                        if (actor == null || actor.campaignId != selected.record.campaignId) Text("Membership changed · retained encounter participant", color = RebuildPalette.GoldBright)
                        if (actor?.playable == true && saved != null) {
                            val projection = remember(saved) { runCatching { CharacterCalculator.calculate(saved.character) }.getOrNull() }
                            Text("HP ${projection?.currentHp?.toString() ?: "Unknown"} / ${projection?.maximumHp?.value?.toString() ?: "Unknown"}", color = RebuildPalette.Muted)
                            PlayerTextButton(enabled = !busy, onClick = {
                                openActor(ReforgeSearchDestination.Character(member.actorId, "Combat", companion = actor.role == GenesisActorRole.COMPANION))
                            }) { Text("Open $name's actions") }
                        } else {
                            Text("Source or archived record · no playable actions here", color = RebuildPalette.Muted)
                            PlayerTextButton(onClick = { sourceActorId = member.actorId }) { Text("View $name's source") }
                        }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("genesis-encounter-error"))
        }
    }
    editorId?.takeIf { it.startsWith("new:") || selected != null }?.let { id -> key(id) {
        GenesisEncounterEditor(environment, if (id.startsWith("new:")) null else selected, campaignId,
            onDismiss = { editorId = null }, onSaved = { snapshot ->
                editorId = null; savedSelection = snapshot; campaignId = snapshot.record.campaignId; selectedId = snapshot.record.id; message = "Encounter setup saved"
                scope.launch {
                    try { environment.preferences.selectCampaign(snapshot.record.campaignId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "Setup saved. Campaign navigation could not be updated: ${e.message.orEmpty()}" }
                }
            })
    } }
    if (editorId != null && !requireNotNull(editorId).startsWith("new:") && selected == null) PlayerSheet("Opening encounter setup", { editorId = null }) {
        if (!rows.first) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Text("This encounter is unavailable. Close this view and review the selected campaign.")
    }
    startReview?.let { frozen ->
        PlayerSheet("Start encounter", { startReview = null; acknowledged = false }, dismissEnabled = !busy, actions = {
            PlayerButton(enabled = acknowledged && referencesLoaded && !busy, onClick = {
                action { repository.start(frozen, command); startReview = null; acknowledged = false; "Encounter started" }
            }) { Text("Start encounter") }
        }) {
            Text(frozen.snapshot.record.title, style = MaterialTheme.typography.titleLarge)
            frozen.snapshot.participants.sortedBy { it.position }.forEachIndexed { index, member ->
                GenesisReviewValue("${index + 1}. ${encounterName(member.actorId, profiles, profileState.first)}", "Initiative: ${member.initiative?.toString() ?: "Not entered"}")
            }
            Text("The first listed participant begins round 1. Initiative is entered manually; this tracker does not roll, spend resources or reset actions.", color = RebuildPalette.Muted)
            Toggle("I confirm this turn order", acknowledged, enabled = referencesLoaded && !busy) { acknowledged = it }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("genesis-encounter-error"))
        }
    }
    confirmation?.let { kind -> selected?.let { snapshot ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(if (kind == "end") "End encounter?" else "Reopen setup?") },
            text = { Column {
                Text(if (kind == "end") "Keep the final round and turn in history. No rest or resource recovery is applied."
                    else "Return to setup with no current turn. The previous encounter history remains saved; HP and resources are unchanged.")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { PlayerTextButton(enabled = !busy, onClick = { confirmation = null }) { Text("Cancel") } },
            confirmButton = { PlayerButton(enabled = !busy, onClick = { action {
                if (kind == "end") repository.end(snapshot.record.id, requireNotNull(confirmationRevision), command)
                else repository.reopenSetup(snapshot.record.id, requireNotNull(confirmationRevision), command)
                confirmation = null; if (kind == "end") "Encounter ended" else "Encounter returned to setup"
            } }) { Text(if (kind == "end") "End encounter" else "Reopen setup") } })
    } }
    sourceActorId?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        PlayerSheet(encounterName(id, profiles, profileState.first), { sourceActorId = null }) {
            if (!referencesLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
            Text("This participant remains a source record. Encounter tracking does not create a playable sheet or invent statistics.", color = RebuildPalette.Muted)
            GenesisReviewValue("Source", profile?.source.orEmpty(), "No source recorded")
            GenesisReviewValue("Role", actors.firstOrNull { it.actorId == id }?.role?.let(::genesisRoleLabel).orEmpty(), "Membership unavailable")
            }
        }
    }
}

@Composable internal fun GenesisEncounterEditor(environment: GenesisConnectedEnvironment, existing: GenesisEncounterSnapshot?,
    initialCampaignId: String?, onDismiss: () -> Unit, onSaved: (GenesisEncounterSnapshot) -> Unit) {
    val campaigns by environment.authority.campaigns.collectAsState(initial = emptyList())
    val profileState by remember(environment.campaign) { environment.campaign.records.characterProfiles.map { true to it } }
        .collectAsState(initial = false to emptyList<CharacterProfile>())
    val profiles = profileState.second
    // Initialize saved participant revisions from the same snapshot as readiness.
    // A later IO emission must not make the effect trust its earlier empty list.
    val actorState = remember(environment.authority) { environment.authority.actorAssociations.map { true to it } }
        .collectAsState(initial = false to emptyList<GenesisActorAuthority>()).value
    val actors = actorState.second
    var initialized by rememberSaveable { mutableStateOf(existing == null) }
    val draftState = rememberReforgeDraft(GenesisEncounterUiDraft(existing?.record?.id ?: UUID.randomUUID().toString(),
        existing?.record?.revision, existing?.record?.campaignId ?: initialCampaignId, existing?.record?.title.orEmpty(), existing?.record?.notes.orEmpty()), GenesisEncounterUiDraft.serializer())
    var draft by draftState
    val baselineState = rememberReforgeDraft(draft, GenesisEncounterUiDraft.serializer())
    var baseline by baselineState
    LaunchedEffect(actorState.first) {
        if (!initialized && actorState.first && existing != null && draftState.canSave && baselineState.canSave) {
            draft = draft.copy(participants = existing.participants.sortedBy { it.position }.map {
                GenesisEncounterMemberDraft(it.actorId, actors.firstOrNull { actor -> actor.actorId == it.actorId }?.revision ?: -1, it.initiative?.toString().orEmpty())
            }); baseline = draft; initialized = true
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val command = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val membershipsValid = draft.participants.all { member -> actors.any {
        it.actorId == member.actorId && it.campaignId == draft.campaignId && it.revision == member.authorityRevision
    } }
    val initiativesValid = draft.participants.all { it.initiative.isBlank() || it.initiative.toIntOrNull() != null }
    val valid = initialized && actorState.first && profileState.first && draftState.canSave && baselineState.canSave && draft.title.isNotBlank() &&
        campaigns.any { it.id == draft.campaignId && !it.archived } && membershipsValid && initiativesValid
    val editable = !busy && initialized && actorState.first && profileState.first && draftState.canSave && baselineState.canSave
    fun move(index: Int, delta: Int) {
        val other = index + delta
        if (!busy && other in draft.participants.indices) {
            val members = draft.participants.toMutableList(); val old = members[index]; members[index] = members[other]; members[other] = old
            draft = draft.copy(participants = members)
        }
    }
    GenesisEditorSheet(if (existing == null) "Create encounter" else "Edit encounter setup", listOf("Details", "Participants", "Review"), creating = true,
        context = "Save a setup before starting. Participant order is manual; unknown initiative stays blank.", onDismiss = onDismiss,
        dirty = draft != baseline, saveLabel = "Save encounter", saveEnabled = valid, busy = busy || !initialized, error = error,
        onSave = {
            if (!busy && valid) {
                busy = true; error = ""
                scope.launch {
                    try { onSaved(environment.authority.encounters.saveDraft(draft.id, draft.revision, requireNotNull(draft.campaignId), draft.title, draft.notes,
                        draft.participants.map { GenesisEncounterParticipantInput(it.actorId, it.authorityRevision, it.initiative.takeIf(String::isNotBlank)?.toInt()) }, command)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Setup was not saved. Your draft is preserved." }
                    finally { busy = false }
                }
            }
        }) { section, select ->
        ReforgeDraftRecovery(draftState, baselineState)
        if (!initialized) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (section) {
            0 -> {
                Field("Encounter name", draft.title, { draft = draft.copy(title = it) }, enabled = editable)
                Choices("Campaign", campaigns.filterNot { it.archived }.map { it.id }, draft.campaignId,
                    display = { id -> campaigns.firstOrNull { it.id == id }?.name ?: "Campaign unavailable" }, enabled = editable) { draft = draft.copy(campaignId = it) }
                Field("Encounter notes", draft.notes, { draft = draft.copy(notes = it) }, single = false, enabled = editable)
                Text("Changing campaign does not move participants. Remove any members who belong elsewhere before saving.", color = RebuildPalette.Muted)
            }
            1 -> {
                Text("Turn order", style = MaterialTheme.typography.titleMedium)
                Text("Arrange each participant explicitly. Initiative values are notes; they do not automatically reorder this list.", color = RebuildPalette.Muted)
                draft.participants.forEachIndexed { index, member -> key(member.actorId) {
                    val name = encounterName(member.actorId, profiles, profileState.first)
                    Text("${index + 1}. $name", style = MaterialTheme.typography.titleMedium)
                    Field("Initiative: $name", member.initiative, { value -> draft = draft.copy(participants = draft.participants.map { if (it.actorId == member.actorId) it.copy(initiative = value) else it }) }, enabled = editable)
                    if (member.initiative.isNotBlank() && member.initiative.toIntOrNull() == null) Text("Enter a whole initiative value or leave it blank.", color = MaterialTheme.colorScheme.error)
                    if (actors.none { it.actorId == member.actorId && it.campaignId == draft.campaignId && it.revision == member.authorityRevision }) Text("Membership changed or belongs to another campaign. Remove and add this participant again after reviewing membership.", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PlayerTextButton(modifier = Modifier.weight(1f), enabled = editable && index > 0, onClick = { move(index, -1) }) { Text("Move $name up") }
                        PlayerTextButton(modifier = Modifier.weight(1f), enabled = editable && index < draft.participants.lastIndex, onClick = { move(index, 1) }) { Text("Move $name down") }
                    }
                    PlayerTextButton(enabled = editable, onClick = { draft = draft.copy(participants = draft.participants.filterNot { it.actorId == member.actorId }) }) { Text("Remove $name") }
                    HorizontalDivider()
                } }
                PlayerSearch(query, { query = it }, "Find a campaign participant")
                val choices = actors.filter { it.campaignId == draft.campaignId && draft.campaignId != null && draft.participants.none { member -> member.actorId == it.actorId } }
                    .filter { query.isBlank() || encounterName(it.actorId, profiles).contains(query.trim(), true) }
                if (!profileState.first || !actorState.first) Text("Loading campaign members…", color = RebuildPalette.Muted)
                else if (choices.isEmpty()) Text("No matching campaign members. Add or review their membership from Campaign first.", color = RebuildPalette.Muted)
                if (draft.participants.size >= 100) Text("100 participants selected. Remove one before adding another.", color = RebuildPalette.Muted)
                if (choices.size > 40) Text("Showing the first 40 matches. Search for a participant to narrow the list.", color = RebuildPalette.Muted)
                choices.take(40).takeIf { profileState.first && actorState.first }.orEmpty().forEach { actor ->
                    val name = encounterName(actor.actorId, profiles)
                    PlayerOutlinedButton(enabled = editable && draft.participants.size < 100, onClick = {
                        draft = draft.copy(participants = draft.participants + GenesisEncounterMemberDraft(actor.actorId, actor.revision))
                    }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("Add $name")
                            Text(genesisRoleLabel(actor.role) + if (actor.playable) " · Playable sheet" else " · Source record", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            2 -> {
                GenesisReviewSection("Details", { select(0) }) {
                    GenesisReviewValue("Name", draft.title)
                    GenesisReviewValue("Campaign", campaigns.firstOrNull { it.id == draft.campaignId }?.name.orEmpty(), "Choose a campaign")
                    GenesisReviewValue("Notes", draft.notes, "No notes")
                }
                GenesisReviewSection("Participants", { select(1) }) {
                    if (draft.participants.isEmpty()) Text("No participants yet. You can save this setup and add them before starting.")
                    draft.participants.forEachIndexed { index, member -> GenesisReviewValue("${index + 1}. ${encounterName(member.actorId, profiles)}", "Initiative: ${member.initiative.ifBlank { "Not entered" }}") }
                }
                if (!membershipsValid) Text("Review changed campaign membership before saving.", color = MaterialTheme.colorScheme.error)
                if (!initiativesValid) Text("Initiative must be a whole number or blank.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
