package com.yazsras.astravar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.rules.*
import kotlin.math.cos
import kotlin.math.sin

/** Nine approved source particles surround neutral Force. Locks use the existing engine. */
@Composable fun ElementWheelV2(profile: Profile, selected: List<DamageType>, close: () -> Unit, choose: (DamageType) -> Unit) {
    val ring = listOf(DamageType.FIRE, DamageType.LIGHTNING, DamageType.THUNDER, DamageType.POISON,
        DamageType.ACID, DamageType.COLD, DamageType.NECROTIC, DamageType.PSYCHIC, DamageType.RADIANT)
    val allowed = if(profile.elementCapacity > 1 && profile.elementCapacityOverride == null) Engine.basic else Engine.elements(profile)
    AlertDialog(onDismissRequest = close, title = { Text("Choose an element") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val center = maxWidth / 2
                    val diameter = maxWidth * .22f
                    val radius = maxWidth * .36f
                    ArcaneDial(Modifier.fillMaxSize(), Frost)
                    ring.forEachIndexed { i, type ->
                        val angle = Math.toRadians(-90.0 + i * 40.0)
                        val enabled = type in allowed
                        Column(Modifier.offset(x = center + radius * cos(angle).toFloat() - diameter / 2,
                            y = center + radius * sin(angle).toFloat() - diameter / 2).size(diameter)
                            .semantics { this.selected = type in selected; contentDescription = "${type.name.lowercase()} ${if(enabled) "element" else "locked element"}" }
                            .clickable(enabled = enabled, role = Role.RadioButton) { choose(type) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            ElementParticle(type, Modifier.size(diameter * .58f))
                            Text(type.name.lowercase().replaceFirstChar(Char::titlecase), color = if(enabled) Frost else Muted, fontSize = 9.sp)
                            if(!enabled) Text("Locked", color = Muted, fontSize = 8.sp)
                        }
                    }
                    Column(Modifier.offset(center - diameter / 2, center - diameter / 2).size(diameter)
                        .semantics { this.selected = selected.isEmpty(); contentDescription = "Neutral Force" }
                        .clickable(role = Role.RadioButton) { choose(DamageType.FORCE) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        ElementParticle(DamageType.FORCE, Modifier.size(diameter * .65f))
                        Text("Force", color = Brass, fontSize = 11.sp)
                    }
                }
                Text("Uses your saved weapon rules and unlocks. The proposed extra neutral Force dice are not active.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
