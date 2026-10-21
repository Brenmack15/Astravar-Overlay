package com.yazsras.astravar.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult
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

    @Test fun largeAsciiTransferOnlyPresentsBoundedArrayBackedInputToTheEncoder() {
        val original = "a".repeat(21 * 1024 * 1024)
        val delegate = Charsets.UTF_8.newEncoder()
        var encodeCalls = 0
        var consumed = 0
        var flushCalls = 0
        val encoder = object : CharsetEncoder(Charsets.UTF_8, 1f, 3f) {
            override fun encodeLoop(input: CharBuffer, output: ByteBuffer): CoderResult {
                assertTrue("ICU must receive an array-backed input window", input.hasArray())
                assertTrue("The input backing array must stay bounded", input.array().size <= 8192)
                assertTrue("No encoder call may see the remaining whole document", input.remaining() <= 8192)
                encodeCalls++
                val start = input.position()
                return delegate.encode(input, output, false).also { consumed += input.position() - start }
            }
            override fun implFlush(output: ByteBuffer): CoderResult {
                flushCalls++
                val finalInput = delegate.encode(CharBuffer.allocate(0), output, true)
                return if (finalInput.isUnderflow) delegate.flush(output) else finalInput
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        CampaignDocumentIoV21.utf8(original, encoder) { bytes, count ->
            assertTrue(count in 1..8192)
            digest.update(bytes, 0, count)
            written += count
        }
        assertTrue(encodeCalls >= original.length / 8192)
        assertEquals(original.length, consumed)
        assertEquals(1, flushCalls)
        assertEquals(original.length.toLong(), written)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(original.toByteArray()), digest.digest())
    }

    @Test fun largeUnicodeTransferKeepsExactBytesAndCallerStreamOwnership() {
        val original = ("Ω🦉é中").repeat(200_000) + '\uD800'
        val expected = original.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var closed = false
        var flushed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw AssertionError("Transfer must write bounded byte windows") }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                assertTrue(count in 1..8192)
                assertTrue(offset >= 0 && offset + count <= bytes.size)
                digest.update(bytes, offset, count)
                written += count
            }
            override fun flush() { flushed = true }
            override fun close() { closed = true }
        }
        CampaignDocumentIoV21.writeUtf8(original, output)
        assertEquals(expected.size.toLong(), written)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest())
        assertEquals(expected.size.toLong(), CampaignDocumentIoV21.utf8Size(original))
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(expected)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expectedHash, CampaignDocumentIoV21.sha256(original))
        assertFalse("The document picker callback owns stream flushing", flushed)
        assertFalse("The document picker callback owns stream closure", closed)
    }

    @Test fun surrogatePairsAndReplacementsSurviveInputAndOutputWindowEdges() {
        val cases = listOf(
            "", "\uD800", "\uDC00", "\uD800\uD800", "\uDC00\uD800",
            "a".repeat(8191) + "🦉" + "b".repeat(8191),
            "a".repeat(8191) + "\uD800Ω\uDC00" + "z".repeat(8190) + '\uD800',
            "a".repeat(8192) + "\uD800\uDFFF",
            "é".repeat(8191) + "🦉Ω" + '\uD800'
        )
        cases.forEachIndexed { index, original ->
            val saved = ByteArrayOutputStream()
            CampaignDocumentIoV21.writeUtf8(original, saved)
            val expected = original.toByteArray(Charsets.UTF_8)
            assertArrayEquals("Unicode case $index", expected, saved.toByteArray())
            assertEquals(expected.size.toLong(), CampaignDocumentIoV21.utf8Size(original))
            CampaignDocumentIoV21.verifySavedUtf8(original, ByteArrayInputStream(expected))
        }
    }
}
