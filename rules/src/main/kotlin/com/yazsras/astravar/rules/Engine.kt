package com.yazsras.astravar.rules

object Engine {
    const val SHATTER_RETIRED = "Shatter is retired: Astravar has one permanent internal nexus. Historical rolls remain readable."
    const val CORE_SWAPS_RETIRED = "Astravar has one permanent internal nexus; spare-core installation and replacement are retired."
    fun meleeLockReason(profile: Profile): String? {
        val melee = profile.melee ?: return "Configure sourced melee values: attack bonus, Force formula, Strength, Dexterity and Intelligence modifiers."
        return when {
            !melee.approved -> "Melee configuration needs accepted source approval."
            melee.intelligenceModifier != profile.intelligence -> "Melee Intelligence and Rules & Profile Intelligence conflict; reconcile their sources before rolling."
            else -> null
        }
    }
    fun guardianLockReason(profile: Profile): String? = meleeLockReason(profile)
        ?: if(profile.melee?.guardianAttackBonus == null) "Configure the sourced ordinary Guardian attack bonus; enhanced Astravar accuracy is separate." else null
    /** Only the explicitly confirmed next weapon turn expires an unused charge. */
    fun chargeGauntlet(s: Campaign): Campaign {
        require(s.onboarded && s.attuned) { "Confirm the profile and attunement first" }
        require(!s.postShatter && s.installation == null) { "Legacy core state needs reviewed permanent-nexus adoption" }
        require(s.installed?.usable(s.gameSeconds) == true) { "Permanent nexus weapon network is unavailable" }
        require(s.gauntletCharge == null) { "A gauntlet is already charged for this turn" }
        meleeLockReason(s.profile)?.let { error(it) }
        s.profile.melee!!.validate()
        require(s.profile.melee.chargedDamageType != null) { "Charged Gauntlet damage type needs a sourced ruling; the reference establishes only STR + DEX + INT flat damage." }
        require(!s.turn.bonus) { "Bonus Action already spent this turn" }
        return s.copy(turn=s.turn.copy(bonus=true),gauntletCharge=GauntletCharge(s.profile.melee,s.turn.number))
    }
    fun needsNexusAdoption(s: Campaign): Boolean = s.onboarded && s.nexusAdoption == null &&
        (s.installed == null || s.postShatter || s.installation != null)

    /** Explicit owner-reviewed reconciliation of a legacy topology, not an in-world install or repair. */
    fun adoptPermanentNexus(s: Campaign, expectedRevision: Long, coreId: String, source: String,
        recordConfirmedExistingNexus: Boolean = false, confirmed: Boolean = false): Campaign {
        require(confirmed && source.isNotBlank() && source.length <= 2000) { "Confirm the established nexus and provide its resolved source." }
        require(coreId.length in 1..100 && coreId.isNotBlank()) { "Choose a stable nexus record ID." }
        s.nexusAdoption?.let { previous ->
            require(previous.coreId == coreId && previous.source == source &&
                previous.recordedConfirmedExistingNexus == recordConfirmedExistingNexus) { "Permanent nexus adoption is already recorded; it cannot be used to swap cores." }
            return s // Harmless identical replay; repository command IDs retain the original transaction receipt.
        }
        require(s.revision == expectedRevision) { "Weapon records changed. Review the current legacy state before adopting." }
        require(needsNexusAdoption(s)) { "This profile already has an intact active nexus; adoption cannot be used to swap cores." }
        val existing=s.cores.firstOrNull { it.id == coreId }
        val nextCores=if(recordConfirmedExistingNexus) {
            require(existing == null && s.cores.none { !it.destroyed }) { "Map an existing intact record instead; no extra core may be created." }
            // Explicit confirmation means this is an already-established intact, available nexus with an empty reservoir.
            // Nothing revives a destroyed record or restores any old payload, slot, charge or other resource.
            s.cores + Core(coreId,"Astravar internal nexus")
        } else {
            require(existing != null && !existing.destroyed) { "Choose an existing intact record. A destroyed core is never repaired by adoption." }
            s.cores
        }
        return s.copy(cores=nextCores,installedId=coreId,postShatter=false,installation=null,
            nexusAdoption=NexusAdoption(coreId,source,s.revision,s.gameSeconds,s.installedId,s.postShatter,
                s.installation,recordConfirmedExistingNexus))
    }
    val basic = listOf(DamageType.ACID, DamageType.COLD, DamageType.FIRE, DamageType.LIGHTNING, DamageType.POISON, DamageType.THUNDER)
    fun elements(p: Profile) = basic + if(p.has(Upgrade.ADVANCED_ELEMENTS)) listOf(DamageType.RADIANT, DamageType.NECROTIC, DamageType.PSYCHIC) else emptyList()
    fun plan(s: Campaign, id: String, ability: Ability, payloadIds: List<String> = emptyList(), immediate: Template? = null,
             elements: List<DamageType> = emptyList(), direct: Boolean = false, distance: Int = 30, point: String = "", manualSlotConfirmed: Boolean = false): ShotPlan {
        require(ability != Ability.SHATTER) { SHATTER_RETIRED }
        require(s.onboarded) { "Confirm your profile first" }
        s.profile.validate()
        if(ability in listOf(Ability.GAUNTLET,Ability.SPELL_GAUNTLET)) meleeLockReason(s.profile)?.let { error(it) }
        if(ability == Ability.GUARDIAN) guardianLockReason(s.profile)?.let { error(it) }
        require(s.attuned) { "Confirm attunement before firing" }
        require(!s.postShatter && s.installation == null) { "Legacy core state needs reviewed permanent-nexus adoption; no automatic replacement is permitted" }
        val c = s.installed ?: error("Permanent nexus configuration is missing; review the preserved legacy state")
        require(if(ability == Ability.GUARDIAN) !c.destroyed else c.usable(s.gameSeconds)) { "Installed core is ${c.readiness(s.gameSeconds)}" }
        require(payloadIds.distinct().size == payloadIds.size) { "Duplicate payload selection" }
        val payloads = payloadIds.map { id -> c.payloads.find { it.id == id } ?: error("Payload unavailable") }
        require(payloads.size + (if(immediate != null) 1 else 0) <= s.profile.releaseLimit) { "Provisional release limit exceeded" }
        payloads.forEach { require(it.template.approved) { "Payload needs approval" } }
        immediate?.let { it.validate(); require(it.approved) { "Immediate payload needs approval" }; checkSlot(s,it.castingLevel,manualSlotConfirmed) }
        require(ability !in listOf(Ability.SPELL,Ability.SPELL_GAUNTLET) || payloads.isNotEmpty() || immediate != null) { "Select stored magic or an immediate slot" }
        require(ability !in listOf(Ability.ARCANE,Ability.ELEMENTAL,Ability.GAUNTLET,Ability.GUARDIAN) || (payloads.isEmpty() && immediate == null)) { "Use Spell-Imbued Shot for payloads" }
        val usesSelector = ability in listOf(Ability.ELEMENTAL,Ability.GAUNTLET) || (ability in listOf(Ability.DISCHARGE,Ability.SHATTER) && s.profile.forceRulesVersion >= 2)
        if(usesSelector) {
            require((ability != Ability.ELEMENTAL || elements.isNotEmpty()) && elements.distinct().size == elements.size && elements.all { it in Engine.elements(s.profile) }) { "Choose unlocked elements" }
            require(elements.size <= s.profile.elementCapacity) { "Selector capacity exceeded; confirm upgrade or explicit profile override" }
            if(elements.size > 1 && s.profile.elementCapacityOverride==null) require(elements.all { it in basic }) { "Dual Resonance uses two basic elements" }
        }
        val p = ShotPlan(id,s.revision,ability,c.id,s.profile,payloads,immediate,if(usesSelector) elements else emptyList(),direct,distance,point,manualSlotConfirmed,if(ability in listOf(Ability.GAUNTLET,Ability.SPELL_GAUNTLET)) s.gauntletCharge else null)
        require(distance in 0..p.range) { "Outside ${p.range} ft range" }
        require(!p.blast || point.isNotBlank()) { "Declare the blast target point" }
        require(p.targetPoint.length <= 500)
        checkAction(s,p)
        return p
    }
    private fun checkAction(s: Campaign,p: ShotPlan) {
        require(!p.hasSpell || s.turn.spellShots < s.profile.spellShotsPerTurn) { "Spell firing limit reached; confirm your next weapon turn" }
        if(!s.encounter) return
        require(!s.turn.fullAction && if(p.blast) s.turn.attacks == 0 else s.turn.attacks < 2) { "Action already spent this turn" }

    }
    fun components(p: ShotPlan, critical: Boolean = false): List<Component> {
        val result = mutableListOf<Component>()
        if((!p.blast || p.directImpact) && p.ability != Ability.GUARDIAN) {
            val force = if(p.melee) p.profile.melee!!.force else p.profile.base
            result += Component("direct",if(p.melee) "Gauntlet Force" else if(p.blast) "Direct impact" else "Weapon",TypedDice(if(critical) force.critical() else force,DamageType.FORCE),Portion.DIRECT)
        }
        if(p.melee) {
            val thunder=p.profile.melee!!.thunder
            result += Component("gauntlet","Guardian Thunder",TypedDice(if(critical) thunder.critical() else thunder,DamageType.THUNDER),Portion.DIRECT)
            p.chargedGauntlet?.let { charge ->
                result += Component("charged","Charged Gauntlet",TypedDice(Dice(0,8,charge.profile.chargedBonus),charge.profile.chargedDamageType ?: error("Charged damage type is missing")),Portion.DIRECT)
            }
        }
        if(p.blast) result += Component("blast","Force blast",TypedDice(Dice(p.blastDiceCount,p.profile.primaryForceDieSides),DamageType.FORCE),Portion.BLAST)
        if(p.ability in listOf(Ability.ELEMENTAL,Ability.GAUNTLET) || (p.blast && p.profile.forceRulesVersion >= 2)) p.elements.forEachIndexed { i,t ->
            result += Component("element$i","Selector",TypedDice(if(critical && !p.blast) p.profile.selectorFor(t).critical() else p.profile.selectorFor(t),t),if(p.blast) Portion.BLAST else Portion.DIRECT)
        }
        val templates = p.selectedPayloads.map { it.template } + listOfNotNull(p.immediate)
        templates.forEachIndexed { i,t ->
            t.damage.forEachIndexed { j,d -> result += Component("payload${i}_$j",t.name,if(critical && !p.blast) d.copy(dice=d.dice.critical()) else d,if(p.blast) Portion.PAYLOAD else Portion.DIRECT) }
            result += Component("overcharge$i","${t.name} overcharge",TypedDice(Dice(t.castingLevel * if(critical && !p.blast) 2 else 1,8),t.overchargeType),if(p.blast) Portion.PAYLOAD else Portion.DIRECT)
        }
        return result
    }
    /** Upgrade previews use the same component construction as combat, without committing resources. */
    fun previewComponents(profile: Profile,ability: Ability,elements: List<DamageType>) = components(
        ShotPlan("preview",0,ability,"",profile,emptyList(),elements=elements)
    )
    fun rollRequest(p: ShotPlan): String = buildString {
        append("Astravar ${p.ability}: ${p.actionCost}. ")
        if(!p.blast || p.directImpact) append("Roll d20 +${p.attackBonus}${if(p.disadvantage) " with disadvantage" else ""}; report hit, miss, or critical. ")
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
        // SHATTER remains a readable historical enum; plan/commit can never execute it.
        next = next.replace(core).copy(pending=next.pending + PendingShot(p))
        if(p.enhancedMelee) next=next.copy(gauntletCharge=null)
        next=next.copy(turn=s.turn.copy(
            attacks=if(s.encounter) s.turn.attacks + if(p.blast) 0 else 1 else s.turn.attacks,
            fullAction=if(s.encounter) p.blast else s.turn.fullAction,
            spellShots=s.turn.spellShots + if(p.hasSpell) 1 else 0))
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
    @Suppress("UNUSED_PARAMETER") // touchConfirmed remains decode/API compatibility; the internal reservoir needs no touch ceremony.
    fun charge(s: Campaign,coreId: String,template: Template,payloadId: String,source: SourceKind,touchConfirmed: Boolean,manualSlot: Boolean = false,scrollId: String? = null,overwriteId: String? = null): Campaign {
        template.validate(); require(template.approved) { "Payload needs a ruling/approval" }
        require(coreId == s.installedId) { "Only the permanent nexus spell reservoir can be charged; spare cores are archived" }
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
    @Suppress("UNUSED_PARAMETER")
    fun install(s: Campaign,coreId: String): Campaign = error(CORE_SWAPS_RETIRED)
    fun advance(s: Campaign,seconds: Long,newTurn: Boolean = false): Campaign {
        require(seconds in 0..31536000 && s.gameSeconds <= 315360000000L - seconds) { "Invalid game-time increment" }
        val next=s.copy(gameSeconds=s.gameSeconds+seconds,turn=if(newTurn) Turn(s.turn.number+1) else s.turn)
        // Legacy pending installations remain preserved for a reviewed adoption, never silently replace the nexus.
        return next.copy(gauntletCharge=if(newTurn) null else next.gauntletCharge)
    }
    /** Player confirms their next weapon action window. No encounter counter or clock advances. */
    fun resetWeaponActions(s: Campaign) = s.copy(turn=Turn(s.turn.number),gauntletCharge=null)
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
