package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.SecureDice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The existing companion sheet remains a source record; playable mechanics require explicit review. */
@Composable internal fun ReforgeCompanionAttackReviewButton(repository: CampaignLedgerRepository, profileId: String) {
    val context = LocalContext.current.applicationContext
    // Reading the preserved source sheet needs no playable-store connection.
    // Open that store only when the player explicitly starts a review.
    var local by remember(context) { mutableStateOf<ReforgeRepository?>(null) }
    val saved by remember(local, profileId) { local?.observe(profileId) ?: flowOf<ReforgeSavedCharacter?>(null) }.collectAsState(initial = null)
    var review by remember(profileId) { mutableStateOf<ReforgeCompanionActionReview?>(null) }
    var expectedRevision by remember(profileId) { mutableStateOf<Long?>(null) }
    var selected by remember(profileId) { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember(profileId) { mutableStateOf("") }
    var busy by remember(profileId) { mutableStateOf(false) }
    var reviewed by remember(profileId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PlayerOutlinedButton(enabled = !busy && saved?.character?.archived != true, onClick = {
        busy = true; message = "Reading saved source…"
        scope.launch {
            try {
                val store = local ?: ReforgeRepository.open(context).also { local = it }
                val preview = store.readLegacyPreview(repository, profileId)
                review = reviewCompanionActions(preview)
                expectedRevision = store.get(profileId)?.revision; selected = emptySet(); reviewed = false; message = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Source could not be read." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Reading source…" else "Review playable attacks") }
    if (saved?.character?.archived == true) Text("Restore this companion's playable sheet from Character → Switch before reviewing attacks.", color = RebuildPalette.GoldBright)
    if (message.isNotBlank()) Text(message, color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
    review?.let { current ->
        val stale = expectedRevision != saved?.revision
        PlayerSheet("Review companion attacks", onDismiss = { if (!busy) review = null }, dirty = selected.isNotEmpty(), actions = {
            PlayerButton(enabled = !busy && expectedRevision != null && !stale && saved?.character?.archived != true && selected.isNotEmpty() && reviewed,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            requireNotNull(local).commitCompanionActions(repository, current, selected, requireNotNull(expectedRevision))
                            review = null; message = "Attacks saved on this device. Select this companion from Character → Switch to roll."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "Attacks were not saved." }
                        finally { busy = false }
                    }
                }) { Text("Add selected attacks") }
        }) {
            Text("Only confirmed, fully specified source attacks are eligible. Their fixed bonuses and complete damage formulas are preserved. Nothing rolls or spends a resource during review.", color = RebuildPalette.Muted)
            if (saved == null) Text("First review and adopt this companion from Character. Its original profile and this source remain unchanged.", color = RebuildPalette.GoldBright)
            if (stale) Text("The playable companion changed. Close and review again before saving.", color = MaterialTheme.colorScheme.error)
            if (current.candidates.isEmpty()) Text("No attacks can be converted without further structured review.", color = RebuildPalette.Muted)
            current.candidates.forEach { candidate ->
                val collision = saved?.character?.attacks?.firstOrNull { it.id == candidate.attack.id }?.let { it != candidate.attack } == true
                PlayerCard {
                    Toggle(candidate.attack.name, candidate.attack.id in selected, enabled = !busy && !collision) { checked ->
                        selected = if (checked) selected + candidate.attack.id else selected - candidate.attack.id; reviewed = false
                    }
                    Text("To hit ${reforgeSigned(candidate.attack.fixedAttackBonus)} · ${candidate.attack.actionType}", color = RebuildPalette.Ice)
                    Text(candidate.attack.damage.joinToString(" + ") { "${SecureDice.format(it.formula)} ${it.type.name.lowercase()}" })
                    Text("Critical hit: double damage dice, preserve flat bonuses. No inferred multiattack or charges.", style = MaterialTheme.typography.bodySmall, color = RebuildPalette.Muted)
                    if (candidate.attack.range.isNotBlank()) Text(candidate.attack.range, color = RebuildPalette.Muted)
                    if (collision) Text("A different playable attack uses this identity. Reconcile it first.", color = MaterialTheme.colorScheme.error)
                    PlayerDisclosure("Source") { ReforgeRichText(candidate.source) }
                }
            }
            if (current.unavailable.isNotEmpty()) PlayerDisclosure("Requires manual setup", initiallyOpen = true) {
                current.unavailable.forEach { (name, reason) -> Text(name, style = MaterialTheme.typography.titleSmall); Text(reason, color = RebuildPalette.Muted) }
            }
            Toggle("I reviewed the selected mechanical interpretation", reviewed, enabled = !busy && selected.isNotEmpty()) { reviewed = it }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            Text("This adds attacks only to the local playable copy. Current HP, charges, source records and synchronization are unchanged.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
