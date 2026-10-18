package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.encodeToString

@Composable fun Dashboard(s: Campaign,a: Actions,start: ()->Unit,grant: ()->Unit,stop: ()->Unit,preferences: Preferences,prefs: OverlayPrefs) {
    val view=LocalView.current
    val ability=if(prefs.mode==Ability.SHATTER) Ability.ARCANE else prefs.mode
    val blast=ability==Ability.DISCHARGE
    val melee=ability in listOf(Ability.GAUNTLET,Ability.SPELL_GAUNTLET,Ability.GUARDIAN)
    val magic=ability in listOf(Ability.SPELL,Ability.DISCHARGE,Ability.SPELL_GAUNTLET)
    val selected=prefs.selectedPayloads.filter {id->s.installed?.payloads?.any {it.id==id}==true}.toSet()
    var immediate by androidx.compose.runtime.saveable.rememberSaveable(prefs.immediateId) {mutableStateOf(prefs.immediateId)}
    var manualSlot by androidx.compose.runtime.saveable.rememberSaveable(prefs.manualSlot) {mutableStateOf(prefs.manualSlot)}
    var direct by androidx.compose.runtime.saveable.rememberSaveable(prefs.directImpact) {mutableStateOf(prefs.directImpact)}
    var distance by androidx.compose.runtime.saveable.rememberSaveable(prefs.distance) {mutableStateOf(prefs.distance.toString())}
    var target by androidx.compose.runtime.saveable.rememberSaveable(prefs.targetPoint) {mutableStateOf(prefs.targetPoint)}
    var advantage by remember(prefs.advantage) {mutableStateOf(prefs.advantage)}
    var disadvantage by remember(prefs.disadvantage) {mutableStateOf(prefs.disadvantage)}
    var overlay by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    var preview by remember {mutableStateOf<ShotPlan?>(null)}
    var externalDice by remember {mutableStateOf(false)}
    val unresolved=s.pending.firstOrNull {it.result==null}
    var resolutionDirty by rememberSaveable(unresolved?.plan?.id) {mutableStateOf(false)}
    val last=s.pending.lastOrNull()
    fun options()=RollOptions(advantage,disadvantage)
    fun plan()=Engine.plan(s,UUID.randomUUID().toString(),ability,if(magic) selected.toList() else emptyList(),
        if(magic) s.templates.find {it.id==immediate} else null,prefs.elements,direct && blast,
        if(melee) 5 else distance.intValue("Distance",0,180),target,manualSlot)
    fun prepare(manual:Boolean=false) {
        try { options().validate(); externalDice=manual; preview=plan() }
        catch(e:Exception) {a.message=e.message ?: "Check attack settings"}
    }
    fun restoreSavedOptions() {
        distance=prefs.distance.toString(); target=prefs.targetPoint; direct=prefs.directImpact
        immediate=prefs.immediateId; manualSlot=prefs.manualSlot
    }
    suspend fun clearExecutedPayloadSelection(p:ShotPlan) {
        if(p.hasSpell) {
            preferences.payloads(emptySet())
            preferences.aim(p.distance,p.targetPoint,p.directImpact,null,false)
            immediate=null; manualSlot=false
        }
    }
    fun fire(p:ShotPlan) {
        a.run("") {
            val o=options()
            preferences.targeting(o)
            preferences.aim(distance.intValue("Distance",0,180),target,direct,immediate,manualSlot)
            val after=a.repo.fire(p,o,"main")
            // Presentation selections reset only AFTER a committed execution, never on failure.
            if(!p.blast || p.directImpact) {
                preferences.targeting(RollOptions()); advantage=false; disadvantage=false
            }
            clearExecutedPayloadSelection(p)
            if(prefs.haptics) rollFeedback(view,after.pending.last().rolled)
        }
    }
    val planned=runCatching {plan()}
    val activePlan=planned.getOrNull()
    val saveOnly=blast && !direct
    Column(Modifier.fillMaxSize()) {
        Page(Modifier.weight(1f)) {
            PlayerHeading("Combat") {
                PlayerTextButton(onClick={overlay="Options"}) {Text("Options")}
            }
            PlayerCard(onClick={overlay="Attacks"},modifier=Modifier.border(1.dp,Brass.copy(alpha=.55f),androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ApprovedAttackMedallion(ability.approvedArt(),Modifier.size(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ability.title,style=MaterialTheme.typography.titleLarge,color=Brass)
                        Text(if(blast) "Area release" else if(melee) "Melee attack · 5 ft reach" else if(ability==Ability.SPELL) "Stored / configured spell" else "Ranged attack",style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                    PlayerTextButton(onClick={overlay="Attacks"}) {Text("Change")}
                }
            }
            if(ability==Ability.ELEMENTAL || ability==Ability.GAUNTLET || blast) {
                PlayerCard(onClick={overlay="Element"}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ElementParticle(prefs.elements.firstOrNull() ?: DamageType.FORCE,Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Active element",style=MaterialTheme.typography.bodySmall,color=Muted)
                            Text(prefs.elements.ifEmpty {listOf(DamageType.FORCE)}.joinToString(" + "){it.name.lowercase().replaceFirstChar(Char::titlecase)},style=MaterialTheme.typography.titleMedium)
                        }
                        Text("Change",color=Frost,style=MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if(magic) PlayerCard(onClick={overlay="Spells"}) {
                Text("Spell payload",style=MaterialTheme.typography.bodySmall,color=Muted)
                Text((s.installed?.payloads?.filter {it.id in selected}?.map {it.template.name}.orEmpty()+
                    listOfNotNull(s.templates.find {it.id==immediate}?.name)).joinToString(" + ").ifBlank {"Select spell"},style=MaterialTheme.typography.titleMedium)
            }
            PlayerCard(onClick={overlay="Damage"}) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(activePlan?.let {Engine.components(it).joinToString(" + "){c->"${c.damage.dice} ${c.damage.type.name.lowercase().replaceFirstChar(Char::titlecase)}"}}
                            ?: if(melee) "Review accepted melee configuration" else "${s.profile.base} Force",style=MaterialTheme.typography.titleMedium,color=Frost)
                        Text(if(saveOnly) "DC ${s.profile.saveDc} Dexterity · 15 ft radius"
                            else if(melee) "${(if(ability==Ability.GUARDIAN) s.profile.melee?.guardianAttackBonus else s.profile.melee?.attackBonus)?.let { if(it>=0) "+$it" else it.toString() } ?: "Unconfigured"} attack · 5 ft reach"
                            else "+${s.profile.attack} Attack · 60 / 180 ft",style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                    Text("›",color=Frost,fontSize=24.sp)
                }
            }
            if(melee && ability!=Ability.GUARDIAN) PlayerCard {
                Text(if(s.gauntletCharge != null) "Charged Gauntlet prepared" else "Charged Gauntlet", style=MaterialTheme.typography.titleMedium, color=Brass)
                Text(if(s.gauntletCharge != null) "The next melee attempt consumes the saved charge, including a miss. It expires when you confirm your next turn." else "A Bonus Action prepares the accepted STR + DEX + INT bonus for your next melee strike.", style=MaterialTheme.typography.bodySmall, color=Muted)
                if(s.gauntletCharge == null) PlayerTextButton(onClick={a.change("Charged Gauntlet prepared · Bonus Action") {Engine.chargeGauntlet(it)}},enabled=!a.busy && Engine.meleeLockReason(s.profile)==null && s.profile.melee?.chargedDamageType!=null && !s.turn.bonus) {Text("Prepare charged strike")}
                if(s.turn.bonus && s.gauntletCharge==null) Text("Bonus Action already spent this turn.",style=MaterialTheme.typography.bodySmall,color=Muted)
                if(s.profile.melee?.chargedDamageType==null) Text("Charged bonus damage type needs an accepted ruling in Gauntlet configuration.", style=MaterialTheme.typography.bodySmall, color=Frost)
            }
            if(s.turn.spellShots>0 || s.turn.bonus || s.gauntletCharge!=null) PlayerTextButton(onClick={a.change("Player confirmed next weapon turn") {Engine.resetWeaponActions(it)}},enabled=!a.busy) {Text("My next turn · reset weapon actions")}
            if(last!=null) RollResultCard(last) else PlayerCard {
                Text("Latest result",color=Muted,style=MaterialTheme.typography.bodySmall)
                Text("No rolls yet",style=MaterialTheme.typography.titleMedium)
            }
            if(unresolved!=null) PlayerTextButton(onClick={overlay="Resolve"}) {Text("Complete saved ${unresolved.plan.ability.title.lowercase()}")}
            if(!s.attuned) Action("Confirm attunement",!a.busy) {a.change("Attunement confirmed by player") {it.copy(attuned=true)}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Internal core · ${s.installed?.readiness(s.gameSeconds) ?: "Not configured"}",
                    style=MaterialTheme.typography.bodySmall,color=Muted)
                PlayerTextButton(onClick={start}) {Text("Floating controls")}
            }
        }
        Column(Modifier.fillMaxWidth().background(RebuildPalette.Canvas).padding(horizontal=16.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!saveOnly) RollModeControl(if(advantage && !disadvantage) RollMode.ADVANTAGE else if(disadvantage && !advantage) RollMode.DISADVANTAGE else RollMode.NORMAL) {mode->
                advantage=mode==RollMode.ADVANTAGE;disadvantage=mode==RollMode.DISADVANTAGE
                a.run("") {preferences.targeting(options())}
            }
            CombatRollAction(ability,if(saveOnly) "Roll ${ability.title.lowercase()}" else "Roll attack",
                !a.busy && activePlan!=null && unresolved==null) {
                if(blast) prepare() else activePlan?.let(::fire)
            }
            if(planned.isFailure && unresolved==null) Text(planned.exceptionOrNull()?.message ?: "Review systems",color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
    when(overlay) {
        "Attacks" -> AttackChooserRebuild(ability,{overlay=null},onSelect={value->
            if(value in listOf(Ability.GAUNTLET,Ability.SPELL_GAUNTLET,Ability.GUARDIAN)) { distance="5"; a.run("") { preferences.mode(value);preferences.aim(5,target,false,immediate,manualSlot) } }
            else a.run("") { preferences.mode(value) }
            overlay=null
        },profile=s.profile,charged=s.gauntletCharge!=null,onCharge={
            a.run("Charged Gauntlet prepared · Bonus Action") {a.repo.mutate(label="Charged Gauntlet prepared · Bonus Action") {Engine.chargeGauntlet(it)};preferences.mode(Ability.GAUNTLET);preferences.aim(5,target,false,immediate,manualSlot);distance="5";overlay=null}
        })
        "Element" -> ElementWheelV2(s.profile,prefs.elements,{overlay=null}) {type->
            a.run("") {
                if(type==DamageType.FORCE) {preferences.elements(emptyList());if(!blast && !melee) preferences.mode(Ability.ARCANE)}
                else {preferences.elements(if(s.profile.elementCapacity==1) listOf(type) else if(type in prefs.elements) prefs.elements-type else (prefs.elements+type).takeLast(s.profile.elementCapacity));if(!blast && !melee) preferences.mode(Ability.ELEMENTAL)}
            };overlay=null
        }
        "Core" -> CorePicker(s,a) {overlay=null}
        "Spells" -> SpellChooserRebuild(s,a,preferences,selected,immediate,{id->
            immediate=id;a.run("") {
                if(id==null) preferences.payloads(emptySet())
                preferences.aim(distance.intValue("Distance",0,180),target,direct,id,manualSlot)
            }
        }) {overlay=null}
        "Damage" -> PlayerSheet("Damage breakdown",{overlay=null}) {
            if(activePlan==null) Text(planned.exceptionOrNull()?.message ?: "Configure a usable attack first.")
            activePlan?.let {p->
                Engine.components(p).forEach {c->PlayerCard {
                    Text(c.label,style=MaterialTheme.typography.titleMedium)
                    Text("${c.damage.dice} ${c.damage.type.name.lowercase()}",color=Frost)
                    Text("Average ${c.damage.dice.average}",color=Muted,style=MaterialTheme.typography.bodySmall)
                }}
                Text(p.actionCost,style=MaterialTheme.typography.bodySmall,color=Muted)
                if(p.hasSpell) Text("Configured payload replaces the free elemental selector.",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
        "Resolve" -> unresolved?.let {shot->PlayerSheet("Saved result",{overlay=null},dirty=resolutionDirty) {ResolutionPanel(shot,a,prefs.manualOverrides) {resolutionDirty=it}}}
        "Options" -> PlayerSheet("Attack options",{restoreSavedOptions();overlay=null},dirty=distance!=prefs.distance.toString() || target!=prefs.targetPoint || direct!=prefs.directImpact || manualSlot!=prefs.manualSlot,
            actions={PlayerTextButton(onClick={
                a.run("Attack options saved") {preferences.targeting(options());preferences.aim(distance.intValue("Distance",0,180),target,direct,immediate,manualSlot);overlay=null}
            },enabled=!a.busy) {Text("Save")}}) {
            if(melee) Text("Melee reach: 5 feet",color=Frost) else Field("Distance in feet",distance,{distance=it})
            if(blast) {
                Field("Blast location",target,{target=it})
                Toggle("Include direct impact attack",direct) {direct=it}
            }
            if(magic && immediate!=null) Toggle("Confirm available slot when count is unknown",manualSlot) {manualSlot=it}
            PlayerTextButton(onClick={overlay=null;prepare()},enabled=!a.busy && unresolved==null) {Text("Preview resource cost")}
            if(prefs.manualOverrides) PlayerTextButton(onClick={overlay=null;prepare(true)},enabled=!a.busy && unresolved==null) {Text("Use external dice")}
            WeaponActionWindow(s,a)
        }
    }
    preview?.let {p->ArcaneDialog(onDismissRequest={preview=null;restoreSavedOptions()},title={Text("Confirm ${p.ability.title.lowercase()}")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(p.actionCost)
        Engine.components(p).forEach {Text("${it.damage.dice} ${it.damage.type.name.lowercase()} · ${it.label}")}
        if(p.hasSpell) Text("Consumes ${p.selectedPayloads.size} stored spell(s)${p.immediate?.let {" and one level-${it.castingLevel} slot"} ?: ""} on execution.")
        if(p.ability==Ability.DISCHARGE) Text("Core recovery: 600 game seconds. Unselected spells remain.")
    }},confirmButton={PlayerTextButton(onClick={if(externalDice) a.run("Enter external dice") {a.repo.commit(p,"main");clearExecutedPayloadSelection(p)} else fire(p);preview=null},enabled=!a.busy) {Text(if(externalDice) "Commit" else "Roll now")}},dismissButton={PlayerTextButton(onClick={preview=null;restoreSavedOptions()}) {Text("Cancel")}})}
}

@Composable private fun CombatRollAction(ability:Ability,label:String,enabled:Boolean,danger:Boolean=false,onClick:()->Unit) {
    val shape=CutCornerShape(topEnd=16.dp,bottomStart=16.dp)
    val colors=if(!enabled) listOf(RebuildPalette.Elevated,RebuildPalette.Elevated)
        else if(danger) listOf(MaterialTheme.colorScheme.errorContainer,MaterialTheme.colorScheme.errorContainer)
        else listOf(RebuildPalette.GoldBurnished,RebuildPalette.GoldBright,RebuildPalette.Gold)
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clip(shape).background(Brush.linearGradient(colors))
        .clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick).padding(horizontal=16.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        ApprovedAttackMedallion(ability.approvedArt(),Modifier.size(40.dp).alpha(if(enabled) 1f else .35f))
        Spacer(Modifier.width(12.dp))
        Text(label,color=if(!enabled) Muted else if(danger) MaterialTheme.colorScheme.onErrorContainer else RebuildPalette.Void,
            style=MaterialTheme.typography.titleMedium)
    }
}

private data class ApprovedAttackChoice(val art:ApprovedAttackArt,val name:String,val classification:String,val mode:Ability,val charge:Boolean=false)
@Composable fun AttackChooserRebuild(selected:Ability,onDismiss:()->Unit,onSelect:(Ability)->Unit,
    profile:Profile=Profile(),charged:Boolean=false,onCharge:(()->Unit)?=null) {
    val meleeLock=Engine.meleeLockReason(profile)
    val chargeLock=meleeLock ?: if(profile.melee?.chargedDamageType==null) "Needs accepted charged bonus damage type" else null
    val choices=listOf(
        ApprovedAttackChoice(ApprovedAttackArt.ELEMENTAL,"Elemental shot","Ranged attack",Ability.ELEMENTAL),
        ApprovedAttackChoice(ApprovedAttackArt.GAUNTLET,"Guardian gauntlet","Ordinary Thunder melee",Ability.GUARDIAN),
        ApprovedAttackChoice(ApprovedAttackArt.GAUNTLET,"Gauntlet strike","Melee · 5 ft",Ability.GAUNTLET),
        ApprovedAttackChoice(ApprovedAttackArt.CHARGED_GAUNTLET,"Charged gauntlet",if(charged) "Charge prepared" else "Bonus Action + melee",Ability.GAUNTLET,true),
        ApprovedAttackChoice(ApprovedAttackArt.SPELL_SHOT,"Spell-imbued shot","Configured spell",Ability.SPELL),
        ApprovedAttackChoice(ApprovedAttackArt.SPELL_STRIKE,"Spell-imbued strike","Melee + spell",Ability.SPELL_GAUNTLET),
        ApprovedAttackChoice(ApprovedAttackArt.DISCHARGE,"Discharge","Save-based area",Ability.DISCHARGE))
    PlayerSheet("Choose attack",onDismiss) {
        choices.chunked(2).forEach {row->Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            row.forEach {choice->
                val lock=if(choice.charge) chargeLock else if(choice.mode==Ability.GUARDIAN) Engine.guardianLockReason(profile) else if(choice.mode in listOf(Ability.GAUNTLET,Ability.SPELL_GAUNTLET)) meleeLock else null
                val available=lock==null && (!choice.charge || charged || onCharge!=null)
                val active=choice.mode==selected && (choice.mode!=Ability.GAUNTLET || choice.charge==charged) || (selected==Ability.ARCANE && choice.mode==Ability.ELEMENTAL)
                PlayerCard(Modifier.weight(1f).fillMaxHeight().then(if(!available) Modifier.semantics {disabled()} else Modifier),
                    onClick=if(available) {{if(choice.charge && !charged) onCharge?.invoke() else onSelect(choice.mode)}} else null,padding=12.dp,spacing=4.dp) {
                    ApprovedAttackMedallion(choice.art,Modifier.size(64.dp).alpha(if(available) 1f else .45f))
                    Text(choice.name,style=MaterialTheme.typography.titleMedium,color=if(available) MaterialTheme.colorScheme.onSurface else Muted)
                    Text(lock ?: if(active) "Selected · ${choice.classification}" else choice.classification,style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
        }}
        if(meleeLock!=null || chargeLock!=null) Text("Review Config → Rules & profile → Gauntlet configuration for the exact missing accepted values. No ability modifiers or damage types are guessed.",style=MaterialTheme.typography.bodySmall,color=Frost)
        PlayerDisclosure("Existing weapon modes") {
            PlayerEntry("Arcane shot","Neutral Force · existing ranged attack","Weapon",{onSelect(Ability.ARCANE)})
        }
    }
}

@Composable private fun SpellChooserRebuild(s:Campaign,a:Actions,preferences:Preferences,selected:Set<String>,immediate:String?,selectImmediate:(String?)->Unit,dismiss:()->Unit) {
    var family by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    val templates=s.templates.filter {it.approved}.groupBy {it.name}
    PlayerSheet(if(family==null) "Spell payload" else family!!,dismiss) {
        if(family!=null) {
            PlayerTextButton(onClick={family=null}) {Text("Back to spells")}
            templates[family].orEmpty().forEach {template->PlayerEntry("Level ${template.castingLevel}",template.damage.joinToString(" + "){"${it.dice} ${it.type.name.lowercase()}"},"Payload",{
                selectImmediate(template.id);dismiss()
            },if(immediate==template.id) "Selected" else null)}
        } else {
            if(s.installed?.payloads.isNullOrEmpty() && templates.isEmpty()) PlayerEmpty("No configured spells","Add approved spell templates in Config → Spell library.","Payload")
            if(!s.installed?.payloads.isNullOrEmpty()) {
                SectionCaption("Stored payloads")
                s.installed?.payloads?.forEach {payload->PlayerEntry(payload.template.name,"Level ${payload.template.castingLevel} · stored","Payload",{
                    a.run("") {preferences.payloads(if(payload.id in selected) selected-payload.id else if(s.profile.releaseLimit==1) setOf(payload.id) else (selected+payload.id).toList().takeLast(s.profile.releaseLimit).toSet())}
                    if(s.profile.releaseLimit==1) dismiss()
                },if(payload.id in selected) "Selected" else null)}
            }
            if(templates.isNotEmpty()) {
                SectionCaption("Configured spell slot")
                templates.forEach {(name,variants)->PlayerEntry(name,if(variants.size==1) "Level ${variants.single().castingLevel}" else "${variants.size} configured variants","Payload",{
                    if(variants.size==1) {selectImmediate(variants.single().id);dismiss()} else family=name
                })}
            }
            PlayerTextButton(onClick={selectImmediate(null);dismiss()}) {Text("Clear temporary selection")}
        }
    }
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
    val p=pending.plan
    val roll=pending.rolled
    val result=pending.result
    val context=LocalContext.current
    var details by androidx.compose.runtime.saveable.rememberSaveable(p.id) {mutableStateOf(false)}
    val components=Engine.components(p,roll?.critical ?: (result?.outcome==Outcome.CRITICAL))
    val damage=if(!p.blast && result!=null) Engine.damage(p,result) else components.mapNotNull {c->(roll?.damage?.get(c.id)?.total ?: result?.rolls?.get(c.id))?.let {c.damage.type to it}}
        .groupBy({it.first},{it.second}).mapValues {it.value.sum()}
    PlayerCard(onClick={details=true}) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Latest result",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text(when(roll?.natural){20->"Natural 20";1->"Natural 1";else->p.ability.title},style=MaterialTheme.typography.labelLarge,color=Frost)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if(roll?.attackTotal!=null || result?.attackTotal!=null) "ATTACK" else "SAVE DC",style=MaterialTheme.typography.labelSmall,color=Muted)
                Text((roll?.attackTotal ?: result?.attackTotal ?: p.profile.saveDc).toString(),fontSize=36.sp,fontFamily=InterfaceFace,color=Frost)
            }
            Column(Modifier.weight(1f)) {
                Text("DAMAGE",style=MaterialTheme.typography.labelSmall,color=Muted)
                Text(damage.values.sum().toString(),fontSize=36.sp,fontFamily=InterfaceFace,color=Brass)
            }
        }
        if(roll?.attackTotal!=null) Text(roll.attackDescription(p),style=MaterialTheme.typography.bodySmall,color=Muted)
        else if(p.blast) Text("Dexterity · ${p.radius} ft radius",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text(damage.damageText(),style=MaterialTheme.typography.bodySmall,color=Frost)
        if(p.blast && result==null) Text("Before saves",style=MaterialTheme.typography.bodySmall,color=Muted)
        else if(result?.outcome==Outcome.UNCONFIRMED) Text("Awaiting adjudication",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
    if(onAgain!=null) PlayerTextButton(onClick=onAgain,enabled=enabled) {Text("Roll again")}
    if(details) PlayerSheet("Roll details",{details=false},actions={PlayerTextButton(onClick={
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Astravar result",shotSummary(pending)))
    }) {Text("Copy full result")}}) {
        Text(shotSummary(pending),style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun DiceDetails(pending: PendingShot) {
    var open by remember(pending.plan.id) {mutableStateOf(false)}
    val roll=pending.rolled ?: return
    PlayerTextButton(onClick={open=!open}) {Text(if(open) "Hide individual dice" else "Show individual dice")}
    if(open) Engine.components(pending.plan,roll.critical).forEach {c->Text("${c.label} · ${c.damage.dice} ${c.damage.type.name.lowercase()}: ${roll.damage.getValue(c.id).detail()}",style=MaterialTheme.typography.bodySmall)}
}

@Composable fun ResolutionPanel(pending: PendingShot,a: Actions,allowManual: Boolean=false,onDirty:(Boolean)->Unit={}) {
    val p=pending.plan
    val roll=pending.rolled
    var manual by rememberSaveable(p.id) {mutableStateOf(false)}
    var outcome by rememberSaveable(p.id) {mutableStateOf<Outcome?>(if(p.blast && !p.directImpact) Outcome.POINT else null)}
    var attack by rememberSaveable(p.id) {mutableStateOf("")}
    var values by rememberSaveable(p.id,stateSaver=Saver<Map<String,String>,String>(
        save={BackupCodec.json.encodeToString(it)},restore={BackupCodec.json.decodeFromString(it)})) {mutableStateOf(mapOf<String,String>())}
    var targets by rememberSaveable(p.id,stateSaver=Saver<List<TargetSave>,String>(
        save={BackupCodec.json.encodeToString(it)},restore={BackupCodec.json.decodeFromString(it)})) {mutableStateOf(emptyList<TargetSave>())}
    val changed=attack.isNotBlank() || values.isNotEmpty() || targets.isNotEmpty() || outcome!=(if(p.blast && !p.directImpact) Outcome.POINT else null)
    LaunchedEffect(changed) {onDirty(changed)}
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
            if(allowManual) PlayerTextButton(onClick={manual=true}) {Text("Enter external dice")}
        } else {
            if(roll!=null) {
                Text(roll.attackTotal?.let {"$it to hit"} ?: "Blast dice ready",style=MaterialTheme.typography.headlineLarge,color=Frost)
                Text(roll.attackDescription(p))
                if(roll.suggestedOutcome==Outcome.CRITICAL) Text("NATURAL 20 · critical dice included",color=Brass)
                if(roll.suggestedOutcome==Outcome.MISS) Text(if(p.blast) "Miss · the blast still detonates" else "Miss · no damage applied; spent magic and charges stay consumed")
                val shown=components.groupBy {it.damage.type}.mapValues {(_,cs)->cs.sumOf {roll.damage.getValue(it.id).total}}
                Text(shown.damageText(),style=MaterialTheme.typography.headlineSmall,color=Brass)
                DiceDetails(pending)
                if(roll.suggestedOutcome==null) {
                    if(p.blast) Choices("DM’s Direct Impact decision",listOf(Outcome.HIT,Outcome.MISS),outcome,{it.name.lowercase().replaceFirstChar(Char::titlecase)}) {outcome=it}
                    else {Text("Did it hit?");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {PlayerButton(onClick={save(Outcome.HIT)},Modifier.weight(1f),enabled=!a.busy) {Text("Hit · apply")};PlayerOutlinedButton(onClick={save(Outcome.MISS)},Modifier.weight(1f),enabled=!a.busy) {Text("Miss")}}}
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
                    PlayerTextButton(onClick={targets=targets.filterIndexed {j,_->j!=i}}) {Text("Remove creature")}
                }
                PlayerOutlinedButton(onClick={targets=targets+TargetSave("Creature ${targets.size+1}",null)},enabled=targets.size<200) {Text("+ Add affected creature")}
                Action("Finish blast · ${targets.size} creatures",!a.busy) {save(resolvedOutcome)}
            } else if(roll==null) Action("Save external result",!a.busy) {save(outcome)}
        }
    }
}
