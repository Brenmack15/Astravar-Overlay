package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException

/** Exposes the ledger editor only for a proven source or managed identity of this playable actor. */
@Composable internal fun LegacyCharacterSources(repository: CampaignLedgerRepository, local: ReforgeRepository,
    saved: ReforgeSavedCharacter, close: () -> Unit, openReferences: () -> Unit) {
    var sourceId by remember(saved.character.id, saved.revision) { mutableStateOf<String?>(null) }
    var message by remember(saved.character.id, saved.revision) { mutableStateOf("Reading source identity…") }
    LaunchedEffect(saved.character.id, saved.revision) {
        try {
            sourceId = local.inspectCampaignIdentity(repository, saved)?.profile?.id
            message = if (sourceId == null) "No separate source record is linked to this actor." else ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "The source identity needs review before its record can be opened." }
    }
    PlayerSheet("Character source evidence", close) {
        Text(saved.character.name)
        Text(saved.origin.label)
        saved.origin.warnings.forEach { Text(it) }
        if (message.isNotEmpty()) Text(message)
        sourceId?.let { id ->
            PlayerDisclosure("Original campaign record", initiallyOpen = true) {
                CharacterOverviewV2(repository, profileId = id)
            }
        }
        PlayerTextButton(openReferences) { Text("Armor source references") }
    }
}
