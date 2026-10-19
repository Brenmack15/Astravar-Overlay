package com.yazsras.astravar.ui

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class CampaignDocumentIoV21Test {
    @Test fun sizeLimitCountsUtf8BytesAndClosesRejectedInput() {
        var closed = false
        val input = object : ByteArrayInputStream("ééé".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IllegalArgumentException::class.java) { CampaignDocumentIoV21.readUtf8(input, 5) }
        assertTrue(closed)
    }

    @Test fun malformedUnicodeCannotChangeTheArchiveDuringDecode() {
        assertThrows(CharacterCodingException::class.java) {
            CampaignDocumentIoV21.readUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), 8)
        }
    }

    @Test fun exactLimitAndNonAsciiOriginalContentSurvive() {
        val original = "Owl 🦉 — Unknown HP"
        val bytes = original.toByteArray(Charsets.UTF_8)
        assertEquals(original, CampaignDocumentIoV21.readUtf8(ByteArrayInputStream(bytes), bytes.size))
        CampaignDocumentIoV21.verifySavedCopy(original, original)
        assertThrows(IllegalArgumentException::class.java) { CampaignDocumentIoV21.verifySavedCopy(original, original + " ") }
    }
}
