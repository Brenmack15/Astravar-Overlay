package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.data.*
import java.util.UUID

@Composable fun Dashboard(s: Campaign,a: Actions,start: ()->Unit,grant: ()->Unit,stop: ()->Unit,preferences: Preferences,prefs: OverlayPrefs) {
    val view=LocalView.current
    val ability=prefs.mode
    val elements=prefs.elements
    val selected=prefs.selectedPayloads.filter {id->s.installed?.payloads?.any {it.id==id}==true}.toSet()
    var immediate by remember(prefs.immediateId) {mutableStateOf(prefs.immediateId)}
    var manualSlot by remember(prefs.manualSlot) {mutableStateOf(prefs.manualSlot)}
    var direct by remember(prefs.directImpact) {mutableStateOf(prefs.directImpact)}
    var distance by remember(prefs.distance) {mutableStateOf(prefs.distance.toString())}
    var target by remember(prefs.targetPoint) {mutableStateOf(prefs.targetPoint)}
    var advantage by remember(prefs.advantage) {mutableStateOf(prefs.advantage)}
    var disadvantage by remember(prefs.disadvantage) {mutableStateOf(prefs.disadvantage)}
    var optionsOpen by remember {mutableStateOf(false)}
    var preview by remember {mutableStateOf<ShotPlan?>(null)}
    var externalDice by remember {mutableStateOf(false)}
    val blast=ability in listOf(Ability.DISCHARGE,Ability.SHATTER)
    val magic=ability in listOf(Ability.SPELL,Ability.DISCHARGE,Ability.SHATTER)
    fun options()=RollOptions(advantage,disadvantage)
    fun plan()=Engine.plan(s,UUID.randomUUID().toString(),ability,if(magic) selected.toList() else emptyList(),if(magic) s.templates.find {it.id==immediate} else null,elements,direct && blast,distance.intValue("Distance",0,180),target,manualSlot)
    fun prepare(manual: Boolean=false) {
        try {options().validate();externalDice=manual;preview=plan()} catch(e: Exception) {a.message=e.message ?: "Check your shot settings"}
    }
    fun fire(p: ShotPlan) {
        a.run("Dice saved") {val o=options();preferences.targeting(o);preferences.aim(distance.intValue("Distance",0,180),target,direct,immediate,manualSlot);val after=a.repo.fire(p,o,"main");if(prefs.haptics) rollFeedback(view,after.pending.last().rolled)}
    }
    val planned=runCatching {plan()}
    val unresolved=s.pending.firstOrNull {it.result==null}
    var showResult by remember(s.pending.lastOrNull()?.plan?.id) {mutableStateOf(true)}
    var corePicker by remember {mutableStateOf(false)}
    var payloadPicker by remember {mutableStateOf(false)}
    fun attack() {if(blast) prepare() else planned.fold({fire(it)},{a.message=it.message ?: "Check your shot"})}
    Column(Modifier.fillMaxSize()) {
    Page(Modifier.weight(1f),resetKey=s.pending.lastOrNull()?.plan?.id) {
        val last=s.pending.lastOrNull()?.takeIf {it.result!=null}
        if(last!=null && showResult) {
            RollResultCard(last)
            TextButton(onClick={showResult=false}) {Text("Back to weapon")}
        } else if(unresolved==null) WeaponHero(s,prefs.image,ability,planned.getOrNull(),planned.exceptionOrNull()?.message)
        if(!s.attuned) Action("Confirm I am attuned",!a.busy) {a.change("Attunement confirmed by player") {it.copy(attuned=true)}}
        if(unresolved!=null) ResolutionPanel(unresolved,a,prefs.manualOverrides)
        SectionCaption("Equipped systems")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ModuleControl("Core",s.installed?.name ?: "None",Frost,Modifier.weight(1f)) {corePicker=true}
            ModuleControl("Payload",s.installed?.payloads?.filter {it.id in selected}?.joinToString {it.template.name}?.ifBlank {"None"} ?: "None",Brass,Modifier.weight(1f)) {payloadPicker=true}
        }
        if((distance.toIntOrNull() ?: 0)>60) Text("Long range · ${if(advantage) "advantage and disadvantage cancel" else "disadvantage applies"}",style=MaterialTheme.typography.bodySmall,color=Frost)
        Choices("Firing mode",Ability.entries.toList(),ability,{it.title}) {v->a.run("") {preferences.mode(v)}}
        if(ability==Ability.ELEMENTAL || blast) {
            TextButton(onClick={a.run("") {preferences.elements(emptyList());if(!blast) preferences.mode(Ability.ARCANE)}}) {Text(if(elements.isEmpty()) "✓ Neutral Force" else "Neutral Force")}
            val allowed=if(s.profile.elementCapacity>1 && s.profile.elementCapacityOverride==null) Engine.basic else Engine.elements(s.profile)
            SectionCaption("Elemental selector",s.profile.selector.toString())
            ElementSelector(allowed,elements) {t->a.run("") {preferences.elements(if(s.profile.elementCapacity==1) listOf(t) else if(t in elements) elements-t else (elements+t).takeLast(s.profile.elementCapacity))}}
            if(elements.size>1) Text(elements.joinToString(" + "){it.name.lowercase()},color=Frost)
        }
        if(blast) Section("Blast targeting") {
            Text(if(ability==Ability.SHATTER) "60 ft radius · destroys the core" else "15 ft radius · core recovers in 10 game minutes",color=if(ability==Ability.SHATTER) MaterialTheme.colorScheme.error else Frost)
            Field("Where does the blast land?",target,{target=it})
            Toggle("Also make a direct pistol attack",direct) {direct=it}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            MetalButton("Floating controls",modifier=Modifier.weight(1f),icon="Emblem") {a.run("") {preferences.targeting(options());preferences.aim(distance.intValue("Distance",0,180),target,direct,immediate,manualSlot);start()}}
            TextButton(onClick={optionsOpen=!optionsOpen},modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(if(optionsOpen) "Less ▴" else "Shot options ▾")}
        }
        WeaponActionWindow(s,a)
        if(optionsOpen) Section("Shot options") {
            Field("Distance in feet",distance,{distance=it})
            if(magic) {
                Choices("Fresh spell slot",listOf<String?>(null)+s.templates.filter {it.approved}.map {it.id},immediate,display={id->s.templates.find {it.id==id}?.name ?: "None"}) {immediate=it}
                if(immediate!=null) {Text("Spends one level-${s.templates.find {it.id==immediate}?.castingLevel} slot.");Toggle("Confirm slot available when its count is unknown",manualSlot) {manualSlot=it}}
            }
            Action("Save shot options",!a.busy) {a.run("Shot options saved") {preferences.targeting(options());preferences.aim(distance.intValue("Distance",0,180),target,direct,immediate,manualSlot);optionsOpen=false}}
            TextButton(onClick={prepare()},enabled=!a.busy && unresolved==null) {Text("Preview dice & resource cost")}
            if(prefs.manualOverrides) TextButton(onClick={prepare(true)},enabled=!a.busy && unresolved==null) {Text("Manual override · external dice")}
        }
    }
    Surface(color=Color.Transparent,modifier=Modifier.instrumentSurface()) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        RollModeControl(if(advantage && !disadvantage) RollMode.ADVANTAGE else if(disadvantage && !advantage) RollMode.DISADVANTAGE else RollMode.NORMAL) {mode->
            advantage=mode==RollMode.ADVANTAGE;disadvantage=mode==RollMode.DISADVANTAGE
            a.run("") {preferences.targeting(options())}
        }
            Action(if(blast) "Review & roll ${ability.title.lowercase()}" else if(showResult && s.pending.lastOrNull()?.result!=null) "Roll again" else "Roll attack",!a.busy && planned.isSuccess && unresolved==null,danger=ability==Ability.SHATTER) {showResult=true;attack()}
            Text(if(blast) "Review the core cost before releasing" else "Attack + potential damage · DM determines the hit",style=MaterialTheme.typography.labelSmall,color=Muted)
        }
    }
    }
    if(corePicker) CorePicker(s,a) {corePicker=false}
    if(payloadPicker) PayloadPicker(s,a,preferences,selected) {payloadPicker=false}
    preview?.let {p->ArcaneDialog(onDismissRequest={preview=null},title={Text(if(p.ability==Ability.SHATTER) "Shatter this core?" else p.ability.title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(p.actionCost)
        Engine.components(p).forEach {Text("${it.damage.dice} ${it.damage.type.name.lowercase()} · ${it.label}")}
        if(p.disadvantage) Text("Long-range disadvantage applies.")
        if(p.hasSpell) Text("Spends ${p.selectedPayloads.size} stored spell(s)${p.immediate?.let {" and one level-${it.castingLevel} slot"} ?: ""}, including on a miss.")
        if(p.ability==Ability.DISCHARGE) Text("The core recovers after 600 game seconds. Unselected spells remain.")
        if(p.ability==Ability.SHATTER) Text("Destroys ${s.core(p.coreId).name} and ALL remaining stored spells. Only the selected spells add damage.",color=MaterialTheme.colorScheme.error)
        Text(if(externalDice) "Costs will be saved; enter your external dice afterward." else "Dice and costs are saved together when you tap Roll.")
    }},confirmButton={TextButton(onClick={if(externalDice) a.run("Enter your dice below") {a.repo.commit(p,"main")} else fire(p);preview=null},enabled=!a.busy) {Text(if(externalDice) "Commit for external dice" else if(p.ability==Ability.SHATTER) "Destroy core & roll" else "Roll now")}},dismissButton={TextButton(onClick={preview=null}) {Text("Cancel")}})}
}

@Composable fun ModuleControl(label:String,value:String,tint:Color,modifier:Modifier=Modifier,open:()->Unit) {
    ArcanePanel(accent=tint,modifier=modifier.clickable(onClick=open)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            InstrumentMedallion(label,Modifier.size(38.dp),tint)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("${label.uppercase()} MODULE",style=MaterialTheme.typography.labelSmall,color=tint)
                Text(value,style=MaterialTheme.typography.bodyMedium,maxLines=2,color=Color(0xFFECE6D9))
            }
            Text("›",color=tint,fontSize=20.sp)
        }
    }
}
@Composable fun WeaponStat(label:String,value:String,modifier:Modifier=Modifier) {
    Column(modifier.clip(CutCornerShape(6.dp)).metalSurface(Brass.copy(alpha=.6f)).padding(horizontal=4.dp,vertical=11.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=Muted,textAlign=TextAlign.Center,maxLines=1)
        Text(value,fontFamily=DisplayFace,fontSize=if(value.any(Char::isLetter)) 16.sp else 23.sp,color=Color(0xFFF0E5CE),maxLines=1)
    }
}
@Composable fun WeaponHero(s:Campaign,image:String,ability:Ability,plan:ShotPlan?,problem:String?=null) {
    SectionCaption("Main weapon",ability.title)
    ArcanePanel(accent=Brass) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("ASTRAVAR SYSTEM ONLINE",style=MaterialTheme.typography.labelSmall,color=Brass)
            StatusPill(if(plan!=null) "READY" else "CHECK WEAPON",plan!=null)
        }
        Box(Modifier.fillMaxWidth().height(164.dp),contentAlignment=Alignment.Center) {
            ArcaneDial(Modifier.size(180.dp),Frost)
            WeaponArtwork(image,Modifier.fillMaxWidth().height(156.dp))
        }
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("ASTRAVAR",fontFamily=DisplayFace,fontSize=25.sp,letterSpacing=3.sp,color=Color(0xFFF0E4CA))
            Text("ARCANE • MECHANICAL • PRECISION",style=MaterialTheme.typography.labelSmall,color=Muted)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            WeaponStat("ATTACK", "+${s.profile.attack}",Modifier.weight(.8f))
            WeaponStat("FORCE",s.profile.base.toString(),Modifier.weight(1.35f))
            WeaponStat("TYPE","Force",Modifier.weight(.95f))
            WeaponStat("SAVE DC",s.profile.saveDc.toString(),Modifier.weight(.9f))
        }
        Text(plan?.let {Engine.components(it).joinToString(" + ") {c->"${c.damage.dice} ${c.damage.type.name.lowercase().replaceFirstChar(Char::titlecase)}"}} ?: "${s.profile.base} Force",style=MaterialTheme.typography.bodyMedium,color=Frost,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
        if(problem!=null)Text(problem,style=MaterialTheme.typography.bodySmall,color=Brass)
    }
}
@Composable fun RollResultCard(pending:PendingShot,onAgain:(()->Unit)?=null,enabled:Boolean=true) {
    val p=pending.plan;val result=pending.result ?: return
    val context=LocalContext.current
    val natural=pending.rolled?.natural
    val critical=natural==20 || result.outcome==Outcome.CRITICAL
    val miss=natural==1 || result.outcome==Outcome.MISS
    val tint=if(miss) MaterialTheme.colorScheme.error else if(critical) Brass else Frost
    SectionCaption("Roll result",p.ability.title)
    ArcanePanel(accent=tint) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(p.ability.title.uppercase(),style=MaterialTheme.typography.labelSmall,color=Brass)
            if(critical)StatusPill("NATURAL 20",false)
        }
        val heading=when {critical->"CRITICAL HIT";natural==1->"NATURAL 1 — MISS";p.blast->"DEXTERITY SAVE · DC ${p.profile.saveDc}";else->"ATTACK ROLL"}
        Text(heading,color=tint,style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
        result.attackTotal?.let {total->
            Box(Modifier.fillMaxWidth().height(166.dp),contentAlignment=Alignment.Center) {
                ArcaneDial(Modifier.size(186.dp),tint)
                Text(total.toString(),fontFamily=DisplayFace,fontSize=79.sp,color=if(critical) Color(0xFFFFE9B7) else Color(0xFFE5F1FF))
            }
        }
        pending.rolled?.let {Text(it.attackDescription(p),color=Color(0xFFDDD5C7),style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())}
        if(p.blast) {
            SectionCaption("Explosion • rolled damage")
            val explosion=Engine.components(p).filter {it.portion!=Portion.DIRECT}.groupBy {it.damage.type}.mapValues {(_,cs)->cs.sumOf {result.rolls[it.id] ?: 0}}
            DamageRows(explosion)
            if(result.targets.isEmpty())Text("No affected creatures recorded")
            result.targets.forEach {t->Text("${t.name} · ${if(t.saved==true) "save passed" else "save failed"}",style=MaterialTheme.typography.labelLarge);DamageRows(Engine.damage(p,result,t))}
        } else {
            val damage=Engine.damage(p,result)
            SectionCaption(if(miss) "Damage" else "Potential damage")
            Text("${damage.values.sum()} ${if(miss) "DAMAGE" else "POTENTIAL DAMAGE"}",style=MaterialTheme.typography.headlineSmall,color=Brass)
            DamageRows(damage)
            if(!miss)Text("DM determines the hit and any target-specific adjustments.",style=MaterialTheme.typography.bodySmall,color=Muted)
            else if(natural!=1)Text("Miss recorded in this historical result.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Text("${pending.rolled?.coreName?.ifBlank {p.coreId} ?: p.coreId} · ${p.selectedPayloads.joinToString {it.template.name}.ifBlank {p.immediate?.name ?: "No spell payload"}}",style=MaterialTheme.typography.bodySmall,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Astravar result",shotSummary(pending)))}) {Text("Copy result")}
        }
        DiceDetails(pending)
        if(onAgain!=null)Action("Roll again",enabled=enabled,onClick=onAgain)
    }
}

@Composable fun DiceDetails(pending: PendingShot) {
    var open by remember(pending.plan.id) {mutableStateOf(false)}
    val roll=pending.rolled ?: return
    TextButton(onClick={open=!open}) {Text(if(open) "Hide individual dice" else "Show individual dice")}
    if(open) Engine.components(pending.plan,roll.critical).forEach {c->Text("${c.label} · ${c.damage.dice} ${c.damage.type.name.lowercase()}: ${roll.damage.getValue(c.id).detail()}",style=MaterialTheme.typography.bodySmall)}
}

@Composable fun ResolutionPanel(pending: PendingShot,a: Actions,allowManual: Boolean=false) {
    val p=pending.plan
    val roll=pending.rolled
    var manual by remember(p.id) {mutableStateOf(false)}
    var outcome by remember(p.id) {mutableStateOf<Outcome?>(if(p.blast && !p.directImpact) Outcome.POINT else null)}
    var attack by remember(p.id) {mutableStateOf("")}
    var values by remember(p.id) {mutableStateOf(mapOf<String,String>())}
    var targets by remember(p.id) {mutableStateOf(emptyList<TargetSave>())}
    val resolvedOutcome=roll?.suggestedOutcome ?: outcome
    val components=Engine.components(p,roll?.critical ?: (outcome==Outcome.CRITICAL)).filterNot {resolvedOutcome==Outcome.MISS && it.portion==Portion.DIRECT}
    fun save(o: Outcome?) {
        a.change("Resolved ${p.ability.title}") {s->
            val result=if(roll!=null) DiceRoller.resolution(p,roll,o,targets) else Resolution(o ?: error("Choose the attack outcome"),attack.takeIf {it.isNotBlank()}?.intValue("Attack total",-20,100),components.associate {it.id to (values[it.id] ?: "").intValue(it.label,it.damage.dice.minimum,it.damage.dice.maximum)},if(p.blast) targets else emptyList())
            Engine.resolve(s,p.id,result)
        }
    }
    Section(if(roll!=null) "${p.ability.title} · dice rolled" else "Finish ${p.ability.title.lowercase()}") {
        Text("Costs already saved. Finishing this result spends nothing more.",style=MaterialTheme.typography.bodySmall)
        if(roll==null && !manual) {
            Action("Roll saved shot now",!a.busy) {a.run("Dice saved") {a.repo.rollPending(p.id,RollOptions(),"main")}}
            if(allowManual) TextButton(onClick={manual=true}) {Text("Enter external dice")}
        } else {
            if(roll!=null) {
                Text(roll.attackTotal?.let {"$it to hit"} ?: "Blast dice ready",style=MaterialTheme.typography.headlineLarge,color=Frost)
                Text(roll.attackDescription(p))
                if(roll.suggestedOutcome==Outcome.CRITICAL) Text("NATURAL 20 · critical dice included",color=Brass)
                if(roll.suggestedOutcome==Outcome.MISS) Text("Miss · the blast still detonates")
                val shown=components.groupBy {it.damage.type}.mapValues {(_,cs)->cs.sumOf {roll.damage.getValue(it.id).total}}
                Text(shown.damageText(),style=MaterialTheme.typography.headlineSmall,color=Brass)
                DiceDetails(pending)
                if(roll.suggestedOutcome==null) {
                    if(p.blast) Choices("DM’s Direct Impact decision",listOf(Outcome.HIT,Outcome.MISS),outcome,{it.name.lowercase().replaceFirstChar(Char::titlecase)}) {outcome=it}
                    else {Text("Did it hit?");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick={save(Outcome.HIT)},Modifier.weight(1f),enabled=!a.busy) {Text("Hit · apply")};OutlinedButton(onClick={save(Outcome.MISS)},Modifier.weight(1f),enabled=!a.busy) {Text("Miss")}}}
                }
            } else {
                if(!p.blast || p.directImpact) {Choices("External attack outcome",listOf(Outcome.HIT,Outcome.CRITICAL,Outcome.MISS),outcome) {outcome=it;values=emptyMap()};Field("Attack total (optional)",attack,{attack=it})}
                components.forEach {c->Field("${c.label}: ${c.damage.dice} ${c.damage.type}",values[c.id] ?: "",{values=values+(c.id to it)})}
            }
            if(p.blast) {
                Text("DC ${p.profile.saveDc} Dexterity · record each creature's actual save. Allies are included.")
                targets.forEachIndexed {i,t->
                    HorizontalDivider()
                    Field("Creature ${i+1}",t.name,{v->targets=targets.toMutableList().also {it[i]=it[i].copy(name=v)}})
                    ChoiceGrid(listOf(false,true),t.saved,{if(it) "Save passed" else "Save failed"}) {v->targets=targets.toMutableList().also {it[i]=it[i].copy(saved=v)}}
                    if(p.directImpact) Toggle("Direct impact target",t.directTarget) {v->targets=targets.mapIndexed {j,x->x.copy(directTarget=j==i && v)}}
                    TextButton(onClick={targets=targets.filterIndexed {j,_->j!=i}}) {Text("Remove creature")}
                }
                OutlinedButton(onClick={targets=targets+TargetSave("Creature ${targets.size+1}",null)},enabled=targets.size<200) {Text("+ Add affected creature")}
                Action("Finish blast · ${targets.size} creatures",!a.busy) {save(resolvedOutcome)}
            } else if(roll==null) Action("Save external result",!a.busy) {save(outcome)}
        }
    }
}
