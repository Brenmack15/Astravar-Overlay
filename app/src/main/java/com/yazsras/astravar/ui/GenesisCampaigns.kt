package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun GenesisCampaignHub(environment: GenesisConnectedEnvironment, open: (String) -> Unit,
    openActor: (String, Boolean) -> Unit) {
    val campaignState by remember(environment.authority) { environment.authority.campaigns.map { true to it } }
        .collectAsState(initial = false to emptyList<GenesisCampaignRecord>())
    val associationState by remember(environment.authority) { environment.authority.actorAssociations.map { true to it } }
        .collectAsState(initial = false to emptyList<GenesisActorAuthority>())
    val profiles by environment.campaign.records.characterProfiles.collectAsState(initial = emptyList())
    val playable by environment.local.observeAll().collectAsState(initial = emptyList())
    val campaigns = campaignState.second
    val actors = associationState.second
    val selected = campaigns.firstOrNull { it.id == environment.selectedCampaignId && !it.archived }
    var section by rememberSaveable { mutableStateOf("People") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun selectCampaign(id: String?) {
        scope.launch {
            try { environment.preferences.selectCampaign(id); status = "Campaign context selected"; dialog = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Campaign selection could not be saved." }
        }
    }
    Column(Modifier.fillMaxSize().background(RebuildPalette.Canvas)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (LocalDensity.current.fontScale >= 1.5f) {
                PlayerHeading("Campaign")
                PlayerTextButton(onClick = { dialog = "Campaigns" }) { Text("Choose campaign") }
            } else PlayerHeading("Campaign") {
                PlayerTextButton(onClick = { dialog = "Campaigns" }) { Text("Choose campaign") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.name ?: if (environment.selectedCampaignId == null) "Unassigned campaign" else "Selected campaign unavailable", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                PlayerTextButton(onClick = { open("Encounters") }) { Text("Encounters") }
            }
            PlayerTabs(listOf("People", "Journal"), section, { section = it })
        }
        Box(Modifier.weight(1f)) {
            if (section == "Journal") Column(Modifier.fillMaxSize()) {
                Text("Journal · all retained campaign records", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Muted)
                Box(Modifier.weight(1f)) { RebirthCampaignJournal(environment.campaign, open) }
            } else Page {
                if (!campaignState.first || !associationState.first || !environment.selectionLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Membership is explicit. Selecting a campaign does not move anyone into it.", color = RebuildPalette.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerButton(onClick = { dialog = "NPC" }, enabled = environment.selectionLoaded) { Text("Create NPC") }
                    PlayerOutlinedButton(onClick = { dialog = "Members" }) { Text("Add existing") }
                }
                PlayerSearch(query, { query = it }, "Find a campaign member")
                val members = actors.filter { it.campaignId == environment.selectedCampaignId }
                    .filter { actor -> query.isBlank() || profiles.firstOrNull { it.id == actor.actorId }?.name?.contains(query.trim(), true) == true }
                if (members.isEmpty()) PlayerEmpty("No matching members", "Create an NPC or assign an existing character or companion. Saved records outside this campaign remain available.")
                members.forEach { actor ->
                    val profile = profiles.firstOrNull { it.id == actor.actorId }
                    PlayerEntry(profile?.name ?: "Saved actor", genesisRoleLabel(actor.role) + if (actor.playable) " · Shared gameplay values" else " · Source record", "People",
                        { dialog = "Actor:${actor.actorId}" }, trailing = "Membership")
                    if (actor.playable && playable.any { it.character.id == actor.actorId && !it.character.archived }) {
                        PlayerTextButton(onClick = { openActor(actor.actorId, actor.role == GenesisActorRole.COMPANION) }) { Text("Open ${profile?.name ?: "sheet"}") }
                    } else if (actor.role == GenesisActorRole.NPC) {
                        PlayerTextButton(onClick = { open("Connections:${actor.actorId}") }) { Text("Open NPC details") }
                    }
                }
                if (status.isNotBlank()) Text(status, color = RebuildPalette.Ice)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    when (dialog) {
        "Campaigns" -> PlayerSheet("Choose campaign", { dialog = null }) {
            PlayerEntry("Unassigned campaign", "Browse explicitly unassigned records", "Journal", { selectCampaign(null) }, trailing = if (environment.selectedCampaignId == null) "Selected" else null)
            campaigns.filterNot { it.archived }.forEach { campaign ->
                PlayerEntry(campaign.name, campaign.notes.ifBlank { "Saved campaign" }, "Journal", { selectCampaign(campaign.id) }, trailing = if (selected?.id == campaign.id) "Selected" else null)
            }
            PlayerButton(onClick = { dialog = "CreateCampaign" }) { Text("Create campaign") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        "CreateCampaign" -> GenesisCampaignEditor(environment.authority, { dialog = null }) { created -> selectCampaign(created.id) }
        "NPC" -> GenesisSourceActorEditor(environment, GenesisActorRole.NPC, { dialog = null }, { status = "NPC saved in campaign records"; dialog = null })
        "Members" -> PlayerSheet("Add existing member", { dialog = null }) {
            Text("Choose a saved identity and review its membership. No record is duplicated.", color = RebuildPalette.Muted)
            PlayerSearch(query, { query = it }, "Find a saved actor")
            profiles.filter { query.isBlank() || it.name.contains(query.trim(), true) }.take(60).forEach { profile ->
                PlayerEntry(profile.name, genesisOwnerLabel(profile, profiles, actors, campaigns), "People", { dialog = "Actor:${profile.id}" })
            }
            playable.filter { saved -> profiles.none { it.id == saved.character.id } && !saved.character.archived && (query.isBlank() || saved.character.name.contains(query.trim(), true)) }.take(60).forEach { saved ->
                PlayerEntry(saved.character.name, "Playable sheet · needs shared-value review", "Emblem", { dialog = "Actor:${saved.character.id}" })
            }
        }
    }
    dialog?.takeIf { it.startsWith("Actor:") }?.substringAfter(':')?.let { id ->
        GenesisActorMembershipEditor(environment, id, { dialog = null })
    }
}

@Composable internal fun GenesisCampaignEditor(authority: GenesisAuthorityRepository, onDismiss: () -> Unit,
    onSaved: (GenesisCampaignRecord) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    val command = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    GenesisEditorSheet("Create campaign", listOf("Details", "Review"), true,
        "Give your campaign its own identity. Existing records stay where they are until you assign them.", onDismiss,
        dirty = name.isNotBlank() || notes.isNotBlank(), saveLabel = "Save campaign", saveEnabled = name.isNotBlank(), busy = busy, error = error,
        onSave = {
            if (!busy) {
                busy = true; error = ""
                scope.launch {
                    try { onSaved(authority.createCampaign(name, notes, command)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Campaign was not saved. Your draft is retained." }
                    finally { busy = false }
                }
            }
        }) { step, select ->
        if (step == 0) {
            Field("Campaign name", name, { name = it }, enabled = !busy)
            Field("Campaign notes", notes, { notes = it }, single = false, enabled = !busy)
        } else GenesisReviewSection("Details", { select(0) }) {
            GenesisReviewValue("Name", name)
            GenesisReviewValue("Notes", notes, "No notes")
        }
    }
}

/** Membership and mechanics have separate explicit save paths. Frozen review never mutates on open. */
@Composable internal fun GenesisActorMembershipEditor(environment: GenesisConnectedEnvironment, actorId: String,
    onDismiss: () -> Unit, initialRole: GenesisActorRole = GenesisActorRole.PLAYER) {
    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<GenesisActorAuthority?>(null) }
    var profile by remember { mutableStateOf<CharacterProfile?>(null) }
    var playable by remember { mutableStateOf<ReforgeSavedCharacter?>(null) }
    var role by rememberSaveable(actorId) { mutableStateOf(initialRole) }
    var campaignId by rememberSaveable(actorId) { mutableStateOf(environment.selectedCampaignId) }
    var ownerId by rememberSaveable(actorId) { mutableStateOf<String?>(null) }
    var initialized by rememberSaveable(actorId) { mutableStateOf(false) }
    var initialMembership by remember { mutableStateOf<Triple<GenesisActorRole, String?, String?>?>(null) }
    var review by remember { mutableStateOf<GenesisActorAuthorityReview?>(null) }
    var approved by remember { mutableStateOf(false) }
    var command by rememberSaveable(actorId) { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(actorId) {
        try {
            original = environment.authority.actor(actorId)
            profile = environment.campaign.records.characterProfiles.first().firstOrNull { it.id == actorId }
            playable = environment.local.get(actorId)
            val baseline = original?.let { Triple(it.role, it.campaignId, it.ownerActorId) }
                ?: Triple(initialRole, environment.selectedCampaignId, null)
            initialMembership = baseline
            if (!initialized) {
                role = baseline.first; campaignId = baseline.second; ownerId = baseline.third
                initialized = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "These records could not be loaded." }
        finally { loaded = true }
    }
    fun action(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Nothing was saved. Review the current records and try again." }
            finally { busy = false }
        }
    }
    val frozen = review
    PlayerSheet(if (frozen == null) "Campaign membership" else "Review shared values", onDismiss,
        dirty = loaded && initialMembership != null && Triple(role, campaignId, ownerId) != initialMembership,
        dismissEnabled = !busy, actions = {
            if (frozen != null) {
                PlayerTextButton(enabled = !busy, onClick = {
                    action {
                        review = environment.authority.reviewActorAuthority(actorId, role, campaignId, ownerId)
                        approved = false; command = UUID.randomUUID().toString()
                    }
                }) { Text("Revise review") }
                PlayerButton(enabled = approved && !busy && genesisLegacyModifiersReviewed(frozen), onClick = {
                    action {
                        val saved = environment.authority.acceptActorAuthority(frozen, command)
                        original = saved.actor; profile = saved.profile; playable = saved.playable
                        initialMembership = Triple(role, campaignId, ownerId)
                        review = null; approved = false; command = UUID.randomUUID().toString()
                        status = "Shared values connected and saved"
                    }
                }) { Text("Accept reviewed values") }
            } else {
                if (original != null || (profile != null && playable == null)) PlayerButton(enabled = loaded && !busy, onClick = {
                    action {
                        val old = original
                        original = if (old == null) environment.authority.registerActorAssociation(actorId, requireNotNull(profile).revision, role, campaignId, ownerId, command).actor
                            else environment.authority.updateActorAssociation(actorId, old.revision, role, campaignId, ownerId, command)
                        initialMembership = Triple(role, campaignId, ownerId)
                        command = UUID.randomUUID().toString(); status = "Membership saved"
                    }
                }) { Text("Save membership") }
                if (playable != null) PlayerOutlinedButton(enabled = loaded && !busy, onClick = {
                    action { review = environment.authority.reviewActorAuthority(actorId, role, campaignId, ownerId); approved = false; command = UUID.randomUUID().toString() }
                }) { Text("Review shared values") }
            }
        }) {
        Text(profile?.name ?: playable?.character?.name ?: "Saved actor", style = MaterialTheme.typography.titleLarge)
        if (!loaded || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (frozen == null && loaded) {
            GenesisAssociationFields(environment, actorId, role, { role = it }, campaignId, { campaignId = it }, ownerId, { ownerId = it }, enabled = !busy)
            Text(if (original?.playable == true) "Gameplay values are shared across character and campaign views. Membership changes preserve the actor and its history."
                else "Membership does not approve imported mechanics. Review shared values separately before connecting a playable sheet.", color = RebuildPalette.Muted)
        } else if (frozen != null) {
            GenesisAssociationSummary(environment, frozen.role, frozen.campaignId, frozen.ownerActorId)
            PlayerTextButton(enabled = !busy, onClick = { review = null; approved = false; command = UUID.randomUUID().toString() }) { Text("Edit membership") }
            Text("Your reviewed playable values will become the shared gameplay values. The original source snapshot and provenance remain in an immutable receipt.", color = RebuildPalette.Muted)
            if (frozen.differences.isEmpty()) Text("The shared values already agree.", color = RebuildPalette.Ice)
            frozen.differences.forEach { difference ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(difference.field, style = MaterialTheme.typography.titleSmall)
                    Text("Source: ${difference.sourceValue}", color = RebuildPalette.Muted)
                    Text("Shared: ${difference.playableValue}")
                }
            }
            key(command, frozen.fingerprint) {
                GenesisLegacyModifierReviewEditor(frozen, busy) { proofs ->
                    action {
                        review = environment.authority.reviewActorAuthority(actorId, role, campaignId, ownerId, proofs)
                        approved = false; command = UUID.randomUUID().toString()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy, role = Role.Checkbox) { approved = !approved },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = approved, onCheckedChange = null, enabled = !busy)
                Text("I reviewed these values and membership", Modifier.weight(1f))
            }
        }
        if (status.isNotBlank()) Text(status, color = RebuildPalette.Ice)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
