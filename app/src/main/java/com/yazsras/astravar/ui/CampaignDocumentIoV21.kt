package com.yazsras.astravar.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Bounds bytes before decoding. Invalid UTF-8 must never be silently replaced in a signed/hashable archive. */
internal object CampaignDocumentIoV21 {
    const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024

    fun readUtf8(input: InputStream, limit: Int = MAX_DOCUMENT_BYTES): String {
        val bytes = readBytes(input, limit)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun readBytes(input: InputStream, limit: Int = MAX_DOCUMENT_BYTES): ByteArray {
        require(limit > 0) { "Document size limit must be positive." }
        val bytes = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val value = stream.read()
                    if (value < 0) break
                    require(bytes.size() < limit) { "Document exceeds the ${limit / (1024 * 1024)} MiB import limit." }
                    bytes.write(value)
                } else {
                    require(count <= limit - bytes.size()) { "Document exceeds the ${limit / (1024 * 1024)} MiB import limit." }
                    bytes.write(buffer, 0, count)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun verifySavedCopy(expected: String, readback: String) {
        require(sha256(expected) == sha256(readback)) { "Saved backup failed independent document readback. Choose a different destination and retry." }
    }

    fun verifySavedBytes(expected: ByteArray, readback: ByteArray) {
        require(sha256(expected) == sha256(readback)) { "Saved attachment failed independent document readback." }
    }
}
