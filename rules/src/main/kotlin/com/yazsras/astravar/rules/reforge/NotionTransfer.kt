package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.Serializable
import java.util.Locale

/** A private, user-selected document. It is never built into Astravar or sent to a server. */
const val NOTION_CHARACTER_TRANSFER_FORMAT = "astravar-notion-character-transfer-v1"

@Serializable data class NotionCharacterFacts(
    val name: String, val ownerLabel: String, val sourceUrl: String,
    val level: Int, val species: String, val className: String, val subclass: String,
    /** Permanent totals, not the temporary values of worn magic equipment. */
    val permanentAbilities: Map<Stat, Int>,
    val baseMaxHp: Int? = null, val baselineConstitutionModifier: Int? = null,
    val armoredAc: Int? = null
)

@Serializable data class NotionInventoryFact(
    val sourceId: String, val sourceUrl: String, val name: String,
    val category: String, val holder: String, val state: String,
    val evidence: String, val quantity: Int? = null, val lastConfirmed: String? = null,
    val knownProperties: String = "", val rulesLimits: String = "",
    /** Only explicitly reviewed, accepted numeric mechanics go here. */
    val acceptedNumericEffects: Map<String, Int> = emptyMap(),
    val requiresAttunement: Boolean = false, val confirmedAttuned: Boolean = false
)

@Serializable data class NotionCurrencyFact(
    val eventId: String, val name: String, val gpEquivalentDelta: Long,
    val status: String, val sourceUrl: String, val receipt: String = ""
)

@Serializable data class NotionCharacterTransfer(
    val format: String, val transferId: String, val capturedDate: String,
    val character: NotionCharacterFacts, val inventory: List<NotionInventoryFact>,
    val currency: List<NotionCurrencyFact>,
    /** A ledger-derived number, NOT an independently verified current wallet. */
    val provisionalGpEquivalent: Long?, val walletVerified: Boolean = false
)

data class NotionTransferChoices(
    val stats: Boolean = true, val items: Boolean = true,
    val replaceConflictingStats: Boolean = false
)

data class NotionTransferPlan(
    val character: ReforgeCharacter,
    val added: List<String>, val alreadyPresent: List<String>, val deferred: List<String>,
    val warnings: List<String>, val statsChanged: Boolean
) {
    val changed: Boolean get() = statsChanged || added.isNotEmpty()
}

private fun notionName(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

private fun notionSameItem(existing: String, incoming: String): Boolean {
    val a = notionName(existing)
    val b = notionName(incoming)
    if (a == b) return true
    // Earlier handwritten sheets often abbreviate the completed composite suit.
    return a.contains("astravar") && b.contains("astravar") &&
        a.contains("mkii") && b.contains("mkii")
}

private fun notionActiveItem(fact: NotionInventoryFact, ownerLabel: String): Boolean =
    fact.evidence == "Confirmed" && fact.holder == ownerLabel && fact.quantity != null &&
        fact.quantity > 0 && fact.state in setOf("Carried", "Equipped", "Stored")

/** Validate the entire file even when only one of its categories will be applied. */
fun validateNotionCharacterTransfer(value: NotionCharacterTransfer) {
    require(value.format == NOTION_CHARACTER_TRANSFER_FORMAT && value.transferId.matches(Regex("[a-zA-Z0-9:_-]{8,120}"))) {
        "This is not an Astravar Notion character-transfer document."
    }
    val c = value.character
    require(c.name.isNotBlank() && c.name.length <= 200 && c.ownerLabel.isNotBlank() && c.ownerLabel.length <= 100)
    require(c.level in 1..20 && c.permanentAbilities.keys.containsAll(Stat.entries))
    require(c.permanentAbilities.values.all { it in 0..100 })
    require(c.baseMaxHp == null || c.baseMaxHp in 0..1_000_000)
    require(c.baselineConstitutionModifier == null || c.baselineConstitutionModifier in -100..100)
    require(c.armoredAc == null || c.armoredAc in 0..1000)
    require(value.inventory.size <= 1000 && value.currency.size <= 1000)
    require(value.inventory.map { it.sourceId }.distinct().size == value.inventory.size) { "Duplicate Notion item identities." }
    require(value.currency.map { it.eventId }.distinct().size == value.currency.size) { "Duplicate currency event identities." }
    fun urlOk(url: String) = url.startsWith("https://www.notion.so/") || url.startsWith("https://app.notion.com/")
    require(urlOk(c.sourceUrl)) { "Character source must be an original Notion URL." }
    value.inventory.forEach { item ->
        require(item.sourceId.matches(Regex("[a-zA-Z0-9_-]{8,120}")))
        require(urlOk(item.sourceUrl) && item.name.isNotBlank() && item.name.length <= 200)
        require(item.holder.length <= 100 && item.category.length <= 100)
        require(item.evidence in setOf("Confirmed", "Proposed", "Disputed", "Unknown"))
        require(item.state in setOf("Carried", "Equipped", "Stored", "Installed", "Consumed", "Sold", "Lost", "Unknown"))
        require(item.quantity == null || item.quantity in 0..1_000_000)
        require(item.knownProperties.length <= 80_000 && item.rulesLimits.length <= 80_000)
        require(item.acceptedNumericEffects.keys.all { it in setOf("STR", "DEX", "CON", "INT", "WIS", "CHA", "ARMOR_CLASS") })
        require(item.acceptedNumericEffects.values.all { it in -1000..1000 })
        require(item.acceptedNumericEffects.isEmpty() || item.evidence == "Confirmed")
        require(!item.confirmedAttuned || item.requiresAttunement)
    }
    value.currency.forEach { row ->
        require(row.eventId.length in 8..120 && row.name.length in 1..200 && urlOk(row.sourceUrl))
        require(row.status in setOf("Confirmed", "Proposed", "Disputed", "Unknown") && row.receipt.length <= 4000)
    }
    require(value.provisionalGpEquivalent == null || value.provisionalGpEquivalent in 0..1_000_000_000L)
    if (value.provisionalGpEquivalent != null) {
        require(value.currency.all { it.status == "Confirmed" }) { "Unconfirmed transactions cannot produce a calculated wallet." }
        require(value.currency.fold(0L) { a, b -> Math.addExact(a, b.gpEquivalentDelta) } == value.provisionalGpEquivalent) {
            "The ledger transactions and proposed balance do not agree."
        }
    }
}

/** No mutation: source identity and item-name conflicts remain explicit. */
fun planNotionCharacterTransfer(
    current: ReforgeCharacter, transfer: NotionCharacterTransfer,
    choices: NotionTransferChoices = NotionTransferChoices()
): NotionTransferPlan {
    validateNotionCharacterTransfer(transfer)
    require(notionName(current.name) == notionName(transfer.character.name)) {
        "The selected character does not match the Notion subject. Choose the correct saved character."
    }
    val facts = transfer.character
    val warnings = mutableListOf<String>()
    var next = current
    if (choices.stats) {
        fun changeText(label: String, currentValue: String, imported: String, apply: (String) -> Unit) {
            if (currentValue == imported) return
            if (currentValue.isBlank() || choices.replaceConflictingStats) apply(imported)
            else warnings += "$label differs; existing value preserved ($currentValue)."
        }
        changeText("Species", next.species, facts.species) { next = next.copy(species = it) }
        changeText("Class", next.className, facts.className) { next = next.copy(className = it) }
        changeText("Subclass", next.subclass, facts.subclass) { next = next.copy(subclass = it) }
        if (next.level != facts.level) {
            if (next.level > facts.level) warnings += "Current level ${next.level} exceeds this Notion checkpoint; level not reduced."
            else if (choices.replaceConflictingStats) next = next.copy(level = facts.level)
            else warnings += "Level ${next.level} differs from Notion level ${facts.level}; enable explicit replacement to use it."
        }
        val abilities = next.baseAbilities.toMutableMap()
        for (stat in Stat.entries) {
            val sourceScore = requireNotNull(facts.permanentAbilities[stat])
            val advancement = next.permanentAdvancements[stat] ?: 0
            val existingPermanent = abilities[stat]?.plus(advancement)
            if (existingPermanent == sourceScore) continue
            if (existingPermanent == null || choices.replaceConflictingStats) abilities[stat] = sourceScore - advancement
            else warnings += "$stat permanent score $existingPermanent differs from Notion $sourceScore; kept existing score."
        }
        next = next.copy(baseAbilities = abilities)
        fun updateNumber(label: String, original: Int?, desired: Int?, apply: (Int) -> Unit) {
            if (desired == null || original == desired) return
            if (original == null || choices.replaceConflictingStats) apply(desired)
            else warnings += "$label differs ($original versus Notion $desired); kept existing value."
        }
        updateNumber("HP baseline", next.baseMaxHp, facts.baseMaxHp) { next = next.copy(baseMaxHp = it) }
        updateNumber("HP baseline Constitution", next.baselineConstitutionModifier, facts.baselineConstitutionModifier) {
            next = next.copy(baselineConstitutionModifier = it)
        }
        // An armored AC is not an unarmored baseline: copying 20 into baseAc would leave
        // the character at AC 20 even after removing the imported suit.
        if (facts.armoredAc != null) warnings +=
            "Notion AC ${facts.armoredAc} is a worn-armor total, not a permanent base AC; it applies only if a matching equipped item has an accepted AC effect."
        if (next.rules.classVersion == "unconfigured" && facts.className.equals("Artificer", true)) {
            next = next.copy(rules = next.rules.copy(core = RuleFamily.LEGACY_2014,
                classVersion = "legacy-artificer-infusions-2020"))
        }
        if (next.spellcastingClassLevel == null && facts.className.equals("Artificer", true)) {
            next = next.copy(spellcastingClassLevel = next.level)
        }
    }
    val added = mutableListOf<String>()
    val present = mutableListOf<String>()
    val deferred = mutableListOf<String>()
    if (choices.items) {
        val definitions = next.itemDefinitions.toMutableList()
        val instances = next.items.toMutableList()
        for (item in transfer.inventory) {
            if (!notionActiveItem(item, facts.ownerLabel)) {
                deferred += "${item.name} (${item.state}; ${item.holder}; ${item.evidence})"
                continue
            }
            val instanceId = "notion-instance-${item.sourceId}"
            val definitionId = "notion-definition-${item.sourceId}"
            if (instances.any { it.id == instanceId } || definitions.any { notionSameItem(it.name, item.name) }) {
                present += item.name
                if (item.acceptedNumericEffects.isNotEmpty()) warnings += "${item.name}: an existing suit was preserved; compare its accepted effects before changing it."
                continue
            }
            val description = buildString {
                append("Notion source: ${item.sourceUrl}\nStatus: ${item.evidence}; ${item.holder} / ${item.state}")
                item.lastConfirmed?.let { append("; checked $it") }
                append("\nCategory: ${item.category}")
                if (item.knownProperties.isNotBlank()) append("\nKnown: ${item.knownProperties}")
                if (item.rulesLimits.isNotBlank()) append("\nLimits: ${item.rulesLimits}")
            }
            val effects = item.acceptedNumericEffects.map { (key, value) ->
                val target = if (key == "ARMOR_CLASS") NumericTarget.ARMOR_CLASS else NumericTarget.ABILITY
                EffectDefinition(
                    id = "notion-effect-${item.sourceId}-$key", name = "${item.name}: $key",
                    source = RuleSource("notion:${item.sourceId}", item.sourceUrl, Acceptance.ACCEPTED_HOMEBREW),
                    payload = EffectPayload.Number(target, NumberOperation.SET, value, if (target == NumericTarget.ABILITY) key else ""),
                    requiresAttunement = item.requiresAttunement
                )
            }
            val attunementCost = instances.filter { it.attuned }.sumOf { held ->
                definitions.firstOrNull { it.id == held.definitionId }?.attunementCost ?: 0
            }
            val attuned = item.confirmedAttuned && attunementCost < next.attunementLimit
            if (item.confirmedAttuned && !attuned) warnings += "${item.name} was added unattuned: the current attunement limit is full."
            val state = when (item.state) {
                "Equipped" -> ItemState.WORN
                "Stored" -> ItemState.STORED
                else -> ItemState.CARRIED
            }
            definitions += ItemDefinition(definitionId, item.name, effects, requiresAttunement = item.requiresAttunement,
                description = description)
            instances += ItemInstance(instanceId, definitionId, state, attuned, requireNotNull(item.quantity))
            added += item.name
        }
        next = next.copy(itemDefinitions = definitions, items = instances)
    }
    CharacterCalculator.validate(next)
    return NotionTransferPlan(next, added, present, deferred, warnings, next.copy(itemDefinitions = current.itemDefinitions,
        items = current.items) != current)
}
