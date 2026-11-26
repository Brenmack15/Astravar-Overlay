package com.yazsras.astravar.ui

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudAttackBuilderTest {
    private fun draft() = CloudAttackDraft("test", "Training blade", "Synthetic equipment review",
        delivery="melee",resolution="attack",timing="attack",ability="STR",proficient=true,bonus="1",reach="5",
        components=listOf(AttackDamageDraft("edge","Weapon damage","slashing","1","8","2",bonus="STR"),
            AttackDamageDraft("enchantment","Elemental charge","cold","0","","3",variants=listOf("cold","fire"))))
    @Test fun reviewedComponentsAndIdentitiesRoundTripWithoutInventingDice() {
        val d=draft()
        val command=d.payload("synthetic")
        val actorFields=setOf("actorId","attackId","name")
        val row=buildJsonObject {
            put("id","test");put("actorId","synthetic");put("kind","attack");put("title",d.name)
            put("data",JsonObject(command.filterKeys { it !in actorFields } + ("selections" to JsonArray(emptyList()))));put("revision",4)
        }
        val fromRecord=CloudAttackDraft.read(row)
        val changed=fromRecord.copy(components=fromRecord.components.reversed()).payload("synthetic",4)
        assertEquals("attack",changed.getValue("resolution").jsonPrimitive.content)
        assertEquals(4,changed.getValue("expectedAttackRevision").jsonPrimitive.int)
        val components=changed.getValue("components").jsonArray
        assertEquals("enchantment",components[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(0,components[0].jsonObject.getValue("dice").jsonPrimitive.int)
        assertEquals(listOf("cold","fire"),components[0].jsonObject.getValue("variants").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("discardUnavailableSelections" in changed)
        assertFalse("fixedHit" in changed)
        assertEquals(command.getValue("components").jsonArray.first(),components.last())
    }
    @Test fun validatesDefaultsAndSeparatesDeliveryFromHowAnOutcomeResolves() {
        val base=draft()
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(components=listOf(base.components.last().copy(type="force"))).payload("synthetic")
        }
        val save=base.copy(delivery="area",resolution="save",saveAbility="DEX",dc="15",onSave="half",area="15-foot sphere")
            .payload("synthetic")
        assertFalse("ability" in save);assertFalse("fixedHit" in save);assertFalse("hitBonus" in save)
        assertEquals(15,save.getValue("saveDc").jsonPrimitive.int)
        val rider=AttackRiderDraft("effect",trigger="failed_save",effect="Reviewed custom effect",component="edge")
        assertThrows(IllegalArgumentException::class.java) { base.copy(riders=listOf(rider)).payload("synthetic") }
        val manual=base.copy(resolution="manual",riders=listOf(rider)).payload("synthetic")
        assertFalse("saveDc" in manual); assertEquals("edge",manual.getValue("riders").jsonArray.single().jsonObject.getValue("componentId").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { base.copy(attuned=true).payload("synthetic") }
    }

    @Test fun sourcedItemTextPrefillsOnlyUnconditionalTypedDamageAndBindsTheExactSource() {
        fun record(kind:String,id:String,revision:Long,title:String,data:JsonObject)=buildJsonObject {
            put("kind",kind);put("id",id);put("revision",revision);put("title",title);put("data",data) }
        val def=record("item_definition","blade-def",5,"Test blade",buildJsonObject {
            put("description","This blade deals 1d8 + 4 radiant damage. If you expend a charge it deals 2d6 fire damage. Includes a ward.") })
        val item=record("item_instance","blade-instance",7,"Test blade",buildJsonObject {
            put("definitionId","blade-def");put("description","Deals 3 cold damage.") })
        val suggestion=reviewedItemAttack(item,def,false)
        val c=suggestion.components.single()
        assertEquals(listOf("1","8","4","radiant"),listOf(c.dice,c.sides,c.flat,c.type))
        assertTrue(listOf(suggestion.timing,suggestion.delivery,suggestion.resolution,suggestion.hit,suggestion.range,suggestion.reach).all(String::isBlank))
        assertThrows(IllegalArgumentException::class.java){suggestion.copy(name="Blade").payload("actor")}
        val cmd=suggestion.copy(name="Reviewed",timing="action",delivery="ranged",resolution="manual").payload("actor")
        assertEquals("blade-instance",cmd.getValue("sourceItemId").jsonPrimitive.content)
        assertEquals(5,cmd.getValue("sourceReview").jsonObject.getValue("definitionRevision").jsonPrimitive.int)
        val flat=reviewedItemAttack(item,def,true).components.single()
        assertEquals(listOf("0","3","cold"),listOf(flat.dice,flat.flat,flat.type))
    }
}
