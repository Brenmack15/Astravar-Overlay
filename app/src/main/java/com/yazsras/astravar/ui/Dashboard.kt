package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
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
    var ac by remember(prefs.armorClass) {mutableStateOf(prefs.armorClass?.toString() ?: "")}
    var advantage by remember(prefs.advantage) {mutableStateOf(prefs.advantage)}
    var disadvantage by remember(prefs.disadvantage) {mutableStateOf(prefs.disadvantage)}
    var optionsOpen by remember {mutableStateOf(false)}
    var preview by remember {mutableStateOf<ShotPlan?>(null)}
    var externalDice by remember {mutableStateOf(false)}
    val blast=ability in listOf(Ability.DISCHARGE,Ability.SHATTER)
    val magic=ability in listOf(Ability.SPELL,Ability.DISCHARGE,Ability.SHATTER)
    fun options()=RollOptions(advantage,disadvantage,ac.takeIf {it.isNotBlank()}?.intValue("Armor Class",1,100))
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
    Page {
        Section(ability.title) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                WeaponArtwork(prefs.image,Modifier.width(88.dp).height(62.dp))
                Column(Modifier.weight(1f)) {
                    Text(if(planned.isSuccess) "● READY" else "○ CHECK WEAPON",color=if(planned.isSuccess) Frost else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelLarge)
                    Text(s.installed?.name ?: "No core installed",style=MaterialTheme.typography.titleMedium)
                    Text(s.installed?.readiness(s.gameSeconds) ?: "Add your core in Inventory",style=MaterialTheme.typography.bodySmall)
                }
                Stat("ATTACK","+${s.profile.attack}")
            }
            Text(planned.getOrNull()?.let {Engine.components(it).joinToString(" + ") {c->"${c.damage.dice} ${c.damage.type.name.lowercase()}"}} ?: "${s.profile.base} Force",style=MaterialTheme.typography.titleLarge,color=Brass)
            Text(if(blast) "Dexterity save · DC ${s.profile.saveDc}" else "Save DC ${s.profile.saveDc} · ${distance} ft",style=MaterialTheme.typography.bodySmall,color=Frost)
            if(planned.isFailure) Text(planned.exceptionOrNull()?.message ?: "Check your configuration",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(!s.attuned) Action("Confirm I am attuned",!a.busy) {a.change("Attunement confirmed by player") {it.copy(attuned=true)}}
        }
        ChoiceGrid(RollMode.entries.toList(),if(advantage) RollMode.ADVANTAGE else if(disadvantage) RollMode.DISADVANTAGE else RollMode.NORMAL,{when(it) {RollMode.NORMAL->"Normal";RollMode.ADVANTAGE->"Adv";RollMode.DISADVANTAGE->"Dis"}}) {mode->
            advantage=mode==RollMode.ADVANTAGE;disadvantage=mode==RollMode.DISADVANTAGE
            a.run("") {preferences.targeting(options())}
        }
        if((distance.toIntOrNull() ?: 0)>60) Text("Long range · ${if(advantage) "advantage and disadvantage cancel" else "disadvantage applies"}",style=MaterialTheme.typography.bodySmall,color=Frost)
        if(unresolved!=null) ResolutionPanel(unresolved,a,prefs.manualOverrides)
        else {
            val last=s.pending.lastOrNull {it.result!=null}
            if(last!=null && showResult) {
                RollResultCard(last,onAgain={attack()},enabled=!a.busy && planned.isSuccess)
                TextButton(onClick={showResult=false}) {Text("Back to weapon")}
            } else Action(if(blast) "Review & roll ${ability.title.lowercase()}" else "Roll attack",!a.busy && planned.isSuccess,danger=ability==Ability.SHATTER) {attack()}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={corePicker=true},modifier=Modifier.weight(1f).heightIn(min=56.dp)) {Column {Text("Core ▾");Text(s.installed?.name ?: "None",style=MaterialTheme.typography.bodySmall)}}
            OutlinedButton(onClick={payloadPicker=true},modifier=Modifier.weight(1f).heightIn(min=56.dp)) {Column {Text("Payload ▾");Text(s.installed?.payloads?.filter {it.id in selected}?.joinToString {it.template.name}?.ifBlank {"None"} ?: "None",style=MaterialTheme.typography.bodySmall)}}
        }
        Choices("Firing mode",Ability.entries.toList(),ability,{it.title}) {v->a.run("") {preferences.mode(v)}}
        if(ability==Ability.ELEMENTAL) {
            val allowed=if(s.profile.elementCapacity>1 && s.profile.elementCapacityOverride==null) Engine.basic else Engine.elements(s.profile)
            ChoiceGrid(allowed,elements.firstOrNull(),{it.name.lowercase().replaceFirstChar(Char::titlecase)}) {t->a.run("") {preferences.elements(if(s.profile.elementCapacity==1) listOf(t) else if(t in elements) elements-t else (elements+t).takeLast(s.profile.elementCapacity))}}
            if(elements.size>1) Text(elements.joinToString(" + "){it.name.lowercase()},color=Frost)
        }
        if(blast) Section("Blast targeting") {
            Text(if(ability==Ability.SHATTER) "60 ft radius · destroys the core" else "15 ft radius · core recovers in 10 game minutes",color=if(ability==Ability.SHATTER) MaterialTheme.colorScheme.error else Frost)
            Field("Where does the blast land?",target,{target=it})
            Toggle("Also make a direct pistol attack",direct) {direct=it}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={a.run("") {preferences.targeting(options());preferences.aim(distance.intValue("Distance",0,180),target,direct,immediate,manualSlot);start()}},modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Floating controls")}
            TextButton(onClick={optionsOpen=!optionsOpen},modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(if(optionsOpen) "Less ▴" else "Shot options ▾")}
        }
        if(optionsOpen) Section("Shot options") {
            Field("Target Armor Class (optional)",ac,{ac=it})
            Text("Leave AC blank to show the attack total without judging hit or miss.",style=MaterialTheme.typography.bodySmall)
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
    if(corePicker) CorePicker(s,a) {corePicker=false}
    if(payloadPicker) PayloadPicker(s,a,preferences,selected) {payloadPicker=false}
    preview?.let {p->AlertDialog(onDismissRequest={preview=null},title={Text(if(p.ability==Ability.SHATTER) "Shatter this core?" else p.ability.title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(p.actionCost)
        Engine.components(p).forEach {Text("${it.damage.dice} ${it.damage.type.name.lowercase()} · ${it.label}")}
        if(p.disadvantage) Text("Long-range disadvantage applies.")
        if(p.hasSpell) Text("Spends ${p.selectedPayloads.size} stored spell(s)${p.immediate?.let {" and one level-${it.castingLevel} slot"} ?: ""}, including on a miss.")
        if(p.ability==Ability.DISCHARGE) Text("The core recovers after 600 game seconds. Unselected spells remain.")
        if(p.ability==Ability.SHATTER) Text("Destroys ${s.core(p.coreId).name} and ALL remaining stored spells. Only the selected spells add damage.",color=MaterialTheme.colorScheme.error)
        Text(if(externalDice) "Costs will be saved; enter your external dice afterward." else "Dice and costs are saved together when you tap Roll.")
    }},confirmButton={TextButton(onClick={if(externalDice) a.run("Enter your dice below") {a.repo.commit(p,"main")} else fire(p);preview=null},enabled=!a.busy) {Text(if(externalDice) "Commit for external dice" else if(p.ability==Ability.SHATTER) "Destroy core & roll" else "Roll now")}},dismissButton={TextButton(onClick={preview=null}) {Text("Cancel")}})}
}

@Composable fun RollResultCard(pending: PendingShot,onAgain: (()->Unit)?=null,enabled: Boolean=true) {
    val p=pending.plan;val result=pending.result ?: return
    val context=LocalContext.current
    Section(p.ability.title) {
        val headline=when(result.outcome) {Outcome.CRITICAL->"CRITICAL HIT";Outcome.MISS->if(pending.rolled?.natural==1) "NATURAL 1 — MISS" else "MISS";Outcome.HIT->"HIT";Outcome.POINT->"DEXTERITY SAVE · DC ${p.profile.saveDc}";Outcome.UNCONFIRMED->"ATTACK"}
        Text(headline,color=if(result.outcome==Outcome.MISS) MaterialTheme.colorScheme.error else Brass,style=MaterialTheme.typography.labelLarge)
        result.attackTotal?.let {Text("$it TO HIT",style=MaterialTheme.typography.headlineLarge,color=Frost)}
        pending.rolled?.let {Text(it.attackDescription(p),color=Frost)}
        if(p.blast) {
            if(result.targets.isEmpty()) Text("No affected creatures recorded")
            result.targets.forEach {t->Text("${t.name}: ${Engine.damage(p,result,t).damageText()}")}
        } else {
            val damage=Engine.damage(p,result)
            Text("${damage.values.sum()} DAMAGE",style=MaterialTheme.typography.headlineMedium,color=Brass)
            Text(damage.damageText(),style=MaterialTheme.typography.titleMedium,color=Frost)
        }
        Text("${pending.rolled?.coreName?.ifBlank {p.coreId} ?: p.coreId} · ${p.selectedPayloads.joinToString {it.template.name}.ifBlank {p.immediate?.name ?: "No spell payload"}}",style=MaterialTheme.typography.bodySmall)
        if(result.outcome==Outcome.UNCONFIRMED) Text("AC unknown · damage shown is the rolled damage",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Astravar result",shotSummary(pending)))}) {Text("Copy result")}
        if(onAgain!=null) Action("Roll again",enabled=enabled,onClick=onAgain)
        DiceDetails(pending)
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
                    if(p.blast) ChoiceGrid(listOf(Outcome.HIT,Outcome.MISS),outcome,{it.name.lowercase().replaceFirstChar(Char::titlecase)}) {outcome=it}
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
                    var defensesOpen by remember(p.id,i) {mutableStateOf(false)}
                    TextButton(onClick={defensesOpen=!defensesOpen}) {Text("Resistances & immunities ${if(defensesOpen) "▴" else "▾"}")}
                    if(defensesOpen) components.map {it.damage.type}.distinct().forEach {type->Choices(type.name,Defense.entries.toList(),t.defenses[type] ?: Defense.NORMAL) {d->targets=targets.toMutableList().also {it[i]=it[i].copy(defenses=it[i].defenses+(type to d))}}}
                    TextButton(onClick={targets=targets.filterIndexed {j,_->j!=i}}) {Text("Remove creature")}
                }
                OutlinedButton(onClick={targets=targets+TargetSave("Creature ${targets.size+1}",null)},enabled=targets.size<200) {Text("+ Add affected creature")}
                Action("Finish blast · ${targets.size} creatures",!a.busy) {save(resolvedOutcome)}
            } else if(roll==null) Action("Save external result",!a.busy) {save(outcome)}
        }
    }
}
