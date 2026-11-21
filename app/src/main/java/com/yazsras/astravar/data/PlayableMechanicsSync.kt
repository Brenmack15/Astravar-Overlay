package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Wire v1 edits one explicitly owned physical instance on one reviewed playable sheet. */
internal object PlayableMechanicsSync {
    const val STATS = "playable-stat"
    const val EQUIPMENT = "playable-equipment"
    const val BONUS = "edit_equipment_bonus"
    const val STATE = "set_equipment_state"
    val readScopes = setOf("character", "companions", "inventory")
    val writeScopes = setOf("character", "inventory")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    @Serializable
    data class Bonus(val id: String, val label: String, val target: NumericTarget, val key: String,
        val amount: Int, val enabled: Boolean, val stackingKey: String?, val expiresAtGameSeconds: Long?)

    fun statistics(c: ReforgeCharacter, role: GenesisActorRole): List<CampaignSyncRecord> {
        val calculated = CharacterCalculator.calculate(c)
        fun stat(target: String, key: String, label: String, effective: Int?,
            details: JsonObjectBuilder.() -> Unit = {}): CampaignSyncRecord {
            val id = "stat-" + campaignHash(listOf(c.id, target, key).joinToString("\u0000"))
            return CampaignSyncRecord(id, STATS, c.revision, buildJsonObject {
                put("id", id); put("schemaVersion", 1); put("revision", c.revision)
                put("profileId", c.id); put("actorRole", role.name); put("target", target)
                put("key", key); put("label", label); put("effective", JsonPrimitive(effective))
                put("source", "Android Reforge calculator v1"); details()
            })
        }
        return buildList {
            for ((ability, value) in calculated.abilities) {
                add(stat("ABILITY", ability.name, ability.name, value.effective.value) {
                    put("base", JsonPrimitive(c.baseAbilities[ability]))
                    put("advancement", c.permanentAdvancements[ability] ?: 0)
                    put("permanent", JsonPrimitive(value.permanent)); put("modifier", JsonPrimitive(value.modifier))
                })
            }
            SkillRules.entries(c, calculated).forEach { add(stat("SKILL", it.id, it.name, it.calculation.value)) }
        }
    }

    private fun origin(itemId: String) = "dm-equipment:$itemId"

    private fun bonus(e: EffectDefinition): Bonus {
        val n = e.payload as EffectPayload.Number
        return Bonus(e.id.removePrefix("dm-bonus:"), e.name, n.target, n.key, n.amount, e.enabled, e.stackingKey, e.expiresAtGameSeconds)
    }

    fun equipment(c: ReforgeCharacter, item: ItemInstance, definition: ItemDefinition,
        authority: GenesisItemAuthority): CampaignSyncRecord {
        val owned = definition.effects.filter { it.source.id == origin(item.id) && it.id.startsWith("dm-bonus:") && it.payload is EffectPayload.Number }
        return CampaignSyncRecord(item.id, EQUIPMENT, authority.revision, buildJsonObject {
            put("schemaVersion", 1); put("id", item.id); put("profileId", c.id); put("definitionId", definition.id)
            put("revision", authority.revision); put("state", item.state.name); put("attuned", item.attuned)
            put("operating", item.operating); put("bonuses", campaignJson.encodeToJsonElement(owned.map(::bonus)))
            put("source", "Android Reforge equipment v1")
        })
    }

    suspend fun edit(db: CampaignLedgerDb, campaignId: String, op: CampaignSyncOperation, command: String, source: String) {
        require(op.kind == EQUIPMENT && op.expectedRevision > 0)
        val row = requireNotNull(readGenesisAuthorityItem(db, op.recordId))
        check(row.revision == op.expectedRevision)
        val actor = requireNotNull(row.ownerActorId?.let { db.genesisAuthorityDao().actor(it) })
        require(actor.playable && actor.role == GenesisActorRole.PLAYER && actor.campaignId == campaignId)
        val repo = ReforgeRepository(db)
        val saved = requireNotNull(repo.get(actor.actorId))
        val c = saved.character
        require(!c.archived && op.fields.getValue("actorRevision").jsonPrimitive.long == saved.revision)
        genesisRequireProjection(db, actor.actorId, saved)
        val item = c.items.single { it.id == row.itemId }
        val def = c.itemDefinitions.single { it.id == item.definitionId }
        require(reforgeJson.decodeFromString<ItemDefinition>(row.definitionJson) == def &&
            reforgeJson.decodeFromString<ItemInstance>(row.instanceJson) == item)
        val next = when (op.type) {
            STATE -> {
                require(op.fields.size == 2)
                when {
                    "state" in op.fields -> GameplayRules.equip(c, item.id, ItemState.valueOf(op.fields.getValue("state").jsonPrimitive.content))
                    "attuned" in op.fields -> GameplayRules.attune(c, item.id, op.fields.getValue("attuned").jsonPrimitive.boolean)
                    "operating" in op.fields -> c.copy(items = c.items.map {
                        if (it.id == item.id) it.copy(operating = op.fields.getValue("operating").jsonPrimitive.boolean) else it
                    })
                    else -> error("Unsupported equipment state field.")
                }
            }
            BONUS -> {
                require(c.items.count { it.definitionId == def.id } == 1) { "Shared definitions need a separate review." }
                val removal = op.fields["removeId"]?.jsonPrimitive?.content
                require(op.fields.keys == setOf("actorRevision", if (removal != null) "removeId" else "bonus"))
                val input = if (removal == null) campaignJson.decodeFromJsonElement<Bonus>(op.fields.getValue("bonus")) else null
                val id = removal ?: requireNotNull(input).id
                require(uuid.matches(id))
                val nativeId = "dm-bonus:$id"
                val existing = def.effects.singleOrNull { it.id == nativeId }
                require(existing == null || existing.source.id == origin(item.id))
                require(c.effects.none { it.id == nativeId } && c.itemDefinitions.none { it.id != def.id && it.effects.any { e -> e.id == nativeId } })
                val effects = if (input == null) {
                    require(existing != null); def.effects.filterNot { it.id == nativeId }
                } else {
                    require(input.label.isNotBlank() && input.label.length <= 100 && input.amount in -100..100)
                    require(input.stackingKey == null || input.stackingKey.matches(Regex("[A-Za-z0-9._:-]{1,80}")))
                    require(input.expiresAtGameSeconds == null || input.expiresAtGameSeconds >= 0)
                    require(when (input.target) {
                        NumericTarget.ABILITY -> Stat.entries.any { it.name == input.key }
                        NumericTarget.SKILL -> SkillRules.entries(c).any { it.id == input.key }
                        else -> false
                    })
                    val payload = EffectPayload.Number(input.target, NumberOperation.ADD, input.amount, input.key)
                    val effect = (existing ?: EffectDefinition(nativeId, input.label,
                        RuleSource(origin(item.id), def.name, Acceptance.ACCEPTED_HOMEBREW), payload))
                        .copy(name = input.label, payload = payload, enabled = input.enabled,
                            stackingKey = input.stackingKey, expiresAtGameSeconds = input.expiresAtGameSeconds)
                    if (existing == null) {
                        require(def.effects.count { it.source.id == origin(item.id) } < 24)
                        def.effects + effect
                    } else def.effects.map { if (it.id == nativeId) effect else it }
                }
                c.copy(itemDefinitions = c.itemDefinitions.map { if (it.id == def.id) it.copy(effects = effects) else it })
            }
            else -> error("Unsupported equipment operation.")
        }
        require(next != c) { "The requested state is already active." }
        repo.save(actor.actorId, saved.revision, GameplayRules.preserveCapacities(c, next),
            "Equipment change · ${source.take(180)}", command)
    }
}
