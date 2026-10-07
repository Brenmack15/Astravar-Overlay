package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.*
import kotlin.math.roundToInt

/** Two independent drafts; each gesture commits only its own whole-number profile field. */
@Composable fun WeaponDamageUpgrades(
    profile: Profile,enabled: Boolean=true,elements: List<DamageType> = emptyList(),
    onBaseCountSelected: (Int)->Unit,onSelectorCountSelected: (Int)->Unit
) {
    var baseCount by remember(profile.baseDamageDiceCount) { mutableIntStateOf(profile.baseDamageDiceCount) }
    var selectorCount by remember(profile.elementalSelectorDiceCount) { mutableIntStateOf(profile.elementalSelectorDiceCount) }
    val preview=profile.copy(baseDamageDiceCount=baseCount,elementalSelectorDiceCount=selectorCount)
    fun expression(ability: Ability)=Engine.previewComponents(preview,ability,elements).joinToString(" + ") {
        "${it.damage.dice} ${it.damage.type.name.lowercase().replaceFirstChar(Char::titlecase)}"
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        DamageCountSlider("PRIMARY FORCE DAMAGE","Primary Force damage",preview.base.copy(flat=0).toString(),preview.primaryForceDieSides,baseCount,enabled,
            onChange={baseCount=it},onFinished={if(baseCount!=profile.baseDamageDiceCount) onBaseCountSelected(baseCount)})
        Text("Primary Force: ${preview.base} Force")
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        DamageCountSlider("ELEMENTAL SELECTOR DAMAGE","Elemental selector damage",preview.selector.toString(),8,selectorCount,enabled,
            onChange={selectorCount=it},onFinished={if(selectorCount!=profile.elementalSelectorDiceCount) onSelectorCountSelected(selectorCount)})
        Text(if(elements.isEmpty()) "NEUTRAL SHOT" else "ELEMENTAL SHOT",style=MaterialTheme.typography.labelLarge)
        Text(expression(Ability.ELEMENTAL),style=MaterialTheme.typography.titleMedium)
        Text("Discharge: ${expression(Ability.DISCHARGE)}")
        Text("Shatter: ${expression(Ability.SHATTER)}")
        Text("Discharge = 3× Primary Force dice. Shatter = 7× Discharge dice. No flat weapon modifier on explosions.",style=MaterialTheme.typography.bodySmall)
        Text("Each active element adds ${preview.selector} once to an elemental shot or explosion; explosion selector dice are never multiplied or critical. Neutral has no selector. Spell shots replace free selector dice.",style=MaterialTheme.typography.bodySmall)
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
