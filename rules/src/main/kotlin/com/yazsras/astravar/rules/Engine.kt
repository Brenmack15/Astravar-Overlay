package com.yazsras.astravar.rules

object Engine {
    val basic = listOf(DamageType.ACID, DamageType.COLD, DamageType.FIRE, DamageType.LIGHTNING, DamageType.POISON, DamageType.THUNDER)
    fun elements(p: Profile) = basic + if(p.has(Upgrade.ADVANCED_ELEMENTS)) listOf(DamageType.RADIANT, DamageType.NECROTIC, DamageType.PSYCHIC) else emptyList()
    fun plan(s: Campaign, id: String, ability: Ability, payloadIds: List<String> = emptyList(), immediate: Template? = null,
             elements: List<DamageType> = emptyList(), direct: Boolean = false, distance: Int = 30, point: String = "", manualSlotConfirmed: Boolean = false): ShotPlan {
        require(s.onboarded) { "Confirm your profile first" }
        require(s.attuned) { "Confirm attunement before firing" }
        require(!s.postShatter && s.installation == null) { "Complete post-Shatter installation first" }
        val c = s.installed ?: error("Install a usable core first")
        require(c.usable(s.gameSeconds)) { "Installed core is ${c.readiness(s.gameSeconds)}" }
        require(payloadIds.distinct().size == payloadIds.size) { "Duplicate payload selection" }
        val payloads = payloadIds.map { id -> c.payloads.find { it.id == id } ?: error("Payload unavailable") }
        require(payloads.size + (if(immediate != null) 1 else 0) <= s.profile.releaseLimit) { "Provisional release limit exceeded" }
        payloads.forEach { require(it.template.approved) { "Payload needs approval" } }
        immediate?.let { it.validate(); require(it.approved) { "Immediate payload needs approval" }; checkSlot(s,it.castingLevel,manualSlotConfirmed) }
        require(ability != Ability.SPELL || payloads.isNotEmpty() || immediate != null) { "Select stored magic or an immediate slot" }
        require(ability !in listOf(Ability.ARCANE,Ability.ELEMENTAL) || (payloads.isEmpty() && immediate == null)) { "Use Spell-Imbued Shot for payloads" }
        if(ability == Ability.ELEMENTAL) {
            require(elements.isNotEmpty() && elements.distinct().size == elements.size && elements.all { it in Engine.elements(s.profile) }) { "Choose unlocked elements" }
            require(elements.size <= s.profile.elementCapacity) { "Selector capacity exceeded; confirm upgrade or explicit profile override" }
            if(elements.size > 1 && s.profile.elementCapacityOverride==null) require(elements.all { it in basic }) { "Dual Resonance uses two basic elements" }
        }
        val p = ShotPlan(id,s.revision,ability,c.id,s.profile,payloads,immediate,if(ability == Ability.ELEMENTAL) elements else emptyList(),direct,distance,point,manualSlotConfirmed)
        require(distance in 0..p.range) { "Outside ${p.range} ft range" }
        require(!p.blast || point.isNotBlank()) { "Declare the blast target point" }
        require(p.targetPoint.length <= 500)
        checkAction(s,p)
        return p
    }
    private fun checkAction(s: Campaign,p: ShotPlan) {
        if(!s.encounter) return
        require(!s.turn.fullAction && if(p.blast) s.turn.attacks == 0 else s.turn.attacks < 2) { "Action already spent this turn" }
        require(!p.hasSpell || s.turn.spellShots < s.profile.spellShotsPerTurn) { "Provisional spell firing limit reached; advance turn or edit policy" }
    }
    fun components(p: ShotPlan, critical: Boolean = false): List<Component> {
        val result = mutableListOf<Component>()
        if(!p.blast || p.directImpact) result += Component("direct",if(p.blast) "Direct impact" else "Weapon",TypedDice(if(critical) p.profile.base.critical() else p.profile.base,DamageType.FORCE),Portion.DIRECT)
        if(p.blast) result += Component("blast","Force blast",TypedDice(Dice(p.profile.dischargeDice * if(p.ability == Ability.SHATTER) 3 else 1,10),DamageType.FORCE),Portion.BLAST)
        if(p.ability == Ability.ELEMENTAL) p.elements.forEachIndexed { i,t -> result += Component("element$i","Selector",TypedDice(Dice(if(critical) 2 else 1,8),t),Portion.DIRECT) }
        val templates = p.selectedPayloads.map { it.template } + listOfNotNull(p.immediate)
        templates.forEachIndexed { i,t ->
            t.damage.forEachIndexed { j,d -> result += Component("payload${i}_$j",t.name,if(critical && !p.blast) d.copy(dice=d.dice.critical()) else d,if(p.blast) Portion.PAYLOAD else Portion.DIRECT) }
            result += Component("overcharge$i","${t.name} overcharge",TypedDice(Dice(t.castingLevel * if(critical && !p.blast) 2 else 1,8),t.overchargeType),if(p.blast) Portion.PAYLOAD else Portion.DIRECT)
        }
        return result
    }
    fun rollRequest(p: ShotPlan): String = buildString {
        append("Astravar ${p.ability}: ${p.actionCost}. ")
        if(!p.blast || p.directImpact) append("Roll d20 +${p.profile.attack}${if(p.disadvantage) " with disadvantage" else ""}; report hit, miss, or critical. ")
        if(p.blast) append("${p.radius} ft radius at ${p.targetPoint}; each creature makes DC ${p.profile.saveDc} Dexterity save. ")
        append(components(p).joinToString(" + ") {"${it.damage.dice} ${it.damage.type} (${it.label})"})
        append(". Tap Roll in Astravar or enter external dice manually. D&D Beyond is not synchronized.")
    }
    fun commit(s: Campaign,p: ShotPlan): Campaign {
        require(s.pending.none { it.plan.id == p.id }) { "Operation already committed" }
        require(s.revision == p.revision) { "State changed; preview again before committing" }
        val checked = plan(s,p.id,p.ability,p.selectedPayloads.map { it.id },p.immediate,p.elements,p.directImpact,p.distance,p.targetPoint,p.manualSlotConfirmed)
        require(checked == p) { "Plan changed; preview again" }
        var next = s
        p.immediate?.let { next = spendSlot(next,it.castingLevel,p.manualSlotConfirmed) }
        var core = next.core(p.coreId).copy(payloads=next.core(p.coreId).payloads.filterNot { payload -> p.selectedPayloads.any { it.id == payload.id } })
        if(p.ability == Ability.DISCHARGE) core = core.copy(exhaustedUntil=s.gameSeconds+600)
        if(p.ability == Ability.SHATTER) core = core.copy(destroyed=true,payloads=emptyList(),exhaustedUntil=null)
        next = next.replace(core).copy(pending=next.pending + PendingShot(p))
        if(p.ability == Ability.SHATTER) next=next.copy(installedId=null,postShatter=true)
        if(s.encounter) next=next.copy(turn=s.turn.copy(attacks=s.turn.attacks + if(p.blast) 0 else 1,fullAction=p.blast,spellShots=s.turn.spellShots + if(p.hasSpell) 1 else 0))
        return next
    }
    private fun checkSlot(s: Campaign,level: Int,manual: Boolean) {
        val count=s.slots[level]
        require(if(count == null) manual else count > 0) { if(count == null) "Slot count unknown; explicitly confirm expenditure" else "No level-$level slots remaining" }
    }
    private fun spendSlot(s: Campaign,level: Int,manual: Boolean): Campaign {
        checkSlot(s,level,manual)
        return if(s.slots[level] == null) s else s.copy(slots=s.slots + (level to (s.slots.getValue(level)-1)))
    }
    fun spendAction(s: Campaign,bonus: Boolean): Campaign {
        if(!s.encounter) return s
        if(bonus) { require(!s.turn.bonus) { "Bonus Action already spent" }; return s.copy(turn=s.turn.copy(bonus=true)) }
        require(!s.turn.fullAction && s.turn.attacks == 0) { "Action already spent" }
        return s.copy(turn=s.turn.copy(fullAction=true))
    }
    fun charge(s: Campaign,coreId: String,template: Template,payloadId: String,source: SourceKind,touchConfirmed: Boolean,manualSlot: Boolean = false,scrollId: String? = null,overwriteId: String? = null): Campaign {
        template.validate(); require(template.approved) { "Payload needs a ruling/approval" }
        require(touchConfirmed) { "Confirm touching the available core" }
        val core=s.core(coreId); require(!core.destroyed) { "Cannot charge a destroyed core" }
        require(s.cores.flatMap { it.payloads }.none { it.id == payloadId }) { "Duplicate payload ID" }
        require(overwriteId == null || core.payloads.any { it.id == overwriteId }) { "Choose the payload to overwrite" }
        val kept=core.payloads.filterNot { it.id == overwriteId }
        require(kept.size < s.profile.capacity) { "Core capacity reached" }
        var next=spendAction(s,true)
        if(source == SourceKind.SLOT) { require(scrollId == null); next=spendSlot(next,template.castingLevel,manualSlot) }
        else {
            val scroll=s.scrolls.find { it.id == scrollId } ?: error("Choose a confirmed scroll from inventory")
            require(scroll.templateId == template.id && scroll.quantity > 0) { "No matching scroll remaining" }
            require(scroll.documentedTemplate==null || scroll.documentedTemplate==template) { "This scroll's documented payload differs from the edited template. Restore its documented template or reconcile the scroll with your table; it cannot upgrade silently." }
            next=next.copy(scrolls=next.scrolls.map { if(it.id==scrollId) it.copy(quantity=it.quantity-1) else it })
        }
        return next.replace(core.copy(payloads=kept+Payload(payloadId,template,source,core.id,s.gameSeconds,scrollId ?: "Level ${template.castingLevel} slot")))
    }
    fun purge(s: Campaign,coreId: String,payloadId: String): Campaign {
        val c=s.core(coreId); require(!c.destroyed && c.payloads.any { it.id==payloadId }) { "Payload not available" }
        return spendAction(s,true).replace(c.copy(payloads=c.payloads.filterNot { it.id==payloadId }))
    }
    fun install(s: Campaign,coreId: String): Campaign {
        require(s.installation == null) { "An installation is already in progress" }
        val c=s.core(coreId); require(c.usable(s.gameSeconds)) { "Core is not usable" }; require(s.installedId != coreId) { "Core already installed" }
        if(s.postShatter && s.profile.replacementCost == "10 in-game minutes") return s.copy(installation=Installation(coreId,s.gameSeconds+600))
        val cost=if(s.postShatter) s.profile.replacementCost else s.profile.swapCost
        return spendAction(s,cost=="Bonus Action").copy(installedId=coreId,postShatter=false)
    }
    fun advance(s: Campaign,seconds: Long,newTurn: Boolean = false): Campaign {
        require(seconds in 0..31536000 && s.gameSeconds <= 315360000000L - seconds) { "Invalid game-time increment" }
        val next=s.copy(gameSeconds=s.gameSeconds+seconds,turn=if(newTurn) Turn(s.turn.number+1) else s.turn)
        val installation=next.installation
        return if(installation != null && next.gameSeconds >= installation.completeAt) next.copy(installedId=installation.coreId,postShatter=false,installation=null) else next
    }
    fun resolve(s: Campaign,id: String,r: Resolution): Campaign {
        val pending=s.pending.find { it.plan.id==id } ?: error("Pending shot not found")
        require(pending.result == null) { "Shot already resolved" }
        validateResult(pending.plan,r)
        return s.copy(pending=s.pending.map { if(it.plan.id==id) it.copy(result=r) else it })
    }
    fun validateResult(p: ShotPlan,r: Resolution) {
        require((!p.blast || p.directImpact) == (r.outcome != Outcome.POINT)) { "Choose the appropriate attack outcome" }
        require(r.attackTotal == null || r.attackTotal in -20..100)
        val components=components(p,r.outcome==Outcome.CRITICAL).filterNot { r.outcome==Outcome.MISS && it.portion==Portion.DIRECT }
        require(r.rolls.keys == components.map { it.id }.toSet()) { "Enter one actual result per shown component" }
        components.forEach { require(r.rolls.getValue(it.id) in it.damage.dice.minimum..it.damage.dice.maximum) { "${it.label}: result outside ${it.damage.dice.minimum}–${it.damage.dice.maximum}" } }
        require(r.targets.size <= 200 && r.targets.all { it.name.length in 1..120 && it.saved!=null } && r.targets.map { it.name }.distinct().size == r.targets.size) { "Enter each creature's actual save outcome and unique name" }
        require(r.targets.count { it.directTarget } <= 1) { "Only one direct impact target" }
        require(!p.directImpact || r.outcome==Outcome.MISS || r.targets.count {it.directTarget}==1) { "Mark the directly hit creature and enter its save separately" }
        require(p.directImpact || r.targets.none {it.directTarget}) {"Point-targeted blasts have no direct-impact target"}
        require(p.blast || r.targets.isEmpty())
    }
    fun damage(p: ShotPlan,r: Resolution,target: TargetSave? = null): Map<DamageType,Int> {
        val packets=mutableMapOf<Pair<Boolean,DamageType>,Int>()
        components(p,r.outcome==Outcome.CRITICAL).forEach { c ->
            var amount=r.rolls[c.id] ?: 0
            if(c.portion==Portion.DIRECT && (r.outcome==Outcome.MISS || (p.blast && target?.directTarget!=true))) amount=0
            if(target?.saved==true) { if(c.portion==Portion.PAYLOAD) amount=0; if(c.portion==Portion.BLAST) amount/=2 }
            val packet=(c.portion==Portion.DIRECT) to c.damage.type
            packets[packet]=(packets[packet] ?: 0)+amount
        }
        val sums=mutableMapOf<DamageType,Int>()
        packets.forEach { (packet,v) ->
            val t=packet.second
            val adjusted=when(target?.defenses?.get(t) ?: Defense.NORMAL) { Defense.NORMAL -> v; Defense.RESISTANT -> v/2; Defense.IMMUNE -> 0; Defense.VULNERABLE -> v*2 }
            sums[t]=(sums[t] ?: 0)+adjusted
        }
        return sums
    }
}
