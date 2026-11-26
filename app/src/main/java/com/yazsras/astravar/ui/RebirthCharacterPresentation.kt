package com.yazsras.astravar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.reforge.*

/** Presentation only. Values and action availability always come from the saved sheet and engine. */
@Composable internal fun RebirthCharacterHeader(character: ReforgeCharacter, projection: CharacterProjection,
    onSwitch: (() -> Unit)?) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()
    val actionStyle = MaterialTheme.typography.labelLarge
    val actionLabel = "Switch character"
    val padding = ButtonDefaults.TextButtonContentPadding
    val requiredWordWidth = with(density) {
        actionLabel.split(' ').maxOf { word ->
            measurer.measure(word, style = actionStyle, softWrap = false, maxLines = 1).size.width
        }.toDp()
    } + padding.calculateLeftPadding(direction) + padding.calculateRightPadding(direction) + 2.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The compact slot may wrap between words, never through "character".
        // Leave a useful name column; move the action below when either cannot fit.
        val inline = requiredWordWidth <= 96.dp && maxWidth >= 52.dp + 24.dp + 96.dp + 144.dp * density.fontScale
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReforgePortrait(character.portraitRef, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(character.name, style = MaterialTheme.typography.headlineSmall,
                        color = RebuildPalette.Silver, modifier = Modifier.semantics { heading() })
                    Text("Level ${character.level} · Proficiency +${projection.proficiencyBonus}",
                        style = MaterialTheme.typography.bodySmall, color = RebuildPalette.GoldBright)
                    val identity = listOf(character.species, character.className).filter(String::isNotBlank).joinToString(" · ")
                    if (identity.isNotBlank()) Text(identity, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                }
                if (onSwitch != null && inline) {
                    PlayerTextButton(onClick = onSwitch, modifier = Modifier.width(96.dp)) { Text(actionLabel, style = actionStyle) }
                }
            }
            if (onSwitch != null && !inline) {
                PlayerTextButton(onClick = onSwitch) { Text(actionLabel, style = actionStyle) }
            }
        }
    }
}

@Composable internal fun RebirthVitals(character: ReforgeCharacter, projection: CharacterProjection,
    onHp: () -> Unit, onDefense: () -> Unit, onInitiative: () -> Unit) {
    val hp = projection.currentHp
    val maximum = projection.maximumHp.value
    val health = if (hp != null && maximum != null && maximum > 0 && hp * 4L <= maximum) MaterialTheme.colorScheme.error else RebuildPalette.Cyan
    val expanded = LocalDensity.current.fontScale >= 1.5f
    val healthContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onHp)
            .heightIn(min = 92.dp).padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "HP ${hp ?: "Not set"} / ${maximum ?: "Not set"}" },
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("HIT POINTS", style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
            Text(if (hp == null && maximum == null) "Not set" else "${hp ?: "Not set"} / ${maximum ?: "Not set"}",
                style = MaterialTheme.typography.headlineSmall, color = health, fontWeight = FontWeight.SemiBold)
            Text("Temporary ${character.temporaryHp?.toString() ?: "not set"}", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
            if (hp != null && maximum != null && maximum > 0) LinearProgressIndicator(
                progress = { (hp.toFloat() / maximum).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                color = health, trackColor = RebuildPalette.Outline)
        }
    }
    Column(Modifier.fillMaxWidth().background(RebuildPalette.Panel, RoundedCornerShape(8.dp))) {
        if (expanded) {
            healthContent()
            Row(Modifier.fillMaxWidth()) {
                RebirthVital("Armor Class", projection.armorClass.value?.toString() ?: "Not set", Modifier.weight(1f), onDefense)
                RebirthVital("Initiative", reforgeSigned(projection.initiative.value), Modifier.weight(1f), onInitiative,
                    enabled = projection.initiative.value != null)
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1.65f)) { healthContent() }
            RebirthVital("Armor Class", projection.armorClass.value?.toString() ?: "Not set", Modifier.weight(1f), onDefense)
            RebirthVital("Initiative", reforgeSigned(projection.initiative.value), Modifier.weight(1f), onInitiative,
                enabled = projection.initiative.value != null)
        }
    }
}

@Composable private fun RebirthVital(label: String, value: String, modifier: Modifier, onClick: () -> Unit,
    enabled: Boolean = true) {
    Column(modifier.heightIn(min = 92.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, style = if (value == "Not set") MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            color = RebuildPalette.Silver, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
    }
}

internal fun ReforgeCharacter.visiblePortrait(): String? {
    val source = CharacterCalculator.activeFormSource(this) ?: return portraitRef
    return source.first.formPortraitRefs[source.second.id] ?: portraitRef
}

@Composable internal fun SheetPortraitEditor(character: ReforgeCharacter, dismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit) {
    val context = LocalContext.current
    val original = requireNotNull(rememberReforgeSnapshot(character))
    val source = remember(original) { CharacterCalculator.activeFormSource(original) }
    val initial = if (source == null) original.portraitRef else source.first.formPortraitRefs[source.second.id]
    var candidate by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = rememberSheetPortrait(candidate)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && uri.scheme == "content") { candidate = uri.toString(); error = null }
    }
    PlayerSheet(source?.second?.name?.let { "$it portrait" } ?: "Character portrait", dismiss, dirty = candidate != initial, actions = {
        PlayerButton(enabled = character.revision == original.revision && candidate != initial && (candidate == null || preview != null), onClick = {
            try {
                candidate?.let { context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(it), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val updated = if (source == null) original.copy(portraitRef = candidate) else original.copy(items = original.items.map { item ->
                    if (item.id != source.first.id) item else item.copy(formPortraitRefs = candidate?.let { item.formPortraitRefs + (source.second.id to it) }
                        ?: (item.formPortraitRefs - source.second.id))
                })
                onSave(updated)
            } catch (_: SecurityException) { error = "Read access to the selected image expired. Choose it again." }
        }) { Text("Save image") }
    }) {
        if (preview != null) androidx.compose.foundation.Image(preview, "Selected portrait", Modifier.size(144.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else if (candidate == null) ReforgePortrait(if (source == null) null else original.portraitRef, Modifier.size(144.dp))
        else Text("Waiting for a readable image preview. Images must be 8 MB or smaller.")
        PlayerOutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(if (candidate == null) "Choose image" else "Replace image") }
        if (candidate != null) PlayerTextButton(onClick = { candidate = null }) { Text("Remove image") }
        if (character.revision != original.revision) Text("This character changed. Reopen the portrait to edit the current revision.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

internal val Stat.fullLabel: String get() = when (this) {
    Stat.STR -> "Strength"; Stat.DEX -> "Dexterity"; Stat.CON -> "Constitution"
    Stat.INT -> "Intelligence"; Stat.WIS -> "Wisdom"; Stat.CHA -> "Charisma"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun SheetRollTarget(label: String, value: Int?, onRoll: () -> Unit, onOptions: () -> Unit,
    modifier: Modifier = Modifier, small: Boolean = false, proficient: Boolean? = null, rollValue: Int? = value) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .combinedClickable(enabled = rollValue != null, role = Role.Button, onClickLabel = "Roll $label",
            onLongClickLabel = "Options for $label", onLongClick = onOptions, onClick = onRoll)
        .semantics(mergeDescendants = true) {
            contentDescription = "$label ${reforgeSigned(rollValue)}" + when (proficient) { true -> ", proficient"; false -> ", untrained"; null -> "" }
        }, contentAlignment = Alignment.Center) {
        Row(Modifier.clip(shape).background(RebuildPalette.Panel).border(1.dp, RebuildPalette.Outline, shape)
            .padding(horizontal = if (small) 5.dp else 9.dp, vertical = if (small) 1.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (proficient != null) Text(if (proficient) "●" else "○", color = RebuildPalette.GoldBright, style = MaterialTheme.typography.labelSmall)
            Text(reforgeSigned(value), style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
                color = if (value == null) RebuildPalette.Muted else RebuildPalette.GoldBright)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun SheetDamageTarget(components: List<DamageComponent>, onDamage: () -> Unit, onOptions: () -> Unit) {
    val formula = components.joinToString(" + ") { SecureDice.format(it.formula) }
    Column(Modifier.widthIn(min = 48.dp, max = 124.dp).heightIn(min = 48.dp).combinedClickable(
        enabled = components.isNotEmpty(), role = Role.Button, onClickLabel = "Roll damage", onLongClickLabel = "Damage options",
        onLongClick = onOptions, onClick = onDamage).padding(horizontal = 5.dp), verticalArrangement = Arrangement.Center) {
        Text("Damage", style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
        Text(formula.ifBlank { "—" }, color = RebuildPalette.GoldBright, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable internal fun RebirthAbilities(character: ReforgeCharacter, projection: CharacterProjection,
    onInspect: (Stat) -> Unit, onCheck: (Stat, Boolean) -> Unit, onSave: (Stat, Boolean) -> Unit) {
    val proficientSaves = remember(character) { CharacterCalculator.saveProficiencies(character) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Stat.entries.chunked(3).forEach { stats ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                stats.forEach { stat ->
                    val ability = projection.abilities.getValue(stat)
                    val shape = RoundedCornerShape(8.dp)
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("reforge-${stat.name}").semantics { contentDescription = "${stat.fullLabel} score ${ability.effective.value ?: "unknown"}" }.clip(shape)
                        .border(1.dp, RebuildPalette.Outline, shape).clickable(role = Role.Button,
                            onClickLabel = "Inspect ${stat.fullLabel} and training") { onInspect(stat) }
                        .padding(vertical = 5.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stat.fullLabel.uppercase(), style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, color = RebuildPalette.Silver)
                        SheetRollTarget("${stat.fullLabel} save", projection.saves[stat]?.value,
                            { onSave(stat, true) }, { onSave(stat, false) }, Modifier.testTag("reforge-save-${stat.name}"),
                            small = true, proficient = stat in proficientSaves)
                        SheetRollTarget("${stat.fullLabel} check", ability.modifier, { onCheck(stat, true) }, { onCheck(stat, false) },
                            modifier = Modifier.testTag("reforge-check-${stat.name}"), rollValue = projection.abilityChecks[stat]?.value)
                        Text(ability.effective.value?.toString() ?: "?", modifier = Modifier.border(1.dp, RebuildPalette.Outline, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** A full-width roll target; no disclosure or small modifier-only button is required. */
@Composable internal fun RebirthCheckRow(name: String, detail: String, value: Int?, modifier: Modifier = Modifier,
    onRoll: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = value != null, role = Role.Button,
        onClickLabel = "Roll $name", onClick = onRoll).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        }
        Text(reforgeSigned(value), style = MaterialTheme.typography.titleMedium,
            color = if (value == null) RebuildPalette.Muted else RebuildPalette.GoldBright)
        if (value != null) ArcaneIcon("d20", Modifier.size(20.dp), RebuildPalette.Gold)
    }
    HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .45f))
}

@Composable internal fun RebirthItemRow(item: ItemInstance, definition: ItemDefinition?, busy: Boolean,
    onInspect: () -> Unit, onEquip: () -> Unit, onManage: () -> Unit,
    onFavorite: ((Boolean) -> Unit)? = null) {
    val equipped = item.state in setOf(ItemState.WORN, ItemState.HELD)
    val stack = !equipped && item.quantity > 1
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(if (equipped) RebuildPalette.Gold.copy(alpha = .1f) else RebuildPalette.Panel,
                RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                ArcaneIcon(when {
                    definition?.equipmentSlot?.contains("weapon", true) == true -> "Weapon"
                    definition?.equipmentSlot?.contains("armor", true) == true -> "Armor"
                    else -> "Bag"
                },
                    tint = if (equipped) RebuildPalette.GoldBright else RebuildPalette.Muted)
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Inspect item", onClick = onInspect),
                verticalArrangement = Arrangement.Center) {
                Text(definition?.name ?: "Item", style = MaterialTheme.typography.titleMedium)
                Text("${item.state.name.lowercase().replaceFirstChar { it.uppercase() }}${if (item.attuned) " · Attuned" else ""} · Quantity ${item.quantity}",
                    color = if (equipped) RebuildPalette.Cyan else RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            PlayerTextButton(enabled = !busy && (equipped || item.quantity > 0),
                onClick = if (stack) onManage else onEquip) {
                Text(if (equipped) "Unequip" else if (stack) "Split stack" else "Equip")
            }
            if (onFavorite != null) IconToggleButton(checked = item.favorite, onCheckedChange = onFavorite,
                enabled = !busy, modifier = Modifier.size(48.dp).testTag("favorite-item-${item.id}").semantics {
                    contentDescription = if (item.favorite) "Remove ${definition?.name ?: "item"} from favorites"
                        else "Add ${definition?.name ?: "item"} to favorites"
                    stateDescription = if (item.favorite) "Favorite" else "Not favorite"
                }) {
                Text(if (item.favorite) "★" else "☆", modifier = Modifier.clearAndSetSemantics { },
                    color = if (item.favorite) RebuildPalette.GoldBright else RebuildPalette.Muted,
                    style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (stack || (!equipped && item.quantity == 0)) Text(
            if (stack) "Split off one item before equipping." else "No items available to equip.",
            color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp))
        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .55f))
    }
}

@Composable internal fun RebirthSpellRow(spell: SpellDefinition, onInspect: () -> Unit, onCast: () -> Unit,
    actionLabel: String = "Cast") {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button,
                onClickLabel = "Read ${spell.name}", onClick = onInspect), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spell.name, style = MaterialTheme.typography.titleMedium)
                val traits = buildList {
                    add(when (spell.preparation) {
                        SpellPreparation.KNOWN -> "Known"
                        SpellPreparation.PREPARED -> "Prepared"
                        SpellPreparation.ALWAYS_PREPARED -> "Always prepared"
                    })
                    if (spell.concentration) add("Concentration")
                    if (spell.ritual) add("Ritual")
                }
                Text(traits.joinToString(" · "), color = RebuildPalette.Purple, style = MaterialTheme.typography.bodySmall)
                val details = listOf(spell.castingTime, spell.range, spell.duration).filter(String::isNotBlank)
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = RebuildPalette.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            PlayerOutlinedButton(onClick = onCast) { Text(actionLabel) }
        }
        HorizontalDivider(color = RebuildPalette.Outline.copy(alpha = .55f))
    }
}

@Composable internal fun RebirthSectionLabel(title: String, onTitle: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).then(if (onTitle == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onTitle)
            .heightIn(min = 48.dp).padding(vertical = 8.dp)).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}
