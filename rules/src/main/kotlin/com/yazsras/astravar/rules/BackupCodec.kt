package com.yazsras.astravar.rules

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object BackupCodec {
    const val MAX_BYTES=8*1024*1024
    val json=Json { encodeDefaults=true; ignoreUnknownKeys=false; isLenient=false }
    fun encode(backup: Backup): String = json.encodeToString(backup)
    fun decode(text: String): Backup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds 8 MiB" }
        var depth=0; var quoted=false; var escaped=false
        text.forEach { c -> if(escaped) escaped=false else if(c=='\\' && quoted) escaped=true else if(c=='\"') quoted=!quoted else if(!quoted) { if(c=='{' || c=='[') { depth++; require(depth <= 64) { "JSON nesting too deep" } }; if(c=='}' || c==']') depth-- } }
        val b=json.decodeFromString<Backup>(text)
        require(b.format=="astravar-backup" && b.schema==1) { "Unsupported backup format/version" }
        validate(b.campaign)
        require(b.history.map { it.id }.distinct().size==b.history.size) { "Duplicate history IDs" }
        b.history.forEach { require(it.id.length in 1..100 && it.label.length in 1..1000 && it.origin.length <= 50); validate(it.before); validate(it.after) }
        require(b.archives.map { it.id }.distinct().size==b.archives.size)
        b.archives.forEach { archive -> require(archive.id.length in 1..100 && archive.history.map {it.id}.distinct().size==archive.history.size);archive.history.forEach {validate(it.before);validate(it.after)} }
        return b
    }
    fun validate(s: Campaign) {
        require(s.schema==1 && s.revision>=0 && s.gameSeconds in 0..315360000000L)
        s.profile.validate()
        fun ids(v: List<String>) { require(v.all { it.length in 1..100 } && v.distinct().size==v.size) { "Duplicate or invalid IDs" } }
        ids(s.cores.map { it.id }); ids(s.templates.map { it.id }); ids(s.scrolls.map { it.id }); ids(s.pending.map { it.plan.id }); ids(s.projects.map { it.id })
        ids(s.cores.flatMap { it.payloads }.map { it.id })
        s.cores.forEach { c ->
            require(c.name.length in 1..120 && c.payloads.size<=12 && (!c.destroyed || c.payloads.isEmpty()))
            require(c.exhaustedUntil==null || c.exhaustedUntil in 0..315360000000L)
            c.payloads.forEach { p -> p.template.validate(); require(p.template.approved && p.coreId==c.id && p.loadedAt in 0..s.gameSeconds && p.sourceReference.length<=500) }
        }
        require(s.installedId==null || s.cores.any { it.id==s.installedId && !it.destroyed })
        require(!s.postShatter || s.installedId==null)
        s.installation?.let { require(s.postShatter && s.installedId==null && s.cores.any { c -> c.id==it.coreId && !c.destroyed } && it.completeAt in s.gameSeconds..315360000000L) }
        s.templates.forEach { it.validate() }
        s.scrolls.forEach { require(it.quantity in 0..100000 && s.templates.any { t -> t.id==it.templateId });it.documentedTemplate?.let { t->t.validate();require(t.id==it.templateId) } }
        s.slots.forEach { (level,count) -> require(level in 1..9 && count in 0..99) }
        require(s.turn.number>0 && s.turn.attacks in 0..2 && s.turn.spellShots in 0..20 && s.notes.length<=20000)
        s.projects.forEach { require(it.daysWorked in 0..10000 && it.name.length in 1..120 && it.notes.length<=2000 && it.recipe.length in 1..4000) }
        s.pending.forEach {
            val p=it.plan; p.profile.validate()
            require(s.cores.any { c -> c.id==p.coreId } && p.revision>=0 && p.revision<=s.revision && p.distance in 0..p.range && p.targetPoint.length<=500)
            require(p.selectedPayloads.size<=12 && p.elements.size<=p.profile.elementCapacity && p.elements.distinct().size==p.elements.size)
            require(p.selectedPayloads.map {it.id}.distinct().size==p.selectedPayloads.size && p.selectedPayloads.size+(if(p.immediate!=null) 1 else 0)<=p.profile.releaseLimit)
            p.immediate?.validate(); p.selectedPayloads.forEach { payload -> payload.template.validate(); require(payload.coreId==p.coreId) }
            require(p.selectedPayloads.all { payload -> payload.template.approved } && (p.immediate==null || p.immediate.approved))
            if(p.ability==Ability.ELEMENTAL) require(p.elements.isNotEmpty() && p.elements.all { t -> t in Engine.elements(p.profile) })
            if(p.ability==Ability.SPELL) require(p.hasSpell)
            if(p.ability in listOf(Ability.ARCANE,Ability.ELEMENTAL)) require(!p.hasSpell)
            it.result?.let { r -> Engine.validateResult(p,r) }
            it.rolled?.let { r -> DiceRoller.validate(p,r) }
        }
    }
    fun session(s: Campaign): String = buildString {
        appendLine("# Astravar session${if(s.demo) " — DEMO" else ""}")
        appendLine("${s.profile.character} · Artificer ${s.profile.level} · Profile ${s.profile.name} v${s.profile.version}")
        appendLine("Game clock: ${s.gameSeconds}s · Attuned: ${s.attuned} · Installed: ${s.installed?.name ?: "None"}")
        appendLine("Post-Shatter replacement required: ${s.postShatter}")
        s.installation?.let { appendLine("Installation: ${it.coreId}, completes at game second ${it.completeAt}") }
        appendLine("\n## Core inventory")
        s.cores.forEach { c -> appendLine("- ${c.name}: ${c.readiness(s.gameSeconds)}"); c.payloads.forEach { p -> appendLine("  - ${p.template.name}, level ${p.template.castingLevel}, ${p.source}; ${p.template.damage.joinToString { "${it.dice} ${it.type}" }} +${p.template.castingLevel}d8 ${p.template.overchargeType}") } }
        appendLine("\nSlots are manually reconciled; missing levels are unknown: ${s.slots}")
        appendLine("\n## Pending operations")
        s.pending.filter { it.result==null }.forEach { appendLine("- ${it.plan.id}: ${it.plan.ability}; committed, costs already spent, results pending") }
        appendLine("\n## Notes\n${s.notes}")
    }
}
