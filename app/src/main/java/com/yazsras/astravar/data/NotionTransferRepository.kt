package com.yazsras.astravar.data

import android.content.Context
import androidx.room.withTransaction
import com.yazsras.astravar.rules.reforge.*
import java.util.UUID

data class NotionTransferOutcome(
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
class NotionTransferRepository(context: Context) {
    private val db = CampaignLedgerDb.open(context.applicationContext)
    private val playable = GenesisApplicationStorage.open(context.applicationContext).playable
    private val party = PartyRecordsRepository(db)

    suspend fun commit(
        selected: ReforgeSavedCharacter, transfer: NotionCharacterTransfer,
        choices: NotionTransferChoices, recordProvisionalWallet: Boolean,
        commandId: String = UUID.randomUUID().toString()
    ): NotionTransferOutcome = db.withTransaction {
        require(commandId.length in 8..120)
        val before = requireNotNull(playable.get(selected.character.id)) { "The selected character is not saved." }
        check(before.revision == selected.revision) {
            "This character changed after the preview. Review the current sheet and retry."
        }
        check(!before.character.archived) { "Restore the archived sheet before importing." }
        val plan = planNotionCharacterTransfer(before.character, transfer, choices)
        val proof = "Notion private transfer ${transfer.transferId} · ${transfer.character.sourceUrl}"
        val updated = if (plan.character != before.character) {
            playable.save(before.character.id, before.revision, plan.character,
                "Reviewed Notion stats/inventory import", "$commandId:playable")
        } else before
        var created = false
        var already = false
        if (recordProvisionalWallet) {
            val gp = transfer.provisionalGpEquivalent
                ?: error("The Notion record contains no calculated GP-equivalent figure.")
            require(!transfer.walletVerified) { "A verified-denomination transfer requires the ordinary wallet workflow." }
            val holder = requireNotNull(db.recordsDao().characterProfile(before.character.id)) {
                "Connect this playable character to its campaign identity before recording a wallet."
            }
            val walletId = "notion-wallet-${campaignHash(transfer.transferId).take(56)}"
            val existing = db.partyDao().allWallets().filter { it.holderProfileId == holder.id }
            val same = existing.firstOrNull { it.id == walletId }
            if (same != null) {
                check(same.gold == gp && same.name.contains("provisional", ignoreCase = true)) {
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
                    campaignId = "local-campaign"
                ), source = proof, commandId = "$commandId:wallet")
                created = true
            }
        }
        NotionTransferOutcome(updated, plan.added.size, plan.alreadyPresent.size, created, already, plan.warnings)
    }
}
