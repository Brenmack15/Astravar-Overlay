package com.yazsras.astravar.rules

import kotlinx.serialization.Serializable

@Serializable enum class DamageType { FORCE, ACID, COLD, FIRE, LIGHTNING, POISON, THUNDER, RADIANT, NECROTIC, PSYCHIC }
@Serializable enum class Upgrade { ADVANCED_ELEMENTS, DUAL_RESONANCE, FAST_SWAP, DUAL_STORAGE, FAST_REPLACEMENT, INSTANT_REPLACEMENT, SYNTHETIC }
@Serializable enum class SourceKind { SLOT, SCROLL }
@Serializable enum class Ability { ARCANE, ELEMENTAL, SPELL, DISCHARGE, SHATTER }
@Serializable enum class Outcome { HIT, CRITICAL, MISS, POINT, UNCONFIRMED }
@Serializable enum class Defense { NORMAL, RESISTANT, IMMUNE, VULNERABLE }
@Serializable enum class Portion { DIRECT, BLAST, PAYLOAD }
@Serializable data class Dice(val count: Int, val sides: Int, val flat: Int = 0) {
    fun validate() { require(count in 0..200 && sides in setOf(4,6,8,10,12,20,100) && flat in -100..100) { "Invalid dice" }; require(count > 0 || flat > 0) }
    fun critical() = copy(count = count * 2)
    val minimum get() = count + flat
    val maximum get() = count * sides + flat
    val average get() = count * (sides + 1) / 2.0 + flat
    override fun toString() = (if (count > 0) "${count}d$sides" else "0") + (if(flat > 0) "+$flat" else if(flat < 0) "$flat" else "")
}
@Serializable data class TypedDice(val dice: Dice, val type: DamageType)
@Serializable data class Profile(
    val name: String = "Astravar · campaign profile", val version: Int = 1,
    val character: String = "Zerg Hasma", val level: Int = 5, val intelligence: Int = 4,
    val proficiency: Int = 3, val saveDc: Int = 15, val calibration: Int = 1,
    val calibrationEnabled: Boolean = true, val proposedCalibrationProgression: Boolean = false,
    val confirmed: Set<Upgrade> = emptySet(), val capacityOverride: Int? = null,
    val elementCapacityOverride: Int? = null,
    val releaseOverride: Int? = null, val spellShotsPerTurn: Int = 1,
    val emergencySpellLevelOverride: Int? = null,
    val fastReplacementLevel: Int = 15, val syntheticGp: Int? = null, val syntheticDays: Int? = null
) {
    val bonus get() = if (!calibrationEnabled) 0 else if(proposedCalibrationProgression) { if(level >= 15) 3 else if(level >= 10) 2 else calibration } else calibration
    val attack get() = intelligence + proficiency + bonus
    val base get() = Dice(1, 10, intelligence + bonus)
    fun has(u: Upgrade) = u in confirmed && level >= when(u) {
        Upgrade.ADVANCED_ELEMENTS -> 8; Upgrade.DUAL_RESONANCE, Upgrade.FAST_SWAP -> 10
        Upgrade.DUAL_STORAGE -> 12; Upgrade.FAST_REPLACEMENT -> fastReplacementLevel
        Upgrade.INSTANT_REPLACEMENT, Upgrade.SYNTHETIC -> 20
    }
    val capacity get() = capacityOverride ?: if(has(Upgrade.DUAL_STORAGE)) 2 else 1
    val elementCapacity get() = elementCapacityOverride ?: if(has(Upgrade.DUAL_RESONANCE)) 2 else 1
    val releaseLimit get() = releaseOverride ?: if(has(Upgrade.DUAL_STORAGE)) 2 else 1
    val highestSpell get() = emergencySpellLevelOverride ?: when { level >= 17 -> 5; level >= 13 -> 4; level >= 9 -> 3; else -> 2 }
    val dischargeDice get() = 4 + 2 * highestSpell
    val swapCost get() = if(has(Upgrade.FAST_SWAP)) "Bonus Action" else "Action"
    val replacementCost get() = when { has(Upgrade.INSTANT_REPLACEMENT) -> "Bonus Action"; has(Upgrade.FAST_REPLACEMENT) -> "Action"; else -> "10 in-game minutes" }
    fun validate() {
        require(name.length in 1..120 && character.length in 1..120 && version > 0)
        require(level in 5..20 && intelligence in -5..10 && proficiency in 0..10 && saveDc in 1..40 && calibration in 0..10)
        require(capacity in 1..12 && elementCapacity in 1..9 && releaseLimit in 1..capacity && spellShotsPerTurn in 1..20 && highestSpell in 1..9 && fastReplacementLevel in 5..20)
        require(syntheticGp == null || syntheticGp in 0..1000000); require(syntheticDays == null || syntheticDays in 1..10000)
    }
}
@Serializable data class Template(
    val id: String, val name: String, val baseLevel: Int, val castingLevel: Int,
    val damage: List<TypedDice>, val overchargeType: DamageType,
    val approved: Boolean = false, val approvalNotes: String = "Needs ruling · instantaneous components only"
) {
    fun validate() { require(id.isNotBlank() && id.length <= 100 && name.length in 1..120); require(baseLevel in 1..9 && castingLevel in baseLevel..9); require(damage.size in 1..16 && approvalNotes.length <= 2000); damage.forEach { it.dice.validate() } }
}
@Serializable data class Payload(val id: String, val template: Template, val source: SourceKind, val coreId: String, val loadedAt: Long, val sourceReference: String = "")
@Serializable data class Core(val id: String, val name: String, val destroyed: Boolean = false, val exhaustedUntil: Long? = null, val payloads: List<Payload> = emptyList()) {
    fun usable(at: Long) = !destroyed && (exhaustedUntil == null || exhaustedUntil <= at)
    fun readiness(at: Long) = when { destroyed -> "Destroyed"; !usable(at) -> "Exhausted · ${exhaustedUntil!! - at}s game time"; else -> "Ready" }
}
@Serializable data class Scroll(val id: String, val templateId: String, val quantity: Int, val documentedTemplate: Template? = null)
@Serializable data class Installation(val coreId: String, val completeAt: Long)
@Serializable data class Turn(val number: Int = 1, val attacks: Int = 0, val fullAction: Boolean = false, val bonus: Boolean = false, val spellShots: Int = 0)
@Serializable data class Component(val id: String, val label: String, val damage: TypedDice, val portion: Portion)
@Serializable data class ShotPlan(
    val id: String, val revision: Long, val ability: Ability, val coreId: String, val profile: Profile,
    val selectedPayloads: List<Payload>, val immediate: Template? = null,
    val elements: List<DamageType> = emptyList(), val directImpact: Boolean = false,
    val distance: Int = 30, val targetPoint: String = "", val manualSlotConfirmed: Boolean = false
) {
    val blast get() = ability == Ability.DISCHARGE || ability == Ability.SHATTER
    val hasSpell get() = selectedPayloads.isNotEmpty() || immediate != null
    val range get() = when(ability) { Ability.DISCHARGE -> 60; Ability.SHATTER -> 120; else -> 180 }
    val radius get() = when(ability) { Ability.DISCHARGE -> 15; Ability.SHATTER -> 60; else -> 0 }
    val actionCost get() = if(blast) "Action" else "One attack within the Attack action"
    val disadvantage get() = (!blast || directImpact) && distance > 60
}
@Serializable data class TargetSave(val name: String, val saved: Boolean?, val defenses: Map<DamageType, Defense> = emptyMap(), val directTarget: Boolean = false)
@Serializable data class Resolution(val outcome: Outcome, val attackTotal: Int? = null, val rolls: Map<String, Int> = emptyMap(), val targets: List<TargetSave> = emptyList(), val provenance: String = "Player entered · manual / D&D Beyond")
@Serializable data class PendingShot(val plan: ShotPlan, val result: Resolution? = null, val rolled: ShotRoll? = null)
@Serializable data class CraftProject(val id: String, val name: String, val recipe: String, val daysWorked: Int = 0, val notes: String = "")
@Serializable data class Campaign(
    val schema: Int = 1, val revision: Long = 0, val onboarded: Boolean = false, val demo: Boolean = false,
    val profile: Profile = Profile(), val attuned: Boolean = false, val installedId: String? = null,
    val postShatter: Boolean = false, val installation: Installation? = null,
    val cores: List<Core> = emptyList(), val templates: List<Template> = emptyList(), val scrolls: List<Scroll> = emptyList(),
    val slots: Map<Int, Int> = emptyMap(), val gameSeconds: Long = 0,
    val encounter: Boolean = false, val turn: Turn = Turn(), val pending: List<PendingShot> = emptyList(),
    val projects: List<CraftProject> = emptyList(), val notes: String = ""
) {
    val installed get() = cores.find { it.id == installedId }
    fun core(id: String) = cores.find { it.id == id } ?: error("Core not found")
    fun replace(core: Core) = copy(cores = cores.map { if(it.id == core.id) core else it })
}
@Serializable data class HistoryEntry(val id: String, val label: String, val gameSeconds: Long, val before: Campaign, val after: Campaign, val origin: String = "main", val undone: Boolean = false)
@Serializable data class HistoryArchive(val id: String, val history: List<HistoryEntry>)
@Serializable data class Backup(val format: String = "astravar-backup", val schema: Int = 1, val campaign: Campaign, val history: List<HistoryEntry> = emptyList(), val archives: List<HistoryArchive> = emptyList())
