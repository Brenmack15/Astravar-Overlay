package com.yazsras.astravar.data

import org.junit.Assert.*
import org.junit.Test

class RecordPresentationTest {
    @Test fun notionMentionBecomesReadableSafeLinkWithoutChangingSource() {
        val original = "A <mention url=\"https://example.org/source\">Known record</mention>."
        assertEquals("A [Known record](https://example.org/source).", RecordPresentation.normalize(original))
        assertTrue(original.contains("<mention"))
    }
    @Test fun encodedMentionsAndScriptsAreDecodedBeforeSanitizing() {
        assertEquals("[History](https://example.org/source)", RecordPresentation.normalize("&lt;mention url=&quot;https://example.org/source&quot;&gt;History&lt;/mention&gt;"))
        assertEquals("Safe", RecordPresentation.normalize("&lt;script&gt;alert('x')&lt;/script&gt;Safe"))
    }
    @Test fun unsupportedMentionRetainsItsHumanLabel() {
        assertEquals("A person", RecordPresentation.normalize("<mention user=\"123\">A person</mention>"))
        assertEquals("Keep label", RecordPresentation.normalize("<mention url=\"javascript:alert(1)\">Keep label</mention>"))
    }
    @Test fun noActiveHtmlSurvivesAndAllowedLinksHaveHosts() {
        assertEquals("Safe text", RecordPresentation.normalize("<script>alert('x')</script><iframe src='x'>hidden</iframe><b>Safe text</b>"))
        listOf("javascript:alert(1)", "data:text/html,a", "file:///secret", "intent://x", "https://", "https://user:password@example.org/", "https://example.org/\nnext").forEach { assertNull(it, RecordPresentation.safeLink(it)) }
        assertEquals("https://example.org/a", RecordPresentation.safeLink("https://example.org/a"))
        assertEquals("astravar://record/item/id", RecordPresentation.safeLink("astravar://record/item/id"))
        assertNull(RecordPresentation.safeLink("astravar://other/item/id"))
    }
    @Test fun npcProjectionSeparatesPlayInformationFromMetadataAndKeepsUnknownProse() {
        val raw = "Sky castle patron.\nStatus: Historical\nEra: [\"Hoard\"]\nEvidence: Confirmed\nKnown facts: Commissioned completed work.\nContinuation of fact.\nPromises/open threads: Future work remains proposed.\nOriginal record: https://example.org/npc\nExternal ID: CONN-NPC\nSource: <mention url=\"https://example.org/source\">Recovered</mention>"
        val profile = RecordPresentation.npc(raw)
        assertEquals("Sky castle patron.", profile.overview)
        assertEquals("Historical", profile.status)
        assertEquals("Hoard", profile.era)
        assertEquals("Confirmed", profile.evidence)
        assertEquals("Commissioned completed work.\nContinuation of fact.", profile.facts)
        assertEquals("Future work remains proposed.", profile.openThreads)
        assertEquals(3, profile.technical.size)
        assertTrue(profile.technical.last().second.contains("<mention"))
        assertFalse(profile.facts.contains("CONN-NPC"))
    }
    @Test fun markdownPreservesHeadingsListsChecksQuotesCodeAndTableCells() {
        val raw = "# Name\n\n**Overview** and *emphasis*.\n- Fact\n1. Step\n- [x] Complete\n- [ ] Pending\n> Quoted\n---\n| Ability | Value |\n| --- | --- |\n| STR | 8 |\n\n```\nraw code\n```"
        val blocks = RecordPresentation.blocks(raw)
        assertEquals(RecordPresentation.Block.Heading(1, "Name"), blocks.first())
        assertTrue(blocks.any { it == RecordPresentation.Block.ListItem("•", "Fact") })
        assertTrue(blocks.any { it == RecordPresentation.Block.ListItem("1.", "Step") })
        assertTrue(blocks.any { it == RecordPresentation.Block.ListItem("☑", "Complete", true) })
        assertTrue(blocks.any { it == RecordPresentation.Block.ListItem("☐", "Pending", false) })
        assertTrue(blocks.any { it == RecordPresentation.Block.Quote("Quoted") })
        assertTrue(blocks.any { it == RecordPresentation.Block.Divider })
        assertEquals(listOf(listOf("Ability", "Value"), listOf("STR", "8")), blocks.filterIsInstance<RecordPresentation.Block.Table>().single().rows)
        assertEquals("raw code", blocks.filterIsInstance<RecordPresentation.Block.Code>().single().text)
    }
    @Test fun malformedImportedMarkupDoesNotDropHumanTextOrThrow() {
        val raw = "Before <mention url='https://example.org'>Human label\nUnknown <custom>fact</custom> after."
        assertEquals("Before Human label\nUnknown fact after.", RecordPresentation.normalize(raw))
        assertEquals(1, RecordPresentation.blocks("Plain source").size)
    }
    @Test fun imageLinksAreNeverAutomaticallyFetchedAndUnsafeSchemesAreUnavailable() {
        val good = RecordPresentation.blocks("![Portrait](https://example.org/image.png)").single() as RecordPresentation.Block.Image
        assertEquals("Portrait", good.alt)
        assertEquals("https://example.org/image.png", good.url)
        val bad = RecordPresentation.blocks("![Portrait](file:///private.png)").single() as RecordPresentation.Block.Image
        assertNull(bad.url)
    }
}
