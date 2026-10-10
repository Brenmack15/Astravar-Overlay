package com.yazsras.astravar.ui

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CorePicker(s: Campaign,a: Actions,dismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionCaption("CHOOSE A CORE", "${s.cores.count {it.usable(s.gameSeconds)}} ready")
            if(s.cores.isEmpty()) Text("Add the completed cores you own in Cores & Payloads.")
            s.cores.forEach {c->
                val active=c.id==s.installedId
                val ready=c.usable(s.gameSeconds)
                ArcanePanel(accent=if(active) Brass else Frost,modifier=Modifier.clickable(enabled=!a.busy && !active && ready && s.installation==null) {a.change("Install ${c.name}") {Engine.install(it,c.id)};dismiss()}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        InstrumentMedallion("Core",Modifier.size(50.dp),if(ready) Frost else Muted)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text("${c.name}${if(active) " · ACTIVE" else ""}",style=MaterialTheme.typography.titleLarge)
                            Text("${c.readiness(s.gameSeconds)} · ${c.payloads.size}/${s.profile.capacity} stored",style=MaterialTheme.typography.bodySmall,color=Muted)
                            if(!active) Text("Install · ${if(s.postShatter) s.profile.replacementCost else s.profile.swapCost}",style=MaterialTheme.typography.labelMedium,color=Brass)
                        }
                        StatusPill(if(ready) "READY" else "UNAVAILABLE",ready)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PayloadPicker(s: Campaign,a: Actions,preferences: Preferences,selected: Set<String>,dismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SectionCaption("STORED SPELLS", "Choose up to ${s.profile.releaseLimit}")
            if(s.installed?.payloads.isNullOrEmpty()) Text("Charge a spell in Cores & Payloads → Cores.")
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
            TextButton(onClick={a.run("Payload selection cleared") {preferences.payloads(emptySet());preferences.mode(Ability.ARCANE)};dismiss()}) {Text("No payload · arcane shot")}
            Action("Done",onClick=dismiss)
            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable fun CombatHistoryPage(s: Campaign) {
    var selected by remember {mutableStateOf<String?>(null)}
    Page {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            InstrumentMedallion("History",Modifier.size(54.dp),Brass)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("ASTRAVAR ARCHIVE",style=MaterialTheme.typography.labelSmall,color=Brass)
                Text("Recent rolls",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurface)
                Text("Newest first · ${s.pending.size.coerceAtMost(HistoryRetention.MAX_HISTORY_ENTRIES)} retained",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
        SectionCaption("COMBAT HISTORY", "Latest ${HistoryRetention.MAX_HISTORY_ENTRIES}")
        if(s.pending.isEmpty()) ArcanePanel(accent=Frost) {
            InstrumentMedallion("History",Modifier.size(64.dp),Frost)
            Text("Your first roll will appear here",style=MaterialTheme.typography.titleLarge)
            Text("Open Weapon and tap Roll attack.",style=MaterialTheme.typography.bodyMedium,color=Muted)
        }
        s.recentRolls().forEach {shot->
            val roll=shot.rolled
            val time=roll?.rolledAtMillis?.takeIf {it>0}?.let {SimpleDateFormat("MMM d · HH:mm",Locale.getDefault()).format(Date(it))} ?: "Earlier"
            val tint=if(roll?.natural==1) MaterialTheme.colorScheme.error else if(roll?.critical==true) Brass else Frost
            val damage=historyDamage(shot)
            val expanded=selected==shot.plan.id
            ArcanePanel(accent=tint,modifier=Modifier.clickable {selected=if(expanded) null else shot.plan.id}) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        InstrumentMedallion(if(shot.plan.blast) "Payload" else "Weapon",Modifier.size(40.dp),tint)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text("$time · ${shot.plan.ability.title}",style=MaterialTheme.typography.labelMedium,color=Brass)
                            when(roll?.natural) {
                                20 -> Text("CRITICAL HIT · natural 20",style=MaterialTheme.typography.labelSmall,color=Brass)
                                1 -> Text("NATURAL 1 — MISS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                            }
                        }
                        Column(horizontalAlignment=Alignment.End) {
                            Text(roll?.attackTotal?.toString() ?: if(shot.plan.blast) shot.plan.profile.saveDc.toString() else "—",style=MaterialTheme.typography.headlineMedium,fontSize=30.sp,lineHeight=34.sp,color=tint)
                            Text(if(roll?.attackTotal!=null) "TO HIT" else if(shot.plan.blast) "SAVE DC" else "ATTACK",style=MaterialTheme.typography.labelSmall,color=Muted)
                        }
                    }
                    Text(roll?.takeIf {it.attackTotal!=null}?.let {it.attackDescription(shot.plan)+if(it.mode==RollMode.NORMAL) " · normal" else ""} ?: if(shot.plan.blast) "Dexterity save · ${shot.result?.targets?.size ?: 0} results${if(shot.result==null) " · awaiting saves" else ""}" else "External dice",style=MaterialTheme.typography.bodySmall,color=Muted)
                    HorizontalDivider(color=tint.copy(alpha=.24f))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        HistoryDamageChips(damage,Modifier.weight(1f))
                        Column(horizontalAlignment=Alignment.End) {
                            Text(damage.values.sum().toString(),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onSurface)
                            Text(if(shot.plan.blast) "ROLLED" else if(shot.result?.outcome==Outcome.MISS) "DAMAGE" else "POTENTIAL",style=MaterialTheme.typography.labelSmall,color=Muted)
                        }
                    }
                    Text("${roll?.coreName?.ifBlank {shot.plan.coreId} ?: shot.plan.coreId} · ${shot.plan.selectedPayloads.joinToString {it.template.name}.ifBlank {shot.plan.immediate?.name ?: "No payload"}}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    Text(if(expanded) "Hide details ▴" else "View dice & details ▾",style=MaterialTheme.typography.labelSmall,color=Frost)
                    if(expanded) {
                        HorizontalDivider(color=tint.copy(alpha=.24f))
                        Text(shotSummary(shot),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }

        }
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
