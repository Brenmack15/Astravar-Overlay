package com.yazsras.astravar.ui

import com.yazsras.astravar.data.ReforgeNoteDraft
import org.junit.Assert.*
import org.junit.Test

class ReforgeNotesRecoveryDecisionTest {
    @Test fun restoredTextWinsItsOlderDraftDuringDebounceAndDuplicateCleanup() {
        val draft = ReforgeNoteDraft("actor", 1, "A", 10)
        assertEquals(ReforgeNotesLoadDecision("B", false, false),
            reforgeNotesLoadDecision("", 1, "B", 1, true, 10, draft))
        assertEquals(ReforgeNotesLoadDecision("B", false, true),
            reforgeNotesLoadDecision("A", 1, "B", 1, true, 10, draft))
    }
    @Test fun coldOpenUsesRecoveryButCompetingWriterAndNewerRevisionRequireReview() {
        val draft = ReforgeNoteDraft("actor", 1, "A", 10)
        assertEquals("A", reforgeNotesLoadDecision("", 1, "", 1, false, null, draft).text)
        assertTrue(reforgeNotesLoadDecision("", 1, "B", 1, true, 9, draft).conflict)
        assertTrue(reforgeNotesLoadDecision("New accepted notes", 2, "B", 1, true, 10, draft).conflict)
    }
}
