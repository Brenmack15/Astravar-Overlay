package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.json.*

/** Resolved actions go through Reforge. The wire receipt names the action at the resulting actor revision. */
internal object PlayableActionSync {
    const val KIND = "playable-action"
    const val CAST = "cast_spell"
    const val REST = "complete_rest"
    const val PREPARE = "prepare_spell"
    const val AUDIT = "PLAYABLE_ACTION_V1"
    const val STRIKE = "resolve_encounter_attack"
    const val DEFINE_SPELL = "define_spell"
    const val FORM = "change_wearable_form"
    const val FEATURE = "use_native_feature"
    const val HP = "resolve_native_hp"
    const val RECHARGE = "resolve_native_recharge"
    const val ACTIVATE_ITEM = "activate_native_item"
    val nativeTypes = setOf(FORM, FEATURE, HP, RECHARGE, ACTIVATE_ITEM)
    val types = setOf(ACTIVATE_ITEM, RECHARGE, HP, FEATURE, FORM, CAST, REST, PREPARE, STRIKE, DEFINE_SPELL)

    fun writeScopes(op: CampaignSyncOperation): Set<String> = buildSet {
        require(op.type in types)
        add("character")
        if (op.type in nativeTypes) {
            if (op.type == ACTIVATE_ITEM) addAll(setOf("inventory","combat","hp"))
            add(when (op.type) { FORM -> "inventory"; HP -> "hp"; else -> "resources" })
            if (role(op.fields) == GenesisActorRole.COMPANION) add("companions")
            return@buildSet
        }
        add("resources")
        if (op.type != PREPARE) add("combat")
        if (op.type == STRIKE) {
            add("hp")
            if (op.fields["targetRole"]?.jsonPrimitive?.content == "COMPANION") add("companions")
        }
        if (op.type == REST) { add("hp"); add("inventory") }
        if (role(op.fields) == GenesisActorRole.COMPANION) add("companions")
    }

    private fun role(fields: Map<String, JsonElement>): GenesisActorRole {
        val value = GenesisActorRole.valueOf(fields.getValue("actorRole").jsonPrimitive.content)
        require(value == GenesisActorRole.PLAYER || value == GenesisActorRole.COMPANION)
        return value
    }

    private fun resolve(c: ReforgeCharacter, type: String, f: JsonObject): ReforgeCharacter {
        fun flag(key: String): Boolean = f.getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
        fun spell(): String = f.getValue("spellId").jsonPrimitive.let {
            require(it.isString && it.content.length in 1..200); it.content
        }
        return when (type) {
            HP -> {
                require(f.keys == setOf("actorRole","kind","amount"))
                val amount = f.getValue("amount").jsonPrimitive.let { require(!it.isString && it.int > 0); it.int }
                when (f.getValue("kind").jsonPrimitive.content) {
                    "DAMAGE" -> GameplayRules.damage(c,amount)
                    "HEAL" -> GameplayRules.heal(c,amount)
                    else -> error("Unsupported HP operation")
                }
            }
            ACTIVATE_ITEM -> {
                require(f.keys == setOf("actorRole","itemId","activationId","rolledTemporaryHp"))
                val roll = f.getValue("rolledTemporaryHp").let { if (it == JsonNull) null else it.jsonPrimitive.let { n -> require(!n.isString); n.int } }
                GameplayRules.activateItem(c,f.getValue("itemId").jsonPrimitive.content,f.getValue("activationId").jsonPrimitive.content,roll)
            }
            RECHARGE -> {
                require(f.keys == setOf("actorRole","poolId","face"))
                val face = f.getValue("face").jsonPrimitive.let { require(!it.isString); it.int }
                GameplayRules.resolveRecharge(c,f.getValue("poolId").jsonPrimitive.content,face)
            }
            FEATURE -> {
                require(f.keys == setOf("actorRole","featureId"))
                GameplayRules.useFeature(c,f.getValue("featureId").jsonPrimitive.content)
            }
            FORM -> {
                require(f.keys.containsAll(setOf("actorRole","itemId","formId")) &&
                    f.keys.all { it in setOf("actorRole","itemId","formId","elapsedSeconds") })
                val form = f.getValue("formId")
                val elapsed = f["elapsedSeconds"]?.jsonPrimitive?.let {
                    require(!it.isString && it.long in 0L..86_400L)
                    it.long
                } ?: 0L
                GameplayRules.changeWearableForm(c, f.getValue("itemId").jsonPrimitive.content,
                    if (form == JsonNull) null else form.jsonPrimitive.content, elapsed)
            }
            DEFINE_SPELL -> {
                require(f.keys == setOf("actorRole", "mode", "spellId", "definition"))
                val id = spell()
                val mode = f.getValue("mode").jsonPrimitive.content
                val previous = c.spells.singleOrNull { it.id == id }
                require((mode == "CREATE" && previous == null) || (mode == "EDIT" && previous != null)) {
                    "Spell identity does not match the reviewed create or edit."
                }
                val changes = f.getValue("definition").jsonObject
                require(changes.isNotEmpty() && "id" !in changes)
                if (previous == null) require(changes.keys.containsAll(setOf("name", "level", "sourceVersion")))
                val original = previous?.let { campaignJson.encodeToJsonElement(it).jsonObject }.orEmpty()
                val definition = campaignJson.decodeFromJsonElement<SpellDefinition>(JsonObject(original + changes + ("id" to JsonPrimitive(id))))
                require(definition.sourceVersion.isNotBlank())
                GameplayRules.saveSpellDefinition(c, definition)
            }
            CAST -> {
                require(f.keys == setOf("actorRole", "spellId", "slotLevel", "ritual", "replaceConcentration"))
                val level = f.getValue("slotLevel").let { if (it == JsonNull) null else it.jsonPrimitive.let { n ->
                    require(!n.isString && n.int in 1..9); n.int
                } }
                val preview = GameplayRules.previewCast(c, spell(), level, flag("replaceConcentration"), flag("ritual"))
                require(preview.slotLevel == level) { "Choose the exact slot level for this resolved cast." }
                GameplayRules.commitCast(c, preview)
            }
            REST -> {
                require(f.keys == setOf("actorRole", "kind", "elapsedSeconds", "confirmed"))
                val elapsed = f.getValue("elapsedSeconds").jsonPrimitive
                require(!elapsed.isString)
                GameplayRules.rest(c, Recharge.valueOf(f.getValue("kind").jsonPrimitive.content), flag("confirmed"), elapsed.long)
            }
            PREPARE -> {
                require(f.keys == setOf("actorRole", "spellId", "prepared"))
                GameplayRules.prepareSpell(c, spell(), flag("prepared")).also { require(it != c) { "Preparation already matches." } }
            }
            else -> error("Unsupported resolved action.")
        }
    }

    private fun row(c: ReforgeCharacter, role: GenesisActorRole, action: JsonElement) =
        CampaignSyncRecord(c.id, KIND, c.revision, buildJsonObject {
            put("id", c.id); put("profileId", c.id); put("actorRole", role.name); put("revision", c.revision)
            put("schemaVersion", 1); put("source", "Android Reforge action v1"); put("action", action)
        })

    suspend fun current(db: CampaignLedgerDb, c: ReforgeCharacter, role: GenesisActorRole): CampaignSyncRecord {
        val previous = db.recordsDao().playableActionAt(c.id, c.revision)
        return row(c, role, previous?.let { campaignJson.parseToJsonElement(it).jsonObject.getValue("action") } ?: JsonNull)
    }

    internal fun strikeRequest(op: CampaignSyncOperation): EncounterStrikeRequest {
        val f = op.fields
        require(f.keys == setOf("actorRole","targetRole","targetId","targetRevision","attackId","encounterId",
            "encounterRevision","naturalD20","rolledDamage","finishTurn"))
        fun string(k: String) = f.getValue(k).jsonPrimitive.let { require(it.isString); it.content.also(::checkId) }
        fun number(k: String) = f.getValue(k).jsonPrimitive.let { require(!it.isString); it.long }
        require(string("targetRole") in setOf("PLAYER","COMPANION"))
        val rolls = f.getValue("rolledDamage").jsonObject
        require(rolls.size <= 512)
        return EncounterStrikeRequest(string("encounterId"),number("encounterRevision"),op.recordId,op.expectedRevision,
            string("targetId"),number("targetRevision"),string("attackId"),Math.toIntExact(number("naturalD20")),
            rolls.mapValues { (id,n) -> checkId(id); n.jsonPrimitive.let { require(!it.isString); it.int } },
            f.getValue("finishTurn").jsonPrimitive.let { require(!it.isString); it.boolean })
    }

    suspend fun apply(db: CampaignLedgerDb, campaign: String, op: CampaignSyncOperation, child: String, source: String) {
        require(op.kind == KIND && op.expectedRevision > 0L)
        val actor = requireNotNull(db.genesisAuthorityDao().actor(op.recordId))
        require(actor.playable && actor.campaignId == campaign && actor.role == role(op.fields))
        val repo = ReforgeRepository(db)
        val before = requireNotNull(repo.get(op.recordId)).character
        require(!before.archived && before.revision == op.expectedRevision)
        val beforeRow = current(db, before, actor.role)
        val result = if (op.type == STRIKE) {
            val request = strikeRequest(op)
            val target = requireNotNull(db.genesisAuthorityDao().actor(request.targetId))
            require(target.campaignId == campaign && target.role.name == op.fields.getValue("targetRole").jsonPrimitive.content)
            EncounterStrikeRepository(db).commit(request,child + "-strike")
            requireNotNull(repo.get(before.id))
        } else {
            val next = resolve(before, op.type, op.fields)
            repo.save(before.id, before.revision, next, "Resolved " + op.type, child + "-playable")
        }
        val action = buildJsonObject { put("type", op.type); put("fields", op.fields) }
        val afterRow = row(result.character, actor.role, action)
        val now = System.currentTimeMillis()
        db.recordsDao().insert(CampaignAudit(child, child, KIND, before.id, AUDIT, source, before.revision,
            result.revision, now, beforeRow.fields.toString(), afterRow.fields.toString()))
        db.recordsDao().insert(CommandReceipt(child, campaignHash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(op))),
            AUDIT, before.id, result.revision, now, outcomeJson = afterRow.fields.toString()))
    }

    fun verifyAudit(audit: CampaignAudit, change: ReforgeChange, receipt: CommandReceipt, previous: CampaignAudit?, strike: GenesisAuthorityReceipt?) {
        require(audit.kind == AUDIT && receipt.kind == AUDIT)
        val before = reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(change.beforeJson)).character
        val after = reforgeJson.decodeFromString<ReforgeSavedCharacter>(change.afterJson).character
        require(before.id == audit.recordId && before.revision == audit.priorRevision && after.revision == audit.resultingRevision)
        val saved = campaignJson.parseToJsonElement(requireNotNull(audit.afterJson)).jsonObject
        val action = saved.getValue("action").jsonObject
        require(action.keys == setOf("type", "fields"))
        val fields = action.getValue("fields").jsonObject
        val type = action.getValue("type").jsonPrimitive.content
        val actorRole = role(fields)
        val op = CampaignSyncOperation(type, before.id, KIND, before.revision, fields)
        require(receipt.fingerprint == campaignHash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(op))))
        val priorAction = previous?.let { campaignJson.parseToJsonElement(requireNotNull(it.afterJson)).jsonObject.getValue("action") } ?: JsonNull
        require(row(before, actorRole, priorAction).fields == campaignJson.parseToJsonElement(requireNotNull(audit.beforeJson)))
        require(row(after, actorRole, action).fields == saved)
        if (type == STRIKE) {
            requireNotNull(strike)
            require(strike.kind == ENCOUNTER_STRIKE && strike.commandId == audit.commandId + "-strike" &&
                change.commandId == genesisChild(strike.commandId,"attacker") &&
                campaignJson.decodeFromString<EncounterStrikeRequest>(strike.requestJson) == strikeRequest(op))
        } else {
            require(strike == null && change.commandId == audit.commandId + "-playable" && change.operation == "Resolved " + type)
            require(resolve(before, type, fields).copy(revision = Math.addExact(before.revision, 1)) == after)
        }
    }
}
