package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/** The original source values remain available for review; narrative is never parsed into a bonus. */
@Serializable data class ReforgeLegacySnapshot(
    val profile: CharacterProfile, val abilities: List<CharacterAbility>, val sheet: PartySheetRecord?,
    val items: List<LedgerItem>, val placements: List<ItemPlacement>, val itemDetails: List<ItemDetails>,
    val itemProperties: List<ItemProperty>,
    val links: List<CampaignLink> = emptyList(),
    val externalReferences: List<ExternalRecordReference> = emptyList(),
    val referenceMetadata: List<CampaignReferenceMetadata> = emptyList(),
    val referenceRelations: List<CampaignReferenceRelation> = emptyList()
)
data class ReforgeAdoptionPreview(
    val character: ReforgeCharacter, val warnings: List<String>, val legacyFingerprint: String,
    val sourceJson: String, val sourceProfileRevision: Long
)

/** Read only this character's consistent SQLite snapshot; no complete export or remote mirror. */
suspend fun ReforgeRepository.readLegacyPreview(legacy: CampaignLedgerRepository, profileId: String): ReforgeAdoptionPreview = withContext(Dispatchers.Default) {
    checkId(profileId)
    val source = legacy.readCharacterSnapshot(profileId)
    val profile = source.profile
    val abilities = source.abilities
    val sheet = source.sheet
    val placements = source.placements
    val items = source.items
    val sourceJson = reforgeJson.encodeToString(source)
    val warnings = buildList {
        add("This is a reviewed local copy. Original campaign records and their synchronization remain separate.")
        add("Recorded AC and maximum HP are copied as baselines. Review whether equipment already contributed before adding automatic effects.")
        add("Item descriptions, spellcasting notes and proposed inventions do not activate mechanical effects.")
        add("Saving-throw and skill training, movement, senses, spells, feature uses, resources and attacks are not converted by this baseline copy. Review Training and configure these mechanics before relying on calculated rolls. The complete original sheet remains preserved.")
        if (profile.level == null) add("The original level is unknown. Confirm the level before adopting this copy; the draft starts at level 1.")
        if (profile.name.isBlank()) add("The original name is blank. Enter a name before saving this character.")
        if (abilities.any { it.magicModifier != 0 }) add("Legacy manual magical modifiers remain in the original records and advanced source snapshot. Translate them into reviewed structured effects before relying on this sheet.")
        if (items.any { it.quantity == null }) add("Items with unknown quantities remain in the original inventory and are omitted from automatic equipment until their quantities are confirmed.")
        if (sheet?.sheet?.unresolvedConflicts?.isNotEmpty() == true) add("The original sheet has unresolved conflicts. Review its advanced source details.")
        if (abilities.none { it.key == "CON" && it.score != null && it.magicModifier == 0 }) add("Baseline Constitution is unknown or has a legacy modifier. Automatic maximum HP scaling remains unavailable until confirmed.")
    }
    val knownItems = items.filter { !it.archived && it.quantity != null && it.itemState !in setOf("Sold", "Lost", "Consumed") }
    val mappedAbilities = Stat.entries.associateWith { stat -> abilities.firstOrNull { it.key == stat.name }?.score }
    val con = abilities.firstOrNull { it.key == "CON" && it.magicModifier == 0 }?.score
    val character = ReforgeCharacter(
        id = profile.id, name = profile.name, level = profile.level ?: 1,
        baseAbilities = mappedAbilities,
        baseMaxHp = profile.maximumHp, baselineConstitutionModifier = con?.let { Math.floorDiv(it - 10, 2) },
        currentHp = profile.currentHp, temporaryHp = profile.temporaryHp,
        hpDamage = if (profile.maximumHp != null && profile.currentHp != null) (profile.maximumHp - profile.currentHp).coerceAtLeast(0) else null,
        baseAc = profile.armorClass,
        itemDefinitions = knownItems.map { item -> ItemDefinition(id = item.id, name = item.title, description = item.description) },
        items = knownItems.map { item ->
            val placement = placements.firstOrNull { it.itemId == item.id }
            ItemInstance(id = item.id, definitionId = item.id, quantity = requireNotNull(item.quantity),
                state = when (placement?.state ?: item.itemState) {
                    "Equipped" -> ItemState.WORN
                    "Stored" -> ItemState.STORED
                    else -> ItemState.CARRIED
                }, operating = item.itemState != "Installed")
        }
    )
    ReforgeAdoptionPreview(character, warnings, reforgeHash(sourceJson), sourceJson, profile.revision)
}

/** Explicit review is the only adoption path; no startup routine calls it or alters original records. */
suspend fun ReforgeRepository.adopt(
    legacy: CampaignLedgerRepository, preview: ReforgeAdoptionPreview,
    confirmedCharacter: ReforgeCharacter = preview.character, commandId: String = UUID.randomUUID().toString()
): ReforgeSavedCharacter = withContext(Dispatchers.Default) {
    require(confirmedCharacter.id == preview.character.id && confirmedCharacter.revision == 0L) { "Adoption must preserve the original profile identity." }
    require(reforgeHash(preview.sourceJson) == preview.legacyFingerprint) { "The reviewed source snapshot changed." }
    val latest = readLegacyPreview(legacy, confirmedCharacter.id)
    check(latest.legacyFingerprint == preview.legacyFingerprint && latest.sourceProfileRevision == preview.sourceProfileRevision) {
        "Original campaign records changed after review. Review the latest values before adopting them."
    }
    // Copying only newly authored mechanics is allowed; original attribution remains immutable.
    val origin = ReforgeOrigin("Reviewed local campaign copy", confirmedCharacter.id, latest.legacyFingerprint,
        latest.sourceJson, latest.warnings)
    createWithOrigin(confirmedCharacter, origin, "Adopt reviewed character", commandId)
}

/** Map the engine's actual result without rerolling, losing discarded d20s or inventing outcomes. */
suspend fun ReforgeRepository.appendDiceRoll(
    profileId: String, expectedRevision: Long, label: String, result: DiceResult,
    ownership: String = "Player", commandId: String = UUID.randomUUID().toString()
): ReforgeRollRecord {
    val verified = SecureDice.manual(result.formula, result.faces.map { it.face }, result.mode).copy(manual = result.manual)
    require(verified == result) { "Dice result disagrees with its formula, mode or selected faces." }
    val component = ReforgeRollComponent(label, result.faces.map { ReforgeRollDie(it.sides, it.face, it.kept, it.sign) }, result.formula.flat)
    require(component.total == result.total) { "Dice result disagrees with its transparent breakdown." }
    return appendRoll(ReforgeRollRecord(id = commandId, profileId = profileId, characterRevision = expectedRevision,
        label = label, formula = SecureDice.format(result.formula), components = listOf(component),
        manual = result.manual, ownership = ownership), commandId)
}
