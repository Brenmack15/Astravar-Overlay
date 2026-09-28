package com.yazsras.astravar.rules

import kotlinx.serialization.Serializable
import java.security.SecureRandom

@Serializable enum class RollMode { NORMAL, ADVANTAGE, DISADVANTAGE }
@Serializable data class RollOptions(val advantage: Boolean = false, val disadvantage: Boolean = false, val armorClass: Int? = null) {
    fun validate() { require(armorClass == null || armorClass in 1..100) { "Armor Class must be 1–100, or leave it blank" } }
    fun mode(p: ShotPlan): RollMode {
        val disadvantageApplies = disadvantage || p.disadvantage
        return when {
            advantage && disadvantageApplies -> RollMode.NORMAL
            advantage -> RollMode.ADVANTAGE
            disadvantageApplies -> RollMode.DISADVANTAGE
            else -> RollMode.NORMAL
        }
    }
}
@Serializable data class DieResult(val faces: List<Int>, val flat: Int) {
    val total get() = faces.sum() + flat
    fun detail(): String = (faces.joinToString(" + ").ifBlank { "0" }) + when { flat > 0 -> " + $flat"; flat < 0 -> " − ${-flat}"; else -> "" } + " = $total"
}
@Serializable data class ShotRoll(
    val options: RollOptions, val mode: RollMode, val attackFaces: List<Int>,
    val attackTotal: Int?, val suggestedOutcome: Outcome?, val damage: Map<String, DieResult>,
    val rolledAtMillis: Long = 0, val coreName: String = ""
) {
    val natural get() = when(mode) { RollMode.ADVANTAGE -> attackFaces.maxOrNull(); RollMode.DISADVANTAGE -> attackFaces.minOrNull(); RollMode.NORMAL -> attackFaces.firstOrNull() }
    val critical get() = natural == 20
    fun attackDescription(p: ShotPlan): String = if(attackTotal == null) "Point-targeted blast · no attack roll" else
        "d20 ${attackFaces.joinToString(" / ")} + ${p.profile.attack} = $attackTotal" + if(mode != RollMode.NORMAL) " · ${mode.name.lowercase()}" else ""
}

/** Explicit player rolls. No clock, renderer, service lifecycle, or retry calls this. */
object DiceRoller {
    private val random = SecureRandom()
    private fun face(sides: Int) = random.nextInt(sides) + 1

    fun roll(p: ShotPlan, options: RollOptions = RollOptions(), die: (Int)->Int = ::face, atMillis: Long = System.currentTimeMillis()): ShotRoll {
        options.validate()
        fun draw(sides: Int): Int = die(sides).also { require(it in 1..sides) { "Dice source returned an invalid face" } }
        val attack = !p.blast || p.directImpact
        val mode = options.mode(p)
        val faces = if(attack) List(if(mode == RollMode.NORMAL) 1 else 2) { draw(20) } else emptyList()
        val natural = when(mode) { RollMode.ADVANTAGE -> faces.maxOrNull(); RollMode.DISADVANTAGE -> faces.minOrNull(); RollMode.NORMAL -> faces.firstOrNull() }
        val total = natural?.plus(p.profile.attack)
        val outcome = when {
            !attack -> Outcome.POINT
            natural == 1 -> Outcome.MISS
            natural == 20 -> Outcome.CRITICAL
            options.armorClass != null -> if(total!! >= options.armorClass) Outcome.HIT else Outcome.MISS
            else -> null
        }
        val damage = Engine.components(p,natural == 20).associate { c ->
            c.id to DieResult(List(c.damage.dice.count) { draw(c.damage.dice.sides) },c.damage.dice.flat)
        }
        return ShotRoll(options,mode,faces,total,outcome,damage,atMillis).also { validate(p,it) }
    }

    fun validate(p: ShotPlan, r: ShotRoll) {
        r.options.validate()
        require(r.rolledAtMillis >= 0 && r.coreName.length <= 120)
        require(r.mode == r.options.mode(p)) { "Roll mode does not match the targeting options" }
        val attack = !p.blast || p.directImpact
        require(r.attackFaces.size == if(attack) { if(r.mode == RollMode.NORMAL) 1 else 2 } else 0)
        require(r.attackFaces.all { it in 1..20 })
        require(r.attackTotal == r.natural?.plus(p.profile.attack))
        val expected = when {
            !attack -> Outcome.POINT
            r.natural == 1 -> Outcome.MISS
            r.natural == 20 -> Outcome.CRITICAL
            r.options.armorClass != null -> if(r.attackTotal!! >= r.options.armorClass) Outcome.HIT else Outcome.MISS
            else -> null
        }
        require(r.suggestedOutcome == expected)
        val components = Engine.components(p,r.critical)
        require(r.damage.keys == components.map { it.id }.toSet())
        components.forEach { c ->
            val dice = r.damage.getValue(c.id)
            require(dice.flat == c.damage.dice.flat && dice.faces.size == c.damage.dice.count && dice.faces.all { it in 1..c.damage.dice.sides }) { "Invalid recorded damage dice" }
        }
    }

    fun resolution(p: ShotPlan,r: ShotRoll,outcome: Outcome? = r.suggestedOutcome,targets: List<TargetSave> = emptyList()): Resolution {
        validate(p,r)
        val actual = outcome ?: if(!p.blast) Outcome.UNCONFIRMED else error("Choose the direct impact outcome; the target's AC is unknown")
        require(r.suggestedOutcome == null || actual == r.suggestedOutcome) { "Use the recorded attack result; manual corrections are separate history entries" }
        require((actual == Outcome.CRITICAL) == r.critical) { "Critical damage must match the recorded natural 20" }
        val components = Engine.components(p,r.critical).filterNot { actual == Outcome.MISS && it.portion == Portion.DIRECT }
        return Resolution(actual,r.attackTotal,components.associate { it.id to r.damage.getValue(it.id).total },targets,"Astravar dice · individual faces saved").also { Engine.validateResult(p,it) }
    }

    fun fire(s: Campaign,p: ShotPlan,options: RollOptions = RollOptions(),die: (Int)->Int = ::face): Campaign =
        rollPending(Engine.commit(s,p),p.id,options,die)

    fun rollPending(s: Campaign,id: String,options: RollOptions = RollOptions(),die: (Int)->Int = ::face): Campaign {
        val pending = s.pending.find { it.plan.id == id } ?: error("Shot not found")
        require(pending.result == null && pending.rolled == null) { "This shot already has dice; use its saved result" }
        val r = roll(pending.plan,options,die).copy(coreName=s.core(pending.plan.coreId).name)
        val result = if(!pending.plan.blast) resolution(pending.plan,r) else null
        return s.copy(pending=s.pending.map { if(it.plan.id == id) it.copy(rolled=r,result=result) else it })
    }
}

val Ability.title: String get() = when(this) { Ability.ARCANE -> "Arcane shot"; Ability.ELEMENTAL -> "Elemental shot"; Ability.SPELL -> "Spell shot"; Ability.DISCHARGE -> "Discharge"; Ability.SHATTER -> "Shatter" }
fun Map<DamageType,Int>.damageText() = entries.filter { it.value != 0 }.joinToString(" + ") { "${it.value} ${it.key.name.lowercase()}" }.ifBlank { "0 damage" }

fun shotSummary(shot: PendingShot): String = buildString {
    val p=shot.plan;val r=shot.rolled
    appendLine(p.ability.title)
    r?.let {
        appendLine(it.attackDescription(p));appendLine("Natural: ${it.natural ?: "no attack"} · ${if(it.critical) "CRITICAL HIT" else if(it.natural==1) "NATURAL 1 — MISS" else if(it.options.armorClass==null) "AC unknown" else "AC ${it.options.armorClass}"}")
        Engine.components(p,it.critical).forEach {c->appendLine("${c.label}: ${c.damage.dice} [${it.damage.getValue(c.id).detail()}] ${c.damage.type.name.lowercase()}")}
        appendLine("Core: ${it.coreName.ifBlank {p.coreId}}")
    }
    appendLine("Used: ${p.actionCost}${p.immediate?.let {", level-${it.castingLevel} slot"} ?: ""}")
    if(p.selectedPayloads.isNotEmpty()) appendLine("Stored spells used: ${p.selectedPayloads.joinToString {it.template.name}}")
    if(p.ability==Ability.DISCHARGE) appendLine("Core exhausted for 600 game seconds")
    if(p.ability==Ability.SHATTER) appendLine("Core and all remaining stored spells destroyed")
    shot.result?.let {result->
        appendLine(if(result.outcome==Outcome.UNCONFIRMED) "Hit/miss not adjudicated · AC unknown" else result.outcome.name)
        if(p.blast) result.targets.forEach {appendLine("${it.name}: ${if(it.saved==true) "save passed" else "save failed"} · ${Engine.damage(p,result,it).damageText()}")}
        else appendLine("${if(result.outcome==Outcome.UNCONFIRMED) "Rolled" else "Result"}: ${Engine.damage(p,result).damageText()}")
    }
}
