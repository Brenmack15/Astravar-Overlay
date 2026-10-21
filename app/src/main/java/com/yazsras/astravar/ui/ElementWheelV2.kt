package com.yazsras.astravar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.rules.*
import kotlin.math.cos
import kotlin.math.sin

/** Shared native/overlay selection policy; combinations remain constrained by the engine. */
fun elementSelectionAfterChoice(profile: Profile, current: List<DamageType>, chosen: DamageType): List<DamageType> {
    if (chosen == DamageType.FORCE) return emptyList()
    require(chosen in Engine.elements(profile)) { "Choose an unlocked element" }
    if (profile.elementCapacity == 1) return listOf(chosen)
    // Accepted Dual Resonance combines basic elements only. An unlocked advanced
    // element is still usable by itself; selecting it replaces the pair, and
    // choosing a basic element afterwards starts a new basic selection.
    if (profile.elementCapacityOverride == null && (chosen !in Engine.basic || current.any { it !in Engine.basic }))
        return listOf(chosen)
    return if (chosen in current) current - chosen else (current + chosen).takeLast(profile.elementCapacity)
}

/** The approved clockwise order surrounds neutral Force; availability remains engine-owned. */
@Composable
fun ElementWheelV2(profile: Profile, selected: List<DamageType>, close: () -> Unit, choose: (DamageType) -> Unit) {
    val ring = listOf(DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.ACID,
        DamageType.POISON, DamageType.THUNDER, DamageType.NECROTIC, DamageType.PSYCHIC, DamageType.RADIANT)
    val allowed = Engine.elements(profile)
    var showUnlocks by rememberSaveable { mutableStateOf(false) }
    PlayerSheet("Choose an element", close) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(.9f).testTag("element-wheel")) {
            val centerX = maxWidth / 2
            val centerY = maxHeight / 2
            val nodeWidth = maxWidth * .24f
            val nodeHeight = maxHeight * .25f
            val radiusX = maxWidth * .36f
            val radiusY = maxHeight * .36f
            // Center the dial on the 48dp particle, whose center is 2dp padding
            // + 24dp from its node's top. Labels must not move the visual center.
            ArcaneDial(Modifier.fillMaxSize().offset(y = 26.dp - nodeHeight / 2), RebuildPalette.BlueAccent.copy(alpha = .65f))
            ring.forEachIndexed { index, type ->
                val angle = Math.toRadians(-90.0 + index * 40.0)
                val enabled = type in allowed
                val active = type in selected
                ElementWheelNode(type, enabled, active,
                    Modifier.offset(x = centerX + radiusX * cos(angle).toFloat() - nodeWidth / 2,
                        y = centerY + radiusY * sin(angle).toFloat() - nodeHeight / 2)
                        .width(nodeWidth).heightIn(min = nodeHeight)) {
                    if (!active) choose(type)
                    close()
                }
            }
            ElementWheelNode(DamageType.FORCE, true, selected.isEmpty(),
                Modifier.offset(centerX - nodeWidth / 2, centerY - nodeHeight / 2)
                    .width(nodeWidth).heightIn(min = nodeHeight)) {
                if (selected.isNotEmpty()) choose(DamageType.FORCE)
                close()
            }
        }
        if (profile.elementCapacity > 1 && profile.elementCapacityOverride == null) Text("Dual Resonance combines two basic elements. An unlocked advanced element replaces that pair and is used alone.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
        TextButton(onClick = { showUnlocks = !showUnlocks }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Radiant, Psychic & Necrotic unlocks")
        }
        if (showUnlocks) Text("These elements require Artificer level 8 or higher and the confirmed Advanced Elements upgrade in Rules & profile. Configure each damage formula there. An unconfirmed capacity override does not grant an upgrade.",
            style = MaterialTheme.typography.bodyMedium, color = RebuildPalette.Ice)
    }
}

@Composable
private fun ElementWheelNode(type: DamageType, enabled: Boolean, active: Boolean, modifier: Modifier,
    onClick: () -> Unit) {
    val name = type.name.lowercase().replaceFirstChar(Char::titlecase)
    Column(modifier.semantics(mergeDescendants = true) {
        selected = active
        contentDescription = if (type == DamageType.FORCE) "Neutral Force" else "$name ${if (enabled) "element" else "locked element"}"
    }.clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = RebuildPalette.Void,
                border = BorderStroke(if (active) 1.8.dp else .7.dp,
                    if (active) RebuildPalette.Cyan else RebuildPalette.Gold.copy(alpha = if (enabled) .6f else .2f))) {
                ElementParticle(type, Modifier.size(48.dp).clip(CircleShape).alpha(if (enabled) 1f else .35f).padding(2.dp))
            }
            if (!enabled) Text("×", color = RebuildPalette.Muted, fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Text(name, color = if (active) RebuildPalette.Ice else if (enabled) MaterialTheme.colorScheme.onSurface else RebuildPalette.Muted,
            fontFamily = InterfaceFace, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
