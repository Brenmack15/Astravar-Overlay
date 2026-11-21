package com.yazsras.astravar.rules.reforge

import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

/** Optional, explicitly reviewed class spell data. A transfer contains no casts or remaining uses. */
@Serializable data class NotionSpellbook(
    val classVersion: String,
    val castingClassLevel: Int? = null,
    val spells: List<NotionSpellFact>,
    /** A confirmed row carrying the original Notion page and revision for this casting level. */
    val castingLevelSourceSpellId: String? = null
)

@Serializable data class NotionSpellFact(
    val sourceId: String, val sourceUrl: String, val sourceRevision: String, val spellKey: String,
    val evidence: String, val name: String, val level: Int, val preparation: SpellPreparation,
    val concentration: Boolean, val castingTime: String, val range: String, val components: String,
    val duration: String, val ritual: Boolean
) {
    val nativeId: String get() {
        val identity = sourceId.replace("-", "").lowercase(Locale.ROOT) + "/" + spellKey
        return "notion-spell-" + UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))
    }

    fun definition() = SpellDefinition(nativeId, name, level, preparation, concentration, castingTime,
        range, components, duration, sourceVersion = "$sourceUrl @ $sourceRevision · $spellKey",
        slotRequired = level > 0, ritual = ritual)
}

internal fun validateNotionSpellbook(book: NotionSpellbook, characterLevel: Int) {
    require(book.classVersion.isNotBlank() && book.classVersion.length <= 120)
    require(book.castingClassLevel == null || book.castingClassLevel in 1..characterLevel)
    require(book.spells.size <= 1000 && book.spells.map { it.nativeId }.distinct().size == book.spells.size) {
        "Invalid or duplicate spell source identities."
    }
    if (book.castingClassLevel != null) require(book.spells.any {
        it.nativeId == book.castingLevelSourceSpellId && it.evidence == "Confirmed"
    }) { "Casting class level needs its confirmed source row." }
    else require(book.castingLevelSourceSpellId == null)
    val uuid = Regex("(?i)(?:[0-9a-f]{32}|[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})")
    for (spell in book.spells) {
        require(uuid.matches(spell.sourceId) && spell.spellKey.matches(Regex("[a-z0-9][a-z0-9._-]{0,89}")))
        val id = spell.sourceId.replace("-", "").lowercase(Locale.ROOT)
        val url = runCatching { java.net.URI(spell.sourceUrl) }.getOrNull()
        val lastSegment = url?.path?.substringAfterLast('/')?.replace("-", "")?.lowercase(Locale.ROOT).orEmpty()
        require(url?.scheme == "https" && url.host in setOf("www.notion.so", "app.notion.com") &&
            url.userInfo == null && url.port == -1 && lastSegment.endsWith(id) && spell.sourceUrl.length <= 1024) {
            "The spell needs a link to its original Notion page."
        }
        require(spell.sourceRevision.isNotBlank() && spell.sourceRevision.length <= 100)
        require(spell.evidence in setOf("Confirmed", "Historical", "Proposed", "Disputed", "Unknown"))
        require(spell.name.isNotBlank() && spell.name.length <= 200 && spell.level in 0..9)
        require(spell.castingTime.isNotBlank() && spell.castingTime.length <= 120 && spell.range.length <= 200 &&
            spell.components.length <= 2000 && spell.duration.length <= 200)
    }
}

internal data class NotionSpellReview(
    val character: ReforgeCharacter, val added: List<String>, val present: List<String>, val deferred: List<String>
)

internal fun reviewNotionSpells(c: ReforgeCharacter, facts: NotionCharacterFacts, book: NotionSpellbook): NotionSpellReview {
    val added = mutableListOf<String>()
    val present = mutableListOf<String>()
    val deferred = mutableListOf<String>()
    if (facts.className.isBlank() || c.rules.classVersion != book.classVersion || !c.className.equals(facts.className, true) ||
        !c.subclass.equals(facts.subclass, true)) {
        return NotionSpellReview(c, added, present, listOf("Resolve the class, subclass and pinned rules version before importing any spells.") + book.spells.map { it.name })
    }
    val level = book.castingClassLevel
    if (level != null && level > c.level)
        return NotionSpellReview(c, added, present, listOf("The source casting class level exceeds the current actor level.") + book.spells.map { it.name })
    if (level != null && c.spellcastingClassLevel != null && level > c.spellcastingClassLevel)
        return NotionSpellReview(c, added, present, listOf("The source exceeds the existing casting class level. Resolve progression first.") + book.spells.map { it.name })
    var reviewedLevel = c.spellcastingClassLevel
    if (level != null && level != reviewedLevel) {
        if (reviewedLevel == null && level <= c.level) reviewedLevel = level
        else deferred += "Casting class level $level needs review. The existing level was retained."
    }
    val caster = c.copy(spellcastingClassLevel = reviewedLevel)
    val capacities = CharacterCalculator.calculate(caster).slotMaxima
    val spells = c.spells.toMutableList()
    fun comparable(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val confirmedNames = book.spells.filter { it.evidence == "Confirmed" }.groupingBy { comparable(it.name) }.eachCount()
    for (fact in book.spells) {
        val next = fact.definition()
        val previous = spells.singleOrNull { it.id == next.id }
        when {
            fact.evidence != "Confirmed" -> deferred += "${fact.name} (${fact.evidence})"
            previous == next -> present += fact.name
            previous != null -> deferred += "${fact.name} has a different saved definition or source revision."
            fact.level > 0 && (capacities[fact.level] ?: 0) <= 0 ->
                deferred += "${fact.name} has no confirmed class slot capacity at level ${fact.level}."
            confirmedNames[comparable(fact.name)] != 1 || spells.any { comparable(it.name) == comparable(fact.name) } ->
                deferred += "${fact.name} needs identity review."
            else -> { spells += next; added += fact.name }
        }
    }
    return NotionSpellReview(caster.copy(spells = spells), added, present, deferred)
}
