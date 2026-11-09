package com.yazsras.astravar.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReforgeSearchSnippetTest {
    @Test fun excerptsShowLinkAndMentionLabelsWithoutTechnicalTargets() {
        assertEquals("Speak with the scout and read the journal.", reforgeSearchSnippet(
            "Speak with [the scout](astravar://record/connection/fictional-scout) and read " +
                "<mention url=\"https://example.org/fictional\">the journal</mention>."))
    }

    @Test fun encodedActiveMarkupIsRemovedFromSearchPreviews() {
        assertEquals("A safe known clue", reforgeSearchSnippet(
            "&lt;script&gt;ignored()&lt;/script&gt;<style>hidden</style>A <b>safe</b> known clue"))
    }

    @Test fun paragraphAndListFormattingBecomesACompactReadableExcerpt() {
        assertEquals("Fictional notes A confirmed discovery Bring silver Keep map", reforgeSearchSnippet(
            "## Fictional notes\nA **confirmed** discovery\n- Bring __silver__\n> Keep `map`"))
    }
}
