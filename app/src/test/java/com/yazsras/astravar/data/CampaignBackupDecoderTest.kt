package com.yazsras.astravar.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/** Synthetic generated-serializer compatibility and bounded escaped-scalar regressions. */
class CampaignBackupDecoderTest {
    private val empty = LedgerExport(exportedAt = 0, items = emptyList(), events = emptyList())
    private fun source(text: String) = empty.copy(archive = CampaignArchiveState(
        sources = listOf(CampaignArchiveSource("fictional-source", text))))
    private fun reject(text: String) {
        try { campaignDecodeBackup(text); fail("Invalid campaign backup was accepted: ${text.take(100)}") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun generatedSerializerValuesMatchTheExistingJsonDecoder() {
        val value = empty.copy(
            items = listOf(LedgerItem("fictional-item", "Fictional café 星図 🧭", quantity = null,
                description = "A quoted \"history\" with \\ slash and\nnew line", imageBase64 = null,
                createdAt = Long.MIN_VALUE, updatedAt = Long.MAX_VALUE, archived = true)),
            events = listOf(LedgerEvent("fictional-event", "fictional-item", "HISTORY", "Confirmed", "No gameplay", "Fictional",
                "fictional-fingerprint", 0, 1, 1, beforeJson = null, afterJson = "{\"quantity\":null}")),
            records = CampaignRecordsExport(
                companionResources = listOf(CompanionResource("Fictional resource", current = null, maximum = 4, id = "fictional-resource")),
                characterProfiles = listOf(CharacterProfile(name = "Fictional player", level = 9)),
                characterAbilities = listOf(CharacterAbility("STR", score = null, magicModifier = -2))),
            archive = CampaignArchiveState(
                references = listOf(CampaignReferenceRecord("r", "c", "e", "Fictional note", "history", "Unchanged é 🧭", "{}", "", "", "", "")),
                relations = listOf(CampaignReferenceRelation("edge", "r", "unknown", resolved = false)),
                backups = listOf(CampaignImportBackup("fictional-package", "{\"prior\":\"é 🧭\"}", "hash", "state", 1)),
                sources = listOf(CampaignArchiveSource("fictional-source", "{\"body\":\"café 星図 🧭\"}"))))
        listOf(campaignJson.encodeToString(value), campaignBackupJson(value)).forEach { text ->
            assertEquals(campaignJson.decodeFromString<LedgerExport>(text), campaignDecodeBackup(text))
            assertEquals(value, campaignDecodeBackup(text))
        }
    }

    @Test fun defaultsNullableFieldsAndArbitraryObjectFieldOrderRemainCompatible() {
        val minimal = """ { "events": [], "items": [], "exportedAt": 0 } """
        assertEquals(empty, campaignDecodeBackup(minimal))
        val text = """{"events":[],"items":[{"updatedAt":2,"quantity":null,"id":"fictional","createdAt":1,"title":"Unknown"}],"exportedAt":0,"records":{"characterProfiles":[{"currentHp":null,"name":"Fictional"}]}}"""
        assertEquals(campaignJson.decodeFromString<LedgerExport>(text), campaignDecodeBackup(text))
        assertNull(campaignDecodeBackup(text).items.single().quantity)
        assertEquals("Unknown", campaignDecodeBackup(text).items.single().owner)
        assertNull(campaignDecodeBackup(text).records.characterProfiles.single().currentHp)
    }

    @Test fun controlsEscapesUnicodeAndEverySurrogateUnitRoundTripExactly() {
        val controls = (0..31).map { it.toChar() }.joinToString("")
        listOf("", "\"\\/\b\t\n\u000C\r", controls + "é 星図 🧭 \u2028\u2029",
            "a".repeat(8191) + "🧭z", "a".repeat(8191) + "\uD800z", "a".repeat(8191) + "\uDC00z").forEach { original ->
            val value = source(original)
            val backup = campaignBackupJson(value)
            assertEquals(value, campaignDecodeBackup(backup))
            assertEquals(original, campaignDecodeBackup(backup).archive.sources.single().payloadJson)
        }
        val escapedSlash = """{"exportedAt":0,"items":[],"events":[],"archive":{"sources":[{"packageHash":"fictional","payloadJson":"a\/b\u0063"}]}}"""
        assertEquals("a/bc", campaignDecodeBackup(escapedSlash).archive.sources.single().payloadJson)
    }

    @Test fun nestedHistoryRemainsAnOriginalStringRatherThanAnotherParsedDocument() {
        val history = "{\"before\":{\"quantity\":null},\"after\":\"é \\\"unchanged\\\"\",\"list\":[{},null]}"
        val value = empty.copy(events = listOf(LedgerEvent("event", "item", "HISTORY", "Known", "Fictional", "Player", "hash", 0, 1, 1,
            beforeJson = history, afterJson = history)), archive = CampaignArchiveState(
            backups = listOf(CampaignImportBackup("package", history, "hash", "state", 1)),
            sources = listOf(CampaignArchiveSource("source", history))))
        assertEquals(value, campaignDecodeBackup(campaignBackupJson(value)))
        assertEquals(history, campaignDecodeBackup(campaignBackupJson(value)).archive.backups.single().snapshotJson)
    }

    @Test fun unknownFieldsWrongTypesMissingRequiredFieldsAndNumberOverflowAreRejected() {
        val validItem = """{"id":"fictional","title":"Fictional","createdAt":1,"updatedAt":1}"""
        listOf("[]", "null", "{}", """{"exportedAt":0,"items":[],"events":[],"unknown":1}""",
            """{"exportedAt":0,"items":[],"events":[],"archive":{"unknown":[]}}""",
            """{"exportedAt":0,"items":{},"events":[]}""", """{"exportedAt":0,"items":[null],"events":[]}""",
            """{"exportedAt":null,"items":[],"events":[]}""", """{"exportedAt":"1","items":[],"events":[]}""",
            """{"exportedAt":9223372036854775808,"items":[],"events":[]}""",
            """{"exportedAt":1.5,"items":[],"events":[]}""", """{"exportedAt":1e309,"items":[],"events":[]}""",
            """{"exportedAt":0,"items":[$validItem],"events":[],"schema":2147483648}""",
            """{"exportedAt":0,"items":[$validItem],"events":[],"format":true}""",
            """{"exportedAt":0,"items":[${validItem.dropLast(1)},"archived":1}],"events":[]}""",
            """{"exportedAt":0,"items":[${validItem.dropLast(1)},"quantity":"1"}],"events":[]}""").forEach(::reject)
    }

    @Test fun syntaxDuplicateMembersAndComplexityAreCheckedBeforeTypedDecoding() {
        listOf("""{"exportedAt":0,"items":[],"events":[],}""",
            """{"exportedAt":0,"items":[],"events":[]} trailing""",
            """{"exportedAt":0,"items":[],"events":[],"schema":1,"sch\u0065ma":4}""",
            """{"exportedAt":0,"items":[],"events":[],"archive":{"sources":[{"packageHash":"x","payloadJson":"\x"}]}}""",
            "[".repeat(129) + "null" + "]".repeat(129),
            "[" + "null,".repeat(CAMPAIGN_JSON_MAX_CONTAINER_VALUES) + "null]").forEach(::reject)
    }

    @Test fun twentyOneMegabyteEscapedUnicodeScalarDecodesWithoutAGrowableBuffer() {
        // This exact scale also runs under -Xmx192m. Keep the Unicode source and decoded result
        // simultaneously, as the backup/recovery path must preserve the original source bytes.
        val original = "{\"fictional\":\"é🧭" + "a".repeat(21 * 1024 * 1024) + "\"}"
        val backup = campaignBackupJson(source(original))
        assertTrue(backup.all { it.code < 128 })
        val decoded = campaignDecodeBackup(backup).archive.sources.single().payloadJson
        assertEquals(original.length, decoded.length)
        assertEquals(original, decoded)
        assertEquals(campaignHash(original), campaignHash(decoded))
    }
}
