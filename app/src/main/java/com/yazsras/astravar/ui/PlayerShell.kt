package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yazsras.astravar.data.CampaignLedgerRepository
import com.yazsras.astravar.R

/** A place for each everyday task. Combat lives on the character sheet; tools are in the header. */
val rebuildDestinations = listOf("Character", "Inventory", "Companions", "Campaign")

@Composable fun RebuildAppHeader(demo: Boolean, search: (() -> Unit)? = null, tools: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val brandStyle = androidx.compose.ui.text.TextStyle(fontFamily = DisplayFace, fontSize = 18.sp, letterSpacing = 1.2.sp)
    val actionStyle = MaterialTheme.typography.labelLarge
    val actions = listOfNotNull(search?.let { "Find" to it }, tools?.let { "Tools" to it })
    val widths = actions.associate { (name, _) -> name to with(density) {
        // Reserve the measured single-line label plus content padding and a small
        // rounding allowance. Row measurement must not squeeze the first action
        // into the space left beside the brand before deciding to wrap.
        measurer.measure(name, style = actionStyle, softWrap = false, maxLines = 1).size.width.toDp() + 32.dp
    } }
    val brandWidth = with(density) { measurer.measure("ASTRAVAR", style = brandStyle).size.width.toDp() } + 32.dp
    val demoWidth = if (demo) with(density) {
        measurer.measure("DEMO", style = MaterialTheme.typography.labelSmall).size.width.toDp()
    } else 0.dp
    val actionCount = actions.size + if (demo) 1 else 0
    val actionWidth = widths.values.fold(demoWidth) { total, width -> total + width.coerceAtLeast(48.dp) } +
        8.dp * (actionCount - 1).coerceAtLeast(0)
    val brand: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArcaneIcon("Emblem", Modifier.size(24.dp), RebuildPalette.GoldBright)
            Text("ASTRAVAR", style = brandStyle, color = RebuildPalette.GoldBright, maxLines = 1, softWrap = false)
        }
    }
    val controls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (name, action) ->
                PlayerTextButton(onClick = action, modifier = Modifier.widthIn(min = widths.getValue(name))) {
                    Text(name, style = actionStyle, maxLines = 1, softWrap = false)
                }
            }
            if (demo) Text("DEMO", color = RebuildPalette.Cyan, style = MaterialTheme.typography.labelSmall)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(RebuildPalette.Void).statusBarsPadding()
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (brandWidth + actionWidth + 8.dp <= maxWidth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) { brand(); controls() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { brand(); controls() }
        }
    }
}

@Composable fun RebuildPrimaryNavigation(selected: String, change: (String) -> Unit) {
    val labelStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidths = rebuildDestinations.associateWith { name ->
        textMeasurer.measure(name, style = labelStyle, softWrap = false, maxLines = 1).size.width
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(RebuildPalette.Void)
        .navigationBarsPadding().padding(horizontal = 8.dp)) {
        val iconWidth = with(density) { 56.dp.toPx() }
        val labelPadding = with(density) { 4.dp.toPx() }
        fun fits(rows: List<List<String>>) = rows.all { row ->
            row.all { name -> maxOf(iconWidth, labelWidths.getValue(name).toFloat()) + labelPadding <= constraints.maxWidth.toFloat() / row.size }
        }
        // Large-font layouts wrap while retaining 48dp touch targets and whole labels.
        val rows = listOf(rebuildDestinations).takeIf(::fits)
            ?: (3 downTo 1).map { rebuildDestinations.chunked(it) }.firstOrNull(::fits)
            ?: rebuildDestinations.map { listOf(it) }
        Column {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { name ->
                        val active = name == selected
                        Column(Modifier.weight(1f).heightIn(min = 68.dp).testTag("navigation-$name").semantics { this.selected = active }
                            .clickable(role = Role.Tab) { change(name) }.padding(horizontal = 2.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.width(56.dp).height(28.dp).background(if (active) RebuildPalette.Active else Color.Transparent,
                                RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                ArcaneIcon(when (name) { "Character" -> "Emblem"; "Inventory" -> "Core"; "Companions" -> "More"; else -> "History" },
                                    Modifier.size(24.dp), if (active) RebuildPalette.GoldBright else Muted)
                            }
                            Text(name, Modifier.fillMaxWidth(), style = labelStyle,
                                color = if (active) Frost else Muted, textAlign = TextAlign.Center,
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/** The playable gear and original campaign ledger are different stores. Keep both discoverable. */
@Composable fun RebuildInventoryHub(repository: CampaignLedgerRepository, openReferences: () -> Unit,
    requestedSection: String = "My gear", routeToken: Int = 0) {
    var section by rememberSaveable { mutableStateOf("My gear") }
    LaunchedEffect(routeToken) { section = requestedSection }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("My gear", "Campaign items").forEach { name ->
                PlayerFilterChip(selected = section == name, onClick = { section = name },
                    modifier = Modifier.weight(1f), label = { Text(name) })
            }
        }
        Box(Modifier.weight(1f)) {
            if (section == "My gear") ReforgeCharacterSheet(repository, initialSection = "Equipment", sectionOnly = true)
            else CampaignLedgerPage(repository, openReferences)
        }
    }
}

@Composable fun RebuildCampaignHub(open: (String) -> Unit) {
    Page {
        PlayerHeading("Campaign")
        PlayerArtBanner("YOUR CHRONICLE", "The living world", "Keep the story connected.", R.drawable.astravar_atlas_banner)
        Text("Your people, projects, and story in one place.", color = RebuildPalette.Muted)
        listOf(Triple("Search everything", "Find anything in your campaign", "History"),
            Triple("Connections", "People, groups and relationships", "Emblem"),
            Triple("Inventions", "Builds, blueprints and progress", "Settings"),
            Triple("Checkpoints", "Session progress and unresolved choices", "History")).forEach { (name, detail, icon) ->
            PlayerCard(onClick = { open(name) }) { PlayerEntry(name, detail, icon, { open(name) }) }
        }
        PlayerDisclosure("Library & source records") {
            PlayerEntry("Reference Library", "Imported records and original source history", "History", { open("Reference Library") })
            PlayerEntry("Campaign import", "Review an imported archive", "Settings", { open("Campaign import") })
        }
    }
}

@Composable fun RebuildConfigHub(open: (String) -> Unit) {
    Page {
        PlayerHeading("Tools & settings")
        PlayerEntry("Armor systems", "Your accepted Astravar abilities", "Weapon", { open("Armor systems") })
        PlayerEntry("ChatGPT Prompts", "Edit campaign handoff prompts", "More", { open("ChatGPT Prompts") })
        PlayerEntry("Appearance", "Visual and feedback preferences", "Settings", { open("Appearance") })
        PlayerDisclosure("Advanced combat & magic") {
            PlayerEntry("Armor weapon", "Specialized attack engine", "Weapon", { open("Armor weapon") })
            PlayerEntry("Spell reservoir", "Internal core and spell loads", "Core", { open("Spell reservoir") })
            PlayerEntry("Spell library", "Spell templates and payloads", "Payload", { open("Spell library") })
            PlayerEntry("Rules & profile", "Accepted upgrades and configuration", "Weapon", { open("Rules & profile") })
            PlayerEntry("Resources & recovery", "Resources and game time", "Core", { open("Resources & recovery") })
            PlayerEntry("Roll history", "Saved dice and rolls", "History", { open("Roll history") })
        }
        PlayerDisclosure("Records, backups & connection") {
            PlayerEntry("Character sources", "Original campaign values and revisions", "History", { open("Character sources") })
            PlayerEntry("Character backup", "Backup your playable sheets", "History", { open("Character backup") })
            PlayerEntry("Campaign backup", "Backup campaign records", "History", { open("Campaign backup") })
            PlayerEntry("Campaign import", "Review an imported archive", "History", { open("Campaign import") })
            PlayerEntry("Owner connection", "Trusted pairing and approvals", "Settings", { open("Owner connection") })
            PlayerEntry("Weapon backup", "Backup the separate weapon system", "History", { open("Weapon backup") })
            if (com.yazsras.astravar.BuildConfig.DEBUG) {
                PlayerEntry("Fictional sync", "Isolated test lab", "More", { open("Fictional sync") })
                PlayerEntry("Remote Updates", "Disposable test profile", "Settings", { open("Remote Updates") })
            }
        }
        PlayerEntry("Help & diagnostics", "Device & build information", "More", { open("Help & diagnostics") })
    }
}

/** Bounded tool sheet gives legacy full-content tools their own internal scrolling area. */
@Composable fun RebuildToolSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.96f),
                color = RebuildPalette.Canvas, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(24.dp)) {
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
