package com.yazsras.astravar.data

import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Proof for the future streaming design, NOT permission to bypass public restore limits. */
class CampaignLargeBackupDesignTest {
    @Test fun streamingCodecBeyond32MillionCharactersKeepsExactValuesAndSupportsIndependentByteVerification() {
        val payload = "a".repeat(33_000_001) + "é🦉\\\"\nOriginal immutable source"
        val value = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList(), archive = CampaignArchiveState(
            sources = listOf(CampaignArchiveSource("original-source-identity", payload)),
            backups = listOf(CampaignImportBackup("original-package", "{\"exact\":\"é🦉\"}", "original-digest", "original-state", 17))))
        val file = Files.createTempFile("astravar-synthetic-stream-", ".json")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var maximumWrite = 0
            DigestOutputStream(Files.newOutputStream(file), digest).use { destination ->
                val boundedSink = object : OutputStream() {
                    override fun write(value: Int) { maximumWrite = maxOf(maximumWrite, 1); destination.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        maximumWrite = maxOf(maximumWrite, length); destination.write(bytes, offset, length)
                    }
                }
                campaignEncodeJson(LedgerExport.serializer(), value, boundedSink, asciiStrings = true)
            }
            assertTrue(Files.size(file) > CAMPAIGN_BACKUP_MAX_CHARACTERS)
            assertTrue("UTF-8 writer must remain bounded", maximumWrite in 1..8192)
            val readback = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; readback.update(buffer, 0, count) }
            }
            val expected = digest.digest()
            assertArrayEquals(expected, readback.digest())
            assertEquals(campaignBackupSerializedHash(value), expected.joinToString("") { "%02x".format(it) })
            // Host-only fidelity proof. A production reader still needs staged, bounded decoding.
            val decoded = campaignJson.decodeFromString<LedgerExport>(Files.newBufferedReader(file).use { it.readText() })
            assertEquals(value, decoded)
            assertEquals(payload, decoded.archive.sources.single().payloadJson)
            val missingLastByte = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192); var remaining = Files.size(file) - 1
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0); missingLastByte.update(buffer, 0, count); remaining -= count
                }
            }
            assertFalse("Truncated saved document must fail readback", expected.contentEquals(missingLastByte.digest()))
        } finally { Files.deleteIfExists(file) }
    }
}
