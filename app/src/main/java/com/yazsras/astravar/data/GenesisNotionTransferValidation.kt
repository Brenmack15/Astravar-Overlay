package com.yazsras.astravar.data

import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.encodeToString

/** Replay a frozen import against its own child history, never today's later character. */
internal fun genesisValidateNotionTransfer(
    receipt: GenesisAuthorityReceipt,
    changes: Map<String, ReforgeChange>,
    authorityReceipts: Map<String, GenesisAuthorityReceipt>,
    recordReceipts: Map<String, CommandReceipt>
): NotionTransferOutcome {
    require(receipt.kind == "notion-transfer")
    val request = campaignJson.decodeFromString<NotionTransferRequest>(receipt.requestJson)
    val outcome = campaignJson.decodeFromString<NotionTransferOutcome>(receipt.outcomeJson)
    require(request.verifiedBackupFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Import backup proof is missing." }
    val selected = request.selected
    require(selected.revision == selected.character.revision && !selected.character.archived)
    validateCharacter(selected.character)
    // A no-op import has no new playable child. Its frozen starting state must
    // still be anchored to the one actual immutable mechanical revision.
    val selectedHistory = changes.values.filter { it.profileId == selected.character.id &&
        it.resultingRevision == selected.revision && it.outcomeType in setOf("CHARACTER", "ACTION", "INVENTORY") }
    require(selectedHistory.size == 1 && reforgeChangeCharacter(selectedHistory.single()) == selected) {
        "Import selected revision has no matching immutable playable history."
    }
    val plan = planNotionCharacterTransfer(selected.character, request.transfer, request.choices)
    require(outcome.itemsAdded == plan.added.size && outcome.itemsPreviouslyPresent == plan.alreadyPresent.size &&
        outcome.warnings == plan.warnings) { "Import summary differs from its reviewed source." }
    require(outcome.profile.origin == selected.origin && outcome.profile.createdAt == selected.createdAt) {
        "Import changed the original character provenance."
    }
    var current = selected
    val definitions = plan.character.itemDefinitions.filter { candidate -> selected.character.itemDefinitions.none { it.id == candidate.id } }
    val children = definitions.map { definition ->
        definition to authorityReceipts[genesisChild(receipt.commandId, "notion-item:${definition.id}")]
    }
    if (children.any { it.second != null }) children.forEach { (definition, optional) ->
        val child = requireNotNull(optional) { "Managed import item creation proof is missing." }
        val instances = plan.character.items.filter { it.definitionId == definition.id }
        val expectedRequest = campaignJson.encodeToString(listOf(current.character.id, current.revision.toString(),
            reforgeJson.encodeToString(definition), reforgeJson.encodeToString(instances)))
        require(child.kind == "create-items" && child.requestJson == expectedRequest &&
            child.fingerprint == genesisAuthorityFingerprint(child.kind, expectedRequest)) { "Import item creation differs from its exact source request." }
        val next = reforgeJson.decodeFromString<ReforgeSavedCharacter>(child.outcomeJson)
        val change = requireNotNull(changes[genesisChild(child.commandId, "playable")]) { "Imported item playable history is missing." }
        require(reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(change.beforeJson)) == current &&
            reforgeChangeCharacter(change) == next && next.revision == Math.addExact(current.revision, 1L) &&
            next.character == genesisCreatedItemsCharacter(current.character, definition, instances).copy(revision = next.revision) &&
            next.origin == current.origin && next.createdAt == current.createdAt) { "Import item history changed unreviewed state." }
        current = next
    }
    val intended = plan.character.copy(revision = current.revision)
    val finalChild = changes[genesisChild(receipt.commandId, "notion-playable")]
    if (intended != current.character) {
        val child = requireNotNull(finalChild) { "Import's final playable receipt is missing." }
        val next = requireNotNull(reforgeChangeCharacter(child))
        val operation = "Reviewed Notion stats/inventory import"
        val fingerprint = reforgeHash(reforgeJson.encodeToString(listOf("save", current.character.id,
            current.revision.toString(), operation, reforgeJson.encodeToString(intended))))
        require(child.profileId == current.character.id && child.operation == operation && child.fingerprint == fingerprint &&
            child.priorRevision == current.revision && child.resultingRevision == Math.addExact(current.revision, 1L) &&
            reforgeJson.decodeFromString<ReforgeSavedCharacter>(requireNotNull(child.beforeJson)) == current &&
            next.character == intended.copy(revision = child.resultingRevision) &&
            next.origin == current.origin && next.createdAt == current.createdAt) { "Final import changed unreviewed mechanics or history." }
        current = next
    } else require(finalChild == null) { "Unchanged import has an unexpected playable mutation." }
    require(current == outcome.profile) { "Import outcome differs from its immutable child history." }
    validateCharacter(outcome.profile.character)
    require(if (request.recordProvisionalWallet) outcome.walletAdded.xor(outcome.walletPreviouslyPresent)
        else !outcome.walletAdded && !outcome.walletPreviouslyPresent) { "Import wallet result differs from the reviewed choice." }
    val walletChild = recordReceipts[genesisChild(receipt.commandId, "notion-wallet")]
    if (outcome.walletAdded) {
        require(!request.transfer.walletVerified)
        val child = requireNotNull(walletChild) { "Imported wallet receipt is missing." }
        val wallet = campaignJson.decodeFromString<PartyWallet>(child.outcomeJson)
        require(child.kind == "wallet" && child.recordId == wallet.id && child.resultingRevision == 1L && wallet.revision == 1L &&
            wallet.id == "notion-wallet-${campaignHash(request.transfer.transferId).take(56)}" &&
            wallet.holderProfileId == selected.character.id && wallet.campaignId.isNotBlank() &&
            wallet.gold == requireNotNull(request.transfer.provisionalGpEquivalent) && wallet.copper == null && wallet.silver == null &&
            wallet.electrum == null && wallet.platinum == null && wallet.name == "${request.transfer.character.name} — provisional GP equivalent" &&
            wallet.source == "UNRECONCILED GP-EQUIVALENT ESTIMATE, not verified coin denominations. Notion private transfer ${request.transfer.transferId} · ${request.transfer.character.sourceUrl}") {
            "Imported wallet differs from the reviewed provisional estimate."
        }
    } else {
        require(walletChild == null) { "Import unexpectedly wrote a wallet." }
        if (outcome.walletPreviouslyPresent) {
            require(!request.transfer.walletVerified)
            val walletId = "notion-wallet-${campaignHash(request.transfer.transferId).take(56)}"
            val gp = requireNotNull(request.transfer.provisionalGpEquivalent)
            // Repeated imports write no wallet child. Their claim still needs an
            // immutable matching wallet, even after a later balance edit.
            // Android wall-clock timestamps do not establish causal ordering.
            require(recordReceipts.values.any { prior ->
                prior.kind == "wallet" && prior.recordId == walletId &&
                    campaignJson.decodeFromString<PartyWallet>(prior.outcomeJson).let { wallet ->
                        wallet.id == walletId && wallet.revision == prior.resultingRevision &&
                            wallet.holderProfileId == selected.character.id && wallet.campaignId.isNotBlank() &&
                            wallet.gold == gp && wallet.name.contains("provisional", ignoreCase = true)
                    }
            }) { "Previously imported wallet has no matching immutable history." }
        }
    }
    val newIds = plan.character.items.map { it.id }.toSet() - selected.character.items.map { it.id }.toSet()
    request.transfer.inventory.filter { "notion-instance-${it.sourceId}" in newIds }.forEach { source ->
        recordReceipts[genesisChild(receipt.commandId, "notion-reference:${source.sourceId}")]?.let { child ->
            val reference = campaignJson.decodeFromString<ExternalRecordReference>(child.outcomeJson)
            require(child.kind == "external-reference" && child.recordId == reference.id && reference.revision == 1L &&
                reference.id == "notion-reference-${campaignHash(source.sourceId).take(48)}" && reference.namespace == "notion" &&
                reference.externalId == source.sourceId && reference.recordType == "item" &&
                reference.recordId == "notion-instance-${source.sourceId}" && reference.url == source.sourceUrl) {
                "Imported source identity differs from its original Notion record."
            }
        }
    }
    return outcome
}

/** Narrow current-commit readback: only the deterministic children of this import are read. */
internal suspend fun genesisRequireNotionTransfer(db: CampaignLedgerDb, receipt: GenesisAuthorityReceipt) {
    val request = campaignJson.decodeFromString<NotionTransferRequest>(receipt.requestJson)
    val plan = planNotionCharacterTransfer(request.selected.character, request.transfer, request.choices)
    val authority = mutableMapOf<String, GenesisAuthorityReceipt>()
    val changes = mutableMapOf<String, ReforgeChange>()
    val records = mutableMapOf<String, CommandReceipt>()
    // Read only IDs for this selected revision, then use the existing chunked
    // reader for its snapshots. Never load another character or complete backup.
    val selectedHistoryIds = db.openHelper.writableDatabase.query(
        "SELECT commandId FROM reforge_changes WHERE profileId=? AND resultingRevision=? AND outcomeType IN ('CHARACTER','ACTION','INVENTORY')",
        arrayOf<Any>(request.selected.character.id, request.selected.revision)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    selectedHistoryIds.forEach { id -> genesisNotionChange(db, id)?.let { changes[it.commandId] = it } }
    genesisNotionChange(db, genesisChild(receipt.commandId, "notion-playable"))?.let { changes[it.commandId] = it }
    db.recordsDao().receipt(genesisChild(receipt.commandId, "notion-wallet"))?.let { records[it.commandId] = it }
    if (request.recordProvisionalWallet) {
        val walletId = "notion-wallet-${campaignHash(request.transfer.transferId).take(56)}"
        val walletHistoryIds = db.openHelper.writableDatabase.query(
            "SELECT commandId FROM command_receipts WHERE kind='wallet' AND recordId=?", arrayOf(walletId)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        walletHistoryIds.forEach { id -> db.recordsDao().receipt(id)?.let { records[it.commandId] = it } }
    }
    plan.character.itemDefinitions.filter { candidate -> request.selected.character.itemDefinitions.none { it.id == candidate.id } }.forEach { definition ->
        readGenesisAuthorityReceipt(db, genesisChild(receipt.commandId, "notion-item:${definition.id}"))?.let { child ->
            authority[child.commandId] = child
            genesisNotionChange(db, genesisChild(child.commandId, "playable"))?.let { changes[it.commandId] = it }
        }
    }
    request.transfer.inventory.forEach { source ->
        db.recordsDao().receipt(genesisChild(receipt.commandId, "notion-reference:${source.sourceId}"))?.let { records[it.commandId] = it }
    }
    val outcome = genesisValidateNotionTransfer(receipt, changes, authority, records)
    require(ReforgeRepository(db).get(outcome.profile.character.id) == outcome.profile) { "Import failed current playable readback." }
    genesisRequireProjection(db, outcome.profile.character.id, outcome.profile)
    if (request.recordProvisionalWallet) {
        val wallet = requireNotNull(db.partyDao().wallet("notion-wallet-${campaignHash(request.transfer.transferId).take(56)}"))
        require(wallet.holderProfileId == outcome.profile.character.id && wallet.gold == request.transfer.provisionalGpEquivalent &&
            wallet.campaignId == db.genesisAuthorityDao().actor(outcome.profile.character.id)?.campaignId) { "Import wallet failed current campaign readback." }
    }
}

private fun genesisNotionChange(db: CampaignLedgerDb, commandId: String): ReforgeChange? =
    GenesisBackupRow.read(db.openHelper.writableDatabase, "reforge_changes",
        "commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType".split(','),
        "commandId", keyColumn = "commandId", key = commandId) {
        ReforgeChange(it.text("commandId"), it.text("profileId"), it.text("operation"), it.text("fingerprint"),
            it.long("priorRevision"), it.long("resultingRevision"), it.long("createdAt"), it.optionalText("beforeJson"),
            it.text("afterJson"), it.text("outcomeType"))
    }.singleOrNull()
