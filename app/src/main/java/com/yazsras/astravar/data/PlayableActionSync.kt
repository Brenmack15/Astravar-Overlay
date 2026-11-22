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
    val types = setOf(CAST, REST, PREPARE)

    fun writeScopes(op: CampaignSyncOperation): Set<String> = buildSet {
        require(op.type in types)
        add("character")
        add("resources")
        if (op.type != PREPARE) add("combat")
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

    suspend fun apply(db: CampaignLedgerDb, campaign: String, op: CampaignSyncOperation, child: String, source: String) {
        require(op.kind == KIND && op.expectedRevision > 0L)
        val actor = requireNotNull(db.genesisAuthorityDao().actor(op.recordId))
        require(actor.playable && actor.campaignId == campaign && actor.role == role(op.fields))
        val repo = ReforgeRepository(db)
        val before = requireNotNull(repo.get(op.recordId)).character
        require(!before.archived && before.revision == op.expectedRevision)
        val beforeRow = current(db, before, actor.role)
        val next = resolve(before, op.type, op.fields)
        val result = repo.save(before.id, before.revision, next, "Resolved " + op.type, child + "-playable")
        val action = buildJsonObject { put("type", op.type); put("fields", op.fields) }
        val afterRow = row(result.character, actor.role, action)
        val now = System.currentTimeMillis()
        db.recordsDao().insert(CampaignAudit(child, child, KIND, before.id, AUDIT, source, before.revision,
            result.revision, now, beforeRow.fields.toString(), afterRow.fields.toString()))
        db.recordsDao().insert(CommandReceipt(child, campaignHash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(op))),
            AUDIT, before.id, result.revision, now, outcomeJson = afterRow.fields.toString()))
    }

    fun verifyAudit(audit: CampaignAudit, change: ReforgeChange, receipt: CommandReceipt, previous: CampaignAudit?) {
        require(audit.kind == AUDIT && receipt.kind == AUDIT && change.commandId == audit.commandId + "-playable")
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
        require(row(after, actorRole, action).fields == saved && change.operation == "Resolved " + type)
        require(resolve(before, type, fields).copy(revision = Math.addExact(before.revision, 1)) == after)
    }
}
