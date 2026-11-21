package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Wire v1 edits one explicitly owned physical instance on one reviewed playable sheet. */
internal object PlayableMechanicsSync {
    const val STATS = "playable-stat"
    const val EQUIPMENT = "playable-equipment"
    const val BONUS = "edit_equipment_bonus"
    const val DEFENSE = "edit_equipment_defense"
    const val STATE = "set_equipment_state"
    val readScopes = setOf("character", "companions", "inventory")
    val writeScopes = setOf("character", "inventory")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    @Serializable
    data class Bonus(val id: String, val label: String, val target: NumericTarget, val key: String,
        val amount: Int, val enabled: Boolean, val stackingKey: String?, val expiresAtGameSeconds: Long?)

    @Serializable
    data class Defense(val id: String, val label: String, val damageType: DamageKind, val kind: DefenseKind,
        val nonmagicalOnly: Boolean, val enabled: Boolean, val expiresAtGameSeconds: Long?)

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
            for ((ability, save) in calculated.saves) {
                add(stat("SAVE", ability.name, ability.name, save.value))
            }
            add(stat("DERIVED", "AC", "Armor Class", calculated.armorClass.value))
            add(stat("DERIVED", "INITIATIVE", "Initiative", calculated.initiative.value))
            add(stat("DERIVED", "PROFICIENCY", "Proficiency bonus", calculated.proficiencyBonus))
            add(stat("DERIVED", "SPEED", "Speed", calculated.speed.value))
            add(stat("DERIVED", "SPELL_ATTACK", "Spell attack", calculated.spellAttack.value))
            add(stat("DERIVED", "SPELL_SAVE_DC", "Spell save DC", calculated.spellSaveDc.value))
            for ((mode, value) in calculated.movement) add(stat("MOVEMENT", mode, mode, value))
            for ((sense, value) in calculated.senses) add(stat("SENSE", sense, sense, value))
            for (defense in calculated.defenses.distinct()) {
                val key = listOf(defense.type.name, defense.kind.name, if (defense.nonmagicalOnly) "NONMAGICAL" else "ALL").joinToString(":")
                add(stat("DAMAGE_DEFENSE", key, defense.kind.name, 1))
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
        revision: Long): CampaignSyncRecord {
        val owned = definition.effects.filter { it.source.id == origin(item.id) && it.id.startsWith("dm-bonus:") && it.payload is EffectPayload.Number }
        val defenses = definition.effects.mapNotNull { effect ->
            val payload = effect.payload as? EffectPayload.Defense ?: return@mapNotNull null
            if (effect.source.id != origin(item.id) || !effect.id.startsWith("dm-defense:")) return@mapNotNull null
            Defense(effect.id.removePrefix("dm-defense:"), effect.name, payload.type, payload.kind, payload.nonmagicalOnly,
                effect.enabled, effect.expiresAtGameSeconds)
        }
        return CampaignSyncRecord(item.id, EQUIPMENT, revision, buildJsonObject {
            put("schemaVersion", 1); put("id", item.id); put("profileId", c.id); put("definitionId", definition.id)
            put("revision", revision); put("state", item.state.name); put("attuned", item.attuned)
            put("operating", item.operating); put("bonuses", campaignJson.encodeToJsonElement(owned.map(::bonus)))
            if (defenses.isNotEmpty()) put("defenses", campaignJson.encodeToJsonElement(defenses))
            put("source", "Android Reforge equipment v1")
        })
    }

    private fun editEffect(c: ReforgeCharacter, item: ItemInstance, def: ItemDefinition, type: String,
        fields: Map<String, JsonElement>): ReforgeCharacter {
        require(c.items.count { it.definitionId == def.id } == 1) { "Shared definitions need a separate review." }
        val kind = if (type == DEFENSE) "defense" else "bonus"
        val removal = fields["removeId"]?.jsonPrimitive?.content
        require(fields.keys == setOf("actorRevision", if (removal == null) kind else "removeId"))
        val raw = if (removal == null) fields.getValue(kind).jsonObject else null
        val id = removal ?: requireNotNull(raw).getValue("id").jsonPrimitive.content
        require(uuid.matches(id))
        val prefix = "dm-$kind:"
        val nativeId = prefix + id
        val existing = def.effects.singleOrNull { it.id == nativeId }
        require(existing == null || existing.source.id == origin(item.id) &&
            if (type == DEFENSE) existing.payload is EffectPayload.Defense else existing.payload is EffectPayload.Number)
        require(c.effects.none { it.id == nativeId } && c.itemDefinitions.none { it.id != def.id && it.effects.any { e -> e.id == nativeId } })
        val effects = if (raw == null) {
            require(existing != null)
            def.effects.filterNot { it.id == nativeId }
        } else {
            val label = raw.getValue("label").jsonPrimitive.content
            require(label.isNotBlank() && label.length <= 100)
            val expiresAt = raw.getValue("expiresAtGameSeconds").let { if (it is JsonNull) null else it.jsonPrimitive.long }
            require(expiresAt == null || expiresAt >= 0)
            val enabled = raw.getValue("enabled").jsonPrimitive.boolean
            val payload = if (type == DEFENSE) {
                val input = campaignJson.decodeFromJsonElement<Defense>(raw)
                EffectPayload.Defense(input.damageType, input.kind, input.nonmagicalOnly)
            } else {
                val input = campaignJson.decodeFromJsonElement<Bonus>(raw)
                require(input.amount in -100..100)
                require(input.stackingKey == null || input.stackingKey.matches(Regex("[A-Za-z0-9._:-]{1,80}")))
                require(when (input.target) {
                    NumericTarget.ABILITY -> Stat.entries.any { it.name == input.key }
                    NumericTarget.SKILL -> SkillRules.entries(c).any { it.id == input.key }
                    else -> false
                })
                EffectPayload.Number(input.target, NumberOperation.ADD, input.amount, input.key)
            }
            val source = RuleSource(origin(item.id), def.name, Acceptance.ACCEPTED_HOMEBREW)
            val stacking = if (type == DEFENSE) existing?.stackingKey else raw["stackingKey"]?.jsonPrimitive?.contentOrNull
            val effect = (existing ?: EffectDefinition(nativeId, label, source, payload)).copy(name = label, payload = payload,
                enabled = enabled, stackingKey = stacking, expiresAtGameSeconds = expiresAt)
            if (existing == null) {
                require(def.effects.count { it.source.id == origin(item.id) && it.id.startsWith(prefix) } < 24)
                def.effects + effect
            } else def.effects.map { if (it.id == nativeId) effect else it }
        }
        return c.copy(itemDefinitions = c.itemDefinitions.map { if (it.id == def.id) it.copy(effects = effects) else it })
    }

    suspend fun edit(db: CampaignLedgerDb, campaignId: String, op: CampaignSyncOperation, command: String, source: String) {
        require(op.kind == EQUIPMENT && op.expectedRevision > 0)
        val row = requireNotNull(readGenesisAuthorityItem(db, op.recordId))
        check(row.revision == op.expectedRevision)
        val actor = requireNotNull(row.ownerActorId?.let { db.genesisAuthorityDao().actor(it) })
        val role = op.fields["actorRole"]?.jsonPrimitive?.content ?: "PLAYER"
        require(actor.playable && actor.role.name == role && actor.role != GenesisActorRole.NPC && actor.campaignId == campaignId)
        val editFields = op.fields - "actorRole"
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
                require(editFields.size == 2)
                when {
                    "state" in op.fields -> GameplayRules.equip(c, item.id, ItemState.valueOf(op.fields.getValue("state").jsonPrimitive.content))
                    "attuned" in op.fields -> GameplayRules.attune(c, item.id, op.fields.getValue("attuned").jsonPrimitive.boolean)
                    "operating" in op.fields -> c.copy(items = c.items.map {
                        if (it.id == item.id) it.copy(operating = op.fields.getValue("operating").jsonPrimitive.boolean) else it
                    })
                    else -> error("Unsupported equipment state field.")
                }
            }
            DEFENSE, BONUS -> editEffect(c, item, def, op.type, editFields)
            else -> error("Unsupported equipment operation.")
        }
        require(next != c) { "The requested state is already active." }
        val result = repo.save(actor.actorId, saved.revision, GameplayRules.preserveCapacities(c, next),
            "Equipment change · ${source.take(180)}", "$command-playable")
        val updated = requireNotNull(readGenesisAuthorityItem(db, item.id))
        check(updated.revision == Math.addExact(row.revision, 1))
        val finalItem = result.character.items.single { it.id == item.id }
        val finalDef = result.character.itemDefinitions.single { it.id == finalItem.definitionId }
        val before = equipment(c, item, def, row.revision).fields.toString()
        val after = equipment(result.character, finalItem, finalDef, updated.revision).fields.toString()
        val now = System.currentTimeMillis()
        val fingerprint = campaignHash(OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(op)))
        db.recordsDao().insert(CampaignAudit(command, command, EQUIPMENT, item.id, "EQUIPMENT_EDIT_V1",
            source, row.revision, updated.revision, now, before, after))
        db.recordsDao().insert(CommandReceipt(command, fingerprint, "EQUIPMENT_EDIT_V1", item.id,
            updated.revision, now, outcomeJson = after))
    }
}
