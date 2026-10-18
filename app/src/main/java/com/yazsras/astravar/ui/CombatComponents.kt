package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.data.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun rollFeedback(view: View,roll: ShotRoll?) {
    val feedback=if(Build.VERSION.SDK_INT>=30) {if(roll?.natural==1) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM} else HapticFeedbackConstants.VIRTUAL_KEY
    view.performHapticFeedback(feedback)
}
@Composable fun Stat(label: String,value: String) {
    Column {Text(label,style=MaterialTheme.typography.labelSmall,color=Frost);Text(value,style=MaterialTheme.typography.headlineSmall,color=Brass)}
}
@Composable fun WeaponArtwork(uri: String,modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val image by produceState<ImageBitmap?>(null,uri) {
        value=if(uri.isBlank()) null else withContext(Dispatchers.IO) {runCatching {
            val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {BitmapFactory.decodeStream(it,null,options)}
            require(options.outWidth in 1..20000 && options.outHeight in 1..20000)
            options.inJustDecodeBounds=false;options.inSampleSize=maxOf(1,maxOf(options.outWidth,options.outHeight)/1600)
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {BitmapFactory.decodeStream(it,null,options)?.asImageBitmap()}
        }.getOrNull()}
    }
    image?.let {Image(it,"Personal Astravar artwork",modifier,contentScale=ContentScale.Fit)} ?: Image(painterResource(R.drawable.astravar_cutout),"Astravar crystal pistol",modifier,contentScale=ContentScale.Fit)
}
@Composable fun CorePicker(s: Campaign, a: Actions, dismiss: () -> Unit) {
    PlayerSheet("Permanent internal nexus", dismiss) {
        Text("Astravar has one permanent internal core. There is no installation, replacement or swap action.", color = Frost)
        s.installed?.let { core ->
            Text(core.name, style = MaterialTheme.typography.titleLarge, color = Brass)
            Text(core.readiness(s.gameSeconds))
            Text("${core.payloads.size}/${s.profile.capacity} stored payloads", color = Muted)
        }
        Text("Charge or overwrite approved spell payloads in Config → Internal spell reservoir. Recovery advances only when elapsed in-world time is recorded.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PayloadPicker(s: Campaign,a: Actions,preferences: Preferences,selected: Set<String>,dismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SectionCaption("STORED SPELLS", "Choose up to ${s.profile.releaseLimit}")
            if(s.installed?.payloads.isNullOrEmpty()) Text("Charge a spell in Config → Internal spell reservoir.")
            s.installed?.payloads?.forEach {p->
                val active=p.id in selected
                val tint=elementTint(p.template.overchargeType)
                ArcanePanel(accent=if(active) Brass else tint,modifier=Modifier.clickable {a.run("Payload selected") {preferences.payloads(if(active) selected-p.id else if(s.profile.releaseLimit==1) setOf(p.id) else (selected+p.id).toList().takeLast(s.profile.releaseLimit).toSet());preferences.mode(Ability.SPELL);if(s.profile.releaseLimit==1) dismiss()}}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        InstrumentMedallion("Payload",Modifier.size(50.dp),tint)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(p.template.name,style=MaterialTheme.typography.titleLarge)
                            Text("L${p.template.castingLevel} · ${p.template.damage.joinToString(" + ") {"${it.dice} ${it.type.name.lowercase()}"}}",style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                        Text(if(active) "✓ SELECTED" else "STORED",style=MaterialTheme.typography.labelSmall,color=if(active) Brass else tint)
                    }
                }
            }
            PlayerTextButton(onClick={a.run("Payload selection cleared") {preferences.payloads(emptySet());preferences.mode(Ability.ARCANE)};dismiss()}) {Text("No payload · arcane shot")}
            Action("Done",onClick=dismiss)
            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable fun CombatHistoryPage(s: Campaign) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val shots = s.recentRolls()
    Page {
        PlayerHeading("Recent rolls") {
            Text("${shots.size} / ${HistoryRetention.MAX_HISTORY_ENTRIES}", color = RebuildPalette.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (shots.isEmpty()) PlayerEmpty("No rolls yet", "Roll an attack in Combat to start your history.", "History")
        shots.forEach { shot ->
            val roll = shot.rolled
            val time = roll?.rolledAtMillis?.takeIf { it > 0 }?.let {
                SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(it))
            } ?: "Earlier"
            val tint = if (roll?.natural == 1) MaterialTheme.colorScheme.error else if (roll?.critical == true) RebuildPalette.Gold else RebuildPalette.Ice
            val damage = historyDamage(shot)
            PlayerCard(onClick = { selected = shot.plan.id }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$time · ${shot.plan.ability.title}", style = MaterialTheme.typography.labelLarge,
                            color = RebuildPalette.Muted)
                        when (roll?.natural) {
                            20 -> Text("Critical · natural 20", style = MaterialTheme.typography.labelLarge, color = RebuildPalette.Gold)
                            1 -> Text("Natural 1 · miss", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(roll?.attackTotal?.toString() ?: if (shot.plan.blast) shot.plan.profile.saveDc.toString() else "—",
                            style = MaterialTheme.typography.headlineSmall, color = tint)
                        Text(if (roll?.attackTotal != null) "TO HIT" else if (shot.plan.blast) "SAVE DC" else "ATTACK",
                            style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
                    }
                    Text("›", color = RebuildPalette.Muted, style = MaterialTheme.typography.titleLarge)
                }
                Text(roll?.takeIf { it.attackTotal != null }?.let { it.attackDescription(shot.plan) }
                    ?: if (shot.plan.blast) "Dexterity save${if (shot.result == null) " · awaiting saves" else ""}" else "External dice",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                if (damage.isNotEmpty()) Text(damage.entries.joinToString(" · ") { (type, total) ->
                    "${type.name.lowercase().replaceFirstChar(Char::titlecase)} $total"
                }, style = MaterialTheme.typography.bodyMedium, color = RebuildPalette.Ice)
                Text("${roll?.coreName?.ifBlank { shot.plan.coreId } ?: shot.plan.coreId} · ${shot.plan.selectedPayloads.joinToString { it.template.name }.ifBlank { shot.plan.immediate?.name ?: "No payload" }}",
                    style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
            }
        }
    }
    val detail = shots.firstOrNull { it.plan.id == selected }
    if (detail != null) PlayerSheet("Roll details", { selected = null }, actions = {
        PlayerTextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Astravar result", shotSummary(detail)))
        }) { Text("Copy full result") }
    }) {
        SelectionContainer { Text(shotSummary(detail), style = MaterialTheme.typography.bodyMedium) }
    }
}
private fun historyDamage(shot: PendingShot): Map<DamageType,Int> {
    if(!shot.plan.blast) shot.result?.let {return Engine.damage(shot.plan,it)}
    val components=Engine.components(shot.plan,shot.rolled?.critical ?: (shot.result?.outcome==Outcome.CRITICAL))
    return components.mapNotNull {component->
        val total=shot.rolled?.damage?.get(component.id)?.total ?: shot.result?.rolls?.get(component.id)
        total?.let {component.damage.type to it}
    }.groupBy({it.first},{it.second}).mapValues {it.value.sum()}
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HistoryDamageChips(damage: Map<DamageType,Int>,modifier: Modifier=Modifier) {
    FlowRow(modifier,horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        damage.forEach {(type,total)->
            val tint=elementTint(type)
            Surface(color=tint.copy(alpha=.06f),shape=RoundedCornerShape(3.dp),border=BorderStroke(.5.dp,tint.copy(alpha=.4f))) {
                Row(Modifier.padding(horizontal=6.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ArcaneIcon(type.name,Modifier.size(12.dp),tint)
                    Text("${type.name.lowercase().replaceFirstChar(Char::titlecase)} $total",style=MaterialTheme.typography.labelMedium,color=tint)
                }
            }
        }
    }
}
