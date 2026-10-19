package com.yazsras.astravar.ui

import com.yazsras.astravar.data.validatedCampaignUtf8
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Bounds bytes before decoding. Invalid UTF-8 must never be silently replaced in a signed/hashable archive. */
internal object CampaignDocumentIoV21 {
    const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024

    fun readUtf8(input: InputStream, limit: Int = MAX_DOCUMENT_BYTES): String {
        // Shared strict validation uses a fixed scratch buffer, then constructs
        // the final String without a second complete UTF-16 decoding buffer.
        return validatedCampaignUtf8(readBytes(input, limit))
    }

    fun readBytes(input: InputStream, limit: Int = MAX_DOCUMENT_BYTES): ByteArray {
        require(limit > 0) { "Document size limit must be positive." }
        val chunks = ArrayList<ByteArray>()
        var total = 0
        var buffer = ByteArray(minOf(8192, limit))
        var filled = 0
        input.use { stream ->
            while (true) {
                if (total == limit) {
                    require(stream.read() < 0) { "Document exceeds the ${limit / (1024 * 1024)} MiB import limit." }
                    break
                }
                if (filled == buffer.size) {
                    chunks += buffer
                    buffer = ByteArray(minOf(8192, limit - total)); filled = 0
                }
                val count = stream.read(buffer, filled, buffer.size - filled)
                if (count < 0) break
                if (count == 0) {
                    val value = stream.read()
                    if (value < 0) break
                    buffer[filled++] = value.toByte(); total++
                } else {
                    require(count <= limit - total) { "Document exceeds the ${limit / (1024 * 1024)} MiB import limit." }
                    filled += count; total += count
                }
            }
        }
        if (filled > 0) chunks += if (filled == buffer.size) buffer else buffer.copyOf(filled)
        return ByteArray(total).also { result ->
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(result, offset); offset += chunk.size }
        }
    }

    private fun utf8(text: String, consume: (ByteArray, Int) -> Unit) {
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val source = CharBuffer.wrap(text)
        val bytes = ByteBuffer.allocate(8192)
        while (true) {
            val result = encoder.encode(source, bytes, true)
            if (result.isError) result.throwException()
            consume(bytes.array(), bytes.position()); bytes.clear()
            if (result.isUnderflow) break
        }
    }

    fun utf8Size(text: String): Long {
        var size = 0L
        utf8(text) { _, count -> size += count }
        return size
    }

    fun writeUtf8(text: String, output: OutputStream) = utf8(text) { bytes, count -> output.write(bytes, 0, count) }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        utf8(text) { bytes, count -> digest.update(bytes, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Hash the independently reopened provider bytes without retaining another document String. */
    fun verifySavedUtf8(expected: String, input: InputStream, limit: Int = MAX_DOCUMENT_BYTES) {
        require(limit > 0) { "Document size limit must be positive." }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = stream.read()
                    if (value < 0) break
                    total++; require(total <= limit) { "Saved backup exceeds the safe document transfer limit." }
                    digest.update(value.toByte())
                } else {
                    total += count; require(total <= limit) { "Saved backup exceeds the safe document transfer limit." }
                    digest.update(buffer, 0, count)
                }
            }
        }
        val savedHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(sha256(expected) == savedHash) { "Saved backup failed independent document readback. Choose a different destination and retry." }
    }

    fun verifySavedCopy(expected: String, readback: String) {
        require(sha256(expected) == sha256(readback)) { "Saved backup failed independent document readback. Choose a different destination and retry." }
    }

    fun verifySavedBytes(expected: ByteArray, readback: ByteArray) {
        require(sha256(expected) == sha256(readback)) { "Saved attachment failed independent document readback." }
    }
}
