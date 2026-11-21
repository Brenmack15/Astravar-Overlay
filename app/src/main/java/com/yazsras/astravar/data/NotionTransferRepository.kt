package com.yazsras.astravar.data

import android.content.Context
import androidx.room.withTransaction
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable data class NotionTransferRequest(
    val selected: ReforgeSavedCharacter, val transfer: NotionCharacterTransfer,
    val choices: NotionTransferChoices, val recordProvisionalWallet: Boolean,
    val verifiedBackupFingerprint: String
)

@Serializable data class NotionTransferOutcome(
    val profile: ReforgeSavedCharacter,
    val itemsAdded: Int, val itemsPreviouslyPresent: Int,
    val walletAdded: Boolean, val walletPreviouslyPresent: Boolean,
    val warnings: List<String>
)

/**
 * One explicit import into a selected existing playable identity. This never name-matches another
 * campaign actor, writes Notion, advances time, or changes an existing wallet/physical item.
 * The same canonical Room transaction protects both the sheet and optional wallet.
 */
class NotionTransferRepository internal constructor(
    private val db: CampaignLedgerDb,
    private val playable: ReforgeRepository = ReforgeRepository(db),
    private val backup: GenesisAuthorityBackupRepository = GenesisAuthorityBackupRepository(db)
) {
    private constructor(storage: GenesisApplicationStorage) : this(storage.db, storage.playable, storage.backup)
    constructor(context: Context) : this(GenesisApplicationStorage.open(context.applicationContext))
    private val party = PartyRecordsRepository(db)
    private val authority = GenesisAuthorityRepository(db)

    suspend fun commit(
        selected: ReforgeSavedCharacter, transfer: NotionCharacterTransfer,
        choices: NotionTransferChoices, recordProvisionalWallet: Boolean,
        verifiedBackupFingerprint: String,
        commandId: String = UUID.randomUUID().toString()
    ): NotionTransferOutcome {
        // Await canonical storage initialization before acquiring its transaction.
        playable.get(selected.character.id)
        return db.withTransaction {
            require(commandId.length in 8..120)
            val request = NotionTransferRequest(selected, transfer, choices, recordProvisionalWallet, verifiedBackupFingerprint)
            val requestJson = campaignJson.encodeToString(request)
            val fingerprint = genesisAuthorityFingerprint("notion-transfer", requestJson)
            readGenesisAuthorityReceipt(db, commandId)?.let { receipt ->
                require(receipt.kind == "notion-transfer" && receipt.requestJson == requestJson && receipt.fingerprint == fingerprint) {
                    "This import command was already used with different content. No records changed."
                }
                return@withTransaction campaignJson.decodeFromString<NotionTransferOutcome>(receipt.outcomeJson)
            }
            require(db.recordsDao().receipt(commandId) == null && db.ledgerDao().event(commandId) == null &&
                db.reforgeDao().change(commandId) == null && db.reforgeDao().roll(commandId) == null) {
                "This command identity belongs to another saved operation."
            }
            require(verifiedBackupFingerprint.matches(Regex("[0-9a-f]{64}"))) {
                "Save and verify a complete current backup before importing."
            }
            val currentBackup = backup.snapshot()
            check(genesisAuthorityBackupFingerprint(currentBackup) == verifiedBackupFingerprint) {
                "Saved backup no longer matches the current records. Make a new verified backup."
            }
            val before = requireNotNull(playable.get(selected.character.id)) { "The selected character is not saved." }
            check(before.revision == selected.revision) {
                "This character changed after the preview. Review the current sheet and retry."
            }
            check(!before.character.archived) { "Restore the archived sheet before importing." }
            val plan = planNotionCharacterTransfer(before.character, transfer, choices)
            val proof = "Notion private transfer ${transfer.transferId} · ${transfer.character.sourceUrl}"
            val newItemIds = plan.character.items.map { it.id }.toSet() - before.character.items.map { it.id }.toSet()
            val newSources = transfer.inventory.filter { "notion-instance-${it.sourceId}" in newItemIds }
            // The explicit backup already contains this source index. Reuse it instead of another
            // database export, and never recreate an older imported item under a new physical ID.
            val sourceMappings = buildList {
                currentBackup.campaign.items.forEach { row ->
                    row.notionPageId?.takeIf { it.isNotBlank() }?.let { add(normalizedNotionIdentity(it) to row.id) }
                }
                currentBackup.campaign.records.externalReferences.filter { it.namespace.equals("notion", true) && it.recordType == "item" }
                    .forEach { add(normalizedNotionIdentity(it.externalId) to it.recordId) }
            }
            newSources.forEach { source ->
                check(sourceMappings.none { it.first == normalizedNotionIdentity(source.sourceId) && it.second != "notion-instance-${source.sourceId}" }) {
                    "${source.name} already has a campaign source identity. Add it through campaign inventory and review ownership; no duplicate was imported."
                }
            }
            var updated = before
            val actor = db.genesisAuthorityDao().actor(before.character.id)
            if (actor?.playable == true) {
                // Managed physical identities need the same audited creation path as the item
                // editor. A raw playable save correctly refuses unreviewed new identities.
                val existingDefinitions = before.character.itemDefinitions.map { it.id }.toSet()
                for (definition in plan.character.itemDefinitions.filter { it.id !in existingDefinitions }) {
                    val instances = plan.character.items.filter { it.definitionId == definition.id }
                    updated = authority.createItems(before.character.id, updated.revision, definition, instances,
                        genesisChild(commandId, "notion-item:${definition.id}"))
                }
                actor.campaignId?.let { campaignId ->
                    newSources.forEach { source ->
                        party.saveExternalReference(ExternalRecordReference(
                            id = "notion-reference-${campaignHash(source.sourceId).take(48)}", namespace = "notion",
                            externalId = source.sourceId, recordType = "item", recordId = "notion-instance-${source.sourceId}",
                            url = source.sourceUrl, campaignId = campaignId), source = proof,
                            commandId = genesisChild(commandId, "notion-reference:${source.sourceId}"))
                    }
                }
            }
            val finalCharacter = plan.character.copy(revision = updated.revision)
            if (finalCharacter != updated.character) {
                updated = playable.save(before.character.id, updated.revision, finalCharacter,
                    "Reviewed Notion stats/inventory import", genesisChild(commandId, "notion-playable"))
            }
            var created = false
            var already = false
            if (recordProvisionalWallet) {
                val gp = transfer.provisionalGpEquivalent
                    ?: error("The Notion record contains no calculated GP-equivalent figure.")
                require(!transfer.walletVerified) { "A verified-denomination transfer requires the ordinary wallet workflow." }
                val holder = requireNotNull(db.recordsDao().characterProfile(before.character.id)) {
                    "Connect this playable character to its campaign identity before recording a wallet."
                }
                val membership = requireNotNull(db.genesisAuthorityDao().actor(holder.id)) {
                    "Review this character's campaign membership before recording a wallet."
                }
                val campaignId = requireNotNull(membership.campaignId) {
                    "Choose this character's campaign explicitly before recording a wallet."
                }
                require(db.genesisAuthorityDao().campaign(campaignId)?.archived == false) {
                    "The selected campaign is unavailable or archived. Review membership before recording a wallet."
                }
                val walletId = "notion-wallet-${campaignHash(transfer.transferId).take(56)}"
                val existing = db.partyDao().allWallets().filter { it.holderProfileId == holder.id }
                val same = existing.firstOrNull { it.id == walletId }
                if (same != null) {
                    check(same.gold == gp && same.campaignId == campaignId && same.name.contains("provisional", ignoreCase = true)) {
                        "The previously imported wallet changed. Review it; no balance was overwritten."
                    }
                    already = true
                } else {
                    check(existing.isEmpty()) {
                        "An existing wallet is already linked to this character. Reconcile it manually; the Notion estimate was not stacked or substituted."
                    }
                    party.saveWallet(PartyWallet(
                        id = walletId, holderProfileId = holder.id,
                        name = "${transfer.character.name} — provisional GP equivalent",
                        gold = gp,
                        source = "UNRECONCILED GP-EQUIVALENT ESTIMATE, not verified coin denominations. " +
                            "${transfer.currency.size} source rows; ${transfer.character.sourceUrl}",
                        campaignId = campaignId
                    ), source = "UNRECONCILED GP-EQUIVALENT ESTIMATE, not verified coin denominations. $proof",
                        commandId = genesisChild(commandId, "notion-wallet"))
                    created = true
                }
            }
            val outcome = NotionTransferOutcome(updated, plan.added.size, plan.alreadyPresent.size, created, already, plan.warnings)
            val receipt = GenesisAuthorityReceipt(commandId, "notion-transfer", fingerprint, requestJson,
                campaignJson.encodeToString(outcome), System.currentTimeMillis())
            db.genesisAuthorityDao().insert(receipt)
            genesisRequireAuthorityCommand(db, receipt)
            outcome
        }
    }
}
