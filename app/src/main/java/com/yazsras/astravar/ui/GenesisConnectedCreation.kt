package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** UI context only. All records remain in the existing shared Room repositories. */
internal data class GenesisConnectedEnvironment(
    val authority: GenesisAuthorityRepository,
    val campaign: CampaignLedgerRepository,
    val local: ReforgeRepository,
    val preferences: ReforgePreferences,
    val selectedCampaignId: String? = null,
    val selectionLoaded: Boolean = true
)
internal val LocalGenesisConnectedEnvironment = staticCompositionLocalOf<GenesisConnectedEnvironment?> { null }
internal val LocalGenesisCharacterCreationFields = staticCompositionLocalOf<@Composable () -> Unit> { {} }
internal val LocalGenesisCharacterCreationReview = staticCompositionLocalOf<@Composable () -> Unit> { {} }

internal fun genesisRoleLabel(role: GenesisActorRole) = when (role) {
    GenesisActorRole.PLAYER -> "Character"
    GenesisActorRole.COMPANION -> "Companion"
    GenesisActorRole.NPC -> "NPC"
}

/** Show a stable identity only when ordinary contextual labels cannot distinguish choices. */
internal fun genesisOwnerLabel(profile: CharacterProfile, profiles: List<CharacterProfile>,
    actors: List<GenesisActorAuthority>, campaigns: List<GenesisCampaignRecord>): String {
    fun context(candidate: CharacterProfile): String {
        val association = actors.firstOrNull { it.actorId == candidate.id }
        return listOfNotNull(association?.role?.let(::genesisRoleLabel),
            association?.campaignId?.let { id -> campaigns.firstOrNull { it.id == id }?.name },
            candidate.level?.let { "Level $it" }).joinToString(" · ").ifBlank { "Saved profile" }
    }
    val label = context(profile)
    val ambiguous = profiles.count { it.name == profile.name && context(it) == label } > 1
    return if (ambiguous) "$label · ${profile.id}" else label
}

@Composable internal fun GenesisAssociationFields(
    environment: GenesisConnectedEnvironment, actorId: String? = null,
    role: GenesisActorRole, setRole: (GenesisActorRole) -> Unit,
    campaignId: String?, setCampaign: (String?) -> Unit,
    ownerId: String?, setOwner: (String?) -> Unit, enabled: Boolean = true,
    roleEditable: Boolean = true
) {
    val campaigns by environment.authority.campaigns.collectAsState(initial = emptyList())
    val actors by environment.authority.actorAssociations.collectAsState(initial = emptyList())
    val profiles by environment.campaign.records.characterProfiles.collectAsState(initial = emptyList())
    var ownerQuery by rememberSaveable { mutableStateOf("") }
    if (roleEditable) Choices("Role", GenesisActorRole.entries, role, display = ::genesisRoleLabel) {
        if (enabled) { setRole(it); if (it != GenesisActorRole.COMPANION) setOwner(null) }
    }
    Choices("Campaign", listOf<String?>(null) + campaigns.filterNot { it.archived }.map { it.id }, campaignId,
        display = { id -> campaigns.firstOrNull { it.id == id }?.name ?: if (id == null) "Unassigned campaign" else "Unavailable campaign" }) {
        if (enabled) setCampaign(it)
    }
    if (campaignId != null && campaigns.none { it.id == campaignId && !it.archived })
        Text("Choose an available campaign before saving.", color = MaterialTheme.colorScheme.error)
    if (role == GenesisActorRole.COMPANION) {
        val candidates = profiles.filter { it.id != actorId && (campaignId == null || actors.any { actor -> actor.actorId == it.id && actor.campaignId == campaignId }) }
        PlayerOutlinedButton(enabled = enabled, onClick = { setOwner(null) }) { Text(if (ownerId == null) "Unassigned owner · Selected" else "Unassigned owner") }
        PlayerSearch(ownerQuery, { if (enabled) ownerQuery = it }, "Find an owner")
        candidates.filter { ownerQuery.isBlank() || it.name.contains(ownerQuery.trim(), true) }.take(40).forEach { profile ->
            PlayerEntry(profile.name.ifBlank { "Unnamed profile" }, genesisOwnerLabel(profile, candidates, actors, campaigns), "Emblem",
                { if (enabled) setOwner(profile.id) }, trailing = if (ownerId == profile.id) "Selected" else null)
        }
        if (ownerId != null && candidates.none { it.id == ownerId }) Text("The owner must belong to this campaign. Choose an available owner or Unassigned owner.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun GenesisAssociationSummary(environment: GenesisConnectedEnvironment,
    role: GenesisActorRole, campaignId: String?, ownerId: String?) {
    val campaigns by environment.authority.campaigns.collectAsState(initial = emptyList())
    val profiles by environment.campaign.records.characterProfiles.collectAsState(initial = emptyList())
    GenesisReviewValue("Role", genesisRoleLabel(role))
    GenesisReviewValue("Campaign", campaignId?.let { id -> campaigns.firstOrNull { it.id == id }?.name ?: "Unavailable campaign" }.orEmpty(), "Unassigned campaign")
    if (role == GenesisActorRole.COMPANION) GenesisReviewValue("Owner", ownerId?.let { id -> profiles.firstOrNull { it.id == id }?.name ?: "Unavailable owner" }.orEmpty(), "Unassigned owner")
}

@Composable internal fun GenesisConnectedCharacterEditor(environment: GenesisConnectedEnvironment,
    onDismiss: () -> Unit, onSaved: (ReforgeSavedCharacter) -> Unit,
    initialRole: GenesisActorRole = GenesisActorRole.PLAYER, initialOwnerId: String? = null) {
    if (!environment.selectionLoaded) {
        PlayerSheet("Create character", onDismiss) { Text("Loading campaign context…") }
        return
    }
    var role by rememberSaveable { mutableStateOf(initialRole) }
    var campaignId by rememberSaveable { mutableStateOf(environment.selectedCampaignId) }
    var ownerId by rememberSaveable { mutableStateOf(initialOwnerId) }
    val command = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(
        LocalGenesisEditorFeedback provides GenesisEditorFeedback(busy || !environment.selectionLoaded, error),
        LocalGenesisCharacterCreationFields provides {
            GenesisAssociationFields(environment, role = role, setRole = { role = it }, campaignId = campaignId,
                setCampaign = { campaignId = it }, ownerId = ownerId, setOwner = { ownerId = it }, enabled = !busy)
        },
        LocalGenesisCharacterCreationReview provides {
            Text("Membership", style = MaterialTheme.typography.titleMedium)
            GenesisAssociationSummary(environment, role, campaignId, ownerId)
        }
    ) {
        ReforgeCharacterEditor(null, onDismiss) { candidate ->
            if (!busy && environment.selectionLoaded) {
                busy = true; error = ""
                scope.launch {
                    try {
                        val outcome = environment.authority.createActor(candidate.name, role, campaignId, ownerId, candidate, command)
                        onSaved(requireNotNull(outcome.playable))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Character was not saved. Your draft is retained." }
                    finally { busy = false }
                }
            }
        }
    }
}

@Composable internal fun GenesisSourceActorEditor(environment: GenesisConnectedEnvironment,
    initialRole: GenesisActorRole, onDismiss: () -> Unit, onSaved: (GenesisActorCreationOutcome) -> Unit,
    initialOwnerId: String? = null) {
    if (!environment.selectionLoaded) {
        PlayerSheet("Create ${genesisRoleLabel(initialRole)}", onDismiss) { Text("Loading campaign context…") }
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    var campaignId by rememberSaveable { mutableStateOf(environment.selectedCampaignId) }
    var ownerId by rememberSaveable { mutableStateOf(initialOwnerId) }
    val command = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val noun = genesisRoleLabel(initialRole)
    GenesisEditorSheet("Create $noun", listOf("Identity", "Association", "Review"), true,
        "Create one saved identity with explicit relationships.", onDismiss,
        dirty = name.isNotBlank() || campaignId != environment.selectedCampaignId || ownerId != initialOwnerId,
        saveLabel = if (initialRole == GenesisActorRole.NPC) "Create NPC" else "Create ${noun.lowercase()}", saveEnabled = name.isNotBlank() && environment.selectionLoaded,
        busy = busy, error = error, onSave = {
            if (!busy) {
                busy = true; error = ""
                scope.launch {
                    try { onSaved(environment.authority.createActor(name, initialRole, campaignId, ownerId, commandId = command)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "$noun was not saved. Your draft is retained." }
                    finally { busy = false }
                }
            }
        }) { step, select ->
        when (step) {
            0 -> { Field("$noun name", name, { name = it }, enabled = !busy)
                Text("Statistics, actions and resources stay unknown until you configure them. This does not create a playable sheet.", color = RebuildPalette.Muted) }
            1 -> GenesisAssociationFields(environment, role = initialRole, setRole = {}, campaignId = campaignId,
                setCampaign = { campaignId = it }, ownerId = ownerId, setOwner = { ownerId = it }, enabled = !busy, roleEditable = false)
            else -> {
                GenesisReviewSection("Identity", { select(0) }) { GenesisReviewValue("Name", name) }
                GenesisReviewSection("Association", { select(1) }) { GenesisAssociationSummary(environment, initialRole, campaignId, ownerId) }
                Text("The same saved identity appears in the campaign roster and its relevant companion or connection views.", color = RebuildPalette.Muted)
            }
        }
    }
}

/** Uses the frozen editor revision. Managed gameplay save already projects every physical item atomically. */
@Composable internal fun GenesisConnectedItemEditor(environment: GenesisConnectedEnvironment, saved: ReforgeSavedCharacter,
    onDismiss: () -> Unit, onSaved: () -> Unit) {
    val frozen = remember { saved }
    val command = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalGenesisEditorFeedback provides GenesisEditorFeedback(busy, error)) {
        ReforgeItemEditor(frozen.character, onDismiss, onSave = { next ->
            if (!busy) {
                busy = true; error = ""
                scope.launch {
                    try {
                        val managed = environment.authority.actor(frozen.character.id)?.playable == true
                        if (managed) {
                            val additions = next.items.filter { item -> frozen.character.items.none { it.id == item.id } }
                            val definition = next.itemDefinitions.single { definition -> frozen.character.itemDefinitions.none { it.id == definition.id } }
                            // The unchanged engine may split an equipped stack. Every new physical
                            // identity is bound to the same explicit creation receipt atomically.
                            environment.authority.createItems(frozen.character.id, frozen.revision, definition, additions, command)
                        } else environment.local.save(frozen.character.id, frozen.revision, next, "Create reviewed equipment", command)
                        onSaved()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Item was not saved. Your draft is retained." }
                    finally { busy = false }
                }
            }
        })
    }
}
