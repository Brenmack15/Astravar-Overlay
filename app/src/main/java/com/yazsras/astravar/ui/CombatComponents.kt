package com.yazsras.astravar.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.net.Uri
import android.graphics.BitmapFactory
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
    image?.let {Image(it,"Personal Astravar artwork",modifier,contentScale=ContentScale.Crop)} ?: Image(painterResource(R.drawable.astravar_reference),"Astravar crystal pistol",modifier,contentScale=ContentScale.Crop)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CorePicker(s: Campaign,a: Actions,dismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Choose a core",style=MaterialTheme.typography.titleLarge,color=Brass)
            if(s.cores.isEmpty()) Text("Add the completed cores you own in Inventory.")
            s.cores.forEach {c->
                val active=c.id==s.installedId
                OutlinedButton(onClick={a.change("Install ${c.name}") {Engine.install(it,c.id)};dismiss()},enabled=!a.busy && !active && c.usable(s.gameSeconds) && s.installation==null,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                    Column(Modifier.fillMaxWidth()) {Text("${c.name}${if(active) " · ACTIVE" else ""}");Text("${c.readiness(s.gameSeconds)} · ${c.payloads.size} stored · ${if(s.postShatter) s.profile.replacementCost else s.profile.swapCost}",style=MaterialTheme.typography.bodySmall)}
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
            Text("Stored spells",style=MaterialTheme.typography.titleLarge,color=Brass)
            Text("Choose up to ${s.profile.releaseLimit} · ${s.installed?.payloads?.size ?: 0} stored")
            if(s.installed?.payloads.isNullOrEmpty()) Text("Charge a spell in Inventory → Cores.")
            s.installed?.payloads?.forEach {p->
                OutlinedButton(onClick={a.run("Payload selected") {preferences.payloads(if(p.id in selected) selected-p.id else if(s.profile.releaseLimit==1) setOf(p.id) else (selected+p.id).toList().takeLast(s.profile.releaseLimit).toSet());preferences.mode(Ability.SPELL);if(s.profile.releaseLimit==1) dismiss()}},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    Text("${if(p.id in selected) "✓ Selected" else "Stored"} · ${p.template.name} · L${p.template.castingLevel}")
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
        Text("Recent rolls",style=MaterialTheme.typography.headlineSmall,color=Brass)
        Text("Latest 20 · complete history remains in your backup",style=MaterialTheme.typography.bodySmall)
        if(s.pending.isEmpty()) Section("Your first roll will appear here") {Text("Open Combat and tap Roll attack.")}
        s.recentRolls().forEach {shot->
            val roll=shot.rolled
            val time=roll?.rolledAtMillis?.takeIf {it>0}?.let {SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(it))} ?: "Earlier"
            Card(Modifier.fillMaxWidth().clickable {selected=if(selected==shot.plan.id) null else shot.plan.id}) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("$time · ${shot.plan.ability.title}",color=Brass)
                    Text(roll?.attackTotal?.let {"$it to hit · ${roll.mode.name.lowercase()}"} ?: if(shot.plan.blast) "DC ${shot.plan.profile.saveDc} Dexterity" else "External dice",style=MaterialTheme.typography.titleMedium)
                    roll?.natural?.let {Text(if(it==20) "CRITICAL HIT · natural 20" else if(it==1) "NATURAL 1 — MISS" else "Natural $it",style=MaterialTheme.typography.labelMedium,color=Frost)}
                    Text(shot.result?.let {if(shot.plan.blast) "${it.targets.size} creatures" else Engine.damage(shot.plan,it).damageText()} ?: "Saves / result pending")
                    Text("${roll?.coreName?.ifBlank {shot.plan.coreId} ?: shot.plan.coreId} · ${shot.plan.selectedPayloads.joinToString {it.template.name}.ifBlank {shot.plan.immediate?.name ?: "No payload"}}",style=MaterialTheme.typography.bodySmall)
                    if(selected==shot.plan.id) Text(shotSummary(shot),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
