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
import com.yazsras.astravar.rules.Campaign

internal data class AstravarPartyMember(val id: String, val label: String, val role: String)
internal val astravarPartyMembers = listOf(
    AstravarPartyMember("local-player", "Zerg Hasma", "Artificer / armor operator"),
    AstravarPartyMember("eek", "Eek", "Mechanical owl / companion"),
    AstravarPartyMember("korr", "Korr", "Party / family"),
    AstravarPartyMember("veyrath", "Veyrath", "Party / family")
)
internal fun partyMemberName(id: String?): String = astravarPartyMembers.firstOrNull { it.id == id }?.label
    ?: if (id == null) "Unknown holder" else id

/** Stable profile IDs share the existing status, abilities, inventory and audit stores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyPageV2(repository: CampaignLedgerRepository, campaign: Campaign?, openInventory: () -> Unit) {
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    val states by repository.records.companionStates.collectAsState(initial = emptyList())
    val sheets by repository.party.sheets.collectAsState(initial = emptyList())
    val placements by repository.party.placements.collectAsState(initial = emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf("Sheet") }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val current = astravarPartyMembers.firstOrNull { it.id == selected }
    BackHandler(enabled = current != null && !detailsOpen) { selected = null }
    Page(resetKey = selected) {
        PlayerHeading(if (current == null) "Party & family" else current.label) {
            if (current != null) PlayerTextButton(onClick = { selected = null }) { Text("‹ Party") }
        }
        if (current == null) {
            Text("Character sheets, shared equipment and sourced progression. Missing values remain unknown.",
                color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            astravarPartyMembers.forEach { member ->
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
                    if (current.id in setOf("korr", "veyrath")) Text(
                        "HP in older review references is disputed. Keep unresolved values unknown until a later authoritative source establishes the correct current and maximum HP.",
                        color = RebuildPalette.Ice, style = MaterialTheme.typography.bodySmall)
                    PartyTypedSheetV2(repository, current.id, current.label)
                    if (current.id != "local-player") PartyCompanionEditorV2(repository, current.id, current.label)
                    if (current.id == "eek") PlayerEntry("Eek's specialized companion tools",
                        "Existing approved mechanics, records, resources and equipment links", "Emblem",
                        onClick = { detailsOpen = true })
                }
                "Equipment" -> PartyEquipmentV2(repository, current.id, openInventory)
                "Currency" -> PartyWalletV2(repository, current.id)
                "History" -> PartyHistoryV2(repository, current.id)
            }
        }
    }
    if (detailsOpen && current?.id == "eek") PlayerSheet("Eek details", onDismiss = { detailsOpen = false }) {
        EekPageV2(repository)
    }
}
