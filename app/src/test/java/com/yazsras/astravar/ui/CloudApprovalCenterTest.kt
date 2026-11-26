package com.yazsras.astravar.ui

import com.yazsras.astravar.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudApprovalCenterTest {
    @Test fun deviceRoutesOnlyPermitBoundReadPathsAndExactOwnerHandoff() {
        val hash = "a".repeat(64)
        val binding = CloudBinding("https://synthetic.example", "qa-inbox", "device:fixture")
        val suffix = approvalViewSuffix("command-1", hash)
        assertTrue(validApprovalViewSuffix(suffix))
        assertTrue(validApprovalViewSuffix("review-inbox?limit=20&after=command-1"))
        assertFalse(validApprovalViewSuffix("approvals/command-1/refuse"))
        assertFalse(validApprovalViewSuffix("review-inbox/command-1?digest=$hash&extra=1"))
        assertFalse(validApprovalViewSuffix("review-inbox?limit=51"))
        assertThrows(IllegalArgumentException::class.java) { approvalViewSuffix("../owner", hash) }
        val uri = exactOwnerReviewPage(binding, "command-1", hash, "verify")
        assertEquals("synthetic.example", uri.host)
        assertEquals("/owner/ascension/qa-inbox", uri.path)
        assertNull(uri.rawQuery)
        assertThrows(IllegalArgumentException::class.java) {
            exactOwnerReviewPage(binding.copy(origin = "https://synthetic.example/#unexpected"), "command-1", hash, "refuse")
        }
        assertEquals("request=command-1&digest=$hash&intent=verify", uri.rawFragment)
        assertThrows(IllegalArgumentException::class.java) { exactOwnerReviewPage(binding, "command-1", hash, "approve_all") }
    }

    @Test fun staleChangesUseActualCurrentStateAndKeepUnknownDistinctFromAbsent() {
        val a = Json.parseToJsonElement("""{"data":{"hp":5,"known":null,"same":"kept"}}""")
        val b = Json.parseToJsonElement("""{"data":{"hp":3,"known":null,"same":"kept"}}""")
        val c = Json.parseToJsonElement("""{"data":{"hp":7,"new":null,"same":"kept"}}""")
        val changes = reviewFieldDeltas(a,b,c).associateBy { it.path }
        assertEquals(setOf("data.hp","data.known","data.new"), changes.keys)
        assertEquals(3, changes.getValue("data.hp").current!!.jsonPrimitive.int)
        assertEquals(7, changes.getValue("data.hp").proposed!!.jsonPrimitive.int)
        assertEquals(JsonNull, changes.getValue("data.known").submitted); assertNull(changes.getValue("data.known").proposed)
        assertNull(changes.getValue("data.new").current); assertEquals(JsonNull, changes.getValue("data.new").proposed)
    }
}
