package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.CloudCampaignClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

private val abilityCodes = listOf("STR", "DEX", "CON", "INT", "WIS", "CHA")
private val damageKinds = listOf("acid", "bludgeoning", "cold", "fire", "force", "lightning", "necrotic", "piercing",
    "poison", "psychic", "radiant", "slashing", "thunder")
private val entityId = Regex("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}")
private fun newId() = UUID.randomUUID().toString()
private fun JsonObject?.str(key: String) = (this?.get(key) as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content.orEmpty()
private fun JsonObject?.strings(key: String) = (this?.get(key) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
private fun JsonObject?.rows(key: String) = (this?.get(key) as? JsonArray).orEmpty().map { it.jsonObject }
private fun JsonObjectBuilder.optional(key: String, value: String) { if (value.isNotBlank()) put(key, value.trim()) }
private fun jsonStrings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
private fun number(text: String, field: String, range: IntRange): Int = requireNotNull(
    text.trim().toIntOrNull()?.takeIf { it in range }) { "$field must be a whole number from ${range.first} to ${range.last}." }
private fun JsonObjectBuilder.optionalNumber(key: String, text: String, field: String, range: IntRange) {
    if (text.isNotBlank()) put(key, number(text, field, range))
}
private fun JsonObjectBuilder.optionalId(key: String, id: String) {
    if (id.isNotBlank()) {
        require(entityId.matches(id.trim())) { "Review the ${cloudLabel(key)} identity." }
        put(key, id.trim())
    }
}

@Serializable internal data class AttackDamageDraft(val id: String, val name: String = "", val type: String = "",
    val dice: String = "", val sides: String = "", val flat: String = "0", val bonus: String = "",
    val critical: Boolean = true, val variants: List<String> = emptyList()) {
    val preview: String get() = buildList {
        if (dice.toIntOrNull() != 0) add("${dice.ifBlank { "?" }}d${sides.ifBlank { "?" }}")
        if (flat.toIntOrNull() != 0 || isEmpty()) add(flat.ifBlank { "?" })
        if (bonus.isNotBlank()) add("$bonus modifier")
    }.joinToString(" + ").replace("+ -", "− ") + " " + type
    fun payload(): JsonObject = buildJsonObject {
        require(entityId.matches(id) && name.trim().length in 1..120 && type in damageKinds) { "Each damage component needs a name and type." }
        val count = number(dice, "Dice count for $name", 0..100)
        val modifier = number(flat, "Flat bonus for $name", -1000..1000)
        require(count > 0 || modifier != 0 || bonus.isNotBlank()) { "$name needs dice, a flat amount, or an ability bonus." }
        put("id", id); put("name", name.trim()); put("type", type); put("dice", count)
        // A flat-only component has zero faces. The wire format still includes a die size.
        put("sides", if (count == 0 && sides.isBlank()) 2 else number(sides, "Die size for $name", 2..1000))
        put("flat", modifier); put("critical", critical); optional("abilityBonus", bonus)
        if (variants.isNotEmpty()) {
            require(type in variants && variants.distinct().size == variants.size && variants.all { it in damageKinds }) {
                "The default type for $name must be among its allowed variants."
            }
            put("variants", jsonStrings(variants))
        }
    }
}
@Serializable internal data class AttackRiderDraft(val id: String, val trigger: String = "hit", val effect: String = "",
    val duration: String = "", val component: String = "", val saveAbility: String = "", val saveDc: String = "") {
    fun payload(): JsonObject = buildJsonObject {
        require(effect.trim().length in 1..500) { "Describe each conditional effect." }
        put("id", id); put("trigger", trigger); put("effect", effect.trim()); optional("duration", duration)
        optional("componentId", component); optional("saveAbility", saveAbility)
        require(saveAbility.isBlank() == saveDc.isBlank()) { "A secondary save needs both its ability and DC." }
        optionalNumber("saveDc", saveDc, "Secondary save DC", 1..100)
    }
}

@Serializable internal data class CloudAttackDraft(val id: String, val name: String = "", val source: String = "",
    val description: String = "", val delivery: String = "melee", val resolution: String = "attack", val timing: String = "attack",
    val classification: String = "", val ability: String = "", val proficient: Boolean = false, val fixed: Boolean = false,
    val hit: String = "", val bonus: String = "", val saveAbility: String = "", val dc: String = "", val onSave: String = "",
    val reach: String = "", val range: String = "", val longRange: String = "", val area: String = "",
    val item: String = "", val attuned: Boolean = false, val operating: Boolean = false, val form: String = "",
    val exclude: String = "", val pool: String = "", val cost: String = "",
    val components: List<AttackDamageDraft> = emptyList(), val riders: List<AttackRiderDraft> = emptyList(),
    val calculatedDc: Boolean = false, val dcAbility: String = "", val dcBase: String = "8",
    val dcProficient: Boolean = false, val dcBonus: String = "") {
    fun payload(actorId: String, revision: Long? = null, discardSelections: Boolean = false): JsonObject = buildJsonObject {
        require(name.trim().length in 1..160 && source.trim().length in 1..300) { "Enter the attack name and source." }
        put("actorId", actorId); put("attackId", id); put("name", name.trim()); put("source", source.trim())
        put("timing", timing); put("delivery", delivery); put("resolution", resolution)
        optional("classification", classification); optional("description", description)
        when (resolution) {
            "attack" -> if (fixed) put("fixedHit", number(hit, "Fixed hit modifier", -200..200)) else {
                require(ability in abilityCodes) { "Select the ability used for the hit calculation." }
                put("ability", ability); put("proficient", proficient); optionalNumber("hitBonus", bonus, "Additional hit bonus", -200..200)
            }
            "save" -> {
                require(saveAbility in abilityCodes) { "Select the target saving throw." }
                put("saveAbility", saveAbility); optional("saveSuccess", onSave)
                if (calculatedDc) {
                    require(dcAbility in abilityCodes) { "Select the source ability used for the DC." }
                    put("dcAbility", dcAbility); put("dcBase", number(dcBase, "Base DC", 0..100)); put("dcProficient", dcProficient)
                    optionalNumber("dcBonus", dcBonus, "Additional DC modifier", -100..100)
                } else put("saveDc", number(dc, "Save DC", 1..100))
            }
        }
        optionalNumber("reach", reach, "Reach", 0..10000); optionalNumber("range", range, "Normal range", 0..10000)
        optionalNumber("longRange", longRange, "Long range", 0..10000); optional("area", area)
        require(longRange.isBlank() || range.isNotBlank() && longRange.trim().toInt() >= range.trim().toInt()) { "Long range must be at least normal range." }
        optionalId("sourceItemId", item); optionalId("formId", form)
        require((!attuned && !operating) || item.isNotBlank()) { "Attunement and operating requirements need a source item." }
        if (attuned) put("requiresAttunement", true)
        if (operating) put("requiresOperating", true)
        val excluded = exclude.split(',').map(String::trim).filter(String::isNotBlank)
        require(excluded.size <= 16 && excluded.size == excluded.distinct().size && excluded.all(entityId::matches) && form !in excluded) {
            "Review the form exclusions."
        }
        if (excluded.isNotEmpty()) put("excludedForms", jsonStrings(excluded))
        require(pool.isBlank() == cost.isBlank()) { "Specify both the pool and its expenditure." }
        if (pool.isNotBlank()) putJsonObject("cost") { optionalId("poolId", pool); put("amount", number(cost, "Resource cost", 1..1_000_000)) }
        require(components.size <= 24 && components.map { it.id }.distinct().size == components.size) { "Review damage component identities." }
        put("components", JsonArray(components.map { it.payload() }))
        if (riders.isNotEmpty()) {
            require(resolution == "manual") { "Select manual resolution for reviewed conditional or secondary effects." }
            require(riders.all { r -> r.component.isBlank() || components.any { it.id == r.component } }) { "A conditional effect refers to a removed damage component." }
            put("riders", JsonArray(riders.map { it.payload() }))
        }
        if (revision != null) { put("expectedAttackRevision", revision); if (discardSelections) put("discardUnavailableSelections", true) }
    }

    companion object {
        fun read(record: JsonObject): CloudAttackDraft {
            val d = record.getValue("data").jsonObject
            val cost = d["cost"] as? JsonObject
            return CloudAttackDraft(record.str("id"), record.str("title"), d.str("source"), d.str("description"),
                d.str("delivery"), d.str("resolution"), d.str("timing"), d.str("classification"), d.str("ability"),
                d.str("proficient") == "true", "fixedHit" in d, d.str("fixedHit"), d.str("hitBonus"), d.str("saveAbility"),
                d.str("saveDc"), d.str("saveSuccess"), d.str("reach"), d.str("range"), d.str("longRange"), d.str("area"),
                d.str("sourceItemId"), d.str("requiresAttunement") == "true", d.str("requiresOperating") == "true", d.str("formId"),
                d.strings("excludedForms").joinToString(", "), cost.str("poolId"), cost.str("amount"),
                d.rows("components").map { c -> AttackDamageDraft(c.str("id"), c.str("name"), c.str("type"), c.str("dice"),
                    c.str("sides"), c.str("flat"), c.str("abilityBonus"), c.str("critical") != "false", c.strings("variants")) },
                d.rows("riders").map { r -> AttackRiderDraft(r.str("id"), r.str("trigger"), r.str("effect"), r.str("duration"),
                    r.str("componentId"), r.str("saveAbility"), r.str("saveDc")) },
                calculatedDc="dcAbility" in d, dcAbility=d.str("dcAbility"), dcBase=d.str("dcBase").ifBlank { "8" },
                dcProficient=d.str("dcProficient")=="true", dcBonus=d.str("dcBonus"))
        }
    }
}

@Composable private fun BuilderChoice(label: String, value: String, all: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PlayerOutlinedButton({ open = true }) { Text("$label · ${value.ifBlank { "Choose" }.let(::cloudLabel)}") }
        DropdownMenu(open, { open = false }) {
            all.forEach { v -> DropdownMenuItem(text = { Text(v.ifBlank { "None" }.let(::cloudLabel)) },
                onClick = { change(v); open = false }) }
        }
    }
}
@Composable private fun BuilderReference(label: String, id: String, records: List<JsonObject>, change: (String) -> Unit) {
    Field(label, id, change)
    if (records.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Box {
            PlayerTextButton({ open = true }) { Text("Choose $label") }
            DropdownMenu(open, { open = false }) {
                records.forEach { row -> DropdownMenuItem(text = { Text(row.str("title")) },
                    onClick = { change(row.str("id")); open = false }) }
            }
        }
    }
}
private fun <T> List<T>.move(index: Int, target: Int): List<T> = toMutableList().apply { add(target, removeAt(index)) }
@Composable internal fun CloudAttackBuilder(actorId: String, attack: JsonObject?, online: Boolean, onDismiss: () -> Unit,
    commit: (String, JsonObject) -> Unit, client: CloudCampaignClient? = null) {
    val recordId = attack.str("id")
    val initial = rememberSaveable(actorId,recordId) { Json.encodeToString(CloudAttackDraft.serializer(),
        attack?.let(CloudAttackDraft::read) ?: CloudAttackDraft(newId())) }
    val startingRevision = rememberSaveable(actorId,recordId) { attack.str("revision") }.toLongOrNull()
    val draftState = rememberReforgeDraft(Json.decodeFromString(CloudAttackDraft.serializer(), initial), CloudAttackDraft.serializer())
    var d by draftState
    var discard by rememberSaveable(actorId,recordId) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val oldSelections = (attack?.get("data") as? JsonObject).rows("selections")
    val orphaned = oldSelections.filter { o -> d.components.none { it.id == o.str("componentId") && o.str("type") in it.variants } }
    val stale = attack != null && (startingRevision == null || startingRevision != attack.str("revision").toLongOrNull() || attack.str("actorId") != actorId)
    val references = remember(actorId) { mutableStateMapOf<String,List<JsonObject>>() }
    LaunchedEffect(client, actorId) {
        val repo = client ?: return@LaunchedEffect
        for (kind in listOf("item_instance", "form", "resource_pool")) {
            try {
                val rows=mutableListOf<JsonObject>()
                var after=""
                do {
                    val page=repo.records(after, setOf(kind), if (kind=="item_instance") null else actorId)
                    rows+=page
                    after=page.lastOrNull().str("id")
                } while(page.size==50 && after.isNotEmpty())
                references[kind]=rows
            } catch (e: CancellationException) { throw e } catch (_: Exception) { references.remove(kind) }
        }
    }
    fun component(index: Int, v: AttackDamageDraft) { d=d.copy(components=d.components.toMutableList().apply { set(index,v) }) }
    fun rider(index: Int, v: AttackRiderDraft) { d=d.copy(riders=d.riders.toMutableList().apply { set(index,v) }) }
    GenesisEditorSheet(if (attack==null) "Create attack" else "Edit attack", listOf("Attack", "Resolution", "Damage", "Advanced", "Review"),
        creating=true, context=attack?.str("title")?.let { "Editing $it" } ?: "New attack", onDismiss=onDismiss,
        dirty=Json.encodeToString(CloudAttackDraft.serializer(),d)!=initial, saveLabel="Save attack",
        saveEnabled=online && !stale && draftState.canSave && (orphaned.isEmpty() || discard),
        error=if(stale) "The attack changed. Reopen its current revision before editing." else error,
        onSave={
            try {
                val input=d.payload(actorId,startingRevision,discard)
                commit(if(attack==null) "define_attack" else "update_attack",input)
                onDismiss()
            } catch(e: IllegalArgumentException) { error=e.message ?: "Review the attack." }
        }) { section, select ->
        ReforgeDraftRecovery(draftState)
        when(section) {
            0 -> {
                Field("Attack name",d.name,{d=d.copy(name=it)})
                Field("Source",d.source,{d=d.copy(source=it)})
                Field("Description",d.description,{d=d.copy(description=it)},single=false)
                Field("Classification",d.classification,{d=d.copy(classification=it)})
                BuilderChoice("Action cost",d.timing,listOf("attack","action","bonus_action","reaction","other")){d=d.copy(timing=it)}
                BuilderChoice("Delivery",d.delivery,listOf("melee","ranged","area","other")){d=d.copy(delivery=it)}
                if(d.delivery=="melee" || d.reach.isNotBlank()) Field("Reach (feet)",d.reach,{d=d.copy(reach=it)})
                if(d.delivery!="melee" || d.range.isNotBlank() || d.longRange.isNotBlank()) {
                    Field("Normal range (feet)",d.range,{d=d.copy(range=it)})
                    Field("Long range (feet)",d.longRange,{d=d.copy(longRange=it)})
                }
                Field("Area and targets",d.area,{d=d.copy(area=it)})
            }
            1 -> {
                BuilderChoice("Resolution",d.resolution,listOf("attack","save","automatic","manual")){d=d.copy(resolution=it)}
                if(d.resolution=="attack"){
                    Toggle("Fixed hit modifier",d.fixed){d=d.copy(fixed=it)}
                    if(d.fixed) Field("Hit modifier",d.hit,{d=d.copy(hit=it)})
                    else {
                        BuilderChoice("Attack ability",d.ability,abilityCodes){d=d.copy(ability=it)}
                        Toggle("Proficient",d.proficient){d=d.copy(proficient=it)}
                        Field("Additional hit bonus",d.bonus,{d=d.copy(bonus=it)})
                    }
                }
                if(d.resolution=="save"){
                    BuilderChoice("Target save",d.saveAbility,abilityCodes){d=d.copy(saveAbility=it)}
                    Toggle("Calculate save DC from this actor",d.calculatedDc){d=d.copy(calculatedDc=it)}
                    if(d.calculatedDc) {
                        Field("Base DC",d.dcBase,{d=d.copy(dcBase=it)})
                        BuilderChoice("Source ability for DC",d.dcAbility,abilityCodes){d=d.copy(dcAbility=it)}
                        Toggle("Add proficiency to DC",d.dcProficient){d=d.copy(dcProficient=it)}
                        Field("Additional DC modifier",d.dcBonus,{d=d.copy(dcBonus=it)})
                    } else Field("Save DC",d.dc,{d=d.copy(dc=it)})
                    BuilderChoice("Damage on successful save",d.onSave,listOf("","none","half","full")){d=d.copy(onSave=it)}
                }
                if(d.resolution=="manual") Text("The DM adjudicates this configured outcome.")
            }
            2 -> {
                d.components.forEachIndexed { index, c -> key(c.id) {
                    PlayerDisclosure(c.name.ifBlank { "Component ${index+1}" }, initiallyOpen=true) {
                        Field("Component name",c.name,{component(index,c.copy(name=it))})
                        BuilderChoice("Default damage type",c.type,damageKinds){component(index,c.copy(type=it))}
                        Field("Dice count",c.dice,{component(index,c.copy(dice=it))})
                        if(c.dice!="0") Field("Die size",c.sides,{component(index,c.copy(sides=it))})
                        Field("Signed flat bonus",c.flat,{component(index,c.copy(flat=it))})
                        BuilderChoice("Ability bonus",c.bonus,listOf("")+abilityCodes){component(index,c.copy(bonus=it))}
                        Toggle("Double these dice on a critical hit",c.critical){component(index,c.copy(critical=it))}
                        Toggle("Selectable damage type",c.variants.isNotEmpty()){ checked ->
                            component(index,c.copy(variants=if(checked) listOf(c.type.ifBlank { damageKinds.first() }) else emptyList()))
                        }
                        if(c.variants.isNotEmpty()) PlayerDisclosure("Allowed variants") {
                            damageKinds.forEach { type-> Toggle(cloudLabel(type),type in c.variants){ selected ->
                                component(index,c.copy(variants=if(selected)(c.variants+type).distinct() else c.variants-type))
                            } }
                        }
                        Text(c.preview)
                        Row { PlayerTextButton({d=d.copy(components=d.components.move(index,index-1))},enabled=index>0){Text("Up")}
                            PlayerTextButton({d=d.copy(components=d.components.move(index,index+1))},enabled=index<d.components.lastIndex){Text("Down")} }
                        Row { PlayerTextButton({d=d.copy(components=d.components.toMutableList().apply { add(index+1,c.copy(id=newId(),name="${c.name} copy")) })},enabled=d.components.size<24){Text("Duplicate")}
                            PlayerTextButton({d=d.copy(components=d.components.filterNot { it.id==c.id })}){Text("Remove")} }
                    }
                } }
                PlayerOutlinedButton({d=d.copy(components=d.components+AttackDamageDraft(newId()))},enabled=d.components.size<24){Text("Add component")}
                if(d.components.isNotEmpty()) Text(d.components.joinToString(" + ") { it.preview })
            }
            3 -> {
                BuilderReference("Source item",d.item,references["item_instance"].orEmpty()){d=d.copy(item=it)}
                Toggle("Requires attunement",d.attuned){d=d.copy(attuned=it)}
                Toggle("Requires operating equipment",d.operating){d=d.copy(operating=it)}
                BuilderReference("Required form",d.form,references["form"].orEmpty()){d=d.copy(form=it)}
                Field("Excluded form IDs (comma separated)",d.exclude,{d=d.copy(exclude=it)})
                BuilderReference("Resource pool",d.pool,references["resource_pool"].orEmpty()){d=d.copy(pool=it)}
                Field("Cost per attempt",d.cost,{d=d.copy(cost=it)})
                d.riders.forEachIndexed { i, r-> key(r.id) {
                    PlayerDisclosure("Conditional effect ${i+1}",initiallyOpen=true) {
                        BuilderChoice("Trigger",r.trigger,listOf("hit","failed_save","prepared_charge","custom")){rider(i,r.copy(trigger=it))}
                        Field("Effect",r.effect,{rider(i,r.copy(effect=it))},single=false)
                        Field("Duration",r.duration,{rider(i,r.copy(duration=it))})
                        BuilderChoice("Affected component",r.component,listOf("")+d.components.map { it.id }){rider(i,r.copy(component=it))}
                        BuilderChoice("Secondary save",r.saveAbility,listOf("")+abilityCodes){rider(i,r.copy(saveAbility=it))}
                        if(r.saveAbility.isNotBlank()) Field("Secondary DC",r.saveDc,{rider(i,r.copy(saveDc=it))})
                        PlayerTextButton({d=d.copy(riders=d.riders.filterNot { it.id==r.id })}){Text("Remove effect")}
                    }
                } }
                PlayerOutlinedButton({d=d.copy(riders=d.riders+AttackRiderDraft(newId()))},enabled=d.riders.size<16){Text("Add conditional effect")}
                if(d.riders.isNotEmpty()) Text("Conditional effects require manual adjudication.")
            }
            else -> {
                GenesisReviewSection("Attack",{select(0)}) {
                    GenesisReviewValue("Name",d.name);GenesisReviewValue("Source",d.source)
                    GenesisReviewValue("Timing and delivery", "${cloudLabel(d.timing)} · ${cloudLabel(d.delivery)}")
                    listOf("Description" to d.description,"Classification" to d.classification,"Reach" to d.reach,"Range" to d.range,
                        "Long range" to d.longRange,"Area" to d.area).filter { it.second.isNotBlank() }.forEach { (k,v)->GenesisReviewValue(k,v) }
                }
                GenesisReviewSection("Resolution",{select(1)}) {
                    Text(when(d.resolution) {
                        "attack" -> if(d.fixed)"1d20 + (${d.hit})" else "1d20 + ${d.ability} modifier" + (if(d.proficient)" + proficiency" else "") + d.bonus.takeIf(String::isNotBlank)?.let { " + ($it)" }.orEmpty()
                        "save" -> "${d.saveAbility} save DC " + (if(d.calculatedDc) "${d.dcBase} + ${d.dcAbility} modifier" +
                            (if(d.dcProficient)" + proficiency" else "") + d.dcBonus.takeIf(String::isNotBlank)?.let { " + ($it)" }.orEmpty()
                            else d.dc) + " · ${cloudLabel(d.onSave.ifBlank { "unresolved" })} on success"
                        else -> cloudLabel(d.resolution)
                    })
                }
                GenesisReviewSection("Damage",{select(2)}) { d.components.forEach { Text("${it.name} · ${it.preview}" + if(it.variants.isEmpty()) "" else " · ${it.variants.joinToString()}") } }
                GenesisReviewSection("Advanced",{select(3)}) {
                    if(d.pool.isNotBlank()) Text("Spend ${d.cost} from ${d.pool}")
                    if(d.item.isNotBlank()) Text("Source item ${d.item} · Attunement ${d.attuned} · Operating ${d.operating}")
                    if(d.form.isNotBlank() || d.exclude.isNotBlank()) Text("Required form ${d.form.ifBlank { "Any" }} · Excluded ${d.exclude.ifBlank { "None" }}")
                    d.riders.forEach { Text("${cloudLabel(it.trigger)} · ${it.effect} ${it.duration}") }
                }
                if(orphaned.isNotEmpty()) {
                    Text("Existing selections that this change removes")
                    orphaned.forEach { Text("${it.str("componentId")} · ${it.str("type")} · ${it.str("formId").ifBlank { "Base form" }}") }
                    Toggle("Remove these selections",discard){discard=it}
                }
            }
        }
    }
}
