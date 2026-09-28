package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.Repository
import com.yazsras.astravar.rules.Campaign
import com.yazsras.astravar.rules.DamageType
import kotlinx.coroutines.*

val Gunmetal=Color(0xFF111419)
val Brass=Color(0xFFC5A56D)
val Frost=Color(0xFFB4EBFF)
fun elementTint(type: DamageType)=when(type) {
    DamageType.FIRE -> Color(0xFFFFA36A)
    DamageType.LIGHTNING -> Color(0xFFDAC0FF)
    DamageType.POISON,DamageType.ACID -> Color(0xFFA8E597)
    DamageType.RADIANT -> Color(0xFFFFDF93)
    DamageType.NECROTIC -> Color(0xFFD2B5EC)
    DamageType.PSYCHIC -> Color(0xFFFFB4DD)
    DamageType.THUNDER -> Color(0xFFC7CAFF)
    else -> Frost
}
@Composable fun AstravarTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Brass,secondary=Frost,background=Gunmetal,surface=Color(0xFF1B2028),error=Color(0xFFFF9C9E)),content=content)
}
class Actions(val repo: Repository,private val scope: CoroutineScope) {
    var message by mutableStateOf(""); var busy by mutableStateOf(false)
    fun run(success: String,block: suspend ()->Unit) {
        if(busy) return
        busy=true
        scope.launch { try { block(); message=success } catch(e: Exception) { message=e.message ?: "Operation failed" } finally { busy=false } }
    }
    fun change(label: String,block: (Campaign)->Campaign) = run(label) { repo.mutate(label=label,change=block) }
}
@Composable fun Section(title: String,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,style=MaterialTheme.typography.titleMedium,color=Brass); content() } }
}
@Composable fun Page(content: @Composable ColumnScope.()->Unit) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}
@Composable fun Field(label: String,value: String,change: (String)->Unit,modifier: Modifier=Modifier,single: Boolean=true) {
    OutlinedTextField(value,change,label={ Text(label) },modifier=modifier.fillMaxWidth(),singleLine=single)
}
@Composable fun Toggle(label: String,value: Boolean,change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp)); Switch(value,change) }
}
@Composable fun <T> Choices(label: String,values: List<T>,selected: T?,display: (T)->String={it.toString()},change: (T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    if(label.isNotBlank()) Text(label,style=MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(selected?.let(display) ?: "Choose…")}
        DropdownMenu(expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=360.dp)) {
            values.forEach {v->DropdownMenuItem(text={Text(display(v))},onClick={change(v);expanded=false})}
        }
    }
}
@Composable fun Action(label: String,enabled: Boolean=true,danger: Boolean=false,onClick: ()->Unit) {
    Button(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled,colors=if(danger) ButtonDefaults.buttonColors(containerColor=Color(0xFF963D44),contentColor=Color.White) else ButtonDefaults.buttonColors()) { Text(label) }
}
@Composable fun <T> ChoiceGrid(values: List<T>,selected: T?,display: (T)->String={it.toString()},change: (T)->Unit) {
    values.chunked(3).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        row.forEach {value->FilterChip(selected==value,onClick={change(value)},label={Text(display(value))},modifier=Modifier.weight(1f).heightIn(min=48.dp))}
        repeat(3-row.size) {Spacer(Modifier.weight(1f))}
    }}
}
fun String.intValue(name: String,min: Int,max: Int): Int = (toIntOrNull() ?: error("$name must be a whole number")).also { require(it in min..max) { "$name must be $min–$max" } }
