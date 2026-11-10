package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.R
import com.yazsras.astravar.data.CampaignLedgerRepository

/** A read-only window onto the same saved journal records used by the editors. */
@Composable fun RebirthCampaignJournal(repository: CampaignLedgerRepository, open: (String) -> Unit) {
    val checkpoints by repository.party.checkpoints.collectAsState(initial = emptyList())
    val entities by repository.records.entities.collectAsState(initial = emptyList())
    val projects by repository.records.projects.collectAsState(initial = emptyList())
    val latest = checkpoints.maxByOrNull { it.updatedAt }
    val expandedText = LocalDensity.current.fontScale > 1.15f
    Page {
        PlayerHeading("Campaign") {
            if (!expandedText) PlayerTextButton(onClick = { open("Search everything") }) { Text("Search everything") }
        }
        if (expandedText) PlayerTextButton(onClick = { open("Search everything") }) { Text("Search everything") }
        PlayerArtBanner("THE CHRONICLE", "Your living world", "People, discoveries and the story so far.", R.drawable.rebirth_atlas)
        val destinations = listOf(Triple("Connections", entities.size, "People"),
            Triple("Inventions", projects.size, "Settings"), Triple("Checkpoints", checkpoints.size, "Journal"))
        if (expandedText) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            destinations.forEach { (title, count, icon) -> JournalDestination(title, count.toString(), icon,
                Modifier.fillMaxWidth(), horizontal = true) { open(title) } }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            destinations.forEach { (title, count, icon) -> JournalDestination(title, count.toString(), icon,
                Modifier.weight(1f)) { open(title) } }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("LATEST SAVED CHECKPOINT", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.labelMedium)
            if (latest == null) {
                Text("Where the story pauses", style = MaterialTheme.typography.headlineSmall, fontFamily = DisplayFace)
                Text("Save the scene, your last action and the decision waiting for you.", color = RebuildPalette.Muted)
                PlayerOutlinedButton(onClick = { open("Checkpoints") }) { Text("Record a checkpoint") }
            } else {
                Text(latest.title, style = MaterialTheme.typography.headlineSmall, fontFamily = DisplayFace,
                    modifier = Modifier.semantics { heading() })
                if (latest.scene.isNotBlank()) Text(latest.scene, color = RebuildPalette.Ice, style = MaterialTheme.typography.titleMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (latest.lastConfirmedAction.isNotBlank()) Text(latest.lastConfirmedAction,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                if (latest.unresolvedChoice.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().background(RebuildPalette.Elevated, RoundedCornerShape(8.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Still unresolved", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.labelLarge)
                        Text(latest.unresolvedChoice, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                PlayerTextButton(onClick = { open("Checkpoints") }) { Text("Read checkpoint") }
            }
        }
        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .5f))
        JournalSectionHeading("People & places", "View all") { open("Connections") }
        if (entities.isEmpty()) {
            Text("Keep the people, groups and places you discover in one journal.", color = RebuildPalette.Muted)
            PlayerTextButton(onClick = { open("Connections") }) { Text("Add a connection") }
        } else entities.sortedByDescending { it.updatedAt }.take(3).forEach { entity ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open("Connections:${entity.id}") }
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PartyPortrait(entity.imageBase64, entity.name, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entity.name, style = MaterialTheme.typography.titleMedium)
                    Text(entity.relationship.ifBlank { entity.kind }, color = RebuildPalette.Muted,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("›", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (projects.isNotEmpty()) {
            HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .5f))
            JournalSectionHeading("At the workbench", "View all") { open("Inventions") }
            projects.sortedByDescending { it.updatedAt }.take(3).forEach { project ->
                PlayerEntry(project.title, project.status, "Settings", { open("Inventions:${project.id}") })
            }
        }
        PlayerDisclosure("Library & source records") {
            PlayerEntry("Reference Library", "Original lore, discoveries and source history", "Journal", { open("Reference Library") })
            PlayerEntry("Campaign import", "Review a saved archive before importing", "Settings", { open("Campaign import") })
        }
    }
}

@Composable private fun JournalDestination(title: String, count: String, icon: String, modifier: Modifier, horizontal: Boolean = false, open: () -> Unit) {
    if (horizontal) {
        Row(modifier.clip(RoundedCornerShape(8.dp)).background(RebuildPalette.Elevated)
            .clickable(role = Role.Button, onClick = open).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArcaneIcon(icon, Modifier.size(24.dp), RebuildPalette.GoldBright)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(count, style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
        }
        return
    }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(RebuildPalette.Elevated)
        .clickable(role = Role.Button, onClick = open).padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArcaneIcon(icon, Modifier.size(22.dp), RebuildPalette.GoldBright)
        Text(count, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(title, style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun JournalSectionHeading(title: String, action: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        PlayerTextButton(onClick = open) { Text(action) }
    }
}
