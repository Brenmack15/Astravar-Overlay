package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Version-one operations bind a reviewed source identity to a real playable object.
 * The existing reference row is the public receipt index. Genesis owns the actual actor/item.
 */
internal object NativeSourceSync {
    const val ITEM = "define_native_item"
    const val ACTOR = "define_native_actor"
    val types = setOf(ITEM, ACTOR)

    @Serializable
    data class Evidence(val namespace: String, val externalId: String, val url: String, val revision: String)

    @Serializable
    data class Request(
        val version: Int, val purpose: String, val mode: String, val evidence: Evidence,
        val anchorId: String, val anchorRevision: Long, val actorId: String, val actorRole: GenesisActorRole,
        val actorRevision: Long, val ownerId: String? = null,
        val attacksPerAction: Int? = null, val profile: JsonObject? = null, val definition: ItemDefinition? = null, val instance: ItemInstance? = null
    )

    private val profileKeys = setOf("name", "species", "className", "subclass", "rules", "level", "baseAbilities",
        "baseMaxHp", "baselineConstitutionModifier", "currentHp", "temporaryHp", "baseAc", "armorUsesDexterity",
        "armorDexterityCap", "speed", "movement", "senses", "reviewedProficiency", "languages", "toolProficiencies",
        "saveProficiencies", "skills", "customSkills", "features", "effects", "conditions", "resources", "attacks",
        "spells", "spellcastingAbility", "spellcastingClassLevel", "slotMaximaOverride", "spellSlots")

    fun parse(op: CampaignSyncOperation): Request {
        require(op.type in types && op.kind == "reference")
        val r = campaignJson.decodeFromJsonElement<Request>(op.fields)
        require(r.version == 1 && r.mode in setOf("CREATE","EDIT") && r.purpose in setOf("GAMEPLAY","MIGRATION"))
        require(r.anchorRevision > 0 && r.actorRevision >= 0 && r.actorRole in setOf(GenesisActorRole.PLAYER,GenesisActorRole.COMPANION))
        checkId(r.anchorId); checkId(r.actorId)
        require(r.evidence.namespace.matches(Regex("[a-z0-9][a-z0-9-]{0,59}")) && r.evidence.namespace != "campaign-sync-membership")
        require(r.evidence.externalId.isNotBlank() && r.evidence.externalId.length <= 180)
        require(r.evidence.revision.isNotBlank() && r.evidence.revision.length <= 90)
        require(r.evidence.url.startsWith("https://") && r.evidence.url.length <= 2000)
        require(r.attacksPerAction == null || op.type == ACTOR && r.mode == "CREATE" && r.attacksPerAction in 1..100)
        if (op.type == ACTOR) {
            val profile = requireNotNull(r.profile)
            require(profile.isNotEmpty() && profile.keys.all(profileKeys::contains) && r.definition == null && r.instance == null)
            if (r.mode == "CREATE") require(r.actorRevision == 0L && "name" in profile && "level" in profile && "rules" in profile)
            else require(r.actorRevision > 0 && r.ownerId == null)
        } else {
            val item = requireNotNull(r.instance)
            require(r.definition?.id == item.definitionId && r.profile == null && r.actorRevision > 0 && r.ownerId == null)
            require(item.containerId == null && item.modesUntil.isEmpty() && (item.donorInstanceIds.isEmpty() || r.purpose == "MIGRATION" && r.mode == "CREATE") && ((item.formId == null && item.formKnown) || r.purpose == "MIGRATION"))
        }
        return r
    }

    fun manual(op: CampaignSyncOperation): Boolean {
        val r = parse(op)
        return op.type == ACTOR || r.mode == "EDIT" || r.purpose == "MIGRATION" ||
            r.instance?.let { it.attuned || it.state != ItemState.CARRIED } == true
    }

    fun scopes(op: CampaignSyncOperation): Set<String> {
        val r = parse(op)
        return buildSet {
            add("continuity"); add("character")
            if (r.actorRole == GenesisActorRole.COMPANION) add("companions")
            if (op.type == ITEM) {
                add("inventory")
                if (r.instance?.pools?.isNotEmpty() == true || r.definition?.activations?.isNotEmpty() == true) addAll(setOf("resources","combat","hp"))
                if (r.mode == "EDIT") addAll(setOf("hp","resources","combat"))
                for (e in requireNotNull(r.definition).effects) {
                    require(e.source.acceptance in setOf(Acceptance.OFFICIAL,Acceptance.ACCEPTED_HOMEBREW))
                    when (val payload = e.payload) {
                        is EffectPayload.Number -> when (payload.target) {
                            NumericTarget.RESOURCE_CAPACITY -> add("resources")
                            NumericTarget.MAX_HP -> add("hp")
                            NumericTarget.ATTACK, NumericTarget.DAMAGE -> add("combat")
                            NumericTarget.ABILITY, NumericTarget.ABILITY_CAP -> if (payload.key == "CON") add("hp") else Unit
                            else -> Unit
                        }
                        is EffectPayload.Proficiency -> Unit
                        else -> add("combat")
                    }
                    if (e.chargeResourceId != null) add("resources")
                }
            } else {
                val keys = requireNotNull(r.profile).keys
                if (keys.any { it in setOf("baseMaxHp","currentHp","temporaryHp","baselineConstitutionModifier","baseAbilities","level","conditions","effects") }) add("hp")
                if (keys.any { it in setOf("resources","spellSlots","slotMaximaOverride","spells","level","effects","features","spellcastingClassLevel") }) add("resources")
                if (r.attacksPerAction != null || keys.any { it in setOf("attacks","effects") }) add("combat")
            }
        }
    }

    suspend fun apply(db: CampaignLedgerDb, campaign: String, op: CampaignSyncOperation, child: String, source: String) {
        scopes(op)
        val r = parse(op)
        val genesis = GenesisAuthorityRepository(db)
        val playable = ReforgeRepository(db)
        val authority = db.genesisAuthorityDao()
        require(authority.campaign(campaign)?.archived == false)
        val anchor = requireNotNull(authority.actor(r.anchorId))
        require(anchor.playable && anchor.campaignId == campaign)
        val current = playable.get(r.actorId)
        val prior = db.partyDao().reference(op.recordId)
        if (r.evidence.namespace == "notion") {
            val normalized = normalizedNotionIdentity(r.evidence.externalId)
            val mapped = db.partyDao().allReferences().filter { it.namespace.equals("notion", true) &&
                normalizedNotionIdentity(it.externalId) == normalized }
            require(mapped.none { it.id != op.recordId }) { "Source already has a mapping. Review its existing identity." }
            if (op.type == ITEM) require(db.ledgerDao().allItems().none {
                !it.notionPageId.isNullOrBlank() && normalizedNotionIdentity(it.notionPageId!!) == normalized && it.id != r.instance?.id
            }) { "Source already belongs to another physical item." }
        }
        val recordType = if (op.type == ITEM) "item" else "character-profile"
        val target = r.instance?.id ?: r.actorId
        require(prior == null || prior.campaignId == campaign && prior.namespace == r.evidence.namespace &&
            prior.externalId == r.evidence.externalId && prior.recordId == target && prior.recordType == recordType)
        require(prior?.revision == op.expectedRevision.takeUnless { it == 0L })
        val association = authority.actor(r.actorId)
        val operationId = genesisChild(child,"native")
        if (op.type == ACTOR && r.mode == "CREATE") {
            require(current == null && association == null && prior == null)
            r.ownerId?.let { require(authority.actor(it)?.campaignId == campaign) }
            require((r.actorRole == GenesisActorRole.COMPANION) == (r.ownerId != null))
            val fields = requireNotNull(r.profile)
            val configured = reforgeJson.decodeFromJsonElement<ReforgeCharacter>(JsonObject(fields + ("id" to JsonPrimitive(r.actorId))))
            val sheet = r.attacksPerAction?.let { configured.copy(turn = configured.turn.copy(attacksPerAction = it)) } ?: configured
            genesis.createActor(sheet.name, r.actorRole, campaign, r.ownerId, sheet, operationId)
        } else {
            require(association != null && association.campaignId == campaign && association.playable && association.role == r.actorRole)
            val sheet = requireNotNull(current).character
            check(!sheet.archived && sheet.revision == r.actorRevision) { "Actor changed since source review." }
            val hp = CharacterCalculator.calculate(sheet).currentHp
            val next = if (op.type == ITEM) {
                val definition = requireNotNull(r.definition)
                val instance = requireNotNull(r.instance)
                if (r.mode == "CREATE") {
                    require(prior == null)
                    require(instance.donorInstanceIds.all { donor -> sheet.items.any { it.id == donor && !it.attuned && it.state in setOf(ItemState.STORED,ItemState.CARRIED) } &&
                        sheet.items.none { donor in it.donorInstanceIds } }) { "Review and release each physical donor before integration." }
                    genesis.createItems(r.actorId,r.actorRevision,definition,listOf(instance),operationId).character
                } else {
                    val existing = sheet.items.single { it.id == instance.id }
                    val supplied = op.fields.getValue("instance").jsonObject
                    val changePools = "pools" in supplied && instance.pools != existing.pools
                    require(!changePools || r.purpose == "MIGRATION") { "Changed charge balances require a reviewed migration and source evidence." }
                    val changeForm = "formId" in supplied || "formKnown" in supplied
                    require(!changeForm || r.purpose == "MIGRATION") { "Use a resolved form action during play." }
                    require(existing.definitionId == definition.id && existing.quantity == instance.quantity &&
                        sheet.items.count { it.definitionId == definition.id } == 1)
                    var changed = sheet.copy(itemDefinitions = sheet.itemDefinitions.map { if (it.id == definition.id) definition else it },
                        items = sheet.items.map { if (it.id == instance.id && changePools) it.copy(pools=instance.pools) else it })
                    changed = GameplayRules.equip(changed, existing.id, instance.state)
                    changed = GameplayRules.attune(changed, existing.id, instance.attuned)
                    changed.copy(items = changed.items.map { item ->
                        if (item.id != instance.id) item else item.copy(
                            operating=instance.operating, modesUntil=if(instance.operating) item.modesUntil else emptyMap(),
                            pools=if(changePools) instance.pools else item.pools,
                            formId=(if(changeForm) instance.formId else item.formId).takeIf { instance.operating },
                            formKnown=!instance.operating || if(changeForm) instance.formKnown else item.formKnown)
                    })
                }
            } else {
                val fields = requireNotNull(r.profile)
                val existing = reforgeJson.encodeToJsonElement(sheet).jsonObject
                // The client supplies just the reviewed fields. Runtime clocks, action economy,
                // item identities and active concentration cannot be replaced with this operation.
                reforgeJson.decodeFromJsonElement<ReforgeCharacter>(JsonObject(existing + fields))
            }
            val baseline = if (op.type == ITEM && r.mode == "CREATE") next else sheet
            val keepHp = r.purpose == "MIGRATION" && r.profile?.containsKey("currentHp") != true
            val corrected = if (keepHp || r.profile?.containsKey("currentHp") == true) {
                val wanted = if (keepHp) hp else next.currentHp
                val cap = CharacterCalculator.calculate(next).maximumHp.value
                require(wanted == null || cap == null || wanted <= cap) { "Current HP exceeds reviewed capacity." }
                if (keepHp && CharacterCalculator.calculate(next).currentHp == wanted) next
                else next.copy(currentHp = wanted, hpDamage = if (wanted != null && cap != null) cap - wanted else null)
            } else next
            if (corrected != baseline) playable.save(r.actorId,baseline.revision,corrected,"Reviewed native source: " + r.evidence.revision,genesisChild(child,"sheet"))
        }
        PartyRecordsRepository(db).saveExternalReference(ExternalRecordReference(op.recordId,r.evidence.namespace,
            r.evidence.externalId,recordType,target,r.evidence.url,campaignId=campaign),
            prior?.revision,source,child)
    }
}
