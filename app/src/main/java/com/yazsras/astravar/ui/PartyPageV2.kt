package com.yazsras.astravar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.CharacterProfile
import com.yazsras.astravar.data.CompanionState
import com.yazsras.astravar.data.ReforgeRepository
import com.yazsras.astravar.data.ReforgePreferences
import com.yazsras.astravar.data.GenesisActorAuthority
import com.yazsras.astravar.data.GenesisActorRole
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import com.yazsras.astravar.data.PartySheetData
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class AstravarPartyMember(val id: String, val label: String, val role: String)
internal val astravarPartyMembers = listOf(
    AstravarPartyMember("local-player", "Zerg Hasma", "Artificer / armor operator"),
    AstravarPartyMember("eek", "Eek", "Mechanical owl / companion"),
    AstravarPartyMember("korr", "Korr", "Party / family"),
    AstravarPartyMember("veyrath", "Veyrath", "Party / family")
)
internal fun partyMemberName(id: String?, profiles: List<CharacterProfile> = emptyList()): String =
    profiles.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank)
    ?: astravarPartyMembers.firstOrNull { it.id == id }?.label
    ?: if (id == null) "Unknown holder" else id

/** Display saved party identities only; opening an empty app never invents a campaign roster. */
internal fun partyRoster(profiles: List<CharacterProfile>, states: List<CompanionState>,
    associations: List<GenesisActorAuthority> = emptyList()): List<AstravarPartyMember> {
    val byProfile = profiles.associateBy { it.id }
    val byState = states.associateBy { it.companionId }
    val known = astravarPartyMembers.filter { it.id in byProfile || it.id in byState }.map { member ->
        member.copy(label = byProfile[member.id]?.name?.takeIf(String::isNotBlank)
            ?: byState[member.id]?.name?.takeIf(String::isNotBlank) ?: member.label)
    }
    // An unrelated NPC profile is not automatically a party member.
    val additional = states.map { it.companionId }.distinct()
        .filterNot { id -> known.any { it.id == id } || id == "local-player" }
        .map { id -> AstravarPartyMember(id, byProfile[id]?.name?.takeIf(String::isNotBlank)
            ?: byState[id]?.name?.takeIf(String::isNotBlank) ?: "Unnamed companion", "Companion") }
    val legacy = (known + additional.sortedBy { it.label.lowercase() }).filter { member ->
        associations.firstOrNull { it.actorId == member.id }?.role.let { it == null || it == GenesisActorRole.COMPANION }
    }
    val connected = associations.filter { it.role == GenesisActorRole.COMPANION && legacy.none { member -> member.id == it.actorId } }
        .map { actor -> AstravarPartyMember(actor.actorId, byProfile[actor.actorId]?.name ?: "Unnamed companion", "Companion") }
    return legacy + connected.sortedBy { it.label.lowercase() }
}

/** Stable profile IDs share the existing status, abilities, inventory and audit stores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyPageV2(
    repository: CampaignLedgerRepository,
    campaign: Campaign?,
    openReferences: (() -> Unit)? = null,
    openPlayable: ((profileId: String, section: String) -> Unit)? = null,
    playableRepository: ReforgeRepository? = null,
    openInventory: () -> Unit
) {
    val profileState by remember(repository) { repository.records.characterProfiles.map { true to it } }
        .collectAsState(initial = false to emptyList<CharacterProfile>())
    val profiles = profileState.second
    val connected = LocalGenesisConnectedEnvironment.current
    val associationState by remember(connected?.authority) {
        connected?.authority?.actorAssociations?.map { true to it }
            ?: flowOf(true to emptyList<GenesisActorAuthority>())
    }.collectAsState(initial = false to emptyList<GenesisActorAuthority>())
    val associations = associationState.second
    val states by repository.records.companionStates.collectAsState(initial = emptyList())
    val sheets by repository.party.sheets.collectAsState(initial = emptyList())
    val roster = remember(profiles, states, associations) { partyRoster(profiles, states, associations) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("Sheet") }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var membershipOpen by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var newOwnerRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var initialOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var ownerQuery by rememberSaveable { mutableStateOf("") }
    var creationCommand by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val appContext = LocalContext.current.applicationContext
    val actorPreferences = remember(appContext) { ReforgePreferences.open(appContext) }
    val actorSelection by remember(actorPreferences) { actorPreferences.activeProfileId.map { true to it } }
        .collectAsState(initial = false to null)
    val activeProfileId = actorSelection.second
    var creationError by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = roster.firstOrNull { it.id == selected }
    BackHandler(enabled = current != null && !detailsOpen) { selected = null }
    Page(resetKey = selected) {
        PlayerHeading(if (current == null) "Companions" else "Companion sheet") {
            if (current != null) PlayerTextButton(onClick = { selected = null }) { Text("‹ Party") }
        }
        if (current == null) {
            PlayerButton(onClick = {
                newName = ""; creationError = ""; ownerQuery = ""
                val owner = profiles.firstOrNull { it.id == activeProfileId }
                newOwnerId = owner?.id; newOwnerRevision = owner?.revision; initialOwnerId = owner?.id
                creationCommand = UUID.randomUUID().toString(); adding = true
            }, enabled = profileState.first && actorSelection.first && associationState.first && connected?.selectionLoaded != false) { Text("+ Add companion") }
            if (!profileState.first || !actorSelection.first || !associationState.first || connected?.selectionLoaded == false)
                Text("Loading companion context…", color = RebuildPalette.Muted)
            Text("Your companions, ready for the next chapter.", color = RebuildPalette.Muted)
            if (roster.isNotEmpty()) PlayerSearch(query, { query = it }, "Find a companion")
            val visibleRoster = roster.filter { member ->
                val sheet = sheets.firstOrNull { it.profileId == member.id }?.sheet
                query.isBlank() || "${member.label} ${sheet?.role.orEmpty()} ${sheet?.creatureType.orEmpty()}".contains(query.trim(), true)
            }
            if (roster.isEmpty()) {
                PlayerArtBanner("YOUR ALLIES", "Every story needs allies", "Add a companion to keep their health, abilities and story close.", R.drawable.astravar_companions_banner)
            } else if (visibleRoster.isEmpty()) PlayerEmpty("No matching companions", "Try a name or role from your party.")
            visibleRoster.forEach { member ->
                val profile = profiles.firstOrNull { it.id == member.id }
                val state = states.firstOrNull { it.companionId == member.id }
                val sheet = sheets.firstOrNull { it.profileId == member.id }?.sheet
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { selected = member.id; section = "Sheet" }
                    .padding(vertical = 14.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PartyPortrait(sheet?.portraitBase64, member.label, 72.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(profile?.name?.ifBlank { member.label } ?: member.label,
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        val role = sheet?.role?.takeIf(String::isNotBlank) ?: sheet?.creatureType?.takeIf(String::isNotBlank) ?: member.role
                        Text(role, style = MaterialTheme.typography.bodyMedium, color = RebuildPalette.Muted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("HP ${partyHealthLabel(profile?.currentHp, profile?.maximumHp)}", color = RebuildPalette.Ice,
                                style = MaterialTheme.typography.labelLarge)
                            profile?.armorClass?.let { Text("AC $it", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.labelLarge) }
                            profile?.level?.let { Text("Level $it", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge) }
                        }
                        state?.condition?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }?.let {
                            Text(it, color = RebuildPalette.GoldBright, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text("›", color = RebuildPalette.Gold, fontSize = 28.sp)
                }
                HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .45f))
            }
            if (openReferences != null) PlayerDisclosure("Sources & history") {
                PlayerEntry("Party source dossiers", "Original profiles, homebrew and imported references", "Journal", openReferences)
            }
        } else {
            if (connected != null) PlayerOutlinedButton(onClick = { membershipOpen = true }) { Text("Campaign membership") }
            PlayerTabs(listOf("Sheet", "Equipment", "Currency", "History"), section, { section = it })
            when (section) {
                "Sheet" -> {
                    val openCompanionSheet: ((String) -> Unit)? =
                        if (openPlayable != null && current.id != "local-player") {
                            { targetSection -> openPlayable(current.id, targetSection) }
                        } else null
                    CharacterOverviewV2(repository = repository, campaign = campaign, profileId = current.id,
                        companionSheet = sheets.firstOrNull { it.profileId == current.id }?.sheet ?: PartySheetData())
                    if (openCompanionSheet != null) key(current.id) {
                        CompanionPlayableCard(current.id, profiles.any { it.id == current.id }, playableRepository, openCompanionSheet)
                    }
                    if (current.id != "local-player") PartyCompanionEditorV2(repository, current.id, current.label)
                    PartyTypedSheetV2(repository, current.id, current.label, openCompanionSheet, playableRepository, expandedDetails = true)
                    if (current.id == "eek") PlayerEntry("Eek's specialized companion tools",
                        "Existing approved mechanics, records, resources and equipment links", "Emblem",
                        onClick = { detailsOpen = true })

                }
                "Equipment" -> PartyEquipmentV2(repository, current.id, openInventory)
                "Currency" -> PartyWalletV2(repository, current.id)
                "History" -> PartyHistoryV2(repository, current.id)
            }
            if (openReferences != null) PlayerDisclosure("Sources & history") {
                PlayerEntry("Party source dossiers", "Original profiles, homebrew and imported references", "Journal", openReferences)
            }
        }
    }
    if (detailsOpen && current?.id == "eek") RebuildToolSheet("Eek details", onDismiss = { detailsOpen = false }) {
        EekPageV2(repository)
    }
    if (membershipOpen && current != null && connected != null) GenesisActorMembershipEditor(connected, current.id, { membershipOpen = false }, initialRole = GenesisActorRole.COMPANION)
    if (adding && connected != null) GenesisSourceActorEditor(
        connected.copy(selectedCampaignId = if (associations.any { it.actorId == newOwnerId }) associations.first { it.actorId == newOwnerId }.campaignId else connected.selectedCampaignId),
        GenesisActorRole.COMPANION,
        onDismiss = { adding = false }, onSaved = { outcome -> selected = outcome.actor.actorId; section = "Sheet"; adding = false },
        initialOwnerId = newOwnerId)
    if (adding && connected == null) GenesisEditorSheet("Add companion", sections = listOf("Identity", "Association", "Review"), creating = true,
        context = "Campaign companions", onDismiss = { if (!creating) adding = false },
        dirty = newName.isNotBlank() || newOwnerId != initialOwnerId, saveLabel = "Create companion", saveEnabled = newName.isNotBlank(),
        busy = creating, error = creationError, onSave = {
                if (!creating) {
                creating = true; creationError = ""
                scope.launch {
                    try {
                        val id = repository.records.createCompanion(newName, newOwnerId, newOwnerRevision, creationCommand)
                        selected = id; section = "Sheet"; adding = false; newName = ""
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { creationError = e.message ?: "Companion could not be saved." }
                    finally { creating = false }
                }
                }
        }) { creationStep, select ->
        if (creationStep == 0) {
            Text("Begin with their name. You can record their statistics, abilities and equipment from their companion sheet.", color = RebuildPalette.Muted)
            Field("Companion name", newName, { newName = it }, enabled = !creating)
        } else if (creationStep == 1) {
            val owner = profiles.firstOrNull { it.id == newOwnerId }
            Text("Connect this companion to an existing campaign profile. You can also keep them unassigned.", color = RebuildPalette.Muted)
            PlayerEntry("Unassigned", "Keep in the campaign roster", "People", {
                newOwnerId = null; newOwnerRevision = null
            }, trailing = if (newOwnerId == null) "Selected" else null)
            PlayerSearch(ownerQuery, { ownerQuery = it }, "Find an owner")
            val matchingOwners = profiles.filter { ownerQuery.isBlank() || it.name.contains(ownerQuery.trim(), true) }
            matchingOwners.take(40).forEach { profile ->
                PlayerEntry(profile.name.ifBlank { "Unnamed profile" }, "Campaign profile", "Emblem", {
                    newOwnerId = profile.id; newOwnerRevision = profile.revision
                }, trailing = if (profile.id == newOwnerId) "Selected" else null)
            }
            if (matchingOwners.size > 40) Text("Refine your search for more profiles.", color = RebuildPalette.Muted)
            if (profiles.isEmpty()) Text("No campaign profiles are available to link yet. The companion can be saved unassigned.", color = RebuildPalette.Muted)
            if (newOwnerId != null && owner == null) Text("The selected owner is unavailable. Choose another profile or Unassigned.", color = MaterialTheme.colorScheme.error)
        } else {
            GenesisReviewSection("Identity", { select(0) }) {
                GenesisReviewValue("Companion name", newName)
                GenesisReviewValue("Destination", "Campaign companion roster")
            }
            GenesisReviewSection("Association", { select(1) }) {
                GenesisReviewValue("Owner", newOwnerId?.let { id -> profiles.firstOrNull { it.id == id }?.name ?: "Unavailable — select an owner again" }.orEmpty(), "Unassigned")
            }
            Text("Health, statistics and resources remain unknown. Creating this record does not grant abilities or create an additional playable character.", color = RebuildPalette.Muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionPlayableCard(
    profileId: String,
    hasSource: Boolean,
    repository: ReforgeRepository?,
    open: (String) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val local = remember(repository, context) { repository ?: ReforgeRepository.open(context) }
    val state by remember(local, profileId) { local.observe(profileId).map { true to it } }.collectAsState(initial = false to null)
    val (loaded, saved) = state
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Playable sheet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val description = when {
            !loaded -> "Checking this companion’s local sheet…"
            saved?.character?.archived == true -> "Archived · Restore this sheet to play."
            saved != null -> "Use this companion’s saved stats and attacks."
            hasSource -> "Review this companion’s campaign record to create a local playable sheet."
            else -> "Save this companion’s campaign profile before reviewing it for play."
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        when {
            !loaded -> Unit
            saved?.character?.archived == true ->
                PlayerOutlinedButton(onClick = { open("Sheet") }) { Text("View archived sheet") }
            saved != null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerButton(onClick = { open("Sheet") }) { Text("Open playable sheet") }
                PlayerOutlinedButton(onClick = { open("Combat") }) { Text("Go to combat") }
            }
            hasSource -> PlayerOutlinedButton(onClick = { open("Sheet") }) { Text("Review for play") }
            else -> Unit
        }
    }
}

/** Portraits belong to the saved record; a monogram never invents a creature's appearance. */
@Composable internal fun PartyPortrait(portrait: String?, name: String, size: Dp = 64.dp) {
    if (!portrait.isNullOrBlank()) LedgerIcon(portrait, Modifier.size(size).clip(RoundedCornerShape(12.dp)), "Portrait for $name")
    else Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(RebuildPalette.Elevated), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "·", color = RebuildPalette.GoldBright,
            fontFamily = DisplayFace, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun partyHealthLabel(current: Int?, maximum: Int?): String = when {
    current == null && maximum == null -> "Not set"
    else -> "${current?.toString() ?: "Not set"} / ${maximum?.toString() ?: "Not set"}"
}
