package com.yazsras.astravar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.Preferences
import com.yazsras.astravar.rules.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Weapon configuration keeps the original repositories, rules and snapshot semantics. */
@Composable fun CoresPage(s: Campaign, a: Actions) {
    var coreId by rememberSaveable { mutableStateOf<String?>(null) }
    var addCore by rememberSaveable { mutableStateOf(false) }
    var charge by rememberSaveable { mutableStateOf(false) }
    Page {
        PlayerHeading("Resonant cores") {
            PlayerTextButton(onClick = { addCore = true }) { Text("Add core") }
        }
        if (s.cores.isEmpty()) PlayerEmpty("No cores recorded", "Record only completed cores you own.", "Core")
        s.cores.forEach { core ->
            val ready = core.usable(s.gameSeconds)
            PlayerCard(onClick = { coreId = core.id }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArcaneIcon("Core", Modifier.size(32.dp), if (ready) RebuildPalette.Ice else RebuildPalette.Muted)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(core.name, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (core.id == s.installedId) "EQUIPPED" else "SPARE", style = MaterialTheme.typography.labelSmall,
                                color = if (core.id == s.installedId) RebuildPalette.Gold else RebuildPalette.Muted)
                            Text(if (ready) "READY" else "UNAVAILABLE", style = MaterialTheme.typography.labelSmall,
                                color = if (ready) RebuildPalette.Ice else RebuildPalette.Muted)
                        }
                    }
                    Text("${core.payloads.size}/${s.profile.capacity}", color = RebuildPalette.Ice,
                        style = MaterialTheme.typography.labelLarge)
                    Text("›", color = RebuildPalette.Muted, style = MaterialTheme.typography.titleLarge)
                }
                Text(core.payloads.joinToString { it.template.name }.ifBlank { "No payload loaded" },
                    color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        PlayerButton(onClick = { charge = true }, modifier = Modifier.fillMaxWidth()) { Text("Charge / overwrite payload") }
        CraftingSection(s, a)
    }
    val core = s.cores.firstOrNull { it.id == coreId }
    if (core != null) WeaponCoreDetailsSheet(s, a, core) { coreId = null }
    if (addCore) RecordCoreSheet(s, a) { addCore = false }
    if (charge) ChargePayloadSheet(s, a) { charge = false }
}

@Composable private fun RecordCoreSheet(s: Campaign, a: Actions, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val revision = rememberSaveable { s.revision }
    val commandId = rememberSaveable { UUID.randomUUID().toString() }
    val newCoreId = rememberSaveable { UUID.randomUUID().toString() }
    var attempted by rememberSaveable { mutableStateOf(false) }
    PlayerSheet("Record a completed core", close, dirty = name.isNotBlank(), showCancel = true, actions = {
        PlayerButton(onClick = {
            attempted = true
            val enteredName = name.trim()
            a.run("Completed core recorded") {
                a.repo.mutate(commandId, "Player confirmed completed core: $enteredName") { current ->
                    requireWeaponDraftRevision(current, revision)
                    current.copy(cores = current.cores + Core(newCoreId, enteredName))
                }
                close()
            }
        }, enabled = !a.busy && name.isNotBlank() && s.revision == revision) { Text("Record core") }
    }) {
        Field("Name of a completed core you actually own", name, { name = it }, enabled = !a.busy)
        WeaponDraftStatus(s, revision, a, attempted)
    }
}

@Composable private fun WeaponCoreDetailsSheet(s: Campaign, a: Actions, core: Core, close: () -> Unit) {
    var step by rememberSaveable { mutableStateOf("details") }
    var payloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewRevision by rememberSaveable { mutableStateOf(s.revision) }
    var commandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val payload = core.payloads.firstOrNull { it.id == payloadId }
    val installCost = if (s.postShatter) s.profile.replacementCost else s.profile.swapCost
    val title = when (step) { "install" -> "Install core"; "purge" -> "Purge stored magic?"; else -> core.name }
    PlayerSheet(title, close, actions = {
        if (step == "install" || step == "purge") PlayerButton(onClick = {
            attempted = true
            val operation = step
            val targetPayloadId = payload?.id
            val expectedRevision = reviewRevision
            val operationId = commandId
            a.run(if (operation == "install") "Core installation confirmed" else "Payload purged") {
                val label = if (operation == "install") "Install ${core.name}" else "Purge payload $targetPayloadId · Bonus Action"
                a.repo.mutate(operationId, label) { current ->
                    requireWeaponDraftRevision(current, expectedRevision)
                    if (operation == "install") Engine.install(current, core.id)
                    else Engine.purge(current, core.id, targetPayloadId ?: error("Payload no longer available"))
                }
                close()
            }
        }, enabled = !a.busy && s.revision == reviewRevision &&
            if (step == "install") core.usable(s.gameSeconds) && core.id != s.installedId && s.installation == null
            else payload != null && !core.destroyed) { Text(if (step == "install") "Confirm install" else "Confirm purge") }
    }) {
        if (step != "details") PlayerTextButton(onClick = { step = "details"; attempted = false }) { Text("‹ Core details") }
        when (step) {
            "install" -> {
                Text(core.name, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Ice)
                Text("Cost: $installCost", style = MaterialTheme.typography.bodyLarge)
                if (s.postShatter && installCost == "10 in-game minutes") Text("Starts installation; recorded game time must advance before completion.")
                else Text("Confirmation applies the existing installation action cost.")
                WeaponDraftStatus(s, reviewRevision, a, attempted)
            }
            "purge" -> {
                Text(payload?.template?.name ?: "Payload no longer available", style = MaterialTheme.typography.titleMedium)
                Text("Spends a Bonus Action. The selected spell energy is lost without refund.")
                WeaponDraftStatus(s, reviewRevision, a, attempted)
            }
            else -> {
                Text(core.readiness(s.gameSeconds), color = RebuildPalette.Ice, style = MaterialTheme.typography.bodyMedium)
                Text("${core.payloads.size}/${s.profile.capacity} stored payloads", color = RebuildPalette.Muted)
                if (core.id != s.installedId && !core.destroyed) PlayerOutlinedButton(onClick = {
                    step = "install"; reviewRevision = s.revision; commandId = UUID.randomUUID().toString(); attempted = false
                }, enabled = !a.busy && core.usable(s.gameSeconds) && s.installation == null,
                    modifier = Modifier.fillMaxWidth()) { Text("Install · $installCost") }
                if (core.payloads.isEmpty()) Text("No payload loaded", color = RebuildPalette.Muted)
                core.payloads.forEach { stored ->
                    PlayerDisclosure(stored.template.name) {
                        PayloadExpressions(stored.template)
                        Text("Level ${stored.template.castingLevel} · ${stored.source.name.lowercase()}", color = RebuildPalette.Muted)
                        Text(stored.template.approvalNotes, style = MaterialTheme.typography.bodySmall)
                        Text("Loaded at game second ${stored.loadedAt}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                        if (!core.destroyed) PlayerTextButton(onClick = {
                            payloadId = stored.id; step = "purge"; reviewRevision = s.revision
                            commandId = UUID.randomUUID().toString(); attempted = false
                        }, enabled = !a.busy) { Text("Purge · Bonus Action") }
                    }
                }
            }
        }
    }
}

@Composable private fun ChargePayloadSheet(s: Campaign, a: Actions, close: () -> Unit) {
    var coreId by rememberSaveable { mutableStateOf(s.installedId ?: s.cores.firstOrNull { !it.destroyed }?.id) }
    var templateId by rememberSaveable { mutableStateOf(s.templates.firstOrNull { it.approved }?.id) }
    var source by rememberSaveable { mutableStateOf(SourceKind.SLOT) }
    var scrollId by rememberSaveable { mutableStateOf<String?>(null) }
    var overwrite by rememberSaveable { mutableStateOf<String?>(null) }
    var touch by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val revision = rememberSaveable { s.revision }
    val commandId = rememberSaveable { UUID.randomUUID().toString() }
    val core = s.cores.firstOrNull { it.id == coreId }
    val template = s.templates.firstOrNull { it.id == templateId }
    PlayerSheet("Charge / overwrite payload", close, dirty = dirty, showCancel = true, actions = {
        PlayerButton(onClick = {
            attempted = true
            val targetCoreId = coreId
            val targetTemplateId = templateId
            val sourceKind = source
            val sourceScrollId = scrollId.takeIf { sourceKind == SourceKind.SCROLL }
            val overwrittenId = overwrite
            val touchConfirmed = touch
            val manualConfirmed = manual
            a.run("Core charged; source spent once") {
                a.repo.mutate(commandId, if (overwrittenId == null) "Charge core · Bonus Action" else "Overwrite payload $overwrittenId · Bonus Action · no refund") { current ->
                    requireWeaponDraftRevision(current, revision)
                    Engine.charge(current, targetCoreId ?: error("Choose core"), current.templates.firstOrNull { it.id == targetTemplateId }
                        ?: error("Choose template"), commandId, sourceKind, touchConfirmed, manualConfirmed, sourceScrollId, overwrittenId)
                }
                close()
            }
        }, enabled = !a.busy && s.revision == revision && core != null && template?.approved == true && touch &&
            (source != SourceKind.SCROLL || scrollId != null)) { Text(if (overwrite == null) "Charge · spend source" else "Overwrite · spend source") }
    }) {
        Text("Bonus Action", color = RebuildPalette.Gold, style = MaterialTheme.typography.labelLarge)
        Choices("Available core", s.cores.filterNot { it.destroyed }.map { it.id }, coreId,
            display = { id -> s.cores.firstOrNull { it.id == id }?.name ?: "Unavailable core" }, enabled = !a.busy) { coreId = it; overwrite = null; dirty = true }
        Choices("Approved payload template", s.templates.filter { it.approved }.map { it.id }, templateId,
            display = { id -> s.templates.firstOrNull { it.id == id }?.name ?: "Unavailable template" }, enabled = !a.busy) { templateId = it; scrollId = null; dirty = true }
        template?.let { PayloadExpressions(it) }
        Choices("Source", SourceKind.entries.toList(), source, enabled = !a.busy) { source = it; dirty = true }
        if (source == SourceKind.SCROLL) {
            Choices("Consume matching scroll", s.scrolls.filter { it.quantity > 0 && it.templateId == templateId }.map { it.id }, scrollId,
                display = { id -> s.scrolls.firstOrNull { it.id == id }?.let { "${it.quantity} remaining" } ?: "Unavailable scroll" }, enabled = !a.busy) { scrollId = it; dirty = true }
        } else {
            Text(template?.let { "Level ${it.castingLevel} slots: ${s.slots[it.castingLevel]?.toString() ?: "unknown"}" } ?: "Choose a template", color = RebuildPalette.Muted)
            Toggle("Confirm available slot if local count is unknown", manual, !a.busy) { manual = it; dirty = true }
        }
        Choices("Payload to overwrite", listOf<String?>(null) + core?.payloads.orEmpty().map { it.id }, overwrite,
            display = { id -> core?.payloads?.firstOrNull { it.id == id }?.template?.name ?: "Do not overwrite" }, enabled = !a.busy) { overwrite = it; dirty = true }
        if (overwrite != null) Text("The selected old energy is lost without refund.", color = MaterialTheme.colorScheme.error)
        Toggle("I am touching this available core", touch, !a.busy) { touch = it; dirty = true }
        WeaponDraftStatus(s, revision, a, attempted)
    }
}

@Composable fun PayloadsPage(s: Campaign, a: Actions, preferences: Preferences? = null, selected: Set<String> = emptySet()) {
    var workspace by rememberSaveable { mutableStateOf<String?>(null) }
    var storedId by rememberSaveable { mutableStateOf<String?>(null) }
    var templateId by rememberSaveable { mutableStateOf<String?>(null) }
    var templateSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    Page {
        PlayerHeading("Stored payloads")
        if (s.installed?.payloads.isNullOrEmpty()) PlayerEmpty("No stored payloads", "Charge an approved payload through Core management.", "Payload")
        s.installed?.payloads?.forEach { payload ->
            val active = payload.id in selected
            PlayerCard(onClick = { storedId = payload.id }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArcaneIcon("Payload", Modifier.size(28.dp), elementTint(payload.template.overchargeType))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(payload.template.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (active) "SELECTED" else "STORED", style = MaterialTheme.typography.labelSmall,
                            color = if (active) RebuildPalette.Gold else RebuildPalette.Muted)
                    }
                    Text("L${payload.template.castingLevel}", style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Ice)
                }
                PayloadExpressions(payload.template)
                if (preferences != null) PlayerOutlinedButton(onClick = {
                    a.run("Payload selection saved") {
                        preferences.payloads(if (active) selected - payload.id else (selected + payload.id).toList().takeLast(s.profile.releaseLimit).toSet())
                        preferences.mode(Ability.SPELL)
                    }
                }, enabled = !a.busy && s.installed?.usable(s.gameSeconds) == true,
                    modifier = Modifier.fillMaxWidth()) { Text(if (active) "Deselect" else "Select payload") }
            }
        }
        PlayerEntry("Spell templates", "${s.templates.count { it.approved }} approved · ${s.templates.size} recorded", "Payload", { workspace = "templates" })
        PlayerEntry("Scroll sources", "${s.scrolls.sumOf { it.quantity }} confirmed scrolls", "History", { workspace = "scrolls" })
        PlayerButton(onClick = { templateSnapshot = null; workspace = "editor" }, modifier = Modifier.fillMaxWidth()) { Text("Create damage template") }
    }
    val stored = s.installed?.payloads?.firstOrNull { it.id == storedId }
    if (stored != null) PlayerSheet(stored.template.name, { storedId = null }) {
        PayloadExpressions(stored.template)
        Text("Level ${stored.template.castingLevel} · ${stored.source.name.lowercase()}", color = RebuildPalette.Muted)
        Text(stored.template.approvalNotes)
        Text(stored.sourceReference, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        Text("Loaded at game second ${stored.loadedAt}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
    }
    when (workspace) {
        "templates" -> TemplateLibrarySheet(s, templateId, { templateId = it }, { workspace = null }) { template ->
            templateSnapshot = template?.let { Json.encodeToString(it) }; workspace = "editor"
        }
        "editor" -> TemplateEditorSheet(s, a, templateSnapshot, { workspace = "templates" }) { id ->
            templateId = id; workspace = "templates"
        }
        "scrolls" -> ScrollSourcesSheet(s, a) { workspace = null }
    }
}

@Composable private fun TemplateLibrarySheet(s: Campaign, selectedId: String?, select: (String?) -> Unit,
    close: () -> Unit, edit: (Template?) -> Unit) {
    val selected = s.templates.firstOrNull { it.id == selectedId }
    PlayerSheet(selected?.name ?: "Spell templates", close, actions = {
        PlayerButton(onClick = { edit(selected) }) { Text(if (selected == null) "Create template" else "Edit future loads") }
    }) {
        if (selected == null) {
            if (s.templates.isEmpty()) PlayerEmpty("No spell templates", "Add manually approved instantaneous damage.", "Payload")
            s.templates.forEach { template ->
                PlayerEntry(template.name, "Level ${template.baseLevel} → ${template.castingLevel} · ${if (template.approved) "Approved" else "Needs ruling"}",
                    "Payload", { select(template.id) })
            }
        } else {
            PlayerTextButton(onClick = { select(null) }) { Text("‹ All templates") }
            PayloadExpressions(selected)
            Text("Base level ${selected.baseLevel} · casting level ${selected.castingLevel}", color = RebuildPalette.Muted)
            Text(if (selected.approved) "Approved" else "Needs ruling", color = RebuildPalette.Ice)
            Text(selected.approvalNotes)
            Text("Editing affects future loads. Stored payload snapshots stay unchanged.", color = RebuildPalette.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun TemplateEditorSheet(s: Campaign, a: Actions, snapshotJson: String?, close: () -> Unit, saved: (String) -> Unit) {
    val snapshot = remember(snapshotJson) { snapshotJson?.let { Json.decodeFromString<Template>(it) } }
    val templateId = rememberSaveable { snapshot?.id ?: UUID.randomUUID().toString() }
    val commandId = rememberSaveable { UUID.randomUUID().toString() }
    val revision = rememberSaveable { s.revision }
    var name by rememberSaveable { mutableStateOf(snapshot?.name.orEmpty()) }
    var base by rememberSaveable { mutableStateOf(snapshot?.baseLevel?.toString() ?: "2") }
    var casting by rememberSaveable { mutableStateOf(snapshot?.castingLevel?.toString() ?: "2") }
    var count by rememberSaveable { mutableStateOf("3") }
    var sides by rememberSaveable { mutableStateOf("6") }
    var flat by rememberSaveable { mutableStateOf("0") }
    var type by rememberSaveable { mutableStateOf(DamageType.COLD) }
    val originalComponents = remember(snapshotJson) { Json.encodeToString(snapshot?.damage ?: emptyList<TypedDice>()) }
    var componentsJson by rememberSaveable { mutableStateOf(originalComponents) }
    val components = remember(componentsJson) { Json.decodeFromString<List<TypedDice>>(componentsJson) }
    var overcharge by rememberSaveable { mutableStateOf(snapshot?.overchargeType ?: DamageType.COLD) }
    var approved by rememberSaveable { mutableStateOf(snapshot?.approved ?: false) }
    var notes by rememberSaveable { mutableStateOf(snapshot?.approvalNotes.orEmpty()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val dirty = name != snapshot?.name.orEmpty() || base != (snapshot?.baseLevel?.toString() ?: "2") ||
        casting != (snapshot?.castingLevel?.toString() ?: "2") || componentsJson != originalComponents ||
        overcharge != (snapshot?.overchargeType ?: DamageType.COLD) || approved != (snapshot?.approved ?: false) ||
        notes != snapshot?.approvalNotes.orEmpty() || count != "3" || sides != "6" || flat != "0" || type != DamageType.COLD
    PlayerSheet(if (snapshot == null) "Create damage template" else "Edit future loads", close, dirty, showCancel = true, actions = {
        PlayerButton(onClick = {
            attempted = true
            val enteredName = name.trim()
            val enteredBase = base
            val enteredCasting = casting
            val enteredComponents = components.toList()
            val enteredOvercharge = overcharge
            val enteredApproved = approved
            val enteredNotes = notes
            a.run("Template saved; existing loads unchanged") {
                a.repo.mutate(commandId, "Saved payload template; existing loads unchanged") { current ->
                    requireWeaponDraftRevision(current, revision)
                    val template = Template(templateId, enteredName, enteredBase.intValue("Base level", 1, 9), enteredCasting.intValue("Casting level", 1, 9),
                        enteredComponents, enteredOvercharge, enteredApproved, enteredNotes)
                    template.validate()
                    current.copy(templates = current.templates.filterNot { it.id == template.id } + template)
                }
                saved(templateId)
            }
        }, enabled = !a.busy && s.revision == revision && name.isNotBlank() && components.isNotEmpty()) { Text("Save template") }
    }) {
        Field("Spell/payload name", name, { name = it }, enabled = !a.busy)
        Field("Base spell level", base, { base = it }, enabled = !a.busy)
        Field("Actual slot or documented scroll casting level", casting, { casting = it }, enabled = !a.busy)
        PlayerDisclosure("Damage components", initiallyOpen = true) {
            components.forEachIndexed { index, damage ->
                PlayerTextButton(onClick = { componentsJson = Json.encodeToString(components.filterIndexed { i, _ -> i != index }) }, enabled = !a.busy) {
                    Text("${damage.dice} ${damage.type.name.lowercase()} · remove")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Dice count", count, { count = it }, Modifier.weight(1f), enabled = !a.busy)
                Field("Die sides", sides, { sides = it }, Modifier.weight(1f), enabled = !a.busy)
            }
            Field("Flat damage", flat, { flat = it }, enabled = !a.busy)
            Choices("Damage type", DamageType.entries.toList(), type, enabled = !a.busy) { type = it }
            PlayerOutlinedButton(onClick = {
                try {
                    val dice = Dice(count.intValue("Count", 0, 200), sides.intValue("Sides", 4, 100), flat.intValue("Flat", -100, 100))
                    dice.validate(); componentsJson = Json.encodeToString(components + TypedDice(dice, type))
                } catch (error: Exception) { attempted = true; a.message = error.message ?: "Invalid dice" }
            }, enabled = !a.busy, modifier = Modifier.fillMaxWidth()) { Text("Add typed component") }
            Text("Enter approved upcast dice explicitly.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Choices("Overcharge type · 1d8 per actual level", DamageType.entries.toList(), overcharge, enabled = !a.busy) { overcharge = it }
        Field("Eligibility / approval notes", notes, { notes = it }, single = false, enabled = !a.busy)
        Toggle("Instantaneous components and source eligibility approved", approved, !a.busy) { approved = it }
        Text("No conditions, concentration, ongoing damage, forced movement or summons transfer. Multi-projectile totals need an explicit ruling.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        WeaponDraftStatus(s, revision, a, attempted)
    }
}

@Composable private fun ScrollSourcesSheet(s: Campaign, a: Actions, close: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var templateId by rememberSaveable { mutableStateOf<String?>(null) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var revision by rememberSaveable { mutableStateOf(s.revision) }
    var commandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var scrollId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    PlayerSheet(if (adding) "Record confirmed scrolls" else "Scroll sources", close,
        dirty = adding && (templateId != null || quantity.isNotBlank()), showCancel = adding, actions = {
            if (adding) PlayerButton(onClick = {
                attempted = true
                val expectedRevision = revision
                val operationId = commandId
                val enteredScrollId = scrollId
                val enteredTemplateId = templateId
                val enteredQuantity = quantity
                a.run("Confirmed scrolls recorded") {
                    a.repo.mutate(operationId, "Player confirmed scroll inventory") { current ->
                        requireWeaponDraftRevision(current, expectedRevision)
                        val template = current.templates.firstOrNull { it.id == enteredTemplateId } ?: error("Choose payload")
                        current.copy(scrolls = current.scrolls + Scroll(enteredScrollId, template.id, enteredQuantity.intValue("Quantity", 1, 100000), template))
                    }
                    close()
                }
            }, enabled = !a.busy && s.revision == revision && templateId != null && quantity.isNotBlank()) { Text("Record scrolls") }
            else PlayerButton(onClick = {
                adding = true; revision = s.revision; commandId = UUID.randomUUID().toString(); scrollId = UUID.randomUUID().toString()
            }) { Text("Add scrolls") }
        }) {
        if (adding) {
            Choices("Documented scroll payload", s.templates.map { it.id }, templateId,
                display = { id -> s.templates.firstOrNull { it.id == id }?.name ?: "Unavailable template" }, enabled = !a.busy) { templateId = it }
            Field("Additional scrolls actually owned", quantity, { quantity = it }, enabled = !a.busy)
            WeaponDraftStatus(s, revision, a, attempted)
        } else {
            if (s.scrolls.isEmpty()) Text("No confirmed scrolls", color = RebuildPalette.Muted)
            s.scrolls.forEach { scroll ->
                Text("${scroll.documentedTemplate?.name ?: s.templates.firstOrNull { it.id == scroll.templateId }?.name ?: "Unknown template"} · ${scroll.quantity} scroll(s)",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PayloadExpressions(template: Template) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        template.damage.forEach { typed ->
            Surface(color = elementTint(typed.type).copy(alpha = .065f), shape = RoundedCornerShape(6.dp),
                border = BorderStroke(.5.dp, elementTint(typed.type).copy(alpha = .25f))) {
                Text("${typed.dice} ${typed.type.name.lowercase().replaceFirstChar(Char::titlecase)}",
                    Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge, color = elementTint(typed.type))
            }
        }
    }
    Text("+ ${template.castingLevel}d8 ${template.overchargeType.name.lowercase()} overcharge",
        style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
}

@Composable fun CraftingSection(s: Campaign, a: Actions) {
    var open by rememberSaveable { mutableStateOf(false) }
    PlayerEntry("Core crafting", "${s.projects.size} planned projects", "Core", { open = true })
    if (open) CoreCraftingSheet(s, a) { open = false }
}

@Composable private fun CoreCraftingSheet(s: Campaign, a: Actions, close: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDay by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var recipe by rememberSaveable { mutableStateOf("Replacement core") }
    var revision by rememberSaveable { mutableStateOf(s.revision) }
    var commandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val newProjectId = rememberSaveable { UUID.randomUUID().toString() }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val recipes = mapOf(
        "First prototype" to "Existing pistol + cult crystal; 150 gp total; 3 focused in-game days; no checks. Special first build only.",
        "Replacement core" to "One suitable rare Resonant Crystal + 100 gp conductive magical metal + 50 gp filament + 50 gp catalyst + 50 gp stabilizing reagents (250 gp secondary total); 3 focused crafting days with tools/workspace.",
        "Synthetic (proposed)" to "Level 20: natural crystal requirement removed. Cost/time require a ruling; configured ${s.profile.syntheticGp ?: "unknown"} gp / ${s.profile.syntheticDays ?: "unknown"} days."
    )
    val project = s.projects.firstOrNull { it.id == projectId }
    val title = when { creating -> "Plan core crafting"; confirmDay -> "Record completed day?"; project != null -> project.name; else -> "Core crafting" }
    PlayerSheet(title, close, dirty = creating && (name.isNotBlank() || notes.isNotBlank() || recipe != "Replacement core"),
        showCancel = creating || confirmDay, actions = {
            when {
                creating -> PlayerButton(onClick = {
                    attempted = true
                    val expectedRevision = revision
                    val operationId = commandId
                    val enteredName = name
                    val enteredRecipe = recipes.getValue(recipe)
                    val enteredNotes = notes
                    a.run("Crafting project recorded") {
                        a.repo.mutate(operationId, "Crafting project recorded") { current ->
                            requireWeaponDraftRevision(current, expectedRevision)
                            current.copy(projects = current.projects + CraftProject(newProjectId, enteredName, enteredRecipe, notes = enteredNotes))
                        }
                        close()
                    }
                }, enabled = !a.busy && s.revision == revision && name.isNotBlank()) { Text("Record planned project") }
                confirmDay && project != null -> PlayerButton(onClick = {
                    attempted = true
                    val expectedRevision = revision
                    val operationId = commandId
                    val targetProjectId = project.id
                    val targetProjectName = project.name
                    a.run("Focused crafting day recorded") {
                        a.repo.mutate(operationId, "Player recorded one focused crafting day for $targetProjectName") { current ->
                            requireWeaponDraftRevision(current, expectedRevision)
                            require(current.projects.any { it.id == targetProjectId }) { "Project no longer available" }
                            current.copy(projects = current.projects.map { if (it.id == targetProjectId) it.copy(daysWorked = it.daysWorked + 1) else it })
                        }
                        confirmDay = false
                    }
                }, enabled = !a.busy && s.revision == revision) { Text("Confirm completed day") }
                project == null -> PlayerButton(onClick = {
                    creating = true; revision = s.revision; commandId = UUID.randomUUID().toString(); attempted = false
                }, enabled = !a.busy) { Text("Add project") }
            }
        }) {
        when {
            creating -> {
                Choices("Recipe", recipes.keys.toList(), recipe, enabled = !a.busy) { recipe = it }
                Text(recipes.getValue(recipe), style = MaterialTheme.typography.bodyMedium)
                Field("Project name", name, { name = it }, enabled = !a.busy)
                Field("Materials / campaign notes", notes, { notes = it }, single = false, enabled = !a.busy)
                Text("A plan does not award a core or spend gold.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
                WeaponDraftStatus(s, revision, a, attempted)
            }
            confirmDay && project != null -> {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                Text("Record one focused day already completed in play. Current progress: ${project.daysWorked} day(s).")
                Text("Does not advance game time, spend gold or create a core.", color = RebuildPalette.Muted)
                PlayerTextButton(onClick = { confirmDay = false }, enabled = !a.busy) { Text("‹ Project details") }
                WeaponDraftStatus(s, revision, a, attempted)
            }
            project != null -> {
                PlayerTextButton(onClick = { projectId = null }, enabled = !a.busy) { Text("‹ All projects") }
                Text("${project.daysWorked} focused day(s)", color = RebuildPalette.Ice, style = MaterialTheme.typography.titleMedium)
                Text(project.recipe, style = MaterialTheme.typography.bodyMedium)
                if (project.notes.isNotBlank()) Text(project.notes)
                PlayerOutlinedButton(onClick = {
                    confirmDay = true; revision = s.revision; commandId = UUID.randomUUID().toString(); attempted = false
                }, enabled = !a.busy, modifier = Modifier.fillMaxWidth()) { Text("Record one completed focused day") }
            }
            else -> {
                if (s.projects.isEmpty()) Text("No core crafting projects", color = RebuildPalette.Muted)
                s.projects.forEach { item -> PlayerEntry(item.name, "${item.daysWorked} focused day(s)", "Core", { if (!a.busy) projectId = item.id }) }
            }
        }
    }
}

private fun requireWeaponDraftRevision(current: Campaign, revision: Long) {
    require(current.revision == revision) { "Configuration changed. Reopen this form to review the current state." }
}

@Composable private fun WeaponDraftStatus(s: Campaign, revision: Long, a: Actions, attempted: Boolean) {
    if (s.revision != revision) Text("Configuration changed. Your draft is retained; close and reopen before saving.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (attempted && !a.busy && a.message.isNotBlank()) Text(a.message, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
}
