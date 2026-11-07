package com.yazsras.astravar.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class ReforgeBackup(
    val format: String = "astravar-reforge", val schema: Int = 1, val exportedAt: Long,
    val characters: List<ReforgeCharacterRow>, val changes: List<ReforgeChange>, val rolls: List<ReforgeRollRecord>,
    val drafts: List<ReforgeNoteDraft> = emptyList()
)
data class ReforgeRestorePreview(
    val valid: Boolean, val summary: String, val conflicts: List<String>, val fingerprint: String,
    val currentFingerprint: String, val backup: ReforgeBackup? = null
)

internal fun decodeReforgeBackup(text: String): ReforgeBackup {
    require(text.length in 1..REFORGE_MAX_JSON) { "Backup exceeds the supported eight-million-character limit." }
    val fields = rejectDuplicateJsonMembers(text)
    require(fields.containsAll(listOf("format", "schema", "characters", "changes", "rolls"))) { "Backup must declare its format, schema and complete record collections." }
    return reforgeJson.decodeFromString<ReforgeBackup>(text).also { validateReforgeBackup(it) }
}

internal fun validateReforgeBackup(value: ReforgeBackup) {
    require(value.format == "astravar-reforge" && value.schema == 1) { "Unsupported Reforge backup format." }
    require(value.characters.size <= 500 && value.changes.size <= 10_000 && value.rolls.size <= 10_000 && value.drafts.size <= 500) { "Backup exceeds the supported record limit." }
    fun unique(ids: List<String>) { require(ids.size == ids.toSet().size) { "Duplicate backup record identity." } }
    unique(value.characters.map { it.profileId }); unique(value.changes.map { it.commandId }); unique(value.rolls.map { it.id }); unique(value.drafts.map { it.profileId })
    val profiles = value.characters.associateBy { it.profileId }
    val rollsById = value.rolls.associateBy { it.id }
    val changesById = value.changes.associateBy { it.commandId }
    value.drafts.forEach { draft ->
        checkId(draft.profileId)
        val character = requireNotNull(profiles[draft.profileId]) { "Notes draft refers to a missing character." }
        require(draft.expectedRevision in 1..character.revision && draft.text.length <= 100_000) { "Invalid notes recovery draft." }
    }
    value.characters.forEach { row ->
        checkId(row.profileId)
        require(row.revision in 1..value.changes.size.toLong() && row.createdAt <= row.updatedAt) { "Invalid character revision or timestamps." }
        val saved = row.saved()
        require(saved.character.id == row.profileId && saved.character.revision == row.revision) { "Character identity or revision disagrees with its saved envelope." }
        validateCharacter(saved.character)
        require(row.originJson.length <= 2_000_000 && row.characterJson.length <= 1_000_000)
    }
    value.changes.forEach { event ->
        checkId(event.commandId)
        require(event.fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid immutable receipt fingerprint." }
        require(event.outcomeType in setOf("CHARACTER", "ROLL", "ACTION", "RESTORE")) { "Unsupported immutable receipt type." }
        if (event.outcomeType == "RESTORE") {
            require(event.profileId == "*" && event.priorRevision == 0L && event.resultingRevision == 0L)
        } else {
            val profile = requireNotNull(profiles[event.profileId]) { "History refers to a missing character." }
            require(event.priorRevision in 0..profile.revision && event.resultingRevision in 1..profile.revision) { "Invalid history revision." }
            val roll = reforgeChangeRoll(event)
            if (roll != null) {
                validateRoll(roll)
                require(roll.profileId == event.profileId && roll.characterRevision == event.priorRevision)
                require(rollsById[roll.id] == roll) { "Roll receipt is missing its immutable result." }
            }
            if (event.outcomeType == "ROLL") {
                require(event.priorRevision == event.resultingRevision && event.beforeJson == null)
            } else {
                val after = reforgeChangeCharacter(event)
                validateCharacter(after.character)
                require(after.character.id == event.profileId && after.revision == event.resultingRevision && after.character.revision == after.revision)
                require(event.resultingRevision == event.priorRevision + 1) { "Mechanical history has a revision gap." }
                event.beforeJson?.let {
                    val before = reforgeJson.decodeFromString<ReforgeSavedCharacter>(it)
                    validateCharacter(before.character)
                    require(before.character.id == event.profileId && before.revision == event.priorRevision && before.character.revision == before.revision)
                }
                require((event.priorRevision == 0L) == (event.beforeJson == null)) { "Mechanical history is missing its prior snapshot." }
            }
        }
    }
    val receiptRolls = value.changes.mapNotNull(::reforgeChangeRoll).groupBy { it.id }
    value.rolls.forEach { roll ->
        validateRoll(roll)
        val profile = requireNotNull(profiles[roll.profileId]) { "Roll refers to a missing character." }
        require(roll.characterRevision <= profile.revision)
        require(receiptRolls[roll.id] == listOf(roll)) { "Roll must have exactly one immutable receipt." }
        changesById[roll.id]?.let { command ->
            require(reforgeChangeRoll(command) == roll) { "Roll identity belongs to a different immutable command." }
        }
    }
    // Every character revision is explained exactly once; plain rolls do not create a new revision.
    value.characters.forEach { row ->
        val mechanical = value.changes.filter { it.profileId == row.profileId && it.outcomeType in setOf("CHARACTER", "ACTION") }
        require(mechanical.map { it.resultingRevision }.toSet().size == mechanical.size) { "Multiple history events claim the same mechanical revision." }
        require(mechanical.map { it.resultingRevision }.sorted() == (1L..row.revision).toList()) { "Character history is incomplete." }
        val last = mechanical.maxByOrNull { it.resultingRevision } ?: error("Character history is missing.")
        require(reforgeChangeCharacter(last) == row.saved()) { "Latest character state disagrees with its immutable history." }
        mechanical.sortedBy { it.resultingRevision }.zipWithNext().forEach { (before, after) ->
            require(reforgeChangeCharacter(before) == reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(after.beforeJson))) {
                "Character history snapshot chain is inconsistent."
            }
        }
    }
}

internal fun reforgeRestoreConflicts(incoming: ReforgeBackup, current: ReforgeBackup): List<String> {
    validateReforgeBackup(incoming)
    val conflicts = mutableListOf<String>()
    fun <T> compare(label: String, input: List<T>, local: List<T>, id: (T) -> String) {
        val existing = local.associateBy(id)
        input.forEach { record -> existing[id(record)]?.let { if (it != record) conflicts += "$label ${id(record)} differs from the saved record." } }
    }
    compare("Character", incoming.characters, current.characters) { it.profileId }
    compare("History", incoming.changes, current.changes) { it.commandId }
    compare("Roll", incoming.rolls, current.rolls) { it.id }
    compare("Notes draft", incoming.drafts, current.drafts) { it.profileId }
    val currentCommands = current.changes.associateBy { it.commandId }
    incoming.rolls.forEach { roll ->
        currentCommands[roll.id]?.let { command ->
            if (runCatching { reforgeChangeRoll(command) }.getOrNull() != roll)
                conflicts += "Roll identity ${roll.id} belongs to another command."
        }
    }
    val incomingCommands = incoming.changes.associateBy { it.commandId }
    current.rolls.forEach { roll ->
        incomingCommands[roll.id]?.let { command ->
            if (runCatching { reforgeChangeRoll(command) }.getOrNull() != roll)
                conflicts += "Command identity ${roll.id} belongs to an existing roll."
        }
    }
    if (conflicts.isEmpty()) {
        val merged = current.copy(
            characters = (current.characters + incoming.characters).distinctBy { it.profileId },
            changes = (current.changes + incoming.changes).distinctBy { it.commandId },
            rolls = (current.rolls + incoming.rolls).distinctBy { it.id },
            drafts = (current.drafts + incoming.drafts).distinctBy { it.profileId }
        )
        runCatching { validateReforgeBackup(merged) }.exceptionOrNull()?.let {
            conflicts += "Combined backup history is inconsistent: ${it.message ?: "review required"}"
        }
    }
    return conflicts.distinct()
}

internal fun reforgeChangeCharacter(event: ReforgeChange): ReforgeSavedCharacter = when (event.outcomeType) {
    "CHARACTER" -> reforgeJson.decodeFromString(event.afterJson)
    "ACTION" -> reforgeJson.decodeFromString<ReforgeActionOutcome>(event.afterJson).character
    else -> error("Receipt does not contain a character outcome.")
}
internal fun reforgeChangeRoll(event: ReforgeChange): ReforgeRollRecord? = when (event.outcomeType) {
    "ROLL" -> reforgeJson.decodeFromString(event.afterJson)
    "ACTION" -> reforgeJson.decodeFromString<ReforgeActionOutcome>(event.afterJson).roll
    else -> null
}
