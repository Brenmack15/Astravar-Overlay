package com.yazsras.astravar.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.nio.charset.CharacterCodingException
import java.io.OutputStream

/** Codec regressions use synthetic content only; no source material, network or campaign gameplay. */
class CampaignStreamCodecTest {
    private fun oldHash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun snapshot(text: String) = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList(),
        archive = CampaignArchiveState(sources = listOf(CampaignArchiveSource("fictional-source", text))))

    @Test fun streamingTextHashMatchesExactUtf8IncludingSplitSupplementaryCharacters() {
        listOf("", "ASCII \"quoted\" \\ slash\n\r\t\u0000", "é 日本語 owl 🦉", "a".repeat(8191) + "🦉" + "z",
            "a".repeat(8191) + "\uD800" + "z", "a".repeat(8191) + "\uDC00" + "z").forEach {
            assertEquals(oldHash(it), campaignHash(it))
        }
    }

    @Test fun streamingJsonHashAndBackupPreserveCanonicalSchemaFourBytesDefaultsNullsAndEscapes() {
        val value = snapshot("Fictional é 日本語 🦉 \"quoted\" \\path\n\u0000").copy(
            items = listOf(LedgerItem("fictional-item", "Fictional unknown quantity", source = "Synthetic fixture", createdAt = 1, updatedAt = 1)))
        val oldJson = campaignJson.encodeToString(value)
        assertEquals(oldHash(oldJson), campaignSerializedHash(LedgerExport.serializer(), value))
        assertEquals(oldJson, campaignBackupJson(value))
        assertEquals(value, campaignJson.decodeFromString<LedgerExport>(campaignBackupJson(value)))
    }

    @Test fun everyJsonControlAndSurrogateBoundaryRetainsThePriorCanonicalDigest() {
        listOf((0..31).map { it.toChar() }.joinToString("") + "\"\\/é🦉\u2028", "a".repeat(8191) + "\uD800z",
            "a".repeat(8191) + "\uDC00z", "a".repeat(8191) + "🦉z").forEach { content ->
            val value = snapshot(content)
            assertEquals(oldHash(campaignJson.encodeToString(value)), campaignSerializedHash(LedgerExport.serializer(), value))
        }
    }

    @Test fun largeQuotedScalarsReachTheSinkOnlyThroughFixedSizeUtf8Chunks() {
        var maximumWrite = 0
        var total = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { maximumWrite = maxOf(maximumWrite, 1); total++ }
            override fun write(value: ByteArray, offset: Int, length: Int) { maximumWrite = maxOf(maximumWrite, length); total += length }
        }
        val value = snapshot("a".repeat(21 * 1024 * 1024) + "\"\\\n" + "🦉")
        campaignEncodeJson(LedgerExport.serializer(), value, sink)
        assertTrue(total > 21 * 1024 * 1024)
        assertTrue("Largest encoder write was $maximumWrite bytes", maximumWrite in 1..8192)
    }

    @Test fun retainedTwentyOneMegabytePayloadHasIdenticalDigestAndExactBackupContent() {
        // Covers the native fixture's retained base64/source scale without an encodeToString growth buffer.
        val payload = "{\"fictional\":\"" + "a".repeat(21 * 1024 * 1024) + "\"}"
        val value = snapshot(payload)
        val json = campaignBackupJson(value)
        assertEquals(oldHash(json), campaignSerializedHash(LedgerExport.serializer(), value))
        assertEquals(payload, campaignJson.decodeFromString<LedgerExport>(json).archive.sources.single().payloadJson)
        assertEquals(oldHash(payload), campaignHash(payload))
    }

    @Test fun jsonScannerSkipsLargeStringValuesAndDecodesOnlyObjectKeys() {
        val large = "{\"format\":\"fictional\",\"schema\":4,\"payload\":\"" + "a".repeat(2_000_000) + "\\n\\u00e9\"}"
        assertEquals(setOf("format", "schema", "payload"), rejectDuplicateJsonMembers(large))
        val invalid = listOf("{\"key\":1,\"k\\u0065y\":2}", "{\"a\":\"\\x\"}", "{\"a\":\"\n\"}", "{\"a\":01}", "{\"a\":true,}",
            "{\"a\":\"\\u123z\"}", "{\"a\":1} trailing", "{\"a\":1\u000b}", "{\"a\":" + "1".repeat(1025) + "}")
        invalid.forEach { text ->
            try { rejectDuplicateJsonMembers(text); fail("Invalid JSON accepted: ${text.take(80)}") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }

    @Test fun boundedUtf8ValidationHandlesChunkSplitMultibyteAndRejectsCorruptBytes() {
        val text = "a".repeat(8191) + "🦉é日本語".repeat(2048)
        assertEquals(text, validatedCampaignUtf8(text.toByteArray(Charsets.UTF_8)))
        listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xe2.toByte(), 0x82.toByte())).forEach { bad ->
            try { validatedCampaignUtf8(bad); fail("Corrupt UTF-8 accepted") } catch (_: CharacterCodingException) {}
        }
    }

    @Test fun jsonComplexityLimitsRejectBeforeTypedCollectionsAreAllocated() {
        val boundary = "[" + "null,".repeat(CAMPAIGN_JSON_MAX_CONTAINER_VALUES - 1) + "null]"
        assertTrue(rejectDuplicateJsonMembers(boundary).isEmpty())
        val tooMany = "[null," + boundary.drop(1)
        val distributed = "{\"a\":$boundary,\"b\":$boundary}"
        val members = "{" + (0..CAMPAIGN_JSON_MAX_OBJECT_MEMBERS).joinToString(",") { "\"k$it\":null" } + "}"
        listOf(tooMany to "record limit", distributed to "complexity limit", members to "member limit").forEach { (text, expected) ->
            try { rejectDuplicateJsonMembers(text); fail("Unsupported JSON collection accepted") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expected)) }
        }
    }

    @Test fun unicodeBackupByteLimitStopsInTheMeasuringPassBeforeOutputAllocation() {
        // Fewer than 32 million UTF-16 characters, but more than the SAF workflow's 32 MiB bytes.
        val oversized = snapshot("é".repeat(CAMPAIGN_BACKUP_MAX_BYTES / 2))
        try { campaignBackupJson(oversized); fail("Oversized UTF-8 backup accepted") } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("32 MiB document-transfer"))
        }
    }

    @Test fun backupSizeLimitStopsBeforeAllocatingOversizedOutput() {
        val oversized = snapshot("a".repeat(CAMPAIGN_BACKUP_MAX_CHARACTERS))
        try { campaignBackupJson(oversized); fail("Oversized complete backup accepted") } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("32-million-character"))
        }
    }
}
