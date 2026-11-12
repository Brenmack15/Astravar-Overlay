package com.yazsras.astravar.ui

import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import org.junit.Assert.*
import org.junit.Test

class ReforgeSearchNavigationTest {
    private fun saved(character: ReforgeCharacter) = ReforgeSavedCharacter(character, 4, ReforgeOrigin(), 1, 2)
    private fun source(id: String, title: String, category: String = "Spell") = CampaignReferenceRecord(
        id, "fictional-campaign", "external-$id", title, category, "Pending source mechanics", "{}",
        "https://example.org/source", "r1", "fictional-content-hash", "fictional-package-hash")

    @Test fun identicalNamesAndOpaqueIdsKeepActorAndItemIdentity() {
        val first = ReforgeCharacter("actor:a", "Twin", itemDefinitions = listOf(ItemDefinition("definition", "Silver blade")),
            items = listOf(ItemInstance("b:blade", "definition")))
        val second = ReforgeCharacter("actor", "Twin", itemDefinitions = listOf(ItemDefinition("definition", "Silver blade")),
            items = listOf(ItemInstance("a:b:blade", "definition")))
        val entries = reforgeSearchCatalog(characters = listOf(saved(first), saved(second)))
        val blades = entries.filter { it.document.title == "Silver blade" }
        assertEquals(2, blades.map { it.document.id }.distinct().size)
        assertEquals(setOf(
            ReforgeSearchDestination.Character("actor:a", "Equipment", "InspectItem:b:blade"),
            ReforgeSearchDestination.Character("actor", "Equipment", "InspectItem:a:b:blade")
        ), blades.map { it.destination }.toSet())
        assertEquals(2, ReforgeSearchIndex.search(entries.map { it.document }, "Silver blade").size)
    }

    @Test fun sourceCategoryCannotTurnImportedTextIntoPlayableSpellOrItem() {
        val spell = SpellDefinition("spell", "Frost spark", 0)
        val character = ReforgeCharacter("actor", "Reader", spells = listOf(spell))
        val entries = reforgeSearchCatalog(sources = listOf(source("spell", "Frost spark")), characters = listOf(saved(character)))
        val results = ReforgeSearchIndex.search(entries.map { it.document }, "Frost spark")
        assertEquals(2, results.size)
        val reference = entries.single { it.origin == ReforgeSearchOrigin.REFERENCE }
        assertNull(reference.destination)
        assertTrue(reference.context.contains("approval not inferred"))
        assertEquals(ReforgeSearchDestination.Character("actor", "Spells", "InspectSpell:spell"),
            entries.single { it.origin == ReforgeSearchOrigin.PLAYABLE && it.document.kind == "Spell" }.destination)
        assertEquals(SpellPreparation.KNOWN, character.spells.single().preparation)
    }

    @Test fun companionSpellTargetsPinnedCompanionWhileUnrelatedNpcStaysSourceOnly() {
        val companion = ReforgeCharacter("owl", "Fictional owl", spells = listOf(SpellDefinition("glow", "Glow", 0)))
        val entries = reforgeSearchCatalog(profiles = listOf(CharacterProfile(id = "owl", name = "Fictional owl"),
            CharacterProfile(id = "shopkeeper", name = "Fictional shopkeeper")),
            companions = listOf(CompanionState(companionId = "owl", name = "Fictional owl")),
            characters = listOf(saved(companion)))
        assertEquals(ReforgeSearchDestination.Character("owl", "Spells", "InspectSpell:glow", companion = true),
            entries.single { it.document.kind == "Spell" }.destination)
        assertEquals(ReforgeSearchDestination.Companion("owl"), entries.single { it.document.kind == "Companion source" }.destination)
        assertNull(entries.single { it.sourceId == "shopkeeper" }.destination)
    }

    @Test fun unusedDefinitionIsReadableWithoutPretendingItIsOwnedEquipment() {
        val c = ReforgeCharacter("actor", "Reader", itemDefinitions = listOf(ItemDefinition("unused", "Proposed wand")))
        val entry = reforgeSearchCatalog(characters = listOf(saved(c))).single { it.sourceId == "unused" }
        assertEquals("Equipment definition", entry.document.kind)
        assertNull(entry.destination)
    }

    @Test fun archivedPlayableSheetsExposeNoGameplayRoutes() {
        val c = ReforgeCharacter("archived", "Archived actor", archived = true,
            spells = listOf(SpellDefinition("spell", "Frost spark", 0)))
        val entries = reforgeSearchCatalog(characters = listOf(saved(c)), sources = listOf(source("archived", "Original source")))
        assertEquals(1, entries.size)
        assertNull(entries.single().destination)
    }

    @Test fun internalReferenceLinksStayWithinSourceRecordsWhenPlayableIdsMatch() {
        val c = ReforgeCharacter("actor", "Reader", spells = listOf(SpellDefinition("shared", "Spell", 0)))
        val entries = reforgeSearchCatalog(characters = listOf(saved(c)),
            sources = listOf(source("from", "Source A"), source("shared", "Source B")))
        val from = entries.single { it.sourceId == "from" }
        val target = reforgeSearchLinkedEntry(entries, from, "shared")
        assertEquals("Source B", target?.document?.title)
        assertNull(target?.destination)
    }

    @Test fun pendingSearchResultsDropRemovedRecordsAndUseCurrentDestination() {
        val original = reforgeSearchCatalog(people = listOf(
            ConnectionEntity(id = "removed", name = "Amber scout"),
            ConnectionEntity(id = "retained", name = "Amber keeper")))
        val pendingHits = ReforgeSearchIndex.search(original.map { it.document }, "Amber")
        assertEquals(2, pendingHits.size)
        // The database emits a newer snapshot while background search still has its old hits.
        val current = reforgeSearchCatalog(people = listOf(ConnectionEntity(id = "retained", name = "Renamed keeper")))
        val resolved = reforgeCurrentSearchHits(pendingHits, current.associateBy { it.document.id })
        assertEquals(1, resolved.size)
        assertEquals("Renamed keeper", resolved.single().second.document.title)
        assertEquals(ReforgeSearchDestination.Connection("retained"), resolved.single().second.destination)
        assertTrue(reforgeCurrentSearchHits(pendingHits, emptyMap()).isEmpty())
    }
}
