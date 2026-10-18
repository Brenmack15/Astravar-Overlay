package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val rebuildDestinations = listOf("Armor", "Combat", "Campaign", "Config")

@Composable fun RebuildAppHeader(demo: Boolean) {
    Row(Modifier.fillMaxWidth().background(RebuildPalette.Void).statusBarsPadding()
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ArcaneIcon("Emblem", Modifier.size(24.dp), Brass)
        Text("ASTRAVAR", fontFamily = DisplayFace, fontSize = 20.sp, letterSpacing = 2.sp, color = Brass)
        Text("Mk.I", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        if (demo) Text("DEMO", color = RebuildPalette.Cyan, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable fun RebuildPrimaryNavigation(selected: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(RebuildPalette.Void).navigationBarsPadding().padding(horizontal = 8.dp)) {
        rebuildDestinations.forEach { name ->
            val active = name == selected
            Column(Modifier.weight(1f).heightIn(min = 68.dp).semantics { this.selected = active }
                .clickable(role = Role.Tab) { change(name) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(56.dp).height(28.dp).background(if (active) RebuildPalette.Active else Color.Transparent,
                    RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    if (name == "Combat") ApprovedAttackMedallion(ApprovedAttackArt.ELEMENTAL, Modifier.size(24.dp))
                    else ArcaneIcon(when (name) { "Armor" -> "Emblem"; "Campaign" -> "History"; else -> "Settings" },
                        Modifier.size(24.dp), if (active) RebuildPalette.Cyan else Muted)
                }
                Text(name, style = MaterialTheme.typography.labelLarge, color = if (active) Frost else Muted)
            }
        }
    }
}

@Composable fun RebuildCampaignHub(open: (String) -> Unit) {
    Page {
        PlayerHeading("Campaign")
        listOf(Triple("Inventory", "Items & equipment", "Core"),
            Triple("Inventions", "Blueprints & construction", "Settings"),
            Triple("Connections", "People, groups & milestones", "Emblem"),
            Triple("Eek", "Companion & equipment", "Emblem")).forEach { (name, detail, icon) ->
            PlayerCard(onClick = { open(name) }) {
                PlayerEntry(name, detail, icon, { open(name) })
            }
        }
    }
}

@Composable fun RebuildConfigHub(open: (String) -> Unit) {
    Page {
        PlayerHeading("Config")
        SectionCaption("Systems")
        PlayerEntry("Core management", "Install, charge & recover", "Core", { open("Core management") })
        PlayerEntry("Spell library", "Configured templates & payloads", "Payload", { open("Spell library") })
        PlayerEntry("Rules & profile", "Accepted configuration & upgrades", "Weapon", { open("Rules & profile") })
        PlayerEntry("Resources & recovery", "Confirmed game time & resources", "Core", { open("Resources & recovery") })
        PlayerEntry("Roll history", "Saved rolls & exact dice", "History", { open("Roll history") })
        SectionCaption("App & data")
        PlayerEntry("Overlay & appearance", "Floating controls & app preferences", "Settings", { open("Overlay & appearance") })
        PlayerEntry("Campaign backup", "Items, projects, connections & companion", "History", { open("Campaign backup") })
        PlayerEntry("Weapon backup", "Separate combat save", "History", { open("Weapon backup") })
        PlayerEntry("Fictional sync", "Public disposable test records", "More", { open("Fictional sync") })
        PlayerEntry("Help & diagnostics", "Device & build information", "More", { open("Help & diagnostics") })
    }
}

/** Bounded tool sheet gives legacy full-content tools their own internal scrolling area. */
@Composable fun RebuildToolSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.96f),
                color = RebuildPalette.Canvas, shape = RoundedCornerShape(24.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        PlayerTextButton(onClick = onDismiss) { Text("Close") }
                    }
                    Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalPlayerToolTitle provides title) { content() } }
                }
            }
        }
    }
}
