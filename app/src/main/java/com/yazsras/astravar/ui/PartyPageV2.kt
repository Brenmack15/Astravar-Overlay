package com.yazsras.astravar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.data.CharacterProfile
import com.yazsras.astravar.data.CompanionState
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.R
import kotlinx.coroutines.CancellationException
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

/** Stable built-in identities plus every real saved companion. No names or counts are hardcoded. */
internal fun partyRoster(profiles: List<CharacterProfile>, states: List<CompanionState>): List<AstravarPartyMember> {
    val byProfile = profiles.associateBy { it.id }
    val byState = states.associateBy { it.companionId }
    val known = astravarPartyMembers.map { member ->
        member.copy(label = byProfile[member.id]?.name?.takeIf(String::isNotBlank)
            ?: byState[member.id]?.name?.takeIf(String::isNotBlank) ?: member.label)
    }
    // An unrelated NPC profile is not automatically a party member.
    val additional = states.map { it.companionId }.distinct()
        .filterNot { id -> known.any { it.id == id } || id == "local-player" }
        .map { id -> AstravarPartyMember(id, byProfile[id]?.name?.takeIf(String::isNotBlank)
            ?: byState[id]?.name?.takeIf(String::isNotBlank) ?: "Unnamed companion", "Companion") }
    return known + additional.sortedBy { it.label.lowercase() }
}

/** Stable profile IDs share the existing status, abilities, inventory and audit stores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyPageV2(repository: CampaignLedgerRepository, campaign: Campaign?, openReferences: (() -> Unit)? = null, openInventory: () -> Unit) {
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    val states by repository.records.companionStates.collectAsState(initial = emptyList())
    val sheets by repository.party.sheets.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    val roster = remember(profiles, states) { partyRoster(profiles, states) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf("Sheet") }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var creationError by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = roster.firstOrNull { it.id == selected }
    BackHandler(enabled = current != null && !detailsOpen) { selected = null }
    Page(resetKey = selected) {
        PlayerHeading(if (current == null) "Party & family" else current.label) {
            if (current != null) PlayerTextButton(onClick = { selected = null }) { Text("‹ Party") }
        }
        if (openReferences != null) PlayerEntry("Party source dossiers", "Read imported character, companion and homebrew references", "History", openReferences)
        if (current == null) {
            PlayerArtBanner("YOUR ALLIES", "Companions", "Keep every ally close.", R.drawable.astravar_companions_banner)
            Text("Character sheets, shared equipment and sourced progression. Missing values remain unknown.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            PlayerButton(onClick = { newName = ""; creationError = ""; adding = true }) { Text("+ Add companion") }
            roster.forEach { member ->
                val profile = profiles.firstOrNull { it.id == member.id }
                val state = states.firstOrNull { it.companionId == member.id }
                val sheet = sheets.firstOrNull { it.profileId == member.id }?.sheet
                PlayerCard(onClick = { selected = member.id; section = "Sheet" }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (sheet?.portraitBase64 != null) LedgerIcon(sheet.portraitBase64,
                            Modifier.size(64.dp), "Portrait for ${member.label}")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile?.name?.ifBlank { member.label } ?: member.label,
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Text(sheet?.role?.ifBlank { member.role } ?: member.role,
                                style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                            Text(listOfNotNull(profile?.level?.let { "Level $it" },
                                profile?.let { "HP ${it.currentHp ?: "?"} / ${it.maximumHp ?: "?"}" },
                                state?.condition, "${placements.count { it.holderProfileId == member.id && it.state !in setOf("Consumed", "Sold", "Lost") }} held item(s)")
                                .joinToString(" · "), color = RebuildPalette.Ice,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sheet", "Equipment", "Currency", "History").forEach { title ->
                    PlayerFilterChip(selected = section == title, onClick = { section = title }, label = { Text(title) })
                }
            }
            when (section) {
                "Sheet" -> {
                    CharacterOverviewV2(repository = repository, campaign = campaign, profileId = current.id)
                    if (current.id == "eek") PlayerEntry("Eek's specialized companion tools",
                        "Existing approved mechanics, records, resources and equipment links", "Emblem",
                        onClick = { detailsOpen = true })
                    if (current.id in setOf("korr", "veyrath")) Text(
                        "HP in older review references is disputed. Keep unresolved values unknown until a later authoritative source establishes the correct current and maximum HP.",
                        color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
                    PartyTypedSheetV2(repository, current.id, current.label)
                    if (current.id != "local-player") PartyCompanionEditorV2(repository, current.id, current.label)
                }
                "Equipment" -> PartyEquipmentV2(repository, current.id, openInventory)
                "Currency" -> PartyWalletV2(repository, current.id)
                "History" -> PartyHistoryV2(repository, current.id)
            }
        }
    }
    if (detailsOpen && current?.id == "eek") RebuildToolSheet("Eek details", onDismiss = { detailsOpen = false }) {
        EekPageV2(repository)
    }
    if (adding) PlayerSheet("Add companion", onDismiss = { if (!creating) adding = false },
        dirty = newName.isNotBlank(), actions = {
            PlayerButton(enabled = !creating && newName.isNotBlank(), onClick = {
                if (creating) return@PlayerButton
                creating = true; creationError = ""
                scope.launch {
                    try {
                        val id = repository.records.createCompanion(newName)
                        selected = id; section = "Sheet"; adding = false; newName = ""
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { creationError = e.message ?: "Companion could not be saved." }
                    finally { creating = false }
                }
            }) { Text("Create companion") }
        }) {
        Text("Add a companion without filling in a complicated character sheet. Statistics, health and equipment stay unknown until you record them.",
            color = RebuildPalette.Muted)
        Field("Companion name", newName, { newName = it }, enabled = !creating)
        if (creating) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (creationError.isNotBlank()) Text(creationError, color = MaterialTheme.colorScheme.error)
    }
}
