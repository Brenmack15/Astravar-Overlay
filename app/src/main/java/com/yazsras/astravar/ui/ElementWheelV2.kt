package com.yazsras.astravar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

/** The approved clockwise order surrounds neutral Force; availability remains engine-owned. */
@Composable
fun ElementWheelV2(profile: Profile, selected: List<DamageType>, close: () -> Unit, choose: (DamageType) -> Unit) {
    val ring = listOf(DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.ACID,
        DamageType.POISON, DamageType.THUNDER, DamageType.NECROTIC, DamageType.PSYCHIC, DamageType.RADIANT)
    val allowed = if (profile.elementCapacity > 1 && profile.elementCapacityOverride == null) Engine.basic else Engine.elements(profile)
    PlayerSheet("Choose an element", close) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(.9f).testTag("element-wheel")) {
            val centerX = maxWidth / 2
            val centerY = maxHeight / 2
            val nodeWidth = maxWidth * .24f
            val nodeHeight = maxHeight * .25f
            val radiusX = maxWidth * .36f
            val radiusY = maxHeight * .36f
            ArcaneDial(Modifier.fillMaxSize(), RebuildPalette.BlueAccent.copy(alpha = .65f))
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
