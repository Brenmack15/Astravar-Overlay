package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.Profile
import kotlin.math.roundToInt

/** Preview while dragging; persist one integer when the gesture or accessibility action finishes. */
@Composable fun BaseDamageUpgrade(profile: Profile,enabled: Boolean=true,onCountSelected: (Int)->Unit) {
    var count by remember(profile.baseDamageDiceCount) { mutableIntStateOf(profile.baseDamageDiceCount) }
    val preview=profile.copy(baseDamageDiceCount=count)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("BASE WEAPON DAMAGE",style=MaterialTheme.typography.labelLarge)
        Text("${count}d10",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary)
        Slider(
            value=count.toFloat(),
            onValueChange={count=it.roundToInt().coerceIn(1,10)},
            onValueChangeFinished={if(count!=profile.baseDamageDiceCount) onCountSelected(count)},
            valueRange=1f..10f,steps=8,enabled=enabled,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {
                contentDescription="Base weapon damage"
                stateDescription="${count}d10"
            }
        )
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("1d10",style=MaterialTheme.typography.labelMedium)
            Text("10d10",style=MaterialTheme.typography.labelMedium)
        }
        Text("Base Damage: ${preview.base} Force")
        Text("Discharge: ${preview.dischargeDice}d10 Force")
        Text("Shatter: ${preview.shatterDice}d10 Force")
        Text("Each additional weapon d10 adds 2d10 to Discharge and 6d10 to Shatter. Elemental, spell payload, and overcharge dice keep their own values.",style=MaterialTheme.typography.bodySmall)
    }
}
