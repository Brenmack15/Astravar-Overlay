package com.yazsras.astravar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.*
import kotlin.math.*

val DisplayFace=FontFamily(Font(R.font.cinzel),Font(R.font.cinzel_semibold,FontWeight.SemiBold))
val InterfaceFace=FontFamily(Font(R.font.inter),Font(R.font.inter_medium,FontWeight.Medium),Font(R.font.inter_semibold,FontWeight.SemiBold))
fun arcaneTypography() = Typography(
    headlineLarge=TextStyle(fontFamily=DisplayFace,fontSize=40.sp,lineHeight=46.sp),
    headlineMedium=TextStyle(fontFamily=DisplayFace,fontSize=27.sp,lineHeight=34.sp,letterSpacing=1.sp),
    headlineSmall=TextStyle(fontFamily=DisplayFace,fontSize=23.sp,lineHeight=30.sp),
    titleLarge=TextStyle(fontFamily=DisplayFace,fontSize=21.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=DisplayFace,fontSize=17.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontFamily=InterfaceFace,fontSize=16.sp,lineHeight=24.sp),
    bodyMedium=TextStyle(fontFamily=InterfaceFace,fontSize=14.sp,lineHeight=21.sp),
    bodySmall=TextStyle(fontFamily=InterfaceFace,fontSize=12.sp,lineHeight=18.sp),
    labelLarge=TextStyle(fontFamily=InterfaceFace,fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium),
    labelMedium=TextStyle(fontFamily=InterfaceFace,fontSize=11.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium),
    labelSmall=TextStyle(fontFamily=InterfaceFace,fontSize=10.sp,lineHeight=14.sp,letterSpacing=.8.sp)
)

/** One small repeating texture; geometry and brushes are cached per measured surface. */
@Composable fun Modifier.instrumentSurface():Modifier {
    val grain=ImageBitmap.imageResource(R.drawable.graphite_texture)
    return drawWithCache {
        val texture=ShaderBrush(ImageShader(grain,TileMode.Repeated,TileMode.Repeated))
        val light=Brush.radialGradient(listOf(Frost.copy(alpha=.07f),Color.Transparent),Offset(size.width*.8f,0f),size.height.coerceAtLeast(1f)*.75f)
        onDrawBehind {drawRect(Color(0xFF0B0E10));drawRect(texture,alpha=.72f);drawRect(light)}
    }
}
private fun framePath(w:Float,h:Float,inset:Float,cut:Float)=Path().apply {
    moveTo(inset+cut,inset);lineTo(w-inset-cut,inset);lineTo(w-inset,inset+cut)
    lineTo(w-inset,h-inset-cut);lineTo(w-inset-cut,h-inset);lineTo(inset+cut,h-inset)
    lineTo(inset,h-inset-cut);lineTo(inset,inset+cut);close()
}
@Composable fun Modifier.metalSurface(accent:Color=Brass,lit:Boolean=false,pressed:Boolean=false):Modifier {
    val grain=ImageBitmap.imageResource(R.drawable.metal_texture)
    return drawWithCache {
        val edge=framePath(size.width,size.height,1.dp.toPx(),10.dp.toPx())
        val inner=framePath(size.width,size.height,5.dp.toPx(),8.dp.toPx())
        val etched=framePath(size.width,size.height,8.dp.toPx(),6.dp.toPx())
        val texture=ShaderBrush(ImageShader(grain,TileMode.Repeated,TileMode.Repeated))
        val base=Brush.verticalGradient(if(pressed) listOf(Color(0xFF242322),Color(0xFF090C0E)) else listOf(Color(0xFF20262A),Color(0xFF101518),Color(0xFF0D1012)))
        val metallic=Brush.linearGradient(listOf(accent.copy(alpha=.85f),Color(0xFF615140),accent.copy(alpha=.38f),accent.copy(alpha=.9f)))
        val glow=Brush.radialGradient(listOf(accent.copy(alpha=if(lit) .23f else .06f),Color.Transparent),Offset(size.width*.5f,size.height),size.width.coerceAtLeast(1f)*.7f)
        onDrawBehind {
            drawPath(edge,base);drawPath(edge,texture,alpha=.22f);drawPath(edge,glow)
            drawPath(edge,metallic,style=Stroke(if(lit) 2.dp.toPx() else 1.dp.toPx()))
            drawPath(inner,accent.copy(alpha=if(lit) .65f else .3f),style=Stroke(.6.dp.toPx()))
            drawPath(etched,Color.Black.copy(alpha=.7f),style=Stroke(1.dp.toPx()))
            val length=20.dp.toPx();val pad=11.dp.toPx()
            for(x in listOf(pad,size.width-pad)) {
                val direction=if(x==pad) 1 else -1
                drawLine(accent.copy(alpha=.6f),Offset(x,6.dp.toPx()),Offset(x+length*direction,6.dp.toPx()),.5.dp.toPx())
                drawLine(accent.copy(alpha=.4f),Offset(x,size.height-6.dp.toPx()),Offset(x+length*direction,size.height-6.dp.toPx()),.5.dp.toPx())
            }
        }
    }
}
@Composable fun ArcanePanel(accent:Color=Brass,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().clip(CutCornerShape(10.dp)).metalSurface(accent).padding(17.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}

@Composable fun ArcaneIcon(kind:String,modifier:Modifier=Modifier,tint:Color=Brass) {
    val resource=when(kind.lowercase()) {
        "weapon"->R.drawable.ic_weapon;"history"->R.drawable.ic_history;"payload"->R.drawable.ic_payload
        "settings"->R.drawable.ic_settings;"more"->R.drawable.ic_more;"roll","d20"->R.drawable.ic_d20
        "acid"->R.drawable.ic_acid;"cold"->R.drawable.ic_cold;"fire"->R.drawable.ic_fire
        "lightning"->R.drawable.ic_lightning;"poison"->R.drawable.ic_poison;"thunder"->R.drawable.ic_thunder
        "force"->R.drawable.ic_force;"radiant"->R.drawable.ic_radiant;"necrotic"->R.drawable.ic_necrotic;"psychic"->R.drawable.ic_psychic
        "core","cores & payloads"->R.drawable.ic_core;else->R.drawable.ic_emblem
    }
    Icon(painterResource(resource),contentDescription=null,modifier=modifier.size(24.dp),tint=tint)
}
@Composable fun ArcaneDial(modifier:Modifier=Modifier,tint:Color=Frost) {
    Canvas(modifier) {
        val r=size.minDimension*.45f;val c=center
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha=.17f),Color.Transparent),c,r*1.2f),r*1.2f,c)
        for(scale in listOf(1f,.88f,.73f))drawCircle(tint.copy(alpha=if(scale==.88f) .45f else .2f),r*scale,c,style=Stroke(.65.dp.toPx()))
        repeat(40) {i->val a=i*PI/20;val start=r*(if(i%5==0) .9f else .96f);drawLine(tint.copy(alpha=.42f),c+Offset((cos(a)*start).toFloat(),(sin(a)*start).toFloat()),c+Offset((cos(a)*r).toFloat(),(sin(a)*r).toFloat()),.7.dp.toPx())}
        repeat(3) {turn->val p=Path();repeat(6) {i->val a=(i*60+turn*20)*PI/180;val x=c.x+(cos(a)*r*.74).toFloat();val y=c.y+(sin(a)*r*.74).toFloat();if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();drawPath(p,tint.copy(alpha=.12f),style=Stroke(.7.dp.toPx()))}
    }
}
@Composable fun InstrumentMedallion(kind:String,modifier:Modifier=Modifier,tint:Color=Brass) {
    Box(modifier.size(64.dp),contentAlignment=Alignment.Center) {
        ArcaneDial(Modifier.fillMaxSize(),tint)
        ArcaneIcon(kind,Modifier.size(29.dp),tint)
    }
}
@Composable fun SectionCaption(title:String,trailing:String?=null) {
    Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(),style=MaterialTheme.typography.labelSmall,color=Brass)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawLine(Brush.horizontalGradient(listOf(Brass.copy(alpha=.55f),Brass.copy(alpha=.08f))),Offset(0f,center.y),Offset(size.width,center.y),.6.dp.toPx())
            val d=3.dp.toPx();val p=Path().apply {moveTo(size.width*.55f-d,center.y);lineTo(size.width*.55f,center.y-d);lineTo(size.width*.55f+d,center.y);lineTo(size.width*.55f,center.y+d);close()};drawPath(p,Brass.copy(alpha=.4f),style=Stroke(.5.dp.toPx()))
        }
        if(trailing!=null)Text(trailing,style=MaterialTheme.typography.labelSmall,color=Muted)
    }
}
@Composable fun StatusPill(label:String,ready:Boolean=true) {
    val tint=if(ready) Ready else if(label=="UNAVAILABLE" || label.contains("MISS")) Color(0xFFE4A19D) else Brass
    Row(Modifier.background(tint.copy(alpha=.07f),CutCornerShape(4.dp)).border(.5.dp,tint.copy(alpha=.3f),CutCornerShape(4.dp)).padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(9.dp)) {drawCircle(tint.copy(alpha=.15f));drawCircle(tint,style=Stroke(1.dp.toPx()));drawCircle(tint,size.width*.17f)}
        Text(label,style=MaterialTheme.typography.labelSmall,color=tint)
    }
}
@Composable fun MetalButton(text:String,enabled:Boolean=true,modifier:Modifier=Modifier,primary:Boolean=false,danger:Boolean=false,icon:String?=null,onClick:()->Unit) {
    val interaction=remember {MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
    val tint=if(!enabled) Muted.copy(alpha=.65f) else if(danger) MaterialTheme.colorScheme.error else Brass
    val buttonModifier=modifier.heightIn(min=if(primary && icon=="d20") 64.dp else 52.dp).clip(CutCornerShape(10.dp)).metalSurface(tint,primary,pressed).clickable(enabled=enabled,role=Role.Button,interactionSource=interaction,indication=ripple(),onClick=onClick)
    Row(buttonModifier.padding(horizontal=14.dp,vertical=12.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        if(icon!=null){ArcaneIcon(icon,Modifier.size(if(primary) 28.dp else 20.dp),tint);Spacer(Modifier.width(10.dp))}
        Text(text,color=tint,style=if(primary && icon=="d20") MaterialTheme.typography.titleLarge else if(primary && text.length<22) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,textAlign=TextAlign.Center)
    }
}
@Composable fun AstravarHeader(demo:Boolean,onSettings:(()->Unit)?=null) {
    Column(Modifier.instrumentSurface().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            InstrumentMedallion("Emblem",Modifier.size(48.dp),Brass)
            Column(Modifier.weight(1f)) {
                Text("ASTRAVAR",fontFamily=DisplayFace,fontSize=23.sp,letterSpacing=3.sp,color=Color(0xFFF0E4CA))
                Text("O V E R L A Y",style=MaterialTheme.typography.labelSmall,color=Brass)
            }
            if(demo)StatusPill("DEMO",false)
            if(onSettings!=null)IconButton(onClick=onSettings,modifier=Modifier.size(48.dp).semantics {contentDescription="Settings"}) {ArcaneIcon("Settings",tint=Brass)}
        }
        HorizontalDivider(color=Brass.copy(alpha=.35f),thickness=.6.dp)
    }
}
val weaponDestinations=listOf("Weapon","Cores & Payloads","History","More")
@Composable fun WeaponNavigation(selected:String,change:(String)->Unit) {
    Column(Modifier.instrumentSurface().navigationBarsPadding()) {
        HorizontalDivider(color=Brass.copy(alpha=.35f),thickness=.6.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
            weaponDestinations.forEach {name->val active=selected==name
                Column(Modifier.weight(1f).heightIn(min=68.dp).semantics {this.selected=active}.clickable(role=Role.Tab) {change(name)}.padding(top=11.dp,bottom=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    ArcaneIcon(name,Modifier.size(23.dp),if(active) Brass else Muted)
                    Text(name,fontSize=if(name=="Cores & Payloads") 9.sp else 11.sp,color=if(active) Brass else Muted,maxLines=1)
                    Box(Modifier.width(if(active) 22.dp else 0.dp).height(2.dp).background(Brass))
                }
            }
        }
    }
}
@Composable fun InstrumentTabs(labels:List<String>,selected:String,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        labels.forEach {label->val active=selected==label
            MetalButton(label,modifier=Modifier.weight(1f).heightIn(min=52.dp).semantics {this.selected=active},primary=active,icon=if(label=="Cores") "Core" else if(label=="Payloads") "Payload" else null) {onSelect(label)}
        }
    }
}
@Composable fun RollModeControl(mode:RollMode,change:(RollMode)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        RollMode.entries.forEach {m->val active=m==mode;val label=when(m){RollMode.NORMAL->"Normal";RollMode.ADVANTAGE->"Advantage";RollMode.DISADVANTAGE->"Disadvantage"}
            Column(Modifier.weight(1f).heightIn(min=52.dp).semantics {selected=active}.clip(CutCornerShape(7.dp)).metalSurface(if(active) Brass else Muted.copy(alpha=.35f),active).clickable(role=Role.RadioButton) {change(m)}.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                ArcaneIcon(if(m==RollMode.NORMAL) "Emblem" else "d20",Modifier.size(16.dp),if(active) Brass else Muted)
                Text(label,fontSize=11.sp,color=if(active) Brass else Muted,maxLines=1)
            }
        }
    }
}
@Composable fun ElementSelector(values:List<DamageType>,selected:List<DamageType>,change:(DamageType)->Unit) {
    values.chunked(3).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        row.forEach {type->val active=type in selected;val tint=elementTint(type)
            Row(Modifier.weight(1f).heightIn(min=48.dp).semantics {this.selected=active}.clip(CutCornerShape(7.dp)).metalSurface(if(active) tint else tint.copy(alpha=.4f),active).clickable(role=Role.Checkbox) {change(type)}.padding(horizontal=8.dp,vertical=10.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                ArcaneIcon(type.name,Modifier.size(18.dp),tint);Spacer(Modifier.width(6.dp));Text(type.name.lowercase().replaceFirstChar(Char::titlecase),fontSize=11.sp,color=if(active) Color.White else Muted)
            }
        };repeat(3-row.size){Spacer(Modifier.weight(1f))}
    }}
}
@Composable fun DamageRows(damage:Map<DamageType,Int>) {
    damage.forEach {(type,total)->Row(Modifier.fillMaxWidth().clip(CutCornerShape(6.dp)).metalSurface(elementTint(type).copy(alpha=.7f)).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        ArcaneIcon(type.name,Modifier.size(22.dp),elementTint(type));Spacer(Modifier.width(10.dp))
        Text(type.name.lowercase().replaceFirstChar(Char::titlecase),Modifier.weight(1f),color=elementTint(type),fontFamily=DisplayFace,fontSize=16.sp)
        Text(total.toString(),fontFamily=DisplayFace,fontSize=25.sp,color=Color(0xFFF4EBDA))
    }}
}
@Composable fun ChargeIndicators(used:Int,capacity:Int,tint:Color=Frost) {
    Row(horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
        repeat(capacity.coerceAtMost(12)) {i->Canvas(Modifier.size(9.dp)) {drawCircle(tint.copy(alpha=if(i<used) .35f else .06f));drawCircle(tint.copy(alpha=if(i<used) 1f else .35f),style=Stroke(.6.dp.toPx()));if(i<used)drawCircle(tint,size.width*.2f)}}
        Text("$used / $capacity",style=MaterialTheme.typography.labelSmall,color=Muted)
    }
}
@Composable fun WeaponActionWindow(s:Campaign,a:Actions) {
    if(s.encounter)ArcanePanel {SectionCaption("Weapon action limits");Text("Attacks ${s.turn.attacks}/2 · ${if(s.turn.fullAction) "Action used" else "Action available"}\n${if(s.turn.bonus) "Bonus Action used" else "Bonus Action available"} · spell firings ${s.turn.spellShots}/${s.profile.spellShotsPerTurn}",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={a.change("Player confirmed next weapon action window") {Engine.resetWeaponActions(it)}},enabled=!a.busy){Text("My next turn · reset weapon actions")}}
}
@Composable fun ControlRoomLink(title:String,subtitle:String,kind:String="More",open:()->Unit) {
    ArcanePanel(modifier=Modifier.clickable(role=Role.Button,onClick=open)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            InstrumentMedallion(kind,Modifier.size(48.dp),if(kind=="Core") Frost else Brass)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.titleMedium,color=Brass);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Muted)}
            Text("›",fontSize=26.sp,color=Brass)
        }
    }
}

/** Shared native dialog shell; resource confirmations keep their existing callbacks. */
@Composable fun ArcaneDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null) {
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismissRequest) {
        ArcanePanel(modifier=Modifier.widthIn(max=560.dp).imePadding()) {
            CompositionLocalProvider(LocalContentColor provides Brass) {ProvideTextStyle(MaterialTheme.typography.headlineSmall) {title()}}
            HorizontalDivider(color=Brass.copy(alpha=.3f))
            Box(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {text()}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                dismissButton?.invoke();confirmButton()
            }
        }
    }
}
