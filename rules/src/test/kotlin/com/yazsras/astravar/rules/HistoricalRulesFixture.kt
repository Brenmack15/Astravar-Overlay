package com.yazsras.astravar.rules

/** Test-only construction of pre-retirement snapshots. Production has no Shatter execution bypass. */
internal fun historicalPlan(s: Campaign,id: String,ability: Ability,payloadIds: List<String> = emptyList(),immediate: Template? = null,
    elements: List<DamageType> = emptyList(),direct: Boolean = false,distance: Int = 30,point: String = "",manualSlotConfirmed: Boolean = false): ShotPlan {
    if(ability != Ability.SHATTER) return Engine.plan(s,id,ability,payloadIds,immediate,elements,direct,distance,point,manualSlotConfirmed)
    require(distance in 0..120)
    return Engine.plan(s,id,Ability.DISCHARGE,payloadIds,immediate,elements,direct,minOf(distance,60),point,manualSlotConfirmed)
        .copy(ability=Ability.SHATTER,distance=distance,blastDiceCount=s.profile.shatterDice)
}
internal fun historicalCommit(s: Campaign,p: ShotPlan): Campaign {
    if(p.ability != Ability.SHATTER) return Engine.commit(s,p)
    val discharge=p.copy(ability=Ability.DISCHARGE,distance=minOf(p.distance,60),blastDiceCount=p.profile.dischargeDice)
    val spent=Engine.commit(s,discharge)
    return spent.replace(spent.core(p.coreId).copy(destroyed=true,exhaustedUntil=null,payloads=emptyList()))
        .copy(installedId=null,postShatter=true,pending=spent.pending.dropLast(1)+PendingShot(p))
}
internal fun historicalFire(s: Campaign,p: ShotPlan,options: RollOptions = RollOptions(),die: (Int)->Int = {1}): Campaign =
    if(p.ability != Ability.SHATTER) DiceRoller.fire(s,p,options,die) else DiceRoller.rollPending(historicalCommit(s,p),p.id,options,die)
