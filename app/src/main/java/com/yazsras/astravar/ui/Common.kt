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

val Gunmetal=Color(0xFF101315)
val Ready=Color(0xFF88D9B2)
val Muted=Color(0xFFB6B9BC)
val Brass=Color(0xFFE3C28C)
val Frost=Color(0xFF9DCEF0)
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
    MaterialTheme(colorScheme=darkColorScheme(primary=Brass,onPrimary=Color(0xFF241B10),secondary=Frost,
        background=Gunmetal,surface=Color(0xFF1C2126),surfaceVariant=Color(0xFF252B30),
        onSurface=Color(0xFFF0ECE4),onSurfaceVariant=Muted,outline=Color(0xFF69665F),error=Color(0xFFF2A4A1)),
        typography=arcaneTypography(),content=content)
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
    ArcanePanel { Text(title,style=MaterialTheme.typography.titleMedium,color=Brass); content() }
}
@Composable fun Page(modifier: Modifier=Modifier,resetKey: Any?=null,content: @Composable ColumnScope.()->Unit) {
    val scroll=rememberScrollState()
    LaunchedEffect(resetKey) {scroll.scrollTo(0)}
    Box(modifier.fillMaxSize().instrumentSurface(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=900.dp).fillMaxWidth().verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
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
    Button(onClick,Modifier.fillMaxWidth().heightIn(min=52.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),enabled=enabled,colors=if(danger) ButtonDefaults.buttonColors(containerColor=Color(0xFF963D44),contentColor=Color.White) else ButtonDefaults.buttonColors()) { Text(label) }
}
@Composable fun <T> ChoiceGrid(values: List<T>,selected: T?,display: (T)->String={it.toString()},change: (T)->Unit) {
    values.chunked(3).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        row.forEach {value->FilterChip(selected==value,onClick={change(value)},label={Text(display(value))},modifier=Modifier.weight(1f).heightIn(min=48.dp))}
        repeat(3-row.size) {Spacer(Modifier.weight(1f))}
    }}
}
fun String.intValue(name: String,min: Int,max: Int): Int = (toIntOrNull() ?: error("$name must be a whole number")).also { require(it in min..max) { "$name must be $min–$max" } }
