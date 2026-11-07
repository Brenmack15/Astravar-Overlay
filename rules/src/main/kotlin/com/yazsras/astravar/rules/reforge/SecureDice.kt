package com.yazsras.astravar.rules.reforge

import java.security.SecureRandom
import kotlinx.serialization.Serializable

@Serializable enum class DiceMode { NORMAL, ADVANTAGE, DISADVANTAGE }
@Serializable data class DiceFace(val sides: Int, val face: Int, val kept: Boolean = true, val sign: Int = 1)
@Serializable data class DiceResult(val formula: DiceFormula, val mode: DiceMode, val faces: List<DiceFace>, val total: Int, val manual: Boolean = false) {
    val naturalD20: Int? get() = faces.singleOrNull { it.sides==20 && it.kept && it.sign==1 }?.face
    fun breakdown(): String = faces.joinToString(" + ") { "${if(it.kept) "" else "discarded "}${if(it.sign<0) "−" else ""}${it.face} (d${it.sides})" } +
        "${if(formula.flat>=0) " + " else " − "}${kotlin.math.abs(formula.flat)} = $total"
}

/** No lifecycle or projection calls roll. Production faces come from java.security.SecureRandom. */
object SecureDice {
    private val random=SecureRandom()
    private val token=Regex("([+-]?)(?:(\\d*)[dD](\\d+)|(\\d+))")
    fun parse(expression:String):DiceFormula {
        require(expression.length in 1..500) { "Enter a dice formula, such as 2d6 + 3." }
        val input=expression.filterNot { it.isWhitespace() }
        require(input.isNotEmpty()) { "Enter a dice formula." }
        val terms=mutableListOf<DiceTerm>();var flat=0;var index=0
        token.findAll(input).forEach { match ->
            require(match.range.first==index && (index==0 || match.groupValues[1].isNotEmpty())) { "Use dice and whole numbers joined by + or −." }
            val sign=if(match.groupValues[1]=="-") -1 else 1
            if(match.groupValues[3].isNotBlank()) {
                val count=match.groupValues[2].ifBlank { "1" }.toIntOrNull() ?: error("Dice count is too large.")
                val sides=match.groupValues[3].toIntOrNull() ?: error("Die size is too large.")
                terms+=DiceTerm(count,sides,sign)
            } else {
                val value=match.groupValues[4].toIntOrNull() ?: error("Modifier is too large.")
                flat=Math.addExact(flat,Math.multiplyExact(sign,value))
            }
            index=match.range.last+1
        }
        require(index==input.length) { "Unsupported dice formula." }
        return DiceFormula(terms,flat).also(::validate)
    }
    fun validate(formula:DiceFormula) {
        require(formula.dice.size<=100 && formula.dice.sumOf { it.count.toLong() }<=200) { "A roll may contain at most 200 dice." }
        require(formula.dice.all { it.count in 1..200 && it.sides in 2..1000 && it.sign in setOf(-1,1) }) { "Use 1–200 dice with 2–1000 sides." }
        require(formula.flat in -1_000_000..1_000_000) { "Modifier is outside the supported range." }
    }
    fun roll(formula:DiceFormula,mode:DiceMode=DiceMode.NORMAL,die:(Int)->Int={ random.nextInt(it)+1 }):DiceResult = draw(formula,mode,false,die)
    fun manual(formula:DiceFormula,faces:List<Int>,mode:DiceMode=DiceMode.NORMAL):DiceResult {
        var next=0
        val result=draw(formula,mode,true) { require(next<faces.size) { "Not enough entered die faces." }; faces[next++] }
        require(next==faces.size) { "Too many entered die faces." }
        return result
    }
    private fun draw(formula:DiceFormula,mode:DiceMode,manual:Boolean,die:(Int)->Int):DiceResult {
        validate(formula)
        val d20Terms=formula.dice.count { it.sides==20 && it.sign==1 }
        require(mode==DiceMode.NORMAL || (d20Terms==1 && formula.dice.single { it.sides==20 && it.sign==1 }.count==1)) { "Advantage or Disadvantage requires exactly one positive d20." }
        val faces=mutableListOf<DiceFace>()
        fun face(sides:Int)=die(sides).also { require(it in 1..sides) { "Die face must be between 1 and $sides." } }
        formula.dice.forEach { term ->
            if(term.sides==20 && term.sign==1 && mode!=DiceMode.NORMAL) {
                val first=face(20);val second=face(20)
                val keepFirst=if(mode==DiceMode.ADVANTAGE) first>=second else first<=second
                faces+=DiceFace(20,first,keepFirst);faces+=DiceFace(20,second,!keepFirst)
            } else repeat(term.count) { faces+=DiceFace(term.sides,face(term.sides),true,term.sign) }
        }
        return DiceResult(formula,mode,faces,formula.flat+faces.filter { it.kept }.sumOf { it.face*it.sign },manual)
    }
    fun format(formula:DiceFormula):String {
        val terms=formula.dice.map { "${if(it.sign<0) "-" else "+"}${it.count}d${it.sides}" }.toMutableList()
        if(formula.flat!=0 || terms.isEmpty()) terms+="${if(formula.flat<0) "" else "+"}${formula.flat}"
        return terms.joinToString("").removePrefix("+")
    }
    fun critical(formula:DiceFormula):DiceFormula = formula.copy(dice=formula.dice.map { it.copy(count=it.count*2) }).also(::validate)
}
