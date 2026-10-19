package com.yazsras.astravar.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
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

    @Test fun chunkBoundariesPreserveUnicodeAndStreamingHashMatchesOriginalUtf8Bytes() {
        val original = "a".repeat(8191) + "🦉Ω" + "b".repeat(8191) + "é"
        val expected = original.toByteArray(Charsets.UTF_8)
        val saved = ByteArrayOutputStream()
        var largestWrite = 0
        CampaignDocumentIoV21.writeUtf8(original, object : OutputStream() {
            override fun write(value: Int) { saved.write(value) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                largestWrite = maxOf(largestWrite, count); saved.write(bytes, offset, count)
            }
        })
        assertTrue("A provider write must use bounded scratch buffers", largestWrite <= 8192)
        assertArrayEquals(expected, saved.toByteArray())
        assertEquals(expected.size.toLong(), CampaignDocumentIoV21.utf8Size(original))
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(expected)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expectedHash, CampaignDocumentIoV21.sha256(original))
        assertEquals(original, CampaignDocumentIoV21.readUtf8(ByteArrayInputStream(expected), expected.size))
        CampaignDocumentIoV21.verifySavedUtf8(original, ByteArrayInputStream(expected), expected.size)
    }

    @Test fun independentProviderReadbackRejectsTamperingOversizeAndClosesEvenZeroLengthReads() {
        val original = "Private fictional source Ω"
        var closed = false
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(original.toByteArray(Charsets.UTF_8))
            override fun read(bytes: ByteArray, offset: Int, count: Int) = 0
            override fun read() = delegate.read()
            override fun close() { closed = true; delegate.close() }
        }
        CampaignDocumentIoV21.verifySavedUtf8(original, input)
        assertTrue(closed)
        assertThrows(IllegalArgumentException::class.java) {
            CampaignDocumentIoV21.verifySavedUtf8(original, ByteArrayInputStream((original + " ").toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CampaignDocumentIoV21.verifySavedUtf8(original, ByteArrayInputStream(original.toByteArray()), 4)
        }
        assertThrows(CharacterCodingException::class.java) {
            CampaignDocumentIoV21.readUtf8(ByteArrayInputStream("a".repeat(8191).toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte())), 9000)
        }
    }

    @Test fun shortAndZeroLengthProviderReadsKeepAnExactBoundedArchive() {
        val original = ("Fictional Ω🦉 ").repeat(1800)
        val bytes = original.toByteArray(Charsets.UTF_8)
        val input = object : ByteArrayInputStream(bytes) {
            private var reads = 0
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
                if (++reads % 3 == 0) 0 else super.read(buffer, offset, minOf(1, count))
        }
        assertEquals(original, CampaignDocumentIoV21.readUtf8(input, bytes.size))
    }
}
