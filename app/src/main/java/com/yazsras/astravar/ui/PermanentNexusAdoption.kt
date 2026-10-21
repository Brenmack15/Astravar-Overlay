package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.rules.*
import java.util.UUID

/** A one-time review for stranded legacy saves; ordinary gameplay never offers core swaps. */
@Composable fun PermanentNexusAdoption(s: Campaign, a: Actions) {
    if (!Engine.needsNexusAdoption(s)) return
    var open by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var baseline by rememberSaveable { mutableLongStateOf(s.revision) }
    val commandId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val newRecordId by rememberSaveable { mutableStateOf("internal-nexus-${UUID.randomUUID()}") }
    val intact = s.cores.filterNot { it.destroyed }
    val recordExisting = intact.isEmpty()
    PlayerEntry("Review legacy nexus", "This older save has no active permanent nexus. Review its source before enabling current play.", "Core", {
        baseline = s.revision; confirmed = false; a.message = ""; open = true
    })
    if (!open) return
    PlayerSheet("Review permanent nexus", { if (!a.busy) open = false }, dirty = source.isNotBlank() || confirmed,
        actions = {
            PlayerButton(enabled = !a.busy && s.revision == baseline && confirmed && source.isNotBlank() && source.length <= 2000 &&
                (recordExisting || intact.any { it.id == selectedId }), onClick = {
                val target = if (recordExisting) newRecordId else selectedId ?: return@PlayerButton
                a.run("Permanent nexus record reconciled; existing resources retained") {
                    a.repo.mutate(commandId, "Sourced permanent nexus adoption") { current ->
                        Engine.adoptPermanentNexus(current, baseline, target, source,
                            recordConfirmedExistingNexus = recordExisting, confirmed = confirmed)
                    }
                    open = false
                }
            }) { Text(if (recordExisting) "Record confirmed nexus" else "Adopt reviewed record") }
        }) {
        Text("This reconciles an old application record. It does not craft, repair or replace a core in the campaign.", color = RebuildPalette.Muted)
        if (recordExisting) {
            Text("Every previous core is destroyed or absent. Those records and their history stay unchanged.")
            Text("Continue only when a resolved source confirms Astravar already has an intact, currently available internal nexus with no stored payloads. If its availability or reservoir contents are unknown, close this review and recover that source first.")
            Toggle("I confirm that already established, available nexus and empty reservoir", confirmed, !a.busy) { confirmed = it }
        } else {
            Choices("Existing intact record", intact, intact.firstOrNull { it.id == selectedId },
                display = { "${it.name} · ${it.readiness(s.gameSeconds)} · ${it.payloads.size} payloads" }, enabled = !a.busy) {
                selectedId = it.id; confirmed = false
            }
            Text("The selected record keeps its ID, payloads and remaining in-world overload. Other cores remain archived unchanged.")
            Toggle("This sourced record represents today's established permanent nexus", confirmed, !a.busy) { confirmed = it }
        }
        Field("Resolved source or checkpoint", source, { source = it }, single = false, enabled = !a.busy)
        Text("Attunement, spell slots, current resources, game time, actions and saved rolls are retained.", style = MaterialTheme.typography.bodySmall)
        if (s.revision != baseline) Text("Weapon records changed. Close and reopen this review before saving.", color = MaterialTheme.colorScheme.error)
        if (a.message.isNotBlank()) Text(a.message, color = RebuildPalette.Ice)
    }
}
