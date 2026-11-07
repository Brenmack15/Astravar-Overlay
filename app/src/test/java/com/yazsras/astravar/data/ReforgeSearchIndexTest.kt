package com.yazsras.astravar.data

import org.junit.Assert.*
import org.junit.Test

class ReforgeSearchIndexTest {
    @Test fun titleRanksAheadOfBodyAndAllWordsMustMatch() {
        val a = ReforgeSearchDocument("a", "Item", "Cold Lantern", "A silver tool")
        val b = ReforgeSearchDocument("b", "Person", "Trader", "Sells a cold lantern")
        assertEquals(listOf("a", "b"), ReforgeSearchIndex.search(listOf(b, a), "cold lantern").map { it.document.id })
        assertTrue(ReforgeSearchIndex.search(listOf(a, b), "cold dragon").isEmpty())
    }
    @Test fun unicodeCaseAndStableIdentityArePreserved() {
        val docs = listOf(ReforgeSearchDocument("1", "Item", "ÉTOILE", ""), ReforgeSearchDocument("1", "Person", "Étoile", ""))
        assertEquals(2, ReforgeSearchIndex.search(docs, "etoile").size)
        assertTrue(ReforgeSearchIndex.search(docs, "  ").isEmpty())
        assertEquals(1, ReforgeSearchIndex.search(docs, "étoile", 1).size)
    }
}
