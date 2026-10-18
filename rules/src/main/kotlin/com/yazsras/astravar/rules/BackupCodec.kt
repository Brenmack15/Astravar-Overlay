package com.yazsras.astravar.rules

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.json.decodeFromJsonElement

/** Bounded weapon working-view compatibility codec; complete Android history backups use HistoryBackup ZIP. */
object BackupCodec {
    const val MAX_BYTES=8*1024*1024
    val json=Json { encodeDefaults=true; ignoreUnknownKeys=false; isLenient=false }
    fun encode(backup: Backup): String = json.encodeToString(HistoryRetention.backup(backup))
    fun decode(text: String): Backup = decode(text.reader())
    fun decode(reader: java.io.Reader): Backup {
        val stream=HistoryJson(reader)
        val text=stream.backupText();stream.finish()
        val decoded=json.decodeFromJsonElement<Backup>(migrateBackup(json.parseToJsonElement(text)))
        val b=HistoryRetention.backup(decoded.copy(campaign=cleanCampaign(decoded.campaign)))
        require(b.format=="astravar-backup" && b.schema==1) { "Unsupported backup format/version" }
        validate(b.campaign)
        require(b.history.map {it.id}.distinct().size==b.history.size) {"Duplicate history IDs"}
        b.history.forEach(::validateHistory)
        return b
    }
    fun validateHistory(e: HistoryEntry) {
        require(e.id.length in 1..100 && e.label.length in 1..1000 && e.origin.length<=50)
        validate(e.before);validate(e.after)
        require(e.rollUndo.size<=HistoryRetention.MAX_HISTORY_ENTRIES && e.rollUndo.all {it.id.length in 1..100})
    }
    private fun cleanCampaign(s: Campaign): Campaign = s.copy(profile=s.profile.currentForceRules(),pending=s.pending.filter {p ->
        p.plan.revision<=s.revision && s.cores.any {it.id==p.plan.coreId}
    }.takeLast(HistoryRetention.MAX_HISTORY_ENTRIES))
    /** Stream legacy arrays before constructing any campaign object. */
    fun decodeCampaignSnapshot(text: String): Campaign = decodeCampaignSnapshot(text.reader())
    fun decodeCampaignSnapshot(reader: java.io.Reader): Campaign {
        val stream=HistoryJson(reader);val text=stream.campaignText();stream.finish()
        return cleanCampaign(json.decodeFromJsonElement<Campaign>(migrateCampaign(json.parseToJsonElement(text)))).also(::validate)
    }
    fun decodeShot(text: String): PendingShot = json.decodeFromJsonElement<PendingShot>(migratePendingShot(json.parseToJsonElement(text))).also(::validateShot)

    fun decodeHistoryEntry(text: String): HistoryEntry {
        val stream=HistoryJson(text.reader());val bounded=stream.eventText();stream.finish()
        return json.decodeFromJsonElement<HistoryEntry>(migrateHistoryEntry(json.parseToJsonElement(bounded)))
    }

    private fun migrateBackup(element: JsonElement): JsonElement {
        val root=element as? JsonObject ?: return element
        val fields=root.toMutableMap()
        fields["campaign"]?.let { fields["campaign"]=migrateCampaign(it) }
        fields["history"]?.let { value ->
            if(value is JsonArray) fields["history"]=JsonArray(value.map(::migrateHistoryEntry))
        }
        return JsonObject(fields)
    }

    private fun migrateHistoryEntry(element: JsonElement): JsonElement {
        val entry=element as? JsonObject ?: return element
        val fields=entry.toMutableMap()
        fields["before"]?.let { fields["before"]=migrateCampaign(it) }
        fields["after"]?.let { fields["after"]=migrateCampaign(it) }
        return JsonObject(fields)
    }

    private fun migrateCampaign(element: JsonElement): JsonElement {
        val campaign=element as? JsonObject ?: return element
        val fields=campaign.toMutableMap()
        fields["profile"]?.let { fields["profile"]=withDamageCounts(it,2) }
        fields["pending"]?.let { value ->
            if(value is JsonArray) fields["pending"]=JsonArray(value.map(::migratePendingShot))
        }
        return JsonObject(fields)
    }

    private fun migratePendingShot(element: JsonElement): JsonElement {
        val shot=element as? JsonObject ?: return element
        val fields=shot.toMutableMap()
        fields["plan"]?.let { planElement ->
            val plan=planElement as? JsonObject
            if(plan!=null) {
                val planFields=plan.toMutableMap()
                planFields["profile"]?.let { planFields["profile"]=withDamageCounts(it,1) }
                if("blastDiceCount" !in planFields) {
                    // Before v1.3, blasts used only the level tier, even when direct dice were upgraded.
                    val profile=json.decodeFromJsonElement<Profile>(planFields.getValue("profile"))
                    val legacyLevel=profile.emergencySpellLevelOverride ?: when { profile.level >= 17 -> 5; profile.level >= 13 -> 4; profile.level >= 9 -> 3; else -> 2 }
                    val legacyDischarge=4+2*legacyLevel
                    val current=profile.forceRulesVersion==PrimaryForceRules.VERSION
                    val count=when(planFields["ability"]?.jsonPrimitive?.content) {
                        "DISCHARGE" -> if(current) profile.dischargeDice else legacyDischarge
                        "SHATTER" -> if(current) profile.shatterDice else legacyDischarge * 3
                        else -> 0
                    }
                    planFields["blastDiceCount"]=JsonPrimitive(count)
                }
                fields["plan"]=JsonObject(planFields)
            }
        }
        return JsonObject(fields)
    }

    private fun withDamageCounts(element: JsonElement,defaultCount: Int): JsonElement {
        val profile=element as? JsonObject ?: return element
        // Only missing fields migrate. Explicit upgrades and per-shot snapshots remain intact.
        val fields=profile.toMutableMap()
        if("baseDamageDiceCount" !in fields) fields["baseDamageDiceCount"]=JsonPrimitive(defaultCount)
        if("elementalSelectorDiceCount" !in fields) fields["elementalSelectorDiceCount"]=JsonPrimitive(defaultCount)
        if("forceRulesVersion" !in fields) fields["forceRulesVersion"]=JsonPrimitive(1)
        return JsonObject(fields)
    }
    fun validate(s: Campaign) {
        require(s.schema==1 && s.revision>=0 && s.gameSeconds in 0..315360000000L)
        s.profile.validate()
        s.gauntletCharge?.let { require(it.turnNumber > 0); it.profile.validate() }
        s.nexusAdoption?.let { adoption ->
            require(adoption.coreId.length in 1..100 && adoption.source.isNotBlank() && adoption.source.length <= 2000)
            require(adoption.priorRevision in 0..s.revision && adoption.recordedAtGameSeconds in 0..s.gameSeconds)
            require(s.installedId == adoption.coreId && !s.postShatter && s.installation == null &&
                s.cores.any { it.id == adoption.coreId && !it.destroyed }) { "Permanent nexus adoption no longer matches the active record." }
            require(adoption.legacyInstalledId == null || s.cores.any { it.id == adoption.legacyInstalledId })
            adoption.legacyInstallation?.let { require(it.coreId.length in 1..100 && it.completeAt in 0..315360000000L) }
        }
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
            require(s.cores.any {c -> c.id==it.plan.coreId} && it.plan.revision<=s.revision)
            validateShot(it)
        }
    }
    fun validateShot(it: PendingShot) {
            val p=it.plan; p.profile.validate()
            require(p.id.length in 1..100 && p.coreId.length in 1..100)
            require(if(p.blast) p.blastDiceCount in 1..maxOf(200,PrimaryForceRules.MAX_SHATTER_DICE) else p.blastDiceCount==0) { "Invalid saved blast dice" }
            require(p.revision>=0 && p.distance in 0..p.range && p.targetPoint.length<=500)
            require(p.selectedPayloads.size<=12 && p.elements.size<=p.profile.elementCapacity && p.elements.distinct().size==p.elements.size)
            require(p.selectedPayloads.map {it.id}.distinct().size==p.selectedPayloads.size && p.selectedPayloads.size+(if(p.immediate!=null) 1 else 0)<=p.profile.releaseLimit)
            p.immediate?.validate(); p.selectedPayloads.forEach { payload -> payload.template.validate(); require(payload.coreId==p.coreId) }
            require(p.selectedPayloads.all { payload -> payload.template.approved } && (p.immediate==null || p.immediate.approved))
            if(p.ability==Ability.ELEMENTAL) require(p.elements.isNotEmpty())
            require(p.elements.all { t -> t in Engine.elements(p.profile) })
            if(p.elements.size>1 && p.profile.elementCapacityOverride==null) require(p.elements.all { it in Engine.basic })
            if(p.ability in listOf(Ability.SPELL,Ability.SPELL_GAUNTLET)) require(p.hasSpell)
            if(p.ability in listOf(Ability.ARCANE,Ability.ELEMENTAL,Ability.GAUNTLET,Ability.GUARDIAN)) require(!p.hasSpell)
            if(p.melee) { require(p.profile.melee?.approved == true); p.profile.melee!!.validate() }
            if(p.ability == Ability.GUARDIAN) require(p.profile.melee?.guardianAttackBonus != null)
            p.chargedGauntlet?.let { charge -> require(p.enhancedMelee && charge.turnNumber > 0 && charge.profile.chargedDamageType != null); charge.profile.validate() }
            it.result?.let { r -> Engine.validateResult(p,r) }
            it.rolled?.let { r -> DiceRoller.validate(p,r) }
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
