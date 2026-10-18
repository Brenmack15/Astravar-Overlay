package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.R
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.rules.Core
import com.yazsras.astravar.rules.DamageType

val campaignDestinationsV2 = listOf("Astravar", "Inventory", "Campaign", "More")

@Composable fun CampaignNavigationV2(selected: String, change: (String) -> Unit) {
    Column(Modifier.instrumentSurface().navigationBarsPadding()) {
        HorizontalDivider(color = Brass.copy(alpha = .4f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            campaignDestinationsV2.forEach { name ->
                val active = selected == name
                Column(Modifier.weight(1f).heightIn(min = 64.dp)
                    .semantics { this.selected = active }.clickable(role = Role.Tab) { change(name) }
                    .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArcaneIcon(when(name) {"Astravar" -> "Emblem"; "Inventory" -> "Core"; "Campaign" -> "History"; else -> "More"},
                        Modifier.size(22.dp), if(active) Brass else Muted)
                    Text(name, fontSize = 12.sp, color = if(active) Brass else Muted)
                    Box(Modifier.width(if(active) 24.dp else 0.dp).height(2.dp).background(Frost))
                }
            }
        }
    }
}

@Composable fun V2SectionTabs(labels: List<String>, selected: String, change: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().instrumentSurface().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { label ->
                val active = selected == label
                Box(Modifier.weight(1f).heightIn(min = 52.dp).clip(CutCornerShape(10.dp))
                    .metalSurface(if (active) Brass else Muted, lit = active)
                    .semantics { this.selected = active }
                    .clickable(role = Role.Tab) { change(label) }
                    .padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontFamily = InterfaceFace, fontSize = 13.sp, color = if (active) Brass else Frost,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable fun ArmorOverviewV2(
    state: Campaign, ledger: CampaignLedgerRepository, combat: () -> Unit, systems: () -> Unit,
    eek: () -> Unit, inventory: () -> Unit, elements: List<DamageType> = emptyList(), selectElement: () -> Unit = combat
) {
    var capabilitiesOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Page {
        CharacterOverviewV2(ledger, state)
        val cardRatio = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) .85f else 1f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerCard(modifier = Modifier.weight(1f).aspectRatio(cardRatio), onClick = selectElement, padding = 12.dp, spacing = 0.dp) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Active element", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth())
                    ElementParticle(elements.firstOrNull() ?: DamageType.FORCE, Modifier.size(56.dp))
                    Text(elements.ifEmpty { listOf(DamageType.FORCE) }.joinToString(" + ") {
                        it.name.lowercase().replaceFirstChar(Char::titlecase)
                    }, color = RebuildPalette.Ice, style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            PlayerCard(modifier = Modifier.weight(1f).aspectRatio(cardRatio), onClick = { capabilitiesOpen = true }, padding = 12.dp, spacing = 0.dp) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Core systems", color = RebuildPalette.Muted, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth())
                    InstrumentMedallion("Core", Modifier.size(56.dp), RebuildPalette.Gold)
                    Text("Capabilities", color = RebuildPalette.Gold, style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (capabilitiesOpen) ArmorCapabilitiesRebuild(onDismiss = { capabilitiesOpen = false })
}

@Composable fun WeaponSetupV2(state: Campaign, actions: Actions, rules: () -> Unit, restore: () -> Unit) {
    Page {
        Section("Confirm your weapon profile") {
            Text("Your existing Astravar engine is preserved. This separate preview starts without assuming cores, attunement, slots or live campaign resources.")
            Text("Saved starter values: ${state.profile.character} · level ${state.profile.level} · INT modifier ${state.profile.intelligence} · proficiency ${state.profile.proficiency} · DC ${state.profile.saveDc}. Review them before play.", color = Muted)
            Action("Review profile & accepted rules", onClick = rules)
            Action("Confirm this weapon profile", !actions.busy) {
                actions.change("Weapon profile confirmed") { it.copy(onboarded = true) }
            }
            PlayerTextButton(onClick = restore) { Text("Restore my weapon backup") }
        }
        Section("Optional fictional demo") {
            Text("Explicitly creates test weapon resources in this preview only. It does not import or replace the installed release.", color = Muted)
            Action("Start labeled demo", !actions.busy) {
                actions.change("Explicit demo started") {
                    it.copy(onboarded = true, demo = true, attuned = true, installedId = "demo-core",
                        cores = listOf(Core("demo-core", "DEMO crystal"), Core("demo-spare", "DEMO spare")), slots = mapOf(1 to 4, 2 to 2))
                }
            }
        }
    }
}

@Composable fun V2ControlRoom(open: (String) -> Unit) {
    Page {
        SectionCaption("ASTRAVAR 2.0", "PREVIEW")
        ControlRoomLink("Synthetic connectivity lab", "Public fictional records · status, receipts and retry", "Emblem") { open("Sync") }
        ControlRoomLink("Campaign backup & restore", "Inventory, inventions, connections, Eek and audit", "History") { open("Campaign Backup") }
        ControlRoomLink("Weapon backup & restore", "Preserve the separate existing combat save", "Core") { open("Backup") }
        ControlRoomLink("Rules & weapon configuration", "Existing engine, damage settings and approved upgrades", "Weapon") { open("Rules") }
        ControlRoomLink("Weapon resources & recovery", "Recorded game time, slots and core recovery", "Core") { open("Resources") }
        ControlRoomLink("Overlay & appearance", "Floating controls, permission and artwork", "Settings") { open("Settings") }
        ControlRoomLink("Help & diagnostics", "Build identity, device details and integration limits", "More") { open("Help") }
        Text("Campaign records stay local. The public sandbox uses only fixed fictional test messages in a separate database.",
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
