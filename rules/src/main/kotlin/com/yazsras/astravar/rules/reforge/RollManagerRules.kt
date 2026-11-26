package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.Serializable

@Serializable data class QueuedCheck(
    val kind: RollKind,
    val key: String,
    val label: String,
    val mode: DiceMode = DiceMode.NORMAL,
    val customFormula: String? = null,
    val primary: Boolean = true
)

@Serializable data class RollPreset(
    val id: String,
    val name: String,
    val entries: List<QueuedCheck>,
    val workshop: Boolean = false
)

/** Selection is pure. Deliberately repeated entries have their own queue position. */
object RollManagerRules {
    fun select(queue: List<QueuedCheck>, check: QueuedCheck, requested: DiceMode?): List<QueuedCheck> {
        val index = queue.indexOfFirst { it.primary && it.kind == check.kind && it.key == check.key }
        if (index < 0) {
            require(queue.size < 24) { "A batch supports up to 24 rolls." }
            return queue + check.copy(primary = true, mode = requested ?: DiceMode.NORMAL)
        }
        return if (requested == null || queue[index].mode == requested)
            queue.filterIndexed { i, _ -> i != index }
        else queue.mapIndexed { i, entry -> if (i == index) entry.copy(mode = requested) else entry }
    }

    fun move(queue: List<QueuedCheck>, from: Int, to: Int): List<QueuedCheck> {
        require(from in queue.indices && to in queue.indices)
        return queue.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    fun templates(): List<RollPreset> {
        val skills = listOf("Investigation", "Arcana", "History").map { QueuedCheck(RollKind.SKILL, it, it) }
        return listOf(
            RollPreset("template-research", "Artifact Research — Standard", skills),
            RollPreset("template-workshop", "Artifact Research — Pocket Workshop", skills.map { it.copy(mode = DiceMode.ADVANTAGE) }, true)
        )
    }

    fun validate(presets: List<RollPreset>) {
        require(presets.size <= 64 && presets.distinctBy { it.id }.size == presets.size) { "Invalid roll preset collection." }
        for (p in presets) {
            require(p.id.isNotBlank() && p.id.length <= 128 && p.name.isNotBlank() && p.name.length <= 120 && p.entries.size in 1..24)
            for (e in p.entries) {
                require(e.key.isNotBlank() && e.key.length <= 160 && e.label.isNotBlank() && e.label.length <= 120)
                require(e.kind in setOf(RollKind.SKILL, RollKind.ABILITY_CHECK, RollKind.SAVE, RollKind.INITIATIVE, RollKind.CUSTOM))
                if (e.kind == RollKind.CUSTOM) SecureDice.parse(requireNotNull(e.customFormula))
                else require(e.customFormula == null)
            }
        }
    }
}
