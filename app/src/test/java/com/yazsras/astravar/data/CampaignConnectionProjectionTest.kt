package com.yazsras.astravar.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CampaignConnectionProjectionTest {
    private val profile=CampaignSyncRecord("fixture","character-profile",2,buildJsonObject {put("id","fixture");put("name","Fixture");put("armorClass",19);put("level",9);put("currentHp",12);put("maximumHp",29);put("temporaryHp",JsonNull);put("revision",2);put("source","Fixture");put("updatedAt",1)})
    private val sheet=CampaignSyncRecord("fixture","party-sheet",3,buildJsonObject {put("profileId","fixture");put("campaignId","local-campaign");put("revision",3);put("source","Fixture");put("updatedAt",1);put("contentJson",buildJsonObject{put("creatureType","Gnome");put("conditions","Poisoned");put("resources",buildJsonArray{add(buildJsonObject{put("name","Second-level slots");put("current",1);put("maximum",3)})});put("portraitBase64","private-image")}.toString())})
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
}
