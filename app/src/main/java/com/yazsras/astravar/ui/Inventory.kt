package com.yazsras.astravar.ui

import androidx.compose.material3.*
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
    Page {
        Section("Core inventory · game clock ${s.gameSeconds}s") {
            Text("Readiness and installed location are tracked separately. Carrying spare completed cores has no arbitrary campaign cap.")
            s.cores.forEach { c ->
                Text("${c.name}${if(c.id==s.installedId) " · INSTALLED" else ""}\n${c.readiness(s.gameSeconds)} · ${c.payloads.size} stored payload(s)")
                if(!c.destroyed && c.id!=s.installedId) Action("Install ${c.name} · ${if(s.postShatter) s.profile.replacementCost else s.profile.swapCost}",!a.busy && c.usable(s.gameSeconds) && s.installation==null) { a.change("Install ${c.name}") { Engine.install(it,c.id) } }
                c.payloads.forEach { p ->
                    Text("${p.template.name} · level ${p.template.castingLevel} ${p.source}\n${p.template.damage.joinToString { "${it.dice} ${it.type}" }} + ${p.template.castingLevel}d8 ${p.template.overchargeType}\nLoaded at game second ${p.loadedAt} · approval: ${p.template.approvalNotes}")
                    TextButton(onClick={purge=c.id to p.id}) { Text("Purge · Bonus Action · no refund") }
                }
                HorizontalDivider()
            }
            Field("Name of a completed core you actually own",name,{name=it})
            Action("Confirm and record completed core",!a.busy && name.isNotBlank()) { val entered=name.trim(); a.change("Player confirmed completed core: $entered") { it.copy(cores=it.cores+Core(UUID.randomUUID().toString(),entered)) }; name="" }
        }
        Section("Charge / overwrite · Bonus Action") {
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
        CraftingSection(s,a)
    }
    purge?.let { (c,p) -> AlertDialog(onDismissRequest={purge=null},title={Text("Purge stored magic?")},text={Text("This spends a Bonus Action and loses the selected payload without refund.")},confirmButton={TextButton(onClick={a.change("Purge payload $p · Bonus Action") { Engine.purge(it,c,p) };purge=null}) {Text("Purge")}},dismissButton={TextButton(onClick={purge=null}) {Text("Cancel")}}) }
}
@Composable fun PayloadsPage(s: Campaign,a: Actions) {
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
        Section("Payload library · templates are not owned spells") {
            Text("Only approved instantaneous damage transfers. No conditions, concentration, ongoing damage, forced movement, summons, or original area shape. Multi-projectile totals require an explicit ruling.")
            s.templates.forEach { t ->
                Text("${t.name} · L${t.baseLevel} → L${t.castingLevel} · ${if(t.approved) "Approved" else "NEEDS RULING"}\n${t.damage.joinToString { "${it.dice} ${it.type}" }} + ${t.castingLevel}d8 ${t.overchargeType}\n${t.approvalNotes}")
                TextButton(onClick={ editId=t.id; name=t.name; base=t.baseLevel.toString(); casting=t.castingLevel.toString(); components=t.damage; overcharge=t.overchargeType; approved=t.approved; notes=t.approvalNotes }) { Text("Edit template · stored snapshots stay unchanged") }
            }
        }
        Section(if(editId==null) "Create approved damage template" else "Edit future loads") {
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
        Section("Manually confirmed scroll inventory") {
            s.scrolls.forEach { x -> Text("${s.templates.find { it.id==x.templateId }?.name}: ${x.quantity} scroll(s)") }
            Choices("Documented scroll payload",s.templates.map { it.id },scrollTemplate,display={id -> s.templates.first { it.id==id }.name}) {scrollTemplate=it}
            Field("Additional scrolls actually owned",quantity,{quantity=it})
            Action("Record confirmed scrolls",!a.busy) { a.change("Player confirmed scroll inventory") { state -> val t=state.templates.find { it.id==scrollTemplate } ?: error("Choose payload");state.copy(scrolls=state.scrolls+Scroll(UUID.randomUUID().toString(),t.id,quantity.intValue("Quantity",1,100000),t)) } }
        }
    }
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
