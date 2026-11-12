package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

@Composable internal fun RebirthAbilities(projection: CharacterProjection, onInspect: (Stat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat.entries.chunked(if (LocalDensity.current.fontScale >= 1.5f) 2 else 3).forEach { stats ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.forEach { stat ->
                    val ability = projection.abilities.getValue(stat)
                    Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 80.dp).testTag("reforge-${stat.name}")
                        .clip(RoundedCornerShape(8.dp)).border(1.dp, RebuildPalette.Outline, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onInspect(stat) }.padding(vertical = 10.dp, horizontal = 8.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (ability.effective.value == null) "${stat.name} Not set"
                                else "${stat.name} ${ability.effective.value} ${reforgeSigned(ability.modifier)}"
                        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                        Text(stat.name, style = MaterialTheme.typography.labelMedium, color = RebuildPalette.Muted)
                        Text(reforgeSigned(ability.modifier), color = RebuildPalette.GoldBright,
                            style = if (ability.modifier == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                        ability.effective.value?.let { score ->
                            Text("Score $score", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Silver)
                        }
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
    onInspect: () -> Unit, onEquip: () -> Unit, onManage: () -> Unit) {
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

@Composable internal fun RebirthSectionLabel(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}
