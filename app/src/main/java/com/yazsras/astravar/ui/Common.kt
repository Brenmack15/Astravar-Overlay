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

val Gunmetal=Color(0xFF0B0E10)
val Ready=Color(0xFF88D9B2)
val Muted=Color(0xFFB6B9BC)
val Brass=Color(0xFFE2C99B)
val Frost=Color(0xFF9DCEF0)
fun elementTint(type: DamageType)=when(type) {
    DamageType.FIRE -> Color(0xFFFFA36A)
    DamageType.LIGHTNING -> Color(0xFF8FCEFF)
    DamageType.POISON,DamageType.ACID -> Color(0xFFA8E597)
    DamageType.RADIANT -> Color(0xFFFFDF93)
    DamageType.NECROTIC -> Color(0xFFD2B5EC)
    DamageType.PSYCHIC -> Color(0xFFFFB4DD)
    DamageType.THUNDER -> Color(0xFFC7CAFF)
    else -> Frost
}
@Composable fun AstravarTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Brass,onPrimary=Color(0xFF241B10),secondary=Frost,
        primaryContainer=Color(0xFF352C1C),onPrimaryContainer=Brass,secondaryContainer=Color(0xFF17323F),onSecondaryContainer=Frost,tertiary=Brass,tertiaryContainer=Color(0xFF352C1C),surfaceTint=Brass,background=Gunmetal,surface=Color(0xFF1C2126),surfaceVariant=Color(0xFF252B30),
        onSurface=Color(0xFFF0ECE4),onSurfaceVariant=Muted,outline=Color(0xFF69665F),error=Color(0xFFF2A4A1)),
        typography=arcaneTypography(),shapes=Shapes(small=androidx.compose.foundation.shape.CutCornerShape(5.dp),medium=androidx.compose.foundation.shape.CutCornerShape(10.dp),large=androidx.compose.foundation.shape.CutCornerShape(14.dp)),content=content)
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
    ArcanePanel { SectionCaption(title); content() }
}
@Composable fun Page(modifier: Modifier=Modifier,resetKey: Any?=null,content: @Composable ColumnScope.()->Unit) {
    val scroll=rememberScrollState()
    LaunchedEffect(resetKey) {scroll.scrollTo(0)}
    Box(modifier.fillMaxSize().instrumentSurface(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=900.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}
@Composable fun Field(label: String,value: String,change: (String)->Unit,modifier: Modifier=Modifier,single: Boolean=true) {
    OutlinedTextField(value,change,label={ Text(label) },modifier=modifier.fillMaxWidth(),singleLine=single,shape=androidx.compose.foundation.shape.CutCornerShape(6.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Brass,unfocusedBorderColor=Brass.copy(alpha=.3f),focusedContainerColor=Color(0xFF11161A),unfocusedContainerColor=Color(0xFF101417)))
}
@Composable fun Toggle(label: String,value: Boolean,change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp)); Switch(value,change) }
}
@Composable fun <T> Choices(label: String,values: List<T>,selected: T?,display: (T)->String={it.toString()},change: (T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    if(label.isNotBlank()) Text(label,style=MaterialTheme.typography.labelLarge)
    Box {
        MetalButton(selected?.let(display) ?: "Choose…",modifier=Modifier.fillMaxWidth()) {expanded=true}
        DropdownMenu(expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=360.dp)) {
            values.forEach {v->DropdownMenuItem(text={Text(display(v))},onClick={change(v);expanded=false})}
        }
    }
}
@Composable fun Action(label: String,enabled: Boolean=true,danger: Boolean=false,onClick: ()->Unit) {
    MetalButton(label,enabled,Modifier.fillMaxWidth(),primary=true,danger=danger,icon=if(label.startsWith("Roll") || label.contains("roll")) "d20" else null,onClick=onClick)
}
@Composable fun <T> ChoiceGrid(values: List<T>,selected: T?,display: (T)->String={it.toString()},change: (T)->Unit) {
    values.chunked(3).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        row.forEach {value->MetalButton(display(value),modifier=Modifier.weight(1f),primary=selected==value) {change(value)}}
        repeat(3-row.size) {Spacer(Modifier.weight(1f))}
    }}
}
fun String.intValue(name: String,min: Int,max: Int): Int = (toIntOrNull() ?: error("$name must be a whole number")).also { require(it in min..max) { "$name must be $min–$max" } }
