package com.yazsras.astravar.rules.reforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

class NotionTransferTest {
    private val notionUrl = "https://app.notion.com/p/11111111111111111111111111111111"
    private fun character() = ReforgeCharacter("fictional-character", "Fictional Maker")
    private fun facts() = NotionCharacterFacts(
        "Fictional Maker", "Maker", notionUrl, 11, "Gnome", "Artificer", "Armorer",
        mapOf(Stat.STR to 8, Stat.DEX to 15, Stat.CON to 14, Stat.INT to 21, Stat.WIS to 11, Stat.CHA to 12),
        baseMaxHp = 80, baselineConstitutionModifier = 2, armoredAc = 20
    )
    private fun item(id: String = "source-item-1", name: String = "Composite Armor", state: String = "Equipped", owner: String = "Maker") =
        NotionInventoryFact(id, notionUrl, name, "Armor", owner, state, "Confirmed", 1,
            acceptedNumericEffects = if (name == "Composite Armor") mapOf("STR" to 21, "CON" to 16, "ARMOR_CLASS" to 20) else emptyMap(),
            requiresAttunement = name == "Composite Armor", confirmedAttuned = name == "Composite Armor")
    private fun transfer(items: List<NotionInventoryFact> = listOf(item())) = NotionCharacterTransfer(
        NOTION_CHARACTER_TRANSFER_FORMAT, "fictional-transfer-1", "2026-11-14", facts(), items,
        listOf(NotionCurrencyFact("source-transaction-1", "Baseline", 100, "Confirmed", notionUrl),
            NotionCurrencyFact("source-transaction-2", "Expense", -20, "Confirmed", notionUrl)),
        80, walletVerified = false
    )

    @Test fun equippedApprovedCompositeChangesTemporaryStatsAndHpWithoutChangingPermanentTotals() {
        val result = planNotionCharacterTransfer(character(), transfer(),
            NotionTransferChoices(replaceConflictingStats = true))
        assertEquals(listOf("Composite Armor"), result.added)
        val sheet = CharacterCalculator.calculate(result.character)
        assertEquals(11, result.character.level)
        assertEquals(8, sheet.abilities.getValue(Stat.STR).permanent)
        assertEquals(21, sheet.abilities.getValue(Stat.STR).effective.value)
        assertEquals(14, sheet.abilities.getValue(Stat.CON).permanent)
        assertEquals(16, sheet.abilities.getValue(Stat.CON).effective.value)
        assertEquals(91, sheet.maximumHp.value)
        assertEquals(20, sheet.armorClass.value)
        assertEquals(RuleFamily.LEGACY_2014, result.character.rules.core)
        assertNull(result.character.currentHp)
        assertTrue(result.character.spellSlots.isEmpty())
        val removed = result.character.copy(items = result.character.items.map { it.copy(state = ItemState.CARRIED) })
        assertEquals(8, CharacterCalculator.calculate(removed).abilities.getValue(Stat.STR).effective.value)
        assertEquals(80, CharacterCalculator.calculate(removed).maximumHp.value)
        assertNull(removed.baseAc)
        assertTrue(CharacterCalculator.calculate(removed).armorClass.value != 20)
    }

    @Test fun donorComponentsConsumedItemsOtherOwnersAndUnknownMasksStayDeferred() {
        val input = transfer(listOf(item(), item("component-2", "Prototype", "Consumed"),
            item("other-333", "Companion Harness", "Equipped", "Companion"),
            item("unknown-4", "Unlocated Mask", "Unknown")))
        val result = planNotionCharacterTransfer(character(), input, NotionTransferChoices(replaceConflictingStats = true))
        assertEquals(1, result.added.size)
        assertEquals(3, result.deferred.size)
        assertEquals(1, result.character.items.size)
    }

    @Test fun rerunningSameDocumentDoesNotDuplicateAnExistingPhysicalItem() {
        val first = planNotionCharacterTransfer(character(), transfer(), NotionTransferChoices(replaceConflictingStats = true))
        val second = planNotionCharacterTransfer(first.character, transfer())
        assertEquals(0, second.added.size)
        assertEquals(1, second.alreadyPresent.size)
        assertEquals(1, second.character.items.size)
    }

    @Test fun existingCharacterHpSlotsAndNamedEquipmentNeverGetOverwritten() {
        val original = character().copy(level = 11, currentHp = 31, temporaryHp = 4, hpDamage = 22,
            spellSlots = mapOf(1 to 1, 2 to 0),
            itemDefinitions = listOf(ItemDefinition("existing", "Composite Armor")),
            items = listOf(ItemInstance("existing-instance", "existing", ItemState.WORN)))
        val preview = planNotionCharacterTransfer(original, transfer(), NotionTransferChoices(replaceConflictingStats = true))
        assertTrue(preview.alreadyPresent.isEmpty())
        assertTrue(preview.deferred.single().contains("source identity needs review"))
        assertEquals(1, preview.character.items.size)
        assertEquals(31, preview.character.currentHp)
        assertEquals(22, preview.character.hpDamage)
        assertEquals(mapOf(1 to 1, 2 to 0), preview.character.spellSlots)
        assertTrue(preview.warnings.any { it.contains("compare its accepted effects") })
    }

    @Test fun notionUuidSpellingsRemainOnePhysicalIdentityEvenAfterARename() {
        val dashed = "9d66ac75-fdca-4a28-9734-019aba8b5666"
        val compact = dashed.replace("-", "").uppercase()
        val first = planNotionCharacterTransfer(character(), transfer(listOf(item(id = dashed))))
        val again = planNotionCharacterTransfer(first.character, transfer(listOf(item(id = compact, name = "Renamed suit"))))
        assertTrue(again.added.isEmpty())
        assertEquals(listOf("Renamed suit"), again.alreadyPresent)
        assertEquals(first.character.items, again.character.items)
        assertFailsWith<IllegalArgumentException> {
            validateNotionCharacterTransfer(transfer(listOf(item(id = dashed), item(id = compact))))
        }
    }

    @Test fun markThreeDoesNotAliasMarkTwo() {
        val first = planNotionCharacterTransfer(character(), transfer(listOf(item(name = "Astravar Mk.II"))))
        val second = planNotionCharacterTransfer(first.character,
            transfer(listOf(item("other-item-source", "Astravar Mk.III"))))
        assertEquals(listOf("Astravar Mk.III"), second.added)
    }

    @Test fun mismatchedActorBadLedgerOrDuplicatedSourceIdentityFailsClosed() {
        assertFailsWith<IllegalArgumentException> { planNotionCharacterTransfer(character(), transfer().copy(character = facts().copy(name = "Different Actor"))) }
        assertFailsWith<IllegalArgumentException> { validateNotionCharacterTransfer(transfer().copy(provisionalGpEquivalent = 90)) }
        assertFailsWith<IllegalArgumentException> { validateNotionCharacterTransfer(transfer(listOf(item(), item()))) }
        assertFailsWith<IllegalArgumentException> { validateNotionCharacterTransfer(transfer().copy(currency = listOf(
            NotionCurrencyFact("other-event-1", "Claim", 80, "Proposed", notionUrl)))) }
    }

    @Test fun privateDocumentFormatRoundTripsWithoutChangingUnknownFields() {
        val original = transfer(listOf(item().copy(knownProperties = "Confirmed but not automated", rulesLimits = "No invented charges")))
        assertEquals(original, Json.decodeFromString<NotionCharacterTransfer>(Json.encodeToString(original)))
    }

    @Test fun optionalDestinationBindingDistinguishesSameNameCharacters() {
        val bound = transfer().let { it.copy(character = it.character.copy(targetProfileId = "different-saved-identity")) }
        assertFailsWith<IllegalArgumentException> { planNotionCharacterTransfer(character(), bound) }
        val correct = bound.copy(character = bound.character.copy(targetProfileId = character().id))
        assertEquals(character().id, planNotionCharacterTransfer(character(), correct).character.id)
    }

    @Test fun nonLatinNamesDoNotCollapseToTheSameEmptyIdentity() {
        val original = character().copy(name = "雪")
        val different = transfer().let { it.copy(character = it.character.copy(name = "火")) }
        assertFailsWith<IllegalArgumentException> { planNotionCharacterTransfer(original, different) }
        val matching = different.copy(character = different.character.copy(name = "雪"))
        assertEquals("雪", planNotionCharacterTransfer(original, matching).character.name)
    }
}
