package com.yazsras.astravar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.rules.*

fun arcaneTypography() = Typography(
    headlineLarge=TextStyle(fontFamily=FontFamily.Serif,fontSize=38.sp,lineHeight=44.sp),
    headlineMedium=TextStyle(fontFamily=FontFamily.Serif,fontSize=28.sp,lineHeight=34.sp),
    headlineSmall=TextStyle(fontFamily=FontFamily.Serif,fontSize=24.sp,lineHeight=30.sp),
    titleLarge=TextStyle(fontFamily=FontFamily.Serif,fontSize=22.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontWeight=FontWeight.Medium,fontSize=17.sp,lineHeight=24.sp),
    labelSmall=TextStyle(fontWeight=FontWeight.Medium,fontSize=10.sp,lineHeight=14.sp,letterSpacing=1.sp)
)

/** Sparse, cached linework: no bitmap textures, shaders, looping animation or per-die views. */
fun Modifier.instrumentSurface()=drawWithCache {
    val backdrop=Brush.verticalGradient(listOf(Color(0xFF171C21),Gunmetal,Color(0xFF0D1012)))
    onDrawBehind {
        drawRect(backdrop)
        for(i in 0..10) {
            val y=i*92.dp.toPx()
            drawLine(Color(0xFF53606D).copy(alpha=.055f),Offset(0f,y),Offset(size.width,y-size.width*.3f),1f)
        }
    }
}

@Composable fun ArcanePanel(accent: Color=Brass,modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color(0xFF1B2025),
        border=BorderStroke(1.dp,accent.copy(alpha=.22f))) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color.White.copy(alpha=.025f),Color.Transparent))).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

@Composable fun ArcaneIcon(kind: String,modifier: Modifier=Modifier,tint: Color=Brass) {
    Canvas(modifier.size(24.dp)) {
        val w=size.width;val h=size.height;val stroke=w*.055f
        fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(tint,Offset(w*x,h*y),Offset(w*x2,h*y2),stroke,StrokeCap.Round)
        when(kind) {
            "Weapon" -> {
                line(.12f,.34f,.88f,.34f);line(.2f,.51f,.8f,.51f);line(.88f,.34f,.88f,.51f)
                line(.24f,.5f,.15f,.84f);line(.15f,.84f,.32f,.84f);line(.32f,.84f,.43f,.5f)
                drawCircle(tint,w*.085f,Offset(w*.53f,h*.43f),style=Stroke(stroke))
            }
            "History" -> {drawRoundRect(tint,Offset(w*.24f,h*.13f),androidx.compose.ui.geometry.Size(w*.54f,h*.75f),androidx.compose.ui.geometry.CornerRadius(w*.07f),style=Stroke(stroke));for(y in listOf(.34f,.5f,.66f)) line(.36f,y,.65f,y)}
            "More" -> for(x in listOf(.2f,.5f,.8f)) drawCircle(tint,w*.055f,Offset(w*x,h*.5f))
            "Payload" -> {
                line(.5f,.08f,.5f,.92f);line(.08f,.5f,.92f,.5f);line(.2f,.2f,.8f,.8f);line(.2f,.8f,.8f,.2f)
                drawCircle(tint,w*.18f,style=Stroke(stroke))
            }
            else -> {
                drawCircle(tint,w*.38f,style=Stroke(stroke*.7f))
                val p=Path().apply {moveTo(w*.5f,h*.08f);lineTo(w*.63f,h*.37f);lineTo(w*.92f,h*.5f);lineTo(w*.63f,h*.63f);lineTo(w*.5f,h*.92f);lineTo(w*.37f,h*.63f);lineTo(w*.08f,h*.5f);lineTo(w*.37f,h*.37f);close()}
                drawPath(p,tint,style=Stroke(stroke));drawCircle(tint,w*.045f)
            }
        }
    }
}

@Composable fun StatusPill(label: String,ready: Boolean=true) {
    val color=if(ready) Ready else if(label=="UNAVAILABLE") Color(0xFFE4A19D) else Brass
    Surface(color=color.copy(alpha=.10f),shape=RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(6.dp)) {drawCircle(color)}
            Text(label,style=MaterialTheme.typography.labelSmall,color=color)
        }
    }
}

@Composable fun AstravarHeader(demo: Boolean) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ArcaneIcon("Core",Modifier.size(34.dp))
        Column(Modifier.weight(1f)) {
            Text("ASTRAVAR",fontFamily=FontFamily.Serif,fontSize=22.sp,letterSpacing=3.sp,color=Brass)
            Text("W E A P O N   S Y S T E M S",style=MaterialTheme.typography.labelSmall,color=Muted)
        }
        if(demo) StatusPill("DEMO",false)
    }
}

val weaponDestinations=listOf("Weapon","Cores & Payloads","History","More")
@Composable fun WeaponNavigation(selected: String,change: (String)->Unit) {
    NavigationBar(containerColor=Color(0xFF121619),tonalElevation=0.dp) {
        weaponDestinations.forEach {name->NavigationBarItem(selected=selected==name,onClick={change(name)},
            icon={ArcaneIcon(name,tint=if(selected==name) Brass else Muted)},
            label={Text(name,fontSize=if(name=="Cores & Payloads") 10.sp else 12.sp,maxLines=1)},
            colors=NavigationBarItemDefaults.colors(indicatorColor=Brass.copy(alpha=.12f),selectedTextColor=Brass,unselectedTextColor=Muted))}
    }
}

@Composable fun RollModeControl(mode: RollMode,change: (RollMode)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        RollMode.entries.forEach {m->
            val selected=m==mode
            OutlinedButton(onClick={change(m)},modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=3.dp,vertical=8.dp),shape=RoundedCornerShape(10.dp),
                border=BorderStroke(1.dp,if(selected) Brass else Muted.copy(alpha=.25f)),
                colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected) Brass.copy(alpha=.12f) else Color.Transparent,contentColor=if(selected) Brass else Muted)) {
                Text(when(m) {RollMode.NORMAL->"Normal";RollMode.ADVANTAGE->"Advantage";RollMode.DISADVANTAGE->"Disadvantage"},fontSize=12.sp,maxLines=1)
            }
        }
    }
}

@Composable fun DamageRows(damage: Map<DamageType,Int>) {
    damage.forEach {(type,total)->Row(Modifier.fillMaxWidth().background(elementTint(type).copy(alpha=.055f),RoundedCornerShape(9.dp)).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        ArcaneIcon("Payload",Modifier.size(18.dp),elementTint(type));Spacer(Modifier.width(10.dp))
        Text(type.name.lowercase().replaceFirstChar(Char::titlecase),Modifier.weight(1f),color=elementTint(type))
        Text(total.toString(),fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
    }}
}

@Composable fun WeaponActionWindow(s: Campaign,a: Actions) {
    if(s.encounter) ArcanePanel {
        Text("Weapon action limits",style=MaterialTheme.typography.labelLarge,color=Brass)
        Text("Attacks ${s.turn.attacks}/2 · ${if(s.turn.fullAction) "Action used" else "Action available"}\n${if(s.turn.bonus) "Bonus Action used" else "Bonus Action available"} · spell firings ${s.turn.spellShots}/${s.profile.spellShotsPerTurn}",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={a.change("Player confirmed next weapon action window") {Engine.resetWeaponActions(it)}},enabled=!a.busy) {Text("My next turn · reset weapon actions")}
    }
}

@Composable fun ControlRoomLink(title: String,subtitle: String,kind: String="More",open: ()->Unit) {
    ArcanePanel(modifier=Modifier.clickable(onClick=open)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            ArcaneIcon(kind,Modifier.size(28.dp),Frost)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,color=Brass)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            Text("›",fontSize=24.sp,color=Brass)
        }
    }
}
