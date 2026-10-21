package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Local recorded denominations are independent. No automatic conversion or inferred opening balance. */
@Composable internal fun PartyWalletV2(repository: CampaignLedgerRepository, memberId: String) {
    val wallets by repository.party.wallets.collectAsState(initial = emptyList())
    val profiles by repository.records.characterProfiles.collectAsState(initial = emptyList())
    var exchangeOpen by rememberSaveable(memberId) { mutableStateOf(false) }
    var editing by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    var snapshotJson by rememberSaveable(memberId) { mutableStateOf<String?>(null) }
    val json = remember { Json { encodeDefaults = true } }
    fun edit(wallet: PartyWallet?, mode: String) { snapshotJson = wallet?.let { json.encodeToString(it) }; editing = mode }
    PlayerHeading("Currency")
    Text("Sourced local balances and transactional income / expenses. Unknown balances stay unknown. No coins are automatically converted.",
        color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    PlayerButton(onClick = { edit(null, "balance") }, enabled = profiles.any { it.id == memberId }) { Text("Add wallet") }
    PlayerOutlinedButton(onClick = { exchangeOpen = true }, enabled = profiles.any { it.id == memberId }) { Text("Item & currency exchange") }
    if (exchangeOpen) PartyExchangeV21(repository, memberId, onDismiss = { exchangeOpen = false })
    if (profiles.none { it.id == memberId }) Text("Save a sourced character status before adding a wallet.", color = RebuildPalette.Ice)
    val held = wallets.filter { it.holderProfileId == memberId }
    if (held.isEmpty()) PlayerEmpty("No wallet recorded", "Opening Party never assumes a zero balance or imports currency from old conversations.")
    held.forEach { wallet -> PlayerCard {
        Text(wallet.name, style = MaterialTheme.typography.titleMedium)
        Text("${wallet.copper ?: "?"} cp · ${wallet.silver ?: "?"} sp · ${wallet.gold ?: "?"} gp · ${wallet.electrum ?: "?"} ep · ${wallet.platinum ?: "?"} pp", color = RebuildPalette.Gold)
        Text("Revision ${wallet.revision} · ${wallet.source}", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        PlayerButton(onClick = { edit(wallet, "change") }) { Text("Record income / expense") }
        PlayerTextButton(onClick = { edit(wallet, "balance") }) { Text("Record sourced balance correction") }
    } }
    editing?.let { mode ->
        val snapshot = snapshotJson?.let { json.decodeFromString<PartyWallet>(it) }
        PartyWalletEditorV2(repository, memberId, mode, snapshot,
            wallets.firstOrNull { it.id == snapshot?.id }?.revision, onDismiss = { editing = null })
    }
}

@Composable private fun PartyWalletEditorV2(repository: CampaignLedgerRepository, memberId: String, mode: String,
    snapshot: PartyWallet?, latestRevision: Long?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(snapshot?.name.orEmpty()) }
    var copper by rememberSaveable { mutableStateOf(if (mode == "balance") snapshot?.copper?.toString().orEmpty() else "") }
    var silver by rememberSaveable { mutableStateOf(if (mode == "balance") snapshot?.silver?.toString().orEmpty() else "") }
    var gold by rememberSaveable { mutableStateOf(if (mode == "balance") snapshot?.gold?.toString().orEmpty() else "") }
    var electrum by rememberSaveable { mutableStateOf(if (mode == "balance") snapshot?.electrum?.toString().orEmpty() else "") }
    var platinum by rememberSaveable { mutableStateOf(if (mode == "balance") snapshot?.platinum?.toString().orEmpty() else "") }
    var source by rememberSaveable { mutableStateOf("") }
    val commandId = rememberSaveable { UUID.randomUUID().toString() }
    val walletId = rememberSaveable { snapshot?.id ?: UUID.randomUUID().toString() }
    val actions = rememberLocalRecordActions()
    val stale = snapshot?.revision != latestRevision
    fun amount(text: String): Long? = text.trim().takeIf(String::isNotEmpty)?.let {
        it.toLongOrNull() ?: error("Use whole coin counts, or leave blank.")
    }
    val invalid = listOf(copper, silver, gold, electrum, platinum).any { it.isNotBlank() && it.toLongOrNull() == null }
    val dirty = name != snapshot?.name.orEmpty() || source.isNotEmpty() ||
        copper != (if (mode == "balance") snapshot?.copper?.toString().orEmpty() else "") ||
        silver != (if (mode == "balance") snapshot?.silver?.toString().orEmpty() else "") ||
        gold != (if (mode == "balance") snapshot?.gold?.toString().orEmpty() else "") ||
        electrum != (if (mode == "balance") snapshot?.electrum?.toString().orEmpty() else "") ||
        platinum != (if (mode == "balance") snapshot?.platinum?.toString().orEmpty() else "")
    PlayerSheet(if (mode == "balance") "Record wallet balances" else "Record currency change", onDismiss, dirty = dirty, actions = {
        PlayerButton(onClick = { actions.run {
            val receipt = if (mode == "balance") repository.party.saveWallet(
                (snapshot ?: PartyWallet(id = walletId, holderProfileId = memberId, name = name.trim())).copy(
                    name = name.trim(), copper = amount(copper), silver = amount(silver), gold = amount(gold), electrum = amount(electrum), platinum = amount(platinum)),
                snapshot?.revision, source.trim(), commandId)
            else repository.party.adjustCurrency(CurrencyCommand(walletId, snapshot?.revision ?: error("Wallet unavailable."),
                amount(copper) ?: 0, amount(silver) ?: 0, amount(gold) ?: 0, source.trim(), commandId, amount(electrum) ?: 0, amount(platinum) ?: 0))
            onDismiss(); "Currency saved; receipt ${receipt.commandId}."
        } }, enabled = !actions.busy && !stale && !invalid && source.isNotBlank() && (mode != "balance" || name.isNotBlank())) { Text("Save currency record") }
    }) {
        if (stale) Text("This wallet changed. Close and reopen before saving.", color = MaterialTheme.colorScheme.error)
        if (mode == "balance") {
            Field("Wallet name", name, { name = it })
            Text("A sourced opening balance or correction can remain unknown. A normal gain or expense belongs in Record income / expense.", color = RebuildPalette.Muted)
        } else {
            Text("${snapshot?.name.orEmpty()} · current ${snapshot?.copper ?: "?"} cp / ${snapshot?.silver ?: "?"} sp / ${snapshot?.gold ?: "?"} gp / ${snapshot?.electrum ?: "?"} ep / ${snapshot?.platinum ?: "?"} pp", color = RebuildPalette.Gold)
            Text("Positive = gained; negative = spent; blank = no change. An unknown denomination cannot be spent until its sourced opening balance is recorded.", color = RebuildPalette.Muted)
        }
        val hint = if (mode == "balance") "blank = unknown" else "+ gained / − spent"
        Field("Copper · $hint", copper, { copper = it })
        Field("Silver · $hint", silver, { silver = it })
        Field("Gold · $hint", gold, { gold = it })
        Field("Electrum · $hint", electrum, { electrum = it })
        Field("Platinum · $hint", platinum, { platinum = it })
        if (invalid) Text("Enter whole numbers only.", color = MaterialTheme.colorScheme.error)
        Field("Currency event source (required)", source, { source = it }, single = false, enabled = !actions.busy)
        LocalRecordStatus(actions.message, actions.busy)
    }
}
