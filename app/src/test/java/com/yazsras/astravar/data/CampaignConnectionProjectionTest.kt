package com.yazsras.astravar.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CampaignConnectionProjectionTest {
    private val profile=CampaignSyncRecord("fixture","character-profile",2,buildJsonObject {put("id","fixture");put("name","Fixture");put("armorClass",19);put("level",9);put("currentHp",12);put("maximumHp",29);put("temporaryHp",JsonNull);put("revision",2);put("source","Fixture");put("updatedAt",1)})
    private val sheet=CampaignSyncRecord("fixture","party-sheet",3,buildJsonObject {put("profileId","fixture");put("campaignId","local-campaign");put("revision",3);put("source","Fixture");put("updatedAt",1);put("contentJson",buildJsonObject{put("creatureType","Gnome");put("conditions","Poisoned");put("resources",buildJsonArray{add(buildJsonObject{put("name","Second-level slots");put("current",1);put("maximum",3)})});put("portraitBase64","private-image");put("forms",buildJsonArray{add(buildJsonObject{put("name","LOCAL_ONLY_FORM_SENTINEL");put("currentHp",73519);put("maximumHp",73529)})})}.toString())})
    @Test fun character_scope_never_uploads_health_resources_conditions_or_portrait() {
        val result=CampaignConnectionProjection.project(listOf(profile,sheet),setOf("character"))
        assertFalse(result[0].fields.containsKey("currentHp"));val body=Json.parseToJsonElement(result[1].fields.getValue("contentJson").jsonPrimitive.content).jsonObject
        assertEquals(setOf("creatureType"),body.keys)
    }
    @Test fun hp_scope_returns_only_health_and_condition_with_identity_metadata() {
        val result=CampaignConnectionProjection.project(listOf(profile,sheet),setOf("hp"))
        assertFalse(result[0].fields.containsKey("name"));assertEquals(12,result[0].fields.getValue("currentHp").jsonPrimitive.int)
        assertEquals(setOf("conditions"),Json.parseToJsonElement(result[1].fields.getValue("contentJson").jsonPrimitive.content).jsonObject.keys)
    }
    @Test fun resources_scope_preserves_unknowns_without_character_or_hp_disclosure() {
        val result=CampaignConnectionProjection.project(listOf(profile,sheet),setOf("resources"));assertEquals(1,result.size)
        assertEquals(setOf("resources"),Json.parseToJsonElement(result.single().fields.getValue("contentJson").jsonPrimitive.content).jsonObject.keys)
    }
    @Test fun cross_category_audit_and_full_archive_require_all_scopes() {
        val audit=CampaignSyncRecord("audit","audit",1,buildJsonObject{put("beforeJson","private health")})
        val archive=CampaignSyncRecord("archive:source","reference",1,buildJsonObject{put("bodyText","all private categories")})
        assertTrue(CampaignConnectionProjection.project(listOf(audit,archive),setOf("continuity")).isEmpty())
        assertEquals(2,CampaignConnectionProjection.project(listOf(audit,archive),CampaignConnectionProtocol.categories).size)
    }
    @Test fun companion_resource_uses_its_own_delegated_category() {
        val record=CampaignSyncRecord("fixture-resource","companion-resource",1,buildJsonObject{put("current",JsonNull);put("maximum",3)})
        assertTrue(CampaignConnectionProjection.project(listOf(record),setOf("resources")).isEmpty())
        assertEquals(JsonNull,CampaignConnectionProjection.project(listOf(record),setOf("companions")).single().fields.getValue("current"))
    }
    @Test fun even_all_scopes_omit_local_forms_and_form_health_from_direct_sheet_projection() {
        val result = CampaignConnectionProjection.project(listOf(sheet), CampaignConnectionProtocol.categories)
        val body = Json.parseToJsonElement(result.single().fields.getValue("contentJson").jsonPrimitive.content).jsonObject
        assertFalse(body.containsKey("forms"))
        assertFalse(result.toString().contains("LOCAL_ONLY_FORM"))
        assertFalse(result.toString().contains("73519"))
    }
    @Test fun full_scope_raw_and_chunked_audit_projection_omit_forms_without_mutating_original_evidence() {
        val content = buildJsonObject {
            put("role", "Fictional role")
            put("progression", "Fictional non-form evidence ".repeat(550))
            put("senses", "Fictional non-form senses ".repeat(200))
            put("forms", buildJsonArray { add(buildJsonObject {
                put("name", "LOCAL_ONLY_FORM_SENTINEL"); put("currentHp", 73519)
                put("statBlock", "LOCAL_ONLY_FORM_CAPABILITIES ".repeat(3000))
            }) })
        }.toString()
        val originalSheet = JsonObject(sheet.fields + ("contentJson" to JsonPrimitive(content))).toString()
        val original = CampaignSyncRecord("form-audit", "audit", 1, buildJsonObject {
            put("recordType", "party-sheet"); put("recordId", "fixture"); put("beforeJson", originalSheet); put("afterJson", originalSheet)
        })
        val direct = CampaignConnectionProjection.project(listOf(original), CampaignConnectionProtocol.categories)
        val chunked = CampaignSyncAuditProjection.records(original)
        assertTrue("Retained non-form evidence must exercise real audit chunking", chunked.size > 1)
        assertFalse(direct.toString().contains("LOCAL_ONLY_FORM"))
        assertFalse(chunked.toString().contains("LOCAL_ONLY_FORM"))
        assertFalse(direct.toString().contains("73519"))
        assertTrue(original.fields.getValue("afterJson").jsonPrimitive.content.contains("LOCAL_ONLY_FORM_SENTINEL"))
        val retained = Json.parseToJsonElement(direct.single().fields.getValue("afterJson").jsonPrimitive.content).jsonObject
        assertEquals("Fictional role", Json.parseToJsonElement(retained.getValue("contentJson").jsonPrimitive.content).jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test fun historicalParentTransactionAuditAlsoOmitsNestedFormPayloadsFromPublication() {
        val originalRows = JsonArray(listOf(buildJsonObject {
            put("recordId", sheet.recordId); put("kind", sheet.kind); put("revision", sheet.revision); put("fields", sheet.fields)
        })).toString()
        val parent = CampaignSyncRecord("historical-parent-audit", "audit", 1, buildJsonObject {
            put("recordType", "campaign-transaction"); put("recordId", "local-campaign")
            put("beforeJson", originalRows); put("afterJson", originalRows)
        })
        val published = CampaignConnectionProjection.project(CampaignSyncAuditProjection.records(parent), CampaignConnectionProtocol.categories)
        assertFalse(published.toString().contains("LOCAL_ONLY_FORM"))
        assertFalse(published.toString().contains("73519"))
        assertTrue(parent.fields.getValue("afterJson").jsonPrimitive.content.contains("LOCAL_ONLY_FORM_SENTINEL"))
    }

    @Test fun unsupportedHistoricalPartyAuditPublishesMetadataWithoutPayloadAndPreservesOriginalEvidence() {
        val legacySnapshot = buildJsonObject {
            put("profileId", "fixture"); put("revision", 2)
            put("oldPayload", "LOCAL_ONLY_UNSUPPORTED_FORM_HEALTH_73519")
        }.toString()
        val original = CampaignSyncRecord("legacy-audit", "audit", 7, buildJsonObject {
            put("id", "legacy-audit"); put("commandId", "original-command")
            put("recordType", "party-sheet"); put("recordId", "fixture"); put("source", "Original checkpoint")
            put("priorRevision", 1); put("resultingRevision", 2)
            put("beforeJson", legacySnapshot); put("afterJson", legacySnapshot)
            put("unsupportedPayload", "LOCAL_ONLY_UNSUPPORTED_FORM_HEALTH_73519")
        })
        val fieldsBefore = original.fields.toString()
        val published = CampaignConnectionProjection.project(CampaignSyncAuditProjection.records(original), CampaignConnectionProtocol.categories).single()
        assertEquals(original.recordId, published.recordId)
        assertEquals(original.revision, published.revision)
        assertEquals("Original checkpoint", published.fields.getValue("source").jsonPrimitive.content)
        assertEquals("METADATA_ONLY_UNSUPPORTED_HISTORICAL_PAYLOAD", published.fields.getValue("projectionStatus").jsonPrimitive.content)
        assertFalse(published.fields.containsKey("beforeJson"))
        assertFalse(published.fields.containsKey("afterJson"))
        assertFalse(published.toString().contains("LOCAL_ONLY_UNSUPPORTED"))
        assertEquals(fieldsBefore, original.fields.toString())
        val partial = CampaignConnectionProjection.project(listOf(original, sheet), setOf("hp"))
        assertEquals(listOf("party-sheet"), partial.map { it.kind })
    }

    @Test fun unsupportedParentAuditShapeCannotAbortReadOrEmitItsOriginalPayload() {
        val original = CampaignSyncRecord("unsupported-parent", "audit", 1, buildJsonObject {
            put("recordType", "campaign-transaction"); put("recordId", "local-campaign"); put("source", "Original source")
            put("beforeJson", "{\"legacy\":\"LOCAL_ONLY_PARENT_SENTINEL\"}")
            put("afterJson", "LOCAL_ONLY_PARENT_SENTINEL")
        })
        val result = CampaignConnectionProjection.project(listOf(original), CampaignConnectionProtocol.categories).single()
        assertTrue(result.fields.containsKey("projectionStatus"))
        assertEquals("local-campaign", result.fields.getValue("recordId").jsonPrimitive.content)
        assertFalse(result.toString().contains("LOCAL_ONLY_PARENT_SENTINEL"))
        assertTrue(original.toString().contains("LOCAL_ONLY_PARENT_SENTINEL"))
        assertTrue(CampaignConnectionProjection.project(listOf(original), setOf("continuity")).isEmpty())
    }

}
