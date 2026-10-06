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

/** Two independent drafts; each gesture commits only its own whole-number profile field. */
@Composable fun WeaponDamageUpgrades(
    profile: Profile,enabled: Boolean=true,
    onBaseCountSelected: (Int)->Unit,onSelectorCountSelected: (Int)->Unit
) {
    var baseCount by remember(profile.baseDamageDiceCount) { mutableIntStateOf(profile.baseDamageDiceCount) }
    var selectorCount by remember(profile.elementalSelectorDiceCount) { mutableIntStateOf(profile.elementalSelectorDiceCount) }
    val preview=profile.copy(baseDamageDiceCount=baseCount,elementalSelectorDiceCount=selectorCount)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        DamageCountSlider("BASE WEAPON DAMAGE","Base weapon damage",preview.base.copy(flat=0).toString(),10,baseCount,enabled,
            onChange={baseCount=it},onFinished={if(baseCount!=profile.baseDamageDiceCount) onBaseCountSelected(baseCount)})
        Text("Base Damage: ${preview.base} Force")
        Text("Discharge: ${preview.dischargeDice}d10 Force")
        Text("Shatter: ${preview.shatterDice}d10 Force")
        Text("Each additional weapon d10 adds 2d10 to Discharge and 6d10 to Shatter.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        DamageCountSlider("ELEMENTAL SELECTOR DAMAGE","Elemental selector damage",preview.selector.toString(),8,selectorCount,enabled,
            onChange={selectorCount=it},onFinished={if(selectorCount!=profile.elementalSelectorDiceCount) onSelectorCountSelected(selectorCount)})
        Text("ELEMENTAL SHOT",style=MaterialTheme.typography.labelLarge)
        Text("${preview.base} Force + ${preview.selector} selected element",style=MaterialTheme.typography.titleMedium)
        Text("Dual Resonance uses ${preview.selector} for each selected element when unlocked. Neutral Force, spell shots, Direct Impact, and explosions receive no free selector dice.",style=MaterialTheme.typography.bodySmall)
    }
}

/** Preview while dragging; persist one integer when the gesture or accessibility action finishes. */
@Composable private fun DamageCountSlider(
    title: String,description: String,expression: String,sides: Int,count: Int,enabled: Boolean,
    onChange: (Int)->Unit,onFinished: ()->Unit
) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.labelLarge)
        Text(expression,style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary)
        Slider(
            value=count.toFloat(),
            onValueChange={if(it.isFinite()) onChange(it.roundToInt().coerceIn(1,10))},
            onValueChangeFinished=onFinished,
            valueRange=1f..10f,steps=8,enabled=enabled,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {
                contentDescription=description
                stateDescription=expression
            }
        )
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("1d$sides",style=MaterialTheme.typography.labelMedium)
            Text("10d$sides",style=MaterialTheme.typography.labelMedium)
        }
    }
}
