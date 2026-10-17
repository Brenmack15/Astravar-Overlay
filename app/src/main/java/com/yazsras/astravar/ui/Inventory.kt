package com.yazsras.astravar.ui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yazsras.astravar.data.Preferences
import androidx.compose.runtime.*
import com.yazsras.astravar.rules.*
import java.util.UUID

@Composable fun CoresPage(s: Campaign,a: Actions) {
    var name by remember { mutableStateOf("") }
    var coreId by remember { mutableStateOf(s.installedId ?: s.cores.firstOrNull()?.id) }
    var templateId by remember { mutableStateOf(s.templates.firstOrNull()?.id) }
    var source by remember { mutableStateOf(SourceKind.SLOT) }
    var scrollId by remember { mutableStateOf<String?>(null) }
    var overwrite by remember { mutableStateOf<String?>(null) }
    var touch by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var purge by remember { mutableStateOf<Pair<String,String>?>(null) }
    var addCore by remember {mutableStateOf(false)}
    var charging by remember {mutableStateOf(false)}
    var crafting by remember {mutableStateOf(false)}
    var expandedCore by remember {mutableStateOf<String?>(null)}
    Page {
        InventoryHeading("Resonant cores","RESONANT SYSTEMS","${s.cores.count {it.usable(s.gameSeconds)}} ready · ${s.profile.capacity} payload capacity per core","Core")
        SectionCaption("SELECT A CORE", "${s.cores.size} recorded")
        if(s.cores.isEmpty()) ArcanePanel(accent=Frost) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                InstrumentMedallion("Core",Modifier.size(64.dp),Frost)
                Column(Modifier.weight(1f)) {Text("No cores recorded",style=MaterialTheme.typography.titleLarge);Text("Record only the completed cores you own.",style=MaterialTheme.typography.bodySmall,color=Muted)}
            }
        }
        s.cores.forEach { c ->
            val ready=c.usable(s.gameSeconds)
            val active=c.id==s.installedId
            val tint=if(ready) Frost else if(c.destroyed) MaterialTheme.colorScheme.error else Brass
            ArcanePanel(accent=if(active) Brass else tint) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        InstrumentMedallion("Core",Modifier.size(52.dp),tint)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(c.name,style=MaterialTheme.typography.titleLarge,fontSize=17.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurface)
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(if(active) "EQUIPPED" else "SPARE CORE",style=MaterialTheme.typography.labelSmall,color=if(active) Brass else Muted)
                                StatusPill(if(ready) "READY" else "UNAVAILABLE",ready)
                            }
                        }
                    }
                    HorizontalDivider(color=tint.copy(alpha=.25f))
                    Text(c.payloads.joinToString {it.template.name}.ifBlank {"No payload loaded"},style=MaterialTheme.typography.bodySmall,color=Muted)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Payload capacity",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Muted)
                        ChargeIndicators(c.payloads.size,s.profile.capacity,tint)
                    }
                    if(!ready) Text(c.readiness(s.gameSeconds),style=MaterialTheme.typography.bodySmall,color=tint)
                    if(!c.destroyed && !active) MetalButton("Install core · ${if(s.postShatter) s.profile.replacementCost else s.profile.swapCost}",enabled=!a.busy && ready && s.installation==null,modifier=Modifier.fillMaxWidth(),icon="Core") {a.change("Install ${c.name}") {Engine.install(it,c.id)}}
                }
                if(c.payloads.isNotEmpty()) {
                    TextButton(onClick={expandedCore=if(expandedCore==c.id) null else c.id},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(expandedCore==c.id) "Hide stored magic ▴" else "Stored magic & purge controls ▾")}
                    if(expandedCore==c.id) c.payloads.forEach {p->
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            InstrumentMedallion("Payload",Modifier.size(42.dp),elementTint(p.template.overchargeType))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(p.template.name,style=MaterialTheme.typography.titleMedium,color=Brass)
                                Text("Level ${p.template.castingLevel} · ${p.source.name.lowercase()} · one stored use",style=MaterialTheme.typography.bodySmall,color=Muted)
                            }
                        }
                        PayloadExpressions(p.template)
                        Text("Loaded at game second ${p.loadedAt} · ${p.template.approvalNotes}",style=MaterialTheme.typography.bodySmall,color=Muted)
                        TextButton(onClick={purge=c.id to p.id}) {Text("Purge · Bonus Action · no refund")}
                    }
                }
            }
        }
        SectionCaption("CORE OPERATIONS")
        TextButton(onClick={addCore=!addCore}) {Text(if(addCore) "Close core entry" else "+ Record a completed core")}
        if(addCore) Section("Record a core") {
            Field("Name of a completed core you actually own",name,{name=it})
            Action("Confirm and record completed core",!a.busy && name.isNotBlank()) { val entered=name.trim(); a.change("Player confirmed completed core: $entered") { it.copy(cores=it.cores+Core(UUID.randomUUID().toString(),entered)) }; name="" }
        }
        Action(if(charging) "Close charging controls" else "Charge / overwrite a payload") {charging=!charging}
        if(charging) Section("Charge / overwrite · Bonus Action") {
            Choices("Available core",s.cores.filter { !it.destroyed }.map { it.id },coreId,display={ id -> s.core(id).name }) { coreId=it; overwrite=null }
            Choices("Approved payload template",s.templates.filter { it.approved }.map { it.id },templateId,display={id -> s.templates.first { it.id==id }.name}) { templateId=it }
            Choices("Source",SourceKind.entries.toList(),source) { source=it }
            if(source==SourceKind.SCROLL) Choices("Consume matching scroll",s.scrolls.filter { it.quantity>0 && it.templateId==templateId }.map { it.id },scrollId,display={id -> "${s.scrolls.first { it.id==id }.quantity} remaining"}) { scrollId=it }
            else Toggle("Confirm available slot if local count is unknown",manual) { manual=it }
            val core=s.cores.find { it.id==coreId }
            Choices("Payload to overwrite (old energy is lost)",listOf<String?>(null)+(core?.payloads?.map { it.id } ?: emptyList()),overwrite,display={id -> core?.payloads?.find { it.id==id }?.template?.name ?: "Do not overwrite"}) { overwrite=it }
            Toggle("I am touching this available core",touch) { touch=it }
            Action(if(overwrite==null) "Charge core · spend source" else "Overwrite selected payload · spend source",!a.busy,danger=overwrite!=null) {
                val op=UUID.randomUUID().toString()
                a.run("Core charged; source spent once") { a.repo.mutate(op,if(overwrite==null) "Charge core · Bonus Action" else "Overwrite payload $overwrite · Bonus Action · no refund") {
                    Engine.charge(it,coreId ?: error("Choose core"),it.templates.find { t->t.id==templateId } ?: error("Choose template"),op,source,touch,manual,scrollId.takeIf { source==SourceKind.SCROLL },overwrite)
                } }
            }
        }
        TextButton(onClick={crafting=!crafting}) {Text(if(crafting) "Hide crafting" else "Core crafting & materials")}
        if(crafting) CraftingSection(s,a)
    }
    purge?.let { (c,p) -> ArcaneDialog(onDismissRequest={purge=null},title={Text("Purge stored magic?")},text={Text("This spends a Bonus Action and loses the selected payload without refund.")},confirmButton={TextButton(onClick={a.change("Purge payload $p · Bonus Action") { Engine.purge(it,c,p) };purge=null}) {Text("Purge")}},dismissButton={TextButton(onClick={purge=null}) {Text("Cancel")}}) }
}
@Composable fun PayloadsPage(s: Campaign,a: Actions,preferences: Preferences?=null,selected: Set<String> = emptySet()) {
    var editing by remember {mutableStateOf(false)}
    var scrollsOpen by remember {mutableStateOf(false)}
    var templatesOpen by remember {mutableStateOf(false)}
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("2") }
    var casting by remember { mutableStateOf("2") }
    var count by remember { mutableStateOf("3") }
    var sides by remember { mutableStateOf("6") }
    var flat by remember { mutableStateOf("0") }
    var type by remember { mutableStateOf(DamageType.COLD) }
    var components by remember { mutableStateOf(listOf<TypedDice>()) }
    var overcharge by remember { mutableStateOf(DamageType.COLD) }
    var approved by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var scrollTemplate by remember { mutableStateOf<String?>(null) }
    var quantity by remember { mutableStateOf("") }
    Page {
        InventoryHeading("Stored payloads","SPELL STORAGE","${s.installed?.name ?: "No installed core"} · choose up to ${s.profile.releaseLimit}","Payload")
        SectionCaption("SELECT A PAYLOAD", "${s.installed?.payloads?.size ?: 0} stored")
        if(s.installed?.payloads.isNullOrEmpty()) ArcanePanel(accent=Brass) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                InstrumentMedallion("Payload",Modifier.size(64.dp),Brass)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("EMPTY",style=MaterialTheme.typography.titleLarge,color=Brass)
                    Text("Charge approved spell energy into a core to use it here. Templates below are references, not charges.",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
        }
        s.installed?.payloads?.forEach {p->
            val ready=s.installed?.usable(s.gameSeconds)==true
            val active=p.id in selected
            val tint=elementTint(p.template.overchargeType)
            ArcanePanel(accent=if(active) Brass else tint) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    InstrumentMedallion("Payload",Modifier.size(52.dp),tint)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(p.template.name,style=MaterialTheme.typography.titleLarge,fontSize=17.sp,lineHeight=22.sp)
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(active) "SELECTED" else "STORED MAGIC",style=MaterialTheme.typography.labelSmall,color=if(active) Brass else Muted)
                            StatusPill(if(ready) "READY" else "UNAVAILABLE",ready)
                        }
                    }
                }
                PayloadExpressions(p.template)
                Text("LEVEL ${p.template.castingLevel} · ${p.source.name.lowercase()} · one stored use",style=MaterialTheme.typography.labelSmall,color=Muted)
                if(preferences!=null) MetalButton(if(active) "Deselect" else "Select payload",enabled=!a.busy && ready,modifier=Modifier.fillMaxWidth(),icon="Payload") {
                    a.run("Payload selection saved") {preferences.payloads(if(active) selected-p.id else (selected+p.id).toList().takeLast(s.profile.releaseLimit).toSet());preferences.mode(Ability.SPELL)}
                }
            }
        }
        SectionCaption("PAYLOAD OPERATIONS")
        ArcanePanel(accent=Frost) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                InstrumentMedallion("History",Modifier.size(48.dp),Frost)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Approved spell templates",style=MaterialTheme.typography.titleLarge,color=Brass)
                    Text("${s.templates.count {it.approved}} approved · ${s.templates.size} recorded",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
            TextButton(onClick={templatesOpen=!templatesOpen},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(templatesOpen) "Hide template catalogue ▴" else "Review templates & edit future loads ▾")}
            if(templatesOpen) {
                Text("Only approved instantaneous damage transfers. No conditions, concentration, ongoing damage, forced movement, summons, or original area shape. Multi-projectile totals require an explicit ruling.",style=MaterialTheme.typography.bodySmall,color=Muted)
                s.templates.forEach {t->
                    HorizontalDivider(color=Frost.copy(alpha=.18f))
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        InstrumentMedallion("Payload",Modifier.size(40.dp),elementTint(t.overchargeType))
                        Column(Modifier.weight(1f)) {Text(t.name,style=MaterialTheme.typography.titleMedium);Text("L${t.baseLevel} → L${t.castingLevel}",style=MaterialTheme.typography.bodySmall,color=Muted)}
                        StatusPill(if(t.approved) "APPROVED" else "NEEDS RULING",t.approved)
                    }
                    PayloadExpressions(t)
                    Text(t.approvalNotes,style=MaterialTheme.typography.bodySmall,color=Muted)
                    TextButton(onClick={editing=true;editId=t.id;name=t.name;base=t.baseLevel.toString();casting=t.castingLevel.toString();components=t.damage;overcharge=t.overchargeType;approved=t.approved;notes=t.approvalNotes}) {Text("Edit template · stored snapshots stay unchanged")}
                }
            }
        }
        Action(if(editing) "Close template editor" else "+ Create a damage template") {editing=!editing}
        if(editing) Section(if(editId==null) "Create approved damage template" else "Edit future loads") {
            Field("Spell/payload name",name,{name=it}); Field("Base spell level",base,{base=it}); Field("Actual slot or documented scroll casting level",casting,{casting=it})
            Text("Add each approved damage component separately. Upcast dice are entered explicitly; the app never invents scaling.")
            Field("Dice count",count,{count=it}); Field("Die sides",sides,{sides=it}); Field("Flat damage",flat,{flat=it})
            Choices("Damage type",DamageType.entries.toList(),type) { type=it }
            Action("Add typed damage component") { try { val d=Dice(count.intValue("Count",0,200),sides.intValue("Sides",4,100),flat.intValue("Flat",-100,100)); d.validate(); components=components+TypedDice(d,type) } catch(e: Exception) { a.message=e.message ?: "Invalid dice" } }
            components.forEachIndexed { i,d -> TextButton(onClick={components=components.filterIndexed { j,_ -> j!=i }}) { Text("${d.dice} ${d.type} · tap to remove") } }
            Choices("Overcharge type · 1d8 per actual level",DamageType.entries.toList(),overcharge) { overcharge=it }
            Field("Eligibility / approval notes",notes,{notes=it},single=false)
            Toggle("Instantaneous components and source eligibility approved",approved) { approved=it }
            Action("Save template · consumes no resource",!a.busy) {
                a.change("Saved payload template; existing loads unchanged") { current ->
                    val t=Template(editId ?: UUID.randomUUID().toString(),name.trim(),base.intValue("Base level",1,9),casting.intValue("Casting level",1,9),components,overcharge,approved,notes)
                    t.validate(); current.copy(templates=current.templates.filterNot { it.id==t.id }+t)
                }
            }
            TextButton(onClick={editId=null;name="";components=emptyList();approved=false;notes=""}) { Text("Start a new template") }
        }
        TextButton(onClick={scrollsOpen=!scrollsOpen}) {Text(if(scrollsOpen) "Hide scroll sources" else "Manage scroll sources")}
        if(scrollsOpen) Section("Manually confirmed scroll inventory") {
            s.scrolls.forEach { x -> Text("${s.templates.find { it.id==x.templateId }?.name}: ${x.quantity} scroll(s)") }
            Choices("Documented scroll payload",s.templates.map { it.id },scrollTemplate,display={id -> s.templates.first { it.id==id }.name}) {scrollTemplate=it}
            Field("Additional scrolls actually owned",quantity,{quantity=it})
            Action("Record confirmed scrolls",!a.busy) { a.change("Player confirmed scroll inventory") { state -> val t=state.templates.find { it.id==scrollTemplate } ?: error("Choose payload");state.copy(scrolls=state.scrolls+Scroll(UUID.randomUUID().toString(),t.id,quantity.intValue("Quantity",1,100000),t)) } }
        }
    }
}

@Composable private fun InventoryHeading(title: String,eyebrow: String,summary: String,kind: String) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        InstrumentMedallion(kind,Modifier.size(54.dp),Brass)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(eyebrow,style=MaterialTheme.typography.labelSmall,color=Brass)
            Text(title,style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurface)
            Text(summary,style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PayloadExpressions(template: Template) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        template.damage.forEach {typed->
            Surface(color=elementTint(typed.type).copy(alpha=.065f),shape=RoundedCornerShape(3.dp),border=BorderStroke(.5.dp,elementTint(typed.type).copy(alpha=.4f))) {
                Text("${typed.dice} ${typed.type.name.lowercase().replaceFirstChar(Char::titlecase)}",Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium,color=elementTint(typed.type))
            }
        }
    }
    Text("+ ${template.castingLevel}d8 ${template.overchargeType.name.lowercase()} overcharge",style=MaterialTheme.typography.bodySmall,color=Muted)
}

@Composable fun CraftingSection(s: Campaign,a: Actions) {
    var name by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    var recipe by remember { mutableStateOf("Replacement core") }
    val recipes=mapOf(
        "First prototype" to "Existing pistol + cult crystal; 150 gp total; 3 focused in-game days; no checks. Special first build only.",
        "Replacement core" to "One suitable rare Resonant Crystal + 100 gp conductive magical metal + 50 gp filament + 50 gp catalyst + 50 gp stabilizing reagents (250 gp secondary total); 3 focused crafting days with tools/workspace.",
        "Synthetic (proposed)" to "Level 20: natural crystal requirement removed. Cost/time require a ruling; configured ${s.profile.syntheticGp ?: "unknown"} gp / ${s.profile.syntheticDays ?: "unknown"} days."
    )
    Section("Crafting projects · manually recorded") {
        Choices("Recipe",recipes.keys.toList(),recipe) { recipe=it }; Text(recipes.getValue(recipe))
        Text("Sourcing may take time; one week was an estimate. Logging work does not award cores or spend gold automatically.")
        Field("Project name",name,{name=it}); Field("Materials / campaign notes",notes,{notes=it},single=false)
        Action("Record planned project",!a.busy) { a.change("Crafting project recorded") { it.copy(projects=it.projects+CraftProject(UUID.randomUUID().toString(),name,recipes.getValue(recipe),notes=notes)) } }
        s.projects.forEach { p -> Text("${p.name} · ${p.daysWorked} focused day(s)\n${p.notes}"); TextButton(onClick={a.change("Player recorded one focused crafting day for ${p.name}") { it.copy(projects=it.projects.map { x -> if(x.id==p.id) x.copy(daysWorked=x.daysWorked+1) else x }) }}) { Text("Record one completed focused day") } }
    }
}
